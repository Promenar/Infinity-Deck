package com.promenar.infinity.ui.production

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.mediagen.CharacterReferenceMode
import com.promenar.infinity.domain.mediagen.GenerationMode
import com.promenar.infinity.domain.mediagen.MediaCapabilities
import com.promenar.infinity.domain.mediagen.MediaChannel
import com.promenar.infinity.domain.mediagen.MediaGenerationQueue
import com.promenar.infinity.domain.mediagen.MediaGenerationRequest
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.domain.mediagen.MediaTask
import com.promenar.infinity.domain.mediagen.MediaTaskState
import com.promenar.infinity.domain.mediagen.Money
import com.promenar.infinity.domain.mediagen.PromptFormat
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class ProductionPhaseTwoPresentationTest {
    private val channel = MediaChannel("video-a", MediaGroup.VIDEO, MediaCapabilities(setOf(GenerationMode.TEXT, GenerationMode.REFERENCE)))
    private val look = CharacterLook("coat", "风衣")
    private val shot = Shot("SC01-01", "回头", durationSeconds = 4.0, status = ShotStatus.REVIEW,
        appearances = listOf(ShotAppearance("char-a", look.id, listOf("front"))))
    private val scene = Scene("SC01", 1, "外 码头 夜", storyboardLocked = true, shots = listOf(shot))
    private val episode = Episode("S1E01", 1, "雾港", scenes = listOf(scene))
    private val asset = Asset("char-a", AssetKind.CHARACTER, "林晚", status = AssetStatus.REVIEW, looks = listOf(look, CharacterLook("suit", "西服")))
    private val production = Production("p", "雾港", ProductionKind.SERIES, "productions/p", seasons = listOf(Season("S1", 1, episodes = listOf(episode))), assets = listOf(asset), createdAt = 1, updatedAt = 1)
    private val snapshot = ProductionSnapshot(production, 1, ManifestSync.InSync)
    private fun task(id: String = "task", state: MediaTaskState = MediaTaskState.AWAITING_APPROVAL, ref: String = "p:$id") = MediaTask(id,
        MediaGenerationRequest(channel.id, GenerationMode.TEXT, "夜色", emptyList(), ref), Money(BigDecimal.ONE, "CNY"), state = state)
    private fun preview(blocked: String? = null) = ShotPlanPreview(channel, true, "通道 A", ShotMode.TEXT, emptyList(), emptyList(), "夜色", false, PromptFormat.GENERIC, null, blocked, emptyList())

    @Test fun `页签批准数与待审数独立且待定场显示圆点`() {
        val p = production.copy(seasons = listOf(Season("S1", 1, episodes = listOf(episode.copy(scenes = listOf(scene, scene.copy(code = "SC02", storyboardLocked = false, shots = emptyList())))))))
        assertThat(p.tabSignals(listOf(task(), task("done", MediaTaskState.COMPLETED)))).isEqualTo(ProductionTabSignals(1, true, 1))
        assertThat(p.copy(experimental = true).tabSignals(emptyList()).storyboardDot).isTrue()
        assertThat(production.tabSignals(emptyList()).storyboardDot).isFalse()
        assertThat(p.tabSignals(listOf(task())).entryBadge).isEqualTo(2)
    }

    @Test fun `项目队列按完整前缀隔离且空前缀不扩大范围`() {
        val tasks = listOf(task(ref = "p:one"), task("other", ref = "p2:one"))
        assertThat(projectTasks(tasks, "p:")).containsExactly(tasks.first())
        assertThat(projectTasks(tasks, "")).isEmpty()
        assertThat(projectTasks(tasks, null)).isEmpty()
    }

    @Test fun `出片未锁定场只占一行且试验项目展开镜头`() {
        val p = production.copy(seasons = listOf(Season("S1", 1, episodes = listOf(episode.copy(scenes = listOf(scene.copy(storyboardLocked = false, shots = listOf(shot, shot.copy(code = "SC01-02")))))))))
        assertThat(p.outputItems(OutputFilter.ALL)).hasSize(1)
        assertThat(p.outputItems(OutputFilter.ALL).single()).isInstanceOf(OutputItem.UnlockedScene::class.java)
        assertThat(p.outputItems(OutputFilter.REVIEW)).isEmpty()
        assertThat(p.copy(experimental = true).outputItems(OutputFilter.REVIEW)).hasSize(2)
    }

    @Test fun `出片按状态筛选未生成不包含已有候选`() {
        val shots = ShotStatus.entries.mapIndexed { index, status -> shot.copy(code = "SC01-${index + 1}", status = status) }
        val p = production.copy(seasons = listOf(Season("S1", 1, episodes = listOf(episode.copy(scenes = listOf(scene.copy(shots = shots)))))))
        assertThat(p.outputItems(OutputFilter.ALL)).hasSize(5)
        listOf(OutputFilter.REVIEW, OutputFilter.REDO, OutputFilter.LOCKED, OutputFilter.TODO).forEach { assertThat(p.outputItems(it)).hasSize(1) }
        val withOldTake = p.copy(seasons = listOf(Season("S1", 1, episodes = listOf(episode.copy(scenes = listOf(scene.copy(shots = listOf(shot.copy(status = ShotStatus.TODO, takes = listOf(Take("old", "old.mp4", TakeSource("A"), 1)))))))))))
        assertThat(withOldTake.outputItems(OutputFilter.TODO)).isEmpty()
    }

    @Test fun `总览组装全部待处理类型并排除已批准变更`() {
        val change = ShotChangeRequest("req", episode.id, scene.code, shot.code, emptyMap(), ChangeSource.AGENT)
        val p = production.copy(changeRequests = listOf(change, change.copy(id = "approved", approved = true)), seasons = listOf(Season("S1", 1, episodes = listOf(episode.copy(scenes = listOf(scene, scene.copy(code = "SC02", storyboardLocked = false, shots = listOf(shot.copy(code = "SC02-01", status = ShotStatus.REDO)))))))))
        val items = p.overviewItems(listOf(task()))
        assertThat(items.map { it.javaClass.simpleName }).containsExactly("Review", "Approval", "Redo", "SceneLock", "ReferenceReview", "ChangeReview").inOrder()
        assertThat(p.copy(experimental = true).overviewItems(emptyList()).filterIsInstance<OverviewItem.SceneLock>()).isEmpty()
    }

    @Test fun `镜头默认页签仅由候选存在决定`() {
        assertThat(shot.defaultDetailTab()).isEqualTo(ShotDetailTab.STORYBOARD)
        assertThat(shot.copy(takes = listOf(Take("t", "t.mp4", TakeSource("A"), 1))).defaultDetailTab()).isEqualTo(ShotDetailTab.CANDIDATES)
    }

    @Test fun `规划摘要包含入参方式和用户改写标记且没有空分隔`() {
        assertThat(planSummary("A", "参考", "合图", true, "已改写")).isEqualTo("A · 参考 · 合图 · 已改写")
        assertThat(planSummary("A", "文生", null, false, "已改写")).isEqualTo("A · 文生")
        assertThat(planSummary("", "文生", "", false, "已改写")).isEqualTo("文生")
    }

    @Test fun `通道选择按模式过滤保留用户顺序并排除音频组`() {
        val first = channel.copy(id = "first")
        val second = channel.copy(id = "second", capabilities = channel.capabilities.copy(characterReferenceMode = CharacterReferenceMode.COMPOSITE))
        val textOnly = channel.copy(id = "text", capabilities = MediaCapabilities(setOf(GenerationMode.TEXT)))
        val audio = channel.copy(id = "audio", group = MediaGroup.AUDIO)
        assertThat(channelChoices(listOf(first, second, textOnly, audio), listOf("missing", "second", "second", "first", "text"), ShotMode.REFERENCE).map { it.id }).containsExactly("second", "first").inOrder()
        assertThat(channelChoices(listOf(first, second), listOf("second"), ShotMode.TEXT).map { it.id }).containsExactly("second", "first").inOrder()
        assertThat(channelChoices(listOf(textOnly), emptyList(), ShotMode.FIRST_LAST)).isEmpty()
    }

    @Test fun `场锁定需确认已存在的分镜且操作由用户动作驱动`() {
        val unlocked = scene.copy(storyboardLocked = false)
        assertThat(unlocked.requiresLockConfirmation()).isTrue()
        assertThat(unlocked.copy(shots = emptyList()).requiresLockConfirmation()).isFalse()
        assertThat(scene.requiresLockConfirmation()).isFalse()
        val action = sceneLockAction(ShotLocation(episode, unlocked, shot))
        val result = ProductionUserEngine.apply(snapshot, 1, action, 2)
        assertThat(result.production.findShot(episode.id, shot.code)!!.scene.storyboardLocked).isTrue()
        assertThat(result.production.findShot(episode.id, shot.code)!!.scene.changedBy).isEqualTo(ChangeSource.USER)
    }

    @Test fun `审批变更批准才应用拒绝保留原内容`() {
        val request = ShotChangeRequest("req", episode.id, scene.code, shot.code, mapOf("title" to ShotFieldChange(JsonPrimitive(shot.title), JsonPrimitive("新标题"))), ChangeSource.AGENT)
        val pending = snapshot.copy(production = production.copy(changeRequests = listOf(request)))
        val rejected = ProductionUserEngine.apply(pending, 1, changeApprovalAction(request, false), 2)
        assertThat(rejected.production.findShot(episode.id, shot.code)!!.shot.title).isEqualTo(shot.title)
        assertThat(rejected.production.changeRequests).isEmpty()
        val approved = ProductionUserEngine.apply(pending, 1, changeApprovalAction(request, true), 2)
        assertThat(approved.production.findShot(episode.id, shot.code)!!.shot.title).isEqualTo("新标题")
        assertThat(approved.production.changeRequests.single().approved).isTrue()
    }

    @Test fun `参考位多选可逆造型切换清空选择并写入用户操作`() {
        val appearance = shot.appearances.single()
        assertThat(appearance.toggleSlot(look, "side").referenceSlotIds).containsExactly("front", "side").inOrder()
        assertThat(appearance.toggleSlot(look, "front").referenceSlotIds).isEmpty()
        val switched = appearance.switchLook(asset.looks.last())
        assertThat(switched.referenceSlotIds).isEmpty()
        val result = ProductionUserEngine.apply(snapshot, 1, ProductionUserAction.SetCharacterLook(episode.id, shot.code, asset.id, switched.lookId), 2)
        assertThat(result.production.findShot(episode.id, shot.code)!!.shot.appearances.single()).isEqualTo(switched)
        val toggled = ProductionUserEngine.apply(snapshot, 1, ProductionUserAction.ToggleReferenceSlot(episode.id, shot.code, asset.id, "side", true), 2)
        assertThat(toggled.production.findShot(episode.id, shot.code)!!.shot.appearances.single().referenceSlotIds).containsExactly("front", "side")
    }

    @Test fun `未锁定阻断与无规划禁止生成试验只豁免锁定`() {
        assertThat(generationEnabled(false, false, preview(), false)).isFalse()
        assertThat(generationEnabled(false, true, preview(), false)).isTrue()
        assertThat(generationEnabled(true, false, preview("输入超限"), false)).isFalse()
        assertThat(generationEnabled(true, true, preview("输入超限"), false)).isFalse()
        assertThat(generationEnabled(true, false, null, false)).isFalse()
        assertThat(generationEnabled(true, false, preview(), true)).isFalse()
        assertThat(generationEnabled(true, false, preview().copy(channel = null), false)).isFalse()
    }

    @Test fun `队列仅待批可批准拒绝且仅失败可重试`() {
        assertThat(task().allowedQueueActions()).containsExactly(QueueAction.REJECT, QueueAction.APPROVE)
        assertThat(task(state = MediaTaskState.FAILED).allowedQueueActions()).containsExactly(QueueAction.RETRY)
        assertThat(task(state = MediaTaskState.RUNNING).allowedQueueActions()).isEmpty()
        assertThat(task(state = MediaTaskState.RUNNING).isActiveTask()).isTrue()
        assertThat(task(state = MediaTaskState.COMPLETED).isActiveTask()).isFalse()
    }

    @Test fun `费用仅汇总已完成实际记账且币种独立`() {
        val tasks = listOf(task().copy(actualCost = Money(BigDecimal.TEN, "CNY")), task("a", MediaTaskState.COMPLETED).copy(actualCost = Money(BigDecimal("1.23"), "CNY")), task("b", MediaTaskState.COMPLETED).copy(actualCost = Money(BigDecimal("2.00"), "USD")))
        assertThat(tasks.spentByCurrency()).containsExactly("CNY", BigDecimal("1.23"), "USD", BigDecimal("2.00"))
        assertThat(centsAmount(10001)).isEqualTo("100.01")
    }

    @Test fun `引用列表识别出场角色且时间线保留没有素材的缺口`() {
        assertThat(asset.referenceLocations(production).map { it.shot.code }).containsExactly(shot.code)
        assertThat(production.cutClips(false).single().path).isNull()
        assertThat(production.cutClips(true).single().title).isEqualTo(shot.title)
    }

    @Test fun `入口只汇总待审镜头和本项目待批准任务`() = runTest {
        val tasks = MutableStateFlow(listOf(task(ref = "p:shot"), task("other", ref = "other:shot")))
        val source = object : ProductionPanelSource {
            override fun observe(sessionId: String) = flowOf(snapshot)
            override fun observeEverBound(sessionId: String) = flowOf(true)
            override suspend fun apply(sessionId: String, expectedRevision: Long, action: ProductionUserAction): ProductionSnapshot = error("不写入")
            override suspend fun resolveManifest(sessionId: String, resolution: ManifestResolution): ProductionSnapshot = error("不写入")
        }
        val queue = object : MediaGenerationQueue {
            override fun observeTasks() = tasks
            override fun observeTask(id: String) = flowOf(tasks.value.firstOrNull { it.id == id })
            override suspend fun submit(request: MediaGenerationRequest): MediaTask = error("不提交")
            override suspend fun approve(id: String) = Unit
            override suspend fun reject(id: String) = Unit
            override suspend fun retry(id: String) = Unit
        }
        val state = source.observeEntry("session", queue) { "${it.id}:" }.first()
        assertThat(state.pending).isEqualTo(1)
        assertThat(state.pendingApprovals).isEqualTo(1)
        assertThat(state.attentionCount).isEqualTo(2)
        assertThat(productionEntryState(null, false, tasks.value).attentionCount).isEqualTo(0)
    }
    @Test fun `模式切换保留兼容通道并让不兼容通道回默认`() {
        val location = ShotLocation(episode, scene, shot.copy(channelId = channel.id))
        assertThat(modeSelectionAction(location, ShotMode.REFERENCE, listOf(channel), emptyList()).channelId).isEqualTo(channel.id)
        assertThat(modeSelectionAction(location, ShotMode.FIRST_LAST, listOf(channel), emptyList()).channelId).isNull()
    }

    @Test fun `角色参考包完整默认位可审核且未填满不可通过`() {
        val incomplete = asset.copy(looks = listOf(look))
        assertThat(incomplete.actions().canApprove).isFalse()
        val complete = asset.copy(looks = listOf(look.copy(slots = look.slots.map { it.copy(path = "assets/${it.id}.png") })))
        assertThat(complete.actions().canApprove).isTrue()
    }

    @Test fun `阶段统计与默认通道使用当前快照和用户顺序`() {
        assertThat(production.stageProgressCounts(ProductionStage.STORYBOARD)).isEqualTo(1 to 1)
        assertThat(production.stageProgressCounts(ProductionStage.SOUND)).isEqualTo(0 to 0)
        assertThat(production.stageProgressCounts(ProductionStage.EDIT)).isNull()
        assertThat(resolvedShotChannel(shot, listOf(channel), listOf(channel.id))).isEqualTo(channel)
        assertThat(resolvedShotChannel(shot.copy(channelId = "missing"), listOf(channel), emptyList())).isNull()
    }

}
