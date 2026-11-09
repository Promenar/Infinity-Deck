package com.promenar.infinity.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.promenar.infinity.data.local.db.entity.SshHostEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SshHostDao {
    @Query("SELECT * FROM ssh_hosts ORDER BY alias")
    abstract fun observeHosts(): Flow<List<SshHostEntity>>

    @Query("SELECT * FROM ssh_hosts WHERE alias = :alias")
    abstract suspend fun find(alias: String): SshHostEntity?

    @Query("SELECT * FROM ssh_hosts WHERE id = :id")
    abstract suspend fun findById(id: String): SshHostEntity?

    @Query("SELECT * FROM ssh_hosts WHERE id = :id")
    abstract fun findByIdForCredential(id: String): SshHostEntity?

    @Query("SELECT * FROM ssh_hosts WHERE key_entry_id = :entryId ORDER BY alias")
    abstract suspend fun findByKeyEntryId(entryId: String): List<SshHostEntity>

    @Query("UPDATE ssh_hosts SET key_entry_id = :entryId WHERE id = :id AND key_entry_id IS :expectedEntryId")
    abstract suspend fun assignKey(id: String, entryId: String?, expectedEntryId: String?): Int

    @Insert
    abstract suspend fun insert(host: SshHostEntity)

    @Update
    abstract suspend fun update(host: SshHostEntity): Int

    @Query("DELETE FROM ssh_hosts WHERE id = :id")
    abstract suspend fun deleteById(id: String): Int

    @Query("""UPDATE ssh_hosts SET trusted_key_type = :keyType, trusted_fingerprint = :fingerprint
        WHERE alias = :alias AND id = :expectedId AND hostname = :expectedHostname AND port = :expectedPort
            AND username = :expectedUsername AND auth_type = :expectedAuthType
            AND trusted_fingerprint IS :expectedOld AND trusted_key_type IS :expectedOldKeyType""")
    abstract suspend fun trust(
        alias: String,
        expectedOld: String?,
        expectedOldKeyType: String?,
        keyType: String,
        fingerprint: String,
        expectedId: String,
        expectedHostname: String,
        expectedPort: Int,
        expectedUsername: String,
        expectedAuthType: String,
    ): Int

    @Transaction
    open suspend fun save(host: SshHostEntity) {
        val aliasOwner = find(host.alias)
        require(aliasOwner == null || aliasOwner.id == host.id) { "SSH 别名已存在" }
        val previous = findById(host.id)
        val sameIdentity = previous != null && previous.hostname == host.hostname && previous.port == host.port &&
            previous.username == host.username && previous.authType == host.authType
        val next = host.copy(
            trustedKeyType = if (sameIdentity) previous?.trustedKeyType else null,
            trustedFingerprint = if (sameIdentity) previous?.trustedFingerprint else null,
        )
        if (previous == null) insert(next) else check(update(next) == 1) { "SSH 主机已变化" }
    }

    /** 调用方已校验密钥与信任状态；配置和部署记录在同一 Room 事务中提交。 */
    @Transaction
    open suspend fun saveKeychainSnapshot(
        host: SshHostEntity,
        expected: SshHostEntity?,
        keychain: com.promenar.infinity.data.keychain.KeychainDao? = null,
        usage: com.promenar.infinity.data.keychain.KeychainUsageEntity? = null,
    ) {
        check(findById(host.id) == expected) { "SSH 主机已变化" }
        val owner = find(host.alias)
        require(owner == null || owner.id == host.id) { "SSH 别名已存在" }
        if (expected == null) insert(host) else check(update(host) == 1) { "SSH 主机已变化" }
        if (usage != null) requireNotNull(keychain).appendUsage(usage)
    }
}
