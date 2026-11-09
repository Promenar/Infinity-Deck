package com.promenar.infinity.ui.keychain

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.keychain.ImportSshKeyResult
import com.promenar.infinity.domain.keychain.KeychainAction
import com.promenar.infinity.domain.keychain.KeychainCategory
import com.promenar.infinity.domain.keychain.KeychainDeleteResult
import com.promenar.infinity.domain.keychain.KeychainItem
import com.promenar.infinity.domain.keychain.KeychainMetadata
import com.promenar.infinity.domain.keychain.KeychainOrigin
import com.promenar.infinity.domain.keychain.KeychainRepository
import com.promenar.infinity.domain.keychain.KeychainUsage
import com.promenar.infinity.domain.keychain.SshKeyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeychainViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = FakeKeychainRepository()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `新建无网址为自定义并清零输入`() = runTest(dispatcher) {
        val model = KeychainViewModel(repository)
        val value = "s3cret-值".toCharArray()
        val result = model.save(null, " 保险柜 ", "", "", " 备注 ", value)

        assertThat(result).isInstanceOf(KeychainSaveResult.Saved::class.java)
        assertThat(value.all { it == '\u0000' }).isTrue()
        val item = repository.items.value.single()
        assertThat(item.category).isEqualTo(KeychainCategory.CUSTOM)
        assertThat(item.name).isEqualTo("保险柜")
        assertThat(item.note).isEqualTo("备注")
        assertThat(item.url).isNull()
        assertThat(String(repository.values.getValue(item.ref), Charsets.UTF_8)).isEqualTo("s3cret-值")
    }

    @Test fun `填了网址为网站登录且网址无效时不保存`() = runTest(dispatcher) {
        val model = KeychainViewModel(repository)
        val invalid = model.save(null, "路由器", "http://", "admin", "", "pw".toCharArray())
        assertThat(invalid).isEqualTo(KeychainSaveResult.Failed(KeychainSaveError.INVALID_URL))
        assertThat(repository.items.value).isEmpty()

        model.save(null, "路由器", "192.168.1.1", "admin", "", "pw".toCharArray())
        val item = repository.items.value.single()
        assertThat(item.category).isEqualTo(KeychainCategory.WEB_LOGIN)
        assertThat(item.username).isEqualTo("admin")
    }

    @Test fun `编辑同类别留空值时保留原值`() = runTest(dispatcher) {
        val model = KeychainViewModel(repository)
        val saved = model.save(null, "保险柜", "", "", "", "old".toCharArray()) as KeychainSaveResult.Saved
        val item = repository.items.value.single()

        val result = model.save(item, "保险柜 2", "", "user", "", null)
        assertThat(result).isEqualTo(KeychainSaveResult.Saved(saved.ref))
        assertThat(repository.items.value.single().name).isEqualTo("保险柜 2")
        assertThat(String(repository.values.getValue(saved.ref), Charsets.UTF_8)).isEqualTo("old")
    }

    @Test fun `编辑加上网址时以网站登录重建并删除旧条目`() = runTest(dispatcher) {
        val model = KeychainViewModel(repository)
        val old = model.save(null, "路由器", "", "", "", "pw".toCharArray()) as KeychainSaveResult.Saved
        val item = repository.items.value.single()

        val moved = model.save(item, "路由器", "192.168.1.1", "", "", null) as KeychainSaveResult.Saved
        assertThat(moved.ref).isNotEqualTo(old.ref)
        val current = repository.items.value.single()
        assertThat(current.category).isEqualTo(KeychainCategory.WEB_LOGIN)
        assertThat(String(repository.values.getValue(current.ref), Charsets.UTF_8)).isEqualTo("pw")
        assertThat(repository.values).doesNotContainKey(old.ref)
    }

    @Test fun `读取值记录使用并返回字符`() = runTest(dispatcher) {
        val model = KeychainViewModel(repository)
        val saved = model.save(null, "保险柜", "", "", "", "值".toCharArray()) as KeychainSaveResult.Saved

        val chars = model.readValue(saved.ref, saved.ref, KeychainAction.REVEAL)
        assertThat(String(chars!!)).isEqualTo("值")
        model.recordUsage(saved.ref, KeychainAction.COPY)
        assertThat(repository.usages.map { it.action }).containsExactly(KeychainAction.REVEAL, KeychainAction.COPY).inOrder()
        assertThat(repository.usages.map { it.context }.toSet()).containsExactly(KEYCHAIN_USAGE_CONTEXT)
        assertThat(model.readValue(saved.ref, "missing", KeychainAction.REVEAL)).isNull()
    }

    @Test fun `仓库写入失败时返回失败`() = runTest(dispatcher) {
        repository.failWrites = true
        val model = KeychainViewModel(repository)
        assertThat(model.save(null, "保险柜", "", "", "", "v".toCharArray()))
            .isEqualTo(KeychainSaveResult.Failed(KeychainSaveError.FAILED))
        assertThat(model.delete("kc-x")).isNull()
    }
}

private class FakeKeychainRepository : KeychainRepository {
    val items = MutableStateFlow<List<KeychainItem>>(emptyList())
    val values = linkedMapOf<String, ByteArray>()
    val usages = mutableListOf<KeychainUsage>()
    var failWrites = false
    private var next = 0

    override fun entries(): Flow<List<KeychainItem>> = items
    override suspend fun reveal(ref: String): ByteArray? = values[ref]?.copyOf()
    override suspend fun recordUsage(entryRef: String, action: KeychainAction, context: String?, at: Long) {
        usages += KeychainUsage(entryRef, action, context, at)
    }
    override fun usage(entryRef: String): Flow<List<KeychainUsage>> = items.map { usages.filter { it.entryRef == entryRef } }

    private fun create(category: KeychainCategory, metadata: KeychainMetadata, value: ByteArray): KeychainItem {
        if (failWrites) error("write failed")
        val item = KeychainItem("kc-${next++}", category, metadata.name, metadata.url, metadata.username, metadata.note)
        values[item.ref] = value.copyOf()
        items.value = items.value + item
        return item
    }
    override suspend fun createCustom(metadata: KeychainMetadata, value: ByteArray, origin: KeychainOrigin, sessionId: String?) =
        create(KeychainCategory.CUSTOM, metadata, value)
    override suspend fun createWebLogin(metadata: KeychainMetadata, value: ByteArray, origin: KeychainOrigin, sessionId: String?) =
        create(KeychainCategory.WEB_LOGIN, metadata, value)
    override suspend fun createSshKey(name: String, privateKeyPem: ByteArray, passphrase: ByteArray?, origin: KeychainOrigin, sessionId: String?) =
        error("unused")
    override suspend fun update(ref: String, metadata: KeychainMetadata, value: ByteArray?, passphrase: ByteArray?): KeychainItem {
        if (failWrites) error("write failed")
        val updated = items.value.first { it.ref == ref }
            .copy(name = metadata.name, url = metadata.url, username = metadata.username, note = metadata.note)
        value?.let { values[ref] = it.copyOf() }
        items.value = items.value.map { if (it.ref == ref) updated else it }
        return updated
    }
    override suspend fun delete(ref: String): KeychainDeleteResult {
        if (failWrites) error("write failed")
        values.remove(ref)
        val before = items.value
        items.value = before.filterNot { it.ref == ref }
        return if (before.size == items.value.size) KeychainDeleteResult.NotFound else KeychainDeleteResult.Deleted
    }
    override suspend fun matchWebLogin(host: String, port: Int?): List<KeychainItem> = emptyList()
    override suspend fun assignKey(hostId: String, entryId: String) = Unit
    override suspend fun saveGit(host: String, username: String, token: ByteArray) = Unit
    override suspend fun saveGitHubLogin(username: String, token: ByteArray) = Unit
    override suspend fun deleteGit(host: String) = Unit
    override suspend fun generateSshKey(name: String, type: SshKeyType, passphrase: ByteArray?): KeychainItem = error("unused")
    override suspend fun importSshKey(name: String, privateKey: ByteArray, passphrase: ByteArray?, origin: KeychainOrigin): ImportSshKeyResult =
        error("unused")
    override suspend fun exportOpenSsh(entryId: String, exportPassphrase: ByteArray?): ByteArray = error("unused")
    override suspend fun changePassphrase(entryId: String, newPassphrase: ByteArray?): KeychainItem = error("unused")
}
