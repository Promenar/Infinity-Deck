package com.promenar.infinity.ui.production

import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.common.InfinityPanelTab
import com.promenar.infinity.ui.common.rememberPanelTabPagerState
import com.promenar.infinity.navigation.InfinityPageStack
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import com.promenar.infinity.ui.settings.MediaSettingsPanelHost
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.rememberLastNonNull
import java.net.URLDecoder
import java.net.URLEncoder
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityMenuDivider
import com.promenar.infinity.ui.common.InfinityMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import com.promenar.infinity.R
import com.promenar.infinity.data.production.ManifestResolution
import com.promenar.infinity.data.production.ManifestSync
import com.promenar.infinity.data.production.ProductionKind
import com.promenar.infinity.data.production.ProductionManifestCandidate
import com.promenar.infinity.data.production.ProductionPanelSource
import com.promenar.infinity.data.production.ProductionSnapshot
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.productionCostStats
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityLoadingIndicator
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityNoticeSeverity
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.common.InfinityStatusStrip
import com.promenar.infinity.ui.common.LocalFrostedChromeMaterial
import com.promenar.infinity.ui.common.LocalInfinityNoticeController
import com.promenar.infinity.ui.common.toNoticeSeverity
import com.promenar.infinity.ui.media.LocalWorkspaceMedia
import com.promenar.infinity.ui.media.rememberSessionWorkspaceMedia
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.promenar.infinity.domain.mediagen.MediaTask
import com.promenar.infinity.domain.mediagen.MediaChannel
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.data.production.AssetKind
import com.promenar.infinity.data.production.AssetStatus
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface

/** 面板加载状态。 */
private sealed interface ProductionLoad {
    data object Loading : ProductionLoad
    data object Empty : ProductionLoad
    data class Ready(val snapshot: ProductionSnapshot) : ProductionLoad
}

/** 用户操作的回调：返回可显示的错误，成功时为 null。 */
typealias ProductionActionHandler = suspend (expectedRevision: Long, action: ProductionUserAction) -> String?

/** 清单冲突处理的回调：返回可显示的错误，成功时为 null。 */
typealias ProductionResolveHandler = suspend (ManifestResolution) -> String?

/**
 * 会话的制作面板：订阅 [source]，提供工作区媒体环境，把用户操作交给 [source]。
 * 功能面板入口（窄屏弹出层、宽屏检视器页签）都承载这一个组件。
 */
@Composable
fun ProductionPanel(
    sessionId: String,
    source: ProductionPanelSource,
    modifier: Modifier = Modifier,
    onLocate: (String) -> Unit = {},
    services: ProductionPanelServices = ProductionPanelServices(),
    onBack: (() -> Unit)? = null,
) {
    val load by produceState<ProductionLoad>(ProductionLoad.Loading, sessionId, source) {
        source.observe(sessionId).collect { snapshot ->
            value = if (snapshot == null) ProductionLoad.Empty else ProductionLoad.Ready(snapshot)
        }
    }
    val tasks by produceState<List<MediaTask>>(emptyList(), services.queue) {
        services.queue?.observeTasks()?.collect { value = it }
    }
    val channels by produceState<List<MediaChannel>>(emptyList(), services.registry) {
        services.registry?.observeChannels()?.collect { value = it }
    }
    val channelOrder by produceState<List<String>>(emptyList(), services.registry) {
        services.registry?.observeOrder(MediaGroup.VIDEO)?.collect { value = it }
    }
    val failed = stringResource(R.string.production_panel_action_failed)
    val media = rememberSessionWorkspaceMedia(sessionId, onLocate)
    // 媒体模型在面板所在的容器里以子页打开：宽屏在检视器内，窄屏在同一个整屏容器内，会话页不被盖住。
    var mediaSettingsOpen by rememberSaveable(sessionId) { mutableStateOf(false) }
    val mediaTransition = updateTransition(targetState = mediaSettingsOpen, label = "production_media_settings")
    val mediaTransitioning = mediaTransition.currentState != mediaTransition.targetState
    val hideBaseLayer = mediaSettingsOpen && !mediaTransitioning
    val chrome = LocalFrostedChromeMaterial.current
    val openRoute = services.openRoute
    val panelServices = remember(services) {
        if (services.openRoute == null) services else services.copy(onOpenMediaModels = { mediaSettingsOpen = true })
    }
    var emptyCandidates by remember(sessionId) { mutableStateOf<List<ProductionManifestCandidate>>(emptyList()) }
    var emptyScanning by remember(sessionId) { mutableStateOf(false) }
    var emptyAttachingPath by remember(sessionId) { mutableStateOf<String?>(null) }
    var emptyCreating by remember(sessionId) { mutableStateOf(false) }
    var emptyError by remember(sessionId) { mutableStateOf<String?>(null) }
    var emptyScanNonce by remember(sessionId) { mutableStateOf(0) }
    val emptyScope = rememberCoroutineScope()
    val emptyNoticeController = LocalInfinityNoticeController.current
    LaunchedEffect(sessionId, source, load is ProductionLoad.Empty, emptyScanNonce) {
        if (load !is ProductionLoad.Empty) return@LaunchedEffect
        emptyScanning = true
        try {
            emptyCandidates = runCatching { source.listAttachableManifests(sessionId) }.getOrDefault(emptyList())
        } finally {
            emptyScanning = false
        }
    }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (hideBaseLayer) 0f else 1f }
                .then(if (mediaSettingsOpen) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            CompositionLocalProvider(LocalWorkspaceMedia provides (media ?: LocalWorkspaceMedia.current)) {
                when (val current = load) {
                    ProductionLoad.Loading -> Column(Modifier.fillMaxSize()) {
                        if (onBack != null) ProductionTopBar(stringResource(R.string.p2_production_title), emptyList(), onBack)
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { InfinityLoadingIndicator() }
                    }
                    ProductionLoad.Empty -> Column(Modifier.fillMaxSize()) {
                        ProductionTopBar(stringResource(R.string.p2_production_title), emptyList(), onBack)
                        ProductionPanelEmpty(
                            modifier = Modifier.weight(1f),
                            candidates = emptyCandidates,
                            scanning = emptyScanning,
                            attachingPath = emptyAttachingPath,
                            creating = emptyCreating,
                            error = emptyError,
                            onDismissError = { emptyError = null },
                            onRescan = { if (!emptyScanning) emptyScanNonce += 1 },
                            onAttach = { path ->
                                if (emptyAttachingPath == null && !emptyCreating) {
                                    emptyAttachingPath = path
                                    emptyError = null
                                    emptyScope.launch {
                                        try {
                                            val err = userFacing(failed) { source.attach(sessionId, path) }
                                            if (err != null) {
                                                if (emptyNoticeController != null) emptyNoticeController.show(InfinityNoticeSeverity.ERROR, err)
                                                else emptyError = err
                                            }
                                        } finally {
                                            emptyAttachingPath = null
                                        }
                                    }
                                }
                            },
                            onRequestCreate = services.requestCreateProduction?.let { trigger ->
                                {
                                    if (!emptyCreating && emptyAttachingPath == null) {
                                        emptyCreating = true
                                        emptyError = null
                                        emptyScope.launch {
                                            try {
                                            val (kind, message) = trigger()
                                            if (emptyNoticeController != null) {
                                                emptyNoticeController.showStatus(kind, message)
                                            } else if (kind == InfinityStatusKind.Error) {
                                                emptyError = message
                                            }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                if (emptyNoticeController != null) emptyNoticeController.show(InfinityNoticeSeverity.ERROR, failed)
                                                else emptyError = failed
                                            } finally {
                                                emptyCreating = false
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                    is ProductionLoad.Ready -> ProductionPanelContent(
                        snapshot = current.snapshot,
                        onAction = { revision, action ->
                            userFacing(failed) { source.apply(sessionId, revision, action) }
                        },
                        onResolve = { resolution -> userFacing(failed) { source.resolveManifest(sessionId, resolution) } },
                        onUnbind = { userFacing(failed) { source.unbind(sessionId) } },
                        modifier = Modifier.fillMaxSize(),
                        sessionId = sessionId,
                        services = panelServices,
                        tasks = projectTasks(tasks, services.projectSourcePrefix?.invoke(current.snapshot.production)),
                        channels = channels,
                        channelOrder = channelOrder,
                        onBack = onBack,
                    )
                }
            }
        }
        if (openRoute != null) mediaTransition.AnimatedVisibility(
            visible = { it },
            enter = InfinityMotion.pageEnter(pop = false),
            exit = InfinityMotion.pageExit(pop = true),
            modifier = Modifier.fillMaxSize(),
        ) {
            val overlayColor = when {
                chrome == null -> MaterialTheme.colorScheme.background
                mediaTransitioning -> chrome.frostedTint?.copy(alpha = 0.96f) ?: chrome.container
                else -> Color.Transparent
            }
            Surface(
                color = overlayColor,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                MediaSettingsPanelHost(
                    onClose = { mediaSettingsOpen = false },
                    openOutside = { route ->
                        mediaSettingsOpen = false
                        openRoute(route)
                    },
                )
            }
        }
    }
}

/** 执行 [block]；参数错误的消息可直接显示，其余异常显示 [fallback]。 */
private suspend fun userFacing(fallback: String, block: suspend () -> Unit): String? = try {
    block()
    null
} catch (error: CancellationException) {
    throw error
} catch (error: IllegalArgumentException) {
    error.message ?: fallback
} catch (error: IllegalStateException) {
    error.message ?: fallback
} catch (_: Exception) {
    fallback
}

@Composable
fun ProductionPanelEmpty(
    modifier: Modifier = Modifier,
    candidates: List<ProductionManifestCandidate> = emptyList(),
    scanning: Boolean = false,
    attachingPath: String? = null,
    creating: Boolean = false,
    error: String? = null,
    onDismissError: () -> Unit = {},
    onRescan: (() -> Unit)? = null,
    onAttach: ((String) -> Unit)? = null,
    onRequestCreate: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val busy = attachingPath != null || creating
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(ProductionUiTags.EMPTY),
        contentPadding = PaddingValues(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(ProductionSectionGap),
    ) {
        if (!error.isNullOrBlank()) {
            item("error") {
                InfinityStatusStrip(
                    kind = InfinityStatusKind.Error,
                    message = error,
                    onDismiss = onDismissError,
                )
            }
        }
        item("hero") {
            BettboxListGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = scheme.primaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Movie,
                                contentDescription = null,
                                tint = scheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.p2_empty_panel_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(
                            if (candidates.isNotEmpty()) R.string.p2_empty_panel_hint_with_candidates
                            else R.string.p2_empty_panel_hint_no_candidates,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (onRequestCreate != null) {
                        Spacer(Modifier.height(2.dp))
                        InfinityPillButton(
                            text = stringResource(R.string.p2_empty_ask_agent_create),
                            onClick = onRequestCreate,
                            tone = InfinityPillTone.PRIMARY,
                            icon = Icons.Rounded.AutoAwesome,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (candidates.isEmpty() && onRescan != null) {
                        InfinityPillButton(
                            text = stringResource(if (scanning) R.string.p2_empty_scanning else R.string.p2_empty_scan_workspace),
                            onClick = onRescan,
                            tone = InfinityPillTone.NEUTRAL,
                            icon = Icons.Rounded.Refresh,
                            enabled = !scanning && !busy,
                            compact = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        if (candidates.isNotEmpty()) {
            item("candidates") {
                ProductionSection(
                    title = stringResource(R.string.p2_empty_workspace_projects),
                    titleAction = onRescan?.let { rescan ->
                        {
                            InfinityPillButton(
                                text = stringResource(if (scanning) R.string.p2_empty_scanning else R.string.p2_empty_rescan),
                                onClick = rescan,
                                tone = InfinityPillTone.NEUTRAL,
                                icon = Icons.Rounded.Refresh,
                                enabled = !scanning && !busy,
                                compact = true,
                            )
                        }
                    },
                ) {
                    candidates.forEachIndexed { index, item ->
                        if (index > 0) {
                            HorizontalDivider(
                                Modifier.padding(horizontal = InfinitySpacing.RowHorizontal),
                                color = scheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                        val meta = if (item.kind == ProductionKind.SERIES || item.episodeCount > 1) {
                            stringResource(R.string.p2_empty_series_episodes, item.episodeCount)
                        } else {
                            stringResource(R.string.p2_empty_film_shots, item.shotCount)
                        }
                        val isAttaching = attachingPath == item.manifestPath
                        ProductionRow(
                            title = item.title,
                            icon = Icons.Rounded.FolderOpen,
                            subtitleSegments = listOf(meta, item.manifestPath),
                            subtitleMaxLines = 2,
                            chevron = false,
                            enabled = !busy && onAttach != null,
                            trailing = {
                                InfinityPillButton(
                                    text = stringResource(if (isAttaching) R.string.p2_empty_attaching else R.string.p2_empty_attach),
                                    onClick = { onAttach?.invoke(item.manifestPath) },
                                    tone = InfinityPillTone.NEUTRAL,
                                    icon = Icons.Rounded.Link,
                                    enabled = !busy && onAttach != null,
                                    compact = true,
                                )
                            },
                            onClick = onAttach?.let { attach -> { attach(item.manifestPath) } },
                        )
                    }
                }
            }
        }
    }
}

/** 制作面板二/三级页面栈条目，支持状态保存与防套娃跳转。 */
internal sealed interface ProductionPage {
    data class Shot(val episodeId: String, val shotCode: String) : ProductionPage
    data class Character(val assetId: String) : ProductionPage
}

internal fun encodeProductionPage(page: ProductionPage): String = when (page) {
    is ProductionPage.Shot -> "shot:${URLEncoder.encode(page.episodeId, "UTF-8")}:${URLEncoder.encode(page.shotCode, "UTF-8")}"
    is ProductionPage.Character -> "char:${URLEncoder.encode(page.assetId, "UTF-8")}"
}

internal fun decodeProductionPage(token: String): ProductionPage? {
    val kind = token.substringBefore(':')
    val rest = token.substringAfter(':', "")
    return when (kind) {
        "shot" -> {
            if (':' !in rest) return null
            val episodeId = URLDecoder.decode(rest.substringBefore(':'), "UTF-8")
            val shotCode = URLDecoder.decode(rest.substringAfter(':'), "UTF-8")
            if (episodeId.isNotEmpty() && shotCode.isNotEmpty()) ProductionPage.Shot(episodeId, shotCode) else null
        }
        "char", "asset" -> {
            val assetId = URLDecoder.decode(rest, "UTF-8")
            if (assetId.isNotEmpty()) ProductionPage.Character(assetId) else null
        }
        else -> null
    }
}

/** 压入子页：已在栈顶则不重复压入；与次顶层相同时弹出顶层回退（避免镜头 ↔ 资产往复套娃）。 */
internal fun pushProductionPage(stack: List<String>, next: String): List<String> = when {
    stack.lastOrNull() == next -> stack
    stack.dropLast(1).lastOrNull() == next -> stack.dropLast(1)
    else -> stack + next
}

internal fun initialProductionPageStack(
    production: com.promenar.infinity.data.production.Production,
    initialShot: Pair<String, String>?,
    initialAssetId: String?,
): List<String> = when {
    initialAssetId != null && production.findAsset(initialAssetId)?.kind != AssetKind.SOUND && production.findAsset(initialAssetId) != null ->
        listOf(encodeProductionPage(ProductionPage.Character(initialAssetId)))
    initialShot != null ->
        listOf(encodeProductionPage(ProductionPage.Shot(initialShot.first, initialShot.second)))
    else -> emptyList()
}

internal fun reconcileProductionPageStack(
    stack: List<String>,
    production: com.promenar.infinity.data.production.Production,
    awaitingNewShot: Boolean,
): List<String> = stack.filterIndexed { index, token ->
    when (val page = decodeProductionPage(token)) {
        is ProductionPage.Character -> production.findAsset(page.assetId)?.let { it.kind != AssetKind.SOUND } == true
        is ProductionPage.Shot -> production.findShot(page.episodeId, page.shotCode) != null ||
            (awaitingNewShot && index == stack.lastIndex)
        null -> false
    }
}

/** 面板主体：标题、清单状态、页签与镜头详情。无副作用，便于预览与截图。 */
@Composable
fun ProductionPanelContent(
    snapshot: ProductionSnapshot,
    onAction: ProductionActionHandler,
    onResolve: ProductionResolveHandler,
    modifier: Modifier = Modifier,
    initialTab: ProductionTab = ProductionTab.OVERVIEW,
    initialShot: Pair<String, String>? = null,
    initialShotTab: ShotDetailTab? = null,
    initialAssetId: String? = null,
    initialPlanPreview: com.promenar.infinity.data.production.ShotPlanPreview? = null,
    sessionId: String = "",
    services: ProductionPanelServices = ProductionPanelServices(),
    tasks: List<MediaTask> = emptyList(),
    channels: List<MediaChannel> = emptyList(),
    channelOrder: List<String> = emptyList(),
    onBack: (() -> Unit)? = null,
    onUnbind: (suspend () -> String?)? = null,
) {
    val production = snapshot.production
    var tab by rememberSaveable(production.id) { mutableStateOf(initialTab) }
    var requestedAssetKind by rememberSaveable(production.id) { mutableStateOf<AssetKind?>(null) }
    var outputFilter by rememberSaveable(production.id) { mutableStateOf(OutputFilter.ALL) }
    var detailInitialTab by rememberSaveable(production.id) { mutableStateOf(initialShotTab) }
    var pageStack by rememberSaveable(production.id) { mutableStateOf(initialProductionPageStack(production, initialShot, initialAssetId)) }
    var openSheetAssetId by rememberSaveable(production.id) { mutableStateOf(initialAssetId?.takeIf { production.findAsset(it)?.kind == AssetKind.SOUND }) }
    val signals = production.tabSignals()
    var addedShotAtRevision by rememberSaveable(production.id) { mutableStateOf<Long?>(null) }
    var storyboardEpisodeId by rememberSaveable(production.id) { mutableStateOf(initialShot?.first) }
    // 总览点剪辑版本后，剪辑页打开时选中该版本；点 3D 分镜预演行时切到预演视图；用过即清空。
    var focusCutId by rememberSaveable(production.id) { mutableStateOf<String?>(null) }
    var focusPreview by rememberSaveable(production.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<ProductionNotice?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val stringResourceFallback = stringResource(R.string.production_panel_action_failed)

    /** 统一执行用户操作：执行期间禁用按钮，错误显示在顶部。 */
    val run: (ProductionUserAction) -> Unit = { action ->
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    error = onAction(snapshot.revision, action)
                } finally {
                    busy = false
                }
            }
        }
    }
    val submit: suspend (ProductionUserAction) -> String? = { action -> onAction(snapshot.revision, action) }
    val openShot: (String, String) -> Unit = { episodeId, code ->
        detailInitialTab = null
        addedShotAtRevision = null
        storyboardEpisodeId = episodeId
        pageStack = pushProductionPage(pageStack, encodeProductionPage(ProductionPage.Shot(episodeId, code)))
    }
    val openCharacter: (String) -> Unit = { assetId ->
        pageStack = pushProductionPage(pageStack, encodeProductionPage(ProductionPage.Character(assetId)))
    }

    val openStoryboardShot: (String, String) -> Unit = { episodeId, code ->
        openShot(episodeId, code)
        detailInitialTab = ShotDetailTab.STORYBOARD
    }
    val newShotTitle = stringResource(R.string.production_wire_new_shot)
    val connectedServices = services.copy(onAddShot = services.onAddShot ?: { episodeId, sceneCode ->
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    error = addShotAndOpen(snapshot, episodeId, sceneCode, newShotTitle, onAction) { id, code ->
                        openStoryboardShot(id, code)
                        addedShotAtRevision = snapshot.revision + 1
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = stringResourceFallback
                } finally { busy = false }
            }
        }
    })
    /** 取消进行中的任务：面板上唯一不经“发送”、立即生效的动作。 */
    val onCancelTask: (String) -> Unit = { taskId ->
        val queue = services.queue
        if (!busy && queue != null) {
            busy = true
            scope.launch {
                try {
                    error = userFacing(stringResourceFallback) { queue.cancel(taskId) }
                } finally { busy = false }
            }
        }
    }
    val costStats = remember(production, tasks) { productionCostStats(production, tasks) }
    val globalNotice = LocalInfinityNoticeController.current
    val sendState = rememberProductionSend(snapshot, services) { nextNotice ->
        if (globalNotice != null) globalNotice.showStatus(nextNotice.kind, nextNotice.message)
        else notice = nextNotice
    }
    LaunchedEffect(error, globalNotice) {
        val activeError = error
        if (activeError != null && globalNotice != null) {
            globalNotice.show(InfinityNoticeSeverity.ERROR, activeError)
            error = null
        }
    }
    // 打开面板时核对排队中的审核轮次并扫描未登记文件；失败不打断面板。
    LaunchedEffect(sessionId, services.reconcileReviews, services.auditFiles) {
        listOfNotNull(services.reconcileReviews, services.auditFiles).forEach { step ->
            try { step() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }
    val awaitingNewShot = addedShotAtRevision != null && snapshot.revision < requireNotNull(addedShotAtRevision)
    val reconciledStack = remember(pageStack, production, awaitingNewShot) {
        reconcileProductionPageStack(pageStack, production, awaitingNewShot)
    }
    if (reconciledStack != pageStack || (addedShotAtRevision != null && !awaitingNewShot)) {
        LaunchedEffect(reconciledStack, awaitingNewShot) {
            if (reconciledStack != pageStack) pageStack = reconciledStack
            if (!awaitingNewShot) addedShotAtRevision = null
        }
    }
    if (openSheetAssetId != null && production.findAsset(openSheetAssetId!!) == null) {
        LaunchedEffect(openSheetAssetId) { openSheetAssetId = null }
    }
    val allTabs = ProductionTab.entries
    val pagerState = rememberPanelTabPagerState(tabs = allTabs, selected = tab, onSelect = { tab = it }, key = production.id)
    CompositionLocalProvider(LocalProductionSend provides sendState) {
        Box(modifier.fillMaxSize()) {
            androidx.compose.runtime.key(production.id) {
                InfinityPageStack(
                    pages = pageStack,
                    popped = pageStack.takeIf { it.isNotEmpty() }?.dropLast(1),
                    onPop = { pageStack = pageStack.dropLast(1) },
                    modifier = Modifier.fillMaxSize().testTag(ProductionUiTags.PANEL),
                ) { stack ->
                    val currentPage = stack.lastOrNull()?.let(::decodeProductionPage)
                    when (currentPage) {
                        is ProductionPage.Character -> {
                            val asset = production.findAsset(currentPage.assetId)
                            Column(Modifier.fillMaxSize()) {
                                val charErrVisible = globalNotice == null && error != null
                                val heldCharErr = rememberLastNonNull(error, key = "char-err")
                                AnimatedVisibility(
                                    visible = charErrVisible && heldCharErr != null,
                                    enter = InfinityMotion.expandIn,
                                    exit = InfinityMotion.shrinkOut,
                                ) {
                                    heldCharErr?.let { InfinityStatusStrip(InfinityStatusKind.Error, it, onDismiss = { error = null }, modifier = Modifier.padding(16.dp)) }
                                }
                                if (asset != null && asset.kind != AssetKind.SOUND) {
                                    ProductionCharacterPage(
                                        asset = asset,
                                        production = production,
                                        busy = busy,
                                        onBack = { pageStack = pageStack.dropLast(1) },
                                        onShot = openShot,
                                        onRun = run,
                                        onSubmit = submit,
                                        modifier = Modifier.weight(1f),
                                        channels = channels,
                                        channelOrder = channelOrder,
                                    )
                                }
                            }
                        }
                        is ProductionPage.Shot -> {
                            val location = production.findShot(currentPage.episodeId, currentPage.shotCode)
                            if (location != null) {
                                ProductionShotDetail(
                                    location = location,
                                    busy = busy,
                                    error = error,
                                    onDismissError = { error = null },
                                    onBack = { pageStack = pageStack.dropLast(1) },
                                    onRun = run,
                                    onSubmit = submit,
                                    modifier = Modifier.fillMaxSize(),
                                    production = production, revision = snapshot.revision, sessionId = sessionId, services = services,
                                    channels = channels, channelOrder = channelOrder, initialTab = detailInitialTab,
                                    onOpenCharacter = openCharacter, initialPreview = initialPlanPreview,
                                    generations = costStats.shot(location.episode.id, location.shot.code),
                                )
                            } else {
                                Box(Modifier.fillMaxSize())
                            }
                        }
                        null -> Column(Modifier.fillMaxSize()) {
                            ProductionHeader(
                                snapshot = snapshot,
                                onBack = onBack,
                                busy = busy,
                                services = services,
                                onRun = run,
                                onResolve = { resolution -> onResolve(resolution).also { error = it } },
                                onUnbind = onUnbind?.let { handler -> { handler().also { error = it } } },
                            )
                            ManifestBanner(snapshot.manifest, onResolve = { resolution ->
                                onResolve(resolution).also { error = it }
                            })
                            val rootErrVisible = globalNotice == null && error != null
                            val heldRootErr = rememberLastNonNull(error, key = "root-err")
                            AnimatedVisibility(
                                visible = rootErrVisible && heldRootErr != null,
                                enter = InfinityMotion.expandIn,
                                exit = InfinityMotion.shrinkOut,
                            ) {
                                heldRootErr?.let {
                                    InfinityStatusStrip(
                                        kind = InfinityStatusKind.Error,
                                        message = it,
                                        onDismiss = { error = null },
                                        modifier = Modifier.padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.XSmall).testTag(ProductionUiTags.ERROR),
                                    )
                                }
                            }
                            InfinityPanelTabs(
                                tabs = listOf(
                                    InfinityPanelTab(ProductionTab.OVERVIEW, stringResource(R.string.production_panel_tab_overview), testTag = ProductionUiTags.TAB_OVERVIEW),
                                    InfinityPanelTab(ProductionTab.SCRIPT, stringResource(R.string.production_panel_tab_script), testTag = ProductionUiTags.TAB_SCRIPT),
                                    InfinityPanelTab(
                                        ProductionTab.ASSETS,
                                        stringResource(R.string.production_panel_tab_assets),
                                        count = production.assets.count { it.status == AssetStatus.REVIEW }.takeIf { it > 0 }?.toString(),
                                        testTag = ProductionUiTags.TAB_ASSETS,
                                    ),
                                    InfinityPanelTab(ProductionTab.STORYBOARD, stringResource(R.string.p2_tab_storyboard), dot = MaterialTheme.colorScheme.primary.takeIf { signals.storyboardDot }, testTag = ProductionUiTags.TAB_STORYBOARD),
                                    InfinityPanelTab(ProductionTab.OUTPUT, stringResource(R.string.p2_tab_output), count = signals.reviews.takeIf { it > 0 }?.toString()),
                                    InfinityPanelTab(ProductionTab.CUT, stringResource(R.string.p2_tab_cut)),
                                ),
                                selected = tab,
                                onSelect = { tab = it },
                                contentInset = InfinitySpacing.ScreenHorizontal,
                                pagerState = pagerState,
                            )
                            HorizontalPager(
                                state = pagerState,
                                key = { allTabs[it] },
                                verticalAlignment = Alignment.Top,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            ) { pageIndex ->
                                when (allTabs[pageIndex]) {
                                    ProductionTab.OVERVIEW -> ProductionManagementTab(
                                        production, tasks, busy, services,
                                        onScene = { storyboardEpisodeId = it; tab = ProductionTab.STORYBOARD },
                                        onAsset = { assetId ->
                                            if (production.findAsset(assetId)?.kind != AssetKind.SOUND) openCharacter(assetId)
                                            else openSheetAssetId = assetId
                                        },
                                        onRun = run, onSubmit = submit, onCancelTask = onCancelTask, channels = channels,
                                        costStats = costStats, onShot = openShot,
                                        onOpenTab = { tab = it },
                                        onOpenAssets = { kind -> requestedAssetKind = kind; tab = ProductionTab.ASSETS },
                                        onOpenPrevis = { focusPreview = true; tab = ProductionTab.CUT },
                                    )
                                    ProductionTab.SCRIPT -> ProductionScriptTab(production = production, busy = busy, onRun = run, onSubmit = submit, onOpenEpisode = { storyboardEpisodeId = it; tab = ProductionTab.STORYBOARD })
                                    ProductionTab.ASSETS -> ProductionAssetsTab(
                                        production = production, busy = busy, onRun = run, onSubmit = submit,
                                        onOpenCharacter = openCharacter,
                                        requestedKind = requestedAssetKind,
                                        onRequestedKindHandled = { requestedAssetKind = null },
                                    )
                                    ProductionTab.STORYBOARD -> ProductionStoryboardTab(
                                        production = production,
                                        episodeId = storyboardEpisodeId ?: production.defaultEpisodeId(),
                                        onSelectEpisode = { storyboardEpisodeId = it },
                                        busy = busy,
                                        onOpenShot = openStoryboardShot,
                                        onRun = run, services = connectedServices, channels = channels, channelOrder = channelOrder,
                                        onSubmit = submit,
                                    )
                                    ProductionTab.OUTPUT -> ProductionOutputTab(
                                        production, outputFilter, { outputFilter = it }, services, tasks, channels, channelOrder, openShot,
                                        onScene = { storyboardEpisodeId = it; tab = ProductionTab.STORYBOARD },
                                        busy = busy, onRun = run, onSubmit = submit,
                                    )
                                    ProductionTab.CUT -> ProductionCutTab(
                                        production = production,
                                        services = services,
                                        sessionId = sessionId,
                                        onShot = openShot,
                                        onError = { error = it },
                                        busy = busy,
                                        onRun = run,
                                        onSubmit = submit,
                                        focusCutId = focusCutId,
                                        onFocusHandled = { focusCutId = null },
                                        focusPreview = focusPreview,
                                        onFocusPreviewHandled = { focusPreview = false },
                                        snapshotRevision = snapshot.revision,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            openSheetAssetId?.let { id ->
                production.findAsset(id)?.let { asset ->
                    ProductionAssetSheet(asset, production, busy, { openSheetAssetId = null }, run, submit)
                }
            }
            if (globalNotice == null) {
                notice?.let { ProductionNoticeStrip(it, onDismiss = { notice = null }, modifier = Modifier.align(Alignment.BottomCenter)) }
            }
        }
    }
}

/** 页头：返回、项目名与一行信息（类型 · 集数 · 镜头数 · 画幅），右侧精简轻量下拉菜单（试验模式、文件、媒体目录与解绑）。 */
@Composable
private fun ProductionHeader(
    snapshot: ProductionSnapshot,
    onBack: (() -> Unit)?,
    busy: Boolean,
    services: ProductionPanelServices,
    onRun: (ProductionUserAction) -> Unit,
    onResolve: suspend (ManifestResolution) -> String?,
    onUnbind: (suspend () -> String?)? = null,
) {
    val production = snapshot.production
    val episodes = production.episodes()
    val stats = production.shotStats()
    var menu by remember { mutableStateOf(false) }
    var rebuilding by remember { mutableStateOf(false) }
    var confirmingUnbind by remember { mutableStateOf(false) }
    var unbinding by remember { mutableStateOf(false) }
    val locate = LocalWorkspaceMedia.current?.onLocate
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    ProductionTopBar(
        title = production.title,
        meta = listOfNotNull(
            stringResource(if (production.kind == ProductionKind.SERIES) R.string.production_panel_kind_series else R.string.production_panel_kind_film),
            episodes.takeIf { production.kind == ProductionKind.SERIES }?.let {
                pluralStringResource(R.plurals.production_panel_episode_count, it.size, it.size)
            },
            pluralStringResource(R.plurals.production_panel_shot_count, stats.total, stats.total),
            production.aspectRatio,
        ),
        onBack = onBack,
        metaAccent = if (production.experimental) stringResource(R.string.p2_trial_badge) else null,
        modifier = Modifier.testTag(ProductionUiTags.HEADER),
    ) {
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag(ProductionUiTags.MENU)) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.p2_more))
            }
            InfinityDropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                alignToAnchorEnd = true,
                minWidth = 236.dp,
            ) {
                InfinityMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.p2_experimental),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(R.string.p2_experimental_menu_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Science,
                            contentDescription = null,
                            tint = if (production.experimental) scheme.primary else scheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    selected = production.experimental,
                    enabled = !busy && !unbinding,
                    onClick = {
                        menu = false
                        onRun(ProductionUserAction.SetExperimental(!production.experimental))
                    },
                )
                if (locate != null) {
                    InfinityMenuItem(
                        text = { Text(stringResource(R.string.p2_open_in_files)) },
                        leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            menu = false
                            locate(production.rootPath)
                        },
                    )
                }
                if (services.onOpenMediaModels != null) {
                    InfinityMenuItem(
                        text = { Text(stringResource(R.string.p2_manage_media_models)) },
                        leadingIcon = { Icon(Icons.Rounded.Hub, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            menu = false
                            services.onOpenMediaModels.invoke()
                        },
                    )
                }
                if (snapshot.manifest is ManifestSync.ExternalChange) {
                    InfinityMenuItem(
                        text = { Text(stringResource(R.string.p2_rebuild_from_manifest)) },
                        leadingIcon = { Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        enabled = !busy && !unbinding,
                        onClick = {
                            menu = false
                            rebuilding = true
                        },
                    )
                }
                if (onUnbind != null) {
                    InfinityMenuDivider()
                    InfinityMenuItem(
                        text = { Text(stringResource(R.string.p2_unbind_project)) },
                        leadingIcon = { Icon(Icons.Rounded.LinkOff, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        destructive = true,
                        enabled = !busy && !unbinding,
                        onClick = {
                            menu = false
                            confirmingUnbind = true
                        },
                    )
                }
            }
        }
    }
    if (rebuilding) {
        InfinityConfirmDialog(
            title = stringResource(R.string.production_panel_manifest_adopt_title),
            message = stringResource(R.string.production_panel_manifest_adopt_body),
            confirmText = stringResource(R.string.production_panel_manifest_adopt),
            onConfirm = { rebuilding = false; scope.launch { onResolve(ManifestResolution.ADOPT_FILE) } },
            onCancel = { rebuilding = false },
            isDestructive = true,
        )
    }
    if (confirmingUnbind && onUnbind != null) {
        InfinityConfirmDialog(
            title = stringResource(R.string.p2_unbind_project_title),
            message = stringResource(R.string.p2_unbind_project_message),
            confirmText = stringResource(R.string.p2_unbind_project_confirm),
            onConfirm = {
                confirmingUnbind = false
                unbinding = true
                scope.launch {
                    try {
                        onUnbind()
                    } finally {
                        unbinding = false
                    }
                }
            },
            onCancel = { confirmingUnbind = false },
            isDestructive = true,
        )
    }
}

private data class ManifestBannerData(val kind: InfinityStatusKind, val message: String, val canAdopt: Boolean)

/** 清单与数据库不一致时的提示与处理入口；同步中与已同步不显示。 */
@Composable
private fun ManifestBanner(manifest: ManifestSync, onResolve: suspend (ManifestResolution) -> String?) {
    val active = when (manifest) {
        ManifestSync.InSync, ManifestSync.Pending -> null
        is ManifestSync.WriteFailed -> ManifestBannerData(InfinityStatusKind.Error, stringResource(R.string.production_panel_manifest_write_failed, manifest.reason), false)
        is ManifestSync.ExternalChange -> ManifestBannerData(InfinityStatusKind.Warning, stringResource(R.string.production_panel_manifest_external), true)
        is ManifestSync.Invalid -> ManifestBannerData(InfinityStatusKind.Error, stringResource(R.string.production_panel_manifest_invalid, manifest.reason), false)
    }
    val held = rememberLastNonNull(active, key = "manifest-banner")
    var confirming by remember { mutableStateOf<ManifestResolution?>(null) }
    var resolving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = active != null,
        enter = InfinityMotion.expandIn,
        exit = InfinityMotion.shrinkOut,
    ) {
        val data = held ?: return@AnimatedVisibility
        Column(
            Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.XSmall).testTag(ProductionUiTags.MANIFEST_BANNER),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            InfinityStatusStrip(kind = data.kind, message = data.message, busy = resolving)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                if (data.canAdopt) {
                    InfinityPillButton(
                        text = stringResource(R.string.production_panel_manifest_adopt),
                        onClick = { confirming = ManifestResolution.ADOPT_FILE },
                        enabled = !resolving,
                        compact = true,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(ProductionUiTags.MANIFEST_ADOPT),
                    )
                }
                InfinityPillButton(
                    text = stringResource(R.string.production_panel_manifest_overwrite),
                    onClick = { confirming = ManifestResolution.OVERWRITE_FILE },
                    enabled = !resolving,
                    compact = true,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(ProductionUiTags.MANIFEST_OVERWRITE),
                )
            }
        }
    }
    confirming?.let { resolution ->
        val adopt = resolution == ManifestResolution.ADOPT_FILE
        InfinityConfirmDialog(
            title = stringResource(if (adopt) R.string.production_panel_manifest_adopt_title else R.string.production_panel_manifest_overwrite_title),
            message = stringResource(if (adopt) R.string.production_panel_manifest_adopt_body else R.string.production_panel_manifest_overwrite_body),
            confirmText = stringResource(if (adopt) R.string.production_panel_manifest_adopt else R.string.production_panel_manifest_overwrite),
            onConfirm = {
                confirming = null
                resolving = true
                scope.launch {
                    try { onResolve(resolution) } finally { resolving = false }
                }
            },
            onCancel = { confirming = null },
            isDestructive = true,
            confirmButtonModifier = Modifier.heightIn(min = 48.dp).testTag(ProductionUiTags.DIALOG_CONFIRM),
            cancelButtonModifier = Modifier.heightIn(min = 48.dp),
        )
    }
}

/** 底部操作按钮组：单按钮占满整行；多按钮一行放不下时整组竖排，按钮文字始终单行。 */
@Composable
internal fun ProductionActionRow(
    actions: List<ProductionButton>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    if (actions.size == 1) {
        ProductionPill(actions.single(), modifier.fillMaxWidth())
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    // 每个按钮按最宽的文字估算；带图标的按钮再加图标与间距。
    val labelWidth = actions.maxOf { button ->
        measurer.measure(AnnotatedString(button.text), style = style, softWrap = false, maxLines = 1).size.width +
            if (button.icon != null) with(density) { 26.dp.roundToPx() } else 0
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val estimated = actions.size * (labelWidth + with(density) { 32.dp.toPx() }) + (actions.size - 1) * with(density) { InfinitySpacing.Small.toPx() }
        if (estimated <= with(density) { maxWidth.toPx() }) {
            // 窄屏平分整行；内屏等宽屏每个按钮不超过上限，整组靠右。
            val gap = InfinitySpacing.Small
            val share = (maxWidth - gap * (actions.size - 1)) / actions.size
            val cap = maxOf(ProductionActionMaxWidth, with(density) { labelWidth.toDp() } + 32.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.End)) {
                actions.forEach { button -> ProductionPill(button, Modifier.width(minOf(share, cap))) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                actions.forEach { button -> ProductionPill(button, Modifier.fillMaxWidth()) }
            }
        }
    }
}

/** 横排操作按钮的单个宽度上限。 */
private val ProductionActionMaxWidth = 168.dp

internal data class ProductionButton(
    val text: String,
    val tone: InfinityPillTone,
    val enabled: Boolean,
    val tag: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val onClick: () -> Unit,
)

@Composable
private fun ProductionPill(button: ProductionButton, modifier: Modifier) {
    InfinityPillButton(
        text = button.text,
        onClick = button.onClick,
        tone = button.tone,
        enabled = button.enabled,
        icon = button.icon,
        modifier = modifier.testTag(button.tag),
    )
}
