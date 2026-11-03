package com.promenar.infinity.domain.script

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.script.*
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.coEvery
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

class ScriptToolRunnerTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "script-runner"
        override val agentId = "agent"
        override val workspaceRootUuid = "root"
        override val workspacePath: String? = null
    }

    @Test fun `实际工具入口读取阻塞也会按请求超时且不进入引擎`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mounts = mockk<ExternalWorkspaceAccess> { coEvery { workspaceMount(any()) } returns null }
        coEvery { mounts.visible(context.sessionId) } returns listOf(ExternalMount("mount", "demo", "saf_tree", "content://synthetic/tree/root", context.sessionId, 1))
        coEvery { mounts.read(context.sessionId, "/mnt/demo/main.js", any()) } answers {
            entered.countDown(); release.await(5, TimeUnit.SECONDS); "result=42".toByteArray()
        }
        val directory = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".infinity-workspace-script-runner").toFile()
        try {
            val runner = runner(mounts, directory, ScriptRuntime { _, _, _ -> error("入口超时不应启动引擎") })
            val pending = async { runner.execute(ScriptRequest("", "/mnt/demo/main.js", timeoutSeconds = 1), context) }
            withContext(Dispatchers.IO) { check(entered.await(2, TimeUnit.SECONDS)) }
            assertThat(withTimeout(2000) { pending.await() }.errorCode).isEqualTo("TIMEOUT")
        } finally { release.countDown(); directory.deleteRecursively() }
    }

    @Test fun `未声明联网时真实主进程能力代理拒绝fetch`() = runBlocking {
        val mounts = mockk<ExternalWorkspaceAccess> { coEvery { workspaceMount(any()) } returns null }
        coEvery { mounts.visible(context.sessionId) } returns emptyList()
        val directory = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".infinity-workspace-script-runner").toFile()
        try {
            val runtime = ScriptRuntime { _, _, capabilities ->
                try { capabilities.invoke("fetch", buildJsonObject { put("url", "https://example.test") }); error("应拒绝联网") }
                catch (failure: ScriptFailure) { ScriptResult(errorCode = failure.code, error = failure.message) }
            }
            assertThat(runner(mounts, directory, runtime).execute(ScriptRequest("1"), context).errorCode).isEqualTo("NETWORK_DENIED")
        } finally { directory.deleteRecursively() }
    }

    @Test fun `运行中输出转给聊天界面，界面停止时取消执行并返回 STOPPED`() = runBlocking {
        val mounts = mockk<ExternalWorkspaceAccess> { coEvery { workspaceMount(any()) } returns null }
        coEvery { mounts.visible(context.sessionId) } returns emptyList()
        val directory = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".infinity-workspace-script-runner").toFile()
        val store = com.promenar.infinity.data.exec.ToolLiveOutputStore()
        val sink = store.open("step")
        val liveContext = object : SkillExecutionContext by context {
            override val liveOutput = sink
        }
        val started = CompletableDeferred<Unit>()
        var runtimeCancelled = false
        try {
            val runtime = ScriptRuntime { _, _, capabilities ->
                capabilities.output(1, "tick\n")
                capabilities.output(2, "warn\n")
                started.complete(Unit)
                try { awaitCancellation() } finally { runtimeCancelled = true }
            }
            val pending = async { runner(mounts, directory, runtime).execute(ScriptRequest("1"), liveContext) }
            withTimeout(5_000) { started.await() }
            assertThat(store.snapshot("step")?.text).isEqualTo("tick\nwarn\n")
            assertThat(store.snapshot("step")?.stoppable).isTrue()

            store.requestStop("step")
            val result = withTimeout(5_000) { pending.await() }
            assertThat(result.errorCode).isEqualTo("STOPPED")
            assertThat(result.error).contains("不要自动重试")
            assertThat(runtimeCancelled).isTrue()
        } finally { directory.deleteRecursively() }
    }

    private fun runner(mounts: ExternalWorkspaceAccess, directory: java.io.File, runtime: ScriptRuntime) = AndroidScriptToolRunner(
        runtime, mockk<IWorkspaceRepository>(), mounts, mockk<IFileOperationRepository>(), FileIndexEventSink.None,
        ScriptFileApprovalBroker(),
    )
}
