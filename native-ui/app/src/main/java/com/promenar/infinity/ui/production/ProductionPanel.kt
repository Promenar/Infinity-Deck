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
) {
    val load by produceState<ProductionLoad>(ProductionLoad.Loading, sessionId, source) {
        source.observe(sessionId).collect { snapshot ->
            value = if (snapshot == null) ProductionLoad.Empty else ProductionLoad.Ready(snapshot)
        }
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
) {
    val production = snapshot.production
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var openEpisodeId by rememberSaveable { mutableStateOf(initialShot?.first) }
    var openShotCode by rememberSaveable { mutableStateOf(initialShot?.second) }
    var storyboardEpisodeId by rememberSaveable { mutableStateOf(initialShot?.first) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
        openEpisodeId = episodeId
        openShotCode = code
        storyboardEpisodeId = episodeId
    }

    val location = openEpisodeId?.let { episodeId -> openShotCode?.let { production.findShot(episodeId, it) } }
    if (openShotCode != null && location == null) {
        // 镜头被智能体移除时回到页签。
        LaunchedEffect(openEpisodeId, openShotCode) { openShotCode = null }
    }
    Column(modifier.fillMaxSize().testTag(ProductionUiTags.PANEL)) {
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
                InfinityPanelTab(ProductionTab.OVERVIEW, stringResource(R.string.production_panel_tab_overview), testTag = ProductionUiTags.TAB_OVERVIEW),
                InfinityPanelTab(ProductionTab.SCRIPT, stringResource(R.string.production_panel_tab_script), testTag = ProductionUiTags.TAB_SCRIPT),
                InfinityPanelTab(ProductionTab.ASSETS, stringResource(R.string.production_panel_tab_assets), testTag = ProductionUiTags.TAB_ASSETS),
                InfinityPanelTab(ProductionTab.STORYBOARD, stringResource(R.string.production_panel_tab_storyboard), testTag = ProductionUiTags.TAB_STORYBOARD),
            ),
            selected = tab,
            onSelect = { tab = it },
            contentInset = InfinitySpacing.Large,
            modifier = Modifier.padding(bottom = InfinitySpacing.XSmall),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                ProductionTab.OVERVIEW -> ProductionOverviewTab(
                    production = production,
                    busy = busy,
                    onOpenShot = openShot,
                    onOpenEpisode = { episodeId ->
                        storyboardEpisodeId = episodeId
                        tab = ProductionTab.STORYBOARD
                    },
                    onOpenAssets = { tab = ProductionTab.ASSETS },
                    onRun = run,
                )
                ProductionTab.SCRIPT -> ProductionScriptTab(production = production, busy = busy, onRun = run)
                ProductionTab.ASSETS -> ProductionAssetsTab(production = production, busy = busy, onRun = run, onSubmit = submit)
                ProductionTab.STORYBOARD -> ProductionStoryboardTab(
                    production = production,
                    episodeId = storyboardEpisodeId ?: production.defaultEpisodeId(),
                    onSelectEpisode = { storyboardEpisodeId = it },
                    busy = busy,
                    onOpenShot = openShot,
                    onRun = run,
                )
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
                    modifier = Modifier.testTag(ProductionUiTags.MANIFEST_ADOPT),
                )
            }
            InfinityPillButton(
                text = stringResource(R.string.production_panel_manifest_overwrite),
                onClick = { confirming = ManifestResolution.OVERWRITE_FILE },
                enabled = !resolving,
                compact = true,
                modifier = Modifier.testTag(ProductionUiTags.MANIFEST_OVERWRITE),
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
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val longest = actions.maxOf { it.text.length }
        // 每个字符约 14dp 加左右内边距与图标；估算宽度超过可用宽度时整组竖排。
        val estimated = actions.size * (longest * 14 + 56) + (actions.size - 1) * 8
        if (estimated <= maxWidth.value) {
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
