package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.domain.keychain.KeychainCategory
import com.promenar.infinity.domain.keychain.KeychainItem
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/** 添加主机表单：主机 ID 规则紧贴字段、错误固定在按钮上方、从钥匙串选密钥。只渲染展示状态，不含秘密。 */
@PreviewTest
@Preview(name = "SSH 添加主机 深色中文 主机ID错误", widthDp = 412, heightDp = 1300, locale = "zh-rCN")
@Composable
fun sshHostEditorAliasErrorPreview() = SshHostEditorPreview(dark = true, errorCode = "SSH_ALIAS_INVALID") {
    alias = "my host"
    hostname = "fnos.example-tailnet.ts.net"
    username = "Promenar"
}

@PreviewTest
@Preview(name = "SSH 添加主机 浅色英文 自动生成ID", widthDp = 412, heightDp = 1300, locale = "en")
@Composable
fun sshHostEditorSuggestionPreview() = SshHostEditorPreview(dark = false, aliasSuggestion = "fnos") {
    hostname = "fnos.example-tailnet.ts.net"
    username = "promenar"
    authType = SshAuthType.PASSWORD
}

/** 密钥认证：从钥匙串选定一把密钥。 */
@PreviewTest
@Preview(name = "SSH 添加主机 深色中文 字号130 已选密钥", widthDp = 360, heightDp = 1900, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sshHostEditorSelectedKeyPreview() = SshHostEditorPreview(dark = true, selectedKey = SampleKey) {
    alias = "fnos"
    hostname = "100.64.0.103"
    username = "promenar"
}

/** 密钥认证未选密钥时保存：选择行标红，错误在按钮上方。 */
@PreviewTest
@Preview(name = "SSH 添加主机 浅色英文 字号130 未选密钥", widthDp = 360, heightDp = 1900, locale = "en", fontScale = 1.3f)
@Composable
fun sshHostEditorKeyRequiredPreview() = SshHostEditorPreview(dark = false, errorCode = "SSH_KEY_REQUIRED") {
    alias = "fnos"
    hostname = "100.64.0.103"
    username = "promenar"
}

private val SampleKey = KeychainItem(
    ref = "kc-ssh",
    category = KeychainCategory.SSH_KEY,
    name = "NAS 部署密钥",
    keyType = "ssh-ed25519",
    fingerprintSha256 = "SHA256:q3n8Zp1XrK0b7c9VwYtE5mLhA2sGdF4jU6iO0pQxRzs",
)

@Composable
private fun SshHostEditorPreview(
    dark: Boolean,
    aliasSuggestion: String? = null,
    selectedKey: KeychainItem? = null,
    errorCode: String? = null,
    setup: SshHostEditorFields.() -> Unit,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
          Column {
            SshHostEditorForm(
                fields = remember { SshHostEditorFields(null).apply(setup) },
                existing = null,
                aliasSuggestion = aliasSuggestion,
                busy = false,
                selectedKey = selectedKey,
                errorCode = errorCode,
                scrollState = rememberScrollState(),
                onAuthTypeChange = {},
                onPickKey = {},
                onCancel = {},
                onSave = {},
            )
          }
        }
    }
}
