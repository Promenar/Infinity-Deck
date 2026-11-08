package com.promenar.infinity.data.generation

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Resources
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.background.generation.ForegroundStartResult
import com.promenar.infinity.background.generation.GenerationForegroundController
import com.promenar.infinity.background.trigger.SystemTriggerNotifications
import com.promenar.infinity.data.backup.BackupStartupState
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.LoopStatus
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.repository.IMessageRepository
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.domain.generation.GenerationCoordinator
import com.promenar.infinity.domain.generation.GenerationEvent
import com.promenar.infinity.domain.generation.GenerationInteractionMode
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationRunner
import com.promenar.infinity.domain.generation.GenerationRuntimePolicy
import com.promenar.infinity.domain.generation.StartGenerationResult
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.MessageManager
import com.promenar.infinity.ui.chat.manager.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class HeadlessGenerationResumerTest {
    @Test
    fun `无界面恢复保留只有引用的用户消息并序列化一次`() = runTest {
        val delegate = coordinator()
        var captured: GenerationRequest? = null
        val coordinator = object : GenerationCoordinator by delegate {
            override suspend fun start(request: GenerationRequest, onAdmission: (String) -> Boolean): StartGenerationResult {
                captured = request
                return delegate.start(request, onAdmission)
            }
        }
        val fixture = fixture(coordinator)
        val quote = com.promenar.infinity.data.model.MessageQuote("source", "引文", "批注")
        fixture.store.updateSession("target") { it.copy(messages = it.messages.map { message ->
            if (message.role == MessageRole.USER) message.copy(content = "", quotes = listOf(quote)) else message
        }) }

        fixture.app.resume(fixture.prompt("target"))

        assertThat(captured!!.userContent).isEqualTo("> 引文\n\n批注")
        assertThat(delegate.observe("target").value?.phase).isEqualTo(GenerationPhase.PREPARING)
    }

    @Test
    fun `启动逐会话校验不受检查期间页面切换影响`() = runTest {
        val missingFirst = GenerationInteraction("old-first", "missing-first", GenerationInteractionKind.REPEATED_TOOL)
        val retained = GenerationInteraction("keep", "retained", GenerationInteractionKind.REPEATED_TOOL)
        val missingLast = GenerationInteraction("old-last", "missing-last", GenerationInteractionKind.REPEATED_TOOL)
        var disk = listOf(missingFirst, retained, missingLast)
        val interactions = GenerationInteractionStore(object : HeadlessInteractionPersistence {
            override suspend fun read() = disk.firstOrNull()
            override suspend fun write(interaction: GenerationInteraction?) { disk = listOfNotNull(interaction) }
            override suspend fun readAll() = disk
            override suspend fun writeAll(interactions: List<GenerationInteraction>) { disk = interactions }
        })
        val checking = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val checked = mutableListOf<String>()
        interactions.selectSession("missing-first")
        val restoring = async {
            interactions.restoreHeadless { id ->
                checked += id
                if (id == "missing-first") { checking.complete(Unit); release.await() }
                id == "retained"
            }
        }
        checking.await()
        interactions.selectSession("retained")
        interactions.selectSession("another-page")
        release.complete(Unit)
        restoring.await()

        assertThat(checked).containsExactly("missing-first", "retained", "missing-last")
        assertThat(disk).containsExactly(retained)
        assertThat(interactions.pendingBySession.value.values).containsExactly(retained)
        assertThat(interactions.pending.value).isNull()
        interactions.selectSession("retained")
        assertThat(interactions.pending.value).isEqualTo(retained)
    }

    @Test
    fun `确认目标会话后切到其它页面仍恢复目标会话`() = runTest {
        val fixture = fixture()
        val target = fixture.prompt("target")
        val visible = fixture.prompt("visible")
        fixture.interactions.selectSession("visible")

        fixture.app.resume(target)

        assertThat(fixture.coordinator.observe("target").value?.phase).isEqualTo(GenerationPhase.PREPARING)
        assertThat(fixture.interactions.pendingBySession.value.keys).containsExactly("visible")
        assertThat(fixture.interactions.pending.value).isEqualTo(visible)
        assertThat(fixture.store.getSession("visible")!!.messages).hasSize(2)
    }

    @Test
    fun `其它会话持有准入锁时目标恢复不等待全局锁`() = runTest {
        val fixture = fixture()
        val target = fixture.prompt("target")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        backgroundScope.launch {
            fixture.store.turnAdmission.withLock("blocked") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val resume = backgroundScope.async { fixture.app.resume(target) }
        runCurrent()

        assertThat(fixture.coordinator.observe("target").value).isNotNull()
        release.complete(Unit)
        resume.await()
    }

    @Test
    fun `四个会话占满时恢复排队且助手占位保留到开始执行`() = runTest {
        val entered = mutableListOf<String>()
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { request, _ -> entered += request.sessionId; awaitCancellation() }
        }, maxConcurrent = 4)
        val fixture = fixture(coordinator)
        val prompt = fixture.prompt("target")
        (1..4).forEach { coordinator.start(request("busy-$it")) }
        runCurrent()

        val failure = runCatching { fixture.app.resume(prompt) }.exceptionOrNull()

        assertThat(failure).isNull()
        val queued = coordinator.observe("target").value!!
        assertThat(queued.phase).isEqualTo(GenerationPhase.QUEUED)
        assertThat(fixture.store.getSession("target")!!.messages.map { it.id }).contains(queued.assistantMessageId)
        assertThat(fixture.interactions.pendingBySession.value).isEmpty()
        assertThat(entered).doesNotContain("target")
        coordinator.cancel(coordinator.observe("busy-1").value!!.taskId)
        runCurrent()
        assertThat(entered).contains("target")
    }

    @Test
    fun `目标决策失败只允许目标重试且其它恢复保持在途`() = runTest {
        val delegate = coordinator()
        val waiting = CompletableDeferred<Unit>()
        val coordinator = object : GenerationCoordinator by delegate {
            override suspend fun awaitSessionIdle(sessionId: String) {
                if (sessionId == "visible") waiting.await() else delegate.awaitSessionIdle(sessionId)
            }
        }
        val fixture = fixture(coordinator)
        val failing = fixture.prompt("target")
        val inFlight = fixture.prompt("visible")
        val messages = mockk<MessageManager>(relaxed = true)
        coEvery { messages.addMessage("target", any()) } throws IllegalStateException("占位写入失败")
        fixture.app.replaceLazy("generationMessageManager", messages)
        assertThat(fixture.interactions.respond(inFlight.id, true)).isTrue()
        assertThat(fixture.interactions.respond(failing.id, true)).isTrue()
        runCurrent()

        assertThat(fixture.interactions.respond(inFlight.id, true)).isFalse()
        assertThat(fixture.interactions.respond(failing.id, true)).isTrue()
        waiting.complete(Unit)
    }

    @Test
    fun `准入已登记后返回异常不能重试出重复轮或删除助手占位`() = runTest {
        val delegate = coordinator()
        var starts = 0
        val coordinator = object : GenerationCoordinator by delegate {
            override suspend fun start(request: GenerationRequest) = start(request) { true }
            override suspend fun start(request: GenerationRequest, onAdmission: (String) -> Boolean): StartGenerationResult {
                starts++
                delegate.start(request, onAdmission)
                throw IllegalStateException("受理回执返回异常")
            }
        }
        val fixture = fixture(coordinator)
        val prompt = fixture.prompt("target")
        assertThat(fixture.interactions.respond(prompt.id, true)).isTrue()
        runCurrent()

        val accepted = delegate.observe("target").value!!
        assertThat(fixture.store.getSession("target")!!.messages.map { it.id }).contains(accepted.assistantMessageId)
        assertThat(fixture.interactions.pendingBySession.value).isEmpty()
        assertThat(fixture.interactions.respond(prompt.id, true)).isFalse()
        runCurrent()
        assertThat(starts).isEqualTo(1)
    }

    @Test
    fun `已有相同请求回执视为受理并保留其助手占位`() = runTest {
        val delegate = coordinator()
        val coordinator = object : GenerationCoordinator by delegate {
            override suspend fun start(request: GenerationRequest) = start(request) { true }
            override suspend fun start(request: GenerationRequest, onAdmission: (String) -> Boolean): StartGenerationResult {
                delegate.start(request, onAdmission)
                return StartGenerationResult.Existing(delegate.observe(request.sessionId).value!!)
            }
        }
        val fixture = fixture(coordinator)
        val prompt = fixture.prompt("target")

        val failure = runCatching { fixture.app.resume(prompt) }.exceptionOrNull()

        assertThat(failure).isNull()
        val accepted = delegate.observe("target").value!!
        assertThat(fixture.store.getSession("target")!!.messages.map { it.id }).contains(accepted.assistantMessageId)
        assertThat(fixture.interactions.pendingBySession.value).isEmpty()
    }

    @Test
    fun `旧恢复收尾不能清掉生成立即登记的新确认`() = runTest {
        lateinit var interactions: GenerationInteractionStore
        lateinit var acceptedTaskId: String
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val coordinator = DefaultGenerationCoordinator(scope, GenerationRunnerFactory { _, taskId ->
            acceptedTaskId = taskId
            GenerationRunner { request, emit ->
                try {
                    // 此处专门注册持久确认，检验旧确认收尾的 ID 隔离，不约束恢复请求的交互模式。
                    interactions.awaitDecision(request.sessionId, GenerationInteractionKind.REPEATED_TOOL,
                        GenerationInteractionMode.HEADLESS)
                } catch (_: HeadlessInteractionRequired) {
                    emit(GenerationEvent.PhaseChanged(GenerationPhase.WAITING_APPROVAL))
                }
            }
        }, maxConcurrent = 4)
        val fixture = fixture(coordinator)
        interactions = fixture.interactions
        val old = fixture.prompt("target")

        fixture.app.resume(old)
        runCurrent()

        val next = interactions.pendingBySession.value.getValue("target")
        assertThat(next.id).isNotEqualTo(old.id)
        val restored = GenerationInteractionStore(FileHeadlessInteractionPersistence(
            java.io.File(fixture.app.noBackupFilesDir, "system-trigger-interaction.json")))
        restored.restoreHeadless()
        assertThat(restored.pendingBySession.value["target"]).isEqualTo(next)
        val completed = coordinator.awaitTaskCompletion("target", acceptedTaskId)
        assertThat(completed?.phase).isEqualTo(GenerationPhase.WAITING_APPROVAL)
        assertThat(coordinator.isSessionBusy("target")).isFalse()
    }

    @Test
    fun `冷恢复读取期间删除会话不能将已删快照插回内存`() = runTest {
        val gate = SessionExecutionGate()
        val store = ChatStore()
        val interactions = GenerationInteractionStore()
        val paused = Session("target", "agent", ragOptions = RagOptions(), messages = listOf(
            Message("user", MessageRole.USER, "检查"),
            Message("paused", MessageRole.ASSISTANT, "", parentMessageId = "user"),
        ))
        var exists = true
        val persistedMessages = paused.messages.associateBy { it.id }.toMutableMap()
        val sessions = mockk<ISessionRepository>(relaxed = true)
        coEvery { sessions.getById("target") } answers {
            if (exists) paused.copy(messages = persistedMessages.values.toList()) else null
        }
        val repository = mockk<IMessageRepository>(relaxed = true)
        coEvery { repository.insert(any(), "target") } answers {
            check(exists) { "目标会话外键已删除" }
            val message = firstArg<Message>()
            persistedMessages[message.id] = message
        }
        coEvery { repository.deleteInSession("target", any()) } answers {
            persistedMessages.remove(secondArg<String>()) != null
        }
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { _, _ -> awaitCancellation() }
        }, executionGate = gate, maxConcurrent = 4)
        val loaded = CompletableDeferred<Unit>()
        val returnSnapshot = CompletableDeferred<Unit>()
        val resumer = HeadlessGenerationResumer(store, interactions, coordinator,
            MessageManager(store, repository, sessions, backgroundScope),
            awaitReady = {},
            loadSession = {
                val snapshot = if (exists) paused else null
                loaded.complete(Unit)
                returnSnapshot.await()
                snapshot
            },
            updateSession = { _, _ -> }, confirmUnlimited = {}, track = {}, executionGate = gate,
        )
        val prompt = try {
            interactions.awaitDecision("target", GenerationInteractionKind.REPEATED_TOOL, GenerationInteractionMode.HEADLESS)
            error("必须进入无界面等待")
        } catch (required: HeadlessInteractionRequired) { required.interaction }
        val restoring = async { runCatching { resumer.resume(prompt, true) } }
        loaded.await()
        val deleting = async {
            gate.withDeletion("target", null) {
                exists = false
                persistedMessages.clear()
                store.removeSession("target")
                interactions.clearHeadless("target")
            }
        }
        runCurrent()
        val deletedBeforeSnapshotReturned = deleting.isCompleted
        returnSnapshot.complete(Unit)
        restoring.await()
        deleting.await()

        assertThat(store.getSession("target")).isNull()
        assertThat(interactions.pendingBySession.value).isEmpty()
        assertThat(coordinator.isSessionBusy("target")).isFalse()
        assertThat(persistedMessages).isEmpty()
        assertThat(deletedBeforeSnapshotReturned).isFalse()
    }

    @Test
    fun `切页后拒绝仅完成目标会话并保留其它确认`() = runTest {
        val fixture = fixture()
        val target = fixture.prompt("target")
        val visible = fixture.prompt("visible")
        fixture.interactions.selectSession("visible")

        fixture.app.resume(target, false)

        assertThat(fixture.store.getSession("target")!!.loopStatus).isEqualTo(LoopStatus.COMPLETED)
        assertThat(fixture.store.getSession("visible")!!.loopStatus).isEqualTo(LoopStatus.IDLE)
        assertThat(fixture.interactions.pendingBySession.value.values).containsExactly(visible)
        assertThat(fixture.coordinator.sessions.value).isEmpty()
    }

    private fun TestScope.coordinator() = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
        GenerationRunner { _, _ -> awaitCancellation() }
    }, maxConcurrent = 4)

    private fun TestScope.fixture(coordinator: GenerationCoordinator = coordinator()): Fixture {
        val app = InfinityApplication()
        val strings = mockk<Resources> {
            every { getString(R.string.schedule_notification_failed_reason) } returns "未能恢复生成"
        }
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getResources() = strings
        }
        ContextWrapper::class.java.getDeclaredField("mBase").apply {
            isAccessible = true
            set(app, context)
        }
        val store = ChatStore().apply {
            listOf("target", "visible", "blocked").forEach { id ->
                upsertSession(Session(id, "agent", ragOptions = RagOptions(), messages = listOf(
                    Message("user-$id", MessageRole.USER, "检查 $id"),
                    Message("paused-$id", MessageRole.ASSISTANT, "", parentMessageId = "user-$id"),
                )))
            }
        }
        val sessions = mockk<ISessionRepository>(relaxed = true)
        val messages = MessageManager(store, mockk<IMessageRepository>(relaxed = true), sessions, backgroundScope)
        app.replaceField("generationScope", backgroundScope)
        app.replaceLazy("chatStore", store)
        app.replaceLazy("generationCoordinator", coordinator)
        app.replaceLazy("generationMessageManager", messages)
        app.replaceLazy("generationSessionManager", SessionManager(store, sessions))
        app.replaceLazy("generationForegroundController", mockk<GenerationForegroundController> {
            every { track(any()) } returns ForegroundStartResult.StartedWithNotifications
        })
        app.replaceLazy("systemTriggerNotifications", mockk<SystemTriggerNotifications>(relaxed = true))
        @Suppress("UNCHECKED_CAST")
        (app.field("_startupState") as MutableStateFlow<BackupStartupState>).value = BackupStartupState.Ready
        return Fixture(app, store, coordinator, app.generationInteractions)
    }

    private data class Fixture(val app: InfinityApplication, val store: ChatStore,
        val coordinator: GenerationCoordinator, val interactions: GenerationInteractionStore) {
        suspend fun prompt(sessionId: String): GenerationInteraction {
            val failure = runCatching {
                interactions.awaitDecision(sessionId, GenerationInteractionKind.REPEATED_TOOL, GenerationInteractionMode.HEADLESS)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(HeadlessInteractionRequired::class.java)
            return interactions.pendingBySession.value.getValue(sessionId)
        }
    }

    private fun request(id: String) = GenerationRequest(id, "ai-$id", "user-$id", "检查", emptyList(),
        GenerationRuntimePolicy.BACKGROUND_ALLOWED)

    private fun InfinityApplication.field(name: String): Any? = InfinityApplication::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }.get(this)

    private fun InfinityApplication.replaceField(name: String, value: Any) {
        InfinityApplication::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun InfinityApplication.replaceLazy(name: String, value: Any) = replaceField("${name}\$delegate", lazyOf(value))

    private suspend fun InfinityApplication.resume(prompt: GenerationInteraction, allowed: Boolean = true): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = InfinityApplication::class.java.getDeclaredMethod("resumeHeadlessInteraction", GenerationInteraction::class.java,
            Boolean::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
        try {
            method.invoke(this, prompt, allowed, continuation)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }
}
