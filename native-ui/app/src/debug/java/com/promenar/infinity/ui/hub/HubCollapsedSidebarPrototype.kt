package com.promenar.infinity.ui.hub

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.ui.chat.ChatScreenActions
import com.promenar.infinity.ui.chat.ChatScreenContent
import com.promenar.infinity.ui.chat.ChatScreenState
import com.promenar.infinity.ui.chat.ChatUiState
import com.promenar.infinity.ui.chat.GenerationStatus
import com.promenar.infinity.ui.chat.LocalChatNavigationIcon
import com.promenar.infinity.ui.common.InfinityAdaptivePaneHost
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.motion.MotionPalette
import com.promenar.infinity.ui.common.motion.rememberMotionPalette
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.selectionIndicator

/*
 * 折叠屏内屏折叠态左侧边栏 V3 高保真原型（完全重构 · 极简纯图形轨）：
 * 1. 宽度全部严格窄于现状 72dp（48dp / 40dp / 52dp），大幅释放右侧正文阅读与工作区空间；
 * 2. 折叠条内彻底取消文本标题、Agent 头像与 Emoji，杜绝窄列截断与形状冲突，完整标题与助手信息留给展开抽屉或按需 Peek；
 * 3. 核心信息只聚焦两件事：会家族化的「主-分支拓扑关系」与「实时状态信号」：
 *    - 家族 1（Infinity）：主会话（置顶 · 空闲）→ 分支 1（当前选中 · 流式）+ 分支 2（工具调用中）
 *    - 家族 2（Infinity）：主会话（空闲）→ 分支 3（待审批）
 *    - 独立会话（视觉导演）：置顶 · 待回答
 *    - 独立会话（总纲主编）：排队中
 */

private const val V3_NOW = 1_795_100_000_000L
private const val V3_SELECTED_ID = "s-fam1-luzhao"

private enum class V3RunState(val label: String) {
    STREAMING("流式生成中"),
    TOOL_EXECUTING("工具调用中"),
    AWAITING_APPROVAL("等待审批"),
    AWAITING_ANSWER("等待用户回答"),
    QUEUED("队列排队中"),
    IDLE("空闲"),
}

private data class V3SessionNode(
    val session: Session,
    val agent: AgentDisplayItem,
    val runState: V3RunState,
    val legacyActivity: HubSessionActivity?,
)

private data class V3Cluster(
    val root: V3SessionNode,
    val branches: List<V3SessionNode> = emptyList(),
)

private val v3AgentInfinity = AgentDisplayItem(
    agent = Agent(id = "infinity", name = "Infinity", icon = "sym:auto_awesome", color = "#6366F1"),
    title = "Infinity",
    subtitle = "主控助手",
)

private val v3AgentDirector = AgentDisplayItem(
    agent = Agent(id = "director", name = "视觉导演", icon = "sym:palette", color = "#EC4899"),
    title = "视觉导演",
    subtitle = "分镜与视觉",
)

private val v3AgentEditor = AgentDisplayItem(
    agent = Agent(id = "editor", name = "总纲主编", icon = "sym:edit_note", color = "#10B981"),
    title = "总纲主编",
    subtitle = "审校与大纲",
)

private fun v3Session(
    id: String,
    agentId: String,
    title: String,
    pinned: Boolean = false,
    parentId: String? = null,
    branch: String? = null,
    minutesAgo: Long,
): Session = Session(
    id = id,
    agentId = agentId,
    title = title,
    isPinned = pinned,
    parentSessionId = parentId,
    worktreeBranch = branch,
    createdAt = V3_NOW - minutesAgo * 60_000L,
    updatedAt = V3_NOW - minutesAgo * 60_000L,
)

private val v3Nodes = listOf(
    V3SessionNode(
        session = v3Session("s-fam1-root", "infinity", "帝国暗面全书制作与分镜拆解", pinned = true, minutesAgo = 6),
        agent = v3AgentInfinity,
        runState = V3RunState.IDLE,
        legacyActivity = null,
    ),
    V3SessionNode(
        session = v3Session("s-fam1-luzhao", "infinity", "陆昭晨间问候 20s 测试片", parentId = "s-fam1-root", branch = "cam-01", minutesAgo = 1),
        agent = v3AgentInfinity,
        runState = V3RunState.STREAMING,
        legacyActivity = HubSessionActivity.GENERATING,
    ),
    V3SessionNode(
        session = v3Session("s-fam1-50mm", "infinity", "50mm 门前行动区参考图派生", parentId = "s-fam1-root", branch = "shot-50mm", minutesAgo = 2),
        agent = v3AgentInfinity,
        runState = V3RunState.TOOL_EXECUTING,
        legacyActivity = HubSessionActivity.GENERATING,
    ),
    V3SessionNode(
        session = v3Session("s-fam2-root", "infinity", "Ktor 与参考图协议排障主线", minutesAgo = 9),
        agent = v3AgentInfinity,
        runState = V3RunState.IDLE,
        legacyActivity = null,
    ),
    V3SessionNode(
        session = v3Session("s-fam2-okhttp", "infinity", "OkHttp 真实上传回归验证分支", parentId = "s-fam2-root", branch = "okhttp-fix", minutesAgo = 4),
        agent = v3AgentInfinity,
        runState = V3RunState.AWAITING_APPROVAL,
        legacyActivity = HubSessionActivity.AWAITING_APPROVAL,
    ),
    V3SessionNode(
        session = v3Session("s-dir-view", "director", "主角三视图与服装设定待确认", pinned = true, minutesAgo = 11),
        agent = v3AgentDirector,
        runState = V3RunState.AWAITING_ANSWER,
        legacyActivity = HubSessionActivity.AWAITING_ANSWER,
    ),
    V3SessionNode(
        session = v3Session("s-ed-queue", "editor", "40 万字第一卷实体表排队校对", minutesAgo = 15),
        agent = v3AgentEditor,
        runState = V3RunState.QUEUED,
        legacyActivity = HubSessionActivity.QUEUED,
    ),
)

private val v3Clusters = listOf(
    V3Cluster(root = v3Nodes[0], branches = listOf(v3Nodes[1], v3Nodes[2])),
    V3Cluster(root = v3Nodes[3], branches = listOf(v3Nodes[4])),
    V3Cluster(root = v3Nodes[5]),
    V3Cluster(root = v3Nodes[6]),
)

@Composable
private fun V3ProtoFrame(mode: InfinityThemeMode, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

/** 硬件无损径向 LED 绘制基元（严禁 BlurMaskFilter，支持 2–4 珠呼吸信号）。 */
private fun DrawScope.drawLosslessLedBeads(
    state: V3RunState,
    palette: MotionPalette,
    warningColor: Color,
    center: Offset,
    beadCount: Int,
    beadRadiusPx: Float,
    spacingPx: Float,
) {
    val totalWidth = (beadCount - 1) * spacingPx
    val startX = center.x - totalWidth / 2f
    val offColor = palette.muted.copy(alpha = if (palette.dark) 0.22f else 0.24f)
    for (i in 0 until beadCount) {
        val cx = startX + i * spacingPx
        val (lit, color, glowAlpha) = when (state) {
            V3RunState.STREAMING -> {
                val active = i >= beadCount - 3
                val c = if (i == beadCount - 1) palette.hot else palette.a
                Triple(active, c, if (i == beadCount - 1) 0.72f else if (active) 0.42f else 0f)
            }
            V3RunState.TOOL_EXECUTING -> {
                val pairIndex = i % 2 == 0
                Triple(pairIndex, if (pairIndex) palette.b else offColor, if (pairIndex) 0.65f else 0f)
            }
            V3RunState.AWAITING_APPROVAL -> Triple(true, palette.b, 0.52f)
            V3RunState.AWAITING_ANSWER -> Triple(true, warningColor, 0.56f)
            V3RunState.QUEUED -> Triple(i == 0 || i == beadCount - 1, palette.muted.copy(alpha = 0.75f), 0f)
            V3RunState.IDLE -> Triple(false, offColor, 0f)
        }
        if (lit && glowAlpha > 0.01f) {
            val haloR = beadRadiusPx * 3.1f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = glowAlpha), Color.Transparent),
                    center = Offset(cx, center.y),
                    radius = haloR,
                ),
                radius = haloR,
                center = Offset(cx, center.y),
            )
        }
        drawCircle(
            color = color,
            radius = beadRadiusPx,
            center = Offset(cx, center.y),
        )
    }
}

@Composable
private fun V3StateGlyph(
    state: V3RunState,
    selected: Boolean,
    compact: Boolean = false,
) {
    val palette = rememberMotionPalette()
    val warning = MaterialTheme.infinityDomainColors.warning
    val beadRadius = if (compact) 1.8.dp else 2.dp
    val iconSize = if (compact) 11.dp else 12.dp
    when (state) {
        V3RunState.STREAMING, V3RunState.TOOL_EXECUTING -> {
            val beads = 3
            val step = if (compact) 5.dp else 5.8.dp
            Canvas(Modifier.size(width = step * (beads - 1) + 10.dp, height = 12.dp)) {
                drawLosslessLedBeads(
                    state = state,
                    palette = palette,
                    warningColor = warning,
                    center = Offset(size.width / 2f, size.height / 2f),
                    beadCount = beads,
                    beadRadiusPx = beadRadius.toPx(),
                    spacingPx = step.toPx(),
                )
            }
        }
        V3RunState.AWAITING_APPROVAL -> {
            val tint = palette.b
            Box(
                modifier = Modifier
                    .size(iconSize + 5.dp)
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(tint.copy(alpha = 0.42f), Color.Transparent),
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = size.minDimension * 0.56f,
                            ),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
            }
        }
        V3RunState.AWAITING_ANSWER -> {
            val haloColor = warning
            Box(
                modifier = Modifier
                    .size(iconSize + 5.dp)
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(haloColor.copy(alpha = 0.40f), Color.Transparent),
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = size.minDimension * 0.56f,
                            ),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.HelpOutline,
                    contentDescription = null,
                    tint = haloColor,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        V3RunState.QUEUED -> {
            Icon(
                Icons.Rounded.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                modifier = Modifier.size(iconSize - 0.5.dp),
            )
        }
        V3RunState.IDLE -> {
            val pillW = if (compact) 11.dp else 14.dp
            val pillH = if (compact) 3.2.dp else 3.6.dp
            val pillColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f)
            }
            Box(
                modifier = Modifier
                    .size(width = pillW, height = pillH)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(pillColor),
            )
        }
    }
}

@Composable
private fun V3RailChromeHeader(railWidth: Dp, buttonSize: Dp, pureSurface: Boolean = false) {
    Spacer(Modifier.height(6.dp))
    val container = if (pureSurface) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
    }
    IconButton(
        onClick = {},
        modifier = Modifier
            .size(buttonSize)
            .border(
                width = 0.75.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (pureSurface) 0.50f else 0.55f),
                shape = RoundedCornerShape(10.dp),
            ),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = container,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ViewSidebar,
            contentDescription = "展开完整会话侧栏",
            modifier = Modifier
                .size(17.dp)
                .graphicsLayer { scaleX = -1f },
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = if (railWidth <= 42.dp) 9.dp else 11.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f),
    )
}

@Composable
private fun V3RailChromeFooter(railWidth: Dp, buttonSize: Dp, pureSurface: Boolean = false) {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = if (railWidth <= 42.dp) 9.dp else 11.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        FilledTonalIconButton(
            onClick = {},
            modifier = Modifier
                .size(buttonSize)
                .border(
                    width = 0.75.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(10.dp),
                ),
            shape = RoundedCornerShape(10.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (pureSurface) 0.52f else 0.85f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "新建会话", modifier = Modifier.size(17.dp))
        }
    }
}

// =====================================================================================
// 现状（72dp）：无拓扑关系、头像占满宽度、同名代理无法区分多组分支
// =====================================================================================

@Composable
private fun V3LegacyStrip() {
    val entries = v3Nodes.map { HubStripEntry(it.session, it.agent) }
    val activity = v3Nodes.mapNotNull { n -> n.legacyActivity?.let { n.session.id to it } }.toMap()
    Box(Modifier.width(HubStripWidth).fillMaxHeight()) {
        HubSessionStrip(
            entries = entries,
            activity = activity,
            selectedSessionId = V3_SELECTED_ID,
            enabled = true,
            onOpenList = {},
            onSelect = {},
        )
    }
}

// =====================================================================================
// 方案 1（48dp，比现状窄 24dp）：精密家族胶囊轨 · Telemetry Capsules
// =====================================================================================

@Composable
private fun V3Plan1CapsulesRail() {
    val railWidth = 48.dp
    Column(
        modifier = Modifier
            .width(railWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        V3RailChromeHeader(railWidth = railWidth, buttonSize = 32.dp)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(v3Clusters.size) { index ->
                V3Plan1ClusterPod(v3Clusters[index])
            }
        }
        V3RailChromeFooter(railWidth = railWidth, buttonSize = 32.dp)
    }
}

@Composable
private fun V3Plan1ClusterPod(cluster: V3Cluster) {
    val podShape = RoundedCornerShape(13.dp)
    val hasActiveChild = cluster.branches.any { it.session.id == V3_SELECTED_ID || it.runState != V3RunState.IDLE }
    val borderColor = if (hasActiveChild || cluster.root.runState != V3RunState.IDLE) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    }
    val podBg = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(podShape)
            .background(podBg)
            .border(width = 0.85.dp, color = borderColor, shape = podShape)
            .padding(vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        V3Plan1Slot(node = cluster.root, isRoot = true, hasBranches = cluster.branches.isNotEmpty(), isLastBranch = cluster.branches.isEmpty())
        cluster.branches.forEachIndexed { idx, branch ->
            V3Plan1Slot(
                node = branch,
                isRoot = false,
                hasBranches = false,
                isLastBranch = idx == cluster.branches.lastIndex,
            )
        }
    }
}

@Composable
private fun V3Plan1Slot(
    node: V3SessionNode,
    isRoot: Boolean,
    hasBranches: Boolean,
    isLastBranch: Boolean,
) {
    val selected = node.session.id == V3_SELECTED_ID
    val palette = rememberMotionPalette()
    val slotShape = RoundedCornerShape(9.dp)
    val guideColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
    val primaryColor = MaterialTheme.colorScheme.primary
    val activeFill = when {
        selected -> MaterialTheme.colorScheme.selectionIndicator()
        node.runState == V3RunState.AWAITING_APPROVAL -> palette.b.copy(alpha = 0.14f)
        node.runState == V3RunState.AWAITING_ANSWER -> MaterialTheme.infinityDomainColors.warning.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    val description = "${node.agent.title} · ${node.session.title} · ${node.runState.label}"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isRoot) 26.dp else 28.dp)
            .padding(horizontal = 3.dp)
            .clip(slotShape)
            .background(activeFill)
            .drawBehind {
                val spineX = 6.5.dp.toPx()
                val cy = size.height / 2f
                if (isRoot && hasBranches) {
                    drawLine(
                        color = guideColor,
                        start = Offset(spineX, cy + 5.dp.toPx()),
                        end = Offset(spineX, size.height),
                        strokeWidth = 1.35.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                } else if (!isRoot) {
                    drawLine(
                        color = guideColor,
                        start = Offset(spineX, 0f),
                        end = Offset(spineX, if (isLastBranch) cy else size.height),
                        strokeWidth = 1.35.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = guideColor,
                        start = Offset(spineX, cy),
                        end = Offset(10.dp.toPx(), cy),
                        strokeWidth = 1.35.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                if (selected) {
                    val barW = 2.4.dp.toPx()
                    val barH = size.height * 0.56f
                    val barX = 0.dp.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(primaryColor.copy(alpha = 0.45f), Color.Transparent),
                            center = Offset(barX + barW, cy),
                            radius = 9.dp.toPx(),
                        ),
                        radius = 9.dp.toPx(),
                        center = Offset(barX + barW, cy),
                    )
                    drawRoundRect(
                        color = primaryColor,
                        topLeft = Offset(barX, cy - barH / 2f),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                    )
                }
            }
            .semantics { contentDescription = description }
            .clickable(role = Role.Button) {},
    ) {
        if (isRoot && node.session.isPinned) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 3.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = if (isRoot) 0.dp else 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isRoot && node.runState == V3RunState.IDLE) {
                Box(
                    modifier = Modifier
                        .size(width = 14.dp, height = 3.8.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)),
                )
            } else {
                V3StateGlyph(state = node.runState, selected = selected, compact = true)
            }
        }
    }
}

// =====================================================================================
// 方案 2（40dp，比现状窄 32dp）：极简电路迹线 · Circuit Patchbay
// =====================================================================================

@Composable
private fun V3Plan2PatchbayRail() {
    val railWidth = 40.dp
    Column(
        modifier = Modifier
            .width(railWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        V3RailChromeHeader(railWidth = railWidth, buttonSize = 28.dp, pureSurface = true)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(v3Clusters.size) { index ->
                V3Plan2CircuitCluster(v3Clusters[index])
            }
        }
        V3RailChromeFooter(railWidth = railWidth, buttonSize = 28.dp, pureSurface = true)
    }
}

@Composable
private fun V3Plan2CircuitCluster(cluster: V3Cluster) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        V3Plan2CircuitRow(
            node = cluster.root,
            isRoot = true,
            hasBranches = cluster.branches.isNotEmpty(),
            isLastBranch = cluster.branches.isEmpty(),
        )
        cluster.branches.forEachIndexed { idx, branch ->
            V3Plan2CircuitRow(
                node = branch,
                isRoot = false,
                hasBranches = false,
                isLastBranch = idx == cluster.branches.lastIndex,
            )
        }
    }
}

@Composable
private fun V3Plan2CircuitRow(
    node: V3SessionNode,
    isRoot: Boolean,
    hasBranches: Boolean,
    isLastBranch: Boolean,
) {
    val selected = node.session.id == V3_SELECTED_ID
    val palette = rememberMotionPalette()
    val warning = MaterialTheme.infinityDomainColors.warning
    val traceColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val selectedBg = MaterialTheme.colorScheme.selectionIndicator(MaterialTheme.colorScheme.surface)
    val description = "${node.agent.title} · ${node.session.title} · ${node.runState.label}"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isRoot) 26.dp else 28.dp)
            .drawBehind {
                val cy = size.height / 2f
                val rootCenterX = size.width * 0.50f
                val trunkX = size.width * 0.25f
                val branchCenterX = size.width * 0.58f

                if (selected) {
                    drawRoundRect(
                        color = selectedBg,
                        topLeft = Offset(3.dp.toPx(), 2.dp.toPx()),
                        size = Size(size.width - 6.dp.toPx(), size.height - 4.dp.toPx()),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    )
                    drawRoundRect(
                        color = primary,
                        topLeft = Offset(0.5.dp.toPx(), 5.dp.toPx()),
                        size = Size(2.5.dp.toPx(), size.height - 10.dp.toPx()),
                        cornerRadius = CornerRadius(1.25.dp.toPx(), 1.25.dp.toPx()),
                    )
                }

                if (isRoot && hasBranches) {
                    drawLine(
                        color = traceColor,
                        start = Offset(trunkX, cy + 4.dp.toPx()),
                        end = Offset(trunkX, size.height),
                        strokeWidth = 1.35.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                } else if (!isRoot) {
                    drawLine(
                        color = traceColor,
                        start = Offset(trunkX, 0f),
                        end = Offset(trunkX, if (isLastBranch) cy else size.height),
                        strokeWidth = 1.35.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = traceColor,
                        start = Offset(trunkX, cy),
                        end = Offset(branchCenterX - 8.dp.toPx(), cy),
                        strokeWidth = 1.35.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                // 节点几何形态（Root = 扁方胶囊；Branch = 居中/偏移状态节点）
                val cx = if (isRoot) rootCenterX else branchCenterX
                when {
                    isRoot && node.runState == V3RunState.IDLE -> {
                        val w = 14.dp.toPx()
                        val h = 6.8.dp.toPx()
                        drawRoundRect(
                            color = onSurfaceVar.copy(alpha = 0.58f),
                            topLeft = Offset(cx - w / 2f, cy - h / 2f),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                            style = Stroke(width = 1.4.dp.toPx()),
                        )
                        if (node.session.isPinned) {
                            drawCircle(
                                color = primary,
                                radius = 2.dp.toPx(),
                                center = Offset(size.width - 6.dp.toPx(), 4.5.dp.toPx()),
                            )
                        }
                    }
                    node.runState == V3RunState.STREAMING || node.runState == V3RunState.TOOL_EXECUTING -> {
                        drawLosslessLedBeads(
                            state = node.runState,
                            palette = palette,
                            warningColor = warning,
                            center = Offset(cx, cy),
                            beadCount = 3,
                            beadRadiusPx = 1.75.dp.toPx(),
                            spacingPx = 4.6.dp.toPx(),
                        )
                    }
                    else -> {
                        // AWAITING / QUEUED 用 11dp Composable 图标居中叠放
                    }
                }
            }
            .semantics { contentDescription = description }
            .clickable(role = Role.Button) {},
        contentAlignment = Alignment.Center,
    ) {
        if (node.runState == V3RunState.AWAITING_APPROVAL ||
            node.runState == V3RunState.AWAITING_ANSWER ||
            node.runState == V3RunState.QUEUED
        ) {
            Box(modifier = Modifier.padding(start = if (isRoot) 0.dp else 7.dp)) {
                V3StateGlyph(state = node.runState, selected = selected, compact = true)
            }
        }
        if (isRoot && node.runState != V3RunState.IDLE && node.session.isPinned) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 4.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(primary),
            )
        }
    }
}

// =====================================================================================
// 方案 3（52dp，比现状窄 20dp）：立体光槽仪 · Sculpted Signal Well
// =====================================================================================

@Composable
private fun V3Plan3SignalWellRail() {
    val railWidth = 52.dp
    Column(
        modifier = Modifier
            .width(railWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        V3RailChromeHeader(railWidth = railWidth, buttonSize = 34.dp)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(v3Clusters.size) { index ->
                V3Plan3WellCluster(v3Clusters[index])
            }
        }
        V3RailChromeFooter(railWidth = railWidth, buttonSize = 34.dp)
    }
}

@Composable
private fun V3Plan3WellCluster(cluster: V3Cluster) {
    val outerShape = RoundedCornerShape(14.dp)
    val palette = rememberMotionPalette()
    val wellFill = if (palette.dark) {
        MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.88f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outerShape)
            .background(wellFill)
            .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f), outerShape)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        V3Plan3WellRootTile(cluster.root)
        if (cluster.branches.isNotEmpty()) {
            val branchRailColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
            Row(modifier = Modifier.fillMaxWidth()) {
                // 左侧连续地铁垂直分支槽轨
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .padding(start = 2.dp, top = 4.dp, bottom = 4.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .drawBehind {
                            val lineX = (-4).dp.toPx()
                            drawLine(
                                color = branchRailColor,
                                start = Offset(lineX, 2.dp.toPx()),
                                end = Offset(lineX, size.height - 6.dp.toPx()),
                                strokeWidth = 1.6.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        },
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    cluster.branches.forEach { branch ->
                        V3Plan3WellBranchTile(branch)
                    }
                }
            }
        }
    }
}

@Composable
private fun V3Plan3WellRootTile(node: V3SessionNode) {
    val selected = node.session.id == V3_SELECTED_ID
    val shape = RoundedCornerShape(9.dp)
    val fill = when {
        selected -> MaterialTheme.colorScheme.selectionIndicator()
        node.runState == V3RunState.AWAITING_APPROVAL -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
        node.runState == V3RunState.AWAITING_ANSWER -> MaterialTheme.infinityDomainColors.warning.copy(alpha = 0.16f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
    }
    val description = "${node.agent.title} · ${node.session.title} · ${node.runState.label}"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(shape)
            .background(fill)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button) {},
        contentAlignment = Alignment.Center,
    ) {
        if (node.session.isPinned) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.5.dp, end = 3.5.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        if (node.runState == V3RunState.IDLE) {
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 4.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)),
            )
        } else {
            V3StateGlyph(state = node.runState, selected = selected, compact = false)
        }
    }
}

@Composable
private fun V3Plan3WellBranchTile(node: V3SessionNode) {
    val selected = node.session.id == V3_SELECTED_ID
    val palette = rememberMotionPalette()
    val primaryColor = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(8.dp)
    val fill = when {
        selected -> MaterialTheme.colorScheme.selectionIndicator()
        node.runState == V3RunState.AWAITING_APPROVAL -> palette.b.copy(alpha = 0.16f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    }
    val description = "${node.agent.title} · ${node.session.title} · ${node.runState.label}"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(shape)
            .background(fill)
            .border(0.75.dp, borderColor, shape)
            .drawBehind {
                if (selected) {
                    val cy = size.height / 2f
                    val barW = 2.5.dp.toPx()
                    val barH = size.height * 0.56f
                    drawRoundRect(
                        color = primaryColor,
                        topLeft = Offset(1.5.dp.toPx(), cy - barH / 2f),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                    )
                }
            }
            .semantics { contentDescription = description }
            .clickable(role = Role.Button) {},
        contentAlignment = Alignment.Center,
    ) {
        V3StateGlyph(state = node.runState, selected = selected, compact = true)
    }
}

// =====================================================================================
// 预览上下文与四栏 1:1 宽度对比容器
// =====================================================================================

@Composable
private fun V3ChatContextPane(
    railContent: @Composable () -> Unit,
    showPeekPopover: Boolean = false,
    peekStartPadding: Dp = 62.dp,
) {
    val activeNode = v3Nodes.first { it.session.id == V3_SELECTED_ID }
    val sampleMessages = listOf(
        Message(
            id = "u1",
            role = MessageRole.USER,
            content = "折叠后的侧边栏去掉文字标题和头像后，右侧正文区整整宽出一截；确认状态灯与分支家族是否足够清晰。",
            createdAt = V3_NOW - 120_000L,
        ),
        Message(
            id = "a1",
            role = MessageRole.ASSISTANT,
            content = "折叠态只承载「会话家族主-分支关系」与「实时状态信号」，完整标题、分支路径与操作均交由展开抽屉或按需 Peek 浮卡呈现。",
            createdAt = V3_NOW - 30_000L,
        ),
    )
    val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            railContent()
            Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CompositionLocalProvider(LocalChatNavigationIcon provides { HubExpandButton(expanded = true, onClick = {}) }) {
                    InfinityAdaptivePaneHost(paneVisible = false, onBack = {}, pane = {}) {
                        ChatScreenContent(
                            state = ChatScreenState(
                                uiState = ChatUiState(
                                    session = activeNode.session,
                                    agentName = activeNode.agent.title,
                                    messages = sampleMessages,
                                    isGenerating = true,
                                    status = GenerationStatus.RECEIVING,
                                    streamingContent = "当前方案比旧版 72dp 更省横向空间，可容纳更完整的分镜表格与代码块输出。",
                                ),
                            ),
                            actions = ChatScreenActions(),
                        )
                    }
                }
            }
        }
        if (showPeekPopover) {
            val cardShape = RoundedCornerShape(16.dp)
            Surface(
                shape = cardShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                modifier = Modifier
                    .padding(start = peekStartPadding, top = 236.dp)
                    .widthIn(max = 268.dp)
                    .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), cardShape),
            ) {
                Column(
                    modifier = Modifier.padding(InfinitySpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Text("按需 Peek · 分支 cam-01", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(activeNode.session.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    InfinityMetaLine(
                        segments = listOf("Infinity", "主会话：帝国暗面", "流式生成中"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {},
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Icon(Icons.Rounded.PushPin, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("置顶", style = MaterialTheme.typography.labelSmall)
                        }
                        V3StateGlyph(state = V3RunState.STREAMING, selected = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun V3CompareColumn(
    title: String,
    badge: String,
    highlighted: Boolean = false,
    rail: @Composable () -> Unit,
) {
    val frameShape = RoundedCornerShape(16.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                    color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(frameShape)
                .border(
                    width = if (highlighted) 1.5.dp else 0.75.dp,
                    color = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.72f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    shape = frameShape,
                ),
        ) {
            rail()
        }
    }
}

@Composable
private fun V3LegendChip(label: String, graphic: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        graphic()
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// =====================================================================================
// 4 个 V3 定向 @Preview 入口（聚焦方案 2 纯黑/纯白底色 + 按需 Peek + 四栏 1:1 对照）
// =====================================================================================

/** 1. 四栏 1:1 真实宽度对照：现状 72dp vs 方案 1(48dp) vs 推荐方案 2(40dp 纯黑) vs 方案 3(52dp)。 */
@OptIn(ExperimentalLayoutApi::class)
@Preview(name = "1. V3对比：现状72 vs 方案2纯黑40(推荐) vs 1/3", widthDp = 420, heightDp = 820, locale = "zh-rCN")
@Composable
fun hubSidebarV3CompareAllDarkPreview() = V3ProtoFrame(InfinityThemeMode.DARK) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            V3CompareColumn(title = "现状", badge = "72dp · 混杂") { V3LegacyStrip() }
            V3CompareColumn(title = "方案 1", badge = "48dp (-24)") { V3Plan1CapsulesRail() }
            V3CompareColumn(title = "方案 2", badge = "40dp · 纯黑", highlighted = true) { V3Plan2PatchbayRail() }
            V3CompareColumn(title = "方案 3", badge = "52dp (-20)") { V3Plan3SignalWellRail() }
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                V3LegendChip("流式") { V3StateGlyph(V3RunState.STREAMING, selected = false, compact = true) }
                V3LegendChip("工具") { V3StateGlyph(V3RunState.TOOL_EXECUTING, selected = false, compact = true) }
                V3LegendChip("待审批") { V3StateGlyph(V3RunState.AWAITING_APPROVAL, selected = false, compact = true) }
                V3LegendChip("待回答") { V3StateGlyph(V3RunState.AWAITING_ANSWER, selected = false, compact = true) }
                V3LegendChip("排队") { V3StateGlyph(V3RunState.QUEUED, selected = false, compact = true) }
                V3LegendChip("主会话空闲") { V3StateGlyph(V3RunState.IDLE, selected = false, compact = true) }
                V3LegendChip("置顶") {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

/** 2. 方案 2（40dp 纯黑底色 · 极简电路迹线）Find N5 814dp 内屏 OLED 纯黑实景。 */
@Preview(name = "2. V3方案2 40dp极简电路(814dp纯黑)", widthDp = 814, heightDp = 760, locale = "zh-rCN")
@Composable
fun hubSidebarV3Plan2DarkPreview() = V3ProtoFrame(InfinityThemeMode.DARK) {
    V3ChatContextPane(railContent = { V3Plan2PatchbayRail() }, showPeekPopover = false)
}

/** 3. 方案 2（40dp 浅色纯白系底色 · 极简电路迹线）Find N5 814dp 内屏浅色对照。 */
@Preview(name = "3. V3方案2 40dp极简电路(814dp浅色)", widthDp = 814, heightDp = 760, locale = "zh-rCN")
@Composable
fun hubSidebarV3Plan2LightPreview() = V3ProtoFrame(InfinityThemeMode.LIGHT) {
    V3ChatContextPane(railContent = { V3Plan2PatchbayRail() }, showPeekPopover = false)
}

/** 4. 方案 2（40dp 纯黑底色 + 按需 Peek 浮卡）Find N5 814dp 内屏实景。 */
@Preview(name = "4. V3方案2 40dp纯黑+按需Peek(814dp深色)", widthDp = 814, heightDp = 760, locale = "zh-rCN")
@Composable
fun hubSidebarV3Plan2DarkPeekPreview() = V3ProtoFrame(InfinityThemeMode.DARK) {
    V3ChatContextPane(railContent = { V3Plan2PatchbayRail() }, showPeekPopover = true, peekStartPadding = 50.dp)
}
