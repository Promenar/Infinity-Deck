package com.promenar.infinity.data.tailnet

import android.content.SharedPreferences
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal class MemoryPreferences : SharedPreferences {
    val values = ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) values.clear()
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}

internal class MemorySecretStore : SecretStore {
    val values = ConcurrentHashMap<String, ByteArray>()
    override fun put(id: SecretId, value: ByteArray) {
        values[id.value] = value.copyOf()
    }
    override fun get(id: SecretId): ByteArray? = values[id.value]?.copyOf()
    override fun contains(id: SecretId): Boolean = values.containsKey(id.value)
    override fun remove(id: SecretId) {
        values.remove(id.value)
    }
}

/**
 * 内存中的假 libnxtailnet.so：经真实管道收发 JSON 行，按 Go 端协议应答。
 * [handler] 返回 null 时使用默认应答。
 */
internal class FakeTailnetChild(
    private val port: Int = 41000 + NEXT.incrementAndGet(),
    private val handler: (FakeTailnetChild, JsonObject) -> Boolean = { _, _ -> false },
) : TailnetProcessHandle {
    private val stdin = Pipe.open()
    private val stdout = Pipe.open()
    override val input = Channels.newOutputStream(stdin.sink())
    override val output = Channels.newInputStream(stdout.source())
    private val out = Channels.newOutputStream(stdout.sink())
    private val exited = CountDownLatch(1)
    val received = CopyOnWriteArrayList<String>()
    @Volatile var destroyed = false
    @Volatile var stdinClosed = false
    @Volatile var backendState = "NeedsLogin"

    init {
        thread(isDaemon = true, name = "fake-tailnet-child") {
            val reader = Channels.newInputStream(stdin.source()).bufferedReader()
            while (true) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                received += line
                val request = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                if (!handler(this, request)) respond(request)
            }
            stdinClosed = true
            exit()
        }
    }

    fun emit(line: String) = synchronized(out) {
        runCatching {
            out.write((line + "\n").toByteArray())
            out.flush()
        }
    }

    fun state(state: String, extra: String = "") =
        emit("""{"event":"state","state":{"backendState":"$state"$extra}}""")

    fun reply(id: Long, result: String = "{}") = emit("""{"id":$id,"ok":true,"result":$result}""")

    fun fail(id: Long, code: String) = emit("""{"id":$id,"ok":false,"error":"$code","message":"失败"}""")

    private fun respond(request: JsonObject) {
        val id = request["id"]!!.jsonPrimitive.long
        when (request["op"]!!.jsonPrimitive.contentOrNull) {
            TailnetOps.START -> {
                reply(id, """{"proxyPort":$port}""")
                state("NeedsLogin", ""","authUrl":"https://login.example.test/a/first"""")
            }
            TailnetOps.STATUS -> reply(id, statusJson())
            TailnetOps.PEERS -> reply(
                id,
                """{"peers":[{"name":"nas","dnsName":"nas.tail1.ts.net","ips":["100.64.0.5"],"os":"linux","online":true},""" +
                    """{"name":"laptop","dnsName":"laptop.tail1.ts.net","ips":["100.64.0.6"],"os":"macOS","online":false}]}""",
            )
            TailnetOps.LOGIN -> {
                reply(id)
                state("NeedsLogin", ""","authUrl":"https://login.example.test/a/second"""")
            }
            TailnetOps.LOGOUT -> {
                backendState = "NeedsLogin"
                reply(id)
                state("NeedsLogin")
            }
            else -> fail(id, TailnetErrors.UNSUPPORTED)
        }
    }

    fun statusJson(): String = if (backendState == "Running") {
        """{"backendState":"Running","selfName":"phone","selfDnsName":"phone.tail1.ts.net","selfIps":["100.64.0.2"],"online":true,""" +
            """"magicDnsSuffix":"tail1.ts.net","tailnetName":"user@example.com"}"""
    } else {
        """{"backendState":"$backendState"}"""
    }

    fun goOnline() {
        backendState = "Running"
        emit("""{"event":"state","state":${statusJson()}}""")
    }

    /** 模拟子进程崩溃退出。 */
    fun crash() = exit()

    private fun exit() {
        synchronized(out) { runCatching { out.close() } }
        exited.countDown()
    }

    override val isAlive: Boolean get() = exited.count > 0
    override fun waitFor(timeoutMillis: Long): Boolean = exited.await(timeoutMillis, TimeUnit.MILLISECONDS)
    override fun destroyForcibly() {
        destroyed = true
        exit()
    }

    companion object {
        private val NEXT = AtomicInteger()
    }
}
