package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.data.exec.PackagedPython
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** 受控内置 Python 3.14 提交器启动参数（真机使用 PackagedPython，JVM 单元测试可注入宿主 python3）。 */
data class SubmitterPythonLaunch(
    val commandPrefix: List<String>,
    val environment: Map<String, String>,
    val compiledCompileFlags: List<String> = listOf("-m", "py_compile"),
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
            )
        }
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
    val resolvedInputs: Map<String, JsonElement> = emptyMap(),
    val stagedFilesByPath: Map<String, File> = emptyMap(),
    val stage: String = "single",
    val branchId: String = "main",
    val inheritedState: JsonObject = JsonObject(emptyMap()),
    val previousWorkflowFile: File? = null,
    val dryRun: Boolean = false,
    val timeoutSeconds: Int = 900,
    val onProgress: suspend (percent: Float?, message: String) -> Unit = { _, _ -> },
)

data class SubmitterOutputArtifact(
    val file: File,
    val kind: InputKind,
    val mimeType: String,
)

data class SubmitterExecutionResult(
    val success: Boolean,
    val exitCode: Int,
    val promptId: String? = null,
    val outputs: List<SubmitterOutputArtifact> = emptyList(),
    val continuationState: JsonObject = JsonObject(emptyMap()),
    val serverLatentSlotKey: String? = null,
    val summary: String = "",
    val stdoutTail: String = "",
    val stderrTail: String = "",
    val latencyMs: Long = 0L,
)

/**
 * 在受控内置 Python 3.14 隔离运行目录中执行 ComfyUI 提交器脚本。
 * 特性：
 * - 不注册为智能体 Python 工具，避免与沙箱 Python / Alpine Python 混杂；
 * - 仅接收本机 `127.0.0.1` 回环代理地址（`COMFY_HOST_URL` / `--host`），绝对不传递任何主机密钥或令牌；
 * - 支持标准化 `PAYLOAD_JSON` 协议与桌面现成 `CLI_ARGS` 脚本协议（如 `submit_qwen21.py` / `submit_h3.py` / `submit_h3_motion.py`）。
 */
class ComfySubmitterRunner(
    private val submitterFiles: ComfySubmitterFileStore,
    private val scratchRoot: File,
    private val launchProvider: () -> SubmitterPythonLaunch?,
) {
    val isPythonAvailable: Boolean get() = launchProvider() != null

    suspend fun dryRunScript(submitter: ComfySubmitterScript, workflow: ComfyWorkflow? = null): SubmitterExecutionResult =
        withContext(Dispatchers.IO) {
            val launch = launchProvider()
                ?: throw ComfyFailure("当前设备架构未打包内置 Python 3.14，无法运行提交器脚本")
            val startedMs = System.currentTimeMillis()
            val runDir = File(scratchRoot, "dry_${UUID.randomUUID().toString().take(12)}")
            val scriptDir = File(runDir, "script")
            val homeDir = File(runDir, "home")
            val tmpDir = File(runDir, "tmp")
            val outputDir = File(runDir, "outputs")
            try {
                listOf(scriptDir, homeDir, tmpDir, outputDir).forEach { it.mkdirs() }
                check(submitterFiles.stageIntoDirectory(submitter.hostId, submitter.scriptId, scriptDir)) {
                    "提交器脚本文件不存在，请重新导入"
                }
                val entryFile = File(scriptDir, submitter.fileName).canonicalFile
                require(entryFile.isFile) { "提交器入口文件缺失：${submitter.fileName}" }
                val baseEnv = buildProcessEnvironment(
                    launch = launch,
                    scriptDir = scriptDir,
                    entryParent = entryFile.parentFile ?: scriptDir,
                    homeDir = homeDir,
                    tmpDir = tmpDir,
                    proxyBaseUrl = null,
                    payloadFile = null,
                    outputDir = outputDir,
                    resultFile = File(runDir, "result.json"),
                )
                // 第一步：全包语法与字节码检查（写入 tmp 目录内的 __pycache__ 无碍后随 runDir 删除）。
                val compileOut = runSimpleCommand(
                    launch.commandPrefix + listOf("-m", "py_compile", entryFile.path),
                    baseEnv,
                    scriptDir,
                    timeoutSeconds = 20,
                )
                if (compileOut.exitCode != 0) {
                    val latency = System.currentTimeMillis() - startedMs
                    return@withContext SubmitterExecutionResult(
                        success = false,
                        exitCode = compileOut.exitCode,
                        summary = "语法检查失败：${compileOut.combinedError()}",
                        stdoutTail = compileOut.stdout,
                        stderrTail = compileOut.stderr,
                        latencyMs = latency,
                    )
                }
                // 第二步：按协议运行轻量自检（CLI 脚本检查 --help 验证全模块导入；PAYLOAD_JSON 传 dry_run=true）。
                val secondCmd = when (submitter.protocol) {
                    SubmitterInvocationProtocol.PAYLOAD_JSON -> {
                        val payloadFile = File(runDir, "payload.json")
                        payloadFile.writeText(
                            buildJsonObject {
                                put("dry_run", true)
                                put("stage", "dry_run")
                                put("output_dir", outputDir.path)
                                workflow?.let { put("workflow_id", it.workflowId) }
                            }.toString(),
                            Charsets.UTF_8,
                        )
                        launch.commandPrefix + listOf(entryFile.path, "--payload", payloadFile.path)
                    }
                    SubmitterInvocationProtocol.CLI_ARGS -> {
                        launch.commandPrefix + listOf(entryFile.path, "--help")
                    }
                }
                val probeOut = runSimpleCommand(secondCmd, baseEnv, scriptDir, timeoutSeconds = 30)
                val latency = System.currentTimeMillis() - startedMs
                return@withContext if (probeOut.exitCode == 0) {
                    SubmitterExecutionResult(
                        success = true,
                        exitCode = 0,
                        summary = "语法、导入与入口协议自检通过（${latency} ms）",
                        stdoutTail = probeOut.stdout,
                        stderrTail = probeOut.stderr,
                        latencyMs = latency,
                    )
                } else {
                    SubmitterExecutionResult(
                        success = false,
                        exitCode = probeOut.exitCode,
                        summary = "入口自检失败（退出码 ${probeOut.exitCode}）：${probeOut.combinedError()}",
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
        val launch = launchProvider()
            ?: throw ComfyFailure("当前设备架构未打包内置 Python 3.14，无法运行 ComfyUI 提交器")
        val startedMs = System.currentTimeMillis()
        val runDir = File(scratchRoot, "run_${UUID.randomUUID().toString().take(12)}")
        val scriptDir = File(runDir, "script")
        val homeDir = File(runDir, "home")
        val tmpDir = File(runDir, "tmp")
        val inputsDir = File(runDir, "inputs")
        val outputDir = File(runDir, "outputs")
        val payloadFile = File(runDir, "payload.json")
        val resultFile = File(runDir, "result.json")
        val receiptFile = File(runDir, "receipt.json")
        val snapshotEmitFile = File(runDir, "emitted_workflow.json")
        listOf(scriptDir, homeDir, tmpDir, inputsDir, outputDir).forEach { it.mkdirs() }

        check(submitterFiles.stageIntoDirectory(request.submitter.hostId, request.submitter.scriptId, scriptDir)) {
            "提交器脚本文件不存在，请重新导入"
        }
        val entryFile = File(scriptDir, request.submitter.fileName).canonicalFile
        require(entryFile.isFile) { "提交器入口文件缺失：${request.submitter.fileName}" }

        val command = when (request.submitter.protocol) {
            SubmitterInvocationProtocol.PAYLOAD_JSON -> {
                val payload = buildJsonObject {
                    put("dry_run", request.dryRun)
                    put("stage", request.stage)
                    put("branch_id", request.branchId)
                    put("output_dir", outputDir.path)
                    put("result_path", resultFile.path)
                    request.proxyBaseUrl?.let { put("host_proxy_url", it) }
                    request.workflow?.let { put("workflow_id", it.workflowId) }
                    put("inputs", JsonObject(rewritePayloadInputs(request.resolvedInputs, request.stagedFilesByPath, inputsDir)))
                    if (request.inheritedState.isNotEmpty()) put("inherited_state", request.inheritedState)
                }
                payloadFile.writeText(payload.toString(), Charsets.UTF_8)
                launch.commandPrefix + listOf(entryFile.path, "--payload", payloadFile.path)
            }
            SubmitterInvocationProtocol.CLI_ARGS -> {
                val wf = requireNotNull(request.workflow) { "CLI_ARGS 提交器需要关联 ComfyWorkflow 配置" }
                buildCliCommand(
                    launchPrefix = launch.commandPrefix,
                    entryFile = entryFile,
                    workflow = wf,
                    request = request,
                    inputsDir = inputsDir,
                    outputDir = outputDir,
                    receiptFile = receiptFile,
                    snapshotEmitFile = snapshotEmitFile,
                )
            }
        }

        val env = buildProcessEnvironment(
            launch = launch,
            scriptDir = scriptDir,
            entryParent = entryFile.parentFile ?: scriptDir,
            homeDir = homeDir,
            tmpDir = tmpDir,
            proxyBaseUrl = request.proxyBaseUrl,
            payloadFile = payloadFile.takeIf { it.isFile },
            outputDir = outputDir,
            resultFile = resultFile,
        )

        val process = try {
            ProcessBuilder(command).apply {
                directory(scriptDir)
                environment().clear()
                environment().putAll(env)
            }.start()
        } catch (e: Exception) {
            runDir.deleteRecursively()
            throw ComfyFailure("无法启动受控 Python 3.14 提交器进程：${e.message?.take(200)}")
        }

        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        val timedOut = AtomicBoolean(false)
        val stopOnCancel = currentCoroutineContext()[Job]?.invokeOnCompletion { process.destroyForcibly() }
        val stderrDrain = thread(name = "comfy-py-stderr", isDaemon = true) {
            runCatching {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    synchronized(stderrBuf) {
                        if (stderrBuf.length < 32_000) stderrBuf.appendLine(line)
                    }
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
                        synchronized(stdoutBuf) {
                            if (stdoutBuf.length < 48_000) stdoutBuf.appendLine(line)
                        }
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
            val resultJson = readResultJson(resultFile, receiptFile, stdoutLines)
            val outputs = collectOutputs(resultJson, outputDir)
            val promptId = (resultJson?.get("prompt_id") as? JsonPrimitive)?.contentOrNull
            val continuation = buildContinuationState(resultJson, receiptFile, snapshotEmitFile, outputDir, request)
            val slotKey = (resultJson?.get("overwrites_slot_key") as? JsonPrimitive)?.contentOrNull
                ?: deriveDefaultSlotKey(continuation)

            if (exitCode != 0) {
                val errDetail = stderrBuf.toString().trim().takeLast(600).ifBlank { stdoutBuf.toString().trim().takeLast(400) }
                throw ComfyFailure("ComfyUI 提交器执行失败（退出码 $exitCode）：$errDetail")
            }

            keepRunDirForOutputs = true
            return@withContext SubmitterExecutionResult(
                success = true,
                exitCode = exitCode,
                promptId = promptId,
                outputs = outputs,
                continuationState = JsonObject(
                    continuation + ("__run_dir_cleanup" to JsonPrimitive(runDir.path)),
                ),
                serverLatentSlotKey = slotKey,
                summary = (resultJson?.get("summary") as? JsonPrimitive)?.contentOrNull
                    ?: "提交器执行成功，产出 ${outputs.size} 个媒体文件",
                stdoutTail = stdoutBuf.toString().takeLast(4000),
                stderrTail = stderrBuf.toString().takeLast(2000),
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

    private fun buildCliCommand(
        launchPrefix: List<String>,
        entryFile: File,
        workflow: ComfyWorkflow,
        request: SubmitterRunRequest,
        inputsDir: File,
        outputDir: File,
        receiptFile: File,
        snapshotEmitFile: File,
    ): List<String> {
        val args = ArrayList<String>()
        args.addAll(launchPrefix)
        args.add(entryFile.path)
        args.addAll(workflow.cliBaseArgs)

        if (!request.proxyBaseUrl.isNullOrBlank() && workflow.hostFlag.isNotBlank()) {
            args += workflow.hostFlag
            args += request.proxyBaseUrl
        }
        if (workflow.outputDirFlag.isNotBlank()) {
            args += workflow.outputDirFlag
            args += outputDir.path
        }
        workflow.receiptFlag?.takeIf { it.isNotBlank() }?.let { flag ->
            args += flag
            args += receiptFile.path
        }
        if (request.dryRun && workflow.dryRunFlag.isNotBlank()) {
            args += workflow.dryRunFlag
        }

        // 有状态链继承锁定上下文注入（仅当工作流声明 CLI 标志且调用端未在字段里重复给出时补充）
        val chainSpec = workflow.chainSpec
        val explicitKeys = request.resolvedInputs.keys
        if (workflow.executionMode == WorkflowExecutionMode.STATEFUL_CHAIN && chainSpec != null) {
            if (request.stage in setOf("first", "next") && "stage" !in explicitKeys && chainSpec.stageFlag.isNotBlank()) {
                args += chainSpec.stageFlag
                args += request.stage
            }
            if (request.previousWorkflowFile != null && chainSpec.previousWorkflowFlag.isNotBlank()) {
                args += chainSpec.previousWorkflowFlag
                args += request.previousWorkflowFile.path
            }
            val inheritedLatentDir = (request.inheritedState["latent_dir"] as? JsonPrimitive)?.contentOrNull
            if (inheritedLatentDir != null && "latent_dir" !in explicitKeys && chainSpec.latentDirFlag.isNotBlank()) {
                args += chainSpec.latentDirFlag
                args += inheritedLatentDir
            }
        }

        var textCounter = 0
        workflow.fields.forEach { field ->
            val rawValue = request.resolvedInputs[field.key] ?: return@forEach
            if (rawValue is JsonNull) return@forEach
            val flag = field.cliFlag?.takeIf { it.isNotBlank() } ?: "--${field.key.replace('_', '-')}"
            when (field.cliMode) {
                CliArgMode.BOOL_FLAG -> {
                    val enabled = (rawValue as? JsonPrimitive)?.booleanOrNull == true
                    if (enabled) args += flag
                }
                CliArgMode.TEXT_FILE -> {
                    val text = (rawValue as? JsonPrimitive)?.contentOrNull ?: rawValue.toString()
                    if (text.isNotBlank()) {
                        val file = File(inputsDir, "arg_${textCounter++}_${field.key.take(24)}.txt")
                        file.writeText(text, Charsets.UTF_8)
                        args += flag
                        args += file.path
                    }
                }
                CliArgMode.REPEAT -> {
                    val items = (rawValue as? JsonArray) ?: JsonArray(listOf(rawValue))
                    items.forEach { item ->
                        val str = resolveStagedOrScalar(item, field.valueType.isMediaFileType, request.stagedFilesByPath)
                        if (str.isNotBlank()) {
                            args += flag
                            args += str
                        }
                    }
                }
                CliArgMode.JSON -> {
                    val rewritten = rewritePayloadElement(rawValue, request.stagedFilesByPath, inputsDir)
                    args += flag
                    args += rewritten.toString()
                }
                CliArgMode.VALUE -> {
                    val str = resolveStagedOrScalar(rawValue, field.valueType.isMediaFileType, request.stagedFilesByPath)
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
        isMedia: Boolean,
        stagedFilesByPath: Map<String, File>,
    ): String {
        val raw = (element as? JsonPrimitive)?.contentOrNull ?: element.toString()
        if (!isMedia) return raw
        return stagedFilesByPath[raw]?.path ?: raw
    }

    private fun rewritePayloadInputs(
        inputs: Map<String, JsonElement>,
        stagedFilesByPath: Map<String, File>,
        inputsDir: File,
    ): Map<String, JsonElement> = inputs.mapValues { (_, v) ->
        rewritePayloadElement(v, stagedFilesByPath, inputsDir)
    }

    private fun rewritePayloadElement(
        element: JsonElement,
        stagedFilesByPath: Map<String, File>,
        @Suppress("UNUSED_PARAMETER") inputsDir: File,
    ): JsonElement = when (element) {
        is JsonPrimitive -> if (element.isString) {
            JsonPrimitive(stagedFilesByPath[element.content]?.path ?: element.content)
        } else element
        is JsonArray -> JsonArray(element.map { rewritePayloadElement(it, stagedFilesByPath, inputsDir) })
        is JsonObject -> JsonObject(element.mapValues { (_, v) -> rewritePayloadElement(v, stagedFilesByPath, inputsDir) })
    }

    private fun buildProcessEnvironment(
        launch: SubmitterPythonLaunch,
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
        return buildMap {
            putAll(launch.environment)
            put("PYTHONPATH", pythonPathParts.joinToString(File.pathSeparator))
            put("HOME", homeDir.path)
            put("TMPDIR", tmpDir.path)
            put("COMFY_OUTPUT_DIR", outputDir.path)
            put("COMFY_RESULT_PATH", resultFile.path)
            if (!proxyBaseUrl.isNullOrBlank()) put("COMFY_HOST_URL", proxyBaseUrl)
            if (payloadFile != null) put("COMFY_PAYLOAD_PATH", payloadFile.path)
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
        for (line in stdoutLines.asReversed()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("{")) {
                val obj = runCatching { Json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
                if (obj != null && ("outputs" in obj || "prompt_id" in obj || "workflow" in obj)) return obj
            }
        }
        return null
    }

    private fun collectOutputs(resultJson: JsonObject?, outputDir: File): List<SubmitterOutputArtifact> {
        val fromResult = (resultJson?.get("outputs") as? JsonArray).orEmpty().mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val pathStr = (obj["path"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["local_path"] as? JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null
            val file = File(pathStr).let { if (it.isAbsolute) it else File(outputDir, pathStr) }
            if (!file.isFile) return@mapNotNull null
            val declaredKind = (obj["kind"] as? JsonPrimitive)?.contentOrNull?.let { k ->
                InputKind.entries.firstOrNull { it.name.equals(k, ignoreCase = true) }
            }
            val inferred = classifyMediaFile(file.name, declaredKind) ?: return@mapNotNull null
            SubmitterOutputArtifact(file, inferred.first, inferred.second)
        }
        if (fromResult.isNotEmpty()) return fromResult.distinctBy { it.file.canonicalPath }

        return outputDir.walkTopDown()
            .filter { it.isFile && it.length() > 0 }
            .mapNotNull { file ->
                val inferred = classifyMediaFile(file.name, null) ?: return@mapNotNull null
                SubmitterOutputArtifact(file, inferred.first, inferred.second)
            }
            .sortedBy { it.file.name }
            .toList()
    }

    private fun buildContinuationState(
        resultJson: JsonObject?,
        receiptFile: File,
        snapshotEmitFile: File,
        outputDir: File,
        request: SubmitterRunRequest,
    ): JsonObject {
        val explicit = (resultJson?.get("continuation_state") as? JsonObject)?.toMutableMap() ?: linkedMapOf()
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

        // 若 receipt 或 workflow 产物中包含 API workflow 快照，持久化进 continuationState 以便后续中段接续 --previous-workflow。
        val workflowObj = (resultJson?.get("workflow") as? JsonObject)
            ?: (resultJson?.get("reconstruction") as? JsonObject)?.get("workflow")?.let { p ->
                val path = (p as? JsonPrimitive)?.contentOrNull
                path?.let { File(it) }?.takeIf { it.isFile }?.let { f ->
                    runCatching { Json.parseToJsonElement(f.readText()) as? JsonObject }.getOrNull()
                }
            }
            ?: snapshotEmitFile.takeIf { it.isFile }?.let { f ->
                runCatching { Json.parseToJsonElement(f.readText()) as? JsonObject }.getOrNull()
            }
            ?: outputDir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith("workflow.json") }?.let { f ->
                runCatching { Json.parseToJsonElement(f.readText()) as? JsonObject }.getOrNull()
            }
        if (workflowObj != null && "workflow_snapshot" !in explicit) {
            explicit["workflow_snapshot"] = workflowObj
        }
        (resultJson?.get("motion_segment") as? JsonObject)?.forEach { (k, v) ->
            explicit.putIfAbsent(k, v)
        }
        if (receiptFile.isFile && "clip_index" !in explicit) {
            val nextIdx = (request.inheritedState["parent_segment_index"] as? JsonPrimitive)?.intOrNull?.plus(1) ?: 1
            explicit["clip_index"] = JsonPrimitive(nextIdx)
        }
        return JsonObject(explicit)
    }

    private fun deriveDefaultSlotKey(continuation: JsonObject): String? {
        val latentDir = (continuation["latent_dir"] as? JsonPrimitive)?.contentOrNull ?: return null
        val clipIndex = (continuation["clip_index"] as? JsonPrimitive)?.intOrNull ?: return null
        return "$latentDir::clip_$clipIndex"
    }

    private data class SimpleCmdOut(val exitCode: Int, val stdout: String, val stderr: String) {
        fun combinedError(): String = stderr.trim().ifBlank { stdout.trim() }.takeLast(400)
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
        fun cleanupRunDirectory(continuationState: JsonObject) {
            val path = (continuationState["__run_dir_cleanup"] as? JsonPrimitive)?.contentOrNull ?: return
            runCatching { File(path).deleteRecursively() }
        }

        internal fun classifyMediaFile(fileName: String, declared: InputKind?): Pair<InputKind, String>? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "png" -> (declared ?: InputKind.IMAGE) to "image/png"
                "jpg", "jpeg" -> (declared ?: InputKind.IMAGE) to "image/jpeg"
                "webp" -> (declared ?: InputKind.IMAGE) to "image/webp"
                "gif" -> (declared ?: InputKind.VIDEO) to "image/gif"
                "mp4" -> InputKind.VIDEO to "video/mp4"
                "webm" -> InputKind.VIDEO to "video/webm"
                "mov" -> InputKind.VIDEO to "video/quicktime"
                "mkv" -> InputKind.VIDEO to "video/x-matroska"
                "avi" -> InputKind.VIDEO to "video/x-msvideo"
                "wav" -> InputKind.AUDIO to "audio/wav"
                "mp3" -> InputKind.AUDIO to "audio/mpeg"
                "flac" -> InputKind.AUDIO to "audio/flac"
                "ogg" -> InputKind.AUDIO to "audio/ogg"
                "m4a" -> InputKind.AUDIO to "audio/mp4"
                else -> null
            }
        }
    }
}
