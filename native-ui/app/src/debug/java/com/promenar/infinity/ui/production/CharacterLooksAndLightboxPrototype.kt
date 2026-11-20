package com.promenar.infinity.ui.production

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.ViewQuilt
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 全视觉参考资产双模体系 & 项目级视频/入参模式设置 & 全站大图查看器高保真原型（v2，隔离于 debug 源码）：
 * 1. 项目设置：支持配置「默认视频模型」与「参考资产入参模式（多视图资产卡 / 分张单视图组）」，
 *    切换视频模型时自动匹配推荐入参模式并支持手动覆盖，对角色/场景/道具/画风统一生效；
 * 2. 双模参考资产：单图多视图资产卡与单图单视图资产组支持二选一登记或双模并存；
 *    当前生效模式置顶展示并带「当前生效」标记，未登记的另一模式折叠为轻量「+ 补充」入口；
 * 3. 全资产一致支持：场景/道具/画风与角色共用同一套双模逻辑与审核定稿闭环（场景/道具支持自由多视角名）；
 * 4. 全站大图查看器：移除触摸无级旋转，HorizontalPager 滑切 + 双指/双击缩放 + 90° 旋转按钮与复位。
 */

private val LightboxBackdropColor = Color(0xFF0B0D10)
private val LightboxToolbarSurface = Color(0xCC161A20)
private val LightboxPillSurface = Color(0x991E242C)
private val LightboxContentTint = Color(0xFFF5F7FA)

@Composable
private fun DualModeProtoFrame(
    mode: InfinityThemeMode,
    sendCount: Int = 1,
    content: @Composable () -> Unit,
) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        CompositionLocalProvider(
            LocalProductionSend provides ProductionSendState(count = sendCount, sending = false, queued = false, onSend = {}),
        ) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

private data class ProtoViewSlotSpec(
    val id: String,
    val name: String,
    val note: String? = null,
    val hasImage: Boolean = true,
    val custom: Boolean = false,
    val badgeText: String = name,
    val isLocation: Boolean = false,
    val tintPair: Pair<Color, Color> = Color(0xFF232830) to Color(0xFF39424E),
)

private enum class ProtoAssetVerdict { NONE, APPROVE, REJECT }

@Composable
private fun ProtoTagLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun ProtoModeSectionHeader(
    title: String,
    badge: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Surface(
            shape = CircleShape,
            color = if (active) scheme.primaryContainer else scheme.surfaceContainerHigh,
        ) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ProtoCharacterCanonSummaryCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "角色设定与谱系",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
        }
    }
}

@Composable
private fun ProtoSheetFeaturedCard(
    title: String,
    subtitle: String,
    active: Boolean,
    versions: List<String> = emptyList(),
    selectedVersion: String? = null,
    isLocation: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProtoModeSectionHeader(
            title = "单图多视图资产卡",
            badge = if (active) "当前生效" else "整卡模式可用",
            active = active,
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = scheme.surfaceContainer,
            border = if (active) BorderStroke(0.75.dp, scheme.primary.copy(alpha = 0.45f)) else null,
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (versions.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("版本", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        versions.forEach { ver ->
                            val selected = ver == selectedVersion
                            Surface(
                                shape = CircleShape,
                                color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
                            ) {
                                Text(
                                    text = ver,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                if (isLocation) listOf(Color(0xFF2D251E), Color(0xFF4F3E2F), Color(0xFF202A34))
                                else listOf(Color(0xFF1D242E), Color(0xFF323E4F), Color(0xFF222933)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(3) { idx ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.White.copy(alpha = 0.1f),
                                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.18f)),
                                modifier = Modifier.size(width = 56.dp, height = 84.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        if (isLocation) Icons.Rounded.Landscape else Icons.Rounded.Person,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.52f),
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Rounded.ViewQuilt, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                            Text("多视图整卡", style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.52f),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(26.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.OpenInFull, contentDescription = "查看大图", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtoSheetCompactCard(
    title: String,
    subtitle: String,
    active: Boolean,
    versions: List<String> = emptyList(),
    selectedVersion: String? = null,
    isLocation: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProtoModeSectionHeader(
            title = "单图多视图资产卡",
            badge = if (active) "当前生效" else "整卡模式可用",
            active = active,
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = scheme.surfaceContainer,
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (versions.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("版本", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        versions.forEach { ver ->
                            val selected = ver == selectedVersion
                            Surface(
                                shape = CircleShape,
                                color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
                            ) {
                                Text(
                                    text = ver,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .width(104.dp)
                            .aspectRatio(16f / 10f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    if (isLocation) listOf(Color(0xFF2D251E), Color(0xFF4F3E2F))
                                    else listOf(Color(0xFF1E2631), Color(0xFF394657)),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (isLocation) Icons.Rounded.Landscape else Icons.Rounded.ViewQuilt,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.88f),
                            modifier = Modifier.size(26.dp),
                        )
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.5f),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(20.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.OpenInFull, contentDescription = "查看大图", tint = Color.White, modifier = Modifier.size(11.dp))
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoEmptyViewsCollapseBar(
    sectionTitle: String,
    subtitle: String,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProtoModeSectionHeader(
            title = sectionTitle,
            badge = "未登记",
            active = false,
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = scheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                InfinityPillButton(
                    text = "补充",
                    onClick = {},
                    tone = InfinityPillTone.NEUTRAL,
                    icon = Icons.Rounded.Add,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun ProtoViewsGridBlock(
    title: String,
    active: Boolean,
    slots: List<ProtoViewSlotSpec>,
    addLabel: String = "添加视角",
    showAddCard: Boolean = true,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProtoModeSectionHeader(
            title = title,
            badge = if (active) "当前生效" else "分张模式可用",
            active = active,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 600.dp) 5 else 3
            val cells: List<ProtoViewSlotSpec?> = if (showAddCard) slots + listOf(null) else slots
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cells.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { slot ->
                            if (slot != null) {
                                ProtoViewSlotCard(slot = slot, modifier = Modifier.weight(1f))
                            } else {
                                ProtoAddViewCard(label = addLabel, modifier = Modifier.weight(1f))
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoViewSlotCard(
    slot: ProtoViewSlotSpec,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainer,
        onClick = {},
        modifier = modifier,
    ) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (slot.hasImage) Brush.verticalGradient(listOf(slot.tintPair.first, slot.tintPair.second))
                        else Brush.verticalGradient(listOf(scheme.surfaceContainerHighest, scheme.surfaceContainerHigh)),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (slot.hasImage) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            if (slot.isLocation) Icons.Rounded.Landscape else Icons.Rounded.Person,
                            contentDescription = slot.name,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(30.dp),
                        )
                        Text(
                            text = slot.badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.48f),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(22.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.OpenInFull,
                                contentDescription = "查看大图",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Image,
                            contentDescription = "待补图",
                            tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = "待补图",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (slot.custom) {
                    Surface(
                        shape = CircleShape,
                        color = scheme.inverseSurface.copy(alpha = 0.86f),
                        onClick = {},
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "移除视角",
                                tint = scheme.inverseOnSurface,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = slot.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = slot.note ?: "未设备注",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProtoAddViewCard(
    label: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val outline = scheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainer,
        onClick = {},
        modifier = modifier,
    ) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(0.75f),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRoundRect(
                        color = outline,
                        cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                        style = Stroke(
                            width = 1.2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                        ),
                    )
                }
                Icon(Icons.Rounded.Add, contentDescription = label, tint = scheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "自由命名",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProtoAssetApprovalFooter(
    verdict: ProtoAssetVerdict = ProtoAssetVerdict.NONE,
    rejectReason: String? = null,
    stagedHintCount: Int = 0,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!rejectReason.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = scheme.errorContainer.copy(alpha = 0.24f),
                border = BorderStroke(0.5.dp, scheme.error.copy(alpha = 0.28f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "否决原因",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.error,
                    )
                    Text(text = rejectReason, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                }
            }
        }

        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))

        ProductionActionRow(
            actions = listOf(
                ProductionButton(
                    text = "否决",
                    tone = InfinityPillTone.DANGER,
                    enabled = true,
                    tag = "proto_dual_reject",
                    icon = Icons.Rounded.Close.takeIf { verdict == ProtoAssetVerdict.REJECT },
                    onClick = {},
                ),
                ProductionButton(
                    text = "建议",
                    tone = InfinityPillTone.NEUTRAL,
                    enabled = true,
                    tag = "proto_dual_suggest",
                    onClick = {},
                ),
                ProductionButton(
                    text = "批准",
                    tone = InfinityPillTone.PRIMARY,
                    enabled = true,
                    tag = "proto_dual_approve",
                    icon = Icons.Rounded.Check.takeIf { verdict == ProtoAssetVerdict.APPROVE },
                    onClick = {},
                ),
            ),
        )

        if (stagedHintCount > 0) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = domain.infoContainer.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = domain.onInfoContainer)
                    Text(
                        text = "已暂存 $stagedHintCount 条意见，点右上角「发送」后同步给智能体",
                        style = MaterialTheme.typography.labelMedium,
                        color = domain.onInfoContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtoProjectReferenceModePanel(
    selectedMode: String,
    modelTitle: String,
    modelSubtitle: String,
    hintText: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ProductionSection("视频生成与参考入参") {
            ProductionRow(
                title = "默认视频模型",
                icon = Icons.Rounded.Hub,
                subtitle = modelSubtitle,
                subtitleMaxLines = 2,
                value = modelTitle,
                onClick = {},
            )
            HorizontalDivider(
                Modifier.padding(horizontal = InfinitySpacing.RowHorizontal),
                color = scheme.outlineVariant.copy(alpha = 0.45f),
            )
            Column(
                Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "参考资产入参模式",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                )
                InfinitySegmentedControl(
                    segments = listOf(
                        InfinitySegment("sheet", "多视图资产卡"),
                        InfinitySegment("separate", "分张单视图组"),
                    ),
                    selected = selectedMode,
                    onSelect = {},
                    fillWidth = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.surfaceContainerHigh.copy(alpha = 0.72f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Info,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.padding(top = 1.dp).size(16.dp),
                        )
                        Text(
                            text = hintText,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(
                Modifier.padding(horizontal = InfinitySpacing.RowHorizontal),
                color = scheme.outlineVariant.copy(alpha = 0.45f),
            )
            ProductionRow(
                title = "管理媒体模型与工作流",
                icon = Icons.Rounded.SwapHoriz,
                onClick = {},
            )
        }
    }
}

private data class LightboxActionSpec(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
)

@Composable
private fun ProtoLightboxCanvas(
    pageIndex: Int,
    pageCount: Int,
    title: String,
    subtitle: String,
    isWide: Boolean,
    rotated90: Boolean,
    zoomMultiplier: String?,
    resetEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val compactLabels = with(LocalDensity.current) { fontScale >= 1.25f }
    val showTransformPill = rotated90 || zoomMultiplier != null
    val pillSummary = listOfNotNull(if (rotated90) "90°" else null, zoomMultiplier).joinToString(" · ")
    val actions = listOf(
        LightboxActionSpec(Icons.Rounded.RestartAlt, "复位", enabled = resetEnabled),
        LightboxActionSpec(Icons.Rounded.RotateRight, "旋转"),
        LightboxActionSpec(Icons.Rounded.SaveAlt, "保存"),
        LightboxActionSpec(Icons.Rounded.Share, "分享"),
        LightboxActionSpec(Icons.Rounded.Draw, "标注"),
        LightboxActionSpec(Icons.Rounded.OpenInNew, "打开"),
    )

    Box(modifier = modifier.fillMaxWidth().background(LightboxBackdropColor)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 74.dp, bottom = 104.dp, start = if (isWide) 72.dp else 20.dp, end = if (isWide) 72.dp else 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF1D232B),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                modifier = if (rotated90) Modifier.fillMaxWidth(0.76f).aspectRatio(16f / 9f)
                else Modifier.fillMaxHeight(0.9f).aspectRatio(0.56f),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF1E2631), Color(0xFF334152), Color(0xFF181D24)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (rotated90) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Rounded.RotateRight,
                                contentDescription = null,
                                tint = LightboxContentTint.copy(alpha = 0.9f),
                                modifier = Modifier.size(40.dp),
                            )
                            Text(
                                text = "已顺时针旋转 90° · 放大 $zoomMultiplier",
                                style = MaterialTheme.typography.titleSmall,
                                color = LightboxContentTint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "放大态锁定翻页，专注边界夹紧拖拽",
                                style = MaterialTheme.typography.labelMedium,
                                color = LightboxContentTint.copy(alpha = 0.72f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = null,
                                tint = LightboxContentTint.copy(alpha = 0.88f),
                                modifier = Modifier.size(84.dp),
                            )
                            Text(
                                text = "侧面全身 · 380×1000",
                                style = MaterialTheme.typography.labelLarge,
                                color = LightboxContentTint.copy(alpha = 0.85f),
                            )
                            Text(
                                text = "未缩放态支持左右滑动切图 · 禁用触摸无级旋转",
                                style = MaterialTheme.typography.labelSmall,
                                color = LightboxContentTint.copy(alpha = 0.65f),
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0xD8000000), Color.Transparent)))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(shape = CircleShape, color = LightboxPillSurface) {
                    Text(
                        text = "$pageIndex / $pageCount",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = LightboxContentTint,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = LightboxContentTint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = LightboxContentTint.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showTransformPill) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                    ) {
                        Text(
                            text = pillSummary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                FilledTonalIconButton(
                    onClick = {},
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = LightboxPillSurface,
                        contentColor = LightboxContentTint,
                    ),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭", modifier = Modifier.size(20.dp))
                }
            }
        }

        if (isWide) {
            FilledTonalIconButton(
                onClick = {},
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = LightboxPillSurface,
                    contentColor = LightboxContentTint,
                ),
            ) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "上一张")
            }
            FilledTonalIconButton(
                onClick = {},
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = LightboxPillSurface,
                    contentColor = LightboxContentTint,
                ),
            ) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "下一张")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDC000000))))
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in 1..pageCount) {
                    val active = i == pageIndex
                    Box(
                        Modifier
                            .height(5.dp)
                            .width(if (active) 16.dp else 5.dp)
                            .clip(CircleShape)
                            .background(if (active) LightboxContentTint else LightboxContentTint.copy(alpha = 0.38f)),
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = LightboxToolbarSurface,
                border = BorderStroke(0.5.dp, LightboxContentTint.copy(alpha = 0.16f)),
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = if (compactLabels) 6.dp else 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions.forEach { item ->
                        val tint = if (item.enabled) LightboxContentTint else LightboxContentTint.copy(alpha = 0.38f)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Transparent,
                            onClick = {},
                            enabled = item.enabled,
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(
                                Modifier.padding(horizontal = 2.dp, vertical = if (compactLabels) 8.dp else 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                    tint = tint,
                                    modifier = Modifier.size(20.dp),
                                )
                                if (!compactLabels) {
                                    Text(
                                        text = item.label,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        color = tint,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val defaultThreeViewSlots = listOf(
    ProtoViewSlotSpec("front", "正面全身", "380×1000 · 2x", badgeText = "正面", tintPair = Color(0xFF202A36) to Color(0xFF394B60)),
    ProtoViewSlotSpec("side", "侧面全身", "380×1000 · 2x", badgeText = "侧面", tintPair = Color(0xFF262D38) to Color(0xFF424E60)),
    ProtoViewSlotSpec("back", "背面全身", "380×1000 · 2x", badgeText = "背面", tintPair = Color(0xFF1F2730) to Color(0xFF344150)),
    ProtoViewSlotSpec("closeup", "面部特写", "特写补充参考", custom = true, badgeText = "特写", tintPair = Color(0xFF2D2832) to Color(0xFF4C4355)),
)

private val defaultSceneViewSlots = listOf(
    ProtoViewSlotSpec("wide", "全景主视角", "晨雾全景", badgeText = "全景", isLocation = true, tintPair = Color(0xFF2A231C) to Color(0xFF4D3C2C)),
    ProtoViewSlotSpec("facade", "门头近景", "木匾特写", badgeText = "门头", isLocation = true, tintPair = Color(0xFF2C2721) to Color(0xFF534538)),
    ProtoViewSlotSpec("corner", "街角侧视", "景深对照", custom = true, badgeText = "侧视", isLocation = true, tintPair = Color(0xFF222830) to Color(0xFF3A4452)),
)

@Preview(name = "1. 项目设置·视频模型与入参模式(深色412dp)", widthDp = 412, heightDp = 980, locale = "zh-rCN")
@Composable
fun proto1ProjectVideoAndInputModeDarkPreview() = DualModeProtoFrame(InfinityThemeMode.DARK, sendCount = 0) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = scheme.surfaceContainer,
            border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "项目设置 · 陆昭晨间问候 20s 测试片",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
                ProtoProjectReferenceModePanel(
                    selectedMode = "separate",
                    modelTitle = "本机 ComfyUI · H3 Ref2VA",
                    modelSubtitle = "3 图 · 原生音频 · 推荐分张",
                    hintText = "切换视频模型时会自动匹配推荐入参模式，也可手动覆盖；对角色、场景、道具、画风全部参考资产统一生效，单镜头可在分镜规划中局部覆写",
                )
                ProductionSection("项目流程与文件") {
                    ProductionRow(
                        title = "试验项目",
                        icon = Icons.Rounded.Science,
                        subtitle = "跳过场批准检查",
                        chevron = false,
                        trailing = { InfinitySwitch(checked = false, onCheckedChange = null) },
                        onClick = {},
                    )
                    ProductionRow(
                        title = "在文件中打开",
                        icon = Icons.Rounded.FolderOpen,
                        subtitle = "AI-Assets/Productions/lu-zhao-20s",
                        onClick = {},
                    )
                }
            }
        }

        ProtoTagLabel("对照态：切换默认视频模型为 Seedance 2.5 时自动联动")
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = scheme.surfaceContainer,
            border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProductionRow(
                    title = "默认视频模型",
                    icon = Icons.Rounded.Hub,
                    subtitle = "最多 9 图 · 推荐单图多视图整卡直接入参",
                    subtitleMaxLines = 2,
                    value = "Seedance 2.5",
                    onClick = {},
                )
                InfinitySegmentedControl(
                    segments = listOf(
                        InfinitySegment("sheet", "多视图资产卡"),
                        InfinitySegment("separate", "分张单视图组"),
                    ),
                    selected = "sheet",
                    onSelect = {},
                    fillWidth = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Preview(name = "2. 角色双模全登记·分张生效态(深色412dp)", widthDp = 412, heightDp = 1540, locale = "zh-rCN")
@Composable
fun proto2CharacterDualModeSeparateActiveDarkPreview() = DualModeProtoFrame(InfinityThemeMode.DARK, sendCount = 1) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        ProductionTopBar(
            title = "陆昭 · 新神州玄鳞飞鱼服 V3",
            meta = listOf("角色", "4 个镜头引用"),
            onBack = {},
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small)),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProtoCharacterCanonSummaryCard(
                text = "Canon 资产编号 ASSET-LZ-01C。30岁东方青年军警，185cm，深黑短发、无胡茬，重磅哑光黑真丝天工-09型风衣，左胸神巡·070001。",
            )
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = scheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "造型参考包",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        ProductionStatusChip("待审", ProductionTone.WARNING)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        InfinitySegmentedControl(
                            segments = listOf(
                                InfinitySegment("armor", "玄鳞飞鱼服"),
                                InfinitySegment("casual", "便装常服"),
                            ),
                            selected = "armor",
                            onSelect = {},
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalIconButton(
                            onClick = {},
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = scheme.surfaceContainerHigh,
                                contentColor = scheme.onSurface,
                            ),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "新建造型", modifier = Modifier.size(20.dp))
                        }
                    }

                    ProtoViewsGridBlock(
                        title = "单图单视图资产组",
                        active = true,
                        slots = defaultThreeViewSlots,
                        addLabel = "添加位",
                    )

                    ProtoSheetCompactCard(
                        title = "ASSET-LZ-01C 三视图定稿整卡",
                        subtitle = "供 Seedance 等整卡模型直接单图入参",
                        active = false,
                    )

                    ProtoAssetApprovalFooter(verdict = ProtoAssetVerdict.NONE)
                }
            }
            ProductionSection("参考语音") {
                ProductionMutedRow("尚未设置参考语音")
            }
            ProductionSection("镜头引用") {
                ProductionRow(
                    title = "承天街晨雾巡街",
                    titleSegments = listOf("SC01-01", "承天街晨雾巡街"),
                    value = "玄鳞飞鱼服 · 分张 3 视角",
                    onClick = {},
                )
                ProductionRow(
                    title = "糕点铺门前停步",
                    titleSegments = listOf("SC01-02", "糕点铺门前停步"),
                    value = "玄鳞飞鱼服 · 分张 3 视角",
                    onClick = {},
                )
            }
        }
    }
}

@Preview(name = "3. 仅整卡态与场景双模详情(深色412dp)", widthDp = 412, heightDp = 1660, locale = "zh-rCN")
@Composable
fun proto3CharacterSheetOnlyAndSceneDualModeDarkPreview() = DualModeProtoFrame(InfinityThemeMode.DARK, sendCount = 1) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProtoTagLabel("A. 角色详情 · 仅登记多视图整卡（当前项目模式：多视图资产卡 · Seedance）")
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = scheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "造型参考包",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    ProductionStatusChip("已批准 · 未发送", ProductionTone.SUCCESS)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InfinitySegmentedControl(
                        segments = listOf(InfinitySegment("armor", "玄鳞飞鱼服")),
                        selected = "armor",
                        onSelect = {},
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalIconButton(
                        onClick = {},
                        modifier = Modifier.size(36.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = scheme.surfaceContainerHigh,
                            contentColor = scheme.onSurface,
                        ),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "新建造型", modifier = Modifier.size(20.dp))
                    }
                }
                ProtoSheetFeaturedCard(
                    title = "ASSET-LZ-01C 三视图定稿整卡",
                    subtitle = "三视图定稿整卡，Seedance 直接单图入参",
                    active = true,
                )
                ProtoEmptyViewsCollapseBar(
                    sectionTitle = "单图单视图资产组",
                    subtitle = "未登记 · 当前整卡模式无需拆分，可随时补充",
                )
                ProtoAssetApprovalFooter(
                    verdict = ProtoAssetVerdict.APPROVE,
                    stagedHintCount = 1,
                )
            }
        }

        ProtoTagLabel("B. 场景详情 · 双模全登记（当前项目模式：分张单视图组）")
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = scheme.surfaceContainerLow,
            border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "糕点铺门前 · 晨光",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                        )
                        Text(
                            text = "场景 · 3 个镜头引用",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    ProductionStatusChip("待审", ProductionTone.WARNING)
                }

                ProtoViewsGridBlock(
                    title = "单图单视图资产组",
                    active = true,
                    slots = defaultSceneViewSlots,
                    addLabel = "添加视角",
                )

                ProtoSheetCompactCard(
                    title = "承天街糕点铺多视角设定卡",
                    subtitle = "用于整卡直接入参的场景综合设定大图",
                    active = false,
                    versions = listOf("v1", "v2"),
                    selectedVersion = "v1",
                    isLocation = true,
                )

                ProtoAssetApprovalFooter(verdict = ProtoAssetVerdict.NONE)
            }
        }
    }
}

@Preview(name = "4. 全站大图查看器多图态(深色412dp)", widthDp = 412, heightDp = 860, locale = "zh-rCN")
@Composable
fun proto4LightboxMultiImageDarkPreview() = DualModeProtoFrame(InfinityThemeMode.DARK, sendCount = 0) {
    ProtoLightboxCanvas(
        pageIndex = 2,
        pageCount = 3,
        title = "侧面全身",
        subtitle = "陆昭 · 玄鳞飞鱼服 · 380×1000",
        isWide = false,
        rotated90 = false,
        zoomMultiplier = null,
        resetEnabled = false,
        modifier = Modifier.fillMaxSize(),
    )
}

@Preview(name = "5. 大图查看器宽屏90度旋转缩放态(深色760dp)", widthDp = 760, heightDp = 640, locale = "zh-rCN")
@Composable
fun proto5LightboxWideRotatedDarkPreview() = DualModeProtoFrame(InfinityThemeMode.DARK, sendCount = 0) {
    ProtoLightboxCanvas(
        pageIndex = 2,
        pageCount = 3,
        title = "侧面全身",
        subtitle = "陆昭 · 玄鳞飞鱼服 · 380×1000",
        isWide = true,
        rotated90 = true,
        zoomMultiplier = "2.0×",
        resetEnabled = true,
        modifier = Modifier.fillMaxSize(),
    )
}

@Preview(name = "6. 浅色大字号极端适配(360dp·1.3x)", widthDp = 360, heightDp = 1660, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun proto6LightLargeFontDualModePreview() = DualModeProtoFrame(InfinityThemeMode.LIGHT, sendCount = 1) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProtoTagLabel("极端大字号(360dp·1.3x) · 项目入参模式双选")
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = scheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "参考资产入参模式",
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                )
                InfinitySegmentedControl(
                    segments = listOf(
                        InfinitySegment("sheet", "多视图资产卡"),
                        InfinitySegment("separate", "分张单视图组"),
                    ),
                    selected = "separate",
                    onSelect = {},
                    fillWidth = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ProtoTagLabel("极端大字号 · 角色双模驳回与待补图")
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = scheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "造型参考包",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    ProductionStatusChip("已否决 · 未发送", ProductionTone.ERROR)
                }
                InfinitySegmentedControl(
                    segments = listOf(
                        InfinitySegment("armor", "玄鳞飞鱼服重甲版"),
                        InfinitySegment("casual", "便装巡查服"),
                    ),
                    selected = "armor",
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth(),
                )
                ProtoViewsGridBlock(
                    title = "单图单视图资产组",
                    active = true,
                    slots = listOf(
                        ProtoViewSlotSpec("front", "正面全身", "已就绪", badgeText = "正面"),
                        ProtoViewSlotSpec("side", "侧面全身", "已就绪", badgeText = "侧面"),
                        ProtoViewSlotSpec("back", "背面全身", "缺细节", hasImage = false),
                    ),
                    showAddCard = false,
                )
                ProtoSheetCompactCard(
                    title = "ASSET-LZ-01C 整卡",
                    subtitle = "整卡模式可直接入参",
                    active = false,
                )
                ProtoAssetApprovalFooter(
                    verdict = ProtoAssetVerdict.REJECT,
                    rejectReason = "背面全身裁切偏糊，肩部金纹缺失，请重出背面单图",
                )
            }
        }

        ProtoTagLabel("极端大字号 · 大图查看器 6 按钮单行自适应")
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = LightboxBackdropColor,
            modifier = Modifier.fillMaxWidth().height(420.dp),
        ) {
            ProtoLightboxCanvas(
                pageIndex = 3,
                pageCount = 3,
                title = "背面全身（长名字截断校验）",
                subtitle = "陆昭 · 玄鳞飞鱼服重甲版",
                isWide = false,
                rotated90 = true,
                zoomMultiplier = "1.5×",
                resetEnabled = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
