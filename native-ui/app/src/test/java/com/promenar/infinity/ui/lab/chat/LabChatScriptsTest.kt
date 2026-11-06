package com.promenar.infinity.ui.lab.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.hub.HubSessionActivity
import com.promenar.infinity.ui.lab.LabScenes
import com.promenar.infinity.ui.lab.stage.LabStateScene
import com.promenar.infinity.ui.lab.stage.LabWorkflowScene
import org.junit.Test

class LabChatScriptsTest {
    private val languages = listOf(true, false)
    private val ipAddress = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b")

    // ── 时间轴 ──

    @Test
    fun `ADE 阶段只增不减并在结尾完成生成`() = languages.forEach { zh ->
        val timeline = adeTimeline(LabAdeText(zh))
        val stages = (0..timeline.durationMs step 50).map { timeline.stateAt(it).stage }
        assertThat(stages).isInOrder()
        assertThat(stages.last()).isEqualTo(ADE_DONE)
        assertThat(adeFrame(timeline.stateAt(timeline.durationMs)).screen.uiState.isGenerating).isFalse()
    }

    @Test
    fun `ops 阶段只增不减并在结尾完成生成`() = languages.forEach { zh ->
        val timeline = opsTimeline(LabOpsText(zh))
        val stages = (0..timeline.durationMs step 50).map { timeline.stateAt(it).stage }
        assertThat(stages).isInOrder()
        assertThat(stages.last()).isEqualTo(OPS_DONE)
    }

    @Test
    fun `流式正文在下一阶段开始前打完`() {
        val text = LabAdeText(true)
        val timeline = adeTimeline(text)
        // 每段正文在所属阶段的最后一刻都已完整。
        (0..timeline.durationMs step 50).zipWithNext().forEach { (t, next) ->
            val now = timeline.stateAt(t)
            if (timeline.stateAt(next).stage != now.stage && now.partial != null) {
                val full = adeFrame(now.copy(partial = null)).screen.uiState.messages.last().content
                assertThat(now.partial).isEqualTo(full)
            }
        }
    }

    @Test
    fun `同一时刻总是得到同一画面`() {
        val timeline = adeTimeline(LabAdeText(false))
        listOf(0L, 7_000L, 21_500L, 31_000L, timeline.durationMs).forEach { t ->
            assertThat(adeFrame(timeline.stateAt(t))).isEqualTo(adeFrame(timeline.stateAt(t)))
        }
    }

    // ── 与真实组件的约定 ──

    @Test
    fun `子代理分组以 agent_task 调用步骤 id 为键`() {
        val frame = adeFrame(AdeState(LabAdeText(true), stage = ADE_TASK, explorerSteps = listOf(4, 6)))
        val call = frame.screen.uiState.messages.flatMap { it.executionSteps.orEmpty() }
            .single { it.toolName == "agent_task" && it.type == "tool_call" }
        assertThat(frame.subagentBatches.keys).containsExactly(call.id)
        val batch = frame.subagentBatches.getValue(call.id)
        assertThat(batch.stepId).isEqualTo(call.id)
        assertThat(batch.runs.map { it.step }).containsExactly(4, 6).inOrder()
        assertThat(batch.wait).isNotNull()
    }

    @Test
    fun `运行中的快速测试挂实时输出并位于最后一条助手消息`() {
        val frame = adeFrame(AdeState(LabAdeText(true), stage = ADE_QUICK_RUN, quickLines = 3))
        val last = frame.screen.uiState.messages.last()
        val call = last.executionSteps.orEmpty().single()
        assertThat(call.type).isEqualTo("tool_call")
        assertThat(frame.liveOutput.getValue(call.id)).hasSize(3)
        assertThat(frame.screen.uiState.isGenerating).isTrue()
    }

    @Test
    fun `分支方案只在等待选择时出现，派发后转为分支与 worktree`() {
        val text = LabAdeText(true)
        val pending = adeFrame(AdeState(text, stage = ADE_BRANCH))
        assertThat(pending.pendingPlan?.plan?.title).isEqualTo(text.branchTitle)
        assertThat(pending.branches).isEmpty()
        val dispatched = adeFrame(AdeState(text, stage = ADE_DISPATCHED))
        assertThat(dispatched.pendingPlan).isNull()
        assertThat(dispatched.branches.single().sessionId).isEqualTo(ADE_BRANCH_SESSION)
        assertThat(dispatched.worktrees.map { it.branch }).containsExactly("main", "config-toml")
    }

    @Test
    fun `计划进度与参考画面一致`() {
        val text = LabAdeText(true)
        val panel = adeFrame(AdeState(text, stage = ADE_PLAN_UPDATE)).screen.taskPanel!!
        assertThat(panel.doneCount).isEqualTo(3)
        assertThat(panel.totalCount).isEqualTo(6)
        assertThat(panel.currentStepTitle).isEqualTo(text.planSteps[3])
        assertThat(adeFrame(AdeState(text, stage = ADE_SUMMARY)).screen.taskPanel!!.isCompleted).isFalse()
        assertThat(adeFrame(AdeState(text, stage = ADE_DONE)).screen.taskPanel!!.isCompleted).isTrue()
    }

    @Test
    fun `审批队列与待批准调用一一对应`() {
        listOf(OPS_APPROVE1, OPS_APPROVE2, OPS_APPROVE3).forEach { stage ->
            val ui = opsFrame(OpsState(LabOpsText(true), stage = stage)).screen.uiState
            val pending = ui.messages.last().pendingApprovalToolIds.orEmpty()
            assertThat(pending).isNotEmpty()
            assertThat(ui.approvalRequest!!.calls.map { it.toolCallId }).isEqualTo(pending)
            assertThat(ui.approvalRequest!!.assistantMessageId).isEqualTo(ui.messages.last().id)
        }
        assertThat(opsFrame(OpsState(LabOpsText(true), stage = OPS_FIX_DONE)).screen.uiState.approvalRequest).isNull()
    }

    @Test
    fun `平移到墙钟后时间差保持不变`() {
        val frame = adeFrame(AdeState(LabAdeText(true), stage = ADE_BG_TOOLS))
        val shifted = frame.atWallClock(1_000_000)
        val steps = frame.screen.uiState.messages.flatMap { it.executionSteps.orEmpty() }
        val moved = shifted.screen.uiState.messages.flatMap { it.executionSteps.orEmpty() }
        assertThat(moved.map { it.timestamp }).isEqualTo(steps.map { it.timestamp + 1_000_000 })
        assertThat(shifted.jobs.single().startedAtMillis).isEqualTo(frame.jobs.single().startedAtMillis + 1_000_000)
        assertThat(shifted.screen.generationStartedAt).isEqualTo(1_000_000)
        val run = frame.subagentBatches.values.single().runs.first()
        val movedRun = shifted.subagentBatches.values.single().runs.first()
        assertThat(movedRun.startedAt).isEqualTo(run.startedAt + 1_000_000)
        assertThat(movedRun.endedAt).isEqualTo(run.endedAt!! + 1_000_000)
    }

    // ── 首页列表与 Git 提交 ──

    @Test
    fun `ADE 列表在派发后才出现嵌在主会话下的分支，生成中与分支都标为生成中`() {
        val text = LabAdeText(true)
        val before = labHubList(LabHubStory.ADE, adeFrame(AdeState(text, stage = ADE_BRANCH)), zh = true)
        assertThat(before.sessions.map { it.title }).containsExactly(text.sessionTitle)
        assertThat(before.activity.getValue(before.selectedSessionId)).isEqualTo(HubSessionActivity.GENERATING)
        val after = labHubList(LabHubStory.ADE, adeFrame(AdeState(text, stage = ADE_DISPATCHED)), zh = true)
        val branch = after.sessions.single { it.parentSessionId != null }
        assertThat(branch.title).isEqualTo(text.branchTitle)
        assertThat(branch.parentSessionId).isEqualTo(after.selectedSessionId)
        assertThat(after.activity.getValue(branch.id)).isEqualTo(HubSessionActivity.GENERATING)
        val done = labHubList(LabHubStory.ADE, adeFrame(AdeState(text, stage = ADE_DONE)), zh = true)
        assertThat(done.activity).doesNotContainKey(done.selectedSessionId)
    }

    @Test
    fun `排障列表把当前会话排在开发会话之前，待批准时标为等待批准`() = languages.forEach { zh ->
        val ops = LabOpsText(zh)
        val list = labHubList(LabHubStory.OPS, opsFrame(OpsState(ops, stage = OPS_APPROVE3)).atWallClock(1_000_000_000), zh)
        assertThat(list.sessions.maxBy { it.updatedAt }.id).isEqualTo(list.selectedSessionId)
        assertThat(list.sessions.map { it.title }).containsExactly(ops.sessionTitle, LabAdeText(zh).sessionTitle, LabAdeText(zh).branchTitle)
        assertThat(list.activity.getValue(list.selectedSessionId)).isEqualTo(HubSessionActivity.AWAITING_APPROVAL)
    }

    @Test
    fun `提交记录六条、从新到旧且父提交首尾相接`() {
        val now = 1_793_000_000_000L
        val commits = adeCommitLog(now).commits
        assertThat(commits).hasSize(6)
        assertThat(commits.first().shortSha).isEqualTo("4831233")
        assertThat(commits.map { it.authorTimeSeconds }).isInStrictOrder(Comparator.reverseOrder<Long>())
        assertThat(commits.first().authorTimeSeconds).isAtMost(now / 1000)
        commits.zipWithNext().forEach { (child, parent) -> assertThat(child.parents).containsExactly(parent.sha) }
        assertThat(commits.last().parents).isEmpty()
        assertThat(commits.map { it.authorName }.toSet()).containsExactly("Starlog Demo")
    }

    // ── 隐私与登记 ──

    @Test
    fun `画面不含真实用户名、家目录与网络地址`() = languages.forEach { zh ->
        val ade = adeTimeline(LabAdeText(zh))
        val ops = opsTimeline(LabOpsText(zh))
        val frames = (0..ade.durationMs step 500).map { adeFrame(ade.stateAt(it)).toString() } +
            (0..ops.durationMs step 500).map { opsFrame(ops.stateAt(it)).toString() }
        frames.forEach { dump ->
            assertThat(dump).doesNotContain("promenar")
            assertThat(dump).doesNotContain("/home/")
            assertThat(dump).doesNotContain("chown")
            assertThat(ipAddress.containsMatchIn(dump)).isFalse()
        }
    }

    @Test
    fun `会话与检查器场景都已登记`() {
        val workflows = listOf("chat.workflow.ade-json", "chat.workflow.ops-502")
        val states = listOf(
            "chat.state.subagents", "chat.state.background-job", "chat.state.branch-plan", "chat.state.ops-approval", "chat.state.ops-report",
            "inspector.state.plan", "inspector.state.git-worktrees", "inspector.state.git-commits",
        )
        workflows.forEach { assertThat(LabScenes.scene(it)).isInstanceOf(LabWorkflowScene::class.java) }
        states.forEach { assertThat(LabScenes.scene(it)).isInstanceOf(LabStateScene::class.java) }
    }
}
