package com.promenar.infinity.ui.production

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.ViewQuilt
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.Asset
import com.promenar.infinity.data.production.AssetKind
import com.promenar.infinity.data.production.AssetStatus
import com.promenar.infinity.data.production.AssetVersion
import com.promenar.infinity.data.production.CharacterLook
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionRules
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.ReferenceInputMode
import com.promenar.infinity.data.production.ReferenceSlot
import com.promenar.infinity.data.production.ReviewTarget
import com.promenar.infinity.data.production.ShotMode
import com.promenar.infinity.data.production.effectiveReferenceInputMode
import com.promenar.infinity.data.production.populatedReferenceViews
import com.promenar.infinity.data.production.populatedSlots
import com.promenar.infinity.domain.mediagen.MediaChannel
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.media.WorkspaceImageItem
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import java.util.UUID

/**
 * 全视觉资产双模详情页：
 * - 支持角色、场景、道具、画风全部视觉参考资产；
 * - 支持「单图多视图资产卡（整卡）」与「单图单视图资产组（分张）」双模并存或二选一，当前生效模式置顶标出；
 * - 所有参考图点击均呼起全站统一多图大图查看器，支持左右滑切、双指/双击缩放与 90° 旋转。
 */
@Composable
internal fun ProductionCharacterPage(
    asset: Asset,
    production: Production,
    busy: Boolean,
    onBack: () -> Unit,
    onShot: (String, String) -> Unit,
    onRun: (ProductionUserAction) -> Unit,
    onSubmit: suspend (ProductionUserAction) -> String?,
    modifier: Modifier = Modifier,
    channels: List<MediaChannel> = emptyList(),
    channelOrder: List<String> = emptyList(),
) {
    var versionNumber by rememberSaveable(asset.id) { mutableStateOf(asset.currentVersion ?: asset.versions.maxOfOrNull { it.number }) }
    val version = asset.versions.firstOrNull { it.number == versionNumber } ?: asset.versions.maxByOrNull { it.number }
    var lookId by rememberSaveable(asset.id) { mutableStateOf(asset.looks.firstOrNull()?.id) }
    val look = asset.looks.firstOrNull { it.id == lookId } ?: asset.looks.firstOrNull()
    var newLook by remember { mutableStateOf(false) }
    var newSlot by remember { mutableStateOf(false) }
    var removeSlot by remember { mutableStateOf<ReferenceSlot?>(null) }
    var newView by remember { mutableStateOf(false) }
    var removeView by remember { mutableStateOf<ReferenceSlot?>(null) }
    var galleryState by remember { mutableStateOf<Pair<List<WorkspaceImageItem>, Int>?>(null) }
    val locations = remember(asset, production) { asset.referenceLocations(production) }
    val scheme = MaterialTheme.colorScheme

    Column(modifier.fillMaxSize()) {
        ProductionTopBar(
            title = asset.name,
            meta = listOf(asset.kind.label(), pluralStringResource(R.plurals.production_panel_asset_usage, locations.size, locations.size)),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = ProductionPagePadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!asset.description.isNullOrBlank() || !asset.userNote.isNullOrBlank()) {
                item("canon") {
                    CanonSummaryCard(
                        title = stringResource(if (asset.kind == AssetKind.CHARACTER) R.string.p2_canon_summary_character else R.string.p2_canon_summary_asset),
                        description = asset.description,
                        userNote = asset.userNote,
                    )
                }
            }

            if (asset.kind == AssetKind.CHARACTER && look != null) {
                item("character_looks") {
                    CharacterDualModeLookCard(
                        asset = asset,
                        look = look,
                        production = production,
                        busy = busy,
                        onSelectLook = { lookId = it },
                        onNewLook = { newLook = true },
                        onNewSlot = { newSlot = true },
                        onRemoveSlot = { removeSlot = it },
                        onOpenGallery = { items, start -> galleryState = items to start },
                        onRun = onRun,
                        onSubmit = onSubmit,
                    )
                }
                if (asset.versions.isNotEmpty()) {
                    item("concept_versions") {
                        SecondaryVersionsCard(
                            asset = asset,
                            selectedVersion = version,
                            onSelectVersion = { versionNumber = it },
                            onOpenGallery = { items, start -> galleryState = items to start },
                        )
                    }
                }
            } else if (asset.kind == AssetKind.CHARACTER) {
                if (version != null) {
                    item("character_versions") {
                        VersionReviewCard(
                            asset = asset,
                            version = version,
                            production = production,
                            busy = busy,
                            onSelectVersion = { versionNumber = it },
                            onOpenGallery = { items, start -> galleryState = items to start },
                            onRun = onRun,
                            onSubmit = onSubmit,
                        )
                    }
                }
                item("looks_empty_guide") {
                    ProductionSection(stringResource(R.string.p2_look_pack_title)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(R.string.p2_no_looks_yet_title), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(if (version != null) R.string.p2_no_looks_use_version else R.string.p2_no_looks_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            InfinityPillButton(
                                text = stringResource(R.string.p2_new_look),
                                onClick = { newLook = true },
                                tone = InfinityPillTone.NEUTRAL,
                                icon = Icons.Rounded.Add,
                                enabled = !busy,
                                compact = true,
                            )
                        }
                    }
                }
            } else {
                item("visual_asset_dual_mode") {
                    VisualAssetDualModeCard(
                        asset = asset,
                        version = version,
                        production = production,
                        busy = busy,
                        onSelectVersion = { versionNumber = it },
                        onNewView = { newView = true },
                        onRemoveView = { removeView = it },
                        onOpenGallery = { items, start -> galleryState = items to start },
                        onRun = onRun,
                        onSubmit = onSubmit,
                    )
                }
            }

            if (asset.kind == AssetKind.CHARACTER) {
                item("voice") {
                    CharacterVoiceSection(asset)
                }
            }

            item("references") {
                ShotReferencesSection(
                    asset = asset,
                    production = production,
                    locations = locations,
                    channels = channels,
                    channelOrder = channelOrder,
                    onShot = onShot,
                )
            }
        }
    }

    galleryState?.let { (items, start) ->
        ProductionImageGroupViewer(
            items = items,
            startIndex = start,
            onDismiss = { galleryState = null },
        )
    }

    if (newLook) {
        ProductionTextDialog(
            title = stringResource(R.string.p2_new_look),
            label = stringResource(R.string.p2_name),
            initial = "",
            confirmText = stringResource(R.string.production_panel_save),
            maxLength = 100,
            minLines = 1,
            fieldTag = "p2_look_name",
            onDismiss = { newLook = false },
            onSubmit = { name ->
                CharacterLook(UUID.randomUUID().toString(), name).let { created ->
                    onSubmit(ProductionUserAction.AddLook(asset.id, created)).also {
                        if (it == null) lookId = created.id
                    }
                }
            },
            allowEmpty = false,
        )
    }
    if (newSlot && look != null) {
        ProductionTextDialog(
            title = stringResource(R.string.p2_add_slot),
            label = stringResource(R.string.p2_name),
            initial = "",
            confirmText = stringResource(R.string.production_panel_save),
            maxLength = 100,
            minLines = 1,
            fieldTag = "p2_slot_name",
            onDismiss = { newSlot = false },
            onSubmit = { name ->
                onSubmit(ProductionUserAction.AddReferenceSlot(asset.id, look.id, ReferenceSlot(UUID.randomUUID().toString(), name)))
            },
            allowEmpty = false,
        )
    }
    removeSlot?.let { slot ->
        InfinityConfirmDialog(
            title = stringResource(R.string.p2_remove_slot),
            message = slot.displayName(),
            confirmText = stringResource(R.string.p2_remove_slot),
            onConfirm = {
                look?.let { onRun(ProductionUserAction.RemoveReferenceSlot(asset.id, it.id, slot.id)) }
                removeSlot = null
            },
            onCancel = { removeSlot = null },
            isDestructive = true,
        )
    }
    if (newView) {
        ProductionTextDialog(
            title = stringResource(R.string.p2_add_view),
            label = stringResource(R.string.p2_name),
            initial = "",
            confirmText = stringResource(R.string.production_panel_save),
            maxLength = 100,
            minLines = 1,
            fieldTag = "p2_view_name",
            onDismiss = { newView = false },
            onSubmit = { name ->
                onSubmit(ProductionUserAction.AddAssetView(asset.id, ReferenceSlot(UUID.randomUUID().toString(), name)))
            },
            allowEmpty = false,
        )
    }
    removeView?.let { slot ->
        InfinityConfirmDialog(
            title = stringResource(R.string.p2_remove_view),
            message = slot.displayName(),
            confirmText = stringResource(R.string.p2_remove_view),
            onConfirm = {
                onRun(ProductionUserAction.RemoveAssetView(asset.id, slot.id))
                removeView = null
            },
            onCancel = { removeView = null },
            isDestructive = true,
        )
    }
}

@Composable
private fun CanonSummaryCard(
    title: String,
    description: String?,
    userNote: String?,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
            )
            description?.takeIf(String::isNotBlank)?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
            }
            userNote?.takeIf(String::isNotBlank)?.let {
                NoteBlock(stringResource(R.string.production_panel_section_user_note), it)
            }
        }
    }
}

@Composable
private fun CharacterDualModeLookCard(
    asset: Asset,
    look: CharacterLook,
    production: Production,
    busy: Boolean,
    onSelectLook: (String) -> Unit,
    onNewLook: () -> Unit,
    onNewSlot: () -> Unit,
    onRemoveSlot: (ReferenceSlot) -> Unit,
    onOpenGallery: (List<WorkspaceImageItem>, Int) -> Unit,
    onRun: (ProductionUserAction) -> Unit,
    onSubmit: suspend (ProductionUserAction) -> String?,
) {
    val scheme = MaterialTheme.colorScheme
    val target = ReviewTarget.assetLook(asset.id, look.id)
    val fallbackChip = ProductionChipSpec(asset.status.label(), asset.status.tone())
    val lookChip = production.reviewChipSpec(target, settled = look.locked) ?: fallbackChip
    val populatedSlots = look.populatedSlots()
    val sheetPath = look.sheetPath?.takeIf(String::isNotBlank)
    val sheetPrimary = populatedSlots.isEmpty() && sheetPath != null
    val slotNames = look.slots.associate { it.id to it.displayName() }
    val galleryItems = remember(asset.name, look, sheetPrimary, slotNames) {
        buildLookGalleryItems(asset.name, look, sheetFirst = sheetPrimary, slotNames = slotNames)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.p2_look_pack_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip(lookChip.text, lookChip.tone)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfinitySegmentedControl(
                    segments = asset.looks.map { InfinitySegment(it.id, it.name) },
                    selected = look.id,
                    onSelect = onSelectLook,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(
                    onClick = onNewLook,
                    enabled = !busy,
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = scheme.surfaceContainerHigh,
                        contentColor = scheme.onSurface,
                    ),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.p2_new_look), modifier = Modifier.size(20.dp))
                }
            }

            val separateSection: @Composable () -> Unit = {
                if (sheetPrimary) {
                    EmptyModeCollapseBar(
                        sectionTitle = stringResource(R.string.p2_mode_separate_title),
                        subtitle = stringResource(R.string.p2_mode_separate_unregistered_sheet_active),
                        active = false,
                        showAddButton = true,
                        enabled = !busy,
                        onAdd = onNewSlot,
                    )
                } else {
                    ViewsGridBlock(
                        title = stringResource(R.string.p2_mode_separate_title),
                        active = false,
                        readyBadge = stringResource(R.string.p2_mode_separate_ready_badge),
                        slots = look.slots,
                        isDefault = { it.id in ProductionRules.defaultSlotOrder },
                        addLabel = stringResource(R.string.p2_add_slot_short),
                        freeHint = null,
                        enabled = !busy,
                        onOpenSlot = { slot ->
                            slot.path?.takeIf(String::isNotBlank)?.let { path ->
                                val idx = galleryItems.indexOfFirst { it.path == path }.coerceAtLeast(0)
                                if (galleryItems.isNotEmpty()) onOpenGallery(galleryItems, idx)
                            }
                        },
                        onRemoveSlot = onRemoveSlot,
                        onAddClick = onNewSlot,
                    )
                }
            }

            val sheetSection: @Composable () -> Unit = {
                if (sheetPath != null) {
                    val sheetTitle = look.name
                    val sheetSubtitle = look.sheetNote?.takeIf(String::isNotBlank) ?: stringResource(R.string.p2_mode_sheet_default_subtitle)
                    val openSheet = {
                        val idx = galleryItems.indexOfFirst { it.path == sheetPath }.coerceAtLeast(0)
                        if (galleryItems.isNotEmpty()) onOpenGallery(galleryItems, idx)
                    }
                    if (sheetPrimary) {
                        SheetFeaturedCard(
                            path = sheetPath,
                            title = sheetTitle,
                            subtitle = sheetSubtitle,
                            active = false,
                            onOpen = openSheet,
                        )
                    } else {
                        SheetCompactCard(
                            path = sheetPath,
                            title = sheetTitle,
                            subtitle = sheetSubtitle,
                            active = false,
                            onOpen = openSheet,
                        )
                    }
                } else {
                    EmptyModeCollapseBar(
                        sectionTitle = stringResource(R.string.p2_mode_sheet_title),
                        subtitle = stringResource(R.string.p2_mode_sheet_unregistered_hint),
                        active = false,
                        showAddButton = false,
                        enabled = false,
                        onAdd = {},
                    )
                }
            }

            if (sheetPrimary) {
                sheetSection()
                separateSection()
            } else {
                separateSection()
                sheetSection()
            }

            look.note?.takeIf(String::isNotBlank)?.let { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }

            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
            ReviewFooterBlock(
                production = production,
                target = target,
                label = listOf(asset.name, look.name),
                settled = look.locked,
                busy = busy,
                onRun = onRun,
                onSubmit = onSubmit,
            )
        }
    }
}

@Composable
private fun VisualAssetDualModeCard(
    asset: Asset,
    version: AssetVersion?,
    production: Production,
    busy: Boolean,
    onSelectVersion: (Int) -> Unit,
    onNewView: () -> Unit,
    onRemoveView: (ReferenceSlot) -> Unit,
    onOpenGallery: (List<WorkspaceImageItem>, Int) -> Unit,
    onRun: (ProductionUserAction) -> Unit,
    onSubmit: suspend (ProductionUserAction) -> String?,
) {
    val scheme = MaterialTheme.colorScheme
    val populatedViews = asset.populatedReferenceViews()
    val sheetPrimary = populatedViews.isEmpty() && version != null
    val reviewUsesViews = (asset.referenceViews.isNotEmpty() && (asset.versions.isEmpty() || production.referenceInputMode != ReferenceInputMode.SHEET)) || asset.versions.isEmpty()
    val reviewTarget = if (reviewUsesViews || version == null) {
        ReviewTarget.assetViews(asset.id)
    } else {
        ReviewTarget.assetVersion(asset.id, version.number)
    }
    val settled = if (reviewUsesViews || version == null) {
        asset.viewsLocked
    } else {
        asset.status == AssetStatus.LOCKED && version.number == asset.currentVersion
    }
    val reviewLabel = if (reviewUsesViews || version == null) {
        listOf(asset.name)
    } else {
        listOf(asset.name, stringResource(R.string.production_panel_version, version.number))
    }
    val fallbackChip = ProductionChipSpec(asset.status.label(), asset.status.tone())
    val headerChip = production.reviewChipSpec(reviewTarget, settled = settled) ?: fallbackChip
    val sortedVersions = remember(asset.versions) { asset.versions.sortedBy { it.number } }
    val versionLabels = sortedVersions.map { it.number to stringResource(R.string.production_panel_version, it.number) }
    val galleryItems = remember(asset.name, asset.referenceViews, sortedVersions, sheetPrimary) {
        buildVisualAssetGalleryItems(asset.name, asset.referenceViews, sortedVersions, sheetFirst = sheetPrimary)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.p2_visual_ref_pack_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip(headerChip.text, headerChip.tone)
            }

            val separateSection: @Composable () -> Unit = {
                if (sheetPrimary && asset.referenceViews.isEmpty()) {
                    EmptyModeCollapseBar(
                        sectionTitle = stringResource(R.string.p2_mode_separate_title),
                        subtitle = stringResource(R.string.p2_mode_separate_unregistered_sheet_active),
                        active = false,
                        showAddButton = true,
                        enabled = !busy,
                        onAdd = onNewView,
                    )
                } else {
                    ViewsGridBlock(
                        title = stringResource(R.string.p2_mode_separate_title),
                        active = false,
                        readyBadge = stringResource(R.string.p2_mode_separate_ready_badge),
                        slots = asset.referenceViews,
                        isDefault = { false },
                        addLabel = stringResource(R.string.p2_add_view),
                        freeHint = stringResource(R.string.p2_free_name_hint),
                        enabled = !busy,
                        onOpenSlot = { slot ->
                            slot.path?.takeIf(String::isNotBlank)?.let { path ->
                                val idx = galleryItems.indexOfFirst { it.path == path }.coerceAtLeast(0)
                                if (galleryItems.isNotEmpty()) onOpenGallery(galleryItems, idx)
                            }
                        },
                        onRemoveSlot = onRemoveView,
                        onAddClick = onNewView,
                    )
                }
            }

            val sheetSection: @Composable () -> Unit = {
                if (version != null) {
                    val sheetTitle = asset.name
                    val sheetSubtitle = version.note?.takeIf(String::isNotBlank) ?: stringResource(R.string.p2_mode_sheet_default_subtitle)
                    val openVersionInGallery = {
                        val idx = galleryItems.indexOfFirst { it.path == version.path }.coerceAtLeast(0)
                        if (galleryItems.isNotEmpty()) onOpenGallery(galleryItems, idx)
                    }
                    if (sheetPrimary) {
                        SheetFeaturedCard(
                            path = version.path,
                            title = sheetTitle,
                            subtitle = sheetSubtitle,
                            active = false,
                            versions = versionLabels,
                            selectedVersion = version.number,
                            onSelectVersion = onSelectVersion,
                            onOpen = openVersionInGallery,
                        )
                    } else {
                        SheetCompactCard(
                            path = version.path,
                            title = sheetTitle,
                            subtitle = sheetSubtitle,
                            active = false,
                            versions = versionLabels,
                            selectedVersion = version.number,
                            onSelectVersion = onSelectVersion,
                            onOpen = openVersionInGallery,
                        )
                    }
                } else {
                    EmptyModeCollapseBar(
                        sectionTitle = stringResource(R.string.p2_mode_sheet_title),
                        subtitle = stringResource(R.string.p2_mode_sheet_unregistered_hint),
                        active = false,
                        showAddButton = false,
                        enabled = false,
                        onAdd = {},
                    )
                }
            }

            if (sheetPrimary) {
                sheetSection()
                separateSection()
            } else {
                separateSection()
                sheetSection()
            }

            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
            ReviewFooterBlock(
                production = production,
                target = reviewTarget,
                label = reviewLabel,
                settled = settled,
                busy = busy,
                onRun = onRun,
                onSubmit = onSubmit,
            )
        }
    }
}

@Composable
private fun VersionReviewCard(
    asset: Asset,
    version: AssetVersion,
    production: Production,
    busy: Boolean,
    onSelectVersion: (Int) -> Unit,
    onOpenGallery: (List<WorkspaceImageItem>, Int) -> Unit,
    onRun: (ProductionUserAction) -> Unit,
    onSubmit: suspend (ProductionUserAction) -> String?,
) {
    val scheme = MaterialTheme.colorScheme
    val sortedVersions = remember(asset.versions) { asset.versions.sortedBy { it.number } }
    val target = ReviewTarget.assetVersion(asset.id, version.number)
    val fallbackChip = ProductionChipSpec(asset.status.label(), asset.status.tone())
    val versionChip = production.reviewChipSpec(
        target,
        settled = asset.status == AssetStatus.LOCKED && version.number == asset.currentVersion,
    ) ?: fallbackChip
    val items = sortedVersions.map {
        WorkspaceImageItem(
            path = it.path,
            title = asset.name,
            subtitle = it.note,
        )
    }
    Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.production_panel_section_versions), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    sortedVersions.forEach { item ->
                        val selected = item.number == version.number
                        Surface(
                            shape = CircleShape,
                            color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
                            onClick = { onSelectVersion(item.number) },
                        ) {
                            Text(
                                stringResource(R.string.production_panel_version, item.number),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                ProductionStatusChip(versionChip.text, versionChip.tone)
            }
            SheetFeaturedCard(
                path = version.path,
                title = asset.name,
                subtitle = version.note ?: stringResource(R.string.p2_mode_sheet_default_subtitle),
                active = true,
                showHeader = false,
                onOpen = {
                    val idx = sortedVersions.indexOfFirst { it.number == version.number }.coerceAtLeast(0)
                    if (items.isNotEmpty()) onOpenGallery(items, idx)
                },
            )
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
            ReviewFooterBlock(
                production = production,
                target = target,
                label = listOf(asset.name, stringResource(R.string.production_panel_version, version.number)),
                settled = asset.status == AssetStatus.LOCKED && version.number == asset.currentVersion,
                busy = busy,
                onRun = onRun,
                onSubmit = onSubmit,
            )
        }
    }
}

@Composable
private fun SecondaryVersionsCard(
    asset: Asset,
    selectedVersion: AssetVersion?,
    onSelectVersion: (Int) -> Unit,
    onOpenGallery: (List<WorkspaceImageItem>, Int) -> Unit,
) {
    val sortedVersions = remember(asset.versions) { asset.versions.sortedBy { it.number } }
    val version = selectedVersion ?: return
    val versionLabels = sortedVersions.map { it.number to stringResource(R.string.production_panel_version, it.number) }
    val galleryItems = sortedVersions.map { WorkspaceImageItem(path = it.path, title = asset.name, subtitle = it.note) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetCompactCard(
                path = version.path,
                title = asset.name,
                subtitle = version.note ?: stringResource(R.string.p2_concept_versions_secondary),
                active = false,
                headerOverride = stringResource(R.string.p2_concept_versions_secondary),
                versions = versionLabels,
                selectedVersion = version.number,
                onSelectVersion = onSelectVersion,
                onOpen = {
                    val idx = sortedVersions.indexOfFirst { it.number == version.number }.coerceAtLeast(0)
                    if (galleryItems.isNotEmpty()) onOpenGallery(galleryItems, idx)
                },
            )
        }
    }
}

@Composable
private fun ReviewFooterBlock(
    production: Production,
    target: ReviewTarget,
    label: List<String>,
    settled: Boolean,
    busy: Boolean,
    onRun: (ProductionUserAction) -> Unit,
    onSubmit: suspend (ProductionUserAction) -> String?,
) {
    ProductionReviewBlock(
        production = production,
        target = target,
        label = label,
        busy = busy,
        onRun = onRun,
        onSubmit = onSubmit,
        settled = settled,
        showStatus = false,
    )
}

@Composable
private fun ModeSectionHeader(
    title: String,
    badge: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Surface(
            shape = CircleShape,
            color = if (active) scheme.primaryContainer else scheme.surfaceContainerHigh,
        ) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SheetFeaturedCard(
    path: String,
    title: String,
    subtitle: String,
    active: Boolean,
    versions: List<Pair<Int, String>> = emptyList(),
    selectedVersion: Int? = null,
    showHeader: Boolean = true,
    onSelectVersion: (Int) -> Unit = {},
    onOpen: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeader) {
            ModeSectionHeader(
                title = stringResource(R.string.p2_mode_sheet_title),
                badge = stringResource(if (active) R.string.p2_mode_active_badge else R.string.p2_mode_sheet_ready_badge),
                active = active,
            )
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = scheme.surfaceContainer,
            border = if (active) BorderStroke(0.75.dp, scheme.primary.copy(alpha = 0.45f)) else null,
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (versions.isNotEmpty()) {
                    VersionChipsRow(versions, selectedVersion, onSelectVersion)
                }
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val overlayContent = MaterialTheme.infinityDomainColors.overlayContent
                    key(path) {
                        ProductionThumbnail(path, Modifier.fillMaxSize(), contentDescription = title)
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = scheme.scrim.copy(alpha = 0.52f),
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Rounded.ViewQuilt, contentDescription = null, tint = overlayContent, modifier = Modifier.size(12.dp))
                            Text(stringResource(R.string.p2_mode_sheet_tag), style = MaterialTheme.typography.labelSmall, color = overlayContent)
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = scheme.scrim.copy(alpha = 0.52f),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(26.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.OpenInFull, contentDescription = stringResource(R.string.p2_view_fullscreen), tint = overlayContent, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetCompactCard(
    path: String,
    title: String,
    subtitle: String,
    active: Boolean,
    headerOverride: String? = null,
    versions: List<Pair<Int, String>> = emptyList(),
    selectedVersion: Int? = null,
    onSelectVersion: (Int) -> Unit = {},
    onOpen: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (headerOverride != null) {
            Text(
                text = headerOverride,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurfaceVariant,
            )
        } else {
            ModeSectionHeader(
                title = stringResource(R.string.p2_mode_sheet_title),
                badge = stringResource(if (active) R.string.p2_mode_active_badge else R.string.p2_mode_sheet_ready_badge),
                active = active,
            )
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = scheme.surfaceContainer,
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (versions.isNotEmpty()) {
                    VersionChipsRow(versions, selectedVersion, onSelectVersion)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.width(104.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        key(path) {
                            ProductionThumbnail(path, Modifier.fillMaxSize(), contentDescription = title)
                        }
                        Surface(
                            shape = CircleShape,
                            color = scheme.scrim.copy(alpha = 0.5f),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(20.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.OpenInFull, contentDescription = stringResource(R.string.p2_view_fullscreen), tint = MaterialTheme.infinityDomainColors.overlayContent, modifier = Modifier.size(11.dp))
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionChipsRow(
    versions: List<Pair<Int, String>>,
    selectedVersion: Int?,
    onSelectVersion: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.production_panel_section_versions), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        versions.forEach { (number, label) ->
            val selected = number == selectedVersion
            Surface(
                shape = CircleShape,
                color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
                onClick = { onSelectVersion(number) },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyModeCollapseBar(
    sectionTitle: String,
    subtitle: String,
    active: Boolean,
    showAddButton: Boolean,
    enabled: Boolean,
    onAdd: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeSectionHeader(
            title = sectionTitle,
            badge = stringResource(R.string.p2_mode_unregistered_badge),
            active = false,
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = scheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (showAddButton) {
                    InfinityPillButton(
                        text = stringResource(R.string.p2_supplement),
                        onClick = onAdd,
                        tone = InfinityPillTone.NEUTRAL,
                        icon = Icons.Rounded.Add,
                        enabled = enabled,
                        compact = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewsGridBlock(
    title: String,
    active: Boolean,
    readyBadge: String,
    slots: List<ReferenceSlot>,
    isDefault: (ReferenceSlot) -> Boolean,
    addLabel: String,
    freeHint: String?,
    enabled: Boolean,
    onOpenSlot: (ReferenceSlot) -> Unit,
    onRemoveSlot: (ReferenceSlot) -> Unit,
    onAddClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeSectionHeader(
            title = title,
            badge = if (active) stringResource(R.string.p2_mode_active_badge) else readyBadge,
            active = active,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 600.dp) 5 else 3
            val cells: List<ReferenceSlot?> = slots + listOf(null)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cells.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { slot ->
                            if (slot != null) {
                                ViewSlotCard(
                                    slot = slot,
                                    isDefaultSlot = isDefault(slot),
                                    enabled = enabled,
                                    modifier = Modifier.weight(1f),
                                    onOpen = { onOpenSlot(slot) },
                                    onRemove = { onRemoveSlot(slot) },
                                )
                            } else {
                                AddViewCard(
                                    label = addLabel,
                                    hint = freeHint,
                                    enabled = enabled,
                                    modifier = Modifier.weight(1f),
                                    onClick = onAddClick,
                                )
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewSlotCard(
    slot: ReferenceSlot,
    isDefaultSlot: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val hasImage = !slot.path.isNullOrBlank()
    val name = slot.displayName()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainer,
        onClick = onOpen,
        enabled = hasImage,
        modifier = modifier,
    ) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(10.dp))
                    .background(scheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (hasImage) {
                    key(slot.path) {
                        ProductionThumbnail(slot.path, Modifier.fillMaxSize(), contentDescription = name)
                    }
                    Surface(
                        shape = CircleShape,
                        color = scheme.scrim.copy(alpha = 0.48f),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(22.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.OpenInFull, contentDescription = stringResource(R.string.p2_view_fullscreen), tint = MaterialTheme.infinityDomainColors.overlayContent, modifier = Modifier.size(12.dp))
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Image,
                            contentDescription = stringResource(R.string.p2_pending_image),
                            tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = stringResource(R.string.p2_pending_image),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (!isDefaultSlot) {
                    Surface(
                        shape = CircleShape,
                        color = scheme.inverseSurface.copy(alpha = 0.86f),
                        onClick = onRemove,
                        enabled = enabled,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.p2_remove_slot),
                                tint = scheme.inverseOnSurface,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = slot.note?.takeIf(String::isNotBlank) ?: stringResource(R.string.p2_no_slot_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AddViewCard(
    label: String,
    hint: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val outline = scheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainer,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.75f), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRoundRect(
                        color = outline,
                        cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                        style = Stroke(
                            width = 1.2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                        ),
                    )
                }
                Icon(Icons.Rounded.Add, contentDescription = label, tint = scheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = hint ?: stringResource(R.string.p2_optional_stage),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CharacterVoiceSection(asset: Asset) {
    val scheme = MaterialTheme.colorScheme
    ProductionSection(stringResource(R.string.p2_reference_voice), Modifier.padding(top = 4.dp)) {
        val voice = asset.referenceVoice
        if (voice?.path == null && voice?.transcript.isNullOrBlank() && voice?.voiceSetting == null && voice?.tone == null) {
            ProductionMutedRow(stringResource(R.string.p2_no_voice))
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (voice?.path != null) {
                    ProductionMediaReference(voice.path, Modifier.fillMaxWidth(), alt = asset.name)
                    ProductionVoiceWaveform(voice.path)
                } else {
                    Text(stringResource(R.string.p2_no_voice), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                voice?.transcript?.takeIf(String::isNotBlank)?.let { Text("「$it」", style = MaterialTheme.typography.bodyMedium) }
                val fields = listOfNotNull(voice?.voiceSetting?.let { R.string.p2_timbre to it }, voice?.tone?.let { R.string.p2_field_tone to it })
                if (fields.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.outlineVariant)
                    fields.forEach { (label, value) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
                            Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShotReferencesSection(
    asset: Asset,
    production: Production,
    locations: List<ShotLocation>,
    channels: List<MediaChannel>,
    channelOrder: List<String>,
    onShot: (String, String) -> Unit,
) {
    ProductionSection(stringResource(R.string.p2_shot_references), Modifier.padding(top = 4.dp)) {
        if (locations.isEmpty()) ProductionMutedRow(stringResource(R.string.p2_no_references))
        locations.forEach { location ->
            val shotChannel = resolvedShotChannel(location.shot, channels, channelOrder, production.defaultVideoChannelId)
            val shotMode = production.effectiveReferenceInputMode(shotChannel)
            val refValue = when {
                asset.kind == AssetKind.CHARACTER -> {
                    val appearance = location.shot.appearances.firstOrNull { it.characterAssetId == asset.id }
                    val usedLook = asset.looks.firstOrNull { it.id == appearance?.lookId }
                    when {
                        usedLook != null && shotMode == ReferenceInputMode.SHEET && !usedLook.sheetPath.isNullOrBlank() ->
                            listOf(usedLook.name, stringResource(R.string.p2_using_sheet_image)).joinToString(" · ") // wrap-check: allow 引用行尾固定单行，保留镜头标题空间
                        usedLook != null ->
                            listOf(usedLook.name, stringResource(R.string.p2_slot_count, appearance?.referenceSlotIds?.size ?: 0)).joinToString(" · ") // wrap-check: allow 引用行尾固定单行，保留镜头标题空间
                        asset.currentPath() != null -> stringResource(R.string.p2_using_version_image)
                        else -> stringResource(R.string.p2_none)
                    }
                }
                shotMode == ReferenceInputMode.SEPARATE && asset.populatedReferenceViews().isNotEmpty() ->
                    stringResource(R.string.p2_views_count, asset.populatedReferenceViews().size)
                asset.currentPath() != null -> stringResource(R.string.p2_using_sheet_image)
                asset.populatedReferenceViews().isNotEmpty() ->
                    stringResource(R.string.p2_views_count, asset.populatedReferenceViews().size)
                else -> stringResource(R.string.p2_none)
            }
            ProductionRow(
                title = location.shot.title,
                titleSegments = listOf(location.shot.code, location.shot.title),
                value = refValue,
                onClick = { onShot(location.episode.id, location.shot.code) },
            )
        }
    }
}

private fun buildLookGalleryItems(
    assetName: String,
    look: CharacterLook,
    sheetFirst: Boolean,
    slotNames: Map<String, String>,
): List<WorkspaceImageItem> {
    val slotItems = look.slots.mapNotNull { slot ->
        val path = slot.path?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val sub = listOfNotNull(assetName, look.name, slot.note?.takeIf(String::isNotBlank)).joinToString(" · ") // wrap-check: allow 查看器顶部副标题单行截断
        WorkspaceImageItem(path = path, title = slotNames[slot.id] ?: slot.name, subtitle = sub)
    }
    val sheetItem = look.sheetPath?.takeIf(String::isNotBlank)?.let { sheet ->
        val sub = listOfNotNull(assetName, look.name, look.sheetNote?.takeIf(String::isNotBlank)).joinToString(" · ") // wrap-check: allow 查看器顶部副标题单行截断
        WorkspaceImageItem(path = sheet, title = look.name, subtitle = sub)
    }
    return if (sheetFirst) {
        listOfNotNull(sheetItem) + slotItems
    } else {
        slotItems + listOfNotNull(sheetItem)
    }
}

private fun buildVisualAssetGalleryItems(
    assetName: String,
    views: List<ReferenceSlot>,
    sortedVersions: List<AssetVersion>,
    sheetFirst: Boolean,
): List<WorkspaceImageItem> {
    val viewItems = views.mapNotNull { slot ->
        val path = slot.path?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val sub = listOfNotNull(assetName, slot.note?.takeIf(String::isNotBlank)).joinToString(" · ") // wrap-check: allow 查看器顶部副标题单行截断
        WorkspaceImageItem(path = path, title = slot.name, subtitle = sub)
    }
    val versionItems = sortedVersions.map { ver ->
        WorkspaceImageItem(path = ver.path, title = "$assetName · v${ver.number}", subtitle = ver.note) // wrap-check: allow 查看器顶部单版本标题单行截断
    }
    return if (sheetFirst) {
        versionItems + viewItems
    } else {
        viewItems + versionItems
    }
}
