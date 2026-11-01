package com.promenar.infinity.ui.lab.chat

import com.promenar.infinity.data.model.ApprovalCallIdentity
import com.promenar.infinity.data.model.ApprovalRequest
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.chat.ChatScreenState
import com.promenar.infinity.ui.chat.ChatUiState
import com.promenar.infinity.ui.chat.GenerationStatus
import com.promenar.infinity.ui.chat.runmode.RunModeBadge
import com.promenar.infinity.ui.common.ExecutionMode
import com.promenar.infinity.ui.lab.timeline.LabTimeline
import com.promenar.infinity.ui.lab.timeline.labTimeline
import com.promenar.infinity.ui.workspace.SessionWorkspaceUi

/**
 * “web-01 502 排障”的脚本：每一轮先说明意图，再提交远程命令等待批准，批准后结果落地。
 * 三轮依次是摸清部署、查日志与配置、修复并验证；远端账户与路径一律用 ~ 表示。
 */
internal data class OpsState(
    val text: LabOpsText,
    val stage: Int = OPS_START,
    val partial: String? = null,
)

internal const val OPS_START = 0
internal const val OPS_SURVEY = 1
internal const val OPS_APPROVE1 = 2
internal const val OPS_SURVEY_DONE = 3
internal const val OPS_FOUND = 4
internal const val OPS_APPROVE2 = 5
internal const val OPS_FOUND_DONE = 6
internal const val OPS_ROOT = 7
internal const val OPS_APPROVE3 = 8
internal const val OPS_FIX_DONE = 9
internal const val OPS_SUMMARY = 10
internal const val OPS_DONE = 11

internal object OpsTimes {
    const val SURVEY = 900L
    const val APPROVE1 = 2_700L
    const val SURVEY_DONE = 5_700L
    const val FOUND = 6_500L
    const val APPROVE2 = 9_700L
    const val FOUND_DONE = 12_200L
    const val ROOT = 13_000L
    const val APPROVE3 = 17_600L
    const val FIX_DONE = 21_000L
    const val SUMMARY = 21_900L
    const val DONE = 26_400L
    const val HOLD = 3_000L
}

internal fun opsTimeline(text: LabOpsText): LabTimeline<OpsState> = labTimeline(OpsState(text)) {
    fun set(at: Long, stage: Int) = at(at) { it.copy(stage = stage, partial = null) }
    fun say(at: Long, duration: Long, stage: Int, content: String) =
        streamOver(at, duration, content) { s, part -> s.copy(stage = stage, partial = part) }

    say(OpsTimes.SURVEY, 1_500, OPS_SURVEY, text.survey)
    set(OpsTimes.APPROVE1, OPS_APPROVE1)
    set(OpsTimes.SURVEY_DONE, OPS_SURVEY_DONE)
    say(OpsTimes.FOUND, 2_800, OPS_FOUND, text.found)
    set(OpsTimes.APPROVE2, OPS_APPROVE2)
    set(OpsTimes.FOUND_DONE, OPS_FOUND_DONE)
    say(OpsTimes.ROOT, 4_200, OPS_ROOT, text.rootCause)
    set(OpsTimes.APPROVE3, OPS_APPROVE3)
    set(OpsTimes.FIX_DONE, OPS_FIX_DONE)
    say(OpsTimes.SUMMARY, 4_000, OPS_SUMMARY, text.summary)
    set(OpsTimes.DONE, OPS_DONE)
    hold(OpsTimes.HOLD)
}

private const val HOST = "web-01"

/** 远程命令调用与它的命令原文；审批卡的位置一栏显示命令原文。 */
private data class OpsCall(val call: LabCall, val command: String)

private fun ssh(callId: String, title: String, command: String, at: Long, durationMs: Long, result: String, done: Boolean) = OpsCall(
    LabCall(
        callId = callId,
        tool = "ssh_exec",
        args = """{"host":"$HOST","title":${jsonString(title)},"command":${jsonString(command)}}""",
        at = at,
        endAt = (at + durationMs).takeIf { done },
        result = result,
    ),
    command,
)

internal fun opsFrame(s: OpsState): LabChatFrame {
    val t = s.text
    val stage = s.stage
    fun body(at: Int, full: String) = if (stage == at && s.partial != null) s.partial else full

    val surveyCalls = listOf(
        ssh("call_status", t.cmdStatus, "systemctl --user status starlog-api starlog-edge --no-pager", OpsTimes.SURVEY_DONE, 612,
            "● starlog-edge.service  active (running)\n● starlog-api.service  activating (auto-restart)  status=1/FAILURE", stage >= OPS_SURVEY_DONE),
        ssh("call_ports", t.cmdPorts, "ss -ltnp | grep -E ':8080|:8081'", OpsTimes.SURVEY_DONE + 612, 438,
            "LISTEN 0 511 *:8080 users:((\"starlog-edge\"))", stage >= OPS_SURVEY_DONE),
    )
    val foundCalls = listOf(
        ssh("call_log", t.cmdLog, "journalctl --user -u starlog-api -n 30 --no-pager", OpsTimes.FOUND_DONE, 704,
            "sqlite3.OperationalError: unable to open database file\nstarlog-api.service: Main process exited, code=exited, status=1/FAILURE",
            stage >= OPS_FOUND_DONE),
        ssh("call_config", t.cmdConfig, "cat ~/starlog/config.toml && stat -c '%y' ~/starlog/config.toml", OpsTimes.FOUND_DONE + 704, 391,
            "port = 8081\ndb_path = \"/srv/starlog/data/starlog.db\"\n2026-10-31 20:32:20", stage >= OPS_FOUND_DONE),
    )
    val fixCalls = listOf(
        ssh("call_restore", t.cmdRestore, "sed -i 's#^db_path = .*#db_path = \"~/starlog/data/starlog.db\"#' ~/starlog/config.toml && grep -E 'port|db_path' ~/starlog/config.toml",
            OpsTimes.FIX_DONE, 356, "port = 8081\ndb_path = \"~/starlog/data/starlog.db\"", stage >= OPS_FIX_DONE),
        ssh("call_restart", t.cmdRestart, "systemctl --user restart starlog-api && echo '=== is-active ===' && systemctl --user is-active starlog-api",
            OpsTimes.FIX_DONE + 356, 1_480, "=== is-active ===\nactive", stage >= OPS_FIX_DONE),
        ssh("call_verify", t.cmdVerify, "curl -s localhost:8081/health; echo; curl -s -o /dev/null -w '[%{http_code}]\\n' localhost:8080/health",
            OpsTimes.FIX_DONE + 1_836, 297, "{\"status\": \"ok\"}\n[200]", stage >= OPS_FIX_DONE),
    )

    val messages = buildList {
        add(userMessage("ops_user", t.prompt, 0))
        if (stage >= OPS_SURVEY) add(assistantMessage("ops_a1", body(OPS_SURVEY, t.survey), OpsTimes.SURVEY,
            if (stage >= OPS_APPROVE1) surveyCalls.map { it.call } else emptyList(), pendingApproval = stage == OPS_APPROVE1))
        if (stage >= OPS_FOUND) add(assistantMessage("ops_a2", body(OPS_FOUND, t.found), OpsTimes.FOUND,
            if (stage >= OPS_APPROVE2) foundCalls.map { it.call } else emptyList(), pendingApproval = stage == OPS_APPROVE2))
        if (stage >= OPS_ROOT) add(assistantMessage("ops_a3", body(OPS_ROOT, t.rootCause), OpsTimes.ROOT,
            if (stage >= OPS_APPROVE3) fixCalls.map { it.call } else emptyList(), pendingApproval = stage == OPS_APPROVE3))
        if (stage >= OPS_SUMMARY) add(assistantMessage("ops_a4", body(OPS_SUMMARY, t.summary), OpsTimes.SUMMARY))
    }
    val approval = when (stage) {
        OPS_APPROVE1 -> opsApproval("ops_a1", surveyCalls)
        OPS_APPROVE2 -> opsApproval("ops_a2", foundCalls)
        OPS_APPROVE3 -> opsApproval("ops_a3", fixCalls)
        else -> null
    }
    val generating = stage < OPS_DONE
    val streaming = s.partial != null && stage in setOf(OPS_SURVEY, OPS_FOUND, OPS_ROOT, OPS_SUMMARY)
    return LabChatFrame(
        screen = ChatScreenState(
            uiState = ChatUiState(
                session = labSession(t.sessionTitle, "workspace"),
                agentName = LAB_AGENT,
                messages = messages,
                isGenerating = generating,
                status = when {
                    !generating -> GenerationStatus.IDLE
                    streaming -> GenerationStatus.RECEIVING
                    else -> GenerationStatus.THINKING
                },
                streamingContent = if (streaming) s.partial.orEmpty() else "",
                approvalRequest = approval,
            ),
            modelDisplayNames = mapOf(LAB_MODEL to LAB_MODEL),
            generationStartedAt = 0L.takeIf { generating },
            generationPhase = when {
                !generating -> null
                approval != null -> GenerationPhase.WAITING_APPROVAL
                streaming -> GenerationPhase.STREAMING
                else -> GenerationPhase.THINKING
            },
            collapseFinishedTurns = false,
            runMode = RunModeBadge(SessionRunMode.LINUX, unavailable = false),
            executionMode = ExecutionMode.WORKSPACE,
            workspace = SessionWorkspaceUi.Private,
        ),
    )
}

/** 审批队列：每条调用都是需要批准的远程进程操作，参数摘要原样展示。 */
private fun opsApproval(messageId: String, calls: List<OpsCall>) = ApprovalRequest(
    assistantMessageId = messageId,
    identityHash = "lab-approval-$messageId",
    calls = calls.map { (call, command) ->
        ApprovalCallIdentity(
            toolCallId = call.callId,
            runtimeToolId = call.tool,
            toolName = call.tool,
            argumentsDigest = "lab",
            definitionDigest = "lab",
            requiresApproval = true,
            argumentsSummary = call.args,
            risk = "process",
            executionTarget = """["process",${jsonString(HOST)},${jsonString(command)}]""",
        )
    },
)
