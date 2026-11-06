package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.withRecordedUserEdits
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.ui.common.InfinityHeaderTitle
import com.promenar.infinity.ui.common.InfinityBackButton
import com.promenar.infinity.ui.common.InfinityChoiceChips
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityFieldRow
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors

/**
 * 模型编辑整页（与模型列表同屏切换）：身份、2×2 规格、类型单选、能力多选、会话请求头、连接测试与删除。
 * 修改即时保存；返回由宿主页的系统返回与预测性返回处理，本页左上角返回按钮调用 [onClose]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelEditorScreen(
    model: ModelInfo,
    testState: ModelTestState,
    onClose: () -> Unit,
    onUpdate: (ModelInfo) -> Unit,
    onTest: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteConfirmation by remember(model.id) { mutableStateOf(false) }
    var selectedType by remember(model.id) { mutableStateOf(model.type) }
    var editName by remember(model.id) { mutableStateOf(model.name) }
    var editRemoteModelId by remember(model.id) { mutableStateOf(model.remoteModelId) }
    var editContext by remember(model.id) { mutableStateOf(model.contextLength.toString()) }
    var editOutput by remember(model.id) { mutableStateOf(model.maxOutputTokens) }
    var sessionHeaderMode by remember(model.id) { mutableStateOf(model.sessionHeaderMode) }
    var activeCaps by remember(model.id) { mutableStateOf(model.capabilities.toSet()) }
    var editingLimit by remember(model.id) { mutableStateOf<ModelSpecField?>(null) }

    // 同步或其它入口改动了模型时，以最新快照为准重置本地编辑态。
    LaunchedEffect(
        model.id,
        model.type,
        model.name,
        model.remoteModelId,
        model.contextLength,
        model.maxOutputTokens,
        model.capabilities,
        model.sessionHeaderMode,
    ) {
        selectedType = model.type
        editName = model.name
        editRemoteModelId = model.remoteModelId
        editContext = model.contextLength.toString()
        editOutput = model.maxOutputTokens
        activeCaps = model.capabilities.toSet()
        sessionHeaderMode = model.sessionHeaderMode
    }

    LaunchedEffect(selectedType, editName, editRemoteModelId, editContext, editOutput, activeCaps, sessionHeaderMode) {
        val updated = model.copy(
            remoteModelId = editRemoteModelId.trim(),
            type = selectedType,
            name = editName,
            contextLength = editContext.toIntOrNull() ?: model.contextLength,
            maxOutputTokens = editOutput,
            capabilities = activeCaps.toList(),
            sessionHeaderMode = sessionHeaderMode,
        )
        if (updated != model) {
            onUpdate(updated.withRecordedUserEdits(model))
        }
    }

    val identityFooter = modelIdentityFooter(model)
    val contextTokens = editContext.toIntOrNull() ?: model.contextLength

    InfinitySettingsPageLayout(
        title = stringResource(R.string.provider_models_editor_title),
        modifier = modifier.testTag(UiTags.providerModelsEditorSheet(model.id)),
        header = {
            TopAppBar(
                title = {
                    InfinityHeaderTitle(
                        text = stringResource(R.string.provider_models_editor_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    InfinityBackButton(
                        onClick = onClose,
                        modifier = Modifier.testTag(UiTags.providerModelsEditorCloseAction(model.id)),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
    ) { contentPadding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .testTag(UiTags.providerModelsEditorList(model.id)),
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection),
                end = contentPadding.calculateEndPadding(layoutDirection),
                top = contentPadding.calculateTopPadding() + InfinitySpacing.Small,
                bottom = contentPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item("identity") {
                InfinitySettingsSection(
                    title = stringResource(R.string.model_editor_group_identity),
                    footer = identityFooter,
                    footerModifier = Modifier.testTag(UiTags.providerModelsEditorSource(model.id)),
                ) {
                    InfinityFieldRow(
                        label = stringResource(R.string.provider_models_field_display_name),
                        value = editName,
                        onValueChange = { editName = it },
                        fieldModifier = Modifier.testTag(UiTags.providerModelsNameField(model.id)),
                    )
                    InfinityGroupDivider()
                    InfinityFieldRow(
                        label = stringResource(R.string.provider_models_field_model_id),
                        value = editRemoteModelId,
                        onValueChange = { editRemoteModelId = it },
                        placeholder = stringResource(R.string.provider_models_placeholder_model_id),
                        monospace = true,
                    )
                }
            }
            item("specs") {
                val unknown = stringResource(R.string.model_spec_unknown)
                fun tokenCell(field: ModelSpecField, label: String, tokens: Int, onClick: (() -> Unit)?) = ModelSpecCell(
                    field = field,
                    label = label,
                    shortValue = formatTokenShort(tokens).ifEmpty { unknown },
                    exactValue = tokens.takeIf { it > 0 }?.let { formatTokenExact(it) },
                    source = modelSpecSource(model, field),
                    onClick = onClick,
                    tag = UiTags.providerModelsSpecCell(model.id, field.name),
                )
                val cells = listOf(
                    tokenCell(ModelSpecField.Context, stringResource(R.string.provider_models_context_label), contextTokens) {
                        editingLimit = ModelSpecField.Context
                    },
                    tokenCell(ModelSpecField.Input, stringResource(R.string.model_spec_input_label), model.inputTokens, null),
                    tokenCell(ModelSpecField.Output, stringResource(R.string.provider_models_editor_output_limit), editOutput) {
                        editingLimit = ModelSpecField.Output
                    },
                    ModelSpecCell(
                        field = ModelSpecField.KnowledgeCutoff,
                        label = stringResource(R.string.model_spec_cutoff_label),
                        shortValue = model.knowledgeCutoff?.takeIf { it.isNotBlank() } ?: unknown,
                        exactValue = null,
                        source = modelSpecSource(model, ModelSpecField.KnowledgeCutoff),
                        tag = UiTags.providerModelsSpecCell(model.id, ModelSpecField.KnowledgeCutoff.name),
                    ),
                )
                InfinitySettingsSection(
                    title = stringResource(R.string.model_editor_group_specs),
                    footer = stringResource(R.string.model_editor_specs_footer),
                ) {
                    ModelSpecGrid(cells)
                }
            }
            item("type") {
                InfinitySettingsSection(title = stringResource(R.string.provider_models_editor_type)) {
                    InfinityChoiceChips(
                        choices = modelTypeChoices(model.id),
                        selected = setOf(selectedType),
                        onToggle = { type ->
                            activeCaps = capabilitiesForTypeChange(activeCaps, type)
                            selectedType = type
                        },
                        multiSelect = false,
                        modifier = Modifier
                            .padding(vertical = InfinitySpacing.Small)
                            .testTag("model_editor_type_picker"),
                    )
                }
            }
            item("capabilities") {
                val capabilityKeys = ModelCapabilitySpecs.mapTo(mutableSetOf()) { it.key }
                val selectedCount = activeCaps.count { it in capabilityKeys }
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = InfinitySpacing.RowHorizontal)
                            .padding(bottom = InfinitySpacing.SectionHeaderBottom),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.provider_models_editor_capabilities),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.model_editor_capabilities_selected, selectedCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InfinitySettingsGroup {
                        InfinityChoiceChips(
                            choices = modelCapabilityChoices(model.id),
                            selected = activeCaps,
                            onToggle = { key -> activeCaps = if (key in activeCaps) activeCaps - key else activeCaps + key },
                            modifier = Modifier.padding(vertical = InfinitySpacing.Small),
                        )
                    }
                }
            }
            item("session-headers") {
                ModelSessionHeaderSetting(mode = sessionHeaderMode, onMode = { sessionHeaderMode = it })
            }
            item("test") {
                ModelEditorTestRow(model.id, testState, onTest, onCancel)
            }
            item("delete") {
                InfinitySettingsGroup(modifier = Modifier.padding(bottom = 0.dp)) {
                    InfinitySettingsItem(
                        title = stringResource(R.string.model_editor_delete),
                        icon = Icons.Rounded.Delete,
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { showDeleteConfirmation = true },
                        modifier = Modifier.testTag(UiTags.providerModelsEditorDeleteAction(model.id)),
                    )
                }
                Text(
                    stringResource(R.string.model_editor_autosave_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = InfinitySpacing.Small, bottom = InfinitySpacing.Large),
                )
            }
        }
    }

    ModelTokenLimitSheet(
        show = editingLimit == ModelSpecField.Context,
        title = stringResource(R.string.provider_models_context_label),
        value = contextTokens,
        quickValues = ContextQuickValues,
        onValueChange = { editContext = it.toString() },
        onDismiss = { editingLimit = null },
        fieldTag = UiTags.providerModelsContextField(model.id),
    )
    ModelTokenLimitSheet(
        show = editingLimit == ModelSpecField.Output,
        title = stringResource(R.string.provider_models_editor_output_limit),
        value = editOutput,
        quickValues = OutputQuickValues,
        onValueChange = { editOutput = it },
        onDismiss = { editingLimit = null },
        fieldTag = UiTags.providerModelsOutputField(model.id),
    )

    if (showDeleteConfirmation) {
        InfinityConfirmDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            modifier = Modifier.testTag(UiTags.providerModelsEditorDeleteConfirmDialog(model.id)),
            title = stringResource(R.string.model_editor_delete),
            message = model.name,
            confirmText = stringResource(R.string.shared_btn_delete),
            confirmButtonModifier = Modifier.testTag(
                UiTags.providerModelsEditorDeleteConfirmButton(model.id),
            ),
            isDestructive = true,
            onConfirm = {
                showDeleteConfirmation = false
                onDelete()
            },
            onCancel = { showDeleteConfirmation = false },
        )
    }
}

/** 身份组脚注：规范 ID 与用户手动改过的字段（取自 [ModelInfo.userEditedFields]）。 */
@Composable
private fun modelIdentityFooter(model: ModelInfo): String? {
    val edited = model.userEditedFields
    val editedLabels = listOfNotNull(
        if ("remoteModelId" in edited) stringResource(R.string.provider_models_field_model_id) else null,
        if ("name" in edited) stringResource(R.string.provider_models_field_display_name) else null,
        if ("type" in edited) stringResource(R.string.provider_models_editor_type) else null,
        if ("capabilities" in edited) stringResource(R.string.provider_models_editor_capabilities) else null,
        if ("contextLength" in edited) stringResource(R.string.provider_models_context_label) else null,
        if ("maxOutputTokens" in edited) stringResource(R.string.provider_models_editor_output_limit) else null,
    )
    return listOfNotNull(
        model.canonicalModelId?.let { stringResource(R.string.provider_models_editor_canonical_id, it) },
        editedLabels.takeIf { it.isNotEmpty() }?.let {
            stringResource(R.string.provider_models_editor_user_overrides, it.joinToString(stringResource(R.string.model_editor_list_separator)))
        },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")  // wrap-check: allow 分区脚注，两项均为可能很长的完整说明
}

@Composable
private fun ModelEditorTestRow(
    modelId: String,
    testState: ModelTestState,
    onTest: () -> Unit,
    onCancel: () -> Unit,
) {
    val isTesting = testState == ModelTestState.Testing
    val status = modelTestStatus(testState) ?: stringResource(R.string.model_editor_test_idle)
    val statusColor = when (testState) {
        is ModelTestState.Success -> MaterialTheme.infinityDomainColors.success
        is ModelTestState.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    InfinitySettingsSection(title = stringResource(R.string.model_editor_group_test)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = InfinitySpacing.RowHorizontal, end = InfinitySpacing.Medium, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        stateDescription = status
                    },
            )
            Spacer(Modifier.width(InfinitySpacing.Medium))
            FilledTonalButton(
                onClick = if (isTesting) onCancel else onTest,
                modifier = Modifier
                    .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                    .testTag(UiTags.providerModelsEditorTestAction(modelId)),
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (isTesting) R.string.settings_btn_cancel else R.string.model_editor_test_action))
            }
        }
    }
}

@Composable
private fun modelTestStatus(testState: ModelTestState): String? = when (testState) {
    ModelTestState.Idle -> null
    ModelTestState.Testing -> stringResource(R.string.model_editor_test_running)
    is ModelTestState.Success -> stringResource(
        R.string.provider_models_test_success,
        "${testState.latencyMs}ms",
    )
    is ModelTestState.Error -> when (testState.code) {
        GenerationFailureCode.NETWORK -> stringResource(R.string.generation_failure_network)
        GenerationFailureCode.AUTH -> stringResource(R.string.generation_failure_auth)
        GenerationFailureCode.RATE_LIMIT -> testState.retryAfterSeconds?.takeIf { it > 0 }?.let {
            stringResource(R.string.generation_failure_rate_limit_retry, it)
        } ?: stringResource(R.string.generation_failure_rate_limit)
        GenerationFailureCode.QUOTA -> stringResource(R.string.generation_failure_quota)
        GenerationFailureCode.TIMEOUT -> stringResource(R.string.generation_failure_timeout)
        GenerationFailureCode.INVALID_REQUEST -> stringResource(R.string.generation_failure_invalid_request)
        GenerationFailureCode.SERVER -> stringResource(R.string.generation_failure_server)
        else -> stringResource(R.string.generation_failure_unknown)
    }
}
