package com.promenar.infinity.data.tailnet

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.backup.BackupPreferencePolicy
import com.promenar.infinity.data.security.SecretCatalog
import org.junit.Test

class TailnetSettingsStoreTest {
    private val preferences = MemoryPreferences()
    private val secrets = MemorySecretStore()
    private val store = TailnetSettingsStore(preferences, secrets)

    @Test
    fun `设置持久化并重新读取`() {
        store.setEnabled(true)
        store.setEphemeral(true)
        assertThat(store.setControlUrl(" https://hs.example.org:8443/ ")).isTrue()
        val reloaded = TailnetSettingsStore(preferences, secrets).current
        assertThat(reloaded.enabled).isTrue()
        assertThat(reloaded.ephemeral).isTrue()
        assertThat(reloaded.controlUrl).isEqualTo("https://hs.example.org:8443")
    }

    @Test
    fun `auth key 只写入秘密存储，不进偏好文件与设置快照`() {
        assertThat(store.setAuthKey("  tskey-auth-SECRETVALUE  ".toCharArray())).isTrue()
        assertThat(String(secrets.get(TailnetSettingsStore.AUTH_KEY_ID)!!)).isEqualTo("tskey-auth-SECRETVALUE")
        assertThat(preferences.values.values.joinToString()).doesNotContain("SECRETVALUE")
        assertThat(store.current.toString()).doesNotContain("SECRETVALUE")
        assertThat(store.current.hasAuthKey).isTrue()
        val revision = store.current.authKeyRevision
        assertThat(String(store.readAuthKey()!!)).isEqualTo("tskey-auth-SECRETVALUE")
        assertThat(store.setAuthKey("with space".toCharArray())).isFalse()
        assertThat(store.setAuthKey("".toCharArray())).isFalse()
        assertThat(store.setAuthKey("x".repeat(TailnetSettingsPolicy.MAX_AUTH_KEY_LENGTH + 1).toCharArray())).isFalse()
        assertThat(store.current.authKeyRevision).isEqualTo(revision)
        store.clearAuthKey()
        assertThat(store.current.hasAuthKey).isFalse()
        assertThat(store.current.authKeyRevision).isEqualTo(revision + 1)
        assertThat(store.readAuthKey()).isNull()
    }

    @Test
    fun `设置与 auth key 都不属于备份范围`() {
        for (key in listOf(
            TailnetSettingsStore.KEY_ENABLED,
            TailnetSettingsStore.KEY_CONTROL_URL,
            TailnetSettingsStore.KEY_EPHEMERAL,
        )) {
            assertThat(BackupPreferencePolicy.isAllowed(TailnetSettingsStore.PREFERENCES_NAME, key)).isFalse()
            assertThat(BackupPreferencePolicy.isAllowed("infinity_settings", key)).isFalse()
        }
        assertThat(SecretCatalog.backupEligible(listOf("tailnet", "openai"))).doesNotContain(TailnetSettingsStore.AUTH_KEY_ID)
    }

    @Test
    fun `控制服务器地址校验`() {
        assertThat(TailnetSettingsPolicy.normalizeControlUrl("")).isEqualTo("")
        assertThat(TailnetSettingsPolicy.normalizeControlUrl("https://controlplane.tailscale.com")).isEqualTo("https://controlplane.tailscale.com")
        assertThat(TailnetSettingsPolicy.normalizeControlUrl("http://100.64.0.1:8080")).isEqualTo("http://100.64.0.1:8080")
        for (invalid in listOf("ftp://hs.example.org", "https://u:p@hs.example.org", "https://hs.example.org/?k=v", "https://hs.example.org/#x", "hs.example.org", "https://", "https://a b")) {
            assertThat(TailnetSettingsPolicy.normalizeControlUrl(invalid)).isNull()
        }
        assertThat(store.setControlUrl("ftp://hs.example.org")).isFalse()
        assertThat(store.current.controlUrl).isEmpty()
    }

    @Test fun `启动清除设备白名单旧键并保留连接设置`() {
        preferences.edit().putString("tailnet_allowed_peers", "nas").putBoolean(TailnetSettingsStore.KEY_ENABLED, true).apply()
        val reloaded = TailnetSettingsStore(preferences, secrets)
        assertThat(preferences.contains("tailnet_allowed_peers")).isFalse()
        assertThat(reloaded.current.enabled).isTrue()
    }
}
