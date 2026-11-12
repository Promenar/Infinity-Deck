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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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

    @Test fun `任务行标题还原镜头编号与标题且只认本项目`() {
        val ref = ProductionSourceRef("p", "S1E01", "SC01-01")
        assertThat(production.taskTitle(ref.encode())).isEqualTo("SC01-01 · 回头")
        assertThat(production.taskTitle(ref.copy(dialogueIndex = 0).encode())).isEqualTo("SC01-01 · 回头 · #1")
        assertThat(production.taskTitle(ref.copy(shotCode = "SC09-09").encode())).isEqualTo("SC09-09")
        assertThat(production.taskTitle(ref.copy(productionId = "p2").encode())).isNull()
        assertThat(production.taskTitle("p:task")).isNull()
    }

    @Test fun `页签待审数只看镜头状态且待定场显示圆点`() {
        val p = production.copy(seasons = listOf(Season("S1", 1, episodes = listOf(episode.copy(scenes = listOf(scene, scene.copy(code = "SC02", storyboardLocked = false, shots = emptyList())))))))
        assertThat(p.tabSignals()).isEqualTo(ProductionTabSignals(storyboardDot = true, reviews = 1))
        assertThat(production.tabSignals()).isEqualTo(ProductionTabSignals(storyboardDot = false, reviews = 1))
    }

    @Test fun `项目队列按完整前缀隔离且空前缀不扩大范围`() {
        val tasks = listOf(task(ref = "production:p/episode:e/shot:s"), task("other", ref = "production:p2/episode:e/shot:s"))
        assertThat(projectTasks(tasks, ProductionSourceRef.projectPrefix("p"))).containsExactly(tasks.first())
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

    @Test fun `未锁定场里已有候选的镜头仍出现在出片与待审筛选中`() {
        val reviewed = shot.copy(code = "SC01-02", status = ShotStatus.REVIEW, takes = listOf(Take("t1", "t1.mp4", TakeSource("ComfyUI"), 1)))
        val p = production.copy(seasons = listOf(Season("S1", 1, episodes = listOf(episode.copy(scenes = listOf(scene.copy(storyboardLocked = false, shots = listOf(shot.copy(status = ShotStatus.TODO), reviewed))))))))
        val all = p.outputItems(OutputFilter.ALL)
        assertThat(all).hasSize(2)
        assertThat(all.first()).isInstanceOf(OutputItem.UnlockedScene::class.java)
        assertThat((all.last() as OutputItem.ShotRow).location.shot.code).isEqualTo("SC01-02")
        assertThat(p.outputItems(OutputFilter.REVIEW).map { (it as OutputItem.ShotRow).location.shot.code }).containsExactly("SC01-02")
        assertThat(p.outputItems(OutputFilter.TODO)).isEmpty()
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
        val cuts = listOf(CutVersion("cut-1", episode.id, 1, 1, emptyList()), CutVersion("cut-2", episode.id, 2, 2, emptyList()))
        val p = production.copy(changeRequests = listOf(change, change.copy(id = "approved", approved = true)), cuts = cuts, seasons = listOf(Season("S1", 1, episodes = listOf(episode.copy(scenes = listOf(scene, scene.copy(code = "SC02", storyboardLocked = false, shots = listOf(shot.copy(code = "SC02-01", status = ShotStatus.REDO)))))))))
        val items = p.overviewItems()
        assertThat(items.map { it.javaClass.simpleName }).containsExactly("Review", "Redo", "SceneLock", "ReferenceReview", "ChangeReview", "CutReview").inOrder()
        assertThat(items.filterIsInstance<OverviewItem.ChangeReview>().single().request.id).isEqualTo("req")
        assertThat(items.filterIsInstance<OverviewItem.CutReview>().single().cut.id).isEqualTo("cut-2")
        assertThat(p.copy(experimental = true).overviewItems().filterIsInstance<OverviewItem.SceneLock>()).isEmpty()
        val reviewed = p.copy(reviews = listOf(ReviewEntry(ReviewTarget.cut("cut-2"), ReviewKind.APPROVE, at = 1, round = 1)))
        assertThat(reviewed.overviewItems().filterIsInstance<OverviewItem.CutReview>()).isEmpty()
    }

    @Test fun `场与改动请求的结论未发送时仍待处理发送后退出`() {
        val unlocked = scene.copy(storyboardLocked = false)
        assertThat(unlocked.awaitsReview(production, episode)).isTrue()
        assertThat(scene.awaitsReview(production, episode)).isFalse()
        val target = ReviewTarget.scene(episode.id, scene.code)
        val unsent = production.copy(reviews = listOf(ReviewEntry(target, ReviewKind.APPROVE, at = 1)))
        assertThat(scene.awaitsReview(unsent, episode)).isTrue()
        val sent = production.copy(reviews = listOf(ReviewEntry(target, ReviewKind.REJECT, at = 1, round = 1)))
        assertThat(unlocked.awaitsReview(sent, episode)).isFalse()
        val suggestionOnly = production.copy(reviews = listOf(ReviewEntry(target, ReviewKind.SUGGEST, "暖一点", at = 1)))
        assertThat(unlocked.awaitsReview(suggestionOnly, episode)).isTrue()

        val request = ShotChangeRequest("req", episode.id, scene.code, shot.code, emptyMap(), ChangeSource.AGENT)
        assertThat(request.awaitsReview(production)).isTrue()
        assertThat(request.copy(approved = true).awaitsReview(production)).isFalse()
        val change = ReviewTarget.change("req")
        assertThat(request.awaitsReview(production.copy(reviews = listOf(ReviewEntry(change, ReviewKind.REJECT, at = 1))))).isTrue()
        assertThat(request.awaitsReview(production.copy(reviews = listOf(ReviewEntry(change, ReviewKind.REJECT, at = 1, round = 2))))).isFalse()
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

    @Test fun `参考位多选可逆造型切换默认三位并经审批写入`() {
        val appearance = shot.appearances.single()
        assertThat(appearance.toggleSlot(look, "side").referenceSlotIds).containsExactly("front", "side").inOrder()
        assertThat(appearance.toggleSlot(look, "front").referenceSlotIds).isEmpty()
        val switched = appearance.switchLook(asset.looks.last())
        assertThat(switched.referenceSlotIds).containsExactly("front", "side", "back").inOrder()
        val result = ProductionUserEngine.apply(snapshot, 1, ProductionUserAction.SetCharacterLook(episode.id, shot.code, asset.id, switched.lookId), 2)
        assertThat(result.production.findShot(episode.id, shot.code)!!.shot.appearances.single()).isEqualTo(appearance)
        val request = result.production.changeRequests.single()
        assertThat(request.approved).isFalse()
        val approved = ProductionUserEngine.apply(result, 2, ProductionUserAction.Review(ReviewTarget.change(request.id), ReviewKind.APPROVE, null), 3)
        assertThat(approved.production.findShot(episode.id, shot.code)!!.shot.appearances.single()).isEqualTo(switched)
        val toggled = ProductionUserEngine.apply(snapshot, 1, ProductionUserAction.ToggleReferenceSlot(episode.id, shot.code, asset.id, "side", true), 2)
        assertThat(toggled.production.findShot(episode.id, shot.code)!!.shot.appearances.single().referenceSlotIds).containsExactly("front", "side")
    }

    @Test fun `只有进行中的任务可以取消`() {
        listOf(MediaTaskState.QUEUED, MediaTaskState.SUBMITTING, MediaTaskState.RUNNING, MediaTaskState.DOWNLOADING).forEach {
            assertThat(task(state = it).canCancel()).isTrue()
        }
        listOf(MediaTaskState.COMPLETED, MediaTaskState.FAILED, MediaTaskState.CANCELED, MediaTaskState.AWAITING_APPROVAL).forEach {
            assertThat(task(state = it).canCancel()).isFalse()
        }
        assertThat(task(state = MediaTaskState.RUNNING).isActiveTask()).isTrue()
        assertThat(task(state = MediaTaskState.COMPLETED).isActiveTask()).isFalse()
    }

    @Test fun `引用列表识别出场角色且时间线保留没有素材的缺口`() {
        assertThat(asset.referenceLocations(production).map { it.shot.code }).containsExactly(shot.code)
        assertThat(production.cutClips(false).single().path).isNull()
        assertThat(production.cutClips(true).single().title).isEqualTo(shot.title)
    }

    @Test fun `入口汇总待审镜头与未发送意见且运行点只看本项目任务`() = runTest {
        val tasks = MutableStateFlow(listOf(task(state = MediaTaskState.QUEUED, ref = "production:other/episode:e/shot:s")))
        val reviewed = snapshot.copy(production = production.copy(reviews = listOf(
            ReviewEntry(ReviewTarget.scene(episode.id, scene.code), ReviewKind.APPROVE, at = 1),
            ReviewEntry(ReviewTarget.shot(episode.id, shot.code), ReviewKind.SUGGEST, "慢一点", at = 2),
            ReviewEntry(ReviewTarget.script(episode.id), ReviewKind.APPROVE, at = 0, round = 1),
        )))
        val source = object : ProductionPanelSource {
            override fun observe(sessionId: String) = flowOf(reviewed)
            override fun observeEverBound(sessionId: String) = flowOf(true)
            override suspend fun apply(sessionId: String, expectedRevision: Long, action: ProductionUserAction): ProductionSnapshot = error("不写入")
            override suspend fun resolveManifest(sessionId: String, resolution: ManifestResolution): ProductionSnapshot = error("不写入")
        }
        val queue = object : MediaGenerationQueue {
            override fun observeTasks() = tasks
            override fun observeTask(id: String) = flowOf(tasks.value.firstOrNull { it.id == id })
            override suspend fun submit(request: MediaGenerationRequest): MediaTask = error("不提交")
            override suspend fun cancel(id: String) = Unit
        }
        val state = source.observeEntry("session", queue) { ProductionSourceRef.projectPrefix(it.id) }.first()
        assertThat(state.pending).isEqualTo(1)
        assertThat(state.unsent).isEqualTo(2)
        assertThat(state.attentionCount).isEqualTo(3)
        assertThat(state.running).isFalse()
        assertThat(productionEntryState(null, false, tasks.value).attentionCount).isEqualTo(0)
    }
    @Test fun `模式切换保留兼容通道并让不兼容通道回默认`() {
        val location = ShotLocation(episode, scene, shot.copy(channelId = channel.id))
        assertThat(modeSelectionAction(location, ShotMode.REFERENCE, listOf(channel), emptyList()).channelId).isEqualTo(channel.id)
        assertThat(modeSelectionAction(location, ShotMode.FIRST_LAST, listOf(channel), emptyList()).channelId).isNull()
    }

    @Test fun `阶段统计与默认通道使用当前快照和用户顺序`() {
        assertThat(production.stageProgressCounts(ProductionStage.STORYBOARD)).isEqualTo(1 to 1)
        assertThat(production.stageProgressCounts(ProductionStage.SOUND)).isEqualTo(0 to 0)
        assertThat(production.stageProgressCounts(ProductionStage.EDIT)).isNull()
        assertThat(resolvedShotChannel(shot, listOf(channel), listOf(channel.id))).isEqualTo(channel)
        assertThat(resolvedShotChannel(shot.copy(channelId = "missing"), listOf(channel), emptyList())).isNull()
    }

    @Test fun `项目任务严格解析字段隔离前缀碰撞和特殊字符`() {
        val p1 = task(ref = "production:p1/episode:e/shot:s")
        val p10 = task("p10", ref = "production:p10/episode:e/shot:s")
        val special = task("special", ref = "production:p%2F1%20%2B%3A%25%E9%9B%BE/episode:e/shot:s/dialogue:0")
        val malformed = task("malformed", ref = "production:p1/episode:e/shot:s/unknown:0")
        val tasks = listOf(p1, p10, special, malformed)
        assertThat(ProductionSourceRef.projectPrefix("p/1 +:%雾")).isEqualTo("production:p%2F1%20%2B%3A%25%E9%9B%BE/")
        assertThat(projectTasks(tasks, ProductionSourceRef.projectPrefix("p1"))).containsExactly(p1)
        assertThat(projectTasks(tasks, ProductionSourceRef.projectPrefix("p10"))).containsExactly(p10)
        assertThat(projectTasks(tasks, ProductionSourceRef.projectPrefix("p/1 +:%雾"))).containsExactly(special)
        assertThat(projectTasks(tasks, "production:p1")).isEmpty()
        assertThat(projectTasks(tasks, "production:p1/episode:")).isEmpty()
    }

    @Test fun `待登记完成任务留在任务区并使用待登记状态`() {
        val pending = task("pending", MediaTaskState.COMPLETED)
        val done = task("done", MediaTaskState.COMPLETED)
        val active = task("active", MediaTaskState.RUNNING)
        val pendingIds = setOf("pending", "active", "other-project")
        assertThat(pending.awaitingRegistration(pendingIds)).isTrue()
        assertThat(active.awaitingRegistration(pendingIds)).isFalse()
        assertThat(pending.taskStatusRes(pendingIds)).isEqualTo(com.promenar.infinity.R.string.production_wire_pending_registration)
        assertThat(done.taskStatusRes(pendingIds)).isEqualTo(com.promenar.infinity.R.string.p2_task_completed)
        assertThat(listOf(pending, done, active).overviewQueueTasks(pendingIds)).containsExactly(pending, active).inOrder()
        assertThat(listOf(pending, done, active).recentQueueTasks(pendingIds)).containsExactly(done)
        assertThat(listOf(pending, done).recentQueueTasks(emptySet())).containsExactly(done, pending).inOrder()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `入口持续观察队列运行完成与项目切换`() = runTest {
        val current = MutableStateFlow<ProductionSnapshot?>(snapshot.copy(production = production.copy(id = "p1")))
        val tasks = MutableStateFlow(listOf(
            task("ours", ref = "production:p1/episode:e/shot:s"),
            task("other", ref = "production:p10/episode:e/shot:s"),
            task("audio", ref = "production:p1/episode:e/shot:s/dialogue:0"),
        ))
        val source = object : ProductionPanelSource {
            override fun observe(sessionId: String) = current
            override fun observeEverBound(sessionId: String) = flowOf(true)
            override suspend fun apply(sessionId: String, expectedRevision: Long, action: ProductionUserAction): ProductionSnapshot = error("不写入")
            override suspend fun resolveManifest(sessionId: String, resolution: ManifestResolution): ProductionSnapshot = error("不写入")
        }
        val queue = object : MediaGenerationQueue {
            override fun observeTasks() = tasks
            override fun observeTask(id: String) = flowOf(tasks.value.firstOrNull { it.id == id })
            override suspend fun submit(request: MediaGenerationRequest): MediaTask = error("不提交")
            override suspend fun cancel(id: String) = Unit
        }
        assertThat(source.observeEntry("session").first().attentionCount).isEqualTo(1)
        var entry = ProductionEntryState.Disabled
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.observeEntry("session", queue) { ProductionSourceRef.projectPrefix(it.id) }.collect { entry = it }
        }
        runCurrent()
        assertThat(entry.attentionCount).isEqualTo(1)
        assertThat(entry.running).isFalse()
        tasks.value = tasks.value.map { if (it.id == "ours") it.copy(state = MediaTaskState.RUNNING) else it }
        runCurrent()
        assertThat(entry.attentionCount).isEqualTo(1)
        assertThat(entry.running).isTrue()
        tasks.value = tasks.value.map { if (it.id in setOf("ours", "audio")) it.copy(state = MediaTaskState.COMPLETED) else it }
        runCurrent()
        assertThat(entry.running).isFalse()
        tasks.value = tasks.value.map { if (it.id == "other") it.copy(state = MediaTaskState.RUNNING) else it }
        runCurrent()
        assertThat(entry.running).isFalse()
        current.value = snapshot.copy(production = production.copy(id = "p10"))
        runCurrent()
        assertThat(entry.running).isTrue()
        assertThat(entry.attentionCount).isEqualTo(1)
        current.value = null
        runCurrent()
        assertThat(entry.attentionCount).isEqualTo(0)
        assertThat(entry.hasProduction).isFalse()
    }

}
