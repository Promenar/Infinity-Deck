package com.promenar.infinity.ui.keychain

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.domain.keychain.DeployOptions
import com.promenar.infinity.domain.keychain.DeployStage
import com.promenar.infinity.ui.theme.InfinitySpacing
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
@Preview(name = "钥匙串 SSH 密钥页 深色中文", widthDp = 390, heightDp = 1200, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "钥匙串 SSH 密钥页 浅色英文 字号130", widthDp = 360, heightDp = 1700, locale = "en", fontScale = 1.3f)
@Composable
fun keychainDetailSshKeyPreview() = Frame(dark = isSystemInDarkTheme()) {
    KeychainSshKeyContent(
        KeychainSshKeyState(
            item = SshKey,
            hosts = listOf(HostWeb, HostNas),
            usage = listOf(KeychainUsage(SshKey.ref, KeychainAction.DEPLOY, KEYCHAIN_USAGE_CONTEXT, NOW - 3_600_000)),
        ),
        KeychainSshKeyActions(),
    )
}

@PreviewTest
@Preview(name = "钥匙串 SSH 密钥页 无主机 浅色中文", widthDp = 390, heightDp = 1000, locale = "zh-rCN")
@Composable
fun keychainSshKeyUnusedPreview() = Frame(dark = false) {
    KeychainSshKeyContent(KeychainSshKeyState(item = SshKey.copy(hasPassphrase = false), usage = emptyList()), KeychainSshKeyActions())
}

// ── SSH 密钥面板 ──

@PreviewTest
@Preview(name = "SSH 生成密钥 浅色中文", widthDp = 390, heightDp = 520, locale = "zh-rCN")
@Preview(name = "SSH 生成密钥 深色英文 字号130", widthDp = 360, heightDp = 640, locale = "en", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun sshKeyGeneratePreview() = Sheet(dark = isSystemInDarkTheme()) {
    SshKeyGenerateContent(SshKeyGenerateForm(name = "web 集群"), busy = false, error = null, onChange = {}, onCancel = {}, onGenerate = {})
}

@PreviewTest
@Preview(name = "SSH 导入 选来源 深色中文", widthDp = 390, heightDp = 420, locale = "zh-rCN")
@Composable
fun sshKeyImportPickPreview() = Sheet(dark = true) {
    SshKeyImportContent(SshKeyImportForm(), false, null, {}, {}, {}, {}, {})
}

@PreviewTest
@Preview(name = "SSH 导入 需要口令 浅色中文", widthDp = 390, heightDp = 560, locale = "zh-rCN")
@Composable
fun sshKeyImportPassphrasePreview() = Sheet(dark = false) {
    SshKeyImportContent(
        SshKeyImportForm(SshKeyImportSource.FILE, "id_ed25519_work", "id_ed25519_work"),
        busy = false,
        error = KEYCHAIN_TEXT_ERROR + "需要私钥口令",
        onPickFile = {}, onPickClipboard = {}, onChange = {}, onCancel = {}, onImport = {},
    )
}

@PreviewTest
@Preview(name = "SSH 导出 无口令须设置 深色中文", widthDp = 390, heightDp = 600, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "SSH 导出 无口令须设置 浅色英文 字号130", widthDp = 360, heightDp = 560, locale = "en", fontScale = 1.3f)
@Composable
fun sshKeyExportPreview() = Sheet(dark = isSystemInDarkTheme()) {
    SshKeyExportContent(
        item = SshKey.copy(hasPassphrase = false),
        form = SshKeyExportForm(passphrase = "short", repeat = ""),
        busy = false, error = null, onChange = {}, onCancel = {}, onExport = {},
    )
}

@PreviewTest
@Preview(name = "SSH 部署 选主机 浅色中文", widthDp = 390, heightDp = 900, locale = "zh-rCN")
@Preview(name = "SSH 部署 选主机 深色英文 字号130", widthDp = 360, heightDp = 1200, locale = "en", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun sshDeployFormPreview() = Sheet(dark = isSystemInDarkTheme()) {
    SshDeployContent(
        candidates = listOf(
            SshDeployCandidate(HostDb, SshDeployLoginKind.STORED_PASSWORD, null),
            SshDeployCandidate(HostNew, SshDeployLoginKind.INPUT_PASSWORD, null),
            SshDeployCandidate(HostOld, SshDeployLoginKind.CURRENT_KEY, "旧笔记本密钥"),
        ),
        form = SshDeployForm(selected = setOf(HostDb.id, HostNew.id)),
        run = null, onChange = {}, onCancel = {}, onRun = {}, onFinish = {},
    )
}

@PreviewTest
@Preview(name = "SSH 部署 进度 深色中文", widthDp = 390, heightDp = 600, locale = "zh-rCN")
@Composable
fun sshDeployProgressPreview() = Sheet(dark = true) {
    SshDeployContent(
        candidates = emptyList(),
        form = SshDeployForm(),
        run = SshDeployRun(
            entryRef = SshKey.ref,
            hosts = listOf(
                SshDeployHostState(HostDb.id, HostDb.alias, DeployStage.COMPLETED),
                SshDeployHostState(HostNew.id, HostNew.alias, DeployStage.WRITING_AUTHORIZED_KEYS),
                SshDeployHostState(HostOld.id, HostOld.alias, DeployStage.FAILED, "KEY_LOGIN_FAILED"),
            ),
            options = DeployOptions(),
        ),
        onChange = {}, onCancel = {}, onRun = {}, onFinish = {},
    )
}

@PreviewTest
@Preview(name = "SSH 部署 全部完成 浅色中文", widthDp = 390, heightDp = 480, locale = "zh-rCN")
@Composable
fun sshDeployDonePreview() = Sheet(dark = false) {
    SshDeployContent(
        candidates = emptyList(),
        form = SshDeployForm(),
        run = SshDeployRun(
            SshKey.ref,
            listOf(SshDeployHostState(HostDb.id, HostDb.alias, DeployStage.COMPLETED), SshDeployHostState(HostNew.id, HostNew.alias, DeployStage.COMPLETED)),
            DeployOptions(),
        ),
        onChange = {}, onCancel = {}, onRun = {}, onFinish = {},
    )
}

@PreviewTest
@Preview(name = "SSH 主机选密钥 深色中文", widthDp = 390, heightDp = 520, locale = "zh-rCN")
@Composable
fun sshKeyPickerPreview() = Sheet(dark = true) {
    SshKeyPickerContent(
        keys = listOf(SshKey, SshKey.copy(ref = "kc-old", name = "旧笔记本密钥", keyType = "ssh-rsa", hasPassphrase = false)),
        hosts = listOf(HostWeb, HostNas),
        selected = SshKey.ref,
        onPick = {}, onGenerate = {}, onImport = {},
    )
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
private fun Sheet(dark: Boolean, content: @Composable () -> Unit) = Frame(dark) {
    Column(Modifier.fillMaxWidth().padding(InfinitySpacing.Large)) { content() }
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
    keyType = "ssh-ed25519",
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

private val HostWeb = SshHost(id = "h-web", alias = "web-01", hostname = "47.96.18.204", username = "root", keyEntryId = "kc-ssh")
private val HostNas = SshHost(id = "h-nas", alias = "nas", hostname = "nas.example-tailnet.ts.net", port = 2222, username = "promenar", keyEntryId = "kc-ssh")
private val HostDb = SshHost(id = "h-db", alias = "db-01", hostname = "47.96.18.205", username = "root", authType = SshAuthType.PASSWORD)
private val HostNew = SshHost(id = "h-new", alias = "web-02", hostname = "47.96.18.210", username = "root", authType = SshAuthType.PASSWORD)
private val HostOld = SshHost(id = "h-old", alias = "pi", hostname = "192.168.1.20", username = "pi", keyEntryId = "kc-old")
