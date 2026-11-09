package com.promenar.infinity.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.data.git.remote.GitCredential
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.SecretKeyboardOptions
import com.promenar.infinity.ui.common.SecretTextInput
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.infinityDomainColors

/** 复制设备码并用系统浏览器打开验证页；只接受 https 的 github.com 地址。 */
internal fun openGitHubVerification(context: Context, verificationUri: String, userCode: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.github_login_code_label), userCode))
    val uri = Uri.parse(verificationUri).takeIf { it.scheme == "https" && it.host == "github.com" }
        ?: Uri.parse("https://github.com/login/device")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // 没有浏览器时代码已复制，用户可在其它设备打开验证页。
    }
}

/** 凭据编辑面板：主机、用户名与始终遮蔽的令牌；更换令牌时主机不可改。 */
@Composable
internal fun GitCredentialEditor(
    host: String,
    onHostChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    token: String,
    onTokenChange: (String) -> Unit,
    hostLocked: Boolean,
    operation: GitCredentialOperation,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
        GitCredentialField(host, onHostChange, R.string.git_credentials_host_label, "git_credential_host",
            placeholder = "github.com", keyboardType = KeyboardType.Uri, enabled = !hostLocked)
        GitCredentialField(username, onUsernameChange, R.string.git_credentials_username_label, "git_credential_username")
        GitCredentialField(token, onTokenChange, R.string.git_credentials_token_label, "git_credential_token", secret = true)
        Text(
            stringResource(R.string.git_credentials_token_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GitCredentialStatus(operation)
        InfinityPillRow(Modifier.fillMaxWidth()) {
            InfinityPillButton(text = stringResource(R.string.common_cancel), onClick = onCancel)
            InfinityPillButton(
                text = stringResource(R.string.shared_btn_save),
                onClick = onSave,
                enabled = host.isNotBlank() && username.isNotBlank() && token.isNotEmpty() &&
                    operation != GitCredentialOperation.Saving,
                tone = InfinityPillTone.PRIMARY,
                modifier = Modifier.testTag("git_credential_save"),
            )
        }
    }
}

@Composable
private fun GitCredentialStatus(operation: GitCredentialOperation) {
    val (text, error) = when (operation) {
        GitCredentialOperation.Idle -> return
        GitCredentialOperation.Saving -> stringResource(R.string.git_credentials_saving) to false
        is GitCredentialOperation.Saved -> stringResource(R.string.git_credentials_saved, operation.host) to false
        is GitCredentialOperation.Deleted -> stringResource(R.string.git_credentials_deleted, operation.host) to false
        is GitCredentialOperation.Error -> stringResource(
            when (operation.code) {
                GitCredentialErrorCode.INVALID_HOST -> R.string.git_credentials_invalid_host
                GitCredentialErrorCode.INVALID_USERNAME -> R.string.git_credentials_invalid_username
                GitCredentialErrorCode.INVALID_TOKEN -> R.string.git_credentials_invalid_token
                GitCredentialErrorCode.LOAD_FAILED -> R.string.git_credentials_load_failed
                GitCredentialErrorCode.SAVE_FAILED -> R.string.git_credentials_save_failed
                GitCredentialErrorCode.DELETE_FAILED -> R.string.git_credentials_delete_failed
            },
        ) to true
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("git_credential_status"),
    )
}

@Composable
private fun GitCredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    label: Int,
    tag: String,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    enabled: Boolean = true,
) {
    val field = @Composable {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(label)) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(tag),
            textStyle = MaterialTheme.typography.bodyLarge.forInput(),
            shape = MaterialTheme.shapes.medium,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (secret) SecretKeyboardOptions else KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        )
    }
    if (secret) SecretTextInput(field) else field()
}
