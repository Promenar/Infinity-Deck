package com.promenar.infinity.ui.chat.manager

import com.promenar.infinity.data.model.BillingUsage
import com.promenar.infinity.data.model.Citation
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.KgPath
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.TurnMemoryState
import com.promenar.infinity.data.model.turnMemory
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.RagMetadata
import com.promenar.infinity.data.model.RagProgress
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionStats
import com.promenar.infinity.data.model.TaskState
import com.promenar.infinity.data.model.TokenMetric
import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.model.ToolResultArtifact
import com.promenar.infinity.data.model.UpdateMessageOptions
import com.promenar.infinity.data.model.json
import com.promenar.infinity.data.repository.IMessageRepository
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.rag.ConversationMemoryLifecycle
import com.promenar.infinity.utils.InfinityLogger
import com.promenar.infinity.ui.chat.ChatStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MessageManagerHooks(
    val beforeUiApply: suspend (String) -> Unit = {},
    val beforeDbWrite: suspend (String) -> Unit = {},
)

internal data class MessageCoordinationState(
    val lanes: Int = 0,
    val pendingUi: Int = 0,
    val inFlightUi: Int = 0,
    val uiJobs: Int = 0,
    val pendingDb: Int = 0,
    val dbJobs: Int = 0,
    val purging: Int = 0,
)

internal fun messageRemovalIds(messages: List<Message>, messageId: String): List<String> {
    val targetIndex = messages.indexOfFirst { it.id == messageId }
    if (targetIndex < 0) return listOf(messageId)
    val removalIds = linkedSetOf(messageId)
    if (messages[targetIndex].role == MessageRole.USER) {
        messages.drop(targetIndex + 1)
            .takeWhile { it.role != MessageRole.USER }
            .forEach { removalIds += it.id }
    }
    var expanded: Boolean
    do {
        expanded = false
        messages.forEach { message ->
            if (message.parentMessageId in removalIds && removalIds.add(message.id)) expanded = true
        }
    } while (expanded)
    return removalIds.toList()
}

class MessageManager(
    private val store: ChatStore,
    private val messageRepository: IMessageRepository,
    private val sessionRepository: ISessionRepository,
    /** 不绑定 ViewModel；生成协调器迁移后由应用级 scope 注入。 */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val hooks: MessageManagerHooks = MessageManagerHooks(),
    /** 聊天记录删除、恢复、编辑后同步对话记忆；为空时不维护记忆。 */
    private val memoryLifecycle: ConversationMemoryLifecycle? = null,
    private val stateBoardRepository: com.promenar.infinity.data.repository.StateBoardRepository? = null,
    private val isSessionGenerating: (String) -> Boolean = { id -> store.get().currentGeneratingSessionId == id },
) {
    private data class PendingUpdate(
        val epoch: Long,
        var content: String,
        var status: String? = null,
        var tokens: TokenUsage? = null,
        var reasoning: String? = null,
        var citations: List<Citation>? = null,
        var ragReferences: List<RagReference>? = null,
        var kgPaths: List<KgPath>? = null,
        var ragReferencesLoading: Boolean? = null,
        var ragMetadata: RagMetadata? = null,
        var thoughtSignature: String? = null,
        var taskState: TaskState? = null,
        var toolCalls: List<ToolCall>? = null,
        var clearToolCalls: Boolean = false,
        var executionSteps: List<ExecutionStep>? = null,
        var pendingApprovalToolIds: List<String>? = null,
        var toolResults: List<ToolResultArtifact>? = null,
        var isError: Boolean? = null,
        var errorMessage: String? = null,
        var clearError: Boolean = false,
        var isLongWait: Boolean? = null,
        var loopCount: Int? = null
    )

    private data class MessageLane(
        val mutex: Mutex = Mutex(),
        var epoch: Long = 0L,
    )

    private data class DbPendingUpdate(
        val epoch: Long,
        val updates: MutableMap<String, Any?>,
    )

    private val stateLock = Any()
    /** 正在写入记忆的轮次（会话:助手消息）及并发写入数，用于识别残留的“写入中”。 */
    private val activeTurnWrites = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val lanes = mutableMapOf<String, MessageLane>()
    private val purgingKeys = mutableSetOf<String>()
    private val pendingUpdates = mutableMapOf<String, PendingUpdate>()
    private val inFlightUiUpdates = mutableMapOf<String, PendingUpdate>()
    private val throttleJobs = mutableMapOf<String, Job>()

    private val dbPendingUpdates = mutableMapOf<String, DbPendingUpdate>()
    private val dbDebounceJobs = mutableMapOf<String, Job>()

    companion object {
        private const val UI_THROTTLE_MS = 100L
        private const val DB_DEBOUNCE_MS = 500L
    }

    suspend fun addMessage(sessionId: String, message: Message) {
        messageRepository.insert(message, sessionId)
        // 首页按最近消息时间排序；这笔记账失败不影响消息本身。
        try {
            sessionRepository.updatePartial(sessionId, mapOf("lastMessage" to message.content))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
        store.update { state ->
            state.copy(
                sessions = state.sessions.map { s ->
                    if (s.id == sessionId) {
                        val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
                        s.copy(
                            messages = s.messages + message,
                            lastMessage = message.content,
                            time = timeFormatter.format(Date(message.createdAt)),
                            updatedAt = maxOf(s.updatedAt, message.createdAt),
                        )
                    } else s
                }
            )
        }

    }

    /**
     * 将已经由跨表 Room 事务持久化的消息同步到内存 Store，禁止再次写库。
     */
    fun mirrorPersistedMessage(sessionId: String, message: Message) {
        store.update { state ->
            state.copy(
                sessions = state.sessions.map { session ->
                    if (session.id != sessionId || session.messages.any { it.id == message.id }) {
                        session
                    } else {
                        val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
                        // SYSTEM 记录（会话消息发送副本、暂停标记）不作为会话预览，避免首页显示内部 JSON。
                        val preview = message.role != MessageRole.SYSTEM
                        session.copy(
                            messages = session.messages + message,
                            lastMessage = if (preview) message.content else session.lastMessage,
                            time = if (preview) timeFormatter.format(Date(message.createdAt)) else session.time,
                            updatedAt = maxOf(session.updatedAt, message.createdAt),
                        )
                    }
                },
            )
        }
    }

    suspend fun setPendingApprovalState(
        sessionId: String,
        messageId: String,
        toolCallIds: List<String>,
    ) = commitPendingApprovalState(sessionId, messageId, toolCallIds)

    suspend fun clearPendingApprovalState(sessionId: String, messageId: String) =
        commitPendingApprovalState(sessionId, messageId, null)

    suspend fun mirrorPersistedPendingApprovalState(
        sessionId: String,
        messageId: String,
        toolCallIds: List<String>?,
    ) = commitPendingApprovalState(
        sessionId = sessionId,
        messageId = messageId,
        toolCallIds = toolCallIds,
        persistApprovalField = false,
    )

    suspend fun flushNonApprovalUpdatesNow(sessionId: String, messageId: String) =
        commitPendingApprovalState(
            sessionId = sessionId,
            messageId = messageId,
            toolCallIds = store.getSession(sessionId)?.messages
                ?.firstOrNull { it.id == messageId }?.pendingApprovalToolIds,
            persistApprovalField = false,
            retryOnFailure = false,
        )

    private suspend fun commitPendingApprovalState(
        sessionId: String,
        messageId: String,
        toolCallIds: List<String>?,
        persistApprovalField: Boolean = true,
        retryOnFailure: Boolean = true,
    ) {
        val key = "$sessionId:$messageId"
        lateinit var lane: MessageLane
        var pendingUi: PendingUpdate? = null
        var inFlightUi: PendingUpdate? = null
        var pendingDb: DbPendingUpdate? = null
        var retryUpdates: Map<String, Any?>? = null
        var failure: Throwable? = null
        synchronized(stateLock) {
            purgingKeys += key
            lane = lanes.getOrPut(key) { MessageLane() }
            lane.epoch += 1
            throttleJobs.remove(key)?.cancel()
            dbDebounceJobs.remove(key)?.cancel()
            pendingUi = pendingUpdates.remove(key)
            inFlightUi = inFlightUiUpdates.remove(key)
            pendingDb = dbPendingUpdates.remove(key)
        }

        try {
            lane.mutex.withLock {
                val updates = mutableMapOf<String, Any?>()
                pendingDb?.updates?.let(updates::putAll)
                inFlightUi?.toDbUpdates()?.let(updates::putAll)
                pendingUi?.toDbUpdates()?.let(updates::putAll)
                updates.remove("pendingApprovalToolIds")
                if (!persistApprovalField) updates.remove("toolCalls")
                if (persistApprovalField) updates["pendingApprovalToolIds"] = toolCallIds
                if (updates.isNotEmpty()) {
                    hooks.beforeDbWrite(key)
                    if (persistApprovalField) {
                        messageRepository.updatePartial(messageId, updates)
                    } else {
                        runCatching { messageRepository.updatePartial(messageId, updates) }
                            .onFailure {
                                retryUpdates = updates.toMap()
                                failure = it
                            }
                    }
                }

                store.updateMessageInSession(sessionId, messageId) { current ->
                    val withLatestPending = pendingUi?.let { pending ->
                        applyPendingToMessage(
                            current,
                            pending,
                            pending.tokens ?: current.tokens ?: TokenUsage(),
                        )
                    } ?: current
                    withLatestPending.copy(pendingApprovalToolIds = toolCallIds)
                }
                synchronized(stateLock) {
                    invalidateMessageStateLocked(key, lane)
                    if (lanes[key] === lane) lanes.remove(key)
                }
            }
        } finally {
            finishPurge(setOf(key))
        }
        retryUpdates?.let { requeueNonApprovalDbUpdate(sessionId, messageId, it) }
        if (!retryOnFailure) failure?.let { throw it }
    }

    private fun requeueNonApprovalDbUpdate(
        sessionId: String,
        messageId: String,
        updates: Map<String, Any?>,
    ) {
        val epoch = synchronized(stateLock) {
            lanes.getOrPut("$sessionId:$messageId") { MessageLane() }.epoch
        }
        debouncedDbUpdate(sessionId, messageId, epoch, updates)
    }

    fun updateMessage(sessionId: String, messageId: String, message: Message) {
        scope.launch {
            try {
                val updates = mapOf(
                    "content" to message.content,
                    "status" to message.status,
                    "reasoning" to message.reasoning,
                    "isArchived" to message.isArchived,
                    "tokens" to message.tokens,
                    "isError" to message.isError,
                    "errorMessage" to message.errorMessage
                )
                messageRepository.updatePartial(messageId, updates)
            } catch (_: Exception) {}
            
            store.updateMessageInSession(sessionId, messageId) { message }
        }
    }

    /** 模型专用字段先提交数据库，再更新内存；持久化失败时不得发送请求。 */
    suspend fun persistModelInputs(sessionId: String, messageId: String, updates: Map<String, String?>) =
        withContextMutation(sessionId) {
            require(updates.keys.all { it == "turnContext" || it == "requestInstruction" || it == "modelId" })
            messageRepository.updatePartial(messageId, updates)
            store.updateMessageInSession(sessionId, messageId) { message ->
                message.copy(
                    modelId = if ("modelId" in updates) updates["modelId"] else message.modelId,
                    turnContext = if ("turnContext" in updates) updates["turnContext"] else message.turnContext,
                    requestInstruction = if ("requestInstruction" in updates) updates["requestInstruction"] else message.requestInstruction,
                )
            }
        }

    /** 用户主动编辑正文；流式更新仍使用 updateMessageContent。 */
    suspend fun editMessageContent(sessionId: String, messageId: String, content: String) = withContextMutation(sessionId) {
        val purgeKeys = setOf("$sessionId:$messageId")
        try {
            purgeMessageState(sessionId, listOf(messageId))
            messageRepository.editContentInSession(sessionId, messageId, content)
            val persisted = checkNotNull(sessionRepository.getById(sessionId)) { "正文编辑后所属会话不存在" }
            val byId = persisted.messages.associateBy(Message::id)
            checkNotNull(byId[messageId]) { "正文编辑后消息不存在" }
            syncMemory { onMessageEdited(sessionId, messageId).forEach { (id, state) -> setTurnMemoryState(sessionId, id, state) } }
            store.update { state ->
                state.copy(
                    sessions = state.sessions.map { current ->
                        if (current.id != sessionId) current else current.copy(
                            summary = persisted.summary,
                            messages = current.messages.map { message ->
                                val saved = byId[message.id]
                                when {
                                    saved == null -> message
                                    message.id == messageId -> message.copy(content = saved.content, isArchived = saved.isArchived, turnContext = saved.turnContext)
                                    else -> message.copy(isArchived = saved.isArchived)
                                }
                            },
                        )
                    },
                )
            }
        } finally {
            finishPurge(purgeKeys)
        }
    }

    fun updateMessageContent(
        sessionId: String,
        messageId: String,
        content: String,
        options: UpdateMessageOptions? = null
    ) = enqueueUpdate(sessionId, messageId) { _ -> content to options }

    /**
     * 在 [stateLock] 内基于消息的“最新视图”计算并合并一次增量更新，正文沿用最新视图。
     *
     * 最新视图 = store 已提交消息 → 叠加正在刷入 store 的缓冲 → 叠加同代未刷新的节流缓冲。
     * 先从 store 读取列表字段（执行步骤、引用等）再经 [updateMessageContent] 整体写回，
     * 会读到节流窗口内尚未刷新的旧值并覆盖其间的追加；对这类读-改-写必须使用本方法。
     * [transform] 在锁内执行，只能做纯计算，不得回调 [MessageManager]。
     */
    fun updateFromLatest(
        sessionId: String,
        messageId: String,
        transform: (Message) -> UpdateMessageOptions?,
    ) = enqueueUpdate(sessionId, messageId) { latestView ->
        val latest = latestView() ?: return@enqueueUpdate null
        latest.content to transform(latest)
    }

    /** [resolve] 返回 null 表示放弃本次更新；最新视图按需惰性构造，流式正文热路径不承担额外开销。 */
    private fun enqueueUpdate(
        sessionId: String,
        messageId: String,
        resolve: (latestView: () -> Message?) -> Pair<String, UpdateMessageOptions?>?,
    ) {
        val key = "$sessionId:$messageId"
        if (store.getSession(sessionId)?.messages?.any { it.id == messageId } != true) return
        val jobToStart = synchronized(stateLock) {
            if (key in purgingKeys) return
            val lane = lanes.getOrPut(key) { MessageLane() }
            val pending = pendingUpdates[key]?.takeIf { it.epoch == lane.epoch }
            val (content, options) = resolve {
                // 必须在锁内读取：flushUpdate 先在锁内把缓冲转入 inFlight，写入 store 后才在锁内移除，
                // 因此任一时刻“store + inFlight + pending”都覆盖全部已排队的更新。
                val inFlight = inFlightUiUpdates[key]?.takeIf { it.epoch == lane.epoch }
                store.getSession(sessionId)?.messages?.firstOrNull { it.id == messageId }?.let { committed ->
                    listOfNotNull(inFlight, pending).fold(committed) { message, update ->
                        applyPendingToMessage(message, update, update.tokens ?: message.tokens ?: TokenUsage())
                    }
                }
            } ?: return
            val current = pending ?: PendingUpdate(epoch = lane.epoch, content = content)

            current.content = content
            options?.status?.let { current.status = it }
            options?.tokens?.let { current.tokens = it }
            options?.reasoning?.let { current.reasoning = it }
            options?.citations?.let { current.citations = it }
            options?.ragReferences?.let { current.ragReferences = it }
            options?.kgPaths?.let { current.kgPaths = it }
            options?.ragReferencesLoading?.let { current.ragReferencesLoading = it }
            options?.ragMetadata?.let { current.ragMetadata = it }
            options?.thoughtSignature?.let { current.thoughtSignature = it }
            options?.planningTask?.let { current.taskState = it }
            if (options?.clearToolCalls == true) {
                current.toolCalls = emptyList()
                current.clearToolCalls = true
            } else {
                options?.toolCalls?.let {
                    current.toolCalls = it
                    current.clearToolCalls = false
                }
            }
            options?.executionSteps?.let { current.executionSteps = it }
            options?.pendingApprovalToolIds?.let { current.pendingApprovalToolIds = it }
            options?.toolResults?.let { current.toolResults = it }
            if (options?.clearError == true) {
                current.isError = false
                current.errorMessage = null
                current.clearError = true
            } else {
                options?.isError?.let { current.isError = it }
                options?.errorMessage?.let { current.errorMessage = it }
            }
            options?.isLongWait?.let { current.isLongWait = it }
            options?.loopCount?.let { current.loopCount = it }
            pendingUpdates[key] = current

            if (throttleJobs.containsKey(key)) null else {
                scope.launch(start = CoroutineStart.LAZY) {
                    delay(UI_THROTTLE_MS)
                    flushUpdate(sessionId, messageId)
                }.also { throttleJobs[key] = it }
            }
        }
        jobToStart?.start()
    }

    internal suspend fun flushUpdate(sessionId: String, messageId: String) {
        val key = "$sessionId:$messageId"
        val lane = synchronized(stateLock) { lanes.getOrPut(key) { MessageLane() } }
        lane.mutex.withLock {
            val pending = synchronized(stateLock) {
                throttleJobs.remove(key)
                pendingUpdates.remove(key)?.takeIf { it.epoch == lane.epoch }
                    ?.also { inFlightUiUpdates[key] = it }
            } ?: return
            hooks.beforeUiApply(key)

            val session = store.getSession(sessionId) ?: return
            val message = session.messages.find { it.id == messageId } ?: return

        val oldTokens = message.tokens ?: TokenUsage()
        val newTokens = pending.tokens ?: oldTokens

        val tokensChanged = newTokens.total != oldTokens.total
        val deltaInput = newTokens.input - oldTokens.input
        val deltaOutput = newTokens.output - oldTokens.output
        val deltaTotal = newTokens.total - oldTokens.total

        val currentBilling = session.stats?.billing ?: BillingUsage()
        val updatedBilling = if (tokensChanged && deltaTotal > 0) {
            when (message.role) {
                MessageRole.ASSISTANT -> {
                    val newOutputCount = currentBilling.chatOutput.count + deltaOutput
                    val ragSystemDelta = deltaTotal - deltaOutput
                    val hasRag = pending.ragMetadata != null || pending.ragReferences != null || pending.kgPaths != null
                            || message.ragMetadata != null || message.ragReferences != null || message.kgPaths != null

                    if (hasRag && ragSystemDelta > 0) {
                        currentBilling.copy(
                            chatOutput = TokenMetric(newOutputCount),
                            ragSystem = TokenMetric(currentBilling.ragSystem.count + ragSystemDelta),
                            total = currentBilling.total + deltaTotal
                        )
                    } else {
                        currentBilling.copy(
                            chatOutput = TokenMetric(newOutputCount),
                            chatInput = TokenMetric(currentBilling.chatInput.count + deltaInput),
                            total = currentBilling.total + deltaTotal
                        )
                    }
                }
                else -> {
                    currentBilling.copy(
                        chatInput = TokenMetric(currentBilling.chatInput.count + deltaInput),
                        total = currentBilling.total + deltaTotal
                    )
                }
            }
        } else {
            currentBilling
        }

        val dbUpdates = buildMap {
            put("content", pending.content)
            pending.status?.let { put("status", it) }
            pending.tokens?.let { put("tokens", newTokens) }
            pending.reasoning?.let { put("reasoning", it) }
            pending.citations?.let { put("citations", it) }
            pending.ragReferences?.let { put("ragReferences", it) }
            pending.kgPaths?.let { put("kgPaths", it) }
            pending.ragMetadata?.let { put("ragMetadata", it) }
            pending.thoughtSignature?.let { put("thoughtSignature", it) }
            pending.taskState?.let { put("planningTask", it) }
            pending.executionSteps?.let { put("executionSteps", it) }
            pending.pendingApprovalToolIds?.let { put("pendingApprovalToolIds", it) }
            pending.toolResults?.let { put("toolResults", it) }
            if (pending.clearToolCalls) put("toolCalls", emptyList<ToolCall>())
            else pending.toolCalls?.let { put("toolCalls", it) }
            if (pending.clearError) {
                put("isError", false)
                put("errorMessage", null)
            } else {
                pending.isError?.let { put("isError", it) }
                pending.errorMessage?.let { put("errorMessage", it) }
            }
        }

            debouncedDbUpdate(sessionId, messageId, pending.epoch, dbUpdates)

            store.update { state ->
                state.copy(
                    sessions = state.sessions.map { s ->
                        if (s.id == sessionId) {
                            s.copy(
                                messages = s.messages.map { m ->
                                    if (m.id == messageId) {
                                        applyPendingToMessage(m, pending, newTokens)
                                    } else m
                                },
                                lastMessage = pending.content,
                                stats = (s.stats ?: SessionStats()).copy(
                                    totalTokens = updatedBilling.total,
                                    billing = updatedBilling
                                )
                            )
                        } else s
                    }
                )
            }
            synchronized(stateLock) {
                if (inFlightUiUpdates[key] === pending) inFlightUiUpdates.remove(key)
            }
        }
    }

    private fun applyPendingToMessage(
        message: Message,
        pending: PendingUpdate,
        newTokens: TokenUsage
    ): Message = message.copy(
        content = pending.content,
        status = pending.status ?: message.status,
        tokens = newTokens,
        reasoning = pending.reasoning ?: message.reasoning,
        citations = pending.citations ?: message.citations,
        ragReferences = pending.ragReferences ?: message.ragReferences,
        kgPaths = pending.kgPaths ?: message.kgPaths,
        ragReferencesLoading = pending.ragReferencesLoading ?: message.ragReferencesLoading,
        ragMetadata = pending.ragMetadata ?: message.ragMetadata,
        thoughtSignature = pending.thoughtSignature ?: message.thoughtSignature,
        planningTask = pending.taskState ?: message.planningTask,
        toolCalls = if (pending.clearToolCalls) emptyList() else pending.toolCalls ?: message.toolCalls,
        executionSteps = pending.executionSteps ?: message.executionSteps,
        pendingApprovalToolIds = pending.pendingApprovalToolIds ?: message.pendingApprovalToolIds,
        toolResults = pending.toolResults ?: message.toolResults,
        isError = if (pending.clearError) false else pending.isError ?: message.isError,
        errorMessage = if (pending.clearError) null else pending.errorMessage ?: message.errorMessage,
        isLongWait = pending.isLongWait ?: message.isLongWait,
        loopCount = pending.loopCount ?: message.loopCount
    )

    private fun debouncedDbUpdate(
        sessionId: String,
        messageId: String,
        epoch: Long,
        updates: Map<String, Any?>,
    ) {
        val key = "$sessionId:$messageId"
        val jobToStart = synchronized(stateLock) {
            val lane = lanes.getOrPut(key) { MessageLane() }
            if (lane.epoch != epoch) return
            val existing = dbPendingUpdates[key]
                ?.takeIf { it.epoch == epoch }
                ?: DbPendingUpdate(epoch, mutableMapOf())
            existing.updates.putAll(updates)
            dbPendingUpdates[key] = existing

            dbDebounceJobs.remove(key)?.cancel()
            scope.launch(start = CoroutineStart.LAZY) {
                delay(DB_DEBOUNCE_MS)
                flushDbUpdate(sessionId, key, messageId, lane)
            }.also { dbDebounceJobs[key] = it }
        }
        jobToStart.start()
    }

    private suspend fun flushDbUpdate(
        sessionId: String,
        key: String,
        messageId: String,
        lane: MessageLane,
    ) {
        var retry: Map<String, Any?>? = null
        var flushedEpoch: Long? = null
        lane.mutex.withLock {
            val pending = synchronized(stateLock) {
                dbDebounceJobs.remove(key)
                dbPendingUpdates.remove(key)?.takeIf { it.epoch == lane.epoch }
            } ?: return
            flushedEpoch = pending.epoch
            hooks.beforeDbWrite(key)
            try {
                messageRepository.updatePartial(messageId, pending.updates)
            } catch (_: Exception) {
                retry = pending.updates.toMap()
            }
        }
        retry?.let { updates ->
            requeueNonApprovalDbUpdate(sessionId, messageId, updates)
        } ?: synchronized(stateLock) {
            val epoch = flushedEpoch ?: return@synchronized
            val hasNewCoordinatedWork = pendingUpdates[key]?.epoch == epoch ||
                inFlightUiUpdates[key]?.epoch == epoch ||
                dbPendingUpdates[key]?.epoch == epoch ||
                throttleJobs.containsKey(key) ||
                dbDebounceJobs.containsKey(key) ||
                key in purgingKeys
            if (lanes[key] === lane && lane.epoch == epoch && !hasNewCoordinatedWork) {
                lanes.remove(key)
            }
        }
    }

    private fun PendingUpdate.toDbUpdates(): MutableMap<String, Any?> = buildMap<String, Any?> {
        put("content", content)
        status?.let { put("status", it) }
        tokens?.let { put("tokens", it) }
        reasoning?.let { put("reasoning", it) }
        citations?.let { put("citations", it) }
        ragReferences?.let { put("ragReferences", it) }
        kgPaths?.let { put("kgPaths", it) }
        ragReferencesLoading?.let { put("ragReferencesLoading", it) }
        ragMetadata?.let { put("ragMetadata", it) }
        thoughtSignature?.let { put("thoughtSignature", it) }
        taskState?.let { put("planningTask", it) }
        executionSteps?.let { put("executionSteps", it) }
        pendingApprovalToolIds?.let { put("pendingApprovalToolIds", it) }
        toolResults?.let { put("toolResults", it) }
        if (clearToolCalls) put("toolCalls", emptyList<ToolCall>())
        else toolCalls?.let { put("toolCalls", it) }
        if (clearError) {
            put("isError", false)
            put("errorMessage", null)
        } else {
            isError?.let { put("isError", it) }
            errorMessage?.let { put("errorMessage", it) }
        }
    }.toMutableMap()

    private fun clearRagFields(updates: MutableMap<String, Any?>) {
        updates["ragProgress"] = null
        updates["ragReferences"] = null
        updates["kgPaths"] = null
        updates["ragMetadata"] = null
        updates["citations"] = null
        updates["ragReferencesLoading"] = false
    }

    private fun applyPendingAndClearRag(
        message: Message,
        pending: PendingUpdate?,
    ): Message {
        val updated = if (pending == null) message else {
            applyPendingToMessage(message, pending, pending.tokens ?: message.tokens ?: TokenUsage())
        }
        return updated.copy(
            ragProgress = null,
            ragReferences = null,
            kgPaths = null,
            ragMetadata = null,
            citations = null,
            ragReferencesLoading = false,
        )
    }

    private fun clearMessageRagStateSerialized(
        sessionId: String,
        messageId: String,
        key: String,
        lane: MessageLane,
        pendingUi: PendingUpdate?,
        pendingDb: DbPendingUpdate?,
    ) {
        scope.launch {
            lane.mutex.withLock {
                val dbUpdates = pendingDb?.updates?.toMutableMap() ?: mutableMapOf()
                pendingUi?.toDbUpdates()?.let(dbUpdates::putAll)
                clearRagFields(dbUpdates)
                try {
                    hooks.beforeDbWrite(key)
                    messageRepository.updatePartial(messageId, dbUpdates)
                } catch (_: Exception) {
                }

                store.updateMessageInSession(sessionId, messageId) { message ->
                    applyPendingAndClearRag(message, pendingUi)
                }
            }
        }
    }

    private fun invalidateMessageStateLocked(key: String, lane: MessageLane) {
        lane.epoch += 1
        throttleJobs.remove(key)?.cancel()
        dbDebounceJobs.remove(key)?.cancel()
        pendingUpdates.remove(key)
        inFlightUiUpdates.remove(key)
        dbPendingUpdates.remove(key)
    }

    private suspend fun purgeMessageState(sessionId: String, messageIds: Collection<String>): Set<String> {
        val keyedLanes = synchronized(stateLock) {
            messageIds.distinct().associate { messageId ->
                val key = "$sessionId:$messageId"
                purgingKeys += key
                val lane = lanes.getOrPut(key) { MessageLane() }
                invalidateMessageStateLocked(key, lane)
                key to lane
            }
        }
        keyedLanes.forEach { (key, lane) ->
            lane.mutex.withLock {
                synchronized(stateLock) {
                    // 等待在飞 flush 后再次失效，覆盖等锁期间已排队的任何旧 epoch 状态。
                    invalidateMessageStateLocked(key, lane)
                    if (lanes[key] === lane) lanes.remove(key)
                }
            }
        }
        return keyedLanes.keys
    }

    private fun finishPurge(keys: Set<String>) {
        synchronized(stateLock) { purgingKeys.removeAll(keys) }
    }

    private fun cascadeRemovalIds(messages: List<Message>, rootIds: Collection<String>): Set<String> {
        val removed = rootIds.toMutableSet()
        do {
            val previousSize = removed.size
            messages.forEach { message ->
                if (message.parentMessageId in removed) removed += message.id
            }
        } while (removed.size != previousSize)
        return removed
    }

    private fun mirrorDeletedMessages(
        sessionId: String,
        removedIds: Set<String>,
        persistedSnapshot: Session?,
        clearAll: Boolean = false,
    ) {
        val archivedById = persistedSnapshot?.messages.orEmpty().associate { it.id to it.isArchived }
        store.update { state ->
            state.copy(
                sessions = state.sessions.map { current ->
                    if (current.id != sessionId) current else current.copy(
                        summary = if (persistedSnapshot != null) persistedSnapshot.summary else current.summary,
                        messages = if (clearAll) emptyList() else current.messages
                            .filterNot { it.id in removedIds }
                            .map { message ->
                                archivedById[message.id]?.let { message.copy(isArchived = it) } ?: message
                            },
                        lastMessage = if (clearAll) null else current.lastMessage,
                    )
                },
            )
        }
    }

    suspend fun deleteMessage(
        sessionId: String,
        messageId: String,
        onAbortGeneration: ((String) -> Unit)? = null
    ) = deleteMessages(sessionId, listOf(messageId), onAbortGeneration)

    /** 删除多条消息及其派生子消息（工具结果等），Store 与数据库一次性同步。 */
    suspend fun deleteMessages(
        sessionId: String,
        messageIds: List<String>,
        onAbortGeneration: ((String) -> Unit)? = null
    ) = deleteMessagesInternal(sessionId, messageIds, onAbortGeneration)

    suspend fun deleteMessagesWithStateUndo(
        sessionId: String,
        messageIds: List<String>,
        onAbortGeneration: ((String) -> Unit)? = null,
    ): List<com.promenar.infinity.data.local.db.entity.StateBoardVersionEntity> {
        var captured = emptyList<com.promenar.infinity.data.local.db.entity.StateBoardVersionEntity>()
        deleteMessagesInternal(sessionId, messageIds, onAbortGeneration) { captured = it }
        return captured
    }

    private suspend fun deleteMessagesInternal(
        sessionId: String,
        messageIds: List<String>,
        onAbortGeneration: ((String) -> Unit)?,
        onStateCaptured: ((List<com.promenar.infinity.data.local.db.entity.StateBoardVersionEntity>) -> Unit)? = null,
    ) {
        if (messageIds.isEmpty()) return
        withContextMutation(sessionId) {
            val storeMessages = store.getSession(sessionId)?.messages.orEmpty()
            val persistedMessages = messageRepository.getBySession(sessionId)
            val allMessages = (storeMessages + persistedMessages).distinctBy { it.id }
            val removalIds = messageIds.flatMapTo(linkedSetOf()) { messageRemovalIds(allMessages, it) }.toList()
            if (
                isSessionGenerating(sessionId) &&
                store.getSession(sessionId)?.messages?.lastOrNull()?.id in removalIds
            ) {
                onAbortGeneration?.invoke(sessionId)
            }

            val purgeKeys = purgeMessageState(sessionId, removalIds)
            try {
                suspend fun deleteRows() {
                    check(messageRepository.deleteManyInSession(sessionId, removalIds) > 0) {
                        "消息不存在或不属于当前会话"
                    }
                }
                if (onStateCaptured != null && stateBoardRepository != null) {
                    onStateCaptured(stateBoardRepository.captureDeletion(sessionId, removalIds.toSet()) { deleteRows() })
                } else deleteRows()
                val persistedSnapshot = sessionRepository.getById(sessionId)
                mirrorDeletedMessages(sessionId, removalIds.toSet(), persistedSnapshot)
            } finally {
                finishPurge(purgeKeys)
            }
            syncMemory { onMessagesRemoved(sessionId) }
        }
    }

    /** 撤销删除：重新写入消息，并按创建时间把它们放回原位置。 */
    suspend fun restoreMessages(sessionId: String, messages: List<Message>) {
        if (messages.isEmpty()) return
        withContextMutation(sessionId) {
            val restoredMessages = messages.map { it.copy(isArchived = false) }
            restoredMessages.forEach { messageRepository.insert(it, sessionId) }
            val restoredIds = restoredMessages.mapTo(hashSetOf()) { it.id }
            store.update { state ->
                state.copy(
                    sessions = state.sessions.map { s ->
                        if (s.id == sessionId) {
                            s.copy(messages = (s.messages.filterNot { it.id in restoredIds } + restoredMessages).sortedBy { it.createdAt })
                        } else s
                    }
                )
            }
            // 恢复记忆后再修正恢复快照里残留的“写入中”。
            syncMemory { onMessagesRestored(sessionId); reconcileTurnMemory(sessionId) }
        }
    }

    /** 发送准备失败的幂等补偿：只按 session+message 删除，DB 无行时也清理 Store 与 lane。 */
    suspend fun discardPreparedMessage(sessionId: String, messageId: String) =
        discardPreparedMessages(sessionId, listOf(messageId))

    /** 同一发送回合的组级补偿；调用方按 assistant→user 传入，Store 只做一次原子过滤。 */
    suspend fun discardPreparedMessages(sessionId: String, messageIds: List<String>) {
        withContextMutation(sessionId) {
            val orderedIds = messageIds.distinct()
            val removalIds = cascadeRemovalIds(store.getSession(sessionId)?.messages.orEmpty(), orderedIds)
            val purgeKeys = purgeMessageState(sessionId, removalIds)
            var failure: Throwable? = null
            var hasDeleted = false
            try {
                orderedIds.forEach { messageId ->
                    try {
                        hasDeleted = messageRepository.deleteInSession(sessionId, messageId) || hasDeleted
                    } catch (error: Throwable) {
                        if (failure == null) failure = error else failure?.addSuppressed(error)
                    }
                }
                val persistedSnapshot = if (hasDeleted) {
                    try {
                        sessionRepository.getById(sessionId)
                    } catch (error: Throwable) {
                        if (failure == null) failure = error else failure?.addSuppressed(error)
                        null
                    }
                } else null
                mirrorDeletedMessages(sessionId, removalIds, persistedSnapshot)
            } finally {
                finishPurge(purgeKeys)
            }
            if (hasDeleted) syncMemory { onMessagesRemoved(sessionId) }
            failure?.let { throw it }
        }
    }

    suspend fun deleteMessagesAfter(
        sessionId: String,
        timestamp: Long,
        onAbortGeneration: ((String) -> Unit)? = null
    ) {
        withContextMutation(sessionId) {
            if (isSessionGenerating(sessionId)) {
                onAbortGeneration?.invoke(sessionId)
            }

            val storeMessages = store.getSession(sessionId)?.messages.orEmpty()
            val persistedMessages = messageRepository.getBySession(sessionId)
            val allMessages = (storeMessages + persistedMessages).distinctBy { it.id }
            val targetIds = cascadeRemovalIds(
                allMessages,
                allMessages.filter { it.createdAt >= timestamp }.map { it.id },
            )
            val purgeKeys = purgeMessageState(sessionId, targetIds)
            try {
                // 数据库删除失败必须向上抛出，避免 Store 已删而数据库仍保留、重启后旧消息回流。
                messageRepository.deleteMessagesAfter(sessionId, timestamp)
                val persistedSnapshot = sessionRepository.getById(sessionId)
                mirrorDeletedMessages(sessionId, targetIds, persistedSnapshot)
            } finally {
                finishPurge(purgeKeys)
            }
            syncMemory { onMessagesRemoved(sessionId) }
        }
    }

    /**
     * 记忆维护在后台执行，不阻塞聊天记录变更，失败也不影响变更本身。
     * 各操作按执行时的数据库状态判定，由实现方串行化，乱序执行结果一致。
     */
    private fun syncMemory(action: suspend ConversationMemoryLifecycle.() -> Unit) {
        val lifecycle = memoryLifecycle ?: return
        scope.launch {
            try {
                lifecycle.action()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                InfinityLogger.logError("[MessageManager] 对话记忆同步失败", error)
            }
        }
    }

    fun updateMessageProgress(sessionId: String, messageId: String, progress: RagProgress) {
        store.updateMessageInSession(sessionId, messageId) { m ->
            m.copy(ragProgress = progress)
        }
    }

    fun clearMessageRagState(sessionId: String, messageId: String) {
        val key = "$sessionId:$messageId"
        val snapshot = synchronized(stateLock) {
            val lane = lanes.getOrPut(key) { MessageLane() }
            lane.epoch += 1
            throttleJobs.remove(key)?.cancel()
            dbDebounceJobs.remove(key)?.cancel()
            val pendingUi = pendingUpdates.remove(key) ?: inFlightUiUpdates.remove(key)
            Triple(lane, pendingUi, dbPendingUpdates.remove(key))
        }
        clearMessageRagStateSerialized(
            sessionId = sessionId,
            messageId = messageId,
            key = key,
            lane = snapshot.first,
            pendingUi = snapshot.second,
            pendingDb = snapshot.third,
        )
    }

    fun updateMessageLayout(sessionId: String, messageId: String, height: Double) {
        val session = store.getSession(sessionId) ?: return
        val message = session.messages.find { it.id == messageId } ?: return

        if (message.layoutHeight == null || kotlin.math.abs((message.layoutHeight ?: 0.0) - height) > 10) {
            store.updateMessageInSession(sessionId, messageId) { m ->
                m.copy(layoutHeight = height)
            }
        }
    }

    /**
     * 一轮的记忆写入状态记在该轮最后一条助手消息上，见 [TurnMemoryState]。
     * 只改写状态列；消息已被删除时数据库中无行可改，界面状态也不再包含该消息。
     */
    suspend fun setTurnMemoryState(sessionId: String, assistantMessageId: String, state: TurnMemoryState?) {
        val encoded = state?.encode()
        try {
            messageRepository.updateTurnMemoryState(assistantMessageId, encoded)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            InfinityLogger.logError("[MessageManager] 记忆状态写入失败", error)
        }
        store.updateMessageInSession(sessionId, assistantMessageId) { it.copy(vectorizationStatus = encoded) }
    }

    /**
     * 执行一轮记忆写入并维护其状态：先记“写入中”，[write] 返回最终状态（异常记为失败）。
     * 最终状态只在该轮仍是本次“写入中”时落盘，写入期间被移除、编辑或删除后恢复的状态保留；
     * 取消时按该轮实际存在的片段记为已记入，没有片段则恢复为 [previous]。
     */
    suspend fun writeTurnMemory(
        sessionId: String,
        assistantMessageId: String,
        previous: TurnMemoryState?,
        write: suspend () -> TurnMemoryState?,
    ) {
        val key = "$sessionId:$assistantMessageId"
        val writing = TurnMemoryState(TurnMemoryState.Status.WRITING, updatedAt = System.currentTimeMillis())
        activeTurnWrites.compute(key) { _, count -> (count ?: 0) + 1 }
        try {
            setTurnMemoryState(sessionId, assistantMessageId, writing)
            val result = try {
                write()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                InfinityLogger.logError("[MessageManager] 本轮记忆写入失败", error)
                TurnMemoryState(TurnMemoryState.Status.FAILED, updatedAt = System.currentTimeMillis())
            }
            withContext(NonCancellable) { compareAndSetTurnMemoryState(sessionId, assistantMessageId, writing.encode(), result) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                val stored = storedTurnMemory(sessionId, assistantMessageId)
                compareAndSetTurnMemoryState(sessionId, assistantMessageId, writing.encode(), stored ?: previous)
            }
            throw cancelled
        } finally {
            activeTurnWrites.computeIfPresent(key) { _, count -> (count - 1).takeIf { it > 0 } }
        }
    }

    /**
     * 修正没有写入在进行却仍显示“写入中”的轮次：写入途中进程被结束，或撤销删除恢复了写入中的快照。
     * 按该轮实际存在的片段记为已记入，没有片段时记为失败以便重试。
     */
    suspend fun reconcileTurnMemory(sessionId: String) {
        val lifecycle = memoryLifecycle ?: return
        val stale = store.getSession(sessionId)?.messages.orEmpty().filter { message ->
            message.turnMemory?.status == TurnMemoryState.Status.WRITING &&
                !activeTurnWrites.containsKey("$sessionId:${message.id}")
        }
        for (message in stale) {
            val count = try {
                lifecycle.turnChunkCount(sessionId, message.id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                InfinityLogger.logError("[MessageManager] 记忆状态修正失败", error)
                continue
            }
            val now = System.currentTimeMillis()
            compareAndSetTurnMemoryState(
                sessionId, message.id, message.vectorizationStatus,
                if (count > 0) TurnMemoryState(TurnMemoryState.Status.STORED, count, now) else TurnMemoryState(TurnMemoryState.Status.FAILED, updatedAt = now),
            )
        }
    }

    private suspend fun storedTurnMemory(sessionId: String, assistantMessageId: String): TurnMemoryState? = try {
        memoryLifecycle?.turnChunkCount(sessionId, assistantMessageId)?.takeIf { it > 0 }
            ?.let { TurnMemoryState(TurnMemoryState.Status.STORED, it, System.currentTimeMillis()) }
    } catch (error: Exception) {
        InfinityLogger.logError("[MessageManager] 读取本轮记忆片段失败", error)
        null
    }

    /** 仅当状态列仍为 [expected] 时改写；数据库不可用时按界面状态判断。 */
    private suspend fun compareAndSetTurnMemoryState(sessionId: String, assistantMessageId: String, expected: String?, state: TurnMemoryState?) {
        val encoded = state?.encode()
        val applied = try {
            messageRepository.compareAndSetTurnMemoryState(assistantMessageId, expected, encoded)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            InfinityLogger.logError("[MessageManager] 记忆状态写入失败", error)
            true
        }
        if (!applied) return
        store.updateMessageInSession(sessionId, assistantMessageId) { message ->
            if (message.vectorizationStatus == expected) message.copy(vectorizationStatus = encoded) else message
        }
    }

    suspend fun setVectorizationStatus(sessionId: String, messageIds: List<String>, status: String) = withContextMutation(sessionId) {
        for (msgId in messageIds) {
            try {
                // 向量化状态独立于摘要归档；isArchived 只由摘要成功提交设置。
                messageRepository.updateArchiveVectorizationStatus(msgId, status)
            } catch (_: Exception) {
            }
        }

        val idSet = messageIds.toSet()
        store.update { state ->
            state.copy(
                sessions = state.sessions.map { s ->
                    if (s.id == sessionId) {
                        s.copy(
                            messages = s.messages.map { m ->
                                if (idSet.contains(m.id) && m.turnMemory == null) {
                                    m.copy(vectorizationStatus = status)
                                } else m
                            }
                        )
                    } else s
                }
            )
        }
    }

    suspend fun clearMessages(sessionId: String) {
        withContextMutation(sessionId) {
            val prefix = "$sessionId:"
            val coordinatedIds = synchronized(stateLock) {
                (lanes.keys + pendingUpdates.keys + inFlightUiUpdates.keys + throttleJobs.keys +
                    dbPendingUpdates.keys + dbDebounceJobs.keys)
                    .asSequence()
                    .filter { it.startsWith(prefix) }
                    .map { it.removePrefix(prefix) }
                    .toSet()
            }
            val targetIds = store.getSession(sessionId)?.messages.orEmpty().mapTo(coordinatedIds.toMutableSet()) { it.id }
            val purgeKeys = purgeMessageState(sessionId, targetIds)
            try {
                var persistedSnapshot: Session? = null
                try {
                    messageRepository.deleteBySessionId(sessionId)
                    persistedSnapshot = sessionRepository.getById(sessionId)
                } catch (_: Exception) {
                }
                mirrorDeletedMessages(sessionId, targetIds, persistedSnapshot, clearAll = true)
            } finally {
                finishPurge(purgeKeys)
            }
            syncMemory { onMessagesRemoved(sessionId) }
        }
    }

    suspend fun flushMessageUpdates(sessionId: String, messageId: String) {
        flushUpdate(sessionId, messageId)
    }

    suspend fun flushGenerationTerminal(sessionId: String, messageId: String) {
        val key = "$sessionId:$messageId"
        lateinit var lane: MessageLane
        var pendingUi: PendingUpdate? = null
        var inFlightUi: PendingUpdate? = null
        var pendingDb: DbPendingUpdate? = null
        synchronized(stateLock) {
            purgingKeys += key
            lane = lanes.getOrPut(key) { MessageLane() }
            lane.epoch += 1
            throttleJobs.remove(key)?.cancel()
            dbDebounceJobs.remove(key)?.cancel()
            pendingUi = pendingUpdates.remove(key)
            inFlightUi = inFlightUiUpdates.remove(key)
            pendingDb = dbPendingUpdates.remove(key)
        }
        var failure: Throwable? = null
        try {
            lane.mutex.withLock {
                val current = store.getSession(sessionId)?.messages?.firstOrNull { it.id == messageId }
                    ?: return@withLock
                val merged = listOfNotNull(inFlightUi, pendingUi).fold(current) { message, pending ->
                    applyPendingToMessage(message, pending, pending.tokens ?: message.tokens ?: TokenUsage())
                }
                val updates = mutableMapOf<String, Any?>()
                pendingDb?.updates?.let(updates::putAll)
                inFlightUi?.toDbUpdates()?.let(updates::putAll)
                pendingUi?.toDbUpdates()?.let(updates::putAll)
                if (updates.isNotEmpty()) {
                    try {
                        messageRepository.updatePartial(messageId, updates)
                    } catch (error: Throwable) {
                        failure = error
                    }
                }
                store.updateMessageInSession(sessionId, messageId) { merged }
                synchronized(stateLock) {
                    invalidateMessageStateLocked(key, lane)
                    if (lanes[key] === lane) lanes.remove(key)
                }
            }
        } finally {
            finishPurge(setOf(key))
        }
        failure?.let { throw it }
    }

    suspend fun markGenerationTerminal(
        sessionId: String,
        messageId: String,
        content: String,
        status: String,
        errorMessage: String? = null,
    ) {
        val key = "$sessionId:$messageId"
        lateinit var lane: MessageLane
        var pendingUi: PendingUpdate? = null
        var inFlightUi: PendingUpdate? = null
        var pendingDb: DbPendingUpdate? = null
        synchronized(stateLock) {
            purgingKeys += key
            lane = lanes.getOrPut(key) { MessageLane() }
            lane.epoch += 1
            throttleJobs.remove(key)?.cancel()
            dbDebounceJobs.remove(key)?.cancel()
            pendingUi = pendingUpdates.remove(key)
            inFlightUi = inFlightUiUpdates.remove(key)
            pendingDb = dbPendingUpdates.remove(key)
        }
        var failure: Throwable? = null
        try {
            lane.mutex.withLock {
                val current = store.getSession(sessionId)?.messages?.firstOrNull { it.id == messageId }
                    ?: return@withLock
                val merged = listOfNotNull(inFlightUi, pendingUi).fold(current) { message, pending ->
                    applyPendingToMessage(message, pending, pending.tokens ?: message.tokens ?: TokenUsage())
                }
                val isError = status == "error"
                val clearUnconfirmedTools = status != "success"
                val completedIds = store.getSession(sessionId)?.messages.orEmpty()
                    .filter { it.role == MessageRole.TOOL && it.parentMessageId == messageId }
                    .mapNotNull { it.toolCallId }.toSet()
                val terminal = merged.copy(
                    content = content,
                    status = status,
                    isError = isError,
                    errorMessage = errorMessage,
                    toolCalls = if (clearUnconfirmedTools) merged.toolCalls.orEmpty().filter { it.id in completedIds } else merged.toolCalls,
                )
                val updates = mutableMapOf<String, Any?>()
                pendingDb?.updates?.let(updates::putAll)
                inFlightUi?.toDbUpdates()?.let(updates::putAll)
                pendingUi?.toDbUpdates()?.let(updates::putAll)
                updates.putAll(
                    mapOf(
                        "content" to content,
                        "status" to status,
                        "isError" to isError,
                        "errorMessage" to errorMessage,
                    ),
                )
                if (clearUnconfirmedTools) updates["toolCalls"] = terminal.toolCalls.orEmpty()
                try {
                    messageRepository.updatePartial(messageId, updates)
                    store.updateMessageInSession(sessionId, messageId) { terminal }
                } catch (error: Throwable) {
                    failure = error
                }
                synchronized(stateLock) {
                    invalidateMessageStateLocked(key, lane)
                    if (lanes[key] === lane) lanes.remove(key)
                }
            }
        } finally {
            finishPurge(setOf(key))
        }
        failure?.let { throw it }
    }

    fun hasPendingUpdates(sessionId: String, messageId: String): Boolean {
        return synchronized(stateLock) {
            pendingUpdates.containsKey("$sessionId:$messageId")
        }
    }

    internal fun coordinationState(): MessageCoordinationState = synchronized(stateLock) {
        MessageCoordinationState(
            lanes = lanes.size,
            pendingUi = pendingUpdates.size,
            inFlightUi = inFlightUiUpdates.size,
            uiJobs = throttleJobs.size,
            pendingDb = dbPendingUpdates.size,
            dbJobs = dbDebounceJobs.size,
            purging = purgingKeys.size,
        )
    }
}
