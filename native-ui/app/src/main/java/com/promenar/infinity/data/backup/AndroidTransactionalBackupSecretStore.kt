package com.promenar.infinity.data.backup

import android.content.Context
import android.content.SharedPreferences
import com.promenar.infinity.data.security.AndroidKeystoreSecretStore
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** 可跨进程重启补偿的 Secret prepared store；事务元数据从不保存 Secret 值。 */
class AndroidTransactionalBackupSecretStore internal constructor(
    private val liveStore: SecretStore,
    private val stagingStore: SecretStore,
    private val metadataPreferences: SharedPreferences,
) : TransactionalBackupSecretStore {
    constructor(
        context: Context,
        liveStore: SecretStore = AndroidKeystoreSecretStore(context),
    ) : this(
        liveStore = liveStore,
        stagingStore = AndroidKeystoreSecretStore(context, STAGING_PREFERENCES, STAGING_KEY_ALIAS),
        metadataPreferences = context.applicationContext.getSharedPreferences(
            METADATA_PREFERENCES,
            Context.MODE_PRIVATE,
        ),
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override suspend fun ids(): Set<SecretId> = liveStore.ids()

    override suspend fun snapshot(ids: Set<SecretId>, maxTotalBytes: Long): Map<SecretId, ByteArray> {
        require(maxTotalBytes >= 0) { "Secret snapshot 预算无效" }
        val result = linkedMapOf<SecretId, ByteArray>()
        var total = 0L
        try {
            ids.asSequence()
                .filter { it != SecretCatalog.automaticBackupPassword }
                .sortedBy(SecretId::value)
                .forEach { id ->
                    val value = liveStore.get(id) ?: return@forEach
                    if (value.size.toLong() > maxTotalBytes - total) {
                        value.fill(0)
                        throw BackupValidationException("密钥快照超过内存预算")
                    }
                    total += value.size
                    result[id] = value
                }
            return result
        } catch (error: Throwable) {
            result.values.forEach { it.fill(0) }
            throw error
        }
    }

    override suspend fun prepare(
        txId: String,
        before: Map<SecretId, ByteArray>,
        after: Map<SecretId, ByteArray>,
    ) = synchronized(LOCK) {
        requireTxId(txId)
        val safeBefore = before.filterKeys { it != SecretCatalog.automaticBackupPassword }
        val safeAfter = after.filterKeys { it != SecretCatalog.automaticBackupPassword }
        val existing = readRecord(txId)
        if (existing != null && existing.phase != SecretTxPhase.PREPARING) {
            if (existing.phase == SecretTxPhase.PREPARED &&
                existing.beforeIds == safeBefore.keys.map(SecretId::value).sorted() &&
                existing.afterIds == safeAfter.keys.map(SecretId::value).sorted()
            ) {
                verifyPreparedBytes(txId, StageSide.BEFORE, safeBefore)
                verifyPreparedBytes(txId, StageSide.AFTER, safeAfter)
                return@synchronized
            }
            throw BackupValidationException("Secret prepared 事务状态冲突")
        }
        val record = existing ?: SecretTxRecord(
            txId = txId,
            phase = SecretTxPhase.PREPARING,
            beforeIds = safeBefore.keys.map(SecretId::value).sorted(),
            afterIds = safeAfter.keys.map(SecretId::value).sorted(),
        ).also(::writeRecord)
        if (record.beforeIds != safeBefore.keys.map(SecretId::value).sorted() ||
            record.afterIds != safeAfter.keys.map(SecretId::value).sorted()
        ) throw BackupValidationException("Secret prepared 重试内容不一致")
        safeBefore.forEach { (id, value) -> stage(txId, StageSide.BEFORE, id, value) }
        safeAfter.forEach { (id, value) -> stage(txId, StageSide.AFTER, id, value) }
        writeRecord(record.copy(phase = SecretTxPhase.PREPARED, completedIds = emptyList()))
    }

    override suspend fun commitPrepared(txId: String) = synchronized(LOCK) {
        requireTxId(txId)
        val initial = readRecord(txId) ?: throw BackupValidationException("Secret prepared 事务不存在")
        when (initial.phase) {
            SecretTxPhase.FINALIZED_COMMIT, SecretTxPhase.COMMITTED -> return@synchronized
            SecretTxPhase.FINALIZED_ROLLBACK, SecretTxPhase.ROLLED_BACK, SecretTxPhase.ROLLING_BACK ->
                throw BackupValidationException("Secret 事务已回滚，不能提交")
            SecretTxPhase.PREPARING -> throw BackupValidationException("Secret prepared 尚未完成")
            SecretTxPhase.PREPARED, SecretTxPhase.COMMITTING -> Unit
        }
        var record = initial.copy(phase = SecretTxPhase.COMMITTING)
        writeRecord(record)
        val allIds = (record.beforeIds + record.afterIds).distinct().sorted()
        allIds.forEach { rawId ->
            if (rawId in record.completedIds) return@forEach
            val id = SecretId(rawId)
            if (rawId in record.afterIds) {
                val value = stagingStore.get(stagingId(txId, StageSide.AFTER, id))
                    ?: throw BackupValidationException("Secret after staging 缺失")
                try {
                    liveStore.put(id, value)
                } finally {
                    value.fill(0)
                }
            } else {
                liveStore.remove(id)
            }
            record = record.copy(completedIds = (record.completedIds + rawId).distinct().sorted())
            writeRecord(record)
        }
        writeRecord(record.copy(phase = SecretTxPhase.COMMITTED))
    }

    override suspend fun rollbackPrepared(txId: String) = synchronized(LOCK) {
        requireTxId(txId)
        val initial = readRecord(txId) ?: return@synchronized
        when (initial.phase) {
            SecretTxPhase.FINALIZED_ROLLBACK, SecretTxPhase.ROLLED_BACK -> return@synchronized
            SecretTxPhase.FINALIZED_COMMIT -> throw BackupValidationException("Secret 事务已提交并完成，不能回滚")
            SecretTxPhase.PREPARING, SecretTxPhase.PREPARED -> {
                writeRecord(initial.copy(phase = SecretTxPhase.ROLLED_BACK, completedIds = emptyList()))
                return@synchronized
            }
            SecretTxPhase.COMMITTING, SecretTxPhase.COMMITTED, SecretTxPhase.ROLLING_BACK -> Unit
        }
        var record = initial.copy(phase = SecretTxPhase.ROLLING_BACK, completedIds = emptyList())
        writeRecord(record)
        val allIds = (record.beforeIds + record.afterIds).distinct().sorted()
        allIds.forEach { rawId ->
            if (rawId in record.completedIds) return@forEach
            val id = SecretId(rawId)
            if (rawId in record.beforeIds) {
                val value = stagingStore.get(stagingId(txId, StageSide.BEFORE, id))
                    ?: throw BackupValidationException("Secret before staging 缺失")
                try {
                    liveStore.put(id, value)
                } finally {
                    value.fill(0)
                }
            } else {
                liveStore.remove(id)
            }
            record = record.copy(completedIds = (record.completedIds + rawId).distinct().sorted())
            writeRecord(record)
        }
        writeRecord(record.copy(phase = SecretTxPhase.ROLLED_BACK))
    }

    override suspend fun finalizePrepared(txId: String) = synchronized(LOCK) {
        requireTxId(txId)
        val record = readRecord(txId) ?: return@synchronized
        if (record.phase == SecretTxPhase.FINALIZED_COMMIT || record.phase == SecretTxPhase.FINALIZED_ROLLBACK) {
            return@synchronized
        }
        val finalPhase = when (record.phase) {
            SecretTxPhase.COMMITTED -> SecretTxPhase.FINALIZED_COMMIT
            SecretTxPhase.ROLLED_BACK -> SecretTxPhase.FINALIZED_ROLLBACK
            else -> throw BackupValidationException("Secret 事务尚未到达可 finalize 状态")
        }
        (record.beforeIds + record.afterIds).distinct().forEach { rawId ->
            val id = SecretId(rawId)
            stagingStore.remove(stagingId(txId, StageSide.BEFORE, id))
            stagingStore.remove(stagingId(txId, StageSide.AFTER, id))
        }
        writeRecord(
            SecretTxRecord(
                txId = txId,
                phase = finalPhase,
                beforeIds = emptyList(),
                afterIds = emptyList(),
            )
        )
    }

    private fun stage(txId: String, side: StageSide, id: SecretId, value: ByteArray) {
        val owned = value.copyOf()
        try {
            stagingStore.put(stagingId(txId, side, id), owned)
        } finally {
            owned.fill(0)
        }
    }

    private fun verifyPreparedBytes(
        txId: String,
        side: StageSide,
        expected: Map<SecretId, ByteArray>,
    ) {
        expected.forEach { (id, expectedBytes) ->
            val staged = stagingStore.get(stagingId(txId, side, id))
                ?: throw BackupValidationException("Secret prepared staging 缺失")
            try {
                if (!MessageDigest.isEqual(staged, expectedBytes)) {
                    throw BackupValidationException("Secret prepared 重试内容不一致")
                }
            } finally {
                staged.fill(0)
            }
        }
    }

    private fun readRecord(txId: String): SecretTxRecord? = metadataPreferences
        .getString(recordKey(txId), null)
        ?.let { encoded ->
            try {
                json.decodeFromString<SecretTxRecord>(encoded).also {
                    if (it.txId != txId) throw BackupValidationException("Secret 事务元数据身份不一致")
                }
            } catch (error: BackupValidationException) {
                throw error
            } catch (error: Exception) {
                throw BackupValidationException("Secret 事务元数据损坏", error)
            }
        }

    private fun writeRecord(record: SecretTxRecord) {
        check(metadataPreferences.edit().putString(recordKey(record.txId), json.encodeToString(record)).commit()) {
            "Secret 事务元数据写入失败"
        }
    }

    private fun recordKey(txId: String): String = "tx:$txId"

    private fun stagingId(txId: String, side: StageSide, id: SecretId): SecretId {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(id.value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return SecretId("backup:$txId:${side.code}:$digest")
    }

    private fun requireTxId(txId: String) {
        if (!TX_ID.matches(txId)) throw BackupValidationException("Secret 事务 ID 无效")
    }

    private enum class StageSide(val code: String) { BEFORE("b"), AFTER("a") }

    @Serializable
    private enum class SecretTxPhase {
        PREPARING,
        PREPARED,
        COMMITTING,
        COMMITTED,
        ROLLING_BACK,
        ROLLED_BACK,
        FINALIZED_COMMIT,
        FINALIZED_ROLLBACK,
    }

    @Serializable
    private data class SecretTxRecord(
        val txId: String,
        val phase: SecretTxPhase,
        val beforeIds: List<String>,
        val afterIds: List<String>,
        val completedIds: List<String> = emptyList(),
    )

    private companion object {
        val LOCK = Any()
        val TX_ID = Regex("[A-Za-z0-9-]{1,72}")
        const val METADATA_PREFERENCES = "infinity_backup_secret_transactions_v1"
        const val STAGING_PREFERENCES = "infinity_backup_secret_staging_v1"
        const val STAGING_KEY_ALIAS = "infinity.backup.secret.staging.v1"
    }
}
