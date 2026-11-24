package com.promenar.infinity.data.exec

import com.promenar.infinity.data.linux.LinuxMemoryWatch
import com.promenar.infinity.data.linux.LinuxResourceBudget
import com.promenar.infinity.data.linux.LinuxResourceGovernor
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 结构化工具和两种 shim 共用同一份 Git 配置及进程请求。 */
class GitHostBroker internal constructor(private val tools: PackagedHostTools, private val config: GitHostConfig) {
    internal fun request(
        translator: HostToolPathTranslator,
        cwd: String,
        args: List<String>,
        gitEnvironment: Map<String, String>,
        budget: LinuxResourceBudget,
        timeoutSeconds: Int,
        inputEnvironment: Map<String, String> = emptyMap(),
    ): BuiltinCommandRequest {
        val command = translator.translate("git", cwd, args, budget.effectiveThreads)
        val git = tools.availableTools()["git"] ?: throw HostToolException("TOOL_UNAVAILABLE", "宿主 Git 未打包。")
        return BuiltinCommandRequest(
            listOf(git.absolutePath) + command.arguments, command.directory,
            config.environment(translator.roots, gitEnvironment, budget, inputEnvironment) + GitRepositoryGuard(translator).environment(command),
            timeoutSeconds = timeoutSeconds,
            memoryWatch = { LinuxResourceGovernor.watch(budget.jobMemoryBudgetBytes) },
        )
    }
}

internal class HostToolProcess(
    val process: Process,
    private val group: Int,
    private val scratch: File,
    private val signaller: ProcessGroupSignaller,
    private val groupAlive: (Int) -> Boolean,
    private val memory: LinuxMemoryWatch?,
) {
    private var killed = false

    @Synchronized
    fun signal(signal: Int): Boolean {
        if (killed) return true
        val delivered = runCatching { signaller.signal(group, signal) }.isSuccess
        if (signal == 9) {
            killed = delivered
            if (!delivered && process.isAlive) process.destroyForcibly()
        }
        return delivered
    }

    fun memoryExceeded() = memory?.check(group, System.nanoTime() / 1_000_000) == true

    fun release() {
        val delivered = signal(9) || signal(9)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        if (delivered && process.waitFor(1, TimeUnit.SECONDS)) {
            while (groupAlive(group) && System.nanoTime() < deadline) Thread.sleep(10)
            if (!groupAlive(group)) {
                scratch.deleteRecursively()
                return
            }
        }
        throw IOException("无法确认宿主工具进程组已回收。")
    }
}
