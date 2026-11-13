package com.promenar.infinity.data.backup

import com.promenar.infinity.data.security.SecretId

/**
 * 外部存储事务必须把 prepared 状态持久化到 app-private 存储，并可在进程重启后按 txId 操作。
 * prepare/commit/rollback/finalize 都必须幂等。
 */
interface TransactionalBackupPreferenceStore {
    suspend fun snapshot(maxTotalBytes: Long): BackupPreferenceSnapshot
    suspend fun snapshot(maxTotalBytes: Long, includeVoiceprints: Boolean): BackupPreferenceSnapshot = snapshot(maxTotalBytes)
    /** 只读验证 typed/providerIds 与 prepared ledger 容量；失败不得产生持久化写入。 */
    suspend fun preflightRestore(
        txId: String,
        before: BackupPreferenceSnapshot,
        after: BackupPreferenceSnapshot,
    )
    suspend fun prepare(txId: String, before: BackupPreferenceSnapshot, after: BackupPreferenceSnapshot)
    suspend fun commitPrepared(txId: String)
    suspend fun rollbackPrepared(txId: String)
    suspend fun finalizePrepared(txId: String)
}

/**
 * 实现必须只以加密形式持久化 before/after staging；不得把 Secret 明文或其值写入 journal/日志。
 * snapshot 返回调用方拥有的 ByteArray；所有操作必须支持进程重启后按 txId 幂等恢复。
 */
interface TransactionalBackupSecretStore {
    /** 仅枚举标识；实现不解密密钥值。 */
    suspend fun ids(): Set<SecretId>
    suspend fun snapshot(ids: Set<SecretId>, maxTotalBytes: Long): Map<SecretId, ByteArray>
    suspend fun prepare(txId: String, before: Map<SecretId, ByteArray>, after: Map<SecretId, ByteArray>)
    suspend fun commitPrepared(txId: String)
    suspend fun rollbackPrepared(txId: String)
    suspend fun finalizePrepared(txId: String)
}

enum class RestoreCrashPoint {
    JOURNAL_PREPARED,
    FILES_MOVED,
    SKILLS_STAGED,
    SKILLS_OLD_MOVED,
    SKILLS_COMMITTED,
    AVATARS_STAGED,
    AVATARS_OLD_MOVED,
    AVATARS_COMMITTED,
    EXTERNAL_COMMITTED,
    ROOM_COMMITTED,
    JOURNAL_COMMITTED,
    RECEIPT_PERSISTED,
}

fun interface RestoreCrashHook {
    fun hit(point: RestoreCrashPoint)
}

class SimulatedRestoreProcessDeath(val point: RestoreCrashPoint) : Error("模拟进程在 $point 中断")
