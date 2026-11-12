package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.domain.mediagen.InputRole as MediaRole
import kotlinx.coroutines.flow.*
import java.math.BigDecimal

internal fun planningProduction(shot: Shot = Shot("SC01-01", "交信", durationSeconds = 5.0, mode = ShotMode.REFERENCE,
    visual = "递出信封", performance = "迟疑，眼神警惕", camera = "平视", cameraMovement = "缓慢推近", size = "近景",
    appearances = listOf(ShotAppearance("lin", "coat", listOf("back", "front"))), locationAssetId = "pier")) = Production(
    "p/1", "雾港", ProductionKind.SERIES, "productions/fog", createdAt = 1, updatedAt = 1,
    seasons = listOf(Season("S1", 1, episodes = listOf(Episode("S1E01", 1, "开场", scenes = listOf(Scene("SC01", 1, "码头", true, listOf(shot))))))),
    assets = listOf(Asset("lin", AssetKind.CHARACTER, "林晚", status = AssetStatus.REVIEW, looks = listOf(CharacterLook("coat", "风衣", listOf(
        ReferenceSlot("back", "背面全身", "ref/back.png"), ReferenceSlot("front", "正面全身", "ref/front.png"), ReferenceSlot("side", "侧面全身", "ref/side.png")))), referenceVoice = ReferenceVoice("ref/voice.wav")),
        Asset("pier", AssetKind.LOCATION, "码头", versions = listOf(AssetVersion(1, "ref/pier.png", 1)), currentVersion = 1)),
)
internal class PlanningStore(var production: Production = planningProduction()) : ShotGenerationStore {
    var revision = 1L
    override suspend fun load(sessionId: String) = ProductionSnapshot(production, revision, ManifestSync.InSync)
    override suspend fun production(id: String) = production.takeIf { it.id == id }
    override suspend fun recompile(sessionId: String, revision: Long, episodeId: String, shotCode: String, prompt: String) {
        check(revision == this.revision)
        production = production.mapShot(episodeId, shotCode) { it.copy(prompt = prompt, promptUserEdited = false) }
        this.revision++
    }
    override suspend fun register(task: MediaTask): Boolean {
        val changed = registerShotTask(production, task, 2) ?: return false
        if (changed != production) { production = changed; revision++ }
        return true
    }
}
internal class PlanningRegistry(val channels: MutableStateFlow<List<MediaChannel>>) : MediaChannelRegistry {
    constructor(vararg channels: MediaChannel) : this(MutableStateFlow(channels.toList()))
    override fun observeChannels() = channels
    override fun observeOrder(group: MediaGroup) = channels.map { it.filter { c -> c.group == group }.map { c -> c.id } }
    override suspend fun channel(id: String) = channels.value.find { it.id == id }
    override suspend fun setOrder(group: MediaGroup, channelIds: List<String>) { channels.value = channelIds.mapNotNull { id -> channels.value.find { it.id == id } } + channels.value.filter { it.id !in channelIds } }
}
internal class PlanningFiles : ProductionWorkspaceFiles {
    val bytes = java.util.concurrent.ConcurrentHashMap(mapOf("ref/front.png" to byteArrayOf(1), "ref/side.png" to byteArrayOf(2), "ref/back.png" to byteArrayOf(3), "ref/pier.png" to byteArrayOf(4), "ref/voice.wav" to byteArrayOf(5)))
    var writes = 0
    override suspend fun read(sessionId: String, path: String) = bytes[path]
    override suspend fun write(sessionId: String, path: String, bytes: ByteArray, expectedSha256: String?) {
        synchronized(this) {
            val previous = this.bytes[path]
            check(if (expectedSha256 == null) previous == null else previous != null && ProductionManifestCodec.sha256(previous) == expectedSha256)
            this.bytes[path] = bytes; writes++
        }
    }
    override suspend fun isImageOrVideo(sessionId: String, path: String) = bytes.containsKey(path)
}
internal fun planningChannel(id: String = "video", pack: CharacterReferenceMode = CharacterReferenceMode.SEPARATE, max: Int = 9, format: PromptFormat = PromptFormat.GENERIC) = MediaChannel(id, MediaGroup.VIDEO,
    MediaCapabilities(setOf(GenerationMode.TEXT, GenerationMode.REFERENCE, GenerationMode.FIRST_FRAME, GenerationMode.FIRST_LAST_FRAME), maxImages = max, maxAudio = 1, nativeAudio = true, promptFormat = format,
        characterReferenceMode = pack, referenceOrder = listOf("back_full_body", "front_full_body", "side_full_body")))
internal class PlanningQueue : MediaGenerationQueue {
    val tasks = MutableStateFlow<List<MediaTask>>(emptyList())
    override suspend fun submit(request: MediaGenerationRequest) = MediaTask("task-${tasks.value.size}", request, Money(BigDecimal.ONE, "CNY")).also { tasks.value += it }
    override fun observeTasks() = tasks
    override fun observeTask(id: String) = tasks.map { it.find { t -> t.id == id } }
    override suspend fun cancel(id: String) { tasks.value = tasks.value.map { if (it.id == id) it.canceled() else it } }
}
