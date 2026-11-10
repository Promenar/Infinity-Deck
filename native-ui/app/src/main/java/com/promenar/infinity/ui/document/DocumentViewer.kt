package com.promenar.infinity.ui.document

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 宽度达到此值时改为正文加侧栏；折叠屏内屏与平板都在此之上。 */
val DocumentViewerWideWidth: Dp = 600.dp
/** 宽版侧栏宽度。 */
val DocumentViewerSidePaneWidth: Dp = 296.dp

/** 查看器的一个操作。窄屏主操作在底栏，宽屏在侧栏竖排；按钮文字始终单行。 */
data class DocumentViewerAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val tone: InfinityPillTone = InfinityPillTone.NEUTRAL,
    val enabled: Boolean = true,
    val testTag: String? = null,
)

/**
 * 查看器里展开的选择面板（例如选文件夹、选会话）：窄屏为底部面板，宽屏在侧栏内原地切换。
 * [content] 由外壳提供纵向滚动与水平内边距，内容本身不再嵌套滚动容器；[footer] 固定在内容下方。
 * [onBack] 用于宽屏面板标题左侧的返回；[onDismiss] 关闭面板。
 */
class DocumentViewerPanel(
    val title: String,
    val onBack: () -> Unit,
    val onDismiss: () -> Unit,
    val testTag: String? = null,
    val footer: (@Composable () -> Unit)? = null,
    val content: @Composable ColumnScope.() -> Unit,
)

/**
 * 文档查看器外壳：顶栏（关闭、标题与副标题、图标操作）、正文、操作区与选择面板。
 *
 * - 窄屏：[actions] 放在底栏的胶囊行里，主操作放最后；[secondaryActions] 作为顶栏图标按钮。
 * - 宽屏（≥ [DocumentViewerWideWidth]）且有操作、详情或面板时：右侧 [DocumentViewerSidePaneWidth] 侧栏，
 *   上方 [details]，下方主操作在前、次要操作在后竖排；面板打开时侧栏换成面板。
 * - [topActions] 两种宽度都在顶栏。
 */
@Composable
fun DocumentViewerScaffold(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    topActions: List<DocumentViewerAction> = emptyList(),
    actions: List<DocumentViewerAction> = emptyList(),
    secondaryActions: List<DocumentViewerAction> = emptyList(),
    details: (@Composable ColumnScope.() -> Unit)? = null,
    panel: DocumentViewerPanel? = null,
    body: @Composable (Modifier) -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= DocumentViewerWideWidth
            val sidePane = wide && (actions.isNotEmpty() || secondaryActions.isNotEmpty() || details != null || panel != null)
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
                DocumentViewerTopBar(
                    title = title,
                    subtitle = subtitle,
                    onClose = onClose,
                    iconActions = if (sidePane) topActions else topActions + secondaryActions,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (sidePane) {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        body(Modifier.weight(1f).fillMaxHeight())
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DocumentViewerSidePane(
                            details = details,
                            actions = actions,
                            secondaryActions = secondaryActions,
                            panel = panel,
                            modifier = Modifier.width(DocumentViewerSidePaneWidth).fillMaxHeight(),
                        )
                    }
                } else {
                    body(Modifier.fillMaxWidth().weight(1f))
                    if (actions.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        InfinityPillRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Small),
                        ) {
                            actions.forEach { action -> DocumentViewerPill(action) }
                        }
                    }
                }
            }
            if (!sidePane && panel != null) {
                InfinityBottomSheet(
                    show = true,
                    onDismiss = panel.onDismiss,
                    title = panel.title,
                    dragGesturesEnabled = false,
                    modifier = panel.testTag?.let { Modifier.testTag(it) } ?: Modifier,
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = panel.content)
                        panel.footer?.let { footer -> Column(Modifier.padding(top = InfinitySpacing.Medium)) { footer() } }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentViewerTopBar(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    iconActions: List<DocumentViewerAction>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            // 顶栏底色延伸到状态栏下方，和系统栏连成一体。
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = InfinitySpacing.XSmall, vertical = InfinitySpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget).testTag(DocumentViewerTags.CLOSE)) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.doc_viewer_close))
        }
        Column(Modifier.weight(1f).padding(horizontal = InfinitySpacing.Small)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        iconActions.forEach { action ->
            IconButton(
                onClick = action.onClick,
                enabled = action.enabled,
                modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget).then(action.testTag?.let { Modifier.testTag(it) } ?: Modifier),
            ) {
                Icon(action.icon, contentDescription = action.label)
            }
        }
    }
}

@Composable
private fun DocumentViewerSidePane(
    details: (@Composable ColumnScope.() -> Unit)?,
    actions: List<DocumentViewerAction>,
    secondaryActions: List<DocumentViewerAction>,
    panel: DocumentViewerPanel?,
    modifier: Modifier,
) {
    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        if (panel != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = InfinitySpacing.XSmall, end = InfinitySpacing.Medium, top = InfinitySpacing.Small),
            ) {
                IconButton(onClick = panel.onBack, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.doc_viewer_back))
                }
                Text(panel.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = InfinitySpacing.ScreenHorizontal)
                    .then(panel.testTag?.let { Modifier.testTag(it) } ?: Modifier),
                content = panel.content,
            )
            panel.footer?.let { footer ->
                Column(Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Medium)) { footer() }
            }
            return@Column
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(vertical = InfinitySpacing.Large),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
        ) {
            details?.let { Column(Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal), content = it) }
            val ordered = actions.sortedByDescending { it.tone == InfinityPillTone.PRIMARY } + secondaryActions
            if (ordered.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.ScreenHorizontal),
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
                ) {
                    ordered.forEach { action -> DocumentViewerPill(action, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

@Composable
private fun DocumentViewerPill(action: DocumentViewerAction, modifier: Modifier = Modifier) {
    InfinityPillButton(
        text = action.label,
        onClick = action.onClick,
        tone = action.tone,
        enabled = action.enabled,
        icon = action.icon,
        modifier = modifier.then(action.testTag?.let { Modifier.testTag(it) } ?: Modifier),
    )
}

object DocumentViewerTags {
    const val ROOT = "document_viewer"
    const val CLOSE = "document_viewer_close"
    const val BODY = "document_viewer_body"
}
