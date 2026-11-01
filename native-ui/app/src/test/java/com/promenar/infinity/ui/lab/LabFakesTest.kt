package com.promenar.infinity.ui.lab

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.memory.LongTermMemory
import com.promenar.infinity.data.memory.LongTermMemoryKind
import com.promenar.infinity.data.subagent.SubagentBatch
import com.promenar.infinity.domain.branch.BranchContextMode
import com.promenar.infinity.domain.branch.BranchDecision
import com.promenar.infinity.domain.branch.BranchPlan
import com.promenar.infinity.domain.branch.BranchPlanAnswer
import com.promenar.infinity.domain.branch.BranchWorkMode
import com.promenar.infinity.domain.branch.PendingBranchPlan
import com.promenar.infinity.domain.branch.WorktreeEntry
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.jobs.BackgroundJobExitAction
import com.promenar.infinity.domain.jobs.BackgroundJobLog
import com.promenar.infinity.domain.jobs.BackgroundJobRunner
import com.promenar.infinity.domain.jobs.BackgroundJobState
import com.promenar.infinity.domain.schedule.ScheduleTrigger
import com.promenar.infinity.domain.schedule.ScheduledTask
import com.promenar.infinity.ui.chat.MemoryChange
import com.promenar.infinity.ui.chat.MemoryChangeAction
import com.promenar.infinity.ui.chat.MemorySnapshot
import com.promenar.infinity.ui.lab.fakes.LabAgentActivityAccess
import com.promenar.infinity.ui.lab.fakes.LabBackgroundJobController
import com.promenar.infinity.ui.lab.fakes.LabBranchAccess
import com.promenar.infinity.ui.lab.fakes.LabBranchControls
import com.promenar.infinity.ui.lab.fakes.LabFakes
import com.promenar.infinity.ui.lab.fakes.LabMemoryChangeHost
import com.promenar.infinity.ui.lab.fakes.LabScheduledTaskController
import com.promenar.infinity.ui.lab.fakes.LabSubagentControls
import com.promenar.infinity.ui.lab.fakes.LabSubagentRunsAccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LabFakesTest {
    @Test
    fun `分支入口按会话隔离且方案操作只记录和回调`() = runTest {
        var resolved: PendingBranchPlan? = null
        val fake = LabBranchControls(onResolvePlan = { pending, _ -> resolved = pending })
        val pending = PendingBranchPlan("s1", "m1", "c1", BranchPlan("方案", "目标", approvalMode = "workspace"), "workspace")
        val answer = BranchPlanAnswer(BranchDecision.DISPATCH, BranchWorkMode.SHARED, BranchContextMode.FRESH, "workspace")
        fake.setPendingPlan("s1", pending)
        assertThat(fake.observePendingPlan("s1").first()).isEqualTo(pending)
        assertThat(fake.observePendingPlan("s2").first()).isNull()
        fake.resolvePlan(pending, answer)
        assertThat(resolved).isEqualTo(pending)
        assertThat(fake.observePendingPlan("s1").first()).isEqualTo(pending)
        fake.setPendingPlan("s1", null)
        assertThat(fake.observePendingPlan("s1").first()).isNull()
        val tree = WorktreeEntry("/demo", "demo", "sample", false)
        fake.setWorktrees("s1", listOf(tree))
        assertThat(fake.listWorktrees("s1").getOrThrow()).containsExactly(tree)
        assertThat(fake.listWorktrees("s2").getOrThrow()).isEmpty()
        assertThat(fake.removeWorktree("s1").isSuccess).isTrue()
        assertThat(fake.calls.value.map { it.name }).containsExactly("resolvePlan", "listWorktrees", "listWorktrees", "removeWorktree").inOrder()
        assertThat(fake.calls.value.first().arguments).containsExactly(pending, answer).inOrder()
    }

    @Test
    fun `分支列表可观察且父会话之间互不串数据`() = runTest {
        val fake = LabBranchControls()
        val branch = com.promenar.infinity.domain.branch.BranchSummary("child", "s1", "分支", com.promenar.infinity.domain.branch.BranchActivity.GENERATING, updatedAt = 1)
        val existingSubscription = fake.observeBranches("s1")
        fake.setBranches("s1", listOf(branch))
        assertThat(existingSubscription.first()).containsExactly(branch)
        assertThat(fake.observeBranches("s2").first()).isEmpty()
        fake.setBranches("s1", emptyList())
        assertThat(existingSubscription.first()).isEmpty()
    }

    @Test
    fun `分支访问包装允许切换元数据且打开会话仅回调`() {
        var opened = ""
        val fake = LabBranchAccess("s1", LabBranchControls(), onOpenSession = { opened = it })
        fake.setContext(parentSessionId = "parent", parentTitle = "主会话", branchPlanTitle = "计划")
        assertThat(fake.access.value.parentTitle).isEqualTo("主会话")
        fake.access.value.onOpenSession("child")
        assertThat(opened).isEqualTo("child")
        assertThat(fake.calls.value.single().arguments).containsExactly("child")
    }

    @Test
    fun `后台任务按归属筛选排序且日志与停止完全驻留内存`() = runTest {
        var stopped = ""
        val fake = LabBackgroundJobController(onStop = { stopped = it; true })
        val old = job("old", "s1", 1)
        val recent = job("recent", "s2", 5)
        fake.setJobs(listOf(old, recent))
        assertThat(fake.observe(null).first()).containsExactly(recent, old).inOrder()
        assertThat(fake.observe("s1").first()).containsExactly(old)
        fake.setLog("s1", "log", BackgroundJobLog("脚本日志", false))
        assertThat(fake.readLog("s1", "log").text).isEqualTo("脚本日志")
        assertThat(fake.readLog("s2", "log").text).doesNotContain("脚本日志")
        assertThat(fake.stop("old")).isTrue()
        assertThat(stopped).isEqualTo("old")
        assertThat(fake.jobs.value.first().state).isEqualTo(BackgroundJobState.RUNNING)
        assertThat(fake.calls.value.map { it.name }).containsExactly("readLog", "readLog", "stop").inOrder()
    }

    @Test
    fun `缺失或终态后台任务默认不能停止`() = runTest {
        val fake = LabBackgroundJobController()
        fake.setJobs(listOf(job("running", "s", 1), job("done", "s", 2).copy(state = BackgroundJobState.EXITED)))
        assertThat(fake.stop("running")).isTrue()
        assertThat(fake.stop("done")).isFalse()
        assertThat(fake.stop("missing")).isFalse()
    }

    @Test
    fun `定时任务可观察且暂停恢复删除不触碰脚本状态`() = runTest {
        val callbacks = mutableListOf<String>()
        val fake = LabScheduledTaskController(
            onPause = { callbacks += "pause:$it" }, onResume = { callbacks += "resume:$it" }, onDelete = { callbacks += "delete:$it" },
        )
        val task = ScheduledTask("task", "s1", "演示", "提示", ScheduleTrigger.Interval(60), false, false, true, null, 0, null, null, null, null, 1)
        fake.setTasks(listOf(task))
        assertThat(fake.observe("s1").first()).containsExactly(task)
        assertThat(fake.observe("s2").first()).isEmpty()
        assertThat(fake.observe(null).first()).containsExactly(task)
        fake.pause("task"); fake.resume("task"); fake.delete("task")
        assertThat(callbacks).containsExactly("pause:task", "resume:task", "delete:task").inOrder()
        assertThat(fake.calls.value.map { it.name }).containsExactly("pause", "resume", "delete").inOrder()
        assertThat(fake.tasks.value).containsExactly(task)
    }

    @Test
    fun `活动访问包装允许脚本设置能力且授权回调不启动系统页面`() {
        var opened = ""
        var grants = 0
        val fake = LabAgentActivityAccess("s", LabBackgroundJobController(), LabScheduledTaskController(),
            onOpenLog = { opened = it }, onGrantExact = { grants++ })
        fake.setAvailability(keepAliveAvailable = false, exactAlarmAvailable = false, canGrantExact = true)
        assertThat(fake.access.value.keepAliveAvailable).isFalse()
        fake.access.value.onOpenLog("log")
        fake.access.value.onGrantExact!!.invoke()
        assertThat(opened).isEqualTo("log")
        assertThat(grants).isEqualTo(1)
        assertThat(fake.calls.value.map { it.name }).containsExactly("openLog", "grantExact").inOrder()
        fake.setAvailability(true, true)
        assertThat(fake.access.value.onGrantExact).isNull()
    }

    @Test
    fun `子代理控制只记录寻址参数并调用指定回调`() = runTest {
        var released = ""
        var stopped = ""
        val fake = LabSubagentControls(onReleaseWait = { session, step -> released = "$session:$step" },
            onStop = { session, run -> stopped = "$session:$run" })
        fake.releaseWait("s", "step")
        fake.stop("s", "run")
        assertThat(released).isEqualTo("s:step")
        assertThat(stopped).isEqualTo("s:run")
        assertThat(fake.calls.value.map { it.name }).containsExactly("releaseWait", "stop").inOrder()
        assertThat(fake.calls.value.last().arguments).containsExactly("s", "run").inOrder()
    }

    @Test
    fun `子代理记录流与快照一致且支持撤回脚本状态`() = runTest {
        var opened = ""
        val fake = LabSubagentRunsAccess(onOpen = { step, index -> opened = "$step:$index" }, modelName = { "模型:$it" })
        val batch = SubagentBatch("s", "step", emptyList())
        val subscription = fake.access.observe("step")
        fake.setBatch("step", batch)
        assertThat(subscription.first()).isEqualTo(batch)
        assertThat(fake.access.snapshot("step")).isEqualTo(batch)
        assertThat(fake.access.snapshot("other")).isNull()
        assertThat(fake.access.modelName("demo")).isEqualTo("模型:demo")
        fake.access.onOpen("step", 2)
        assertThat(opened).isEqualTo("step:2")
        assertThat(fake.calls.value.single().arguments).containsExactly("step", 2).inOrder()
        fake.setBatch("step", null)
        assertThat(subscription.first()).isNull()
        assertThat(fake.access.snapshot("step")).isNull()
    }

    @Test
    fun `记忆宿主可设置展示状态且撤销为空操作`() {
        var undone: MemoryChange? = null
        val fake = LabMemoryChangeHost(onUndo = { undone = it })
        val memory = LongTermMemory("id", LongTermMemoryKind.PROJECT, "标题", "内容", null, null, null, 1, 1)
        val change = MemoryChange("call", MemoryChangeAction.CREATED, "id", MemorySnapshot(memory.kind, memory.title, memory.content))
        fake.setMemories(mapOf(memory.id to memory))
        fake.undo(change)
        assertThat(fake.memories.value["id"]).isEqualTo(memory)
        assertThat(undone).isEqualTo(change)
        assertThat(fake.calls.value.single().arguments).containsExactly(change)
        fake.setMemories(emptyMap())
        assertThat(fake.memories.value).isEmpty()
    }

    @Test
    fun `实时输出逐行追加且替换可支持倒退重放并隔离key`() = runTest {
        val fakes = LabFakes()
        fakes.toolOutput.setLines("a", listOf("第一行", "第二行"))
        fakes.toolOutput.appendLines("a", listOf("第三行"))
        assertThat(fakes.liveOutput.observe("a").first()!!.text).isEqualTo("第一行\n第二行\n第三行\n")
        fakes.toolOutput.setLines("b", listOf("独立"))
        fakes.toolOutput.setLines("a", listOf("第一行"))
        assertThat(fakes.liveOutput.snapshot("a")!!.text).isEqualTo("第一行\n")
        assertThat(fakes.liveOutput.snapshot("b")!!.text).isEqualTo("独立\n")
        fakes.liveOutput.requestStop("a")
        assertThat(fakes.toolOutput.calls.value.single().arguments).containsExactly("a")
        assertThat(fakes.liveOutput.snapshot("a")!!.stopRequested).isTrue()
        fakes.toolOutput.clear("a")
        assertThat(fakes.liveOutput.snapshot("a")).isNull()
        assertThat(fakes.liveOutput.activeKeys.value).containsExactly("b")
    }

    @Test
    fun `聚合器使用同一组内存依赖且实例互相隔离`() = runTest {
        val opens = mutableListOf<String>()
        val first = LabFakes("demo", onOpenSession = { opens += it }, onOpenLog = { opens += it },
            onOpenSubagent = { step, index -> opens += "$step:$index" }, modelName = { "演示:$it" })
        val second = LabFakes("other")
        assertThat(first.branchAccess.access.value.controls).isSameInstanceAs(first.branches)
        assertThat(first.agentActivity.access.value.jobs).isSameInstanceAs(first.jobs)
        assertThat(first.agentActivity.access.value.schedules).isSameInstanceAs(first.schedules)
        assertThat(first.agentActivity.access.value.sessionId).isEqualTo("demo")
        first.jobs.setJobs(listOf(job("one", "demo", 1)))
        assertThat(second.jobs.observe(null).first()).isEmpty()
        first.toolOutput.setLines("key", listOf("第一套"))
        assertThat(second.liveOutput.snapshot("key")).isNull()
        first.branchAccess.access.value.onOpenSession("child")
        first.agentActivity.access.value.onOpenLog("log")
        first.subagentRuns.access.onOpen("step", 1)
        assertThat(opens).containsExactly("child", "log", "step:1").inOrder()
        assertThat(first.subagentRuns.access.modelName("model")).isEqualTo("演示:model")
    }

    @Test
    fun `分支假结果可由回调明确失败且日志在回调前记录`() = runTest {
        val fake = LabBranchControls(onRemoveWorktree = { Result.failure(IllegalStateException("实验室不可用")) },
            onListWorktrees = { Result.success(listOf(WorktreeEntry("/demo", null, "sample", false))) })
        assertThat(fake.removeWorktree("s").exceptionOrNull()!!.message).isEqualTo("实验室不可用")
        assertThat(fake.listWorktrees("s").getOrThrow().single().path).isEqualTo("/demo")
        assertThat(fake.calls.value.map { it.name }).containsExactly("removeWorktree", "listWorktrees").inOrder()
    }

    @Test
    fun `子代理分组拒绝错误步骤索引`() {
        val fake = LabSubagentRunsAccess()
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            fake.setBatch("other", SubagentBatch("s", "step", emptyList()))
        }
        assertThat(fake.batches.value).isEmpty()
    }

    private fun job(id: String, sessionId: String, startedAt: Long) = BackgroundJob(
        id, sessionId, BackgroundJobRunner.SHELL, "脚本输出", BackgroundJobState.RUNNING, null, startedAt,
        null, 10, BackgroundJobExitAction.NONE, null, "live:$id",
    )
}
