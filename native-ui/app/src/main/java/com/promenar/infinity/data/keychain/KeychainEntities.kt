package com.promenar.infinity.data.keychain

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "keychain_entries", indices = [Index(value = ["fingerprint_sha256"])])
data class KeychainEntryEntity(
    @PrimaryKey val id: String,
    val category: String,
    val name: String,
    val url: String?,
    val username: String?,
    val note: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val origin: String,
    @ColumnInfo(name = "added_by_session_id") val addedBySessionId: String?,
    @ColumnInfo(name = "key_type") val keyType: String?,
    @ColumnInfo(name = "fingerprint_sha256") val fingerprintSha256: String?,
    @ColumnInfo(name = "public_key") val publicKey: String?,
    @ColumnInfo(name = "has_passphrase", defaultValue = "0") val hasPassphrase: Boolean = false,
    @ColumnInfo(name = "agent_enabled", defaultValue = "1") val agentEnabled: Boolean = true,
)

@Entity(tableName = "keychain_usage", indices = [Index(value = ["entry_ref", "at"])])
data class KeychainUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entry_ref") val entryRef: String,
    val action: String,
    val context: String?,
    val at: Long,
)

@Dao
abstract class KeychainDao {
    @Query("SELECT * FROM keychain_entries ORDER BY name, id")
    abstract fun observeEntries(): Flow<List<KeychainEntryEntity>>
    @Query("SELECT * FROM keychain_entries WHERE id = :id")
    abstract suspend fun find(id: String): KeychainEntryEntity?
    @Query("SELECT * FROM keychain_entries WHERE category = 'SSH_KEY' AND fingerprint_sha256 = :fingerprint ORDER BY created_at, id LIMIT 1")
    abstract suspend fun findByFingerprint(fingerprint: String): KeychainEntryEntity?
    @Query("SELECT * FROM keychain_entries WHERE category = 'WEB_LOGIN' AND url = :host ORDER BY name, id")
    abstract suspend fun findWebLogins(host: String): List<KeychainEntryEntity>
    @Insert abstract suspend fun insert(entry: KeychainEntryEntity)
    @Update abstract suspend fun update(entry: KeychainEntryEntity): Int
    @Query("DELETE FROM keychain_entries WHERE id = :id") abstract suspend fun delete(id: String): Int
    @Query("SELECT * FROM keychain_usage WHERE entry_ref = :ref ORDER BY at DESC, id DESC LIMIT 200")
    abstract fun observeUsage(ref: String): Flow<List<KeychainUsageEntity>>
    @Insert abstract suspend fun insertUsage(usage: KeychainUsageEntity)
    @Query("DELETE FROM keychain_usage WHERE entry_ref = :ref AND id NOT IN (SELECT id FROM keychain_usage WHERE entry_ref = :ref ORDER BY at DESC, id DESC LIMIT 200)")
    abstract suspend fun trimUsage(ref: String)
    @Transaction open suspend fun appendUsage(usage: KeychainUsageEntity) {
        insertUsage(usage)
        trimUsage(usage.entryRef)
    }
}
