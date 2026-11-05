package com.promenar.infinity.data.rag

import android.content.SharedPreferences
import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.dao.KgTurnCount
import com.promenar.infinity.data.local.db.dao.KgTurnRelation
import com.promenar.infinity.data.local.db.dao.SessionGraphTaskRow
import com.promenar.infinity.data.model.contentForModel
import com.promenar.infinity.data.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.LocalDate

/*
 * 会话图谱抽取：每轮回复结束后把本轮问答排入后台队列（任务类型 VectorizationQueue.TYPE_SESSION_GRAPH），
 * 抽取出的实体与关系写成 `session` 证据并带本轮最后一条助手消息的 id。证据的可见条件要求该消息存在，
 * 删除、丢弃、回退与撤销由统一规则处理；写入目标由会话图谱绑定决定。
 *
 * 每轮的抽取状态不另存列：排队、进行中、失败与未完成由任务行表达，完成后任务行删除，
 * 由证据按消息 id 统计实体与关系数。
 */

/** 本轮问答用于抽取的文本；过短的轮次不值得一次模型调用，返回 null。 */
internal object SessionGraphTurn {
    const val MIN_CHARS = 24
    const val MAX_CHARS = 12_000

    fun text(userContent: String, assistantContent: String): String? {
        val user = userContent.trim()
        val assistant = assistantContent.trim()
        if (user.isEmpty() || assistant.isEmpty() || user.length + assistant.length < MIN_CHARS) return null
        return "User: $user\n\nAssistant: $assistant".take(MAX_CHARS)
    }
}

/** 为一轮排入抽取任务；开关关闭、消息不存在或本轮过短时不排入，返回是否排入。 */
fun interface SessionGraphScheduler {
    suspend fun schedule(sessionId: String, assistantMessageId: String, userMessageId: String?): Boolean
}

/** 队列中会话图谱任务的执行者。 */
fun interface SessionGraphTaskProcessor {
    /** 预算耗尽时返回未完成的说明，任务记为部分完成；完整完成返回 null。 */
    suspend fun process(task: VectorizationTask): String?
}

class RoomSessionGraphScheduler(
    private val databaseProvider: () -> InfinityDatabase,
    private val enabled: () -> Boolean,
    private val queue: suspend () -> VectorizationQueue,
) : SessionGraphScheduler {
    override suspend fun schedule(sessionId: String, assistantMessageId: String, userMessageId: String?): Boolean {
        if (!enabled()) return false
        if (!withContext(Dispatchers.IO) { KgCollectionRepository(databaseProvider()).readBinding(sessionId).writable }) return false
        val turn = withContext(Dispatchers.IO) { loadTurn(databaseProvider(), sessionId, assistantMessageId, userMessageId) } ?: return false
        if (SessionGraphTurn.text(turn.second, turn.third) == null) return false
        queue().enqueueSessionGraph(sessionId, turn.first, assistantMessageId, turn.second, turn.third)
        return true
    }

    /** 返回（用户消息 id、用户正文、助手正文）；未指定用户消息时沿父消息链向上找本轮用户消息。 */
    private suspend fun loadTurn(
        database: InfinityDatabase,
        sessionId: String,
        assistantMessageId: String,
        userMessageId: String?,
    ): Triple<String, String, String>? {
        val messages = database.messageDao()
        val assistant = messages.getById(assistantMessageId)
            ?.takeIf { it.sessionId == sessionId && MessageRole.fromSerializedName(it.role) == MessageRole.ASSISTANT }
            ?: return null
        var user = userMessageId?.let { messages.getById(it) }
        var cursor = assistant.parentMessageId
        var hops = 0
        while (user == null && cursor != null && hops++ < MAX_PARENT_HOPS) {
            val parent = messages.getById(cursor) ?: break
            if (MessageRole.fromSerializedName(parent.role) == MessageRole.USER) user = parent
            cursor = parent.parentMessageId
        }
        val owner = user?.takeIf { it.sessionId == sessionId } ?: return null
        return Triple(owner.id, owner.contentForModel(), assistant.content)
    }

    private companion object {
        const val MAX_PARENT_HOPS = 64
    }
}

class RoomSessionGraphProcessor(
    private val databaseProvider: () -> InfinityDatabase,
    private val extractor: () -> GraphExtractor,
    private val stats: SessionGraphStats,
) : SessionGraphTaskProcessor {
    override suspend fun process(task: VectorizationTask): String? {
        val sessionId = requireNotNull(task.sessionId) { "会话图谱任务缺少会话" }
        val messageId = requireNotNull(task.assistantMessageId) { "会话图谱任务缺少助手消息" }
        val binding = withContext(Dispatchers.IO) { KgCollectionRepository(databaseProvider()).readBinding(sessionId) }
        if (!binding.writable) return null
        val text = SessionGraphTurn.text(task.userContent.orEmpty(), task.aiContent.orEmpty()) ?: return null
        val contentHash = kgSha256(text)
        val result = extractor().extractCandidate(text, contentHash = contentHash, sessionId = sessionId)
        stats.record(calls = result.progress?.calls ?: 0)
        if (!result.isSuccess) error(result.error ?: "图谱抽取失败")
        GraphStore(databaseProvider()).replaceTurn(sessionId, messageId, contentHash, ValidatedGraph.from(result.nodes, result.edges), binding.collectionId)
        return result.progress?.takeIf { it.reason != null }?.let { "已达到抽取预算上限（${it.description}）" }
    }
}

/** 每日会话图谱抽取统计，只存轮数与模型调用次数。 */
class SessionGraphStats(
    private val preferences: SharedPreferences,
    private val today: () -> String = { LocalDate.now().toString() },
) {
    data class Snapshot(val day: String, val turns: Int, val calls: Int)

    private val state = MutableStateFlow(read())
    fun observe(): Flow<Snapshot> = state.asStateFlow()

    @Synchronized
    fun record(calls: Int) {
        val current = read()
        val next = Snapshot(today(), current.turns + 1, current.calls + calls.coerceAtLeast(0))
        preferences.edit().putString(KEY_DAY, next.day).putInt(KEY_TURNS, next.turns).putInt(KEY_CALLS, next.calls).apply()
        state.value = next
    }

    private fun read(): Snapshot {
        val day = today()
        if (preferences.getString(KEY_DAY, null) != day) return Snapshot(day, 0, 0)
        return Snapshot(day, preferences.getInt(KEY_TURNS, 0), preferences.getInt(KEY_CALLS, 0))
    }

    private companion object {
        const val KEY_DAY = "day"
        const val KEY_TURNS = "turns"
        const val KEY_CALLS = "calls"
    }
}

/** 一轮的图谱抽取状态，键为该轮最后一条助手消息。 */
data class TurnGraphState(val status: Status, val entities: Int = 0, val relations: Int = 0, val error: String? = null) {
    enum class Status { QUEUED, RUNNING, DONE, PARTIAL, FAILED }
}

data class TurnGraphDetails(val entities: List<TurnGraphEntity>, val relations: List<KgTurnRelation>) {
    /** 打开图谱时的定位实体：关系中出现最多者，并列取先出现的；没有关系时退回第一个实体。 */
    val focusEntity: String?
        get() = relations.flatMap { listOf(it.source, it.target) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: entities.firstOrNull()?.name
}
data class TurnGraphEntity(val name: String, val type: String)

/** 会话设置中展示的后台抽取队列；开关关闭时为 null。 */
data class SessionGraphQueueStatus(val pending: Int, val failed: Int, val todayTurns: Int, val todayCalls: Int)

internal fun turnGraphStates(tasks: List<SessionGraphTaskRow>, counts: List<KgTurnCount>): Map<String, TurnGraphState> {
    val byMessage = counts.associateBy { it.messageId }
    val fromTasks = tasks.mapNotNull { row ->
        val count = byMessage[row.messageId]
        val status = when (row.status) {
            "pending", "interrupted" -> TurnGraphState.Status.QUEUED
            "failed" -> TurnGraphState.Status.FAILED
            "partial" -> TurnGraphState.Status.PARTIAL
            "completed" -> return@mapNotNull null
            else -> TurnGraphState.Status.RUNNING
        }
        row.messageId?.let { it to TurnGraphState(status, count?.entities ?: 0, count?.relations ?: 0, row.error) }
    }.toMap()
    val done = counts.filter { it.messageId !in fromTasks && (it.entities > 0 || it.relations > 0) }
        .associate { it.messageId to TurnGraphState(TurnGraphState.Status.DONE, it.entities, it.relations) }
    return done + fromTasks
}

/** 聊天界面读取会话图谱抽取状态与明细、重试一轮。 */
interface SessionGraphPort {
    fun observeTurns(sessionId: String): Flow<Map<String, TurnGraphState>>
    suspend fun turnDetails(assistantMessageId: String): TurnGraphDetails
    suspend fun retry(sessionId: String, assistantMessageId: String): Boolean
    /** 开关关闭时为 null。 */
    fun observeQueue(): Flow<SessionGraphQueueStatus?>
    fun enabled(): Boolean
}

class RoomSessionGraphPort(
    private val databaseProvider: () -> InfinityDatabase,
    private val scheduler: SessionGraphScheduler,
    private val stats: SessionGraphStats,
    private val isEnabled: () -> Boolean,
) : SessionGraphPort {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeTurns(sessionId: String): Flow<Map<String, TurnGraphState>> {
        val database = databaseProvider()
        return combine(database.sessionDao().observeById(sessionId), database.kgEvidenceDao().observeCollectionIds()) { _, _ -> Unit }
            .flatMapLatest {
                val collectionId = KgCollectionRepository(database).readBinding(sessionId).collectionId
                combine(
                    database.vectorizationTaskDao().observeSessionGraphTasks(sessionId),
                    database.kgEvidenceDao().observeTurnCounts(sessionId, collectionId),
                    ::turnGraphStates,
                )
            }.flowOn(Dispatchers.IO)
    }

    override suspend fun turnDetails(assistantMessageId: String): TurnGraphDetails = withContext(Dispatchers.IO) {
        val database = databaseProvider()
        database.withTransaction {
            val message = database.messageDao().getById(assistantMessageId)
                ?: return@withTransaction TurnGraphDetails(emptyList(), emptyList())
            val collectionId = KgCollectionRepository(database).readBinding(message.sessionId).collectionId
            TurnGraphDetails(
                entities = database.kgNodeDao().getForMessage(assistantMessageId, DETAIL_LIMIT, collectionId).map { TurnGraphEntity(it.name, it.type) },
                relations = database.kgEdgeDao().getRelationsForMessage(assistantMessageId, DETAIL_LIMIT, collectionId),
            )
        }
    }

    override suspend fun retry(sessionId: String, assistantMessageId: String): Boolean =
        scheduler.schedule(sessionId, assistantMessageId, userMessageId = null)

    override fun observeQueue(): Flow<SessionGraphQueueStatus?> = combine(
        databaseProvider().vectorizationTaskDao().observeSessionGraphQueue(),
        stats.observe(),
    ) { queue, today ->
        if (!isEnabled()) null else SessionGraphQueueStatus(queue.pending, queue.failed, today.turns, today.calls)
    }.flowOn(Dispatchers.IO)

    override fun enabled(): Boolean = isEnabled()

    private companion object {
        const val DETAIL_LIMIT = 24
    }
}
