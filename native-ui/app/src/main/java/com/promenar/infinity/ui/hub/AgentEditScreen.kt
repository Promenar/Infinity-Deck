package com.promenar.infinity.ui.hub

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.data.agent.PresetAgents
import com.promenar.infinity.data.model.catalog.ModelCatalogRuntime
import com.promenar.infinity.ui.avatar.rememberAvatarCropLauncher
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.settings.SettingsViewModel
import com.promenar.infinity.domain.avatar.AgentAvatarCodec
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditScreen(
    agentId: String,
    onNavigateBack: () -> Unit,
    onNavigateToSkills: () -> Unit = {},
    onNavigateToKnowledgeSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: AgentEditViewModel = viewModel(
        factory = AgentEditViewModel.factory(context.applicationContext as android.app.Application)
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(context.applicationContext as android.app.Application)
    )

    val name by viewModel.name.collectAsState()
    val description by viewModel.description.collectAsState()
    val systemPrompt by viewModel.systemPrompt.collectAsState()
    val selectedColor by viewModel.selectedColor.collectAsState()
    val selectedIcon by viewModel.selectedIcon.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val avatarPath by viewModel.avatarPath.collectAsState()
    val defaultWorkspaceId by viewModel.defaultWorkspaceId.collectAsState()
    val defaultWorkspace by viewModel.defaultWorkspace.collectAsState()
    val memoryPolicy by viewModel.memoryPolicy.collectAsState()
    val libraryCollections by androidx.compose.runtime.produceState(emptyList<com.promenar.infinity.data.rag.KgCollectionSummary>()) {
        val app = context.applicationContext as? com.promenar.infinity.InfinityApplication ?: return@produceState
        value = runCatching { com.promenar.infinity.data.rag.loadLibraryCollections(app.database) }.getOrDefault(emptyList())
    }
    val subagentModels by viewModel.subagentModels.collectAsState()
    val agentSampling by viewModel.sampling.collectAsState()
    val agentReasoningEffort by viewModel.reasoningEffort.collectAsState()
    val baseSchemeId by viewModel.baseSchemeId.collectAsState()
    val parameterSchemes by viewModel.parameterSchemes.collectAsState()
    val providers by settingsViewModel.providers.collectAsState()
    val executionMode by viewModel.executionMode.collectAsState()
    val toolsEnabledByDefault by viewModel.toolsEnabledByDefault.collectAsState()
    val retrievalConfig by viewModel.retrievalConfig.collectAsState()
    val mcpServerIds by viewModel.mcpServerIds.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val sessionCount by viewModel.sessionCount.collectAsState()
    val conflict by viewModel.conflict.collectAsState()
    val promptConflict by viewModel.promptConflict.collectAsState()
    val refreshedFields by viewModel.refreshedFields.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val promptRevisions by viewModel.promptRevisions.collectAsState()
    val app = context.applicationContext as? com.promenar.infinity.InfinityApplication
    val mcpSettings = app?.mcpConnectionSettings
    val mcpConnectionRevision = mcpSettings?.revision?.collectAsStateWithLifecycle()?.value
    val mcpRows = remember(mcpServers, mcpServerIds, mcpConnectionRevision) {
        agentMcpRows(mcpServers, mcpServerIds) { server ->
            server.enabled && (mcpSettings?.isAllowed(server.id, server.type, server.url)
                ?: com.promenar.infinity.data.remote.mcp.McpEndpointPolicy.isAllowed(server.type, server.url))
        }
    }
    // 技能摘要只扫描用户级技能：智能体没有会话工作区，项目技能随新会话的工作区决定。
    val skillCatalog: com.promenar.infinity.ui.skills.SkillCatalogViewModel = viewModel(
        factory = com.promenar.infinity.ui.skills.SkillCatalogViewModel.factory(context.applicationContext as android.app.Application)
    )
    val skillsState by skillCatalog.state.collectAsState()
    LaunchedEffect(skillCatalog) { skillCatalog.bind(null) }
    val enabledUserSkills = com.promenar.infinity.ui.chat.sessionSkillsSummary(skillsState)?.enabledUserSkills

    val allModels by settingsViewModel.providerModels.collectAsState()
    val resolver = ModelCatalogRuntime.resolver
    val resolvedModels = remember(allModels, resolver) {
        allModels.map { it.toModelSelectionUiModel(resolver) }
    }
    val modelItems = remember(allModels, resolvedModels) {
        resolvedModels.filter { resolved ->
            allModels.firstOrNull { it.id == resolved.selectionId }?.enabled == true
        }
    }
    val subagentModelItems = remember(modelItems) { modelItems.subagentCapable() }
    val currentModelDisplayName = remember(resolvedModels, selectedModel) {
        if (selectedModel.isBlank()) ""
        else resolvedModels.firstOrNull { it.selectionId == selectedModel }?.displayName ?: selectedModel
    }

    val localizedPresetName = if (PresetAgents.isPreset(agentId)) {
        stringResource(PresetAgents.nameRes(agentId))
    } else null
    val localizedPresetDescription = if (PresetAgents.isPreset(agentId)) {
        stringResource(PresetAgents.descRes(agentId))
    } else null

    var showSystemPromptEditor by remember { mutableStateOf(false) }
    // 编辑器打开时的内容；编辑期间智能体改了提示词不会替换草稿，改由提示条处理。
    var promptEditorText by remember { mutableStateOf("") }
    var showPromptHistory by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    var showDefaultParams by remember { mutableStateOf(false) }

    val modelProfile = remember(selectedModel, providers, allModels) {
        com.promenar.infinity.ui.chat.sessionModelParameterProfile(selectedModel, providers, allModels)
    }
    val reasoningControl = modelProfile?.reasoning?.let {
        com.promenar.infinity.ui.chat.ChatReasoningControl(it, com.promenar.infinity.data.model.params.ReasoningEffort.fromWireName(agentReasoningEffort))
    }
    val samplingEditor = agentSamplingEditorState(
        sampling = agentSampling,
        baseSchemeId = baseSchemeId,
        layout = com.promenar.infinity.ui.common.params.samplingParamLayout(modelProfile, reasoningControl?.stored),
        schemes = parameterSchemes,
    )
    val samplingActions = remember(viewModel, samplingEditor) {
        com.promenar.infinity.ui.common.params.SamplingEditorActions(
            onApplyModelDefault = viewModel::applyModelDefaultSampling,
            onApplyScheme = viewModel::applyParameterScheme,
            onParamChange = viewModel::setSamplingParam,
            onSaveScheme = { name -> viewModel.saveParameterScheme(name, samplingEditor.savableValues) },
            onOverwriteScheme = { scheme -> viewModel.overwriteParameterScheme(scheme, samplingEditor.savableValues) },
            onRenameScheme = viewModel::renameParameterScheme,
            onDeleteScheme = viewModel::deleteParameterScheme,
        )
    }

    val defaultPrimary = MaterialTheme.colorScheme.primary
    val parsedColor = remember(selectedColor, defaultPrimary) { parseAgentColor(selectedColor) ?: defaultPrimary }
    val avatarSpec = remember(selectedIcon) { AgentAvatarCodec.decode(selectedIcon) }
    val avatarCropLauncher = rememberAvatarCropLauncher(
        onCropped = viewModel::importAvatar,
        onFailure = viewModel::reportAvatarImportFailure,
    )

    LaunchedEffect(agentId) {
        viewModel.loadAgent(agentId, localizedPresetName, localizedPresetDescription)
    }

    UnifiedPromptEditor(
        show = showSystemPromptEditor,
        onDismiss = {
            showSystemPromptEditor = false
            viewModel.endPromptEdit()
        },
        onSave = viewModel::saveSystemPrompt,
        title = stringResource(R.string.agent_edit_prompt_label),
        initialText = promptEditorText,
        placeholder = stringResource(R.string.agent_edit_prompt_placeholder),
        mode = EditorMode.DIALOG,
        banner = { handle ->
            AgentPromptConflictBanner(
                conflict = promptConflict,
                notice = notice,
                handle = handle,
                onAdopt = viewModel::adoptRemotePrompt,
                onKeep = viewModel::keepMyPrompt,
                onNoticeShown = viewModel::consumeNotice,
            )
        },
    )

    AgentPromptHistoryDialog(
        show = showPromptHistory,
        revisions = promptRevisions,
        currentPrompt = systemPrompt,
        onRestore = { viewModel.restorePromptRevision(it.id) },
        onDismiss = { showPromptHistory = false },
    )

    ConfirmDialog(
        show = showDeleteConfirm,
        onDismiss = { showDeleteConfirm = false },
        onConfirm = {
            viewModel.deleteAgent(agentId) {
                showDeleteConfirm = false
                onNavigateBack()
            }
        },
        title = stringResource(R.string.agent_edit_delete_title),
        description = agentDeleteMessage(sessionCount),
        confirmLabel = stringResource(R.string.agent_edit_delete_confirm),
        confirmColor = MaterialTheme.colorScheme.error,
        destructive = true
    )

    com.promenar.infinity.ui.common.InfinityBottomSheet(show = showDefaultParams, onDismiss = { showDefaultParams = false }) {
        AgentDefaultParamsContent(
            reasoning = reasoningControl,
            onReasoningChange = { viewModel.setReasoningEffort(it?.wireName) },
            editor = samplingEditor,
            actions = samplingActions,
            modelName = currentModelDisplayName.takeIf { selectedModel.isNotBlank() },
        )
    }

    if (showModelPicker) {
        ModelPicker(
            show = true,
            title = stringResource(R.string.common_model_picker_title),
            filterTag = "chat",
            currentModelId = selectedModel,
            models = modelItems,
            onDismiss = { showModelPicker = false },
            onSelect = { id, _ ->
                viewModel.setModel(id)
                showModelPicker = false
            }
        )
    }

    com.promenar.infinity.ui.workspace.WorkspacePickerSheet(
        show = showWorkspacePicker,
        onDismiss = { showWorkspacePicker = false },
        currentWorkspaceId = defaultWorkspaceId,
        onPicked = { picked ->
            showWorkspacePicker = false
            viewModel.setDefaultWorkspace(picked.id)
        },
        onPickPrivate = {
            showWorkspacePicker = false
            viewModel.setDefaultWorkspace(null)
        },
        privateTitle = stringResource(R.string.workspace_agent_default_none),
    )

    AgentAvatarPickerSheet(
        show = showIconPicker,
        agentId = agentId,
        agentName = name,
        initialIcon = selectedIcon,
        initialColor = selectedColor,
        photoUri = avatarPath,
        onDismiss = { showIconPicker = false },
        onConfirm = { icon, color ->
            showIconPicker = false
            // 先改颜色再提交图标：setIcon 会立即持久化，并清掉照片头像。
            viewModel.setColor(color)
            viewModel.setIcon(icon)
        },
        onPickPhoto = {
            showIconPicker = false
            avatarCropLauncher.launch()
        },
    )

    InfinitySettingsPageLayout(
        title = stringResource(R.string.agent_edit_title),
        onBack = onNavigateBack,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            saveError?.let { error ->
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = stringResource(
                                when (error) {
                                    AgentEditErrorCode.NOT_FOUND -> R.string.agent_edit_error_load
                                    AgentEditErrorCode.LOAD_FAILED -> R.string.agent_edit_error_load
                                    AgentEditErrorCode.SAVE_FAILED -> R.string.agent_edit_error_save
                                    AgentEditErrorCode.DELETE_FAILED -> R.string.agent_edit_error_delete
                                    AgentEditErrorCode.AVATAR_IMPORT_FAILED -> R.string.agent_edit_error_avatar
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        InfinityRetryButton(
                            onClick = viewModel::retryLastFailure,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            // 冲突处理与恢复后的说明；提示词编辑器打开时由编辑器内显示。
            notice?.takeUnless { showSystemPromptEditor }?.let { current ->
                item(key = "agent_edit_notice") {
                    AgentEditNoticeLine(current, viewModel::consumeNotice, Modifier.padding(bottom = 8.dp))
                }
            }
            conflict?.let { current ->
                item(key = "agent_edit_conflict") {
                    val localValue = stringResource(R.string.agent_edit_conflict_local_value)
                    AgentFieldConflictBanner(
                        conflict = current,
                        local = { field ->
                            when (field) {
                                com.promenar.infinity.domain.model.AgentField.NAME -> name
                                com.promenar.infinity.domain.model.AgentField.DESCRIPTION -> description
                                else -> localValue
                            }
                        },
                        onAdopt = viewModel::adoptRemoteConflict,
                        onKeep = viewModel::keepMineConflict,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            // ─── 1. Hero 角色舞台：大头像 + 柔和光晕 + 实时身份预览 ───
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // Ambient Radial Glow
                        Box(
                            modifier = Modifier
                                .size(108.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            parsedColor.copy(alpha = 0.35f),
                                            parsedColor.copy(alpha = 0.10f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )

                        // 主头像：点击打开头像选择器，照片也从选择器里选。
                        AgentAvatar(
                            spec = avatarSpec,
                            color = selectedColor,
                            seed = agentId,
                            name = name,
                            photoUri = avatarPath,
                            size = 80.dp,
                            onClick = { showIconPicker = true },
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = (-4).dp, y = (-4).dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = name.ifBlank { stringResource(R.string.agent_edit_placeholder_name) },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (name.isNotBlank()) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            }

            // 基本信息与外观：名称、描述与头像三行，头像的图标、生成样式、照片与颜色都在选择器里改。
            item {
                InfinitySettingsSection(title = stringResource(R.string.agent_edit_section_basic)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                agentRefreshHighlight(
                                    com.promenar.infinity.domain.model.AgentField.NAME in refreshedFields ||
                                        com.promenar.infinity.domain.model.AgentField.DESCRIPTION in refreshedFields,
                                ),
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        InCardInputField(
                            label = stringResource(R.string.agent_edit_label_name),
                            value = name,
                            onValueChange = viewModel::setName,
                            placeholder = stringResource(R.string.agent_edit_placeholder_name),
                            singleLine = true,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        )
                        InCardInputField(
                            label = stringResource(R.string.agent_edit_label_desc),
                            value = description,
                            onValueChange = viewModel::setDescription,
                            placeholder = stringResource(R.string.agent_edit_placeholder_desc),
                            singleLine = false,
                            maxLines = 3,
                        )
                    }
                    // 头像、图标与颜色合并为一个入口，行首直接展示当前头像。
                    InfinitySettingsItem(
                        title = stringResource(R.string.agent_avatar_row_title),
                        subtitle = agentAvatarSummary(avatarSpec, hasPhoto = avatarPath != null),
                        onClick = { showIconPicker = true },
                        showChevron = true,
                        modifier = Modifier.testTag(UiTags.AGENT_EDIT_AVATAR_ROW),
                        leadingContent = {
                            AgentAvatar(
                                spec = avatarSpec,
                                color = selectedColor,
                                seed = agentId,
                                name = name,
                                photoUri = avatarPath,
                                size = 24.dp,
                            )
                        },
                    )
                }
            }

            // 模型与参数：新会话沿用，会话内可再单独调整。
            item {
                InfinitySettingsSection(
                    title = stringResource(R.string.agent_edit_section_model),
                    footer = stringResource(R.string.agent_edit_model_params_footer),
                ) {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.SmartToy,
                        title = stringResource(R.string.agent_edit_current_model),
                        subtitle = currentModelDisplayName.ifBlank { stringResource(R.string.settings_not_set) },
                        onClick = { showModelPicker = true },
                        showChevron = true,
                        modifier = Modifier
                            .background(agentRefreshHighlight(com.promenar.infinity.domain.model.AgentField.MODEL_ID in refreshedFields))
                            .testTag("agent_edit_model"),
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Tune,
                        title = stringResource(R.string.agent_edit_default_params),
                        subtitle = agentDefaultParamsSummary(samplingEditor, reasoningControl),
                        onClick = { showDefaultParams = true },
                        showChevron = true,
                        modifier = Modifier.testTag("agent_edit_default_params"),
                    )
                }
            }

            // 系统提示词以短状态呈现，完整内容由编辑器承载。
            item {
                InfinitySettingsSection(title = stringResource(R.string.agent_edit_section_personality)) {
                    // 副标题预览提示词开头两行，完整内容在编辑器中查看。
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Psychology,
                        title = stringResource(R.string.agent_edit_prompt_label),
                        subtitle = systemPromptPreview(systemPrompt) ?: stringResource(R.string.agent_edit_prompt_not_set),
                        subtitleMaxLines = 2,
                        modifier = Modifier
                            .background(agentRefreshHighlight(com.promenar.infinity.domain.model.AgentField.SYSTEM_PROMPT in refreshedFields))
                            .testTag("agent_edit_prompt"),
                        onClick = {
                            promptEditorText = viewModel.beginPromptEdit()
                            showSystemPromptEditor = true
                        },
                        showChevron = true,
                    )
                    // 智能体修改、编辑页保存与恢复都会记一版，可对比并恢复。
                    InfinitySettingsItem(
                        icon = Icons.Rounded.History,
                        title = stringResource(R.string.agent_prompt_history_row),
                        subtitle = if (promptRevisions.isEmpty()) stringResource(R.string.agent_prompt_history_row_empty)
                            else pluralStringResource(R.plurals.agent_prompt_history_row_count, promptRevisions.size, promptRevisions.size),
                        onClick = { showPromptHistory = true },
                        showChevron = true,
                        modifier = Modifier.testTag("agent_edit_prompt_history"),
                    )
                }
            }

            item {
                AgentNewSessionDefaultsSection(
                    executionMode = executionMode,
                    workspaceId = defaultWorkspaceId,
                    workspace = defaultWorkspace,
                    enabledUserSkills = enabledUserSkills,
                    mcpRows = mcpRows,
                    toolsEnabledByDefault = toolsEnabledByDefault,
                    onToolsEnabledByDefaultChange = viewModel::setToolsEnabledByDefault,
                    onExecutionModeChange = viewModel::setExecutionMode,
                    onChooseWorkspace = { showWorkspacePicker = true },
                    onOpenSkills = onNavigateToSkills,
                    onMcpToggle = viewModel::setMcpServerEnabled,
                )
            }

            item {
                AgentSubagentModelSection(
                    policy = subagentModels,
                    available = subagentModelItems,
                    displayName = { id -> resolvedModels.firstOrNull { it.selectionId == id }?.displayName ?: id },
                    onPolicyChange = viewModel::updateSubagentModels,
                )
            }

            item {
                AgentKnowledgeMemorySection(
                    retrievalMode = retrievalConfig.retrievalMode,
                    fileAccess = retrievalConfig.knowledgeFileAccess,
                    policy = memoryPolicy,
                    onRetrievalModeChange = viewModel::setRetrievalMode,
                    onFileAccessChange = viewModel::setKnowledgeFileAccess,
                    onPolicyChange = viewModel::updateMemoryPolicy,
                )
            }

            item {
                AgentScopeSection(memoryPolicy, viewModel::updateMemoryPolicy, onNavigateToKnowledgeSettings, libraryCollections)
            }

            item {
                InfinitySettingsSection(title = stringResource(R.string.settings_retrieval_agent_actions_section)) {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.DeleteOutline,
                        title = stringResource(R.string.agent_edit_delete_btn),
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = {
                            viewModel.refreshSessionCount()
                            showDeleteConfirm = true
                        },
                        modifier = Modifier.testTag("agent_edit_delete"),
                    )
                }
            }
        }
    }
}

/** 删除确认写明将先关闭的会话数；没有会话时只提示无法撤销。首页删除共用此文案。 */
@Composable
internal fun agentDeleteMessage(sessionCount: Int): String =
    if (sessionCount > 0) pluralStringResource(R.plurals.agent_delete_message_sessions, sessionCount, sessionCount)
    else stringResource(R.string.agent_edit_delete_message)

@Composable
private fun InCardInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 3,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(2.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ).forInput(),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 系统提示词预览：去掉空行并合并多余空白，最多取 120 个字符；空白提示词返回 null。 */
internal fun systemPromptPreview(prompt: String): String? {
    val lines = prompt.lineSequence().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
    val text = lines.joinToString(" ").takeIf { it.isNotEmpty() } ?: return null
    return if (text.length > PROMPT_PREVIEW_LENGTH) text.take(PROMPT_PREVIEW_LENGTH).trimEnd() + "…" else text
}

private const val PROMPT_PREVIEW_LENGTH = 120

/** 智能体静默刷新某字段后的短暂高亮底色。 */
@Composable
private fun agentRefreshHighlight(active: Boolean): Color {
    val target = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent
    return androidx.compose.animation.animateColorAsState(target, label = "agent_refresh_highlight").value
}
