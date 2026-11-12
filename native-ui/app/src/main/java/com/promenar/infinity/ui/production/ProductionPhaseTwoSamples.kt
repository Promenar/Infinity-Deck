package com.promenar.infinity.ui.production

import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.mediagen.CharacterReferenceMode
import com.promenar.infinity.domain.mediagen.GenerationMode
import com.promenar.infinity.domain.mediagen.InputKind
import com.promenar.infinity.domain.mediagen.InputRole
import com.promenar.infinity.domain.mediagen.MediaCapabilities
import com.promenar.infinity.domain.mediagen.MediaChannel
import com.promenar.infinity.domain.mediagen.MediaGenerationRequest
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.domain.mediagen.MediaTask
import com.promenar.infinity.domain.mediagen.MediaTaskState
import com.promenar.infinity.domain.mediagen.Money
import com.promenar.infinity.domain.mediagen.PromptFormat
import java.math.BigDecimal

/** 仅供预览的完整第 2 期数据，不触发媒体生成、网络与持久化。 */
internal object ProductionPhaseTwoSamples {
    val channels = listOf(
        MediaChannel("seedance", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.TEXT, GenerationMode.REFERENCE), maxImages = 9, nativeAudio = true, resolutions = listOf("1080p"), promptFormat = PromptFormat.SEEDANCE, characterReferenceMode = CharacterReferenceMode.COMPOSITE)),
        MediaChannel("h3", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.REFERENCE), maxImages = 3, promptFormat = PromptFormat.H3)),
    )
    val order = channels.map { it.id }
    private val look = CharacterLook("coat", "风衣", defaultReferenceSlots().map { it.copy(path = "productions/harbor/assets/${it.id}.png") } + ReferenceSlot("detail", "怀表链", "productions/harbor/assets/detail.png"))
    private val base = ProductionSamples.production.copy(
        budgetCents = 10000,
        assets = ProductionSamples.production.assets.map { asset -> if (asset.kind != AssetKind.CHARACTER) asset else asset.copy(looks = listOf(look, CharacterLook("suit", "西服")), referenceVoice = ReferenceVoice("productions/harbor/voices/${asset.id}.wav", "有些事情，总得有人记得。", "低沉、微哑", "克制")) },
        seasons = ProductionSamples.production.seasons.map { season -> season.copy(episodes = season.episodes.map { episode -> episode.copy(scenes = episode.scenes.map { scene -> scene.copy(shots = scene.shots.map { shot ->
            if (shot.code != "SC01-02") shot else shot.copy(
                camera = "平视", cameraMovement = "缓推", visual = "林晚停步，回望栈桥尽头。", performance = "目光警觉，呼吸放缓。",
                dialogue = listOf(ShotDialogue("char-linwan", "你听见了吗？", "低声")), sound = "雾笛与海浪", transition = "切入", continuity = "接栈桥远景",
                channelId = "seedance", appearances = listOf(ShotAppearance("char-linwan", look.id, listOf("front", "side", "back"))), promptUserEdited = true,
            )
        }) }) }) },
    )
    /** 两个粗剪版本：v1 已导出，v2 正在导出。 */
    private val withCuts = ProductionCuts.create(ProductionCuts.create(base, "S1E01", emptyMap(), 1_000), "S1E01", emptyMap(), 2_000).let { p ->
        val first = p.cuts.first()
        ProductionCuts.registerExport(p, first.id, CutExportRecord(ProductionCuts.videoPath(p, first), ProductionCuts.subtitlePath(p, first), 42.0, 18_400_000, 3_000))
    }
    val production = withCuts
    val snapshot = ProductionSnapshot(production, 12, ManifestSync.InSync)
    val preview = ShotPlanPreview(channels.first(), true, "Seedance 2.5 · 火山方舟", ShotMode.REFERENCE, listOf(
        PlannedInput(1, "林晚 · 风衣 · 合图", "正面全身 / 侧面全身 / 背面全身", InputKind.IMAGE, InputRole.REFERENCE, null, look.slots.take(3).mapNotNull { it.path }),
        PlannedInput(null, "林晚 · 参考语音", "原生对白", InputKind.AUDIO, InputRole.REFERENCE_VOICE, "productions/harbor/voices/char-linwan.wav"),
    ), listOf(PlannedInput(4, "怀表链", "参考位", InputKind.IMAGE, InputRole.REFERENCE, "productions/harbor/assets/detail.png")), "图1中的林晚停步回头。镜头平视缓推，目光警觉。低声说：你听见了吗？", true, PromptFormat.SEEDANCE, Money(BigDecimal("1.20"), "CNY"), null, listOf("老周的参考包待审"))
    val tasks = listOf(
        MediaTask("approve", MediaGenerationRequest("seedance", GenerationMode.REFERENCE, "海雾", emptyList(), ProductionSourceRef("harbor", "S1E01", "SC01-02").encode()), Money(BigDecimal("1.20"), "CNY")),
        MediaTask("run", MediaGenerationRequest("h3", GenerationMode.REFERENCE, "推门", emptyList(), ProductionSourceRef("harbor", "S1E01", "SC02-01").encode()), Money(BigDecimal.ZERO, "CNY"), state = MediaTaskState.RUNNING, progress = 0.42f),
        MediaTask("failed", MediaGenerationRequest("h3", GenerationMode.REFERENCE, "信封", emptyList(), ProductionSourceRef("harbor", "S1E01", "SC01-03").encode()), Money(BigDecimal.ZERO, "CNY"), state = MediaTaskState.FAILED, error = "主机暂不可用"),
    )
    val services = ProductionPanelServices(
        planner = object : ShotGenerationPlanner {
            override suspend fun preview(sessionId: String, episodeId: String, shotCode: String) = ProductionPhaseTwoSamples.preview
            override suspend fun generate(sessionId: String, episodeId: String, shotCode: String) = ShotGenerateResult.Rejected("预览环境")
            override suspend fun recompilePrompt(sessionId: String, episodeId: String, shotCode: String) = ProductionPhaseTwoSamples.preview.prompt
        },
        queue = object : com.promenar.infinity.domain.mediagen.MediaGenerationQueue {
            override fun observeTasks() = kotlinx.coroutines.flow.flowOf(tasks)
            override fun observeTask(id: String) = kotlinx.coroutines.flow.flowOf(tasks.firstOrNull { it.id == id })
            override suspend fun submit(request: MediaGenerationRequest): MediaTask = error("预览环境不提交")
            override suspend fun approve(id: String) = Unit
            override suspend fun reject(id: String) = Unit
            override suspend fun retry(id: String) = Unit
        },
        projectSourcePrefix = { "${it.id}:" },
        shotSourceRef = { p, location -> "${p.id}:${location.shot.code}" },
        channelLabel = { if (it == "seedance") "Seedance 2.5" else "本机 H3" },
        cutExport = object : com.promenar.infinity.data.cut.CutExportController {
            override suspend fun createVersion(sessionId: String, episodeId: String): CutVersion = error("预览环境")
            override suspend fun deleteVersion(sessionId: String, versionId: String) = Unit
            override suspend fun setBurnSubtitles(sessionId: String, versionId: String, burn: Boolean) = Unit
            override suspend fun export(sessionId: String, versionId: String, burnSubtitles: Boolean?) = Unit
            override suspend fun cancel(versionId: String) = Unit
            override fun observe() = kotlinx.coroutines.flow.MutableStateFlow<Map<String, com.promenar.infinity.data.cut.CutExportState>>(
                mapOf(production.cuts.last().id to com.promenar.infinity.data.cut.CutExportState.Running(0.42f)),
            )
        },
    )
}
