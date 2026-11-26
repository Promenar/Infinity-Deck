package com.promenar.infinity.data.production

import com.promenar.infinity.data.mediagen.comfy.ComfyErrorDiagnostics
import com.promenar.infinity.data.mediagen.comfy.ComfyWorkflowExecutionRequest
import com.promenar.infinity.data.mediagen.comfy.ComfyWorkflowExecutionResult
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/** 只读取已保存的模板预检结果；工具提交不触发探测、估价或审批。 */
data class ProductionTemplate(val template: ComfyTemplate, val ready: Boolean)

sealed interface ProductionDeriveResult {
    data class Submitted(val task: MediaTask) : ProductionDeriveResult
    data class Workflow(
        val runId: String,
        val takeId: String?,
        val outputPaths: List<String>,
        val status: RunState?,
        val summary: String,
    ) : ProductionDeriveResult
}

interface ProductionProcessing {
    suspend fun operations(sessionId: String, episodeId: String, shotCode: String): ShotOperations
    suspend fun derive(sessionId: String, episodeId: String, shotCode: String, takeId: String, operationId: String, params: Map<String, JsonElement> = emptyMap()): ProductionDeriveResult
    suspend fun score(
        sessionId: String,
        assetId: String,
        templateId: String? = null,
        prompt: String? = null,
        durationSeconds: Double? = null,
        versions: Int = 4,
        channelId: String? = null,
    ): List<MediaTask>
}
class DefaultProductionProcessing(
    private val store: ProductionWorkStore, private val registry: MediaChannelRegistry, private val queue: MediaGenerationQueue,
    private val templates: suspend () -> List<ProductionTemplate>,
    private val specsFor: suspend (MediaChannel, Shot) -> GenerationSpecs,
    private val exists: suspend (String, String) -> Boolean,
    private val seed: () -> Long = { kotlin.random.Random.nextLong(0, Long.MAX_VALUE) },
    private val workflows: suspend () -> List<ComfyWorkflowState> = { emptyList() },
    private val runs: ProductionRunService? = null,
    private val executeWorkflow: (suspend (ComfyWorkflowExecutionRequest) -> ComfyWorkflowExecutionResult)? = null,
) : ProductionProcessing {
    private val mutex = ProductionSubmissionMutex
    private suspend fun load(session: String) = requireNotNull(store.load(session)) { "当前会话没有制作项目。" }
    override suspend fun operations(sessionId: String, episodeId: String, shotCode: String): ShotOperations = withContext(Dispatchers.IO) {
        val p = load(sessionId).production
        val shot = p.requiredShot(episodeId, shotCode)
        val channels = registry.observeChannels().first()
        val projectDefault = p.defaultVideoChannelId?.takeIf { it.isNotBlank() }
        val selected = shot.channelId?.let { id -> channels.firstOrNull { it.id == id } }
            ?: projectDefault?.let { id -> channels.firstOrNull { it.id == id && shot.mode.toGenerationMode() in it.capabilities.modes } }
            ?: if (shot.channelId == null) resolveDefaultChannel(MediaGroup.VIDEO, shot.mode.toGenerationMode(), channels) else null
        resolveShotOperations(selected, channels, readyShotDerivations(templates(), workflows()))
    }
    override suspend fun derive(
        sessionId: String,
        episodeId: String,
        shotCode: String,
        takeId: String,
        operationId: String,
        params: Map<String, JsonElement>,
    ): ProductionDeriveResult = withContext(Dispatchers.IO) {
        val workflowRun = mutex.withLock {
            val snapshot = load(sessionId)
            val p = snapshot.production
            val shot = p.requiredShot(episodeId, shotCode)
            val states = templates()
            val available = readyShotDerivations(states, workflows())
            val operation = available.firstOrNull { it.id == operationId }
                ?: throw IllegalArgumentException("处理不可用：$operationId；当前可用：${available.joinToString { "${it.id}（${it.label}）" }.ifEmpty { "无" }}")
            val source = requireNotNull(shot.takes.firstOrNull { it.id == takeId }) { "来源候选不存在。" }
            require(exists(sessionId, source.path)) { "来源候选文件不可读。" }
            ProductionRules.generationRejection(p, episodeId, shotCode)?.let { throw IllegalArgumentException(it) }
            val occupied = occupiedShotCandidates(p, episodeId, shotCode, queue) { task ->
                states.firstOrNull { it.template.channelId == task.request.channelId }?.template?.slots
                    ?.filter { it.kind == SlotKind.OUTPUT }?.sumOf { it.capacity } ?: 1
            }
            val outputDir = shotTakesDirectory(p, episodeId)
            val sourceRef = ProductionSourceRef(p.id, episodeId, shotCode)
            when (operation) {
                is ShotDeriveOperation.Template -> {
                    val template = operation.template
                    require(template.group == MediaGroup.VIDEO) {
                        "该派生模板无法产出镜头视频；可用视频处理：${available.filterIsInstance<ShotDeriveOperation.Template>()
                            .filter { it.template.group == MediaGroup.VIDEO }.joinToString { it.id }}"
                    }
                    val outputs = template.slots.filter { it.kind == SlotKind.OUTPUT }.sumOf { it.capacity }.coerceAtLeast(1)
                    require(occupied + outputs <= 30) { "候选容量不足。" }
                    val channel = template.channel()
                    val overrides = params.mapValues { (key, value) ->
                        (value as? JsonPrimitive)?.takeUnless { it == JsonNull }
                            ?: throw IllegalArgumentException("params.$key 必须是字符串、数值或布尔值。")
                    }
                    val request = MediaGenerationRequest(channel.id, GenerationMode.REFERENCE, shot.prompt.ifBlank { operation.label },
                        listOf(MediaInput(source.path, InputKind.VIDEO, com.promenar.infinity.domain.mediagen.InputRole.REFERENCE)),
                        sourceRef.encode(), specsFor(channel, shot), sessionId, outputDir,
                        template.resolveParams(overrides), ProductionMediaContext(template.fingerprint(), source.id, operation.label, operation.id))
                    require(validateRequest(channel, request).valid) { "模板能力不支持来源视频，请核对 REFERENCE 模式。" }
                    require(load(sessionId).let { it.production.id == p.id && it.revision == snapshot.revision }) { "制作项目已变化，请重试。" }
                    return@withContext ProductionDeriveResult.Submitted(queue.submit(request))
                }
                is ShotDeriveOperation.Workflow -> {
                    require(occupied < 30) { "候选容量不足。" }
                    val workflow = operation.workflow
                    operation.unavailableReason?.let { throw IllegalArgumentException(it) }
                    val videoInput = requireNotNull(operation.videoInput)
                    val fields = operation.params.associateBy { it.key }
                    params.forEach { (key, value) ->
                        val field = requireNotNull(fields[key]) { "params.$key 需要使用工作流声明的非视频 CONTENT 字段。" }
                        field.validateValue(value)
                    }
                    val execute = requireNotNull(executeWorkflow) { "通用工作流执行器不可用。" }
                    val service = requireNotNull(runs) { "制作任务登记服务不可用。" }
                    val request = ComfyWorkflowExecutionRequest(
                        sessionId = sessionId, workflowId = workflow.workflowId,
                        expectedWorkflow = workflow, requirePreflightPass = true,
                        contentInputs = params + (videoInput.key to JsonPrimitive(source.path)),
                        outputDir = outputDir, shotRef = "$episodeId/$shotCode",
                    )
                    withContext(NonCancellable) {
                        val run = service.start(sessionId, RunTarget(episodeId, shotCode), workflow.name, steps = 100, expected = snapshot)
                        run.reserveShotCandidate(p.id)
                        WorkflowDerivation(execute, service, request, p.id, run.id)
                    }
                }
            }
        }
        runWorkflowDerivation(workflowRun, takeId, params)
    }

    private data class WorkflowDerivation(
        val execute: suspend (ComfyWorkflowExecutionRequest) -> ComfyWorkflowExecutionResult,
        val runs: ProductionRunService,
        val request: ComfyWorkflowExecutionRequest,
        val productionId: String,
        val runId: String,
    )

    private suspend fun runWorkflowDerivation(
        work: WorkflowDerivation,
        sourceTakeId: String,
        params: Map<String, JsonElement>,
    ): ProductionDeriveResult.Workflow {
        val sessionId = work.request.sessionId
        var completedSteps = 0
        var outputPaths = emptyList<String>()
        var executionSummary: String? = null
        suspend fun fail(reason: String) = work.runs.reportWorkflowFailure(sessionId, work.productionId, work.runId, reason)
        try {
            val result = work.execute(work.request.copy(onProgress = { pct, message ->
                val steps = pct?.takeIf { it.isFinite() }?.let { (it * 100f).roundToInt().coerceIn(0, 99) } ?: completedSteps
                val next = maxOf(completedSteps, steps)
                try {
                    retryProductionRunRevision {
                        work.runs.progress(sessionId, work.runId, next, message.trim().take(1000).ifBlank { "执行中" }, work.productionId)
                    }
                    completedSteps = next
                } catch (_: IllegalArgumentException) {
                    // 进度冲突不阻断执行或覆盖用户已关闭的登记。
                }
            }))
            outputPaths = result.savedMedia.map { it.path }
            executionSummary = result.summary
            val video = requireNotNull(result.savedMedia.firstOrNull { it.kind == MediaKind.VIDEO }) { "工作流未产出可登记的镜头视频。" }
            val run = retryProductionRunRevision {
                work.runs.finish(
                    sessionId, work.runId, video.path, sourceTakeId, result.workflow.name, params,
                    serviceName = "ComfyUI（${result.workflow.name}）",
                    expectedProductionId = work.productionId,
                )
            }
            return ProductionDeriveResult.Workflow(run.id, run.resultTakeId, outputPaths, run.state, result.summary)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { fail("已取消") }
            throw cancelled
        } catch (failure: Exception) {
            val reason = ComfyErrorDiagnostics.redactSensitive(failure.message.orEmpty()).trim().take(1000)
                .ifBlank { "ComfyUI 工作流派生失败。" }
            val actual = fail(reason)
            val summary = when (actual?.state) {
                RunState.COMPLETED -> executionSummary ?: "工作流已完成，产物已登记。"
                RunState.CANCELED -> "制作任务已取消。"
                null -> "制作任务登记状态无法确认。$reason"
                else -> actual?.failure ?: reason
            }
            return ProductionDeriveResult.Workflow(work.runId, actual?.resultTakeId, outputPaths, actual?.state, summary)
        } finally {
            releaseShotCandidate(work.runId)
        }
    }

    override suspend fun score(
        sessionId: String,
        assetId: String,
        templateId: String?,
        prompt: String?,
        durationSeconds: Double?,
        versions: Int,
        channelId: String?,
    ): List<MediaTask> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = load(sessionId); val p = snapshot.production
            val asset = requireNotNull(p.assets.firstOrNull { it.id == assetId && it.kind == AssetKind.SOUND }) { "需要存在的声音资产。" }
            require(versions in 1..30) { "versions 需要 1～30。" }
            val readyTemplates = templates().filter {
                it.ready && it.template.role == TemplateRole.GENERATE && it.template.group == MediaGroup.MUSIC &&
                    GenerationMode.MUSIC in it.template.channel().capabilities.modes &&
                    it.template.slots.any { s -> s.kind == SlotKind.DURATION } &&
                    it.template.slots.any { s -> s.kind == SlotKind.SEED } &&
                    it.template.slots.filter { s -> s.kind == SlotKind.OUTPUT }.let { outputs ->
                        outputs.size == 1 && outputs.single().capacity == 1 && (outputs.single().outputKind == InputKind.AUDIO || outputs.single().input == "audio")
                    }
            }
            val channels = registry.observeChannels().first()
            val musicChannels = channels.filter { it.group == MediaGroup.MUSIC && GenerationMode.MUSIC in it.capabilities.modes }
            val readyTemplateByChannel = readyTemplates.associateBy { it.template.channelId }
            val availableChannels = musicChannels.filter { ch ->
                if (ch.id.startsWith("comfy:")) ch.id in readyTemplateByChannel else true
            }

            val requestedTarget = channelId?.takeIf { it.isNotBlank() } ?: templateId?.takeIf { it.isNotBlank() }
            val selectedTemplate: ComfyTemplate?
            val selectedChannel: MediaChannel
            if (requestedTarget != null) {
                val matchedTemplate = readyTemplates.firstOrNull { it.template.id == requestedTarget || it.template.channelId == requestedTarget }?.template
                if (matchedTemplate != null) {
                    selectedTemplate = matchedTemplate
                    selectedChannel = matchedTemplate.channel()
                } else {
                    val matchedChannel = availableChannels.firstOrNull { it.id == requestedTarget }
                        ?: throw IllegalArgumentException("配乐通道不可用：$requestedTarget；当前可用：${availableChannels.joinToString { it.id }.ifEmpty { "无（请启用 Lyria 等云端音乐模型或预检通过的 MUSIC 配乐模板）" }}")
                    selectedTemplate = null
                    selectedChannel = matchedChannel
                }
            } else {
                selectedChannel = availableChannels.firstOrNull()
                    ?: readyTemplates.firstOrNull()?.template?.channel()
                    ?: throw IllegalArgumentException("配乐通道不可用；当前可用：无（请启用 Lyria 等云端音乐模型或预检通过的 MUSIC 配乐模板）")
                selectedTemplate = readyTemplateByChannel[selectedChannel.id]?.template
            }

            val latest = p.cuts.maxByOrNull { it.createdAt }
            val seconds = durationSeconds ?: latest?.let { it.export?.durationSeconds ?: it.clips.sumOf { clip -> clip.seconds } }
                ?: if (selectedTemplate == null) 30.0 else throw IllegalArgumentException("尚无剪辑版本，请提供 duration_seconds。")
            require(seconds.isFinite() && seconds > 0 && seconds <= Int.MAX_VALUE) { "配乐时长必须是有限正数。" }
            val pending = queue.observeTasks().first().filter { ProductionAssetSourceRef.parse(it.request.sourceRef) == ProductionAssetSourceRef(p.id, asset.id) && it.state !in setOf(MediaTaskState.FAILED, MediaTaskState.CANCELED) }
                .sumOf { task -> if (task.state == MediaTaskState.COMPLETED) task.outputPaths.count { path -> asset.versions.none { it.taskId == task.id && it.path == path } } else 1 }
            require(asset.versions.size + pending + versions <= 30) { "声音资产版本容量不足。" }
            val effectivePrompt = prompt?.takeIf { it.isNotBlank() } ?: asset.description?.takeIf { it.isNotBlank() } ?: asset.name
            val baseSeed = seed().and(Long.MAX_VALUE)
            val requests = List(versions) { index ->
                if (selectedTemplate != null) {
                    val actualSeed = (baseSeed + index).and(Long.MAX_VALUE)
                    MediaGenerationRequest(
                        selectedTemplate.channelId,
                        GenerationMode.MUSIC,
                        effectivePrompt,
                        emptyList(),
                        ProductionAssetSourceRef(p.id, asset.id).encode(),
                        GenerationSpecs(seed = actualSeed, durationSecondsExact = seconds),
                        sessionId,
                        "${p.rootPath}/assets/${asset.id}",
                        selectedTemplate.resolveParams(emptyMap()),
                        ProductionMediaContext(templateFingerprint = selectedTemplate.fingerprint(), templateId = selectedTemplate.id, assetId = asset.id),
                    )
                } else {
                    val clampedDuration = seconds.toInt().coerceAtLeast(1)
                    val specs = if (selectedChannel.capabilities.minDurationSeconds != null || selectedChannel.capabilities.maxDurationSeconds != null) {
                        GenerationSpecs(durationSeconds = clampedDuration)
                    } else {
                        GenerationSpecs(durationSecondsExact = seconds)
                    }
                    MediaGenerationRequest(
                        selectedChannel.id,
                        GenerationMode.MUSIC,
                        effectivePrompt,
                        emptyList(),
                        ProductionAssetSourceRef(p.id, asset.id).encode(),
                        specs,
                        sessionId,
                        "${p.rootPath}/assets/${asset.id}",
                        emptyMap(),
                        ProductionMediaContext(assetId = asset.id),
                    )
                }
            }
            requests.forEach { require(validateRequest(selectedChannel, it).valid) { "通道不支持请求的配乐时长或规格。" } }
            require(load(sessionId).let { it.production.id == p.id && it.revision == snapshot.revision }) { "制作项目已变化，请重试。" }
            // 每次 submit 都持久化；中断后可由任务查询和登记器恢复已提交部分。
            val submitted = mutableListOf<MediaTask>()
            for (request in requests) {
                try { submitted += queue.submit(request) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { if (submitted.isEmpty()) throw failure else break }
            }
            submitted
        }
    }
}
