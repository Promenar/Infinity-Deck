package com.promenar.infinity.data.mediagen

import com.promenar.infinity.data.mediagen.cloud.*
import com.promenar.infinity.data.mediagen.comfy.ComfyConfigurationCodec
import com.promenar.infinity.data.mediagen.comfy.validateFilledComfySpecs
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.production.isActive
import com.promenar.infinity.domain.mediagen.*
import kotlinx.serialization.json.*

/** 模板规格只读取显式声明的槽位；未声明的时长由锁定工作流决定。 */
internal fun comfyPlanningSpecs(template: ComfyTemplate): GenerationSpecs {
    fun value(kind: SlotKind): Long? {
        val slots = template.slots.filter { it.kind == kind }
        if (slots.isEmpty()) return null
        val values = slots.map { slot ->
            require(slot.capacity == 1)
            (((template.workflow[slot.nodeId] as? JsonObject)?.get("inputs") as? JsonObject)?.get(slot.input) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                ?: throw IllegalArgumentException("模板数值槽位需要显式整数")
        }
        require(values.distinct().size == 1)
        return values.singleOrNull() ?: values.first()
    }
    fun positive(kind: SlotKind): Int? = value(kind)?.also { require(it in 1..Int.MAX_VALUE.toLong()) }?.toInt()
    val frames = positive(SlotKind.FRAMES)?.also { require(it >= template.frameBase && (it - template.frameBase) % template.frameStep == 0 && (template.maxFrames == null || it <= template.maxFrames)) }
    fun aligned(kind: SlotKind) = positive(kind)?.also { require(it <= Int.MAX_VALUE - 31) }?.let { ((it + 31) / 32) * 32 }
    val specs = GenerationSpecs(width = aligned(SlotKind.WIDTH), height = aligned(SlotKind.HEIGHT), frames = frames, seed = value(SlotKind.SEED))
    require((specs.width == null) == (specs.height == null))
    return specs
}

internal fun cloudPlanningSpecs(model: CloudMediaModel, shot: Shot): GenerationSpecs {
    val fixed = model.settings.api == CloudMediaApi.MINIMAX_VIDEO_V1 && model.modelId.equals("S2V-01", true)
    return GenerationSpecs(resolution = if (fixed) null else model.settings.options.defaultResolution,
        durationSeconds = if (fixed) null else shot.durationSeconds.takeIf { it % 1.0 == 0.0 && it <= Int.MAX_VALUE }?.toInt())
}

/** 预览复用纯协议校验；不执行 HTTP、上传、计费或凭据读取。 */
internal fun validateCloudShotPlan(model: CloudMediaModel, channel: MediaChannel, request: MediaGenerationRequest): List<String> = try {
    val effective = model.requestWithDefaults(channel, request)
    require(effective.inputs.none { it.kind == InputKind.VIDEO && !it.location.startsWith("https://") && !it.location.startsWith("http://") && !it.location.startsWith("asset://") }) { "云端视频参考需要已上传的素材" }
    when (val api = model.settings.api) {
        CloudMediaApi.MINIMAX_SPEECH -> miniMaxSpeechBody(model.modelId, effective, model.settings.options)
        CloudMediaApi.MINIMAX_VIDEO_V1, CloudMediaApi.MINIMAX_VIDEO_V2 -> miniMaxVideoBody(model.modelId, effective, api)
        CloudMediaApi.SEEDANCE -> require(effective.specs.durationSeconds != null)
        null -> error("无媒体后端")
    }
    emptyList()
} catch (_: Exception) {
    if (model.settings.api == CloudMediaApi.MINIMAX_SPEECH && request.inputs.isNotEmpty()) listOf("语音通道仅接受已配置音色 ID，不能使用角色参考音频")
    else listOf("通道不支持所选输入或生成规格")
}

internal fun cloudConfigurationFingerprint(model: CloudMediaModel): String = productionConfigurationHash(buildJsonObject {
    put("provider", model.providerId); put("model", model.modelId); put("base", model.baseUrl)
    put("settings", Json.encodeToJsonElement(MediaModelSettings.serializer(), model.settings))
}.toString())
internal fun comfyConfigurationFingerprint(template: ComfyTemplate, endpoint: String): String = productionConfigurationHash(endpoint + "\n" + ComfyConfigurationCodec.encodeTemplate(template))
internal fun productionConfigurationHash(value: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun comfyExpectedOutputCount(template: ComfyTemplate): Int = template.slots.filter { it.kind == SlotKind.OUTPUT }.sumOf { it.capacity }.coerceAtLeast(1)

internal fun validateComfyShotPlan(template: ComfyTemplate, request: MediaGenerationRequest): List<String> = try {
    require(template.locked)
    val values = mutableMapOf<SlotKind, List<String>>()
    if (template.role != TemplateRole.DERIVE || template.slots.any { it.kind == SlotKind.PROMPT }) values[SlotKind.PROMPT] = listOf(request.prompt)
    request.inputs.forEach { input ->
        val kind = when {
            input.kind == InputKind.VIDEO -> SlotKind.INPUT_VIDEO
            else -> when (input.role) {
            InputRole.FIRST_FRAME -> SlotKind.FIRST_FRAME
            InputRole.LAST_FRAME -> SlotKind.LAST_FRAME
            InputRole.REFERENCE_VOICE -> SlotKind.REFERENCE_AUDIO
            InputRole.REFERENCE -> if (input.kind == InputKind.IMAGE) SlotKind.REFERENCE_IMAGES else SlotKind.REFERENCE_AUDIO
        }
        }
        values[kind] = values[kind].orEmpty() + input.location
    }
    request.specs.frames?.let { values[SlotKind.FRAMES] = listOf(it.toString()) }
    request.specs.seed?.let { values[SlotKind.SEED] = listOf(it.toString()) }
    request.specs.width?.let { values[SlotKind.WIDTH] = listOf(it.toString()) }
    request.specs.height?.let { values[SlotKind.HEIGHT] = listOf(it.toString()) }
    if (template.slots.any { it.kind == SlotKind.DURATION }) (request.specs.durationSecondsExact?.toString() ?: request.specs.durationSeconds?.toString())?.let { values[SlotKind.DURATION] = listOf(it) }
    require(request.specs.durationSeconds == null || request.specs.frames != null || template.slots.any { it.kind == SlotKind.DURATION })
    require(request.specs.durationSecondsExact == null || template.slots.any { it.kind == SlotKind.DURATION })
    validateFilledComfySpecs(template, fillComfyWorkflow(template, values, request.params))
    emptyList()
} catch (_: Exception) { listOf("模板尚未锁定或输入与工作流槽位不兼容") }

internal suspend fun productionRequestAvailable(request: MediaGenerationRequest, store: com.promenar.infinity.data.production.ShotGenerationStore): Boolean {
    val asset = com.promenar.infinity.data.production.ProductionAssetSourceRef.parse(request.sourceRef)
    if (asset != null) return store.load(request.sessionId)?.production?.let { project -> project.id == asset.productionId && project.assets.any { it.id == asset.assetId } } == true
    val ref = com.promenar.infinity.data.production.ProductionSourceRef.parse(request.sourceRef) ?: return true
    return store.load(request.sessionId)?.production?.let { project ->
        val context = request.productionContext
        val chainAvailable = context?.chainId?.let { id ->
            project.seasons.flatMap { it.episodes }.firstOrNull { it.id == ref.episodeId }?.scenes?.flatMap { it.shots }?.firstOrNull { it.code == ref.shotCode }?.chains?.any {
                it.id == id && it.isActive() && it.attempt == context.attempt && it.templateFingerprint == context.templateFingerprint
            } == true
        } ?: true
        project.id == ref.productionId && chainAvailable && com.promenar.infinity.data.production.ProductionRules.generationRejection(project, ref.episodeId, ref.shotCode) == null
    } == true
}
