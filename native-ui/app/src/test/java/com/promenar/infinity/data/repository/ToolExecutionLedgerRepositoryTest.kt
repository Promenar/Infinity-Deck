package com.promenar.infinity.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.ApprovalRequest
import com.promenar.infinity.data.model.LoopStatus
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.model.toDomain
import com.promenar.infinity.data.model.toEntity
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import com.promenar.infinity.domain.media.MediaViewMode
import com.promenar.infinity.domain.media.ViewedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ToolExecutionLedgerRepositoryTest {
    private lateinit var database: InfinityDatabase
    private lateinit var repository: ToolExecutionLedgerRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ToolExecutionLedgerRepository(database) { now++ }
        runBlocking {
            database.sessionDao().insert(
                SessionEntity(
                    id = "s1",
                    agentId = "a1",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            listOf("m1", "m2", "m3").forEachIndexed { index, id ->
                database.messageDao().insert(
                    Message(
                        id = id,
                        role = MessageRole.ASSISTANT,
                        content = "assistant",
                        createdAt = now + index,
                    ).toEntity("s1"),
                )
            }
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `用户批准工具清零自动唤醒计数并解除自动处理暂停`() = runBlocking {
        verifyUserDecisionResetsAutoProcessing(approved = true)
    }

    @Test
    fun `用户拒绝工具清零自动唤醒计数并解除自动处理暂停`() = runBlocking {
        verifyUserDecisionResetsAutoProcessing(approved = false)
    }

    @Test
    fun `审批恢复保留自动处理暂停且拒绝结果写入失败回滚清零`() = runBlocking {
        val call = ToolCall("reject-failed", "write_file", "{}")
        val request = exactRequest("m1", listOf(call), setOf(call.id))
        repository.createToolApproval("s1", "m1", listOf(call), setOf(call.id), request)
        val latest = database.sessionDao().getById("s1")!!
        database.sessionDao().updateRow(latest.copy(sessionAutoWakeCount = 8, sessionAutoProcessingPaused = true))
        repository.recoverApprovalState()
        assertThat(database.sessionDao().getById("s1")!!.sessionAutoWakeCount).isEqualTo(8)
        assertThat(database.sessionDao().getById("s1")!!.sessionAutoProcessingPaused).isTrue()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_user_rejection BEFORE INSERT ON messages WHEN NEW.role = 'tool' BEGIN SELECT RAISE(ABORT, 'test write failure'); END")

        assertThat(runCatching { repository.decideExactToolApproval("s1", request, approved = false) }.isFailure).isTrue()

        assertThat(repository.state(key(call.id))).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
        val unchanged = database.sessionDao().getById("s1")!!
        assertThat(unchanged.sessionAutoWakeCount).isEqualTo(8)
        assertThat(unchanged.sessionAutoProcessingPaused).isTrue()
        assertThat(unchanged.approvalRequest).isEqualTo(latest.approvalRequest)
    }

    private suspend fun verifyUserDecisionResetsAutoProcessing(approved: Boolean) {
        val call = ToolCall("user-decision", "write_file", "{}")
        val request = exactRequest("m1", listOf(call), setOf(call.id))
        assertThat(repository.createToolApproval("s1", "m1", listOf(call), setOf(call.id), request))
            .isEqualTo(ToolApprovalCreation.CREATED)
        val latest = database.sessionDao().getById("s1")!!
        database.sessionDao().updateRow(latest.copy(sessionAutoWakeCount = 8, sessionAutoProcessingPaused = true))

        assertThat(repository.decideExactToolApproval("s1", request.copy(assistantMessageId = "stale"), approved))
            .isEqualTo(ExactToolApprovalDecision.Conflict)
        assertThat(database.sessionDao().getById("s1")!!.sessionAutoWakeCount).isEqualTo(8)
        assertThat(database.sessionDao().getById("s1")!!.sessionAutoProcessingPaused).isTrue()
        assertThat(repository.decideExactToolApproval("s1", request, approved))
            .isInstanceOf(ExactToolApprovalDecision.Decided::class.java)

        val decided = database.sessionDao().getById("s1")!!
        assertThat(decided.sessionAutoWakeCount).isEqualTo(0)
        assertThat(decided.sessionAutoProcessingPaused).isFalse()
        assertThat(decided.approvalRequest).isEqualTo(latest.approvalRequest)
        assertThat(decided.loopStatus).isEqualTo(latest.loopStatus)
    }

    @Test
    fun `register 原子核对完整 identity 与审批策略`() = runBlocking {
        val safeKey = key("safe")
        val riskyKey = key("risky")
        val safeIdentity = legacyIdentity(ToolCall("safe", "read_file", "{\"path\":\"a\"}"), false)
        val riskyIdentity = legacyIdentity(ToolCall("risky", "write_file", "{}"), true)

        assertThat(repository.register(safeKey, safeIdentity))
            .isEqualTo(ToolRegistrationResult.Registered(ToolLedgerState.APPROVED))
        assertThat(repository.register(riskyKey, riskyIdentity))
            .isEqualTo(ToolRegistrationResult.Registered(ToolLedgerState.PENDING_APPROVAL))
        assertThat(repository.register(safeKey, safeIdentity))
            .isEqualTo(ToolRegistrationResult.Existing(ToolLedgerState.APPROVED))

        assertThat(repository.register(safeKey, safeIdentity.copy(runtimeToolId = "different")))
            .isEqualTo(ToolRegistrationResult.Conflict)
        assertThat(repository.register(safeKey, safeIdentity.copy(toolName = "different")))
            .isEqualTo(ToolRegistrationResult.Conflict)
        assertThat(repository.register(safeKey, safeIdentity.copy(argumentsDigest = "different")))
            .isEqualTo(ToolRegistrationResult.Conflict)
        assertThat(repository.register(safeKey, safeIdentity.copy(definitionDigest = "different")))
            .isEqualTo(ToolRegistrationResult.Conflict)
        assertThat(repository.register(safeKey, safeIdentity.copy(requiresApproval = true)))
            .isEqualTo(ToolRegistrationResult.Conflict)
        assertThat(repository.state(safeKey)).isEqualTo(ToolLedgerState.APPROVED)
        assertThat(repository.state(riskyKey)).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
    }

    @Test
    fun identityConflictCASComparesAllFieldsAndTerminalizesAwaitingOnlyOnce() = runBlocking {
        val pendingCall = ToolCall("pending-cas", "write_file", """{"path":"safe.txt"}""")
        val pendingIdentity = preparedIdentity(pendingCall, requiresApproval = true)
        val pendingKey = key(pendingCall.id)
        repository.register(pendingKey, pendingIdentity)

        listOf(
            pendingIdentity.copy(runtimeToolId = "different-runtime"),
            pendingIdentity.copy(toolName = "different-name"),
            pendingIdentity.copy(argumentsDigest = "different-arguments"),
            pendingIdentity.copy(definitionDigest = "different-definition"),
            pendingIdentity.copy(requiresApproval = false),
        ).forEach { wrongIdentity ->
            assertThat(repository.failAwaitingIdentityConflict(pendingKey, wrongIdentity, null)).isNull()
            assertThat(repository.state(pendingKey)).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
        }

        val pendingTerminal = repository.failAwaitingIdentityConflict(
            pendingKey,
            pendingIdentity,
            "thought-signature",
        )
        assertThat(pendingTerminal!!.content)
            .isEqualTo("工具调用与已登记身份不一致，已安全终止。")
        assertThat(pendingTerminal.thoughtSignature).isEqualTo("thought-signature")
        assertThat(repository.state(pendingKey)).isEqualTo(ToolLedgerState.FAILED)
        assertThat(repository.failAwaitingIdentityConflict(pendingKey, pendingIdentity, null)).isNull()

        val approvedCall = ToolCall("approved-cas", "delete_file", "{}")
        val approvedIdentity = preparedIdentity(approvedCall, requiresApproval = true)
        val approvedKey = key(approvedCall.id)
        repository.register(approvedKey, approvedIdentity)
        repository.approve(setOf(approvedKey))
        assertThat(repository.failAwaitingIdentityConflict(approvedKey, approvedIdentity, null))
            .isNotNull()
        assertThat(repository.state(approvedKey)).isEqualTo(ToolLedgerState.FAILED)

        assertThat(database.messageDao().getBySession("s1").filter { it.role == "tool" }
            .mapNotNull { it.toolCallId })
            .containsExactly(pendingCall.id, approvedCall.id)
        Unit
    }

    @Test
    fun `历史空 digest 账本无法证明 identity 时注册失败关闭`() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO tool_execution_ledger(
               session_id,assistant_message_id,tool_call_id,tool_name,runtime_tool_id,
               arguments_digest,definition_digest,requires_approval,status,created_at,updated_at
               ) VALUES('s1','m1','legacy','write_file','write_file','','',1,'PENDING_APPROVAL',1,1)""",
        )

        val result = repository.register(
            key("legacy"),
            legacyIdentity(ToolCall("legacy", "write_file", "{}"), true),
        )

        assertThat(result).isEqualTo(ToolRegistrationResult.Conflict)
    }

    @Test
    fun `legacy identity 对合法参数生成稳定非空 definition 与 canonical arguments digest`() {
        val first = legacyIdentity(ToolCall("call", "write_file", "{\"b\":2,\"a\":1}"), true)
        val reordered = legacyIdentity(ToolCall("call", "write_file", "{\"a\":1,\"b\":2}"), true)

        assertThat(first.argumentsDigest)
            .isEqualTo("43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777")
        assertThat(first.argumentsDigest).isEqualTo(reordered.argumentsDigest)
        assertThat(first.definitionDigest).isNotEmpty()
        assertThat(first.definitionDigest).isEqualTo(reordered.definitionDigest)
        assertThat(first.runtimeToolId).isEqualTo("write_file")
    }

    @Test
    fun `createToolApproval 拒绝畸形 JSON 且不留下部分账本`() = runBlocking {
        val call = ToolCall("broken", "write_file", "{\"path\":")

        assertThat(repository.createToolApproval(
            "s1",
            "m1",
            listOf(call),
            setOf(call.id),
            ApprovalRequest(type = "tool_approval"),
        )).isEqualTo(ToolApprovalCreation.CONFLICT)
        assertThat(database.toolExecutionLedgerDao().getForAssistant("s1", "m1")).isEmpty()
    }

    @Test
    fun `createToolApproval 对历史空 digest 冲突失败关闭`() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO tool_execution_ledger(
               session_id,assistant_message_id,tool_call_id,tool_name,runtime_tool_id,
               arguments_digest,definition_digest,requires_approval,status,created_at,updated_at
               ) VALUES('s1','m1','legacy','write_file','write_file','','',1,'PENDING_APPROVAL',1,1)""",
        )
        val call = ToolCall("legacy", "write_file", "{}")

        assertThat(repository.createToolApproval(
            "s1",
            "m1",
            listOf(call),
            setOf(call.id),
            ApprovalRequest(type = "tool_approval"),
        )).isEqualTo(ToolApprovalCreation.CONFLICT)
    }

    @Test
    fun `createToolApproval 拒绝仅按工具名称重算的 legacy pending identity`() = runBlocking {
        val call = ToolCall("legacy-pending", "write_file", "{}")
        val legacy = legacyIdentity(call, requiresApproval = true)
        val request = ToolApprovalRequestFactory.createFromIdentities(
            assistantMessageId = "m1",
            calls = listOf(
                com.promenar.infinity.data.model.ApprovalCallIdentity(
                    toolCallId = call.id,
                    runtimeToolId = legacy.runtimeToolId,
                    toolName = legacy.toolName,
                    argumentsDigest = legacy.argumentsDigest,
                    definitionDigest = legacy.definitionDigest,
                    requiresApproval = true,
                    argumentsSummary = "{}",
                    risk = "unknown",
                ),
            ),
        )

        assertThat(
            repository.createToolApproval("s1", "m1", listOf(call), setOf(call.id), request),
        ).isEqualTo(ToolApprovalCreation.CONFLICT)
        assertThat(repository.state(key(call.id))).isNull()
    }

    @Test
    fun approveClaimAndFinishOnlyAllowLegalTransitions() = runBlocking {
        val key = key("risky")
        repository.register(key, "write_file", requiresApproval = true)

        assertThat(repository.claim(key)).isFalse()
        assertThat(repository.approve(setOf(key))).isEqualTo(1)
        assertThat(repository.claim(key)).isTrue()
        assertThat(repository.claim(key)).isFalse()
        assertThat(repository.finish(key, ToolExecutionOutcome.Succeeded)).isTrue()
        assertThat(repository.finish(key, ToolExecutionOutcome.Failed("late failure"))).isFalse()
        assertThat(repository.reject(setOf(key))).isEqualTo(0)
        assertThat(repository.state(key)).isEqualTo(ToolLedgerState.SUCCEEDED)
    }

    @Test
    fun claimComparesCompleteExpectedInvocationIdentity() = runBlocking {
        val call = ToolCall("identity-claim", "read_file", """{"uuid":"one"}""")
        val expected = legacyIdentity(call, requiresApproval = false)
        val key = key(call.id)
        repository.register(key, expected)

        assertThat(repository.claim(
            key,
            expected.copy(definitionDigest = "f".repeat(64)),
        )).isFalse()
        assertThat(repository.claim(key, expected)).isTrue()
        assertThat(repository.claim(key, expected)).isFalse()
    }

    @Test
    fun concurrentClaimHasExactlyOneWinner() = runBlocking {
        val key = key("concurrent")
        repository.register(key, "write_file", requiresApproval = false)

        val winners = (1..16).map {
            async(Dispatchers.IO) { repository.claim(key) }
        }.awaitAll().count { it }

        assertThat(winners).isEqualTo(1)
        assertThat(repository.state(key)).isEqualTo(ToolLedgerState.RUNNING)
    }

    @Test
    fun terminalDecisionsAreFailClosedAndScopedByCompositeKey() = runBlocking {
        val rejected = key("same", messageId = "m1")
        val cancelled = key("same", messageId = "m2")
        val timedOut = key("same", messageId = "m3")
        listOf(rejected, cancelled, timedOut).forEach {
            repository.register(it, "write_file", requiresApproval = true)
        }

        assertThat(repository.reject(setOf(rejected))).isEqualTo(1)
        assertThat(repository.cancel(setOf(cancelled))).isEqualTo(1)
        assertThat(repository.timeout(setOf(timedOut))).isEqualTo(1)
        assertThat(repository.approve(setOf(rejected, cancelled, timedOut))).isEqualTo(0)
        assertThat(repository.state(rejected)).isEqualTo(ToolLedgerState.REJECTED)
        assertThat(repository.state(cancelled)).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(repository.state(timedOut)).isEqualTo(ToolLedgerState.TIMED_OUT)
    }

    @Test
    fun cancellationTerminalizesRunningEntryAndPersistsExactlyOneResult() = runBlocking {
        val running = key("running-cancel")
        repository.register(running, "write_file", requiresApproval = false)
        assertThat(repository.claim(running)).isTrue()
        val request = ToolTerminalRequest(running, "write_file", "sig")

        val messages = repository.terminalizeWithResults(
            setOf(request),
            ToolLedgerState.CANCELLED,
        )

        assertThat(repository.state(running)).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(messages).hasSize(1)
        assertThat(messages.single().content).isEqualTo("工具执行已取消。")
        assertThat(repository.terminalizeWithResults(
            setOf(request),
            ToolLedgerState.CANCELLED,
        )).isEmpty()
        assertThat(database.messageDao().getBySession("s1").filter { it.role == "tool" })
            .hasSize(1)
    }

    @Test fun `插队取消待审批调用时结果保留原因且不会重复落盘`() = runBlocking {
        val pending = key("steering-pending")
        repository.register(pending, "write_file", requiresApproval = true)
        val reason = "用户插队打断了本步，调用未执行。"
        val request = ToolTerminalRequest(pending, "write_file", interruptionReason = reason)
        val messages = repository.terminalizeWithResults(setOf(request), ToolLedgerState.CANCELLED)
        assertThat(messages.single().content).isEqualTo(reason)
        assertThat(database.messageDao().getById(messages.single().id)!!.content).isEqualTo(reason)
        assertThat(repository.terminalizeWithResults(setOf(request), ToolLedgerState.CANCELLED)).isEmpty()
    }

    @Test
    fun interruptedRunningEntriesBecomeFailedWithoutReplayingSideEffects() = runBlocking {
        val running = key("running")
        val approved = key("approved")
        repository.register(running, "write_file", requiresApproval = false)
        repository.register(approved, "read_file", requiresApproval = false)
        repository.claim(running)

        assertThat(repository.recoverInterruptedRunning("进程在工具执行期间中断")).isEqualTo(1)

        assertThat(repository.state(running)).isEqualTo(ToolLedgerState.FAILED)
        assertThat(repository.state(approved)).isEqualTo(ToolLedgerState.APPROVED)
        assertThat(repository.claim(running)).isFalse()
        val recoveryMessages = database.messageDao().getBySession("s1")
            .filter { it.role == "tool" }
        assertThat(recoveryMessages).hasSize(1)
        assertThat(recoveryMessages.single().toolCallId).isEqualTo("running")
        assertThat(recoveryMessages.single().parentMessageId).isEqualTo("m1")
        assertThat(recoveryMessages.single().content).contains("中断")

        assertThat(repository.recoverInterruptedRunning("再次恢复")).isEqualTo(0)
        val repeatedRecoveryMessages = database.messageDao().getBySession("s1")
            .filter { it.role == "tool" }
        assertThat(repeatedRecoveryMessages).hasSize(1)
        assertThat(repeatedRecoveryMessages.single().toolCallId).isEqualTo("running")
        assertThat(repeatedRecoveryMessages.single().parentMessageId).isEqualTo("m1")
    }

    @Test
    fun `ledger recovery 显式传播取消且事务不伪造 FAILED`() = runBlocking {
        val running = key("cancel-recovery")
        repository.register(running, "write_file", requiresApproval = false)
        assertThat(repository.claim(running)).isTrue()
        val cancellation = CancellationException("cancel recovery")
        val cancellingRepository = ToolExecutionLedgerRepository(database) { throw cancellation }

        val thrown = try {
            cancellingRepository.recoverInterruptedRunning("should not be persisted")
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(thrown).hasMessageThat().isEqualTo(cancellation.message)
        assertThat(repository.state(running)).isEqualTo(ToolLedgerState.RUNNING)
    }

    @Test
    fun finishPersistsLedgerAndDeterministicToolMessageInOneIdempotentTransaction() = runBlocking {
        val key = key("atomic")
        repository.register(key, "write_file", requiresApproval = false)
        repository.claim(key)

        val message = repository.finishWithResult(
            key = key,
            toolName = "write_file",
            content = "写入完成",
            thoughtSignature = "sig",
            outcome = ToolExecutionOutcome.Succeeded,
        )

        assertThat(message).isNotNull()
        assertThat(message!!.role).isEqualTo(MessageRole.TOOL)
        assertThat(repository.state(key)).isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(database.toolExecutionLedgerDao().get("s1", "m1", "atomic")?.resultMessageId)
            .isEqualTo(message.id)
        assertThat(database.messageDao().getById(message.id)?.content).isEqualTo("写入完成")
        assertThat(repository.finishWithResult(
            key,
            "write_file",
            "重复完成",
            "sig",
            ToolExecutionOutcome.Succeeded,
        )).isNull()
        val toolMessages = database.messageDao().getBySession("s1")
            .filter { it.role == "tool" }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages.single().toolCallId).isEqualTo("atomic")
        assertThat(toolMessages.single().parentMessageId).isEqualTo("m1")
    }

    @Test
    fun `finishWithResult 将查看描述实际写入最终 TOOL 消息且重复完成不覆盖`() = runBlocking {
        val key = key("view-media")
        repository.register(key, "view_media", requiresApproval = false)
        repository.claim(key)
        val viewed = listOf(
            ViewedMedia(
                ref = MediaRef(
                    path = "/media/片段.mp4",
                    mimeType = "video/mp4",
                    kind = MediaKind.VIDEO,
                    sizeBytes = 42L,
                    sha256 = "a".repeat(64),
                    durationMs = 5_000L,
                ),
                mode = MediaViewMode.KEYFRAMES,
                frameTimestampsSeconds = listOf(1.25, 3.75),
            ),
        )

        val message = repository.finishWithResult(
            key = key,
            toolName = "view_media",
            content = "以下为关键帧，不含声音",
            thoughtSignature = "view-signature",
            outcome = ToolExecutionOutcome.Succeeded,
            images = "legacy-image-payload",
            viewedMedia = viewed,
        )!!

        val stored = database.messageDao().getById(message.id)!!
        assertThat(stored.toDomain().viewedMedia).containsExactlyElementsIn(viewed)
        assertThat(stored.toDomain()).isEqualTo(message)
        assertThat(stored.images).isEqualTo("legacy-image-payload")
        assertThat(stored.thoughtSignature).isEqualTo("view-signature")
        assertThat(repository.state(key)).isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(repository.finishWithResult(
            key = key,
            toolName = "view_media",
            content = "重复结果",
            thoughtSignature = null,
            outcome = ToolExecutionOutcome.Succeeded,
            viewedMedia = emptyList(),
        )).isNull()
        assertThat(database.messageDao().getById(message.id)!!.toDomain().viewedMedia)
            .containsExactlyElementsIn(viewed)
        Unit
    }

    @Test
    fun rejectionPersistsEveryTerminalToolMessageWithoutDuplicates() = runBlocking {
        val first = key("reject-1")
        val second = key("reject-2")
        listOf(first, second).forEach { repository.register(it, "write_file", true) }
        val requests = setOf(
            ToolTerminalRequest(first, "write_file", "sig"),
            ToolTerminalRequest(second, "write_file", "sig"),
        )

        val messages = repository.terminalizeWithResults(requests, ToolLedgerState.REJECTED)

        assertThat(messages.mapNotNull { it.toolCallId }).containsExactly("reject-1", "reject-2")
        messages.forEach { message ->
            assertThat(database.toolExecutionLedgerDao().get(
                "s1",
                "m1",
                message.toolCallId!!,
            )?.resultMessageId).isEqualTo(message.id)
        }
        val toolMessages = database.messageDao().getBySession("s1")
            .filter { it.role == "tool" }
        assertThat(toolMessages.mapNotNull { it.toolCallId })
            .containsExactly("reject-1", "reject-2")
        assertThat(toolMessages.all { it.parentMessageId == "m1" }).isTrue()
        assertThat(repository.terminalizeWithResults(requests, ToolLedgerState.REJECTED)).isEmpty()
        val repeatedToolMessages = database.messageDao().getBySession("s1")
            .filter { it.role == "tool" }
        assertThat(repeatedToolMessages.mapNotNull { it.toolCallId })
            .containsExactly("reject-1", "reject-2")
        assertThat(repeatedToolMessages.all { it.parentMessageId == "m1" }).isTrue()
    }

    @Test
    fun sessionAndAssistantDeletionCascadeLedgerRows() = runBlocking {
        val messageScoped = key("message-cascade", messageId = "m2")
        val sessionScoped = key("session-cascade", messageId = "m3")
        repository.register(messageScoped, "write_file", false)
        repository.register(sessionScoped, "write_file", false)
        repository.claim(messageScoped)
        val child = repository.finishWithResult(
            messageScoped,
            "write_file",
            "done",
            null,
            ToolExecutionOutcome.Succeeded,
        )!!
        assertThat(database.messageDao().getById(child.id)?.parentMessageId).isEqualTo("m2")

        database.messageDao().deleteById("m2")
        assertThat(database.toolExecutionLedgerDao().get("s1", "m2", "message-cascade")).isNull()
        assertThat(database.messageDao().getById(child.id)).isNull()
        assertThat(database.messageDao().getById("m3")).isNotNull()

        database.sessionDao().deleteById("s1")
        assertThat(database.toolExecutionLedgerDao().get("s1", "m3", "session-cascade")).isNull()
    }

    @Test
    fun crossSessionAssistantReferenceIsRejectedByCompositeForeignKey() = runBlocking {
        database.sessionDao().insert(
            SessionEntity(id = "s2", agentId = "a1", createdAt = now, updatedAt = now++),
        )
        database.messageDao().insert(
            Message(id = "other-message", role = MessageRole.ASSISTANT, content = "other").toEntity("s2"),
        )

        val failure = runCatching {
            database.openHelper.writableDatabase.execSQL(
                """INSERT INTO tool_execution_ledger(
                   session_id, assistant_message_id, tool_call_id, tool_name, requires_approval, status, created_at, updated_at
                   ) VALUES('s1', 'other-message', 'cross-session', 'write_file', 0, 'APPROVED', 1, 1)""",
            )
        }.exceptionOrNull()

        assertThat(failure).isNotNull()

        val parentFailure = runCatching {
            database.messageDao().insert(
                Message(
                    id = "cross-session-tool",
                    role = MessageRole.TOOL,
                    content = "invalid",
                    parentMessageId = "other-message",
                ).toEntity("s1"),
            )
        }.exceptionOrNull()
        assertThat(parentFailure).isNotNull()
    }

    @Test
    fun orphanRowIsCleanedWithoutBlockingValidRunningRecovery() = runBlocking {
        val valid = key("valid-running")
        repository.register(valid, "write_file", false)
        repository.claim(valid)
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("PRAGMA foreign_keys=OFF")
        sqlite.execSQL(
            """INSERT INTO tool_execution_ledger(
               session_id, assistant_message_id, tool_call_id, tool_name, requires_approval, status, created_at, updated_at
               ) VALUES('orphan-session', 'orphan-message', 'orphan-running', 'write_file', 0, 'RUNNING', 1, 1)""",
        )
        sqlite.execSQL("PRAGMA foreign_keys=ON")

        assertThat(repository.recoverInterruptedRunning("进程中断")).isEqualTo(1)

        assertThat(repository.state(valid)).isEqualTo(ToolLedgerState.FAILED)
        assertThat(database.toolExecutionLedgerDao().get(
            "orphan-session",
            "orphan-message",
            "orphan-running",
        )).isNull()
        assertThat(database.messageDao().getBySession("s1").count { it.toolCallId == "valid-running" })
            .isEqualTo(1)
    }

    @Test
    fun unknownRawDatabaseStateIsRejectedInsteadOfSilentlyMapped() = runBlocking {
        val key = key("unknown-state")
        repository.register(key, "write_file", false)
        database.openHelper.writableDatabase.execSQL(
            """UPDATE tool_execution_ledger SET status = 'UNKNOWN_STATE'
               WHERE session_id = 's1' AND assistant_message_id = 'm1'
                 AND tool_call_id = 'unknown-state'""",
        )

        val failure = runCatching {
            database.toolExecutionLedgerDao().get("s1", "m1", "unknown-state")
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
    }

    @Test
    fun createAndCompleteApprovalUpdateAllRoomStateInTransactions() = runBlocking {
        val calls = listOf(
            com.promenar.infinity.data.model.ToolCall("safe", "read_file", "{}"),
            com.promenar.infinity.data.model.ToolCall("risky", "write_file", "{\"path\":\"a\"}"),
        )
        val request = exactRequest("m1", calls, setOf("risky"))
        repository.register(key("safe"), preparedIdentity(calls[0], requiresApproval = false))

        repository.createToolApproval("s1", "m1", calls, setOf("risky"), request)

        assertThat(repository.state(key("safe"))).isEqualTo(ToolLedgerState.APPROVED)
        assertThat(repository.state(key("risky"))).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
        assertThat(database.toolExecutionLedgerDao().get("s1", "m1", "safe")!!.requiresApproval)
            .isFalse()
        assertThat(database.toolExecutionLedgerDao().get("s1", "m1", "risky")!!.requiresApproval)
            .isTrue()
        assertThat(database.messageDao().getById("m1")!!.pendingApprovalToolIds).contains("risky")
        val session = database.sessionDao().getById("s1")!!
        assertThat(session.approvalRequest).isNotNull()
        assertThat(session.loopStatus).isEqualTo("waiting_for_approval")

        assertThat(repository.approve(setOf(key("risky")))).isEqualTo(1)
        assertThat(repository.claim(key("risky"))).isTrue()
        assertThat(repository.finish(key("risky"), ToolExecutionOutcome.Succeeded)).isTrue()
        repository.completeToolApproval("s1", "m1")

        assertThat(database.messageDao().getById("m1")!!.pendingApprovalToolIds).isNull()
        assertThat(database.sessionDao().getById("s1")!!.approvalRequest).isNull()
        assertThat(database.sessionDao().getById("s1")!!.loopStatus).isEqualTo("running")
    }

    @Test
    fun exactApprovalDecisionAdvancesOneOrderedCallAndRejectsDuplicateOrStaleIdentity() = runBlocking {
        val first = ToolCall("first-risk", "write_file", "{\"path\":\"a\"}")
        val second = ToolCall("second-risk", "delete_file", "{\"path\":\"b\"}")
        val request = ToolApprovalRequestFactory.create(
            assistantMessageId = "m1",
            candidates = listOf(
                ApprovalCallCandidate(first, com.promenar.infinity.domain.tool.ToolRisk.FILE_WRITE),
                ApprovalCallCandidate(second, com.promenar.infinity.domain.tool.ToolRisk.DELETE),
            ),
            reason = "manual",
        )

        assertThat(
            repository.createToolApproval("s1", "m1", listOf(first, second), setOf(first.id, second.id), request),
        ).isEqualTo(ToolApprovalCreation.CREATED)

        val firstDecision = repository.decideExactToolApproval("s1", request, approved = true)
        val duplicate = repository.decideExactToolApproval("s1", request, approved = true)
        val staleMessage = repository.decideExactToolApproval(
            "s1",
            request.copy(assistantMessageId = "old-message"),
            approved = true,
        )

        assertThat(firstDecision).isInstanceOf(ExactToolApprovalDecision.Decided::class.java)
        assertThat((firstDecision as ExactToolApprovalDecision.Decided).key.toolCallId)
            .isEqualTo(first.id)
        assertThat(duplicate).isEqualTo(ExactToolApprovalDecision.Conflict)
        assertThat(staleMessage).isEqualTo(ExactToolApprovalDecision.Conflict)

        assertThat(repository.completeExactToolApproval("s1", request))
            .isEqualTo(ExactToolApprovalCompletion.Conflict)
        assertThat(repository.claim(key(first.id))).isTrue()
        assertThat(repository.finish(key(first.id), ToolExecutionOutcome.Succeeded)).isTrue()

        val completion = repository.completeExactToolApproval("s1", request)
        assertThat(completion).isInstanceOf(ExactToolApprovalCompletion.Completed::class.java)
        val transition = (completion as ExactToolApprovalCompletion.Completed).transition
        assertThat(transition.loopStatus).isEqualTo(LoopStatus.WAITING_FOR_APPROVAL)
        assertThat(transition.approvalRequest!!.assistantMessageId).isEqualTo("m1")
        assertThat(transition.approvalRequest.calls.map { it.toolCallId })
            .containsExactly(second.id)
        assertThat(database.messageDao().getById("m1")!!.pendingApprovalToolIds)
            .isEqualTo("[\"second-risk\"]")
    }

    @Test
    fun exactApprovalCompletionRejectsApprovedAndRunningUntilExecutionIsTerminal() = runBlocking {
        val call = ToolCall("risk", "write_file", "{}")
        val request = exactRequest("m1", listOf(call), setOf(call.id))
        repository.createToolApproval("s1", "m1", listOf(call), setOf(call.id), request)

        repository.decideExactToolApproval("s1", request, approved = true)
        assertThat(repository.completeExactToolApproval("s1", request))
            .isEqualTo(ExactToolApprovalCompletion.Conflict)
        assertThat(repository.claim(key(call.id))).isTrue()
        assertThat(repository.completeExactToolApproval("s1", request))
            .isEqualTo(ExactToolApprovalCompletion.Conflict)
        assertThat(repository.finish(key(call.id), ToolExecutionOutcome.Failed("boom"))).isTrue()
        assertThat(repository.completeExactToolApproval("s1", request))
            .isInstanceOf(ExactToolApprovalCompletion.Completed::class.java)
    }

    @Test
    fun recoveryPreservesValidatedPendingQueueOrderDespiteSameTimestampAndInverseIds() = runBlocking {
        val first = ToolCall("z-first", "write_file", "{}")
        val second = ToolCall("a-second", "delete_file", "{}")
        val request = exactRequest("m1", listOf(first, second), setOf(first.id, second.id))
        repository.createToolApproval("s1", "m1", listOf(first, second), setOf(first.id, second.id), request)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE tool_execution_ledger SET created_at=42, updated_at=42 WHERE assistant_message_id='m1'",
        )
        database.sessionDao().updateToolApprovalState(
            "s1",
            com.promenar.infinity.data.model.json.encodeToString(ApprovalRequest.serializer(), request),
            "paused",
            now++,
        )

        assertThat(repository.recoverApprovalState()).isEqualTo(1)

        val restored = com.promenar.infinity.data.model.json.decodeFromString<ApprovalRequest>(
            database.sessionDao().getById("s1")!!.approvalRequest!!,
        )
        assertThat(restored.calls.map { it.toolCallId }).containsExactly("z-first", "a-second").inOrder()
    }

    @Test
    fun recoveryPreservesExactRiskSummaryReasonAndIdentityHash() = runBlocking {
        val call = ToolCall(
            "exact-recovery",
            "delete_file",
            """{"path":"archive/important.txt","recursive":false}""",
        )
        val request = ToolApprovalRequestFactory.create(
            assistantMessageId = "m1",
            candidates = listOf(
                ApprovalCallCandidate(
                    call,
                    com.promenar.infinity.domain.tool.ToolRisk.DELETE,
                ).copy(executionTarget = """["path","/archive/important.txt"]"""),
            ),
            reason = "用户要求删除归档文件",
        )
        repository.createToolApproval("s1", "m1", listOf(call), setOf(call.id), request)
        database.sessionDao().updateToolApprovalState(
            "s1",
            com.promenar.infinity.data.model.json.encodeToString(ApprovalRequest.serializer(), request),
            "paused",
            now++,
        )

        assertThat(repository.recoverApprovalState()).isEqualTo(1)

        val restored = com.promenar.infinity.data.model.json.decodeFromString<ApprovalRequest>(
            database.sessionDao().getById("s1")!!.approvalRequest!!,
        )
        assertThat(restored).isEqualTo(request)
        assertThat(restored.calls.single().risk).isEqualTo("delete")
        assertThat(restored.calls.single().executionTarget).isEqualTo("""["path","/archive/important.txt"]""")
        assertThat(restored.calls.single().argumentsSummary)
            .isEqualTo("{\"path\":\"archive/important.txt\",\"recursive\":false}")
        assertThat(restored.reason).isEqualTo("用户要求删除归档文件")
        assertThat(restored.identityHash).isEqualTo(request.identityHash)
    }

    @Test
    fun recoveryWithoutExactPersistedApprovalFailsClosedInsteadOfRebuildingUnknownRisk() = runBlocking {
        val call = ToolCall("missing-exact", "delete_file", """{"path":"archive.txt"}""")
        val request = ToolApprovalRequestFactory.create(
            assistantMessageId = "m1",
            candidates = listOf(
                ApprovalCallCandidate(call, com.promenar.infinity.domain.tool.ToolRisk.DELETE),
            ),
            reason = "精确审批原因",
        )
        repository.createToolApproval("s1", "m1", listOf(call), setOf(call.id), request)
        database.sessionDao().updateToolApprovalState("s1", null, "paused", now++)

        assertThat(repository.recoverApprovalState()).isEqualTo(0)

        assertThat(repository.state(key(call.id))).isEqualTo(ToolLedgerState.FAILED)
        assertThat(database.sessionDao().getById("s1")!!.approvalRequest).isNull()
        assertThat(database.sessionDao().getById("s1")!!.loopStatus).isEqualTo("paused")
        val terminal = database.messageDao().getBySession("s1").single {
            it.role == "tool" && it.toolCallId == call.id
        }
        assertThat(terminal.content).isEqualTo("工具审批状态无效，已安全终止，请重新发起。")
        assertThat(terminal.content).doesNotContain("archive.txt")
        assertThat(terminal.content).doesNotContain("精确审批原因")
    }

    @Test
    fun exactApprovalFailsClosedWhenQueueHashOrOrderedIdentityDoesNotMatchPersistedState() = runBlocking {
        val first = ToolCall("first-risk", "write_file", "{\"path\":\"a\"}")
        val second = ToolCall("second-risk", "delete_file", "{\"path\":\"b\"}")
        val request = ToolApprovalRequestFactory.create(
            "m1",
            listOf(
                ApprovalCallCandidate(first, com.promenar.infinity.domain.tool.ToolRisk.FILE_WRITE),
                ApprovalCallCandidate(second, com.promenar.infinity.domain.tool.ToolRisk.DELETE),
            ),
        )
        repository.createToolApproval("s1", "m1", listOf(first, second), setOf(first.id, second.id), request)

        val staleHash = repository.decideExactToolApproval(
            "s1",
            request.copy(identityHash = "stale-hash"),
            approved = true,
        )
        val reordered = repository.decideExactToolApproval(
            "s1",
            request.copy(calls = request.calls.reversed()),
            approved = false,
        )

        assertThat(staleHash).isEqualTo(ExactToolApprovalDecision.Conflict)
        assertThat(reordered).isEqualTo(ExactToolApprovalDecision.Conflict)
        assertThat(repository.state(key(first.id))).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
        assertThat(repository.state(key(second.id))).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
        assertThat(database.messageDao().getBySession("s1").filter { it.role == "tool" }).isEmpty()
    }

    @Test
    fun startupFailsClosedForApprovedAndPendingRecoveryMixWithoutRebuildingApproval() = runBlocking {
        val calls = listOf(
            com.promenar.infinity.data.model.ToolCall("pending", "write_file", "{}"),
            com.promenar.infinity.data.model.ToolCall("approved", "read_file", "{}"),
        )
        repository.register(key("approved"), preparedIdentity(calls[1], requiresApproval = false))
        repository.createToolApproval(
            "s1",
            "m1",
            calls,
            setOf("pending"),
            exactRequest("m1", calls, setOf("pending")),
        )
        repository.approve(setOf(key("pending")))
        database.messageDao().updatePendingApprovalToolIds("m1", null)
        database.sessionDao().updateToolApprovalState("s1", null, "paused", now++)

        assertThat(repository.recoverApprovalState()).isEqualTo(0)

        assertThat(repository.state(key("pending"))).isEqualTo(ToolLedgerState.FAILED)
        assertThat(repository.state(key("approved"))).isEqualTo(ToolLedgerState.FAILED)
        assertThat(database.messageDao().getById("m1")!!.pendingApprovalToolIds).isNull()
        assertThat(database.sessionDao().getById("s1")!!.approvalRequest).isNull()
        assertThat(database.sessionDao().getById("s1")!!.loopStatus).isEqualTo("paused")
        assertThat(database.messageDao().getBySession("s1").filter { it.role == "tool" }
            .mapNotNull { it.toolCallId }).containsExactly("approved", "pending")
        Unit
    }

    @Test
    fun startupFailsClosedForRunningAndPendingRecoveryMix() = runBlocking {
        val running = ToolCall("running", "write_file", "{}")
        val pending = ToolCall("pending", "delete_file", "{}")
        val request = exactRequest("m1", listOf(running, pending), setOf(running.id, pending.id))
        repository.createToolApproval(
            "s1",
            "m1",
            listOf(running, pending),
            setOf(running.id, pending.id),
            request,
        )
        repository.decideExactToolApproval("s1", request, approved = true)
        assertThat(repository.claim(key(running.id))).isTrue()

        assertThat(repository.recoverApprovalState()).isEqualTo(0)

        assertThat(repository.state(key(running.id))).isEqualTo(ToolLedgerState.FAILED)
        assertThat(repository.state(key(pending.id))).isEqualTo(ToolLedgerState.FAILED)
        assertThat(database.sessionDao().getById("s1")!!.approvalRequest).isNull()
        assertThat(database.sessionDao().getById("s1")!!.loopStatus).isEqualTo("paused")
        assertThat(database.messageDao().getBySession("s1").filter { it.role == "tool" }
            .mapNotNull { it.toolCallId }).containsExactly("running", "pending")
        Unit
    }

    @Test
    fun invalidApprovalLayoutFailsClosedWithoutBlockingValidGroup() = runBlocking {
        database.sessionDao().insert(
            SessionEntity(id = "s2", agentId = "a1", createdAt = now, updatedAt = now++),
        )
        database.messageDao().insert(
            Message(id = "m4", role = MessageRole.ASSISTANT, content = "assistant").toEntity("s2"),
        )
        val goodCall = com.promenar.infinity.data.model.ToolCall("good", "write_file", "{}")
        val badCall = com.promenar.infinity.data.model.ToolCall("bad", "write_file", "{}")
        repository.createToolApproval(
            "s1", "m1", listOf(goodCall), setOf(goodCall.id),
            exactRequest("m1", listOf(goodCall), setOf(goodCall.id)),
        )
        repository.createToolApproval(
            "s2", "m4", listOf(badCall), setOf(badCall.id),
            exactRequest("m4", listOf(badCall), setOf(badCall.id)),
        )
        database.openHelper.writableDatabase.execSQL("UPDATE messages SET role='user' WHERE id='m4'")

        assertThat(repository.recoverApprovalState()).isEqualTo(1)

        assertThat(repository.state(key("good", "m1"))).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
        assertThat(repository.state(ToolExecutionKey("s2", "m4", "bad")))
            .isEqualTo(ToolLedgerState.FAILED)
        assertThat(database.messageDao().getBySession("s2").any {
            it.toolCallId == "bad" && it.parentMessageId == "m4"
        }).isFalse()
        assertThat(database.toolExecutionLedgerDao().get("s2", "m4", "bad")?.resultMessageId)
            .isNull()
        assertThat(database.messageDao().getById("m4")?.pendingApprovalToolIds).isNull()
    }

    @Test
    fun concurrentApprovalCreationAllowsOnlyOneGroupPerSession() = runBlocking {
        val first = com.promenar.infinity.data.model.ToolCall("first", "write_file", "{}")
        val second = com.promenar.infinity.data.model.ToolCall("second", "write_file", "{}")
        val results = listOf(
            async(Dispatchers.IO) {
                runCatching { repository.createToolApproval(
                    "s1", "m1", listOf(first), setOf(first.id),
                    exactRequest("m1", listOf(first), setOf(first.id)),
                ) }
            },
            async(Dispatchers.IO) {
                runCatching { repository.createToolApproval(
                    "s1", "m2", listOf(second), setOf(second.id),
                    exactRequest("m2", listOf(second), setOf(second.id)),
                ) }
            },
        ).awaitAll()

        assertThat(results.mapNotNull { it.getOrNull() }).containsExactly(
            com.promenar.infinity.data.repository.ToolApprovalCreation.CREATED,
            com.promenar.infinity.data.repository.ToolApprovalCreation.CONFLICT,
        )
        assertThat(database.toolExecutionLedgerDao().getAwaitingApprovalForSession("s1")
            .map { it.assistantMessageId }.distinct()).hasSize(1)
    }

    @Test
    fun concurrentSameAssistantSameContractIsCreatedThenExisting() = runBlocking {
        val call = com.promenar.infinity.data.model.ToolCall("same", "write_file", "{}")
        val request = exactRequest("m1", listOf(call), setOf(call.id))
        val results = (1..2).map {
            async(Dispatchers.IO) {
                repository.createToolApproval("s1", "m1", listOf(call), setOf(call.id), request)
            }
        }.awaitAll()

        assertThat(results).containsExactly(
            com.promenar.infinity.data.repository.ToolApprovalCreation.CREATED,
            com.promenar.infinity.data.repository.ToolApprovalCreation.EXISTING,
        )
        assertThat(database.toolExecutionLedgerDao().getAwaitingApprovalForSession("s1")).hasSize(1)
    }

    @Test
    fun existingApprovalContractStillAllowsSafeToolToBeClaimedExactlyOnce() = runBlocking {
        val calls = listOf(
            com.promenar.infinity.data.model.ToolCall("safe", "read_file", "{}"),
            com.promenar.infinity.data.model.ToolCall("risk", "write_file", "{}"),
        )
        val request = exactRequest("m1", calls, setOf("risk"))
        repository.register(key("safe"), preparedIdentity(calls[0], requiresApproval = false))

        assertThat(repository.createToolApproval("s1", "m1", calls, setOf("risk"), request))
            .isEqualTo(ToolApprovalCreation.CREATED)
        assertThat(repository.createToolApproval("s1", "m1", calls, setOf("risk"), request))
            .isEqualTo(ToolApprovalCreation.EXISTING)

        assertThat(repository.claim(key("safe"))).isTrue()
        assertThat(repository.claim(key("safe"))).isFalse()
        assertThat(repository.state(key("risk"))).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
    }

    @Test
    fun concurrentSameAssistantDifferentContractConflictsWithoutOverwrite() = runBlocking {
        val first = com.promenar.infinity.data.model.ToolCall("same", "write_file", "{}")
        val second = first.copy(arguments = "{\"path\":\"different\"}")
        val request = exactRequest("m1", listOf(first), setOf(first.id))
        val results = listOf(first, second).map { call ->
            async(Dispatchers.IO) {
                runCatching {
                    repository.createToolApproval("s1", "m1", listOf(call), setOf(call.id), request)
                }
            }
        }.awaitAll()

        assertThat(results.mapNotNull { it.getOrNull() }).containsExactly(
            com.promenar.infinity.data.repository.ToolApprovalCreation.CREATED,
            com.promenar.infinity.data.repository.ToolApprovalCreation.CONFLICT,
        )
        assertThat(database.toolExecutionLedgerDao().getAwaitingApprovalForSession("s1")).hasSize(1)
    }

    @Test
    fun recoverySelectsEarliestValidGroupAndFailsClosedTheOther() = runBlocking {
        val first = com.promenar.infinity.data.model.ToolCall("first", "write_file", "{}")
        repository.createToolApproval(
            "s1", "m1", listOf(first), setOf(first.id),
            exactRequest("m1", listOf(first), setOf(first.id)),
        )
        database.messageDao().updateToolApprovalPayload(
            "m2",
            """[{"id":"second","name":"write_file","arguments":"{}"}]""",
            """["second"]""",
        )
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO tool_execution_ledger(
               session_id, assistant_message_id, tool_call_id, tool_name, requires_approval, status, created_at, updated_at
               ) VALUES('s1','m2','second','write_file',1,'PENDING_APPROVAL',2,2)""",
        )

        assertThat(repository.recoverApprovalState()).isEqualTo(1)

        assertThat(repository.state(key("first", "m1"))).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
        assertThat(repository.state(key("second", "m2"))).isEqualTo(ToolLedgerState.FAILED)
        assertThat(database.messageDao().getById("m1")!!.pendingApprovalToolIds).contains("first")
        assertThat(database.messageDao().getById("m2")!!.pendingApprovalToolIds).isNull()
    }

    @Test
    fun completingCurrentGroupFailsClosedNextGroupWithoutItsExactPersistedApproval(): Unit = runBlocking {
        val first = com.promenar.infinity.data.model.ToolCall("first", "write_file", "{}")
        val second = com.promenar.infinity.data.model.ToolCall("second", "write_file", "{}")
        val secondIdentity = preparedIdentity(second, requiresApproval = true)
        repository.createToolApproval(
            "s1", "m1", listOf(first), setOf(first.id),
            exactRequest("m1", listOf(first), setOf(first.id)),
        )
        database.messageDao().updateToolApprovalPayload(
            "m2",
            """[{"id":"second","name":"write_file","arguments":"{}"}]""",
            """["second"]""",
        )
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO tool_execution_ledger(
               session_id, assistant_message_id, tool_call_id, tool_name, runtime_tool_id,
               arguments_digest, definition_digest, requires_approval, status, created_at, updated_at
               ) VALUES('s1','m2','second','write_file',?,?,?,1,'PENDING_APPROVAL',2,2)""",
            arrayOf(
                secondIdentity.runtimeToolId,
                secondIdentity.argumentsDigest,
                secondIdentity.definitionDigest,
            ),
        )
        repository.approve(setOf(key("first", "m1")))
        repository.claim(key("first", "m1"))
        repository.finishWithResult(
            key("first", "m1"),
            "write_file",
            "done",
            null,
            ToolExecutionOutcome.Succeeded,
        )

        val transition = repository.completeToolApproval("s1", "m1")

        assertThat(database.sessionDao().getById("s1")!!.loopStatus).isEqualTo("paused")
        assertThat(database.messageDao().getById("m2")!!.pendingApprovalToolIds).isNull()
        assertThat(repository.state(key("second", "m2"))).isEqualTo(ToolLedgerState.FAILED)
        assertThat(transition.loopStatus).isEqualTo(com.promenar.infinity.data.model.LoopStatus.PAUSED)
        assertThat(transition.nextAssistantMessageId).isNull()
        assertThat(transition.nextPendingToolCallIds).isEmpty()
        Unit
    }

    @Test
    fun completingRiskApprovalNeverSelectsUnclaimedSafeToolAsNextApproval() = runBlocking {
        val risk = com.promenar.infinity.data.model.ToolCall("risk", "write_file", "{}")
        repository.createToolApproval(
            "s1", "m1", listOf(risk), setOf(risk.id),
            exactRequest("m1", listOf(risk), setOf(risk.id)),
        )
        repository.register(key("safe", messageId = "m2"), "read_file", requiresApproval = false)
        repository.approve(setOf(key("risk")))
        repository.claim(key("risk"))
        repository.finishWithResult(
            key("risk"),
            "write_file",
            "done",
            null,
            ToolExecutionOutcome.Succeeded,
        )

        val transition = repository.completeToolApproval("s1", "m1")

        assertThat(transition.loopStatus)
            .isEqualTo(com.promenar.infinity.data.model.LoopStatus.RUNNING)
        assertThat(transition.nextAssistantMessageId).isNull()
        assertThat(repository.state(key("safe", messageId = "m2")))
            .isEqualTo(ToolLedgerState.APPROVED)
    }

    private fun key(
        toolCallId: String,
        messageId: String = "m1",
    ) = ToolExecutionKey(
        sessionId = "s1",
        assistantMessageId = messageId,
        toolCallId = toolCallId,
    )

    private suspend fun ToolExecutionLedgerRepository.register(
        key: ToolExecutionKey,
        toolName: String,
        requiresApproval: Boolean,
    ): ToolRegistrationResult = register(
        key,
        legacyIdentity(ToolCall(key.toolCallId, toolName, "{}"), requiresApproval),
    )

    private suspend fun ToolExecutionLedgerRepository.claim(key: ToolExecutionKey): Boolean =
        claim(key, requireNotNull(invocationIdentity(key)))

    private fun exactRequest(
        assistantMessageId: String,
        calls: List<ToolCall>,
        pendingIds: Set<String>,
    ): ApprovalRequest = ToolApprovalRequestFactory.create(
        assistantMessageId = assistantMessageId,
        candidates = calls.filter { it.id in pendingIds }.map {
            ApprovalCallCandidate(it, com.promenar.infinity.domain.tool.ToolRisk.UNKNOWN)
        },
    )

    private fun ApprovalCallCandidate(
        call: ToolCall,
        risk: com.promenar.infinity.domain.tool.ToolRisk,
    ): ApprovalCallCandidate = ApprovalCallCandidate(
        call = call,
        risk = risk,
        identity = preparedIdentity(call, requiresApproval = true, risk = risk),
    )

    private fun preparedIdentity(
        call: ToolCall,
        requiresApproval: Boolean,
        risk: com.promenar.infinity.domain.tool.ToolRisk = com.promenar.infinity.domain.tool.ToolRisk.UNKNOWN,
    ): ToolInvocationIdentity {
        val tool = com.promenar.infinity.data.remote.protocol.ProtocolTool(
            function = com.promenar.infinity.data.remote.protocol.ProtocolToolFunction(
                name = call.name,
                description = "test definition",
                parameters = """{"type":"object","additionalProperties":true}""",
            ),
            risk = risk,
            runtimeToolId = "test:${call.name}",
            sourceId = "test",
        )
        return when (val result = ToolInvocationIdentityFactory.fromPreparedToolCall(
            call,
            tool,
            requiresApproval,
        )) {
            is ToolInvocationIdentityResolution.Valid -> result.identity
            is ToolInvocationIdentityResolution.Invalid -> error(result.message)
        }
    }

    private fun legacyIdentity(
        call: ToolCall,
        requiresApproval: Boolean,
    ): ToolInvocationIdentity = when (
        val result = ToolInvocationIdentityFactory.fromLegacyToolCall(call, requiresApproval)
    ) {
        is ToolInvocationIdentityResolution.Valid -> result.identity
        is ToolInvocationIdentityResolution.Invalid -> error(result.message)
    }
}
