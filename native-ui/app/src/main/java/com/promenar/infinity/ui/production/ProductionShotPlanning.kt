package com.promenar.infinity.ui.production

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.rememberLastNonNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class PlanningSheet { MODE, CHANNEL, LOOK }
private enum class PlanningDialog { PROMPT, RECOMPILE, CONTENT }

/**
 * 镜头详情「候选」页签的默认选中候选：
 * 1. 优先落到最新一条尚未审批（未批准且未否决）的派生（后处理）候选，避免对比审片误落已定稿的原片；
 * 2. 否则若有已批准的派生候选（已选定或存在批准结论），选中最新的已批准派生候选；
 * 3. 其余情况回退既有逻辑 `shot.displayTake()`（已选定候选，否则最新候选）。
 */
internal fun initialViewingTake(production: Production, episodeId: String, shot: Shot): Take? {
    val derivedTakes = shot.takes.withIndex().filter { it.value.derivedFrom != null }
    if (derivedTakes.isNotEmpty()) {
        val newestOrder = compareBy<IndexedValue<Take>> { it.value.createdAt }.thenBy { it.index }
        fun isSettled(take: Take): Boolean = take.id == shot.selectedTakeId && shot.status == ShotStatus.LOCKED
        val unreviewedDerived = derivedTakes.filter { (_, take) ->
            production.verdictOf(ReviewTarget.take(episodeId, shot.code, take.id)) == null && !isSettled(take)
        }.maxWithOrNull(newestOrder)?.value
        if (unreviewedDerived != null) return unreviewedDerived

        val selectedDerived = shot.selectedTake()?.takeIf { it.derivedFrom != null }
        if (selectedDerived != null) return selectedDerived

        val approvedDerived = derivedTakes.filter { (_, take) ->
            val verdict = production.verdictOf(ReviewTarget.take(episodeId, shot.code, take.id))
            verdict?.kind == ReviewKind.APPROVE || isSettled(take)
        }.maxWithOrNull(newestOrder)?.value
        if (approvedDerived != null) return approvedDerived
    }
    return shot.displayTake()
}

/**
 * 解析当前查看候选的一键 A/B 对比配对（原片候选 to 后处理派生候选）：
 * - 若当前候选本身是派生候选且来源候选存在，返回 `sourceTake to currentTake`；
 * - 否则若存在以当前候选为来源的派生子候选，返回 `currentTake to latestDerivedChild`；
 * - 其余情况返回 null，不展示 A/B 切换条。
 */
internal fun abComparePair(shot: Shot, currentTake: Take): Pair<Take, Take>? {
    val sourceTake = currentTake.derivedFrom?.sourceTakeId?.let { id ->
        shot.takes.firstOrNull { it.id == id && it.id != currentTake.id }
    }
    if (sourceTake != null) return sourceTake to currentTake
    val derivedChild = shot.takes.withIndex()
        .filter { it.value.derivedFrom?.sourceTakeId == currentTake.id && it.value.id != currentTake.id }
        .maxWithOrNull(compareBy<IndexedValue<Take>> { it.value.createdAt }.thenBy { it.index })
        ?.value
    return derivedChild?.let { currentTake to it }
}

/**
 * 判断镜头当前是否对生效通道/工作流的默认规格进行了实质覆盖，用于控制「规格」行的「自定」标签与默认提示：
 * - ComfyUI 通道仅认 `specWidth`/`specHeight`（忽略历史残留的 `specResolution`），且与工作流默认宽高一致时不标「自定」；
 * - 云端通道仅认 `specResolution`（忽略 Comfy 宽高），且与通道默认分辨率一致时不标「自定」；
 * - 未解析通道时回退 `shot.hasCustomSpecs()`。
 */
internal fun hasEffectiveCustomSpecs(
    shot: Shot,
    channel: MediaChannel?,
    workflow: ComfyWorkflow?,
): Boolean {
    if (channel == null) return shot.hasCustomSpecs()
    return if (channel.id.startsWith("comfy:")) {
        val customW = shot.specWidth
        val customH = shot.specHeight
        if (customW == null || customH == null) return false
        val defaultW = workflow?.fields?.firstOrNull { it.semanticRole == FieldSemanticRole.WIDTH }?.defaultValue
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
        val defaultH = workflow?.fields?.firstOrNull { it.semanticRole == FieldSemanticRole.HEIGHT }?.defaultValue
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
        if (defaultW != null && defaultH != null) customW != defaultW || customH != defaultH
        else true
    } else {
        val customRes = shot.specResolution?.trim()?.takeIf(String::isNotBlank) ?: return false
        val defaultRes = channel.capabilities.resolutions.firstOrNull()?.trim()?.takeIf(String::isNotBlank)
        defaultRes == null || !customRes.equals(defaultRes, ignoreCase = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProductionShotDetail(
    location: ShotLocation, busy: Boolean, error: String?, onDismissError: () -> Unit, onBack: () -> Unit,
    onRun: (ProductionUserAction) -> Unit, onSubmit: suspend (ProductionUserAction) -> String?, modifier: Modifier = Modifier,
    production: Production, revision: Long, sessionId: String, services: ProductionPanelServices,
    channels: List<MediaChannel>, channelOrder: List<String>, initialTab: ShotDetailTab? = null,
    onOpenCharacter: (String) -> Unit = {},
    initialPreview: ShotPlanPreview? = null,
    generations: GenerationCount = GenerationCount(),
) {
    val shot = location.shot
    val episodeId = location.episode.id
    var tab by rememberSaveable(episodeId, shot.code, initialTab) { mutableStateOf(initialTab ?: shot.defaultDetailTab()) }
    val defaultViewing = remember(production, episodeId, shot) { initialViewingTake(production, episodeId, shot) }
    // 查看的候选 id，或进行中占位项的 key。
    var viewingId by rememberSaveable(episodeId, shot.code) { mutableStateOf(defaultViewing?.id) }
    val placeholders = production.shotPlaceholders(episodeId, shot.code)
    val viewingPlaceholder = placeholders.firstOrNull { it.key == viewingId }
    // 正在查看的链完成后转到它登记的候选。
    val chainTake = shot.chains.firstOrNull { "chain:${it.id}" == viewingId }?.takeId?.let { id -> shot.takes.firstOrNull { it.id == id } }
    val viewing = if (viewingPlaceholder != null) null else shot.takes.firstOrNull { it.id == viewingId } ?: chainTake ?: defaultViewing
    val now = rememberProductionNow(placeholders.any { it is ShotPlaceholder.Run })
    var dialog by rememberSaveable(episodeId, shot.code) { mutableStateOf<PlanningDialog?>(null) }
    val sceneReviewTarget = ReviewTarget.scene(episodeId, location.scene.code)
    var sceneReviewOpen by rememberSaveable(episodeId, location.scene.code) { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<PlanningSheet?>(null) }
    var specsExpanded by rememberSaveable(episodeId, shot.code) { mutableStateOf(false) }
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
    val working = busy || localBusy || loading
    val comfyWorkflows by produceState<List<com.promenar.infinity.domain.mediagen.ComfyWorkflowState>>(emptyList(), services.comfyRepository) {
        val repo = services.comfyRepository
        if (repo == null) value = emptyList() else repo.observeEffectiveWorkflows().collect { value = it }
    }
    val comfyHosts by produceState<List<com.promenar.infinity.data.mediagen.comfy.ComfyHost>>(emptyList(), services.comfyRepository) {
        val repo = services.comfyRepository
        if (repo == null) value = emptyList() else repo.observeHosts().collect { value = it }
    }
    val comfyChains by produceState<List<com.promenar.infinity.domain.mediagen.StatefulChainRecord>>(emptyList(), services.comfyChainStore, sessionId) {
        val store = services.comfyChainStore
        if (store == null) value = emptyList() else store.observeSession(sessionId).collect { value = it }
    }
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
                stringResource(R.string.production_panel_episode_number, location.episode.number).takeIf { production.showEpisodeNumbers() },
                stringResource(R.string.production_panel_scene_number, location.scene.number),
                shot.size?.takeIf(String::isNotBlank),
                secondsLabel(shot.durationSeconds),
                generatedTimes(generations.total).takeIf { generations.total > 0 && !costAlert(generations) },
            ),
            onBack = onBack,
            metaAccent = generatedTimes(generations.total).takeIf { costAlert(generations) },
        )
        val detailTabs = ShotDetailTab.entries
        val pagerState = rememberPanelTabPagerState(
            tabs = detailTabs,
            selected = tab,
            onSelect = { tab = it },
            key = Triple(episodeId, shot.code, initialTab),
        )
        InfinityPanelTabs(
            tabs = listOf(
                InfinityPanelTab(ShotDetailTab.STORYBOARD, stringResource(R.string.p2_tab_storyboard), dot = MaterialTheme.colorScheme.primary.takeIf { !location.scene.storyboardLocked }),
                InfinityPanelTab(ShotDetailTab.CANDIDATES, stringResource(R.string.p2_tab_candidates), count = shot.takes.size.takeIf { it > 0 }?.toString()),
            ),
            selected = tab,
            onSelect = { tab = it },
            contentInset = InfinitySpacing.Large,
            pagerState = pagerState,
        )
        val globalNotice = LocalInfinityNoticeController.current
        val activeError = error ?: localError
        LaunchedEffect(activeError, globalNotice) {
            if (activeError != null && globalNotice != null) {
                globalNotice.show(InfinityNoticeSeverity.ERROR, activeError)
                if (error != null) onDismissError()
                localError = null
            }
        }
        LaunchedEffect(notice, globalNotice) {
            val activeNotice = notice
            if (activeNotice != null && globalNotice != null) {
                globalNotice.show(InfinityNoticeSeverity.INFO, activeNotice)
                notice = null
            }
        }
        if (globalNotice == null) {
            activeError?.let { message -> InfinityStatusStrip(InfinityStatusKind.Error, message, onDismiss = { onDismissError(); localError = null }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            notice?.let { InfinityStatusStrip(InfinityStatusKind.Info, it, onDismiss = { notice = null }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        }
        HorizontalPager(
            state = pagerState,
            key = { detailTabs[it] },
            verticalAlignment = Alignment.Top,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { pageIndex ->
            if (detailTabs[pageIndex] == ShotDetailTab.CANDIDATES) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(ProductionSectionGap)) {
                    if (production.experimental) item("experimental") { ProductionTrialStrip() }
                    item("player") {
                        val abPair = remember(shot, viewing) { viewing?.let { abComparePair(shot, it) } }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (viewingPlaceholder == null && viewing != null && abPair != null) {
                                val (sourceTake, derivedTake) = abPair
                                val sourceSpec = sourceTake.compactSpecLabel()
                                val derivedSpec = derivedTake.compactSpecLabel()
                                InfinitySegmentedControl(
                                    segments = listOf(
                                        InfinitySegment(
                                            value = sourceTake.id,
                                            label = if (sourceSpec != null) stringResource(R.string.p2_ab_source_with_spec, sourceSpec)
                                            else stringResource(R.string.p2_ab_source_take),
                                        ),
                                        InfinitySegment(
                                            value = derivedTake.id,
                                            label = if (derivedSpec != null) stringResource(R.string.p2_ab_derived_with_spec, derivedSpec)
                                            else stringResource(R.string.p2_ab_derived_take),
                                        ),
                                    ),
                                    selected = viewing.id,
                                    onSelect = { viewingId = it },
                                    fillWidth = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (viewingPlaceholder != null) ProductionPlaceholderCard(viewingPlaceholder, now, working, onRun, episodeId, shot.code)
                            else if (viewing != null) key(viewing.id) { ProductionMediaReference(viewing.path, Modifier.fillMaxWidth(), alt = shot.title) }
                            else ProductionSection(null) {
                                ProductionMutedRow(stringResource(R.string.production_panel_no_takes))
                            }
                            if (shot.takes.isNotEmpty() || placeholders.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(shot.takes, key = { it.id }) { take -> TakeThumb(take, shot.takes.indexOf(take) + 1, take.id == viewing?.id, take.id == shot.selectedTakeId, services.channelLabelSegments(take.source.service).formatChannelLabel()) { viewingId = take.id } }
                                items(placeholders, key = { it.key }) { item -> PlaceholderThumb(item, item.key == viewingPlaceholder?.key, now) { viewingId = item.key } }
                            }
                            if (viewing != null) {
                                val number = shot.takes.indexOf(viewing) + 1
                                val takeTitle = stringResource(R.string.production_panel_take_number, number)
                                ProductionReviewCard(
                                    title = stringResource(R.string.production_review_card_title, takeTitle),
                                    production = production,
                                    target = ReviewTarget.take(episodeId, shot.code, viewing.id),
                                    label = listOf(shot.code, takeTitle),
                                    busy = working,
                                    onRun = onRun,
                                    onSubmit = onSubmit,
                                    // 审核记录之前已选定的候选视为已批准。
                                    settled = viewing.id == shot.selectedTakeId && shot.status == ShotStatus.LOCKED,
                                )
                                TakeSourceSection(viewing, shot, production) { viewingId = it }
                                TakeSegmentsSection(viewing)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = ProductionPagePadding, verticalArrangement = Arrangement.spacedBy(ProductionSectionGap)) {
                    if (production.experimental) item("experimental") { ProductionTrialStrip() }
                    if (!location.scene.storyboardLocked && !production.experimental) item("unlocked") {
                        val sceneLabel = listOf(
                            stringResource(R.string.production_panel_scene_number, location.scene.number),
                            location.scene.heading,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProductionSection(null) {
                                ProductionRow(
                                    title = stringResource(R.string.p2_unlocked_scene),
                                    icon = Icons.Rounded.LockOpen,
                                    chip = production.reviewChipSpec(sceneReviewTarget),
                                    chevron = false,
                                    trailing = {
                                        Icon(
                                            imageVector = if (sceneReviewOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                            contentDescription = stringResource(
                                                if (sceneReviewOpen) R.string.production_review_collapse else R.string.production_review_entry,
                                            ),
                                            tint = if (sceneReviewOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    enabled = !working,
                                    onClick = { sceneReviewOpen = !sceneReviewOpen },
                                )
                            }
                            ProductionInlineReviewSlot(
                                visible = sceneReviewOpen,
                                production = production,
                                target = sceneReviewTarget,
                                label = sceneLabel,
                                busy = working,
                                onDismiss = { sceneReviewOpen = false },
                                onSubmit = onSubmit,
                                settled = location.scene.storyboardLocked,
                            )
                            ProductionReviewNotes(production, sceneReviewTarget)
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
                        val effectiveChannel = remember(shot.channelId, shot.mode, channels, channelOrder, production.defaultVideoChannelId) {
                            resolvedShotChannel(shot, channels, channelOrder, production.defaultVideoChannelId)
                        }
                        val effectiveMode = production.effectiveReferenceInputMode(effectiveChannel)
                        Column {
                            ProductionSectionHeader(stringResource(R.string.p2_character_refs))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                shot.appearances.forEach { appearance ->
                                    CharacterReferenceCard(
                                        appearance = appearance,
                                        production = production,
                                        enabled = !working,
                                        inputMode = effectiveMode,
                                        onOpen = { onOpenCharacter(appearance.characterAssetId) },
                                        onLook = { lookCharacter = appearance.characterAssetId; sheet = PlanningSheet.LOOK },
                                    ) { look, slotId ->
                                        val updated = appearance.toggleSlot(look, slotId)
                                        onRun(ProductionUserAction.ToggleReferenceSlot(episodeId, shot.code, appearance.characterAssetId, slotId, slotId in updated.referenceSlotIds))
                                    }
                                }
                            }
                        }
                    }
                    item("channel-mode") {
                        val resolvedChannel = if (preview != null) preview?.channel else resolvedShotChannel(shot, channels, channelOrder, production.defaultVideoChannelId)
                        val noChannel = resolvedChannel == null
                        val channelName = preview?.channelLabel?.takeIf(String::isNotBlank)
                            ?.split(" $InfinityMetaSeparator ") ?: shot.channelId?.let(services.channelLabelSegments)
                            ?: listOf(
                                if (noChannel) stringResource(R.string.p2_no_channel_for_mode, shot.mode.label())
                                else stringResource(R.string.p2_default_channel),
                            )
                        val defaultLabel = stringResource(R.string.p2_default)
                            .takeIf { !noChannel && preview?.channelIsDefault == true && !preview?.channelLabel.isNullOrBlank() }
                        ProductionSection(
                            stringResource(R.string.p2_channel_mode),
                            modifier = Modifier.animateContentSize(InfinityMotion.contentSizeSpec()),
                        ) {
                            ProductionRow(stringResource(R.string.p2_mode), value = shot.mode.label(), enabled = !working, onClick = { sheet = PlanningSheet.MODE })
                            ProductionRow(stringResource(R.string.p2_channel), valueSegments = channelName + listOfNotNull(defaultLabel), enabled = !working, onClick = { sheet = PlanningSheet.CHANNEL })
                            AnimatedVisibility(
                                visible = noChannel && (!loading || preview != null || services.planner == null),
                                enter = InfinityMotion.expandIn,
                                exit = InfinityMotion.shrinkOut,
                            ) {
                                ChannelMismatchGuideCard(
                                    shot = shot,
                                    channels = channels,
                                    services = services,
                                    enabled = !working,
                                    onSwitchMode = { targetMode -> onRun(modeSelectionAction(location, targetMode, channels, channelOrder)) },
                                )
                            }
                            val activeWfForSpecs = resolvedChannel?.id?.let { id ->
                                comfyWorkflows.firstOrNull { wf -> wf.workflow.channelId == id }?.workflow
                            }
                            val canConfigureSpecs = resolvedChannel != null && (
                                (!resolvedChannel.id.startsWith("comfy:") && resolvedChannel.capabilities.resolutions.isNotEmpty()) ||
                                    (resolvedChannel.id.startsWith("comfy:") && (
                                        (activeWfForSpecs?.fields?.any { it.semanticRole == FieldSemanticRole.WIDTH } == true &&
                                            activeWfForSpecs.fields.any { it.semanticRole == FieldSemanticRole.HEIGHT }) ||
                                            (preview?.specs?.width != null && preview?.specs?.height != null)
                                        ))
                                )
                            val effectiveCustomSpecs = hasEffectiveCustomSpecs(shot, resolvedChannel, activeWfForSpecs)
                            val defaultSpecsHint = if (effectiveCustomSpecs) {
                                val defaultLabelText = when {
                                    resolvedChannel?.id?.startsWith("comfy:") == true -> {
                                        val dw = activeWfForSpecs?.fields?.firstOrNull { it.semanticRole == FieldSemanticRole.WIDTH }?.defaultValue
                                            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                                        val dh = activeWfForSpecs?.fields?.firstOrNull { it.semanticRole == FieldSemanticRole.HEIGHT }?.defaultValue
                                            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                                        if (dw != null && dh != null) "${dw}×${dh}" else null
                                    }
                                    else -> resolvedChannel?.capabilities?.resolutions?.firstOrNull()
                                }
                                defaultLabelText?.let { stringResource(R.string.p2_specs_default_label, it) }
                            } else null
                            ProductionRow(
                                title = stringResource(R.string.p2_specs),
                                subtitle = defaultSpecsHint,
                                valueSegments = planSpecsSegments(preview, production, shot),
                                chip = if (effectiveCustomSpecs) ProductionChipSpec(stringResource(R.string.p2_specs_custom), ProductionTone.NEUTRAL) else null,
                                chevron = false,
                                trailing = if (canConfigureSpecs) ({
                                    Icon(
                                        imageVector = if (specsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                        contentDescription = stringResource(R.string.p2_specs_sheet_title),
                                        tint = if (specsExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }) else null,
                                enabled = !working && canConfigureSpecs,
                                onClick = if (canConfigureSpecs) ({ specsExpanded = !specsExpanded }) else null,
                            )
                            AnimatedVisibility(
                                visible = specsExpanded && canConfigureSpecs && resolvedChannel != null,
                                enter = InfinityMotion.expandIn,
                                exit = InfinityMotion.shrinkOut,
                            ) {
                                resolvedChannel?.let { activeChannel ->
                                    ShotSpecsInlineEditor(
                                        shot = shot,
                                        channel = activeChannel,
                                        workflow = activeWfForSpecs,
                                        previewSpecs = preview?.specs,
                                        enabled = !working,
                                        onSave = { resolution, width, height ->
                                            specsExpanded = false
                                            onRun(ProductionUserAction.SetShotSpecs(episodeId, shot.code, resolution, width, height))
                                        },
                                    )
                                }
                            }
                            ProductionRow(stringResource(R.string.p2_preview_asset), value = shot.preview?.label() ?: stringResource(R.string.p2_none))
                            shot.previewPath?.let { ProductionMediaReference(it, Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 8.dp), asLink = shot.preview == PreviewKind.TEXT_CARD) }
                        }
                    }
                    item("inputs") {
                        val inputs = preview?.inputs.orEmpty()
                        val resolvedChannel = if (preview != null) preview?.channel else resolvedShotChannel(shot, channels, channelOrder, production.defaultVideoChannelId)
                        val noChannel = resolvedChannel == null
                        val packing = resolvedChannel?.takeIf { shot.mode == ShotMode.REFERENCE && (shot.appearances.isNotEmpty() || shot.locationAssetId != null || shot.propAssetIds.isNotEmpty()) }?.let {
                            stringResource(if (production.effectiveReferenceInputMode(it) == ReferenceInputMode.SHEET) R.string.p2_composite else R.string.p2_separate)
                        }
                        val images = inputs.count { it.kind == InputKind.IMAGE && !it.isAnchor }
                        val anchors = inputs.count { it.isAnchor }
                        val wfFps = resolvedChannel?.id?.let { id ->
                            comfyWorkflows.firstOrNull { it.workflow.channelId == id }?.workflow?.fps
                        }
                        Column(
                            modifier = Modifier.animateContentSize(InfinityMotion.contentSizeSpec()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val sectionTitle = stringResource(R.string.production_panel_section_inputs)
                            ProductionSection(
                                sectionTitle,
                                titleSegments = listOfNotNull(
                                    sectionTitle,
                                    packing,
                                    stringResource(R.string.p2_image_count, images).takeIf { images > 0 },
                                    stringResource(R.string.p2_anchor_count, anchors).takeIf { anchors > 0 },
                                    stringResource(R.string.p2_waiting_channel).takeIf { noChannel && inputs.isNotEmpty() },
                                ),
                                trailing = preview?.channel?.let { stringResource(R.string.p2_inputs_by_channel, preview?.channelLabel?.substringBefore(" $InfinityMetaSeparator ") ?: it.id) },
                                modifier = Modifier.animateContentSize(InfinityMotion.contentSizeSpec()),
                            ) {
                                AnimatedVisibility(
                                    visible = loading && preview == null,
                                    enter = InfinityMotion.expandIn,
                                    exit = InfinityMotion.shrinkOut,
                                ) {
                                    Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(12.dp), contentAlignment = Alignment.Center) { InfinityLoadingIndicator() }
                                }
                                inputs.forEachIndexed { idx, planned ->
                                    if (idx > 0) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                            modifier = Modifier.padding(start = 76.dp, end = InfinitySpacing.RowHorizontal),
                                        )
                                    }
                                    PlannedInputRow(planned, fps = wfFps)
                                }
                                if (preview != null && inputs.isEmpty()) ProductionMutedRow(stringResource(R.string.p2_no_inputs))
                                if (services.planner == null) ProductionMutedRow(noPlanner)
                            }
                            val overflowText = preview?.overflow?.takeIf { it.isNotEmpty() }?.let { overflow ->
                                stringResource(R.string.p2_overflow, overflow.joinToString("、") { it.title })
                            }
                            val heldOverflow = rememberLastNonNull(overflowText, key = "plan-overflow")
                            AnimatedVisibility(
                                visible = overflowText != null && heldOverflow != null,
                                enter = InfinityMotion.expandIn,
                                exit = InfinityMotion.shrinkOut,
                            ) {
                                heldOverflow?.let { InfinityStatusStrip(InfinityStatusKind.Warning, it) }
                            }
                            val warnings = preview?.warnings.orEmpty()
                            AnimatedVisibility(
                                visible = warnings.isNotEmpty(),
                                enter = InfinityMotion.expandIn,
                                exit = InfinityMotion.shrinkOut,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    warnings.forEach { warning ->
                                        val targetCharacter = shot.appearances.mapNotNull { app -> production.findAsset(app.characterAssetId) }
                                            .firstOrNull { char -> char.status != AssetStatus.LOCKED && char.name in warning }
                                        if (targetCharacter != null) {
                                            ReviewWarningBanner(warning) { onOpenCharacter(targetCharacter.id) }
                                        } else {
                                            InfinityStatusStrip(InfinityStatusKind.Warning, warning)
                                        }
                                    }
                                }
                            }
                            val blockedReasons = remainingBlockedReasons(preview, location.scene.storyboardLocked, noChannel, shot.channelId)
                            AnimatedVisibility(
                                visible = blockedReasons.isNotEmpty(),
                                enter = InfinityMotion.expandIn,
                                exit = InfinityMotion.shrinkOut,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    blockedReasons.forEach { reason ->
                                        InfinityStatusStrip(InfinityStatusKind.Warning, reason)
                                    }
                                }
                            }
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
                            val sectionTitle = stringResource(R.string.p2_submission_prompt)
                            ProductionSectionHeader(
                                sectionTitle,
                                titleSegments = listOf(sectionTitle, preview?.promptFormat?.label() ?: stringResource(R.string.p2_format_generic)),
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
                    val activeChannelId = shot.channelId ?: resolvedShotChannel(shot, channels, channelOrder, production.defaultVideoChannelId)?.id
                    val activeComfyWf = activeChannelId?.let { id ->
                        comfyWorkflows.firstOrNull { wf -> wf.workflow.channelId == id }
                    }
                    if (activeComfyWf != null) item("comfy-workflow-chain") {
                        ProductionComfyChainSection(
                            workflowState = activeComfyWf,
                            hosts = comfyHosts,
                            chains = comfyChains,
                            sessionId = sessionId,
                            shot = shot,
                            defaultPrompt = preview?.prompt ?: shot.prompt,
                            executionEngine = services.comfyExecutionEngine,
                            chainStore = services.comfyChainStore,
                            parentWorking = working,
                            onResultMessage = { msg -> notice = msg },
                            onErrorMessage = { err -> localError = err },
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
            val cloudChoices = choices.filterNot { it.id.startsWith("comfy:") }
            val comfyChoices = choices.filter { it.id.startsWith("comfy:") }
            val cloudLabel = stringResource(R.string.p2_channel_cloud_group)
            val singleLabel = stringResource(R.string.media_settings_exec_single)
            val chainLabel = stringResource(R.string.media_settings_exec_chain)
            val hostNames = comfyHosts.associate { it.id to it.name }
            val workflowsByChannel = comfyWorkflows.associateBy { it.workflow.channelId }
            val options = buildList {
                cloudChoices.forEach { ch ->
                    add(
                        ProductionChoice(
                            value = ch.id,
                            label = ch.id,
                            detail = cloudLabel,
                            icon = Icons.Rounded.Cloud,
                            labelSegments = services.channelLabelSegments(ch.id),
                        ),
                    )
                }
                val groupedComfy = comfyChoices.groupBy { ch -> workflowsByChannel[ch.id]?.workflow?.hostId ?: "comfy" }
                groupedComfy.forEach { (hostId, hostChoices) ->
                    val hostTitle = hostNames[hostId] ?: hostId
                    val comfyDetail = stringResource(R.string.p2_channel_comfy_group, hostTitle)
                    hostChoices.forEach { ch ->
                        val wf = workflowsByChannel[ch.id]?.workflow
                        val isChain = wf?.executionMode == com.promenar.infinity.domain.mediagen.WorkflowExecutionMode.STATEFUL_CHAIN
                        add(
                            ProductionChoice(
                                value = ch.id,
                                label = ch.id,
                                detail = comfyDetail,
                                icon = Icons.Rounded.Dns,
                                chip = ProductionChipSpec(
                                    if (isChain) chainLabel else singleLabel,
                                    if (isChain) ProductionTone.INFO else ProductionTone.NEUTRAL,
                                ),
                                labelSegments = services.channelLabelSegments(ch.id),
                            ),
                        )
                    }
                }
            }
            ProductionChoiceSheet(
                title = stringResource(R.string.p2_channel),
                options = options,
                selected = shot.channelId ?: choices.firstOrNull()?.id,
                onDismiss = { sheet = null },
                emptyText = stringResource(R.string.p2_no_channels),
            ) { onRun(ProductionUserAction.SetShotPlan(episodeId, shot.code, it, shot.mode)) }
        }
        PlanningSheet.LOOK -> {
            val character = production.findAsset(lookCharacter.orEmpty())
            ProductionOptionSheet(
                title = stringResource(R.string.p2_look),
                options = character?.looks.orEmpty().map { it.id to it.name },
                selected = shot.appearances.firstOrNull { it.characterAssetId == character?.id }?.lookId,
                onDismiss = { sheet = null },
                emptyText = stringResource(R.string.p2_no_looks),
            ) { id -> character?.let { onRun(ProductionUserAction.SetCharacterLook(episodeId, shot.code, it.id, id)) } }
        }
        null -> Unit
    }
    when (dialog) {
        PlanningDialog.RECOMPILE -> InfinityConfirmDialog(stringResource(R.string.p2_recompile), stringResource(R.string.p2_recompile_message), stringResource(R.string.p2_recompile), onConfirm = {
            dialog = null; execute { services.planner?.recompilePrompt(sessionId, episodeId, shot.code); refresh++ }
        }, onCancel = { dialog = null })
        PlanningDialog.PROMPT -> ProductionTextDialog(stringResource(R.string.production_panel_prompt_title), stringResource(R.string.production_panel_section_prompt), preview?.prompt ?: shot.prompt, stringResource(R.string.production_panel_save), PRODUCTION_PROMPT_MAX, 6, ProductionUiTags.PROMPT_FIELD, { dialog = null }, { onSubmit(ProductionUserAction.EditPrompt(episodeId, shot.code, it)) })
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
                Text(label, style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.widthIn(min = 44.dp, max = 96.dp))
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

/** 角色参考卡：带头像缩略图；有造型时依入参模式展示整卡标记或分张参考位，未拆造型有版本图时标为“使用版本图”。 */
@Composable
private fun CharacterReferenceCard(
    appearance: ShotAppearance,
    production: Production,
    enabled: Boolean,
    inputMode: ReferenceInputMode,
    onOpen: () -> Unit,
    onLook: () -> Unit,
    onToggle: (CharacterLook, String) -> Unit,
) {
    val asset = production.findAsset(appearance.characterAssetId)
    val look = asset?.looks?.firstOrNull { it.id == appearance.lookId }
    val thumbPath = asset?.coverPreviewPath()
    val usesVersionFallback = look == null && asset?.looks.isNullOrEmpty()
    val hasVersion = asset?.currentPath() != null
    val scheme = MaterialTheme.colorScheme
    BettboxListGroup {
        if (usesVersionFallback) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onOpen)
                    .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CharacterRefThumb(thumbPath, asset?.name ?: appearance.characterAssetId)
                Text(
                    asset?.name ?: appearance.characterAssetId,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip(
                    stringResource(if (hasVersion) R.string.p2_using_version_image_full else R.string.p2_missing_look),
                    if (hasVersion) ProductionTone.NEUTRAL else ProductionTone.WARNING,
                )
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(start = InfinitySpacing.RowHorizontal, end = 10.dp, top = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.weight(1f).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onOpen).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CharacterRefThumb(thumbPath, asset?.name ?: appearance.characterAssetId)
                        Text(
                            asset?.name ?: appearance.characterAssetId,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Surface(shape = CircleShape, color = scheme.surfaceContainerHighest, onClick = onLook, enabled = enabled) {
                        Row(Modifier.heightIn(min = 36.dp).padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(look?.name ?: stringResource(R.string.p2_missing_look), style = MaterialTheme.typography.labelLarge)
                            Icon(Icons.Rounded.ExpandMore, contentDescription = stringResource(R.string.p2_look), modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (look != null) {
                    if (inputMode == ReferenceInputMode.SHEET && !look.sheetPath.isNullOrBlank()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ProductionStatusChip(stringResource(R.string.p2_using_sheet_image_full), ProductionTone.INFO)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            look.slots.forEach { slot -> InfinityChip(slot.displayName(), selected = slot.id in appearance.referenceSlotIds, enabled = enabled, onClick = { onToggle(look, slot.id) }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterRefThumb(path: String?, name: String) {
    val scheme = MaterialTheme.colorScheme
    if (path != null) {
        ProductionThumbnail(path, Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)), contentDescription = name)
    } else {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Person, contentDescription = name, modifier = Modifier.size(18.dp), tint = scheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun ChannelMismatchGuideCard(
    shot: Shot,
    channels: List<MediaChannel>,
    services: ProductionPanelServices,
    enabled: Boolean,
    onSwitchMode: (ShotMode) -> Unit,
) {
    val domain = MaterialTheme.infinityDomainColors
    val videoChannels = remember(channels) { channels.filter { it.group == MediaGroup.VIDEO }.distinctBy { it.id } }
    val message = when (videoChannels.size) {
        0 -> stringResource(R.string.p2_channel_guide_empty)
        1 -> {
            val only = videoChannels.single()
            val modes = only.capabilities.modes.mapNotNull { it.toShotMode() }.distinct()
            val modesLabel = modes.map { it.label() }.joinToString(" / ").ifBlank { "-" }
            stringResource(R.string.p2_channel_guide_single, services.channelLabelSegments(only.id).formatChannelLabel(), modesLabel, shot.mode.label())
        }
        else -> stringResource(R.string.p2_channel_guide_multiple, videoChannels.size, shot.mode.label())
    }
    val alternativeMode = videoChannels.asSequence()
        .flatMap { it.capabilities.modes.asSequence() }
        .mapNotNull { it.toShotMode() }
        .firstOrNull { it != shot.mode }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = domain.infoContainer.copy(alpha = 0.6f),
        border = BorderStroke(0.5.dp, domain.onInfoContainer.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(18.dp).padding(top = 1.dp), tint = domain.onInfoContainer)
                Text(message, style = MaterialTheme.typography.bodySmall, color = domain.onInfoContainer)
            }
            if (services.onOpenMediaModels != null || alternativeMode != null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    services.onOpenMediaModels?.let { openModels ->
                        InfinityPillButton(stringResource(R.string.p2_media_models), onClick = openModels, tone = InfinityPillTone.PRIMARY, enabled = enabled, compact = true)
                    }
                    alternativeMode?.let { mode ->
                        InfinityPillButton(stringResource(R.string.p2_switch_mode_to, mode.label()), onClick = { onSwitchMode(mode) }, tone = InfinityPillTone.NEUTRAL, enabled = enabled, compact = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewWarningBanner(warning: String, onReview: () -> Unit) {
    val domain = MaterialTheme.infinityDomainColors
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = domain.warningContainer,
        border = BorderStroke(0.8.dp, domain.onWarningContainer.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp), tint = domain.onWarningContainer)
            Text(warning, style = MaterialTheme.typography.bodySmall, color = domain.onWarningContainer, modifier = Modifier.weight(1f))
            InfinityPillButton(stringResource(R.string.p2_go_review), onClick = onReview, tone = InfinityPillTone.NEUTRAL, compact = true)
        }
    }
}

private fun GenerationMode.toShotMode(): ShotMode? = when (this) {
    GenerationMode.TEXT -> ShotMode.TEXT
    GenerationMode.FIRST_FRAME -> ShotMode.FIRST_FRAME
    GenerationMode.LAST_FRAME -> ShotMode.LAST_FRAME
    GenerationMode.FIRST_LAST_FRAME -> ShotMode.FIRST_LAST
    GenerationMode.REFERENCE -> ShotMode.REFERENCE
    GenerationMode.SPEECH, GenerationMode.MUSIC -> null
}

@Composable
private fun remainingBlockedReasons(
    preview: ShotPlanPreview?,
    sceneLocked: Boolean,
    noChannel: Boolean,
    explicitChannelId: String?,
): List<String> {
    val raw = preview?.blockedReason ?: return emptyList()
    val unlockedText = stringResource(R.string.shot_plan_unlocked, "")
    val noChannelModeText = stringResource(R.string.shot_plan_no_channel_mode, "")
    val explicitNoChannelPrefix = explicitChannelId?.let { stringResource(R.string.shot_plan_no_channel, it) }
    val overflowPrefix = stringResource(R.string.shot_plan_overflow, "")
    return raw.lineSequence().map(String::trim).filter(String::isNotBlank).filterNot { line ->
        (!sceneLocked && unlockedText.isNotBlank() && line.startsWith(unlockedText.trim())) ||
            (noChannel && line == noChannelModeText.trim()) ||
            (noChannel && explicitNoChannelPrefix != null && line == explicitNoChannelPrefix.trim()) ||
            (preview.overflow.isNotEmpty() && overflowPrefix.isNotBlank() && line.startsWith(overflowPrefix.trim()))
    }.toList()
}

/** 单镜头规格面板原位内联编辑区（方向 C 已确认原型，不遮挡左侧对话，不改动工作流或模型默认设置）。 */
@Composable
internal fun ShotSpecsInlineEditor(
    shot: Shot,
    channel: MediaChannel,
    workflow: ComfyWorkflow?,
    previewSpecs: GenerationSpecs?,
    enabled: Boolean = true,
    onSave: (resolution: String?, width: Int?, height: Int?) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val isComfy = channel.id.startsWith("comfy:")
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (dark) scheme.surfaceContainerHigh.copy(alpha = 0.72f) else scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isComfy) {
                val defaultW = workflow?.fields?.firstOrNull { it.semanticRole == FieldSemanticRole.WIDTH }?.defaultValue
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                    ?: previewSpecs?.width?.takeIf { shot.specWidth == null }
                val defaultH = workflow?.fields?.firstOrNull { it.semanticRole == FieldSemanticRole.HEIGHT }?.defaultValue
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                    ?: previewSpecs?.height?.takeIf { shot.specHeight == null }
                var customMode by remember(shot.code, shot.specWidth, shot.specHeight, defaultW, defaultH) {
                    mutableStateOf(hasEffectiveCustomSpecs(shot, channel, workflow))
                }
                var widthText by remember(shot.code, shot.specWidth, defaultW) {
                    mutableStateOf((shot.specWidth ?: defaultW)?.toString().orEmpty())
                }
                var heightText by remember(shot.code, shot.specHeight, defaultH) {
                    mutableStateOf((shot.specHeight ?: defaultH)?.toString().orEmpty())
                }
                var validationError by remember { mutableStateOf<String?>(null) }
                val invalidDimensionsMsg = stringResource(R.string.p2_specs_invalid_dimensions)

                InfinitySegmentedControl(
                    segments = listOf(
                        InfinitySegment(false, stringResource(R.string.p2_specs_workflow_default)),
                        InfinitySegment(true, stringResource(R.string.p2_specs_custom_short)),
                    ),
                    selected = customMode,
                    enabled = enabled,
                    onSelect = { wantCustom ->
                        customMode = wantCustom
                        validationError = null
                        if (!wantCustom && shot.hasCustomSpecs()) {
                            onSave(null, null, null)
                        }
                    },
                    fillWidth = true,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (customMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        InlinePixelSlot(
                            label = stringResource(R.string.p2_specs_width_short),
                            value = widthText,
                            enabled = enabled,
                            onValueChange = { widthText = it.filter(Char::isDigit).take(4); validationError = null },
                            modifier = Modifier.weight(1f),
                        )
                        Text("×", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                        InlinePixelSlot(
                            label = stringResource(R.string.p2_specs_height_short),
                            value = heightText,
                            enabled = enabled,
                            onValueChange = { heightText = it.filter(Char::isDigit).take(4); validationError = null },
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = scheme.surfaceContainerHighest.copy(alpha = 0.7f),
                        ) {
                            IconButton(
                                onClick = {
                                    val oldW = widthText
                                    widthText = heightText
                                    heightText = oldW
                                    validationError = null
                                },
                                enabled = enabled,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SwapHoriz,
                                    contentDescription = stringResource(R.string.p2_specs_swap),
                                    tint = scheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    validationError?.let { err ->
                        Text(err, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                    }
                    val parsedW = widthText.toIntOrNull()
                    val parsedH = heightText.toIntOrNull()
                    val ratioLabel = if (parsedW != null && parsedH != null && parsedW > 0 && parsedH > 0) {
                        formatGenericRatioLabel(parsedW, parsedH)
                    } else null
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = ratioLabel?.let { stringResource(R.string.p2_specs_ratio_hint, it) }
                                ?: stringResource(R.string.p2_specs_range_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        InfinityPillButton(
                            text = stringResource(R.string.p2_specs_apply),
                            onClick = {
                                val w = widthText.toIntOrNull()
                                val h = heightText.toIntOrNull()
                                if (w == null || h == null || w !in 64..8192 || h !in 64..8192) {
                                    validationError = invalidDimensionsMsg
                                } else {
                                    onSave(null, w, h)
                                }
                            },
                            enabled = enabled,
                            tone = InfinityPillTone.PRIMARY,
                            icon = Icons.Rounded.Check,
                            compact = true,
                        )
                    }
                } else {
                    val defaultSegments = buildList {
                        if (defaultW != null && defaultH != null) {
                            add("${defaultW}×${defaultH}")
                            add(formatGenericRatioLabel(defaultW, defaultH))
                        }
                        add(stringResource(R.string.p2_specs_follow_workflow_hint))
                    }
                    InfinityMetaLine(
                        segments = defaultSegments,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                val resolutions = channel.capabilities.resolutions.distinct()
                val options: List<Pair<String?, String>> = listOf(null to stringResource(R.string.p2_specs_default_short)) +
                    resolutions.map { it to it }
                val useEqualRow = options.size <= 4
                if (useEqualRow) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        options.forEach { (value, label) ->
                            val selected = if (value == null) shot.specResolution.isNullOrBlank() else shot.specResolution == value
                            InlineResolutionOptionButton(
                                label = label,
                                selected = selected,
                                enabled = enabled,
                                onClick = { onSave(value, null, null) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        options.forEach { (value, label) ->
                            val selected = if (value == null) shot.specResolution.isNullOrBlank() else shot.specResolution == value
                            InlineResolutionOptionButton(
                                label = label,
                                selected = selected,
                                enabled = enabled,
                                onClick = { onSave(value, null, null) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlinePixelSlot(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceContainerHighest.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.titleMedium.forInput().copy(
                    fontFamily = InfinityMonospace,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                ),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InlineResolutionOptionButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest.copy(alpha = 0.65f),
        border = if (selected) BorderStroke(1.dp, scheme.primary.copy(alpha = 0.5f)) else null,
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = scheme.onPrimaryContainer,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun formatGenericRatioLabel(width: Int, height: Int): String {
    val ratio = width.toDouble() / height.toDouble()
    val known = listOf(
        16.0 / 9.0 to "16:9",
        9.0 / 16.0 to "9:16",
        4.0 / 3.0 to "4:3",
        3.0 / 4.0 to "3:4",
        21.0 / 9.0 to "21:9",
        3.0 / 2.0 to "3:2",
        2.0 / 3.0 to "2:3",
        1.0 to "1:1",
    )
    val base = known.firstOrNull { abs(ratio - it.first) < 0.04 }?.second
        ?: "${((ratio * 100.0).roundToInt() / 100.0)}:1"
    val orientRes = when {
        width > height -> R.string.p2_specs_orient_landscape
        height > width -> R.string.p2_specs_orient_portrait
        else -> R.string.p2_specs_orient_square
    }
    return "$base ${stringResource(orientRes)}"
}

/** 输入素材行（纯检视，匹配已确认 v1.3 原型）：缩略图严格左对齐，锁帧锚点用徽章与帧号清晰区分于普通参考图。 */
@Composable
internal fun PlannedInputRow(input: PlannedInput, fps: Double? = null) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            input.compositeOf.isNotEmpty() -> Row(Modifier.width(80.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                input.compositeOf.take(4).forEach { ProductionThumbnail(it, Modifier.weight(1f).height(48.dp)) }
            }
            input.kind == InputKind.AUDIO -> Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(scheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.GraphicEq, null, Modifier.size(22.dp), scheme.onTertiaryContainer)
            }
            else -> ProductionThumbnail(input.path, Modifier.size(48.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = input.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (input.isAnchor) FontWeight.Medium else FontWeight.Normal,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (input.isAnchor) {
                    Surface(shape = RoundedCornerShape(6.dp), color = domain.infoContainer) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(Icons.Rounded.PushPin, contentDescription = null, tint = domain.onInfoContainer, modifier = Modifier.size(11.dp))
                            Text(
                                text = stringResource(R.string.p2_anchor_badge),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = domain.onInfoContainer,
                            )
                        }
                    }
                } else {
                    input.number?.let { num ->
                        Text(
                            text = stringResource(R.string.p2_image_number, num),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val anchorTiming = if (input.isAnchor && input.atSeconds != null) {
                val secs = formatShotSeconds(input.atSeconds)
                val frame = fps?.let { kotlin.math.round(input.atSeconds * it).toInt().coerceAtLeast(0) }
                if (frame != null) stringResource(R.string.p2_anchor_frame_seconds, frame, secs) else input.detail
            } else input.detail
            if (anchorTiming.isNotBlank()) {
                Text(
                    text = anchorTiming,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}
