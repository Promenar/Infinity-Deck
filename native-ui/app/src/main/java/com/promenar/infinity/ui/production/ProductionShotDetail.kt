package com.promenar.infinity.ui.production

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.Asset
import com.promenar.infinity.data.production.AssetStatus
import com.promenar.infinity.data.production.PreviewKind
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.production.ShotInput
import com.promenar.infinity.data.production.Take
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityChoice
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.common.InfinityStatusStrip
import com.promenar.infinity.ui.media.MediaCardStyle
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 提示词与批注的长度上限，与后端校验一致。 */
internal const val PRODUCTION_PROMPT_MAX = 7000
internal const val PRODUCTION_NOTE_MAX = 1000

private enum class ShotDialog { REDO, PROMPT }

/**
 * 镜头详情：播放当前查看的候选，切换候选，选定、重做或解锁，编辑提示词，查看输入与预演素材。
 */
@Composable
internal fun ProductionShotDetail(
    location: ShotLocation,
    busy: Boolean,
    error: String?,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    onRun: (ProductionUserAction) -> Unit,
    onSubmit: suspend (ProductionUserAction) -> String?,
    modifier: Modifier = Modifier,
) {
    val shot = location.shot
    val episodeId = location.episode.id
    var viewingTakeId by rememberSaveable(episodeId, shot.code) { mutableStateOf(shot.displayTake()?.id) }
    val viewing = shot.takes.firstOrNull { it.id == viewingTakeId } ?: shot.displayTake()
    var dialog by rememberSaveable { mutableStateOf<ShotDialog?>(null) }
    val actions = shot.actions(viewing?.id)

    Column(modifier.fillMaxSize().testTag(ProductionUiTags.SHOT_DETAIL)) {
        Row(
            Modifier.fillMaxWidth().padding(start = InfinitySpacing.XSmall, end = InfinitySpacing.Large, top = InfinitySpacing.XSmall, bottom = InfinitySpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.production_panel_back))
            }
            Column(Modifier.weight(1f)) {
                Text(shot.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                InfinityMetaLine(
                    segments = listOf(episodeId, location.scene.code, shot.code),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            ProductionStatusChip(shot.status.label(), shot.status.tone())
        }
        error?.let {
            InfinityStatusStrip(
                kind = InfinityStatusKind.Error,
                message = it,
                onDismiss = onDismissError,
                modifier = Modifier.padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.XSmall).testTag(ProductionUiTags.ERROR),
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = InfinitySpacing.Large, end = InfinitySpacing.Large, top = InfinitySpacing.XSmall, bottom = InfinitySpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            item("media") {
                if (viewing != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
                        key(viewing.path) {
                            ProductionMediaReference(path = viewing.path, alt = shot.title, style = MediaCardStyle.TOOL, modifier = Modifier.fillMaxWidth())
                        }
                        InfinityMetaLine(
                            segments = listOfNotNull(viewing.source.service, viewing.source.model, viewing.source.mode?.label()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        viewing.note?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        contentAlignment = Alignment.Center,
                    ) {
                        ProductionThumbnail(shot.previewPath ?: shot.inputs.firstOrNull()?.path, Modifier.fillMaxSize())
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(
                                stringResource(R.string.production_panel_no_takes),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.XSmall),
                            )
                        }
                    }
                }
            }
            if (shot.takes.isNotEmpty()) {
                item("takes") {
                    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                        ProductionSectionTitle(stringResource(R.string.production_panel_section_takes))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                            items(shot.takes, key = { it.id }) { take ->
                                TakeThumb(
                                    take = take,
                                    index = shot.takes.indexOf(take) + 1,
                                    viewing = take.id == viewing?.id,
                                    selected = take.id == shot.selectedTakeId,
                                    onClick = { viewingTakeId = take.id },
                                )
                            }
                        }
                    }
                }
            }
            item("actions") {
                ProductionActionRow(
                    buildList {
                        if (actions.canUnlock) {
                            add(ProductionButton(stringResource(R.string.production_panel_unlock), InfinityPillTone.NEUTRAL, !busy, ProductionUiTags.SHOT_UNLOCK) {
                                onRun(ProductionUserAction.UnlockShot(episodeId, shot.code))
                            })
                        } else {
                            add(ProductionButton(stringResource(R.string.production_panel_redo), InfinityPillTone.NEUTRAL, !busy && actions.canRedo, ProductionUiTags.SHOT_REDO) {
                                dialog = ShotDialog.REDO
                            })
                            add(ProductionButton(stringResource(R.string.production_panel_select), InfinityPillTone.PRIMARY, !busy && actions.canSelect, ProductionUiTags.SHOT_SELECT) {
                                viewing?.let { onRun(ProductionUserAction.SelectTake(episodeId, shot.code, it.id)) }
                            })
                        }
                    },
                )
            }
            item("info") {
                InfinityMetaLine(
                    segments = listOfNotNull(shot.size, secondsLabel(shot.durationSeconds), shot.mode.label()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            shot.userNote?.takeIf(String::isNotBlank)?.let { note ->
                item("user-note") { NoteBlock(stringResource(R.string.production_panel_section_user_note), note) }
            }
            shot.agentNote?.takeIf(String::isNotBlank)?.let { note ->
                item("agent-note") { NoteBlock(stringResource(R.string.production_panel_section_agent_note), note) }
            }
            item("prompt") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProductionSectionTitle(stringResource(R.string.production_panel_section_prompt), Modifier.weight(1f))
                        IconButton(
                            onClick = { dialog = ShotDialog.PROMPT },
                            enabled = !busy && actions.canEditPrompt,
                            modifier = Modifier.size(48.dp).testTag(ProductionUiTags.SHOT_PROMPT_EDIT),
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.production_panel_edit), modifier = Modifier.size(20.dp))
                        }
                    }
                    Text(
                        shot.prompt.ifBlank { stringResource(R.string.production_panel_no_prompt) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (shot.prompt.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (shot.inputs.isNotEmpty()) {
                item("inputs") {
                    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                        ProductionSectionTitle(stringResource(R.string.production_panel_section_inputs))
                        shot.inputs.forEach { input -> InputRow(input) }
                    }
                }
            }
            shot.previewPath?.let { path ->
                item("preview") {
                    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                            ProductionSectionTitle(stringResource(R.string.production_panel_section_preview))
                            shot.preview?.let { ProductionStatusChip(it.label(), ProductionTone.NEUTRAL) }
                        }
                        ProductionMediaReference(
                            path = path,
                            asLink = shot.preview == PreviewKind.TEXT_CARD,
                            style = MediaCardStyle.TOOL,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    when (dialog) {
        ShotDialog.REDO -> ProductionTextDialog(
            title = stringResource(R.string.production_panel_redo_title),
            label = stringResource(R.string.production_panel_reason),
            initial = "",
            confirmText = stringResource(R.string.production_panel_redo),
            maxLength = PRODUCTION_NOTE_MAX,
            minLines = 3,
            fieldTag = ProductionUiTags.REASON_FIELD,
            onDismiss = { dialog = null },
            onSubmit = { note -> onSubmit(ProductionUserAction.RequestRedo(episodeId, shot.code, note.ifBlank { null })) },
        )
        ShotDialog.PROMPT -> ProductionTextDialog(
            title = stringResource(R.string.production_panel_prompt_title),
            label = stringResource(R.string.production_panel_section_prompt),
            initial = shot.prompt,
            confirmText = stringResource(R.string.production_panel_save),
            maxLength = PRODUCTION_PROMPT_MAX,
            minLines = 6,
            fieldTag = ProductionUiTags.PROMPT_FIELD,
            onDismiss = { dialog = null },
            onSubmit = { prompt -> onSubmit(ProductionUserAction.EditPrompt(episodeId, shot.code, prompt)) },
        )
        null -> Unit
    }
}

@Composable
private fun TakeThumb(take: Take, index: Int, viewing: Boolean, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.production_panel_take_number, index)
    Column(
        Modifier
            .width(112.dp)
            .semantics { this.selected = viewing }
            .clickable(role = Role.Tab, onClick = onClick)
            .testTag(ProductionUiTags.TAKE),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
    ) {
        Box {
            ProductionThumbnail(
                take.path,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .then(if (viewing) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, ProductionThumbShape) else Modifier),
                contentDescription = label,
            )
            if (selected) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp),
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.production_panel_selected),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(2.dp),
                    )
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (viewing) FontWeight.SemiBold else FontWeight.Normal,
            color = if (viewing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun InputRow(input: ShotInput) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
        ProductionThumbnail(input.path, Modifier.size(48.dp), contentDescription = input.label)
        Column(Modifier.weight(1f)) {
            Text(input.label?.takeIf(String::isNotBlank) ?: input.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            InfinityMetaLine(
                segments = listOfNotNull(input.role.label(), input.atSeconds?.let { stringResource(R.string.production_panel_anchor_at, formatShotSeconds(it)) }),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun NoteBlock(title: String, text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(InfinitySpacing.Medium), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
            ProductionSectionTitle(title)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 资产详情：查看各版本，通过、打回或解锁。 */
@Composable
internal fun ProductionAssetSheet(
    asset: Asset,
    usage: Int,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRun: (ProductionUserAction) -> Unit,
    onSubmit: suspend (ProductionUserAction) -> String?,
) {
    var versionNumber by rememberSaveable(asset.id) { mutableStateOf(asset.currentVersion ?: asset.versions.maxOfOrNull { it.number }) }
    var rejecting by rememberSaveable(asset.id) { mutableStateOf(false) }
    val version = asset.versions.firstOrNull { it.number == versionNumber } ?: asset.versions.maxByOrNull { it.number }
    val actions = asset.actions()
    InfinityBottomSheet(show = true, onDismiss = onDismiss, title = asset.name, scrollContent = true) {
        Column(Modifier.fillMaxWidth().testTag(ProductionUiTags.ASSET_SHEET), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                InfinityMetaLine(
                    segments = listOf(asset.kind.label(), pluralStringResource(R.plurals.production_panel_asset_usage, usage, usage)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip(asset.status.label(), asset.status.tone())
            }
            version?.let { current ->
                key(current.path) {
                    ProductionMediaReference(path = current.path, alt = asset.name, style = MediaCardStyle.TOOL, modifier = Modifier.fillMaxWidth())
                }
                current.note?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (asset.versions.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                    ProductionSectionTitle(stringResource(R.string.production_panel_section_versions))
                    ProductionChoiceRow(
                        choices = asset.versions.sortedBy { it.number }.map {
                            InfinityChoice(it.number, stringResource(R.string.production_panel_version, it.number))
                        },
                        selected = version?.number ?: -1,
                        onSelect = { versionNumber = it },
                    )
                }
            }
            asset.description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            asset.userNote?.takeIf(String::isNotBlank)?.let { NoteBlock(stringResource(R.string.production_panel_section_user_note), it) }
            ProductionActionRow(
                buildList {
                    if (actions.canUnlock) {
                        add(ProductionButton(stringResource(R.string.production_panel_unlock), InfinityPillTone.NEUTRAL, !busy, ProductionUiTags.ASSET_UNLOCK) {
                            onRun(ProductionUserAction.SetAssetStatus(asset.id, AssetStatus.REVIEW, null))
                        })
                    } else {
                        add(ProductionButton(stringResource(R.string.production_panel_reject), InfinityPillTone.NEUTRAL, !busy && actions.canRedo, ProductionUiTags.ASSET_REJECT) {
                            rejecting = true
                        })
                        add(ProductionButton(stringResource(R.string.production_panel_approve), InfinityPillTone.PRIMARY, !busy && actions.canApprove, ProductionUiTags.ASSET_APPROVE) {
                            onRun(ProductionUserAction.SetAssetStatus(asset.id, AssetStatus.LOCKED, null))
                        })
                    }
                },
            )
        }
    }
    if (rejecting) {
        ProductionTextDialog(
            title = stringResource(R.string.production_panel_reject_title),
            label = stringResource(R.string.production_panel_reason),
            initial = "",
            confirmText = stringResource(R.string.production_panel_reject),
            maxLength = PRODUCTION_NOTE_MAX,
            minLines = 3,
            fieldTag = ProductionUiTags.REASON_FIELD,
            onDismiss = { rejecting = false },
            onSubmit = { note -> onSubmit(ProductionUserAction.SetAssetStatus(asset.id, AssetStatus.REDO, note.ifBlank { null })) },
        )
    }
}
