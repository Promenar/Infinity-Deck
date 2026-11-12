package com.promenar.infinity.ui.production

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
        ProductionTopBar(
            title = "${shot.code} ${shot.title}",
            meta = listOfNotNull(
                stringResource(R.string.production_panel_episode_number, location.episode.number),
                stringResource(R.string.production_panel_scene_number, location.scene.number),
                shot.size?.takeIf(String::isNotBlank),
                secondsLabel(shot.durationSeconds),
            ),
            onBack = onBack,
        ) {
            ProductionStatusChip(shot.status.label(), shot.status.tone(), Modifier.padding(end = 12.dp))
        }
        InfinityPanelTabs(listOf(
            InfinityPanelTab(ShotDetailTab.STORYBOARD, stringResource(R.string.p2_tab_storyboard), dot = MaterialTheme.colorScheme.primary.takeIf { !location.scene.storyboardLocked }),
            InfinityPanelTab(ShotDetailTab.CANDIDATES, stringResource(R.string.p2_tab_candidates), count = shot.takes.size.takeIf { it > 0 }?.toString()),
        ), selected = tab, onSelect = { tab = it }, contentInset = InfinitySpacing.Large)
        (error ?: localError)?.let { message -> InfinityStatusStrip(InfinityStatusKind.Error, message, onDismiss = { onDismissError(); localError = null }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        notice?.let { InfinityStatusStrip(InfinityStatusKind.Info, it, onDismiss = { notice = null }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        LazyColumn(Modifier.weight(1f), contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(ProductionSectionGap)) {
            if (production.experimental) item("experimental") { ProductionTrialStrip() }
            if (tab == ShotDetailTab.CANDIDATES) {
                item("player") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (viewing != null) key(viewing.id) { ProductionMediaReference(viewing.path, Modifier.fillMaxWidth(), alt = shot.title) }
                        else ProductionSection(null) {
                            ProductionMutedRow(stringResource(R.string.production_panel_no_takes))
                            ProductionRow(stringResource(R.string.p2_go_generate), titleColor = MaterialTheme.colorScheme.primary, onClick = { tab = ShotDetailTab.STORYBOARD })
                        }
                        if (shot.takes.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(shot.takes, key = { it.id }) { take -> TakeThumb(take, shot.takes.indexOf(take) + 1, take.id == viewing?.id, take.id == shot.selectedTakeId) { viewingId = take.id } }
                        }
                        if (shot.status == ShotStatus.REDO && !shot.userNote.isNullOrBlank()) InfinityStatusStrip(InfinityStatusKind.Warning, shot.userNote)
                        val actions = shot.actions(viewing?.id)
                        ProductionActionRow(buildList {
                            if (actions.canUnlock) add(ProductionButton(stringResource(R.string.production_panel_unlock), InfinityPillTone.NEUTRAL, !working, ProductionUiTags.SHOT_UNLOCK) { onRun(ProductionUserAction.UnlockShot(episodeId, shot.code)) })
                            else if (shot.takes.isNotEmpty()) {
                                add(ProductionButton(stringResource(R.string.production_panel_redo), InfinityPillTone.DANGER, !working && actions.canRedo, ProductionUiTags.SHOT_REDO) { dialog = PlanningDialog.REDO })
                                add(ProductionButton(stringResource(R.string.production_panel_select), InfinityPillTone.PRIMARY, !working && actions.canSelect, ProductionUiTags.SHOT_SELECT) { viewing?.let { onRun(ProductionUserAction.SelectTake(episodeId, shot.code, it.id)) } })
                            }
                        })
                    }
                }
            } else {
                if (!location.scene.storyboardLocked && !production.experimental) item("unlocked") {
                    ProductionSection(null) {
                        ProductionRow(
                            stringResource(R.string.p2_unlocked_scene),
                            icon = Icons.Rounded.LockOpen,
                            trailing = { ProductionCommand(stringResource(R.string.p2_lock_scene), enabled = !working) { dialog = PlanningDialog.LOCK } },
                            chevron = false,
                        )
                    }
                }
                item("content") {
                    Column {
                        ProductionSectionHeader(
                            stringResource(R.string.p2_shot_section),
                            trailing = stringResource(if (location.scene.storyboardLocked) R.string.p2_content_locked else R.string.p2_content_editable),
                            action = {
                                IconButton(onClick = { dialog = PlanningDialog.CONTENT }, enabled = !working, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Edit, stringResource(R.string.p2_edit_content), Modifier.size(20.dp)) }
                            },
                        )
                        BettboxListGroup { ShotFieldGrid(shot, production) }
                    }
                }
                item("planning") {
                    Row(Modifier.padding(start = InfinitySpacing.RowHorizontal, top = 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.p2_generation_plan), style = MaterialTheme.typography.titleSmall)
                        if (shot.promptUserEdited) Text(stringResource(R.string.p2_prompt_edited), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (shot.appearances.isNotEmpty()) item("characters") {
                    Column {
                        ProductionSectionHeader(stringResource(R.string.p2_character_refs))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            shot.appearances.forEach { appearance ->
                                CharacterReferenceCard(appearance, production, !working, onOpen = { onOpenCharacter(appearance.characterAssetId) }, onLook = { lookCharacter = appearance.characterAssetId; sheet = PlanningSheet.LOOK }) { look, slotId ->
                                    val updated = appearance.toggleSlot(look, slotId)
                                    onRun(ProductionUserAction.ToggleReferenceSlot(episodeId, shot.code, appearance.characterAssetId, slotId, slotId in updated.referenceSlotIds))
                                }
                            }
                        }
                    }
                }
                item("channel-mode") {
                    ProductionSection(stringResource(R.string.p2_channel_mode)) {
                        ProductionRow(stringResource(R.string.p2_mode), value = shot.mode.label(), enabled = !working, onClick = { sheet = PlanningSheet.MODE })
                        val channelName = preview?.channelLabel ?: shot.channelId?.let(services.channelLabel) ?: stringResource(R.string.p2_default_channel)
                        val channelValue = if (preview?.channelIsDefault == true && preview?.channelLabel != null) "$channelName · ${stringResource(R.string.p2_default)}" else channelName
                        ProductionRow(stringResource(R.string.p2_channel), value = channelValue, enabled = !working, onClick = { sheet = PlanningSheet.CHANNEL })
                        ProductionRow(stringResource(R.string.p2_specs), value = (preview?.channel?.capabilities?.resolutions.orEmpty() + listOfNotNull(production.aspectRatio, secondsLabel(shot.durationSeconds))).joinToString(" · "))
                        ProductionRow(stringResource(R.string.p2_preview_asset), value = shot.preview?.label() ?: stringResource(R.string.p2_none))
                        shot.previewPath?.let { ProductionMediaReference(it, Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 8.dp), asLink = shot.preview == PreviewKind.TEXT_CARD) }
                    }
                }
                item("inputs") {
                    val inputs = preview?.inputs.orEmpty()
                    val packing = preview?.channel?.takeIf { shot.mode == ShotMode.REFERENCE && shot.appearances.isNotEmpty() }?.let {
                        stringResource(if (it.capabilities.characterReferenceMode == CharacterReferenceMode.COMPOSITE) R.string.p2_composite else R.string.p2_separate)
                    }
                    val images = inputs.count { it.kind == InputKind.IMAGE }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProductionSection(
                            listOfNotNull(stringResource(R.string.production_panel_section_inputs), packing, stringResource(R.string.p2_image_count, images).takeIf { images > 0 }).joinToString(" · "),
                            trailing = preview?.channel?.let { stringResource(R.string.p2_inputs_by_channel, preview?.channelLabel?.substringBefore(" · ") ?: it.id) },
                        ) {
                            if (loading && preview == null) Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { InfinityLoadingIndicator() }
                            inputs.forEach { PlannedInputRow(it) }
                            if (preview != null && inputs.isEmpty()) ProductionMutedRow(stringResource(R.string.p2_no_inputs))
                            if (services.planner == null) ProductionMutedRow(noPlanner)
                        }
                        preview?.overflow?.takeIf { it.isNotEmpty() }?.let { overflow -> InfinityStatusStrip(InfinityStatusKind.Warning, stringResource(R.string.p2_overflow, overflow.joinToString("、") { it.title })) }
                        preview?.warnings?.forEach { warning -> InfinityStatusStrip(InfinityStatusKind.Warning, warning) }
                    }
                }
                if (shot.dialogue.isNotEmpty()) item("dialogue-audio") {
                    ProductionSection(stringResource(R.string.p2_dialogue_audio)) {
                        shot.dialogue.forEachIndexed { index, line ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                DialogueLine(production.findAsset(line.characterAssetId)?.name ?: line.characterAssetId, line.text, null, Modifier.weight(1f))
                                InfinitySegmentedControl(
                                    DialogueAudioSource.entries.map { InfinitySegment(it, stringResource(if (it == DialogueAudioSource.NATIVE) R.string.p2_native else R.string.p2_dubbed)) },
                                    selected = line.audioSource, enabled = !working,
                                    onSelect = { onRun(ProductionUserAction.SetDialogueSource(episodeId, shot.code, index, it)) },
                                    modifier = Modifier.width(136.dp),
                                )
                            }
                        }
                    }
                }
                item("prompt") {
                    Column {
                        ProductionSectionHeader(
                            listOf(stringResource(R.string.p2_submission_prompt), preview?.promptFormat?.label() ?: stringResource(R.string.p2_format_generic)).joinToString(" · "),
                            action = {
                                IconButton(onClick = { dialog = PlanningDialog.RECOMPILE }, enabled = !working && services.planner != null, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Refresh, stringResource(R.string.p2_recompile), Modifier.size(20.dp)) }
                                IconButton(onClick = { dialog = PlanningDialog.PROMPT }, enabled = !working, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Edit, stringResource(R.string.production_panel_edit), Modifier.size(20.dp)) }
                            },
                        )
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                preview?.prompt ?: shot.prompt.ifBlank { stringResource(R.string.production_panel_no_prompt) },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
                            )
                        }
                    }
                }
                item("generate") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        preview?.blockedReason?.let { InfinityStatusStrip(InfinityStatusKind.Warning, it) }
                        preview?.estimate?.let { Text(stringResource(R.string.p2_estimate, moneyText(it.currency, it.amount)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal)) }
                        InfinityPillButton(
                            text = stringResource(R.string.p2_generate),
                            tone = InfinityPillTone.PRIMARY,
                            enabled = generationEnabled(location.scene.storyboardLocked, production.experimental, preview, working) && services.planner != null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            onClick = {
                                execute {
                                    when (val result = services.planner?.generate(sessionId, episodeId, shot.code)) {
                                        is ShotGenerateResult.Submitted -> { notice = if (result.awaitingApproval) awaiting else submitted; refresh++ }
                                        is ShotGenerateResult.Rejected -> localError = result.reason
                                        null -> localError = noPlanner
                                    }
                                }
                            },
                        )
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

/** 镜头字段表：左列字段名，右列内容；对白角色名用强调色，出场用单行横向滚动的小标签。 */
@Composable
private fun ShotFieldGrid(shot: Shot, production: Production) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable
        fun field(label: String, content: @Composable () -> Unit) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.width(44.dp))
                Box(Modifier.weight(1f)) { content() }
            }
        }
        @Composable
        fun text(label: String, value: String?) {
            value?.takeIf(String::isNotBlank)?.let { field(label) { Text(it, style = MaterialTheme.typography.bodyMedium) } }
        }
        text(stringResource(R.string.p2_field_size), shot.size)
        text(stringResource(R.string.p2_field_camera), shot.camera)
        text(stringResource(R.string.p2_field_movement), shot.cameraMovement)
        text(stringResource(R.string.p2_field_duration_short), secondsLabel(shot.durationSeconds))
        text(stringResource(R.string.p2_field_visual), shot.visual)
        text(stringResource(R.string.p2_field_performance), shot.performance)
        shot.dialogue.forEachIndexed { index, line ->
            field(if (index == 0) stringResource(R.string.p2_field_dialogue) else "") {
                DialogueLine(production.findAsset(line.characterAssetId)?.name ?: line.characterAssetId, line.text, line.tone, textStyle = MaterialTheme.typography.bodyMedium)
            }
        }
        text(stringResource(R.string.p2_field_sound), shot.sound)
        text(stringResource(R.string.p2_field_transition), shot.transition)
        text(stringResource(R.string.p2_field_continuity), shot.continuity)
        val cast = shot.appearances.map { production.findAsset(it.characterAssetId)?.name ?: it.characterAssetId } +
            listOfNotNull(shot.locationAssetId?.let { production.findAsset(it)?.name }) + shot.propAssetIds.map { production.findAsset(it)?.name ?: it }
        if (cast.isNotEmpty()) field(stringResource(R.string.p2_cast)) {
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cast.forEach { name ->
                    Surface(shape = RoundedCornerShape(8.dp), color = androidx.compose.ui.graphics.Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Text(name, style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }
        }
    }
}

/** 角色参考卡：角色名（进参考包）、造型选择与单行横向滚动的参考位开关。 */
@Composable
private fun CharacterReferenceCard(appearance: ShotAppearance, production: Production, enabled: Boolean, onOpen: () -> Unit, onLook: () -> Unit, onToggle: (CharacterLook, String) -> Unit) {
    val asset = production.findAsset(appearance.characterAssetId)
    val look = asset?.looks?.firstOrNull { it.id == appearance.lookId }
    BettboxListGroup {
        Column(Modifier.fillMaxWidth().padding(start = InfinitySpacing.RowHorizontal, end = 10.dp, top = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    asset?.name ?: appearance.characterAssetId,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onOpen).padding(vertical = 10.dp),
                )
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, onClick = onLook, enabled = enabled) {
                    Row(Modifier.heightIn(min = 36.dp).padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(look?.name ?: stringResource(R.string.p2_missing_look), style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Rounded.ExpandMore, contentDescription = stringResource(R.string.p2_look), modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (look != null) Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                look.slots.forEach { slot -> InfinityChip(slot.displayName(), selected = slot.id in appearance.referenceSlotIds, enabled = enabled, onClick = { onToggle(look, slot.id) }) }
            }
        }
    }
}

/** 输入素材行：图号、缩略图（合图并排、语音用波形块）、标题与说明。 */
@Composable
internal fun PlannedInputRow(input: PlannedInput) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(input.number?.let { stringResource(R.string.p2_image_number, it) }.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
        when {
            input.compositeOf.isNotEmpty() -> Row(Modifier.width(80.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) { input.compositeOf.take(4).forEach { ProductionThumbnail(it, Modifier.weight(1f).height(48.dp)) } }
            input.kind == InputKind.AUDIO -> Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.GraphicEq, null, Modifier.size(22.dp), MaterialTheme.colorScheme.onTertiaryContainer)
            }
            else -> ProductionThumbnail(input.path, Modifier.size(48.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(input.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (input.detail.isNotBlank()) Text(input.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}
