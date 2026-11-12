package com.promenar.infinity.domain.mediagen

import java.math.BigDecimal

/** 生成层使用的不透明标识由上游维护，不解析其内部结构。 */
enum class MediaGroup { IMAGE, VIDEO, AUDIO }
enum class GenerationMode { TEXT, REFERENCE, FIRST_FRAME, FIRST_LAST_FRAME, SPEECH, MUSIC }
enum class InputKind { IMAGE, VIDEO, AUDIO }
enum class InputRole { REFERENCE, FIRST_FRAME, LAST_FRAME, REFERENCE_VOICE }
enum class PromptFormat { H3, SEEDANCE, GENERIC }
enum class CharacterReferenceMode { COMPOSITE, SEPARATE }

data class MediaCapabilities(
    val modes: Set<GenerationMode>,
    val maxImages: Int = 0,
    val maxVideos: Int = 0,
    val maxAudio: Int = 0,
    val nativeAudio: Boolean = false,
    val resolutions: List<String> = emptyList(),
    val minDurationSeconds: Int? = null,
    val maxDurationSeconds: Int? = null,
    val promptFormat: PromptFormat = PromptFormat.GENERIC,
    val characterReferenceMode: CharacterReferenceMode = CharacterReferenceMode.SEPARATE,
    val referenceOrder: List<String> = emptyList(),
) {
    init {
        require(maxImages >= 0 && maxVideos >= 0 && maxAudio >= 0)
        require(minDurationSeconds == null || minDurationSeconds >= 0)
        require(maxDurationSeconds == null || maxDurationSeconds >= 0)
        require(minDurationSeconds == null || maxDurationSeconds == null || minDurationSeconds <= maxDurationSeconds)
    }
}

data class MediaChannel(val id: String, val group: MediaGroup, val capabilities: MediaCapabilities)
data class MediaInput(val location: String, val kind: InputKind, val role: InputRole)
data class GenerationSpecs(
    val resolution: String? = null,
    val durationSeconds: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frames: Int? = null,
    val seed: Long? = null,
)
data class MediaGenerationRequest(
    val channelId: String,
    val mode: GenerationMode,
    val prompt: String,
    val inputs: List<MediaInput>,
    val sourceRef: String,
    val specs: GenerationSpecs = GenerationSpecs(),
    /** 输入与产物所属的会话；输入路径与 outputDir 都是该会话工作区内的相对路径。 */
    val sessionId: String = "",
    /** 产物下载到的工作区相对目录，例如 productions/wugang/S1E01/takes。 */
    val outputDir: String = "",
)

data class RequestValidation(
    val channelMismatch: Boolean,
    val unsupportedMode: Boolean,
    val excessInputs: List<MediaInput>,
    val invalidInputs: List<MediaInput>,
    val specErrors: List<String>,
) {
    val valid: Boolean get() = !channelMismatch && !unsupportedMode && excessInputs.isEmpty() && invalidInputs.isEmpty() && specErrors.isEmpty()
}

fun resolveDefaultChannel(group: MediaGroup, mode: GenerationMode, orderedChannels: List<MediaChannel>): MediaChannel? =
    orderedChannels.firstOrNull { it.group == group && mode in it.capabilities.modes }

fun validateRequest(channel: MediaChannel, request: MediaGenerationRequest): RequestValidation {
    val caps = channel.capabilities
    val counts = mutableMapOf<InputKind, Int>()
    val excess = mutableListOf<MediaInput>()
    val invalid = mutableListOf<MediaInput>()
    request.inputs.forEach { input ->
        if (input.role == InputRole.REFERENCE || input.role == InputRole.REFERENCE_VOICE) {
            val count = counts.getOrDefault(input.kind, 0) + 1
            counts[input.kind] = count
            val max = when (input.kind) { InputKind.IMAGE -> caps.maxImages; InputKind.VIDEO -> caps.maxVideos; InputKind.AUDIO -> caps.maxAudio }
            if (count > max) excess.add(input)
        }
        val validRole = when (input.role) {
            InputRole.FIRST_FRAME, InputRole.LAST_FRAME -> input.kind == InputKind.IMAGE
            InputRole.REFERENCE_VOICE -> input.kind == InputKind.AUDIO
            InputRole.REFERENCE -> true
        }
        val validMode = when (request.mode) {
            GenerationMode.TEXT -> false
            GenerationMode.FIRST_FRAME -> input.role == InputRole.FIRST_FRAME
            GenerationMode.FIRST_LAST_FRAME -> input.role == InputRole.FIRST_FRAME || input.role == InputRole.LAST_FRAME
            GenerationMode.REFERENCE -> input.role == InputRole.REFERENCE || input.role == InputRole.REFERENCE_VOICE
            GenerationMode.SPEECH -> input.role == InputRole.REFERENCE_VOICE
            GenerationMode.MUSIC -> input.role == InputRole.REFERENCE
        }
        if (!validRole || !validMode || input.location.isBlank()) invalid.add(input)
    }
    if (request.mode == GenerationMode.FIRST_FRAME || request.mode == GenerationMode.FIRST_LAST_FRAME) {
        val first = request.inputs.count { it.role == InputRole.FIRST_FRAME }
        val last = request.inputs.count { it.role == InputRole.LAST_FRAME }
        if (first != 1 || last != if (request.mode == GenerationMode.FIRST_LAST_FRAME) 1 else 0) {
            invalid.addAll(request.inputs.filter { it !in invalid })
            if (request.inputs.isEmpty()) invalid.add(MediaInput("", InputKind.IMAGE, InputRole.FIRST_FRAME))
        }
    }
    val specs = buildList {
        request.specs.resolution?.let { if (caps.resolutions.isNotEmpty() && it !in caps.resolutions) add("resolution") }
        request.specs.durationSeconds?.let { if (it <= 0 || caps.minDurationSeconds?.let { min -> it < min } == true || caps.maxDurationSeconds?.let { max -> it > max } == true) add("durationSeconds") }
        if (request.specs.width?.let { it <= 0 } == true) add("width")
        if (request.specs.height?.let { it <= 0 } == true) add("height")
        if (request.specs.frames?.let { it <= 0 } == true) add("frames")
    }
    return RequestValidation(channel.id != request.channelId, request.mode !in caps.modes, excess, invalid, specs)
}

data class Money(val amount: BigDecimal, val currency: String) {
    init { require(amount >= BigDecimal.ZERO && currency.isNotBlank()) }
}
data class UnitPrice(val amount: BigDecimal, val currency: String) {
    init { require(amount >= BigDecimal.ZERO && currency.isNotBlank()) }
    fun cost(units: Long): Money { require(units >= 0); return Money(amount.multiply(BigDecimal.valueOf(units)), currency) }
}
fun costFromUsage(price: UnitPrice?, units: Long?): Money? = when {
    price == null -> null
    units == null -> null
    else -> price.cost(units)
}

fun interface AutoSubmitPolicy {
    suspend fun allowsAutoSubmit(sourceRef: String, estimate: Money): Boolean
}
object RequireApproval : AutoSubmitPolicy {
    override suspend fun allowsAutoSubmit(sourceRef: String, estimate: Money) = false
}

/** 远端任务状态；由各后端把提供商的响应翻译成这几种。 */
sealed interface RemoteStatus {
    data class Running(val progress: Float? = null) : RemoteStatus
    data class Succeeded(val outputs: List<RemoteOutput>, val usage: Map<String, Long> = emptyMap()) : RemoteStatus
    data class Failed(val message: String, val retryable: Boolean = false) : RemoteStatus
}

/** 远端产物：下载地址（ComfyUI 为 /view 查询串）与媒体种类。 */
data class RemoteOutput(val location: String, val kind: InputKind)

/**
 * 一个提供商或主机的生成后端。队列只通过它与外部交互：
 * 调用 submit 前任务已持久化；submit 返回远端任务 ID 后队列立即持久化该 ID。
 */
interface MediaGenerationBackend {
    fun handles(channel: MediaChannel): Boolean
    /** 提交前预估费用；不计费返回 0，未知返回 null。 */
    suspend fun estimate(channel: MediaChannel, request: MediaGenerationRequest): Money?
    suspend fun submit(channel: MediaChannel, request: MediaGenerationRequest): String
    suspend fun poll(channel: MediaChannel, remoteId: String): RemoteStatus
    /** 下载产物到 request.outputDir，返回工作区相对路径。 */
    suspend fun download(channel: MediaChannel, request: MediaGenerationRequest, remoteId: String, outputs: List<RemoteOutput>): List<String>
    /** 按完成时的用量计算实际费用；不计费返回 0，未知返回 null。 */
    fun actualCost(channel: MediaChannel, usage: Map<String, Long>): Money?
}

/** 通道注册表：合并云端媒体模型与 ComfyUI 工作流模板，并按默认模型设置排序。 */
interface MediaChannelRegistry {
    fun observeChannels(): kotlinx.coroutines.flow.Flow<List<MediaChannel>>
    /** 该组的有序通道 id，第一项为默认。 */
    fun observeOrder(group: MediaGroup): kotlinx.coroutines.flow.Flow<List<String>>
    suspend fun setOrder(group: MediaGroup, channelIds: List<String>)
    suspend fun channel(id: String): MediaChannel?
}
