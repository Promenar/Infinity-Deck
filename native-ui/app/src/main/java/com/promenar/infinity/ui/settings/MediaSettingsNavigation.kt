package com.promenar.infinity.ui.settings

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.model.effectiveMediaSettings
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.navigation.SettingsNavigator
import com.promenar.infinity.navigation.composablePage
import java.util.UUID

/** 制作面板与设置首页通过此组路由进入 ComfyUI 与媒体明细页面。 */
object MediaSettingsRoutes {
    const val MODELS = "media_models"
    const val MODEL = "media_model/{modelId}"
    const val COMFY_HUB = NavDestinations.COMFY_HUB
    const val HOST = "comfy_host/{hostId}"
    const val HOST_FORM = "comfy_host_form?hostId={hostId}"
    const val TEMPLATE = "comfy_template/{templateId}"
    const val WORKFLOW_EDITOR = "comfy_workflow_editor?hostId={hostId}&workflowId={workflowId}"

    fun model(id: String) = "media_model/${Uri.encode(id)}"
    fun host(id: String) = "comfy_host/${Uri.encode(id)}"
    fun hostForm(id: String? = null) = if (id == null) "comfy_host_form" else "comfy_host_form?hostId=${Uri.encode(id)}"
    fun template(id: String) = "comfy_template/${Uri.encode(id)}"
    fun workflowEditor(hostId: String, workflowId: String? = null): String =
        if (workflowId == null) "comfy_workflow_editor?hostId=${Uri.encode(hostId)}"
        else "comfy_workflow_editor?hostId=${Uri.encode(hostId)}&workflowId=${Uri.encode(workflowId)}"
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
            MediaModelsScreenContent(
                state,
                onBack = { navigator.back() },
                onMoveUp = vm::moveChannelUp,
                onOpen = { row ->
                    row.modelId?.let { navigator.open(MediaSettingsRoutes.model(it)) }
                        ?: row.templateId?.let { navigator.open(MediaSettingsRoutes.template(it)) }
                },
                onProviders = { navigator.open(NavDestinations.PROVIDER_LIST) },
                onRetry = vm::reload,
            )
        }
    }
    composablePage(MediaSettingsRoutes.COMFY_HUB) { entry ->
        navigator.Page(entry) {
            val vm = mediaSettingsViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            LifecycleResumeEffect(vm) {
                vm.checkConnections()
                onPauseOrDispose { }
            }
            ComfyHubScreenContent(
                state = state,
                onBack = { navigator.back() },
                onOpenHost = { hostId -> navigator.open(MediaSettingsRoutes.host(hostId)) },
                onAddHost = { navigator.open(MediaSettingsRoutes.hostForm()) },
                onCheckAll = vm::checkConnections,
                onRetry = vm::reload,
            )
        }
    }
    composablePage(MediaSettingsRoutes.MODEL, arguments = listOf(navArgument("modelId") { type = NavType.StringType })) { entry ->
        navigator.Page(entry) {
            val vm = mediaSettingsViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val id = entry.arguments?.getString("modelId").orEmpty()
            val model = state.snapshot.models.firstOrNull { it.id == id && it.effectiveMediaSettings() != null }
            if (model == null) MediaMissingScreen(state, { navigator.back() }, vm::reload) else {
                MediaModelDetailScreenContent(
                    model,
                    state,
                    onBack = { navigator.back() },
                    onMode = { vm.setReferenceMode(id, it) },
                    onReferenceUp = { vm.moveReferenceUp(id, it) },
                    onSaveTariff = { draft, saved -> vm.saveTariff(id, draft, saved) },
                    onRetry = vm::reload,
                )
            }
        }
    }
    composablePage(MediaSettingsRoutes.HOST, arguments = listOf(navArgument("hostId") { type = NavType.StringType })) { entry ->
        navigator.Page(entry) {
            val vm = mediaSettingsViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val id = entry.arguments?.getString("hostId").orEmpty()
            val host = state.snapshot.hosts.firstOrNull { it.id == id }
            if (host == null) MediaMissingScreen(state, { navigator.back() }, vm::reload) else {
                LifecycleResumeEffect(host) {
                    vm.checkConnections()
                    onPauseOrDispose { }
                }
                ComfyHostScreenContent(
                    host = host,
                    state = state,
                    onBack = { navigator.back() },
                    onEdit = { navigator.open(MediaSettingsRoutes.hostForm(id)) },
                    onDelete = { vm.removeHost(id) { navigator.back() } },
                    onOpenTemplate = { navigator.open(MediaSettingsRoutes.template(it)) },
                    onOpenWorkflowEditor = { wfId -> navigator.open(MediaSettingsRoutes.workflowEditor(id, wfId)) },
                    onNewWorkflow = { navigator.open(MediaSettingsRoutes.workflowEditor(id)) },
                    onPreflightWorkflow = vm::preflightWorkflow,
                    onDeleteWorkflow = { wfId -> vm.deleteWorkflow(wfId) },
                    onSaveSubmitter = { scriptId, fileName, displayName, description, sourceKind, protocol, sourceText, supportFiles, runDryRun, onDone ->
                        vm.saveSubmitter(
                            hostId = id,
                            scriptId = scriptId,
                            fileName = fileName,
                            displayName = displayName,
                            description = description,
                            sourceKind = sourceKind,
                            protocol = protocol,
                            sourceText = sourceText,
                            supportFiles = supportFiles,
                            runDryRun = runDryRun,
                            onSaved = onDone,
                        )
                    },
                    onDryRunSubmitter = { scriptId -> vm.dryRunSubmitter(id, scriptId) },
                    onDryRunAllSubmitters = { vm.dryRunAllSubmitters(id) },
                    onDeleteSubmitter = { scriptId -> vm.deleteSubmitter(id, scriptId) },
                    onReadSubmitterSource = { scriptId -> vm.readSubmitterSource(id, scriptId) },
                    onPreflight = { vm.preflightHost(id) },
                    onCheck = vm::checkConnections,
                    onRetry = vm::reload,
                )
            }
        }
    }
    composablePage(
        MediaSettingsRoutes.WORKFLOW_EDITOR,
        arguments = listOf(
            navArgument("hostId") { type = NavType.StringType },
            navArgument("workflowId") { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) { entry ->
        navigator.Page(entry) {
            val vm = mediaSettingsViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val hostId = entry.arguments?.getString("hostId").orEmpty()
            val wfId = entry.arguments?.getString("workflowId")
            val host = state.snapshot.hosts.firstOrNull { it.id == hostId }
            val existing = wfId?.let { target -> state.snapshot.workflows.firstOrNull { it.workflow.workflowId == target }?.workflow }
            if (host == null || (wfId != null && state.loaded && existing == null)) {
                MediaMissingScreen(state, { navigator.back() }, vm::reload)
            } else {
                ComfyWorkflowEditorScreenContent(
                    host = host,
                    existingWorkflow = existing,
                    submitters = state.snapshot.submitters,
                    state = state,
                    onBack = { navigator.back() },
                    onSave = { wf -> vm.saveWorkflow(wf) { navigator.back() } },
                    onRetry = vm::reload,
                )
            }
        }
    }
    composablePage(
        MediaSettingsRoutes.HOST_FORM,
        arguments = listOf(navArgument("hostId") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { entry ->
        navigator.Page(entry) {
            val vm = mediaSettingsViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val id = entry.arguments?.getString("hostId")
            val newId = remember { UUID.randomUUID().toString() }
            val host = state.snapshot.hosts.firstOrNull { it.id == id }
            if (!state.loaded || (id != null && host == null)) MediaMissingScreen(state, { navigator.back() }, vm::reload) else {
                ComfyHostFormScreenContent(
                    host,
                    id != null && id in state.snapshot.credentialHosts,
                    state,
                    onBack = { navigator.back() },
                    onSave = { draft, credential ->
                        vm.saveHost(draft.toHost(id ?: newId, host), credential) { navigator.back() }
                    },
                    onRetry = vm::reload,
                )
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
                ComfyTemplateScreenContent(
                    template,
                    state,
                    onBack = { navigator.back() },
                    onLock = { vm.lockTemplate(id, it) },
                    onPreflight = { vm.preflight(id) },
                    onRetry = vm::reload,
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
