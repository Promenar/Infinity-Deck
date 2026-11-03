package com.promenar.infinity.data.script

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.script.*
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScriptRuntimeDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val noCapabilities = ScriptCapabilities { _, _ -> throw ScriptFailure("CAPABILITY_DENIED", "此用例未提供能力") }

    @Test fun isolatedProcessCannotReadApplicationPrivateFile() {
        val service = context.packageManager.getServiceInfo(ComponentName(context, ScriptRuntimeService::class.java), 0)
        assertThat(service.flags and ServiceInfo.FLAG_ISOLATED_PROCESS).isNotEqualTo(0)
        val privateFile = File(context.filesDir, "script-private-probe.txt")
        privateFile.writeText("仅主进程可读的合成测试内容")
        try { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server -> assertThat(probe(privateFile, server.localPort).first).isFalse() } }
        finally { privateFile.delete() }
    }

    @Test fun isolatedProcessCannotConnectDirectlyToNetwork() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            assertThat(probe(File(context.filesDir, "missing-script-probe"), server.localPort).second).isFalse()
        }
    }

    private fun probe(file: File, port: Int): Pair<Boolean, Boolean> {
        val connection = bindRaw("com.promenar.infinity.data.script.ScriptIsolationProbeService", "permission_probe")
        try {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.promenar.infinity.script.probe")
                data.writeString(file.path); data.writeInt(port)
                check(connection.binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
                reply.readException()
                val read = reply.readInt() != 0; val network = reply.readInt() != 0
                assertThat(reply.readInt()).isNotEqualTo(Process.myUid())
                return read to network
            } finally { data.recycle(); reply.recycle() }
        } finally { context.unbindService(connection.connection) }
    }

    @Test fun fetchThroughBinderProxySucceeds() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serving = thread(name = "script-http-fixture") {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine()?.takeIf(String::isNotEmpty) != null) { }
                    val body = "{\"answer\":42}".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                    }
                }
            }
            val http = ScriptHttpClient()
            val result = executeOnce(ScriptRequest("var result = fetch('http://127.0.0.1:${server.localPort}/').then(r=>r.json()).then(v=>v.answer)", allowNetwork = true), ScriptCapabilities { op, args ->
                check(op == "fetch"); http.fetch(args, ScriptNetworkBudget())
            })
            serving.join(5000)
            assertThat(result.errorCode).isNull()
            assertThat(result.value).isEqualTo(JsonPrimitive(42))
        }
    }

    @Test fun infiniteLoopTimeoutTerminatesProcess() = runBlocking {
        val session = "loop-probe"
        val observer = observeRuntime(session)
        val processes = CopyOnWriteArrayList<AndroidScriptProcess>()
        val runtime = SupervisedScriptRuntime { AndroidScriptProcess.bind(context, it).also(processes::add) }
        try {
            val result = withTimeout(10000) { runtime.execute(session, ScriptRequest("while(true){}", timeoutSeconds = 1), noCapabilities) }
            assertThat(result.errorCode).isEqualTo("TIMEOUT")
            assertThat(observer.death.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(processes.single().alive).isFalse()
        } finally { processes.forEach { it.terminate() }; context.unbindService(observer.connection) }
    }

    @Test fun memoryLimitTerminatesProcess() = runBlocking {
        val session = "memory-probe"
        val observer = observeRuntime(session)
        val processes = CopyOnWriteArrayList<AndroidScriptProcess>()
        val runtime = SupervisedScriptRuntime { AndroidScriptProcess.bind(context, it).also(processes::add) }
        try {
            val result = withTimeout(15000) { runtime.execute(session, ScriptRequest("new Uint8Array(128*1024*1024)", timeoutSeconds = 10), noCapabilities) }
            assertThat(result.errorCode).isEqualTo("MEMORY_LIMIT")
            assertThat(observer.death.await(5, TimeUnit.SECONDS)).isTrue()
        } finally { processes.forEach { it.terminate() }; context.unbindService(observer.connection) }
    }

    @Test fun processDeathRebuildsOnNextInvocation() = runBlocking {
        val session = "death-probe"
        val processes = CopyOnWriteArrayList<AndroidScriptProcess>()
        val runtime = SupervisedScriptRuntime { AndroidScriptProcess.bind(context, it).also(processes::add) }
        val observer = observeRuntime(session)
        try {
            assertThat(runtime.execute(session, ScriptRequest("21*2"), noCapabilities).value).isEqualTo(JsonPrimitive(42))
            Messenger(observer.binder).send(Message.obtain(null, ScriptWire.TERMINATE))
            assertThat(observer.death.await(5, TimeUnit.SECONDS)).isTrue()
        } finally { context.unbindService(observer.connection) }
        try {
            // 死亡回调在主线程分发，等待旧句柄确认失联。
            withTimeout(5000) { while (processes.first().alive) delay(20) }
            assertThat(runtime.execute(session, ScriptRequest("6*7"), noCapabilities).value).isEqualTo(JsonPrimitive(42))
            assertThat(processes.size).isEqualTo(2)
        } finally { processes.forEach { it.terminate() } }
    }

    @Test fun serviceConnectionMayArriveBeforeBindReturns() = runBlocking {
        val observer = observeRuntime("early-callback-probe")
        val earlyContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun bindIsolatedService(
                intent: Intent, flags: Int, instanceName: String, executor: Executor, connection: ServiceConnection,
            ): Boolean {
                connection.onServiceConnected(ComponentName(context, ScriptRuntimeService::class.java), observer.binder)
                return true
            }
            override fun unbindService(connection: ServiceConnection) = Unit
        }
        try {
            val process = AndroidScriptProcess.bind(earlyContext, "early-callback-probe")
            try {
                assertThat(process.execute(ScriptRequest("21*2"), noCapabilities).value).isEqualTo(JsonPrimitive(42))
            } finally { process.terminate() }
        } finally { context.unbindService(observer.connection) }
    }

    @Test fun rejectedBindingFailsWithoutReturningLiveProcess() = runBlocking {
        val rejectedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun bindIsolatedService(
                intent: Intent, flags: Int, instanceName: String, executor: Executor, connection: ServiceConnection,
            ): Boolean = false
        }
        val failure = runCatching { AndroidScriptProcess.bind(rejectedContext, "rejected-probe") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(ScriptFailure::class.java)
        assertThat((failure as ScriptFailure).code).isEqualTo("PROCESS_DIED")
    }

    @Test fun relativeModuleImportResolvesWithinEntryDirectory() = runBlocking {
        val result = executeOnce(ScriptRequest("import {v} from './lib.mjs'; var result = v + args[0];", "/project/main.mjs", buildJsonArray { add(2) }), ScriptCapabilities { op, args ->
            assertThat(op).isEqualTo("module")
            assertThat(args["path"]?.jsonPrimitive?.content).isEqualTo("/project/lib.mjs")
            JsonPrimitive("export const v = 40")
        })
        assertThat(result.errorCode).isNull()
        assertThat(result.value).isEqualTo(JsonPrimitive(42))
    }

    @Test fun moduleImportCannotEscapeRoot() = runBlocking {
        val result = executeOnce(ScriptRequest("import '../outside.js'", "/project/main.mjs"), noCapabilities)
        assertThat(result.errorCode).isEqualTo("IMPORT_DENIED")
    }

    private suspend fun executeOnce(request: ScriptRequest, capabilities: ScriptCapabilities): ScriptResult {
        val process = AndroidScriptProcess.bind(context, "device-probe")
        return try { withTimeout(15000) { process.execute(request, capabilities) } } finally { process.terminate() }
    }

    private data class RawConnection(val binder: IBinder, val connection: ServiceConnection, val death: CountDownLatch)

    private fun observeRuntime(session: String): RawConnection {
        val name = MessageDigest.getInstance("SHA-256").digest(session.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        return bindRaw(ScriptRuntimeService::class.java.name, "script_$name")
    }

    private fun bindRaw(className: String, instance: String): RawConnection {
        val connected = CountDownLatch(1)
        val death = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                service.linkToDeath({ death.countDown() }, 0)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { death.countDown() }
        }
        check(context.bindIsolatedService(Intent().setComponent(ComponentName(context, className)), Context.BIND_AUTO_CREATE, instance, context.mainExecutor, connection))
        check(connected.await(5, TimeUnit.SECONDS)) { "隔离服务绑定超时" }
        return RawConnection(requireNotNull(binder), connection, death)
    }
}
