package com.promenar.infinity.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotInterested
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.CloudMediaPreset
import com.promenar.infinity.data.model.MediaModelSettings
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.cloudMediaPresetsFor
import com.promenar.infinity.data.model.effectiveMediaSettings
import com.promenar.infinity.data.model.matchCloudMediaPreset
import com.promenar.infinity.data.model.mediaPresetSettings
import com.promenar.infinity.data.model.withRecordedUserEdits
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.ui.common.InfinityBackButton
import com.promenar.infinity.ui.common.InfinityChoice
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityFieldRow
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityHeaderTitle
import com.promenar.infinity.ui.common.InfinityMultiChoiceSheet
import com.promenar.infinity.ui.common.InfinityNoticeSeverity
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySingleChoiceSheet
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.common.InfinityStatusStrip
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.common.LocalInfinityNoticeController
import com.promenar.infinity.ui.common.ModelBrandTile
import com.promenar.infinity.ui.common.infinityPointerHand
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing

/**
 * 模型编辑整页（与模型列表同屏切换）：顶栏一键测速、品牌身份头卡、自适应规格与时间线、
 * 两行均衡零裁切类型选择器、可视化能力胶囊、会话请求头与统一语义红删除操作。
 * 修改即时保存；返回由宿主页的系统返回与预测性返回处理，本页左上角返回按钮调用 [onClose]。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    onToggle: (() -> Unit)? = null,
    onOpenMediaSettings: (() -> Unit)? = null,
    onSetMediaSettings: ((MediaModelSettings?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val noticeHost = LocalInfinityNoticeController.current
    val scheme = MaterialTheme.colorScheme

    var showDeleteConfirmation by remember(model.id) { mutableStateOf(false) }
    var selectedType by remember(model.id) { mutableStateOf(model.type) }
    var editName by remember(model.id) { mutableStateOf(model.name) }
    var editRemoteModelId by remember(model.id) { mutableStateOf(model.remoteModelId) }
    var editContext by remember(model.id) { mutableStateOf(model.contextLength.toString()) }
    var editOutput by remember(model.id) { mutableStateOf(model.maxOutputTokens) }
    var sessionHeaderMode by remember(model.id) { mutableStateOf(model.sessionHeaderMode) }
    var activeCaps by remember(model.id) { mutableStateOf(model.capabilities.toSet()) }
    var editingLimit by remember(model.id) { mutableStateOf<ModelSpecField?>(null) }
    var showCapabilities by remember(model.id) { mutableStateOf(false) }
    var showMediaPresetPicker by remember(model.id) { mutableStateOf(false) }

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
    val effectiveMedia = model.effectiveMediaSettings()
    val mediaPresetGroup = when (selectedType.lowercase()) {
        "video" -> MediaGroup.VIDEO
        "speech", "audio" -> MediaGroup.SPEECH
        "music" -> MediaGroup.MUSIC
        else -> null
    }
    val matchedCloudPreset = remember(effectiveMedia) { matchCloudMediaPreset(effectiveMedia) }
    val isMediaModel = selectedType.lowercase() in setOf("video", "audio", "speech", "music", "realtime", "image") || effectiveMedia != null
    val isTesting = testState == ModelTestState.Testing
    val statusText = modelTestStatus(testState, isMediaModel)
    val latestStatusText by rememberUpdatedState(statusText)
    var lastObservedTestState by remember(model.id) { mutableStateOf(testState) }
    LaunchedEffect(model.id, testState, noticeHost) {
        val previous = lastObservedTestState
        lastObservedTestState = testState
        val host = noticeHost ?: return@LaunchedEffect
        if (previous == testState) return@LaunchedEffect
        val text = latestStatusText ?: return@LaunchedEffect
        when (testState) {
            is ModelTestState.Success -> host.show(InfinityNoticeSeverity.SUCCESS, text)
            is ModelTestState.Error -> host.show(InfinityNoticeSeverity.ERROR, text)
            ModelTestState.Idle, ModelTestState.Testing -> Unit
        }
    }
    val heroSubtitle = model.canonicalModelId?.takeIf { it.isNotBlank() }
        ?: editRemoteModelId.ifBlank { model.id.substringAfter("::", model.id) }
    val copyIdLabel = stringResource(R.string.model_editor_copy_id)
    val copiedTemplate = stringResource(R.string.model_editor_id_copied, heroSubtitle)

    InfinitySettingsPageLayout(
        title = stringResource(R.string.provider_models_editor_title),
        modifier = modifier.testTag(UiTags.providerModelsEditorSheet(model.id)),
        headerFrosted = true,
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
                actions = {
                    FilledTonalButton(
                        onClick = if (isTesting) onCancel else onTest,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .height(34.dp)
                            .testTag(UiTags.providerModelsEditorTestAction(model.id)),
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                when {
                                    isTesting -> R.string.settings_btn_cancel
                                    isMediaModel -> R.string.model_editor_media_test_action
                                    else -> R.string.model_editor_test_action
                                },
                            ),
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    titleContentColor = scheme.onBackground,
                    navigationIconContentColor = scheme.onSurfaceVariant,
                ),
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .testTag(UiTags.providerModelsEditorList(model.id)),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (noticeHost == null) {
                item("test-feedback") {
                    AnimatedVisibility(
                        visible = statusText != null,
                        enter = InfinityMotion.expandIn,
                        exit = InfinityMotion.shrinkOut,
                    ) {
                        statusText?.let { text ->
                            val kind = when (testState) {
                                is ModelTestState.Success -> InfinityStatusKind.Success
                                is ModelTestState.Error -> InfinityStatusKind.Error
                                else -> InfinityStatusKind.Info
                            }
                            InfinityStatusStrip(
                                kind = kind,
                                message = text,
                                busy = isTesting,
                                modifier = Modifier
                                    .padding(bottom = InfinitySpacing.Medium)
                                    .semantics {
                                        liveRegion = LiveRegionMode.Polite
                                        stateDescription = text
                                    },
                            )
                        }
                    }
                }
            }
            item("identity") {
                InfinitySettingsGroup(
                    footer = identityFooter,
                    footerModifier = Modifier.testTag(UiTags.providerModelsEditorSource(model.id)),
                    typographicFooter = true,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ModelBrandTile(
                            modelId = model.canonicalModelId ?: editRemoteModelId,
                            size = 44.dp,
                            shape = RoundedCornerShape(13.dp),
                            fallbackIcon = modelTypeIcon(selectedType),
                            withHairlineBorder = true,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = editName.ifBlank { heroSubtitle },
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold),
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(role = Role.Button, onClickLabel = copyIdLabel) {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(ClipData.newPlainText(copyIdLabel, heroSubtitle))
                                        noticeHost?.show(InfinityNoticeSeverity.INFO, copiedTemplate)
                                    }
                                    .infinityPointerHand()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = heroSubtitle,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace, fontSize = 11.5.sp),
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = copyIdLabel,
                                    tint = scheme.onSurfaceVariant.copy(alpha = 0.65f),
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                        if (onToggle != null) {
                            Spacer(Modifier.width(8.dp))
                            InfinitySwitch(
                                checked = model.enabled,
                                onCheckedChange = { onToggle() },
                            )
                        }
                    }
                    InfinityGroupDivider(startInset = 16.dp)
                    InfinityFieldRow(
                        label = stringResource(R.string.provider_models_field_display_name),
                        value = editName,
                        onValueChange = { editName = it },
                        fieldModifier = Modifier.testTag(UiTags.providerModelsNameField(model.id)),
                    )
                    InfinityGroupDivider(startInset = 16.dp)
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
                val primaryCells = buildList {
                    add(
                        tokenCell(
                            ModelSpecField.Context,
                            stringResource(R.string.model_spec_context_label),
                            contextTokens,
                        ) { editingLimit = ModelSpecField.Context },
                    )
                    add(
                        tokenCell(
                            ModelSpecField.Output,
                            stringResource(R.string.provider_models_editor_output_limit),
                            editOutput,
                        ) { editingLimit = ModelSpecField.Output },
                    )
                    if (model.inputTokens > 0 && model.inputTokens != contextTokens) {
                        add(
                            tokenCell(
                                ModelSpecField.Input,
                                stringResource(R.string.model_spec_input_label),
                                model.inputTokens,
                                null,
                            ),
                        )
                    }
                }
                InfinitySettingsSection(
                    title = stringResource(R.string.model_editor_group_specs),
                    trailingHeader = stringResource(R.string.model_editor_autosave_hint),
                    footer = stringResource(R.string.model_editor_specs_footer_merged),
                    typographicFooter = true,
                ) {
                    ModelSpecGrid(primaryCells)
                    InfinityGroupDivider(startInset = 0.dp)
                    ModelSpecTimelineRow(
                        knowledgeCutoff = model.knowledgeCutoff,
                        releaseDate = model.releaseDate,
                        cutoffTag = UiTags.providerModelsSpecCell(model.id, ModelSpecField.KnowledgeCutoff.name),
                    )
                }
            }
            item("type") {
                InfinitySettingsSection(
                    title = stringResource(R.string.provider_models_editor_type),
                    typographicFooter = true,
                ) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 11.dp)
                            .testTag("model_editor_type_picker"),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ModelTypeSpecs.forEach { spec ->
                            val isSelected = normalizedModelFilterType(selectedType) == spec.key
                            val label = stringResource(spec.labelRes)
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = if (isSelected) scheme.primaryContainer else scheme.surfaceContainerHighest.copy(alpha = 0.55f),
                                border = if (isSelected) BorderStroke(1.dp, scheme.primary.copy(alpha = 0.45f)) else null,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable(role = Role.RadioButton) {
                                        if (spec.key != selectedType) {
                                            activeCaps = capabilitiesForTypeChange(activeCaps, spec.key)
                                            selectedType = spec.key
                                            val existingMediaGroup = model.mediaSettings?.group
                                            val nextMediaGroup = when (spec.key.lowercase()) {
                                                "video" -> MediaGroup.VIDEO
                                                "speech", "audio" -> MediaGroup.SPEECH
                                                "music" -> MediaGroup.MUSIC
                                                "image" -> MediaGroup.IMAGE
                                                else -> null
                                            }
                                            if (existingMediaGroup != null && existingMediaGroup != nextMediaGroup) {
                                                onSetMediaSettings?.invoke(null)
                                            }
                                        }
                                    }
                                    .infinityPointerHand()
                                    .semantics {
                                        role = Role.RadioButton
                                        selected = isSelected
                                    }
                                    .testTag(UiTags.providerModelsTypeAction(model.id, spec.key)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = spec.icon,
                                        contentDescription = null,
                                        tint = if (isSelected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                        color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item("cloud-media") {
                val showCloudMediaSection = mediaPresetGroup != null || (onOpenMediaSettings != null && effectiveMedia != null)
                AnimatedVisibility(
                    visible = showCloudMediaSection,
                    enter = InfinityMotion.expandIn,
                    exit = InfinityMotion.shrinkOut,
                ) {
                    if (mediaPresetGroup != null) {
                        val presetIcon = when (mediaPresetGroup) {
                            MediaGroup.VIDEO -> Icons.Rounded.Movie
                            MediaGroup.SPEECH -> Icons.Rounded.RecordVoiceOver
                            MediaGroup.MUSIC -> Icons.Rounded.MusicNote
                            MediaGroup.IMAGE -> Icons.Rounded.Image
                        }
                        val currentPresetLabel = matchedCloudPreset?.let { stringResource(cloudMediaPresetLabelRes(it)) }
                            ?: stringResource(R.string.model_editor_media_preset_none)
                        InfinitySettingsSection(
                            title = stringResource(R.string.model_editor_group_cloud_media),
                            footer = stringResource(R.string.model_editor_media_preset_footer),
                            typographicFooter = true,
                        ) {
                            InfinitySettingsItem(
                                title = stringResource(R.string.model_editor_media_preset),
                                subtitle = currentPresetLabel,
                                icon = presetIcon,
                                showChevron = onSetMediaSettings != null,
                                onClick = { if (onSetMediaSettings != null) showMediaPresetPicker = true },
                                modifier = Modifier.testTag(UiTags.providerModelsMediaPresetPicker(model.id)),
                            )
                            if (onOpenMediaSettings != null && effectiveMedia != null) {
                                InfinityGroupDivider()
                                InfinitySettingsItem(
                                    title = stringResource(R.string.model_editor_media_settings_item),
                                    subtitle = stringResource(
                                        when (mediaPresetGroup) {
                                            MediaGroup.VIDEO -> R.string.model_editor_media_settings_video_subtitle
                                            MediaGroup.SPEECH -> R.string.model_editor_media_settings_speech_subtitle
                                            MediaGroup.MUSIC -> R.string.model_editor_media_settings_music_subtitle
                                            MediaGroup.IMAGE -> R.string.model_editor_media_settings_image_subtitle
                                        },
                                    ),
                                    icon = Icons.Rounded.Tune,
                                    showChevron = true,
                                    onClick = onOpenMediaSettings,
                                )
                            }
                        }
                    } else if (onOpenMediaSettings != null && effectiveMedia != null) {
                        InfinitySettingsSection(
                            title = stringResource(R.string.media_settings_title),
                            typographicFooter = true,
                        ) {
                            InfinitySettingsItem(
                                title = stringResource(R.string.model_editor_media_settings_item),
                                subtitle = stringResource(R.string.model_editor_media_settings_image_subtitle),
                                icon = Icons.Rounded.Image,
                                showChevron = true,
                                onClick = onOpenMediaSettings,
                            )
                        }
                    }
                }
            }
            item("capabilities") {
                val activeLower = activeCaps.mapTo(mutableSetOf()) { it.lowercase() }
                val selectedSpecs = ModelCapabilitySpecs.filter { it.key in activeLower }
                val capabilityChoices = modelCapabilityChoices(model.id)
                val capabilitiesTitle = stringResource(R.string.provider_models_editor_capabilities)
                InfinitySettingsSection(
                    title = capabilitiesTitle,
                    trailingHeader = stringResource(R.string.model_editor_capabilities_tap_hint, selectedSpecs.size),
                    typographicFooter = true,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clickable(role = Role.Button) { showCapabilities = true }
                            .infinityPointerHand()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag(UiTags.providerModelsCapabilityPicker(model.id)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectedSpecs.isEmpty()) {
                            Text(
                                text = stringResource(R.string.model_editor_capabilities_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                selectedSpecs.forEach { spec ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = scheme.surfaceContainerHighest.copy(alpha = 0.65f),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                imageVector = spec.icon,
                                                contentDescription = null,
                                                tint = scheme.onSurfaceVariant,
                                                modifier = Modifier.size(13.dp),
                                            )
                                            Text(
                                                text = stringResource(spec.labelRes),
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                                                color = scheme.onSurface,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                InfinityMultiChoiceSheet(
                    show = showCapabilities,
                    title = capabilitiesTitle,
                    choices = capabilityChoices,
                    selected = activeCaps,
                    onToggle = { key -> activeCaps = if (key in activeCaps) activeCaps - key else activeCaps + key },
                    onDismiss = { showCapabilities = false },
                )
            }
            item("session-headers") {
                ModelSessionHeaderSetting(mode = sessionHeaderMode, onMode = { sessionHeaderMode = it })
            }
            item("delete") {
                val deleteLabel = stringResource(R.string.model_editor_delete)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = scheme.errorContainer.copy(alpha = 0.42f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = InfinitySpacing.Large)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button, onClickLabel = deleteLabel) { showDeleteConfirmation = true }
                        .infinityPointerHand()
                        .semantics {
                            role = Role.Button
                            contentDescription = deleteLabel
                        }
                        .testTag(UiTags.providerModelsEditorDeleteAction(model.id)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = scheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = deleteLabel,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = scheme.error,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }

    ModelTokenLimitSheet(
        show = editingLimit == ModelSpecField.Context,
        title = stringResource(R.string.model_spec_context_label),
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

    if (mediaPresetGroup != null && onSetMediaSettings != null) {
        val groupIcon = when (mediaPresetGroup) {
            MediaGroup.VIDEO -> Icons.Rounded.Movie
            MediaGroup.SPEECH -> Icons.Rounded.RecordVoiceOver
            MediaGroup.MUSIC -> Icons.Rounded.MusicNote
            MediaGroup.IMAGE -> Icons.Rounded.Image
        }
        val presetChoices = listOf(
            InfinityChoice<CloudMediaPreset?>(
                value = null,
                label = stringResource(R.string.model_editor_media_preset_none),
                icon = Icons.Rounded.NotInterested,
                tag = UiTags.providerModelsMediaPresetOption("none"),
            ),
        ) + cloudMediaPresetsFor(mediaPresetGroup).map { preset ->
            InfinityChoice<CloudMediaPreset?>(
                value = preset,
                label = stringResource(cloudMediaPresetLabelRes(preset)),
                icon = groupIcon,
                tag = UiTags.providerModelsMediaPresetOption(preset.name),
            )
        }
        InfinitySingleChoiceSheet(
            show = showMediaPresetPicker,
            title = stringResource(R.string.model_editor_media_preset_sheet_title),
            choices = presetChoices,
            selected = matchedCloudPreset,
            onSelect = { preset ->
                onSetMediaSettings(preset?.let(::mediaPresetSettings))
            },
            onDismiss = { showMediaPresetPicker = false },
        )
    }

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

/** 身份组脚注：用户手动改过的字段（规范 ID 已提升至顶部品牌头卡可一键复制）。 */
@Composable
private fun modelIdentityFooter(model: ModelInfo): String? {
    val edited = model.userEditedFields
    val editedLabels = listOfNotNull(
        if ("remoteModelId" in edited) stringResource(R.string.provider_models_field_model_id) else null,
        if ("name" in edited) stringResource(R.string.provider_models_field_display_name) else null,
        if ("type" in edited) stringResource(R.string.provider_models_editor_type) else null,
        if ("capabilities" in edited) stringResource(R.string.provider_models_editor_capabilities) else null,
        if ("contextLength" in edited) stringResource(R.string.model_spec_context_label) else null,
        if ("maxOutputTokens" in edited) stringResource(R.string.provider_models_editor_output_limit) else null,
    )
    return editedLabels.takeIf { it.isNotEmpty() }?.let {
        stringResource(R.string.provider_models_editor_user_overrides, it.joinToString(stringResource(R.string.model_editor_list_separator)))
    }
}

@StringRes
internal fun cloudMediaPresetLabelRes(preset: CloudMediaPreset): Int = when (preset) {
    CloudMediaPreset.SEEDANCE_2_5 -> R.string.media_preset_seedance_2_5
    CloudMediaPreset.SEEDANCE_2_0 -> R.string.media_preset_seedance_2_0
    CloudMediaPreset.SEEDANCE_2_0_FAST -> R.string.media_preset_seedance_2_0_fast
    CloudMediaPreset.SEEDANCE_1_0_PRO -> R.string.media_preset_seedance_1_0_pro
    CloudMediaPreset.SEEDANCE_1_0_PRO_FAST -> R.string.media_preset_seedance_1_0_pro_fast
    CloudMediaPreset.MINIMAX_H3 -> R.string.media_preset_minimax_h3
    CloudMediaPreset.MINIMAX_H3_HIGHSPEED -> R.string.media_preset_minimax_h3_highspeed
    CloudMediaPreset.MINIMAX_H3_LITE -> R.string.media_preset_minimax_h3_lite
    CloudMediaPreset.MINIMAX_H3_MAX -> R.string.media_preset_minimax_h3_max
    CloudMediaPreset.MINIMAX_HAILUO_02 -> R.string.media_preset_minimax_hailuo_02
    CloudMediaPreset.MINIMAX_S2V_01 -> R.string.media_preset_minimax_s2v_01
    CloudMediaPreset.GEMINI_3_8_FLASH_TTS -> R.string.media_preset_gemini_3_8_flash_tts
    CloudMediaPreset.GEMINI_3_8_FLASH_LITE_TTS -> R.string.media_preset_gemini_3_8_flash_lite_tts
    CloudMediaPreset.GEMINI_2_5_FLASH_TTS -> R.string.media_preset_gemini_2_5_flash_tts
    CloudMediaPreset.GEMINI_2_5_PRO_TTS -> R.string.media_preset_gemini_2_5_pro_tts
    CloudMediaPreset.MINIMAX_SPEECH_2_8_HD -> R.string.media_preset_minimax_speech_2_8_hd
    CloudMediaPreset.MINIMAX_SPEECH_2_8_TURBO -> R.string.media_preset_minimax_speech_2_8_turbo
    CloudMediaPreset.OPENAI_GPT_4O_MINI_TTS -> R.string.media_preset_openai_gpt_4o_mini_tts
    CloudMediaPreset.GEMINI_LYRIA_3_5 -> R.string.media_preset_gemini_lyria_3_5
    CloudMediaPreset.GEMINI_LYRIA_3_PRO -> R.string.media_preset_gemini_lyria_3_pro
    CloudMediaPreset.GEMINI_LYRIA_3_CLIP -> R.string.media_preset_gemini_lyria_3_clip
}

@Composable
private fun modelTestStatus(testState: ModelTestState, isMediaModel: Boolean): String? = when (testState) {
    ModelTestState.Idle -> null
    ModelTestState.Testing -> stringResource(
        if (isMediaModel) R.string.model_editor_media_test_running else R.string.model_editor_test_running,
    )
    is ModelTestState.Success -> stringResource(
        if (testState.isMediaVerify || isMediaModel) {
            R.string.model_editor_media_test_success
        } else {
            R.string.provider_models_test_success
        },
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
