package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.StaticMenuPanel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/** 添加主机表单：主机 ID 规则紧贴字段、错误固定在按钮上方、粘贴私钥入口。只渲染展示状态，不含秘密。 */
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

@PreviewTest
@Preview(name = "SSH 添加主机 深色中文 字号130 已粘贴私钥", widthDp = 360, heightDp = 1900, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sshHostEditorPastedKeyPreview() = SshHostEditorPreview(
    dark = true,
    keySource = SshKeySource.CLIPBOARD,
    errorCode = "INVALID_KEY_PASSPHRASE",
) {
    alias = "fnos"
    hostname = "100.64.0.103"
    username = "promenar"
}

/** 私钥来源：导入文件为主体，粘贴与生成收进箭头段菜单（下方静态浮层示意展开状态）。 */
@PreviewTest
@Preview(name = "SSH 添加主机 浅色英文 字号130 私钥来源菜单", widthDp = 360, heightDp = 1900, locale = "en", fontScale = 1.3f)
@Composable
fun sshHostEditorKeySourceMenuPreview() = SshHostEditorPreview(dark = false, showKeyMenu = true) {
    alias = "fnos"
    hostname = "100.64.0.103"
    username = "promenar"
}

@Composable
private fun SshHostEditorPreview(
    dark: Boolean,
    aliasSuggestion: String? = null,
    keySource: SshKeySource? = null,
    errorCode: String? = null,
    showKeyMenu: Boolean = false,
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
            if (showKeyMenu) {
                StaticMenuPanel(Modifier.align(Alignment.End).padding(top = 12.dp, end = 16.dp)) {
                    SshKeySourceMenuItems(onPasteKey = {}, onGenerate = {})
                }
            }
            SshHostEditorForm(
                fields = remember { SshHostEditorFields(null).apply(setup) },
                existing = null,
                aliasSuggestion = aliasSuggestion,
                busy = false,
                keySource = keySource,
                errorCode = errorCode,
                scrollState = rememberScrollState(),
                onAuthTypeChange = {},
                onImportKey = {},
                onPasteKey = {},
                onGenerate = {},
                onCancel = {},
                onSave = {},
            )
          }
        }
    }
}
