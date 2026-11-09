package com.promenar.infinity.ui.ssh

import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.promenar.infinity.ui.common.SecretKeyboardOptions
import com.promenar.infinity.ui.common.SecretTextInput
import com.promenar.infinity.ui.common.excludeFromAutofill
import androidx.compose.ui.window.DialogProperties
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.data.ssh.SshUserPrompt
import com.promenar.infinity.data.ssh.SshUserPromptBroker
import com.promenar.infinity.ui.theme.InfinitySpacing
import androidx.compose.material3.LocalTextStyle
import com.promenar.infinity.ui.theme.forInput

/** 挂在应用根部：Agent 工具执行中需要用户输入 SSH 密码或核对主机指纹时弹出。 */
@Composable
fun SshUserPromptHost(broker: SshUserPromptBroker) {
    val prompt by broker.current.collectAsState()
    when (val current = prompt) {
        is SshUserPrompt.Password -> SshPasswordPromptDialog(current)
        is SshUserPrompt.HostKey -> SshHostKeyPromptDialog(current)
        null -> Unit
    }
}

@Composable
internal fun SshPasswordPromptDialog(prompt: SshUserPrompt.Password) {
    var password by remember(prompt) { mutableStateOf("") }
    InfinityConfirmDialog(
        onDismissRequest = { prompt.submit(null) },
        properties = DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.testTag(SSH_PROMPT_PASSWORD_DIALOG),
        title = stringResource(R.string.ssh_agent_password_title, prompt.alias),
        message = "",
        confirmText = stringResource(R.string.ssh_save),
        cancelText = stringResource(R.string.ssh_cancel),
        onConfirm = {
            prompt.submit(password.toByteArray(Charsets.UTF_8))
            password = ""
        },
        onCancel = { password = ""; prompt.submit(null) },
        confirmEnabled = password.isNotEmpty(),
        confirmButtonModifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(SSH_PROMPT_CONFIRM),
        cancelButtonModifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(SSH_PROMPT_CANCEL),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                Text(
                    stringResource(R.string.ssh_agent_password_message, "${prompt.username}@${prompt.hostname}:${prompt.port}"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SecretTextInput {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.ssh_password)) },
                        textStyle = LocalTextStyle.current.forInput(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = SecretKeyboardOptions,
                        modifier = Modifier.fillMaxWidth().testTag(SSH_PROMPT_PASSWORD_FIELD).excludeFromAutofill(),
                    )
                }
            }
        },
    )
}

@Composable
internal fun SshHostKeyPromptDialog(prompt: SshUserPrompt.HostKey) {
    InfinityConfirmDialog(
        onDismissRequest = { prompt.answer(false) },
        properties = DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.testTag(SSH_PROMPT_HOST_KEY_DIALOG),
        title = stringResource(if (prompt.previous == null) R.string.ssh_trust_title else R.string.ssh_changed_title),
        message = "",
        confirmText = stringResource(if (prompt.previous == null) R.string.ssh_trust else R.string.ssh_replace_trust),
        cancelText = stringResource(R.string.ssh_cancel),
        onConfirm = { prompt.answer(true) },
        onCancel = { prompt.answer(false) },
        confirmButtonModifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(SSH_PROMPT_CONFIRM),
        cancelButtonModifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(SSH_PROMPT_CANCEL),
        content = {
            // 统一对话框正文不自带滚动与高度上限，长指纹在此限高滚动，避免挤掉按钮。
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                Text(
                    stringResource(R.string.ssh_agent_host_key_message, prompt.alias, "${prompt.hostname}:${prompt.port}"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.ssh_compare_fingerprint), style = MaterialTheme.typography.bodyMedium)
                prompt.previous?.let { previous ->
                    Text(stringResource(R.string.ssh_previous_fingerprint), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer {
                        Text("${previous.type}\n${previous.fingerprint}", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace))
                    }
                }
                Text(stringResource(R.string.ssh_presented_fingerprint), style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text("${prompt.presented.type}\n${prompt.presented.fingerprint}", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace))
                }
            }
        },
    )
}

internal const val SSH_PROMPT_PASSWORD_DIALOG = "ssh_prompt_password_dialog"
internal const val SSH_PROMPT_PASSWORD_FIELD = "ssh_prompt_password_field"
internal const val SSH_PROMPT_HOST_KEY_DIALOG = "ssh_prompt_host_key_dialog"
internal const val SSH_PROMPT_CONFIRM = "ssh_prompt_confirm"
internal const val SSH_PROMPT_CANCEL = "ssh_prompt_cancel"
