package com.promenar.infinity.data.branch

import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.repository.AskUserLedgerRepository
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.domain.branch.*
import com.promenar.infinity.domain.generation.*
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.MessageManager
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** 分支仍走普通会话生成器；派发服务只准备持久输入、工作区和续跑，不拥有父轮的取消权。 */
class BranchSessionService(
    private val database: InfinityDatabase,
    private val plans: BranchPlanLedger,
    private val questions: AskUserLedgerRepository,
    private val scope: CoroutineScope,
    private val coordinator: GenerationCoordinator,
    private val store: ChatStore,
    private val messages: MessageManager,
    private val load: suspend (String) -> Session?,
    private val ensurePrivateRoot: suspend (String) -> Unit,
    private val worktrees: BranchWorktreeService,
    private val mailbox: SessionMessageStore,
    private val gate: SessionExecutionGate,
    private val track: (GenerationTaskSnapshot) -> Unit,
    private val worktreeFailedText: (String) -> String,
    private val subagentTitle: suspend (String, String) -> String = { _, id -> id },
    private val reconcileSharedSource: suspend (String, String) -> Unit = { _, _ -> },
) : BranchControls {
    private val starts = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    override fun observePendingPlan(sessionId: String): Flow<PendingBranchPlan?> = plans.observePending(sessionId)
    override fun observeBranches(parentSessionId: String): Flow<List<BranchSummary>> =
        combine(database.sessionDao().observeAll(), coordinator.sessions) { rows, tasks ->
            val parent = rows.firstOrNull { it.id == parentSessionId }
            rows.filter { it.parentSessionId == parentSessionId && it.agentId == parent?.agentId }.sortedBy { it.createdAt }.map { row ->
                val rounds = database.messageDao().getBySession(row.id).count { it.role == "assistant" && it.status == "success" && it.toolCalls.isNullOrBlank() }
                val phase = tasks[row.id]?.phase
                val activity = when {
                    phase == GenerationPhase.QUEUED || phase == null && row.loopStatus == "branch_starting" -> BranchActivity.QUEUED
                    phase == GenerationPhase.WAITING_INPUT -> BranchActivity.WAITING_INPUT
                    phase == GenerationPhase.WAITING_APPROVAL -> BranchActivity.WAITING_APPROVAL
                    coordinator.isSessionBusy(row.id) -> BranchActivity.GENERATING
                    row.loopStatus == "waiting_for_input" -> if (plans.pending(row.id) != null) BranchActivity.WAITING_APPROVAL else BranchActivity.WAITING_INPUT
                    row.approvalRequest != null || row.loopStatus == "waiting_for_approval" -> BranchActivity.WAITING_APPROVAL
                    rounds > 0 -> BranchActivity.ROUND_DONE
                    else -> BranchActivity.IDLE
                }
                BranchSummary(row.id, parentSessionId, row.title, activity, row.worktreeBranch, rounds, row.updatedAt)
            }
        }.flowOn(Dispatchers.IO)
    fun observeRunningBranchCount(parentSessionId: String): Flow<Int> = observeBranches(parentSessionId).map {
        it.count { branch -> branch.activity in setOf(BranchActivity.GENERATING, BranchActivity.QUEUED) }
    }
    override suspend fun resolvePlan(pending: PendingBranchPlan, answer: BranchPlanAnswer) = withContext(Dispatchers.IO) {
        mailbox.resetForUserInput(pending.sessionId)
        val result = plans.resolve(pending, answer) ?: return@withContext
        messages.mirrorPersistedMessage(pending.sessionId, result.continuation.result)
        messages.mirrorPersistedMessage(pending.sessionId, result.continuation.assistant)
        store.updateSession(pending.sessionId) { it.copy(loopStatus = LoopStatus.INPUT_ANSWERED) }
        result.branchSessionId?.let { id -> scope.launch { startBranch(id) } }
        scope.launch { resumePlan(pending.sessionId) }
    }
    suspend fun prepareWorkspace(id: String) = starts.getOrPut(id) { Mutex() }.withLock {
        var session = withContext(Dispatchers.IO) { database.sessionDao().getById(id) } ?: return@withLock
        if (session.branchPlanJson == null) return@withLock
        ensurePrivateRoot(id)
        session = checkNotNull(database.sessionDao().getById(id))
        store.updateSession(id) { it.copy(workspaceRootUuid = session.workspaceRootUuid, workspacePath = session.workspacePath) }
        val plan = session.branchPlanJson?.let { BranchPlanProtocol.decode(it, session.executionMode).getOrThrow() } ?: return@withLock
        if (plan.workMode != BranchWorkMode.WORKTREE || session.worktreeBranch != null) return@withLock
        val binding = try {
            val authorization = database.messageDao().getBySession(id).singleOrNull { it.name == BranchWorktreeAuthorization.MESSAGE_NAME }
                ?.let { Json.decodeFromString<BranchWorktreeAuthorization>(it.content) }
                ?: error("worktree 缺少已批准的命令身份")
            val workspace = session.workspaceId?.let { database.workspaceDao().getById(it) }
            require(session.workspaceId == authorization.workspaceId && workspace?.kind == authorization.workspaceKind &&
                workspace.location == authorization.workspaceLocation) { "获批工作区已变化，请重新提交方案" }
            val command = BranchWorktreeCommands.plan(authorization.workingDirectory, plan)
            require(BranchWorktreeCommands.matchesApprovedCommand(command, authorization.workingDirectory, authorization.command)) { "获批命令参数已变化，请重新提交方案" }
            if (authorization.mountId != null) {
                val mount = database.externalMountDao().get(authorization.mountId)
                require(mount != null && mount.scopeKey == id && mount.sourceId == authorization.mountSource &&
                    mount.sourceType == "real_path" && !mount.readOnly && mount.unavailableReason == null &&
                    command.directory.startsWith("/mnt/${mount.alias}/")) { "获批挂载来源已变化，请重新提交方案" }
            } else require(!command.directory.startsWith("/mnt/")) { "worktree 缺少已批准的挂载来源" }
            val frozenSource = session.copy(worktreeDirectory = null, worktreeBranch = null, worktreeBase = null)
            worktrees.create(frozenSource, session, plan).getOrThrow()
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val explanation = Message(id = "branch_worktree_failure_$id", role = MessageRole.ASSISTANT,
                content = worktreeFailedText(failure.message.orEmpty()), status = "error")
            if (database.messageDao().getById(explanation.id) == null) messages.addMessage(id, explanation)
            throw IllegalStateException("分支工作区准备失败，已停止", failure)
        }
        database.withTransaction {
            val latest = checkNotNull(database.sessionDao().getById(id))
            database.sessionDao().updateRow(latest.copy(worktreeDirectory = binding.directory,
                worktreeBranch = binding.branch, worktreeBase = binding.baseCommit))
        }
        store.updateSession(id) { it.copy(worktreeDirectory = binding.directory, worktreeBranch = binding.branch, worktreeBase = binding.baseCommit) }
    }
    private suspend fun startBranch(id: String) {
        try {
            gate.withNewSessionAdmission(id) {
                val session = load(id) ?: return@withNewSessionAdmission
                store.getOrLoadSession(id) { session }
                val user = session.messages.firstOrNull { it.name == BranchPlanLedger.PLAN_MESSAGE_NAME } ?: return@withNewSessionAdmission
                val existing = session.messages.firstOrNull { it.id == "branch_start_$id" }
                if (existing != null && (existing.content.isNotEmpty() || !existing.toolCalls.isNullOrEmpty() ||
                    existing.status != null || existing.tokens != null)) return@withNewSessionAdmission
                startTurn(id, user, "branch_start_$id")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            withContext(Dispatchers.IO) {
                val row = database.sessionDao().getById(id) ?: return@withContext
                val text = worktreeFailedText(failure.message.orEmpty())
                database.messageDao().insert(Message(id = "branch_failure_$id", role = MessageRole.ASSISTANT,
                    content = text, status = "error").toEntity(id))
                database.sessionDao().updateToolApprovalState(id, null, "paused", System.currentTimeMillis())
                load(id)?.let { store.upsertSession(it) }
            }
        }
    }
    private suspend fun resumePlan(id: String) {
        coordinator.awaitSessionIdle(id)
        val continuation = questions.resumption(id) ?: return
        store.getOrLoadSession(id) { load(id) }
        messages.mirrorPersistedMessage(id, continuation.result)
        messages.mirrorPersistedMessage(id, continuation.assistant)
        startTurn(id, continuation.user, continuation.assistant.id)
    }
    private suspend fun startTurn(id: String, user: Message, assistantId: String) = store.turnAdmission.withLock(id) {
        if (coordinator.isSessionBusy(id)) return@withLock
        val session = store.getSession(id) ?: return@withLock
        if (session.messages.none { it.id == assistantId }) messages.addMessage(id, Message(id = assistantId,
            role = MessageRole.ASSISTANT, content = "", parentMessageId = user.id, modelId = session.modelId))
        val result = coordinator.start(GenerationRequest(id, assistantId, user.id, user.content, user.userImages.orEmpty(),
            GenerationRuntimePolicy.BACKGROUND_ALLOWED, requestId = assistantId, interactionMode = GenerationInteractionMode.INTERACTIVE))
        val taskId = when (result) {
            is StartGenerationResult.Started -> result.taskId
            is StartGenerationResult.Queued -> result.taskId
            is StartGenerationResult.Existing -> result.snapshot.taskId
            is StartGenerationResult.Busy -> return@withLock
            is StartGenerationResult.Rejected -> error("生成器未接受分支续跑请求：${result.error.code}")
        }
        if (assistantId.startsWith("branch_start_")) withContext(Dispatchers.IO) {
            database.sessionDao().updateToolApprovalState(id, null, "running", System.currentTimeMillis())
        }
        coordinator.observe(id).value?.takeIf { it.taskId == taskId }?.let(track)
        scope.launch {
            val terminal = coordinator.awaitTaskCompletion(id, taskId)
            if (terminal?.phase != GenerationPhase.PERSISTENCE_FAILED) coordinator.acknowledgeTerminal(taskId)
            coordinator.release(id, taskId)
        }
    }
    suspend fun recover() = withContext(Dispatchers.IO) {
        database.sessionDao().getAll().forEach { row ->
            if (row.branchPlanJson != null && row.loopStatus in setOf("branch_starting", "running")) scope.launch { startBranch(row.id) }
            if (questions.resumption(row.id)?.result?.name == BranchLimits.TOOL_NAME) scope.launch { resumePlan(row.id) }
        }
        mailbox.recover()
    }
    suspend fun afterTools(id: String) = withContext(Dispatchers.IO) {
        val branch = database.sessionDao().getById(id) ?: return@withContext
        if (branch.branchPlanJson == null || branch.worktreeDirectory != null) return@withContext
        val source = branch.workspaceId?.let { database.workspaceDao().getById(it) }?.takeIf { it.kind == "branch_shared" } ?: return@withContext
        reconcileSharedSource(source.location, id)
    }
    override suspend fun removeWorktree(branchSessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(!coordinator.isSessionBusy(branchSessionId)) { "请先停止该分支当前生成" }
            val row = checkNotNull(database.sessionDao().getById(branchSessionId))
            worktrees.remove(row).getOrThrow()
            // 保留绑定，删除入口随后移除会话；移除后不能把一个仍存在的分支回退到共用目录。
        }
    }
    override suspend fun listWorktrees(sessionId: String): Result<List<WorktreeEntry>> = withContext(Dispatchers.IO) {
        val row = database.sessionDao().getById(sessionId) ?: return@withContext Result.failure(IllegalArgumentException("会话不存在"))
        worktrees.list(row, database.sessionDao().getAll().filter { it.agentId == row.agentId })
    }
    suspend fun approvalTarget(source: Session, toolName: String, arguments: String): String? = withContext(Dispatchers.IO) {
        if (toolName !in setOf("session_list", "session_read", "session_search")) return@withContext null
        val args = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: return@withContext null
        val row = database.sessionDao().getById(source.id) ?: return@withContext null
        if (toolName == "session_list") return@withContext if (args["scope"]?.jsonPrimitive?.content == "assistant")
            SessionCommunicationPolicy.approvalTarget(row, "assistant") else null
        val id = args["id"]?.jsonPrimitive?.content ?: return@withContext null
        if (id.startsWith("sa_")) return@withContext null
        val target = database.sessionDao().getById(id) ?: return@withContext null
        if (SessionCommunicationPolicy.access(row, target, toolName) == SessionCommunicationPolicy.Access.APPROVAL)
            SessionCommunicationPolicy.approvalTarget(row, id) else null
    }
    suspend fun list(args: JsonObject, context: SkillExecutionContext): String = withContext(Dispatchers.IO) {
        val source = requireNotNull(database.sessionDao().getById(context.sessionId)) { "未找到该会话或子代理" }
        val all = args["scope"]?.jsonPrimitive?.content == "assistant"
        if (all) {
            require(source.parentSessionId == null && source.branchPlanJson == null && context.sourceSubagentId == null) { "分支或子代理只能访问自己的主会话" }
            require(context.isApprovalBoundExecution && context.approvedExecutionTarget == SessionCommunicationPolicy.approvalTarget(source, "assistant")) { "读取同一智能体的会话列表需要用户批准" }
        }
        val rows = database.sessionDao().getAll().filter { it.agentId == source.agentId &&
            (all || SessionCommunicationPolicy.access(source, it, "session_read", context.sourceSubagentId) == SessionCommunicationPolicy.Access.FREE) }
        buildJsonArray { rows.forEach { row -> add(buildJsonObject { put("id", row.id); put("title", row.title)
            put("parentSessionId", row.parentSessionId); put("parentSessionDeleted", row.parentSessionDeleted)
            put("phase", coordinator.observe(row.id).value?.phase?.name ?: row.loopStatus); put("worktreeBranch", row.worktreeBranch) }) } }.toString()
    }
    suspend fun address(operation: String, id: String, args: JsonObject, context: SkillExecutionContext): String = withContext(Dispatchers.IO) {
        val source = requireNotNull(database.sessionDao().getById(context.sessionId)) { "未找到该会话或子代理" }
        val target = requireNotNull(database.sessionDao().getById(id)) { "未找到该会话或子代理" }
        when (SessionCommunicationPolicy.access(source, target, operation, context.sourceSubagentId)) {
            SessionCommunicationPolicy.Access.DENIED -> throw IllegalArgumentException("未找到可执行该操作的会话或子代理")
            SessionCommunicationPolicy.Access.APPROVAL -> require(context.isApprovalBoundExecution &&
                context.approvedExecutionTarget == SessionCommunicationPolicy.approvalTarget(source, id)) { "读取其它会话需要用户批准" }
            else -> Unit
        }
        when (operation) {
            "session_send" -> {
                val text = args["message"]?.jsonPrimitive?.content.orEmpty()
                val wrapUp = args["wrapUp"]?.jsonPrimitive?.booleanOrNull ?: false
                require(text.isNotBlank() || wrapUp) { "message 不能为空" }
                val relation = if (context.sourceSubagentId != null) SessionMessageEntry.PeerRelation.SUBAGENT
                    else if (source.parentSessionId == id) SessionMessageEntry.PeerRelation.PARENT else SessionMessageEntry.PeerRelation.BRANCH
                mailbox.send(source.id, id, SessionMessageEntry(SessionMessageEntry.Direction.SENT,
                    id, relation, target.title, text, System.currentTimeMillis()), wrapUp, context.sourceSubagentId, context.sourceSubagentId?.let { subagentTitle(source.id, it) })
                "消息已持久化，等待在对方安全步骤边界送达。"
            }
            "session_stop" -> {
                store.turnAdmission.withLock(id) {
                    coordinator.cancelAndJoinSession(id, CancellationReason.USER)
                    gate.withNewSessionAdmission(id) {
                        questions.cancel(id).forEach { if (store.getSession(id) != null) messages.mirrorPersistedMessage(id, it) }
                        gate.withSessionMutation(id) {
                            val awaiting = database.toolExecutionLedgerDao().getAwaitingApprovalForSession(id)
                            awaiting.groupBy { it.assistantMessageId }.forEach { (assistantId, entries) ->
                                val terminal = com.promenar.infinity.data.repository.ToolExecutionLedgerRepository(database).terminalizeWithResults(
                                    entries.mapTo(linkedSetOf()) { entry -> com.promenar.infinity.data.repository.ToolTerminalRequest(
                                        com.promenar.infinity.data.repository.ToolExecutionKey(id, assistantId, entry.toolCallId), entry.toolName) },
                                    com.promenar.infinity.data.local.db.entity.ToolLedgerStatus.CANCELLED)
                                if (store.getSession(id) != null) {
                                    terminal.forEach { messages.mirrorPersistedMessage(id, it) }
                                    messages.mirrorPersistedPendingApprovalState(id, assistantId, null)
                                }
                            }
                            database.sessionDao().updateToolApprovalState(id, null, "paused", System.currentTimeMillis())
                            store.updateSession(id) { it.copy(approvalRequest = null, loopStatus = LoopStatus.PAUSED) }
                        }
                    }
                }
                "已停止该会话当前这一轮生成。"
            }
            "session_read" -> {
                val dao = database.messageDao()
                val internal = listOf(SessionMessageStore.DELIVERY_MESSAGE_NAME, SessionMessageStore.WAKE_MESSAGE_NAME, BranchWorktreeAuthorization.MESSAGE_NAME)
                val before = args["before"]?.jsonPrimitive?.content
                val after = args["after"]?.jsonPrimitive?.content
                require(before == null || after == null) { "before 与 after 不能同时使用" }
                val cursor = before ?: after
                if (cursor != null) require(dao.getById(cursor)?.sessionId == id) { "游标不存在或不属于该会话" }
                val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 40
                require(limit in 1..100) { "limit 无效" }
                val rows = if (after != null) dao.historyAfter(id, after, limit, internal)
                    else dao.historyBefore(id, before, limit, internal).asReversed()
                val first = rows.firstOrNull()?.id
                val last = rows.lastOrNull()?.id
                val moreBefore = if (first != null) dao.historyCountBefore(id, first, internal) > 0
                    else after != null && (dao.historyCountBefore(id, after, internal) > 0 || dao.getById(after)?.let { !SessionMessageStore.isInternal(it.name) } == true)
                val moreAfter = if (last != null) dao.historyCountAfter(id, last, internal) > 0
                    else before != null && (dao.historyCountAfter(id, before, internal) > 0 || dao.getById(before)?.let { !SessionMessageStore.isInternal(it.name) } == true)
                buildJsonObject {
                    put("id", id); put("title", target.title); put("phase", coordinator.observe(id).value?.phase?.name ?: target.loopStatus)
                    put("has_more_before", moreBefore); put("has_more_after", moreAfter)
                    put("messages", buildJsonArray { rows.forEach {
                        add(buildJsonObject { put("id", it.id); put("role", it.role); put("name", it.name); put("content", it.content.take(8_000))
                            put("archived", it.isArchived != 0); put("created_at", it.createdAt) })
                    } })
                }.toString()
            }
            "session_search" -> {
                val dao = database.messageDao()
                val internal = listOf(SessionMessageStore.DELIVERY_MESSAGE_NAME, SessionMessageStore.WAKE_MESSAGE_NAME, BranchWorktreeAuthorization.MESSAGE_NAME)
                val query = args["query"]!!.jsonPrimitive.content
                val role = args["role"]?.jsonPrimitive?.content
                val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20
                require(query.codePointCount(0, query.length) in 1..200 && limit in 1..50 && (role == null || role in setOf("user", "assistant", "tool"))) { "检索参数无效" }
                val pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                val rows = dao.searchHistory(id, pattern, role, limit, internal)
                buildJsonObject {
                    put("id", id); put("total_matches", dao.countHistoryMatches(id, pattern, role, internal))
                    put("messages", buildJsonArray { rows.forEach { row ->
                        add(buildJsonObject { put("message_id", row.id); put("role", row.role); put("created_at", row.createdAt)
                            put("archived", row.isArchived != 0); put("snippet", historySnippet(row.content, query)) })
                    } })
                }.toString()
            }
            else -> throw IllegalArgumentException("不支持的会话操作")
        }
    }
}

internal fun historySnippet(content: String, query: String): String {
    val match = content.indexOf(query, ignoreCase = true).coerceAtLeast(0)
    val matchEnd = (match + query.length).coerceAtMost(content.length)
    val start = content.offsetByCodePoints(match, -minOf(150, content.codePointCount(0, match)))
    val end = content.offsetByCodePoints(matchEnd, minOf(150, content.codePointCount(matchEnd, content.length)))
    return (if (start > 0) "…" else "") + content.substring(start, end) + (if (end < content.length) "…" else "")
}
