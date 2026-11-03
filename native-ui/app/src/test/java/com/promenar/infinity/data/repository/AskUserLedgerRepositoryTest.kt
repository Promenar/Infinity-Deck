package com.promenar.infinity.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.model.*
import com.promenar.infinity.domain.ask.AskUserAnswer
import com.promenar.infinity.domain.ask.PendingQuestion
import com.promenar.infinity.ui.chat.manager.AskUserTool
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AskUserLedgerRepositoryTest {
    private val databaseName = "ask_user_test_${java.util.UUID.randomUUID()}.db"
    private lateinit var db: InfinityDatabase
    private lateinit var repository: AskUserLedgerRepository
    private val call = ToolCall("question", "ask_user", """{"questions":[{"header":"方案","question":"选哪个？","options":[{"label":"甲"},{"label":"乙"}]}]}""")
    private val tool = AskUserTool().toProtocolTool()

    @Before fun setup() = runBlocking {
        db = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
        repository = AskUserLedgerRepository(db)
        db.sessionDao().insert(SessionEntity(id = "s", agentId = "a", createdAt = 1, updatedAt = 1))
        db.messageDao().insert(Message(id = "u", role = MessageRole.USER, content = "处理任务", createdAt = 1).toEntity("s"))
        db.messageDao().insert(Message(id = "m", role = MessageRole.ASSISTANT, content = "", parentMessageId = "u", createdAt = 2).toEntity("s"))
    }

    @After fun cleanup() { db.close(); ApplicationProvider.getApplicationContext<Context>().deleteDatabase(databaseName) }

    private suspend fun create(calls: List<ToolCall> = listOf(call), available: Boolean = true) =
        repository.createBatch("s", "m", calls, if (available) tool else null)

    private suspend fun pending(): PendingQuestion = repository.pending("s")!!

    @Test fun `插队关闭提问并持久化工具结果原因且不会重复关闭`() = runBlocking {
        create()
        val reason = "用户插队打断了本步，调用未执行。"
        val cancelled = repository.cancel("s", interruptionReason = reason)
        assertThat(cancelled.single().toolCallId).isEqualTo("question")
        assertThat(cancelled.single().content).isEqualTo(reason)
        assertThat(db.messageDao().getById(cancelled.single().id)!!.content).isEqualTo(reason)
        assertThat(repository.pending("s")).isNull()
        assertThat(repository.cancel("s", interruptionReason = reason)).isEmpty()
    }

    @Test fun `用户回答清零自动唤醒计数并解除自动处理暂停`() = runBlocking {
        create()
        val latest = db.sessionDao().getById("s")!!
        db.sessionDao().updateRow(latest.copy(sessionAutoWakeCount = 8, sessionAutoProcessingPaused = true))

        assertThat(repository.answer(pending(), AskUserAnswer(skipped = true))).isNotNull()

        val answered = db.sessionDao().getById("s")!!
        assertThat(answered.sessionAutoWakeCount).isEqualTo(0)
        assertThat(answered.sessionAutoProcessingPaused).isFalse()
        assertThat(answered.loopStatus).isEqualTo("input_answered")
    }

    @Test fun `过期回答和提问恢复不能清零自动唤醒计数`() = runBlocking {
        create()
        val question = pending()
        val latest = db.sessionDao().getById("s")!!
        db.sessionDao().updateRow(latest.copy(sessionAutoWakeCount = 8, sessionAutoProcessingPaused = true))

        assertThat(repository.answer(question.copy(toolCallId = "stale"), AskUserAnswer(skipped = true))).isNull()
        AskUserLedgerRepository(db).recoverAnsweredState()
        assertThat(AskUserLedgerRepository(db).pending("s")).isEqualTo(question)

        val restored = db.sessionDao().getById("s")!!
        assertThat(restored.sessionAutoWakeCount).isEqualTo(8)
        assertThat(restored.sessionAutoProcessingPaused).isTrue()
    }

    @Test fun `提问和账本原子持久化且重建和审批恢复保留提问`() = runBlocking {
        assertThat(create().pending).isNotNull()
        val expected = pending()
        assertThat(db.sessionDao().getById("s")!!.loopStatus).isEqualTo("waiting_for_input")
        ToolExecutionLedgerRepository(db).recoverApprovalState()
        val rebuilt = AskUserLedgerRepository(db)
        assertThat(rebuilt.pending("s")).isEqualTo(expected)
        assertThat(rebuilt.observePending("s").first()).isEqualTo(expected)
        assertThat(db.messageDao().getById("m")!!.pendingApprovalToolIds).isNull()
        assertThat(db.toolExecutionLedgerDao().get("s", "m", "question")!!.status.name).isEqualTo("WAITING_INPUT")
    }

    @Test fun `同批全部其它调用失败且不创建任何待审批调用`() = runBlocking {
        val before = ToolCall("before", "shell_exec", "{}")
        val after = ToolCall("after", "read_file", "{}")
        val duplicate = call.copy(id = "question2")
        val result = create(listOf(before, call, after, duplicate))
        assertThat(result.pending!!.toolCallId).isEqualTo("question")
        assertThat(result.messages.map { it.toolCallId }).containsExactly("before", "after", "question2")
        val failures = result.messages.associate { it.toolCallId to Json.parseToJsonElement(it.content).jsonObject }
        assertThat(failures.getValue("before").getValue("error").jsonPrimitive.content).isEqualTo("ASK_USER_BATCH_NOT_EXECUTED")
        assertThat(failures.getValue("after").getValue("error").jsonPrimitive.content).isEqualTo("ASK_USER_BATCH_NOT_EXECUTED")
        assertThat(failures.getValue("question2").getValue("error").jsonPrimitive.content).isEqualTo("ASK_USER_MULTIPLE_CALLS")
        assertThat(db.toolExecutionLedgerDao().getAwaitingApproval()).isEmpty()
    }

    @Test fun `非法参数与无界面误调用返回错误且不暂停`() = runBlocking {
        val result = create(listOf(call.copy(arguments = "{}")))
        assertThat(result.pending).isNull()
        assertThat(result.messages.single().content).contains("ASK_USER_INVALID_ARGUMENTS")
        assertThat(db.sessionDao().getById("s")!!.loopStatus).isNotEqualTo("waiting_for_input")
        val unavailable = create(listOf(call.copy(id = "headless")), available = false)
        assertThat(unavailable.pending).isNull()
        assertThat(unavailable.messages.single().content).contains("ASK_USER_UNAVAILABLE")
    }

    @Test fun `回答JSON落盘后仅建立一个续跑锚点且可以从重建仓库恢复`() = runBlocking {
        create()
        val question = pending()
        val answer = AskUserAnswer(mapOf("方案" to listOf("甲")), other = "尽量简洁")
        val result = repository.answer(question, answer)!!
        assertThat(repository.pending("s")).isNull()
        assertThat(Json.parseToJsonElement(result.result.content)).isEqualTo(Json.parseToJsonElement(
            """{"answers":{"方案":["甲"]},"other":"尽量简洁"}"""))
        assertThat(repository.answer(question, answer)).isNull()
        val restored = AskUserLedgerRepository(db).resumption("s")!!
        assertThat(repository.resumptionForAssistant("s", result.assistant.id)).isEqualTo(restored)
        assertThat(restored.assistant.id).isEqualTo(result.assistant.id)
        assertThat(restored.user.id).isEqualTo("u")
        assertThat(repository.consumeResumption("s", result.assistant.id)).isTrue()
        assertThat(repository.consumeResumption("s", result.assistant.id)).isFalse()
        assertThat(repository.resumption("s")).isNull()
        assertThat(db.messageDao().getBySession("s").count { it.toolCallId == "question" }).isEqualTo(1)
    }

    @Test fun `多选自由回答和跳过分别形成正确结果`() = runBlocking {
        val cases = listOf(AskUserAnswer(mapOf("方案" to listOf("甲", "乙"))), AskUserAnswer(other = "自行选择"), AskUserAnswer(skipped = true))
        cases.forEachIndexed { index, answer ->
            val next = call.copy(id = "q$index", arguments = call.arguments.replace("\"header\"", "\"multiSelect\":true,\"header\""))
            create(listOf(next))
            val result = repository.answer(pending(), answer)!!
            if (answer.skipped) assertThat(result.result.content).isEqualTo("{\"skipped\":true}")
            else assertThat(result.result.content).contains(if (answer.other != null) "自行选择" else "乙")
            repository.consumeResumption("s", result.assistant.id)
        }
    }

    @Test fun `过期非法和并发重复回答不产生第二个结果或续跑`() = runBlocking {
        create()
        val question = pending()
        assertThat(repository.answer(question.copy(toolCallId = "stale"), AskUserAnswer(skipped = true))).isNull()
        assertThat(repository.answer(question, AskUserAnswer(mapOf("方案" to listOf("未知"))))).isNull()
        assertThat(repository.pending("s")).isEqualTo(question)
        val results = (1..8).map { async(Dispatchers.IO) { repository.answer(question, AskUserAnswer(skipped = true)) } }.awaitAll()
        assertThat(results.filterNotNull()).hasSize(1)
        assertThat(db.messageDao().getBySession("s").count { it.toolCallId == "question" }).isEqualTo(1)
    }

    @Test fun `待回答停止写入取消结果且停止已回答续跑不会改写答案`() = runBlocking {
        create()
        val question = pending()
        val messages = repository.cancel("s")
        assertThat(messages.single().content).contains("用户未回答并停止了本轮")
        assertThat(repository.pending("s")).isNull()
        assertThat(repository.answer(question, AskUserAnswer(skipped = true))).isNull()
        create(listOf(call.copy(id = "second")))
        val answered = repository.answer(pending(), AskUserAnswer(skipped = true))!!
        repository.cancel("s")
        assertThat(repository.resumption("s")).isNull()
        assertThat(db.messageDao().getById(answered.result.id)!!.content).isEqualTo("{\"skipped\":true}")
    }

    @Test fun `删除会话后问题与回答入口失效`() = runBlocking {
        create()
        val question = pending()
        repository.cancel("s")
        db.sessionDao().deleteById("s")
        assertThat(repository.pending("s")).isNull()
        assertThat(repository.answer(question, AskUserAnswer(skipped = true))).isNull()
        assertThat(db.toolExecutionLedgerDao().getInputForSession("s")).isEmpty()
    }

    @Test fun `回答结果写失败回滚全部状态且原提问可以重试`() = runBlocking {
        create()
        val question = pending()
        val latest = db.sessionDao().getById("s")!!
        db.sessionDao().updateRow(latest.copy(sessionAutoWakeCount = 8, sessionAutoProcessingPaused = true))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_answer BEFORE INSERT ON messages WHEN NEW.role = 'tool' BEGIN SELECT RAISE(ABORT, 'test write failure'); END")
        assertThat(runCatching { repository.answer(question, AskUserAnswer(skipped = true)) }.isFailure).isTrue()
        assertThat(repository.pending("s")).isEqualTo(question)
        assertThat(repository.resumption("s")).isNull()
        assertThat(db.sessionDao().getById("s")!!.sessionAutoWakeCount).isEqualTo(8)
        assertThat(db.sessionDao().getById("s")!!.sessionAutoProcessingPaused).isTrue()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_answer")
        assertThat(repository.answer(question, AskUserAnswer(skipped = true))).isNotNull()
        assertThat(db.sessionDao().getById("s")!!.sessionAutoWakeCount).isEqualTo(0)
        assertThat(db.sessionDao().getById("s")!!.sessionAutoProcessingPaused).isFalse()
    }

    @Test fun `同助手消息的冲突提问不得覆盖当前提问参数`() = runBlocking {
        create()
        val question = pending()
        val result = create(listOf(call.copy(id = "conflict", arguments = call.arguments.replace("选哪个？", "另一个问题"))))
        assertThat(result.pending).isNull()
        assertThat(result.messages.single().content).contains("ASK_USER_CONFLICT")
        assertThat(repository.pending("s")).isEqualTo(question)
    }

    @Test fun `会话普通保存不能撤销待回答状态`() = runBlocking {
        val stale = db.sessionDao().getById("s")!!
        create()
        db.sessionDao().update(stale.copy(title = "新的标题"))
        assertThat(repository.pending("s")).isNotNull()
        assertThat(db.sessionDao().getById("s")!!.title).isEqualTo("新的标题")
    }

    @Test fun `待回答与待审批双向互斥`() = runBlocking {
        create()
        val key = ToolExecutionKey("s", "m", "approval")
        val approvalCall = ToolCall("approval", "shell_exec", "{}")
        val approvalTool = tool.copy(function = tool.function.copy(name = "shell_exec", parameters = "{}"), runtimeToolId = "shell_exec", risk = com.promenar.infinity.domain.tool.ToolRisk.PROCESS)
        val identity = (ToolInvocationIdentityFactory.fromPreparedToolCall(approvalCall, approvalTool, true) as ToolInvocationIdentityResolution.Valid).identity
        val request = ToolApprovalRequestFactory.create("m", listOf(ApprovalCallCandidate(approvalCall, approvalTool.risk, identity)))
        val toolLedger = ToolExecutionLedgerRepository(db)
        assertThat(toolLedger.createToolApproval("s", "m", listOf(approvalCall), setOf("approval"), request)).isEqualTo(ToolApprovalCreation.CONFLICT)
        assertThat(repository.pending("s")).isNotNull()
        repository.cancel("s")
        assertThat(toolLedger.createToolApproval("s", "m", listOf(approvalCall), setOf("approval"), request)).isEqualTo(ToolApprovalCreation.CREATED)
        db.messageDao().insert(Message(id = "m2", role = MessageRole.ASSISTANT, content = "", parentMessageId = "u", createdAt = 5).toEntity("s"))
        val blocked = repository.createBatch("s", "m2", listOf(call.copy(id = "blocked")), tool)
        assertThat(blocked.pending).isNull()
        assertThat(blocked.messages.single().content).contains("ASK_USER_CONFLICT")
        assertThat(db.sessionDao().getById("s")!!.loopStatus).isEqualTo("waiting_for_approval")
    }

    @Test fun `关闭并重开数据库保留提问及回答后的续跑锚点`() = runBlocking {
        create()
        val question = pending()
        fun reopen() {
            db.close()
            db = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java, databaseName)
                .allowMainThreadQueries().build()
            repository = AskUserLedgerRepository(db)
        }
        reopen()
        ToolExecutionLedgerRepository(db).recoverApprovalState()
        assertThat(repository.pending("s")).isEqualTo(question)
        val answered = repository.answer(question, AskUserAnswer(skipped = true))!!
        reopen()
        ToolExecutionLedgerRepository(db).recoverApprovalState()
        assertThat(repository.resumption("s")!!.assistant.id).isEqualTo(answered.assistant.id)
        assertThat(repository.resumption("s")!!.result.content).isEqualTo("{\"skipped\":true}")
    }

    @Test fun `旧的等待状态快照不能复活已关闭的问题或阻塞系统轮`() = runBlocking {
        create()
        val stale = db.sessionDao().getById("s")!!
        assertThat(db.sessionDao().hasPendingUserInteraction()).isTrue()
        // 系统轮按会话判断：其它会话不受这一会话的提问阻挡。
        assertThat(db.sessionDao().hasPendingUserInteraction("s")).isTrue()
        assertThat(db.sessionDao().hasPendingUserInteraction("other")).isFalse()
        val result = repository.answer(pending(), AskUserAnswer(skipped = true))!!
        assertThat(db.sessionDao().hasPendingUserInteraction()).isTrue()
        repository.consumeResumption("s", result.assistant.id)
        db.sessionDao().update(stale.copy(title = "陈旧快照的标题更新"))
        assertThat(db.sessionDao().getById("s")!!.loopStatus).isEqualTo("running")
        assertThat(db.sessionDao().hasPendingUserInteraction()).isFalse()
        assertThat(db.sessionDao().hasPendingUserInteraction("s")).isFalse()
    }

    @Test fun `续跑终态持久化后的进程中断只补齐账本不重放模型`() = runBlocking {
        create()
        val answer = repository.answer(pending(), AskUserAnswer(skipped = true))!!
        val latest = db.sessionDao().getById("s")!!
        db.sessionDao().updateRow(latest.copy(sessionAutoWakeCount = 8, sessionAutoProcessingPaused = true))
        db.messageDao().updateStatus(answer.assistant.id, "error")
        assertThat(repository.resumption("s")).isNull()
        val restored = AskUserLedgerRepository(db)
        restored.recoverAnsweredState()
        assertThat(db.toolExecutionLedgerDao().get("s", "m", "question")!!.status.name).isEqualTo("SUCCEEDED")
        assertThat(db.sessionDao().getById("s")!!.loopStatus).isEqualTo("paused")
        assertThat(db.sessionDao().getById("s")!!.sessionAutoWakeCount).isEqualTo(8)
        assertThat(db.sessionDao().getById("s")!!.sessionAutoProcessingPaused).isTrue()
        restored.recoverAnsweredState()
        assertThat(db.messageDao().getBySession("s").count { it.toolCallId == "question" }).isEqualTo(1)
    }

    @Test fun `删除收口必须持有删除门禁并在删除失败保留已终结的工具结果`() = runBlocking {
        val gate = com.promenar.infinity.data.session.SessionExecutionGate()
        repository = AskUserLedgerRepository(db, gate)
        create()
        assertThat(runCatching { repository.cancelForDeletion("s") }.isFailure).isTrue()
        val failure = runCatching {
            gate.withDeletion("s", workspaceRootUuid = null) {
                val result = repository.cancelForDeletion("s")
                assertThat(result.single().content).contains("ASK_USER_CANCELLED")
                throw java.io.IOException("test deletion failure")
            }
        }
        assertThat(failure.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
        assertThat(repository.pending("s")).isNull()
        assertThat(db.sessionDao().getById("s")!!.loopStatus).isEqualTo("paused")
    }

    @Test fun `普通助手消息不触发提问恢复数据库查询`() = runBlocking {
        db.close()
        assertThat(repository.resumptionForAssistant("s", "ai_ordinary")).isNull()
    }
}
