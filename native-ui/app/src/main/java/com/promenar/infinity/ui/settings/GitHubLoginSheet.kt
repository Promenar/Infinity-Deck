package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.data.git.remote.GitHubDeviceFailure
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.delay

/** 授权面板的按钮回调；打开浏览器与复制代码由页面实现。 */
internal data class GitHubLoginSheetActions(
    val onCancel: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenBrowser: (verificationUri: String, userCode: String) -> Unit = { _, _ -> },
    val onDone: () -> Unit = {},
)

/** “用浏览器登录 GitHub”底部面板：取码、等待授权、成功与失败四种状态；关闭面板即取消等待。 */
@Composable
internal fun GitHubLoginSheet(state: GitHubLoginState, actions: GitHubLoginSheetActions) {
    if (state == GitHubLoginState.Idle) return
    InfinityBottomSheet(
        show = true,
        onDismiss = { if (state is GitHubLoginState.Success) actions.onDone() else actions.onCancel() },
        title = stringResource(R.string.github_login_title),
        height = InfinitySheetHeight.Content,
    ) {
        GitHubLoginContent(state, actions)
    }
}

@Composable
internal fun GitHubLoginContent(
    state: GitHubLoginState,
    actions: GitHubLoginSheetActions,
    initiallyOpened: Boolean = false,
    nowMillis: (() -> Long)? = null,
) {
    // 打开过浏览器后改为“等待授权”，按钮变为“重新打开浏览器”。
    var opened by remember(state is GitHubLoginState.AwaitingUser) { mutableStateOf(initiallyOpened) }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("github_login_sheet").semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        when (state) {
            GitHubLoginState.Idle -> Unit
            GitHubLoginState.Requesting -> {
                Progress(stringResource(R.string.github_login_requesting))
                Buttons(cancel = actions.onCancel)
            }
            is GitHubLoginState.AwaitingUser -> {
                if (!opened) {
                    Body(stringResource(R.string.github_login_instructions, state.verificationUri.removePrefix("https://")))
                    UserCode(state.userCode, large = true)
                    Note(stringResource(R.string.github_login_code_note))
                    Buttons(
                        cancel = actions.onCancel,
                        primary = stringResource(R.string.github_login_copy_open) to {
                            actions.onOpenBrowser(state.verificationUri, state.userCode)
                            opened = true
                        },
                    )
                } else {
                    Progress(stringResource(R.string.github_login_waiting))
                    UserCode(state.userCode, large = false)
                    Note(stringResource(R.string.github_login_waiting_note, remainingText(state.expiresAtMillis, nowMillis)))
                    Buttons(
                        cancel = actions.onCancel,
                        secondary = stringResource(R.string.github_login_reopen) to { actions.onOpenBrowser(state.verificationUri, state.userCode) },
                    )
                }
            }
            is GitHubLoginState.Success -> {
                Outcome(Icons.Rounded.CheckCircle, MaterialTheme.infinityDomainColors.success,
                    stringResource(R.string.github_login_success_title), stringResource(R.string.github_login_success_detail, state.login))
                Note(stringResource(R.string.github_login_success_note))
                Buttons(primary = stringResource(R.string.github_login_done) to actions.onDone)
            }
            is GitHubLoginState.Failed -> {
                val (title, detail) = failureText(state.failure)
                Outcome(Icons.Rounded.ErrorOutline, MaterialTheme.colorScheme.error, stringResource(title), stringResource(detail))
                Buttons(cancel = actions.onCancel, primary = stringResource(R.string.github_login_retry) to actions.onRetry)
            }
        }
    }
}

private fun failureText(failure: GitHubDeviceFailure): Pair<Int, Int> = when (failure) {
    GitHubDeviceFailure.EXPIRED -> R.string.github_login_expired_title to R.string.github_login_expired_detail
    GitHubDeviceFailure.DENIED -> R.string.github_login_denied_title to R.string.github_login_denied_detail
    GitHubDeviceFailure.DEVICE_FLOW_DISABLED -> R.string.github_login_disabled_title to R.string.github_login_disabled_detail
    GitHubDeviceFailure.NETWORK -> R.string.github_login_network_title to R.string.github_login_network_detail
    GitHubDeviceFailure.INVALID_RESPONSE -> R.string.github_login_invalid_title to R.string.github_login_invalid_detail
    GitHubDeviceFailure.SAVE_FAILED -> R.string.github_login_save_failed_title to R.string.github_login_save_failed_detail
    GitHubDeviceFailure.NOT_CONFIGURED -> R.string.github_login_not_configured_title to R.string.github_login_not_configured_detail
}

/** 剩余时间每秒刷新；截图测试传入固定时钟。 */
@Composable
private fun remainingText(expiresAtMillis: Long, nowMillis: (() -> Long)?): String {
    var now by remember { mutableLongStateOf(nowMillis?.invoke() ?: System.currentTimeMillis()) }
    if (nowMillis == null) {
        LaunchedEffect(expiresAtMillis) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    val seconds = ((expiresAtMillis - now).coerceAtLeast(0) + 999) / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun UserCode(code: String, large: Boolean) {
    SelectionContainer {
        Text(
            code,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = InfinityMonospace,
                fontWeight = FontWeight.Medium,
                fontSize = if (large) 32.sp else 22.sp,
                letterSpacing = 3.sp,
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = if (large) InfinitySpacing.Medium else InfinitySpacing.Small).testTag("github_login_code"),
        )
    }
}

@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Outcome(icon: ImageVector, tint: Color, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** 面板页脚：取消在左、主操作在最右；只有一个操作时靠右放单个胶囊，不铺满整行。 */
@Composable
private fun Buttons(
    cancel: (() -> Unit)? = null,
    secondary: Pair<String, () -> Unit>? = null,
    primary: Pair<String, () -> Unit>? = null,
) {
    if (listOfNotNull(cancel, secondary, primary).size == 1) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { ButtonPills(cancel, secondary, primary) }
    } else {
        InfinityPillRow(Modifier.fillMaxWidth()) { ButtonPills(cancel, secondary, primary) }
    }
}

@Composable
private fun ButtonPills(
    cancel: (() -> Unit)?,
    secondary: Pair<String, () -> Unit>?,
    primary: Pair<String, () -> Unit>?,
) {
    if (cancel != null) {
        InfinityPillButton(
            text = stringResource(R.string.common_cancel),
            onClick = cancel,
            modifier = Modifier.testTag("github_login_cancel"),
        )
    }
    secondary?.let { (label, onClick) ->
        InfinityPillButton(
            text = label,
            onClick = onClick,
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            modifier = Modifier.testTag("github_login_secondary"),
        )
    }
    primary?.let { (label, onClick) ->
        InfinityPillButton(
            text = label,
            onClick = onClick,
            tone = InfinityPillTone.PRIMARY,
            modifier = Modifier.testTag("github_login_primary"),
        )
    }
}
