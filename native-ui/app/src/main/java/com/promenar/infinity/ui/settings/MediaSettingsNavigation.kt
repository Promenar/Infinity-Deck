package com.promenar.infinity.ui.settings

import androidx.compose.material.icons.rounded.ChatBubbleOutline
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.mediagen.comfy.ComfyHost
import com.promenar.infinity.data.model.effectiveMediaSettings
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.navigation.SettingsNavigator
import com.promenar.infinity.navigation.composablePage
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** 制作面板通过现有 onNavigateToSecondary 回调打开 [MODELS]。 */
object MediaSettingsRoutes {
    const val MODELS = "media_models"
    const val MODEL = "media_model/{modelId}"
    const val HOST = "comfy_host/{hostId}"
    const val HOST_FORM = "comfy_host_form?hostId={hostId}"
    const val TEMPLATE = "comfy_template/{templateId}"
    fun model(id: String) = "media_model/${Uri.encode(id)}"
    fun host(id: String) = "comfy_host/${Uri.encode(id)}"
    fun hostForm(id: String? = null) = if (id == null) "comfy_host_form" else "comfy_host_form?hostId=${Uri.encode(id)}"
    fun template(id: String) = "comfy_template/${Uri.encode(id)}"
}

@Composable
internal fun mediaSettingsViewModel(): MediaSettingsViewModel {
    val app = LocalContext.current.applicationContext as InfinityApplication
    return viewModel(factory = MediaSettingsViewModel.factory(app))
}

internal fun NavGraphBuilder.mediaSettingsDestinations(navigator: SettingsNavigator) {
    composablePage(MediaSettingsRoutes.MODELS) { entry ->
        navigator.Page(entry) {
            val vm = mediaSettingsViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            MediaModelsScreenContent(state, onBack = { navigator.back() }, onMoveUp = vm::moveChannelUp,
                onOpen = { row ->
                    row.modelId?.let { navigator.open(MediaSettingsRoutes.model(it)) }
                        ?: row.templateId?.let { navigator.open(MediaSettingsRoutes.template(it)) }
                }, onProviders = { navigator.open(NavDestinations.PROVIDER_LIST) }, onRetry = vm::reload)
        }
    }
    composablePage(MediaSettingsRoutes.MODEL, arguments = listOf(navArgument("modelId") { type = NavType.StringType })) { entry ->
        navigator.Page(entry) {
            val vm = mediaSettingsViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val id = entry.arguments?.getString("modelId").orEmpty()
            val model = state.snapshot.models.firstOrNull { it.id == id && it.effectiveMediaSettings() != null }
            if (model == null) MediaMissingScreen(state, { navigator.back() }, vm::reload) else {
                MediaModelDetailScreenContent(model, state, onBack = { navigator.back() },
                    onMode = { vm.setReferenceMode(id, it) }, onReferenceUp = { vm.moveReferenceUp(id, it) },
                    onSaveTariff = { draft, saved -> vm.saveTariff(id, draft, saved) }, onRetry = vm::reload)
            }
        }
    }
    composablePage(MediaSettingsRoutes.HOST, arguments = listOf(navArgument("hostId") { type = NavType.StringType })) { entry ->
        navigator.Page(entry) {
            val vm = mediaSettingsViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val id = entry.arguments?.getString("hostId").orEmpty()
            val host = state.snapshot.hosts.firstOrNull { it.id == id }
            var configure by remember { mutableStateOf(false) }
            if (host == null) MediaMissingScreen(state, { navigator.back() }, vm::reload) else {
                LifecycleResumeEffect(host) {
                    vm.checkConnections()
                    onPauseOrDispose { }
                }
                ComfyHostScreenContent(host, state, onBack = { navigator.back() },
                    onEdit = { navigator.open(MediaSettingsRoutes.hostForm(id)) },
                    onDelete = { vm.removeHost(id) { navigator.back() } },
                    onOpenTemplate = { navigator.open(MediaSettingsRoutes.template(it)) },
                    onPreflight = { vm.preflightHost(id) }, onCheck = vm::checkConnections,
                    onConfigure = { configure = true }, onRetry = vm::reload)
                if (configure) ComfyAgentHandoff(host, onDismiss = { configure = false }, onSession = {
                    configure = false
                    navigator.open(NavDestinations.chatHero(it))
                })
            }
        }
    }
    composablePage(MediaSettingsRoutes.HOST_FORM, arguments = listOf(navArgument("hostId") {
        type = NavType.StringType; nullable = true; defaultValue = null
    })) { entry ->
        navigator.Page(entry) {
            val vm = mediaSettingsViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val id = entry.arguments?.getString("hostId")
            val newId = remember { UUID.randomUUID().toString() }
            val host = state.snapshot.hosts.firstOrNull { it.id == id }
            if (!state.loaded || (id != null && host == null)) MediaMissingScreen(state, { navigator.back() }, vm::reload) else {
                ComfyHostFormScreenContent(host, id != null && id in state.snapshot.credentialHosts, state, onBack = { navigator.back() },
                    onSave = { draft, credential ->
                        vm.saveHost(draft.toHost(id ?: newId, host), credential) { navigator.back() }
                    }, onRetry = vm::reload)
            }
        }
    }
    composablePage(MediaSettingsRoutes.TEMPLATE, arguments = listOf(navArgument("templateId") { type = NavType.StringType })) { entry ->
        navigator.Page(entry) {
            val vm = mediaSettingsViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val id = entry.arguments?.getString("templateId").orEmpty()
            val template = state.snapshot.templates.firstOrNull { it.template.id == id }
            if (template == null) MediaMissingScreen(state, { navigator.back() }, vm::reload) else {
                ComfyTemplateScreenContent(template, state, onBack = { navigator.back() }, onUnlock = { vm.requestUnlock(id) },
                    onLock = { vm.lockTemplate(id, it) }, onPreflight = { vm.preflight(id) }, onRetry = vm::reload)
                if (state.unlock.pendingId != null) InfinityConfirmDialog(
                    title = stringResource(R.string.media_settings_unlock_title), message = stringResource(R.string.media_settings_unlock_message),
                    confirmText = stringResource(R.string.media_settings_unlock), cancelText = stringResource(R.string.media_settings_cancel),
                    onConfirm = vm::confirmUnlock, onCancel = vm::cancelUnlock, confirmEnabled = !state.busy,
                )
            }
        }
    }
}

@Composable
private fun MediaMissingScreen(state: MediaSettingsUiState, onBack: () -> Unit, onRetry: () -> Unit) {
    MediaSettingsPage(stringResource(R.string.media_settings_title), onBack) {
        MediaOperationState(state, onRetry)
        if (state.loaded) MediaNotice(stringResource(R.string.media_settings_missing))
    }
}

/** 复制和跳转都由用户触发，不自动发送消息或覆盖会话草稿。 */
@Composable
private fun ComfyAgentHandoff(host: ComfyHost, onDismiss: () -> Unit, onSession: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as InfinityApplication
    val instruction = stringResource(R.string.media_settings_instruction, host.name, host.id)
    var sessions by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            sessions = withContext(Dispatchers.IO) { app.sessionRepository.getAllConfigurations().sortedByDescending { it.updatedAt }.take(10).map { it.id to it.title } }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    fun copy() {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(host.name, instruction))
    }
    InfinityConfirmDialog(title = stringResource(R.string.media_settings_configure), message = "",
        confirmText = stringResource(R.string.media_settings_copy_instruction), cancelText = stringResource(R.string.media_settings_cancel),
        onConfirm = { copy() }, onCancel = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(instruction, style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.media_settings_sessions), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                if (failed) MediaNotice(stringResource(R.string.media_settings_failed), error = true)
                else if (sessions == null) MediaNotice(stringResource(R.string.media_settings_loading))
                else if (sessions!!.isEmpty()) MediaNotice(stringResource(R.string.media_settings_no_sessions))
                sessions.orEmpty().forEach { (id, title) ->
                    com.promenar.infinity.ui.common.InfinitySettingsItem(
                        icon = androidx.compose.material.icons.Icons.Rounded.ChatBubbleOutline, title = title,
                        onClick = { copy(); onSession(id) }, showChevron = true, horizontalPadding = 0.dp, titleMaxLines = 1,
                    )
                }
            }
        })
}
