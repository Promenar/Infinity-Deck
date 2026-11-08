package com.promenar.infinity.data.branch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.*
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.*
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.repository.AskUserLedgerRepository
import com.promenar.infinity.data.repository.*
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.domain.branch.*
import com.promenar.infinity.domain.generation.*
import com.promenar.infinity.domain.trigger.*
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.MessageManager
import com.promenar.infinity.ui.chat.manager.registry.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
class BranchSessionGenerationTest {
    private lateinit var db: InfinityDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var store: ChatStore
    private lateinit var messages: MessageManager
    private lateinit var coordinator: DefaultGenerationCoordinator
    private lateinit var service: BranchSessionService
    private lateinit var plans: BranchPlanLedger
    private lateinit var questions: AskUserLedgerRepository
    private val entered = ConcurrentHashMap.newKeySet<String>()
    private var runnerPrepare: suspend (GenerationRequest) -> Unit = {}
    private var runnerStopped: suspend (GenerationRequest) -> Unit = {}
    private var addStarted: CompletableDeferred<Unit>? = null
    private var addCancelled: CompletableDeferred<Unit>? = null
    private val commandOwners = mutableListOf<String?>()
    private var added = false
    private val head = "1".repeat(40)

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        store = ChatStore(); messages = mockk(relaxed = true)
        coEvery { messages.addMessage(any(), any()) } coAnswers {
            val id = firstArg<String>(); val message = secondArg<Message>()
            db.messageDao().insert(message.toEntity(id)); mirror(id, message)
        }
        coEvery { messages.mirrorPersistedMessage(any(), any()) } coAnswers { mirror(firstArg(), secondArg()) }
        val gate = SessionExecutionGate()
        questions = AskUserLedgerRepository(db, gate); plans = BranchPlanLedger(db, gate)
        coordinator = DefaultGenerationCoordinator(scope, GenerationRunnerFactory { _, _ -> GenerationRunner { request, emit ->
            runnerPrepare(request)
            entered += request.sessionId
            emit(GenerationEvent.PhaseChanged(GenerationPhase.STREAMING))
            try { awaitCancellation() } finally { withContext(NonCancellable) { runnerStopped(request) } }
        } }, executionGate = gate)
        val mailbox = SessionMessageStore(db, scope, { object : SystemTurnDispatcher {
            override suspend fun request(request: SystemTurnRequest) = SystemTurnOutcome.Completed
        } }, store, messages, { "暂停" })
        val worktrees = BranchWorktreeService({ "/workspace" }) { owner, _, command ->
            commandOwners += owner.workspaceId
            when {
                "rev-parse" in command -> BranchWorktreeCommandOutput(0, head + "\n")
                "worktree list" in command -> BranchWorktreeCommandOutput(0,
                    "worktree /workspace\u0000HEAD $head\u0000branch refs/heads/main\u0000\u0000" +
                    if (added) "worktree /workspace/child\u0000HEAD $head\u0000branch refs/heads/feat/test\u0000\u0000" else "")
                "worktree add" in command -> {
                    addStarted?.complete(Unit)
                    if (addCancelled != null) try { awaitCancellation() } finally { addCancelled!!.complete(Unit) }
                    added = true; BranchWorktreeCommandOutput(0)
                }
                else -> BranchWorktreeCommandOutput(0)
            }
        }
        service = BranchSessionService(db, plans, questions, scope, coordinator, store, messages, ::load,
            { id -> val row = db.sessionDao().getById(id)!!; db.sessionDao().updateRow(row.copy(workspaceRootUuid = "root-$id", workspacePath = "/private/$id")) },
            worktrees, mailbox, gate, {}, { it })
        db.sessionDao().insert(SessionEntity("main", "a", executionMode = "workspace", createdAt = 1, updatedAt = 1))
        db.messageDao().insert(Message("u", MessageRole.USER, "工作", createdAt = 1).toEntity("main"))
        db.messageDao().insert(Message("ai", MessageRole.ASSISTANT, "", parentMessageId = "u", createdAt = 2).toEntity("main"))
        store.upsertSession(load("main")!!)
    }
    @After fun cleanup() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    private suspend fun load(id: String) = db.sessionDao().getById(id)?.toDomain()?.copy(messages = db.messageDao().getBySession(id).map { it.toDomain() })
    private fun mirror(id: String, message: Message) { store.updateSession(id) { it.copy(messages = (it.messages.filterNot { old -> old.id == message.id } + message).sortedBy { it.createdAt }) } }
    private suspend fun pending(worktree: Boolean = false): PendingBranchPlan {
        val plan = BranchPlan("分支", "验收", listOf("执行"), workMode = if (worktree) BranchWorkMode.WORKTREE else BranchWorkMode.SHARED,
            worktree = if (worktree) BranchWorktree("feat/test", "main", "child", "隔离修改") else null, approvalMode = "workspace")
        return plans.createBatch("main", "ai", listOf(ToolCall("dispatch", "session_branch", BranchPlanProtocol.encode(plan))), SessionBranchTool().toProtocolTool()).pending!!
    }
    private fun answer(pending: PendingBranchPlan) = BranchPlanAnswer(BranchDecision.DISPATCH, pending.plan.workMode, BranchContextMode.FRESH, "workspace")
    private suspend fun awaitCondition(block: () -> Boolean) = withTimeout(10_000) { while (!block()) delay(5) }
    private fun context() = object : SkillExecutionContext {
        override val sessionId = "main"; override val agentId = "a"
        override val workspacePath: String? = null; override val workspaceRootUuid = "root"
    }
    @Test fun `历史分页和字面检索覆盖归档消息与内部记录`() = runBlocking {
        val dao = db.messageDao()
        dao.insert(MessageEntity("archived", "main", "user", "前".repeat(155) + "😀A%_\\B" + "后".repeat(155), isArchived = 1, createdAt = 3))
        dao.insert(MessageEntity("internal", "main", "system", "A%_\\B", name = SessionMessageStore.WAKE_MESSAGE_NAME, createdAt = 4))
        dao.insert(MessageEntity("last", "main", "assistant", "a%_\\b", createdAt = 5))
        fun args(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
        suspend fun read(vararg pairs: Pair<String, JsonElement>) = Json.parseToJsonElement(service.address("session_read", "main", args(*pairs), context())).jsonObject
        val last = read("limit" to JsonPrimitive(2))
        assertThat(last["messages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }).containsExactly("archived", "last").inOrder()
        assertThat(last["has_more_before"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(last["has_more_after"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(last["messages"]!!.jsonArray.first().jsonObject["archived"]!!.jsonPrimitive.boolean).isTrue()
        val previous = read("before" to JsonPrimitive("archived"), "limit" to JsonPrimitive(2))
        assertThat(previous["messages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }).containsExactly("u", "ai").inOrder()
        assertThat(previous["has_more_before"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(previous["has_more_after"]!!.jsonPrimitive.boolean).isTrue()
        val next = read("after" to JsonPrimitive("ai"), "limit" to JsonPrimitive(1))
        assertThat(next["messages"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content).isEqualTo("archived")
        assertThat(next["has_more_before"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(next["has_more_after"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(runCatching { read("before" to JsonPrimitive("missing")) }.isFailure).isTrue()
        assertThat(runCatching { read("before" to JsonPrimitive("u"), "after" to JsonPrimitive("ai")) }.isFailure).isTrue()
        val search = Json.parseToJsonElement(service.address("session_search", "main", args("query" to JsonPrimitive("A%_\\B"), "limit" to JsonPrimitive(1)), context())).jsonObject
        assertThat(search["total_matches"]!!.jsonPrimitive.int).isEqualTo(2)
        assertThat(search["messages"]!!.jsonArray.single().jsonObject["message_id"]!!.jsonPrimitive.content).isEqualTo("last")
        val filtered = Json.parseToJsonElement(service.address("session_search", "main", args("query" to JsonPrimitive("A%_\\B"), "role" to JsonPrimitive("user")), context())).jsonObject
        val hit = filtered["messages"]!!.jsonArray.single().jsonObject
        assertThat(hit["archived"]!!.jsonPrimitive.boolean).isTrue()
        val snippet = hit["snippet"]!!.jsonPrimitive.content
        assertThat(snippet).startsWith("…")
        assertThat(snippet).endsWith("…")
        assertThat(snippet).contains("😀A%_\\B")
        assertThat(snippet).doesNotContain("�")
    }
    @Test fun `批准后父子独立并行停止父轮不取消分支`() = runBlocking {
        val pending = pending(); service.resolvePlan(pending, answer(pending))
        awaitCondition { entered.size == 2 }
        val child = db.sessionDao().getAll().single { it.parentSessionId == "main" }
        assertThat(entered).containsAtLeast("main", child.id)
        coordinator.cancelAndJoinSession("main", CancellationReason.USER)
        assertThat(coordinator.isSessionBusy(child.id)).isTrue()
    }
    @Test fun `会话名额满载时派发分支进入同一FIFO队列`() = runBlocking {
        val limit = com.promenar.infinity.domain.generation.MAX_CONCURRENT_GENERATIONS
        repeat(limit) { index -> coordinator.start(GenerationRequest("busy$index", "ai$index", null, "", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED)) }
        awaitCondition { entered.size == limit }
        val pending = pending(); service.resolvePlan(pending, answer(pending))
        val child = db.sessionDao().getAll().single { it.parentSessionId == "main" }
        awaitCondition { coordinator.observe(child.id).value?.phase == GenerationPhase.QUEUED }
        assertThat(coordinator.sessions.value.values.count { it.phase == GenerationPhase.STREAMING }).isEqualTo(limit)
        assertThat(entered).doesNotContain(child.id)
        coordinator.cancelAndJoinSession("busy0")
        awaitCondition { child.id in entered || "main" in entered }
    }
    @Test fun `批准工作区冻结父切换后仍在原工作区执行且成功绑定`() = runBlocking {
        db.workspaceDao().insert(WorkspaceEntity("w1", "project", "一", "one", 1))
        db.workspaceDao().insert(WorkspaceEntity("w2", "project", "二", "two", 1))
        db.sessionDao().updateRow(db.sessionDao().getById("main")!!.copy(workspaceId = "w1"))
        val pending = pending(true)
        val childId = plans.resolve(pending, answer(pending))!!.branchSessionId!!
        store.upsertSession(load(childId)!!)
        db.sessionDao().updateRow(db.sessionDao().getById("main")!!.copy(workspaceId = "w2"))
        service.prepareWorkspace(childId)
        assertThat(commandOwners).isNotEmpty()
        assertThat(commandOwners.toSet()).containsExactly("w1")
        assertThat(db.sessionDao().getById(childId)!!.worktreeDirectory).isEqualTo("/workspace/child")
        assertThat(db.sessionDao().getById(childId)!!.worktreeBase).isEqualTo(head)
    }
    @Test fun `工作区准备属于生成任务硬停止会取消命令且不开始模型`() = runBlocking {
        db.workspaceDao().insert(WorkspaceEntity("w1", "project", "一", "one", 1))
        db.sessionDao().updateRow(db.sessionDao().getById("main")!!.copy(workspaceId = "w1"))
        addStarted = CompletableDeferred(); addCancelled = CompletableDeferred()
        runnerPrepare = { request -> if (request.sessionId.startsWith("branch_")) service.prepareWorkspace(request.sessionId) }
        val pending = pending(true); service.resolvePlan(pending, answer(pending))
        withTimeout(10_000) { addStarted!!.await() }
        val child = db.sessionDao().getAll().single { it.parentSessionId == "main" }
        withTimeout(5_000) { service.address("session_stop", child.id, buildJsonObject { put("id", child.id) }, context()) }
        withTimeout(10_000) { addCancelled!!.await() }
        assertThat(entered).doesNotContain(child.id)
        assertThat(db.sessionDao().getById(child.id)!!.worktreeDirectory).isNull()
        assertThat(coordinator.isSessionBusy(child.id)).isFalse()
    }
    @Test fun `同助手读取须精确批准且界面重验目标与执行目标相同`() = runBlocking {
        db.sessionDao().insert(SessionEntity("other", "a", createdAt = 1, updatedAt = 1))
        val call = ToolCall("read", "session_read", """{"id":"other"}""")
        val tool = com.promenar.infinity.data.remote.protocol.ProtocolTool(
            function = com.promenar.infinity.data.remote.protocol.ProtocolToolFunction("session_read", "读取会话", """{"type":"object","properties":{"id":{"type":"string"}}}"""),
            runtimeToolId = "session_read")
        val source = load("main")!!.copy(messages = listOf(Message("read-ai", MessageRole.ASSISTANT, "", toolCalls = listOf(call))))
        val target = service.approvalTarget(source, call.name, call.arguments)!!
        assertThat(service.approvalTarget(source, "session_search", """{"id":"other","query":"x"}""")).isEqualTo(target)
        assertThat(runCatching { service.address("session_search", "other", buildJsonObject { put("query", "x") }, context()) }.isFailure).isTrue()
        val identity = (ToolInvocationIdentityFactory.fromPreparedToolCall(call, tool, true)
            as ToolInvocationIdentityResolution.Valid).identity
        val request = ToolApprovalRequestFactory.create("read-ai", listOf(ApprovalCallCandidate(call, com.promenar.infinity.domain.tool.ToolRisk.UNKNOWN, identity, executionTarget = target)))
        val revalidated = com.promenar.infinity.ui.chat.resolveChatApprovalExecutionTarget(source, "read-ai", request.calls.single(),
            { tool }, null, { _, _ -> null }, sessionCommunicationTarget = service::approvalTarget)
        assertThat(revalidated).isEqualTo(target)
        assertThat(runCatching { service.address(call.name, "other", Json.parseToJsonElement(call.arguments).jsonObject, context()) }.isFailure).isTrue()
        val approved = object : SkillExecutionContext by context() {
            override val isApprovalBoundExecution = true
            override val approvedExecutionTarget = target
        }
        assertThat(service.address(call.name, "other", Json.parseToJsonElement(call.arguments).jsonObject, approved)).contains("other")
        assertThat(runCatching { service.address("session_send", "other", buildJsonObject { put("message", "越权") }, approved) }.isFailure).isTrue()
    }
    @Test fun `硬停止分支待审批轮后旧批准不能执行`() = runBlocking {
        val pending = pending()
        val childId = plans.resolve(pending, answer(pending))!!.branchSessionId!!
        val call = ToolCall("write", "write_file", "{}")
        val tool = com.promenar.infinity.data.remote.protocol.ProtocolTool(
            function = com.promenar.infinity.data.remote.protocol.ProtocolToolFunction(call.name, "写入", """{"type":"object"}"""), runtimeToolId = call.name)
        val identity = (ToolInvocationIdentityFactory.fromPreparedToolCall(call, tool, true) as ToolInvocationIdentityResolution.Valid).identity
        val request = ToolApprovalRequestFactory.create("approval-ai", listOf(ApprovalCallCandidate(call, com.promenar.infinity.domain.tool.ToolRisk.UNKNOWN, identity)))
        db.messageDao().insert(Message("approval-ai", MessageRole.ASSISTANT, "", toolCalls = listOf(call)).toEntity(childId))
        val ledger = ToolExecutionLedgerRepository(db)
        assertThat(ledger.createToolApproval(childId, "approval-ai", listOf(call), setOf(call.id), request)).isEqualTo(ToolApprovalCreation.CREATED)
        store.upsertSession(load(childId)!!)
        withTimeout(5_000) { service.address("session_stop", childId, buildJsonObject { put("id", childId) }, context()) }
        assertThat(ledger.state(ToolExecutionKey(childId, "approval-ai", call.id))).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(ledger.decideExactToolApproval(childId, request, true)).isEqualTo(ExactToolApprovalDecision.Conflict)
        assertThat(db.sessionDao().getById(childId)!!.loopStatus).isEqualTo("paused")
    }
    @Test fun `硬停止清理与同会话下一轮准入串行`() = runBlocking {
        val pending = pending(); service.resolvePlan(pending, answer(pending))
        awaitCondition { entered.size == 2 }
        val childId = db.sessionDao().getAll().single { it.parentSessionId == "main" }.id
        val cancelling = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        runnerStopped = { request -> if (request.sessionId == childId) { cancelling.complete(Unit); finish.await() } }
        val stopping = async(Dispatchers.Default) { service.address("session_stop", childId, buildJsonObject { put("id", childId) }, context()) }
        withTimeout(5_000) { cancelling.await() }
        val next = async(Dispatchers.Default) {
            store.turnAdmission.withLock(childId) {
                db.sessionDao().updateToolApprovalState(childId, null, "running", 10)
            }
        }
        delay(50)
        assertThat(next.isCompleted).isFalse()
        finish.complete(Unit)
        withTimeout(5_000) { stopping.await(); next.await() }
        assertThat(db.sessionDao().getById(childId)!!.loopStatus).isEqualTo("running")
    }
}
