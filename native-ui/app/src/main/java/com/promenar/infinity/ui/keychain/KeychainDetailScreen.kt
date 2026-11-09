package com.promenar.infinity.ui.keychain

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.domain.keychain.KeychainAction
import com.promenar.infinity.domain.keychain.KeychainCategory
import com.promenar.infinity.domain.keychain.KeychainDeleteResult
import com.promenar.infinity.domain.keychain.KeychainItem
import com.promenar.infinity.domain.keychain.KeychainUsage
import com.promenar.infinity.ui.chat.SensitiveClipboard
import com.promenar.infinity.ui.chat.rememberSecretAuthenticator
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityPropertyRow
import com.promenar.infinity.ui.common.InfinityPropertySheet
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySnackbar
import com.promenar.infinity.ui.common.InfinitySnackbarData
import com.promenar.infinity.ui.common.SnackbarType
import com.promenar.infinity.ui.settings.GitCredentialSettingsViewModel
import com.promenar.infinity.ui.settings.openGitHubVerification
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 验证后显示的值保留的秒数。 */
internal const val KEYCHAIN_REVEAL_SECONDS = 30

/** 一个值分量在页面上的状态：[revealed] 为 null 表示遮罩。 */
internal data class KeychainValueState(val valueRef: String, val label: String?, val revealed: String?)

internal data class KeychainDetailState(
    val item: KeychainItem,
    val values: List<KeychainValueState> = emptyList(),
    val secondsLeft: Int = 0,
    val usage: List<KeychainUsage>? = null,
)

internal data class KeychainDetailActions(
    val onBack: () -> Unit = {},
    val onReveal: (valueRef: String) -> Unit = {},
    val onHide: () -> Unit = {},
    val onCopy: (valueRef: String) -> Unit = {},
    val onEdit: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onReplaceToken: () -> Unit = {},
    val onReloginGitHub: () -> Unit = {},
    val onOpenSettings: (KeychainSettingsLink) -> Unit = {},
)

@Composable
fun KeychainEntryScreen(
    ref: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenSettings: (KeychainSettingsLink) -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val viewModel: KeychainViewModel = viewModel(factory = KeychainViewModel.factory(application))
    val gitViewModel: GitCredentialSettingsViewModel = viewModel(factory = GitCredentialSettingsViewModel.factory(application))
    val items by viewModel.items.collectAsState()
    val git by gitViewModel.state.collectAsState()
    val item = items?.firstOrNull { it.ref == ref }
    val usage by remember(ref) { viewModel.usage(ref) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val authenticator = rememberSecretAuthenticator()
    // 显示中的值：分量引用 → 字符数组；隐藏、到期、离开页面或切到后台时清零。
    var revealed by remember { mutableStateOf<Map<String, CharArray>>(emptyMap()) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var gitEditor by remember { mutableStateOf<KeychainGitEditor?>(null) }
    var everLoaded by remember { mutableStateOf(false) }

    fun hideAll() {
        revealed.values.forEach { it.fill('\u0000') }
        revealed = emptyMap()
        secondsLeft = 0
    }
    fun notify(message: String) { scope.launch { snackbar.showSnackbar(message) } }

    DisposableEffect(Unit) { onDispose { hideAll() } }
    LifecycleResumeEffect(Unit) { onPauseOrDispose { hideAll() } }
    LaunchedEffect(revealed.isNotEmpty()) {
        if (revealed.isEmpty()) return@LaunchedEffect
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft -= 1
        }
        hideAll()
    }
    // 条目被删除或在别处移除后退出详情。
    LaunchedEffect(items, item) {
        if (item != null) everLoaded = true
        else if (items != null && everLoaded) onBack()
    }

    val missing = stringResource(R.string.keychain_value_missing)
    val copied = stringResource(R.string.keychain_copied)
    val deleteFailed = stringResource(R.string.keychain_delete_failed)
    if (item == null) {
        InfinitySettingsPageLayout(title = stringResource(R.string.keychain_title), onBack = onBack) {}
        return
    }
    if (item.category == KeychainCategory.SSH_KEY) {
        KeychainSshKeyEntry(item, usage, viewModel, onBack)
        return
    }
    val revealSubtitle = stringResource(R.string.keychain_auth_reveal, item.name)
    val copySubtitle = stringResource(R.string.keychain_auth_copy, item.name)
    val deletedMessage = stringResource(R.string.keychain_deleted, item.name)
    val inUseTemplate = stringResource(R.string.keychain_delete_in_use, "%s")

    Box(Modifier.fillMaxSize()) {
        KeychainDetailContent(
            state = KeychainDetailState(
                item = item,
                values = keychainValueStates(item, revealed),
                secondsLeft = secondsLeft,
                usage = usage,
            ),
            actions = KeychainDetailActions(
                onBack = onBack,
                onReveal = { valueRef ->
                    authenticator.authenticate(revealSubtitle, onSuccess = {
                        scope.launch {
                            val chars = viewModel.readValue(item.ref, valueRef, KeychainAction.REVEAL)
                            if (chars == null) notify(missing) else {
                                revealed[valueRef]?.fill('\u0000')
                                revealed = revealed + (valueRef to chars)
                                secondsLeft = KEYCHAIN_REVEAL_SECONDS
                            }
                        }
                    }, onError = ::notify)
                },
                onHide = ::hideAll,
                onCopy = { valueRef ->
                    val shown = revealed[valueRef]
                    if (shown != null) {
                        SensitiveClipboard.copy(context, item.name, shown)
                        scope.launch { viewModel.recordUsage(item.ref, KeychainAction.COPY) }
                        notify(copied)
                    } else {
                        authenticator.authenticate(copySubtitle, onSuccess = {
                            scope.launch {
                                val chars = viewModel.readValue(item.ref, valueRef, KeychainAction.COPY)
                                if (chars == null) notify(missing) else {
                                    try { SensitiveClipboard.copy(context, item.name, chars) } finally { chars.fill('\u0000') }
                                    notify(copied)
                                }
                            }
                        }, onError = ::notify)
                    }
                },
                onEdit = { onEdit(item.ref) },
                onDelete = { confirmDelete = true },
                onReplaceToken = { item.gitHost()?.let { gitEditor = KeychainGitEditor.ReplaceToken(it, item.username.orEmpty()) } },
                onReloginGitHub = gitViewModel::startGitHubLogin,
                onOpenSettings = onOpenSettings,
            ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { data ->
            InfinitySnackbar(InfinitySnackbarData(data.visuals.message, SnackbarType.SUCCESS))
        }
    }

    if (confirmDelete) {
        InfinityConfirmDialog(
            title = stringResource(R.string.keychain_delete_title, item.name),
            message = stringResource(R.string.keychain_delete_message),
            confirmText = stringResource(R.string.keychain_delete),
            isDestructive = true,
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                hideAll()
                val host = item.gitHost()
                if (host != null) {
                    gitViewModel.delete(host)
                    notify(deletedMessage)
                } else scope.launch {
                    when (val result = viewModel.delete(item.ref)) {
                        KeychainDeleteResult.Deleted, KeychainDeleteResult.NotFound -> notify(deletedMessage)
                        is KeychainDeleteResult.InUse -> notify(inUseTemplate.replace("%s", result.hosts.joinToString("、") { it.alias }))
                        null -> notify(deleteFailed)
                    }
                }
            },
        )
    }
    KeychainGitSheets(
        git = git,
        editor = gitEditor,
        onDismissEditor = { gitEditor = null },
        onSave = gitViewModel::save,
        onStartGitHubLogin = gitViewModel::startGitHubLogin,
        onCloseGitHubLogin = gitViewModel::closeGitHubLogin,
        onOpenGitHubVerification = { uri, code -> openGitHubVerification(context, uri, code) },
    )
}

@Composable
private fun keychainValueStates(item: KeychainItem, revealed: Map<String, CharArray>): List<KeychainValueState> {
    if (!item.hasRevealableValue()) return emptyList()
    val refs = item.valueRefs()
    val apiKey = stringResource(R.string.keychain_value_api_key)
    val vertex = stringResource(R.string.keychain_value_vertex)
    return refs.map { valueRef ->
        val label = when {
            item.category != KeychainCategory.PROVIDER -> null
            isVertexValueRef(valueRef) -> vertex
            else -> apiKey
        }
        KeychainValueState(valueRef, label, revealed[valueRef]?.let(::String))
    }
}

/** 钥匙串条目详情：值（验证后显示与复制）、属性、Git 状态、使用记录；右上角编辑与更多操作。 */
@Composable
internal fun KeychainDetailContent(state: KeychainDetailState, actions: KeychainDetailActions, modifier: Modifier = Modifier) {
    val item = state.item
    InfinitySettingsPageLayout(
        title = item.name,
        onBack = actions.onBack,
        modifier = modifier,
        actions = { KeychainDetailActionsMenu(item, actions) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().testTag("keychain_detail"), contentPadding = padding) {
            if (item.gitRejected) {
                item(key = "rejected") { KeychainGitRejectedBanner(item, actions) }
            }
            if (state.values.isNotEmpty()) {
                item(key = "values") {
                    Column(
                        Modifier.padding(bottom = InfinitySpacing.SectionGap),
                        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                    ) {
                        state.values.forEach { value -> KeychainValueBox(value, state.secondsLeft, actions) }
                    }
                }
            }
            item(key = "properties") { KeychainProperties(item, actions) }
            item(key = "usage") { KeychainUsageSection(state.usage) }
        }
    }
}

@Composable
private fun KeychainDetailActionsMenu(item: KeychainItem, actions: KeychainDetailActions) {
    if (item.editableInKeychain()) {
        IconButton(onClick = actions.onEdit, modifier = Modifier.testTag("keychain_detail_edit")) {
            Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.keychain_edit))
        }
    }
    val link = item.settingsLink()
    val deletable = item.editableInKeychain() || item.category == KeychainCategory.GIT
    if (!deletable && link == null) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("keychain_detail_more")) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.keychain_more))
        }
        InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (item.category == KeychainCategory.GIT) {
                if (item.gitFromGitHubLogin) {
                    InfinityMenuItem(
                        text = { Text(stringResource(R.string.keychain_relogin_github)) },
                        onClick = { open = false; actions.onReloginGitHub() },
                    )
                } else {
                    InfinityMenuItem(
                        text = { Text(stringResource(R.string.keychain_replace_token)) },
                        onClick = { open = false; actions.onReplaceToken() },
                    )
                }
            }
            if (link != null) {
                InfinityMenuItem(
                    text = { Text(stringResource(R.string.keychain_edit_in_settings)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                    onClick = { open = false; actions.onOpenSettings(link) },
                )
            }
            if (deletable) {
                InfinityMenuItem(
                    text = { Text(stringResource(R.string.keychain_delete)) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    destructive = true,
                    onClick = { open = false; actions.onDelete() },
                    modifier = Modifier.testTag("keychain_detail_delete"),
                )
            }
        }
    }
}

@Composable
private fun KeychainGitRejectedBanner(item: KeychainItem, actions: KeychainDetailActions) {
    val warning = MaterialTheme.infinityDomainColors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = InfinitySpacing.SectionGap)
            .background(warning.warningContainer, RoundedCornerShape(16))
            .padding(InfinitySpacing.Large),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = warning.warning)
            Text(
                stringResource(if (item.gitFromGitHubLogin) R.string.keychain_sub_git_rejected_login else R.string.keychain_sub_git_rejected_token),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        InfinityPillButton(
            text = stringResource(if (item.gitFromGitHubLogin) R.string.keychain_relogin else R.string.keychain_replace_token),
            onClick = if (item.gitFromGitHubLogin) actions.onReloginGitHub else actions.onReplaceToken,
            tone = InfinityPillTone.PRIMARY,
            compact = true,
            modifier = Modifier.testTag("keychain_git_fix"),
        )
    }
}

@Composable
private fun KeychainValueBox(value: KeychainValueState, secondsLeft: Int, actions: KeychainDetailActions) {
    val shown = value.revealed
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16))
            .padding(InfinitySpacing.Large)
            .testTag("keychain_value_${value.valueRef}"),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        value.label?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = shown ?: KEYCHAIN_MASK,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = InfinityMonospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (shown == null) 1 else 6,
        )
        if (shown != null) {
            Text(
                stringResource(R.string.keychain_hide_countdown, secondsLeft),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InfinityPillRow {
            if (shown == null) {
                InfinityPillButton(
                    text = stringResource(R.string.keychain_reveal),
                    icon = Icons.Rounded.Visibility,
                    onClick = { actions.onReveal(value.valueRef) },
                    compact = true,
                    modifier = Modifier.testTag("keychain_reveal"),
                )
            } else {
                InfinityPillButton(
                    text = stringResource(R.string.keychain_hide),
                    icon = Icons.Rounded.VisibilityOff,
                    onClick = actions.onHide,
                    compact = true,
                    modifier = Modifier.testTag("keychain_hide"),
                )
            }
            InfinityPillButton(
                text = stringResource(R.string.keychain_copy),
                icon = Icons.Rounded.ContentCopy,
                onClick = { actions.onCopy(value.valueRef) },
                compact = true,
                modifier = Modifier.testTag("keychain_copy"),
            )
        }
    }
}

private const val KEYCHAIN_MASK = "••••••••••••••••"

@Composable
private fun KeychainProperties(item: KeychainItem, actions: KeychainDetailActions) {
    InfinitySettingsGroup {
        InfinityPropertySheet(Modifier.padding(vertical = InfinitySpacing.Small)) {
            InfinityPropertyRow(stringResource(R.string.keychain_prop_category), keychainCategoryName(item.category))
            item.username?.let { InfinityPropertyRow(stringResource(R.string.keychain_prop_username), it) }
            item.url?.let { InfinityPropertyRow(stringResource(R.string.keychain_prop_url), it) }
            if (item.category == KeychainCategory.GIT) {
                InfinityPropertyRow(
                    stringResource(R.string.keychain_prop_git_source),
                    stringResource(if (item.gitFromGitHubLogin) R.string.keychain_git_via_github else R.string.keychain_git_via_token),
                )
                InfinityPropertyRow(
                    label = stringResource(R.string.keychain_prop_status),
                    value = stringResource(
                        when {
                            !item.gitRejected -> R.string.keychain_status_ok
                            item.gitFromGitHubLogin -> R.string.keychain_status_rejected_login
                            else -> R.string.keychain_status_rejected_token
                        },
                    ),
                    valueColor = if (item.gitRejected) MaterialTheme.infinityDomainColors.warning else null,
                )
            }
            item.note?.let { InfinityPropertyRow(stringResource(R.string.keychain_prop_note), it) }
            item.origin?.let { InfinityPropertyRow(stringResource(R.string.keychain_prop_origin), keychainOriginLabel(it)) }
            if (item.updatedAt > 0) InfinityPropertyRow(stringResource(R.string.keychain_prop_updated), keychainDate(item.updatedAt))
            if (item.excludedFromExport()) {
                InfinityPropertyRow(stringResource(R.string.keychain_prop_export), stringResource(R.string.keychain_export_excluded))
            }
            item.settingsLink()?.let { link ->
                InfinityPropertyRow(
                    label = stringResource(R.string.keychain_prop_edit_in),
                    value = keychainLinkLabel(link),
                    onClick = { actions.onOpenSettings(link) },
                    modifier = Modifier.testTag("keychain_detail_link"),
                )
            }
        }
    }
}

@Composable
internal fun KeychainUsageSection(usage: List<KeychainUsage>?) {
    if (usage == null) return
    val keychainContext = stringResource(R.string.keychain_usage_context_keychain)
    InfinitySettingsSection(title = stringResource(R.string.keychain_section_usage)) {
        if (usage.isEmpty()) {
            Text(
                stringResource(R.string.keychain_usage_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Large),
            )
        }
        usage.take(USAGE_LIMIT).forEach { record ->
            InfinitySettingsRowLayout(
                title = keychainActionLabel(record.action),
                subtitleSegments = listOfNotNull(
                    record.context?.let { if (it == KEYCHAIN_USAGE_CONTEXT) keychainContext else it },
                    keychainDateTime(record.at),
                ),
                modifier = Modifier.semantics(mergeDescendants = true) {},
            )
        }
    }
}

private const val USAGE_LIMIT = 20
