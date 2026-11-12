package com.promenar.infinity.data.mediagen.queue

import androidx.room.*
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** channel 与路由标识在入队时冻结；远端续查不能随默认通道设置改变。 */
@Serializable
data class PersistentMediaTask(
    val task: MediaTask,
    val channel: MediaChannel,
    val bindingId: String,
    val providerId: String,
    val modelId: String,
    val updatedAt: Long,
    val pollAttempt: Int = 0,
    val consecutivePollErrors: Int = 0,
    val nextPollAt: Long = 0,
    val remoteSucceeded: Boolean = false,
    val remoteOutputs: List<RemoteOutput> = emptyList(),
    val usage: Map<String, Long> = emptyMap(),
    /** 备份恢复的历史记录只能查看，避免把历史记录重新调度。 */
    val historyOnly: Boolean = false,
)

data class MediaUsageEntry(
    val taskId: String,
    val providerId: String,
    val modelId: String,
    val sourceRef: String,
    val cost: Money?,
    val completedAt: Long,
)

interface MediaTaskStore {
    fun observe(): Flow<List<PersistentMediaTask>>
    suspend fun all(): List<PersistentMediaTask>
    suspend fun get(id: String): PersistentMediaTask?
    /** 状态和费用在同一事务入账，taskId 作为费用去重键。 */
    suspend fun save(record: PersistentMediaTask, entry: MediaUsageEntry? = null)
    suspend fun delete(id: String)
}

@Entity(tableName = "media_generation_tasks", indices = [Index("source_ref"), Index("state")])
data class MediaTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_ref") val sourceRef: String,
    val state: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "record_json") val recordJson: String,
)

@Entity(tableName = "media_generation_usage", indices = [Index(value = ["provider_id", "model_id"]), Index("completed_at")])
data class MediaUsageEntity(
    @PrimaryKey @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "source_ref") val sourceRef: String,
    /** 两列同时为 null 表示未知；小数以字符串保存。 */
    val amount: String?,
    val currency: String?,
    @ColumnInfo(name = "completed_at") val completedAt: Long,
) {
    fun toEntry(): MediaUsageEntry {
        require((amount == null) == (currency == null))
        return MediaUsageEntry(taskId, providerId, modelId, sourceRef,
            amount?.let { Money(it.toBigDecimal(), requireNotNull(currency)) }, completedAt)
    }
}

@Dao
abstract class MediaGenerationDao {
    @Query("SELECT * FROM media_generation_tasks ORDER BY updated_at, id")
    abstract fun observeTasks(): Flow<List<MediaTaskEntity>>
    @Query("SELECT * FROM media_generation_tasks ORDER BY updated_at, id")
    abstract suspend fun tasks(): List<MediaTaskEntity>
    @Query("SELECT * FROM media_generation_tasks WHERE id = :id")
    abstract suspend fun task(id: String): MediaTaskEntity?
    @Upsert abstract suspend fun upsertTask(entity: MediaTaskEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertUsage(entity: MediaUsageEntity)
    @Query("DELETE FROM media_generation_tasks WHERE id = :id") abstract suspend fun deleteTask(id: String)
    @Query("SELECT * FROM media_generation_usage ORDER BY completed_at, task_id")
    abstract fun observeUsage(): Flow<List<MediaUsageEntity>>
    @Query("SELECT * FROM media_generation_usage ORDER BY completed_at, task_id")
    abstract suspend fun usage(): List<MediaUsageEntity>
    @Transaction
    open suspend fun save(task: MediaTaskEntity, usage: MediaUsageEntity?) {
        upsertTask(task)
        usage?.let { insertUsage(it) }
    }
}

object MediaTaskCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    fun encode(record: PersistentMediaTask): String = json.encodeToString(record)
    fun decode(raw: String): PersistentMediaTask = json.decodeFromString(raw)
}

class RoomMediaTaskStore(private val dao: MediaGenerationDao) : MediaTaskStore {
    constructor(database: InfinityDatabase) : this(database.mediaGenerationDao())
    override fun observe() = dao.observeTasks().map { rows -> rows.map { MediaTaskCodec.decode(it.recordJson) } }
    override suspend fun all() = dao.tasks().map { MediaTaskCodec.decode(it.recordJson) }
    override suspend fun get(id: String) = dao.task(id)?.let { MediaTaskCodec.decode(it.recordJson) }
    override suspend fun save(record: PersistentMediaTask, entry: MediaUsageEntry?) = dao.save(
        MediaTaskEntity(record.task.id, record.task.request.sourceRef, record.task.state.name, record.updatedAt, MediaTaskCodec.encode(record)),
        entry?.let { MediaUsageEntity(it.taskId, it.providerId, it.modelId, it.sourceRef, it.cost?.amount?.toPlainString(), it.cost?.currency, it.completedAt) },
    )
    override suspend fun delete(id: String) = dao.deleteTask(id)
}
