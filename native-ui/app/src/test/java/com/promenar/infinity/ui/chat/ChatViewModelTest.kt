package com.promenar.infinity.ui.chat

import com.promenar.infinity.data.workspace.isWorkspaceSwitchNotice
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.remote.protocol.*
import com.promenar.infinity.data.remote.ProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ProviderResolutionError
import com.promenar.infinity.data.remote.ResolvedProviderModel
import com.promenar.infinity.data.remote.UnifiedLlmClient
import com.promenar.infinity.data.remote.UnifiedProviderConfig
import com.promenar.infinity.data.remote.provider.LlmProvider
import com.promenar.infinity.data.repository.IMessageRepository
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.repository.ApprovalCallCandidate
import com.promenar.infinity.data.repository.ToolApprovalRequestFactory
import com.promenar.infinity.data.repository.ToolInvocationIdentityFactory
import com.promenar.infinity.data.repository.ToolInvocationIdentityResolution
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.backup.BackupContent
import com.promenar.infinity.data.backup.BackupDataSource
import com.promenar.infinity.data.backup.BackupRuntime
import com.promenar.infinity.data.backup.BackupSnapshot
import com.promenar.infinity.data.backup.ValidatedBackup
import com.promenar.infinity.data.generation.ChatProviderRouteGate
import com.promenar.infinity.data.document.DocumentReadResult
import com.promenar.infinity.data.document.FullContextDocumentReader
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.repository.IAgentRepository
import com.promenar.infinity.domain.usecase.AgentConfigResolver
import com.promenar.infinity.ui.chat.manager.ContextBuilderResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

private const val BACKGROUND_UNAVAILABLE_MESSAGE = "background generation unavailable"
private const val NOTIFICATIONS_UNAVAILABLE_MESSAGE = "notifications unavailable"
private const val BACKGROUND_SERVICE_STOPPED_MESSAGE = "background service stopped"

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestInfinityApplication::class, sdk = [33])
class ChatViewModelTest {
    @Test
    @org.robolectric.annotation.Config(sdk = [34])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `失效画板预览不能恢复编辑或发送且失败还原草稿`() = runTest {
        seedSession("annotation-invalid"); advanceUntilIdle()
        val session = requireNotNull(viewModel.uiState.value.session)
        val annotation = com.promenar.infinity.domain.media.ImageAnnotation(
            com.promenar.infinity.domain.media.ImageAnnotationKind.SKETCH, 4, 4, "/sketch.png")
        val png = java.io.ByteArrayOutputStream().also { output ->
            android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
            }
        }.toByteArray()
        val file = java.io.File.createTempFile("infinity-annotation-invalid", ".png")
        try {
            for (context in listOf(null, com.promenar.infinity.data.media.annotation.ImageAnnotationContext.from(session.copy(workspaceId = "other")))) {
                file.writeBytes(com.promenar.infinity.data.media.annotation.ImageAnnotationPreviewCodec.embed(png, annotation, context))
                val uri = android.net.Uri.fromFile(file)
                assertThat(viewModel.readImageAnnotation(uri)).isNull()
                viewModel.sendMessage("手绘草稿", listOf(uri)); advanceUntilIdle()
                awaitReal { viewModel.uiState.value.status == GenerationStatus.ERROR }
                assertThat(savedMessages).isEmpty()
                assertThat(protocolRequestCount).isEqualTo(0)
                assertThat(viewModel.restoredDraftImages.value).contains(uri.toString())
            }
        } finally { file.delete() }
    }

    @Test
    @org.robolectric.annotation.Config(sdk = [34])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `画板预览经权限等待持久化并在重试时保留标注`() = runTest {
        notificationPermissionState = com.promenar.infinity.background.generation.NotificationPermissionPromptState(
            sdkInt = 35, granted = false, alreadyAsked = false)
        seedSession("annotation-permission"); advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("完成"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
        val annotation = com.promenar.infinity.domain.media.ImageAnnotation(
            com.promenar.infinity.domain.media.ImageAnnotationKind.MARKUP, 4, 4, "/layer.png", "/source.png")
        val image = java.io.File.createTempFile("infinity-annotation", ".png")
        try {
            val output = java.io.ByteArrayOutputStream()
            android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
            }
            image.writeBytes(com.promenar.infinity.data.media.annotation.ImageAnnotationPreviewCodec.embed(output.toByteArray(), annotation))
            val context = com.promenar.infinity.data.media.annotation.ImageAnnotationContext.from(
                requireNotNull(viewModel.uiState.value.session))
            image.writeBytes(com.promenar.infinity.data.media.annotation.ImageAnnotationPreviewCodec.embed(output.toByteArray(), annotation, context))
            assertThat(viewModel.readImageAnnotation(android.net.Uri.fromFile(image))).isEqualTo(annotation)
            viewModel.sendMessage("调整圈出部分", listOf(android.net.Uri.fromFile(image))); advanceUntilIdle()
            // 图片在真实 IO 线程上转码，等到权限请求出现后再继续。
            awaitReal { viewModel.notificationPermissionRequests.value != null }
            assertThat(savedMessages).isEmpty()
            val token = requireNotNull(viewModel.notificationPermissionRequests.value).taskId
            notificationPermissionState = notificationPermissionState!!.copy(alreadyAsked = true)
            viewModel.onNotificationPermissionResult(token, granted = false); advanceUntilIdle()
            awaitReal { protocolRequestCount == 1 && !viewModel.uiState.value.isGenerating }
            val user = savedMessages.map { it.first }.single { it.role == MessageRole.USER }
            assertThat(user.userImageAnnotations).containsExactly(annotation)
            assertThat(user.userImageAnnotationContext).isEqualTo(context)
            assertThat(user.userImages!!.single()).startsWith("data:image/jpeg;base64,")
            assertThat(lastPromptRequest!!.messages.last { it.role == "user" }.content).contains("markup=\"/layer.png\"")
            val assistant = viewModel.uiState.value.messages.last { it.role == MessageRole.ASSISTANT }
            viewModel.regenerateMessage(assistant.id); advanceUntilIdle()
            awaitReal { protocolRequestCount == 2 && !viewModel.uiState.value.isGenerating }
            assertThat(lastPromptRequest!!.messages.last { it.role == "user" }.content).contains("markup=\"/layer.png\"")
        } finally { image.delete() }
    }

    @Test fun `当前会话限时授权流随切页切换且撤销只影响当前会话`() = runTest {
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        app.timedApprovalGrants.grant("timed-a", com.promenar.infinity.domain.tool.ApprovalGrantCategory.SKILLS)
        app.timedApprovalGrants.grant("timed-b", com.promenar.infinity.domain.tool.ApprovalGrantCategory.COMMAND)
        seedSession("timed-a"); advanceUntilIdle()
        assertThat(viewModel.activeTimedGrants.value.keys).containsExactly(com.promenar.infinity.domain.tool.ApprovalGrantCategory.SKILLS)
        seedSession("timed-b"); advanceUntilIdle()
        assertThat(viewModel.activeTimedGrants.value.keys).containsExactly(com.promenar.infinity.domain.tool.ApprovalGrantCategory.COMMAND)
        viewModel.revokeTimedGrants(); advanceUntilIdle()
        assertThat(viewModel.activeTimedGrants.value).isEmpty()
        assertThat(app.timedApprovalGrants.observe("timed-a").value).isNotEmpty()
        viewModel.approveWithTimedGrant(ApprovalRequest())
        advanceUntilIdle()
        assertThat(viewModel.activeTimedGrants.value).isEmpty()
    }

    @Test fun `已受理的回答续跑快照不会重复派发账本查询`() = runTest {
        val question = seedAskUser("active-question")
        seedSession("active-question-idle")
        awaitReal { viewModel.uiState.value.session?.id == "active-question-idle" }
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        val continuation = app.askUserLedger.answer(question,
            com.promenar.infinity.domain.ask.AskUserAnswer(skipped = true))!!
        val observed = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        val queries = java.util.concurrent.atomic.AtomicInteger()
        val resumptions = app.askUserLedger.observeResumption(question.sessionId).onEach { observed.complete(Unit) }
        val ledger = io.mockk.spyk(app.askUserLedger)
        io.mockk.every { ledger.observeResumption(question.sessionId) } returns resumptions
        io.mockk.coEvery { ledger.resumption(question.sessionId) } coAnswers {
            queries.incrementAndGet()
            releaseQuery.await()
            continuation
        }
        ChatViewModel::class.java.getDeclaredField("askUserLedger").apply {
            isAccessible = true
            set(viewModel, ledger)
        }
        val states = kotlinx.coroutines.flow.MutableStateFlow<com.promenar.infinity.domain.generation.GenerationTaskSnapshot?>(
            com.promenar.infinity.domain.generation.GenerationTaskSnapshot("active-resumption", question.sessionId,
                continuation.assistant.id, com.promenar.infinity.domain.generation.GenerationPhase.STREAMING, 0, 0),
        )
        val coordinator = object : com.promenar.infinity.domain.generation.GenerationCoordinator by generationCoordinator {
            override fun observe(sessionId: String) = if (sessionId == question.sessionId) states else generationCoordinator.observe(sessionId)
        }
        ChatViewModel::class.java.getDeclaredField("generationCoordinator").apply {
            isAccessible = true
            set(viewModel, coordinator)
        }
        try {
            viewModel.loadSession(question.sessionId)
            awaitReal { observed.isCompleted }
            assertThat(observed.isCompleted).isTrue()
            repeat(20) { index ->
                states.value = states.value!!.copy(generatedChars = index + 1)
                runCurrent()
            }
            assertThat(queries.get()).isEqualTo(0)
        } finally {
            releaseQuery.complete(Unit)
        }
    }

    @Test fun `切页自动续跑不等待其它已回答会话的资源收尾`() = runTest {
        val first = seedAskUser("auto-cleanup-a")
        val second = seedAskUser("auto-cleanup-b")
        seedSession("auto-cleanup-idle")
        awaitReal { viewModel.uiState.value.session?.id == "auto-cleanup-idle" }
        val delegate = generationCoordinator
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val waiting = kotlinx.coroutines.flow.MutableStateFlow<com.promenar.infinity.domain.generation.GenerationTaskSnapshot?>(
            com.promenar.infinity.domain.generation.GenerationTaskSnapshot("auto-cleanup-task", first.sessionId,
                first.assistantMessageId, com.promenar.infinity.domain.generation.GenerationPhase.WAITING_INPUT, 0, 0),
        )
        val coordinator = object : com.promenar.infinity.domain.generation.GenerationCoordinator by delegate {
            override fun observe(sessionId: String) = if (sessionId == first.sessionId) waiting else delegate.observe(sessionId)
            override suspend fun awaitSessionIdle(sessionId: String) {
                if (sessionId == first.sessionId) {
                    cleanupStarted.complete(Unit)
                    cleanup.await()
                } else delegate.awaitSessionIdle(sessionId)
            }
        }
        ChatViewModel::class.java.getDeclaredField("generationCoordinator").apply {
            isAccessible = true
            set(viewModel, coordinator)
        }
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        assertThat(app.askUserLedger.answer(first, com.promenar.infinity.domain.ask.AskUserAnswer(skipped = true))).isNotNull()
        assertThat(app.askUserLedger.answer(second, com.promenar.infinity.domain.ask.AskUserAnswer(other = "自动恢复回答"))).isNotNull()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("自动完成"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
        viewModel.loadSession(first.sessionId)
        try {
            awaitReal { cleanupStarted.isCompleted }
            assertThat(cleanupStarted.isCompleted).isTrue()
            viewModel.loadSession(second.sessionId)
            awaitReal { protocolRequestCount == 1 }
            assertThat(protocolRequestCount).isEqualTo(1)
            assertThat(lastPromptRequest!!.messages.first { it.role == "tool" && it.toolCallId == second.toolCallId }.content)
                .contains("自动恢复回答")
        } finally {
            waiting.value = null
            cleanup.complete(Unit)
        }
    }

    @Test fun `回答续跑不等待其它会话的提问资源收尾`() = runTest {
        val first = seedAskUser("question-cleanup-a")
        val second = seedAskUser("question-cleanup-b")
        val delegate = generationCoordinator
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val waiting = kotlinx.coroutines.flow.MutableStateFlow<com.promenar.infinity.domain.generation.GenerationTaskSnapshot?>(
            com.promenar.infinity.domain.generation.GenerationTaskSnapshot("cleanup-task", first.sessionId,
                first.assistantMessageId, com.promenar.infinity.domain.generation.GenerationPhase.WAITING_INPUT, 0, 0),
        )
        val coordinator = object : com.promenar.infinity.domain.generation.GenerationCoordinator by delegate {
            override fun observe(sessionId: String) = if (sessionId == first.sessionId) waiting else delegate.observe(sessionId)
            override suspend fun awaitSessionIdle(sessionId: String) {
                if (sessionId == first.sessionId) {
                    cleanupStarted.complete(Unit)
                    cleanup.await()
                } else delegate.awaitSessionIdle(sessionId)
            }
        }
        ChatViewModel::class.java.getDeclaredField("generationCoordinator").apply {
            isAccessible = true
            set(viewModel, coordinator)
        }
        fakeStreamChunks = listOf(StreamChunk.TextDelta("回答完成"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
        viewModel.loadSession(first.sessionId)
        awaitReal { viewModel.pendingQuestion.value == first }
        try {
            assertThat(viewModel.answerQuestion(first, com.promenar.infinity.domain.ask.AskUserAnswer(skipped = true))).isTrue()
            awaitReal { cleanupStarted.isCompleted }
            assertThat(cleanupStarted.isCompleted).isTrue()
            viewModel.loadSession(second.sessionId)
            awaitReal { viewModel.pendingQuestion.value == second }
            assertThat(viewModel.answerQuestion(second, com.promenar.infinity.domain.ask.AskUserAnswer(other = "独立回答"))).isTrue()
            awaitReal { protocolRequestCount == 1 }
            assertThat(protocolRequestCount).isEqualTo(1)
            assertThat(lastPromptRequest!!.messages.first { it.role == "tool" && it.toolCallId == second.toolCallId }.content)
                .contains("独立回答")
        } finally {
            waiting.value = null
            cleanup.complete(Unit)
        }
    }

    @Test fun `名额占满时回答提问只排队一次且空位后续跑`() = runTest {
        holdStreamOpen = true
        fakeStreamChunks = listOf(StreamChunk.TextDelta("处理中"))
        repeat(com.promenar.infinity.domain.generation.MAX_CONCURRENT_GENERATIONS) { index ->
            val id = "question-busy-$index"
            seedSession(id, messages = listOf(
                Message("u-$id", MessageRole.USER, "占用名额"),
                Message("a-$id", MessageRole.ASSISTANT, ""),
            ))
            generationCoordinator.start(com.promenar.infinity.domain.generation.GenerationRequest(
                id, "a-$id", "u-$id", "占用名额", emptyList(),
                com.promenar.infinity.domain.generation.GenerationRuntimePolicy.BACKGROUND_ALLOWED,
            ))
        }
        awaitReal { protocolRequestCount == com.promenar.infinity.domain.generation.MAX_CONCURRENT_GENERATIONS }
        val question = seedAskUser("question-queued")
        assertThat(viewModel.answerQuestion(question, com.promenar.infinity.domain.ask.AskUserAnswer(other = "采用甲方案"))).isTrue()
        awaitReal { generationCoordinator.observe(question.sessionId).value?.phase == com.promenar.infinity.domain.generation.GenerationPhase.QUEUED }
        val queued = generationCoordinator.observe(question.sessionId).value!!
        assertThat(protocolRequestCount).isEqualTo(com.promenar.infinity.domain.generation.MAX_CONCURRENT_GENERATIONS)
        assertThat(viewModel.answerQuestion(question, com.promenar.infinity.domain.ask.AskUserAnswer(skipped = true))).isFalse()
        viewModel.loadSession("question-busy-1")
        viewModel.loadSession(question.sessionId)
        runCurrent()
        assertThat(generationCoordinator.observe(question.sessionId).value?.taskId).isEqualTo(queued.taskId)
        assertThat(generationCoordinator.sessions.value.values.count { it.sessionId == question.sessionId }).isEqualTo(1)
        generationCoordinator.cancel(generationCoordinator.observe("question-busy-0").value!!.taskId)
        awaitReal { protocolRequestCount == com.promenar.infinity.domain.generation.MAX_CONCURRENT_GENERATIONS + 1 }
        assertThat(generationCoordinator.observe(question.sessionId).value?.phase)
            .isEqualTo(com.promenar.infinity.domain.generation.GenerationPhase.STREAMING)
        assertThat(lastPromptRequest!!.messages.first { it.role == "tool" && it.toolCallId == question.toolCallId }.content)
            .contains("采用甲方案")
        assertThat(viewModel.uiState.value.error).isNull()
    }

    private suspend fun TestScope.seedAskUser(id: String = "ask-vm"): com.promenar.infinity.domain.ask.PendingQuestion {
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        val call = ToolCall("$id-call", "ask_user", """{"questions":[{"header":"方案","question":"选哪个？","options":[{"label":"甲"},{"label":"乙"}]}]}""")
        val user = Message("$id-user", MessageRole.USER, "处理任务", createdAt = 1)
        val assistant = Message("$id-assistant", MessageRole.ASSISTANT, "", parentMessageId = user.id, toolCalls = listOf(call), createdAt = 2)
        val session = Session(id, "a1", modelId = "gpt-4o", workspaceRootUuid = "test-workspace-root", workspacePath = "/test/workspace", messages = listOf(user, assistant))
        app.database.sessionDao().insert(session.toEntity())
        app.database.messageDao().insert(user.toEntity(id))
        app.database.messageDao().insert(assistant.toEntity(id))
        val tool = com.promenar.infinity.ui.chat.manager.AskUserTool()
        val pending = app.askUserLedger.createBatch(id, assistant.id, listOf(call),
            com.promenar.infinity.data.remote.protocol.ProtocolTool(function = ProtocolToolFunction(tool.name, tool.description, tool.parametersSchema),
                runtimeToolId = tool.runtimeToolId, sourceId = tool.sourceId, risk = tool.risk)).pending!!
        val restored = app.database.sessionDao().getById(id)!!.toDomain().copy(messages = listOf(user, assistant))
        savedSessions += restored
        savedMessages += listOf(user to id, assistant to id)
        app.chatStore.upsertSession(restored)
        viewModel.loadSession(id)
        awaitReal { viewModel.pendingQuestion.value == pending }
        return pending
    }

    @Test fun `待回答接口拒绝非法过期与重复回答并只续跑一次`() = runTest {
        val question = seedAskUser()
        assertThat(viewModel.uiState.value.isGenerating).isFalse()
        assertThat(viewModel.answerQuestion(question.copy(toolCallId = "stale"), com.promenar.infinity.domain.ask.AskUserAnswer(skipped = true))).isFalse()
        assertThat(viewModel.answerQuestion(question, com.promenar.infinity.domain.ask.AskUserAnswer(mapOf("未知" to listOf("甲"))))).isFalse()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("回答已收到"), StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
        assertThat(viewModel.answerQuestion(question, com.promenar.infinity.domain.ask.AskUserAnswer(other = "自由回答"))).isTrue()
        assertThat(viewModel.answerQuestion(question, com.promenar.infinity.domain.ask.AskUserAnswer(skipped = true))).isFalse()
        awaitReal { protocolRequestCount == 1 && !viewModel.uiState.value.isGenerating }
        assertThat(protocolRequestCount).isEqualTo(1)
        assertThat(viewModel.pendingQuestion.value).isNull()
        assertThat(lastPromptRequest!!.messages.first { it.role == "tool" && it.toolCallId == question.toolCallId }.content).contains("自由回答")
        assertThat(viewModel.answerQuestion(question, com.promenar.infinity.domain.ask.AskUserAnswer(skipped = true))).isFalse()
    }

    @Test fun `待回答保存失败通过错误通道提示且可重试`() = runTest {
        val question = seedAskUser()
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        app.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_vm_answer BEFORE INSERT ON messages WHEN NEW.role = 'tool' BEGIN SELECT RAISE(ABORT, 'test write failure'); END")
        assertThat(viewModel.answerQuestion(question, com.promenar.infinity.domain.ask.AskUserAnswer(skipped = true))).isTrue()
        awaitReal { viewModel.uiState.value.error != null }
        assertThat(viewModel.uiState.value.error).isNotNull()
        assertThat(viewModel.pendingQuestion.value).isEqualTo(question)
        app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_vm_answer")
        assertThat(viewModel.answerQuestion(question, com.promenar.infinity.domain.ask.AskUserAnswer(skipped = true))).isTrue()
        awaitReal { viewModel.pendingQuestion.value == null }
    }

    @Test fun `切换会话隔离待回答状态并可从数据库还原`() = runTest {
        val first = seedAskUser("ask-one")
        val second = seedAskUser("ask-two")
        assertThat(viewModel.pendingQuestion.value).isEqualTo(second)
        assertThat(viewModel.answerQuestion(first, com.promenar.infinity.domain.ask.AskUserAnswer(skipped = true))).isFalse()
        viewModel.loadSession("ask-one")
        awaitReal { viewModel.pendingQuestion.value == first }
        assertThat(viewModel.pendingQuestion.value).isEqualTo(first)
    }

    @Test fun `停止待回答清除问题并将工具调用以失败收口`() = runTest {
        val question = seedAskUser()
        viewModel.stopGeneration()
        awaitReal { viewModel.pendingQuestion.value == null }
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        assertThat(app.database.messageDao().getBySession(question.sessionId).first { it.toolCallId == question.toolCallId && it.role == "tool" }.content)
            .contains("用户未回答并停止了本轮")
        assertThat(protocolRequestCount).isEqualTo(0)
    }

    @Test fun `停止清理获得准备锁后取消刚登记的回答续跑`() = runTest {
        seedSession(messages = listOf(
            Message("resume-user", MessageRole.USER, "处理任务"),
            Message("ask_resume_test", MessageRole.ASSISTANT, "", parentMessageId = "resume-user"),
        ))
        advanceUntilIdle()
        val preparation = ChatViewModel::class.java.getDeclaredField("sendPreparationMutexes").run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (get(viewModel) as java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>)
                .getOrPut("s1") { kotlinx.coroutines.sync.Mutex() }
        }
        val cleaned = CompletableDeferred<Unit>()
        beforeSshClose = { cleaned.complete(Unit) }
        holdStreamOpen = true
        fakeStreamChunks = listOf(StreamChunk.TextDelta("正在续跑"))
        preparation.lock()
        try {
            viewModel.stopGeneration()
            generationCoordinator.start(com.promenar.infinity.domain.generation.GenerationRequest(
                "s1", "ask_resume_test", "resume-user", "处理任务", emptyList(),
                com.promenar.infinity.domain.generation.GenerationRuntimePolicy.BACKGROUND_ALLOWED,
            ))
            awaitReal { protocolRequestCount == 1 }
            ApplicationProvider.getApplicationContext<InfinityApplication>().chatStore.updateSession("s1") {
                it.copy(loopStatus = LoopStatus.RUNNING)
            }
        } finally {
            preparation.unlock()
        }
        awaitReal { cleaned.isCompleted && generationCoordinator.active.value == null }

        assertThat(cleaned.isCompleted).isTrue()
        // 并发后取消经各自协程传播，不再调用共享 Provider 的全局取消；以本会话任务的终态为准。
        assertThat(generationCoordinator.observe("s1").value?.phase)
            .isAnyOf(null, com.promenar.infinity.domain.generation.GenerationPhase.CANCELLED)
        assertThat(generationCoordinator.active.value).isNull()
    }

    @Test fun `回答续跑持久化失败后重新打开会话可以重试且不循环`() = runTest {
        val question = seedAskUser()
        failQuestionResultUpdate = true
        fakeStreamChunks = listOf(StreamChunk.TextDelta("回答已收到"), StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
        assertThat(viewModel.answerQuestion(question, com.promenar.infinity.domain.ask.AskUserAnswer(skipped = true))).isTrue()
        awaitReal { generationCoordinator.observe(question.sessionId).value?.phase == com.promenar.infinity.domain.generation.GenerationPhase.PERSISTENCE_FAILED }
        assertThat(generationCoordinator.observe(question.sessionId).value?.phase)
            .isEqualTo(com.promenar.infinity.domain.generation.GenerationPhase.PERSISTENCE_FAILED)
        assertThat(protocolRequestCount).isEqualTo(1)
        assertThat(lastPromptRequest!!.messages.first { it.role == "user" }.turnContext).isNull()

        failQuestionResultUpdate = false
        seedSession("other-session")
        advanceUntilIdle()
        viewModel.loadSession(question.sessionId)
        awaitReal { protocolRequestCount == 2 && generationCoordinator.active.value == null }

        assertThat(protocolRequestCount).isEqualTo(2)
        assertThat(lastPromptRequest!!.messages.first { it.role == "user" }.turnContext).isNull()
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        assertThat(app.askUserLedger.resumption(question.sessionId)).isNull()
        assertThat(app.database.messageDao().getBySession(question.sessionId).count { it.role == "tool" && it.toolCallId == question.toolCallId }).isEqualTo(1)
    }
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: ChatViewModel
    private lateinit var presentationStore: com.promenar.infinity.data.generation.GenerationPresentationStore
    private lateinit var queueStore: com.promenar.infinity.data.chat.queue.FileComposerQueueStore
    private var claimFailure: Throwable? = null
    private var editGate: CompletableDeferred<Unit>? = null
    private var queueEnqueueGate: CompletableDeferred<Unit>? = null
    /** 模拟真机写盘时序：停止写入 STOPPED 被推迟到终态处理读过快照之后，终态的暂停写入再晚于它落盘。 */
    private var stoppedPauseGate: CompletableDeferred<Unit>? = null
    private var stoppedPauseDone: CompletableDeferred<Unit>? = null
    private var beforeQueueImageOpen: () -> Unit = {}
    private lateinit var queuePort: com.promenar.infinity.data.chat.queue.ComposerQueueStore
    private lateinit var recoveryStore: com.promenar.infinity.data.chat.queue.ComposerDraftRecoveryStore
    private var streamGate: CompletableDeferred<List<StreamChunk>>? = null
    private lateinit var generationScope: CoroutineScope
    private lateinit var generationCoordinator: com.promenar.infinity.domain.generation.GenerationCoordinator
    private lateinit var compactionService: com.promenar.infinity.data.generation.ContextCompactionService
    private var contextEstimateFails = false
    private var documentSaveGate: CompletableDeferred<Unit>? = null
    private var compactionOperation: suspend (String) -> com.promenar.infinity.data.generation.ContextCompactionResult = {
        com.promenar.infinity.data.generation.ContextCompactionResult(PromptRequest(model = "model", messages = emptyList()), 0, 0, 0, 0)
    }
    private val foregroundTrackedTasks = mutableListOf<
        com.promenar.infinity.domain.generation.GenerationTaskSnapshot
    >()
    private var foregroundStartResult:
        com.promenar.infinity.background.generation.ForegroundStartResult =
        com.promenar.infinity.background.generation.ForegroundStartResult.StartedWithNotifications
    private val foregroundFailures = kotlinx.coroutines.flow.MutableSharedFlow<
        com.promenar.infinity.background.generation.ForegroundServiceFailure
    >(extraBufferCapacity = 4)
    private var notificationPermissionState:
        com.promenar.infinity.background.generation.NotificationPermissionPromptState? = null

    private val kgPath = KgPath(
        queryKeywords = listOf("Infinity Deck"),
        nodes = listOf(KgNode("n1", "Infinity Deck", "project")),
        edges = emptyList(),
    )

    @Test
    fun mergeToolCallsByIdKeepsStableOrderAndLatestCompletePayload() {
        val merged = mergeToolCallsById(
            existing = listOf(
                ToolCall("same", "", ""),
                ToolCall("safe", "read_file", "{}"),
            ),
            incoming = listOf(
                ToolCall("same", "write_file", "{\"path\":\"a\"}"),
                ToolCall("safe", "read_file", "{}"),
            ),
        )

        assertThat(merged.map { it.id }).containsExactly("same", "safe").inOrder()
        assertThat(merged.first()).isEqualTo(
            ToolCall("same", "write_file", "{\"path\":\"a\"}"),
        )
    }

    @Test
    fun fallbackToolCallIdIsStableForTheSameLogicalCall() {
        val first = stableFallbackToolCallId("fallback", "write_file", "{\"path\":\"a\"}", 0)
        val rebuilt = stableFallbackToolCallId("fallback", "write_file", "{\"path\":\"a\"}", 0)
        val different = stableFallbackToolCallId("fallback", "write_file", "{\"path\":\"b\"}", 0)

        assertThat(rebuilt).isEqualTo(first)
        assertThat(different).isNotEqualTo(first)
    }

    @Test
    fun contextResultWithOnlyKgPathsKeepsRagStateAndBuildsKgUpdate() {
        val result = ContextBuilderResult(
            searchContext = "",
            finalSystemPrompt = "system",
            ragContext = "",
            citations = emptyList(),
            ragReferences = emptyList(),
            ragUsage = null,
            kgPaths = listOf(kgPath),
        )

        assertThat(result.hasPersistableRagContext()).isTrue()
        val options = result.toMessageRagUpdateOptions()
        assertThat(options).isNotNull()
        assertThat(options!!.kgPaths).containsExactly(kgPath)
        assertThat(options.ragReferences).isNull()
        assertThat(options.citations).isNull()
        assertThat(options.ragMetadata).isNull()
    }

    @Test
    fun contextResultWithReferencesAndKgPathsBuildsOneCompleteUpdate() {
        val reference = RagReference(id = "r1", content = "content", source = "doc", score = 0.9f)
        val result = ContextBuilderResult(
            searchContext = "",
            finalSystemPrompt = "system",
            ragContext = "context",
            citations = emptyList(),
            ragReferences = listOf(reference),
            ragUsage = RagUsage(ragSystem = 12),
            kgPaths = listOf(kgPath),
        )

        val options = result.toMessageRagUpdateOptions()
        assertThat(options).isNotNull()
        assertThat(options!!.ragReferences).containsExactly(reference)
        assertThat(options.kgPaths).containsExactly(kgPath)
        assertThat(options.ragMetadata?.chunkCount).isEqualTo(1)
        assertThat(options.ragMetadata?.totalTokens).isEqualTo(12)
    }

    @Test
    fun retrievalWithoutHitsStillPersistsTraceSoTheReplyShowsNoMatch() {
        val trace = com.promenar.infinity.data.model.RagRetrievalTrace(
            outcome = com.promenar.infinity.data.model.RagRetrievalTrace.OUTCOME_EMPTY,
            searchTimeMs = 42,
        )
        val result = ContextBuilderResult(
            searchContext = "",
            finalSystemPrompt = "system",
            ragContext = "",
            citations = emptyList(),
            ragReferences = emptyList(),
            ragUsage = null,
            ragTrace = trace,
        )

        assertThat(result.hasPersistableRagContext()).isTrue()
        val metadata = result.toMessageRagUpdateOptions()?.ragMetadata
        assertThat(metadata?.trace).isEqualTo(trace)
        assertThat(metadata?.chunkCount).isEqualTo(0)
        assertThat(metadata?.retrievalTimeMs).isEqualTo(42)
    }

    @Test
    fun emptyKgPathsDoNotCreateMessageUpdateOrFakeRagMetadata() {
        val result = ContextBuilderResult(
            searchContext = "",
            finalSystemPrompt = "system",
            ragContext = "",
            citations = emptyList(),
            ragReferences = emptyList(),
            ragUsage = null,
            kgPaths = emptyList(),
        )

        assertThat(result.hasPersistableRagContext()).isFalse()
        assertThat(result.toMessageRagUpdateOptions()).isNull()
    }

    @Test
    fun applicationDoesNotKeepCredentialBearingProviderStateFlow() {
        assertThat(InfinityApplication::class.java.declaredFields.map { it.name })
            .doesNotContain("_llmProvider")
    }

    private val savedSessions = mutableListOf<Session>()
    private val savedMessages = mutableListOf<Pair<Message, String>>()
    private val deletedMessages = mutableListOf<String>()
    private var failScopedDelete = false
    private var failSessionUpdate: Throwable? = null
    private var failQuestionResultUpdate = false
    private var afterInsert: suspend (Message, String) -> Unit = { _, _ -> }
    private val sshLeases = linkedSetOf<String>()
    private val pausedSshOwners = linkedSetOf<String>()
    private var beforeSshClose: suspend () -> Unit = {}
    private var holdSessionUpdates: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private var holdDraftUpdates: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private var beforeDraftUpdate: suspend (String, String?) -> Unit = { _, _ -> }

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {
            savedSessions.add(session)
        }

        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {
            failSessionUpdate?.let { throw it }
            if (updates.containsKey("draft")) {
                beforeDraftUpdate(id, updates["draft"] as String?)
                holdDraftUpdates?.await()
            }
            // 真实 Room 写入会挂起；测试用闸门模拟写入途中 ViewModel 被清理。
            holdSessionUpdates?.await()
            val index = savedSessions.indexOfFirst { it.id == id }
            if (index != -1) {
                var session = savedSessions[index]
                if (updates.containsKey("title")) {
                    session = session.copy(title = updates["title"] as String)
                }
                if (updates.containsKey("stats")) {
                    session = session.copy(stats = updates["stats"] as SessionStats?)
                }
                if (updates.containsKey("draft")) {
                    session = session.copy(draft = updates["draft"] as String?)
                }
                if (updates.containsKey("modelId")) {
                    session = session.copy(modelId = updates["modelId"] as String?)
                }
                if (updates.containsKey("customPrompt")) {
                    session = session.copy(customPrompt = updates["customPrompt"] as String?)
                }
                if (updates.containsKey("options")) {
                    session = session.copy(options = updates["options"] as SessionOptions)
                }
                if (updates.containsKey("approvalRequest")) {
                    session = session.copy(approvalRequest = updates["approvalRequest"] as ApprovalRequest?)
                }
                if (updates.containsKey("loopStatus")) {
                    session = session.copy(loopStatus = updates["loopStatus"] as LoopStatus)
                }
                savedSessions[index] = session
            }
        }
        override suspend fun delete(id: String) {
            savedSessions.removeAll { it.id == id }
        }
        override suspend fun getById(id: String): Session? = savedSessions.find { it.id == id }
        override suspend fun getAll(): List<Session> = savedSessions.toList()
    }

    private val stubMessageRepo = object : IMessageRepository {
        override suspend fun insert(message: Message, sessionId: String) {
            savedMessages.add(message to sessionId)
            // Also update the stubbed session
            savedSessions.find { it.id == sessionId }?.let { session ->
                val updated = session.copy(messages = session.messages + message)
                savedSessions.removeIf { it.id == sessionId }
                savedSessions.add(updated)
            }
            afterInsert(message, sessionId)
        }

        override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {
            // 仅拒绝回答结果，允许请求指令与模型身份在发送前落库。
            if (failQuestionResultUpdate && messageId.startsWith("ask_resume_") && "content" in updates) {
                throw java.io.IOException("test write failure")
            }
            val index = savedMessages.indexOfFirst { it.first.id == messageId }
            if (index != -1) {
                val (msg, sid) = savedMessages[index]
                var updatedMsg = msg
                if (updates.containsKey("content")) {
                    updatedMsg = updatedMsg.copy(content = updates["content"] as String)
                }
                if (updates.containsKey("reasoning")) {
                    updatedMsg = updatedMsg.copy(reasoning = updates["reasoning"] as String?)
                }
                if (updates.containsKey("status")) {
                    updatedMsg = updatedMsg.copy(status = updates["status"] as String?)
                }
                if (updates.containsKey("tokens")) {
                    updatedMsg = updatedMsg.copy(tokens = updates["tokens"] as TokenUsage)
                }
                if (updates.containsKey("citations")) {
                    @Suppress("UNCHECKED_CAST")
                    updatedMsg = updatedMsg.copy(citations = updates["citations"] as List<Citation>)
                }
                if (updates.containsKey("toolCalls")) {
                    @Suppress("UNCHECKED_CAST")
                    updatedMsg = updatedMsg.copy(toolCalls = updates["toolCalls"] as List<ToolCall>)
                }
                savedMessages[index] = updatedMsg to sid

                // 同步更新 session 中的 messages 缓存
                savedSessions.find { it.id == sid }?.let { session ->
                    val updatedMsgs = session.messages.map { 
                        if (it.id == messageId) updatedMsg else it 
                    }
                    val updatedSession = session.copy(messages = updatedMsgs)
                    savedSessions.removeIf { it.id == sid }
                    savedSessions.add(updatedSession)
                }
            }
        }
        override suspend fun delete(messageId: String) {
            deletedMessages.add(messageId)
            savedMessages.removeIf { it.first.id == messageId }
            savedSessions.forEachIndexed { index, session ->
                savedSessions[index] = session.copy(messages = session.messages.filter { it.id != messageId })
            }
        }
        override suspend fun deleteInSession(sessionId: String, messageId: String): Boolean {
            if (failScopedDelete) throw IllegalStateException("delete blocked")
            if (savedMessages.none { it.second == sessionId && it.first.id == messageId }) return false
            delete(messageId)
            return true
        }

        override suspend fun deleteBySessionId(sessionId: String) {
            savedMessages.removeIf { it.second == sessionId }
        }
        override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) {
            savedMessages.removeIf { it.second == sessionId && it.first.createdAt >= timestamp }
        }
        override suspend fun getById(messageId: String): Message? = savedMessages.find { it.first.id == messageId }?.first
        override suspend fun getBySession(sessionId: String): List<Message> = savedMessages.filter { it.second == sessionId }.map { it.first }
        override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) {}
    }

    private val stubAgentRepo = object : IAgentRepository {
        private val agents = mutableListOf<Agent>()
        var lastGetByIdId: String? = null

        fun seed(agent: Agent) {
            agents.add(agent)
        }

        override fun observeAll() = kotlinx.coroutines.flow.flowOf(agents.toList())
        override fun observeById(id: String) = kotlinx.coroutines.flow.flowOf(agents.find { it.id == id })
        override suspend fun create(agent: Agent) { agents.add(agent) }
        override suspend fun update(agent: Agent) {
            agents.removeAll { it.id == agent.id }
            agents.add(agent)
        }
        override suspend fun delete(id: String) { agents.removeAll { it.id == id } }
        override suspend fun getById(id: String): Agent? {
            lastGetByIdId = id
            return agents.find { it.id == id }
        }
    }

    private var workspaceRootFailure: Exception? = null
    private var workspaceRootGate: CompletableDeferred<Unit>? = null

    private var fakeStreamChunks: List<StreamChunk> = emptyList()
    // 斜杠技能命令：生成运行时从这里扫描并预加载技能。
    private val slashSkillCatalog = com.promenar.infinity.ui.chat.command.StaticSkillCatalog()
    private var protocolRequestCount = 0
    private var lastPromptRequest: PromptRequest? = null
    private var holdStreamOpen = false
    private var providerCancelled = false
    private var forcedProviderFailure: ProviderResolution.Failure? = null

    @Test
    fun createNewSessionInheritsAgentExecutionModeAndOrderedMcpServersButNotLegacySkills() = runTest {
        stubAgentRepo.create(
            Agent(
                id = "approval-agent",
                name = "Approval Agent",
                executionMode = com.promenar.infinity.domain.model.ExecutionMode.WORKSPACE,
                skills = listOf("read_file", "calculator"),
                mcpServerIds = listOf("server-a", "server-b"),
            ),
        )

        viewModel.createNewSession("approval-agent")
        advanceUntilIdle()

        val created = savedSessions.single { it.agentId == "approval-agent" }
        assertThat(created.executionMode).isEqualTo("workspace")
        // 技能只有全局与工作区两层，智能体的旧版技能名单不再复制到新会话。
        assertThat(created.activeSkillIds).isEmpty()
        assertThat(created.activeMcpServerIds).containsExactly("server-a", "server-b").inOrder()
    }

    @Test
    fun `新建会话身份异常展示错误并保留会话且可发送消息`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        workspaceRootFailure = SecurityException("持久身份标记格式无效")
        fakeStreamChunks = listOf(StreamChunk.TextDelta("回复"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))

        viewModel.createNewSession("a1")
        advanceUntilIdle()

        val session = savedSessions.single()
        assertThat(viewModel.uiState.value.session?.id).isEqualTo(session.id)
        assertThat(viewModel.uiState.value.error).isNotEmpty()
        assertThat(session.workspaceRootUuid).isNull()
        viewModel.sendMessage("你好")
        advanceUntilIdle()
        assertThat(savedMessages.map { it.first.content }).containsAtLeast("你好", "回复")
    }

    @Test
    fun `打开缓存会话身份异常展示错误且会话仍被选中`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        val session = Session(id = "cached-no-root", agentId = "a1", createdAt = 1, updatedAt = 1)
        savedSessions.add(session)
        ApplicationProvider.getApplicationContext<InfinityApplication>().chatStore.upsertSession(session)
        workspaceRootFailure = SecurityException("工作区根身份与数据库认领不一致")

        viewModel.loadSession(session.id)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.session?.id).isEqualTo(session.id)
        assertThat(viewModel.uiState.value.error).isNotEmpty()
        assertThat(viewModel.uiState.value.session?.workspaceRootUuid).isNull()
    }

    @Test
    fun `打开未缓存会话身份异常展示错误且会话仍被选中`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        val session = Session(id = "persisted-no-root", agentId = "a1", createdAt = 1, updatedAt = 1)
        savedSessions.add(session)
        workspaceRootFailure = SecurityException("工作区根身份与数据库认领不一致")

        viewModel.loadSession(session.id)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.session?.id).isEqualTo(session.id)
        assertThat(viewModel.uiState.value.error).isNotEmpty()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `新建会话工作区初始化取消保持取消语义且不显示身份错误`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        workspaceRootFailure = kotlinx.coroutines.CancellationException("取消初始化")
        viewModel.createNewSession("a1")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.session).isNull()
    }

    @Test
    fun `冷加载会话工作区初始化取消不会变成可见错误`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        savedSessions.add(Session(id = "cancelled-load", agentId = "a1", createdAt = 1, updatedAt = 1))
        workspaceRootFailure = kotlinx.coroutines.CancellationException("取消初始化")

        viewModel.loadSession("cancelled-load")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.session).isNull()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `旧会话延迟身份失败不会污染当前会话且关闭提示可清除`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        val session = Session(id = "late-workspace", agentId = "a1", createdAt = 1, updatedAt = 1)
        savedSessions.add(session)
        ApplicationProvider.getApplicationContext<InfinityApplication>().chatStore.upsertSession(session)
        workspaceRootFailure = SecurityException("身份不符")
        val gate = CompletableDeferred<Unit>()
        workspaceRootGate = gate
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        seedSession("healthy-session")
        advanceUntilIdle()

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.session?.id).isEqualTo("healthy-session")
        assertThat(viewModel.uiState.value.error).isNull()

        viewModel.loadSession(session.id)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isNotEmpty()
        viewModel.clearError()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isNull()

        workspaceRootFailure = null
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.session?.workspaceRootUuid).isEqualTo("test-workspace-root")
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `切换健康会话的每次状态发布均不携带上一会话工作区错误`() = runTest {
        val observed = mutableListOf<Pair<String?, String?>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { observed += it.session?.id to it.error }
        }
        workspaceRootFailure = SecurityException("身份不符")
        viewModel.createNewSession("a1")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isNotEmpty()

        workspaceRootFailure = null
        seedSession("healthy-after-failure")
        advanceUntilIdle()

        val healthy = observed.filter { it.first == "healthy-after-failure" }
        assertThat(healthy).isNotEmpty()
        assertThat(healthy.all { it.second == null }).isTrue()
    }

    @Test
    fun `首页和斜杠新建会话初值一致且携带智能体默认工作区`() = runTest {
        val agent = Agent("shared-agent", "智能体", modelId = "local::model",
            defaultWorkspaceId = "ws-shared", skills = listOf("custom"), mcpServerIds = listOf("mcp"),
            executionMode = com.promenar.infinity.domain.model.ExecutionMode.FULL_ACCESS)
        stubAgentRepo.create(agent)
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        val global = com.promenar.infinity.domain.usecase.RagConfigPersistence(app.getSharedPreferences("rag_settings", 0)).loadFullConfig()
        val hubAgents = io.mockk.mockk<com.promenar.infinity.data.repository.AgentRepository>()
        io.mockk.every { hubAgents.observeById(agent.id) } returns kotlinx.coroutines.flow.flowOf(agent)
        val hubRepo = io.mockk.mockk<ISessionRepository>(relaxed = true)
        io.mockk.coEvery { hubRepo.getById(any()) } returns null
        val hubStore = ChatStore()
        val home = com.promenar.infinity.ui.hub.SessionListViewModel(hubStore, hubRepo, hubAgents, globalRagConfig = { global })
        home.createSession(agent.id) {}
        advanceUntilIdle()
        val homeSession = hubStore.get().sessions.single()
        viewModel.createNewSession(agent.id)
        advanceUntilIdle()
        val slashId = viewModel.createSessionForCurrentAgent()!!
        advanceUntilIdle()
        val slash = savedSessions.single { it.id == slashId }
        assertThat(slash.workspaceId).isEqualTo("ws-shared")
        assertThat(slash.copy(id = homeSession.id, createdAt = homeSession.createdAt, updatedAt = homeSession.updatedAt,
            workspacePath = homeSession.workspacePath, workspaceRootUuid = homeSession.workspaceRootUuid)).isEqualTo(homeSession)
    }

    @Test
    fun createNewSessionCopiesAgentDefaultWorkspace() = runTest {
        stubAgentRepo.create(Agent(id = "workspace-agent", name = "Workspace Agent", defaultWorkspaceId = "ws-1"))

        viewModel.createNewSession("workspace-agent")
        advanceUntilIdle()

        assertThat(savedSessions.single { it.agentId == "workspace-agent" }.workspaceId).isEqualTo("ws-1")
    }

    @Test
    fun createNewSessionWithoutAgentFallsBackToSemiAndEmptyToolSelections() = runTest {
        viewModel.createNewSession("missing-approval-agent")
        advanceUntilIdle()

        val created = savedSessions.single { it.agentId == "missing-approval-agent" }
        assertThat(created.executionMode).isEqualTo("workspace")
        assertThat(created.activeSkillIds).isEmpty()
        assertThat(created.activeMcpServerIds).isEmpty()
        assertThat(created.workspaceId).isNull()
    }

    private val fakeProtocol = object : LlmProtocol {
        override val protocolType = ProtocolType.OpenAI_ChatCompletions
        override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
            protocolRequestCount += 1
            lastPromptRequest = request
            val chunks = fakeStreamChunks.toList()
            val gate = streamGate
            return flow {
                for (chunk in chunks) {
                    emit(chunk)
                }
                if (gate != null) gate.await().forEach { emit(it) }
                if (holdStreamOpen) kotlinx.coroutines.awaitCancellation()
            }
        }

        override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
            return PromptResponse(content = "test response")
        }

        override fun cancel() { providerCancelled = true }
    }

    private val fakeLlmProvider = LlmProvider(fakeProtocol)
    private val fakeProviderRouter = object : ProviderRequestRouter {
        override fun resolve(modelId: String): ProviderResolution = forcedProviderFailure ?: ProviderResolution.Success(
            ResolvedProviderModel(
                modelId = modelId,
                remoteModelId = modelId.substringAfter("::", modelId),
                providerId = "test-local",
                providerName = "测试本地 Provider",
                config = UnifiedProviderConfig(
                    protocolType = ProtocolType.Local,
                    baseUrl = "",
                    apiKey = "",
                    defaultModel = modelId.substringAfter("::", modelId),
                ),
            )
        )

        override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient =
            error("本地测试路由不应创建云客户端")
    }

    private val fakeTaskRepository = object : com.promenar.infinity.domain.repository.ITaskRepository {
        override fun observeActiveTree(sessionId: String): Flow<List<TaskStep>> = kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun initializePlan(sessionId: String, goal: String, tree: List<TaskStep>): TaskState = TaskState(id = "", title = goal, status = "idle", steps = emptyList())
        override suspend fun updatePlan(sessionId: String, operations: List<com.promenar.infinity.domain.repository.PlanPatchOp>): TaskState = TaskState(id = "", title = "", status = "idle", steps = emptyList())
        override suspend fun getPlan(sessionId: String): TaskState? = null
        override suspend fun dropPlan(sessionId: String, reason: String) {}
        override fun deriveParentStatus(children: List<TaskStep>): String = "todo"
        override fun countLeafProgress(steps: List<TaskStep>): Pair<Int, Int> = 0 to 0
    }

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
        compactionOperation = {
            com.promenar.infinity.data.generation.ContextCompactionResult(PromptRequest(model = "model", messages = emptyList()), 0, 0, 0, 0)
        }
        compactionService = com.promenar.infinity.data.generation.ContextCompactionService(
            isGenerating = { false }, manualCompaction = { sessionId, _ -> compactionOperation(sessionId) },
        )
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        
        try {
            val delegateField = InfinityApplication::class.java.getDeclaredField("taskRepository\$delegate")
            delegateField.isAccessible = true
            val customLazy = lazy { fakeTaskRepository }
            delegateField.set(app, customLazy)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        workspaceRootFailure = null
        workspaceRootGate = null
        app.chatStore.clear()
        streamGate = null
        claimFailure = null; editGate = null; queueEnqueueGate = null; beforeQueueImageOpen = {}
        stoppedPauseGate = null; stoppedPauseDone = null
        recoveryStore = com.promenar.infinity.data.chat.queue.ComposerDraftRecoveryStore()
        queueStore = com.promenar.infinity.data.chat.queue.FileComposerQueueStore(
            java.nio.file.Files.createTempDirectory("infinity-vm-queue").toFile(),
            { beforeQueueImageOpen(); app.contentResolver.openInputStream(android.net.Uri.parse(it)) }, testDispatcher,
        )
        queuePort = object : com.promenar.infinity.data.chat.queue.ComposerQueueStore by queueStore {
            override suspend fun enqueue(sessionId: String, text: String, imageUris: List<String>, documents: List<MessageDocumentAttachment>, quotes: List<com.promenar.infinity.data.model.MessageQuote>): com.promenar.infinity.data.chat.queue.QueuedMessage {
                queueEnqueueGate?.await()
                return queueStore.enqueue(sessionId, text, imageUris, documents, quotes)
            }
            override suspend fun claimNext(sessionId: String): com.promenar.infinity.data.chat.queue.QueuedMessage? {
                claimFailure?.let { throw it }
                return queueStore.claimNext(sessionId)
            }
            override suspend fun pause(sessionId: String, reason: com.promenar.infinity.data.chat.queue.QueuePauseReason?) {
                val gate = stoppedPauseGate
                val done = stoppedPauseDone
                if (gate != null && done != null) {
                    if (reason == com.promenar.infinity.data.chat.queue.QueuePauseReason.STOPPED && !done.isCompleted) {
                        gate.await()
                        queueStore.pause(sessionId, reason)
                        done.complete(Unit)
                        return
                    }
                    if (!gate.isCompleted) {
                        gate.complete(Unit)
                        done.await()
                    }
                }
                queueStore.pause(sessionId, reason)
            }
            override suspend fun prepareEdit(sessionId: String, id: String): com.promenar.infinity.data.chat.queue.QueuedMessage? {
                val prepared = queueStore.prepareEdit(sessionId, id)
                editGate?.await()
                return prepared
            }
        }
        
        savedSessions.clear()
        savedMessages.clear()
        deletedMessages.clear()
        fakeStreamChunks = emptyList()
        failScopedDelete = false
        failSessionUpdate = null
        failQuestionResultUpdate = false
        holdDraftUpdates = null
        beforeDraftUpdate = { _, _ -> }
        afterInsert = { _, _ -> }
        sshLeases.clear()
        pausedSshOwners.clear()
        beforeSshClose = {}
        protocolRequestCount = 0
        lastPromptRequest = null
        holdStreamOpen = false
        providerCancelled = false
        foregroundTrackedTasks.clear()
        foregroundStartResult =
            com.promenar.infinity.background.generation.ForegroundStartResult.StartedWithNotifications
        forcedProviderFailure = null
        notificationPermissionState = null
        // 假 Provider 默认不发完成信号，属于可重试失败；本类断言 Provider 启动次数与顺序，关闭自动重试。
        app.getSharedPreferences("infinity_settings", 0).edit()
            .putInt(com.promenar.infinity.domain.generation.STREAM_RETRY_LIMIT_KEY, 0)
            .commit()

        stubAgentRepo.seed(Agent(
            id = "a1",
            name = "Test Agent",
            systemPrompt = "test prompt",
            modelId = "gpt-4o"
        ))

        val configResolver = AgentConfigResolver(
            app.getSharedPreferences("infinity_settings", 0)
        )
        generationScope = CoroutineScope(SupervisorJob() + testDispatcher)
        presentationStore = com.promenar.infinity.data.generation.GenerationPresentationStore()
        val generationSessionManager = com.promenar.infinity.ui.chat.manager.SessionManager(app.chatStore, stubSessionRepo)
        val generationMessageManager = com.promenar.infinity.ui.chat.manager.MessageManager(
            app.chatStore,
            stubMessageRepo,
            stubSessionRepo,
            generationScope,
        )
        val generationToolLedger = io.mockk.mockk<com.promenar.infinity.data.repository.ToolExecutionLedger>(relaxed = true)
        io.mockk.coEvery { generationToolLedger.invocationIdentity(any()) } returns null
        io.mockk.coEvery { generationToolLedger.register(any(), any()) } returns com.promenar.infinity.data.repository.ToolRegistrationResult.Registered(com.promenar.infinity.data.local.db.entity.ToolLedgerStatus.APPROVED)
        io.mockk.coEvery { generationToolLedger.claim(any(), any()) } returns true
        io.mockk.coEvery { generationToolLedger.finishWithResult(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val key = arg<com.promenar.infinity.data.repository.ToolExecutionKey>(0)
            Message(id = "tool-${key.toolCallId}", role = MessageRole.TOOL, content = arg(2), toolCallId = key.toolCallId, parentMessageId = key.assistantMessageId, name = arg(1)).also { stubMessageRepo.insert(it, key.sessionId) }
        }
        val generationToolResolver = com.promenar.infinity.data.generation.DefaultSessionToolResolver(
            app.getSharedPreferences("infinity_settings", 0),
            null,
        )
        val generationToolExecutor = com.promenar.infinity.ui.chat.manager.ToolExecutor(
            app.chatStore,
            generationMessageManager,
            null,
            generationToolResolver,
            fakeTaskRepository,
            generationToolLedger,
        )
        val runnerFactory = com.promenar.infinity.data.generation.DefaultChatGenerationRunnerFactory(
            settings = app.getSharedPreferences("infinity_settings", 0),
            applicationScope = generationScope,
            store = app.chatStore,
            agentRepository = stubAgentRepo,
            configResolver = configResolver,
            routeGate = ChatProviderRouteGate(fakeProviderRouter, testDispatcher),
            contextBuilder = com.promenar.infinity.ui.chat.manager.ContextBuilder(taskRepository = fakeTaskRepository),
            messageManager = generationMessageManager,
            localProviderFactory = { fakeLlmProvider },
            provider = fakeLlmProvider,
            toolLedger = generationToolLedger,
            toolExecutor = generationToolExecutor,
            postProcessor = com.promenar.infinity.ui.chat.manager.PostProcessor(
                app.chatStore,
                generationSessionManager,
                generationMessageManager,
            ),
            memoryManager = null,
            summaryManager = com.promenar.infinity.ui.chat.manager.SummaryManager(fakeLlmProvider),
            sessionManager = generationSessionManager,
            skillRegistry = null,
            toolResolver = generationToolResolver,
            presentationStore = presentationStore,
            toolBuildDispatcher = testDispatcher,
            composerQueueStore = queuePort,
            skillCatalog = slashSkillCatalog,
            askUserLedger = app.askUserLedger,
        )
        generationCoordinator = com.promenar.infinity.data.generation.DefaultGenerationCoordinator(
            applicationScope = generationScope,
            runnerFactory = runnerFactory,
            presentationStore = presentationStore,
        )

        val documentReader = io.mockk.mockk<FullContextDocumentReader>()
        viewModel = ChatViewModel(
            application = app,
            sessionRepository = stubSessionRepo,
            messageRepository = stubMessageRepo,
            agentRepository = stubAgentRepo,
            llmProvider = fakeLlmProvider,
            providerRequestRouter = fakeProviderRouter,
            providerResolutionDispatcher = testDispatcher,
            localLlmProviderFactory = { fakeLlmProvider },
            configResolver = configResolver,
            sessionToolResolver = generationToolResolver,
            fullContextDocumentReader = documentReader,
            generationCoordinatorOverride = generationCoordinator,
            generationPresentationStoreOverride = presentationStore,
            composerQueueStoreOverride = queuePort,
            composerDraftRecoveryOverride = recoveryStore,
            generationForegroundControllerOverride =
                object : com.promenar.infinity.background.generation.GenerationForegroundController {
                    override val failures = foregroundFailures
                    override fun track(
                        snapshot: com.promenar.infinity.domain.generation.GenerationTaskSnapshot,
                    ): com.promenar.infinity.background.generation.ForegroundStartResult {
                        foregroundTrackedTasks += snapshot
                        return foregroundStartResult
                    }
                },
            notificationPermissionGateway = { notificationPermissionState },
            stringResourceResolverOverride = { resourceId ->
                when (resourceId) {
                    com.promenar.infinity.R.string.generation_background_unavailable ->
                        BACKGROUND_UNAVAILABLE_MESSAGE
                    com.promenar.infinity.R.string.generation_notifications_unavailable ->
                        NOTIFICATIONS_UNAVAILABLE_MESSAGE
                    com.promenar.infinity.R.string.generation_background_service_stopped ->
                        BACKGROUND_SERVICE_STOPPED_MESSAGE
                    com.promenar.infinity.R.string.chat_document_error_unsupported -> "unsupported document"
                    com.promenar.infinity.R.string.chat_document_error_empty -> "empty document"
                    com.promenar.infinity.R.string.chat_document_error_too_large -> "document too large"
                    com.promenar.infinity.R.string.chat_document_error_binary -> "binary document"
                    com.promenar.infinity.R.string.chat_document_error_encoding -> "invalid encoding"
                    com.promenar.infinity.R.string.chat_document_error_read -> "document read failed"
                    com.promenar.infinity.R.string.chat_document_import_in_progress ->
                        "document import in progress"
                    com.promenar.infinity.R.string.chat_document_error_unknown_capacity -> "unknown capacity"
                    com.promenar.infinity.R.string.chat_document_error_invalid_estimate -> "invalid estimate"
                    com.promenar.infinity.R.string.chat_document_error_over_capacity ->
                        "requires %1\$d tokens, %2\$d available"
                    com.promenar.infinity.R.string.chat_queue_error_load -> "chat_queue_error_load"
                    com.promenar.infinity.R.string.chat_queue_error_enqueue -> "chat_queue_error_enqueue"
                    com.promenar.infinity.R.string.chat_queue_error_draft_after_enqueue -> "chat_queue_error_draft_after_enqueue"
                    com.promenar.infinity.R.string.chat_queue_error_draft_after_restore -> "chat_queue_error_draft_after_restore"
                    com.promenar.infinity.R.string.chat_queue_error_update -> "chat_queue_error_update"
                    com.promenar.infinity.R.string.chat_queue_error_send -> "chat_queue_error_send"
                    com.promenar.infinity.R.string.chat_summary_card_progress_preparing -> "preparing"
                    com.promenar.infinity.R.string.chat_context_folding -> "folding"
                    com.promenar.infinity.R.string.chat_context_summarizing -> "summarizing"
                    com.promenar.infinity.R.string.chat_context_compaction_result -> "folded %1\$d, turns %2\$d, %3\$d -> %4\$d"
                    com.promenar.infinity.R.string.chat_context_nothing_to_compress -> "retain %1\$d turns"
                    com.promenar.infinity.R.string.chat_context_compaction_failed -> "compaction failed"
                    com.promenar.infinity.R.string.chat_context_compress_unavailable_generating -> "generating"
                    com.promenar.infinity.R.string.chat_message_edit_failed -> "message edit failed"
                    com.promenar.infinity.R.string.legacy_root_marker_workspace_unavailable -> "工作区不可用，可继续对话"
                    com.promenar.infinity.R.string.ask_user_answer_failed -> "回答保存失败，请重试"
                    com.promenar.infinity.R.string.ask_user_resume_failed -> "回答已保存，重新打开会话续跑"
                    else -> error("unexpected string resource: $resourceId")
                }
            },
            sessionWorkspaceRootOverride = {
                workspaceRootGate?.await()
                workspaceRootFailure?.let { throw it }
                "test-workspace-root" to "/test/workspace"
            },
            sshOwnerCloser = { owner -> beforeSshClose(); sshLeases.remove(owner); Unit },
            sshOwnerPauser = { owner -> if (owner in sshLeases) pausedSshOwners.add(owner); Unit },
            sshOwnerResumer = { owner -> pausedSshOwners.remove(owner); Unit },
            sshCleanupDispatcher = testDispatcher,
            contextCompactionService = compactionService,
            contextUsageEstimator = {
                if (contextEstimateFails) throw java.io.IOException("估算不可用")
                com.promenar.infinity.data.generation.ContextUsageEstimate(100, 0, 0, 0, 0, 128000)
            },
            draftDocumentBudgetEstimator = { id, text, documents ->
                val session = app.chatStore.getSession(id)!!
                com.promenar.infinity.data.generation.DraftDocumentBudgetEvaluator.evaluate(session,
                    com.promenar.infinity.data.generation.DraftBudgetContext(session.modelId.orEmpty(),
                        PromptRequest(model = "model", messages = emptyList()),
                        com.promenar.infinity.data.generation.PromptWindow(if (session.modelId == "small") 2400 else 128000, 256)), text, documents)
            },
            documentWorkspaceSaver = object : com.promenar.infinity.data.document.DocumentWorkspaceSaver {
                override suspend fun save(sessionId: String, document: MessageDocumentAttachment, relativeDirectory: String?): com.promenar.infinity.data.document.DocumentWorkspaceSaveResult {
                    documentSaveGate?.await()
                    return com.promenar.infinity.data.document.DocumentWorkspaceSaveResult.Success(document.copy(content = "",
                        workspaceReference = WorkspaceDocumentReference("/session/attachments/${document.name}", 1, 8000,
                            sourceSessionId = sessionId, isPrivate = true, linuxNamespace = false)))
                }
            },
            workspaceLookupOverride = { id -> workspaceRecords[id] },
            workspaceSwitchNoticeTextOverride = { from, to -> "切换：$from → $to" },
        )
        this.documentReader = documentReader
    }

    private val workspaceRecords = mutableMapOf<String, com.promenar.infinity.data.local.db.entity.WorkspaceEntity>()

    @Test fun `草稿预算随文本文档模型和会话变化且转存后只计引用`() = runTest {
        seedSession(); advanceUntilIdle()
        val doc = MessageDocumentAttachment("budget-doc", "a.txt", "text/plain", "中".repeat(2000), 6000, "hash", 6000)
        @Suppress("UNCHECKED_CAST")
        val documents = ChatViewModel::class.java.getDeclaredField("_draftDocuments").apply { isAccessible = true }
            .get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<List<MessageDocumentAttachment>>
        documents.value = listOf(doc)
        viewModel.updateInputText("问题")
        awaitReal { viewModel.draftDocumentBudget.value?.documents?.singleOrNull()?.documentId == doc.id }
        assertThat(viewModel.draftDocumentBudget.value!!.status).isEqualTo(com.promenar.infinity.data.generation.DraftBudgetStatus.FITS)
        viewModel.updateModelId("small")
        awaitReal { viewModel.draftDocumentBudget.value?.status == com.promenar.infinity.data.generation.DraftBudgetStatus.EXCEEDED }
        val result = viewModel.saveDraftDocumentToWorkspace(doc.id)
        assertThat(result).isInstanceOf(com.promenar.infinity.data.document.DocumentWorkspaceSaveResult.Success::class.java)
        assertThat(viewModel.draftDocuments.value.single().content).isEmpty()
        awaitReal { viewModel.draftDocumentBudget.value?.documents?.singleOrNull()?.inline == false }
        assertThat(viewModel.draftDocumentBudget.value!!.status).isEqualTo(com.promenar.infinity.data.generation.DraftBudgetStatus.FITS)
        seedSession("budget-other"); advanceUntilIdle()
        awaitReal { viewModel.draftDocuments.value.isEmpty() && viewModel.draftDocumentBudget.value?.documents?.isEmpty() == true }
    }

    @Test fun `转存期间移除草稿时不把保存结果重新附加`() = runTest {
        seedSession(); advanceUntilIdle()
        val doc = MessageDocumentAttachment("save-doc", "a.txt", "text/plain", "正文", 6, "hash", 2)
        @Suppress("UNCHECKED_CAST")
        val documents = ChatViewModel::class.java.getDeclaredField("_draftDocuments").apply { isAccessible = true }
            .get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<List<MessageDocumentAttachment>>
        documents.value = listOf(doc)
        documentSaveGate = CompletableDeferred()
        var saved: com.promenar.infinity.data.document.DocumentWorkspaceSaveResult? = null
        val job = launch { saved = viewModel.saveDraftDocumentToWorkspace(doc.id) }
        runCurrent()
        viewModel.removeDraftDocument(doc.id)
        documentSaveGate!!.complete(Unit)
        job.join()
        assertThat((saved as com.promenar.infinity.data.document.DocumentWorkspaceSaveResult.Failure).reason)
            .isEqualTo(com.promenar.infinity.data.document.DocumentWorkspaceSaveFailure.DraftChanged)
        assertThat(documents.value).isEmpty()
    }

    @Test
    fun `切换工作区写入会话并给出前后提示，生成中拒绝切换`() = runTest {
        workspaceRecords["ws-demo"] = com.promenar.infinity.data.local.db.entity.WorkspaceEntity(
            "ws-demo", com.promenar.infinity.data.local.db.entity.WorkspaceEntity.KIND_PROJECT, "演示项目", "demo", 1,
        )
        seedSession()
        advanceUntilIdle()
        val results = mutableListOf<com.promenar.infinity.data.workspace.WorkspaceSwitchResult>()
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()

        viewModel.switchSessionWorkspace("ws-demo") { results += it }
        advanceUntilIdle()
        val switched = results.last() as com.promenar.infinity.data.workspace.WorkspaceSwitchResult.Switched
        assertThat(switched.from).isEqualTo(com.promenar.infinity.data.workspace.WorkspaceLabel.Private)
        assertThat(switched.to).isEqualTo(
            com.promenar.infinity.data.workspace.WorkspaceLabel.Registered("演示项目", "project", "demo"),
        )
        assertThat(app.chatStore.getSession("s1")?.workspaceId).isEqualTo("ws-demo")
        assertThat(viewModel.workspaceSwitchNotice.value).isEqualTo(switched)
        val notices = app.chatStore.getSession("s1")?.messages.orEmpty().filter { it.isWorkspaceSwitchNotice }
        assertThat(notices).hasSize(1)
        assertThat(notices.single().content).contains("演示项目")

        holdStreamOpen = true
        viewModel.sendMessage("生成一段回复")
        advanceUntilIdle()
        assertThat(viewModel.isWorkspaceSwitchBlocked()).isTrue()
        viewModel.switchSessionWorkspace(null) { results += it }
        advanceUntilIdle()
        assertThat(results.last()).isEqualTo(com.promenar.infinity.data.workspace.WorkspaceSwitchResult.RejectedBusy)
        assertThat(app.chatStore.getSession("s1")?.workspaceId).isEqualTo("ws-demo")

        viewModel.stopGeneration()
        advanceUntilIdle()
        viewModel.switchSessionWorkspace(null) { results += it }
        advanceUntilIdle()
        val back = results.last() as com.promenar.infinity.data.workspace.WorkspaceSwitchResult.Switched
        assertThat(back.to).isEqualTo(com.promenar.infinity.data.workspace.WorkspaceLabel.Private)
        assertThat(app.chatStore.getSession("s1")?.workspaceId).isNull()
    }

    @Test
    fun `待审批的工具调用期间拒绝切换工作区`() = runTest {
        workspaceRecords["ws-demo"] = com.promenar.infinity.data.local.db.entity.WorkspaceEntity(
            "ws-demo", com.promenar.infinity.data.local.db.entity.WorkspaceEntity.KIND_PROJECT, "演示项目", "demo", 1,
        )
        seedSession(approvalRequest = ApprovalRequest(toolName = "write_file", args = "{}", reason = "需要审批"))
        advanceUntilIdle()
        var result: com.promenar.infinity.data.workspace.WorkspaceSwitchResult? = null
        viewModel.switchSessionWorkspace("ws-demo") { result = it }
        advanceUntilIdle()
        assertThat(result).isEqualTo(com.promenar.infinity.data.workspace.WorkspaceSwitchResult.RejectedBusy)
        assertThat(ApplicationProvider.getApplicationContext<InfinityApplication>().chatStore.getSession("s1")?.workspaceId).isNull()
    }

    private lateinit var documentReader: FullContextDocumentReader

    @Test
    fun `生成结束后的估算失败不会使立即压缩持续禁用`() = runTest {
        seedSession()
        advanceUntilIdle()
        holdStreamOpen = true
        viewModel.sendMessage("生成一段回复")
        advanceUntilIdle()
        assertThat(viewModel.tokenIndicatorState.value.isGenerating).isTrue()

        contextEstimateFails = true
        viewModel.stopGeneration()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isGenerating).isFalse()
        assertThat(viewModel.tokenIndicatorState.value.isGenerating).isFalse()
        assertThat(viewModel.tokenIndicatorState.value.used).isEqualTo(100)
    }

    @After
    fun tearDown() {
        val viewModelJob = viewModel.viewModelScope.coroutineContext.job
        runBlocking {
            generationScope.coroutineContext.job.cancelAndJoin()
        }
        ViewModelStore().also { it.put("chat", viewModel) }.clear()
        runBlocking {
            viewModelJob.join()
        }
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `手动压缩进度按会话投影且切换后可再次压缩`() = runTest {
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        compactionOperation = { sessionId ->
            calls += sessionId
            if (sessionId == "s1") release.await()
            com.promenar.infinity.data.generation.ContextCompactionResult(
                PromptRequest(model = "model", messages = emptyList()), 1, 0, 1000, 500,
                newlyFoldedToolResults = 1,
            )
        }
        seedSession("s1")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.session?.id).isEqualTo("s1")
        viewModel.compactNow()
        runCurrent()
        assertThat(calls).containsExactly("s1")
        assertThat(compactionService.observe("s1").value.isRunning).isTrue()
        assertThat(viewModel.compressionState.value.isCompressing).isTrue()
        seedSession("s2")
        runCurrent()
        assertThat(viewModel.compressionState.value.isCompressing).isFalse()
        viewModel.compactNow()
        runCurrent()
        assertThat(calls).containsExactly("s1", "s2").inOrder()
        assertThat(viewModel.compressionState.value.result).isNotNull()
        release.complete(Unit)
        runCurrent()
        viewModel.loadSession("s1")
        runCurrent()
        assertThat(viewModel.compressionState.value.isCompressing).isFalse()
        assertThat(viewModel.compressionState.value.result).isNotNull()
        viewModel.dismissCompressionResult()
        assertThat(viewModel.compressionState.value.result).isNull()
        viewModel.compactNow()
        runCurrent()
        assertThat(calls).containsExactly("s1", "s2", "s1").inOrder()
        assertThat(viewModel.compressionState.value.isCompressing).isFalse()
    }

    private fun seedSession(
        id: String = "s1",
        messages: List<Message> = emptyList(),
        approvalRequest: ApprovalRequest? = null,
    ) {
        val session = Session(
            id = id,
            agentId = "a1",
            title = "Test",
            modelId = "gpt-4o",
            messages = messages,
            approvalRequest = approvalRequest,
            loopStatus = if (approvalRequest != null) LoopStatus.WAITING_FOR_APPROVAL else LoopStatus.IDLE,
            workspaceRootUuid = "test-workspace-root",
            workspacePath = "/test/workspace",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        messages.forEach { savedMessages.add(it to id) }
        savedSessions.add(session)
        viewModel.loadSession(id)
    }

    @Test
    fun updateModelId通过会话管理器同步Store并持久化同一模型() = runTest {
        seedSession()
        advanceUntilIdle()

        viewModel.updateModelId("provider-b::model-b")
        advanceUntilIdle()

        assertThat((ApplicationProvider.getApplicationContext() as TestInfinityApplication)
            .chatStore.getSession("s1")?.modelId).isEqualTo("provider-b::model-b")
        assertThat(savedSessions.single { it.id == "s1" }.modelId)
            .isEqualTo("provider-b::model-b")
    }

    @Test
    fun 拖动字号即使未触发结束回调也会持久化最终值() = runTest {
        seedSession()
        advanceUntilIdle()

        viewModel.updateFontSizeLocally(17)
        advanceUntilIdle()

        assertThat(savedSessions.single { it.id == "s1" }.options?.fontSize).isEqualTo(17)
    }

    @Test
    fun saveCustomPrompt等待仓库成功后才更新Store() = runTest {
        seedSession()
        advanceUntilIdle()

        val result = viewModel.saveCustomPrompt("persisted")

        assertThat(result.isSuccess).isTrue()
        assertThat(savedSessions.single { it.id == "s1" }.customPrompt).isEqualTo("persisted")
        assertThat((ApplicationProvider.getApplicationContext() as TestInfinityApplication)
            .chatStore.getSession("s1")?.customPrompt).isEqualTo("persisted")
    }

    @Test
    fun saveCustomPrompt仓库失败时不更新Store() = runTest {
        seedSession()
        advanceUntilIdle()
        failSessionUpdate = IllegalStateException("room failed")

        val result = viewModel.saveCustomPrompt("not-persisted")

        assertThat(result.isFailure).isTrue()
        assertThat((ApplicationProvider.getApplicationContext() as TestInfinityApplication)
            .chatStore.getSession("s1")?.customPrompt).isNull()
    }

    @Test
    fun saveCustomPrompt取消必须传播() = runTest {
        seedSession()
        advanceUntilIdle()
        failSessionUpdate = CancellationException("cancelled")

        var thrown: CancellationException? = null
        try {
            viewModel.saveCustomPrompt("cancelled")
        } catch (cancelled: CancellationException) {
            thrown = cancelled
        }
        assertThat(thrown?.message).isEqualTo("cancelled")
    }

    @Test
    fun sendMessage_createsUserAndAssistantMessages() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))

        viewModel.sendMessage("hello"); advanceUntilIdle()
        
        val app = ApplicationProvider.getApplicationContext<com.promenar.infinity.InfinityApplication>()
        println("=== DIAGNOSTIC LOG ===")
        println("chatStore sessions size: ${app.chatStore.get().sessions.size}")
        if (app.chatStore.get().sessions.isNotEmpty()) {
            println("chatStore session id: ${app.chatStore.get().sessions[0].id}")
            println("chatStore session messages size: ${app.chatStore.get().sessions[0].messages.size}")
        }
        println("currentSessionId: ${viewModel.uiState.value.session?.id}")
        println("uiState messages size: ${viewModel.uiState.value.messages.size}")
        println("=======================")

        val uiState = viewModel.uiState.value
        assertThat(uiState.messages).hasSize(2)
        assertThat(uiState.messages[0].role).isEqualTo(MessageRole.USER)
        assertThat(uiState.messages[1].role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(foregroundTrackedTasks.map { it.sessionId }).containsExactly("s1")
        
    }

    @Test
    fun sendMessage_persistsMessagesToRepository() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))

        viewModel.sendMessage("hello"); advanceUntilIdle()
        

        assertThat(savedMessages).hasSize(2)
        assertThat(savedMessages[0].first.role).isEqualTo(MessageRole.USER)
        assertThat(savedMessages[0].second).isEqualTo("s1")
        
    }

    @Test
    fun `完整文档随用户消息持久化并进入最终协议正文`() = runTest {
        val document = MessageDocumentAttachment(
            id = "doc-1",
            name = "history.md",
            mimeType = "text/markdown",
            content = "# 历史\n完整正文",
            sizeBytes = 20,
            sha256 = "hash",
            estimatedTokens = 5,
        )
        io.mockk.coEvery { documentReader.read(any()) } returns DocumentReadResult.Success(document)
        ApplicationProvider.getApplicationContext<TestInfinityApplication>()
            .getSharedPreferences("infinity_settings", 0)
            .edit()
            .putInt("model_info_gpt-4o_context", 128_000)
            .commit()
        seedSession()
        advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))

        viewModel.importFullContextDocument(android.net.Uri.parse("content://test/history"))
        advanceUntilIdle()
        viewModel.sendMessage("继续分析")
        advanceUntilIdle()

        assertThat(savedMessages.first().first.userDocuments).containsExactly(document)
        val userProtocol = lastPromptRequest!!.messages.last { it.role == "user" }
        assertThat(userProtocol.content).contains("继续分析")
        assertThat(userProtocol.content).contains(document.content)
        assertThat(viewModel.draftDocuments.value).isEmpty()
        assertThat(viewModel.draftConsumptionEpoch.value).isEqualTo(1L)
    }

    @Test
    fun `写了批注的引用随用户消息持久化并序列化进协议正文，未写批注的丢弃`() = runTest {
        seedSession()
        advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))

        viewModel.addQuote("ai-source", "  第一段引文  ")
        viewModel.addQuote("ai-source", "第二段引文")
        // 同一来源的同一段引文不重复加卡。
        viewModel.addQuote("ai-source", "第一段引文")
        assertThat(viewModel.draftQuotes.value).hasSize(2)
        val annotated = viewModel.draftQuotes.value.first()
        viewModel.updateQuoteAnnotation(annotated.id, " 说法不准确 ")

        viewModel.sendMessage("")
        advanceUntilIdle()

        val user = savedMessages.first { it.first.role == MessageRole.USER }.first
        assertThat(user.content).isEmpty()
        assertThat(user.quotes).containsExactly(
            com.promenar.infinity.data.model.MessageQuote("ai-source", "第一段引文", "说法不准确"),
        )
        val userProtocol = lastPromptRequest!!.messages.last { it.role == "user" }
        assertThat(userProtocol.content).contains("> 第一段引文\n\n说法不准确")
        assertThat(userProtocol.content).doesNotContain("第二段引文")
        assertThat(viewModel.draftQuotes.value).isEmpty()
    }

    @Test
    fun `只有未写批注的引用时不发送且引用卡保留`() = runTest {
        seedSession()
        advanceUntilIdle()

        viewModel.addQuote("ai-source", "引文")
        viewModel.sendMessage("")
        runCurrent()

        assertThat(savedMessages).isEmpty()
        assertThat(protocolRequestCount).isEqualTo(0)
        assertThat(viewModel.draftQuotes.value).hasSize(1)
    }

    @Test
    fun `移除引用卡后不再发送该引用`() = runTest {
        seedSession()
        advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))

        viewModel.addQuote("ai-source", "要删的引文")
        val quote = viewModel.draftQuotes.value.single()
        viewModel.updateQuoteAnnotation(quote.id, "批注")
        viewModel.removeQuote(quote.id)
        viewModel.sendMessage("正文")
        advanceUntilIdle()

        val user = savedMessages.first { it.first.role == MessageRole.USER }.first
        assertThat(user.content).isEqualTo("正文")
        assertThat(user.quotes).isNull()
    }

    @Test
    fun `多文档按选择顺序读取且全部结束前保持导入状态`() = runTest {
        val firstUri = android.net.Uri.parse("content://test/first")
        val secondUri = android.net.Uri.parse("content://test/second")
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val first = MessageDocumentAttachment(
            "first", "first.txt", "text/plain", "one", 3L, "hash-1", 1,
        )
        val second = MessageDocumentAttachment(
            "second", "second.md", "text/markdown", "two", 3L, "hash-2", 1,
        )
        io.mockk.coEvery { documentReader.read(firstUri) } coAnswers {
            firstGate.await()
            DocumentReadResult.Success(first)
        }
        io.mockk.coEvery { documentReader.read(secondUri) } coAnswers {
            secondGate.await()
            DocumentReadResult.Success(second)
        }

        viewModel.importFullContextDocuments(listOf(firstUri, secondUri))
        runCurrent()
        assertThat(viewModel.isImportingDocument.value).isTrue()

        firstGate.complete(Unit)
        runCurrent()
        assertThat(viewModel.isImportingDocument.value).isTrue()

        secondGate.complete(Unit)
        advanceUntilIdle()
        assertThat(viewModel.isImportingDocument.value).isFalse()
        assertThat(viewModel.draftDocuments.value).containsExactly(first, second).inOrder()
    }

    @Test
    fun `文档读取期间发送被阻止且不持久化消息`() = runTest {
        val uri = android.net.Uri.parse("content://test/pending")
        val gate = CompletableDeferred<Unit>()
        io.mockk.coEvery { documentReader.read(uri) } coAnswers {
            gate.await()
            DocumentReadResult.Rejected(
                com.promenar.infinity.data.document.DocumentReadRejectReason.ReadFailed,
            )
        }
        seedSession()
        advanceUntilIdle()

        viewModel.importFullContextDocuments(listOf(uri))
        runCurrent()
        viewModel.sendMessage("不能抢跑")
        runCurrent()

        assertThat(savedMessages).isEmpty()
        assertThat(protocolRequestCount).isEqualTo(0)
        assertThat(viewModel.uiState.value.error).isEqualTo("document import in progress")
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `完整文档超预算时不持久化且不调用Provider`() = runTest {
        val document = MessageDocumentAttachment(
            id = "doc-large",
            name = "large.txt",
            mimeType = "text/plain",
            content = "中".repeat(200_000),
            sizeBytes = 600_000,
            sha256 = "large-hash",
            estimatedTokens = 200_000,
        )
        io.mockk.coEvery { documentReader.read(any()) } returns DocumentReadResult.Success(document)
        ApplicationProvider.getApplicationContext<TestInfinityApplication>()
            .getSharedPreferences("infinity_settings", 0)
            .edit()
            .putInt("model_info_gpt-4o_context", 128_000)
            .commit()
        seedSession()
        advanceUntilIdle()

        viewModel.importFullContextDocument(android.net.Uri.parse("content://test/large"))
        advanceUntilIdle()
        viewModel.sendMessage("请分析")
        advanceUntilIdle()

        assertThat(savedMessages).isEmpty()
        assertThat(protocolRequestCount).isEqualTo(0)
        // 文档本身超过窗口，属于当前轮放不下，提示转存工作区或换模型。
        assertThat(viewModel.uiState.value.generationNotice?.code)
            .isEqualTo("generation.failure.context_limit_turn_documents")
        assertThat(viewModel.draftDocuments.value).containsExactly(document)
        assertThat(viewModel.inputText.value).isEqualTo("请分析")
        assertThat(savedSessions.single { it.id == "s1" }.draft).isEqualTo("请分析")

        viewModel.clearError()
        assertThat(viewModel.uiState.value.status).isEqualTo(GenerationStatus.IDLE)
    }

    @Test
    fun `排队后准备失败时合并已提交草稿与之后输入的新草稿`() = runTest {
        val document = MessageDocumentAttachment(
            id = "queued-large", name = "large.txt", mimeType = "text/plain",
            content = "中".repeat(200_000), sizeBytes = 600_000,
            sha256 = "queued-large-hash", estimatedTokens = 200_000,
        )
        io.mockk.coEvery { documentReader.read(any()) } returns DocumentReadResult.Success(document)
        ApplicationProvider.getApplicationContext<TestInfinityApplication>()
            .getSharedPreferences("infinity_settings", 0).edit()
            .putInt("model_info_gpt-4o_context", 128_000).commit()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("正在运行"))
        holdStreamOpen = true
        (1..com.promenar.infinity.domain.generation.MAX_CONCURRENT_GENERATIONS).forEach { index ->
            seedSession("occupied-$index")
            advanceUntilIdle()
            viewModel.sendMessage("占用 $index")
            advanceUntilIdle()
        }
        seedSession("queued-document")
        advanceUntilIdle()
        viewModel.importFullContextDocument(android.net.Uri.parse("content://test/queued-large"))
        advanceUntilIdle()
        viewModel.sendMessage("请分析排队文档")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isGenerating).isTrue()
        viewModel.updateInputText("之后输入的新草稿")
        viewModel.saveCurrentDraft()
        viewModel.loadSession("occupied-1")
        advanceUntilIdle()
        viewModel.stopGeneration()
        viewModel.loadSession("occupied-2")
        advanceUntilIdle()
        assertThat(savedMessages.filter { it.second == "queued-document" }).isEmpty()
        viewModel.loadSession("queued-document")
        advanceUntilIdle()
        assertThat(viewModel.inputText.value).isEqualTo("请分析排队文档\n\n之后输入的新草稿")
        assertThat(viewModel.draftDocuments.value).containsExactly(document)
        viewModel.saveCurrentDraft()
        seedSession("attachment-isolation"); advanceUntilIdle()
        assertThat(viewModel.draftDocuments.value).isEmpty()
        viewModel.sendMessage("只发送这个会话的文字"); advanceUntilIdle()
        assertThat(savedMessages.last { it.second == "attachment-isolation" && it.first.role == MessageRole.USER }.first.userDocuments).isNull()
        viewModel.stopGeneration(); advanceUntilIdle()
        viewModel.loadSession("queued-document"); advanceUntilIdle()
        assertThat(viewModel.draftDocuments.value).containsExactly(document)
    }

    @Test
    fun `并发恢复同一草稿时只合并一次`() = runTest {
        val document = MessageDocumentAttachment(
            id = "recovery-lock", name = "large.txt", mimeType = "text/plain",
            content = "中".repeat(200_000), sizeBytes = 600_000,
            sha256 = "recovery-lock-hash", estimatedTokens = 200_000,
        )
        io.mockk.coEvery { documentReader.read(any()) } returns DocumentReadResult.Success(document)
        ApplicationProvider.getApplicationContext<TestInfinityApplication>()
            .getSharedPreferences("infinity_settings", 0).edit().putInt("model_info_gpt-4o_context", 128_000).commit()
        seedSession("recovery-lock"); advanceUntilIdle()
        viewModel.importFullContextDocument(android.net.Uri.parse("content://test/recovery-lock")); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        holdDraftUpdates = gate
        viewModel.sendMessage("只恢复一次")
        runCurrent()
        assertThat(viewModel.inputText.value).isEqualTo("只恢复一次")
        holdDraftUpdates = null
        gate.complete(Unit); advanceUntilIdle()
        assertThat(viewModel.inputText.value).isEqualTo("只恢复一次")
        assertThat(viewModel.draftDocuments.value).containsExactly(document)
    }

    @Test
    fun `恢复旧草稿写盘挂起时切页保存的新输入不被覆盖`() = runTest {
        seedSession("draft-race-b"); advanceUntilIdle()
        seedSession("draft-race-a"); advanceUntilIdle()
        val document = MessageDocumentAttachment(
            id = "race-large", name = "large.txt", mimeType = "text/plain",
            content = "中".repeat(200_000), sizeBytes = 600_000,
            sha256 = "race-large-hash", estimatedTokens = 200_000,
        )
        io.mockk.coEvery { documentReader.read(any()) } returns DocumentReadResult.Success(document)
        ApplicationProvider.getApplicationContext<TestInfinityApplication>()
            .getSharedPreferences("infinity_settings", 0).edit().putInt("model_info_gpt-4o_context", 128_000).commit()
        viewModel.importFullContextDocument(android.net.Uri.parse("content://test/race-large")); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        beforeDraftUpdate = { id, text ->
            if (id == "draft-race-a" && text == "旧草稿" && !entered.isCompleted) {
                entered.complete(Unit)
                gate.await()
            }
        }
        viewModel.sendMessage("旧草稿")
        runCurrent()
        assertThat(entered.isCompleted).isTrue()
        viewModel.updateInputText("旧草稿和新输入")
        viewModel.loadSession("draft-race-b")
        runCurrent()
        viewModel.loadSession("draft-race-a")
        runCurrent()
        val immediate = viewModel.inputText.value
        gate.complete(Unit); advanceUntilIdle()
        assertThat(immediate).isEqualTo("旧草稿和新输入")
        assertThat(viewModel.inputText.value).isEqualTo("旧草稿和新输入")
        assertThat(savedSessions.single { it.id == "draft-race-a" }.draft).isEqualTo("旧草稿和新输入")
    }

    @Test
    fun `草稿恢复写盘失败后重试发送会恢复并继续发送`() = runTest {
        seedSession("draft-retry"); advanceUntilIdle()
        beforeDraftUpdate = { _, _ -> throw IllegalStateException("暂时无法写盘") }
        presentationStore.retainSubmittedDraft(com.promenar.infinity.data.generation.SubmittedGenerationDraft(
            sessionId = "draft-retry", text = "原始草稿", documents = emptyList(), imageUris = emptyList(), recoveryReady = true,
        ))
        advanceUntilIdle()
        assertThat(viewModel.inputText.value).isEqualTo("原始草稿")
        beforeDraftUpdate = { _, _ -> }
        viewModel.updateInputText("恢复后的新输入")
        viewModel.sendMessage("恢复后的新输入")
        advanceUntilIdle()
        assertThat(savedMessages.any { it.second == "draft-retry" && it.first.role == MessageRole.USER && it.first.content == "恢复后的新输入" }).isTrue()
    }

    @Test
    fun `恢复重试写盘时再次编辑不得误发送新输入`() = runTest {
        seedSession("draft-retry-edit"); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        beforeDraftUpdate = { _, _ -> gate.await() }
        viewModel.updateInputText("第一条")
        presentationStore.retainSubmittedDraft(com.promenar.infinity.data.generation.SubmittedGenerationDraft(
            sessionId = "draft-retry-edit", text = "第一条", documents = emptyList(), imageUris = emptyList(),
            recoveryReady = true, recoveryApplied = true,
        ))
        runCurrent()
        viewModel.sendMessage("第一条")
        runCurrent()
        viewModel.updateInputText("第二条")
        gate.complete(Unit); advanceUntilIdle()
        assertThat(savedMessages.filter { it.second == "draft-retry-edit" && it.first.role == MessageRole.USER }).isEmpty()
        assertThat(viewModel.inputText.value).contains("第一条")
        assertThat(viewModel.inputText.value).contains("第二条")
    }

    @Test
    fun `发送受理前切页后失败仍能恢复原会话草稿`() = runTest {
        val document = MessageDocumentAttachment(
            id = "preparing-large", name = "large.txt", mimeType = "text/plain",
            content = "中".repeat(200_000), sizeBytes = 600_000,
            sha256 = "preparing-large-hash", estimatedTokens = 200_000,
        )
        io.mockk.coEvery { documentReader.read(any()) } returns DocumentReadResult.Success(document)
        ApplicationProvider.getApplicationContext<TestInfinityApplication>()
            .getSharedPreferences("infinity_settings", 0).edit()
            .putInt("model_info_gpt-4o_context", 128_000).commit()
        seedSession("preparing-b"); advanceUntilIdle()
        seedSession("preparing-a"); advanceUntilIdle()
        viewModel.importFullContextDocument(android.net.Uri.parse("content://test/preparing-large"))
        advanceUntilIdle()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        afterInsert = { message, session ->
            if (session == "preparing-a" && message.role == MessageRole.USER) {
                entered.complete(Unit); resume.await()
            }
        }
        viewModel.sendMessage("准备中的草稿")
        entered.await()
        viewModel.saveCurrentDraft()
        viewModel.loadSession("preparing-b"); runCurrent()
        resume.complete(Unit); advanceUntilIdle()
        viewModel.loadSession("preparing-a"); advanceUntilIdle()
        assertThat(viewModel.inputText.value).isEqualTo("准备中的草稿")
        assertThat(viewModel.draftDocuments.value).containsExactly(document)
    }

    @Test
    fun `发送受理前页面销毁后草稿保留在会话恢复状态`() = runTest {
        val document = MessageDocumentAttachment(
            id = "preparing-doc", name = "small.txt", mimeType = "text/plain",
            content = "内容", sizeBytes = 6, sha256 = "preparing-hash", estimatedTokens = 2,
        )
        io.mockk.coEvery { documentReader.read(any()) } returns DocumentReadResult.Success(document)
        seedSession("preparing-destroy"); advanceUntilIdle()
        viewModel.importFullContextDocument(android.net.Uri.parse("content://test/preparing-doc"))
        advanceUntilIdle()
        val entered = CompletableDeferred<Unit>()
        afterInsert = { message, session ->
            if (session == "preparing-destroy" && message.role == MessageRole.USER) {
                entered.complete(Unit); kotlinx.coroutines.awaitCancellation()
            }
        }
        viewModel.sendMessage("销毁前草稿")
        entered.await()
        viewModel.saveCurrentDraft()
        ViewModelStore().also { it.put("chat", viewModel) }.clear()
        advanceUntilIdle()
        val recovered = presentationStore.submittedDraft("preparing-destroy")
        assertThat(recovered?.recoveryReady).isTrue()
        assertThat(recovered?.text).isEqualTo("销毁前草稿")
        assertThat(recovered?.documents).containsExactly(document)
    }

    @Test
    fun `返回已载入的会话时重新选择该会话的确认`() = runTest {
        val interactions = ApplicationProvider.getApplicationContext<TestInfinityApplication>().generationInteractions
        val first = async { interactions.awaitDecision("selection-a", com.promenar.infinity.data.generation.GenerationInteractionKind.REPEATED_TOOL) }
        val second = async { interactions.awaitDecision("selection-b", com.promenar.infinity.data.generation.GenerationInteractionKind.REPEATED_TOOL) }
        runCurrent()
        seedSession("selection-a"); advanceUntilIdle()
        seedSession("selection-b"); advanceUntilIdle()
        assertThat(interactions.pending.value?.sessionId).isEqualTo("selection-b")
        viewModel.loadSession("selection-a")
        assertThat(interactions.pending.value?.sessionId).isEqualTo("selection-a")
        interactions.pendingBySession.value.values.toList().forEach { interactions.respond(it.id, false) }
        assertThat(first.await()).isFalse()
        assertThat(second.await()).isFalse()
    }

    @Test
    fun `点击发送立即清空输入框且生成期间的新输入不被接受后清掉`() = runTest {
        seedSession()
        advanceUntilIdle()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("ok"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN),
        )

        viewModel.updateInputText("很长的一段文字")
        viewModel.sendMessage("很长的一段文字")
        assertThat(viewModel.inputText.value).isEmpty()
        assertThat(viewModel.draftConsumptionEpoch.value).isEqualTo(1L)

        viewModel.updateInputText("下一条")
        advanceUntilIdle()

        assertThat(savedMessages.map { it.first.content }).contains("很长的一段文字")
        assertThat(viewModel.inputText.value).isEqualTo("下一条")
        assertThat(viewModel.draftConsumptionEpoch.value).isEqualTo(1L)
    }

    @Test
    fun sendMessage_streamingResponseUpdatesStreamingContent() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("Hello"),
            StreamChunk.TextDelta(" world"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN)
        )

        viewModel.sendMessage("hi"); advanceUntilIdle()
        
          

        val session = viewModel.uiState.value.session
        val assistantMsg = session?.messages?.find { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMsg?.content).isEqualTo("Hello world")
        
    }

    @Test
    fun sendMessage_completedGenerationCallsPostProcessor() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("response"),
            StreamChunk.Usage(ProtocolUsage(input = 10, output = 20, total = 30)),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN)
        )

        viewModel.sendMessage("test"); advanceUntilIdle()
        
         

        val session = viewModel.uiState.value.session
        val stats = session?.stats
        assertThat(stats).isNotNull()
        assertThat(stats!!.totalTokens).isEqualTo(30)
        
    }

    @Test
    fun `完成终态被当前VM消费后回到IDLE`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession()
        advanceUntilIdle()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("done"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN),
        )

        viewModel.sendMessage("test")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isGenerating).isFalse()
        assertThat(viewModel.uiState.value.status).isEqualTo(GenerationStatus.IDLE)
    }

    @Test
    fun `完成终态即使状态已回到IDLE也只发出一次完成结果`() = runTest {
        val outcomes = mutableListOf<ChatGenerationOutcome>()
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch(testDispatcher) { viewModel.generationOutcomes.collect { outcomes += it } }
        seedSession()
        advanceUntilIdle()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("done"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN),
        )

        viewModel.sendMessage("test")
        awaitReal { outcomes.isNotEmpty() && viewModel.uiState.value.status == GenerationStatus.IDLE }

        assertThat(viewModel.uiState.value.status).isEqualTo(GenerationStatus.IDLE)
        assertThat(outcomes.map { it.kind }).containsExactly(ChatGenerationOutcome.Kind.COMPLETED)
        assertThat(outcomes.single().sessionId).isEqualTo(viewModel.uiState.value.session?.id)
    }

    @Test
    fun `生成失败发出失败结果`() = runTest {
        val outcomes = mutableListOf<ChatGenerationOutcome>()
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch(testDispatcher) { viewModel.generationOutcomes.collect { outcomes += it } }
        seedSession()
        advanceUntilIdle()
        forcedProviderFailure = ProviderResolution.Failure(
            reason = ProviderResolutionError.API_KEY_MISSING,
            modelId = "gpt-4o",
            providerId = "provider-missing-key",
        )

        viewModel.sendMessage("trigger route failure")
        awaitReal { outcomes.isNotEmpty() }

        assertThat(outcomes.map { it.kind }).containsExactly(ChatGenerationOutcome.Kind.FAILED)
    }

    @Test
    fun `Coordinator终态先到时combine仍保留路由Rejected设置入口直至UI消费`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession()
        advanceUntilIdle()
        forcedProviderFailure = ProviderResolution.Failure(
            reason = ProviderResolutionError.API_KEY_MISSING,
            modelId = "gpt-4o",
            providerId = "provider-missing-key",
        )

        viewModel.sendMessage("trigger route failure")
        advanceUntilIdle()

        assertThat(viewModel.providerResolutionFailure.value?.providerId)
            .isEqualTo("provider-missing-key")
        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.generationNotice?.code)
            .isEqualTo("generation.failure.auth")
        assertThat(viewModel.uiState.value.generationNotice?.formatArgs).isEmpty()
        assertThat(viewModel.uiState.value.generationNotice?.formatArgs?.joinToString())
            .doesNotContain("provider-missing-key")
        assertThat(viewModel.uiState.value.status).isEqualTo(GenerationStatus.ERROR)
        assertThat(viewModel.uiState.value.isGenerating).isFalse()

        seedSession("clean-session")
        advanceUntilIdle()
        assertThat(viewModel.providerResolutionFailure.value).isNull()
        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.generationNotice).isNull()
        assertThat(viewModel.ragPhases.value).isEmpty()
        assertThat(viewModel.uiState.value.status).isEqualTo(GenerationStatus.IDLE)
    }

    @Test
    fun stopGeneration_resetsIsGenerating() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        

        assertThat(viewModel.uiState.value.isGenerating).isFalse()
        
    }

    @Test
    fun `等待审批的显式停止立即释放当前owner而不影响其它owner`() = runTest {
        seedSession("ssh-stop", approvalRequest = ApprovalRequest(toolName = "ssh_session_send", args = "{}"))
        advanceUntilIdle()
        sshLeases += listOf("agent:ssh-stop", "agent:other", "ui")

        viewModel.stopGeneration()
        advanceUntilIdle()

        assertThat(sshLeases).containsExactly("agent:other", "ui")
    }

    @Test
    fun `没有活动runner的显式停止也释放当前SSH租约`() = runTest {
        seedSession("ssh-stopped-runner")
        advanceUntilIdle()
        sshLeases += "agent:ssh-stopped-runner"

        viewModel.stopGeneration()
        advanceUntilIdle()

        assertThat(sshLeases).isEmpty()
    }

    @Test
    fun `新回合丢弃待审批任务时释放上一回合SSH租约`() = runTest {
        seedSession("ssh-new-turn", approvalRequest = ApprovalRequest(toolName = "ssh_session_send", args = "{}"))
        advanceUntilIdle()
        sshLeases += "agent:ssh-new-turn"

        viewModel.sendMessage("new question")
        advanceUntilIdle()

        assertThat(sshLeases).isEmpty()
    }

    @Test
    fun `待审批取消挂起期间连续发送保留第一份草稿并将第二份入队`() = runTest {
        seedSession("approval-race", approvalRequest = ApprovalRequest(toolName = "write_file", args = "{}"))
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        holdSessionUpdates = gate
        viewModel.sendMessage("第一份")
        runCurrent()
        viewModel.sendMessage("第二份")
        runCurrent()
        val firstDraft = presentationStore.submittedDraft("approval-race")?.text
        val queued = queueStore.snapshot("approval-race").items.map { it.text }
        holdSessionUpdates = null
        gate.complete(Unit); advanceUntilIdle()
        assertThat(firstDraft).isEqualTo("第一份")
        assertThat(queued).containsExactly("第二份")
    }

    @Test
    fun `一个会话的发送准备挂起不阻止或取消另一会话`() = runTest {
        seedSession("preparation-b"); advanceUntilIdle()
        seedSession("preparation-a", approvalRequest = ApprovalRequest(toolName = "write_file", args = "{}")); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        beforeSshClose = { if (!entered.isCompleted) { entered.complete(Unit); gate.await() } }
        viewModel.sendMessage("会话 A")
        runCurrent()
        assertThat(entered.isCompleted).isTrue()
        viewModel.loadSession("preparation-b"); advanceUntilIdle()
        viewModel.sendMessage("会话 B"); advanceUntilIdle()
        val sentB = savedMessages.any { it.second == "preparation-b" && it.first.role == MessageRole.USER && it.first.content == "会话 B" }
        val retainedA = presentationStore.submittedDraft("preparation-a")?.text
        gate.complete(Unit); advanceUntilIdle()
        assertThat(sentB).isTrue()
        assertThat(retainedA).isEqualTo("会话 A")
    }

    @Test
    fun `一个会话的消息入库挂起不阻止另一会话入库`() = runTest {
        seedSession("admission-b"); advanceUntilIdle()
        seedSession("admission-a"); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        afterInsert = { message, sessionId ->
            if (sessionId == "admission-a" && message.role == MessageRole.USER && !entered.isCompleted) {
                entered.complete(Unit)
                gate.await()
            }
        }
        viewModel.sendMessage("入库 A"); runCurrent()
        assertThat(entered.isCompleted).isTrue()
        viewModel.loadSession("admission-b"); advanceUntilIdle()
        viewModel.sendMessage("入库 B"); advanceUntilIdle()
        val sentB = savedMessages.any { it.second == "admission-b" && it.first.role == MessageRole.USER && it.first.content == "入库 B" }
        gate.complete(Unit); advanceUntilIdle()
        assertThat(sentB).isTrue()
    }

    @Test
    fun `清空历史终止待审批任务并释放SSH租约`() = runTest {
        seedSession("ssh-clear", approvalRequest = ApprovalRequest(toolName = "ssh_session_send", args = "{}"))
        advanceUntilIdle()
        sshLeases += "agent:ssh-clear"

        viewModel.clearHistory()
        advanceUntilIdle()

        assertThat(sshLeases).isEmpty()
    }

    @Test
    fun `新回合在显式停止的SSH清理完成前不得启动Provider`() = runTest {
        seedSession("ssh-stop-order")
        advanceUntilIdle()
        sshLeases += "agent:ssh-stop-order"
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeSshClose = { cleaning.complete(Unit); release.await() }

        viewModel.stopGeneration()
        cleaning.await()
        viewModel.sendMessage("next question")
        runCurrent()
        assertThat(protocolRequestCount).isEqualTo(0)

        release.complete(Unit)
        advanceUntilIdle()
        assertThat(sshLeases).isEmpty()
        assertThat(protocolRequestCount).isEqualTo(1)
    }

    @Test
    fun `遗留审批被终止后立即清除SSH租约`() = runTest {
        val request = ApprovalRequest(toolName = "ssh_session_send", args = "{}")
        seedSession("ssh-obsolete-approval", approvalRequest = request)
        advanceUntilIdle()
        sshLeases += "agent:ssh-obsolete-approval"

        viewModel.approveRequest(request)
        advanceUntilIdle()

        assertThat(sshLeases).isEmpty()
        val session = (ApplicationProvider.getApplicationContext() as TestInfinityApplication).chatStore.getSession("ssh-obsolete-approval")!!
        assertThat(session.approvalRequest).isNull()
        assertThat(session.loopStatus).isEqualTo(LoopStatus.PAUSED)
    }

    @Test
    fun `部分审批首次批准创建PTY后待审批资源必须登记租约`() = runTest {
        val batch = sshOpenApprovalBatch()
        seedSession("ssh-partial-open", approvalRequest = batch)
        advanceUntilIdle()
        viewModel.settleApprovalSshOwner("ssh-partial-open", expectedTaskId = null)
        assertThat(pausedSshOwners).isEmpty()

        // 首次等待时没有连接；批准 open 创建 PTY，剩余 exec 审批仍等待用户决定。
        sshLeases += listOf("agent:ssh-partial-open", "agent:other", "ui")
        val remaining = ToolApprovalRequestFactory.createFromIdentities(
            batch.assistantMessageId!!, batch.calls.drop(1),
        )
        val store = ApplicationProvider.getApplicationContext<TestInfinityApplication>().chatStore
        store.updateSession("ssh-partial-open") { it.copy(approvalRequest = remaining) }

        viewModel.settleApprovalSshOwner("ssh-partial-open", expectedTaskId = null)
        advanceUntilIdle()

        assertThat(pausedSshOwners).containsExactly("agent:ssh-partial-open")
        assertThat(sshLeases).containsExactly("agent:ssh-partial-open", "agent:other", "ui")
        assertThat(store.getSession("ssh-partial-open")!!.approvalRequest!!.calls.map { it.toolName })
            .containsExactly("ssh_exec")
    }

    @Test
    fun `部分审批旧任务收尾不得暂停新任务资源`() = runTest {
        val batch = sshOpenApprovalBatch()
        seedSession("ssh-partial-new-task", approvalRequest = batch)
        advanceUntilIdle()
        holdStreamOpen = true
        viewModel.sendMessage("next question")
        advanceUntilIdle()
        assertThat(protocolRequestCount).isEqualTo(1)
        val store = ApplicationProvider.getApplicationContext<TestInfinityApplication>().chatStore
        store.updateSession("ssh-partial-new-task") {
            it.copy(approvalRequest = batch, loopStatus = LoopStatus.WAITING_FOR_APPROVAL)
        }
        sshLeases += "agent:ssh-partial-new-task"

        viewModel.settleApprovalSshOwner("ssh-partial-new-task", expectedTaskId = "previous-task")
        advanceUntilIdle()

        assertThat(pausedSshOwners).isEmpty()
        assertThat(sshLeases).containsExactly("agent:ssh-partial-new-task")
    }

    @Test
    fun `部分审批实际执行前清除当前owner旧等待租约`() = runTest {
        seedSession("ssh-partial-execute", approvalRequest = sshOpenApprovalBatch())
        advanceUntilIdle()
        val owner = "agent:ssh-partial-execute"
        sshLeases += listOf(owner, "agent:other", "ui")
        pausedSshOwners += listOf(owner, "agent:other", "ui")
        var waitingLeaseDuringExecution = true

        viewModel.executeWithApprovedSshOwner("ssh-partial-execute") {
            waitingLeaseDuringExecution = owner in pausedSshOwners
        }
        advanceUntilIdle()

        assertThat(waitingLeaseDuringExecution).isFalse()
        assertThat(pausedSshOwners).containsExactly("agent:other", "ui")
        assertThat(sshLeases).containsExactly(owner, "agent:other", "ui")
    }

    private fun sshOpenApprovalBatch(): ApprovalRequest {
        val calls = listOf(
            ToolCall("partial-open", "ssh_session_open", """{"host":"local"}"""),
            ToolCall("partial-exec", "ssh_exec", """{"host":"local","command":"echo ready"}"""),
        )
        val candidates = calls.map { call ->
            val tool = ProtocolTool(
                function = ProtocolToolFunction(call.name, "SSH 租约测试", "{}"),
                risk = com.promenar.infinity.domain.tool.ToolRisk.PROCESS,
                runtimeToolId = "test:${call.name}",
                sourceId = "test",
            )
            val identity = (ToolInvocationIdentityFactory.fromPreparedToolCall(call, tool, true)
                as ToolInvocationIdentityResolution.Valid).identity
            ApprovalCallCandidate(
                call, com.promenar.infinity.domain.tool.ToolRisk.PROCESS, identity,
                com.promenar.infinity.domain.tool.ToolExecutionTargets.extract(tool, call.arguments),
            )
        }
        return ToolApprovalRequestFactory.create("partial-assistant", candidates)
    }

    @Test
    fun `FGS启动不允许时提示回复仍在生成而不是误报取消`() = runTest {
        seedSession("fgs-not-allowed")
        advanceUntilIdle()
        holdStreamOpen = true
        foregroundStartResult = com.promenar.infinity.background.generation.ForegroundStartResult.NotAllowed(
            android.app.ForegroundServiceStartNotAllowedException("blocked"),
        )

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.backgroundWarning?.message)
            .isEqualTo(BACKGROUND_UNAVAILABLE_MESSAGE)
        assertThat(viewModel.uiState.value.isGenerating).isTrue()
    }

    @Test
    fun `FGS权限异常时降级提示且应用内Stop仍可取消当前task`() = runTest {
        seedSession("fgs-permission")
        advanceUntilIdle()
        holdStreamOpen = true
        foregroundStartResult = com.promenar.infinity.background.generation.ForegroundStartResult.PermissionDenied(
            SecurityException("fgs permission"),
        )

        viewModel.sendMessage("hello")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.backgroundWarning?.message)
            .isEqualTo(BACKGROUND_UNAVAILABLE_MESSAGE)

        viewModel.stopGeneration()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isGenerating).isFalse()
    }

    @Test
    fun `通知未授权时生成继续并按taskId发出一次权限请求且授权后清warning`() = runTest {
        seedSession("notification-permission")
        advanceUntilIdle()
        holdStreamOpen = true
        foregroundStartResult =
            com.promenar.infinity.background.generation.ForegroundStartResult.StartedWithoutNotifications
        assertThat(viewModel.notificationPermissionRequests.value).isNull()
        viewModel.sendMessage("hello")
        advanceUntilIdle()

        val permissionRequest = requireNotNull(viewModel.notificationPermissionRequests.value)
        val warning = viewModel.uiState.value.backgroundWarning!!
        assertThat(permissionRequest.taskId).isEqualTo(warning.taskId)
        assertThat(warning.kind).isEqualTo(BackgroundWarningKind.NOTIFICATIONS_UNAVAILABLE)
        assertThat(viewModel.uiState.value.isGenerating).isTrue()

        viewModel.onNotificationPermissionResult(permissionRequest.taskId, granted = true)
        assertThat(viewModel.uiState.value.backgroundWarning).isNull()
        assertThat(viewModel.notificationPermissionRequests.value).isNull()
    }

    @Test
    fun `Android13首次发送先等待权限结果且不落消息不启动生成`() = runTest {
        notificationPermissionState =
            com.promenar.infinity.background.generation.NotificationPermissionPromptState(
                sdkInt = 35,
                granted = false,
                alreadyAsked = false,
            )
        seedSession("permission-first-send")
        advanceUntilIdle()

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        assertThat(viewModel.notificationPermissionRequests.value).isNotNull()
        assertThat(savedMessages).isEmpty()
        assertThat(protocolRequestCount).isEqualTo(0)
        assertThat(foregroundTrackedTasks).isEmpty()
        assertThat(viewModel.uiState.value.isGenerating).isFalse()
        assertThat(viewModel.uiState.value.status).isEqualTo(GenerationStatus.IDLE)
    }

    @Test
    fun `首次授权后恢复后台生成并清除权限请求`() = runTest {
        notificationPermissionState =
            com.promenar.infinity.background.generation.NotificationPermissionPromptState(
                sdkInt = 35,
                granted = false,
                alreadyAsked = false,
            )
        seedSession("permission-granted")
        advanceUntilIdle()
        viewModel.sendMessage("hello")
        advanceUntilIdle()
        val token = requireNotNull(viewModel.notificationPermissionRequests.value).taskId

        notificationPermissionState = notificationPermissionState!!.copy(
            granted = true,
            alreadyAsked = true,
        )
        viewModel.onNotificationPermissionResult(token, granted = true)
        advanceUntilIdle()

        assertThat(viewModel.notificationPermissionRequests.value).isNull()
        assertThat(savedMessages.map { it.first.role })
            .containsExactly(MessageRole.USER, MessageRole.ASSISTANT)
            .inOrder()
        assertThat(protocolRequestCount).isEqualTo(1)
        assertThat(foregroundTrackedTasks.map { it.sessionId })
            .containsExactly("permission-granted")
    }

    @Test
    fun `首次拒绝后仅前台生成且后续发送不重复提示不启动前台服务`() = runTest {
        notificationPermissionState =
            com.promenar.infinity.background.generation.NotificationPermissionPromptState(
                sdkInt = 35,
                granted = false,
                alreadyAsked = false,
            )
        seedSession("permission-denied")
        advanceUntilIdle()
        viewModel.sendMessage("first")
        advanceUntilIdle()
        val token = requireNotNull(viewModel.notificationPermissionRequests.value).taskId

        notificationPermissionState = notificationPermissionState!!.copy(alreadyAsked = true)
        viewModel.onNotificationPermissionResult(token, granted = false)
        advanceUntilIdle()

        assertThat(viewModel.notificationPermissionRequests.value).isNull()
        assertThat(protocolRequestCount).isEqualTo(1)
        assertThat(savedMessages).hasSize(2)
        assertThat(foregroundTrackedTasks).isEmpty()

        viewModel.sendMessage("second")
        advanceUntilIdle()

        assertThat(viewModel.notificationPermissionRequests.value).isNull()
        assertThat(protocolRequestCount).isEqualTo(2)
        assertThat(savedMessages).hasSize(4)
        assertThat(foregroundTrackedTasks).isEmpty()
    }

    @Test
    fun `取消或切换会话后过期权限token不能重放待发送消息`() = runTest {
        notificationPermissionState =
            com.promenar.infinity.background.generation.NotificationPermissionPromptState(
                sdkInt = 35,
                granted = false,
                alreadyAsked = false,
            )
        seedSession("permission-stale-A")
        advanceUntilIdle()
        viewModel.sendMessage("cancelled")
        advanceUntilIdle()
        val cancelledToken = requireNotNull(viewModel.notificationPermissionRequests.value).taskId

        viewModel.stopGeneration()
        viewModel.onNotificationPermissionResult(cancelledToken, granted = true)
        advanceUntilIdle()
        assertThat(savedMessages).isEmpty()
        assertThat(protocolRequestCount).isEqualTo(0)

        viewModel.sendMessage("switched")
        advanceUntilIdle()
        val switchedToken = requireNotNull(viewModel.notificationPermissionRequests.value).taskId
        seedSession("permission-stale-B")
        advanceUntilIdle()
        viewModel.onNotificationPermissionResult(switchedToken, granted = true)
        advanceUntilIdle()

        assertThat(viewModel.notificationPermissionRequests.value).isNull()
        assertThat(savedMessages).isEmpty()
        assertThat(protocolRequestCount).isEqualTo(0)
    }

    @Test
    fun `权限回调排队后切页仍可恢复原会话草稿`() = runTest {
        notificationPermissionState = com.promenar.infinity.background.generation.NotificationPermissionPromptState(35, false, false)
        seedSession("permission-race-b"); advanceUntilIdle()
        seedSession("permission-race-a"); advanceUntilIdle()
        viewModel.sendMessage("待授权草稿"); advanceUntilIdle()
        val token = requireNotNull(viewModel.notificationPermissionRequests.value).taskId
        @Suppress("UNCHECKED_CAST")
        val locks = ChatViewModel::class.java.getDeclaredField("sendPreparationMutexes").apply { isAccessible = true }
            .get(viewModel) as java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>
        val lock = requireNotNull(locks["permission-race-a"])
        lock.lock()
        viewModel.onNotificationPermissionResult(token, granted = true)
        viewModel.loadSession("permission-race-b")
        lock.unlock()
        advanceUntilIdle()
        viewModel.loadSession("permission-race-a"); advanceUntilIdle()
        assertThat(viewModel.inputText.value).isEqualTo("待授权草稿")
    }

    @Test
    fun `clearError不清除独立后台warning`() = runTest {
        seedSession("warning-clear")
        advanceUntilIdle()
        holdStreamOpen = true
        foregroundStartResult =
            com.promenar.infinity.background.generation.ForegroundStartResult.StartedWithoutNotifications

        viewModel.sendMessage("hello")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.backgroundWarning).isNotNull()

        viewModel.clearError()
        assertThat(viewModel.uiState.value.backgroundWarning).isNotNull()
    }

    @Test
    fun `后台promotion失败在终态竞态后仍显示同task停止错误且切session清理`() = runTest {
        seedSession("warning-A")
        advanceUntilIdle()
        holdStreamOpen = true
        viewModel.sendMessage("hello")
        advanceUntilIdle()
        val taskId = foregroundTrackedTasks.single().taskId

        foregroundFailures.tryEmit(
            com.promenar.infinity.background.generation.ForegroundServiceFailure(
                "stale",
                com.promenar.infinity.background.generation.ForegroundServiceFailureOutcome.GENERATION_STOPPED,
            ),
        )
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isNull()

        viewModel.stopGeneration()
        advanceUntilIdle()
        foregroundFailures.tryEmit(
            com.promenar.infinity.background.generation.ForegroundServiceFailure(
                taskId,
                com.promenar.infinity.background.generation.ForegroundServiceFailureOutcome.GENERATION_STOPPED,
            ),
        )
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isEqualTo(BACKGROUND_SERVICE_STOPPED_MESSAGE)

        seedSession("warning-B")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `停止错误遮挡普通错误时两次清理按展示层依次暴露`() = runTest {
        seedSession("layered-errors")
        advanceUntilIdle()
        holdStreamOpen = true
        viewModel.sendMessage("hello")
        advanceUntilIdle()
        val taskId = foregroundTrackedTasks.single().taskId
        foregroundFailures.tryEmit(
            com.promenar.infinity.background.generation.ForegroundServiceFailure(
                taskId,
                com.promenar.infinity.background.generation.ForegroundServiceFailureOutcome.GENERATION_STOPPED,
            ),
        )
        advanceUntilIdle()

        @Suppress("UNCHECKED_CAST")
        val ordinaryError = ChatViewModel::class.java.getDeclaredField("_error").run {
            isAccessible = true
            get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<String?>
        }
        ordinaryError.value = "new provider error"
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isEqualTo(BACKGROUND_SERVICE_STOPPED_MESSAGE)

        viewModel.clearError()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isEqualTo("new provider error")

        viewModel.clearError()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `界面重建后重新载入同一会话保留未发送的输入`() = runTest {
        seedSession("A")
        advanceUntilIdle()
        viewModel.updateInputText("还没发送的话")

        viewModel.loadSession("A")
        advanceUntilIdle()
        assertThat(viewModel.inputText.value).isEqualTo("还没发送的话")

        seedSession("B")
        advanceUntilIdle()
        assertThat(viewModel.inputText.value).isEmpty()
    }

    @Test
    fun `离开会话时清空的草稿在ViewModel随即清理后仍然生效`() = runTest {
        seedSession("A")
        advanceUntilIdle()
        viewModel.updateInputText("旧草稿")
        viewModel.saveCurrentDraft()
        advanceUntilIdle()
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        assertThat(app.chatStore.getSession("A")!!.draft).isEqualTo("旧草稿")

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        holdSessionUpdates = gate
        viewModel.updateInputText("")
        viewModel.saveCurrentDraft()
        ViewModelStore().also { it.put("chat", viewModel) }.clear()
        // 重新进入会话读的是内存会话，写库完成前就必须是新草稿。
        assertThat(app.chatStore.getSession("A")!!.draft).isNull()
        holdSessionUpdates = null
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(app.chatStore.getSession("A")!!.draft).isNull()
        assertThat(savedSessions.first { it.id == "A" }.draft).isNull()
    }

    @Test
    fun `生成中界面重建后重新载入同一会话不重置生成状态`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession("A")
        advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("partial"))
        holdStreamOpen = true
        viewModel.sendMessage("first")
        advanceUntilIdle()
        val before = viewModel.uiState.value
        assertThat(before.isGenerating).isTrue()
        assertThat(before.streamingContent).isEqualTo("partial")

        viewModel.loadSession("A")
        advanceUntilIdle()

        val after = viewModel.uiState.value
        assertThat(after.isGenerating).isTrue()
        assertThat(after.status).isEqualTo(before.status)
        assertThat(after.streamingContent).isEqualTo("partial")
    }

    @Test
    fun `A和B同时生成且切换与停止只影响当前会话`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession("A")
        advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("partial-A"))
        holdStreamOpen = true
        viewModel.sendMessage("from A")
        advanceUntilIdle()
        assertThat(protocolRequestCount).isEqualTo(1)
        assertThat(foregroundTrackedTasks.map { it.sessionId }).containsExactly("A")

        seedSession("B")
        advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("partial-B"))
        viewModel.updateInputText("from B")
        viewModel.sendMessage("from B")
        advanceUntilIdle()

        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        assertThat(app.chatStore.getSession("B")!!.messages).isNotEmpty()
        assertThat(viewModel.uiState.value.isGenerating).isTrue()
        assertThat(viewModel.uiState.value.streamingContent).isEqualTo("partial-B")
        assertThat(protocolRequestCount).isEqualTo(2)
        assertThat(foregroundTrackedTasks.map { it.sessionId }).containsExactly("A", "B")
        assertThat(providerCancelled).isFalse()

        viewModel.stopGeneration()
        advanceUntilIdle()
        assertThat(providerCancelled).isFalse()

        viewModel.loadSession("A")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isGenerating).isTrue()
        assertThat(viewModel.uiState.value.streamingContent).isEqualTo("partial-A")
        viewModel.stopGeneration()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isGenerating).isFalse()
        assertThat(providerCancelled).isFalse()
    }

    @Test
    fun `生成中再次发送保留当前轮并清空排队草稿`() = runTest {
        seedSession("A")
        advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("partial"))
        holdStreamOpen = true

        viewModel.sendMessage("first")
        advanceUntilIdle()
        viewModel.updateInputText("second")
        viewModel.sendMessage("second")
        advanceUntilIdle()

        val session = ApplicationProvider.getApplicationContext<InfinityApplication>()
            .chatStore.getSession("A")!!
        assertThat(session.messages.map { it.content }).containsExactly("first", "partial").inOrder()
        assertThat(protocolRequestCount).isEqualTo(1)
        assertThat(viewModel.inputText.value).isEmpty()
    }

    @Test
    fun `首轮仍在准备时第二次发送入队且不取消首轮`() = runTest {
        seedSession("A"); advanceUntilIdle()
        val inserted = CompletableDeferred<Unit>()
        afterInsert = { message, _ ->
            if (message.role == MessageRole.ASSISTANT) { inserted.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        }
        viewModel.sendMessage("first")
        inserted.await()
        viewModel.updateInputText("second")
        viewModel.sendMessage("second")
        advanceUntilIdle()
        assertThat(viewModel.composerQueue.value.items.single().text).isEqualTo("second")
        assertThat(viewModel.inputText.value).isEmpty()
        assertThat(savedMessages.map { it.first.content }).contains("first")
        viewModel.stopGeneration(); advanceUntilIdle()
    }

    @Test
    fun `成功后自动发送队首且保留生成期间新草稿`() = runTest {
        seedSession(); advanceUntilIdle()
        val gate = CompletableDeferred<List<StreamChunk>>()
        streamGate = gate
        fakeStreamChunks = listOf(StreamChunk.TextDelta("首轮"))
        viewModel.sendMessage("first"); advanceUntilIdle()
        viewModel.updateInputText("second"); viewModel.sendMessage("second"); advanceUntilIdle()
        viewModel.updateInputText("待编辑")
        streamGate = null
        fakeStreamChunks = listOf(StreamChunk.TextDelta("次轮"), StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
        gate.complete(listOf(StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN)))
        awaitReal { protocolRequestCount == 2 && viewModel.composerQueue.value.items.isEmpty() && !viewModel.uiState.value.isGenerating }
        assertThat(protocolRequestCount).isEqualTo(2)
        assertThat(viewModel.composerQueue.value.items).isEmpty()
        assertThat(viewModel.inputText.value).isEqualTo("待编辑")
        assertThat(savedMessages.filter { it.first.role == MessageRole.USER }.map { it.first.content }).containsExactly("first", "second").inOrder()
    }

    @Test
    fun `生成中发送的技能命令进入队列原样保留，出队生成时预加载该技能`() = runTest {
        slashSkillCatalog.skills = listOf(com.promenar.infinity.ui.chat.command.slashTestSkill("demo"))
        seedSession(); advanceUntilIdle()
        val gate = CompletableDeferred<List<StreamChunk>>()
        streamGate = gate
        fakeStreamChunks = listOf(StreamChunk.TextDelta("首轮"))
        viewModel.sendMessage("first"); advanceUntilIdle()
        val firstMessages = lastPromptRequest!!.messages
        val firstSystem = firstMessages.first { it.role == "system" }.content
        val firstContext = firstMessages.last { it.role == "user" }.turnContext
        assertThat(firstContext).doesNotContain("## Requested Skill")
        viewModel.sendMessage("/demo 整理本周进展"); advanceUntilIdle()
        assertThat(viewModel.composerQueue.value.items.map { it.text }).containsExactly("/demo 整理本周进展")
        streamGate = null
        fakeStreamChunks = listOf(StreamChunk.TextDelta("次轮"), StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
        gate.complete(listOf(StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN)))
        awaitReal { protocolRequestCount == 2 && viewModel.composerQueue.value.items.isEmpty() && !viewModel.uiState.value.isGenerating }
        assertThat(protocolRequestCount).isEqualTo(2)
        val messages = lastPromptRequest!!.messages
        val system = messages.first { it.role == "system" }.content
        assertThat(system).isEqualTo(firstSystem)
        assertThat(system).doesNotContain("## Requested Skill")
        assertThat(system).doesNotContain("Body of demo")
        val queuedUser = messages.last { it.role == "user" }
        assertThat(queuedUser.turnContext).contains("## Requested Skill")
        assertThat(queuedUser.turnContext).contains("# Skill: demo")
        assertThat(queuedUser.turnContext).contains("Body of demo")
        assertThat(queuedUser.content).isEqualTo("/demo 整理本周进展")
        assertThat(messages.first { it.role == "user" }.turnContext).isEqualTo(firstContext)
        assertThat(savedMessages.filter { it.first.role == MessageRole.USER }.map { it.first.content })
            .containsExactly("first", "/demo 整理本周进展").inOrder()
    }

    @Test
    fun `队列未暂停时用户停止，终态取消不把暂停原因改成离开会话`() = runTest {
        seedSession(); advanceUntilIdle()
        holdStreamOpen = true
        viewModel.sendMessage("first"); advanceUntilIdle()
        viewModel.sendMessage("queued"); advanceUntilIdle()
        assertThat(viewModel.composerQueue.value.pauseReason).isNull()
        stoppedPauseGate = CompletableDeferred()
        stoppedPauseDone = CompletableDeferred()
        viewModel.stopGeneration(); advanceUntilIdle()
        // 终态处理没有再写暂停时放行停止的写入，避免测试悬挂。
        stoppedPauseGate?.complete(Unit); advanceUntilIdle()
        assertThat(viewModel.composerQueue.value.items.map { it.text }).containsExactly("queued")
        assertThat(viewModel.composerQueue.value.pauseReason).isEqualTo(ComposerQueuePauseReason.STOPPED)
    }

    @Test
    fun `失败和用户停止分别暂停队列`() = runTest {
        seedSession(); advanceUntilIdle()
        val gate = CompletableDeferred<List<StreamChunk>>()
        streamGate = gate
        fakeStreamChunks = listOf(StreamChunk.TextDelta("partial"))
        viewModel.sendMessage("first"); advanceUntilIdle()
        viewModel.sendMessage("queued"); advanceUntilIdle()
        gate.complete(emptyList()); advanceUntilIdle()
        assertThat(viewModel.composerQueue.value.pauseReason).isEqualTo(ComposerQueuePauseReason.FAILED)
        streamGate = null
        holdStreamOpen = true
        viewModel.sendMessage("retry"); advanceUntilIdle()
        viewModel.stopGeneration(); advanceUntilIdle()
        assertThat(viewModel.composerQueue.value.pauseReason).isEqualTo(ComposerQueuePauseReason.STOPPED)
        assertThat(protocolRequestCount).isEqualTo(2)
    }

    @Test
    fun `暂停中插队优先发送且继续发送剩余队列`() = runTest {
        seedSession(); advanceUntilIdle()
        queueStore.enqueue("s1", "first")
        val steer = queueStore.enqueue("s1", "steer")
        queueStore.pause("s1", com.promenar.infinity.data.chat.queue.QueuePauseReason.FAILED)
        advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("done"), StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
        viewModel.steerQueuedMessage(steer.id)
        // 剩余队首在同一生成循环的边界经真实 IO 注入，需等待其完成。
        awaitReal { viewModel.composerQueue.value.items.isEmpty() && !viewModel.uiState.value.isGenerating }
        assertThat(savedMessages.filter { it.first.role == MessageRole.USER }.map { it.first.content }).containsExactly("steer", "first").inOrder()
        assertThat(viewModel.composerQueue.value.items).isEmpty()
    }

    @Test
    fun `另一会话生成时仍可继续当前会话的队列`() = runTest {
        seedSession("A"); advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("partial")); holdStreamOpen = true
        viewModel.sendMessage("running"); advanceUntilIdle()
        seedSession("B"); advanceUntilIdle()
        queueStore.enqueue("B", "queued")
        viewModel.resumeComposerQueue(); advanceUntilIdle()
        assertThat(viewModel.composerQueue.value.pauseReason).isNull()
        assertThat(savedMessages.any { it.second == "B" && it.first.role == MessageRole.USER }).isTrue()
    }

    @Test
    fun `切走生成会话与打开无活动队列均暂停INTERRUPTED`() = runTest {
        seedSession("A"); advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("partial")); holdStreamOpen = true
        viewModel.sendMessage("running"); advanceUntilIdle()
        viewModel.sendMessage("queued"); advanceUntilIdle()
        seedSession("B"); advanceUntilIdle()
        assertThat(queueStore.snapshot("A").pauseReason).isEqualTo(com.promenar.infinity.data.chat.queue.QueuePauseReason.INTERRUPTED)
        queueStore.enqueue("B", "later")
        viewModel.loadSession("A"); advanceUntilIdle()
        viewModel.loadSession("B"); advanceUntilIdle()
        assertThat(viewModel.composerQueue.value.pauseReason).isEqualTo(ComposerQueuePauseReason.INTERRUPTED)
    }

    @Test
    fun `队列发送准备被拒回到队首且不污染输入框`() = runTest {
        seedSession(); advanceUntilIdle()
        val doc = MessageDocumentAttachment("large", "large.txt", "text/plain", "中".repeat(200_000), 600_000, "hash", 200_000)
        ApplicationProvider.getApplicationContext<TestInfinityApplication>().getSharedPreferences("infinity_settings", 0).edit().putInt("model_info_gpt-4o_context", 128_000).commit()
        queueStore.enqueue("s1", "queued", documents = listOf(doc))
        queueStore.pause("s1", com.promenar.infinity.data.chat.queue.QueuePauseReason.FAILED)
        viewModel.updateInputText("保留草稿")
        viewModel.resumeComposerQueue()
        awaitReal { viewModel.composerQueue.value.items.isNotEmpty() && viewModel.composerQueue.value.pauseReason == ComposerQueuePauseReason.FAILED }
        assertThat(viewModel.composerQueue.value.items.single().text).isEqualTo("queued")
        assertThat(viewModel.composerQueue.value.pauseReason).isEqualTo(ComposerQueuePauseReason.FAILED)
        assertThat(viewModel.inputText.value).isEqualTo("保留草稿")
        assertThat(savedMessages).isEmpty()
    }

    @Test
    fun `编辑队列只在无文字文档图片时接受并恢复文档`() = runTest {
        seedSession(); advanceUntilIdle()
        val doc = MessageDocumentAttachment("d", "a.txt", "text/plain", "内容", 6, "hash", 2)
        val item = queueStore.enqueue("s1", "queued", documents = listOf(doc)); advanceUntilIdle()
        viewModel.updateInputText("draft")
        assertThat(viewModel.editQueuedMessage(item.id)).isFalse()
        viewModel.updateInputText("")
        viewModel.updateComposerImages(listOf(android.net.Uri.parse("content://selected")))
        assertThat(viewModel.editQueuedMessage(item.id)).isFalse()
        viewModel.updateComposerImages(emptyList())
        assertThat(viewModel.editQueuedMessage(item.id)).isTrue()
        advanceUntilIdle()
        assertThat(viewModel.inputText.value).isEqualTo("queued")
        assertThat(viewModel.draftDocuments.value).containsExactly(doc)
        assertThat(viewModel.composerQueue.value.items).isEmpty()
    }


    @Test
    fun `生成中插队立即取消Provider流且原队列按步骤边界注入`() = runTest {
        seedSession(); advanceUntilIdle()
        val gate = CompletableDeferred<List<StreamChunk>>()
        streamGate = gate
        fakeStreamChunks = listOf(StreamChunk.ToolCallDelta("yield-call", "missing_test_tool", "{}", 0))
        viewModel.sendMessage("first"); advanceUntilIdle()
        viewModel.sendMessage("普通队列"); advanceUntilIdle()
        viewModel.sendMessage("优先指令"); advanceUntilIdle()
        val id = viewModel.composerQueue.value.items.last().id
        streamGate = null
        fakeStreamChunks = listOf(StreamChunk.TextDelta("done"), StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
        viewModel.steerQueuedMessage(id)
        awaitReal { protocolRequestCount >= 3 && viewModel.composerQueue.value.items.isEmpty() }
        assertThat(savedMessages.filter { it.first.role == MessageRole.USER }.map { it.first.content }).containsExactly("first", "优先指令", "普通队列").inOrder()
        val history = savedMessages.map { it.first }
        assertThat(history.first { it.role == MessageRole.TOOL }.toolCallId).isEqualTo("yield-call")
        assertThat(history.first { it.role == MessageRole.ASSISTANT }.status).isEqualTo("interrupted")
        assertThat(gate.isCompleted).isFalse()
        assertThat(history.count { it.role == MessageRole.ASSISTANT }).isEqualTo(3)
    }

    @Test
    fun `生成中插队发送立即注入且普通队列等待当前步骤`() = runTest {
        seedSession(); advanceUntilIdle()
        holdStreamOpen = true; fakeStreamChunks = listOf(StreamChunk.TextDelta("partial"))
        viewModel.sendMessage("first"); advanceUntilIdle()
        viewModel.sendMessage("普通队列"); advanceUntilIdle()
        viewModel.sendMessageSteering("优先指令")
        awaitReal { protocolRequestCount == 2 && savedMessages.any { it.first.role == MessageRole.USER && it.first.content == "优先指令" } }
        val items = viewModel.composerQueue.value.items
        assertThat(items.map { it.text }).containsExactly("普通队列")
        assertThat(savedMessages.count { it.first.role == MessageRole.USER && it.first.content == "优先指令" }).isEqualTo(1)
        assertThat(viewModel.inputText.value).isEmpty()
    }

    @Test
    fun `停止并立即发送取消本轮后先发草稿再继续原队列`() = runTest {
        seedSession(); advanceUntilIdle()
        holdStreamOpen = true; fakeStreamChunks = listOf(StreamChunk.TextDelta("partial"))
        viewModel.sendMessage("first"); advanceUntilIdle()
        viewModel.sendMessage("普通队列"); advanceUntilIdle()
        holdStreamOpen = false
        fakeStreamChunks = listOf(StreamChunk.TextDelta("done"), StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
        val enqueue = CompletableDeferred<Unit>()
        queueEnqueueGate = enqueue
        viewModel.stopAndSendNow("立即发送")
        advanceUntilIdle()
        assertThat(protocolRequestCount).isEqualTo(1)
        assertThat(savedMessages.first { it.first.role == MessageRole.ASSISTANT }.first.status).isEqualTo("cancelled")
        enqueue.complete(Unit)
        awaitReal { viewModel.composerQueue.value.items.isEmpty() && savedMessages.count { it.first.role == MessageRole.USER } >= 3 }
        assertThat(savedMessages.filter { it.first.role == MessageRole.USER }.map { it.first.content })
            .containsExactly("first", "立即发送", "普通队列").inOrder()
        assertThat(viewModel.composerQueue.value.pauseReason).isNull()
    }

    @Test
    fun `未在生成时停止并立即发送等同普通发送`() = runTest {
        seedSession(); advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("done"), StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
        viewModel.stopAndSendNow("直接发送")
        awaitReal { savedMessages.any { it.first.role == MessageRole.USER } }
        assertThat(savedMessages.filter { it.first.role == MessageRole.USER }.map { it.first.content }).containsExactly("直接发送")
        assertThat(viewModel.composerQueue.value.items).isEmpty()
    }

    @Test
    fun `等待审批时插队立即关闭审批并发送`() = runTest {
        val pending = approvedToolTurn().dropLast(1).map {
            if (it.role == MessageRole.ASSISTANT) it.copy(pendingApprovalToolIds = listOf("call-1")) else it
        }
        seedSession(messages = pending, approvalRequest = ApprovalRequest(assistantMessageId = "a1", type = "tool")); advanceUntilIdle()
        val item = queueStore.enqueue("s1", "插队问题")
        queueStore.pause("s1", com.promenar.infinity.data.chat.queue.QueuePauseReason.FAILED)
        fakeStreamChunks = listOf(StreamChunk.TextDelta("done"), StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
        viewModel.steerQueuedMessage(item.id)
        awaitReal { lastPromptRequest != null }
        assertThat(viewModel.uiState.value.approvalRequest).isNull()
        assertThat(lastPromptRequest!!.messages.filterNot { it.role == "system" }.map { it.role }).containsExactly("user", "assistant", "tool", "user").inOrder()
        assertThat(viewModel.composerQueue.value.items).isEmpty()
    }

    @Test
    fun `图片复制失败恢复排队草稿与文档并提示错误`() = runTest {
        seedSession(); advanceUntilIdle()
        holdStreamOpen = true; fakeStreamChunks = listOf(StreamChunk.TextDelta("partial"))
        viewModel.sendMessage("first"); advanceUntilIdle()
        val document = MessageDocumentAttachment("d", "a.txt", "text/plain", "内容", 6, "hash", 2)
        val field = ChatViewModel::class.java.getDeclaredField("_draftDocuments").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<List<MessageDocumentAttachment>>).value = listOf(document)
        viewModel.updateInputText("待排队")
        viewModel.sendMessage("待排队", listOf(android.net.Uri.parse("content://missing/image")))
        advanceUntilIdle()
        assertThat(viewModel.inputText.value).isEqualTo("待排队")
        assertThat(viewModel.draftDocuments.value).containsExactly(document)
        assertThat(viewModel.restoredDraftImages.value).containsExactly("content://missing/image")
        assertThat(viewModel.uiState.value.error).isNotNull()
        assertThat(viewModel.composerQueue.value.items).isEmpty()
    }

    @Test
    fun `销毁ViewModel时活动队列持久化INTERRUPTED`() = runTest {
        seedSession(); advanceUntilIdle()
        holdStreamOpen = true; fakeStreamChunks = listOf(StreamChunk.TextDelta("partial"))
        viewModel.sendMessage("first"); advanceUntilIdle()
        viewModel.sendMessage("queued"); advanceUntilIdle()
        ViewModelStore().also { it.put("chat", viewModel) }.clear()
        advanceUntilIdle()
        assertThat(queueStore.snapshot("s1").pauseReason).isEqualTo(com.promenar.infinity.data.chat.queue.QueuePauseReason.INTERRUPTED)
    }

    @Test
    fun `持久化失败终态暂停队列且不自动发出`() = runTest {
        seedSession(); advanceUntilIdle()
        queueStore.enqueue("s1", "queued")
        presentationStore.begin("s1", "persistence-task")
        presentationStore.accept("s1", "persistence-task", com.promenar.infinity.domain.generation.GenerationEvent.PhaseChanged(com.promenar.infinity.domain.generation.GenerationPhase.PERSISTENCE_FAILED))
        advanceUntilIdle()
        assertThat(viewModel.composerQueue.value.pauseReason).isEqualTo(ComposerQueuePauseReason.FAILED)
        assertThat(protocolRequestCount).isEqualTo(0)
        assertThat(viewModel.composerQueue.value.items.single().text).isEqualTo("queued")
    }

    @Test
    fun `编辑复制期间新输入或切换会话保留队列与新草稿`() = runTest {
        seedSession("B"); advanceUntilIdle()
        seedSession("A"); advanceUntilIdle()
        val item = queueStore.enqueue("A", "queued"); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); editGate = gate
        assertThat(viewModel.editQueuedMessage(item.id)).isTrue(); runCurrent()
        viewModel.updateInputText("新草稿")
        gate.complete(Unit); advanceUntilIdle()
        assertThat(viewModel.inputText.value).isEqualTo("新草稿")
        assertThat(queueStore.snapshot("A").items.single().id).isEqualTo(item.id)
        viewModel.updateInputText("")
        val switched = CompletableDeferred<Unit>(); editGate = switched
        assertThat(viewModel.editQueuedMessage(item.id)).isTrue(); runCurrent()
        viewModel.loadSession("B"); advanceUntilIdle()
        switched.complete(Unit); advanceUntilIdle()
        assertThat(queueStore.snapshot("A").items.single().id).isEqualTo(item.id)
        assertThat(viewModel.inputText.value).isEmpty()
    }

    @Test
    fun `入队图片复制失败切会话后完整恢复原附件`() = runTest {
        seedSession("B"); advanceUntilIdle()
        seedSession("A"); advanceUntilIdle()
        holdStreamOpen = true; fakeStreamChunks = listOf(StreamChunk.TextDelta("partial"))
        viewModel.sendMessage("first"); advanceUntilIdle()
        val doc = MessageDocumentAttachment("d", "a.txt", "text/plain", "内容", 6, "hash", 2)
        @Suppress("UNCHECKED_CAST")
        (ChatViewModel::class.java.getDeclaredField("_draftDocuments").apply { isAccessible = true }.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<List<MessageDocumentAttachment>>).value = listOf(doc)
        val gate = CompletableDeferred<Unit>(); queueEnqueueGate = gate
        viewModel.sendMessage("queued", listOf(android.net.Uri.parse("content://missing/image"))); advanceUntilIdle()
        viewModel.loadSession("B"); advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()
        assertThat(viewModel.draftDocuments.value).isEmpty()
        viewModel.loadSession("A"); advanceUntilIdle()
        assertThat(viewModel.inputText.value).isEqualTo("queued")
        assertThat(viewModel.draftDocuments.value).containsExactly(doc)
        assertThat(viewModel.restoredDraftImages.value).containsExactly("content://missing/image")
    }

    @Test
    fun `出队准备切走后台接受后回来能够继续剩余队列`() = runTest {
        seedSession("B"); advanceUntilIdle()
        seedSession("A"); advanceUntilIdle()
        val inserted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var suspended = false
        afterInsert = { message, _ -> if (!suspended && message.role == MessageRole.ASSISTANT) { suspended = true; inserted.complete(Unit); release.await() } }
        queueStore.enqueue("A", "first"); queueStore.enqueue("A", "second")
        fakeStreamChunks = listOf(StreamChunk.TextDelta("done"), StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
        viewModel.resumeComposerQueue(); inserted.await()
        viewModel.loadSession("B"); advanceUntilIdle()
        release.complete(Unit); advanceUntilIdle()
        viewModel.loadSession("A"); advanceUntilIdle()
        viewModel.resumeComposerQueue(); advanceUntilIdle()
        assertThat(savedMessages.filter { it.second == "A" && it.first.role == MessageRole.USER }.map { it.first.content }).containsExactly("first", "second").inOrder()
        assertThat(viewModel.composerQueue.value.items).isEmpty()
    }

    @Test
    fun `权限门等待时切会话回滚发送租约后能够继续`() = runTest {
        seedSession("B"); advanceUntilIdle()
        seedSession("A"); advanceUntilIdle()
        notificationPermissionState = com.promenar.infinity.background.generation.NotificationPermissionPromptState(35, false, false)
        queueStore.enqueue("A", "queued")
        viewModel.resumeComposerQueue(); advanceUntilIdle()
        assertThat(viewModel.notificationPermissionRequests.value).isNotNull()
        assertThat(queueStore.snapshot("A").sending).isNotNull()
        viewModel.loadSession("B"); advanceUntilIdle()
        assertThat(queueStore.snapshot("A").sending).isNull()
        assertThat(queueStore.snapshot("A").items.single().text).isEqualTo("queued")
        viewModel.loadSession("A"); advanceUntilIdle()
        notificationPermissionState = null
        fakeStreamChunks = listOf(StreamChunk.TextDelta("done"), StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
        viewModel.resumeComposerQueue(); advanceUntilIdle()
        assertThat(protocolRequestCount).isEqualTo(1)
        assertThat(viewModel.composerQueue.value.items).isEmpty()
    }

    @Test
    fun `用户停止先于入队写盘仍保存STOPPED暂停原因`() = runTest {
        seedSession(); advanceUntilIdle()
        holdStreamOpen = true; fakeStreamChunks = listOf(StreamChunk.TextDelta("partial"))
        viewModel.sendMessage("first"); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); queueEnqueueGate = gate
        viewModel.sendMessage("queued"); advanceUntilIdle()
        viewModel.stopGeneration(); advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()
        assertThat(viewModel.composerQueue.value.pauseReason).isEqualTo(ComposerQueuePauseReason.STOPPED)
        assertThat(viewModel.composerQueue.value.items.single().text).isEqualTo("queued")
    }

    @Test
    @org.robolectric.annotation.Config(sdk = [34])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `排队图片和文档通过原发送链进入下一轮Prompt`() = runTest {
        ApplicationProvider.getApplicationContext<TestInfinityApplication>().getSharedPreferences("infinity_settings", 0).edit().putInt("model_info_gpt-4o_context", 128_000).commit()
        seedSession(); advanceUntilIdle()
        val gate = CompletableDeferred<List<StreamChunk>>(); streamGate = gate
        fakeStreamChunks = listOf(StreamChunk.TextDelta("partial"))
        viewModel.sendMessage("first"); advanceUntilIdle()
        val image = java.io.File.createTempFile("infinity-queued-image", ".png")
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(40, 120, 200))
        image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val annotation = com.promenar.infinity.domain.media.ImageAnnotation(
            com.promenar.infinity.domain.media.ImageAnnotationKind.MASK, 4, 4, "/mask.png", "/source.png")
        image.writeBytes(com.promenar.infinity.data.media.annotation.ImageAnnotationPreviewCodec.embed(image.readBytes(), annotation,
            com.promenar.infinity.data.media.annotation.ImageAnnotationContext.from(requireNotNull(viewModel.uiState.value.session))))
        val doc = MessageDocumentAttachment("d", "a.txt", "text/plain", "附件正文", 12, "hash", 4)
        @Suppress("UNCHECKED_CAST")
        (ChatViewModel::class.java.getDeclaredField("_draftDocuments").apply { isAccessible = true }.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<List<MessageDocumentAttachment>>).value = listOf(doc)
        viewModel.sendMessage("带附件", listOf(android.net.Uri.fromFile(image))); advanceUntilIdle()
        assertThat(viewModel.composerQueue.value.items.single().imageCount).isEqualTo(1)
        assertThat(viewModel.composerQueue.value.items.single().documentNames).containsExactly("a.txt")
        assertThat(viewModel.editQueuedMessage(viewModel.composerQueue.value.items.single().id)).isTrue(); advanceUntilIdle()
        val draftImages = viewModel.restoredDraftImages.value!!.map(android.net.Uri::parse)
        val draftFile = java.io.File(draftImages.single().path!!)
        assertThat(draftFile.exists()).isTrue()
        viewModel.sendMessage(viewModel.inputText.value, draftImages); advanceUntilIdle()
        assertThat(draftFile.exists()).isFalse()
        val queuedFile = java.io.File(queueStore.snapshot("s1").items.single().imagePaths.single())
        assertThat(queuedFile.exists()).isTrue()
        streamGate = null
        fakeStreamChunks = listOf(StreamChunk.TextDelta("done"), StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN))
        gate.complete(listOf(StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN)))
        awaitReal { protocolRequestCount == 2 }
        assertThat(protocolRequestCount).isEqualTo(2)
        val sent = lastPromptRequest!!.messages.last { it.role == "user" }
        assertThat(sent.imageUrls).hasSize(1)
        assertThat(sent.content).contains("附件正文")
        assertThat(sent.content).contains("mask=\"/mask.png\"")
        assertThat(savedMessages.map { it.first }.last { it.role == MessageRole.USER }.userImageAnnotations).containsExactly(annotation)
        assertThat(queuedFile.exists()).isFalse()
        assertThat(viewModel.draftDocuments.value).isEmpty()
        image.delete()
    }

    @Test
    fun `出队写盘失败保留队列并通过错误通道暂停`() = runTest {
        seedSession(); advanceUntilIdle()
        queueStore.enqueue("s1", "queued")
        claimFailure = java.io.IOException("queue write failed")
        viewModel.resumeComposerQueue(); advanceUntilIdle()
        assertThat(viewModel.composerQueue.value.items.single().text).isEqualTo("queued")
        assertThat(viewModel.composerQueue.value.pauseReason).isEqualTo(ComposerQueuePauseReason.FAILED)
        assertThat(viewModel.uiState.value.error).isNotNull()
        assertThat(protocolRequestCount).isEqualTo(0)
    }

    private fun kotlinx.coroutines.test.TestScope.awaitReal(condition: () -> Boolean) {
        repeat(250) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(20)
        }
        advanceUntilIdle()
    }

    private val executedWriteCall = ToolCall("call-1", "write_file", "{\"path\":\"/a.md\"}")

    /** 一轮已执行完审批工具的会话：用户消息、发起调用的助手消息与工具结果。 */
    private fun approvedToolTurn(): List<Message> = listOf(
        Message(id = "u1", role = MessageRole.USER, content = "写文件", createdAt = 2000L),
        Message(
            id = "a1",
            role = MessageRole.ASSISTANT,
            content = "我来写入文件",
            toolCalls = listOf(executedWriteCall),
            parentMessageId = "u1",
            createdAt = 2001L,
        ),
        Message(
            id = "t1",
            role = MessageRole.TOOL,
            content = "写入成功",
            toolCallId = "call-1",
            name = "write_file",
            parentMessageId = "a1",
            createdAt = 2002L,
        ),
    )

    @Test
    fun `审批后恢复生成新建助手消息并保留原工具调用`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(messages = approvedToolTurn()); advanceUntilIdle()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("文件已写好"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN),
        )

        viewModel.resumeAfterApproval("s1"); advanceUntilIdle()

        val messages = viewModel.uiState.value.messages
        val original = messages.single { it.id == "a1" }
        assertThat(original.toolCalls).containsExactly(executedWriteCall)
        assertThat(original.content).isEqualTo("我来写入文件")
        val answer = messages.last { it.role == MessageRole.ASSISTANT }
        assertThat(answer.id).isNotEqualTo("a1")
        assertThat(answer.content).isEqualTo("文件已写好")
        assertThat(answer.parentMessageId).isEqualTo("u1")
        val sent = requireNotNull(lastPromptRequest).messages.filterNot { it.role == "system" }
        assertThat(sent.map { it.role }).containsExactly("user", "assistant", "tool").inOrder()
        assertThat(sent[1].toolCalls?.map { it.id }).containsExactly("call-1")
        assertThat(sent[2].toolCallId).isEqualTo("call-1")
    }

    @Test
    fun `等待审批时发送新消息先关闭审批且历史中的调用都有结果`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        val pending = listOf(
            Message(id = "u1", role = MessageRole.USER, content = "写文件", createdAt = 2000L),
            Message(
                id = "a1",
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(executedWriteCall),
                pendingApprovalToolIds = listOf("call-1"),
                parentMessageId = "u1",
                createdAt = 2001L,
            ),
        )
        seedSession(
            messages = pending,
            approvalRequest = ApprovalRequest(assistantMessageId = "a1", type = "tool"),
        ); advanceUntilIdle()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("好的，换个做法"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN),
        )

        viewModel.sendMessage("算了，换个做法")
        // 审批台账走真实 Room 事务（后台线程），按真实时间等待其完成。
        awaitReal { lastPromptRequest != null && viewModel.uiState.value.messages.last().content == "好的，换个做法" }

        assertThat(viewModel.uiState.value.approvalRequest).isNull()
        val sent = requireNotNull(lastPromptRequest).messages.filterNot { it.role == "system" }
        assertThat(sent.map { it.role }).containsExactly("user", "assistant", "tool", "user").inOrder()
        assertThat(sent[2].toolCallId).isEqualTo("call-1")
    }

    /** 一轮多消息回复：工具调用、工具结果与最终回答。 */
    private fun multiMessageTurn(): List<Message> = listOf(
        Message(id = "u1", role = MessageRole.USER, content = "查一下", createdAt = 2000L),
        Message(
            id = "a1",
            role = MessageRole.ASSISTANT,
            content = "",
            toolCalls = listOf(ToolCall("call-r", "read_file", "{}")),
            parentMessageId = "u1",
            createdAt = 2001L,
        ),
        Message(
            id = "t1",
            role = MessageRole.TOOL,
            content = "内容",
            toolCallId = "call-r",
            name = "read_file",
            parentMessageId = "a1",
            createdAt = 2002L,
        ),
        Message(id = "a2", role = MessageRole.ASSISTANT, content = "旧回答", parentMessageId = "u1", createdAt = 2003L),
    )

    @Test
    fun `重新生成替换整轮多消息回复且旧回复不进入Prompt`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(messages = multiMessageTurn()); advanceUntilIdle()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("新回答"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN),
        )

        viewModel.regenerateMessage("a2"); advanceUntilIdle()

        val messages = viewModel.uiState.value.messages
        assertThat(messages.map { it.content }).containsExactly("查一下", "新回答").inOrder()
        assertThat(savedMessages.map { it.first.id }).containsNoneOf("a1", "t1", "a2")
        val sent = requireNotNull(lastPromptRequest).messages.filterNot { it.role == "system" }
        assertThat(sent.map { it.content }).containsExactly("查一下")
    }

    @Test
    fun `重新生成期间隐藏旧回复且停止后原样恢复`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(messages = multiMessageTurn()); advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("生成中"))
        holdStreamOpen = true

        viewModel.regenerateMessage("u1"); advanceUntilIdle()
        assertThat(viewModel.uiState.value.messages.map { it.id }).containsNoneOf("a1", "t1", "a2")

        viewModel.stopGeneration(); advanceUntilIdle()

        assertThat(viewModel.uiState.value.messages.map { it.id })
            .containsExactly("u1", "a1", "t1", "a2").inOrder()
    }

    @Test
    fun `删除用户消息连同已记入记忆的回复时撤销提示说明记忆一并删除`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        val stored = com.promenar.infinity.data.model.TurnMemoryState(com.promenar.infinity.data.model.TurnMemoryState.Status.STORED, 1)
        seedSession(messages = multiMessageTurn().map { if (it.id == "a2") it.copy(vectorizationStatus = stored.encode()) else it })
        advanceUntilIdle()

        viewModel.deleteMessage("u1")
        viewModel.deletionUndo.first { it != null }
        viewModel.uiState.first { it.messages.isEmpty() }
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.messages).isEmpty()
        assertThat(viewModel.deletionUndo.value?.let { it.count to it.withMemory }).isEqualTo(2 to true)
    }

    @Test
    fun `删除助手回复按整轮删除并可撤销`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(messages = multiMessageTurn()); advanceUntilIdle()

        viewModel.deleteMessage("a2")
        viewModel.deletionUndo.first { it != null }
        viewModel.uiState.first { it.messages.map { message -> message.id } == listOf("u1") }
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.messages.map { it.id }).containsExactly("u1")
        // 撤销提示按气泡计数：整轮回复（两条助手消息与一条工具消息）算一条。
        assertThat(viewModel.deletionUndo.value?.count).isEqualTo(1)
        assertThat(viewModel.deletionUndo.value?.withMemory).isFalse()

        viewModel.undoLastDeletion(); advanceUntilIdle()

        assertThat(viewModel.uiState.value.messages.map { it.id })
            .containsExactly("u1", "a1", "t1", "a2").inOrder()
        assertThat(viewModel.deletionUndo.value).isNull()
    }

    @Test
    fun retryLastMessage_resendsLastUserMessage() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("first response"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN)
        )

        viewModel.sendMessage("hello"); advanceUntilIdle()
        
         

        assertThat(viewModel.uiState.value.messages.any { it.content == "first response" }).isTrue()

        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("retry response"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN)
        )

        viewModel.retryLastMessage(); advanceUntilIdle()
        
         

        val messages = viewModel.uiState.value.messages
        val assistantMessages = messages.filter { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMessages.any { it.content == "retry response" }).isTrue()
        assertThat(savedMessages.count { it.first.role == MessageRole.USER }).isEqualTo(1)
        assertThat(savedMessages.count { it.first.role == MessageRole.ASSISTANT }).isEqualTo(1)
        assertThat(foregroundTrackedTasks).hasSize(2)
        
    }

    @Test
    fun `其它会话运行时仍可重试且停止互不影响`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession("retry-target")
        advanceUntilIdle()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("existing reply"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN),
        )
        viewModel.sendMessage("hello")
        advanceUntilIdle()

        seedSession("active")
        advanceUntilIdle()
        fakeStreamChunks = listOf(StreamChunk.TextDelta("active partial"))
        holdStreamOpen = true
        viewModel.sendMessage("keep running")
        advanceUntilIdle()

        viewModel.loadSession("retry-target")
        advanceUntilIdle()
        val requestsBeforeRetry = protocolRequestCount
        viewModel.retryLastMessage()
        advanceUntilIdle()

        assertThat(protocolRequestCount).isEqualTo(requestsBeforeRetry + 1)
        assertThat(viewModel.uiState.value.isGenerating).isTrue()
        assertThat(viewModel.uiState.value.generationNotice?.code).isNotEqualTo("generation.failure.busy")
        viewModel.stopGeneration()
        advanceUntilIdle()

        viewModel.loadSession("active")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isGenerating).isTrue()
        viewModel.stopGeneration()
        advanceUntilIdle()
    }

    @Test
    fun `删除生成中的回复只停止该会话`() = runTest {
        fakeStreamChunks = listOf(StreamChunk.TextDelta("partial"))
        holdStreamOpen = true
        seedSession("delete-a"); advanceUntilIdle()
        viewModel.sendMessage("a"); advanceUntilIdle()
        seedSession("delete-b"); advanceUntilIdle()
        viewModel.sendMessage("b"); advanceUntilIdle()
        val app = ApplicationProvider.getApplicationContext<TestInfinityApplication>()
        val assistant = app.chatStore.getSession("delete-b")!!.messages.last { it.role == MessageRole.ASSISTANT }
        viewModel.deleteMessage(assistant.id)
        // 删除前先在 IO 线程核对该会话是否有待回答的提问，需等真实线程完成。
        awaitReal { !viewModel.uiState.value.isGenerating }
        assertThat(viewModel.uiState.value.isGenerating).isFalse()
        viewModel.loadSession("delete-a"); advanceUntilIdle()
        assertThat(viewModel.uiState.value.isGenerating).isTrue()
        viewModel.stopGeneration(); advanceUntilIdle()
    }

    @Test
    fun `重试启动后路由失败仍保留旧回复并清理新占位`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession("retry-route")
        advanceUntilIdle()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("existing reply"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN),
        )
        viewModel.sendMessage("hello")
        advanceUntilIdle()

        forcedProviderFailure = ProviderResolution.Failure(
            ProviderResolutionError.PROVIDER_DISABLED,
            "gpt-4o",
        )
        viewModel.retryLastMessage()
        advanceUntilIdle()

        val retryMessages = savedMessages
            .filter { it.second == "retry-route" }
            .map { it.first }
        assertThat(retryMessages.map { it.content })
            .containsExactly("hello", "existing reply")
            .inOrder()
        assertThat(retryMessages.count { it.role == MessageRole.ASSISTANT }).isEqualTo(1)
        assertThat(viewModel.uiState.value.generationNotice?.code)
            .isEqualTo("generation.failure.invalid_request")
    }

    @Test
    fun `重试首次触发权限且取消等待时保留旧回复`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession("retry-permission")
        advanceUntilIdle()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("existing reply"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN),
        )
        viewModel.sendMessage("hello")
        advanceUntilIdle()
        assertThat(savedMessages.map { it.first.content })
            .containsExactly("hello", "existing reply")
            .inOrder()

        notificationPermissionState =
            com.promenar.infinity.background.generation.NotificationPermissionPromptState(
                sdkInt = 35,
                granted = false,
                alreadyAsked = false,
            )
        viewModel.retryLastMessage()
        advanceUntilIdle()

        assertThat(viewModel.notificationPermissionRequests.value).isNotNull()
        viewModel.stopGeneration()
        advanceUntilIdle()

        assertThat(savedMessages.map { it.first.content })
            .containsExactly("hello", "existing reply")
            .inOrder()
        assertThat(viewModel.uiState.value.messages.any { it.content == "existing reply" }).isTrue()
    }

    @Test
    fun `重试成功后删除旧回复失败时旧回复仍保留`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        fakeStreamChunks = listOf(
            StreamChunk.TextDelta("first response"),
            StreamChunk.Completed(com.promenar.infinity.domain.generation.CompletionReason.END_TURN),
        )
        viewModel.sendMessage("hello"); advanceUntilIdle()
        val requestsBeforeRetry = protocolRequestCount
        failScopedDelete = true

        viewModel.retryLastMessage(); advanceUntilIdle()

        assertThat(protocolRequestCount).isEqualTo(requestsBeforeRetry + 1)
        assertThat(viewModel.uiState.value.messages.any { it.content == "first response" }).isTrue()
    }

    @Test
    fun sendMessage_withError_updatesErrorState() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        
        fakeStreamChunks = listOf(
            StreamChunk.Error(technical = "Something went wrong")
        )

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.generationNotice?.code)
            .isEqualTo("generation.failure.unknown")
        assertThat(viewModel.uiState.value.generationNotice?.formatArgs).isEmpty()
        assertThat(viewModel.uiState.value.generationNotice?.formatArgs?.joinToString())
            .doesNotContain("Something went wrong")
        
    }

    @Test
    fun `onCleared只移除观察者不取消应用级生成`() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()
        holdStreamOpen = true
        fakeStreamChunks = listOf(StreamChunk.TextDelta("partial"))
        viewModel.sendMessage("hello")
        kotlinx.coroutines.yield()
        assertThat(protocolRequestCount).isGreaterThan(0)
        providerCancelled = false

        ChatViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(viewModel)

        assertThat(providerCancelled).isFalse()
        assertThat(viewModel.uiState.value.isGenerating).isTrue()
        viewModel.stopGeneration()
    }

    @Test
    fun loadSession_fetchesAgentNameFromRepository() = runTest {
        backgroundScope.launch { viewModel.uiState.collect {} }
        seedSession(); advanceUntilIdle()

        assertThat(stubAgentRepo.lastGetByIdId).isEqualTo("a1")
    }
}

class TestInfinityApplication : InfinityApplication() {
    override val secretStore: SecretStore = MemorySecretStore()
    override fun createBackupRuntime(): BackupRuntime = BackupRuntime(object : BackupDataSource {
        override suspend fun snapshot(content: Set<BackupContent>): BackupSnapshot = error("unused")
        override suspend fun restore(validated: ValidatedBackup) = error("unused")
        override suspend fun recoverInterruptedRestore() = Unit
    })
}

private class MemorySecretStore : SecretStore {
    private val values = mutableMapOf<SecretId, ByteArray>()
    override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
    override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
    override fun contains(id: SecretId): Boolean = id in values
    override fun remove(id: SecretId) { values.remove(id) }
}
