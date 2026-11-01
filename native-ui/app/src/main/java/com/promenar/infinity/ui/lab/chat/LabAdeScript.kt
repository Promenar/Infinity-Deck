package com.promenar.infinity.ui.lab.chat

import com.promenar.infinity.data.model.FileChangeSummary
import com.promenar.infinity.data.model.FileChangeType
import com.promenar.infinity.data.model.TaskStep
import com.promenar.infinity.data.subagent.SubagentBatch
import com.promenar.infinity.data.subagent.SubagentRun
import com.promenar.infinity.data.subagent.SubagentStatus
import com.promenar.infinity.data.subagent.SubagentToolProfile
import com.promenar.infinity.data.subagent.SubagentWait
import com.promenar.infinity.domain.branch.BranchActivity
import com.promenar.infinity.domain.branch.BranchContextMode
import com.promenar.infinity.domain.branch.BranchPlan
import com.promenar.infinity.domain.branch.BranchSummary
import com.promenar.infinity.domain.branch.BranchWorkMode
import com.promenar.infinity.domain.branch.BranchWorktree
import com.promenar.infinity.domain.branch.PendingBranchPlan
import com.promenar.infinity.domain.branch.WorktreeEntry
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.jobs.BackgroundJobExitAction
import com.promenar.infinity.domain.jobs.BackgroundJobRunner
import com.promenar.infinity.domain.jobs.BackgroundJobState
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.chat.ChatScreenState
import com.promenar.infinity.ui.chat.ChatUiState
import com.promenar.infinity.ui.chat.GenerationStatus
import com.promenar.infinity.ui.chat.runmode.RunModeBadge
import com.promenar.infinity.ui.chat.taskPanelUiState
import com.promenar.infinity.ui.common.ExecutionMode
import com.promenar.infinity.ui.git.GitEntrySummary
import com.promenar.infinity.ui.lab.timeline.LabTimeline
import com.promenar.infinity.ui.lab.timeline.labTimeline
import com.promenar.infinity.ui.workspace.SessionWorkspaceUi

/**
 * “给 starlog 加 JSON 输出”的脚本：计划、两个子代理并行、实现与测试、后台完整测试、
 * 分支会话方案与派发、提交。[stage] 只增不减，画面由 [adeFrame] 从阶段推出。
 */
internal data class AdeState(
    val text: LabAdeText,
    val stage: Int = ADE_START,
    /** 当前阶段正在流式输出的正文；为空表示该阶段的正文已完整。 */
    val partial: String? = null,
    /** 两个探索者各自的步数。 */
    val explorerSteps: List<Int> = listOf(1, 1),
    /** 快速测试的实时输出已出现的行数。 */
    val quickLines: Int = 0,
)

internal const val ADE_START = 0
internal const val ADE_SURVEY = 1
internal const val ADE_EXPLORED = 2
internal const val ADE_PLAN = 3
internal const val ADE_DISPATCH = 4
internal const val ADE_TASK = 5
internal const val ADE_EXPLORER1_DONE = 6
internal const val ADE_TASK_DONE = 7
internal const val ADE_IMPLEMENT = 8
internal const val ADE_FILES = 9
internal const val ADE_TEST_FILE = 10
internal const val ADE_QUICK = 11
internal const val ADE_QUICK_RUN = 12
internal const val ADE_QUICK_DONE = 13
internal const val ADE_BACKGROUND = 14
internal const val ADE_BG_TOOLS = 15
internal const val ADE_PROPOSE = 16
internal const val ADE_PLAN_UPDATE = 17
internal const val ADE_BRANCH = 18
internal const val ADE_DISPATCHED = 19
internal const val ADE_JOB_DONE = 20
internal const val ADE_SUITE = 21
internal const val ADE_COMMIT = 22
internal const val ADE_COMMITTED = 23
internal const val ADE_SUMMARY = 24
internal const val ADE_DONE = 25

/** 各阶段开始的脚本时刻（毫秒）。 */
internal object AdeTimes {
    const val SURVEY = 1_200L
    const val EXPLORED = 1_900L
    const val PLAN = 4_900L
    const val DISPATCH = 5_500L
    const val TASK = 6_500L
    const val EXPLORER1_DONE = 12_500L
    const val TASK_DONE = 13_800L
    const val IMPLEMENT = 14_400L
    const val FILES = 17_600L
    const val TEST_FILE = 18_600L
    const val QUICK = 19_400L
    const val QUICK_RUN = 21_200L
    const val QUICK_DONE = 23_400L
    const val BACKGROUND = 24_000L
    const val BG_TOOLS = 26_200L
    const val PROPOSE = 27_000L
    const val PLAN_UPDATE = 30_000L
    const val BRANCH = 30_700L
    const val DISPATCHED = 36_000L
    const val JOB_DONE = 37_600L
    const val SUITE = 38_300L
    const val COMMIT = 39_800L
    const val COMMITTED = 41_000L
    const val SUMMARY = 41_500L
    const val DONE = 45_000L
    const val HOLD = 3_000L

    /** 子代理分组的前台等待时限：5 分钟。 */
    const val WAIT = 300_000L
}

internal fun adeTimeline(text: LabAdeText): LabTimeline<AdeState> = labTimeline(AdeState(text)) {
    fun set(at: Long, stage: Int) = at(at) { it.copy(stage = stage, partial = null) }
    fun say(at: Long, duration: Long, stage: Int, content: String) =
        streamOver(at, duration, content) { s, part -> s.copy(stage = stage, partial = part) }

    set(AdeTimes.SURVEY, ADE_SURVEY)
    say(AdeTimes.EXPLORED, 2_600, ADE_EXPLORED, text.explored)
    set(AdeTimes.PLAN, ADE_PLAN)
    say(AdeTimes.DISPATCH, 700, ADE_DISPATCH, text.dispatch)
    set(AdeTimes.TASK, ADE_TASK)
    // 两个探索者交替推进步数。
    listOf(1_000L to listOf(2, 1), 2_000L to listOf(2, 3), 3_000L to listOf(3, 4), 4_000L to listOf(4, 5), 5_000L to listOf(5, 6))
        .forEach { (offset, steps) -> at(AdeTimes.TASK + offset) { it.copy(explorerSteps = steps) } }
    set(AdeTimes.EXPLORER1_DONE, ADE_EXPLORER1_DONE)
    at(AdeTimes.EXPLORER1_DONE + 600) { it.copy(explorerSteps = listOf(5, 7)) }
    set(AdeTimes.TASK_DONE, ADE_TASK_DONE)
    say(AdeTimes.IMPLEMENT, 2_800, ADE_IMPLEMENT, text.implement)
    set(AdeTimes.FILES, ADE_FILES)
    set(AdeTimes.TEST_FILE, ADE_TEST_FILE)
    say(AdeTimes.QUICK, 1_500, ADE_QUICK, text.quickTest)
    set(AdeTimes.QUICK_RUN, ADE_QUICK_RUN)
    QUICK_OUTPUT.indices.forEach { line ->
        at(AdeTimes.QUICK_RUN + 300 + line * 260L) { it.copy(quickLines = line + 1) }
    }
    set(AdeTimes.QUICK_DONE, ADE_QUICK_DONE)
    say(AdeTimes.BACKGROUND, 1_900, ADE_BACKGROUND, text.toBackground)
    set(AdeTimes.BG_TOOLS, ADE_BG_TOOLS)
    say(AdeTimes.PROPOSE, 2_700, ADE_PROPOSE, text.proposeBranch)
    set(AdeTimes.PLAN_UPDATE, ADE_PLAN_UPDATE)
    set(AdeTimes.BRANCH, ADE_BRANCH)
    set(AdeTimes.DISPATCHED, ADE_DISPATCHED)
    set(AdeTimes.JOB_DONE, ADE_JOB_DONE)
    say(AdeTimes.SUITE, 1_200, ADE_SUITE, text.suiteDone)
    set(AdeTimes.COMMIT, ADE_COMMIT)
    set(AdeTimes.COMMITTED, ADE_COMMITTED)
    say(AdeTimes.SUMMARY, 3_200, ADE_SUMMARY, text.summary)
    set(AdeTimes.DONE, ADE_DONE)
    hold(AdeTimes.HOLD)
}

private val QUICK_OUTPUT = listOf(
    "test_cli_json_flag (tests.test_main.CliTest) ... ok",
    "test_json_schema (tests.test_main.CliTest) ... ok",
    "test_empty_log (tests.test_main.CliTest) ... ok",
    "test_large_log (tests.test_large_log.LargeLogTest) ... ok",
    "----------------------------------------------------------------------",
    "Ran 21 tests in 0.412s",
    "",
    "OK",
)

private const val SUITE_COMMAND = "cd /root/starlog && time python3 -m unittest discover -s tests -t ."
private const val JOB_ID = "lab-job-suite"
internal const val ADE_BRANCH_SESSION = "lab-branch-toml"

/** 某一阶段的整屏快照；时间均为脚本时刻。 */
internal fun adeFrame(s: AdeState): LabChatFrame {
    val t = s.text
    val stage = s.stage
    fun body(at: Int, full: String) = if (stage == at && s.partial != null) s.partial else full

    val survey = LabCall("call_survey", "shell_exec", commandArgs(t.cmdSurvey, "cd /root/starlog && ls -R && wc -l tests/*.py"),
        AdeTimes.SURVEY, AdeTimes.SURVEY + 195, "parser.py  report.py  __main__.py  tests/")
    val plan = LabCall("call_plan", "initialize_plan", planArgs(t), AdeTimes.PLAN, AdeTimes.PLAN + 120, """{"currentFocusStepId":"s1"}""")
    val task = LabCall("call_task", "agent_task", taskArgs(t), AdeTimes.TASK,
        endAt = (AdeTimes.TASK_DONE).takeIf { stage >= ADE_TASK_DONE }, result = "2 个子代理已完成")
    val files = listOf(
        LabCall("call_main", "patch_file", """{"path":"starlog/__main__.py"}""", AdeTimes.FILES, AdeTimes.FILES + 380, "ok",
            fileChanges = listOf(FileChangeSummary("starlog/__main__.py", FileChangeType.MODIFIED, added = 12, removed = 2))),
        LabCall("call_report", "patch_file", """{"path":"starlog/report.py"}""", AdeTimes.FILES + 380, AdeTimes.FILES + 763, "ok",
            fileChanges = listOf(FileChangeSummary("starlog/report.py", FileChangeType.MODIFIED, added = 41, removed = 2))),
    )
    val testFile = LabCall("call_test", "write_file", """{"path":"tests/test_main.py"}""", AdeTimes.TEST_FILE, AdeTimes.TEST_FILE + 216, "ok",
        fileChanges = listOf(FileChangeSummary("tests/test_main.py", FileChangeType.CREATED, added = 165, removed = 0)))
    val quick = LabCall("call_quick", "shell_exec", commandArgs(t.cmdQuick, "cd /root/starlog && STARLOG_LARGE_LINES=1000 python3 -m unittest discover -s tests -t ."),
        AdeTimes.QUICK_RUN, (AdeTimes.QUICK_RUN + 570).takeIf { stage >= ADE_QUICK_DONE }, QUICK_OUTPUT.joinToString("\n"))
    val bgTools = listOf(
        LabCall("call_cli", "shell_exec", commandArgs(t.cmdCli, "cd /root/starlog && python3 -m starlog sample.log --json | head -20"),
            AdeTimes.BG_TOOLS, AdeTimes.BG_TOOLS + 412, """{"total": 4000000, "errors": 1287, "warnings": 9301}"""),
        LabCall("call_suite", "shell_exec", commandArgs(t.cmdSuite, SUITE_COMMAND, "background" to true),
            AdeTimes.BG_TOOLS + 412, AdeTimes.BG_TOOLS + 823, """{"job_id":"$JOB_ID","state":"running"}"""),
    )
    val planUpdate = LabCall("call_plan_update", "update_plan", PLAN_UPDATE_ARGS, AdeTimes.PLAN_UPDATE, AdeTimes.PLAN_UPDATE + 110,
        """{"currentPlan":{"currentFocusStepId":"s4"}}""")
    val branch = LabCall("call_branch", "session_branch", """{"title":${jsonString(t.branchTitle)},"goal":${jsonString(t.branchGoal)}}""",
        AdeTimes.BRANCH, AdeTimes.DISPATCHED.takeIf { stage >= ADE_DISPATCHED },
        """{"status":"dispatched","answer":{"decision":"dispatch","workMode":"worktree","contextMode":"fresh","approvalMode":"workspace","branchSessionId":"$ADE_BRANCH_SESSION"}}""")
    val wait = LabCall("call_wait", "job_wait", """{"job_id":"$JOB_ID"}""", AdeTimes.JOB_DONE, AdeTimes.JOB_DONE + 300,
        """{"job_id":"$JOB_ID","state":"exited","exit_code":0,"elapsed_seconds":59,"summary":"$SUITE_COMMAND","log_tail":"Ran 21 tests in 58.970s\n\nOK","log_truncated":false}""")
    val commit = LabCall("call_commit", "shell_exec", commandArgs(t.cmdCommit, "cd /root/starlog && git add -A && git commit -m \"Add --json output to the starlog CLI\""),
        AdeTimes.COMMIT, (AdeTimes.COMMIT + 318).takeIf { stage >= ADE_COMMITTED }, "[main 4831233] Add --json output to the starlog CLI\n 3 files changed, 218 insertions(+), 4 deletions(-)")

    val messages = buildList {
        add(userMessage("ade_user", t.prompt, 0))
        if (stage >= ADE_SURVEY) add(assistantMessage("ade_a1", "", AdeTimes.SURVEY, listOf(survey)))
        if (stage >= ADE_EXPLORED) add(assistantMessage("ade_a2", body(ADE_EXPLORED, t.explored), AdeTimes.EXPLORED,
            if (stage >= ADE_PLAN) listOf(plan) else emptyList()))
        if (stage >= ADE_DISPATCH) add(assistantMessage("ade_a3", body(ADE_DISPATCH, t.dispatch), AdeTimes.DISPATCH,
            if (stage >= ADE_TASK) listOf(task) else emptyList()))
        if (stage >= ADE_IMPLEMENT) add(assistantMessage("ade_a4", body(ADE_IMPLEMENT, t.implement), AdeTimes.IMPLEMENT,
            if (stage >= ADE_FILES) files else emptyList()))
        // 写测试前有一段思考，真实会话里它把两组文件改动分成两行。
        if (stage >= ADE_TEST_FILE) add(assistantMessage("ade_a5", "", AdeTimes.TEST_FILE, listOf(testFile)).copy(reasoning = t.testThinking))
        if (stage >= ADE_QUICK) add(assistantMessage("ade_a6", body(ADE_QUICK, t.quickTest), AdeTimes.QUICK,
            if (stage >= ADE_QUICK_RUN) listOf(quick) else emptyList()))
        if (stage >= ADE_BACKGROUND) add(assistantMessage("ade_a7", body(ADE_BACKGROUND, t.toBackground), AdeTimes.BACKGROUND,
            if (stage >= ADE_BG_TOOLS) bgTools else emptyList()))
        if (stage >= ADE_PROPOSE) add(assistantMessage("ade_a8", body(ADE_PROPOSE, t.proposeBranch), AdeTimes.PROPOSE,
            listOfNotNull(planUpdate.takeIf { stage >= ADE_PLAN_UPDATE }, branch.takeIf { stage >= ADE_BRANCH })))
        if (stage >= ADE_JOB_DONE) add(assistantMessage("ade_a9", "", AdeTimes.JOB_DONE, listOf(wait)))
        if (stage >= ADE_SUITE) add(assistantMessage("ade_a10", body(ADE_SUITE, t.suiteDone), AdeTimes.SUITE,
            if (stage >= ADE_COMMIT) listOf(commit) else emptyList()))
        if (stage >= ADE_SUMMARY) add(assistantMessage("ade_a11", body(ADE_SUMMARY, t.summary), AdeTimes.SUMMARY))
    }

    val generating = stage < ADE_DONE
    val streaming = s.partial != null && stage in STREAMING_STAGES
    val phase = when {
        !generating -> null
        streaming -> GenerationPhase.STREAMING
        stage == ADE_BRANCH -> GenerationPhase.WAITING_INPUT
        stage in RUNNING_TOOL_STAGES -> GenerationPhase.EXECUTING_TOOLS
        else -> GenerationPhase.THINKING
    }
    val committed = stage >= ADE_COMMITTED
    val screen = ChatScreenState(
        uiState = ChatUiState(
            session = labSession(t.sessionTitle, "full_access"),
            agentName = LAB_AGENT,
            messages = messages,
            isGenerating = generating,
            status = when {
                !generating -> GenerationStatus.IDLE
                streaming -> GenerationStatus.RECEIVING
                else -> GenerationStatus.THINKING
            },
            streamingContent = if (streaming) s.partial.orEmpty() else "",
        ),
        modelDisplayNames = mapOf(LAB_MODEL to LAB_MODEL),
        taskPanel = if (stage >= ADE_PLAN) taskPanelUiState(adePlanTree(t, stage), generating, t.planTitle) else null,
        generationStartedAt = 0L.takeIf { generating },
        generationPhase = phase,
        collapseFinishedTurns = false,
        gitSummary = GitEntrySummary.Repository(
            branch = "main",
            detached = false,
            headShortSha = if (committed) "4831233" else "c627b5b",
            unborn = false,
            changeCount = when {
                committed -> 0
                stage >= ADE_QUICK_DONE -> 5
                stage >= ADE_TEST_FILE -> 3
                stage >= ADE_FILES -> 2
                else -> 0
            },
            truncated = false,
        ),
        runMode = RunModeBadge(SessionRunMode.LINUX, unavailable = false),
        executionMode = ExecutionMode.FULL_ACCESS,
        workspace = SessionWorkspaceUi.Directory("lab-ws-starlog", "starlog", "linux_home", "/root/starlog"),
    )
    return LabChatFrame(
        screen = screen,
        subagentBatches = if (stage >= ADE_TASK) mapOf(task.stepId to adeBatch(t, s, task.stepId)) else emptyMap(),
        jobs = if (stage >= ADE_BG_TOOLS) listOf(adeJob(stage)) else emptyList(),
        pendingPlan = if (stage == ADE_BRANCH) adePendingPlan(t) else null,
        branches = if (stage >= ADE_DISPATCHED) listOf(adeBranch(t, stage)) else emptyList(),
        worktrees = if (stage >= ADE_DISPATCHED) adeWorktrees(t) else emptyList(),
        liveOutput = buildMap {
            if (stage == ADE_QUICK_RUN) put(quick.stepId, QUICK_OUTPUT.take(s.quickLines))
            if (stage in ADE_BG_TOOLS until ADE_JOB_DONE) put("job:$JOB_ID", listOf("test_cli_json_flag ... ok", "test_large_log ... "))
        },
    )
}

private val STREAMING_STAGES = setOf(ADE_EXPLORED, ADE_DISPATCH, ADE_IMPLEMENT, ADE_QUICK, ADE_BACKGROUND, ADE_PROPOSE, ADE_SUITE, ADE_SUMMARY)
private val RUNNING_TOOL_STAGES = setOf(ADE_TASK, ADE_EXPLORER1_DONE, ADE_QUICK_RUN, ADE_COMMIT)

private const val PLAN_UPDATE_ARGS =
    """{"operations":[{"action":"set_status","stepId":"s2","payload":{"status":"done"}},""" +
        """{"action":"set_status","stepId":"s3","payload":{"status":"done"}},""" +
        """{"action":"set_status","stepId":"s4","payload":{"status":"doing"}}]}"""

private fun planArgs(t: LabAdeText): String = t.planSteps.withIndex().joinToString(
    ",", """{"goal":${jsonString(t.planTitle)},"tree":[""", "]}",
) { (index, title) -> """{"id":"s${index + 1}","title":${jsonString(title)}}""" }

private fun taskArgs(t: LabAdeText): String =
    """{"tasks":[{"description":${jsonString(t.explorer1)},"prompt":${jsonString(t.explorer1)},"preset":"explorer"},""" +
        """{"description":${jsonString(t.explorer2)},"prompt":${jsonString(t.explorer2)},"preset":"explorer"}]}"""

/** 计划各步骤在某一阶段的状态。 */
private fun adePlanTree(t: LabAdeText, stage: Int): List<TaskStep> {
    val done = when {
        stage >= ADE_COMMITTED -> 6
        stage >= ADE_JOB_DONE -> 4
        stage >= ADE_PLAN_UPDATE -> 3
        stage >= ADE_TASK_DONE -> 1
        else -> 0
    }
    // 派发分支后第 5 步提前完成，后台测试仍在进行。
    val branchDone = stage >= ADE_DISPATCHED
    return t.planSteps.mapIndexed { index, title ->
        val status = when {
            index < done -> "done"
            index == 4 && branchDone -> "done"
            index == done -> "doing"
            else -> "todo"
        }
        TaskStep(id = "s${index + 1}", title = title, status = status, sortOrder = index)
    }
}

private fun adeBatch(t: LabAdeText, s: AdeState, stepId: String): SubagentBatch {
    val stage = s.stage
    fun run(index: Int, description: String, result: String, doneAt: Long, done: Boolean) = SubagentRun(
        index = index,
        id = "lab-explorer-${index + 1}",
        preset = "explorer",
        description = description,
        prompt = description,
        toolProfile = SubagentToolProfile.READ_ONLY,
        modelId = LAB_MODEL,
        maxSteps = 0,
        status = if (done) SubagentStatus.COMPLETED else SubagentStatus.RUNNING,
        step = s.explorerSteps[index],
        startedAt = AdeTimes.TASK,
        endedAt = doneAt.takeIf { done },
        result = result.takeIf { done },
    )
    return SubagentBatch(
        sessionId = LAB_SESSION_ID,
        stepId = stepId,
        runs = listOf(
            run(0, t.explorer1, t.explorer1Result, AdeTimes.EXPLORER1_DONE, stage >= ADE_EXPLORER1_DONE),
            run(1, t.explorer2, t.explorer2Result, AdeTimes.TASK_DONE, stage >= ADE_TASK_DONE),
        ),
        wait = SubagentWait(AdeTimes.TASK, AdeTimes.TASK + AdeTimes.WAIT).takeIf { stage < ADE_TASK_DONE },
    )
}

private fun adeJob(stage: Int): BackgroundJob {
    val done = stage >= ADE_JOB_DONE
    return BackgroundJob(
        id = JOB_ID,
        sessionId = LAB_SESSION_ID,
        runner = BackgroundJobRunner.LINUX,
        summary = SUITE_COMMAND,
        state = if (done) BackgroundJobState.EXITED else BackgroundJobState.RUNNING,
        exitCode = 0.takeIf { done },
        startedAtMillis = AdeTimes.BG_TOOLS + 412,
        endedAtMillis = AdeTimes.JOB_DONE.takeIf { done },
        maxRuntimeMinutes = 30,
        onExit = BackgroundJobExitAction.NOTIFY,
        logWorkspacePath = null,
        liveOutputKey = "job:$JOB_ID",
    )
}

private fun adePendingPlan(t: LabAdeText) = PendingBranchPlan(
    sessionId = LAB_SESSION_ID,
    assistantMessageId = "ade_a8",
    toolCallId = "call_branch",
    parentApprovalMode = "full_access",
    plan = BranchPlan(
        title = t.branchTitle,
        goal = t.branchGoal,
        steps = t.branchSteps,
        exclusions = t.branchExclusions,
        workMode = BranchWorkMode.WORKTREE,
        worktree = BranchWorktree(branch = "config-toml", base = "main", directory = "/root/starlog-toml", reason = t.branchReason),
        communication = t.branchCommunication,
        contextMode = BranchContextMode.FRESH,
        approvalMode = "workspace",
    ),
)

private fun adeBranch(t: LabAdeText, stage: Int) = BranchSummary(
    sessionId = ADE_BRANCH_SESSION,
    parentSessionId = LAB_SESSION_ID,
    title = t.branchTitle,
    activity = if (stage >= ADE_DONE) BranchActivity.ROUND_DONE else BranchActivity.GENERATING,
    worktreeBranch = "config-toml",
    completedRounds = if (stage >= ADE_DONE) 1 else 0,
    updatedAt = AdeTimes.DISPATCHED,
)

internal fun adeWorktrees(t: LabAdeText) = listOf(
    WorktreeEntry("/root/starlog", "main", "4831233", isMain = true, sessionId = LAB_SESSION_ID, sessionTitle = t.sessionTitle),
    WorktreeEntry("/root/starlog-toml", "config-toml", "c627b5b", isMain = false, sessionId = ADE_BRANCH_SESSION, sessionTitle = t.branchTitle),
)
