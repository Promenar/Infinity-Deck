package com.promenar.infinity.ui.production

import com.promenar.infinity.data.production.Asset
import com.promenar.infinity.data.production.AssetKind
import com.promenar.infinity.data.production.AssetStatus
import com.promenar.infinity.data.production.AssetVersion
import com.promenar.infinity.data.production.DocumentRef
import com.promenar.infinity.data.production.Episode
import com.promenar.infinity.data.production.InputRole
import com.promenar.infinity.data.production.ManifestSync
import com.promenar.infinity.data.production.PreviewKind
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionKind
import com.promenar.infinity.data.production.ProductionSnapshot
import com.promenar.infinity.data.production.ProductionStage
import com.promenar.infinity.data.production.Scene
import com.promenar.infinity.data.production.Season
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.production.ShotInput
import com.promenar.infinity.data.production.ShotMode
import com.promenar.infinity.data.production.ShotStatus
import com.promenar.infinity.data.production.StageState
import com.promenar.infinity.data.production.StageStatus
import com.promenar.infinity.data.production.Take
import com.promenar.infinity.data.production.TakeSource

/** 预览与截图测试用的示例项目：两集剧集，覆盖全部镜头与资产状态。 */
internal object ProductionSamples {
    private const val ROOT = "productions/harbor"

    private fun take(id: String, n: Int, service: String = "Seedance 2.5", mode: ShotMode = ShotMode.FIRST_FRAME) =
        Take(id, "$ROOT/takes/$id.mp4", TakeSource(service, mode = mode), createdAt = n.toLong())

    private val pilotScenes = listOf(
        Scene(
            code = "SC01", number = 1, heading = "外 码头栈桥 夜", storyboardLocked = true,
            shots = listOf(
                Shot(
                    code = "SC01-01", title = "雾中栈桥远景", size = "远景", durationSeconds = 5.0, mode = ShotMode.TEXT,
                    prompt = "夜色中的旧码头，海雾低垂，栈桥尽头一盏孤灯摇晃。",
                    status = ShotStatus.LOCKED, takes = listOf(take("t01", 1, mode = ShotMode.TEXT)), selectedTakeId = "t01",
                ),
                Shot(
                    code = "SC01-02", title = "林晚回头", size = "近景", durationSeconds = 4.5, mode = ShotMode.REFERENCE,
                    prompt = "林晚在雾中停步回头，灯光从侧后方勾出轮廓，神情警觉。",
                    inputs = listOf(
                        ShotInput(InputRole.REFERENCE, "$ROOT/assets/linwan-v2.png", label = "林晚", assetId = "char-linwan"),
                        ShotInput(InputRole.ANCHOR, "$ROOT/keyframes/sc01-02-k1.png", label = "回头关键帧", atSeconds = 2.0),
                    ),
                    preview = PreviewKind.KEYFRAME, previewPath = "$ROOT/keyframes/sc01-02-k1.png",
                    status = ShotStatus.REVIEW, takes = listOf(take("t02", 2, mode = ShotMode.REFERENCE), take("t03", 3, "本机 H3", ShotMode.REFERENCE)),
                    agentNote = "候选 2 保留了关键帧构图。",
                ),
                Shot(
                    code = "SC01-03", title = "灯下的信封", size = "特写", durationSeconds = 3.0, mode = ShotMode.FIRST_LAST,
                    status = ShotStatus.REDO, userNote = "信封字迹要清楚。",
                    inputs = listOf(
                        ShotInput(InputRole.FIRST_FRAME, "$ROOT/keyframes/sc01-03-a.png", label = "首帧"),
                        ShotInput(InputRole.LAST_FRAME, "$ROOT/keyframes/sc01-03-b.png", label = "尾帧"),
                    ),
                ),
            ),
        ),
        Scene(
            code = "SC02", number = 2, heading = "内 渔具仓库 夜",
            shots = listOf(
                Shot(code = "SC02-01", title = "推门进入", size = "中景", durationSeconds = 4.0, status = ShotStatus.GENERATING),
                Shot(code = "SC02-02", title = "货架间的影子", size = "全景", durationSeconds = 6.0, status = ShotStatus.TODO),
            ),
        ),
    )

    val production = Production(
        id = "harbor",
        title = "雾港",
        kind = ProductionKind.SERIES,
        rootPath = ROOT,
        logline = "失踪案十年后，女儿回到雾港，在旧码头找到父亲留下的信。",
        aspectRatio = "16:9",
        bible = listOf(DocumentRef("故事圣经", "$ROOT/bible/story.md"), DocumentRef("视觉风格", "$ROOT/bible/look.md")),
        stages = listOf(
            StageState(ProductionStage.SCRIPT, StageStatus.LOCKED),
            StageState(ProductionStage.VISUAL_DEV, StageStatus.LOCKED),
            StageState(ProductionStage.STORYBOARD, StageStatus.IN_PROGRESS),
            StageState(ProductionStage.ANIMATIC, StageStatus.SKIPPED),
            StageState(ProductionStage.GENERATION, StageStatus.IN_PROGRESS),
            StageState(ProductionStage.SOUND),
            StageState(ProductionStage.EDIT),
        ),
        seasons = listOf(
            Season(
                id = "S1", number = 1,
                episodes = listOf(
                    Episode("S1E01", 1, "归港", scriptPath = "$ROOT/scripts/s1e01.fountain", scriptLocked = true, scenes = pilotScenes),
                    Episode("S1E02", 2, "潮汐表", scriptPath = "$ROOT/scripts/s1e02.fountain"),
                ),
            ),
        ),
        assets = listOf(
            Asset(
                "char-linwan", AssetKind.CHARACTER, "林晚", description = "二十八岁，短发，深色风衣。",
                versions = listOf(
                    AssetVersion(1, "$ROOT/assets/linwan-v1.png", 1),
                    AssetVersion(2, "$ROOT/assets/linwan-v2.png", 2, note = "调整发型"),
                ),
                currentVersion = 2, status = AssetStatus.LOCKED,
            ),
            Asset(
                "char-zhou", AssetKind.CHARACTER, "老周",
                versions = listOf(AssetVersion(1, "$ROOT/assets/zhou-v1.png", 3)), currentVersion = 1, status = AssetStatus.REVIEW,
            ),
            Asset("loc-pier", AssetKind.LOCATION, "旧码头栈桥", versions = listOf(AssetVersion(1, "$ROOT/assets/pier-v1.png", 4)), currentVersion = 1, status = AssetStatus.LOCKED),
            Asset("prop-letter", AssetKind.PROP, "信封", status = AssetStatus.REDO, userNote = "需要旧纸质感。"),
        ),
        createdAt = 1,
        updatedAt = 2,
    )

    fun snapshot(manifest: ManifestSync = ManifestSync.InSync) = ProductionSnapshot(production, revision = 12, manifest = manifest)
}
