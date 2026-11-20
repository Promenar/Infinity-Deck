package com.promenar.infinity.data.trigger

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.background.generation.*
import com.promenar.infinity.data.backup.BackupStartupState
import com.promenar.infinity.data.generation.*
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.domain.generation.*
import com.promenar.infinity.domain.trigger.*
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.MessageManager
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Test
import java.time.OffsetDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationSystemTurnRuntimeTest {
    @Test fun `等待用户的完成通知重试逐步退避且最长三十秒`() = runTest {
        val store = ChatStore().apply { upsertSession(Session("s", "a")) }
        val inspections = Channel<Long>(Channel.UNLIMITED)
        val state = MutableStateFlow<GenerationTaskSnapshot?>(
            GenerationTaskSnapshot("parent", "s", "assistant", GenerationPhase.WAITING_INPUT, 0, 1),
        )
        val coordinator = mockk<GenerationCoordinator>(relaxed = true)
        every { coordinator.observe("s") } answers {
            inspections.trySend(testScheduler.currentTime)
            state
        }
        val bridge = SubagentCompletionSteering(backgroundScope)
        val runtime = runtime(store, messageManager(store), coordinator, mockk(relaxed = true), bridge) { _, _, _ -> }
        val completion = async { runtime.runSubagentCompletion(completionRequest(), SystemTurnAdmissionTicket()) }
        try {
            val inspectedAt = List(10) { inspections.receive() }

            assertThat(inspectedAt).containsExactly(
                0L, 250L, 750L, 1_750L, 3_750L, 7_750L, 15_750L, 31_750L, 61_750L, 91_750L,
            ).inOrder()
            assertThat(store.getSession("s")!!.messages).isEmpty()
        } finally {
            completion.cancelAndJoin()
        }
    }

    @Test fun `重启恢复持久完成通知会生成回复且不重复插入通知`() = runTest {
        val persisted = persistedCompletion()
        val store = ChatStore()
        val generated = mutableListOf<GenerationRequest>()
        val coordinator = completionCoordinator(store, generated)
        val bridge = SubagentCompletionSteering(backgroundScope)
        val runtime = runtime(store, messageManager(store), coordinator, mockk(relaxed = true), bridge,
            findSession = { Session(it, "a", messages = listOf(persisted)) }) { _, _, _ -> }

        runtime.recoverSubagentCompletions(listOf("s", "s"))
        runCurrent()
        runtime.recoverSubagentCompletions(listOf("s"))
        runCurrent()

        assertThat(generated).hasSize(1)
        assertThat(generated.single().userMessageId).isEqualTo("persisted-completion")
        assertThat(generated.single().userContent).isEqualTo(persisted.content)
        assertThat(generated.single().interactionMode).isEqualTo(GenerationInteractionMode.HEADLESS)
        assertThat(store.getSession("s")!!.messages.map { it.id }).contains("persisted-completion")
        assertThat(store.getSession("s")!!.messages).hasSize(2)
        assertThat(store.getSession("s")!!.messages.last().content).isEqualTo("已处理子代理结论")
    }

    @Test fun `会话消息写入唤醒标记后退出仍恢复原消息且不重插入`() = runTest {
        val persisted = Message("mail", MessageRole.USER, SystemTriggerMessage.compose(
            SystemTurnRequest("s", SystemTurnKind.SESSION_MESSAGE, "branch", "分支", "阶段结论", deliveryIds = listOf("mail-id")),
            OffsetDateTime.now()), name = SystemTriggerMessage.MESSAGE_NAME)
        val wake = Message("session_wake_mail", MessageRole.SYSTEM, "admitted", name = "session_message_wake")
        val store = ChatStore()
        val generated = mutableListOf<GenerationRequest>()
        val coordinator = completionCoordinator(store, generated)
        val bridge = SubagentCompletionSteering(backgroundScope)
        val runtime = runtime(store, messageManager(store), coordinator, mockk(relaxed = true), bridge,
            findSession = { Session(it, "a", messages = listOf(persisted, wake), sessionAutoWakeCount = 1) }) { _, _, _ -> }
        runtime.recoverSubagentCompletions(listOf("s"))
        runCurrent()
        assertThat(generated).hasSize(1)
        assertThat(generated.single().userMessageId).isEqualTo("mail")
        assertThat(store.getSession("s")!!.messages.count { it.id == "mail" }).isEqualTo(1)
        assertThat(store.getSession("s")!!.sessionAutoWakeCount).isEqualTo(1)
    }
    @Test fun `重启恢复复用尚未启动生成的空助手占位`() = runTest {
        val persisted = persistedCompletion()
        val placeholder = Message("prepared-assistant", MessageRole.ASSISTANT, "", parentMessageId = persisted.id)
        val store = ChatStore().apply { upsertSession(Session("s", "a", messages = listOf(persisted, placeholder))) }
        val generated = mutableListOf<GenerationRequest>()
        val coordinator = completionCoordinator(store, generated)
        val bridge = SubagentCompletionSteering(backgroundScope)
        val runtime = runtime(store, messageManager(store), coordinator, mockk(relaxed = true), bridge) { _, _, _ -> }

        runtime.recoverSubagentCompletions(listOf("s"))
        runCurrent()

        assertThat(generated).hasSize(1)
        assertThat(generated.single().assistantMessageId).isEqualTo("prepared-assistant")
        assertThat(store.getSession("s")!!.messages).hasSize(2)
        assertThat(store.getSession("s")!!.messages.last().content).isEqualTo("已处理子代理结论")
    }

    @Test fun `恢复完成通知在全局生成满载时排队并在名额释放后处理`() = runTest {
        val store = ChatStore()
        val generated = mutableListOf<GenerationRequest>()
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { request, emit ->
                if (request.sessionId != "s") awaitCancellation()
                generated += request
                store.updateMessageInSession("s", request.assistantMessageId) { it.copy(content = "已处理子代理结论") }
                emit(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
            }
        })
        repeat(MAX_CONCURRENT_GENERATIONS) { index ->
            coordinator.start(GenerationRequest("busy-$index", "assistant-$index", null, "", emptyList(),
                GenerationRuntimePolicy.BACKGROUND_ALLOWED))
        }
        val persisted = persistedCompletion()
        val bridge = SubagentCompletionSteering(backgroundScope)
        val runtime = runtime(store, messageManager(store), coordinator, mockk(relaxed = true), bridge,
            findSession = { Session(it, "a", messages = listOf(persisted)) }) { _, _, _ -> }

        runtime.recoverSubagentCompletions(listOf("s"))

        assertThat(coordinator.observe("s").value!!.phase).isEqualTo(GenerationPhase.QUEUED)
        assertThat(generated).isEmpty()
        assertThat(store.getSession("s")!!.messages).hasSize(2)
        assertThat(store.getSession("s")!!.messages.first().id).isEqualTo("persisted-completion")

        coordinator.cancel(coordinator.observe("busy-0").value!!.taskId)
        runCurrent()

        assertThat(generated).hasSize(1)
        assertThat(generated.single().userMessageId).isEqualTo("persisted-completion")
        assertThat(store.getSession("s")!!.messages).hasSize(2)
        assertThat(store.getSession("s")!!.messages.last().content).isEqualTo("已处理子代理结论")
    }

    @Test fun `通知前关联助手已处理结论时恢复不会重放`() = runTest {
        val originalUser = Message("original-user", MessageRole.USER, "原用户问题")
        val assistant = Message("consumer-assistant", MessageRole.ASSISTANT, "已处理通知", parentMessageId = originalUser.id)
        val notice = persistedCompletion().let {
            it.copy(content = it.content.replaceFirst("<system_trigger ", "<system_trigger consumer_assistant=\"consumer-assistant\" "))
        }
        val store = ChatStore().apply { upsertSession(Session("s", "a", messages = listOf(originalUser, assistant, notice))) }
        val generated = mutableListOf<GenerationRequest>()
        val coordinator = completionCoordinator(store, generated)
        val bridge = SubagentCompletionSteering(backgroundScope)
        val runtime = runtime(store, messageManager(store), coordinator, mockk(relaxed = true), bridge) { _, _, _ -> }

        runtime.recoverSubagentCompletions(listOf("s"))
        runCurrent()

        assertThat(generated).isEmpty()
        assertThat(store.getSession("s")!!.messages).hasSize(3)
        assertThat(store.getSession("s")!!.messages[1].content).isEqualTo("已处理通知")
    }

    @Test fun `通知前关联空助手在恢复时复用`() = runTest {
        val originalUser = Message("original-user", MessageRole.USER, "原用户问题")
        val assistant = Message("consumer-assistant", MessageRole.ASSISTANT, "", parentMessageId = originalUser.id)
        val notice = persistedCompletion().let {
            it.copy(content = it.content.replaceFirst("<system_trigger ", "<system_trigger consumer_assistant=\"consumer-assistant\" "))
        }
        val store = ChatStore().apply { upsertSession(Session("s", "a", messages = listOf(originalUser, assistant, notice))) }
        val generated = mutableListOf<GenerationRequest>()
        val coordinator = completionCoordinator(store, generated)
        val bridge = SubagentCompletionSteering(backgroundScope)
        val runtime = runtime(store, messageManager(store), coordinator, mockk(relaxed = true), bridge) { _, _, _ -> }

        runtime.recoverSubagentCompletions(listOf("s"))
        runCurrent()

        assertThat(generated).hasSize(1)
        assertThat(generated.single().assistantMessageId).isEqualTo("consumer-assistant")
        assertThat(generated.single().userMessageId).isEqualTo("original-user")
        assertThat(generated.single().userContent).isEqualTo("原用户问题")
        assertThat(store.getSession("s")!!.messages).hasSize(3)
        assertThat(store.getSession("s")!!.messages[1].parentMessageId).isEqualTo("original-user")
        assertThat(store.getSession("s")!!.messages[1].content).isEqualTo("已处理子代理结论")
    }

    @Test fun `关联前置空助手缺少合法用户锚点时创建通知后的新助手`() = runTest {
        val assistant = Message("consumer-assistant", MessageRole.ASSISTANT, "", parentMessageId = "missing-user")
        val notice = persistedCompletion().let {
            it.copy(content = it.content.replaceFirst("<system_trigger ", "<system_trigger consumer_assistant=\"consumer-assistant\" "))
        }
        val store = ChatStore().apply { upsertSession(Session("s", "a", messages = listOf(assistant, notice))) }
        val generated = mutableListOf<GenerationRequest>()
        val coordinator = completionCoordinator(store, generated)
        val bridge = SubagentCompletionSteering(backgroundScope)
        val runtime = runtime(store, messageManager(store), coordinator, mockk(relaxed = true), bridge) { _, _, _ -> }

        runtime.recoverSubagentCompletions(listOf("s"))
        runCurrent()

        assertThat(generated).hasSize(1)
        assertThat(generated.single().assistantMessageId).isNotEqualTo("consumer-assistant")
        assertThat(generated.single().userMessageId).isEqualTo("persisted-completion")
        assertThat(store.getSession("s")!!.messages).hasSize(3)
        assertThat(store.getSession("s")!!.messages.first().content).isEmpty()
        assertThat(store.getSession("s")!!.messages.last().parentMessageId).isEqualTo("persisted-completion")
    }

    @Test fun `恢复不会重放已有助手输出或后续用户消息的通知`() = runTest {
        val persisted = persistedCompletion()
        val replies = listOf(
            Message("reply", MessageRole.ASSISTANT, "已有回复", parentMessageId = persisted.id),
            Message("reasoned", MessageRole.ASSISTANT, "", reasoning = "已有思考", parentMessageId = persisted.id),
            Message("failed", MessageRole.ASSISTANT, "", isError = true, parentMessageId = persisted.id),
            Message("finished", MessageRole.ASSISTANT, "", status = "success", parentMessageId = persisted.id),
            Message("tool", MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("call", "exec", "{}")), parentMessageId = persisted.id),
            Message("user", MessageRole.USER, "后续用户问题"),
        )
        val store = ChatStore().apply {
            replies.forEachIndexed { index, reply -> upsertSession(Session("s-$index", "a", messages = listOf(persisted, reply))) }
        }
        val generated = mutableListOf<GenerationRequest>()
        val coordinator = completionCoordinator(store, generated)
        val bridge = SubagentCompletionSteering(backgroundScope)
        val runtime = runtime(store, messageManager(store), coordinator, mockk(relaxed = true), bridge) { _, _, _ -> }

        runtime.recoverSubagentCompletions(replies.indices.map { "s-$it" })
        runCurrent()

        assertThat(generated).isEmpty()
        assertThat(store.get().sessions.map { it.messages.size }).containsExactly(2, 2, 2, 2, 2, 2)
    }

    @Test fun `恢复在父会话生成中或等待用户时不启动完成通知轮次`() = runTest {
        for (phase in listOf(GenerationPhase.STREAMING, GenerationPhase.WAITING_INPUT, GenerationPhase.WAITING_APPROVAL)) {
            val store = ChatStore().apply { upsertSession(Session("s", "a", messages = listOf(persistedCompletion()))) }
            val coordinator = mockk<GenerationCoordinator>(relaxed = true)
            every { coordinator.observe("s") } returns MutableStateFlow(
                GenerationTaskSnapshot("parent", "s", "assistant", phase, 0, 1),
            )
            every { coordinator.isSessionBusy("s") } returns (phase == GenerationPhase.STREAMING)
            val bridge = SubagentCompletionSteering(backgroundScope)
            val runtime = runtime(store, messageManager(store), coordinator, mockk(relaxed = true), bridge) { _, _, _ -> }

            runtime.recoverSubagentCompletions(listOf("s"))

            coVerify(exactly = 0) { coordinator.start(any(), any()) }
            assertThat(store.getSession("s")!!.messages).hasSize(1)
        }
    }

    @Test fun `父轮通知已准入但写盘超过轮询间隔时仍等待并交付回执`() = runTest {
        val store = ChatStore().apply { upsertSession(Session("s", "a")) }
        val coordinator = mockk<GenerationCoordinator>(relaxed = true)
        every { coordinator.observe("s") } returns MutableStateFlow(
            GenerationTaskSnapshot("parent", "s", "assistant", GenerationPhase.STREAMING, 0, 1),
        )
        val inspected = CompletableDeferred<Unit>()
        every { coordinator.isSessionBusy("s") } answers {
            inspected.complete(Unit)
            true
        }
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val messages = messageManager(store)
        coEvery { messages.addMessage(any(), any()) } coAnswers {
            val sessionId = firstArg<String>()
            val message = secondArg<Message>()
            writeStarted.complete(Unit)
            releaseWrite.await()
            store.updateSession(sessionId) { it.copy(messages = it.messages + message) }
        }
        val bridge = SubagentCompletionSteering(backgroundScope)
        val runtime = runtime(store, messages, coordinator, mockk(relaxed = true), bridge) { _, _, _ -> }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val completion = async { dispatcher.request(completionRequest()) }
        inspected.await()
        val delivery = backgroundScope.async { bridge.deliver("s", store, messages) }
        try {
            writeStarted.await()
            advanceTimeBy(1_000)
            runCurrent()
            assertThat(completion.isCompleted).isFalse()
            assertThat(store.getSession("s")!!.messages).isEmpty()

            releaseWrite.complete(Unit)
            delivery.await()
            runCurrent()

            assertThat(completion.isCompleted).isTrue()
            assertThat(completion.await()).isEqualTo(SystemTurnOutcome.Completed)
            assertThat(store.getSession("s")!!.messages.single().content).contains("子代理结论")
            coVerify(exactly = 1) { messages.addMessage("s", any()) }
            verify(exactly = 0) { coordinator.cancel(any(), any()) }
        } finally {
            releaseWrite.complete(Unit)
            completion.cancelAndJoin()
            delivery.await()
        }
    }

    @Test fun `生成中的子代理结论在步骤边界插入且不另起一轮`() = runTest {
        val store = ChatStore().apply { upsertSession(Session("s", "a")) }
        val coordinator = mockk<GenerationCoordinator>(relaxed = true)
        every { coordinator.observe("s") } returns MutableStateFlow(
            GenerationTaskSnapshot("parent", "s", "assistant", GenerationPhase.STREAMING, 0, 1),
        )
        every { coordinator.isSessionBusy("s") } returns true
        val bridge = SubagentCompletionSteering(backgroundScope)
        val messages = messageManager(store)
        val runtime = runtime(store, messages, coordinator, mockk(relaxed = true), bridge) { _, _, _ -> }
        val completion = async { runtime.runSubagentCompletion(completionRequest(), SystemTurnAdmissionTicket()) }
        runCurrent()
        assertThat(completion.isCompleted).isFalse()
        assertThat(store.getSession("s")!!.messages).isEmpty()

        bridge.deliver("s", store, messages)
        runCurrent()

        assertThat(completion.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(store.getSession("s")!!.messages.single().content).contains("子代理结论")
        coVerify(exactly = 0) { coordinator.start(any(), any()) }
        verify(exactly = 0) { coordinator.cancel(any(), any()) }
    }

    @Test fun `取消等待送达的调用方不会取消正在生成的父轮`() = runTest {
        val store = ChatStore().apply { upsertSession(Session("s", "a")) }
        val coordinator = mockk<GenerationCoordinator>(relaxed = true)
        every { coordinator.observe("s") } returns MutableStateFlow(
            GenerationTaskSnapshot("parent", "s", "assistant", GenerationPhase.STREAMING, 0, 1),
        )
        every { coordinator.isSessionBusy("s") } returns true
        val bridge = SubagentCompletionSteering(backgroundScope)
        val messages = messageManager(store)
        val runtime = runtime(store, messages, coordinator, mockk(relaxed = true), bridge) { _, _, _ -> }
        val completion = launch { runtime.runSubagentCompletion(completionRequest(), SystemTurnAdmissionTicket()) }
        runCurrent()

        completion.cancelAndJoin()

        assertThat(bridge.deliver("s", store, messages)).isNull()
        verify(exactly = 0) { coordinator.cancel(any(), any()) }
        assertThat(store.getSession("s")!!.messages).isEmpty()
    }

    @Test fun `等待用户的子代理通知暂缓直到恢复下一步骤`() = runTest {
        for (phase in listOf(GenerationPhase.WAITING_INPUT, GenerationPhase.WAITING_APPROVAL)) {
            val store = ChatStore().apply { upsertSession(Session("s", "a")) }
            val state = MutableStateFlow(GenerationTaskSnapshot("parent", "s", "assistant", phase, 0, 1))
            val coordinator = mockk<GenerationCoordinator>(relaxed = true)
            every { coordinator.observe("s") } returns state
            every { coordinator.isSessionBusy("s") } answers { state.value.phase == GenerationPhase.BUILDING_CONTEXT }
            val bridge = SubagentCompletionSteering(backgroundScope)
            val messages = messageManager(store)
            val runtime = runtime(store, messages, coordinator, mockk(relaxed = true), bridge) { _, _, _ -> }
            val completion = async { runtime.runSubagentCompletion(completionRequest(), SystemTurnAdmissionTicket()) }
            runCurrent()
            assertThat(completion.isCompleted).isFalse()
            assertThat(store.getSession("s")!!.messages).isEmpty()
            coVerify(exactly = 0) { coordinator.start(any(), any()) }

            state.value = state.value.copy(phase = GenerationPhase.BUILDING_CONTEXT)
            bridge.deliver("s", store, messages)
            runCurrent()
            assertThat(completion.await()).isEqualTo(SystemTurnOutcome.Completed)
        }
    }

    @Test fun `空闲完成通知落盘后回执不等待新系统轮完成`() = runTest {
        val store = ChatStore()
        val finish = CompletableDeferred<Unit>()
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { _, emit ->
                finish.await()
                emit(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
            }
        })
        val bridge = SubagentCompletionSteering(backgroundScope)
        val foreground = mockk<GenerationForegroundController>(relaxed = true)
        val runtime = runtime(store, messageManager(store), coordinator, foreground, bridge) { _, _, _ -> }
        // 真实 IO 调度由 runTest 的墙钟超时保护，虚拟超时可能先于 IO 返回触发。
        val outcome = runtime.runSubagentCompletion(completionRequest(), SystemTurnAdmissionTicket())
        assertThat(outcome).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(finish.isCompleted).isFalse()
        assertThat(coordinator.isSessionBusy("s")).isTrue()
        assertThat(store.getSession("s")!!.messages.first().name).isEqualTo(SystemTriggerMessage.MESSAGE_NAME)
        finish.complete(Unit)
        runCurrent()
    }

    @Test fun `空闲后台任务结束通知落盘并唤醒会话`() = runTest {
        val store = ChatStore()
        val finish = CompletableDeferred<Unit>()
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { _, emit ->
                finish.await()
                emit(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
            }
        })
        val bridge = SubagentCompletionSteering(backgroundScope)
        val foreground = mockk<GenerationForegroundController>(relaxed = true)
        val runtime = runtime(store, messageManager(store), coordinator, foreground, bridge) { _, _, _ -> }
        // 真实 IO 调度由 runTest 的墙钟超时保护，虚拟超时可能先于 IO 返回触发。
        val outcome = DefaultSystemTurnDispatcher(backgroundScope, runtime, "失败", "中断").request(
            completionRequest().copy(kind = SystemTurnKind.JOB_EXIT),
        )
        assertThat(outcome).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(finish.isCompleted).isFalse()
        assertThat(coordinator.isSessionBusy("s")).isTrue()
        assertThat(store.getSession("s")!!.messages.first().name).isEqualTo(SystemTriggerMessage.MESSAGE_NAME)
        finish.complete(Unit)
        runCurrent()
    }

    @Test fun `生成名额满载时空闲父会话完成通知随系统轮排队`() = runTest {
        val store = ChatStore()
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { _, _ -> awaitCancellation() }
        })
        repeat(MAX_CONCURRENT_GENERATIONS) { index ->
            coordinator.start(GenerationRequest("busy-$index", "assistant-$index", null, "", emptyList(),
                GenerationRuntimePolicy.BACKGROUND_ALLOWED))
        }
        val bridge = SubagentCompletionSteering(backgroundScope)
        val runtime = runtime(store, messageManager(store), coordinator, mockk(relaxed = true), bridge) { _, _, _ -> }

        assertThat(runtime.runSubagentCompletion(completionRequest(), SystemTurnAdmissionTicket()))
            .isEqualTo(SystemTurnOutcome.Completed)

        assertThat(coordinator.observe("s").value!!.phase).isEqualTo(GenerationPhase.QUEUED)
        assertThat(store.getSession("s")!!.messages).hasSize(2)
    }

    @Test fun `完成通知新系统轮准入后返回挂起时取消只收尾已受理的新任务`() = runTest {
        val store = ChatStore()
        val messages = messageManager(store)
        val coordinator = mockk<GenerationCoordinator>(relaxed = true)
        every { coordinator.observe("s") } returns MutableStateFlow(null)
        every { coordinator.isSessionBusy("s") } returns false
        val entered = CompletableDeferred<Unit>()
        coEvery { coordinator.start(any(), any()) } coAnswers {
            check(secondArg<(String) -> Boolean>().invoke("notification-task"))
            entered.complete(Unit)
            awaitCancellation()
        }
        coEvery { coordinator.awaitTaskCompletion("s", "notification-task") } returns
            GenerationTaskSnapshot("notification-task", "s", "assistant", GenerationPhase.CANCELLED, 0, 1)
        val bridge = SubagentCompletionSteering(backgroundScope)
        val runtime = runtime(store, messages, coordinator, mockk(relaxed = true), bridge) { _, _, _ -> }
        val job = launch { runtime.runSubagentCompletion(completionRequest(), SystemTurnAdmissionTicket()) }
        entered.await()

        job.cancelAndJoin()

        verify(exactly = 1) { coordinator.cancel("notification-task", CancellationReason.SHUTDOWN) }
        coVerify(exactly = 1) { coordinator.awaitTaskCompletion("s", "notification-task") }
        coVerify(exactly = 0) { messages.discardPreparedMessages(any(), any()) }
    }

    @Test fun `系统轮按目标会话查询待交互状态`() = runTest {
        val states = mutableMapOf("pending" to true)
        val checked = mutableListOf<String>()
        val runtime = ApplicationSystemTurnRuntime(MutableStateFlow(BackupStartupState.Ready), ChatStore(),
            findSession = { Session(it, "a") }, exists = { true },
            hasApproval = { checked += it; states[it] == true },
            messages = mockk(relaxed = true), coordinator = mockk { every { isSessionBusy(any()) } returns false },
            foreground = mockk(relaxed = true), interactions = GenerationInteractionStore(), executionGate = SessionExecutionGate(),
            notify = { _, _, _ -> }, failureText = { "失败" })
        assertThat(runtime.isBusy("pending")).isTrue()
        assertThat(runtime.isBusy("other")).isFalse()
        states["pending"] = false
        assertThat(runtime.isBusy("pending")).isFalse()
        assertThat(checked).containsExactly("pending", "other", "pending").inOrder()
    }

    @Test fun `前台跟踪异常时已受理任务会取消并收尾`() = runTest {
        val store = ChatStore()
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { _, _ -> awaitCancellation() }
        })
        val foreground = mockk<GenerationForegroundController> {
            every { track(any()) } throws IllegalStateException("前台服务启动异常")
        }
        val notices = mutableListOf<SystemTurnOutcome>()
        val runtime = runtime(store, messageManager(store), coordinator, foreground) { _, result, _ -> notices += result }
        runtime.loadSession("s")
        val result = async { runtime.run(request(), SystemTurnAdmissionTicket()) }
        runCurrent()
        assertThat(result.await()).isInstanceOf(SystemTurnOutcome.Failed::class.java)
        assertThat(coordinator.isSessionBusy("s")).isFalse()
        assertThat(notices).hasSize(1)
    }

    @Test fun `生成已受理但返回挂起时同来源新请求不能撤销原轮`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = ChatStore()
            val accepted = CompletableDeferred<Unit>()
            val returnGate = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val delegate = DefaultGenerationCoordinator(appScope, GenerationRunnerFactory { _, _ ->
                GenerationRunner { _, emit ->
                    finish.await()
                    emit(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
                }
            })
            val coordinator = object : GenerationCoordinator by delegate {
                override suspend fun start(request: GenerationRequest, onAdmission: (String) -> Boolean): StartGenerationResult {
                    val result = delegate.start(request, onAdmission)
                    if (request.userContent.contains("旧内容")) {
                        accepted.complete(Unit)
                        returnGate.await()
                    }
                    return result
                }
            }
            val foreground = mockk<GenerationForegroundController> { every { track(any()) } returns ForegroundStartResult.StartedWithNotifications }
            val runtime = runtime(store, messageManager(store), coordinator, foreground) { _, _, _ -> }
            val dispatcher = DefaultSystemTurnDispatcher(appScope, runtime, "未完成", "系统中断")
            val old = async(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.request(SystemTurnRequest("s", SystemTurnKind.SCHEDULE, "same", "任务", "旧内容"))
            }
            withTimeout(5000) { accepted.await() }
            val newer = async(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.request(SystemTurnRequest("s", SystemTurnKind.SCHEDULE, "same", "任务", "新内容"))
            }
            yield()
            val completedBeforeReturn = old.isCompleted
            returnGate.complete(Unit)
            finish.complete(Unit)
            val oldResult = withTimeout(5000) { old.await() }
            val newResult = withTimeout(5000) { newer.await() }
            assertThat(completedBeforeReturn).isFalse()
            assertThat(oldResult).isEqualTo(SystemTurnOutcome.Completed)
            assertThat(newResult).isEqualTo(SystemTurnOutcome.Completed)
        } finally {
            appScope.cancel()
        }
    }

    @Test fun `系统轮只被目标会话的生成或排队阻挡`() = runTest {
        val store = ChatStore()
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { _, _ -> awaitCancellation() }
        })
        for (id in listOf("one", "two", "three", "four", "queued")) {
            coordinator.start(GenerationRequest(id, "a-$id", "u-$id", "", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED))
        }
        val runtime = runtime(store, messageManager(store), coordinator, mockk(relaxed = true)) { _, _, _ -> }
        assertThat(runtime.isBusy("one")).isTrue()
        assertThat(runtime.isBusy("queued")).isTrue()
        assertThat(runtime.isBusy("unrelated")).isFalse()
    }

    @Test fun `取消旧系统轮只等待它的任务完成`() = runTest {
        val store = ChatStore()
        val coordinator = mockk<GenerationCoordinator>(relaxed = true)
        val snapshot = GenerationTaskSnapshot("old-task", "s", "assistant", GenerationPhase.PREPARING, 0, 1)
        val entered = CompletableDeferred<Unit>()
        var taskWaits = 0
        var sessionWaits = 0
        coEvery { coordinator.start(any(), any()) } coAnswers {
            check(secondArg<(String) -> Boolean>().invoke("old-task"))
            StartGenerationResult.Started("old-task")
        }
        every { coordinator.observe("s") } returns MutableStateFlow(snapshot)
        every { coordinator.cancel("old-task", any()) } returns false
        coEvery { coordinator.awaitTaskCompletion("s", "old-task") } coAnswers {
            taskWaits += 1
            if (taskWaits == 1) { entered.complete(Unit); awaitCancellation() }
            snapshot.copy(phase = GenerationPhase.CANCELLED)
        }
        coEvery { coordinator.awaitSessionIdle("s") } coAnswers { sessionWaits += 1 }
        val foreground = mockk<GenerationForegroundController>(relaxed = true)
        val runtime = runtime(store, messageManager(store), coordinator, foreground) { _, _, _ -> }
        runtime.loadSession("s")
        val job = launch { runtime.run(request(), SystemTurnAdmissionTicket()) }
        entered.await()
        job.cancelAndJoin()
        assertThat(taskWaits).isEqualTo(2)
        assertThat(sessionWaits).isEqualTo(0)
    }

    @Test fun `未打开会话发起轮次服务被拒仍完成且消息带系统标记`() = runTest {
        val store = ChatStore()
        val messages = messageManager(store)
        var generated: GenerationRequest? = null
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { request, emit ->
                generated = request
                store.updateMessageInSession(request.sessionId, request.assistantMessageId) { it.copy(content = "首行\n正文") }
                emit(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
            }
        })
        val foreground = mockk<GenerationForegroundController>()
        every { foreground.track(any()) } returns ForegroundStartResult.NotAllowed(mockk())
        val notices = mutableListOf<SystemTurnOutcome>()
        val runtime = runtime(store, messages, coordinator, foreground) { _, result, _ -> notices += result }
        assertThat(runtime.loadSession("s")).isTrue()
        val result = async { runtime.run(request(), SystemTurnAdmissionTicket()) }; runCurrent()
        assertThat(result.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(generated!!.interactionMode).isEqualTo(GenerationInteractionMode.HEADLESS)
        val session = store.getSession("s")!!
        assertThat(session.messages[0].name).isEqualTo(SystemTriggerMessage.MESSAGE_NAME)
        assertThat(session.messages[0].role).isEqualTo(MessageRole.USER)
        assertThat(SystemTriggerMessage.parse(session.messages[0].content)!!.body).isEqualTo("检查")
        assertThat(session.messages[1].parentMessageId).isEqualTo(session.messages[0].id)
        assertThat(coordinator.active.value).isNull()
        assertThat(notices).containsExactly(SystemTurnOutcome.Completed)
        verify { foreground.track(any()) }
    }

    @Test fun `未打开会话审批暂停的锚点可以在ViewModel外重建`() = runTest {
        val store = ChatStore()
        val messages = messageManager(store)
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { request, emit ->
                store.updateSession("s") { it.copy(approvalRequest = ApprovalRequest(assistantMessageId = request.assistantMessageId)) }
                emit(GenerationEvent.PhaseChanged(GenerationPhase.WAITING_APPROVAL))
            }
        })
        val foreground = mockk<GenerationForegroundController> { every { track(any()) } returns ForegroundStartResult.StartedWithNotifications }
        val runtime = runtime(store, messages, coordinator, foreground) { _, _, _ -> }
        runtime.loadSession("s")
        val result = async { runtime.run(request(), SystemTurnAdmissionTicket()) }; runCurrent()
        assertThat(result.await()).isEqualTo(SystemTurnOutcome.NeedsUser)
        val hydrated = store.getSession("s")!!.copy()
        val continuation = generationResumption(hydrated)!!
        assertThat(continuation.assistant.id).isEqualTo(hydrated.approvalRequest!!.assistantMessageId)
        assertThat(continuation.user.name).isEqualTo(SystemTriggerMessage.MESSAGE_NAME)
        assertThat(continuation.user.content).contains("检查")
        assertThat(coordinator.active.value).isNull()
    }

    @Test fun `会话载入与页面并发只读一次数据库且不会覆盖内存消息`() = runTest {
        val store = ChatStore()
        var reads = 0
        val read = CompletableDeferred<Unit>()
        val first = async { store.getOrLoadSession("s") { reads++; read.await(); Session("s", "a") } }
        runCurrent()
        val second = async { store.getOrLoadSession("s") { reads++; error("重复读取") } }
        runCurrent(); read.complete(Unit)
        assertThat(first.await()).isSameInstanceAs(second.await())
        store.updateSession("s") { it.copy(messages = listOf(Message("u", MessageRole.USER, "内存正文"))) }
        store.getOrLoadSession("s") { error("不应重新读取") }
        assertThat(reads).isEqualTo(1)
        assertThat(store.getSession("s")!!.messages.single().content).isEqualTo("内存正文")
    }

    @Test fun `已有会话未水合时使用关闭文档检索的持久配置`() = runTest {
        val store = ChatStore().apply { upsertSession(Session("s", "a")) }
        val runtime = ApplicationSystemTurnRuntime(MutableStateFlow(BackupStartupState.Ready), store,
            findSession = { Session(it, "a", ragOptions = RagOptions(enableMemory = false, enableDocs = false)) },
            exists = { true }, hasApproval = { false }, messageManager(store), mockk(), mockk(),
            GenerationInteractionStore(), SessionExecutionGate(), { _, _, _ -> }, { "未完成" })
        runtime.loadSession("s")
        assertThat(store.getSession("s")!!.ragOptions!!.enableDocs).isFalse()
        assertThat(store.getSession("s")!!.ragOptions!!.enableMemory).isFalse()
    }

    private fun messageManager(store: ChatStore): MessageManager = mockk<MessageManager>(relaxed = true).also { messages ->
        coEvery { messages.addMessage(any(), any()) } coAnswers {
            val id = firstArg<String>(); val message = secondArg<Message>()
            store.updateSession(id) { it.copy(messages = it.messages + message) }
        }
    }

    private fun runtime(store: ChatStore, messages: MessageManager, coordinator: GenerationCoordinator,
        foreground: GenerationForegroundController, bridge: SubagentCompletionSteering? = null,
        findSession: suspend (String) -> Session? = { Session(it, "a") },
        notify: suspend (SystemTurnRequest, SystemTurnOutcome, String?) -> Unit,
    ) = ApplicationSystemTurnRuntime(MutableStateFlow(BackupStartupState.Ready), store,
        findSession = findSession, exists = { true }, hasApproval = { false },
        messages, coordinator, foreground, GenerationInteractionStore(), SessionExecutionGate(), notify, { "未完成" },
        subagentCompletions = bridge)

    private fun TestScope.completionCoordinator(store: ChatStore, generated: MutableList<GenerationRequest>) =
        DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { request, emit ->
                generated += request
                store.updateMessageInSession(request.sessionId, request.assistantMessageId) { it.copy(content = "已处理子代理结论") }
                emit(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
            }
        })

    private fun persistedCompletion() = Message(
        "persisted-completion", MessageRole.USER,
        SystemTriggerMessage.compose(completionRequest().copy(deliveryIds = listOf("sa_notice:1")), OffsetDateTime.parse("2026-10-28T10:00:00+08:00")),
        name = SystemTriggerMessage.MESSAGE_NAME,
    )

    private fun completionRequest() = SystemTurnRequest("s", SystemTurnKind.SUBAGENT_DONE, "sa_notice", "子代理", "子代理结论", sequence = 1)

    private fun request() = SystemTurnRequest("s", SystemTurnKind.SCHEDULE, "task", "任务", "检查")
}
