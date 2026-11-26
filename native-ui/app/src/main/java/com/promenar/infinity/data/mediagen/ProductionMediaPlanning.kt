package com.promenar.infinity.data.mediagen

import com.promenar.infinity.data.mediagen.cloud.*
import com.promenar.infinity.data.mediagen.comfy.ComfyConfigurationCodec
import com.promenar.infinity.data.mediagen.comfy.validateFilledComfySpecs
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.production.isActive
import com.promenar.infinity.domain.mediagen.*
import kotlinx.serialization.json.*

/** 模板规格只读取显式声明的槽位；若声明了 fps 则按镜头秒数对齐帧数约束，未声明时回退锁定工作流默认帧数。 */
internal fun comfyPlanningSpecs(template: ComfyTemplate, shot: Shot? = null, framesOverride: Int? = null): GenerationSpecs {
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
    val hasFramesSlot = template.slots.any { it.kind == SlotKind.FRAMES }
    val frames = when {
        framesOverride != null -> {
            require(hasFramesSlot) { "模板未声明 FRAMES 槽位，不支持指定帧数" }
            require(framesOverride >= template.frameBase && (framesOverride - template.frameBase) % template.frameStep == 0 &&
                (template.maxFrames == null || framesOverride <= template.maxFrames)) {
                "帧数必须满足 ${template.frameBase}+${template.frameStep}n 与上限 ${template.maxFrames ?: "不限"}"
            }
            framesOverride
        }
        shot != null && hasFramesSlot && template.fps != null -> template.snapFramesForDuration(shot.durationSeconds)
        else -> positive(SlotKind.FRAMES)?.also { require(it >= template.frameBase && (it - template.frameBase) % template.frameStep == 0 && (template.maxFrames == null || it <= template.maxFrames)) }
    }
    fun aligned(kind: SlotKind) = positive(kind)?.also { require(it <= Int.MAX_VALUE - 31) }?.let { ((it + 31) / 32) * 32 }
    val hasWidthSlot = template.slots.any { it.kind == SlotKind.WIDTH }
    val hasHeightSlot = template.slots.any { it.kind == SlotKind.HEIGHT }
    val customWidth = shot?.specWidth?.takeIf { hasWidthSlot && hasHeightSlot && shot.specHeight != null }
    val customHeight = shot?.specHeight?.takeIf { hasWidthSlot && hasHeightSlot && shot.specWidth != null }
    val durationExact = if (shot != null && template.slots.any { it.kind == SlotKind.DURATION }) shot.durationSeconds else null
    val specs = GenerationSpecs(
        width = customWidth ?: aligned(SlotKind.WIDTH),
        height = customHeight ?: aligned(SlotKind.HEIGHT),
        frames = frames,
        seed = value(SlotKind.SEED),
        durationSecondsExact = durationExact,
    )
    require((specs.width == null) == (specs.height == null))
    return specs
}

internal fun cloudPlanningSpecs(model: CloudMediaModel, shot: Shot): GenerationSpecs {
    val fixed = model.settings.api == CloudMediaApi.MINIMAX_VIDEO_V1 && model.modelId.equals("S2V-01", true)
    return GenerationSpecs(
        resolution = if (fixed) null else shot.specResolution?.takeIf(String::isNotBlank) ?: model.settings.options.defaultResolution,
        durationSeconds = if (fixed) null else shot.durationSeconds.takeIf { it % 1.0 == 0.0 && it <= Int.MAX_VALUE }?.toInt(),
    )
}

/** 预览复用纯协议校验；不执行 HTTP、上传、计费或凭据读取。 */
internal fun validateCloudShotPlan(model: CloudMediaModel, channel: MediaChannel, request: MediaGenerationRequest): List<String> = try {
    val effective = model.requestWithDefaults(channel, request)
    require(effective.inputs.none { it.kind == InputKind.VIDEO && !it.location.startsWith("https://") && !it.location.startsWith("http://") && !it.location.startsWith("asset://") }) { "云端视频参考需要已上传的素材" }
    when (val api = model.settings.api) {
        CloudMediaApi.MINIMAX_SPEECH -> miniMaxSpeechBody(model.modelId, effective, model.settings.options)
        CloudMediaApi.GEMINI_TTS -> geminiTtsBody(effective, model.settings.options)
        CloudMediaApi.OPENAI_SPEECH -> openAiSpeechBody(model.modelId, effective.prompt, model.settings.options.voiceId.ifBlank { "alloy" }, model.settings.options.audioFormat)
        CloudMediaApi.GEMINI_LYRIA -> extractLyriaInlineImages(effective)
        CloudMediaApi.MINIMAX_VIDEO_V1, CloudMediaApi.MINIMAX_VIDEO_V2 -> miniMaxVideoBody(model.modelId, effective, api)
        CloudMediaApi.SEEDANCE -> require(effective.specs.durationSeconds != null) { "Seedance 通道需要整数时长" }
        null -> error("无媒体后端")
    }
    emptyList()
} catch (e: IllegalArgumentException) {
    if (model.settings.api in setOf(CloudMediaApi.MINIMAX_SPEECH, CloudMediaApi.GEMINI_TTS, CloudMediaApi.OPENAI_SPEECH) && request.inputs.isNotEmpty()) {
        listOf("云端语音通道使用预置音色，不能传入角色参考音频")
    } else listOf(e.message?.takeIf { it.isNotBlank() } ?: "通道不支持所选输入或生成规格")
} catch (_: Exception) {
    if (model.settings.api in setOf(CloudMediaApi.MINIMAX_SPEECH, CloudMediaApi.GEMINI_TTS, CloudMediaApi.OPENAI_SPEECH) && request.inputs.isNotEmpty()) {
        listOf("云端语音通道使用预置音色，不能传入角色参考音频")
    } else listOf("通道不支持所选输入或生成规格")
}

internal fun cloudConfigurationFingerprint(model: CloudMediaModel): String = productionConfigurationHash(buildJsonObject {
    put("provider", model.providerId); put("model", model.modelId); put("base", model.baseUrl)
    put("settings", Json.encodeToJsonElement(MediaModelSettings.serializer(), model.settings))
}.toString())
internal fun comfyConfigurationFingerprint(template: ComfyTemplate, endpoint: String): String = productionConfigurationHash(endpoint + "\n" + ComfyConfigurationCodec.encodeTemplate(template))
internal fun productionConfigurationHash(value: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun comfyExpectedOutputCount(template: ComfyTemplate): Int = template.slots.filter { it.kind == SlotKind.OUTPUT }.sumOf { it.capacity }.coerceAtLeast(1)

internal fun validateComfyShotPlan(template: ComfyTemplate, request: MediaGenerationRequest): List<String> = try {
    require(template.locked) { "模板尚未锁定" }
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
    require(request.specs.durationSeconds == null || request.specs.frames != null || template.slots.any { it.kind == SlotKind.DURATION }) {
        "ComfyUI 时长需要 DURATION 槽位或显式指定 frames"
    }
    require(request.specs.durationSecondsExact == null || template.slots.any { it.kind == SlotKind.DURATION }) {
        "指定小数秒数需要 DURATION 槽位"
    }
    validateFilledComfySpecs(template, fillComfyWorkflow(template, values, request.params))
    emptyList()
} catch (e: IllegalArgumentException) {
    listOf(e.message?.takeIf { it.isNotBlank() } ?: "模板尚未锁定或输入与工作流槽位不兼容")
} catch (_: Exception) {
    listOf("模板尚未锁定或输入与工作流槽位不兼容")
}

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
