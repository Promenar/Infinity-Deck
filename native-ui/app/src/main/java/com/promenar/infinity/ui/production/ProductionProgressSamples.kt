package com.promenar.infinity.ui.production

import com.promenar.infinity.data.production.*
import kotlinx.serialization.json.JsonPrimitive

/** 仅供预览：进行中的衔接链与登记项、派生与拼接候选、未登记文件与配乐版本。 */
internal object ProductionProgressSamples {
    private const val ROOT = "productions/harbor"
    /** 远在未来的回报时间，使示例登记项不被判为未回报。 */
    private const val FRESH = Long.MAX_VALUE / 4

    val chain = ShotChain(
        id = "c1",
        state = ChainState.RUNNING,
        segments = listOf(
            ChainSegment(0, 5.0, state = ChainSegmentState.COMPLETED, path = "$ROOT/S1E01/takes/chain-c1/seg-0.mp4"),
            ChainSegment(1, 5.0, state = ChainSegmentState.RUNNING, firstFramePath = "$ROOT/S1E01/takes/chain-c1/seg-0-last.png"),
            ChainSegment(2, 4.0),
        ),
        current = 1,
        startTakeId = "t02",
        channelId = "h3",
        createdAt = 10,
        updatedAt = 20,
    )
    val failedChain = chain.copy(
        id = "c0",
        state = ChainState.FAILED,
        failure = "模板已变化",
        segments = chain.segments.mapIndexed { index, segment ->
            if (index == 1) segment.copy(state = ChainSegmentState.FAILED, failure = "模板已变化") else segment
        },
    )
    val run = ProductionRun(
        id = "r1",
        target = RunTarget(episodeId = "S1E01", shotCode = "SC01-02"),
        label = "H3 续接 3 段",
        steps = 3,
        completedSteps = 1,
        message = "第 2 段采样中",
        startedAt = 0,
        reportedAt = FRESH,
    )
    /** 超过 30 分钟没有回报。 */
    val staleRun = run.copy(id = "r2", label = "尾段衔接", steps = null, completedSteps = 0, message = null, reportedAt = 0)

    private val derived = Take(
        id = "t04",
        path = "$ROOT/takes/t04.mp4",
        source = TakeSource("本机 ComfyUI"),
        createdAt = 4,
        derivedFrom = TakeDerivation("t02", "超分", "upscale-4k", mapOf("scale" to JsonPrimitive(2), "model" to JsonPrimitive("RealESRGAN"))),
        spec = MediaSpec(3840, 2160, 24.0, 4.5),
    )
    private val joined = Take(
        id = "t05",
        path = "$ROOT/takes/t05.mp4",
        source = TakeSource("本机 H3"),
        createdAt = 5,
        segments = listOf(TakeSegment(0, "$ROOT/S1E01/takes/chain-c9/seg-0.mp4", 0.0, 5.0), TakeSegment(1, "$ROOT/S1E01/takes/chain-c9/seg-1.mp4", 5.0, 9.96)),
        chainId = "c9",
        spec = MediaSpec(1280, 720, 24.0, 9.96),
    )
    private val score = Asset(
        id = "snd-theme",
        kind = AssetKind.SOUND,
        name = "雾港主题",
        versions = listOf(
            AssetVersion(1, "$ROOT/assets/score/theme-v1.flac", 1, seed = 4_211, durationSeconds = 42.0),
            AssetVersion(2, "$ROOT/assets/score/theme-v2.flac", 2, seed = 98_031, durationSeconds = 42.0),
            AssetVersion(3, "$ROOT/assets/score/theme-v3.flac", 3, seed = 551_207, durationSeconds = 42.0),
        ),
        currentVersion = 3,
        status = AssetStatus.REVIEW,
    )

    val production: Production = ProductionPhaseTwoSamples.production.let { base ->
        base.copy(
            seasons = base.seasons.map { season -> season.copy(episodes = season.episodes.map { episode -> episode.copy(scenes = episode.scenes.map { scene -> scene.copy(shots = scene.shots.map { shot ->
                if (shot.code != "SC01-02") shot else shot.copy(takes = shot.takes + derived + joined, chains = listOf(failedChain, chain))
            }) }) }) },
            runs = listOf(run, staleRun),
            assets = base.assets + score,
            reviews = base.reviews + ReviewEntry(ReviewTarget.assetVersion(score.id, 2), ReviewKind.APPROVE, at = 4_000),
            unregisteredFiles = listOf("$ROOT/S1E01/takes/h3-ext-01.mp4", "$ROOT/S1E01/takes/h3-ext-02.mp4", "$ROOT/assets/score/draft.wav"),
        )
    }
    val shot: Shot get() = requireNotNull(production.findShot("S1E01", "SC01-02")).shot
    val derivedTake: Take get() = derived
    val joinedTake: Take get() = joined
    val scoreAsset: Asset get() = score
}
