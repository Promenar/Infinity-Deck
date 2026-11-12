package com.promenar.infinity.ui.production

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.*
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.theme.InfinitySpacing
import java.util.UUID

@Composable
internal fun ProductionCharacterPage(asset: Asset, production: Production, busy: Boolean, onBack: () -> Unit, onShot: (String, String) -> Unit, onRun: (ProductionUserAction) -> Unit, onSubmit: suspend (ProductionUserAction) -> String?, modifier: Modifier = Modifier) {
    var lookId by rememberSaveable(asset.id) { mutableStateOf(asset.looks.firstOrNull()?.id) }
    val look = asset.looks.firstOrNull { it.id == lookId } ?: asset.looks.firstOrNull()
    var newLook by remember { mutableStateOf(false) }
    var newSlot by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<ReferenceSlot?>(null) }
    var assetReview by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        ProductionTopBar(asset.name, listOf(asset.kind.label(), stringResource(R.string.p2_reference_pack_short)), onBack) {
            ProductionStatusChip(asset.status.label(), asset.status.tone())
            IconButton(onClick = { assetReview = true }, enabled = !busy) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.p2_more)) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("looks") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (asset.looks.isNotEmpty()) InfinitySegmentedControl(asset.looks.map { InfinitySegment(it.id, it.name) }, selected = look?.id, onSelect = { lookId = it }, enabled = !busy, modifier = Modifier.weight(1f))
                    else Text(stringResource(R.string.p2_missing_look), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = InfinitySpacing.RowHorizontal))
                    IconButton(onClick = { newLook = true }, enabled = !busy) { Icon(Icons.Rounded.Add, stringResource(R.string.p2_new_look)) }
                }
            }
            item("slots") {
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
            }
            item("composite") {
                ProductionSection(null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(Modifier.width(112.dp).height(56.dp).clip(RoundedCornerShape(10.dp)), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            ProductionRules.defaultSlotOrder.forEach { id -> ProductionThumbnail(look?.slots?.firstOrNull { it.id == id }?.path, Modifier.weight(1f).fillMaxHeight()) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.p2_composite), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.p2_composite_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item("voice") {
                ProductionSection(stringResource(R.string.p2_reference_voice), Modifier.padding(top = 8.dp)) {
                    val voice = asset.referenceVoice
                    Column(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (voice?.path != null) {
                            ProductionMediaReference(voice.path, Modifier.fillMaxWidth(), alt = asset.name)
                            ProductionVoiceWaveform(voice.path)
                        } else Text(stringResource(R.string.p2_no_voice), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        voice?.transcript?.takeIf(String::isNotBlank)?.let { Text("「$it」", style = MaterialTheme.typography.bodyMedium) }
                        val fields = listOfNotNull(voice?.voiceSetting?.let { R.string.p2_timbre to it }, voice?.tone?.let { R.string.p2_field_tone to it })
                        if (fields.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            fields.forEach { (label, value) ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
                                    Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            item("references") {
                ProductionSection(stringResource(R.string.p2_shot_references), Modifier.padding(top = 8.dp)) {
                    val locations = asset.referenceLocations(production)
                    if (locations.isEmpty()) ProductionMutedRow(stringResource(R.string.p2_no_references))
                    locations.forEach { location ->
                        val appearance = location.shot.appearances.firstOrNull { it.characterAssetId == asset.id }
                        val usedLook = asset.looks.firstOrNull { it.id == appearance?.lookId }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { onShot(location.episode.id, location.shot.code) }
                                .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(location.shot.code, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            Text(location.shot.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(
                                listOfNotNull(usedLook?.name, appearance?.let { stringResource(R.string.p2_slot_count, it.referenceSlotIds.size) }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
                        }
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
    if (assetReview) ProductionAssetSheet(asset, production, busy, { assetReview = false }, onRun, onSubmit)
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
                Modifier.fillMaxWidth().aspectRatio(0.75f).drawBehind {
                    drawRoundRect(outline, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, stringResource(R.string.p2_add_slot), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(stringResource(R.string.p2_reference_slots), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}
