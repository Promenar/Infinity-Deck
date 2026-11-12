package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive

/** 只读取已保存的模板预检结果；工具提交不触发探测、估价或审批。 */
data class ProductionTemplate(val template: ComfyTemplate, val ready: Boolean)
interface ProductionProcessing {
    suspend fun operations(sessionId: String, episodeId: String, shotCode: String): ShotOperations
    suspend fun derive(sessionId: String, episodeId: String, shotCode: String, takeId: String, operationId: String, params: Map<String, JsonPrimitive> = emptyMap()): MediaTask
    suspend fun score(sessionId: String, assetId: String, templateId: String? = null, prompt: String? = null, durationSeconds: Double? = null, versions: Int = 4): List<MediaTask>
}
class DefaultProductionProcessing(
    private val store: ProductionWorkStore, private val registry: MediaChannelRegistry, private val queue: MediaGenerationQueue,
    private val templates: suspend () -> List<ProductionTemplate>,
    private val specsFor: suspend (MediaChannel, Shot) -> GenerationSpecs,
    private val exists: suspend (String, String) -> Boolean,
    private val seed: () -> Long = { kotlin.random.Random.nextLong(0, Long.MAX_VALUE) },
) : ProductionProcessing {
    private val mutex = ProductionSubmissionMutex
    private suspend fun load(session: String) = requireNotNull(store.load(session)) { "当前会话没有制作项目。" }
    override suspend fun operations(sessionId: String, episodeId: String, shotCode: String): ShotOperations = withContext(Dispatchers.IO) {
        val shot = load(sessionId).production.requiredShot(episodeId, shotCode)
        val channels = registry.observeChannels().first()
        val selected = shot.channelId?.let { id -> channels.firstOrNull { it.id == id } } ?: if (shot.channelId == null) resolveDefaultChannel(MediaGroup.VIDEO, shot.mode.toGenerationMode(), channels) else null
        val states = templates()
        resolveShotOperations(selected, channels, states.map { it.template }) { t -> states.any { it.template == t && it.ready } }
    }
    override suspend fun derive(sessionId: String, episodeId: String, shotCode: String, takeId: String, operationId: String, params: Map<String, JsonPrimitive>): MediaTask = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = load(sessionId); val p = snapshot.production
            val shot = p.requiredShot(episodeId, shotCode)
            val available = templates().filter { it.ready && it.template.role == TemplateRole.DERIVE }
            val template = available.firstOrNull { it.template.id == operationId }?.template
                ?: throw IllegalArgumentException("处理不可用：$operationId；当前可用：${available.joinToString { it.template.id + "（" + it.template.operation?.label + "）" }.ifEmpty { "无" }}")
            require(template.group == MediaGroup.VIDEO) { "该派生模板无法产出镜头视频；可用视频处理：${available.filter { it.template.group == MediaGroup.VIDEO }.joinToString { it.template.id }}" }
            val source = requireNotNull(shot.takes.firstOrNull { it.id == takeId }) { "来源候选不存在。" }
            require(exists(sessionId, source.path)) { "来源候选文件不可读。" }
            ProductionRules.generationRejection(p, episodeId, shotCode)?.let { throw IllegalArgumentException(it) }
            val states = templates().map { it.template }
            val occupied = occupiedShotCandidates(p, episodeId, shotCode, queue) { task -> states.firstOrNull { it.channelId == task.request.channelId }?.slots?.filter { it.kind == SlotKind.OUTPUT }?.sumOf { it.capacity } ?: 1 }
            require(occupied + template.slots.filter { it.kind == SlotKind.OUTPUT }.sumOf { it.capacity }.coerceAtLeast(1) <= 30) { "候选容量不足。" }
            val channel = template.channel()
            val request = MediaGenerationRequest(channel.id, GenerationMode.REFERENCE, shot.prompt.ifBlank { requireNotNull(template.operation).label },
                listOf(MediaInput(source.path, InputKind.VIDEO, com.promenar.infinity.domain.mediagen.InputRole.REFERENCE)),
                ProductionSourceRef(p.id, episodeId, shotCode).encode(), specsFor(channel, shot), sessionId, "${p.rootPath}/$episodeId/takes",
                template.resolveParams(params), ProductionMediaContext(template.fingerprint(), source.id, requireNotNull(template.operation).label, template.id))
            require(validateRequest(channel, request).valid) { "模板能力不支持来源视频，请核对 REFERENCE 模式。" }
            require(load(sessionId).let { it.production.id == p.id && it.revision == snapshot.revision }) { "制作项目已变化，请重试。" }
            queue.submit(request)
        }
    }
    override suspend fun score(sessionId: String, assetId: String, templateId: String?, prompt: String?, durationSeconds: Double?, versions: Int): List<MediaTask> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = load(sessionId); val p = snapshot.production
            val asset = requireNotNull(p.assets.firstOrNull { it.id == assetId && it.kind == AssetKind.SOUND }) { "需要存在的声音资产。" }
            require(versions in 1..30) { "versions 需要 1～30。" }
            val available = templates().filter { it.ready && it.template.role == TemplateRole.GENERATE && it.template.group == MediaGroup.AUDIO && GenerationMode.MUSIC in it.template.channel().capabilities.modes && it.template.slots.any { s -> s.kind == SlotKind.DURATION } && it.template.slots.any { s -> s.kind == SlotKind.SEED } && it.template.slots.filter { s -> s.kind == SlotKind.OUTPUT }.let { outputs -> outputs.size == 1 && outputs.single().capacity == 1 && (outputs.single().outputKind == InputKind.AUDIO || outputs.single().input == "audio") } }
            val ordered = registry.observeChannels().first().map { it.id }
            val template = (if (templateId != null) available.firstOrNull { it.template.id == templateId } else available.minByOrNull { ordered.indexOf(it.template.channelId).let { index -> if (index < 0) Int.MAX_VALUE else index } })?.template
                ?: throw IllegalArgumentException("配乐模板不可用；当前可用：${available.joinToString { it.template.id }.ifEmpty { "无（需要 MUSIC、DURATION 与 SEED 槽位）" }}")
            val latest = p.cuts.maxByOrNull { it.createdAt }
            val seconds = durationSeconds ?: latest?.let { it.export?.durationSeconds ?: it.clips.sumOf { clip -> clip.seconds } }
                ?: throw IllegalArgumentException("尚无剪辑版本，请提供 duration_seconds。")
            require(seconds.isFinite() && seconds > 0 && seconds <= Int.MAX_VALUE) { "配乐时长必须是有限正数。" }
            val pending = queue.observeTasks().first().filter { ProductionAssetSourceRef.parse(it.request.sourceRef) == ProductionAssetSourceRef(p.id, asset.id) && it.state !in setOf(MediaTaskState.FAILED, MediaTaskState.CANCELED) }
                .sumOf { task -> if (task.state == MediaTaskState.COMPLETED) task.outputPaths.count { path -> asset.versions.none { it.taskId == task.id && it.path == path } } else 1 }
            require(asset.versions.size + pending + versions <= 30) { "声音资产版本容量不足。" }
            val baseSeed = seed().and(Long.MAX_VALUE)
            val requests = List(versions) { index ->
                val actualSeed = (baseSeed + index).and(Long.MAX_VALUE)
                MediaGenerationRequest(template.channelId, GenerationMode.MUSIC, prompt?.takeIf { it.isNotBlank() } ?: asset.description?.takeIf { it.isNotBlank() } ?: asset.name,
                    emptyList(), ProductionAssetSourceRef(p.id, asset.id).encode(), GenerationSpecs(seed = actualSeed, durationSecondsExact = seconds), sessionId,
                    "${p.rootPath}/assets/${asset.id}", template.resolveParams(emptyMap()), ProductionMediaContext(templateFingerprint = template.fingerprint(), templateId = template.id, assetId = asset.id))
            }
            requests.forEach { require(validateRequest(template.channel(), it).valid) { "模板不支持请求的配乐时长。" } }
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
