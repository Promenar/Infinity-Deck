package com.promenar.infinity.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityObsidianSolidChip
import com.promenar.infinity.ui.theme.InfinityObsidianSolidContainer
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 全站悬浮层、弹窗、弹出面板与审批卡熏黑主题化原型（v1，隔离于 debug 源码）：
 *
 * 4 级黑曜石纵深阶梯（彻底替换深色下发灰的 MD3 surfaceContainer*）：
 * - L0 纯黑画布：#000000
 * - L1 固定侧边栏 / 通用底部弹层 / 居中弹出面板：#070709 + 12% 细白描边 + 14% 顶缘镜面高光（内嵌卡 #111217）
 * - L2 对话框 / Popover（模型、上下文）/ 审批卡：#0A0B0F + 13% 细白描边 + 15% 顶缘镜面高光（内嵌块 #13151C）
 * - L3 悬浮菜单（审批模式、思考强度、附件、更多）：#0E1016 + 14% 细白描边 + 16% 顶缘高光（选中项 #191C26）
 * 浅色主题同步保持通透冷白容器 + 清晰细描边，不受负面影响。
 */

private val ObsidianL1Surface = InfinityObsidianSolidContainer // #070709
private val ObsidianL1Chip = InfinityObsidianSolidChip         // #111217
private val ObsidianL2Surface = Color(0xFF0A0B0F)
private val ObsidianL2Chip = Color(0xFF13151C)
private val ObsidianL3Menu = Color(0xFF0E1016)
private val ObsidianL3Selected = Color(0xFF191C26)

@Composable
private fun OverlayProtoTheme(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        darkTheme = dark,
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
        content = content,
    )
}

/** 统一熏黑浮层壳：外描边 + 顶缘 1dp 镜面高光 + 清晰分层底色。 */
@Composable
private fun ObsidianSurfaceShell(
    dark: Boolean,
    level: Int, // 1 = Sheet/Panel, 2 = Dialog/Popover/Card, 3 = DropdownMenu
    cornerRadius: Dp,
    bottomFlat: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = if (bottomFlat) {
        RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius, bottomStart = 0.dp, bottomEnd = 0.dp)
    } else {
        RoundedCornerShape(cornerRadius)
    }
    val container = if (dark) {
        when (level) {
            1 -> ObsidianL1Surface
            2 -> ObsidianL2Surface
            else -> ObsidianL3Menu
        }
    } else {
        when (level) {
            1 -> scheme.surfaceContainerLowest
            2 -> scheme.surfaceContainerLowest
            else -> scheme.surfaceContainerLowest
        }
    }
    val borderAlpha = when (level) {
        1 -> 0.12f
        2 -> 0.13f
        else -> 0.14f
    }
    val glintAlpha = when (level) {
        1 -> 0.14f
        2 -> 0.15f
        else -> 0.16f
    }
    val borderColor = if (dark) InfinityPureWhite.copy(alpha = borderAlpha) else scheme.outlineVariant.copy(alpha = 0.58f)
    val glintColor = if (dark) InfinityPureWhite.copy(alpha = glintAlpha) else InfinityPureWhite.copy(alpha = 0.9f)

    Surface(
        modifier = modifier,
        shape = shape,
        color = container,
        contentColor = scheme.onSurface,
        border = BorderStroke(if (level == 3) 0.75.dp else 1.dp, borderColor),
        shadowElevation = when (level) {
            1 -> 10.dp
            2 -> 12.dp
            else -> 14.dp
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    val inset = cornerRadius.toPx()
                    val stroke = 0.75.dp.toPx()
                    drawLine(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.18f to glintColor,
                            0.82f to glintColor,
                            1f to Color.Transparent,
                        ),
                        start = Offset(inset * 0.45f, stroke / 2f),
                        end = Offset(size.width - inset * 0.45f, stroke / 2f),
                        strokeWidth = stroke,
                    )
                },
            content = content,
        )
    }
}

@Composable
private fun ObsidianMenuRow(
    dark: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    accent: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val rowBg = if (selected) {
        if (dark) ObsidianL3Selected else scheme.primaryContainer.copy(alpha = 0.45f)
    } else Color.Transparent
    val iconColor = when {
        accent != null -> accent
        selected -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(rowBg)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = InfinityTypography.bodyMedium.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                color = scheme.onSurface,
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(subtitle, style = InfinityTypography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
        }
    }
}

/** 熏黑审批卡原型（替代原 MD3 surfaceContainerLow 灰卡）。 */
@Composable
private fun ProtoObsidianApprovalCard(dark: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val warning = MaterialTheme.infinityDomainColors.warning
    val codeBg = if (dark) ObsidianL2Chip else scheme.surfaceContainerLow
    val divider = if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.45f)

    ObsidianSurfaceShell(dark = dark, level = 2, cornerRadius = 20.dp, modifier = modifier) {
        // 顶部语义风险色带：深色采用低饱和通透琥珀雾底，避免刺眼灰块
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(warning.copy(alpha = if (dark) 0.12f else 0.14f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Terminal, contentDescription = null, tint = warning, modifier = Modifier.size(16.dp))
            Text("终端命令 · 需要审批", style = InfinityTypography.labelLarge, color = warning, modifier = Modifier.weight(1f))
            Surface(shape = CircleShape, color = warning.copy(alpha = if (dark) 0.18f else 0.22f)) {
                Text(
                    "工作区修改",
                    style = InfinityTypography.labelSmall,
                    color = warning,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        HorizontalDivider(color = divider)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("shell_exec", style = InfinityTypography.titleSmall, color = scheme.onSurface)
                Text("验证全量静态检查与截图基线回归", style = InfinityTypography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = codeBg,
                border = BorderStroke(0.5.dp, divider),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "./gradlew :app:validateSideloadDebugScreenshotTest",
                    style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace),
                    color = scheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {},
                    border = BorderStroke(1.dp, if (dark) InfinityPureWhite.copy(alpha = 0.16f) else scheme.outlineVariant),
                    modifier = Modifier.weight(1f).height(40.dp),
                ) {
                    Text("拒绝", style = InfinityTypography.labelLarge)
                }
                Button(
                    onClick = {},
                    modifier = Modifier.weight(1.3f).height(40.dp),
                ) {
                    Text("批准执行", style = InfinityTypography.labelLarge)
                }
            }
        }
    }
}

/** 熏黑上下文小面板原型（InfinityPopover）。 */
@Composable
private fun ProtoObsidianContextPopover(dark: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val innerBg = if (dark) ObsidianL2Chip else scheme.surfaceContainerLow
    val divider = if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.45f)
    val trackColor = if (dark) InfinityPureWhite.copy(alpha = 0.10f) else scheme.outlineVariant.copy(alpha = 0.45f)

    ObsidianSurfaceShell(dark = dark, level = 2, cornerRadius = 18.dp, modifier = modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                        drawArc(trackColor, 0f, 360f, false, style = stroke)
                        drawArc(scheme.primary, -90f, 360f * 0.42f, false, style = stroke)
                    }
                    Text("42%", style = InfinityTypography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = scheme.onSurface)
                }
                Column(Modifier.weight(1f)) {
                    Text("上下文用量", style = InfinityTypography.titleSmall, color = scheme.onSurface)
                    Text("84.2K / 200K tokens · 剩余 115.8K", style = InfinityTypography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            // 分色构成条
            Row(
                Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(trackColor),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(Modifier.fillMaxWidth(0.18f).height(6.dp).background(scheme.primary))
                Box(Modifier.fillMaxWidth(0.28f).height(6.dp).background(scheme.tertiary))
                Box(Modifier.fillMaxWidth(0.24f).height(6.dp).background(MaterialTheme.infinityDomainColors.info))
            }
            Surface(shape = RoundedCornerShape(12.dp), color = innerBg, border = BorderStroke(0.5.dp, divider)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("系统提示与规则" to "14.6K", "工具定义" to "21.8K", "会话消息历史" to "47.8K").forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(k, style = InfinityTypography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(v, style = InfinityTypography.labelMedium.copy(fontFamily = InfinityMonospace), color = scheme.onSurface)
                        }
                    }
                }
            }
            FilledTonalButton(
                onClick = {},
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (dark) ObsidianL3Selected else scheme.secondaryContainer,
                    contentColor = scheme.onSurface,
                ),
                modifier = Modifier.fillMaxWidth().height(38.dp),
            ) {
                Icon(Icons.Rounded.Compress, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("立即压缩上下文", style = InfinityTypography.labelLarge)
            }
        }
    }
}

/** 熏黑模型小菜单原型（InfinityPopover）。 */
@Composable
private fun ProtoObsidianModelPopover(dark: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val divider = if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.45f)

    ObsidianSurfaceShell(dark = dark, level = 2, cornerRadius = 18.dp, modifier = modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "切换会话模型",
                style = InfinityTypography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            ObsidianMenuRow(dark, Icons.Rounded.AutoAwesome, "Gemini 2.5 Pro", "推理 · 200K 上下文", selected = true)
            ObsidianMenuRow(dark, Icons.Rounded.AutoAwesome, "Claude Opus 4.5", "高阶架构 · 工具调用", selected = false)
            HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = divider)
            ObsidianMenuRow(dark, Icons.Rounded.AutoAwesome, "DeepSeek V4.1 Flash", "高速响应 · 低成本", selected = false)
        }
    }
}

/** 熏黑全站对话框原型（InfinityConfirmDialog，支持常规与危险双态）。 */
@Composable
private fun ProtoObsidianConfirmDialog(
    dark: Boolean,
    destructive: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (destructive) scheme.error else scheme.primary
    val icon = if (destructive) Icons.Rounded.WarningAmber else Icons.Rounded.VerifiedUser

    ObsidianSurfaceShell(dark = dark, level = 2, cornerRadius = 24.dp, modifier = modifier) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(accent.copy(alpha = if (dark) 0.16f else 0.12f), RoundedCornerShape(12.dp))
                    .border(0.5.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = if (destructive) "删除当前会话与工作区？" else "切换为熏黑统一主题？",
                style = InfinityTypography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (destructive) "此操作将永久移除 24 条消息记录与本地临时产物，且无法恢复。" else "确认后将把全站弹窗、菜单与侧边栏同步收敛至黑曜石熏黑材质。",
                style = InfinityTypography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {},
                    border = BorderStroke(1.dp, if (dark) InfinityPureWhite.copy(alpha = 0.16f) else scheme.outlineVariant),
                    modifier = Modifier.weight(1f).height(42.dp),
                ) {
                    Text("取消", style = InfinityTypography.labelLarge)
                }
                Button(
                    onClick = {},
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (destructive) scheme.error else scheme.primary,
                        contentColor = if (destructive) scheme.onError else scheme.onPrimary,
                    ),
                    modifier = Modifier.weight(1f).height(42.dp),
                ) {
                    Text(if (destructive) "确认删除" else "立即应用", style = InfinityTypography.labelLarge)
                }
            }
        }
    }
}

/** 熏黑全站弹出面板原型（InfinityCenteredSheet 宽屏居中面板 & InfinityBottomSheet 窄屏底部弹层）。 */
@Composable
private fun ProtoObsidianModalPanel(
    dark: Boolean,
    asBottomSheet: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val innerBg = if (dark) ObsidianL1Chip else scheme.surfaceContainerLow
    val divider = if (dark) InfinityPureWhite.copy(alpha = 0.08f) else scheme.outlineVariant.copy(alpha = 0.45f)

    ObsidianSurfaceShell(
        dark = dark,
        level = 1,
        cornerRadius = if (asBottomSheet) 24.dp else 26.dp,
        bottomFlat = asBottomSheet,
        modifier = modifier,
    ) {
        if (asBottomSheet) {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(38.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (dark) InfinityPureWhite.copy(alpha = 0.24f) else scheme.outlineVariant),
                )
            }
        } else {
            Spacer(Modifier.height(18.dp))
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (asBottomSheet) "运行模式与权限面板（底部弹层）" else "会话配置弹出面板（宽屏居中面板）",
                style = InfinityTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
            )
            Surface(shape = RoundedCornerShape(16.dp), color = innerBg, border = BorderStroke(0.5.dp, divider)) {
                Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ObsidianMenuRow(dark, Icons.Rounded.Security, "本机工作区模式", "隔离读写会话目录，高危命令需审批", selected = true)
                    HorizontalDivider(Modifier.padding(horizontal = 10.dp), color = divider)
                    ObsidianMenuRow(dark, Icons.Rounded.Terminal, "Linux 容器环境", "独立沙箱终端，支持完整构建工具链", selected = false)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// =========================================================================
// Preview B1: 会话内全部熏黑浮层组件（审批卡 + 3 类悬浮小菜单 + 模型/上下文 Popover）
// =========================================================================

@Preview(name = "B1-会话熏黑组件全家桶-审批卡与全部小菜单", widthDp = 960, heightDp = 640, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun ObsidianChatOverlaysDarkPreview() {
    OverlayProtoTheme(dark = true) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF000000)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "熏黑主题化 · 会话内全部浮层与审批卡（深色 4 级黑曜石纵深，彻底移除 MD3 浅灰底）",
                style = InfinityTypography.titleSmall,
                color = InfinityPureWhite,
            )

            // 上半区：审批卡 + 模型 Popover + 上下文 Popover
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("审批卡 · ApprovalCard (L2 #0A0B0F)", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProtoObsidianApprovalCard(dark = true, modifier = Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(0.95f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("上下文小面板 · InfinityPopover (L2 #0A0B0F)", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProtoObsidianContextPopover(dark = true, modifier = Modifier.fillMaxWidth())
                }
            }

            // 下半区：模型小菜单 + 审批模式菜单 + 思考强度小菜单 + 附件小菜单
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("模型小菜单 · InfinityPopover (L2)", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProtoObsidianModelPopover(dark = true, modifier = Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("审批模式菜单 · Dropdown (L3 #0E1016)", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ObsidianSurfaceShell(dark = true, level = 3, cornerRadius = 14.dp) {
                        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            ObsidianMenuRow(true, Icons.Rounded.Shield, "谨慎审批", "高危写操作需确认", selected = true)
                            ObsidianMenuRow(true, Icons.Rounded.VerifiedUser, "自动放行", "工作区内常规命令免确认", selected = false)
                            ObsidianMenuRow(true, Icons.Rounded.Security, "只读模式", "禁止修改文件与执行命令", selected = false)
                        }
                    }
                }
                Column(Modifier.weight(0.85f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("思考强度小菜单 · Dropdown (L3)", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ObsidianSurfaceShell(dark = true, level = 3, cornerRadius = 14.dp) {
                        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            ObsidianMenuRow(true, Icons.Rounded.Lightbulb, "高强度思考", selected = true)
                            ObsidianMenuRow(true, Icons.Rounded.Lightbulb, "中等思考", selected = false)
                            ObsidianMenuRow(true, Icons.Rounded.Lightbulb, "轻度思考", selected = false)
                            ObsidianMenuRow(true, Icons.Rounded.Tune, "关闭思考", selected = false)
                        }
                    }
                }
                Column(Modifier.weight(0.85f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("附件小菜单 · Dropdown (L3)", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ObsidianSurfaceShell(dark = true, level = 3, cornerRadius = 14.dp) {
                        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            ObsidianMenuRow(true, Icons.Rounded.Image, "从相册选择", selected = false)
                            ObsidianMenuRow(true, Icons.Rounded.CameraAlt, "拍照上传", selected = false)
                            ObsidianMenuRow(true, Icons.Rounded.AttachFile, "选择本地文件", selected = false)
                            ObsidianMenuRow(true, Icons.Rounded.FolderOpen, "引用工作区文件", selected = false)
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// Preview B2: 全站弹出对话框（常规/危险）+ 弹出面板（宽屏居中 / 窄屏底部）
// =========================================================================

@Preview(name = "B2-全站熏黑对话框与弹出面板总览", widthDp = 920, heightDp = 620, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun ObsidianDialogsAndModalPanelsDarkPreview() {
    OverlayProtoTheme(dark = true) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF000000)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "熏黑主题化 · 全站弹出对话框与弹出面板（暗幕叠层效果）",
                style = InfinityTypography.titleSmall,
                color = InfinityPureWhite,
            )
            // 第一行：常规对话框 + 危险对话框
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("全站弹出对话框 · 常规态 (InfinityConfirmDialog)", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProtoObsidianConfirmDialog(dark = true, destructive = false, modifier = Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("全站弹出对话框 · 危险态 (InfinityConfirmDialog)", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProtoObsidianConfirmDialog(dark = true, destructive = true, modifier = Modifier.fillMaxWidth())
                }
            }
            // 第二行：宽屏居中弹出面板 + 窄屏底部上滑弹出面板
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("宽屏居中弹出面板 (InfinityCenteredSheet · L1 #070709)", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProtoObsidianModalPanel(dark = true, asBottomSheet = false, modifier = Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("通用底部上滑弹出面板 (InfinityBottomSheet · L1 #070709)", style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProtoObsidianModalPanel(dark = true, asBottomSheet = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

// =========================================================================
// Preview B3: 浅色主题冷白通透同步验收（审批卡 / 悬浮菜单 / 对话框 / 弹出面板）
// =========================================================================

@Preview(name = "B3-浅色冷白通透对照-浮层与对话框", widthDp = 920, heightDp = 560, showBackground = true, backgroundColor = 0xFFF4F6FA)
@Composable
fun ObsidianOverlaysAndDialogsLightPreview() {
    OverlayProtoTheme(dark = false) {
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "浅色主题同步对照 · 冷白通透容器与清晰细描边（无深色色阶泄漏）",
                style = InfinityTypography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ProtoObsidianApprovalCard(dark = false, modifier = Modifier.weight(1.1f))
                ProtoObsidianConfirmDialog(dark = false, destructive = false, modifier = Modifier.weight(0.95f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ProtoObsidianModalPanel(dark = false, asBottomSheet = true, modifier = Modifier.weight(1.1f))
                ObsidianSurfaceShell(dark = false, level = 3, cornerRadius = 14.dp, modifier = Modifier.weight(0.95f)) {
                    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ObsidianMenuRow(false, Icons.Rounded.Shield, "谨慎审批", "高危写操作需确认", selected = true)
                        ObsidianMenuRow(false, Icons.Rounded.VerifiedUser, "自动放行", "工作区内常规命令免确认", selected = false)
                        ObsidianMenuRow(false, Icons.Rounded.DeleteOutline, "只读模式", "禁止修改文件与执行命令", selected = false)
                    }
                }
            }
        }
    }
}
