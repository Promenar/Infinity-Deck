package com.promenar.infinity.ui.keychain

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.git.remote.GitCredential
import com.promenar.infinity.domain.keychain.KeychainAction
import com.promenar.infinity.domain.keychain.KeychainCategory
import com.promenar.infinity.domain.keychain.KeychainItem
import com.promenar.infinity.domain.keychain.KeychainOrigin
import com.promenar.infinity.domain.keychain.KeychainUsage
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

// ── 列表 ──

@PreviewTest
@Preview(name = "钥匙串 列表 浅色中文", widthDp = 390, heightDp = 1500, locale = "zh-rCN")
@Preview(name = "钥匙串 列表 浅色英文 字号130", widthDp = 360, heightDp = 2100, locale = "en", fontScale = 1.3f)
@Composable
fun keychainListPreview() = Frame(dark = false) {
    KeychainListContent(KeychainListState(items = SampleItems), KeychainListActions())
}

@PreviewTest
@Preview(name = "钥匙串 Git 筛选 深色中文", widthDp = 390, heightDp = 700, locale = "zh-rCN")
@Composable
fun keychainGitFilterPreview() = Frame(dark = true) {
    KeychainListContent(
        KeychainListState(items = SampleItems, filter = KeychainCategory.GIT, githubLoginAvailable = true),
        KeychainListActions(),
    )
}

@PreviewTest
@Preview(name = "钥匙串 搜索无结果 浅色中文", widthDp = 390, heightDp = 600, locale = "zh-rCN")
@Composable
fun keychainEmptySearchPreview() = Frame(dark = false) {
    KeychainListContent(KeychainListState(items = SampleItems, query = "nas", searchActive = true), KeychainListActions())
}

// ── 详情 ──

@PreviewTest
@Preview(name = "钥匙串 详情 网站登录 遮罩 浅色中文", widthDp = 390, heightDp = 1100, locale = "zh-rCN")
@Composable
fun keychainDetailMaskedPreview() = Frame(dark = false) {
    KeychainDetailContent(
        KeychainDetailState(
            item = Router,
            values = listOf(KeychainValueState(Router.ref, null, null)),
            usage = listOf(
                KeychainUsage(Router.ref, KeychainAction.COPY, KEYCHAIN_USAGE_CONTEXT, NOW - 3_600_000),
                KeychainUsage(Router.ref, KeychainAction.FILL, "浏览器 · 192.168.1.1", NOW - 86_400_000),
            ),
        ),
        KeychainDetailActions(),
    )
}

@PreviewTest
@Preview(name = "钥匙串 详情 已显示 深色英文 字号130", widthDp = 360, heightDp = 1300, locale = "en", fontScale = 1.3f)
@Composable
fun keychainDetailRevealedPreview() = Frame(dark = true) {
    KeychainDetailContent(
        KeychainDetailState(
            item = Router,
            values = listOf(KeychainValueState(Router.ref, null, "k7#Rw2!pQz9mXa4&Lt8e")),
            secondsLeft = 24,
            usage = emptyList(),
        ),
        KeychainDetailActions(),
    )
}

@PreviewTest
@Preview(name = "钥匙串 详情 Git 授权失效 浅色中文", widthDp = 390, heightDp = 1000, locale = "zh-rCN")
@Composable
fun keychainDetailGitRejectedPreview() = Frame(dark = false) {
    KeychainDetailContent(
        KeychainDetailState(
            item = GitRejected,
            values = listOf(KeychainValueState(GitRejected.ref, null, null)),
            usage = emptyList(),
        ),
        KeychainDetailActions(),
    )
}

@PreviewTest
@Preview(name = "钥匙串 详情 SSH 密钥 深色中文", widthDp = 390, heightDp = 1000, locale = "zh-rCN")
@Composable
fun keychainDetailSshKeyPreview() = Frame(dark = true) {
    KeychainDetailContent(KeychainDetailState(item = SshKey, usage = emptyList()), KeychainDetailActions())
}

@PreviewTest
@Preview(name = "钥匙串 详情 提供商 两个值 浅色英文", widthDp = 390, heightDp = 1100, locale = "en")
@Composable
fun keychainDetailProviderPreview() = Frame(dark = false) {
    KeychainDetailContent(
        KeychainDetailState(
            item = Provider,
            values = listOf(
                KeychainValueState(Provider.secretRefs[0], "API Key", null),
                KeychainValueState(Provider.secretRefs[1], "Vertex service account", null),
            ),
            usage = emptyList(),
        ),
        KeychainDetailActions(),
    )
}

// ── 编辑 ──

@PreviewTest
@Preview(name = "钥匙串 新建 浅色中文", widthDp = 390, heightDp = 800, locale = "zh-rCN")
@Preview(name = "钥匙串 新建 浅色英文 字号130", widthDp = 360, heightDp = 1100, locale = "en", fontScale = 1.3f)
@Composable
fun keychainEditorNewPreview() = Frame(dark = false) {
    KeychainEditorContent(
        KeychainEditorState(editing = false, form = KeychainEditorForm(name = "路由器管理密码", value = "k7#Rw2!pQz9mXa4&Lt8e"), valueVisible = true),
        KeychainEditorActions(),
    )
}

@PreviewTest
@Preview(name = "钥匙串 编辑 网址无效 深色中文", widthDp = 390, heightDp = 800, locale = "zh-rCN")
@Composable
fun keychainEditorInvalidUrlPreview() = Frame(dark = true) {
    KeychainEditorContent(
        KeychainEditorState(
            editing = true,
            form = KeychainEditorForm(name = "家里路由器", url = "http://", username = "admin", note = "客厅那台"),
            urlError = true,
        ),
        KeychainEditorActions(),
    )
}

@Composable
private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) { content() }
    }
}

private const val NOW = 1_794_000_000_000L

private val Router = KeychainItem(
    ref = "kc-router",
    category = KeychainCategory.WEB_LOGIN,
    name = "家里路由器",
    url = "http://192.168.1.1",
    username = "admin",
    note = "客厅那台",
    createdAt = NOW - 30L * 86_400_000,
    updatedAt = NOW - 2L * 86_400_000,
    origin = KeychainOrigin.MANUAL,
)

private val GitRejected = KeychainItem(
    ref = "git:github.com",
    category = KeychainCategory.GIT,
    name = "github.com",
    username = "promenar",
    updatedAt = NOW - 5L * 86_400_000,
    gitSource = GitCredential.SOURCE_GITHUB_LOGIN,
    gitRejectedAt = NOW - 86_400_000,
)

private val SshKey = KeychainItem(
    ref = "kc-ssh",
    category = KeychainCategory.SSH_KEY,
    name = "NAS 部署密钥",
    updatedAt = NOW - 7L * 86_400_000,
    origin = KeychainOrigin.LOCAL_GENERATED,
    keyType = "ED25519",
    fingerprintSha256 = "SHA256:q3n8Zp1XrK0b7c9VwYtE5mLhA2sGdF4jU6iO0pQxRzs",
    publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHq3n8Zp1XrK0b7c9VwYtE5mLhA2sGdF4jU6iO0pQxRz infinity",
    hasPassphrase = true,
)

private val Provider = KeychainItem(
    ref = "secret:provider_api_key:abc",
    category = KeychainCategory.PROVIDER,
    name = "Google Gemini",
    secretRefs = listOf("secret:provider_api_key:abc", "secret:vertex_service_account:abc"),
)

private val SampleItems = listOf(
    KeychainItem("kc-wifi", KeychainCategory.CUSTOM, "工作室 Wi-Fi", note = "5G 频段", updatedAt = NOW - 86_400_000),
    Router,
    SshKey,
    GitRejected,
    KeychainItem("git:gitea.lan", KeychainCategory.GIT, "gitea.lan", username = "deploy", updatedAt = NOW - 9L * 86_400_000),
    KeychainItem("ssh-password:h1", KeychainCategory.SSH_PASSWORD, "nas", username = "root"),
    Provider,
    KeychainItem("secret:tavily_api_key", KeychainCategory.SERVICE, "Tavily"),
    KeychainItem("secret:automatic_backup_password", KeychainCategory.SERVICE, "自动备份口令"),
)
