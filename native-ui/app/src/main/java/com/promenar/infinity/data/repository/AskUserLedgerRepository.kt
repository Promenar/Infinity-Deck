package com.promenar.infinity.data.repository

import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.ToolExecutionLedgerEntity
import com.promenar.infinity.data.local.db.entity.ToolLedgerStatus
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.domain.ask.*
import com.promenar.infinity.startup.StartupSubsystem
import com.promenar.infinity.startup.StartupWriteBarrier
import com.promenar.infinity.ui.chat.manager.AskUserDecode
import com.promenar.infinity.ui.chat.manager.AskUserProtocol
import com.promenar.infinity.ui.chat.manager.AskUserValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

data class AskUserBatchResult(val pending: PendingQuestion?, val messages: List<Message>)

/** 回答、工具结果与唯一的续跑助手消息在同一事务中创建。 */
data class AskUserResumption(val result: Message, val assistant: Message, val user: Message)

/** 沿用账本文本状态和助手消息的工具参数；不改变 Room 表结构。 */
class AskUserLedgerRepository(
    private val database: InfinityDatabase,
    private val executionGate: SessionExecutionGate? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val ledger = database.toolExecutionLedgerDao()
    private val messages = database.messageDao()
    private val sessions = database.sessionDao()

    private suspend fun <T> writable(sessionId: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        StartupWriteBarrier.requireWritable(sessionId = sessionId, subsystem = StartupSubsystem.TOOL_LEDGER)
        if (executionGate == null) block() else executionGate.withSessionMutation(sessionId, block)
    }

    fun observePending(sessionId: String): Flow<PendingQuestion?> = ledger.observeInputForSession(sessionId).map { pending(sessionId) }
    fun observeResumption(sessionId: String): Flow<AskUserResumption?> = ledger.observeInputForSession(sessionId).map { resumption(sessionId) }
    fun observeAllPending(): Flow<List<PendingQuestion>> = ledger.observeAllPendingInput().map { entries ->
        entries.map { it.sessionId }.distinct().mapNotNull { pending(it) }
    }

    suspend fun pending(sessionId: String): PendingQuestion? = withContext(Dispatchers.IO) {
        database.withTransaction { pendingInTransaction(sessionId) }
    }

    private suspend fun pendingInTransaction(sessionId: String): PendingQuestion? {
        val session = sessions.getById(sessionId) ?: return null
        if (session.loopStatus != LoopStatus.WAITING_FOR_INPUT.toSerializedName() || session.approvalRequest != null) return null
        val entry = ledger.getInputForSession(sessionId).singleOrNull { it.status == ToolLedgerStatus.WAITING_INPUT } ?: return null
        if (entry.requiresApproval || entry.toolName != AskUserLimits.TOOL_NAME || entry.runtimeToolId != AskUserLimits.TOOL_NAME) return null
        val assistant = messages.getById(entry.assistantMessageId)?.takeIf { it.sessionId == sessionId && it.role == "assistant" } ?: return null
        val calls = assistant.toolCalls?.let { runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull() } ?: return null
        val call = calls.singleOrNull { it.id == entry.toolCallId && it.name == AskUserLimits.TOOL_NAME } ?: return null
        val arguments = com.promenar.infinity.domain.tool.ToolArgumentsValidator().validate(call.arguments)
            as? com.promenar.infinity.domain.tool.ToolArgumentsValidation.Valid ?: return null
        if (arguments.sha256 != entry.argumentsDigest) return null
        val request = (AskUserProtocol.decode(call.arguments) as? AskUserDecode.Valid)?.request ?: return null
        return PendingQuestion(sessionId, assistant.id, call.id, request)
    }

    /** 先对整个批次作决定，任何普通工具都不会在提问批次内执行。 */
    suspend fun createBatch(
        sessionId: String,
        assistantMessageId: String,
        calls: List<ToolCall>,
        availableTool: ProtocolTool?,
    ): AskUserBatchResult = writable(sessionId) {
        database.withTransaction {
            val assistant = messages.getById(assistantMessageId)?.takeIf { it.sessionId == sessionId && it.role == "assistant" }
                ?: error("提问所属助手消息不存在")
            val session = sessions.getById(sessionId) ?: error("提问所属会话不存在")
            val distinct = calls.distinctBy { it.id }
            val first = distinct.first { it.name == AskUserLimits.TOOL_NAME }
            val existing = ledger.getInputForSession(sessionId)
            val decoded = AskUserProtocol.decode(first.arguments)
            val identity = availableTool?.takeIf {
                it.function.name == AskUserLimits.TOOL_NAME && it.runtimeToolId == AskUserLimits.TOOL_NAME && it.sourceId == "builtin"
            }?.let { ToolInvocationIdentityFactory.fromPreparedToolCall(first, it, requiresApproval = false) }
                as? ToolInvocationIdentityResolution.Valid
            val conflict = session.approvalRequest != null || ledger.getAwaitingApprovalForSession(sessionId).isNotEmpty() ||
                existing.any { it.assistantMessageId != assistantMessageId || it.toolCallId != first.id }
            val persisted = ledger.get(sessionId, assistantMessageId, first.id)
            if (persisted?.status == ToolLedgerStatus.WAITING_INPUT) {
                return@withTransaction AskUserBatchResult(pendingInTransaction(sessionId), emptyList())
            }
            val previousCalls = if (conflict || existing.isNotEmpty()) assistant.toolCalls?.let {
                runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
            }.orEmpty() else emptyList()
            val previousIds = previousCalls.mapTo(mutableSetOf()) { it.id }
            val persistedCalls = previousCalls + distinct.filterNot { it.id in previousIds }
            check(messages.updateQuestionToolCalls(sessionId, assistantMessageId, json.encodeToString(persistedCalls)) == 1)
            val results = mutableListOf<Message>()
            var question: PendingQuestion? = null
            distinct.forEach { call ->
                // 已有终态或不同身份的调用不覆盖，不产生第二条结果。
                if (ledger.get(sessionId, assistantMessageId, call.id) != null) return@forEach
                val failure = when {
                    call.name != AskUserLimits.TOOL_NAME -> AskUserProtocol.failure("ASK_USER_BATCH_NOT_EXECUTED",
                        "本批包含 ask_user，其它调用未执行；得到回答后请按需重新调用。")
                    call.id != first.id -> AskUserProtocol.failure("ASK_USER_MULTIPLE_CALLS", "一次只能提一个问题。",
                        "请把相关问题集中到一次 ask_user 调用中。")
                    availableTool == null -> AskUserProtocol.unavailable()
                    decoded is AskUserDecode.Invalid -> AskUserProtocol.failure("ASK_USER_INVALID_ARGUMENTS", decoded.message)
                    identity == null -> AskUserProtocol.failure("ASK_USER_INVALID_ARGUMENTS", "工具定义或参数结构不符合 ask_user 契约。")
                    conflict -> AskUserProtocol.failure("ASK_USER_CONFLICT", "该会话已有待回答或待审批的请求。",
                        "请等待当前请求处理完成。")
                    else -> null
                }
                val now = clock()
                val result = failure?.let { content -> Message(
                    id = "ask_result_${UUID.randomUUID()}", role = MessageRole.TOOL, content = content,
                    toolCallId = call.id, name = call.name, parentMessageId = assistantMessageId,
                    thoughtSignature = assistant.thoughtSignature, createdAt = now,
                ) }
                val validIdentity = identity?.identity?.takeIf { call.id == first.id }
                check(ledger.insert(ToolExecutionLedgerEntity(
                    sessionId = sessionId, assistantMessageId = assistantMessageId, toolCallId = call.id,
                    toolName = call.name, runtimeToolId = validIdentity?.runtimeToolId ?: "ask_user_rejected",
                    argumentsDigest = validIdentity?.argumentsDigest ?: digest(call.name + "\u0000" + call.arguments),
                    definitionDigest = validIdentity?.definitionDigest ?: digest("ask_user_rejected"), requiresApproval = false,
                    status = if (failure == null) ToolLedgerStatus.WAITING_INPUT else ToolLedgerStatus.FAILED,
                    resultMessageId = result?.id, error = if (failure != null) "提问批次校验失败" else null,
                    createdAt = now, updatedAt = now,
                )) != -1L)
                if (result != null) { messages.insert(result.toEntity(sessionId)); results += result }
                else question = PendingQuestion(sessionId, assistantMessageId, call.id, (decoded as AskUserDecode.Valid).request)
            }
            if (question != null) check(sessions.updateToolApprovalState(sessionId, null,
                LoopStatus.WAITING_FOR_INPUT.toSerializedName(), clock()) == 1)
            AskUserBatchResult(question, results)
        }
    }

    suspend fun answer(expected: PendingQuestion, answer: AskUserAnswer): AskUserResumption? = writable(expected.sessionId) {
        database.withTransaction {
            if (pendingInTransaction(expected.sessionId) != expected || AskUserValidation.answer(expected.request, answer) != null) return@withTransaction null
            val assistant = messages.getById(expected.assistantMessageId) ?: return@withTransaction null
            val user = assistant.parentMessageId?.let { messages.getById(it) }?.takeIf { it.sessionId == expected.sessionId && it.role == "user" }
                ?: messages.getBySession(expected.sessionId).takeWhile { it.id != assistant.id }.lastOrNull { it.role == "user" }
                ?: error("提问缺少所属用户消息")
            val now = clock()
            val result = Message(id = "ask_result_${UUID.randomUUID()}", role = MessageRole.TOOL,
                content = Json.encodeToString(answer), toolCallId = expected.toolCallId, name = AskUserLimits.TOOL_NAME,
                parentMessageId = assistant.id, thoughtSignature = assistant.thoughtSignature, createdAt = now)
            if (ledger.decideTerminal(expected.sessionId, assistant.id, expected.toolCallId,
                    setOf(ToolLedgerStatus.WAITING_INPUT), ToolLedgerStatus.INPUT_ANSWERED, result.id, null, now) != 1) return@withTransaction null
            val session = sessions.getById(expected.sessionId) ?: error("提问所属会话不存在")
            sessions.updateRow(session.copy(sessionAutoWakeCount = 0, sessionAutoProcessingPaused = false))
            val continuation = Message(id = resumeId(result.id), role = MessageRole.ASSISTANT, content = "",
                parentMessageId = user.id, modelId = assistant.modelId, createdAt = now + 1)
            messages.insert(result.toEntity(expected.sessionId))
            messages.insert(continuation.toEntity(expected.sessionId))
            check(sessions.updateToolApprovalState(expected.sessionId, null, LoopStatus.INPUT_ANSWERED.toSerializedName(), now) == 1)
            AskUserResumption(result, continuation, user.toDomain())
        }
    }

    /** 回答落盘与任务启动之间退出时，复用已持久化的唯一助手消息。 */
    suspend fun resumption(sessionId: String): AskUserResumption? = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (sessions.getById(sessionId)?.loopStatus != LoopStatus.INPUT_ANSWERED.toSerializedName()) return@withTransaction null
            val entry = ledger.getInputForSession(sessionId).singleOrNull { it.status == ToolLedgerStatus.INPUT_ANSWERED } ?: return@withTransaction null
            val result = entry.resultMessageId?.let { messages.getById(it) } ?: return@withTransaction null
            val assistant = messages.getById(resumeId(result.id)) ?: return@withTransaction null
            if (assistant.status in setOf("success", "error", "cancelled", "paused")) return@withTransaction null
            val user = assistant.parentMessageId?.let { messages.getById(it) } ?: return@withTransaction null
            AskUserResumption(result.toDomain(), assistant.toDomain(), user.toDomain())
        }
    }

    /** 普通生成消息不读取提问账本，保留其准备失败的同步收口路径。 */
    suspend fun resumptionForAssistant(sessionId: String, assistantMessageId: String): AskUserResumption? =
        if (!assistantMessageId.startsWith(RESUME_PREFIX)) null
        else resumption(sessionId)?.takeIf { it.assistant.id == assistantMessageId }

    /** 首个模型回合或终态持久化后消费；准备、排队、忙碌或启动失败保留恢复入口。 */
    suspend fun consumeResumption(sessionId: String, assistantMessageId: String): Boolean {
        if (!assistantMessageId.startsWith(RESUME_PREFIX)) return false
        return writable(sessionId) {
            database.withTransaction {
                val entry = ledger.getInputForSession(sessionId).singleOrNull {
                    it.status == ToolLedgerStatus.INPUT_ANSWERED && it.resultMessageId?.let(::resumeId) == assistantMessageId
                } ?: return@withTransaction false
                if (ledger.consumeInputResume(sessionId, entry.assistantMessageId, entry.toolCallId, clock()) != 1) return@withTransaction false
                check(sessions.updateToolApprovalState(sessionId, null, LoopStatus.RUNNING.toSerializedName(), clock()) == 1)
                true
            }
        }
    }

    /** 终态落盘后、恢复标记关闭前退出的窗口，仅补齐账本，不重复请求模型。 */
    suspend fun recoverAnsweredState() {
        ledger.getAllInput().filter { it.status == ToolLedgerStatus.INPUT_ANSWERED }.forEach { entry ->
            writable(entry.sessionId) {
                database.withTransaction {
                    val target = entry.resultMessageId?.let { messages.getById(resumeId(it)) }
                    if (target == null || target.status in setOf("success", "error", "cancelled", "paused")) {
                        if (ledger.consumeInputResume(entry.sessionId, entry.assistantMessageId, entry.toolCallId, clock()) == 1) {
                            sessions.updateToolApprovalState(entry.sessionId, null,
                                (if (target?.status == "success") LoopStatus.COMPLETED else LoopStatus.PAUSED).toSerializedName(), clock())
                        }
                    }
                }
            }
        }
    }

    suspend fun cancel(sessionId: String, interruptionReason: String? = null): List<Message> = writable(sessionId) { cancelInTransaction(sessionId, interruptionReason) }

    /** 删除入口已封闭会话写入，不能重新申请普通写租约。 */
    internal suspend fun cancelForDeletion(sessionId: String): List<Message> = withContext(Dispatchers.IO) {
        check(executionGate?.isDeleting(sessionId) == true) { "提问删除收口必须持有会话删除门禁" }
        StartupWriteBarrier.requireWritable(sessionId = sessionId)
        cancelInTransaction(sessionId)
    }

    private suspend fun cancelInTransaction(sessionId: String, interruptionReason: String? = null): List<Message> = database.withTransaction {
        val results = mutableListOf<Message>()
        val entries = ledger.getInputForSession(sessionId)
        entries.forEach { entry ->
            if (entry.status == ToolLedgerStatus.INPUT_ANSWERED) {
                ledger.consumeInputResume(sessionId, entry.assistantMessageId, entry.toolCallId, clock())
                entry.resultMessageId?.let { messages.updateStatus(resumeId(it), "cancelled") }
            } else {
                val result = Message(id = "ask_cancel_${UUID.randomUUID()}", role = MessageRole.TOOL,
                    name = entry.toolName, toolCallId = entry.toolCallId, parentMessageId = entry.assistantMessageId,
                    content = if (interruptionReason != null) interruptionReason else if (entry.toolName == com.promenar.infinity.domain.secret.SecretIoLimits.REQUEST_TOOL_NAME)
                        Json.encodeToString<com.promenar.infinity.domain.secret.SecretInputOutcome>(com.promenar.infinity.domain.secret.SecretInputOutcome.Cancelled)
                    else if (entry.toolName == "session_branch") "工作方案未处理，用户已停止本轮。" else AskUserProtocol.failure("ASK_USER_CANCELLED", "用户未回答并停止了本轮。", "本轮已停止，请勿继续执行。"),
                    thoughtSignature = messages.getById(entry.assistantMessageId)?.thoughtSignature, createdAt = clock())
                if (ledger.decideTerminal(sessionId, entry.assistantMessageId, entry.toolCallId,
                        setOf(ToolLedgerStatus.WAITING_INPUT), ToolLedgerStatus.CANCELLED, result.id, "用户未回答并停止了本轮", clock()) == 1) {
                    messages.insert(result.toEntity(sessionId)); results += result
                }
            }
        }
        if (entries.isNotEmpty()) sessions.updateToolApprovalState(sessionId, null, LoopStatus.PAUSED.toSerializedName(), clock())
        results
    }

    private fun resumeId(resultId: String) = "$RESUME_PREFIX$resultId"
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private companion object { const val RESUME_PREFIX = "ask_resume_" }
}
