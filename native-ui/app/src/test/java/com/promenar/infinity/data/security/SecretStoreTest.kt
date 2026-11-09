package com.promenar.infinity.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import java.security.MessageDigest
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecretStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `生产 Keystore alias 固定为绑定规格值`() {
        assertThat(AndroidKeystoreSecretStore.KEY_ALIAS).isEqualTo("infinity.secrets.v1")
    }

    @Test
    fun `普通 SharedPreferences 只保存 envelope 不保存明文`() {
        val prefsName = "secret_store_${System.nanoTime()}"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val store = AndroidKeystoreSecretStore(prefs, newKey())
        val plaintext = "only-a-fake-secret"

        store.put(SecretCatalog.tavilyApiKey, plaintext.toByteArray())

        val persisted = prefs.all.values.single() as String
        assertThat(persisted).doesNotContain(plaintext)
        assertThat(persisted.split('|')).hasSize(3)
        val xml = context.dataDir.resolve("shared_prefs/$prefsName.xml")
        assertThat(xml.exists()).isTrue()
        assertThat(xml.readText()).doesNotContain(plaintext)
    }

    @Test
    fun `覆盖读取删除和空字节遵循 SecretStore 接口`() {
        val prefs = context.getSharedPreferences("secret_store_ops_${System.nanoTime()}", Context.MODE_PRIVATE)
        val store: SecretStore = AndroidKeystoreSecretStore(prefs, newKey())
        val id = SecretCatalog.embeddingApiKey

        assertThat(store.get(id)).isNull()
        assertThat(store.contains(id)).isFalse()

        store.put(id, byteArrayOf())
        assertThat(store.contains(id)).isTrue()
        assertThat(store.get(id)).isEqualTo(byteArrayOf())

        store.put(id, "replacement".toByteArray())
        assertThat(store.get(id)).isEqualTo("replacement".toByteArray())

        store.remove(id)
        assertThat(store.contains(id)).isFalse()
        assertThat(store.get(id)).isNull()
    }

    @Test
    fun `动态 providerId 先经 SHA-256 规范化再成为存储键`() {
        val providerId = "Provider/含空格?raw=true"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(providerId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val apiKeyId = SecretCatalog.providerApiKey(providerId)
        val vertexId = SecretCatalog.vertexServiceAccount(providerId)

        assertThat(apiKeyId.value).isEqualTo("provider_api_key:$digest")
        assertThat(vertexId.value).isEqualTo("vertex_service_account:$digest")
        assertThat(apiKeyId.value).doesNotContain(providerId)
        assertThat(vertexId.value).doesNotContain(providerId)
    }

    @Test
    fun `备份目录包含固定密钥和每个 provider 的两类密钥`() {
        val providerIds = listOf("provider-a", "provider-b", "provider-a")

        val result = SecretCatalog.backupEligible(providerIds)

        assertThat(result).containsExactly(
            SecretCatalog.tavilyApiKey,
            SecretCatalog.keenableApiKey,
            SecretCatalog.embeddingApiKey,
            SecretCatalog.webDavAuthRecord,
            SecretCatalog.webDavPassword,
            SecretId("tailnet_auth_key_v1"),
            SecretId("git_credential_index_v1"),
            SecretCatalog.automaticBackupPassword,
            SecretCatalog.providerApiKey("provider-a"),
            SecretCatalog.vertexServiceAccount("provider-a"),
            SecretCatalog.providerApiKey("provider-b"),
            SecretCatalog.vertexServiceAccount("provider-b"),
        )
    }

    private fun newKey() = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }
}
