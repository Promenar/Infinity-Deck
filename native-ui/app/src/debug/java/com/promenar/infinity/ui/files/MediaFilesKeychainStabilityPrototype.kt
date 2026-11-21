package com.promenar.infinity.ui.files

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.ComparisonBlock
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.NarrowLightVerificationFrame
import com.promenar.infinity.ui.common.PrototypeBoardHeader
import com.promenar.infinity.ui.common.PrototypeBoardSurface
import com.promenar.infinity.ui.common.PrototypeChip
import com.promenar.infinity.ui.common.PrototypeChipTone
import com.promenar.infinity.ui.common.StateTransitionBanner
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 全站「非常驻 UI 与布局跳变」治理 — 批次 4（ui/media/ + ui/files/ + ui/keychain/ D1–D7，2 张看板）高保真对比原型。
 * 隔离在 debug 源集，供 Android Compose Preview 审阅，不修改 src/main/ 正式代码。
 */

// =====================================================================
// D1: WorkspaceVideoCard 播控条与错误覆层
// =====================================================================

@Composable
private fun SampleVideoCard(playing: Boolean, overlayControls: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val overlayFg = MaterialTheme.infinityDomainColors.overlayContent
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .background(scheme.surfaceContainerHighest),
            ) {
                if (!playing) {
                    Box(Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape).background(scheme.scrim.copy(alpha = 0.65f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "播放", modifier = Modifier.size(22.dp), tint = overlayFg)
                    }
                    Surface(shape = CircleShape, color = scheme.scrim.copy(alpha = 0.72f), modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                        Text("视频 · 00:24", style = MaterialTheme.typography.labelSmall, color = overlayFg, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) // wrap-check: allow 原型单行样例
                    }
                } else if (overlayControls) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(scheme.scrim.copy(alpha = 0.72f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(16.dp), tint = overlayFg)
                        LinearProgressIndicator(progress = { 0.45f }, modifier = Modifier.weight(1f))
                        Text("00:11", style = MaterialTheme.typography.labelSmall, color = overlayFg, maxLines = 1)
                        Icon(Icons.Rounded.Fullscreen, contentDescription = "全屏", modifier = Modifier.size(16.dp), tint = overlayFg)
                    }
                }
            }
            if (playing && !overlayControls) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = { 0.45f }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(16.dp), tint = scheme.onSurface)
                            Text("00:11 / 00:24", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                        }
                        Icon(Icons.Rounded.Fullscreen, contentDescription = "全屏", modifier = Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// =====================================================================
// D2: WorkspaceAudioCard 播放键与单/双行控制区
// =====================================================================

@Composable
private fun SampleAudioCard(playing: Boolean, unifiedSingleRowLayout: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (unifiedSingleRowLayout || !playing) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "播放",
                            modifier = Modifier.size(18.dp),
                            tint = scheme.onPrimaryContainer,
                        )
                    }
                } else {
                    Spacer(Modifier.size(36.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("scene_02_dialogue.wav", style = MaterialTheme.typography.bodySmall, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    InfinityMetaLine(segments = listOf("音频", if (playing) "00:08 / 00:19" else "00:19"), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
            Slider(value = if (playing) 0.42f else 0f, onValueChange = {}, modifier = Modifier.fillMaxWidth().height(24.dp))
            if (!unifiedSingleRowLayout && playing) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(16.dp), tint = scheme.primary)
                    Text("00:08 / 00:19", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

// =====================================================================
// D3: FullscreenVideoDialog 控制浮层
// =====================================================================

@Composable
private fun SampleFullscreenOverlayCard(afterFadeSlide: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().height(92.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(8.dp)) {
            PrototypeChip(if (afterFadeSlide) "零占位跳变 · fadeIn + slideIn" else "点屏幕瞬显瞬隐", if (afterFadeSlide) PrototypeChipTone.SUCCESS else PrototypeChipTone.WARNING, Modifier.align(Alignment.TopStart))
            Icon(Icons.Rounded.Close, contentDescription = "关闭", modifier = Modifier.align(Alignment.TopEnd).size(18.dp), tint = scheme.onSurface)
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(16.dp), tint = scheme.onSurface)
                LinearProgressIndicator(progress = { 0.38f }, modifier = Modifier.weight(1f))
                Text("退出全屏", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

// =====================================================================
// D7: KeychainSshKeySheets busy 独立圆环
// =====================================================================

@Composable
private fun SampleKeychainSheetFooter(busy: Boolean, spinnerInsidePrimary: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("新建 Ed25519 密钥对", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1)
            if (busy && !spinnerInsidePrimary) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("新起一行圆环，按钮下推 36dp", style = MaterialTheme.typography.labelSmall, color = scheme.error, maxLines = 1)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfinityPillButton("取消", onClick = {}, tone = InfinityPillTone.NEUTRAL, enabled = !busy, compact = true, modifier = Modifier.weight(1f))
                if (busy && spinnerInsidePrimary) {
                    Surface(shape = CircleShape, color = scheme.primary.copy(alpha = 0.85f), modifier = Modifier.weight(1f).height(48.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
                            Spacer(Modifier.size(6.dp))
                            Text("生成中", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary, maxLines = 1)
                        }
                    }
                } else {
                    InfinityPillButton("生成", onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// =====================================================================
// D4: FileManagerHeader 浏览 / 搜索（含筛选芯片）/ 多选顶栏
// =====================================================================

@Composable
private fun SampleFileTopBar(mode: Int, afterTag: Boolean) { // 0=浏览, 1=搜索+筛选, 2=多选
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (mode) {
                    0 -> {
                        Text("工作区", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
                        Icon(Icons.Rounded.Search, contentDescription = "搜索文件", modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                        Icon(Icons.Rounded.MoreVert, contentDescription = "更多选项", modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                    }
                    1 -> {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "退出搜索", modifier = Modifier.size(18.dp), tint = scheme.onSurface)
                        Text("在工作区按文件名搜索", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                        Icon(Icons.Rounded.Close, contentDescription = "清除搜索", modifier = Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                    }
                    else -> {
                        Icon(Icons.Rounded.Close, contentDescription = "退出选择", modifier = Modifier.size(18.dp), tint = scheme.onSurface)
                        Text("已选 3", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
                        Icon(Icons.Rounded.Share, contentDescription = "分享", modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                        Icon(Icons.Rounded.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp), tint = scheme.error)
                    }
                }
            }
            if (mode == 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrototypeChip("整个位置", PrototypeChipTone.INFO)
                    PrototypeChip("类型", PrototypeChipTone.NEUTRAL)
                    PrototypeChip("修改时间", PrototypeChipTone.NEUTRAL)
                    if (afterTag) {
                        PrototypeChip("二级槽滑动", PrototypeChipTone.SUCCESS)
                    }
                }
            }
        }
    }
}

// =====================================================================
// D5: FileManagerPane 面包屑 / GitStrip / FAB / 搜索加载条
// =====================================================================

@Composable
private fun SampleFilePaneStrips(showSubStrips: Boolean, overlaySearchBar: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showSubStrips) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Nexara / native-ui / app", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                    PrototypeChip("按名称", PrototypeChipTone.NEUTRAL)
                }
                Surface(shape = RoundedCornerShape(10.dp), color = domain.successContainer.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.AccountTree, contentDescription = null, modifier = Modifier.size(15.dp), tint = domain.onSuccessContainer)
                            Text("3 处改动", style = MaterialTheme.typography.labelMedium, color = domain.onSuccessContainer, maxLines = 1)
                        }
                        Text("查看变更", style = MaterialTheme.typography.labelMedium, color = domain.onSuccessContainer, maxLines = 1)
                    }
                }
            } else {
                if (overlaySearchBar) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("占首行挤下命中结果", style = MaterialTheme.typography.labelSmall, color = scheme.error, maxLines = 1)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (showSubStrips) "12 个文件夹 · 48 个文件" else "命中 9 个匹配文件", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1) // wrap-check: allow 原型单行样例
                if (showSubStrips) {
                    Surface(shape = RoundedCornerShape(12.dp), color = scheme.primaryContainer) {
                        Icon(Icons.Rounded.Add, contentDescription = "新建或导入", modifier = Modifier.padding(6.dp).size(16.dp), tint = scheme.onPrimaryContainer)
                    }
                }
            }
        }
    }
}

// =====================================================================
// D6: FileManagerItems 进入多选行尾 48dp 缩为 8dp
// =====================================================================

@Composable
private fun SampleFileItemRow(selecting: Boolean, keepTrailing48dpSlot: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selecting) scheme.primaryContainer.copy(alpha = 0.28f) else scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(
                    if (selecting) Icons.Rounded.CheckBox else Icons.Rounded.Description,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (selecting) scheme.primary else scheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Text("ProductionPrevisViewport.kt", style = MaterialTheme.typography.bodySmall, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                InfinityMetaLine(segments = listOf("18.4 KB", "12 分钟前"), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            PrototypeChip("已修改", PrototypeChipTone.WARNING)
            if (!selecting || keepTrailing48dpSlot) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .alpha(if (selecting) 0f else 1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "文件操作", modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                }
            } else {
                Spacer(Modifier.size(width = 8.dp, height = 40.dp))
            }
        }
    }
}

// =====================================================================
// 批次 4 两张 Compose Preview 看板
// =====================================================================

@Preview(name = "B4-1 媒体卡片与密钥弹窗跳变对比 (深色)", widthDp = 820, heightDp = 1720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun mediaAndKeychainBatch4Preview() {
    PrototypeBoardSurface(dark = true) {
        PrototypeBoardHeader(
            batchBadge = "批次 4 · 1/2",
            title = "媒体与密钥 ui/media/ · ui/keychain/ (D1/D2/D3/D7)",
            subtitle = "左：现状无动画硬插与控制区跳行 ｜ 右：建议方案",
        )
        ComparisonBlock(
            code = "D1",
            title = "WorkspaceVideoCard 播控条与错误覆层",
            sourceRef = "WorkspaceMediaCards.kt:282-332",
            beforeSummary = "点击播放直接下推两排播控条，撑高聊天消息卡",
            afterSummary = "改为画框底部叠层播控（零高度跳变）或平滑折叠",
            beforeContent = {
                StateTransitionBanner("未播：纯 16:9 画框")
                SampleVideoCard(playing = false, overlayControls = false)
                StateTransitionBanner("播放中：画框下方硬插双排控件推下消息流")
                SampleVideoCard(playing = true, overlayControls = false)
            },
            afterContent = {
                StateTransitionBanner("未播：居中播放键与角标 fadeIn/fadeOut")
                SampleVideoCard(playing = false, overlayControls = true)
                StateTransitionBanner("播放中：底部半透叠层播控，卡片高度零变化")
                SampleVideoCard(playing = true, overlayControls = true)
            },
        )
        ComparisonBlock(
            code = "D2",
            title = "WorkspaceAudioCard 左上角播放键与底部单双行突变",
            sourceRef = "WorkspaceMediaCards.kt:392-444",
            beforeSummary = "起播左上变空白占位，底部滑块突增双行控件",
            afterSummary = "左上常驻播/停键 + 底部恒为单行进度，零跳变",
            beforeContent = {
                SampleAudioCard(playing = false, unifiedSingleRowLayout = false)
                SampleAudioCard(playing = true, unifiedSingleRowLayout = false)
            },
            afterContent = {
                SampleAudioCard(playing = false, unifiedSingleRowLayout = true)
                SampleAudioCard(playing = true, unifiedSingleRowLayout = true)
            },
        )
        ComparisonBlock(
            code = "D3",
            title = "FullscreenVideoDialog 全屏控制浮层显隐",
            sourceRef = "WorkspaceMediaPlayers.kt:261-308",
            beforeSummary = "点击画面硬开关关闭键与底条（无布局影响仅缺过渡）",
            afterSummary = "补 fadeIn/fadeOut 与底部 slideInVertically",
            beforeContent = { SampleFullscreenOverlayCard(afterFadeSlide = false) },
            afterContent = { SampleFullscreenOverlayCard(afterFadeSlide = true) },
        )
        ComparisonBlock(
            code = "D7",
            title = "KeychainSshKeySheets 提交 busy 独立圆环跳变",
            sourceRef = "KeychainSshKeySheets.kt:177, 258, 331, 371",
            beforeSummary = "4 处提交态上方起行插 24dp 圆环推下按钮 36dp",
            afterSummary = "加载圈内嵌进主按钮（对齐图谱弹窗），零跳高",
            beforeContent = {
                SampleKeychainSheetFooter(busy = false, spinnerInsidePrimary = false)
                SampleKeychainSheetFooter(busy = true, spinnerInsidePrimary = false)
            },
            afterContent = {
                SampleKeychainSheetFooter(busy = false, spinnerInsidePrimary = true)
                SampleKeychainSheetFooter(busy = true, spinnerInsidePrimary = true)
            },
        )
    }
}

@Preview(name = "B4-2 文件管理跳变对比与 360dp 浅色 1.3x 验收", widthDp = 820, heightDp = 1680, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun filesBatch4Preview() {
    PrototypeBoardSurface(dark = true) {
        PrototypeBoardHeader(
            batchBadge = "批次 4 · 2/2",
            title = "文件管理 ui/files/ (D4/D5/D6) · 含 360dp 浅色大字号验证",
            subtitle = "消除三态顶栏硬切、多选行标题集体右跳 40dp 与搜索首行挤占",
        )
        ComparisonBlock(
            code = "D4",
            title = "FileManagerHeader 浏览 / 搜索（含芯片）/ 多选切换",
            sourceRef = "FileManagerHeader.kt:121-127",
            beforeSummary = "三态硬切无过渡，进搜索突增筛选芯片推下列表",
            afterSummary = "主顶栏定高 fadeThrough，筛选芯片二级槽平滑展开",
            beforeContent = {
                SampleFileTopBar(mode = 0, afterTag = false)
                SampleFileTopBar(mode = 1, afterTag = false)
                SampleFileTopBar(mode = 2, afterTag = false)
            },
            afterContent = {
                SampleFileTopBar(mode = 0, afterTag = true)
                SampleFileTopBar(mode = 1, afterTag = true)
                SampleFileTopBar(mode = 2, afterTag = true)
            },
        )
        ComparisonBlock(
            code = "D5",
            title = "FileManagerPane 面包屑 / GitStrip / FAB / 搜索加载",
            sourceRef = "FileManagerPane.kt:293-333, 457-462, 644-650",
            beforeSummary = "进出子目录/多选硬插删条块，搜索加载挤首行",
            afterSummary = "面包屑/Git 用 expandIn，FAB 缩放，加载改顶缘细条",
            beforeContent = {
                SampleFilePaneStrips(showSubStrips = true, overlaySearchBar = false)
                SampleFilePaneStrips(showSubStrips = false, overlaySearchBar = false)
            },
            afterContent = {
                SampleFilePaneStrips(showSubStrips = true, overlaySearchBar = true)
                SampleFilePaneStrips(showSubStrips = false, overlaySearchBar = true)
            },
        )
        ComparisonBlock(
            code = "D6",
            title = "FileManagerItems 进入多选行尾 48dp 菜单缩为 8dp",
            sourceRef = "FileManagerItems.kt:481-485",
            beforeSummary = "进多选尾部缩 40dp，整屏文件标题集体右跳重排",
            afterSummary = "保留尾部 48dp 槽位淡隐禁用（0dp 横跳）",
            beforeContent = {
                StateTransitionBanner("浏览态：尾部 48dp 更多菜单占位")
                SampleFileItemRow(selecting = false, keepTrailing48dpSlot = false)
                StateTransitionBanner("多选态：缩为 8dp，文件名右扩 40dp 集体重排")
                SampleFileItemRow(selecting = true, keepTrailing48dpSlot = false)
            },
            afterContent = {
                StateTransitionBanner("浏览态：固定 48dp 行尾操作槽")
                SampleFileItemRow(selecting = false, keepTrailing48dpSlot = true)
                StateTransitionBanner("多选态：原槽淡隐禁用，整行文字与徽章零位移")
                SampleFileItemRow(selecting = true, keepTrailing48dpSlot = true)
            },
        )
        NarrowLightVerificationFrame(title = "360dp 窄屏 · 浅色主题 · 1.3x 大字号验证 (D2 / D6 / D7)") {
            SampleAudioCard(playing = true, unifiedSingleRowLayout = true)
            HorizontalDivider()
            SampleFileItemRow(selecting = true, keepTrailing48dpSlot = true)
            SampleKeychainSheetFooter(busy = true, spinnerInsidePrimary = true)
        }
    }
}
