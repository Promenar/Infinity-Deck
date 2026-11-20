package com.promenar.infinity.ui.production

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.production.*
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityChip
import com.promenar.infinity.ui.theme.forInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 内容表单只编辑结构化字段；锁定场交给待审请求入口，保持原快照不变。 */
@Composable
internal fun ProductionShotContentEditor(location: ShotLocation, production: Production, revision: Long, services: ProductionPanelServices, onSubmit: suspend (ProductionUserAction) -> String?, onDismiss: () -> Unit) {
    var proposed by remember(location.shot) { mutableStateOf(location.shot) }
    var seconds by rememberSaveable(location.shot.code) { mutableStateOf(formatShotSeconds(location.shot.durationSeconds)) }
    var characterIndex by remember { mutableStateOf<Int?>(null) }
    var locationPickerOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val locked = location.scene.storyboardLocked
    val connected = !locked || services.requestContentChange != null
    val failed = stringResource(R.string.production_panel_action_failed)
    val characters = remember(production.assets) { production.assets.filter { it.kind == AssetKind.CHARACTER } }
    val locations = remember(production.assets) { production.assets.filter { it.kind == AssetKind.LOCATION } }
    val props = remember(production.assets) { production.assets.filter { it.kind == AssetKind.PROP } }
    InfinityBottomSheet(show = true, title = stringResource(R.string.p2_edit_content), onDismiss = { if (!saving) onDismiss() }, scrollContent = true) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (locked) Text(stringResource(if (connected) R.string.p2_content_request_hint else R.string.p2_change_unavailable), style = MaterialTheme.typography.bodySmall)
            ContentField(stringResource(R.string.p2_field_title), proposed.title, !saving) { proposed = proposed.copy(title = it) }
            ContentField(stringResource(R.string.p2_field_size), proposed.size.orEmpty(), !saving) { proposed = proposed.copy(size = it) }
            ContentField(stringResource(R.string.p2_field_camera), proposed.camera.orEmpty(), !saving) { proposed = proposed.copy(camera = it) }
            ContentField(stringResource(R.string.p2_field_movement), proposed.cameraMovement.orEmpty(), !saving) { proposed = proposed.copy(cameraMovement = it) }
            ContentField(stringResource(R.string.p2_field_duration), seconds, !saving) { seconds = it }
            ContentField(stringResource(R.string.p2_field_visual), proposed.visual.orEmpty(), !saving) { proposed = proposed.copy(visual = it) }
            ContentField(stringResource(R.string.p2_field_performance), proposed.performance.orEmpty(), !saving) { proposed = proposed.copy(performance = it) }
            if (characters.isNotEmpty() || locations.isNotEmpty() || props.isNotEmpty()) {
                ProductionSectionTitle(stringResource(R.string.p2_cast_assets))
                if (characters.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        characters.forEach { character ->
                            val selected = proposed.appearances.any { it.characterAssetId == character.id }
                            InfinityChip(
                                label = character.name,
                                selected = selected,
                                enabled = !saving,
                                onClick = {
                                    proposed = proposed.copy(
                                        appearances = if (selected) {
                                            proposed.appearances.filterNot { it.characterAssetId == character.id }
                                        } else {
                                            val defaultLook = character.looks.firstOrNull()
                                            proposed.appearances + ShotAppearance(
                                                characterAssetId = character.id,
                                                lookId = defaultLook?.id ?: "default",
                                                referenceSlotIds = defaultLook?.let(ProductionRules::defaultSlotIds).orEmpty(),
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                if (locations.isNotEmpty()) {
                    ProductionValueRow(
                        title = stringResource(R.string.production_panel_asset_location),
                        value = proposed.locationAssetId?.let { production.findAsset(it)?.name } ?: stringResource(R.string.p2_none),
                        enabled = !saving,
                        onClick = { locationPickerOpen = true },
                    )
                }
                if (props.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        props.forEach { prop ->
                            val selected = prop.id in proposed.propAssetIds
                            InfinityChip(
                                label = prop.name,
                                selected = selected,
                                enabled = !saving,
                                onClick = {
                                    proposed = proposed.copy(
                                        propAssetIds = if (selected) proposed.propAssetIds - prop.id else (proposed.propAssetIds + prop.id).distinct(),
                                    )
                                },
                            )
                        }
                    }
                }
            }
            proposed.dialogue.forEachIndexed { index, line ->
                ProductionValueRow(stringResource(R.string.production_panel_asset_character), production.findAsset(line.characterAssetId)?.name ?: stringResource(R.string.p2_choose_character), enabled = !saving) { characterIndex = index }
                ContentField(stringResource(R.string.p2_field_dialogue), line.text, !saving) { value -> proposed = proposed.copy(dialogue = proposed.dialogue.mapIndexed { i, old -> if (i == index) old.copy(text = value) else old }) }
                ContentField(stringResource(R.string.p2_field_tone), line.tone.orEmpty(), !saving) { value -> proposed = proposed.copy(dialogue = proposed.dialogue.mapIndexed { i, old -> if (i == index) old.copy(tone = value) else old }) }
                ProductionCommand(stringResource(R.string.p2_remove_dialogue), enabled = !saving) { proposed = proposed.copy(dialogue = proposed.dialogue.filterIndexed { i, _ -> i != index }) }
            }
            ProductionCommand(stringResource(R.string.p2_add_dialogue), enabled = !saving) { proposed = proposed.copy(dialogue = proposed.dialogue + ShotDialogue("", "")) }
            ContentField(stringResource(R.string.p2_field_sound), proposed.sound.orEmpty(), !saving) { proposed = proposed.copy(sound = it) }
            ContentField(stringResource(R.string.p2_field_transition), proposed.transition.orEmpty(), !saving) { proposed = proposed.copy(transition = it) }
            ContentField(stringResource(R.string.p2_field_continuity), proposed.continuity.orEmpty(), !saving) { proposed = proposed.copy(continuity = it) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ProductionCommand(stringResource(if (locked) R.string.p2_submit_change else R.string.production_panel_save), enabled = connected && !saving && seconds.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true && proposed.title.isNotBlank() && proposed.dialogue.all { it.characterAssetId.isNotBlank() && it.text.isNotBlank() }, primary = true) {
                saving = true
                scope.launch {
                    try {
                        val shot = proposed.copy(durationSeconds = seconds.toDouble())
                        error = if (locked) services.requestContentChange?.invoke(revision, location, shot) else onSubmit(ProductionUserAction.EditShotContent(location.episode.id, location.shot.code, shot))
                        if (error == null) onDismiss()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = failed }
                    finally { saving = false }
                }
            }
        }
    }
    characterIndex?.let { index ->
        ProductionOptionSheet(
            title = stringResource(R.string.production_panel_asset_character),
            options = characters.map { it.id to it.name },
            selected = proposed.dialogue.getOrNull(index)?.characterAssetId,
            onDismiss = { characterIndex = null },
            emptyText = stringResource(R.string.p2_no_characters),
        ) { id ->
            proposed = proposed.copy(dialogue = proposed.dialogue.mapIndexed { i, old -> if (i == index) old.copy(characterAssetId = id) else old })
        }
    }
    if (locationPickerOpen) {
        val options = listOf<Pair<String?, String>>(null to stringResource(R.string.p2_none)) + locations.map { it.id to it.name }
        ProductionOptionSheet(
            title = stringResource(R.string.production_panel_asset_location),
            options = options,
            selected = proposed.locationAssetId,
            onDismiss = { locationPickerOpen = false },
            emptyText = stringResource(R.string.p2_no_locations),
        ) { id ->
            proposed = proposed.copy(locationAssetId = id)
        }
    }
}

@Composable
private fun ContentField(label: String, value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onValueChange = { onValueChange(it.take(PRODUCTION_PROMPT_MAX)) },
        label = { Text(label) },
        textStyle = LocalTextStyle.current.forInput(),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun contentFieldLabel(field: String): String = stringResource(when (field) {
    "title" -> R.string.p2_field_title
    "size" -> R.string.p2_field_size
    "camera" -> R.string.p2_field_camera
    "cameraMovement" -> R.string.p2_field_movement
    "durationSeconds" -> R.string.p2_field_duration
    "visual" -> R.string.p2_field_visual
    "performance" -> R.string.p2_field_performance
    "dialogue" -> R.string.p2_field_dialogue
    "sound" -> R.string.p2_field_sound
    "transition" -> R.string.p2_field_transition
    "continuity" -> R.string.p2_field_continuity
    "appearances" -> R.string.p2_cast
    "locationAssetId" -> R.string.production_panel_asset_location
    "propAssetIds" -> R.string.production_panel_asset_prop
    else -> R.string.p2_shot_content
})
