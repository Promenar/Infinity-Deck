package com.promenar.infinity.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.AgentAvatar
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/*
 * 对话列表首页「智能体」展开后子会话树线与选中背景重叠 BUG 的隔离对比原型（v1）：
 * - 现状（ProtoAgentSessionsBuggy）：竖线在 36dp，子会话容器只缩进 24dp 且行无圆角直接铺满整行选中色，
 *   drawContent() 后画线导致蓝线劈穿选中蓝色块。
 * - 修复方案 v1（ProtoAgentSessionsFixed）：竖线保持 36dp（精准对齐上方 40dp 头像圆心），
 *   子会话列表外移至线右（start = 46dp, end = 8dp, bottom = 6dp），每行裁成 10dp 圆角胶囊 + 12dp 行内留白，
 *   竖线与选中背景完全分离且不浪费窄列文字空间。
 */

private data class ProtoSessionSample(
    val id: String,
    val title: String,
    val time: String,
    val isBranch: Boolean = false,
    val nested: Boolean = false,
    val selected: Boolean = false,
)

private val ProtoPhoneSamples = listOf(
    ProtoSessionSample("s1", "H3视频模板校验通过", "11/20"),
    ProtoSessionSample("s2", "前后台任务执行", "11/20"),
    ProtoSessionSample("s3", "本地落后远端六提交", "11/19"),
    ProtoSessionSample("s4", "陆昭晨间问候 20s 测试片：素材盘点与制作方案", "08:48", isBranch = true, selected = true),
)

private val ProtoBranchHierarchySamples = listOf(
    ProtoSessionSample("s1", "H3视频模板校验通过", "11/20", selected = true),
    ProtoSessionSample("s2", "分支：H3 107 帧首镜调优", "09:12", isBranch = true, nested = true),
    ProtoSessionSample("s3", "前后台任务执行", "11/20"),
    ProtoSessionSample("s4", "陆昭晨间问候 20s 测试片", "08:48", isBranch = true),
)

private val ProtoNestedSelectedSamples = listOf(
    ProtoSessionSample("s1", "H3视频模板校验通过", "11/20"),
    ProtoSessionSample("s2", "分支：H3 107 帧首镜调优", "09:12", isBranch = true, nested = true, selected = true),
    ProtoSessionSample("s3", "前后台任务执行", "11/20"),
)

@Composable
private fun ProtoAgentHeader(
    id: String,
    name: String,
    icon: String,
    color: String,
    sessionCount: Int,
    expanded: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = InfinitySpacing.RowTwoLineHeight)
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            AgentAvatar(icon = icon, color = color, seed = id, name = name, size = 40.dp)
        }
        Spacer(Modifier.width(InfinitySpacing.Medium))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.height(InfinitySpacing.RowTwoLineGap))
            Text(
                text = "$sessionCount 个会话",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(InfinitySpacing.Medium))
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .rotate(if (expanded) 180f else 0f),
        )
    }
}

@Composable
private fun ProtoSessionItem(
    sample: ProtoSessionSample,
    horizontalPadding: Dp,
    roundedSelection: Boolean,
) {
    val selectedColor = MaterialTheme.colorScheme.secondaryContainer
    val rowShape = RoundedCornerShape(10.dp)
    val selectionModifier = when {
        !sample.selected -> Modifier
        roundedSelection -> Modifier.clip(rowShape).background(selectedColor)
        else -> Modifier.background(selectedColor)
    }
    InfinitySettingsRowLayout(
        title = sample.title,
        subtitle = null,
        singleLineTitle = true,
        horizontalPadding = horizontalPadding,
        modifier = selectionModifier,
        leadingContent = if (!sample.isBranch) null else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sample.nested) Spacer(Modifier.width(InfinitySpacing.Medium))
                    Icon(
                        imageVector = Icons.Rounded.AccountTree,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        trailingContent = {
            Text(
                text = sample.time,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        },
    )
}

@Composable
private fun ProtoAgentSessionsBuggy(samples: List<ProtoSessionSample>) {
    val guideColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    Column(
        Modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val lineX = (InfinitySpacing.RowHorizontal + 20.dp).toPx()
                val insetY = 6.dp.toPx()
                if (size.height > insetY * 2) {
                    drawLine(
                        color = guideColor,
                        start = Offset(lineX, insetY),
                        end = Offset(lineX, size.height - insetY),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            .padding(start = 24.dp),
    ) {
        samples.forEach { sample ->
            ProtoSessionItem(
                sample = sample,
                horizontalPadding = InfinitySpacing.RowHorizontal,
                roundedSelection = false,
            )
        }
    }
}

@Composable
private fun ProtoAgentSessionsFixed(samples: List<ProtoSessionSample>) {
    val guideColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    Column(
        Modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val lineX = (InfinitySpacing.RowHorizontal + 20.dp).toPx()
                val topInset = 4.dp.toPx()
                val bottomInset = 10.dp.toPx()
                if (size.height > topInset + bottomInset) {
                    drawLine(
                        color = guideColor,
                        start = Offset(lineX, topInset),
                        end = Offset(lineX, size.height - bottomInset),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            .padding(start = 46.dp, end = 8.dp, bottom = 6.dp),
    ) {
        samples.forEach { sample ->
            ProtoSessionItem(
                sample = sample,
                horizontalPadding = 12.dp,
                roundedSelection = true,
            )
        }
    }
}

@Composable
private fun ProtoAgentsCard(
    samples: List<ProtoSessionSample>,
    fixed: Boolean,
) {
    InfinitySettingsSection(
        title = "智能体",
        headerStartPadding = InfinitySpacing.ScreenHorizontal,
    ) {
        ProtoAgentHeader(
            id = "infinity",
            name = "Infinity",
            icon = "sym:auto_awesome",
            color = "#6366F1",
            sessionCount = samples.size,
            expanded = true,
        )
        if (fixed) {
            ProtoAgentSessionsFixed(samples)
        } else {
            ProtoAgentSessionsBuggy(samples)
        }
        ProtoAgentHeader(
            id = "nian-001",
            name = "念-001",
            icon = "sym:palette",
            color = "#EC4899",
            sessionCount = 2,
            expanded = false,
        )
    }
}

@Composable
private fun ProtoCaptionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal),
    )
}

@Composable
private fun ProtoHost(mode: InfinityThemeMode, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = mode,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                content()
            }
        }
    }
}

@Preview(name = "1. 折叠屏左栏宽(310dp) 深色前后对比", widthDp = 310, heightDp = 960, locale = "zh-rCN")
@Composable
fun hubGuideBeforeAfterDarkPreview() = ProtoHost(InfinityThemeMode.DARK) {
    ProtoCaptionLabel("修改前：竖线穿过选中背景")
    ProtoAgentsCard(ProtoPhoneSamples, fixed = false)

    Spacer(Modifier.height(4.dp))

    ProtoCaptionLabel("修改后 v1：竖线与 10dp 圆角选中态分离")
    ProtoAgentsCard(ProtoPhoneSamples, fixed = true)
}

@Preview(name = "2. 标准竖屏(360dp) 普通行与分支嵌套行选中态", widthDp = 360, heightDp = 860, locale = "zh-rCN")
@Composable
fun hubGuideStatesDarkPreview() = ProtoHost(InfinityThemeMode.DARK) {
    ProtoCaptionLabel("常规会话选中 + 子分支缩进")
    ProtoAgentsCard(ProtoBranchHierarchySamples, fixed = true)

    Spacer(Modifier.height(4.dp))

    ProtoCaptionLabel("嵌套分支会话选中态")
    ProtoAgentsCard(ProtoNestedSelectedSamples, fixed = true)
}

@Preview(name = "3. 浅色 + 310dp窄屏 + 1.3x大字号", widthDp = 310, heightDp = 820, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun hubGuideLightLargeFontPreview() = ProtoHost(InfinityThemeMode.LIGHT) {
    ProtoCaptionLabel("浅色窄屏 1.3x 大字号验证")
    ProtoAgentsCard(ProtoPhoneSamples, fixed = true)
}
