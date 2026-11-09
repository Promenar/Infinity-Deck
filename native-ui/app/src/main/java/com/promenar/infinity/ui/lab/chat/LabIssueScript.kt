package com.promenar.infinity.ui.lab.chat

import com.promenar.infinity.data.model.Citation
import com.promenar.infinity.data.model.ImageGenerationActual
import com.promenar.infinity.data.model.ImageGenerationRequested
import com.promenar.infinity.data.model.ImageGenerationResultData
import com.promenar.infinity.data.model.TaskStep
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.jobs.BackgroundJobExitAction
import com.promenar.infinity.domain.jobs.BackgroundJobRunner
import com.promenar.infinity.domain.jobs.BackgroundJobState
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.chat.ChatScreenState
import com.promenar.infinity.ui.chat.ChatUiState
import com.promenar.infinity.ui.chat.GenerationStatus
import com.promenar.infinity.ui.chat.runmode.RunModeBadge
import com.promenar.infinity.ui.chat.taskPanelUiState
import com.promenar.infinity.ui.common.ExecutionMode
import com.promenar.infinity.ui.lab.timeline.LabTimeline
import com.promenar.infinity.ui.lab.timeline.labTimeline
import com.promenar.infinity.ui.workspace.SessionWorkspaceUi

/**
 * “starlog 周刊第 12 期”的脚本（外屏起始）：列计划、联网核对引用、封面交给 gpu-box 后台生成、
 * 本机 Linux 构建页面、取回封面后发布到 web-01，验证时页面返回 502，接到 [opsTimeline] 的排障。
 * 脚本停在交接处，生成仍在进行；[stage] 只增不减。
 */
internal data class IssueState(
    val text: LabIssueText,
    val stage: Int = ISSUE_START,
    val partial: String? = null,
    /** 构建命令的实时输出已出现的行数。 */
    val buildLines: Int = 0,
)

internal const val ISSUE_START = 0
internal const val ISSUE_PLAN_SAY = 1
internal const val ISSUE_PLAN = 2
internal const val ISSUE_CHECK_SAY = 3
internal const val ISSUE_SEARCH = 4
internal const val ISSUE_SEARCH_DONE = 5
internal const val ISSUE_VERDICT = 6
internal const val ISSUE_COVER_SAY = 7
internal const val ISSUE_COVER = 8
internal const val ISSUE_BUILD = 9
internal const val ISSUE_BUILD_DONE = 10
internal const val ISSUE_COVER_DONE = 11
internal const val ISSUE_PUBLISH_SAY = 12
internal const val ISSUE_PUBLISH = 13
internal const val ISSUE_PUBLISH_DONE = 14
internal const val ISSUE_VERIFY = 15
internal const val ISSUE_VERIFY_DONE = 16
internal const val ISSUE_FAIL_SAY = 17
internal const val ISSUE_HANDOFF = 18

/** 各阶段开始的脚本时刻（毫秒）：整段约 15.5 秒，相邻动作间隔不超过 0.6 秒。 */
internal object IssueTimes {
    const val PLAN_SAY = 600L
    const val PLAN = 1_300L
    const val CHECK_SAY = 1_800L
    const val SEARCH = 2_700L
    const val SEARCH_DONE = 3_800L
    const val VERDICT = 4_000L
    const val COVER_SAY = 5_900L
    const val COVER = 6_900L
    const val BUILD = 7_500L
    const val BUILD_DONE = 8_900L
    const val COVER_DONE = 9_400L
    const val PUBLISH_SAY = 9_900L
    const val PUBLISH = 10_700L
    const val PUBLISH_DONE = 11_400L
    const val VERIFY = 11_600L
    const val VERIFY_DONE = 12_000L
    const val FAIL_SAY = 12_300L
    const val HANDOFF = 13_700L
    const val HOLD = 1_800L
}

internal fun issueTimeline(text: LabIssueText): LabTimeline<IssueState> = labTimeline(IssueState(text)) {
    fun set(at: Long, stage: Int) = at(at) { it.copy(stage = stage, partial = null) }
    fun say(at: Long, duration: Long, stage: Int, content: String) =
        streamOver(at, duration, content) { s, part -> s.copy(stage = stage, partial = part) }

    say(IssueTimes.PLAN_SAY, 600, ISSUE_PLAN_SAY, text.planIntro)
    set(IssueTimes.PLAN, ISSUE_PLAN)
    say(IssueTimes.CHECK_SAY, 800, ISSUE_CHECK_SAY, text.checkSay)
    set(IssueTimes.SEARCH, ISSUE_SEARCH)
    set(IssueTimes.SEARCH_DONE, ISSUE_SEARCH_DONE)
    say(IssueTimes.VERDICT, 1_700, ISSUE_VERDICT, text.verdict)
    say(IssueTimes.COVER_SAY, 800, ISSUE_COVER_SAY, text.coverSay)
    set(IssueTimes.COVER, ISSUE_COVER)
    set(IssueTimes.BUILD, ISSUE_BUILD)
    BUILD_OUTPUT.indices.forEach { line ->
        at(IssueTimes.BUILD + 200 + line * 300L) { it.copy(buildLines = line + 1) }
    }
    set(IssueTimes.BUILD_DONE, ISSUE_BUILD_DONE)
    set(IssueTimes.COVER_DONE, ISSUE_COVER_DONE)
    say(IssueTimes.PUBLISH_SAY, 700, ISSUE_PUBLISH_SAY, text.publishSay)
    set(IssueTimes.PUBLISH, ISSUE_PUBLISH)
    set(IssueTimes.PUBLISH_DONE, ISSUE_PUBLISH_DONE)
    set(IssueTimes.VERIFY, ISSUE_VERIFY)
    set(IssueTimes.VERIFY_DONE, ISSUE_VERIFY_DONE)
    say(IssueTimes.FAIL_SAY, 1_300, ISSUE_FAIL_SAY, text.failSay)
    set(IssueTimes.HANDOFF, ISSUE_HANDOFF)
    hold(IssueTimes.HOLD)
}

private val BUILD_OUTPUT = listOf(
    "render content/issues/012-four-bright-stars.md -> public/issues/12/index.html",
    "links: 2 citations, 0 broken",
    "cover: pending (public/issues/12/cover.jpg)",
    "built 1 page in 0.84s",
)

private const val SITE = "/root/starlog-site"
private const val COVER_JOB_ID = "lab-job-cover"

/** 生成的封面在会话工作区里的虚拟路径；实验室解析器把它映射到 [ISSUE_COVER_ASSET]。 */
internal const val ISSUE_COVER_PATH = "/media/2026-11-09/gen-2148-5f1c0a.jpg"

/** 封面演示图在 assets 里的位置；浏览器版本的页面也引用它。 */
internal const val ISSUE_COVER_ASSET = "lab/issue-12-cover.jpg"

internal val ISSUE_COVER_REF = MediaRef(
    path = ISSUE_COVER_PATH,
    mimeType = "image/jpeg",
    kind = MediaKind.IMAGE,
    sizeBytes = 134_659,
    width = 1344,
    height = 768,
)

private val ISSUE_CITATIONS = listOf(
    Citation(
        title = "List of brightest stars",
        url = "https://en.wikipedia.org/wiki/List_of_brightest_stars",
        source = "en.wikipedia.org",
        snippet = "Sirius −1.46 · Canopus −0.74 · Arcturus −0.05 · Vega +0.03",
    ),
    Citation(
        title = "SIMBAD Astronomical Database",
        url = "https://simbad.cds.unistra.fr/simbad/",
        source = "simbad.cds.unistra.fr",
        snippet = "alf Boo V −0.05 · alf Lyr V 0.03",
    ),
)

private val ISSUE_SEARCH_RESULT = ISSUE_CITATIONS.withIndex().joinToString("\n\n") { (index, citation) ->
    "[${index + 1}] ${citation.title}\n${citation.url}\n${citation.snippet}"
}

private val ISSUE_CITATIONS_JSON = ISSUE_CITATIONS.joinToString(",", "[", "]") { citation ->
    """{"title":${jsonString(citation.title)},"url":${jsonString(citation.url)},"source":${jsonString(citation.source.orEmpty())},"snippet":${jsonString(citation.snippet.orEmpty())}}"""
}

private val ISSUE_IMAGE_DATA = ImageGenerationResultData(
    mode = "generate",
    model = "qwen-image",
    requested = ImageGenerationRequested(size = "1344x768", quality = "high"),
    actual = ImageGenerationActual(size = "1344x768", outputFormat = "jpeg"),
).encode()

/** 某一阶段的整屏快照；时间均为脚本时刻。 */
internal fun issueFrame(s: IssueState): LabChatFrame {
    val t = s.text
    val stage = s.stage
    fun body(at: Int, full: String) = if (stage == at && s.partial != null) s.partial else full

    val plan = LabCall("call_plan", "initialize_plan", issuePlanArgs(t), IssueTimes.PLAN, IssueTimes.PLAN + 120, """{"currentFocusStepId":"s1"}""")
    val search = LabCall(
        "call_search", "web_search", """{"query":"Sirius Canopus Arcturus Vega apparent magnitude"}""", IssueTimes.SEARCH,
        endAt = IssueTimes.SEARCH_DONE.takeIf { stage >= ISSUE_SEARCH_DONE }, result = ISSUE_SEARCH_RESULT, data = ISSUE_CITATIONS_JSON,
    )
    val cover = LabCall(
        "call_cover", "generate_image",
        """{"prompt":${jsonString(t.coverPrompt)},"size":"1344x768","quality":"high","run_in_background":true}""",
        IssueTimes.COVER, IssueTimes.COVER + 300,
        """{"runtime":"in_app","runner":"in_app","job_id":"$COVER_JOB_ID","state":"running","launch_confirmed":true}""",
    )
    val build = LabCall(
        "call_build", "shell_exec", commandArgs(t.cmdBuild, "cd $SITE && python3 build.py --issue 12"), IssueTimes.BUILD,
        endAt = IssueTimes.BUILD_DONE.takeIf { stage >= ISSUE_BUILD_DONE }, result = BUILD_OUTPUT.joinToString("\n"),
    )
    val wait = LabCall(
        "call_wait", "job_wait", """{"job_id":"$COVER_JOB_ID"}""", IssueTimes.COVER_DONE, IssueTimes.COVER_DONE + 300,
        """{"runtime":"in_app","runner":"in_app","job_id":"$COVER_JOB_ID","state":"exited","exit_code":0,"summary":${jsonString(t.coverJobSummary)},""" +
            """"elapsed_seconds":38,"result_text":"$ISSUE_COVER_PATH","log_tail":"","log_truncated":false,"timed_out":false}""",
        media = listOf(ISSUE_COVER_REF), data = ISSUE_IMAGE_DATA,
    )
    val publish = LabCall(
        "call_publish", "shell_exec",
        commandArgs(t.cmdPublish, "cd $SITE && cp media/2026-11-09/gen-2148-5f1c0a.jpg public/issues/12/cover.jpg && rsync -az public/ deploy@web-01:~/starlog/public/"),
        IssueTimes.PUBLISH, IssueTimes.PUBLISH_DONE.takeIf { stage >= ISSUE_PUBLISH_DONE },
        "sent 412,906 bytes  received 1,284 bytes  165,676.00 bytes/sec\ntotal size is 3,861,042  speedup is 9.32",
    )
    val verify = LabCall(
        "call_verify", "shell_exec", commandArgs(t.cmdVerify, "curl -s -o /dev/null -w '[%{http_code}]\\n' http://web-01:8080/issues/12/"),
        IssueTimes.VERIFY, IssueTimes.VERIFY_DONE.takeIf { stage >= ISSUE_VERIFY_DONE }, "[502]",
    )

    val messages = buildList {
        add(userMessage("iss_user", t.prompt, 0))
        if (stage >= ISSUE_PLAN_SAY) add(assistantMessage("iss_a1", body(ISSUE_PLAN_SAY, t.planIntro), IssueTimes.PLAN_SAY,
            if (stage >= ISSUE_PLAN) listOf(plan) else emptyList()))
        if (stage >= ISSUE_CHECK_SAY) add(
            assistantMessage("iss_a2", body(ISSUE_CHECK_SAY, t.checkSay), IssueTimes.CHECK_SAY,
                if (stage >= ISSUE_SEARCH) listOf(search) else emptyList())
                .copy(citations = ISSUE_CITATIONS.takeIf { stage >= ISSUE_SEARCH_DONE }),
        )
        if (stage >= ISSUE_VERDICT) add(assistantMessage("iss_a3", body(ISSUE_VERDICT, t.verdict), IssueTimes.VERDICT))
        if (stage >= ISSUE_COVER_SAY) add(assistantMessage("iss_a4", body(ISSUE_COVER_SAY, t.coverSay), IssueTimes.COVER_SAY,
            listOfNotNull(cover.takeIf { stage >= ISSUE_COVER }, build.takeIf { stage >= ISSUE_BUILD })))
        if (stage >= ISSUE_COVER_DONE) add(assistantMessage("iss_a5", "", IssueTimes.COVER_DONE, listOf(wait)))
        if (stage >= ISSUE_PUBLISH_SAY) add(assistantMessage("iss_a6", body(ISSUE_PUBLISH_SAY, t.publishSay), IssueTimes.PUBLISH_SAY,
            listOfNotNull(publish.takeIf { stage >= ISSUE_PUBLISH }, verify.takeIf { stage >= ISSUE_VERIFY })))
        if (stage >= ISSUE_FAIL_SAY) add(assistantMessage("iss_a7", body(ISSUE_FAIL_SAY, t.failSay), IssueTimes.FAIL_SAY))
    }

    val streaming = s.partial != null && stage in ISSUE_STREAMING_STAGES
    return LabChatFrame(
        screen = ChatScreenState(
            uiState = ChatUiState(
                session = labSession(t.sessionTitle, "full_access"),
                agentName = LAB_AGENT,
                messages = messages,
                isGenerating = true,
                status = if (streaming) GenerationStatus.RECEIVING else GenerationStatus.THINKING,
                streamingContent = if (streaming) s.partial.orEmpty() else "",
            ),
            modelDisplayNames = mapOf(LAB_MODEL to LAB_MODEL),
            tokenState = labTokenState(stage, ISSUE_HANDOFF, startTokens = 8_200, endTokens = 19_600),
            taskPanel = if (stage >= ISSUE_PLAN) taskPanelUiState(issuePlanTree(t, stage), true, t.planTitle) else null,
            generationStartedAt = 0L,
            generationPhase = when {
                streaming -> GenerationPhase.STREAMING
                stage in ISSUE_RUNNING_TOOL_STAGES -> GenerationPhase.EXECUTING_TOOLS
                else -> GenerationPhase.THINKING
            },
            collapseFinishedTurns = false,
            runMode = RunModeBadge(SessionRunMode.LINUX, unavailable = false),
            executionMode = ExecutionMode.FULL_ACCESS,
            workspace = SessionWorkspaceUi.Directory("lab-ws-starlog-site", "starlog-site", "linux_home", SITE),
        ),
        jobs = if (stage >= ISSUE_COVER) listOf(issueCoverJob(t, stage)) else emptyList(),
        liveOutput = if (stage == ISSUE_BUILD) mapOf(build.stepId to BUILD_OUTPUT.take(s.buildLines)) else emptyMap(),
    )
}

private val ISSUE_STREAMING_STAGES = setOf(ISSUE_PLAN_SAY, ISSUE_CHECK_SAY, ISSUE_VERDICT, ISSUE_COVER_SAY, ISSUE_PUBLISH_SAY, ISSUE_FAIL_SAY)
private val ISSUE_RUNNING_TOOL_STAGES = setOf(ISSUE_SEARCH, ISSUE_BUILD, ISSUE_PUBLISH, ISSUE_VERIFY)

private fun issuePlanArgs(t: LabIssueText): String = t.planSteps.withIndex().joinToString(
    ",", """{"goal":${jsonString(t.planTitle)},"tree":[""", "]}",
) { (index, title) -> """{"id":"s${index + 1}","title":${jsonString(title)}}""" }

/** 计划四步的状态：封面在后台生成期间，构建发布已同时开始。 */
private fun issuePlanTree(t: LabIssueText, stage: Int): List<TaskStep> {
    fun status(done: Boolean, doing: Boolean) = when {
        done -> "done"
        doing -> "doing"
        else -> "todo"
    }
    val statuses = listOf(
        status(done = stage >= ISSUE_COVER_SAY, doing = true),
        status(done = stage >= ISSUE_COVER_DONE, doing = stage >= ISSUE_COVER_SAY),
        status(done = stage >= ISSUE_PUBLISH_DONE, doing = stage >= ISSUE_BUILD),
        status(done = false, doing = stage >= ISSUE_VERIFY),
    )
    return t.planSteps.mapIndexed { index, title ->
        TaskStep(id = "s${index + 1}", title = title, status = statuses[index], sortOrder = index)
    }
}

/** 封面的应用内后台任务：生图转后台后登记，取回结果时已结束。 */
private fun issueCoverJob(t: LabIssueText, stage: Int): BackgroundJob {
    val done = stage >= ISSUE_COVER_DONE
    return BackgroundJob(
        id = COVER_JOB_ID,
        sessionId = LAB_SESSION_ID,
        runner = BackgroundJobRunner.IN_APP,
        summary = t.coverJobSummary,
        state = if (done) BackgroundJobState.EXITED else BackgroundJobState.RUNNING,
        exitCode = 0.takeIf { done },
        startedAtMillis = IssueTimes.COVER + 300,
        endedAtMillis = (IssueTimes.COVER_DONE - 100).takeIf { done },
        maxRuntimeMinutes = 10,
        onExit = BackgroundJobExitAction.WAKE,
        logWorkspacePath = null,
        liveOutputKey = "job:$COVER_JOB_ID",
        title = t.coverJob,
    )
}
