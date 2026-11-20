package com.promenar.infinity.ui.production

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Info
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.*
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.media.MediaCardStyle
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import java.util.UUID

@Composable
internal fun ProductionCharacterPage(asset: Asset, production: Production, busy: Boolean, onBack: () -> Unit, onShot: (String, String) -> Unit, onRun: (ProductionUserAction) -> Unit, onSubmit: suspend (ProductionUserAction) -> String?, modifier: Modifier = Modifier) {
    var versionNumber by rememberSaveable(asset.id) { mutableStateOf(asset.currentVersion ?: asset.versions.maxOfOrNull { it.number }) }
    val version = asset.versions.firstOrNull { it.number == versionNumber } ?: asset.versions.maxByOrNull { it.number }
    var lookId by rememberSaveable(asset.id) { mutableStateOf(asset.looks.firstOrNull()?.id) }
    val look = asset.looks.firstOrNull { it.id == lookId } ?: asset.looks.firstOrNull()
    var newLook by remember { mutableStateOf(false) }
    var newSlot by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<ReferenceSlot?>(null) }
    val locations = remember(asset, production) { asset.referenceLocations(production) }
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Column(modifier.fillMaxSize()) {
        ProductionTopBar(
            title = asset.name,
            meta = listOf(asset.kind.label(), pluralStringResource(R.plurals.production_panel_asset_usage, locations.size, locations.size)),
            onBack = onBack,
        )
        LazyColumn(Modifier.weight(1f), contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("overview") {
                Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.production_panel_section_versions), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                            Row(
                                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                asset.versions.sortedBy { it.number }.forEach { item ->
                                    val selected = item.number == version?.number
                                    Surface(
                                        shape = CircleShape,
                                        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
                                        onClick = { versionNumber = item.number },
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
                            ProductionStatusChip(asset.status.label(), asset.status.tone())
                        }
                        if (version != null) {
                            key(version.path) {
                                ProductionMediaReference(path = version.path, alt = asset.name, style = MediaCardStyle.TOOL, modifier = Modifier.fillMaxWidth())
                            }
                            version.note?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                        } else {
                            ProductionMutedRow(stringResource(R.string.production_panel_no_assets))
                        }
                        asset.description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface) }
                        asset.userNote?.takeIf(String::isNotBlank)?.let { NoteBlock(stringResource(R.string.production_panel_section_user_note), it) }
                        if (version != null) {
                            val target = ReviewTarget.assetVersion(asset.id, version.number)
                            ProductionReviewBlock(
                                production = production,
                                target = target,
                                label = listOf(asset.name, stringResource(R.string.production_panel_version, version.number)),
                                busy = busy,
                                onRun = onRun,
                                onSubmit = onSubmit,
                                settled = asset.status == AssetStatus.LOCKED && version.number == asset.currentVersion,
                            )
                            val unsentCount = LocalProductionSend.current?.count ?: 0
                            if (unsentCount > 0 && production.unsentReviews().any { it.target == target }) {
                                Surface(shape = RoundedCornerShape(10.dp), color = domain.infoContainer.copy(alpha = 0.55f), modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = domain.onInfoContainer)
                                        Text(stringResource(R.string.p2_review_staged_hint, unsentCount), style = MaterialTheme.typography.labelMedium, color = domain.onInfoContainer)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item("looks") {
                if (asset.looks.isEmpty()) {
                    ProductionSection(stringResource(R.string.p2_look_pack_optional)) {
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
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProductionSectionHeader(stringResource(R.string.p2_look_pack_optional))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            InfinitySegmentedControl(asset.looks.map { InfinitySegment(it.id, it.name) }, selected = look?.id, onSelect = { lookId = it }, enabled = !busy, modifier = Modifier.weight(1f))
                            IconButton(onClick = { newLook = true }, enabled = !busy) { Icon(Icons.Rounded.Add, stringResource(R.string.p2_new_look)) }
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val columns = if (maxWidth >= 600.dp) 5 else 3
                            val cells: List<ReferenceSlot?> = look?.slots.orEmpty() + listOf(null)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                cells.chunked(columns).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.forEach { slot ->
                                            if (slot != null) SlotCard(slot, !busy, Modifier.weight(1f)) { remove = slot }
                                            else AddSlotCard(!busy && look != null, Modifier.weight(1f)) { newSlot = true }
                                        }
                                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                        ProductionSection(null) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(Modifier.width(112.dp).height(56.dp).clip(RoundedCornerShape(10.dp)), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                    ProductionRules.defaultSlotOrder.forEach { id -> ProductionThumbnail(look?.slots?.firstOrNull { it.id == id }?.path, Modifier.weight(1f).fillMaxHeight()) }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.p2_composite), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.p2_composite_detail), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            item("voice") {
                ProductionSection(stringResource(R.string.p2_reference_voice), Modifier.padding(top = 8.dp)) {
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
            item("references") {
                ProductionSection(stringResource(R.string.p2_shot_references), Modifier.padding(top = 8.dp)) {
                    if (locations.isEmpty()) ProductionMutedRow(stringResource(R.string.p2_no_references))
                    locations.forEach { location ->
                        val appearance = location.shot.appearances.firstOrNull { it.characterAssetId == asset.id }
                        val usedLook = asset.looks.firstOrNull { it.id == appearance?.lookId }
                        val refValue = when {
                            usedLook != null -> listOf(usedLook.name, stringResource(R.string.p2_slot_count, appearance?.referenceSlotIds?.size ?: 0))
                                .joinToString(" · ") // wrap-check: allow 引用行尾固定单行，保留镜头标题空间
                            asset.currentPath() != null -> stringResource(R.string.p2_using_version_image)
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
        }
    }
    if (newLook) ProductionTextDialog(stringResource(R.string.p2_new_look), stringResource(R.string.p2_name), "", stringResource(R.string.production_panel_save), 100, 1, "p2_look_name", { newLook = false }, { name -> CharacterLook(UUID.randomUUID().toString(), name).let { created -> onSubmit(ProductionUserAction.AddLook(asset.id, created)).also { if (it == null) lookId = created.id } } }, allowEmpty = false)
    if (newSlot && look != null) ProductionTextDialog(stringResource(R.string.p2_add_slot), stringResource(R.string.p2_name), "", stringResource(R.string.production_panel_save), 100, 1, "p2_slot_name", { newSlot = false }, { name -> onSubmit(ProductionUserAction.AddReferenceSlot(asset.id, look.id, ReferenceSlot(UUID.randomUUID().toString(), name))) }, allowEmpty = false)
    remove?.let { slot ->
        InfinityConfirmDialog(stringResource(R.string.p2_remove_slot), slot.name, stringResource(R.string.p2_remove_slot), onConfirm = { look?.let { onRun(ProductionUserAction.RemoveReferenceSlot(asset.id, it.id, slot.id)) }; remove = null }, onCancel = { remove = null }, isDestructive = true)
    }
}

/** 参考位卡片：3:4 图、名称；默认位标“默认”，自定义位右上角可移除。 */
@Composable
private fun SlotCard(slot: ReferenceSlot, enabled: Boolean, modifier: Modifier, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                ProductionThumbnail(slot.path, Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(10.dp)), slot.displayName())
                if (slot.id !in ProductionRules.defaultSlotOrder) Surface(
                    shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                    onClick = onRemove, enabled = enabled,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, stringResource(R.string.p2_remove_slot), Modifier.size(16.dp), MaterialTheme.colorScheme.inverseOnSurface) }
                }
            }
            Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(slot.displayName(), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (slot.id in ProductionRules.defaultSlotOrder) Text(stringResource(R.string.p2_default), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AddSlotCard(enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, onClick = onClick, enabled = enabled, modifier = modifier) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(0.75f).border(1.dp, outline, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, stringResource(R.string.p2_add_slot), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(stringResource(R.string.p2_reference_slots), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}
