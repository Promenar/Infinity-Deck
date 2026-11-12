package com.promenar.infinity.ui.production

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
        ProductionBackHeader(asset.name, stringResource(R.string.p2_reference_pack), onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
            item("looks") {
                InfinitySegmentedControl(asset.looks.map { InfinitySegment(it.id, it.name) }, selected = look?.id, onSelect = { lookId = it }, enabled = !busy)
                ProductionCommand(stringResource(R.string.p2_new_look), enabled = !busy) { newLook = true }
                ProductionValueRow(stringResource(R.string.p2_reference_review), asset.status.label(), enabled = !busy) { assetReview = true }
            }
            item("slots") {
                ProductionSectionTitle(stringResource(R.string.p2_reference_slots))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth >= 600.dp) 4 else 2
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        look?.slots.orEmpty().chunked(columns).forEach { slots ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                slots.forEach { slot ->
                                    Column(Modifier.weight(1f)) {
                                        ProductionThumbnail(slot.path, Modifier.fillMaxWidth().aspectRatio(1f), slot.displayName())
                                        Text(slot.displayName(), style = MaterialTheme.typography.labelMedium)
                                        if (slot.id in ProductionRules.defaultSlotOrder) Text(stringResource(R.string.p2_default), style = MaterialTheme.typography.labelSmall)
                                        else IconButton(onClick = { remove = slot }, enabled = !busy, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, stringResource(R.string.p2_remove_slot)) }
                                    }
                                }
                                repeat(columns - slots.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                ProductionCommand(stringResource(R.string.p2_add_slot), enabled = !busy && look != null) { newSlot = true }
            }
            item("composite") {
                ProductionBlock(stringResource(R.string.p2_composite)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ProductionRules.defaultSlotOrder.forEach { id -> ProductionThumbnail(look?.slots?.firstOrNull { it.id == id }?.path, Modifier.weight(1f).aspectRatio(0.65f)) }
                    }
                }
            }
            item("voice") {
                ProductionBlock(stringResource(R.string.p2_reference_voice)) {
                    val voice = asset.referenceVoice
                    if (voice?.path != null) {
                        ProductionMediaReference(voice.path, Modifier.fillMaxWidth(), alt = asset.name)
                        ProductionVoiceWaveform(voice.path)
                    } else Text(stringResource(R.string.p2_no_voice))
                    voice?.transcript?.let { ProductionValueRow(stringResource(R.string.p2_transcript), it) }
                    voice?.voiceSetting?.let { ProductionValueRow(stringResource(R.string.p2_timbre), it) }
                    voice?.tone?.let { ProductionValueRow(stringResource(R.string.p2_field_tone), it) }
                }
            }
            item("references") {
                ProductionBlock(stringResource(R.string.p2_shot_references)) {
                    val locations = asset.referenceLocations(production)
                    if (locations.isEmpty()) Text(stringResource(R.string.p2_no_references))
                    locations.forEach { location ->
                        val appearance = location.shot.appearances.firstOrNull { it.characterAssetId == asset.id }
                        val usedLook = asset.looks.firstOrNull { it.id == appearance?.lookId }
                        ProductionValueRow("${location.episode.id} · ${location.shot.code} · ${location.shot.title}", listOfNotNull(usedLook?.name, appearance?.let { stringResource(R.string.p2_slot_count, it.referenceSlotIds.size) }).joinToString(" · "), onClick = { onShot(location.episode.id, location.shot.code) })
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
    if (assetReview) ProductionAssetSheet(asset, asset.referenceLocations(production).size, busy, { assetReview = false }, onRun, onSubmit)
}
