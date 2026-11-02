package com.promenar.infinity.ui.chat

import androidx.compose.animation.animateContentSize
import com.promenar.infinity.ui.theme.forInput
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.secret.PendingSecretInput
import com.promenar.infinity.domain.secret.SecretDestination
import com.promenar.infinity.domain.secret.SecretExposure
import com.promenar.infinity.domain.secret.SecretKind
import com.promenar.infinity.ui.common.InfinityHapticEvent
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.SecretField
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.delay

// ── 展示逻辑（纯函数，界面与测试共用）──

/** 距失效的剩余时间（分、秒）；已到期返回 null。 */
internal fun secretInputRemaining(expiresAtMillis: Long, nowMillis: Long): Pair<Int, Int>? {
    val left = expiresAtMillis - nowMillis
    if (left <= 0) return null
    val seconds = ((left + 999) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return seconds / 60 to seconds % 60
}

/** 去向在卡片上的三种呈现：持久凭据、写入任务终端、本会话环境变量。 */
internal enum class SecretInputStyle { CREDENTIAL, JOB, SESSION_ENV }

internal fun secretInputStyle(destination: SecretDestination): SecretInputStyle = when (destination) {
    is SecretDestination.GitCredential, is SecretDestination.SshHost, is SecretDestination.ProviderApiKey -> SecretInputStyle.CREDENTIAL
    is SecretDestination.JobInput -> SecretInputStyle.JOB
    is SecretDestination.SessionEnv -> SecretInputStyle.SESSION_ENV
}

/** 必填项都已填写：值不为空，需要用户名时用户名不为空白。 */
internal fun secretInputReady(valueLength: Int, needsUsername: Boolean, username: String): Boolean =
    valueLength > 0 && (!needsUsername || username.isNotBlank())

private fun secretFieldLabel(kind: SecretKind): Int = when (kind) {
    SecretKind.PASSWORD -> R.string.secret_input_field_password
    SecretKind.TOKEN -> R.string.secret_input_field_token
    SecretKind.API_KEY -> R.string.secret_input_field_api_key
    SecretKind.PASSPHRASE -> R.string.secret_input_field_passphrase
    SecretKind.OTHER -> R.string.secret_input_field_other
}

// ── 待输入卡 ──

/**
 * 保密输入卡：说明用途、去向与 Agent 可见性，用户填写后由 [onSubmit] 交给协调器落地。
 *
 * 值只在本卡的 Compose 状态里（不进 rememberSaveable，旋转或进程重建即丢弃），提交时转成 [CharArray]
 * 交出并立即清空输入框；数组由接收方清零。Compose 文本输入只能用 String，输入期间存在平台无法原地擦除的
 * 短生命周期字符串，这一点与 [SecretField] 相同。卡片获得焦点或有内容时窗口禁止截屏。
 */
@Composable
internal fun SecretInputCard(
    pending: PendingSecretInput,
    onSubmit: (value: CharArray, username: String?) -> Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    submitting: Boolean = false,
    error: String? = null,
    /** 去向是 github.com 且应用配置了浏览器登录时显示；为 null 时不显示入口。 */
    onGitHubBrowserLogin: (() -> Unit)? = null,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    val haptics = LocalInfinityHaptics.current
    val warning = MaterialTheme.infinityDomainColors.warning
    val key = pending.toolCallId
    var value by remember(key) { mutableStateOf("") }
    var focused by remember(key) { mutableStateOf(false) }
    var username by rememberSaveable(key) {
        mutableStateOf((pending.request.destination as? SecretDestination.GitCredential)?.username.orEmpty())
    }
    var now by remember(key) { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(key, pending.expiresAtMillis) {
        while (now < pending.expiresAtMillis) {
            delay(1_000)
            now = nowMillis()
        }
        value = ""
    }
    val remaining = secretInputRemaining(pending.expiresAtMillis, now)
    SecureWindowEffect(active = remaining != null && (focused || value.isNotEmpty()))
    if (remaining == null) {
        SecretInputReceiptRow(
            icon = Icons.Rounded.RemoveCircleOutline,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            title = stringResource(R.string.secret_input_expired),
            detail = null,
            modifier = modifier,
        )
        return
    }
    val style = secretInputStyle(pending.request.destination)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .animateContentSize()
            .testTag(UiTags.CHAT_SECRET_INPUT_CARD),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, warning.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, contentDescription = null, tint = warning, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.secret_input_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = warning,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.secret_input_expires_in, remaining.first, remaining.second),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.secret_input_agent_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                pending.request.purpose,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (style) {
                    SecretInputStyle.CREDENTIAL -> {
                        SecretInfoRow(stringResource(R.string.secret_input_dest_saved_to), pending.destinationLabel)
                        SecretInfoRow(stringResource(R.string.secret_input_visibility), stringResource(R.string.secret_input_visibility_saved))
                    }
                    SecretInputStyle.JOB -> {
                        SecretInfoRow(stringResource(R.string.secret_input_dest_job), pending.destinationLabel)
                        SecretInfoRow(stringResource(R.string.secret_input_keep), stringResource(R.string.secret_input_keep_once))
                        SecretInfoRow(stringResource(R.string.secret_input_visibility), stringResource(R.string.secret_input_visibility_job))
                    }
                    SecretInputStyle.SESSION_ENV -> {
                        SecretInfoRow(stringResource(R.string.secret_input_dest_env), pending.destinationLabel, monospace = true)
                        SecretInfoRow(stringResource(R.string.secret_input_scope), stringResource(R.string.secret_input_scope_session))
                        SecretInfoRow(stringResource(R.string.secret_input_lifetime), stringResource(R.string.secret_input_lifetime_session))
                    }
                }
            }
            if (pending.exposure == SecretExposure.COMMAND_READABLE) {
                SecretWarningNote(stringResource(R.string.secret_input_command_readable), Modifier.padding(top = 12.dp))
            }
            if (pending.needsUsername) {
                OutlinedTextField(
                    value = username,
                    textStyle = LocalTextStyle.current.forInput(),
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.secret_input_username)) },
                    singleLine = true,
                    enabled = !submitting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .semantics { contentType = ContentType.Username }
                        .testTag(UiTags.CHAT_SECRET_INPUT_USERNAME),
                )
            }
            SecretField(
                value = value,
                onValueChange = { value = it },
                hasStoredSecret = false,
                onRevealRequest = { null },
                onClear = { value = "" },
                label = stringResource(secretFieldLabel(pending.request.kind)),
                placeholder = stringResource(secretFieldLabel(pending.request.kind)),
                isError = error != null,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentType = ContentType.Password }
                    .testTag(UiTags.CHAT_SECRET_INPUT_FIELD),
            )
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.secret_input_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let {
                Text(
                    stringResource(R.string.secret_input_failed, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (pending.offersGitHubBrowserLogin && onGitHubBrowserLogin != null) {
                InfinityPillButton(
                    text = stringResource(R.string.secret_input_github_browser),
                    onClick = onGitHubBrowserLogin,
                    enabled = !submitting,
                    icon = Icons.Rounded.OpenInBrowser,
                    modifier = Modifier.padding(top = 8.dp).testTag(UiTags.CHAT_SECRET_INPUT_GITHUB_LOGIN),
                )
            }
            InfinityPillRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), spacing = 8.dp) {
                InfinityPillButton(
                    text = stringResource(R.string.secret_input_cancel),
                    onClick = {
                        value = ""
                        onCancel()
                    },
                    enabled = !submitting,
                    modifier = Modifier.testTag(UiTags.CHAT_SECRET_INPUT_CANCEL),
                )
                InfinityPillButton(
                    text = stringResource(
                        when (style) {
                            SecretInputStyle.CREDENTIAL -> R.string.secret_input_submit_credential
                            SecretInputStyle.JOB -> R.string.secret_input_submit_job
                            SecretInputStyle.SESSION_ENV -> R.string.secret_input_submit_env
                        },
                    ),
                    onClick = {
                        haptics.perform(InfinityHapticEvent.CONFIRM)
                        val chars = value.toCharArray()
                        value = ""
                        onSubmit(chars, username.takeIf { pending.needsUsername || it.isNotBlank() })
                    },
                    tone = InfinityPillTone.PRIMARY,
                    enabled = !submitting && secretInputReady(value.length, pending.needsUsername, username),
                    modifier = Modifier.testTag(UiTags.CHAT_SECRET_INPUT_SUBMIT),
                )
            }
            if (style == SecretInputStyle.JOB) {
                Text(
                    stringResource(R.string.secret_input_job_expiry_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** 卡片里“标题 · 值”的一行。 */
@Composable
internal fun SecretInfoRow(head: String, value: String, monospace: Boolean = false) {
    Row {
        Text(
            head,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(
            value,
            style = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace) else MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 警示色风险提示块。 */
@Composable
internal fun SecretWarningNote(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.infinityDomainColors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.warningContainer,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = colors.onWarningContainer, modifier = Modifier.size(16.dp).padding(top = 1.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = colors.onWarningContainer)
        }
    }
}

// ── 提交后的单行回执 ──

@Composable
internal fun SecretInputReceiptCard(receipt: SecretInputReceipt, modifier: Modifier = Modifier) {
    when (receipt.status) {
        SecretInputReceipt.Status.SAVED -> SecretInputReceiptRow(
            icon = Icons.Rounded.CheckCircle,
            tint = MaterialTheme.infinityDomainColors.success,
            title = stringResource(R.string.secret_input_saved, receipt.destinationLabel),
            detail = receipt.username?.let { stringResource(R.string.secret_input_saved_username, it) }
                ?: stringResource(R.string.secret_input_saved_plain),
            modifier = modifier,
        )
        SecretInputReceipt.Status.CANCELLED -> SecretInputReceiptRow(
            icon = Icons.Rounded.RemoveCircleOutline,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            title = stringResource(R.string.secret_input_cancelled),
            detail = null,
            modifier = modifier,
        )
        SecretInputReceipt.Status.EXPIRED -> SecretInputReceiptRow(
            icon = Icons.Rounded.RemoveCircleOutline,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            title = stringResource(R.string.secret_input_expired),
            detail = null,
            modifier = modifier,
        )
    }
}

@Composable
private fun SecretInputReceiptRow(icon: ImageVector, tint: Color, title: String, detail: String?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(UiTags.CHAT_SECRET_INPUT_RECEIPT),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
