package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.data.exec.PackagedPython
import com.promenar.infinity.data.linux.LinuxRootfsFiles
import com.promenar.infinity.domain.linux.LinuxBind
import com.promenar.infinity.domain.linux.LinuxLaunchSpec
import com.promenar.infinity.domain.linux.LinuxProcessLaunch
import com.promenar.infinity.domain.linux.LinuxRuntime
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.domain.model.SessionRunMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** ComfyUI 提交器执行后端类型。 */
enum class ComfySubmitterRuntime {
    BUILTIN_PYTHON,
    LINUX,
}

/** ComfyUI 工作流执行模式：正式执行、仅导出图审阅、仅连接预检。 */
enum class ComfyWorkflowRunMode {
    EXECUTE,
    EMIT_WORKFLOW,
    PREFLIGHT_ONLY,
}

/** 受控内置 Python 3.14 提交器启动参数（真机使用 PackagedPython，JVM 单元测试可注入宿主 python3）。 */
data class SubmitterPythonLaunch(
    val commandPrefix: List<String>,
    val environment: Map<String, String>,
    val compiledCompileFlags: List<String> = listOf("-m", "py_compile"),
    /** 可选随应用打包的工具目录（如 nativeLibraryDir 内的 libnxffmpeg.so / libnxffprobe.so）。 */
    val bundledToolsDir: File? = null,
    /** 当前内置环境声明的平台标识集合。 */
    val platforms: Set<String> = setOf("android", "posix"),
) {
    companion object {
        fun packaged(python: PackagedPython): SubmitterPythonLaunch? {
            if (!python.isAvailable) return null
            return SubmitterPythonLaunch(
                commandPrefix = listOf(python.launcher.path),
                environment = python.environment() + mapOf(
                    "PATH" to "/system/bin",
                    "LANG" to "C.UTF-8",
                    "PYTHONUTF8" to "1",
                ),
                bundledToolsDir = python.launcher.parentFile,
                platforms = setOf("android", "posix"),
            )
        }
    }
}

/** Linux 虚拟环境提交器桥接契约；真机由 ProcessLinuxRuntime 提供，单测可注入假实现。 */
interface ComfyLinuxSubmitterBridge {
    fun isReady(): Boolean
    suspend fun shouldUseLinux(sessionId: String?): Boolean
    suspend fun prepareLaunch(
        runDir: File,
        guestRunRoot: String,
        stagedRoot: File?,
        guestStagedRoot: String?,
        argv: List<String>,
        workingDirectoryGuest: String,
        environment: Map<String, String>,
    ): LinuxProcessLaunch

    suspend fun checkTools(tools: List<String>): Map<String, Boolean>
    fun platforms(): Set<String> = setOf("linux", "android", "posix")
}

class DefaultComfyLinuxSubmitterBridge(
    private val linuxRuntime: LinuxRuntime,
    private val sessionRunModeProvider: suspend (String) -> SessionRunMode?,
) : ComfyLinuxSubmitterBridge {
    override fun isReady(): Boolean = linuxRuntime.state.value is LinuxRuntimeState.Ready

    override suspend fun shouldUseLinux(sessionId: String?): Boolean {
        if (sessionId.isNullOrBlank() || !isReady()) return false
        return sessionRunModeProvider(sessionId) == SessionRunMode.LINUX
    }

    override suspend fun prepareLaunch(
        runDir: File,
        guestRunRoot: String,
        stagedRoot: File?,
        guestStagedRoot: String?,
        argv: List<String>,
        workingDirectoryGuest: String,
        environment: Map<String, String>,
    ): LinuxProcessLaunch {
        val mounts = buildList {
            add(LinuxBind(runDir, guestRunRoot, readOnly = false))
            if (stagedRoot != null && !guestStagedRoot.isNullOrBlank() && stagedRoot.exists()) {
                add(LinuxBind(stagedRoot, guestStagedRoot, readOnly = false))
            }
        }
        return linuxRuntime.prepareLaunch(
            LinuxLaunchSpec(
                argv = argv,
                mounts = mounts,
                workingDirectory = workingDirectoryGuest,
                environment = environment,
            ),
        )
    }

    override suspend fun checkTools(tools: List<String>): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val rootfs = linuxRuntime.paths.rootfs
        val searchDirs = listOf("usr/local/sbin", "usr/local/bin", "usr/sbin", "usr/bin", "sbin", "bin")
        tools.associateWith { tool ->
            searchDirs.any { dir -> LinuxRootfsFiles.isGuestFile(rootfs, File(rootfs, "$dir/$tool")) }
        }
    }
}

/** 宿主机与 Linux guest 之间的确定性路径换算层。 */
internal class SubmitterPathTranslator(
    private val mappings: List<Pair<File, String>>,
) {
    private val normalizedMappings = mappings.map { (hostDir, guestPrefix) ->
        hostDir.canonicalFile to guestPrefix.trimEnd('/')
    }.sortedByDescending { it.first.path.length }

    fun toGuest(hostFile: File): String {
        if (normalizedMappings.isEmpty()) return hostFile.path
        val canon = runCatching { hostFile.canonicalFile }.getOrElse { hostFile.absoluteFile }
        for ((hostRoot, guestRoot) in normalizedMappings) {
            if (canon == hostRoot) return guestRoot
            val prefix = hostRoot.path.trimEnd(File.separatorChar) + File.separatorChar
            if (canon.path.startsWith(prefix)) {
                val rel = canon.path.removePrefix(prefix).replace(File.separatorChar, '/')
                return "$guestRoot/$rel"
            }
        }
        return hostFile.path
    }

    fun toHost(rawPath: String, fallbackBaseDir: File): File {
        val trimmed = rawPath.trim()
        for ((hostRoot, guestRoot) in normalizedMappings) {
            if (trimmed == guestRoot) return hostRoot
            val prefix = "$guestRoot/"
            if (trimmed.startsWith(prefix)) {
                val rel = trimmed.removePrefix(prefix)
                return File(hostRoot, rel)
            }
        }
        val direct = File(trimmed)
        return if (direct.isAbsolute) direct else File(fallbackBaseDir, trimmed)
    }

    companion object {
        val IDENTITY = SubmitterPathTranslator(emptyList())
    }
}

data class SubmitterStagedMedia(
    val localFile: File,
    val originalPath: String,
)

data class SubmitterRunRequest(
    val submitter: ComfySubmitterScript,
    val workflow: ComfyWorkflow?,
    val proxyBaseUrl: String?,
    val sessionId: String? = null,
    val runMode: ComfyWorkflowRunMode = ComfyWorkflowRunMode.EXECUTE,
    val resolvedInputs: Map<String, JsonElement> = emptyMap(),
    val stagedFilesByPath: Map<String, File> = emptyMap(),
    val stagedRootDir: File? = null,
    val hostMetadata: Map<String, String> = emptyMap(),
    val stage: String = "single",
    val branchId: String = "main",
    val branchPolicy: StatefulBranchPolicy = StatefulBranchPolicy.ISOLATED_DIR,
    val parentNodeId: String? = null,
    val segmentIndex: Int = 1,
    val readLatentDir: String? = null,
    val writeLatentDir: String? = null,
    val inheritedState: JsonObject = JsonObject(emptyMap()),
    val previousWorkflowFile: File? = null,
    val previousSidecars: Map<String, String> = emptyMap(),
    val dryRun: Boolean = false,
    val timeoutSeconds: Int = 900,
    val onProgress: suspend (percent: Float?, message: String) -> Unit = { _, _ -> },
)

data class SubmitterOutputArtifact(
    val file: File,
    val kind: MediaKind,
    val mimeType: String,
)

data class SubmitterExecutionResult(
    val success: Boolean,
    val exitCode: Int,
    val runtime: ComfySubmitterRuntime = ComfySubmitterRuntime.BUILTIN_PYTHON,
    val promptId: String? = null,
    val outputs: List<SubmitterOutputArtifact> = emptyList(),
    val continuationState: JsonObject = JsonObject(emptyMap()),
    val reportedLatentDir: String? = null,
    val reportedClipIndex: Int? = null,
    val serverLatentSlotKey: String? = null,
    val emittedWorkflowFile: File? = null,
    val emittedWorkflowJson: JsonObject? = null,
    val preflightReport: JsonObject? = null,
    val warnings: List<String> = emptyList(),
    val summary: String = "",
    val stdoutTail: String = "",
    val stderrTail: String = "",
    val latencyMs: Long = 0L,
)

/**
 * 在受控内置 Python 3.14 或已激活 Linux 虚拟环境隔离运行目录中执行 ComfyUI 提交器脚本。
 * 特性：
 * - 会话激活 Linux 模式且环境就绪时自动切换至 Linux 虚拟环境执行（与内置 Python 执行器一致）；
 * - 仅接收本机 `127.0.0.1` 回环代理地址（`COMFY_HOST_URL` / `--host`），绝对不传递任何主机密钥或令牌；
 * - 全能力均由工作流元数据配置驱动（参数模板、布尔假值标志、文件/目录暂存、主机版本插值、配套 sidecar、多行 JSON/正则提取与依赖预检）。
 */
class ComfySubmitterRunner(
    private val submitterFiles: ComfySubmitterFileStore,
    private val scratchRoot: File,
    private val launchProvider: () -> SubmitterPythonLaunch?,
    private val linuxBridge: ComfyLinuxSubmitterBridge? = null,
) {
    val isPythonAvailable: Boolean
        get() = launchProvider() != null || linuxBridge?.isReady() == true

    suspend fun resolveRuntime(sessionId: String?): ComfySubmitterRuntime =
        if (linuxBridge?.shouldUseLinux(sessionId) == true) {
            ComfySubmitterRuntime.LINUX
        } else {
            ComfySubmitterRuntime.BUILTIN_PYTHON
        }

    fun availableRuntimes(): List<ComfySubmitterRuntime> = buildList {
        if (launchProvider() != null) add(ComfySubmitterRuntime.BUILTIN_PYTHON)
        if (linuxBridge?.isReady() == true) add(ComfySubmitterRuntime.LINUX)
    }

    suspend fun checkTools(runtime: ComfySubmitterRuntime, tools: List<String>): Map<String, Boolean> {
        if (tools.isEmpty()) return emptyMap()
        return when (runtime) {
            ComfySubmitterRuntime.LINUX -> {
                linuxBridge?.takeIf { it.isReady() }?.checkTools(tools)
                    ?: tools.associateWith { false }
            }
            ComfySubmitterRuntime.BUILTIN_PYTHON -> {
                val launch = launchProvider() ?: return tools.associateWith { false }
                checkBuiltinTools(launch, tools)
            }
        }
    }

    suspend fun dryRunScript(
        submitter: ComfySubmitterScript,
        workflow: ComfyWorkflow? = null,
        sessionId: String? = null,
    ): SubmitterExecutionResult = withContext(Dispatchers.IO) {
        val runtime = resolveRuntime(sessionId)
        val builtinLaunch = if (runtime == ComfySubmitterRuntime.BUILTIN_PYTHON) {
            launchProvider() ?: throw ComfyFailure("当前设备架构未打包内置 Python 3.14，且未就绪 Linux 运行环境，无法运行提交器脚本")
        } else null
        val startedMs = System.currentTimeMillis()
        val runDir = File(scratchRoot, "dry_${UUID.randomUUID().toString().take(12)}")
        val scriptDir = File(runDir, "script")
        val homeDir = File(runDir, "home")
        val tmpDir = File(runDir, "tmp")
        val outputDir = File(runDir, "outputs")
        val binDir = File(runDir, "bin")
        try {
            listOf(scriptDir, homeDir, tmpDir, outputDir, binDir).forEach { it.mkdirs() }
            check(submitterFiles.stageIntoDirectory(submitter.hostId, submitter.scriptId, scriptDir)) {
                "提交器脚本文件不存在，请重新导入"
            }
            val entryFile = File(scriptDir, submitter.fileName).canonicalFile
            require(entryFile.isFile) { "提交器入口文件缺失：${submitter.fileName}" }
            val translator = if (runtime == ComfySubmitterRuntime.LINUX) {
                SubmitterPathTranslator(listOf(runDir to GUEST_RUN_ROOT))
            } else {
                SubmitterPathTranslator.IDENTITY
            }
            if (runtime == ComfySubmitterRuntime.BUILTIN_PYTHON && builtinLaunch != null) {
                prepareBundledToolShims(builtinLaunch.bundledToolsDir, binDir)
            }

            suspend fun runProbe(pythonArgs: List<String>, timeoutSeconds: Long): SimpleCmdOut {
                return if (runtime == ComfySubmitterRuntime.LINUX) {
                    val bridge = requireNotNull(linuxBridge)
                    val guestEnv = buildGuestEnvironment(
                        translator = translator,
                        scriptDir = scriptDir,
                        entryParent = entryFile.parentFile ?: scriptDir,
                        tmpDir = tmpDir,
                        proxyBaseUrl = null,
                        payloadFile = null,
                        outputDir = outputDir,
                        resultFile = File(runDir, "result.json"),
                    )
                    val prepared = bridge.prepareLaunch(
                        runDir = runDir,
                        guestRunRoot = GUEST_RUN_ROOT,
                        stagedRoot = null,
                        guestStagedRoot = null,
                        argv = listOf("python3") + pythonArgs,
                        workingDirectoryGuest = translator.toGuest(scriptDir),
                        environment = guestEnv,
                    )
                    runSimpleCommand(
                        command = listOf(prepared.executable) + prepared.arguments,
                        env = prepared.environment,
                        workDir = prepared.hostWorkingDirectory,
                        timeoutSeconds = timeoutSeconds,
                    )
                } else {
                    val launch = requireNotNull(builtinLaunch)
                    val baseEnv = buildBuiltinEnvironment(
                        launch = launch,
                        binDir = binDir,
                        scriptDir = scriptDir,
                        entryParent = entryFile.parentFile ?: scriptDir,
                        homeDir = homeDir,
                        tmpDir = tmpDir,
                        proxyBaseUrl = null,
                        payloadFile = null,
                        outputDir = outputDir,
                        resultFile = File(runDir, "result.json"),
                    )
                    runSimpleCommand(
                        command = launch.commandPrefix + pythonArgs,
                        env = baseEnv,
                        workDir = scriptDir,
                        timeoutSeconds = timeoutSeconds,
                    )
                }
            }

            val entryArg = translator.toGuest(entryFile)
            val compileOut = runProbe(listOf("-m", "py_compile", entryArg), timeoutSeconds = 20)
            if (compileOut.exitCode != 0) {
                val latency = System.currentTimeMillis() - startedMs
                return@withContext SubmitterExecutionResult(
                    success = false,
                    exitCode = compileOut.exitCode,
                    runtime = runtime,
                    summary = "语法检查失败（${runtime.name}）：${compileOut.combinedError()}",
                    stdoutTail = compileOut.stdout,
                    stderrTail = compileOut.stderr,
                    latencyMs = latency,
                )
            }

            val secondArgs = when (submitter.protocol) {
                SubmitterInvocationProtocol.PAYLOAD_JSON -> {
                    val payloadFile = File(runDir, "payload.json")
                    payloadFile.writeText(
                        buildJsonObject {
                            put("dry_run", true)
                            put("stage", "dry_run")
                            put("output_dir", translator.toGuest(outputDir))
                            workflow?.let { put("workflow_id", it.workflowId) }
                        }.toString(),
                        Charsets.UTF_8,
                    )
                    listOf(entryArg, "--payload", translator.toGuest(payloadFile))
                }
                SubmitterInvocationProtocol.CLI_ARGS -> listOf(entryArg, "--help")
            }
            val probeOut = runProbe(secondArgs, timeoutSeconds = 30)
            val latency = System.currentTimeMillis() - startedMs
            return@withContext if (probeOut.exitCode == 0) {
                SubmitterExecutionResult(
                    success = true,
                    exitCode = 0,
                    runtime = runtime,
                    summary = "语法、导入与入口协议自检通过（${runtime.name}，${latency} ms）",
                    stdoutTail = probeOut.stdout,
                    stderrTail = probeOut.stderr,
                    latencyMs = latency,
                )
            } else {
                SubmitterExecutionResult(
                    success = false,
                    exitCode = probeOut.exitCode,
                    runtime = runtime,
                    summary = "入口自检失败（${runtime.name}，退出码 ${probeOut.exitCode}）：${probeOut.combinedError()}",
                    stdoutTail = probeOut.stdout,
                    stderrTail = probeOut.stderr,
                    latencyMs = latency,
                )
            }
        } finally {
            runDir.deleteRecursively()
        }
    }

    suspend fun execute(request: SubmitterRunRequest): SubmitterExecutionResult = withContext(Dispatchers.IO) {
        val runtime = resolveRuntime(request.sessionId)
        val builtinLaunch = if (runtime == ComfySubmitterRuntime.BUILTIN_PYTHON) {
            launchProvider() ?: throw ComfyFailure("当前设备架构未打包内置 Python 3.14，且未就绪 Linux 运行环境，无法运行 ComfyUI 提交器")
        } else null

        // 依赖与平台前置门禁（失败关闭，绝不在缺少依赖时盲目启动脚本）
        request.workflow?.let { wf ->
            validatePlatformAndTools(wf, runtime, builtinLaunch)
        }

        val startedMs = System.currentTimeMillis()
        val runDir = File(scratchRoot, "run_${UUID.randomUUID().toString().take(12)}")
        val scriptDir = File(runDir, "script")
        val homeDir = File(runDir, "home")
        val tmpDir = File(runDir, "tmp")
        val inputsDir = File(runDir, "inputs")
        val outputDir = File(runDir, "outputs")
        val binDir = File(runDir, "bin")
        val payloadFile = File(runDir, "payload.json")
        val resultFile = File(runDir, "result.json")
        val receiptFile = File(runDir, "receipt.json")
        val snapshotEmitFile = File(runDir, "emitted_workflow.json")
        listOf(scriptDir, homeDir, tmpDir, inputsDir, outputDir, binDir).forEach { it.mkdirs() }

        check(submitterFiles.stageIntoDirectory(request.submitter.hostId, request.submitter.scriptId, scriptDir)) {
            "提交器脚本文件不存在，请重新导入"
        }
        val entryFile = File(scriptDir, request.submitter.fileName).canonicalFile
        require(entryFile.isFile) { "提交器入口文件缺失：${request.submitter.fileName}" }

        // 若提供了上一段工作流图及配套 sidecar，放入 runDir 内以便双运行时统一可见。
        val runPrevWorkflowFile = request.previousWorkflowFile?.takeIf { it.isFile }?.let { src ->
            val dest = File(inputsDir, "previous_workflow.json")
            src.copyTo(dest, overwrite = true)
            request.previousSidecars.forEach { (suffix, content) ->
                val sidecarFile = File(inputsDir, "previous_workflow$suffix")
                sidecarFile.writeText(content, Charsets.UTF_8)
            }
            dest
        }

        val effectiveStagedRoot = request.stagedRootDir?.takeIf { it.exists() }
        val translator = if (runtime == ComfySubmitterRuntime.LINUX) {
            val mappings = buildList {
                add(runDir to GUEST_RUN_ROOT)
                if (effectiveStagedRoot != null) add(effectiveStagedRoot to GUEST_STAGED_ROOT)
            }
            SubmitterPathTranslator(mappings)
        } else {
            SubmitterPathTranslator.IDENTITY
        }

        val entryArg = translator.toGuest(entryFile)
        val pythonScriptArgs = when (request.submitter.protocol) {
            SubmitterInvocationProtocol.PAYLOAD_JSON -> {
                val payload = buildJsonObject {
                    put("dry_run", request.dryRun || request.runMode != ComfyWorkflowRunMode.EXECUTE)
                    put("run_mode", request.runMode.name.lowercase())
                    put("stage", request.stage)
                    put("branch_id", request.branchId)
                    put("branch_policy", request.branchPolicy.name)
                    put("segment_index", request.segmentIndex)
                    request.parentNodeId?.let { put("parent_node_id", it) }
                    request.readLatentDir?.let { put("read_latent_dir", it) }
                    request.writeLatentDir?.let {
                        put("write_latent_dir", it)
                        put("latent_dir", it)
                    }
                    put("output_dir", translator.toGuest(outputDir))
                    put("result_path", translator.toGuest(resultFile))
                    if (request.runMode == ComfyWorkflowRunMode.EMIT_WORKFLOW) {
                        put("emit_workflow_path", translator.toGuest(snapshotEmitFile))
                    }
                    runPrevWorkflowFile?.let { put("previous_workflow", translator.toGuest(it)) }
                    request.proxyBaseUrl?.let { put("host_proxy_url", it) }
                    request.workflow?.let { put("workflow_id", it.workflowId) }
                    if (request.hostMetadata.isNotEmpty()) {
                        put("host", JsonObject(request.hostMetadata.mapValues { JsonPrimitive(it.value) }))
                    }
                    put(
                        "inputs",
                        JsonObject(
                            rewritePayloadInputs(
                                inputs = request.resolvedInputs,
                                stagedFilesByPath = request.stagedFilesByPath,
                                hostMetadata = request.hostMetadata,
                                translator = translator,
                            ),
                        ),
                    )
                    if (request.inheritedState.isNotEmpty()) put("inherited_state", request.inheritedState)
                }
                payloadFile.writeText(payload.toString(), Charsets.UTF_8)
                listOf(entryArg, "--payload", translator.toGuest(payloadFile))
            }
            SubmitterInvocationProtocol.CLI_ARGS -> {
                val wf = requireNotNull(request.workflow) { "CLI_ARGS 提交器需要关联 ComfyWorkflow 配置" }
                buildCliScriptArgs(
                    entryArg = entryArg,
                    workflow = wf,
                    request = request,
                    inputsDir = inputsDir,
                    outputDir = outputDir,
                    receiptFile = receiptFile,
                    snapshotEmitFile = snapshotEmitFile,
                    previousWorkflowFile = runPrevWorkflowFile,
                    translator = translator,
                )
            }
        }

        val processBuilder = if (runtime == ComfySubmitterRuntime.LINUX) {
            val bridge = requireNotNull(linuxBridge)
            val guestEnv = buildGuestEnvironment(
                translator = translator,
                scriptDir = scriptDir,
                entryParent = entryFile.parentFile ?: scriptDir,
                tmpDir = tmpDir,
                proxyBaseUrl = request.proxyBaseUrl,
                payloadFile = payloadFile.takeIf { it.isFile },
                outputDir = outputDir,
                resultFile = resultFile,
            )
            val prepared = bridge.prepareLaunch(
                runDir = runDir,
                guestRunRoot = GUEST_RUN_ROOT,
                stagedRoot = effectiveStagedRoot,
                guestStagedRoot = GUEST_STAGED_ROOT.takeIf { effectiveStagedRoot != null },
                argv = listOf("python3") + pythonScriptArgs,
                workingDirectoryGuest = translator.toGuest(scriptDir),
                environment = guestEnv,
            )
            ProcessBuilder(listOf(prepared.executable) + prepared.arguments).apply {
                directory(prepared.hostWorkingDirectory)
                environment().clear()
                environment().putAll(prepared.environment)
            }
        } else {
            val launch = requireNotNull(builtinLaunch)
            prepareBundledToolShims(launch.bundledToolsDir, binDir)
            val env = buildBuiltinEnvironment(
                launch = launch,
                binDir = binDir,
                scriptDir = scriptDir,
                entryParent = entryFile.parentFile ?: scriptDir,
                homeDir = homeDir,
                tmpDir = tmpDir,
                proxyBaseUrl = request.proxyBaseUrl,
                payloadFile = payloadFile.takeIf { it.isFile },
                outputDir = outputDir,
                resultFile = resultFile,
            )
            ProcessBuilder(launch.commandPrefix + pythonScriptArgs).apply {
                directory(scriptDir)
                environment().clear()
                environment().putAll(env)
            }
        }

        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            runDir.deleteRecursively()
            throw ComfyFailure("无法启动 ComfyUI 提交器进程（${runtime.name}）：${e.message?.take(200)}")
        }

        val stdoutBuf = HeadTailBuffer(headLimit = 8_192, tailLimit = 24_576)
        val stderrBuf = HeadTailBuffer(headLimit = 8_192, tailLimit = 24_576)
        val timedOut = AtomicBoolean(false)
        val stopOnCancel = currentCoroutineContext()[Job]?.invokeOnCompletion { process.destroyForcibly() }
        val stderrDrain = thread(name = "comfy-py-stderr", isDaemon = true) {
            runCatching {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    stderrBuf.appendLine(line)
                }
            }
        }
        val stdoutLines = mutableListOf<String>()
        var exitCode = -1
        var keepRunDirForOutputs = false
        try {
            coroutineScope {
                val watchdog = launch {
                    delay(request.timeoutSeconds.coerceAtLeast(10) * 1000L)
                    timedOut.set(true)
                    process.destroyForcibly()
                }
                try {
                    val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
                    while (true) {
                        val line = reader.readLine() ?: break
                        stdoutBuf.appendLine(line)
                        if (stdoutLines.size >= 800) stdoutLines.removeAt(0)
                        stdoutLines += line
                        parseProgressLine(line)?.let { (pct, msg) ->
                            request.onProgress(pct, msg)
                        }
                    }
                } finally {
                    watchdog.cancel()
                }
            }
            if (timedOut.get()) {
                process.destroyForcibly()
                throw ComfyFailure("ComfyUI 提交器执行超时（${request.timeoutSeconds} 秒）", retryable = true)
            }
            process.waitFor(5, TimeUnit.SECONDS)
            if (process.isAlive) process.destroyForcibly()
            exitCode = runCatching { process.exitValue() }.getOrElse { -1 }
            stderrDrain.join(500)

            val latency = System.currentTimeMillis() - startedMs
            val stdoutFull = stdoutLines.joinToString("\n")
            val resultJson = readResultJson(resultFile, receiptFile, stdoutLines)
            val promptId = extractPromptId(resultJson, stdoutFull, request.workflow?.promptIdPattern)

            if (exitCode != 0) {
                val errDetail = ComfyErrorDiagnostics.summarizeSubprocessFailure(
                    exitCode = exitCode,
                    stderrHead = stderrBuf.headText(),
                    stderrTail = stderrBuf.tailText(),
                    stdoutHead = stdoutBuf.headText(),
                    stdoutTail = stdoutBuf.tailText(),
                )
                throw ComfyFailure("ComfyUI 提交器执行失败（${runtime.name}，退出码 $exitCode）：$errDetail")
            }

            // 审阅导出模式：直接读取导出图 JSON
            if (request.runMode == ComfyWorkflowRunMode.EMIT_WORKFLOW) {
                val emittedObj = snapshotEmitFile.takeIf { it.isFile }?.let { f ->
                    runCatching { Json.parseToJsonElement(f.readText(Charsets.UTF_8)) as? JsonObject }.getOrNull()
                } ?: (resultJson?.get("workflow") as? JsonObject) ?: resultJson
                keepRunDirForOutputs = snapshotEmitFile.isFile
                return@withContext SubmitterExecutionResult(
                    success = true,
                    exitCode = exitCode,
                    runtime = runtime,
                    emittedWorkflowFile = snapshotEmitFile.takeIf { it.isFile },
                    emittedWorkflowJson = emittedObj,
                    continuationState = if (keepRunDirForOutputs) {
                        JsonObject(mapOf("__run_dir_cleanup" to JsonPrimitive(runDir.path)))
                    } else JsonObject(emptyMap()),
                    summary = "工作流导出与结构校验通过（${runtime.name}，未提交 GPU）",
                    stdoutTail = stdoutBuf.tailText().takeLast(4000),
                    stderrTail = stderrBuf.tailText().takeLast(2000),
                    latencyMs = latency,
                )
            }

            // 预检模式：直接返回脚本预检 JSON 或摘要
            if (request.runMode == ComfyWorkflowRunMode.PREFLIGHT_ONLY) {
                return@withContext SubmitterExecutionResult(
                    success = true,
                    exitCode = exitCode,
                    runtime = runtime,
                    preflightReport = resultJson,
                    summary = "提交器远端预检通过（${runtime.name}，未提交 GPU）",
                    stdoutTail = stdoutBuf.tailText().takeLast(4000),
                    stderrTail = stderrBuf.tailText().takeLast(2000),
                    latencyMs = latency,
                )
            }

            val outputs = collectOutputs(resultJson, outputDir, translator)
            val continuationBuild = buildContinuationState(
                resultJson = resultJson,
                receiptFile = receiptFile,
                snapshotEmitFile = snapshotEmitFile,
                runDir = runDir,
                request = request,
                translator = translator,
            )
            val slotKey = (resultJson?.get("overwrites_slot_key") as? JsonPrimitive)?.contentOrNull
                ?: deriveDefaultSlotKey(continuationBuild.state, request)

            keepRunDirForOutputs = true
            return@withContext SubmitterExecutionResult(
                success = true,
                exitCode = exitCode,
                runtime = runtime,
                promptId = promptId,
                outputs = outputs,
                continuationState = JsonObject(
                    continuationBuild.state + ("__run_dir_cleanup" to JsonPrimitive(runDir.path)),
                ),
                reportedLatentDir = continuationBuild.reportedLatentDir,
                reportedClipIndex = continuationBuild.reportedClipIndex,
                serverLatentSlotKey = slotKey,
                warnings = continuationBuild.warnings,
                summary = (resultJson?.get("summary") as? JsonPrimitive)?.contentOrNull
                    ?: "提交器执行成功（${runtime.name}），产出 ${outputs.size} 个产物文件",
                stdoutTail = stdoutBuf.tailText().takeLast(4000),
                stderrTail = stderrBuf.tailText().takeLast(2000),
                latencyMs = latency,
            )
        } finally {
            stopOnCancel?.dispose()
            if (process.isAlive) process.destroyForcibly()
            stderrDrain.join(200)
            if (!keepRunDirForOutputs) {
                runDir.deleteRecursively()
            }
        }
    }

    private suspend fun validatePlatformAndTools(
        workflow: ComfyWorkflow,
        runtime: ComfySubmitterRuntime,
        builtinLaunch: SubmitterPythonLaunch?,
    ) {
        if (workflow.requiredPlatforms.isNotEmpty()) {
            val activePlatforms = when (runtime) {
                ComfySubmitterRuntime.LINUX -> linuxBridge?.platforms() ?: setOf("linux", "android", "posix")
                ComfySubmitterRuntime.BUILTIN_PYTHON -> builtinLaunch?.platforms ?: setOf("android", "posix")
            }
            val matched = workflow.requiredPlatforms.any { req -> req.lowercase() in activePlatforms }
            if (!matched) {
                throw ComfyFailure(
                    "工作流 ${workflow.workflowId} 要求平台 ${workflow.requiredPlatforms.joinToString("/")}，" +
                        "与当前执行环境（${runtime.name}: ${activePlatforms.joinToString("/")}）不兼容，已提前拒绝执行。",
                )
            }
        }
        if (workflow.requiredTools.isNotEmpty()) {
            val availability = checkTools(runtime, workflow.requiredTools)
            val missing = workflow.requiredTools.filterNot { availability[it] == true }
            if (missing.isNotEmpty()) {
                val hint = when (runtime) {
                    ComfySubmitterRuntime.LINUX ->
                        "请在已激活的 Linux 虚拟环境中安装对应软件包（例如 ${missing.joinToString("、")}）后重试。"
                    ComfySubmitterRuntime.BUILTIN_PYTHON ->
                        "内置 Python 环境未检测到这些外部工具；请切换到已安装对应工具的 Linux 会话模式，或调整工作流参数跳过依赖。"
                }
                throw ComfyFailure(
                    "工作流 ${workflow.workflowId} 缺少所需外部工具（${runtime.name}）：${missing.joinToString("、")}。$hint",
                )
            }
        }
    }

    private fun checkBuiltinTools(launch: SubmitterPythonLaunch, tools: List<String>): Map<String, Boolean> {
        val pathDirs = launch.environment["PATH"].orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map(::File)
        return tools.associateWith { tool ->
            val bundledHit = launch.bundledToolsDir?.let { dir ->
                File(dir, "libnx$tool.so").isFile || File(dir, tool).let { it.isFile && it.canExecute() }
            } == true
            bundledHit || pathDirs.any { dir -> File(dir, tool).let { it.isFile && it.canExecute() } }
        }
    }

    private fun prepareBundledToolShims(bundledDir: File?, binDir: File) {
        if (bundledDir == null || !bundledDir.isDirectory) return
        val knownShims = listOf("ffmpeg" to "libnxffmpeg.so", "ffprobe" to "libnxffprobe.so")
        for ((toolName, libName) in knownShims) {
            val target = File(bundledDir, libName)
            if (!target.isFile) continue
            val link = File(binDir, toolName)
            if (link.exists()) continue
            runCatching {
                Files.createSymbolicLink(link.toPath(), target.toPath())
            }
        }
    }

    private fun buildCliScriptArgs(
        entryArg: String,
        workflow: ComfyWorkflow,
        request: SubmitterRunRequest,
        inputsDir: File,
        outputDir: File,
        receiptFile: File,
        snapshotEmitFile: File,
        previousWorkflowFile: File?,
        translator: SubmitterPathTranslator,
    ): List<String> {
        val args = ArrayList<String>()
        args.add(entryArg)
        workflow.cliBaseArgs.forEach { rawArg ->
            args.add(interpolateHostMetadata(rawArg, request.hostMetadata))
        }

        val isEmitMode = request.runMode == ComfyWorkflowRunMode.EMIT_WORKFLOW
        val isPreflightMode = request.runMode == ComfyWorkflowRunMode.PREFLIGHT_ONLY

        if (!isEmitMode && !request.proxyBaseUrl.isNullOrBlank() && workflow.hostFlag.isNotBlank()) {
            args += workflow.hostFlag
            args += request.proxyBaseUrl
        }
        if (!isEmitMode && !isPreflightMode && workflow.outputDirFlag.isNotBlank()) {
            args += workflow.outputDirFlag
            args += translator.toGuest(outputDir)
        }
        if (!isEmitMode && !isPreflightMode) {
            workflow.receiptFlag?.takeIf { it.isNotBlank() }?.let { flag ->
                args += flag
                args += translator.toGuest(receiptFile)
            }
        }
        if (isEmitMode) {
            val emitFlag = workflow.emitWorkflowFlag?.takeIf { it.isNotBlank() }
                ?: throw ComfyFailure("工作流 ${workflow.workflowId} 未配置 emitWorkflowFlag，不支持 emit_workflow 模式")
            args += emitFlag
            if (workflow.emitWorkflowTakesPath) {
                args += translator.toGuest(snapshotEmitFile)
            } else {
                // 如 --dry-run 这类无路径参数的布尔导图标志，部分脚本仍要求传入 --receipt，一并补齐。
                workflow.receiptFlag?.takeIf { it.isNotBlank() }?.let { flag ->
                    args += flag
                    args += translator.toGuest(receiptFile)
                }
            }
        }
        if (isPreflightMode) {
            val preflightFlag = workflow.preflightOnlyFlag?.takeIf { it.isNotBlank() }
                ?: throw ComfyFailure("工作流 ${workflow.workflowId} 未配置 preflightOnlyFlag，不支持 preflight_only 模式")
            args += preflightFlag
        }
        if (request.dryRun && !isEmitMode && !isPreflightMode && workflow.dryRunFlag.isNotBlank()) {
            args += workflow.dryRunFlag
        }

        // 有状态链继承锁定上下文注入（首段与续接段统一托管 latent_dir 与段号；成对读写目录优先于单目录标志）
        val chainSpec = workflow.chainSpec
        val explicitKeys = workflow.fields.mapNotNullTo(mutableSetOf()) { f ->
            f.key.takeIf { key -> request.resolvedInputs[key].let { it != null && it !is JsonNull } }
        }
        if (workflow.executionMode == WorkflowExecutionMode.STATEFUL_CHAIN && chainSpec != null) {
            if (request.stage in setOf("first", "next") && "stage" !in explicitKeys && chainSpec.stageFlag.isNotBlank()) {
                args += chainSpec.stageFlag
                args += request.stage
            }
            if (previousWorkflowFile != null && chainSpec.previousWorkflowFlag.isNotBlank()) {
                args += chainSpec.previousWorkflowFlag
                args += translator.toGuest(previousWorkflowFile)
            }
            val inheritedLatentDir = (request.inheritedState["latent_dir"] as? JsonPrimitive)?.contentOrNull
            val effectiveReadDir = request.readLatentDir?.takeIf { it.isNotBlank() } ?: inheritedLatentDir
            val effectiveWriteDir = request.writeLatentDir?.takeIf { it.isNotBlank() } ?: inheritedLatentDir
            if (chainSpec.readLatentDirFlag.isNotBlank() && chainSpec.writeLatentDirFlag.isNotBlank()) {
                effectiveReadDir?.let {
                    args += chainSpec.readLatentDirFlag
                    args += it
                }
                effectiveWriteDir?.let {
                    args += chainSpec.writeLatentDirFlag
                    args += it
                }
            } else {
                val singleDir = effectiveWriteDir ?: effectiveReadDir
                if (singleDir != null && "latent_dir" !in explicitKeys && chainSpec.latentDirFlag.isNotBlank()) {
                    args += chainSpec.latentDirFlag
                    args += singleDir
                }
            }
            if (chainSpec.segmentIndexFlag.isNotBlank() &&
                "clip_index" !in explicitKeys &&
                "segment_index" !in explicitKeys
            ) {
                args += chainSpec.segmentIndexFlag
                args += request.segmentIndex.toString()
            }
            if (request.branchId.isNotBlank() && "branch_id" !in explicitKeys && chainSpec.branchIdFlag.isNotBlank()) {
                args += chainSpec.branchIdFlag
                args += request.branchId
            }
        }

        var textCounter = 0
        workflow.fields.forEach { field ->
            val rawValue = request.resolvedInputs[field.key] ?: return@forEach
            if (rawValue is JsonNull) return@forEach
            val flag = field.cliFlag?.takeIf { it.isNotBlank() } ?: "--${field.key.replace('_', '-')}"
            val effectiveTemplate = field.cliValueTemplate?.takeIf { it.isNotBlank() }
                ?: if (field.valueType == FieldValueType.TIMED_FILES && field.cliMode == CliArgMode.REPEAT) {
                    "{frame_idx}:{file}"
                } else null

            when {
                field.cliMode == CliArgMode.TEMPLATE || effectiveTemplate != null -> {
                    val tpl = requireNotNull(effectiveTemplate)
                    val items = (rawValue as? JsonArray) ?: JsonArray(listOf(rawValue))
                    items.forEach { item ->
                        val formatted = renderCliTemplate(
                            template = tpl,
                            element = item,
                            stagedFilesByPath = request.stagedFilesByPath,
                            hostMetadata = request.hostMetadata,
                            translator = translator,
                        )
                        if (formatted.isNotBlank()) {
                            args += flag
                            args += formatted
                        }
                    }
                }
                field.cliMode == CliArgMode.BOOL_FLAG -> {
                    val enabled = when (val prim = rawValue as? JsonPrimitive) {
                        null -> false
                        else -> prim.booleanOrNull ?: prim.content.equals("true", ignoreCase = true)
                    }
                    if (enabled) {
                        args += flag
                    } else if (!field.cliFalseFlag.isNullOrBlank()) {
                        args += field.cliFalseFlag
                    }
                }
                field.cliMode == CliArgMode.TEXT_FILE -> {
                    val rawText = (rawValue as? JsonPrimitive)?.contentOrNull ?: rawValue.toString()
                    val text = interpolateHostMetadata(rawText, request.hostMetadata)
                    if (text.isNotBlank()) {
                        val file = File(inputsDir, "arg_${textCounter++}_${field.key.take(24)}.txt")
                        file.writeText(text, Charsets.UTF_8)
                        args += flag
                        args += translator.toGuest(file)
                    }
                }
                field.cliMode == CliArgMode.REPEAT -> {
                    val items = (rawValue as? JsonArray) ?: JsonArray(listOf(rawValue))
                    items.forEach { item ->
                        val str = resolveStagedOrScalar(
                            element = item,
                            isWorkspacePath = field.valueType.isWorkspacePathType,
                            stagedFilesByPath = request.stagedFilesByPath,
                            hostMetadata = request.hostMetadata,
                            translator = translator,
                        )
                        if (str.isNotBlank()) {
                            args += flag
                            args += str
                        }
                    }
                }
                field.cliMode == CliArgMode.JSON -> {
                    val rewritten = rewritePayloadElement(
                        element = rawValue,
                        stagedFilesByPath = request.stagedFilesByPath,
                        hostMetadata = request.hostMetadata,
                        translator = translator,
                    )
                    args += flag
                    args += rewritten.toString()
                }
                else -> {
                    val str = resolveStagedOrScalar(
                        element = rawValue,
                        isWorkspacePath = field.valueType.isWorkspacePathType,
                        stagedFilesByPath = request.stagedFilesByPath,
                        hostMetadata = request.hostMetadata,
                        translator = translator,
                    )
                    if (str.isNotEmpty() || field.required) {
                        args += flag
                        args += str
                    }
                }
            }
        }
        return args
    }

    private fun resolveStagedOrScalar(
        element: JsonElement,
        isWorkspacePath: Boolean,
        stagedFilesByPath: Map<String, File>,
        hostMetadata: Map<String, String>,
        translator: SubmitterPathTranslator,
    ): String {
        val raw = (element as? JsonPrimitive)?.contentOrNull ?: element.toString()
        if (isWorkspacePath) {
            stagedFilesByPath[raw]?.let { return translator.toGuest(it) }
        }
        return interpolateHostMetadata(raw, hostMetadata)
    }

    private fun renderCliTemplate(
        template: String,
        element: JsonElement,
        stagedFilesByPath: Map<String, File>,
        hostMetadata: Map<String, String>,
        translator: SubmitterPathTranslator,
        preferredPathKey: String? = null,
    ): String {
        val preInterpolated = interpolateHostMetadata(template, hostMetadata)
        val placeholderRegex = Regex("""\{([A-Za-z0-9_.-]+)\}""")
        fun stagedOrValue(raw: String): String =
            stagedFilesByPath[raw]?.let { translator.toGuest(it) } ?: interpolateHostMetadata(raw, hostMetadata)

        return placeholderRegex.replace(preInterpolated) { match ->
            val key = match.groupValues[1]
            when (element) {
                is JsonObject -> when (key) {
                    "file" -> {
                        val candidateKeys = listOfNotNull(preferredPathKey?.takeIf { it.isNotBlank() }) +
                            listOf("file", "path", "image_path", "video", "audio")
                        val rawPath = candidateKeys
                            .firstNotNullOfOrNull { k -> (element[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
                            ?: throw ComfyFailure("参数模板 {$key} 缺少文件路径字段（${candidateKeys.distinct().joinToString("/")}）")
                        stagedOrValue(rawPath)
                    }
                    "json" -> {
                        val target = element["value"] ?: element
                        rewritePayloadElement(target, stagedFilesByPath, hostMetadata, translator).toString()
                    }
                    "value" -> {
                        val target = element["value"] ?: element
                        if (target is JsonPrimitive) stagedOrValue(target.content) else target.toString()
                    }
                    else -> {
                        val target = element[key]
                            ?: throw ComfyFailure("参数模板占位符 {$key} 在输入项中不存在：$element")
                        if (target is JsonPrimitive) stagedOrValue(target.content)
                        else rewritePayloadElement(target, stagedFilesByPath, hostMetadata, translator).toString()
                    }
                }
                is JsonPrimitive -> when (key) {
                    "file", "value" -> stagedOrValue(element.content)
                    "json" -> element.toString()
                    else -> throw ComfyFailure("标量输入无法解析参数模板占位符 {$key}")
                }
                else -> when (key) {
                    "json", "value" -> rewritePayloadElement(element, stagedFilesByPath, hostMetadata, translator).toString()
                    else -> throw ComfyFailure("复合输入无法解析参数模板占位符 {$key}")
                }
            }
        }
    }

    private fun rewritePayloadInputs(
        inputs: Map<String, JsonElement>,
        stagedFilesByPath: Map<String, File>,
        hostMetadata: Map<String, String>,
        translator: SubmitterPathTranslator,
    ): Map<String, JsonElement> = inputs.mapValues { (_, v) ->
        rewritePayloadElement(v, stagedFilesByPath, hostMetadata, translator)
    }

    private fun rewritePayloadElement(
        element: JsonElement,
        stagedFilesByPath: Map<String, File>,
        hostMetadata: Map<String, String>,
        translator: SubmitterPathTranslator,
    ): JsonElement = when (element) {
        is JsonPrimitive -> if (element.isString) {
            val mapped = stagedFilesByPath[element.content]?.let { translator.toGuest(it) }
                ?: interpolateHostMetadata(element.content, hostMetadata)
            JsonPrimitive(mapped)
        } else element
        is JsonArray -> JsonArray(element.map { rewritePayloadElement(it, stagedFilesByPath, hostMetadata, translator) })
        is JsonObject -> JsonObject(element.mapValues { (_, v) -> rewritePayloadElement(v, stagedFilesByPath, hostMetadata, translator) })
    }

    private fun buildBuiltinEnvironment(
        launch: SubmitterPythonLaunch,
        binDir: File,
        scriptDir: File,
        entryParent: File,
        homeDir: File,
        tmpDir: File,
        proxyBaseUrl: String?,
        payloadFile: File?,
        outputDir: File,
        resultFile: File,
    ): Map<String, String> {
        val pythonPathParts = listOfNotNull(
            entryParent.path,
            scriptDir.path.takeIf { it != entryParent.path },
            launch.environment["PYTHONPATH"]?.takeIf { it.isNotBlank() },
        )
        val existingPath = launch.environment["PATH"].orEmpty()
        val combinedPath = listOf(binDir.path, existingPath).filter { it.isNotBlank() }.joinToString(File.pathSeparator)
        return buildMap {
            putAll(launch.environment)
            put("PATH", combinedPath)
            put("PYTHONDONTWRITEBYTECODE", "1")
            put("PYTHONPATH", pythonPathParts.joinToString(File.pathSeparator))
            put("HOME", homeDir.path)
            put("TMPDIR", tmpDir.path)
            put("COMFY_OUTPUT_DIR", outputDir.path)
            put("COMFY_RESULT_PATH", resultFile.path)
            put("NO_PROXY", "127.0.0.1,localhost")
            put("no_proxy", "127.0.0.1,localhost")
            if (!proxyBaseUrl.isNullOrBlank()) put("COMFY_HOST_URL", proxyBaseUrl)
            if (payloadFile != null) put("COMFY_PAYLOAD_PATH", payloadFile.path)
        }
    }

    private fun buildGuestEnvironment(
        translator: SubmitterPathTranslator,
        scriptDir: File,
        entryParent: File,
        tmpDir: File,
        proxyBaseUrl: String?,
        payloadFile: File?,
        outputDir: File,
        resultFile: File,
    ): Map<String, String> {
        val guestEntryParent = translator.toGuest(entryParent)
        val guestScriptDir = translator.toGuest(scriptDir)
        val pythonPath = listOf(guestEntryParent, guestScriptDir).distinct().joinToString(":")
        return buildMap {
            put("PYTHONDONTWRITEBYTECODE", "1")
            put("PYTHONUTF8", "1")
            put("PYTHONPATH", pythonPath)
            put("TMPDIR", translator.toGuest(tmpDir))
            put("COMFY_OUTPUT_DIR", translator.toGuest(outputDir))
            put("COMFY_RESULT_PATH", translator.toGuest(resultFile))
            // 确保 127.0.0.1 回环鉴权代理直连，不被 tailnet 代理劫持
            put("NO_PROXY", "127.0.0.1,localhost")
            put("no_proxy", "127.0.0.1,localhost")
            if (!proxyBaseUrl.isNullOrBlank()) put("COMFY_HOST_URL", proxyBaseUrl)
            if (payloadFile != null) put("COMFY_PAYLOAD_PATH", translator.toGuest(payloadFile))
        }
    }

    private fun parseProgressLine(line: String): Pair<Float?, String>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        val obj = runCatching { Json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        if (type != "progress" && "progress" !in obj) return null
        val rawPct = (obj["percent"] as? JsonPrimitive)?.doubleOrNull
            ?: (obj["progress"] as? JsonPrimitive)?.doubleOrNull
        val pct = rawPct?.let { (if (it > 1.0) it / 100.0 else it).toFloat().coerceIn(0f, 1f) }
        val msg = (obj["message"] as? JsonPrimitive)?.contentOrNull ?: ""
        return pct to msg
    }

    private fun readResultJson(resultFile: File, receiptFile: File, stdoutLines: List<String>): JsonObject? {
        if (resultFile.isFile) {
            runCatching { Json.parseToJsonElement(resultFile.readText(Charsets.UTF_8)) as? JsonObject }.getOrNull()?.let { return it }
        }
        if (receiptFile.isFile) {
            runCatching { Json.parseToJsonElement(receiptFile.readText(Charsets.UTF_8)) as? JsonObject }.getOrNull()?.let { return it }
        }
        // 1. 快速路径：尾部单行 JSON
        for (line in stdoutLines.asReversed()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                val obj = runCatching { Json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
                if (obj != null && isResultLikeJson(obj)) return obj
            }
        }
        // 2. 通用路径：解析 stdout 末尾最后一个平衡的多行缩进 JSON 对象（如 Music3 / Qwen2.1 indent=2）
        return extractLastBalancedJsonObject(stdoutLines.joinToString("\n"))
    }

    private fun collectOutputs(
        resultJson: JsonObject?,
        outputDir: File,
        translator: SubmitterPathTranslator,
    ): List<SubmitterOutputArtifact> {
        val fromResult = (resultJson?.get("outputs") as? JsonArray).orEmpty().mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val pathStr = (obj["path"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["local_path"] as? JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null
            val file = translator.toHost(pathStr, outputDir)
            if (!file.isFile || file.length() <= 0L) return@mapNotNull null
            val declaredKind = (obj["kind"] as? JsonPrimitive)?.contentOrNull?.let { k ->
                MediaKind.entries.firstOrNull { it.name.equals(k, ignoreCase = true) }
            }
            val inferred = classifyOutputFile(file.name, declaredKind) ?: return@mapNotNull null
            SubmitterOutputArtifact(file, inferred.first, inferred.second)
        }
        if (fromResult.isNotEmpty()) return fromResult.distinctBy { it.file.canonicalPath }

        // 也检查 receipt 中脚本输出的 downloaded 列表（兼容各类 CLI 回执中的下载路径数组）
        val fromDownloaded = (resultJson?.get("downloaded") as? JsonArray).orEmpty().mapNotNull { elem ->
            val pathStr = (elem as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val file = translator.toHost(pathStr, outputDir)
            if (!file.isFile || file.length() <= 0L) return@mapNotNull null
            val inferred = classifyOutputFile(file.name, null) ?: return@mapNotNull null
            SubmitterOutputArtifact(file, inferred.first, inferred.second)
        }
        if (fromDownloaded.isNotEmpty()) return fromDownloaded.distinctBy { it.file.canonicalPath }

        return outputDir.walkTopDown()
            .filter { it.isFile && it.length() > 0 }
            .mapNotNull { file ->
                val inferred = classifyOutputFile(file.name, null) ?: return@mapNotNull null
                SubmitterOutputArtifact(file, inferred.first, inferred.second)
            }
            .sortedBy { it.file.name }
            .toList()
    }

    private data class ContinuationBuildResult(
        val state: JsonObject,
        val reportedLatentDir: String?,
        val reportedClipIndex: Int?,
        val warnings: List<String>,
    )

    private fun buildContinuationState(
        resultJson: JsonObject?,
        receiptFile: File,
        snapshotEmitFile: File,
        runDir: File,
        request: SubmitterRunRequest,
        translator: SubmitterPathTranslator,
    ): ContinuationBuildResult {
        val explicit = (resultJson?.get("continuation_state") as? JsonObject)?.toMutableMap() ?: linkedMapOf()
        val rawScriptLatentDir = (explicit["latent_dir"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val rawScriptClipIndex = (explicit["clip_index"] as? JsonPrimitive)?.intOrNull
        val warnings = mutableListOf<String>()

        request.writeLatentDir?.takeIf { it.isNotBlank() }?.let { explicit.putIfAbsent("latent_dir", JsonPrimitive(it)) }
        request.inheritedState["latent_dir"]?.let { explicit.putIfAbsent("latent_dir", it) }
        request.inheritedState["width"]?.let { explicit.putIfAbsent("width", it) }
        request.inheritedState["height"]?.let { explicit.putIfAbsent("height", it) }
        request.inheritedState["family"]?.let { explicit.putIfAbsent("family", it) }
        request.inheritedState["context_length"]?.let { explicit.putIfAbsent("context_length", it) }
        request.resolvedInputs["latent_dir"]?.let { explicit["latent_dir"] = it }
        request.resolvedInputs["width"]?.let { explicit["width"] = it }
        request.resolvedInputs["height"]?.let { explicit["height"] = it }
        request.resolvedInputs["family"]?.let { explicit["family"] = it }
        request.resolvedInputs["context_length"]?.let { explicit["context_length"] = it }

        // 防御一过性沙箱目录（含 Linux /mnt/comfy_run）泄漏进 latent_dir
        val rawLatentDir = (explicit["latent_dir"] as? JsonPrimitive)?.contentOrNull
        if (rawLatentDir != null && isEphemeralRunPath(rawLatentDir, runDir)) {
            val managedWriteDir = request.writeLatentDir?.takeUnless { isEphemeralRunPath(it, runDir) }
            val inherited = (request.inheritedState["latent_dir"] as? JsonPrimitive)?.contentOrNull
                ?.takeUnless { isEphemeralRunPath(it, runDir) }
            val fallbackDir = managedWriteDir ?: inherited ?: "comfy_chain/${request.workflow?.workflowId ?: "chain"}/${request.branchId.ifBlank { "main" }}"
            explicit["latent_dir"] = JsonPrimitive(fallbackDir)
        }

        // 若 receipt 或 workflow 产物中包含 API workflow 快照，持久化进 continuationState 以便后续中段接续 --previous-workflow。
        val reconstructionObj = resultJson?.get("reconstruction") as? JsonObject
        val reconstructedWorkflowFile = (reconstructionObj?.get("workflow") as? JsonPrimitive)?.contentOrNull
            ?.let { translator.toHost(it, runDir) }
            ?.takeIf { it.isFile }
        val workflowObj = (resultJson?.get("workflow") as? JsonObject)
            ?: reconstructedWorkflowFile?.let { f ->
                runCatching { Json.parseToJsonElement(f.readText(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            }
            ?: snapshotEmitFile.takeIf { it.isFile }?.let { f ->
                runCatching { Json.parseToJsonElement(f.readText(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            }
        if (workflowObj != null) {
            explicit["workflow_snapshot"] = workflowObj
        }
        // 保留前段导出工作流文件的精确原始文本，确保续段侧车证明文件（如 .segment.json）校验 SHA-256 逐字节一致。
        val rawSnapshotFile = reconstructedWorkflowFile ?: snapshotEmitFile.takeIf { it.isFile }
        val rawSnapshotText = rawSnapshotFile
            ?.takeIf { it.length() <= MAX_WORKFLOW_SNAPSHOT_RAW_BYTES }
            ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
        if (rawSnapshotText != null) {
            explicit["__workflow_snapshot_raw"] = JsonPrimitive(rawSnapshotText)
        }

        // G9: 按 chainSpec.sidecarSuffixes 及 reconstruction.segment 持久保存前段配套证明文件（上限每项 256 KiB）
        val sidecars = linkedMapOf<String, JsonPrimitive>()
        val sidecarSuffixes = request.workflow?.chainSpec?.sidecarSuffixes.orEmpty()
        val anchorFiles = listOfNotNull(
            reconstructedWorkflowFile,
            snapshotEmitFile.takeIf { it.isFile },
            receiptFile.takeIf { it.isFile },
        )
        for (suffix in sidecarSuffixes) {
            val found = anchorFiles.firstNotNullOfOrNull { anchor ->
                File(anchor.parentFile, "${anchor.nameWithoutExtension}$suffix").takeIf { it.isFile && it.length() <= MAX_SIDECAR_BYTES }
            }
            if (found != null) {
                sidecars[suffix] = JsonPrimitive(found.readText(Charsets.UTF_8))
            }
        }
        val explicitSegmentFile = (reconstructionObj?.get("segment") as? JsonPrimitive)?.contentOrNull
            ?.let { translator.toHost(it, runDir) }
            ?.takeIf { it.isFile && it.length() <= MAX_SIDECAR_BYTES }
        if (explicitSegmentFile != null && ".segment.json" !in sidecars) {
            sidecars[".segment.json"] = JsonPrimitive(explicitSegmentFile.readText(Charsets.UTF_8))
        }
        if (sidecars.isNotEmpty()) {
            explicit["__sidecar_files"] = JsonObject(sidecars)
        }

        (resultJson?.get("motion_segment") as? JsonObject)?.forEach { (k, v) ->
            explicit.putIfAbsent(k, v)
        }
        val isStatefulChain = request.workflow?.executionMode == WorkflowExecutionMode.STATEFUL_CHAIN
        if ((receiptFile.isFile || isStatefulChain) && "clip_index" !in explicit) {
            explicit["clip_index"] = JsonPrimitive(request.segmentIndex)
        }

        val finalLatentDir = (explicit["latent_dir"] as? JsonPrimitive)?.contentOrNull
        val finalClipIndex = (explicit["clip_index"] as? JsonPrimitive)?.intOrNull

        if (isStatefulChain) {
            if (rawScriptLatentDir != null &&
                !request.writeLatentDir.isNullOrBlank() &&
                !isEphemeralRunPath(rawScriptLatentDir, runDir) &&
                rawScriptLatentDir != request.writeLatentDir
            ) {
                warnings += "脚本回报 latent_dir=$rawScriptLatentDir，偏离宿主分配的 write_latent_dir=${request.writeLatentDir}"
            }
            if (rawScriptClipIndex != null && rawScriptClipIndex != request.segmentIndex) {
                warnings += "脚本回报 clip_index=$rawScriptClipIndex，与宿主段落序号 segment_index=${request.segmentIndex} 不一致"
            }
        }

        return ContinuationBuildResult(
            state = JsonObject(explicit),
            reportedLatentDir = finalLatentDir,
            reportedClipIndex = finalClipIndex,
            warnings = warnings,
        )
    }

    private fun isEphemeralRunPath(raw: String, runDir: File): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed == GUEST_RUN_ROOT || trimmed.startsWith("$GUEST_RUN_ROOT/")) return true
        if (trimmed == GUEST_STAGED_ROOT || trimmed.startsWith("$GUEST_STAGED_ROOT/")) return true
        val runPath = runDir.path
        val runCanonical = runCatching { runDir.canonicalPath }.getOrNull()
        val scratchParent = runDir.parentFile
        val scratchPath = scratchParent?.path
        val scratchCanonical = scratchParent?.let { runCatching { it.canonicalPath }.getOrNull() }
        val candidates = listOfNotNull(runPath, runCanonical, scratchPath, scratchCanonical)
        val rawCanonical = runCatching { File(trimmed).canonicalPath }.getOrNull()
        return candidates.any { prefix ->
            trimmed == prefix || trimmed.startsWith("$prefix/") ||
                (rawCanonical != null && (rawCanonical == prefix || rawCanonical.startsWith("$prefix/")))
        }
    }

    private fun deriveDefaultSlotKey(
        continuation: JsonObject,
        @Suppress("UNUSED_PARAMETER") request: SubmitterRunRequest,
    ): String? {
        val latentDir = (continuation["latent_dir"] as? JsonPrimitive)?.contentOrNull ?: return null
        val clipIndex = (continuation["clip_index"] as? JsonPrimitive)?.intOrNull ?: return null
        return "$latentDir::clip_$clipIndex"
    }

    private data class SimpleCmdOut(val exitCode: Int, val stdout: String, val stderr: String) {
        fun combinedError(): String = ComfyErrorDiagnostics.redactSensitive(
            stderr.trim().ifBlank { stdout.trim() }.takeLast(600),
        )
    }

    private fun runSimpleCommand(
        command: List<String>,
        env: Map<String, String>,
        workDir: File,
        timeoutSeconds: Long,
    ): SimpleCmdOut {
        val process = ProcessBuilder(command).apply {
            directory(workDir)
            environment().clear()
            environment().putAll(env)
        }.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val tOut = thread(isDaemon = true) {
            runCatching { stdout.append(process.inputStream.bufferedReader().readText()) }
        }
        val tErr = thread(isDaemon = true) {
            runCatching { stderr.append(process.errorStream.bufferedReader().readText()) }
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            tOut.join(200)
            tErr.join(200)
            return SimpleCmdOut(-1, stdout.toString(), "超时（>${timeoutSeconds}s）\n$stderr")
        }
        tOut.join(300)
        tErr.join(300)
        return SimpleCmdOut(process.exitValue(), stdout.toString(), stderr.toString())
    }

    companion object {
        const val GUEST_RUN_ROOT = "/mnt/comfy_run"
        const val GUEST_STAGED_ROOT = "/mnt/comfy_stage"
        private const val MAX_SIDECAR_BYTES = 256L * 1024L
        private const val MAX_WORKFLOW_SNAPSHOT_RAW_BYTES = 2L * 1024L * 1024L
        private val HOST_PLACEHOLDER_REGEX = Regex("""\{host\.([A-Za-z0-9_]+)\}""")
        private val DEFAULT_PROMPT_ID_REGEX = Regex("""\bprompt_id\s*[:=]\s*([A-Za-z0-9_-]{6,128})""")

        fun cleanupRunDirectory(continuationState: JsonObject) {
            val path = (continuationState["__run_dir_cleanup"] as? JsonPrimitive)?.contentOrNull ?: return
            runCatching { File(path).deleteRecursively() }
        }

        internal fun interpolateHostMetadata(text: String, hostMetadata: Map<String, String>): String {
            if ("{host." !in text) return text
            return HOST_PLACEHOLDER_REGEX.replace(text) { match ->
                val key = match.groupValues[1]
                hostMetadata[key]?.takeIf { it.isNotBlank() }
                    ?: throw ComfyFailure("无法解析主机元数据占位符 {host.$key}：请确认主机在线并可读取 /system_stats")
            }
        }

        internal fun extractLastBalancedJsonObject(text: String): JsonObject? {
            if (text.isBlank()) return null
            val candidates = ArrayList<String>()
            var depth = 0
            var inString = false
            var escaped = false
            var start = -1
            for (i in text.indices) {
                val c = text[i]
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (c == '\\') {
                        escaped = true
                    } else if (c == '"') {
                        inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> {
                        if (depth > 0) {
                            depth--
                            if (depth == 0 && start >= 0) {
                                candidates += text.substring(start, i + 1)
                                start = -1
                            }
                        }
                    }
                }
            }
            for (raw in candidates.asReversed()) {
                val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: continue
                if (isResultLikeJson(obj)) return obj
            }
            for (raw in candidates.asReversed()) {
                val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                if (obj != null) return obj
            }
            return null
        }

        internal fun extractPromptId(resultJson: JsonObject?, stdoutText: String, pattern: String?): String? {
            (resultJson?.get("prompt_id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            if (!pattern.isNullOrBlank()) {
                val m = runCatching { Regex(pattern).find(stdoutText) }.getOrNull()
                if (m != null) {
                    val captured = if (m.groupValues.size > 1) m.groupValues[1] else m.groupValues[0]
                    if (captured.isNotBlank()) return captured.trim()
                }
            }
            return DEFAULT_PROMPT_ID_REGEX.find(stdoutText)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        }

        private fun isResultLikeJson(obj: JsonObject): Boolean =
            "outputs" in obj || "prompt_id" in obj || "workflow" in obj || "preflight" in obj || "downloaded" in obj

        internal fun classifyOutputFile(fileName: String, declared: MediaKind?): Pair<MediaKind, String>? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "png" -> (declared ?: MediaKind.IMAGE) to "image/png"
                "jpg", "jpeg" -> (declared ?: MediaKind.IMAGE) to "image/jpeg"
                "webp" -> (declared ?: MediaKind.IMAGE) to "image/webp"
                "gif" -> (declared ?: MediaKind.VIDEO) to "image/gif"
                "mp4" -> MediaKind.VIDEO to "video/mp4"
                "webm" -> MediaKind.VIDEO to "video/webm"
                "mov" -> MediaKind.VIDEO to "video/quicktime"
                "mkv" -> MediaKind.VIDEO to "video/x-matroska"
                "avi" -> MediaKind.VIDEO to "video/x-msvideo"
                "wav" -> MediaKind.AUDIO to "audio/wav"
                "mp3" -> MediaKind.AUDIO to "audio/mpeg"
                "flac" -> MediaKind.AUDIO to "audio/flac"
                "ogg" -> MediaKind.AUDIO to "audio/ogg"
                "m4a" -> MediaKind.AUDIO to "audio/mp4"
                "glb" -> MediaKind.FILE to "model/gltf-binary"
                "gltf" -> MediaKind.FILE to "model/gltf+json"
                "obj" -> MediaKind.FILE to "model/obj"
                "stl" -> MediaKind.FILE to "model/stl"
                "fbx" -> MediaKind.FILE to "application/octet-stream"
                "ply" -> MediaKind.FILE to "application/octet-stream"
                "usd", "usdz" -> MediaKind.FILE to "model/vnd.usdz+zip"
                "json" -> MediaKind.FILE to "application/json"
                "txt" -> MediaKind.FILE to "text/plain"
                "zip" -> MediaKind.FILE to "application/zip"
                else -> declared?.let { it to "application/octet-stream" }
            }
        }
    }
}
