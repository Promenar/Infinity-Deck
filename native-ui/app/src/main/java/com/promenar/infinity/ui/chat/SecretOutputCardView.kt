package com.promenar.infinity.ui.chat

import android.content.ClipData
import com.promenar.infinity.ui.theme.forInput
import androidx.compose.material3.LocalTextStyle
import android.content.ClipboardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.foundation.layout.ColumnScope
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillMenuButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.infinity.R
import com.promenar.infinity.domain.secret.SecretDestination
import com.promenar.infinity.domain.secret.SecretInputOutcome
import com.promenar.infinity.domain.secret.SecretOutputCard
import com.promenar.infinity.domain.secret.SecretSlotState
import com.promenar.infinity.domain.secret.SecretSource
import com.promenar.infinity.domain.secret.SshSecretField
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 显示原文后自动隐藏的秒数。 */
internal const val SECRET_OUTPUT_REVEAL_SECONDS = 30

private const val SECRET_MASK = "••••••••••••••••••••••••"

// ── 敏感剪贴板 ──

/**
 * 复制保密值：剪贴板条目标记为敏感（系统不在复制提示与键盘建议里显示原文），
 * [CLEAR_DELAY_MILLIS] 后若剪贴板仍是这一条则清空。延时任务挂在主线程 Handler 上，离开页面后照常执行。
 */
internal object SensitiveClipboard {
    const val CLEAR_DELAY_MILLIS = 60_000L
    /** `ClipDescription.EXTRA_IS_SENSITIVE`（API 33 起的常量），低版本系统忽略该标记。 */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingClear: Runnable? = null

    /** 写入剪贴板；[value] 由调用方在返回后清零（系统在本调用内已复制出数据）。 */
    fun copy(context: Context, label: String, value: CharArray) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, java.nio.CharBuffer.wrap(value))
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        val stamp = clipboard.primaryClipDescription?.timestamp
        pendingClear?.let(handler::removeCallbacks)
        val clear = Runnable {
            pendingClear = null
            val current = clipboard.primaryClipDescription
            if (shouldClearSensitiveClip(current != null, current?.label, current?.timestamp, label, stamp)) {
                clipboard.clearPrimaryClip()
            }
        }
        pendingClear = clear
        handler.postDelayed(clear, CLEAR_DELAY_MILLIS)
    }
}

/**
 * 到期时是否清空剪贴板：能读到剪贴板描述时，只有标签与时间戳都与写入时一致才清；
 * 应用在后台读不到描述时无法确认，按保密优先清空。
 */
internal fun shouldClearSensitiveClip(
    readable: Boolean,
    currentLabel: CharSequence?,
    currentStamp: Long?,
    ownLabel: String,
    ownStamp: Long?,
): Boolean = if (!readable) true else currentLabel?.toString() == ownLabel && (ownStamp == null || currentStamp == ownStamp)

// ── 设备验证 ──

/** 设备验证入口：通过后调用 onSuccess；不可用或出错时 onError 带可显示的说明，用户主动取消时不回调。 */
internal fun interface SecretAuthenticator {
    fun authenticate(subtitle: String, onSuccess: () -> Unit, onError: (String) -> Unit)
}

/** 框架 BiometricPrompt：强生物识别或锁屏凭据（PIN、图案、密码）任一通过即可。 */
@Composable
internal fun rememberSecretAuthenticator(): SecretAuthenticator {
    val context = LocalContext.current
    val title = stringResource(R.string.secret_output_auth_title)
    val unavailable = stringResource(R.string.secret_output_auth_unavailable)
    return remember(context, title, unavailable) {
        SecretAuthenticator { subtitle, onSuccess, onError ->
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            val manager = context.getSystemService(BiometricManager::class.java)
            if (manager?.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
                onError(unavailable)
                return@SecretAuthenticator
            }
            val prompt = BiometricPrompt.Builder(context)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(authenticators)
                .setConfirmationRequired(false)
                .build()
            prompt.authenticate(
                CancellationSignal(),
                context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onSuccess()

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        ) return
                        onError(errString?.toString() ?: unavailable)
                    }
                },
            )
        }
    }
}

// ── 保密输出卡 ──

/** 输出卡的操作入口；值只经 [onReveal] 的返回值取用，调用方用完清零。 */
data class SecretOutputActions(
    val onReveal: () -> CharArray? = { null },
    val onSaveAs: (SecretDestination, String?, (SecretInputOutcome) -> Unit) -> Unit = { _, _, _ -> },
    val onDestroy: () -> Unit = {},
    val loadSaveTargets: suspend () -> SecretSaveTargets = { SecretSaveTargets() },
)

/**
 * `show_secret` 交给用户的保密输出卡。默认遮蔽；“验证后显示”与“复制”都先过设备验证。
 * 原文显示 [SECRET_OUTPUT_REVEAL_SECONDS] 秒后自动隐藏，应用进入后台或卡片离开组合时立即隐藏并清零；
 * 显示期间窗口禁止截屏。渲染原文需要短生命周期字符串，这一点与 [com.promenar.infinity.ui.common.SecretField] 相同。
 */
@Composable
internal fun SecretOutputCardView(
    card: SecretOutputCard,
    actions: SecretOutputActions,
    modifier: Modifier = Modifier,
    authenticator: SecretAuthenticator = rememberSecretAuthenticator(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val key = card.toolCallId + "/" + card.slot.name
    var revealed by remember(key) { mutableStateOf<CharArray?>(null) }
    var secondsLeft by remember(key) { mutableIntStateOf(0) }
    var copied by remember(key) { mutableStateOf(false) }
    var notice by remember(key) { mutableStateOf<String?>(null) }
    val resources = androidx.compose.ui.platform.LocalResources.current
    var destroyedLocally by remember(key) { mutableStateOf(false) }
    var confirmingDestroy by remember(key) { mutableStateOf(false) }
    var savingAs by remember(key) { mutableStateOf(false) }
    val unavailable = stringResource(R.string.secret_output_unavailable)
    val clipLabel = stringResource(R.string.secret_output_clip_label)
    // 值已不在（销毁、重启清除或被同名新值替换）时卡片收成一行记录。
    val spentState = if (destroyedLocally) SecretSlotState.DESTROYED else card.slot.state.takeIf { it != SecretSlotState.AVAILABLE }
    val destroyed = spentState != null

    fun hide() {
        revealed?.fill('\u0000')
        revealed = null
    }
    DisposableEffect(lifecycleOwner, key) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) hide() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            hide()
        }
    }
    LaunchedEffect(revealed) {
        if (revealed == null) return@LaunchedEffect
        secondsLeft = SECRET_OUTPUT_REVEAL_SECONDS
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft -= 1
        }
        hide()
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(6_000)
            copied = false
        }
    }
    LaunchedEffect(destroyed) { if (destroyed) hide() }
    SecureWindowEffect(active = revealed != null)

    fun withValue(use: (CharArray) -> Unit) {
        revealed?.let { shown ->
            val copy = shown.copyOf()
            try { use(copy) } finally { copy.fill('\u0000') }
            return
        }
        authenticator.authenticate(card.slot.label, onSuccess = {
            val value = actions.onReveal()
            if (value == null) notice = unavailable
            else try { use(value) } finally { value.fill('\u0000') }
        }, onError = { notice = it })
    }

    AnimatedContent(targetState = spentState, contentKey = { it != null }, label = "secretOutputSpent") { spent ->
        if (spent != null) {
            SecretOutputSpentRow(card, spent, modifier)
            return@AnimatedContent
        }
        SecretOutputCardContent(
            card = card,
            revealedText = revealed?.let { remember(it) { String(it) } },
            secondsLeft = secondsLeft,
            copied = copied,
            notice = notice,
            onReveal = {
                notice = null
                authenticator.authenticate(card.slot.label, onSuccess = {
                    val value = actions.onReveal()
                    if (value == null) notice = unavailable
                    else {
                        revealed?.fill('\u0000')
                        revealed = value
                    }
                }, onError = { notice = it })
            },
            onHide = ::hide,
            onCopy = {
                notice = null
                withValue { value ->
                    SensitiveClipboard.copy(context, clipLabel, value)
                    copied = true
                }
            },
            onSaveAs = { savingAs = true },
            onDestroy = { confirmingDestroy = true },
            modifier = modifier,
        )
    }
    if (savingAs && !destroyed) {
        SecretSaveAsSheet(
            card = card,
            loadTargets = actions.loadSaveTargets,
            onSave = actions.onSaveAs,
            onSaved = { label ->
                savingAs = false
                notice = resources.getString(R.string.secret_save_as_saved, label)
            },
            onDismiss = { savingAs = false },
        )
    }
    if (confirmingDestroy) {
        InfinityConfirmDialog(
            title = stringResource(R.string.secret_output_destroy_title),
            message = stringResource(R.string.secret_output_destroy_message, card.slot.name),
            confirmText = stringResource(R.string.secret_output_destroy),
            onConfirm = {
                confirmingDestroy = false
                hide()
                destroyedLocally = true
                actions.onDestroy()
            },
            onCancel = { confirmingDestroy = false },
            isDestructive = true,
            confirmButtonModifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(UiTags.CHAT_SECRET_OUTPUT_DESTROY_CONFIRM),
        )
    }
}

/** 来源的显示文案。 */
@Composable
internal fun secretSourceText(source: SecretSource): String = when (source) {
    is SecretSource.CommandStdout -> stringResource(R.string.secret_output_source_command, source.commandSummary)
    is SecretSource.File -> stringResource(R.string.secret_output_source_file, source.path)
    SecretSource.UserInput -> stringResource(R.string.secret_output_source_user)
}

/** 无状态的输出卡外观，供状态包装与截图预览共用。[revealedText] 为 null 时遮蔽。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SecretOutputCardContent(
    card: SecretOutputCard,
    revealedText: String?,
    secondsLeft: Int,
    copied: Boolean,
    notice: String?,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onCopy: () -> Unit,
    onSaveAs: () -> Unit,
    onDestroy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .animateContentSize()
            .testTag(UiTags.CHAT_SECRET_OUTPUT_CARD),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (revealedText != null) card.slot.label else stringResource(R.string.secret_output_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (revealedText != null) {
                    Text(
                        pluralStringResource(R.plurals.secret_output_hide_in, secondsLeft, secondsLeft),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (revealedText == null) {
                if (card.purpose.isNotBlank()) {
                    Text(
                        stringResource(R.string.secret_input_agent_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(card.purpose, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                }
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SecretInfoRow(
                        stringResource(R.string.secret_output_source),
                        pluralStringResource(R.plurals.secret_output_bytes, card.slot.byteLength, secretSourceText(card.slot.source), card.slot.byteLength),
                    )
                    SecretInfoRow(stringResource(R.string.secret_input_visibility), stringResource(R.string.secret_output_visibility_value))
                    SecretInfoRow(stringResource(R.string.secret_output_retain), stringResource(R.string.secret_output_retain_value))
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                DisableSelection {
                    Text(
                        text = revealedText ?: SECRET_MASK,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = InfinityMonospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).testTag(UiTags.CHAT_SECRET_OUTPUT_VALUE),
                    )
                }
            }
            if (revealedText == null) {
                // 保存为凭据与销毁是低频操作，收进复制旁的“更多”菜单，不与主操作抢位置；竖排时复制与“更多”仍同行。
                InfinityPillRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    spacing = 8.dp,
                    reverseWhenStacked = false,
                ) {
                    InfinityPillButton(
                        text = stringResource(R.string.secret_output_reveal),
                        onClick = onReveal,
                        tone = InfinityPillTone.PRIMARY,
                        icon = Icons.Rounded.Visibility,
                        modifier = Modifier.testTag(UiTags.CHAT_SECRET_OUTPUT_REVEAL),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        InfinityPillButton(
                            text = stringResource(R.string.secret_output_copy),
                            onClick = onCopy,
                            icon = Icons.Rounded.ContentCopy,
                            modifier = Modifier.weight(1f).testTag(UiTags.CHAT_SECRET_OUTPUT_COPY),
                        )
                        InfinityPillMenuButton(
                            contentDescription = stringResource(R.string.secret_output_more_cd),
                            modifier = Modifier.testTag(UiTags.CHAT_SECRET_OUTPUT_MORE),
                        ) { dismiss ->
                            SecretOutputMoreMenuItems(
                                onSaveAs = { dismiss(); onSaveAs() },
                                onDestroy = { dismiss(); onDestroy() },
                            )
                        }
                    }
                }
            } else {
                InfinityPillRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    spacing = 8.dp,
                    reverseWhenStacked = false,
                ) {
                    InfinityPillButton(
                        text = stringResource(R.string.secret_output_copy),
                        onClick = onCopy,
                        tone = InfinityPillTone.PRIMARY,
                        icon = Icons.Rounded.ContentCopy,
                        modifier = Modifier.testTag(UiTags.CHAT_SECRET_OUTPUT_COPY),
                    )
                    InfinityPillButton(
                        text = stringResource(R.string.secret_output_hide),
                        onClick = onHide,
                        icon = Icons.Rounded.VisibilityOff,
                        modifier = Modifier.testTag(UiTags.CHAT_SECRET_OUTPUT_HIDE),
                    )
                }
            }
            if (revealedText != null) {
                Text(
                    stringResource(R.string.secret_output_revealed_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (copied) {
                Text(
                    stringResource(R.string.secret_output_copied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.infinityDomainColors.success,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** 值已不在的输出卡：只留一行记录，样式与保密输入回执一致。 */
@Composable
internal fun SecretOutputSpentRow(card: SecretOutputCard, state: SecretSlotState, modifier: Modifier = Modifier) {
    val status = stringResource(
        when (state) {
            SecretSlotState.CLEARED -> R.string.secret_output_cleared
            SecretSlotState.REPLACED -> R.string.secret_output_replaced
            else -> R.string.secret_output_destroyed
        },
    )
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(UiTags.CHAT_SECRET_OUTPUT_CARD),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    card.slot.label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "\$${card.slot.name} · ${card.purpose.ifBlank { secretSourceText(card.slot.source) }}", // wrap-check: allow 单行省略的保密输出回执
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            // 状态限宽可折行，窄屏大字号时把宽度留给标题。
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 112.dp),
            )
        }
    }
}

// ── 保存为凭据 ──

/** 另存为去向的三种类别。 */
internal enum class SecretSaveAsKind { GIT, SSH, PROVIDER }

/** 当前选择能否组成一个去向；字段不全时为 null。 */
internal fun secretSaveAsDestination(
    kind: SecretSaveAsKind,
    gitHost: String,
    sshHostId: String?,
    sshField: SshSecretField,
    providerId: String?,
): SecretDestination? = when (kind) {
    SecretSaveAsKind.GIT -> gitHost.trim().takeIf { it.isNotEmpty() }?.let { SecretDestination.GitCredential(it) }
    SecretSaveAsKind.SSH -> sshHostId?.let { SecretDestination.SshHost(it, sshField) }
    SecretSaveAsKind.PROVIDER -> providerId?.let { SecretDestination.ProviderApiKey(it) }
}

@Composable
private fun SecretSaveAsSheet(
    card: SecretOutputCard,
    loadTargets: suspend () -> SecretSaveTargets,
    onSave: (SecretDestination, String?, (SecretInputOutcome) -> Unit) -> Unit,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var targets by remember(card.toolCallId) { mutableStateOf<SecretSaveTargets?>(null) }
    LaunchedEffect(card.toolCallId) { targets = loadTargets() }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    InfinityBottomSheet(
        show = true,
        onDismiss = { if (!saving) onDismiss() },
        title = stringResource(R.string.secret_save_as_title),
        height = InfinitySheetHeight.Content,
    ) {
        SecretSaveAsContent(
            targets = targets ?: SecretSaveTargets(),
            saving = saving,
            error = error,
            onConfirm = { destination, username, label ->
                saving = true
                error = null
                onSave(destination, username) { outcome ->
                    scope.launch {
                        saving = false
                        when (outcome) {
                            is SecretInputOutcome.Saved -> onSaved(label)
                            is SecretInputOutcome.Failed -> error = outcome.message
                            else -> onDismiss()
                        }
                    }
                }
            },
        )
    }
}

/** 另存为面板内容：选去向类别，再选主机或服务商；Git 凭据填主机与用户名。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SecretSaveAsContent(
    targets: SecretSaveTargets,
    saving: Boolean,
    error: String?,
    onConfirm: (destination: SecretDestination, username: String?, label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var kind by remember { mutableStateOf(SecretSaveAsKind.GIT) }
    val defaultGit = targets.gitHosts.firstOrNull()
    var gitHost by remember(defaultGit) { mutableStateOf(defaultGit?.first ?: "github.com") }
    var gitUsername by remember(defaultGit) { mutableStateOf(defaultGit?.second.orEmpty()) }
    var sshHostId by remember(targets) { mutableStateOf(targets.sshHosts.firstOrNull()?.id) }
    var sshField by remember { mutableStateOf(SshSecretField.PASSWORD) }
    var providerId by remember(targets) { mutableStateOf(targets.providers.firstOrNull()?.id) }
    val gitLabel = stringResource(R.string.secret_save_as_git)
    val destination = secretSaveAsDestination(kind, gitHost, sshHostId, sshField, providerId)
    val ready = destination != null && (kind != SecretSaveAsKind.GIT || gitUsername.isNotBlank())
    Column(modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                SecretSaveAsKind.GIT to R.string.secret_save_as_git,
                SecretSaveAsKind.SSH to R.string.secret_save_as_ssh,
                SecretSaveAsKind.PROVIDER to R.string.secret_save_as_provider,
            ).forEach { (option, label) ->
                FilterChip(
                    selected = kind == option,
                    onClick = { kind = option },
                    enabled = !saving,
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.testTag(UiTags.chatSecretSaveAsTarget(option.name.lowercase())),
                )
            }
        }
        when (kind) {
            SecretSaveAsKind.GIT -> {
                OutlinedTextField(
                    value = gitHost,
                    textStyle = LocalTextStyle.current.forInput(),
                    onValueChange = { gitHost = it },
                    label = { Text(stringResource(R.string.secret_save_as_host)) },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = gitUsername,
                    textStyle = LocalTextStyle.current.forInput(),
                    onValueChange = { gitUsername = it },
                    label = { Text(stringResource(R.string.secret_save_as_username)) },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            SecretSaveAsKind.SSH -> {
                if (targets.sshHosts.isEmpty()) {
                    SecretEmptyNote(stringResource(R.string.secret_save_as_no_ssh))
                } else {
                    SecretTargetList(targets.sshHosts, sshHostId, enabled = !saving) { sshHostId = it }
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            SshSecretField.PASSWORD to R.string.secret_save_as_ssh_password,
                            SshSecretField.KEY_PASSPHRASE to R.string.secret_save_as_ssh_passphrase,
                        ).forEach { (field, label) ->
                            FilterChip(
                                selected = sshField == field,
                                onClick = { sshField = field },
                                enabled = !saving,
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }
                }
            }
            SecretSaveAsKind.PROVIDER -> {
                if (targets.providers.isEmpty()) SecretEmptyNote(stringResource(R.string.secret_save_as_no_provider))
                else SecretTargetList(targets.providers, providerId, enabled = !saving) { providerId = it }
            }
        }
        error?.let {
            Text(
                stringResource(R.string.secret_save_as_failed, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(
            onClick = {
                val target = destination ?: return@Button
                val label = when (kind) {
                    SecretSaveAsKind.GIT -> "$gitLabel · ${gitHost.trim()}" // wrap-check: allow 单个去向短语，作为回执标签整体显示
                    SecretSaveAsKind.SSH -> targets.sshHosts.firstOrNull { it.id == sshHostId }?.label.orEmpty()
                    SecretSaveAsKind.PROVIDER -> targets.providers.firstOrNull { it.id == providerId }?.label.orEmpty()
                }
                onConfirm(target, gitUsername.takeIf { kind == SecretSaveAsKind.GIT }, label)
            },
            enabled = ready && !saving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                .testTag(UiTags.CHAT_SECRET_SAVE_AS_CONFIRM),
        ) { Text(stringResource(R.string.secret_save_as_confirm)) }
    }
}

@Composable
private fun SecretTargetList(
    targets: List<SecretSaveTargets.Target>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp).selectableGroup()) {
        targets.forEach { target ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                    .selectable(selected = target.id == selected, enabled = enabled, role = Role.RadioButton) { onSelect(target.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = target.id == selected, onClick = null, enabled = enabled)
                Spacer(Modifier.width(12.dp))
                Text(target.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SecretEmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** 保密输出卡“更多”菜单项；截图预览以静态浮层渲染同一内容。 */
@Composable
internal fun ColumnScope.SecretOutputMoreMenuItems(onSaveAs: () -> Unit, onDestroy: () -> Unit) {
    InfinityMenuItem(
        text = { Text(stringResource(R.string.secret_output_save_as)) },
        leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
        onClick = onSaveAs,
        modifier = Modifier.testTag(UiTags.CHAT_SECRET_OUTPUT_SAVE_AS),
    )
    InfinityMenuItem(
        text = { Text(stringResource(R.string.secret_output_destroy)) },
        leadingIcon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null) },
        destructive = true,
        onClick = onDestroy,
        modifier = Modifier.testTag(UiTags.CHAT_SECRET_OUTPUT_DESTROY),
    )
}
