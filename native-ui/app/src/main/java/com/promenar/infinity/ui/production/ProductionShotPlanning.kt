package com.promenar.infinity.ui.production

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.promenar.infinity.R
import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class PlanningSheet { MODE, CHANNEL, LOOK }
private enum class PlanningDialog { REDO, PROMPT, RECOMPILE, LOCK, CONTENT }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProductionShotDetail(
    location: ShotLocation, busy: Boolean, error: String?, onDismissError: () -> Unit, onBack: () -> Unit,
    onRun: (ProductionUserAction) -> Unit, onSubmit: suspend (ProductionUserAction) -> String?, modifier: Modifier = Modifier,
    production: Production, revision: Long, sessionId: String, services: ProductionPanelServices,
    channels: List<MediaChannel>, channelOrder: List<String>, initialTab: ShotDetailTab? = null,
    onOpenCharacter: (String) -> Unit = {},
    initialPreview: ShotPlanPreview? = null,
) {
    val shot = location.shot
    val episodeId = location.episode.id
    var tab by rememberSaveable(episodeId, shot.code, initialTab) { mutableStateOf(initialTab ?: shot.defaultDetailTab()) }
    var viewingId by rememberSaveable(episodeId, shot.code) { mutableStateOf(shot.displayTake()?.id) }
    val viewing = shot.takes.firstOrNull { it.id == viewingId } ?: shot.displayTake()
    var dialog by rememberSaveable(episodeId, shot.code) { mutableStateOf<PlanningDialog?>(null) }
    var sheet by remember { mutableStateOf<PlanningSheet?>(null) }
    var lookCharacter by remember { mutableStateOf<String?>(null) }
    var localBusy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var preview by remember(sessionId, episodeId, shot.code, revision, refresh, services.planner) { mutableStateOf(initialPreview) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.production_panel_action_failed)
    val noPlanner = stringResource(R.string.p2_planner_unavailable)
    val awaiting = stringResource(R.string.p2_task_approval)
    val submitted = stringResource(R.string.p2_submitted)
    val working = busy || localBusy || loading
    LaunchedEffect(sessionId, episodeId, shot.code, revision, refresh, services.planner) {
        val planner = services.planner
        if (planner != null) {
            loading = true
            try { preview = planner.preview(sessionId, episodeId, shot.code); localError = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { localError = failed }
            finally { loading = false }
        }
    }
    fun execute(block: suspend () -> Unit) {
        if (!working) {
            localBusy = true
            scope.launch {
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { localError = failed }
                finally { localBusy = false }
            }
        }
    }
    Column(modifier.fillMaxSize().testTag(ProductionUiTags.SHOT_DETAIL)) {
        ProductionBackHeader("${shot.code} · ${shot.title}", "${episodeId} · ${location.scene.heading}", onBack)
        InfinityPanelTabs(listOf(
            InfinityPanelTab(ShotDetailTab.STORYBOARD, stringResource(R.string.p2_tab_storyboard), dot = MaterialTheme.colorScheme.primary.takeIf { !location.scene.storyboardLocked }),
            InfinityPanelTab(ShotDetailTab.CANDIDATES, stringResource(R.string.p2_tab_candidates), count = shot.takes.size.takeIf { it > 0 }?.toString()),
        ), selected = tab, onSelect = { tab = it })
        (error ?: localError)?.let { message -> InfinityStatusStrip(InfinityStatusKind.Error, message, onDismiss = { onDismissError(); localError = null }, modifier = Modifier.padding(16.dp)) }
        notice?.let { InfinityStatusStrip(InfinityStatusKind.Info, it, onDismiss = { notice = null }, modifier = Modifier.padding(16.dp)) }
        LazyColumn(Modifier.weight(1f), contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
            if (production.experimental) item("experimental") { InfinityStatusStrip(InfinityStatusKind.Warning, stringResource(R.string.p2_experimental_strip)) }
            if (tab == ShotDetailTab.CANDIDATES) {
                item("player") {
                    if (viewing != null) key(viewing.id) { ProductionMediaReference(viewing.path, Modifier.fillMaxWidth(), alt = shot.title) }
                    else ProductionBlock {
                        Text(stringResource(R.string.production_panel_no_takes))
                        ProductionCommand(stringResource(R.string.p2_go_generate)) { tab = ShotDetailTab.STORYBOARD }
                    }
                }
                item("takes") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shot.takes, key = { it.id }) { take -> TakeThumb(take, shot.takes.indexOf(take) + 1, take.id == viewing?.id, take.id == shot.selectedTakeId) { viewingId = take.id } }
                    }
                }
                if (shot.status == ShotStatus.REDO && !shot.userNote.isNullOrBlank()) item("redo-note") { InfinityStatusStrip(InfinityStatusKind.Warning, shot.userNote) }
                item("actions") {
                    val actions = shot.actions(viewing?.id)
                    ProductionActionRow(buildList {
                        if (actions.canUnlock) add(ProductionButton(stringResource(R.string.production_panel_unlock), InfinityPillTone.NEUTRAL, !working, ProductionUiTags.SHOT_UNLOCK) { onRun(ProductionUserAction.UnlockShot(episodeId, shot.code)) })
                        else {
                            add(ProductionButton(stringResource(R.string.production_panel_redo), InfinityPillTone.NEUTRAL, !working && actions.canRedo, ProductionUiTags.SHOT_REDO) { dialog = PlanningDialog.REDO })
                            add(ProductionButton(stringResource(R.string.production_panel_select), InfinityPillTone.PRIMARY, !working && actions.canSelect, ProductionUiTags.SHOT_SELECT) { viewing?.let { onRun(ProductionUserAction.SelectTake(episodeId, shot.code, it.id)) } })
                        }
                    })
                }
            } else {
                if (!location.scene.storyboardLocked && !production.experimental) item("unlocked") {
                    ProductionBlock {
                        InfinityStatusStrip(InfinityStatusKind.Warning, stringResource(R.string.p2_unlocked_scene))
                        ProductionCommand(stringResource(R.string.p2_lock_scene), enabled = !working) { dialog = PlanningDialog.LOCK }
                    }
                }
                item("content") {
                    ProductionBlock(stringResource(R.string.p2_shot_content)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(if (location.scene.storyboardLocked) R.string.p2_content_locked else R.string.p2_content_editable), modifier = Modifier.weight(1f))
                            IconButton(onClick = { dialog = PlanningDialog.CONTENT }, enabled = !working, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Edit, stringResource(R.string.p2_edit_content)) }
                        }
                        ProductionShotText(shot, production)
                        val cast = shot.appearances.map { production.findAsset(it.characterAssetId)?.name ?: it.characterAssetId } + listOfNotNull(shot.locationAssetId?.let { production.findAsset(it)?.name }) + shot.propAssetIds.map { production.findAsset(it)?.name ?: it }
                        if (cast.isNotEmpty()) ProductionValueRow(stringResource(R.string.p2_cast), cast.joinToString(" · "))
                    }
                }
                item("planning") { ProductionSectionTitle(stringResource(R.string.p2_generation_plan)) }
                shot.appearances.forEach { appearance ->
                    item("character-${appearance.characterAssetId}") {
                        val asset = production.findAsset(appearance.characterAssetId)
                        val look = asset?.looks?.firstOrNull { it.id == appearance.lookId }
                        ProductionBlock {
                            ProductionValueRow(asset?.name ?: appearance.characterAssetId, stringResource(R.string.p2_reference_pack), onClick = { onOpenCharacter(appearance.characterAssetId) })
                            ProductionValueRow(stringResource(R.string.p2_look), look?.name ?: stringResource(R.string.p2_missing_look), enabled = !working) { lookCharacter = appearance.characterAssetId; sheet = PlanningSheet.LOOK }
                            if (look != null) Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                look.slots.forEach { slot ->
                                    InfinityChip(slot.displayName(), selected = slot.id in appearance.referenceSlotIds, enabled = !working, onClick = {
                                        val updated = appearance.toggleSlot(look, slot.id)
                                        onRun(ProductionUserAction.ToggleReferenceSlot(episodeId, shot.code, appearance.characterAssetId, slot.id, slot.id in updated.referenceSlotIds))
                                    })
                                }
                            }
                        }
                    }
                }
                item("channel-mode") {
                    ProductionBlock(stringResource(R.string.p2_channel_mode)) {
                        ProductionValueRow(stringResource(R.string.p2_mode), shot.mode.label(), enabled = !working) { sheet = PlanningSheet.MODE }
                        val channelName = preview?.channelLabel ?: shot.channelId?.let(services.channelLabel) ?: stringResource(R.string.p2_default_channel)
                        val channelValue = if (preview?.channelIsDefault == true && preview?.channelLabel != null) "$channelName · ${stringResource(R.string.p2_default)}" else channelName
                        ProductionValueRow(stringResource(R.string.p2_channel), channelValue, enabled = !working) { sheet = PlanningSheet.CHANNEL }
                        ProductionValueRow(stringResource(R.string.p2_specs), (preview?.channel?.capabilities?.resolutions.orEmpty() + listOfNotNull(production.aspectRatio, secondsLabel(shot.durationSeconds))).joinToString(" · "))
                        ProductionValueRow(stringResource(R.string.production_panel_section_preview), shot.preview?.label() ?: stringResource(R.string.p2_none))
                        shot.previewPath?.let { ProductionMediaReference(it, Modifier.fillMaxWidth(), asLink = shot.preview == PreviewKind.TEXT_CARD) }
                    }
                }
                item("inputs") {
                    ProductionBlock(stringResource(R.string.production_panel_section_inputs)) {
                        if (loading) InfinityLoadingIndicator()
                        preview?.inputs?.forEach { PlannedInputRow(it) }
                        if (preview?.inputs?.isEmpty() == true) Text(stringResource(R.string.p2_no_inputs))
                        if (services.planner == null) Text(noPlanner, style = MaterialTheme.typography.bodySmall)
                    }
                    preview?.overflow?.takeIf { it.isNotEmpty() }?.let { inputs -> InfinityStatusStrip(InfinityStatusKind.Warning, stringResource(R.string.p2_overflow, inputs.joinToString("、") { it.title })) }
                    preview?.warnings?.forEach { warning -> InfinityStatusStrip(InfinityStatusKind.Warning, warning) }
                }
                if (shot.dialogue.isNotEmpty()) item("dialogue-audio") {
                    ProductionBlock(stringResource(R.string.p2_dialogue_audio)) {
                        shot.dialogue.forEachIndexed { index, line ->
                            Text("${production.findAsset(line.characterAssetId)?.name ?: line.characterAssetId} · ${line.text}", style = MaterialTheme.typography.bodyMedium)
                            InfinitySegmentedControl(DialogueAudioSource.entries.map { InfinitySegment(it, stringResource(if (it == DialogueAudioSource.NATIVE) R.string.p2_native else R.string.p2_dubbed)) }, selected = line.audioSource, enabled = !working, onSelect = { onRun(ProductionUserAction.SetDialogueSource(episodeId, shot.code, index, it)) })
                        }
                    }
                }
                item("prompt") {
                    ProductionBlock(stringResource(R.string.p2_submission_prompt)) {
                        Text(preview?.promptFormat?.label() ?: stringResource(R.string.p2_format_generic), style = MaterialTheme.typography.labelSmall)
                        if (shot.promptUserEdited) Text(stringResource(R.string.p2_edited), style = MaterialTheme.typography.labelSmall)
                        Row {
                            IconButton(onClick = { dialog = PlanningDialog.RECOMPILE }, enabled = !working && services.planner != null, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Refresh, stringResource(R.string.p2_recompile)) }
                            IconButton(onClick = { dialog = PlanningDialog.PROMPT }, enabled = !working, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Edit, stringResource(R.string.production_panel_edit)) }
                        }
                        Text(preview?.prompt ?: shot.prompt.ifBlank { stringResource(R.string.production_panel_no_prompt) }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item("generate") {
                    preview?.blockedReason?.let { InfinityStatusStrip(InfinityStatusKind.Warning, it) }
                    preview?.estimate?.let { Text(stringResource(R.string.p2_estimate, "${it.currency} ${it.amount.toPlainString()}")) }
                    ProductionCommand(stringResource(R.string.p2_generate), enabled = generationEnabled(location.scene.storyboardLocked, production.experimental, preview, working) && services.planner != null, primary = true) {
                        execute {
                            when (val result = services.planner?.generate(sessionId, episodeId, shot.code)) {
                                is ShotGenerateResult.Submitted -> { notice = if (result.awaitingApproval) awaiting else submitted; refresh++ }
                                is ShotGenerateResult.Rejected -> localError = result.reason
                                null -> localError = noPlanner
                            }
                        }
                    }
                }
            }
        }
    }
    when (sheet) {
        PlanningSheet.MODE -> ProductionOptionSheet(stringResource(R.string.p2_mode), ShotMode.entries.map { it to it.label() }, shot.mode, { sheet = null }) { onRun(modeSelectionAction(location, it, channels, channelOrder)) }
        PlanningSheet.CHANNEL -> {
            val choices = channelChoices(channels, channelOrder, shot.mode)
            ProductionOptionSheet(stringResource(R.string.p2_channel), choices.map { it.id to services.channelLabel(it.id) }, shot.channelId ?: choices.firstOrNull()?.id, { sheet = null }) { onRun(ProductionUserAction.SetShotPlan(episodeId, shot.code, it, shot.mode)) }
        }
        PlanningSheet.LOOK -> {
            val character = production.findAsset(lookCharacter.orEmpty())
            ProductionOptionSheet(stringResource(R.string.p2_look), character?.looks.orEmpty().map { it.id to it.name }, shot.appearances.firstOrNull { it.characterAssetId == character?.id }?.lookId, { sheet = null }) { id -> character?.let { onRun(ProductionUserAction.SetCharacterLook(episodeId, shot.code, it.id, id)) } }
        }
        null -> Unit
    }
    when (dialog) {
        PlanningDialog.LOCK -> InfinityConfirmDialog(stringResource(R.string.p2_lock_scene), stringResource(R.string.p2_lock_scene_message), stringResource(R.string.p2_lock_scene), onConfirm = { onRun(ProductionUserAction.LockScene(episodeId, location.scene.code, true)); dialog = null }, onCancel = { dialog = null })
        PlanningDialog.RECOMPILE -> InfinityConfirmDialog(stringResource(R.string.p2_recompile), stringResource(R.string.p2_recompile_message), stringResource(R.string.p2_recompile), onConfirm = {
            dialog = null; execute { services.planner?.recompilePrompt(sessionId, episodeId, shot.code); refresh++ }
        }, onCancel = { dialog = null })
        PlanningDialog.PROMPT -> ProductionTextDialog(stringResource(R.string.production_panel_prompt_title), stringResource(R.string.production_panel_section_prompt), preview?.prompt ?: shot.prompt, stringResource(R.string.production_panel_save), PRODUCTION_PROMPT_MAX, 6, ProductionUiTags.PROMPT_FIELD, { dialog = null }, { onSubmit(ProductionUserAction.EditPrompt(episodeId, shot.code, it)) })
        PlanningDialog.REDO -> ProductionTextDialog(stringResource(R.string.production_panel_redo_title), stringResource(R.string.production_panel_reason), "", stringResource(R.string.production_panel_redo), PRODUCTION_NOTE_MAX, 3, ProductionUiTags.REASON_FIELD, { dialog = null }, { onSubmit(ProductionUserAction.RequestRedo(episodeId, shot.code, it)) }, allowEmpty = false)
        PlanningDialog.CONTENT -> ProductionShotContentEditor(location, production, revision, services, onSubmit) { dialog = null }
        null -> Unit
    }
}

@Composable
internal fun PlannedInputRow(input: PlannedInput) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        input.number?.let { Text(stringResource(R.string.p2_image_number, it), style = MaterialTheme.typography.labelSmall) }
        if (input.compositeOf.isNotEmpty()) {
            Row(Modifier.width(72.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) { input.compositeOf.forEach { ProductionThumbnail(it, Modifier.weight(1f).height(48.dp)) } }
        } else ProductionThumbnail(input.path, Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(input.title, style = MaterialTheme.typography.bodyMedium)
            Text(input.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
