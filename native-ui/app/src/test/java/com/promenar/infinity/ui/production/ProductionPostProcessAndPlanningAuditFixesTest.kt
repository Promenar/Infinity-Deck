package com.promenar.infinity.ui.production

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.production.Asset
import com.promenar.infinity.data.production.AssetKind
import com.promenar.infinity.data.production.AssetVersion
import com.promenar.infinity.data.production.CharacterLook
import com.promenar.infinity.data.production.Episode
import com.promenar.infinity.data.production.InputRole
import com.promenar.infinity.data.production.MediaSpec
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionKind
import com.promenar.infinity.data.production.ReferenceSlot
import com.promenar.infinity.data.production.ReferenceVoice
import com.promenar.infinity.data.production.ReviewEntry
import com.promenar.infinity.data.production.ReviewKind
import com.promenar.infinity.data.production.ReviewTarget
import com.promenar.infinity.data.production.Scene
import com.promenar.infinity.data.production.Season
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.production.ShotAppearance
import com.promenar.infinity.data.production.ShotInput
import com.promenar.infinity.data.production.ShotMode
import com.promenar.infinity.data.production.ShotStatus
import com.promenar.infinity.data.production.Take
import com.promenar.infinity.data.production.TakeDerivation
import com.promenar.infinity.data.production.TakePlanSnapshot
import com.promenar.infinity.data.production.TakeSource
import com.promenar.infinity.domain.mediagen.ComfyWorkflow
import com.promenar.infinity.domain.mediagen.FieldSemanticRole
import com.promenar.infinity.domain.mediagen.FieldValueType
import com.promenar.infinity.domain.mediagen.GenerationMode
import com.promenar.infinity.domain.mediagen.GenerationSpecs
import com.promenar.infinity.domain.mediagen.MediaCapabilities
import com.promenar.infinity.domain.mediagen.MediaChannel
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.domain.mediagen.WorkflowFieldSpec
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ProductionPostProcessAndPlanningAuditFixesTest {
    private val ep1 = Episode("S1E01", 1, "青石雨巷")
    private val ep2 = Episode("S1E02", 2, "旧阁听雨")
    private val baseProduction = Production(
        id = "p-audit",
        title = "雨巷",
        kind = ProductionKind.SERIES,
        rootPath = "productions/rain",
        seasons = listOf(Season("S1", 1, episodes = listOf(ep1, ep2))),
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun makeTake(
        id: String,
        createdAt: Long,
        derivedFrom: TakeDerivation? = null,
        spec: MediaSpec? = null,
        snapshotSpecs: GenerationSpecs? = null,
    ) = Take(
        id = id,
        path = "productions/rain/S1E01/takes/$id.mp4",
        source = TakeSource("ComfyUI"),
        createdAt = createdAt,
        derivedFrom = derivedFrom,
        spec = spec,
        planSnapshot = snapshotSpecs?.let {
            TakePlanSnapshot(mode = ShotMode.REFERENCE, inputs = emptyList(), prompt = "p", specs = it)
        },
    )

    @Test
    fun `对比审片默认优先选中未审批的最新派生候选而不是已锁定的原片`() {
        val source = makeTake("t1", createdAt = 10L, spec = MediaSpec(1344, 768, 24.0, 5.0))
        val derived = makeTake("t2", createdAt = 20L, derivedFrom = TakeDerivation("t1", "FlashVSR 2×"), spec = MediaSpec(2688, 1536, 24.0, 5.0))
        val shot = Shot("SC01-01", "提灯独行", durationSeconds = 5.0, status = ShotStatus.LOCKED, takes = listOf(source, derived), selectedTakeId = "t1")
        val approvedSource = baseProduction.copy(
            reviews = listOf(ReviewEntry(ReviewTarget.take("S1E01", "SC01-01", "t1"), ReviewKind.APPROVE, at = 15L, round = 1)),
        )

        assertThat(initialViewingTake(approvedSource, "S1E01", shot)?.id).isEqualTo("t2")
    }

    @Test
    fun `存在多条派生候选时跳过已否决项优先最新待审派生且全批时落已批准派生`() {
        val source = makeTake("t1", createdAt = 10L)
        val rejectedDerived = makeTake("t2", createdAt = 20L, derivedFrom = TakeDerivation("t1", "FlashVSR 2×"))
        val pendingDerived = makeTake("t3", createdAt = 30L, derivedFrom = TakeDerivation("t1", "FlashVSR 2×"))
        val shot = Shot("SC01-01", "提灯独行", durationSeconds = 5.0, status = ShotStatus.REVIEW, takes = listOf(source, rejectedDerived, pendingDerived), selectedTakeId = "t1")
        val withReject = baseProduction.copy(
            reviews = listOf(ReviewEntry(ReviewTarget.take("S1E01", "SC01-01", "t2"), ReviewKind.REJECT, "边缘锐化过重", at = 25L)),
        )

        assertThat(initialViewingTake(withReject, "S1E01", shot)?.id).isEqualTo("t3")

        val withApproved = withReject.copy(
            reviews = withReject.reviews + ReviewEntry(ReviewTarget.take("S1E01", "SC01-01", "t3"), ReviewKind.APPROVE, at = 35L),
        )
        assertThat(initialViewingTake(withApproved, "S1E01", shot)?.id).isEqualTo("t3")

        // 仅剩被否决的派生候选时回退既有展示逻辑（原片已选定候选）。
        val onlyRejectedShot = shot.copy(takes = listOf(source, rejectedDerived))
        assertThat(initialViewingTake(withReject, "S1E01", onlyRejectedShot)?.id).isEqualTo("t1")

        // 无派生候选时完全沿用 displayTake。
        val plainShot = shot.copy(takes = listOf(source, makeTake("t4", createdAt = 40L)), selectedTakeId = null)
        assertThat(initialViewingTake(baseProduction, "S1E01", plainShot)?.id).isEqualTo("t4")
    }

    @Test
    fun `一键AB对比同时支持从派生看原片与从原片切最新派生并正确提取规格文案`() {
        val source = makeTake("t1", createdAt = 10L, spec = MediaSpec(1344, 768))
        val derived1 = makeTake("t2", createdAt = 20L, derivedFrom = TakeDerivation("t1", "FlashVSR 2×"), spec = MediaSpec(2688, 1536))
        val derived2 = makeTake("t3", createdAt = 30L, derivedFrom = TakeDerivation("t1", "DLSS 5 2×"), snapshotSpecs = GenerationSpecs(width = 2560, height = 1440))
        val standalone = makeTake("t4", createdAt = 40L, snapshotSpecs = GenerationSpecs(resolution = "1080p"))
        val shot = Shot("SC01-01", "提灯独行", durationSeconds = 5.0, takes = listOf(source, derived1, derived2, standalone))

        assertThat(abComparePair(shot, derived1)).isEqualTo(source to derived1)
        assertThat(abComparePair(shot, source)).isEqualTo(source to derived2)
        assertThat(abComparePair(shot, standalone)).isNull()

        assertThat(source.compactSpecLabel()).isEqualTo("1344×768")
        assertThat(derived2.compactSpecLabel()).isEqualTo("2560×1440")
        assertThat(standalone.compactSpecLabel()).isEqualTo("1080p")
        assertThat(makeTake("t5", createdAt = 50L).compactSpecLabel()).isNull()
    }

    @Test
    fun `ComfyUI规格自定标签忽略残留分辨率且与工作流默认值一致时不误标自定`() {
        val comfyChannel = MediaChannel("comfy:h3_ref", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.REFERENCE)))
        val cloudChannel = MediaChannel("minimax:h3", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.REFERENCE), resolutions = listOf("768P", "1080P")))
        val workflow = ComfyWorkflow(
            workflowId = "h3_ref",
            hostId = "win-main",
            name = "H3 参考",
            submitterScriptId = "h3",
            fields = listOf(
                WorkflowFieldSpec("width", "宽", valueType = FieldValueType.INT, defaultValue = JsonPrimitive(1344), semanticRole = FieldSemanticRole.WIDTH),
                WorkflowFieldSpec("height", "高", valueType = FieldValueType.INT, defaultValue = JsonPrimitive(768), semanticRole = FieldSemanticRole.HEIGHT),
            ),
        )
        val baseShot = Shot("SC01-01", "提灯独行", durationSeconds = 5.0)

        // ComfyUI 忽略残留的 specResolution。
        assertThat(hasEffectiveCustomSpecs(baseShot.copy(specResolution = "720p"), comfyChannel, workflow)).isFalse()
        // ComfyUI 宽高与工作流默认一致时不标自定。
        assertThat(hasEffectiveCustomSpecs(baseShot.copy(specWidth = 1344, specHeight = 768), comfyChannel, workflow)).isFalse()
        // ComfyUI 宽高覆盖工作流默认值时标自定。
        assertThat(hasEffectiveCustomSpecs(baseShot.copy(specWidth = 1920, specHeight = 1080), comfyChannel, workflow)).isTrue()

        // 云端通道忽略残留的 Comfy 像素宽高；与默认分辨率一致不标自定，异于默认时标自定。
        assertThat(hasEffectiveCustomSpecs(baseShot.copy(specWidth = 1920, specHeight = 1080), cloudChannel, null)).isFalse()
        assertThat(hasEffectiveCustomSpecs(baseShot.copy(specResolution = "768p"), cloudChannel, null)).isFalse()
        assertThat(hasEffectiveCustomSpecs(baseShot.copy(specResolution = "1080P"), cloudChannel, null)).isTrue()

        // 未解析通道时回退基础判断。
        assertThat(hasEffectiveCustomSpecs(baseShot.copy(specResolution = "1080P"), null, null)).isTrue()
        assertThat(hasEffectiveCustomSpecs(baseShot, null, null)).isFalse()
    }

    @Test
    fun `多集筛选器按所选集过滤并在空选或失效集ID时回退全部剧集`() {
        val all = listOf(ep1, ep2)
        assertThat(filterEpisodes(all, null)).containsExactly(ep1, ep2).inOrder()
        assertThat(filterEpisodes(all, "S1E02")).containsExactly(ep2)
        assertThat(filterEpisodes(all, "S1E99")).containsExactly(ep1, ep2).inOrder()
    }

    @Test
    fun `声音资产卡解析绑定角色与激活版本时长格式信息`() {
        val character = Asset(
            id = "char-lin",
            kind = AssetKind.CHARACTER,
            name = "林提灯",
            referenceVoice = ReferenceVoice(path = "voices/lin_whisper.wav"),
        )
        val boundSound = Asset(
            id = "snd-lin",
            kind = AssetKind.SOUND,
            name = "林提灯·雨夜低语配音",
            versions = listOf(
                AssetVersion(1, "productions/rain/voices/lin_whisper.wav", createdAt = 1L, durationSeconds = 13.6),
                AssetVersion(2, "productions/rain/voices/lin_whisper_v2.mp3", createdAt = 2L, durationSeconds = 15.0),
            ),
            currentVersion = 1,
        )
        val extOnlySound = Asset(
            id = "snd-sfx",
            kind = AssetKind.SOUND,
            name = "雨声环境音",
            versions = listOf(AssetVersion(1, "productions/rain/sfx/rain.flac", createdAt = 1L)),
            currentVersion = 1,
        )
        val emptySound = Asset(id = "snd-empty", kind = AssetKind.SOUND, name = "待录制配音")
        val prod = baseProduction.copy(assets = listOf(character, boundSound, extOnlySound, emptySound))

        assertThat(prod.boundCharacterForSound(boundSound)).isEqualTo(character)
        assertThat(prod.boundCharacterForSound(extOnlySound)).isNull()
        assertThat(prod.boundCharacterForSound(emptySound)).isNull()
        assertThat(prod.boundCharacterForSound(character)).isNull()

        assertThat(soundAssetMetaSegments(boundSound)).containsExactly("13.6s", "WAV").inOrder()
        assertThat(soundAssetMetaSegments(boundSound.copy(currentVersion = 2))).containsExactly("15s", "MP3").inOrder()
        assertThat(soundAssetMetaSegments(extOnlySound)).containsExactly("FLAC")
        assertThat(soundAssetMetaSegments(emptySound)).isEmpty()
    }

    @Test
    fun `分镜参考数传真实总数而非前4张缩略图数量且场景审批改为内联槽`() {
        val look = CharacterLook(
            id = "coat",
            name = "雨夜常服",
            slots = listOf(
                ReferenceSlot("front", "正", "p/c1.png"),
                ReferenceSlot("side", "侧", "p/c2.png"),
                ReferenceSlot("back", "背", "p/c3.png"),
            ),
        )
        val charAsset = Asset("char-1", AssetKind.CHARACTER, "林提灯", looks = listOf(look))
        val locAsset = Asset("loc-1", AssetKind.LOCATION, "青石雨巷", versions = listOf(AssetVersion(1, "p/loc.png", 1L)), currentVersion = 1)
        val propAsset = Asset("prop-1", AssetKind.PROP, "青铜提灯", versions = listOf(AssetVersion(1, "p/prop.png", 1L)), currentVersion = 1)
        val shot = Shot(
            code = "SC01-01",
            title = "提灯独行",
            durationSeconds = 5.0,
            appearances = listOf(ShotAppearance("char-1", "coat", listOf("front", "side", "back"))),
            locationAssetId = "loc-1",
            propAssetIds = listOf("prop-1"),
            inputs = listOf(ShotInput(InputRole.REFERENCE, "p/extra.png")),
        )
        val prod = baseProduction.copy(
            assets = listOf(charAsset, locAsset, propAsset),
            seasons = listOf(Season("S1", 1, episodes = listOf(ep1.copy(scenes = listOf(Scene("SC01", 1, "巷口", shots = listOf(shot))))))),
        )
        // 真实参考图数为 6（> 缩略图上限 4）。
        assertThat(prod.importedReferencePaths(shot)).hasSize(6)

        fun productionSource(name: String): String {
            val path = Path.of("app/src/main/java/com/promenar/infinity/ui/production/$name").let {
                if (Files.exists(it)) it else Path.of("src/main/java/com/promenar/infinity/ui/production/$name")
            }
            return Files.readString(path)
        }
        val tabsSource = productionSource("ProductionTabs.kt")
        assertThat(tabsSource).contains("val allPaths = production.importedReferencePaths(shot)")
        assertThat(tabsSource).contains("val previewPaths = allPaths.take(4)")
        assertThat(tabsSource).contains("R.string.p2_ref_count_compact, allPaths.size")
        assertThat(tabsSource).contains("externalExpanded = reviewOpen")
        assertThat(tabsSource).contains("ProductionInlineReviewSlot(")

        val planningSource = productionSource("ProductionShotPlanning.kt")
        assertThat(planningSource).contains("ProductionInlineReviewSlot(")
        assertThat(planningSource).doesNotContain("PlanningDialog.SCENE_REVIEW")
    }
}
