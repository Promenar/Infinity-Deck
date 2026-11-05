package com.promenar.infinity.data.git

import com.promenar.infinity.data.exec.BuiltinCommandRequest
import com.promenar.infinity.data.exec.BuiltinCommandRunner
import com.promenar.infinity.data.exec.BuiltinExecEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.promenar.infinity.utils.InfinityLogger
import java.io.File
import java.io.IOException
import java.util.UUID

/** 一次检查子命令的进程请求。 */
data class GitInspectCommand(
    val argv: List<String>,
    val workingDirectory: File,
    val environment: Map<String, String>,
    val timeoutSeconds: Int,
)

data class GitInspectProcessResult(
    /** 超时或被结束时为空。 */
    val exitCode: Int?,
    val timedOut: Boolean,
    /** stderr 尾部，仅用于诊断。 */
    val stderr: String,
)

/** 运行检查子命令；生产实现经 [BuiltinCommandRunner] 在独立进程组中运行，测试可替换。 */
fun interface GitInspectCommandRunner {
    suspend fun run(command: GitInspectCommand): GitInspectProcessResult
}

/** 打包 Python 解释器与其子进程环境。 */
interface GitPythonRuntime {
    val available: Boolean
    val launcher: File
    fun environment(workspace: File, kind: String): Map<String, String>
}

class BuiltinGitPythonRuntime(private val environment: BuiltinExecEnvironment) : GitPythonRuntime {
    override val available: Boolean get() = environment.pythonAvailable
    override val launcher: File get() = environment.pythonLauncher
    override fun environment(workspace: File, kind: String): Map<String, String> = environment.forPython(workspace, kind)
}

class BuiltinGitInspectCommandRunner(private val runner: BuiltinCommandRunner) : GitInspectCommandRunner {
    override suspend fun run(command: GitInspectCommand): GitInspectProcessResult {
        val output = runner.run(
            BuiltinCommandRequest(
                argv = command.argv,
                workingDirectory = command.workingDirectory,
                environment = command.environment,
                stdin = null,
                timeoutSeconds = command.timeoutSeconds,
            ),
        )
        return GitInspectProcessResult(output.exitCode, output.timedOut, output.stderr)
    }
}

/**
 * 用打包 Python 在工作区根运行 `infinity_git --infinity-inspect`。结果不走 stdout（命令运行器只保留输出尾部），
 * 而是写入 [outputDirectory]（App 私有缓存目录）下的临时文件，读取后删除。
 * 不经过 shell_exec 与执行审批；子命令只读，不修改索引、配置与工作区。
 */
class GitInspector(
    private val runtime: GitPythonRuntime,
    private val runner: GitInspectCommandRunner,
    private val outputDirectory: () -> File,
    private val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    private val maxOutputBytes: Long = MAX_OUTPUT_BYTES,
) {
    val pythonAvailable: Boolean get() = runtime.available

    suspend fun status(target: GitWorkspaceTarget, limit: Int = GitInspectLimits.MAX_ENTRIES): GitInspectResult<GitStatus> =
        inspect(target, "status", listOf("--limit", limit.toString(), "--cache-dir", File(outputDirectory(), "status-cache").path), GitInspectJson::parseStatus)

    suspend fun read(target: GitWorkspaceTarget, blobs: List<GitBlobRef>): GitInspectResult<List<GitFileContent>> {
        if (blobs.isEmpty()) return GitInspectResult.Success(emptyList())
        blobs.firstOrNull { !GitPaths.isSafe(it.path) }?.let {
            return GitInspectResult.Failure(GitInspectError.UnsafePath(it.path))
        }
        val args = blobs.flatMap { listOf("--blob", "${it.revision}:${it.path}") }
        return inspect(target, "read", args) { GitInspectJson.parseRead(it, blobs) }
    }

    suspend fun log(target: GitWorkspaceTarget, limit: Int = GitInspectLimits.DEFAULT_COMMITS): GitInspectResult<GitCommitLog> =
        inspect(target, "log", listOf("--limit", limit.toString()), GitInspectJson::parseLog)

    suspend fun branches(target: GitWorkspaceTarget, limit: Int = GitInspectLimits.MAX_ENTRIES): GitInspectResult<GitBranchList> =
        inspect(target, "branches", listOf("--limit", limit.toString()), GitInspectJson::parseBranches)

    suspend fun commit(
        target: GitWorkspaceTarget,
        sha: String,
        limit: Int = GitInspectLimits.MAX_ENTRIES,
    ): GitInspectResult<GitCommitDetail> {
        if (!GitRevisions.isFullSha(sha)) {
            return GitInspectResult.Failure(GitInspectError.InspectFailed("INVALID_ARGUMENT", "提交 sha 无效"))
        }
        return inspect(target, "commit", listOf("--commit", sha, "--limit", limit.toString()), GitInspectJson::parseCommit)
    }

    private suspend fun <T> inspect(
        target: GitWorkspaceTarget,
        operation: String,
        args: List<String>,
        parse: (String) -> GitInspectResult<T>,
    ): GitInspectResult<T> = withContext(Dispatchers.IO) {
        val startedMillis = System.currentTimeMillis()
        val startedNanos = System.nanoTime()
        var statusJson: String? = null
        val directory = outputDirectory()
        val output = File(directory, "inspect-${UUID.randomUUID()}.json")
        val partial = File(output.path + ".tmp")
        try {
            if (!runtime.available) return@withContext GitInspectResult.Failure(GitInspectError.PythonUnavailable)
            // 状态与提交列表会并行检查：mkdirs 因目录已被另一检查建好而返回 false 时以目录实际存在为准。
            if (!directory.mkdirs() && !directory.isDirectory) {
                return@withContext GitInspectResult.Failure(GitInspectError.CommandFailed(null, "无法创建临时目录"))
            }
            val argv = listOf(runtime.launcher.path, "-m", MODULE, FLAG, operation, "--output", output.path, "--repo", target.root.path) + args
            val environment = runtime.environment(target.root, target.kind).toMutableMap()
            if (operation == "status") environment["INFINITY_GIT_START_MS"] = startedMillis.toString()
            // 仅装配点可提供映射，不从仓库配置、模型输出或执行环境继承。
            environment.remove("INFINITY_GIT_LINUX_PATH_MAP")
            target.linuxPathMappings?.let { mappings ->
                environment["INFINITY_GIT_LINUX_PATH_MAP"] = JsonObject(mappings.mapValues { JsonPrimitive(it.value) }).toString()
            }
            val result = runner.run(GitInspectCommand(argv, target.root, environment, timeoutSeconds))
            when {
                result.timedOut -> GitInspectResult.Failure(GitInspectError.TimedOut)
                result.exitCode != 0 -> GitInspectResult.Failure(GitInspectError.CommandFailed(result.exitCode, result.stderr.takeLast(DETAIL_LIMIT)))
                !output.isFile -> GitInspectResult.Failure(GitInspectError.CommandFailed(result.exitCode, "检查子命令没有写出结果"))
                output.length() > maxOutputBytes -> GitInspectResult.Failure(GitInspectError.ParseFailed("输出超过 $maxOutputBytes 字节"))
                else -> {
                    val text = output.readText(Charsets.UTF_8)
                    if (operation == "status") statusJson = text
                    parse(text)
                }
            }
        } catch (failure: IOException) {
            GitInspectResult.Failure(GitInspectError.CommandFailed(null, failure.message ?: failure.javaClass.simpleName))
        } finally {
            if (operation == "status") logStatusTimings(statusJson, (System.nanoTime() - startedNanos) / 1_000_000)
            output.delete()
            partial.delete()
        }
    }

    private fun logStatusTimings(text: String?, elapsedMillis: Long) {
        val root = runCatching { text?.let { Json.parseToJsonElement(it).jsonObject } }.getOrNull()
        fun number(section: String, key: String): String = runCatching {
            val value = root?.get(section)?.jsonObject?.get(key)?.jsonPrimitive?.doubleOrNull
            if (value != null && value.isFinite() && value >= 0) value.toString() else "-1"
        }.getOrDefault("-1")
        val stages = listOf("startup", "imports", "index", "staged", "unstaged", "untracked", "tracking", "lines", "total")
        val counts = listOf("tracked", "staged", "unstaged", "untracked")
        val message = "status " + stages.joinToString(" ") { "$it=${number("timings_ms", it)}" } +
            " elapsed=$elapsedMillis " + counts.joinToString(" ") { "${it}_files=${number("file_counts", it)}" }
        runCatching { InfinityLogger.log("[InfinityGit] $message") }
        runCatching { android.util.Log.i("InfinityGit", message) }
    }

    companion object {
        const val MODULE = "infinity_git"
        const val FLAG = "--infinity-inspect"
        const val DEFAULT_TIMEOUT_SECONDS = 60
        /** 两侧各 512 KiB 文本经 JSON 转义后的余量。 */
        const val MAX_OUTPUT_BYTES = 16L * 1024 * 1024
        private const val DETAIL_LIMIT = 2_000
    }
}
