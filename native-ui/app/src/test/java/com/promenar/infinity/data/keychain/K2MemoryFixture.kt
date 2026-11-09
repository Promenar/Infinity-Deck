package com.promenar.infinity.data.keychain

import com.promenar.infinity.data.git.remote.GitCredential
import com.promenar.infinity.data.git.remote.GitCredentialStore
import com.promenar.infinity.data.local.db.dao.SshHostDao
import com.promenar.infinity.data.local.db.entity.SshHostEntity
import com.promenar.infinity.data.security.*
import com.promenar.infinity.data.ssh.RoomSshHostStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class K2MemoryFixture {
    val keys = K2KeyDao()
    val hostDao = K2HostDao()
    val secrets = K2Secrets()
    val hosts = RoomSshHostStore(hostDao, secrets, keys)
    val repository = DefaultKeychainRepository(keys, hostDao, hosts, secrets, object : GitCredentialStore {
        override val credentials = MutableStateFlow<List<GitCredential>>(emptyList())
        override fun save(host: String, username: String, token: ByteArray) = Unit
        override fun delete(host: String) = Unit
        override fun token(host: String): ByteArray? = null
    }, flowOf(emptyList()))
}

internal class K2KeyDao : KeychainDao() {
    val rows = MutableStateFlow<List<KeychainEntryEntity>>(emptyList())
    val history = MutableStateFlow<List<KeychainUsageEntity>>(emptyList())
    var failUpdate = false
    var failUsage = false
    override fun observeEntries() = rows
    override suspend fun find(id: String) = rows.value.firstOrNull { it.id == id }
    override suspend fun findByFingerprint(fingerprint: String) = rows.value.firstOrNull { it.fingerprintSha256 == fingerprint }
    override suspend fun findWebLogins(host: String) = rows.value.filter { it.url == host && it.category == "WEB_LOGIN" }
    override suspend fun insert(entry: KeychainEntryEntity) { check(find(entry.id) == null); rows.value += entry }
    override suspend fun update(entry: KeychainEntryEntity): Int {
        if (failUpdate) error("模拟目录写入失败")
        if (find(entry.id) == null) return 0
        rows.value = rows.value.map { if (it.id == entry.id) entry else it }; return 1
    }
    override suspend fun delete(id: String): Int { val before = rows.value.size; rows.value = rows.value.filterNot { it.id == id }; return before - rows.value.size }
    override fun observeUsage(ref: String) = history.map { all -> all.filter { it.entryRef == ref } }
    override suspend fun insertUsage(usage: KeychainUsageEntity) { if (failUsage) error("模拟使用记录失败"); history.value += usage }
    override suspend fun trimUsage(ref: String) = Unit
}

internal class K2HostDao : SshHostDao() {
    val rows = MutableStateFlow<List<SshHostEntity>>(emptyList())
    var failUpdate = false
    var failInsert = false
    override fun observeHosts() = rows
    override suspend fun find(alias: String) = rows.value.firstOrNull { it.alias == alias }
    override suspend fun findById(id: String) = findByIdForCredential(id)
    override fun findByIdForCredential(id: String) = rows.value.firstOrNull { it.id == id }
    override suspend fun findByKeyEntryId(entryId: String) = rows.value.filter { it.keyEntryId == entryId }
    override suspend fun assignKey(id: String, entryId: String?, expectedEntryId: String?): Int {
        val row = findById(id) ?: return 0
        if (row.keyEntryId != expectedEntryId) return 0
        return update(row.copy(keyEntryId = entryId))
    }
    override suspend fun insert(host: SshHostEntity) { if (failInsert) error("模拟新增失败"); rows.value += host }
    override suspend fun update(host: SshHostEntity): Int {
        if (failUpdate) error("模拟主机写入失败")
        if (findById(host.id) == null) return 0
        rows.value = rows.value.map { if (it.id == host.id) host else it }; return 1
    }
    override suspend fun deleteById(id: String): Int { rows.value = rows.value.filterNot { it.id == id }; return 1 }
    override suspend fun saveKeychainSnapshot(host: SshHostEntity, expected: SshHostEntity?, keychain: KeychainDao?, usage: KeychainUsageEntity?) {
        val before = rows.value
        try { super.saveKeychainSnapshot(host, expected, keychain, usage) }
        catch (failure: Throwable) { rows.value = before; throw failure }
    }
    override suspend fun trust(alias: String, expectedOld: String?, expectedOldKeyType: String?, keyType: String, fingerprint: String,
        expectedId: String, expectedHostname: String, expectedPort: Int, expectedUsername: String, expectedAuthType: String): Int {
        val row = find(alias) ?: return 0
        if (row.id != expectedId || row.hostname != expectedHostname || row.port != expectedPort || row.username != expectedUsername ||
            row.authType != expectedAuthType || row.trustedFingerprint != expectedOld || row.trustedKeyType != expectedOldKeyType) return 0
        return update(row.copy(trustedKeyType = keyType, trustedFingerprint = fingerprint))
    }
}

internal class K2Secrets : SecretStore {
    private val values = linkedMapOf<SecretId, ByteArray>()
    private val revision = MutableStateFlow(0L)
    override val changes = revision
    var failNextWrite: SecretId? = null
    var onPut: (SecretId, ByteArray) -> Unit = { _, _ -> }
    override fun put(id: SecretId, value: ByteArray) {
        onPut(id, value)
        if (failNextWrite == id) { failNextWrite = null; error("模拟安全存储写入失败") }
        values.put(id, value.copyOf())?.fill(0); revision.value++
    }
    override fun get(id: SecretId) = values[id]?.copyOf()
    override fun contains(id: SecretId) = id in values
    override fun remove(id: SecretId) {
        if (failNextWrite == id) { failNextWrite = null; error("模拟安全存储删除失败") }
        values.remove(id)?.fill(0); revision.value++
    }
    override fun ids() = values.keys.toSet()
}
