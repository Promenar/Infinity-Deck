package com.promenar.infinity.ui.keychain

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.domain.keychain.KeychainAction
import com.promenar.infinity.domain.keychain.KeychainCategory
import com.promenar.infinity.domain.keychain.KeychainItem
import com.promenar.infinity.domain.keychain.KeychainOrigin
import com.promenar.infinity.data.git.remote.GitCredential
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.settings.GitCredentialEditor
import com.promenar.infinity.ui.settings.GitCredentialOperation
import com.promenar.infinity.ui.settings.GitCredentialUiState
import com.promenar.infinity.ui.settings.GitHubLoginSheet
import com.promenar.infinity.ui.settings.GitHubLoginSheetActions
import com.promenar.infinity.ui.theme.InfinitySpacing

@Composable
internal fun keychainCategoryName(category: KeychainCategory): String = stringResource(
    when (category) {
        KeychainCategory.CUSTOM -> R.string.keychain_category_custom
        KeychainCategory.WEB_LOGIN -> R.string.keychain_category_web
        KeychainCategory.SSH_KEY -> R.string.keychain_category_ssh_key
        KeychainCategory.GIT -> R.string.keychain_category_git
        KeychainCategory.SSH_PASSWORD -> R.string.keychain_category_ssh_password
        KeychainCategory.PROVIDER -> R.string.keychain_category_provider
        KeychainCategory.SERVICE -> R.string.keychain_category_service
    },
)

@Composable
internal fun keychainCategoryShortName(category: KeychainCategory): String = stringResource(
    when (category) {
        KeychainCategory.CUSTOM -> R.string.keychain_category_custom
        KeychainCategory.WEB_LOGIN -> R.string.keychain_category_web_short
        KeychainCategory.SSH_KEY -> R.string.keychain_category_ssh_key
        KeychainCategory.GIT -> R.string.keychain_category_git_short
        KeychainCategory.SSH_PASSWORD -> R.string.keychain_category_ssh_password_short
        KeychainCategory.PROVIDER -> R.string.keychain_category_provider_short
        KeychainCategory.SERVICE -> R.string.keychain_category_service
    },
)

internal fun keychainCategoryIcon(category: KeychainCategory): ImageVector = when (category) {
    KeychainCategory.CUSTOM -> Icons.Rounded.Key
    KeychainCategory.WEB_LOGIN -> Icons.Rounded.Public
    KeychainCategory.SSH_KEY -> Icons.Rounded.VpnKey
    KeychainCategory.GIT -> Icons.Rounded.Source
    KeychainCategory.SSH_PASSWORD -> Icons.Rounded.Terminal
    KeychainCategory.PROVIDER -> Icons.Rounded.Cloud
    KeychainCategory.SERVICE -> Icons.Rounded.Hub
}

@Composable
internal fun keychainOriginLabel(origin: KeychainOrigin): String = stringResource(
    when (origin) {
        KeychainOrigin.LOCAL_GENERATED -> R.string.keychain_origin_local_generated
        KeychainOrigin.FILE_IMPORT -> R.string.keychain_origin_file_import
        KeychainOrigin.CLIPBOARD_IMPORT -> R.string.keychain_origin_clipboard_import
        KeychainOrigin.AGENT_GENERATED -> R.string.keychain_origin_agent_generated
        KeychainOrigin.AGENT_SAVED -> R.string.keychain_origin_agent_saved
        KeychainOrigin.SECRET_OUTPUT -> R.string.keychain_origin_secret_output
        KeychainOrigin.MANUAL -> R.string.keychain_origin_manual
        KeychainOrigin.MIGRATION -> R.string.keychain_origin_migration
    },
)

@Composable
internal fun keychainActionLabel(action: KeychainAction): String = stringResource(
    when (action) {
        KeychainAction.FILL -> R.string.keychain_usage_fill
        KeychainAction.COPY -> R.string.keychain_usage_copy
        KeychainAction.REVEAL -> R.string.keychain_usage_reveal
        KeychainAction.EXPORT -> R.string.keychain_usage_export
        KeychainAction.SIGN -> R.string.keychain_usage_sign
        KeychainAction.DEPLOY -> R.string.keychain_usage_deploy
        KeychainAction.AGENT_UPDATE -> R.string.keychain_usage_agent_update
    },
)

@Composable
internal fun keychainLinkLabel(link: KeychainSettingsLink): String = stringResource(
    when (link) {
        KeychainSettingsLink.SSH_HOSTS -> R.string.keychain_link_ssh
        KeychainSettingsLink.PROVIDERS -> R.string.keychain_link_providers
        KeychainSettingsLink.BUILTIN_TOOLS -> R.string.keychain_link_builtin_tools
        KeychainSettingsLink.DEFAULT_MODELS -> R.string.keychain_link_default_models
        KeychainSettingsLink.BACKUP -> R.string.keychain_link_backup
        KeychainSettingsLink.TAILSCALE -> R.string.keychain_link_tailscale
        KeychainSettingsLink.MCP -> R.string.keychain_link_mcp
    },
)

@Composable
internal fun keychainDate(millis: Long): String =
    DateUtils.formatDateTime(LocalContext.current, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

@Composable
internal fun keychainDateTime(millis: Long): String =
    DateUtils.formatDateTime(
        LocalContext.current,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
    )

internal val KeychainItem.gitRejected: Boolean get() = category == KeychainCategory.GIT && gitRejectedAt != null
internal val KeychainItem.gitFromGitHubLogin: Boolean get() = gitSource == GitCredential.SOURCE_GITHUB_LOGIN

/** 列表行副标题的各段；失效的 Git 凭据只在最后一段说明需要的处理。 */
@Composable
internal fun keychainRowSegments(item: KeychainItem): List<String> {
    if (item.category == KeychainCategory.SSH_KEY) {
        return listOfNotNull(
            item.keyType,
            item.fingerprintSha256?.let { if (it.length > 16) it.take(16) + "…" else it },
            stringResource(R.string.keychain_sub_passphrase).takeIf { item.hasPassphrase },
        )
    }
    val parts = mutableListOf<String>()
    item.url?.let(parts::add)
    item.username?.let(parts::add)
    if (item.gitRejected) {
        parts += stringResource(if (item.gitFromGitHubLogin) R.string.keychain_sub_git_rejected_login else R.string.keychain_sub_git_rejected_token)
        return parts
    }
    if (item.gitFromGitHubLogin) parts += stringResource(R.string.keychain_sub_github)
    if (item.updatedAt > 0) parts += stringResource(R.string.keychain_sub_updated, keychainDate(item.updatedAt))
    return parts
}

/** Git 凭据面板目标：新增或为某个主机更换令牌。 */
internal sealed interface KeychainGitEditor {
    data object Add : KeychainGitEditor
    data class ReplaceToken(val host: String, val username: String) : KeychainGitEditor
}

/** 添加 Git 凭据、更换令牌与 GitHub 浏览器登录三个面板；令牌只在面板内短暂存在。 */
@Composable
internal fun KeychainGitSheets(
    git: GitCredentialUiState,
    editor: KeychainGitEditor?,
    onDismissEditor: () -> Unit,
    onSave: (host: String, username: String, token: CharArray) -> Unit,
    onStartGitHubLogin: () -> Unit,
    onCloseGitHubLogin: () -> Unit,
    onOpenGitHubVerification: (verificationUri: String, userCode: String) -> Unit,
) {
    if (editor != null) {
        val replace = editor as? KeychainGitEditor.ReplaceToken
        var host by remember(editor) { mutableStateOf(replace?.host.orEmpty()) }
        var username by remember(editor) { mutableStateOf(replace?.username.orEmpty()) }
        var token by remember(editor) { mutableStateOf("") }
        var submitted by remember(editor) { mutableStateOf(false) }
        LaunchedEffect(git.operation, submitted) {
            if (submitted && git.operation is GitCredentialOperation.Saved) onDismissEditor()
        }
        InfinityBottomSheet(
            show = true,
            onDismiss = { token = ""; onDismissEditor() },
            title = stringResource(if (replace == null) R.string.keychain_add_git else R.string.keychain_replace_token),
            height = InfinitySheetHeight.Content,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                if (replace == null && git.githubLoginAvailable) {
                    InfinitySettingsItem(
                        icon = Icons.AutoMirrored.Rounded.Login,
                        title = stringResource(R.string.keychain_github_login),
                        onClick = { token = ""; onDismissEditor(); onStartGitHubLogin() },
                        showChevron = true,
                        modifier = Modifier.testTag("keychain_git_sheet_github"),
                    )
                }
                GitCredentialEditor(
                    host = host,
                    onHostChange = { host = it },
                    username = username,
                    onUsernameChange = { username = it },
                    token = token,
                    onTokenChange = { token = it },
                    hostLocked = replace != null,
                    operation = if (submitted) git.operation else GitCredentialOperation.Idle,
                    onCancel = { token = ""; onDismissEditor() },
                    onSave = {
                        val secret = token.toCharArray()
                        token = ""
                        submitted = true
                        onSave(host, username, secret)
                    },
                )
            }
        }
    }
    GitHubLoginSheet(
        state = git.githubLogin,
        actions = GitHubLoginSheetActions(
            onCancel = onCloseGitHubLogin,
            onRetry = onStartGitHubLogin,
            onOpenBrowser = onOpenGitHubVerification,
            onDone = onCloseGitHubLogin,
        ),
    )
}
