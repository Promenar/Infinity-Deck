package com.promenar.infinity.ui.keychain

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.domain.keychain.DeployOptions
import com.promenar.infinity.domain.keychain.ImportSshKeyResult
import com.promenar.infinity.domain.keychain.KeychainCategory
import com.promenar.infinity.domain.keychain.KeychainDeleteResult
import com.promenar.infinity.domain.keychain.KeychainItem
import com.promenar.infinity.domain.keychain.KeychainOrigin
import com.promenar.infinity.domain.keychain.KeychainUsage
import com.promenar.infinity.ui.chat.SensitiveClipboard
import com.promenar.infinity.ui.chat.rememberSecretAuthenticator
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityPropertyRow
import com.promenar.infinity.ui.common.InfinityPropertySheet
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinitySnackbar
import com.promenar.infinity.ui.common.InfinitySnackbarData
import com.promenar.infinity.ui.common.SnackbarType
import com.promenar.infinity.ui.ssh.SshPrivateKeyInput
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── 页面内容（无状态，供截图） ──

internal data class KeychainSshKeyState(
    val item: KeychainItem,
    val hosts: List<SshHost> = emptyList(),
    val usage: List<KeychainUsage>? = null,
)

internal data class KeychainSshKeyActions(
    val onBack: () -> Unit = {},
    val onCopyPublicKey: () -> Unit = {},
    val onSharePublicKey: () -> Unit = {},
    val onDeploy: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onChangePassphrase: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

/** SSH 密钥页：公钥、属性、使用这把密钥的主机与部署入口、使用记录；菜单里导出、改口令、重命名与删除。 */
@Composable
internal fun KeychainSshKeyContent(state: KeychainSshKeyState, actions: KeychainSshKeyActions, modifier: Modifier = Modifier) {
    val item = state.item
    InfinitySettingsPageLayout(
        title = item.name,
        onBack = actions.onBack,
        modifier = modifier,
        actions = { SshKeyMenu(actions) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().testTag("keychain_ssh_key"), contentPadding = padding) {
            item(key = "public") { SshPublicKeyCard(item, actions) }
            item(key = "properties") { SshKeyProperties(item) }
            item(key = "hosts") { SshKeyHosts(state.hosts, actions) }
            item(key = "usage") { KeychainUsageSection(state.usage) }
        }
    }
}

@Composable
private fun SshKeyMenu(actions: KeychainSshKeyActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("keychain_detail_more")) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.keychain_more))
        }
        InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            InfinityMenuItem(
                text = { Text(stringResource(R.string.keychain_ssh_export)) },
                leadingIcon = { Icon(Icons.Rounded.FileUpload, contentDescription = null) },
                onClick = { open = false; actions.onExport() },
                modifier = Modifier.testTag("ssh_key_menu_export"),
            )
            InfinityMenuItem(
                text = { Text(stringResource(R.string.keychain_ssh_change_passphrase)) },
                leadingIcon = { Icon(Icons.Rounded.Password, contentDescription = null) },
                onClick = { open = false; actions.onChangePassphrase() },
                modifier = Modifier.testTag("ssh_key_menu_passphrase"),
            )
            InfinityMenuItem(
                text = { Text(stringResource(R.string.keychain_ssh_rename)) },
                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                onClick = { open = false; actions.onRename() },
                modifier = Modifier.testTag("ssh_key_menu_rename"),
            )
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

@Composable
private fun SshPublicKeyCard(item: KeychainItem, actions: KeychainSshKeyActions) {
    InfinitySettingsGroup {
        Column(Modifier.fillMaxWidth().padding(InfinitySpacing.Large), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
            Text(
                stringResource(R.string.keychain_ssh_public_key),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    item.publicKey.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("ssh_key_public"),
                )
            }
            InfinityPillRow {
                InfinityPillButton(
                    text = stringResource(R.string.keychain_copy_public_key),
                    icon = Icons.Rounded.ContentCopy,
                    onClick = actions.onCopyPublicKey,
                    compact = true,
                    modifier = Modifier.testTag("ssh_key_copy_public"),
                )
                InfinityPillButton(
                    text = stringResource(R.string.keychain_ssh_share),
                    icon = Icons.Rounded.Share,
                    onClick = actions.onSharePublicKey,
                    compact = true,
                    modifier = Modifier.testTag("ssh_key_share_public"),
                )
            }
        }
    }
}

@Composable
private fun SshKeyProperties(item: KeychainItem) {
    InfinitySettingsGroup {
        InfinityPropertySheet(Modifier.padding(vertical = InfinitySpacing.Small)) {
            keychainKeyTypeLabel(item.keyType)?.let { InfinityPropertyRow(stringResource(R.string.keychain_prop_key_type), it) }
            item.fingerprintSha256?.let { InfinityPropertyRow(stringResource(R.string.keychain_prop_fingerprint), it) }
            InfinityPropertyRow(
                stringResource(R.string.keychain_prop_passphrase),
                stringResource(if (item.hasPassphrase) R.string.keychain_yes else R.string.keychain_no),
            )
            item.origin?.let { InfinityPropertyRow(stringResource(R.string.keychain_prop_origin), keychainOriginLabel(it)) }
            if (item.updatedAt > 0) InfinityPropertyRow(stringResource(R.string.keychain_prop_updated), keychainDate(item.updatedAt))
        }
    }
}

@Composable
private fun SshKeyHosts(hosts: List<SshHost>, actions: KeychainSshKeyActions) {
    InfinitySettingsSection(title = stringResource(R.string.keychain_ssh_hosts_section)) {
        if (hosts.isEmpty()) {
            Text(
                stringResource(R.string.keychain_ssh_hosts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Large),
            )
        }
        hosts.forEach { host ->
            InfinitySettingsRowLayout(
                title = host.alias,
                subtitle = "${host.username}@${host.hostname}" + if (host.port != 22) ":${host.port}" else "",
                icon = SshHostIcon,
                subtitleIsIdentifier = true,
                modifier = Modifier.testTag("ssh_key_host_${host.alias}"),
            ) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.keychain_ssh_host_key_login),
                    tint = MaterialTheme.infinityDomainColors.success,
                )
            }
        }
        InfinityPillButton(
            text = stringResource(R.string.keychain_ssh_deploy),
            icon = Icons.Rounded.FileUpload,
            onClick = actions.onDeploy,
            tone = InfinityPillTone.PRIMARY,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Medium)
                .testTag("ssh_key_deploy"),
        )
    }
}

// ── 有状态页面 ──

private enum class SshKeySheet { EXPORT, PASSPHRASE, RENAME, DEPLOY }

/** 钥匙串详情里的 SSH 密钥条目。 */
@Composable
internal fun KeychainSshKeyEntry(item: KeychainItem, usage: List<KeychainUsage>?, viewModel: KeychainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val ssh: KeychainSshViewModel = viewModel(factory = KeychainSshViewModel.factory(application))
    val hosts by ssh.hosts.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var sheet by remember { mutableStateOf<SshKeySheet?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun notify(message: String) { scope.launch { snackbar.showSnackbar(message) } }

    val publicKeyCopied = stringResource(R.string.keychain_public_key_copied)
    val deletedMessage = stringResource(R.string.keychain_deleted, item.name)
    val inUseTemplate = stringResource(R.string.keychain_delete_in_use, "%s")
    val deleteFailed = stringResource(R.string.keychain_delete_failed)
    val keyHosts = hostsUsingKey(hosts, item.ref)

    Box(Modifier.fillMaxSize()) {
        KeychainSshKeyContent(
            state = KeychainSshKeyState(item, keyHosts, usage),
            actions = KeychainSshKeyActions(
                onBack = onBack,
                onCopyPublicKey = {
                    copyPublicKey(context, item)
                    notify(publicKeyCopied)
                },
                onSharePublicKey = { item.publicKey?.let { sharePublicKey(context, item.name, it) } },
                onDeploy = { sheet = SshKeySheet.DEPLOY },
                onExport = { sheet = SshKeySheet.EXPORT },
                onChangePassphrase = { sheet = SshKeySheet.PASSPHRASE },
                onRename = { sheet = SshKeySheet.RENAME },
                onDelete = { confirmDelete = true },
            ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { data ->
            InfinitySnackbar(InfinitySnackbarData(data.visuals.message, SnackbarType.SUCCESS))
        }
    }

    val close = { sheet = null }
    when (sheet) {
        SshKeySheet.EXPORT -> SshKeyExportSheet(item, ssh, onDismiss = close, onExported = { close(); notify(it) })
        SshKeySheet.PASSPHRASE -> SshKeyPassphraseSheet(item, ssh, onDismiss = close, onChanged = { close(); notify(it) })
        SshKeySheet.RENAME -> SshKeyRenameSheet(item, ssh, onDismiss = close)
        SshKeySheet.DEPLOY -> SshKeyDeploySheet(item, ssh, viewModel, onDismiss = close)
        null -> Unit
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
                scope.launch {
                    when (val result = viewModel.delete(item.ref)) {
                        KeychainDeleteResult.Deleted, KeychainDeleteResult.NotFound -> notify(deletedMessage)
                        is KeychainDeleteResult.InUse -> notify(inUseTemplate.replace("%s", result.hosts.joinToString("、") { it.alias }))
                        null -> notify(deleteFailed)
                    }
                }
            },
        )
    }
}

private fun copyPublicKey(context: Context, item: KeychainItem) {
    val key = item.publicKey ?: return
    val chars = key.toCharArray()
    try { SensitiveClipboard.copy(context, item.name, chars) } finally { chars.fill('\u0000') }
}

private fun sharePublicKey(context: Context, name: String, publicKey: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, name)
        putExtra(Intent.EXTRA_TEXT, publicKey)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ── 面板 ──

@Composable
private fun KeychainSheet(title: String, busy: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    InfinityBottomSheet(
        show = true,
        onDismiss = { if (!busy) onDismiss() },
        dismissEnabled = !busy,
        title = title,
        height = InfinitySheetHeight.Content,
        content = content,
    )
}

/** 生成 SSH 密钥；成功后复制公钥并交出新条目。 */
@Composable
internal fun SshKeyGenerateSheet(onDismiss: () -> Unit, onGenerated: (KeychainItem) -> Unit) {
    val context = LocalContext.current
    val ssh: KeychainSshViewModel = viewModel(factory = KeychainSshViewModel.factory(context.applicationContext as android.app.Application))
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(SshKeyGenerateForm()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { form = form.copy(passphrase = "") } }
    KeychainSheet(stringResource(R.string.keychain_ssh_generate_title), busy, onDismiss) {
        SshKeyGenerateContent(
            form = form,
            busy = busy,
            error = error,
            onChange = { form = it; error = null },
            onCancel = onDismiss,
            onGenerate = {
                busy = true
                val phrase = form.passphrase.toCharArray()
                form = form.copy(passphrase = "")
                scope.launch {
                    when (val result = ssh.generate(form.name, form.type, phrase)) {
                        is KeychainSshResult.Ok -> {
                            copyPublicKey(context, result.value)
                            onGenerated(result.value)
                        }
                        is KeychainSshResult.Failed -> error = result.code
                    }
                    busy = false
                }
            },
        )
    }
}

/** 从文件或剪贴板导入私钥；剪贴板来源在导入成功后清空。读入的私钥字节在面板关闭时清零。 */
@Composable
internal fun SshKeyImportSheet(onDismiss: () -> Unit, onImported: (KeychainItem, fromClipboard: Boolean) -> Unit) {
    val context = LocalContext.current
    val ssh: KeychainSshViewModel = viewModel(factory = KeychainSshViewModel.factory(context.applicationContext as android.app.Application))
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(SshKeyImportForm()) }
    var keyBytes by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val defaultName = stringResource(R.string.keychain_ssh_import_name_default)
    val passphraseRequired = stringResource(R.string.keychain_ssh_passphrase_required)
    val alreadyTemplate = stringResource(R.string.keychain_ssh_already_exists, "%s")
    DisposableEffect(Unit) { onDispose { keyBytes?.fill(0); keyBytes = null } }

    fun accept(source: SshKeyImportSource, fileName: String?, read: () -> ByteArray) {
        busy = true
        error = null
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { read() }
                keyBytes?.fill(0)
                keyBytes = bytes
                form = SshKeyImportForm(source, fileName, fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: defaultName)
            } catch (failure: Exception) {
                error = failure.sshCode()
            } finally { busy = false }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) accept(SshKeyImportSource.FILE, displayName(context, uri)) {
            context.contentResolver.openInputStream(uri)?.use(SshPrivateKeyInput::read)
                ?: throw com.promenar.infinity.ui.ssh.SshInputException("SSH_KEY_READ_FAILED")
        }
    }

    KeychainSheet(stringResource(R.string.keychain_ssh_import_title), busy, onDismiss) {
        SshKeyImportContent(
            form = form,
            busy = busy,
            error = error,
            onPickFile = { pickFile.launch(arrayOf("*/*")) },
            onPickClipboard = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
                if (text.isNullOrBlank()) error = "SSH_KEY_CLIPBOARD_EMPTY"
                else accept(SshKeyImportSource.CLIPBOARD, null) { SshPrivateKeyInput.read(text.toString().trim().byteInputStream(Charsets.UTF_8)) }
            },
            onChange = { form = it; error = null },
            onCancel = onDismiss,
            onImport = {
                val bytes = keyBytes?.copyOf() ?: return@SshKeyImportContent
                val phrase = form.passphrase.toCharArray()
                val source = form.source
                busy = true
                scope.launch {
                    val origin = if (source == SshKeyImportSource.CLIPBOARD) KeychainOrigin.CLIPBOARD_IMPORT else KeychainOrigin.FILE_IMPORT
                    when (val result = ssh.import(form.name, bytes, phrase, origin)) {
                        is KeychainSshResult.Failed -> error = result.code
                        is KeychainSshResult.Ok -> when (val imported = result.value) {
                            is ImportSshKeyResult.Imported -> {
                                if (source == SshKeyImportSource.CLIPBOARD) {
                                    context.getSystemService(ClipboardManager::class.java)?.clearPrimaryClip()
                                }
                                keyBytes?.fill(0)
                                keyBytes = null
                                form = form.copy(passphrase = "")
                                onImported(imported.item, source == SshKeyImportSource.CLIPBOARD)
                            }
                            is ImportSshKeyResult.AlreadyExists -> error = KEYCHAIN_TEXT_ERROR + alreadyTemplate.replace("%s", imported.item.name)
                            ImportSshKeyResult.PassphraseRequired -> error = KEYCHAIN_TEXT_ERROR + passphraseRequired
                            ImportSshKeyResult.WrongPassphrase -> error = "INVALID_KEY_PASSPHRASE"
                            is ImportSshKeyResult.InvalidFormat -> error = imported.code
                            ImportSshKeyResult.UnsupportedKeyType -> error = "UNSUPPORTED_USER_KEY"
                        }
                    }
                    busy = false
                }
            },
        )
    }
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

/** 导出 OpenSSH 私钥：先选保存位置，再验证身份，最后写入文件；失败时删除空文件。 */
@Composable
private fun SshKeyExportSheet(item: KeychainItem, ssh: KeychainSshViewModel, onDismiss: () -> Unit, onExported: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authenticator = rememberSecretAuthenticator()
    var form by remember { mutableStateOf(SshKeyExportForm()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val subtitle = stringResource(R.string.keychain_auth_export, item.name)
    val exportedTemplate = stringResource(R.string.keychain_ssh_exported, "%s")
    DisposableEffect(Unit) { onDispose { form = SshKeyExportForm() } }

    fun discard(uri: Uri) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = displayName(context, uri) ?: exportFileName(item)
        authenticator.authenticate(subtitle, onSuccess = {
            val phrase = if (form.needsNew(item)) form.passphrase.toCharArray() else null
            form = SshKeyExportForm(form.mode)
            busy = true
            scope.launch {
                val result = ssh.export(item.ref, phrase) { bytes ->
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                            ?: throw com.promenar.infinity.ui.ssh.SshInputException("SSH_KEY_READ_FAILED")
                    }
                }
                busy = false
                when (result) {
                    is KeychainSshResult.Ok -> onExported(exportedTemplate.replace("%s", fileName))
                    is KeychainSshResult.Failed -> { discard(uri); error = result.code }
                }
            }
        }, onError = { message -> discard(uri); error = KEYCHAIN_TEXT_ERROR + message })
    }

    KeychainSheet(stringResource(R.string.keychain_ssh_export_title), busy, onDismiss) {
        SshKeyExportContent(
            item = item,
            form = form,
            busy = busy,
            error = error,
            onChange = { form = it; error = null },
            onCancel = onDismiss,
            onExport = { createFile.launch(exportFileName(item)) },
        )
    }
}

/** 导出文件名：名称中的空白换成连字符，后接密钥类型。 */
internal fun exportFileName(item: KeychainItem): String {
    val base = item.name.trim().replace(Regex("[\\s/\\\\:*?\"<>|]+"), "-").ifEmpty { "id" }
    val type = when (item.keyType) {
        "ssh-ed25519" -> "ed25519"
        "ssh-rsa" -> "rsa"
        null -> "key"
        else -> item.keyType.substringAfterLast('-').lowercase()
    }
    return "${base}_$type"
}

@Composable
private fun SshKeyPassphraseSheet(item: KeychainItem, ssh: KeychainSshViewModel, onDismiss: () -> Unit, onChanged: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(SshKeyPassphraseForm()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val changed = stringResource(R.string.keychain_ssh_passphrase_changed)
    DisposableEffect(Unit) { onDispose { form = SshKeyPassphraseForm() } }
    KeychainSheet(stringResource(R.string.keychain_ssh_change_passphrase), busy, onDismiss) {
        SshKeyPassphraseContent(
            form = form,
            busy = busy,
            error = error,
            onChange = { form = it; error = null },
            onCancel = onDismiss,
            onSave = {
                val phrase = form.passphrase.toCharArray()
                form = SshKeyPassphraseForm()
                busy = true
                scope.launch {
                    when (val result = ssh.changePassphrase(item.ref, phrase)) {
                        is KeychainSshResult.Ok -> onChanged(changed)
                        is KeychainSshResult.Failed -> error = result.code
                    }
                    busy = false
                }
            },
        )
    }
}

@Composable
private fun SshKeyRenameSheet(item: KeychainItem, ssh: KeychainSshViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(item.name) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    KeychainSheet(stringResource(R.string.keychain_ssh_rename), busy, onDismiss) {
        SshKeyRenameContent(
            name = name,
            busy = busy,
            error = error,
            onChange = { name = it; error = null },
            onCancel = onDismiss,
            onSave = {
                busy = true
                scope.launch {
                    when (val result = ssh.rename(item, name)) {
                        is KeychainSshResult.Ok -> onDismiss()
                        is KeychainSshResult.Failed -> error = result.code
                    }
                    busy = false
                }
            },
        )
    }
}

/** 部署面板：选主机与登录方式后在 ViewModel 里运行；关闭面板不中断，重新打开继续显示进度。 */
@Composable
private fun SshKeyDeploySheet(item: KeychainItem, ssh: KeychainSshViewModel, keychain: KeychainViewModel, onDismiss: () -> Unit) {
    val hosts by ssh.hosts.collectAsState()
    val items by keychain.items.collectAsState()
    val run by ssh.deploy.collectAsState()
    var form by remember { mutableStateOf(SshDeployForm()) }
    DisposableEffect(Unit) { onDispose { form = form.copy(passwords = emptyMap()) } }
    val candidates = remember(hosts, items, item.ref) { sshDeployCandidates(item.ref, hosts, items.orEmpty()) }
    val current = run?.takeIf { it.entryRef == item.ref }
    InfinityBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.keychain_ssh_deploy_title, item.name),
        height = InfinitySheetHeight.Content,
        scrollContent = true,
    ) {
        SshDeployContent(
            candidates = candidates,
            form = form,
            run = current,
            onChange = { form = it },
            onCancel = onDismiss,
            onRun = {
                val logins = candidates.filter { it.host.id in form.selected }.associate { candidate ->
                    candidate.host to when (candidate.kind) {
                        SshDeployLoginKind.CURRENT_KEY -> SshDeployLogin.CurrentKey
                        SshDeployLoginKind.STORED_PASSWORD -> SshDeployLogin.StoredPassword
                        SshDeployLoginKind.INPUT_PASSWORD -> SshDeployLogin.OneTimePassword(form.passwords[candidate.host.id].orEmpty().toCharArray())
                    }
                }
                val options = DeployOptions(switchToKey = form.switchToKey, deleteStoredPassword = form.switchToKey && form.dropPassword)
                form = SshDeployForm()
                ssh.startDeploy(item.ref, logins, options)
            },
            onFinish = {
                ssh.dismissDeploy()
                onDismiss()
            },
        )
    }
}

/** SSH 密钥条目按名称排序，供主机选钥面板使用。 */
internal fun sshKeyItems(items: List<KeychainItem>?): List<KeychainItem> =
    items.orEmpty().filter { it.category == KeychainCategory.SSH_KEY }.sortedBy { it.name.lowercase() }
