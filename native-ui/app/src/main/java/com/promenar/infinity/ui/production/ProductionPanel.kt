package com.promenar.infinity.ui.production

import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.common.InfinityPanelTab
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.promenar.infinity.data.production.ProductionPanelSource
import com.promenar.infinity.data.production.ProductionSnapshot
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityLoadingIndicator
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityStatusKind
import com.promenar.infinity.ui.common.InfinityStatusStrip
import com.promenar.infinity.ui.media.LocalWorkspaceMedia
import com.promenar.infinity.ui.media.rememberSessionWorkspaceMedia
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.promenar.infinity.domain.mediagen.MediaTask
import com.promenar.infinity.domain.mediagen.MediaChannel
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.data.production.AssetKind

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
    CompositionLocalProvider(LocalWorkspaceMedia provides (media ?: LocalWorkspaceMedia.current)) {
        when (val current = load) {
            ProductionLoad.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { InfinityLoadingIndicator() }
            ProductionLoad.Empty -> ProductionPanelEmpty(modifier)
            is ProductionLoad.Ready -> ProductionPanelContent(
                snapshot = current.snapshot,
                onAction = { revision, action ->
                    userFacing(failed) { source.apply(sessionId, revision, action) }
                },
                onResolve = { resolution -> userFacing(failed) { source.resolveManifest(sessionId, resolution) } },
                modifier = modifier,
                sessionId = sessionId,
                services = services,
                tasks = projectTasks(tasks, services.projectSourcePrefix?.invoke(current.snapshot.production)),
                channels = channels,
                channelOrder = channelOrder,
            )
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
fun ProductionPanelEmpty(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag(ProductionUiTags.EMPTY), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.production_panel_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
) {
    val production = snapshot.production
    var tab by rememberSaveable(production.id) { mutableStateOf(initialTab) }
    var outputFilter by rememberSaveable(production.id) { mutableStateOf(OutputFilter.ALL) }
    var detailInitialTab by rememberSaveable(production.id) { mutableStateOf(initialShotTab) }
    var openAssetId by rememberSaveable(production.id) { mutableStateOf(initialAssetId) }
    val signals = production.tabSignals(tasks)
    var openEpisodeId by rememberSaveable(production.id) { mutableStateOf(initialShot?.first) }
    var openShotCode by rememberSaveable(production.id) { mutableStateOf(initialShot?.second) }
    var storyboardEpisodeId by rememberSaveable(production.id) { mutableStateOf(initialShot?.first) }
    var error by remember { mutableStateOf<String?>(null) }
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
        openAssetId = null
        openEpisodeId = episodeId
        openShotCode = code
        storyboardEpisodeId = episodeId
    }

    val openStoryboardShot: (String, String) -> Unit = { episodeId, code ->
        openShot(episodeId, code)
        detailInitialTab = ShotDetailTab.STORYBOARD
    }
    val onQueue: (String, QueueAction) -> Unit = { taskId, action ->
        val queue = services.queue
        if (!busy && queue != null) {
            busy = true
            scope.launch {
                try {
                    error = userFacing(stringResourceFallback) {
                        when (action) {
                            QueueAction.APPROVE -> queue.approve(taskId)
                            QueueAction.REJECT -> queue.reject(taskId)
                            QueueAction.RETRY -> queue.retry(taskId)
                        }
                    }
                } finally { busy = false }
            }
        }
    }
    val location = openEpisodeId?.let { episodeId -> openShotCode?.let { production.findShot(episodeId, it) } }
    if (openShotCode != null && location == null) {
        // 镜头被智能体移除时回到页签。
        LaunchedEffect(openEpisodeId, openShotCode) { openShotCode = null }
    }
    Column(modifier.fillMaxSize().testTag(ProductionUiTags.PANEL)) {
        val asset = openAssetId?.let(production::findAsset)
        if (openAssetId != null && asset == null) LaunchedEffect(openAssetId) { openAssetId = null }
        if (asset?.kind == AssetKind.CHARACTER) {
            BackHandler { openAssetId = null }
            error?.let { InfinityStatusStrip(InfinityStatusKind.Error, it, onDismiss = { error = null }, modifier = Modifier.padding(16.dp)) }
            ProductionCharacterPage(asset, production, busy, { openAssetId = null }, openShot, run, submit, Modifier.weight(1f))
            return@Column
        }
        if (asset != null) ProductionAssetSheet(asset, production.shotsUsingAsset(asset.id), busy, { openAssetId = null }, run, submit)
        if (location != null) {
            BackHandler { openShotCode = null }
            ProductionShotDetail(
                location = location,
                busy = busy,
                error = error,
                onDismissError = { error = null },
                onBack = { openShotCode = null },
                onRun = run,
                onSubmit = submit,
                modifier = Modifier.weight(1f),
                production = production, revision = snapshot.revision, sessionId = sessionId, services = services,
                channels = channels, channelOrder = channelOrder, initialTab = detailInitialTab,
                onOpenCharacter = { openAssetId = it }, initialPreview = initialPlanPreview,
            )
            return@Column
        }
        ProductionHeader(snapshot)
        ManifestBanner(snapshot.manifest, onResolve = { resolution ->
            onResolve(resolution).also { error = it }
        })
        error?.let {
            InfinityStatusStrip(
                kind = InfinityStatusKind.Error,
                message = it,
                onDismiss = { error = null },
                modifier = Modifier.padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.XSmall).testTag(ProductionUiTags.ERROR),
            )
        }
        InfinityPanelTabs(
            tabs = listOf(
                InfinityPanelTab(ProductionTab.OVERVIEW, stringResource(R.string.production_panel_tab_overview), count = signals.approvals.takeIf { it > 0 }?.toString(), testTag = ProductionUiTags.TAB_OVERVIEW),
                InfinityPanelTab(ProductionTab.SCRIPT, stringResource(R.string.production_panel_tab_script), testTag = ProductionUiTags.TAB_SCRIPT),
                InfinityPanelTab(ProductionTab.ASSETS, stringResource(R.string.production_panel_tab_assets), testTag = ProductionUiTags.TAB_ASSETS),
                InfinityPanelTab(ProductionTab.STORYBOARD, stringResource(R.string.p2_tab_storyboard), dot = MaterialTheme.colorScheme.primary.takeIf { signals.storyboardDot }, testTag = ProductionUiTags.TAB_STORYBOARD),
                InfinityPanelTab(ProductionTab.OUTPUT, stringResource(R.string.p2_tab_output), count = signals.reviews.takeIf { it > 0 }?.toString()),
                InfinityPanelTab(ProductionTab.CUT, stringResource(R.string.p2_tab_cut)),
            ),
            selected = tab,
            onSelect = { tab = it },
            contentInset = InfinitySpacing.Large,
            modifier = Modifier.padding(bottom = InfinitySpacing.XSmall),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                ProductionTab.OVERVIEW -> ProductionManagementTab(
                    production, tasks, busy, services,
                    onOutput = { outputFilter = it; tab = ProductionTab.OUTPUT },
                    onScene = { storyboardEpisodeId = it; tab = ProductionTab.STORYBOARD },
                    onAsset = { openAssetId = it }, onRun = run, onQueue = onQueue,
                )
                ProductionTab.SCRIPT -> ProductionScriptTab(production = production, busy = busy, onRun = run, onOpenEpisode = { storyboardEpisodeId = it; tab = ProductionTab.STORYBOARD })
                ProductionTab.ASSETS -> ProductionAssetsTab(production = production, busy = busy, onRun = run, onSubmit = submit, onOpenCharacter = { openAssetId = it })
                ProductionTab.STORYBOARD -> ProductionStoryboardTab(
                    production = production,
                    episodeId = storyboardEpisodeId ?: production.defaultEpisodeId(),
                    onSelectEpisode = { storyboardEpisodeId = it },
                    busy = busy,
                    onOpenShot = openStoryboardShot,
                    onRun = run, services = services, channels = channels, channelOrder = channelOrder,
                )
                ProductionTab.OUTPUT -> ProductionOutputTab(production, outputFilter, { outputFilter = it }, services, tasks, channels, channelOrder, openShot, { storyboardEpisodeId = it; tab = ProductionTab.STORYBOARD })
                ProductionTab.CUT -> ProductionCutTab(production, services, openShot)
            }
        }
    }
}

@Composable
private fun ProductionHeader(snapshot: ProductionSnapshot) {
    val production = snapshot.production
    val episodes = production.episodes()
    val stats = production.shotStats()
    Column(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small)) {
        Text(
            production.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
        InfinityMetaLine(
            segments = listOfNotNull(
                stringResource(if (production.kind == ProductionKind.SERIES) R.string.production_panel_kind_series else R.string.production_panel_kind_film),
                episodes.takeIf { production.kind == ProductionKind.SERIES }?.let {
                    pluralStringResource(R.plurals.production_panel_episode_count, it.size, it.size)
                },
                pluralStringResource(R.plurals.production_panel_shot_count, stats.total, stats.total),
                production.aspectRatio,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        production.logline?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                modifier = Modifier.padding(top = InfinitySpacing.XSmall),
            )
        }
    }
}

/** 清单与数据库不一致时的提示与处理入口；同步中与已同步不显示。 */
@Composable
private fun ManifestBanner(manifest: ManifestSync, onResolve: suspend (ManifestResolution) -> String?) {
    val (kind, message, canAdopt) = when (manifest) {
        ManifestSync.InSync, ManifestSync.Pending -> return
        is ManifestSync.WriteFailed -> Triple(InfinityStatusKind.Error, stringResource(R.string.production_panel_manifest_write_failed, manifest.reason), false)
        is ManifestSync.ExternalChange -> Triple(InfinityStatusKind.Warning, stringResource(R.string.production_panel_manifest_external), true)
        is ManifestSync.Invalid -> Triple(InfinityStatusKind.Error, stringResource(R.string.production_panel_manifest_invalid, manifest.reason), false)
    }
    var confirming by remember { mutableStateOf<ManifestResolution?>(null) }
    var resolving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.XSmall).testTag(ProductionUiTags.MANIFEST_BANNER),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        InfinityStatusStrip(kind = kind, message = message, busy = resolving)
        Row(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
            if (canAdopt) {
                InfinityPillButton(
                    text = stringResource(R.string.production_panel_manifest_adopt),
                    onClick = { confirming = ManifestResolution.ADOPT_FILE },
                    enabled = !resolving,
                    compact = true,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(ProductionUiTags.MANIFEST_ADOPT),
                )
            }
            InfinityPillButton(
                text = stringResource(R.string.production_panel_manifest_overwrite),
                onClick = { confirming = ManifestResolution.OVERWRITE_FILE },
                enabled = !resolving,
                compact = true,
                modifier = Modifier.heightIn(min = 48.dp).testTag(ProductionUiTags.MANIFEST_OVERWRITE),
            )
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

/** 底部操作按钮组：一行放不下时整组竖排，按钮文字始终单行。 */
@Composable
internal fun ProductionActionRow(
    actions: List<ProductionButton>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val labelWidth = actions.maxOf { measurer.measure(AnnotatedString(it.text), style = style, softWrap = false, maxLines = 1).size.width }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val estimated = actions.size * (labelWidth + with(density) { 32.dp.toPx() }) + (actions.size - 1) * with(density) { InfinitySpacing.Small.toPx() }
        if (estimated <= with(density) { maxWidth.toPx() }) {
            Row(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                actions.forEach { button -> ProductionPill(button, Modifier.weight(1f)) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                actions.forEach { button -> ProductionPill(button, Modifier.fillMaxWidth()) }
            }
        }
    }
}

internal data class ProductionButton(
    val text: String,
    val tone: InfinityPillTone,
    val enabled: Boolean,
    val tag: String,
    val onClick: () -> Unit,
)

@Composable
private fun ProductionPill(button: ProductionButton, modifier: Modifier) {
    InfinityPillButton(
        text = button.text,
        onClick = button.onClick,
        tone = button.tone,
        enabled = button.enabled,
        modifier = modifier.testTag(button.tag),
    )
}
