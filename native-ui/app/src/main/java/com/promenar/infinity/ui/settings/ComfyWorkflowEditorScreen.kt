package com.promenar.infinity.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.promenar.infinity.R
import com.promenar.infinity.data.mediagen.comfy.ComfyHost
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.keepCjkTailTogether
import kotlinx.serialization.json.*

@Composable
internal fun ComfyWorkflowEditorScreenContent(
    host: ComfyHost,
    existingWorkflow: ComfyWorkflow?,
    submitters: List<ComfySubmitterScript>,
    state: MediaSettingsUiState,
    onBack: () -> Unit = {},
    onSave: (ComfyWorkflow) -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val isNew = existingWorkflow == null
    var workflowId by remember(existingWorkflow?.workflowId) { mutableStateOf(existingWorkflow?.workflowId ?: "") }
    var name by remember(existingWorkflow?.workflowId) { mutableStateOf(existingWorkflow?.name ?: "") }
    var description by remember(existingWorkflow?.workflowId) { mutableStateOf(existingWorkflow?.description ?: "") }
    var category by remember(existingWorkflow?.workflowId) { mutableStateOf(existingWorkflow?.mediaCategory ?: ComfyMediaCategory.IMAGE) }
    var execMode by remember(existingWorkflow?.workflowId) { mutableStateOf(existingWorkflow?.executionMode ?: WorkflowExecutionMode.SINGLE) }
    var selectedSubmitterId by remember(existingWorkflow?.workflowId) { mutableStateOf(existingWorkflow?.submitterScriptId) }
    var rawJsonText by remember(existingWorkflow?.workflowId) {
        mutableStateOf(existingWorkflow?.rawWorkflowJson?.toString() ?: "")
    }
    var outputNodesText by remember(existingWorkflow?.workflowId) {
        mutableStateOf(existingWorkflow?.outputNodeIds?.joinToString(", ") ?: "")
    }
    var cliBaseArgsText by remember(existingWorkflow?.workflowId) {
        mutableStateOf(existingWorkflow?.cliBaseArgs?.joinToString(" ") ?: "")
    }
    var lockedKeysText by remember(existingWorkflow?.workflowId) {
        mutableStateOf(
            existingWorkflow?.chainSpec?.lockedInheritedKeys?.joinToString(", ")
                ?: "latent_dir, width, height, family, context_length, audio_context_length",
        )
    }
    var branchPolicy by remember(existingWorkflow?.workflowId) {
        mutableStateOf(existingWorkflow?.chainSpec?.defaultBranchPolicy ?: StatefulBranchPolicy.ISOLATED_DIR)
    }
    var modes by remember(existingWorkflow?.workflowId) {
        mutableStateOf(existingWorkflow?.capabilities?.modes ?: setOf(GenerationMode.TEXT))
    }
    var fpsText by remember(existingWorkflow?.workflowId) { mutableStateOf(existingWorkflow?.fps?.toString() ?: "") }
    var fields by remember(existingWorkflow?.workflowId) { mutableStateOf(existingWorkflow?.fields ?: emptyList()) }

    var editingFieldState by remember { mutableStateOf<Pair<Int?, WorkflowFieldSpec>?>(null) }
    var buildError by remember { mutableStateOf<String?>(null) }

    val hostSubmitters = remember(submitters, host.id) { submitters.filter { it.hostId == host.id } }
    val contentFields = remember(fields) { fields.filter { it.mutability == FieldMutability.CONTENT } }
    val fixedFields = remember(fields) { fields.filter { it.mutability == FieldMutability.FIXED } }

    fun tryBuildWorkflow(): Result<ComfyWorkflow> = runCatching {
        val cleanId = workflowId.trim()
        val rawJson = rawJsonText.trim().takeIf { it.isNotEmpty() }?.let { raw ->
            Json.parseToJsonElement(raw) as? JsonObject ?: throw IllegalArgumentException("API Format JSON 必须是 JSON 对象")
        }
        val outIds = outputNodesText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val cliArgs = cliBaseArgsText.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val chainSpec = if (execMode == WorkflowExecutionMode.STATEFUL_CHAIN) {
            val lockedKeys = lockedKeysText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            StatefulChainSpec(
                lockedInheritedKeys = lockedKeys.ifEmpty { listOf("latent_dir") },
                defaultBranchPolicy = branchPolicy,
            )
        } else null
        val fps = fpsText.trim().takeIf { it.isNotEmpty() }?.let {
            it.toDoubleOrNull() ?: throw IllegalArgumentException("FPS 必须是有效数字")
        }
        ComfyWorkflow(
            workflowId = cleanId,
            hostId = host.id,
            name = name.trim(),
            description = description.trim(),
            mediaCategory = category,
            submitterScriptId = selectedSubmitterId,
            rawWorkflowJson = rawJson,
            outputNodeIds = outIds,
            executionMode = execMode,
            chainSpec = chainSpec,
            fields = fields,
            capabilities = (existingWorkflow?.capabilities ?: MediaCapabilities(modes = modes)).copy(
                modes = modes.ifEmpty { setOf(GenerationMode.TEXT) },
            ),
            cliBaseArgs = cliArgs,
            fps = fps,
        )
    }

    val titleRes = if (isNew) R.string.media_settings_add_workflow else R.string.media_settings_edit_workflow
    InfinitySettingsPageLayout(
        title = stringResource(titleRes),
        onBack = onBack,
        actions = {
            InfinityPillButton(
                text = stringResource(R.string.media_settings_save),
                onClick = {
                    val built = tryBuildWorkflow()
                    built.onSuccess { wf ->
                        buildError = null
                        onSave(wf)
                    }.onFailure { err ->
                        buildError = err.message ?: "工作流配置无效"
                    }
                },
                enabled = !state.busy,
                tone = InfinityPillTone.PRIMARY,
                compact = true,
                modifier = Modifier.padding(end = 8.dp).testTag("comfy_workflow_save_btn"),
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .animateContentSize(InfinityMotion.contentSizeSpec())
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MediaOperationState(state, onRetry)
            AnimatedVisibility(visible = buildError != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                buildError?.let { MediaNotice(it, error = true) }
            }
            if (state.errorDetail != null) {
                MediaNotice(state.errorDetail, error = true)
            }

            InfinitySettingsSection(title = stringResource(R.string.media_settings_wf_basic)) {
                InfinityFieldRow(
                    label = stringResource(R.string.media_settings_wf_name),
                    value = name,
                    onValueChange = { name = it; buildError = null },
                )
                InfinityGroupDivider()
                InfinityFieldRow(
                    label = stringResource(R.string.media_settings_wf_id),
                    value = workflowId,
                    onValueChange = { if (isNew) { workflowId = it; buildError = null } },
                    enabled = isNew,
                    monospace = true,
                )
                InfinityGroupDivider()
                InfinityFieldRow(
                    label = stringResource(R.string.media_settings_wf_desc),
                    value = description,
                    onValueChange = { description = it },
                )
                InfinityGroupDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.media_settings_type), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InfinityTagScrollRow(spacing = 8.dp) {
                        ComfyMediaCategory.entries.forEach { cat ->
                            InfinityChip(
                                label = comfyMediaCategoryLabel(cat),
                                selected = category == cat,
                                onClick = { category = cat },
                            )
                        }
                    }
                    Text(stringResource(R.string.media_settings_modes_select), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InfinityTagScrollRow(spacing = 8.dp) {
                        GenerationMode.entries.forEach { mode ->
                            InfinityChip(
                                label = generationModeLabel(mode),
                                selected = mode in modes,
                                onClick = {
                                    modes = if (mode in modes && modes.size > 1) modes - mode else modes + mode
                                },
                            )
                        }
                    }
                    InfinitySegmentedControl(
                        segments = listOf(
                            InfinitySegment(WorkflowExecutionMode.SINGLE, stringResource(R.string.media_settings_exec_single)),
                            InfinitySegment(WorkflowExecutionMode.STATEFUL_CHAIN, stringResource(R.string.media_settings_exec_chain)),
                        ),
                        selected = execMode,
                        onSelect = { execMode = it },
                        fillWidth = true,
                    )
                }
            }

            InfinitySettingsSection(title = stringResource(R.string.media_settings_wf_executor)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfinityTagScrollRow(spacing = 8.dp) {
                        InfinityChip(
                            label = stringResource(R.string.media_settings_builtin_json_executor),
                            selected = selectedSubmitterId == null,
                            onClick = { selectedSubmitterId = null },
                        )
                        hostSubmitters.forEach { sub ->
                            InfinityChip(
                                label = sub.fileName,
                                selected = selectedSubmitterId == sub.scriptId,
                                onClick = { selectedSubmitterId = sub.scriptId },
                            )
                        }
                    }
                }
                if (selectedSubmitterId != null) {
                    InfinityGroupDivider()
                    InfinityFieldRow(
                        label = stringResource(R.string.media_settings_wf_cli_args),
                        value = cliBaseArgsText,
                        onValueChange = { cliBaseArgsText = it },
                        monospace = true,
                    )
                } else {
                    InfinityGroupDivider()
                    InfinityFieldRow(
                        label = stringResource(R.string.media_settings_wf_output_nodes),
                        value = outputNodesText,
                        onValueChange = { outputNodesText = it },
                        monospace = true,
                    )
                    InfinityGroupDivider()
                    OutlinedTextField(
                        value = rawJsonText,
                        onValueChange = { rawJsonText = it; buildError = null },
                        label = { Text(stringResource(R.string.media_settings_wf_raw_json)) },
                        textStyle = LocalTextStyle.current.forInput().copy(fontFamily = InfinityMonospace),
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
                if (category == ComfyMediaCategory.VIDEO || category == ComfyMediaCategory.POST_PROCESS) {
                    InfinityGroupDivider()
                    InfinityFieldRow(
                        label = "FPS",
                        value = fpsText,
                        onValueChange = { fpsText = it },
                    )
                }
            }

            AnimatedVisibility(visible = execMode == WorkflowExecutionMode.STATEFUL_CHAIN, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                InfinitySettingsSection(title = stringResource(R.string.media_settings_wf_chain_config)) {
                    InfinityFieldRow(
                        label = stringResource(R.string.media_settings_wf_chain_locked_keys),
                        value = lockedKeysText,
                        onValueChange = { lockedKeysText = it },
                        monospace = true,
                    )
                    InfinityGroupDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.media_settings_wf_branch_policy), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        InfinitySegmentedControl(
                            segments = listOf(
                                InfinitySegment(StatefulBranchPolicy.ISOLATED_DIR, stringResource(R.string.media_settings_branch_isolated)),
                                InfinitySegment(StatefulBranchPolicy.IN_PLACE_OVERWRITE, stringResource(R.string.media_settings_branch_overwrite)),
                            ),
                            selected = branchPolicy,
                            onSelect = { branchPolicy = it },
                            fillWidth = true,
                        )
                    }
                }
            }

            WorkflowFieldGroupSection(
                title = stringResource(R.string.media_settings_fields_content),
                fields = contentFields,
                locked = false,
                onAdd = {
                    editingFieldState = null to WorkflowFieldSpec(
                        key = "field_${fields.size + 1}",
                        label = "",
                        mutability = FieldMutability.CONTENT,
                        valueType = FieldValueType.STRING,
                    )
                },
                onEdit = { field ->
                    val idx = fields.indexOfFirst { it.key == field.key }
                    editingFieldState = idx to field
                },
            )

            WorkflowFieldGroupSection(
                title = stringResource(R.string.media_settings_fields_fixed),
                fields = fixedFields,
                locked = true,
                onAdd = {
                    editingFieldState = null to WorkflowFieldSpec(
                        key = "fixed_${fields.size + 1}",
                        label = "",
                        mutability = FieldMutability.FIXED,
                        valueType = FieldValueType.STRING,
                    )
                },
                onEdit = { field ->
                    val idx = fields.indexOfFirst { it.key == field.key }
                    editingFieldState = idx to field
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    editingFieldState?.let { (editIndex, initialSpec) ->
        ComfyWorkflowFieldEditorDialog(
            initial = initialSpec,
            isNew = editIndex == null,
            existingKeys = fields.filterIndexed { i, _ -> i != editIndex }.map { it.key }.toSet(),
            onDismiss = { editingFieldState = null },
            onSave = { updated ->
                fields = if (editIndex != null && editIndex in fields.indices) {
                    fields.toMutableList().apply { set(editIndex, updated) }
                } else {
                    fields + updated
                }
                editingFieldState = null
            },
            onDelete = if (editIndex != null) {
                {
                    fields = fields.filterIndexed { i, _ -> i != editIndex }
                    editingFieldState = null
                }
            } else null,
        )
    }
}

@Composable
private fun WorkflowFieldGroupSection(
    title: String,
    fields: List<WorkflowFieldSpec>,
    locked: Boolean,
    onAdd: () -> Unit,
    onEdit: (WorkflowFieldSpec) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    InfinitySettingsSection(title = title) {
        if (fields.isEmpty()) {
            MediaNotice(stringResource(R.string.media_settings_no_fields))
        } else {
            fields.forEachIndexed { index, field ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = { onEdit(field) })
                        .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = if (locked) Icons.Rounded.Lock else Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = if (locked) domain.onWarningContainer else scheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        InfinityScrollingLine(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(field.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = scheme.onSurface, maxLines = 1, softWrap = false)
                                Text(field.key, style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace), color = scheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            }
                        }
                        InfinityTagScrollRow(spacing = 6.dp) {
                            com.promenar.infinity.ui.production.ProductionStatusChip(
                                fieldValueTypeLabel(field.valueType),
                                if (locked) com.promenar.infinity.ui.production.ProductionTone.WARNING else com.promenar.infinity.ui.production.ProductionTone.INFO,
                            )
                            if (field.semanticRole != FieldSemanticRole.NONE) {
                                com.promenar.infinity.ui.production.ProductionStatusChip(
                                    fieldSemanticRoleLabel(field.semanticRole),
                                    com.promenar.infinity.ui.production.ProductionTone.NEUTRAL,
                                )
                            }
                            field.cliFlag?.let {
                                com.promenar.infinity.ui.production.ProductionStatusChip(it, com.promenar.infinity.ui.production.ProductionTone.NEUTRAL)
                            }
                        }
                        field.defaultValue?.let { defVal ->
                            InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                Text(
                                    text = field.displayValue(defVal),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.media_settings_edit_field), tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                if (index < fields.lastIndex) InfinityGroupDivider()
            }
        }
        InfinityGroupDivider()
        InfinitySettingsItem(
            icon = Icons.Rounded.Add,
            title = stringResource(R.string.media_settings_add_field),
            onClick = onAdd,
            titleColor = if (locked) domain.onWarningContainer else scheme.primary,
        )
    }
}

@Composable
private fun ComfyWorkflowFieldEditorDialog(
    initial: WorkflowFieldSpec,
    isNew: Boolean,
    existingKeys: Set<String>,
    onDismiss: () -> Unit,
    onSave: (WorkflowFieldSpec) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var key by remember(initial) { mutableStateOf(initial.key) }
    var label by remember(initial) { mutableStateOf(initial.label) }
    var mutability by remember(initial) { mutableStateOf(initial.mutability) }
    var valueType by remember(initial) { mutableStateOf(initial.valueType) }
    var semanticRole by remember(initial) { mutableStateOf(initial.semanticRole) }
    var required by remember(initial) { mutableStateOf(initial.required) }
    var defaultText by remember(initial) {
        mutableStateOf(
            when (val d = initial.defaultValue) {
                null, JsonNull -> ""
                is JsonPrimitive -> d.content
                else -> d.toString()
            },
        )
    }
    var optionsText by remember(initial) { mutableStateOf(initial.options.joinToString(", ")) }
    var cliFlag by remember(initial) { mutableStateOf(initial.cliFlag ?: "") }
    var cliMode by remember(initial) { mutableStateOf(initial.cliMode) }
    var nodeId by remember(initial) { mutableStateOf(initial.nodeId ?: "") }
    var inputKey by remember(initial) { mutableStateOf(initial.inputKey ?: "") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(top = 24.dp),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.media_settings_cancel)) }
                    InfinityScrollingLine(Modifier.weight(1f)) {
                        Text(
                            stringResource(if (isNew) R.string.media_settings_add_field else R.string.media_settings_edit_field),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    if (onDelete != null) {
                        InfinityPillButton(
                            text = stringResource(R.string.media_settings_delete),
                            onClick = onDelete,
                            tone = InfinityPillTone.DANGER,
                            compact = true,
                        )
                    }
                    InfinityPillButton(
                        text = stringResource(R.string.media_settings_save),
                        onClick = {
                            runCatching {
                                val cleanKey = key.trim()
                                require(cleanKey !in existingKeys) { "字段 key '$cleanKey' 重复" }
                                val options = optionsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                val parsedDefault = parseDefaultFieldValue(valueType, defaultText.trim())
                                WorkflowFieldSpec(
                                    key = cleanKey,
                                    label = label.trim().ifBlank { cleanKey },
                                    mutability = mutability,
                                    valueType = valueType,
                                    required = required,
                                    defaultValue = parsedDefault,
                                    options = options,
                                    semanticRole = semanticRole,
                                    cliFlag = cliFlag.trim().takeIf { it.isNotEmpty() },
                                    cliMode = cliMode,
                                    nodeId = nodeId.trim().takeIf { it.isNotEmpty() },
                                    inputKey = inputKey.trim().takeIf { it.isNotEmpty() },
                                )
                            }.onSuccess(onSave).onFailure { errorMsg = it.message ?: "字段无效" }
                        },
                        tone = InfinityPillTone.PRIMARY,
                        compact = true,
                    )
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AnimatedVisibility(visible = errorMsg != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                        errorMsg?.let { MediaNotice(it, error = true) }
                    }
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it; errorMsg = null },
                        label = { Text(stringResource(R.string.media_settings_field_key)) },
                        textStyle = LocalTextStyle.current.forInput().copy(fontFamily = InfinityMonospace),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it; errorMsg = null },
                        label = { Text(stringResource(R.string.media_settings_field_label)) },
                        textStyle = LocalTextStyle.current.forInput(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.media_settings_field_mutability), style = MaterialTheme.typography.labelMedium)
                    InfinitySegmentedControl(
                        segments = listOf(
                            InfinitySegment(FieldMutability.CONTENT, stringResource(R.string.media_settings_fields_content).substringBefore("（").substringBefore(" (").trim()),
                            InfinitySegment(FieldMutability.FIXED, stringResource(R.string.media_settings_fields_fixed).substringBefore("（").substringBefore(" (").trim()),
                        ),
                        selected = mutability,
                        onSelect = { mutability = it },
                        fillWidth = true,
                    )
                    Text(stringResource(R.string.media_settings_field_type), style = MaterialTheme.typography.labelMedium)
                    InfinityTagScrollRow(spacing = 8.dp) {
                        FieldValueType.entries.forEach { t ->
                            InfinityChip(label = fieldValueTypeLabel(t), selected = valueType == t, onClick = { valueType = t })
                        }
                    }
                    if (valueType == FieldValueType.ENUM) {
                        OutlinedTextField(
                            value = optionsText,
                            onValueChange = { optionsText = it },
                            label = { Text(stringResource(R.string.media_settings_field_options)) },
                            textStyle = LocalTextStyle.current.forInput(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = defaultText,
                        onValueChange = { defaultText = it },
                        label = { Text(stringResource(R.string.media_settings_field_default)) },
                        textStyle = LocalTextStyle.current.forInput(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.media_settings_field_required), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        InfinitySwitch(checked = required, onCheckedChange = { required = it })
                    }
                    Text(stringResource(R.string.media_settings_field_role), style = MaterialTheme.typography.labelMedium)
                    InfinityTagScrollRow(spacing = 8.dp) {
                        FieldSemanticRole.entries.forEach { role ->
                            InfinityChip(label = fieldSemanticRoleLabel(role), selected = semanticRole == role, onClick = { semanticRole = role })
                        }
                    }
                    OutlinedTextField(
                        value = cliFlag,
                        onValueChange = { cliFlag = it },
                        label = { Text(stringResource(R.string.media_settings_field_cli_flag)) },
                        textStyle = LocalTextStyle.current.forInput().copy(fontFamily = InfinityMonospace),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.media_settings_field_cli_mode), style = MaterialTheme.typography.labelMedium)
                    InfinityTagScrollRow(spacing = 8.dp) {
                        CliArgMode.entries.forEach { mode ->
                            InfinityChip(label = mode.name, selected = cliMode == mode, onClick = { cliMode = mode })
                        }
                    }
                    OutlinedTextField(
                        value = nodeId,
                        onValueChange = { nodeId = it },
                        label = { Text(stringResource(R.string.media_settings_field_node_id)) },
                        textStyle = LocalTextStyle.current.forInput().copy(fontFamily = InfinityMonospace),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = inputKey,
                        onValueChange = { inputKey = it },
                        label = { Text(stringResource(R.string.media_settings_field_input_key)) },
                        textStyle = LocalTextStyle.current.forInput().copy(fontFamily = InfinityMonospace),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun parseDefaultFieldValue(type: FieldValueType, raw: String): JsonElement? {
    if (raw.isEmpty()) return null
    return when (type) {
        FieldValueType.INT -> JsonPrimitive(raw.toLongOrNull() ?: throw IllegalArgumentException("整数默认值无效：$raw"))
        FieldValueType.FLOAT -> JsonPrimitive(raw.toDoubleOrNull() ?: throw IllegalArgumentException("小数默认值无效：$raw"))
        FieldValueType.BOOL -> when (raw.lowercase()) {
            "true", "1" -> JsonPrimitive(true)
            "false", "0" -> JsonPrimitive(false)
            else -> throw IllegalArgumentException("布尔默认值须为 true 或 false")
        }
        FieldValueType.JSON, FieldValueType.FILE_LIST, FieldValueType.TIMED_FILES -> Json.parseToJsonElement(raw)
        else -> JsonPrimitive(raw)
    }
}

@Composable
internal fun comfyMediaCategoryLabel(category: ComfyMediaCategory): String = when (category) {
    ComfyMediaCategory.IMAGE -> stringResource(R.string.media_settings_image)
    ComfyMediaCategory.VIDEO -> stringResource(R.string.media_settings_video)
    ComfyMediaCategory.AUDIO -> stringResource(R.string.media_settings_audio)
    ComfyMediaCategory.POST_PROCESS -> stringResource(R.string.media_settings_cat_post_process)
    ComfyMediaCategory.MODEL_3D -> stringResource(R.string.media_settings_cat_model_3d)
    ComfyMediaCategory.FILE -> stringResource(R.string.media_settings_cat_file)
}

internal fun comfyMediaCategoryIcon(category: ComfyMediaCategory): ImageVector = when (category) {
    ComfyMediaCategory.IMAGE -> Icons.Rounded.Image
    ComfyMediaCategory.VIDEO -> Icons.Rounded.Movie
    ComfyMediaCategory.AUDIO -> Icons.Rounded.MusicNote
    ComfyMediaCategory.POST_PROCESS -> Icons.Rounded.AutoFixHigh
    ComfyMediaCategory.MODEL_3D -> Icons.Rounded.ViewInAr
    ComfyMediaCategory.FILE -> Icons.Rounded.Description
}

private fun fieldValueTypeLabel(type: FieldValueType): String = when (type) {
    FieldValueType.STRING -> "STRING"
    FieldValueType.TEXT -> "TEXT"
    FieldValueType.INT -> "INT"
    FieldValueType.FLOAT -> "FLOAT"
    FieldValueType.BOOL -> "BOOL"
    FieldValueType.ENUM -> "ENUM"
    FieldValueType.JSON -> "JSON"
    FieldValueType.IMAGE_FILE -> "IMAGE_FILE"
    FieldValueType.VIDEO_FILE -> "VIDEO_FILE"
    FieldValueType.AUDIO_FILE -> "AUDIO_FILE"
    FieldValueType.ANY_FILE -> "ANY_FILE"
    FieldValueType.DIRECTORY -> "DIRECTORY"
    FieldValueType.FILE_LIST -> "FILE_LIST"
    FieldValueType.TIMED_FILES -> "TIMED_FILES"
}

private fun fieldSemanticRoleLabel(role: FieldSemanticRole): String = when (role) {
    FieldSemanticRole.NONE -> "NONE"
    FieldSemanticRole.PROMPT -> "PROMPT"
    FieldSemanticRole.NEGATIVE_PROMPT -> "NEG_PROMPT"
    FieldSemanticRole.REFERENCE_IMAGES -> "REF_IMAGES"
    FieldSemanticRole.FIRST_FRAME -> "FIRST_FRAME"
    FieldSemanticRole.LAST_FRAME -> "LAST_FRAME"
    FieldSemanticRole.GUIDES -> "GUIDES"
    FieldSemanticRole.FRAMES -> "FRAMES"
    FieldSemanticRole.DURATION_SECONDS -> "DURATION"
    FieldSemanticRole.WIDTH -> "WIDTH"
    FieldSemanticRole.HEIGHT -> "HEIGHT"
    FieldSemanticRole.SEED -> "SEED"
    FieldSemanticRole.AUDIO_REF -> "AUDIO_REF"
    FieldSemanticRole.INPUT_VIDEO -> "INPUT_VIDEO"
}
