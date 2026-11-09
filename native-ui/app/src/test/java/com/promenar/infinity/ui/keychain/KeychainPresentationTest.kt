package com.promenar.infinity.ui.keychain

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.keychain.KeychainCategory
import com.promenar.infinity.domain.keychain.KeychainItem
import java.security.SecureRandom
import org.junit.Test

class KeychainPresentationTest {
    private val items = listOf(
        KeychainItem("secret:tavily_api_key", KeychainCategory.SERVICE, "Tavily"),
        KeychainItem("kc-1", KeychainCategory.WEB_LOGIN, "家里路由器", url = "http://192.168.1.1", username = "Admin"),
        KeychainItem("kc-2", KeychainCategory.CUSTOM, "工作室 Wi-Fi", note = "5G 频段"),
        KeychainItem("git:github.com", KeychainCategory.GIT, "github.com", username = "octocat"),
        KeychainItem("kc-3", KeychainCategory.CUSTOM, "保险柜"),
    )

    @Test fun `分组按固定类别顺序且保留组内原顺序`() {
        val groups = groupKeychainItems(items, filter = null, query = "")
        assertThat(groups.map { it.category })
            .containsExactly(KeychainCategory.CUSTOM, KeychainCategory.WEB_LOGIN, KeychainCategory.GIT, KeychainCategory.SERVICE)
            .inOrder()
        assertThat(groups.first().items.map { it.ref }).containsExactly("kc-2", "kc-3").inOrder()
    }

    @Test fun `关键字不区分大小写匹配名称用户名网址与备注`() {
        assertThat(groupKeychainItems(items, null, "admin").flatMap { it.items }.map { it.ref }).containsExactly("kc-1")
        assertThat(groupKeychainItems(items, null, "192.168").flatMap { it.items }.map { it.ref }).containsExactly("kc-1")
        assertThat(groupKeychainItems(items, null, " 5g ").flatMap { it.items }.map { it.ref }).containsExactly("kc-2")
        assertThat(groupKeychainItems(items, null, "nas")).isEmpty()
    }

    @Test fun `类别筛选与关键字同时生效`() {
        val groups = groupKeychainItems(items, KeychainCategory.GIT, "octo")
        assertThat(groups.single().items.single().ref).isEqualTo("git:github.com")
        assertThat(groupKeychainItems(items, KeychainCategory.CUSTOM, "octo")).isEmpty()
        assertThat(keychainCounts(items)[KeychainCategory.CUSTOM]).isEqualTo(2)
    }

    @Test fun `汇总条目映射到原设置页`() {
        fun service(id: String) = KeychainItem("secret:$id", KeychainCategory.SERVICE, id)
        assertThat(service("tavily_api_key").settingsLink()).isEqualTo(KeychainSettingsLink.BUILTIN_TOOLS)
        assertThat(service("keenable_api_key").settingsLink()).isEqualTo(KeychainSettingsLink.BUILTIN_TOOLS)
        assertThat(service("embedding_api_key").settingsLink()).isEqualTo(KeychainSettingsLink.DEFAULT_MODELS)
        assertThat(service("webdav_password").settingsLink()).isEqualTo(KeychainSettingsLink.BACKUP)
        assertThat(service("automatic_backup_password").settingsLink()).isEqualTo(KeychainSettingsLink.BACKUP)
        assertThat(service("tailnet_auth_key_v1").settingsLink()).isEqualTo(KeychainSettingsLink.TAILSCALE)
        assertThat(service("mcp_bearer_v1:abc").settingsLink()).isEqualTo(KeychainSettingsLink.MCP)
        assertThat(service("unknown").settingsLink()).isNull()
        assertThat(KeychainItem("ssh-password:h", KeychainCategory.SSH_PASSWORD, "nas").settingsLink()).isEqualTo(KeychainSettingsLink.SSH_HOSTS)
        assertThat(KeychainItem("secret:provider_api_key:x", KeychainCategory.PROVIDER, "p").settingsLink()).isEqualTo(KeychainSettingsLink.PROVIDERS)
        assertThat(items[1].settingsLink()).isNull()
    }

    @Test fun `条目能力与分量`() {
        val provider = KeychainItem(
            "secret:provider_api_key:x", KeychainCategory.PROVIDER, "p",
            secretRefs = listOf("secret:provider_api_key:x", "secret:vertex_service_account:x"),
        )
        assertThat(provider.valueRefs()).hasSize(2)
        assertThat(provider.valueRefs().map(::isVertexValueRef)).containsExactly(false, true).inOrder()
        assertThat(items[1].valueRefs()).containsExactly("kc-1")
        assertThat(KeychainItem("kc-k", KeychainCategory.SSH_KEY, "k").hasRevealableValue()).isFalse()
        assertThat(items[3].hasRevealableValue()).isTrue()
        assertThat(items[1].editableInKeychain()).isTrue()
        assertThat(items[3].editableInKeychain()).isFalse()
        assertThat(items[3].gitHost()).isEqualTo("github.com")
        assertThat(items[1].gitHost()).isNull()
        assertThat(KeychainItem("secret:automatic_backup_password", KeychainCategory.SERVICE, "a").excludedFromExport()).isTrue()
        assertThat(items[0].excludedFromExport()).isFalse()
    }

    @Test fun `生成的密码含四类字符且不含易混字符`() {
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }
        repeat(200) {
            val password = generateKeychainPassword(random = random)
            assertThat(password.size).isEqualTo(20)
            assertThat(password.any(Char::isUpperCase)).isTrue()
            assertThat(password.any(Char::isLowerCase)).isTrue()
            assertThat(password.any(Char::isDigit)).isTrue()
            assertThat(password.any { !it.isLetterOrDigit() }).isTrue()
            assertThat(password.none { it in "O0Il1" }).isTrue()
        }
        assertThat(generateKeychainPassword(length = 4).size).isEqualTo(4)
    }
}
