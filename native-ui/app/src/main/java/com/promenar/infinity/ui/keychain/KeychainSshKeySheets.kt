package com.promenar.infinity.ui.keychain

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.domain.keychain.DeployStage
import com.promenar.infinity.domain.keychain.KeychainCategory
import com.promenar.infinity.domain.keychain.KeychainItem
import com.promenar.infinity.domain.keychain.SshKeyType
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.SecretKeyboardOptions
import com.promenar.infinity.ui.common.SecretTextInput
import com.promenar.infinity.ui.common.excludeFromAutofill
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.ssh.sshErrorText
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.rememberLastNonNull

// ── 通用 ──

@Composable
internal fun KeychainSheetField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
    secret: Boolean = false,
    error: String? = null,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    val field = @Composable {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, maxLines = 1) },
            singleLine = true,
            enabled = enabled,
            isError = error != null,
            supportingText = (error ?: supporting)?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth().testTag(tag).then(if (secret) Modifier.excludeFromAutofill() else Modifier),
            textStyle = MaterialTheme.typography.bodyLarge.forInput(),
            shape = MaterialTheme.shapes.medium,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (secret) SecretKeyboardOptions else KeyboardOptions(autoCorrectEnabled = false),
        )
    }
    if (secret) SecretTextInput(field) else field()
}

@Composable
private fun KeychainSheetButtons(
    confirmText: String,
    confirmEnabled: Boolean,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    confirmTag: String,
) {
    val scheme = MaterialTheme.colorScheme
    val interactive = confirmEnabled && !busy
    val container = when {
        busy -> scheme.primary.copy(alpha = 0.85f)
        confirmEnabled -> scheme.primary
        else -> scheme.onSurface.copy(alpha = 0.12f)
    }
    val content = when {
        busy || confirmEnabled -> scheme.onPrimary
        else -> scheme.onSurface.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfinityPillButton(
            text = stringResource(R.string.common_cancel),
            onClick = onCancel,
            enabled = !busy,
            modifier = Modifier.weight(1f),
        )
        Surface(
            onClick = onConfirm,
            enabled = interactive,
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .heightIn(min = 48.dp)
                .testTag(confirmTag),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                AnimatedVisibility(
                    visible = busy,
                    enter = expandHorizontally(tween(InfinityMotion.DurationShort)) + fadeIn(tween(InfinityMotion.DurationShort)),
                    exit = shrinkHorizontally(tween(InfinityMotion.DurationShort)) + fadeOut(tween(InfinityMotion.DurationShort)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = content,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Text(
                    text = confirmText,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun KeychainSheetError(code: String?) {
    val lastCode = rememberLastNonNull(code)
    AnimatedVisibility(
        visible = code != null,
        enter = InfinityMotion.expandIn,
        exit = InfinityMotion.shrinkOut,
    ) {
        lastCode?.let { shownCode ->
            Text(
                if (shownCode.startsWith(KEYCHAIN_TEXT_ERROR)) shownCode.removePrefix(KEYCHAIN_TEXT_ERROR) else sshErrorText(shownCode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("keychain_sheet_error"),
            )
        }
    }
}

/** 错误区直接显示文字时加此前缀，其余视为 SSH 错误码。 */
internal const val KEYCHAIN_TEXT_ERROR = "text:"

/** 密钥类型的显示名：ssh-ed25519 → Ed25519。 */
internal fun keychainKeyTypeLabel(keyType: String?): String? = when (keyType) {
    null -> null
    "ssh-ed25519" -> "Ed25519"
    "ssh-rsa" -> "RSA"
    "ecdsa-sha2-nistp256" -> "ECDSA P-256"
    "ecdsa-sha2-nistp384" -> "ECDSA P-384"
    "ecdsa-sha2-nistp521" -> "ECDSA P-521"
    else -> keyType
}

/** 使用 [entryRef] 这把密钥登录的主机。 */
internal fun hostsUsingKey(hosts: List<SshHost>, entryRef: String): List<SshHost> =
    hosts.filter { it.authType == SshAuthType.KEY && it.keyEntryId == entryRef }

@Composable
internal fun sshKeySubtitleSegments(item: KeychainItem, hostCount: Int): List<String> = listOfNotNull(
    keychainKeyTypeLabel(item.keyType),
    if (hostCount > 0) pluralStringResource(R.plurals.keychain_ssh_sub_hosts, hostCount, hostCount)
    else stringResource(R.string.keychain_ssh_sub_unused),
    stringResource(R.string.keychain_sub_passphrase).takeIf { item.hasPassphrase },
)

// ── 生成 ──

internal data class SshKeyGenerateForm(val name: String = "", val type: SshKeyType = SshKeyType.ED25519, val passphrase: String = "")

@Composable
internal fun SshKeyGenerateContent(
    form: SshKeyGenerateForm,
    busy: Boolean,
    error: String?,
    onChange: (SshKeyGenerateForm) -> Unit,
    onCancel: () -> Unit,
    onGenerate: () -> Unit,
) {
    Column(
        modifier = Modifier.animateContentSize(InfinityMotion.contentSizeSpec()),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        KeychainSheetField(form.name, { onChange(form.copy(name = it)) }, stringResource(R.string.keychain_field_name), "ssh_gen_name", enabled = !busy)
        InfinitySegmentedControl(
            segments = listOf(
                InfinitySegment(SshKeyType.ED25519, stringResource(R.string.keychain_ssh_type_ed25519)),
                InfinitySegment(SshKeyType.RSA_4096, stringResource(R.string.keychain_ssh_type_rsa)),
            ),
            selected = form.type,
            onSelect = { onChange(form.copy(type = it)) },
            enabled = !busy,
            fillWidth = true,
        )
        KeychainSheetField(
            form.passphrase, { onChange(form.copy(passphrase = it)) },
            stringResource(R.string.keychain_ssh_passphrase_optional), "ssh_gen_passphrase", secret = true, enabled = !busy,
        )
        KeychainSheetError(error)
        KeychainSheetButtons(
            confirmText = stringResource(R.string.keychain_ssh_generate),
            confirmEnabled = form.name.isNotBlank(),
            busy = busy,
            onCancel = onCancel,
            onConfirm = onGenerate,
            confirmTag = "ssh_gen_confirm",
        )
    }
}

// ── 导入 ──

internal enum class SshKeyImportSource { FILE, CLIPBOARD }

internal data class SshKeyImportForm(
    val source: SshKeyImportSource? = null,
    val fileName: String? = null,
    val name: String = "",
    val passphrase: String = "",
)

@Composable
internal fun SshKeyImportContent(
    form: SshKeyImportForm,
    busy: Boolean,
    error: String?,
    onPickFile: () -> Unit,
    onPickClipboard: () -> Unit,
    onChange: (SshKeyImportForm) -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
) {
    AnimatedContent(
        targetState = form.source == null,
        transitionSpec = { InfinityMotion.fadeThrough() },
        label = "ssh_import_stage",
    ) { picking ->
        if (picking) {
            Column(
                modifier = Modifier.animateContentSize(InfinityMotion.contentSizeSpec()),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                Text(
                    stringResource(R.string.keychain_ssh_import_formats),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InfinitySettingsItem(
                    icon = Icons.Rounded.UploadFile,
                    title = stringResource(R.string.keychain_ssh_import_file),
                    onClick = onPickFile,
                    enabled = !busy,
                    modifier = Modifier.testTag("ssh_import_file"),
                )
                InfinitySettingsItem(
                    icon = Icons.Rounded.ContentPaste,
                    title = stringResource(R.string.keychain_ssh_import_clipboard),
                    subtitle = stringResource(R.string.keychain_ssh_import_clipboard_note),
                    onClick = onPickClipboard,
                    enabled = !busy,
                    modifier = Modifier.testTag("ssh_import_clipboard"),
                )
                KeychainSheetError(error)
                InfinityPillRow(Modifier.fillMaxWidth()) {
                    InfinityPillButton(text = stringResource(R.string.common_cancel), onClick = onCancel, enabled = !busy)
                }
            }
        } else {
            Column(
                modifier = Modifier.animateContentSize(InfinityMotion.contentSizeSpec()),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (form.source == SshKeyImportSource.FILE) Icons.Rounded.UploadFile else Icons.Rounded.ContentPaste,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        form.fileName ?: stringResource(R.string.keychain_ssh_import_clipboard_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                KeychainSheetField(form.name, { onChange(form.copy(name = it)) }, stringResource(R.string.keychain_field_name), "ssh_import_name", enabled = !busy)
                KeychainSheetField(
                    form.passphrase, { onChange(form.copy(passphrase = it)) },
                    stringResource(R.string.keychain_ssh_passphrase), "ssh_import_passphrase", secret = true, enabled = !busy,
                )
                KeychainSheetError(error)
                KeychainSheetButtons(
                    confirmText = stringResource(R.string.keychain_ssh_import),
                    confirmEnabled = form.name.isNotBlank(),
                    busy = busy,
                    onCancel = onCancel,
                    onConfirm = onImport,
                    confirmTag = "ssh_import_confirm",
                )
            }
        }
    }
}

// ── 导出 ──

internal enum class SshExportMode { KEEP, NEW }

internal data class SshKeyExportForm(val mode: SshExportMode = SshExportMode.KEEP, val passphrase: String = "", val repeat: String = "") {
    fun needsNew(item: KeychainItem) = !item.hasPassphrase || mode == SshExportMode.NEW
    fun canExport(item: KeychainItem) = !needsNew(item) || (passphrase.length >= 8 && passphrase == repeat)
}

@Composable
internal fun SshKeyExportContent(
    item: KeychainItem,
    form: SshKeyExportForm,
    busy: Boolean,
    error: String?,
    onChange: (SshKeyExportForm) -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier.animateContentSize(InfinityMotion.contentSizeSpec()),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Text(
            stringResource(R.string.keychain_ssh_export_format),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item.hasPassphrase) {
            InfinitySegmentedControl(
                segments = listOf(
                    InfinitySegment(SshExportMode.KEEP, stringResource(R.string.keychain_ssh_export_keep)),
                    InfinitySegment(SshExportMode.NEW, stringResource(R.string.keychain_ssh_export_new)),
                ),
                selected = form.mode,
                onSelect = { onChange(form.copy(mode = it)) },
                enabled = !busy,
                fillWidth = true,
            )
        } else {
            Text(
                stringResource(R.string.keychain_ssh_export_requires),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.infinityDomainColors.warning,
            )
        }
        AnimatedVisibility(
            visible = form.needsNew(item),
            enter = InfinityMotion.expandIn,
            exit = InfinityMotion.shrinkOut,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                val short = form.passphrase.isNotEmpty() && form.passphrase.length < 8
                val mismatch = form.repeat.isNotEmpty() && form.repeat != form.passphrase
                KeychainSheetField(
                    form.passphrase, { onChange(form.copy(passphrase = it)) },
                    stringResource(R.string.keychain_ssh_export_passphrase), "ssh_export_passphrase", secret = true,
                    supporting = stringResource(R.string.keychain_ssh_passphrase_rule).takeIf { !short },
                    error = stringResource(R.string.keychain_ssh_passphrase_rule).takeIf { short },
                    enabled = !busy,
                )
                KeychainSheetField(
                    form.repeat, { onChange(form.copy(repeat = it)) },
                    stringResource(R.string.keychain_ssh_repeat), "ssh_export_repeat", secret = true,
                    error = stringResource(R.string.keychain_ssh_passphrase_mismatch).takeIf { mismatch },
                    enabled = !busy,
                )
            }
        }
        KeychainSheetError(error)
        KeychainSheetButtons(
            confirmText = stringResource(R.string.keychain_ssh_export_button),
            confirmEnabled = form.canExport(item),
            busy = busy,
            onCancel = onCancel,
            onConfirm = onExport,
            confirmTag = "ssh_export_confirm",
        )
    }
}

// ── 修改口令 ──

internal data class SshKeyPassphraseForm(val passphrase: String = "", val repeat: String = "") {
    val canSave: Boolean get() = passphrase == repeat
}

@Composable
internal fun SshKeyPassphraseContent(
    form: SshKeyPassphraseForm,
    busy: Boolean,
    error: String?,
    onChange: (SshKeyPassphraseForm) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier.animateContentSize(InfinityMotion.contentSizeSpec()),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        KeychainSheetField(
            form.passphrase, { onChange(form.copy(passphrase = it)) },
            stringResource(R.string.keychain_ssh_new_passphrase), "ssh_pass_new", secret = true,
            supporting = stringResource(R.string.keychain_ssh_passphrase_empty_hint), enabled = !busy,
        )
        KeychainSheetField(
            form.repeat, { onChange(form.copy(repeat = it)) },
            stringResource(R.string.keychain_ssh_repeat), "ssh_pass_repeat", secret = true,
            error = stringResource(R.string.keychain_ssh_passphrase_mismatch).takeIf { form.repeat.isNotEmpty() && !form.canSave },
            enabled = !busy,
        )
        KeychainSheetError(error)
        KeychainSheetButtons(
            confirmText = stringResource(R.string.keychain_save),
            confirmEnabled = form.canSave,
            busy = busy,
            onCancel = onCancel,
            onConfirm = onSave,
            confirmTag = "ssh_pass_save",
        )
    }
}

// ── 重命名 ──

@Composable
internal fun SshKeyRenameContent(
    name: String,
    busy: Boolean,
    error: String?,
    onChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier.animateContentSize(InfinityMotion.contentSizeSpec()),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        KeychainSheetField(name, onChange, stringResource(R.string.keychain_field_name), "ssh_rename_name", enabled = !busy)
        KeychainSheetError(error)
        KeychainSheetButtons(
            confirmText = stringResource(R.string.keychain_save),
            confirmEnabled = name.isNotBlank(),
            busy = busy,
            onCancel = onCancel,
            onConfirm = onSave,
            confirmTag = "ssh_rename_save",
        )
    }
}

// ── 部署 ──

internal enum class SshDeployLoginKind { CURRENT_KEY, STORED_PASSWORD, INPUT_PASSWORD }

internal data class SshDeployCandidate(val host: SshHost, val kind: SshDeployLoginKind, val currentKeyName: String?)

/** 可以部署的主机：排除已在用这把密钥的主机。 */
internal fun sshDeployCandidates(entryRef: String, hosts: List<SshHost>, items: List<KeychainItem>): List<SshDeployCandidate> {
    val savedPasswords = items.filter { it.category == KeychainCategory.SSH_PASSWORD }.mapTo(mutableSetOf()) { it.ref }
    val keyNames = items.filter { it.category == KeychainCategory.SSH_KEY }.associate { it.ref to it.name }
    return hosts.filterNot { it.authType == SshAuthType.KEY && it.keyEntryId == entryRef }.map { host ->
        when {
            host.authType == SshAuthType.KEY -> SshDeployCandidate(host, SshDeployLoginKind.CURRENT_KEY, host.keyEntryId?.let(keyNames::get))
            "ssh-password:${host.id}" in savedPasswords -> SshDeployCandidate(host, SshDeployLoginKind.STORED_PASSWORD, null)
            else -> SshDeployCandidate(host, SshDeployLoginKind.INPUT_PASSWORD, null)
        }
    }
}

internal data class SshDeployForm(
    val selected: Set<String> = emptySet(),
    val passwords: Map<String, String> = emptyMap(),
    val switchToKey: Boolean = true,
    val dropPassword: Boolean = true,
) {
    fun canRun(candidates: List<SshDeployCandidate>): Boolean = selected.isNotEmpty() && candidates.all { candidate ->
        candidate.host.id !in selected || candidate.kind != SshDeployLoginKind.INPUT_PASSWORD || passwords[candidate.host.id].orEmpty().isNotEmpty()
    }
}

@Composable
internal fun SshDeployContent(
    candidates: List<SshDeployCandidate>,
    form: SshDeployForm,
    run: SshDeployRun?,
    onChange: (SshDeployForm) -> Unit,
    onCancel: () -> Unit,
    onRun: () -> Unit,
    onFinish: () -> Unit,
) {
    val lastRun = rememberLastNonNull(run)
    AnimatedContent(
        targetState = run != null,
        transitionSpec = { InfinityMotion.fadeThrough() },
        label = "ssh_deploy_stage",
    ) { running ->
        if (running && lastRun != null) {
            SshDeployProgress(lastRun, onFinish)
        } else {
            Column(
                modifier = Modifier.animateContentSize(InfinityMotion.contentSizeSpec()),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            ) {
                Text(
                    stringResource(R.string.keychain_ssh_deploy_target),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.promenar.infinity.ui.theme.InfinityMonospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (candidates.isEmpty()) {
                    Text(
                        stringResource(R.string.keychain_ssh_deploy_no_hosts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = InfinitySpacing.Large),
                    )
                }
                candidates.forEach { candidate ->
                    val id = candidate.host.id
                    val checked = id in form.selected
                    InfinitySettingsItem(
                        title = candidate.host.alias,
                        subtitle = when (candidate.kind) {
                            SshDeployLoginKind.CURRENT_KEY -> candidate.currentKeyName?.let { stringResource(R.string.keychain_ssh_deploy_via_key, it) }
                                ?: stringResource(R.string.keychain_ssh_deploy_via_current_key)
                            SshDeployLoginKind.STORED_PASSWORD -> stringResource(R.string.keychain_ssh_deploy_via_saved)
                            SshDeployLoginKind.INPUT_PASSWORD -> stringResource(R.string.keychain_ssh_deploy_via_input)
                        },
                        onClick = {
                            onChange(form.copy(selected = if (checked) form.selected - id else form.selected + id))
                        },
                        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                        horizontalPadding = 0.dp,
                        modifier = Modifier.testTag("ssh_deploy_host_${candidate.host.alias}"),
                    )
                    AnimatedVisibility(
                        visible = checked && candidate.kind == SshDeployLoginKind.INPUT_PASSWORD,
                        enter = InfinityMotion.expandIn,
                        exit = InfinityMotion.shrinkOut,
                    ) {
                        KeychainSheetField(
                            form.passwords[id].orEmpty(),
                            { onChange(form.copy(passwords = form.passwords + (id to it))) },
                            stringResource(R.string.keychain_ssh_deploy_password, candidate.host.alias),
                            "ssh_deploy_password_${candidate.host.alias}",
                            secret = true,
                        )
                    }
                }
                if (candidates.isNotEmpty()) {
                    SettingsToggle(
                        title = stringResource(R.string.keychain_ssh_deploy_switch),
                        checked = form.switchToKey,
                        onCheckedChange = { onChange(form.copy(switchToKey = it)) },
                        modifier = Modifier.testTag("ssh_deploy_switch"),
                    )
                    AnimatedVisibility(
                        visible = form.switchToKey,
                        enter = InfinityMotion.expandIn,
                        exit = InfinityMotion.shrinkOut,
                    ) {
                        SettingsToggle(
                            title = stringResource(R.string.keychain_ssh_deploy_drop),
                            checked = form.dropPassword,
                            onCheckedChange = { onChange(form.copy(dropPassword = it)) },
                            modifier = Modifier.testTag("ssh_deploy_drop"),
                        )
                    }
                }
                KeychainSheetButtons(
                    confirmText = pluralStringResource(R.plurals.keychain_ssh_deploy_run, form.selected.size, form.selected.size),
                    confirmEnabled = form.canRun(candidates),
                    busy = false,
                    onCancel = onCancel,
                    onConfirm = onRun,
                    confirmTag = "ssh_deploy_run",
                )
            }
        }
    }
}

@Composable
private fun SshDeployProgress(run: SshDeployRun, onFinish: () -> Unit) {
    val failed = run.hosts.any { it.stage == DeployStage.FAILED }
    Text(
        stringResource(
            when {
                !run.finished -> R.string.keychain_ssh_deploy_running
                failed -> R.string.keychain_ssh_deploy_partial
                else -> R.string.keychain_ssh_deploy_done
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = if (run.finished && failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("ssh_deploy_status"),
    )
    run.hosts.forEach { host ->
        InfinitySettingsRowLayout(
            title = host.alias,
            subtitle = when (host.stage) {
                null -> stringResource(R.string.keychain_ssh_stage_waiting)
                DeployStage.CONNECTING -> stringResource(R.string.keychain_ssh_stage_connecting)
                DeployStage.WRITING_AUTHORIZED_KEYS -> stringResource(R.string.keychain_ssh_stage_writing)
                DeployStage.VERIFYING_KEY -> stringResource(R.string.keychain_ssh_stage_verifying)
                DeployStage.COMPLETED -> stringResource(R.string.keychain_ssh_stage_completed)
                DeployStage.FAILED -> sshErrorText(host.errorCode ?: "DEPLOY_FAILED")
            },
            subtitleMaxLines = 2,
            horizontalPadding = 0.dp,
            leadingContent = {
                when (host.stage) {
                    DeployStage.COMPLETED -> Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.infinityDomainColors.success)
                    DeployStage.FAILED -> Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error)
                    null -> Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            },
            modifier = Modifier.testTag("ssh_deploy_progress_${host.alias}"),
        )
    }
    if (run.finished && run.options.switchToKey && run.succeeded.isNotEmpty()) {
        Text(
            stringResource(if (run.options.deleteStoredPassword) R.string.keychain_ssh_deploy_switched_dropped else R.string.keychain_ssh_deploy_switched),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    InfinityPillRow(Modifier.fillMaxWidth()) {
        InfinityPillButton(
            text = stringResource(R.string.keychain_ssh_deploy_finish),
            onClick = onFinish,
            enabled = run.finished,
            tone = InfinityPillTone.PRIMARY,
            modifier = Modifier.testTag("ssh_deploy_finish"),
        )
    }
}

// ── 主机选钥 ──

@Composable
internal fun SshKeyPickerContent(
    keys: List<KeychainItem>,
    hosts: List<SshHost>,
    selected: String?,
    onPick: (KeychainItem) -> Unit,
    onGenerate: () -> Unit,
    onImport: () -> Unit,
) {
    Column {
        keys.forEach { key ->
            InfinitySettingsItem(
                title = key.name,
                subtitleSegments = sshKeySubtitleSegments(key, hostsUsingKey(hosts, key.ref).size),
                onClick = { onPick(key) },
                leadingContent = { RadioButton(selected = key.ref == selected, onClick = null) },
                horizontalPadding = 0.dp,
                modifier = Modifier.testTag("ssh_key_pick_${key.ref}"),
            )
        }
        Text(
            stringResource(R.string.keychain_ssh_pick_new),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = InfinitySpacing.Large, bottom = InfinitySpacing.Small),
        )
        InfinitySettingsItem(
            icon = Icons.Rounded.VpnKey,
            title = stringResource(R.string.keychain_ssh_pick_generate),
            onClick = onGenerate,
            horizontalPadding = 0.dp,
            modifier = Modifier.testTag("ssh_key_pick_generate"),
        )
        InfinitySettingsItem(
            icon = Icons.Rounded.UploadFile,
            title = stringResource(R.string.keychain_ssh_pick_import),
            onClick = onImport,
            horizontalPadding = 0.dp,
            modifier = Modifier.testTag("ssh_key_pick_import"),
        )
    }
}

/** 主机行图标：部署面板与密钥页的主机列表共用。 */
internal val SshHostIcon = Icons.Rounded.Terminal
