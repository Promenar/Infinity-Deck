package com.promenar.infinity.data.ssh

import com.promenar.infinity.data.keychain.K2MemoryFixture
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SshCredential
import com.promenar.infinity.domain.keychain.KeychainMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KeychainSshHostTest {
    @Test fun `新主机与编辑主机一次保存引用且仅保留登录密码`() = runBlocking {
        val f = K2MemoryFixture(); val item = f.repository.generateSshKey("选择")
        val host = SshHost(alias = "server", hostname = "server.test", username = "u")
        val saved = f.hosts.saveWithKey(host, item.ref, "fixture password".toByteArray())
        assertEquals(item.ref, saved.keyEntryId)
        val binding = saved.binding()
        try { com.promenar.infinity.data.security.SshCredentialStore(f.secrets).get(saved.id, binding)!!.use {
            assertTrue(it.privateKey.isEmpty()); assertTrue(it.passphrase.isEmpty()); assertTrue(it.password.isNotEmpty())
        } } finally { binding.fill(0) }
        f.hosts.save(saved.copy(keepTerminal = true, keyEntryId = null))
        assertEquals(item.ref, f.hosts.find(host.alias)!!.keyEntryId)
        val edited = f.hosts.saveWithKey(saved.copy(username = "other"), item.ref)
        f.hosts.credential(edited)!!.use { assertEquals("fixture password", it.password.toString(Charsets.UTF_8)) }
    }

    @Test fun `非法分类缺失值与指纹不一致均不保存主机`() = runBlocking {
        val f = K2MemoryFixture(); val key = f.repository.generateSshKey("选择")
        val custom = f.repository.createCustom(KeychainMetadata("非私钥"), "fixture".toByteArray())
        val host = SshHost(alias = "server", hostname = "server.test", username = "u")
        assertTrue(runCatching { f.hosts.saveWithKey(host, custom.ref) }.isFailure)
        val value = f.repository.reveal(key.ref)!!
        f.secrets.remove(SecretCatalog.keychain(key.ref))
        assertTrue(runCatching { f.hosts.saveWithKey(host, key.ref) }.isFailure)
        f.secrets.put(SecretCatalog.keychain(key.ref), value)
        val row = f.keys.find(key.ref)!!; f.keys.update(row.copy(fingerprintSha256 = "SHA256:wrong"))
        assertTrue(runCatching { f.hosts.saveWithKey(host, key.ref) }.isFailure)
        assertNull(f.hosts.find(host.alias)); value.fill(0)
    }

    @Test fun `新增与编辑失败均恢复凭据与配置`() = runBlocking {
        val f = K2MemoryFixture(); val key = f.repository.generateSshKey("选择")
        val host = SshHost(alias = "server", hostname = "server.test", username = "u", authType = SshAuthType.PASSWORD)
        f.hostDao.failInsert = true
        assertTrue(runCatching { f.hosts.saveWithKey(host, key.ref) }.isFailure)
        assertFalse(f.secrets.contains(SecretCatalog.sshCredential(host.id))); assertNull(f.hosts.find(host.alias))
        f.hostDao.failInsert = false
        SshCredential(password = "fixture".toByteArray()).use { f.hosts.save(host, it) }
        val before = f.secrets.get(SecretCatalog.sshCredential(host.id))
        f.hostDao.failUpdate = true
        assertTrue(runCatching { f.hosts.saveWithKey(host, key.ref) }.isFailure)
        assertEquals(host, f.hosts.find(host.alias)); assertArrayEquals(before, f.secrets.get(SecretCatalog.sshCredential(host.id)))
    }
}
