package com.promenar.infinity.ui.production

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.FrostedChromeBackdropLayer
import com.promenar.infinity.ui.common.FrostedChromeMaterial
import com.promenar.infinity.ui.common.FrostedChromeRole
import com.promenar.infinity.ui.common.InfinityMetaSeparator
import com.promenar.infinity.ui.common.InfinityPanePresentation
import com.promenar.infinity.ui.common.InfinityPanelTab
import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.LocalFrostedChromeMaterial
import com.promenar.infinity.ui.common.LocalInfinityPanePresentation
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.common.rememberFrostedChromeMaterial
import com.promenar.infinity.ui.theme.ComposerSurfaceStyle
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotionStyles
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 剪辑空画面占位监视器与剧本限高嵌套滚动原型（v1，隔离于 debug 源码）：
 * 1. ProtoCutPlaceholderPlayer：16:9 监视器无画面态，补充镜号胶囊（第 1/4 镜）、模式状态胶囊（预演占位 / 暂无画面 / 占位连播中）、
 *    镜号 + 镜头名拼接主标题及清晰状态副标题，消除“经过 / 4.5 秒”语义歧义；
 * 2. ProtoCutTimelinePreview：时间轴当前预览镜高亮描边与柔和主色底，呼应上方监视器；
 * 3. ProtoBoundedScriptCard：收起态保持 220dp 静态预览不抢手势；展开态按视口比例限高（约 48% 屏高，300dp~440dp）
 *    并启用框内垂直滚动 + 上下边缘渐隐 + 右侧 3dp 细滚动条，让审批区与下方「场次」始终就近可达。
 */

private enum class CutPolishProtoTab { OVERVIEW, SCRIPT, ASSETS, STORYBOARD, OUTPUT, CUT }

private data class ProtoTimelineShot(
    val shotCode: String,
    val title: String,
    val seconds: Double,
    val dialogue: String,
)

private val ProtoSampleShots = listOf(
    ProtoTimelineShot("SC01-01", "经过", 4.5, "老街糕点铺…"),
    ProtoTimelineShot("SC01-02", "招呼", 5.0, "老街糕点铺掌…"),
    ProtoTimelineShot("SC01-03", "婉拒", 5.2, "陆昭 · 原生"),
    ProtoTimelineShot("SC01-04", "回应", 5.9, "老街…"),
)

private val ProtoSampleScriptMarkdown = """
    ```yaml
    artifact:
      project_id: huaxia-chronicles
      artifact_id: lu-zhao-morning-greeting
      artifact_type: screenplay
      version: 3
      status: review
    ```

    ## 本轮修改要点

    第 2 轮审稿指出两点：旧稿七句过于短促，缺乏清晨老街的呼吸感；同时无需为点心单独拆出道具资产与交接特写，保留包子铺与掌柜在环境内的自然互动即可。

    ---

    ## 第一场 · 日外 · 承天市城东新老街 · 包子铺门前

    **时间**：2099 年清晨，薄雾未散。  
    **人物**：陆昭（灵调科第七特勤组组长）、老街糕点铺掌柜。

    清晨青石老街还笼着一层薄湿水汽，檐角低频悬停的巡检浮标在雾里微微泛红。临街的老字号包子铺刚揭开第一屉竹蒸笼，滚烫白气一股脑涌到门前石阶上，把招牌下的暖黄灯笼洇出柔和光晕。

    **1. 经过（4.5s）**

    陆昭身着哑光黑防风长外套，肩线收得利落，沿着湿润路沿不紧不慢走过铺口。他余光扫过蒸汽缭绕的木案板，脚步并未刻意放慢，只抬腕看了一眼表盘时间。

    > **动作提示**：中远景横移跟拍，突出前景白雾与背景老街纵深，不做人物大特写。

    **2. 招呼（5.0s）**

    掌柜正系着洗旧的白布围裙往竹屉上盖布，一抬头正巧看见陆昭经过，立刻笑着朝蒸笼一让。

    **掌柜**（挥手，笑呵呵）  
    “陆组长早啊！刚出锅的鲜肉大包，带两只路上热乎吃？”

    **3. 婉拒（5.2s）**

    陆昭听见声音停住半步，略微侧过肩，朝掌柜轻轻摆了摆左手。神情温和但不拖泥带水，胸前的执勤编号扣在白雾里只露半边微光。

    **陆昭**（微颔首，语速平稳）  
    “早上还有巡检，先记着，谢了李叔。”

    **4. 回应（5.9s）**

    掌柜爽朗一笑，顺手把竹屉重新掩好，冲陆昭背影点了点头；蒸笼上方翻涌的热气重新把门口招牌裹住，陆昭继续朝街口薄雾深处走去，远处早市人声与低空车流渐渐汇入晨风。

    > **收尾节奏备注**：4 镜合计 20.6 秒，预留首尾各 0.5 秒气口余量，供后续对白与环境声轨对齐。
""".trimIndent()

@Composable
private fun CutScriptPolishTheme(
    mode: InfinityThemeMode,
    sendCount: Int = 0,
    content: @Composable () -> Unit,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = mode,
            colorSource = InfinityColorSource.INFINITY,
            motion = InfinityMotionStyles(surface = ComposerSurfaceStyle.FROSTED),
        ),
    ) {
        CompositionLocalProvider(
            LocalProductionSend provides ProductionSendState(
                count = sendCount,
                sending = false,
                queued = false,
                onSend = {},
            ),
        ) {
            content()
        }
    }
}

/** 剪辑无画面占位监视器：以顶部 OSD 胶囊 + 镜号标题 + 明确状态副标题替代孤立的镜头名与秒数。 */
@Composable
internal fun ProtoCutPlaceholderPlayer(
    shotCode: String?,
    shotTitle: String?,
    durationSeconds: Double?,
    clipIndex: Int,
    totalClips: Int,
    isPreviewMode: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
    progressFraction: Float = 0.42f,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
    ) {
        if (shotTitle == null && shotCode == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(scheme.onSurface.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VideocamOff,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = "暂无镜头",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "分镜拆分完成后可在此预览序列",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Surface
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compactHeight = maxHeight < 195.dp
            // 顶部 OSD 行：左侧镜序 + 右侧状态胶囊，固定高度避让下方主内容区。
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (totalClips > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = scheme.surfaceContainerHighest.copy(alpha = 0.88f),
                    ) {
                        Text(
                            text = "第 ${clipIndex + 1}/$totalClips 镜",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (playing) scheme.primaryContainer else scheme.surfaceContainerHighest.copy(alpha = 0.88f),
                ) {
                    val statusTint = if (playing) scheme.onPrimaryContainer else scheme.onSurfaceVariant
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = when {
                                playing -> Icons.Rounded.PlayArrow
                                isPreviewMode -> Icons.Rounded.Slideshow
                                else -> Icons.Rounded.Movie
                            },
                            contentDescription = null,
                            tint = statusTint,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = when {
                                playing -> "占位连播中"
                                isPreviewMode -> "预演占位"
                                else -> "暂无画面"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = statusTint,
                            maxLines = 1,
                        )
                    }
                }
            }

            // 居中语义区：顶部额外留白避让 OSD 胶囊，保证窄屏 1.3x 字号不重叠。
            val primaryTitle = listOfNotNull(
                shotCode?.takeIf(String::isNotBlank),
                shotTitle?.takeIf(String::isNotBlank),
            ).joinToString(" $InfinityMetaSeparator ") // wrap-check: allow 监视器占位卡镜号与标题固定单行省略
            val statusPhrase = if (isPreviewMode) "暂无预演画面" else "待选定镜头成片"
            val durationPhrase = durationSeconds?.let { "计划时长 ${formatShotSeconds(it)} 秒" }
            val subtitleText = listOfNotNull(statusPhrase, durationPhrase).joinToString(" $InfinityMetaSeparator ") // wrap-check: allow 监视器占位副标题按完整短语居中换行

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 16.dp, top = 36.dp, bottom = if (playing) 14.dp else 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) 4.dp else 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (compactHeight) 30.dp else 34.dp)
                            .clip(CircleShape)
                            .background(if (playing) scheme.primary.copy(alpha = 0.14f) else scheme.onSurface.copy(alpha = 0.06f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Rounded.PlayArrow else Icons.Rounded.VideocamOff,
                            contentDescription = null,
                            tint = if (playing) scheme.primary else scheme.onSurfaceVariant,
                            modifier = Modifier.size(if (compactHeight) 16.dp else 18.dp),
                        )
                    }
                    Text(
                        text = primaryTitle,
                        style = if (compactHeight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (playing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .background(scheme.outlineVariant.copy(alpha = 0.28f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction.coerceIn(0.05f, 1f))
                            .background(scheme.primary),
                    )
                }
            }
        }
    }
}

/** 剪辑时间轴原型：当前正在预览的片段带主色描边与柔和高亮底色，呼应上方监视器。 */
@Composable
private fun ProtoCutTimelinePreview(
    shots: List<ProtoTimelineShot>,
    activeIndex: Int,
    isPreviewMode: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val clipPerSec = 18.dp
    val widths = shots.map { (clipPerSec * it.seconds.toFloat()).coerceAtLeast(64.dp) }
    val totalWidth = widths.fold(0.dp) { acc, w -> acc + w }.coerceAtLeast(120.dp)
    val totalSecText = "${formatShotSeconds(shots.sumOf { it.seconds })} 秒"

    ProductionSection(null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "陆昭晨间问候 20s 测试片",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = totalSecText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Column(
                    modifier = Modifier.width(totalWidth + 3.dp * shots.size),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        shots.forEachIndexed { index, shot ->
                            val active = index == activeIndex
                            val shape = RoundedCornerShape(7.dp)
                            val clipModifier = Modifier
                                .width(widths[index])
                                .height(54.dp)
                                .clip(shape)
                                .then(
                                    when {
                                        active -> Modifier
                                            .background(scheme.primary.copy(alpha = 0.12f))
                                            .border(1.5.dp, scheme.primary, shape)
                                        isPreviewMode -> Modifier.background(scheme.surfaceContainerHighest)
                                        else -> Modifier.border(1.dp, scheme.outline, shape)
                                    },
                                )
                            Box(clipModifier) {
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = shot.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${formatShotSeconds(shot.seconds)} 秒",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (active) scheme.primary else scheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        shots.forEachIndexed { index, shot ->
                            ProtoTrackBar(
                                text = shot.dialogue,
                                container = scheme.tertiaryContainer,
                                content = scheme.onTertiaryContainer,
                                width = widths[index],
                            )
                        }
                    }
                    ProtoTrackBar(
                        text = "环境 · 蒸笼沸水、远处锅铲、早市人声、低空飞行汽车低频", // wrap-check: allow 18dp 剪辑轨道固定单行省略
                        container = domain.successContainer,
                        content = domain.onSuccessContainer,
                        width = totalWidth,
                    )
                    ProtoTrackBar(
                        text = "音乐 · 无", // wrap-check: allow 18dp 剪辑轨道固定单行省略
                        container = domain.infoContainer,
                        content = domain.onInfoContainer,
                        width = totalWidth,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtoTrackBar(text: String, container: Color, content: Color, width: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(container)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 剧本阅读卡片（支持收起静态预览 vs 展开限高框内滚动）。 */
@Composable
internal fun ProtoBoundedScriptCard(
    title: String,
    path: String,
    chip: ProductionChipSpec,
    expanded: Boolean,
    scriptMarkdown: String,
    totalChars: Int,
    rejectReason: String? = null,
    hasSavedVerdict: Boolean = true,
    expandedMaxHeight: Dp = 380.dp,
    simulatedScrollFraction: Float? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val chrome = LocalFrostedChromeMaterial.current
    val dark = MaterialTheme.infinityDomainColors.isDark
    val readingBg = if (chrome != null) {
        scheme.onSurface.copy(alpha = if (dark) 0.05f else 0.035f)
    } else {
        scheme.surfaceContainer
    }
    // 将渐隐目标色与阅读视口底色做像素合成，避免深浅双主题及熏黑毛玻璃下出现额外暗带或色阶差。
    val effectiveCardBg = if (chrome != null) {
        chrome.innerCard.compositeOver(chrome.frostedTint ?: chrome.container)
    } else {
        scheme.surfaceContainerLow
    }
    val fadeTarget = readingBg.compositeOver(effectiveCardBg)

    BettboxListGroup(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Description, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ProductionStatusChip(chip.text, chip.tone)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                InfinityPillButton(
                    text = "全屏查看",
                    onClick = {},
                    tone = InfinityPillTone.NEUTRAL,
                    icon = Icons.Rounded.OpenInFull,
                    compact = true,
                )
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = readingBg,
                border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.40f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    if (!expanded) {
                        // 收起态：固定 220dp 静态裁剪预览，不启用内部滚动，保证页面滑过时不抢外层列表手势。
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clipToBounds(),
                        ) {
                            Box(Modifier.fillMaxWidth().padding(12.dp)) {
                                MarkdownText(markdown = scriptMarkdown, isStreaming = false, showCursor = false, fontSize = 14)
                            }
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(30.dp)
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, fadeTarget))),
                            )
                        }
                    } else {
                        // 展开态：外层固定视口高度限制，内层挂载垂直滚动；边缘渐隐与 3dp 细滚动条固定于外层 Box，不随内容滚走。
                        val density = LocalDensity.current
                        val initialOffsetPx = with(density) {
                            ((simulatedScrollFraction ?: 0f) * 560f * density.fontScale).dp.roundToPx()
                        }
                        val scrollState = rememberScrollState(initial = initialOffsetPx)
                        val showTopFade: Boolean
                        val showBottomFade: Boolean
                        val scrollFraction: Float?
                        if (simulatedScrollFraction != null) {
                            val f = simulatedScrollFraction.coerceIn(0f, 1f)
                            showTopFade = f > 0.01f
                            showBottomFade = f < 0.99f
                            scrollFraction = f
                        } else {
                            val max = scrollState.maxValue
                            showTopFade = scrollState.value > 2
                            showBottomFade = max in 1 until Int.MAX_VALUE && scrollState.value < max - 2
                            scrollFraction = if (max in 1 until Int.MAX_VALUE) {
                                (scrollState.value.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                            } else {
                                null
                            }
                        }

                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = expandedMaxHeight)
                                .clipToBounds(),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(scrollState)
                                    .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                            ) {
                                MarkdownText(markdown = scriptMarkdown, isStreaming = false, showCursor = false, fontSize = 14)
                            }
                            Box(Modifier.matchParentSize()) {
                                if (showTopFade) {
                                    Box(
                                        Modifier
                                            .align(Alignment.TopCenter)
                                            .fillMaxWidth()
                                            .height(20.dp)
                                            .background(Brush.verticalGradient(listOf(fadeTarget, Color.Transparent))),
                                    )
                                }
                                if (showBottomFade) {
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .height(28.dp)
                                            .background(Brush.verticalGradient(listOf(Color.Transparent, fadeTarget))),
                                    )
                                }
                                if (scrollFraction != null) {
                                    val thumbColor = scheme.onSurfaceVariant.copy(alpha = 0.30f)
                                    Canvas(
                                        Modifier
                                            .align(Alignment.CenterEnd)
                                            .fillMaxHeight()
                                            .padding(top = 8.dp, bottom = 8.dp, end = 4.dp)
                                            .width(3.dp),
                                    ) {
                                        val minThumb = 28.dp.toPx()
                                        val thumbHeight = (size.height * 0.34f).coerceIn(minThumb, size.height)
                                        val travel = (size.height - thumbHeight).coerceAtLeast(0f)
                                        val topOffset = travel * scrollFraction
                                        drawRoundRect(
                                            color = thumbColor,
                                            topLeft = Offset(0f, topOffset),
                                            size = Size(size.width, thumbHeight),
                                            cornerRadius = CornerRadius(size.width / 2f, size.width / 2f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val footerText = if (expanded) {
                            "共 $totalChars 字 $InfinityMetaSeparator 可滚动" // wrap-check: allow 剧本字数栏单行省略
                        } else {
                            "共 $totalChars 字"
                        }
                        Text(
                            text = footerText,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        InfinityPillButton(
                            text = if (expanded) "收起正文" else "展开全文",
                            onClick = {},
                            tone = InfinityPillTone.NEUTRAL,
                            icon = if (expanded) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore,
                            compact = true,
                        )
                    }
                }
            }

            if (!rejectReason.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.errorContainer.copy(alpha = 0.24f),
                    border = BorderStroke(0.5.dp, scheme.error.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("否决原因", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = scheme.error)
                        Text(rejectReason, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                    }
                }
            }

            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (hasSavedVerdict) "已暂存本轮意见，可随时调整" else "尚未提交审批结论",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                InfinityPillButton(
                    text = if (hasSavedVerdict) "修改审批" else "审批",
                    onClick = {},
                    tone = if (hasSavedVerdict) InfinityPillTone.NEUTRAL else InfinityPillTone.PRIMARY,
                    icon = Icons.Rounded.RateReview,
                    compact = false,
                )
            }
        }
    }
}

@Composable
private fun ProtoCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ProtoSceneListSection() {
    ProductionSection("场次") {
        ProductionRow(
            title = "日外 · 承天市城东新老街 · 包子铺门前", // wrap-check: allow 剧本页真实场次标题单行省略
            subtitle = "4 镜 · 20.6 秒", // wrap-check: allow 真实场次副标题单行省略
            leading = {
                Text(
                    text = "1",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            chip = ProductionChipSpec("待审", ProductionTone.WARNING),
            chevron = false,
            onClick = {},
        )
    }
}

@Composable
private fun ProtoInspectorShell(
    chrome: FrostedChromeMaterial,
    selectedTab: CutPolishProtoTab,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = chrome.frostedTint ?: chrome.container,
        border = chrome.border,
        shadowElevation = 8.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            FrostedChromeBackdropLayer(
                source = null,
                material = chrome,
                highlightHeight = 56.dp,
                modifier = Modifier.matchParentSize(),
            )
            CompositionLocalProvider(
                LocalFrostedChromeMaterial provides chrome,
                LocalInfinityPanePresentation provides InfinityPanePresentation.Inspector,
            ) {
                Column(Modifier.fillMaxSize()) {
                    ProductionTopBar(
                        title = "陆昭晨间问候 20s 测试片",
                        meta = listOf("电影", "4 个镜头", "16:9"),
                        onBack = null,
                        actions = {
                            IconButton(onClick = {}) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = scheme.onSurfaceVariant)
                            }
                        },
                    )
                    InfinityPanelTabs(
                        tabs = listOf(
                            InfinityPanelTab(CutPolishProtoTab.OVERVIEW, "总览"),
                            InfinityPanelTab(CutPolishProtoTab.SCRIPT, "剧本"),
                            InfinityPanelTab(CutPolishProtoTab.ASSETS, "资产"),
                            InfinityPanelTab(CutPolishProtoTab.STORYBOARD, "分镜", dot = scheme.primary),
                            InfinityPanelTab(CutPolishProtoTab.OUTPUT, "出片"),
                            InfinityPanelTab(CutPolishProtoTab.CUT, "剪辑"),
                        ),
                        selected = selectedTab,
                        onSelect = {},
                        contentInset = InfinitySpacing.Large,
                        modifier = Modifier.padding(bottom = InfinitySpacing.XSmall),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
                        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                        content = content,
                    )
                    HorizontalDivider(color = chrome.edgeDivider.copy(alpha = 0.7f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Surface(shape = CircleShape, color = scheme.onSurface.copy(alpha = 0.10f)) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Rounded.Movie, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(18.dp))
                                Text("制作", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {}, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Rounded.OpenInFull, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = {}, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Rounded.KeyboardDoubleArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "1. 剪辑占位监视器 · 四态对比(深色 411dp)", widthDp = 411, heightDp = 1320, locale = "zh-rCN")
@Composable
fun cutPlaceholderStatesDarkPreview() = CutScriptPolishTheme(InfinityThemeMode.DARK, sendCount = 0) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProtoCaption("A. 预演模式静止占位 · 第 1/4 镜与时间轴当前镜高亮")
            ProtoCutPlaceholderPlayer(
                shotCode = "SC01-01",
                shotTitle = "经过",
                durationSeconds = 4.5,
                clipIndex = 0,
                totalClips = 4,
                isPreviewMode = true,
                playing = false,
            )
            ProtoCutTimelinePreview(
                shots = ProtoSampleShots,
                activeIndex = 0,
                isPreviewMode = true,
            )

            ProtoCaption("B. 占位连播中 · 状态高亮 + 底部微进度条")
            ProtoCutPlaceholderPlayer(
                shotCode = "SC01-02",
                shotTitle = "招呼",
                durationSeconds = 5.0,
                clipIndex = 1,
                totalClips = 4,
                isPreviewMode = true,
                playing = true,
                progressFraction = 0.42f,
            )

            ProtoCaption("C. 已批准候选 / 粗剪版待选定成片")
            ProtoCutPlaceholderPlayer(
                shotCode = "SC01-03",
                shotTitle = "婉拒",
                durationSeconds = 5.2,
                clipIndex = 2,
                totalClips = 4,
                isPreviewMode = false,
                playing = false,
            )

            ProtoCaption("D. 无镜头空状态")
            ProtoCutPlaceholderPlayer(
                shotCode = null,
                shotTitle = null,
                durationSeconds = null,
                clipIndex = 0,
                totalClips = 0,
                isPreviewMode = true,
                playing = false,
            )
        }
    }
}

@Preview(name = "2. 剧本阅读卡 · 收起 vs 展开限高滚动(深色 411dp)", widthDp = 411, heightDp = 1600, locale = "zh-rCN")
@Composable
fun scriptBoundedScrollStatesDarkPreview() = CutScriptPolishTheme(InfinityThemeMode.DARK, sendCount = 1) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProtoCaption("A. 默认收起态 · 220dp 静态预览不抢外层页面滚动")
            ProtoBoundedScriptCard(
                title = "陆昭晨间问候 20s 测试片",
                path = "AI-Assets/Productions/se…/screenplay.md",
                chip = ProductionChipSpec("已否决 · 未发送", ProductionTone.ERROR),
                expanded = false,
                scriptMarkdown = ProtoSampleScriptMarkdown,
                totalChars = 3636,
                rejectReason = "还是有点太急促了，正常的日常对话不应该全部都是这种短对话。这么急促，就感觉很不正常",
                hasSavedVerdict = true,
            )

            ProtoCaption("B. 展开全文态 · 380dp 限高 + 框内滚动 + 上下渐隐与细滚动条")
            ProtoBoundedScriptCard(
                title = "陆昭晨间问候 20s 测试片",
                path = "AI-Assets/Productions/se…/screenplay.md",
                chip = ProductionChipSpec("已否决 · 未发送", ProductionTone.ERROR),
                expanded = true,
                scriptMarkdown = ProtoSampleScriptMarkdown,
                totalChars = 3636,
                rejectReason = "还是有点太急促了，正常的日常对话不应该全部都是这种短对话。这么急促，就感觉很不正常",
                hasSavedVerdict = true,
                expandedMaxHeight = 380.dp,
                simulatedScrollFraction = 0.36f,
            )

            ProtoSceneListSection()
        }
    }
}

@Preview(name = "3. 内屏悬浮检视器实景 · 剧本展开与剪辑页(深色 840dp)", widthDp = 840, heightDp = 960, locale = "zh-rCN")
@Composable
fun innerInspectorContextDarkPreview() = CutScriptPolishTheme(InfinityThemeMode.DARK, sendCount = 1) {
    val chrome = rememberFrostedChromeMaterial(FrostedChromeRole.FLOATING_INSPECTOR)
    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProtoInspectorShell(
                chrome = chrome,
                selectedTab = CutPolishProtoTab.SCRIPT,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    // 展开限高按主体可用高度比例裁剪并钳制，使审批区与下方「场次」在检视器内同屏可见。
                    val dynamicExpandedMax = (maxHeight * 0.36f).coerceIn(220.dp, 360.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProtoBoundedScriptCard(
                            title = "陆昭晨间问候 20s 测试片",
                            path = "AI-Assets/Productions/se…/screenplay.md",
                            chip = ProductionChipSpec("已否决 · 未发送", ProductionTone.ERROR),
                            expanded = true,
                            scriptMarkdown = ProtoSampleScriptMarkdown,
                            totalChars = 3636,
                            rejectReason = "还是有点太急促了，正常的日常对话不应该全部都是这种短对话。",
                            hasSavedVerdict = true,
                            expandedMaxHeight = dynamicExpandedMax,
                            simulatedScrollFraction = 0.36f,
                        )
                        ProtoSceneListSection()
                    }
                }
            }

            ProtoInspectorShell(
                chrome = chrome,
                selectedTab = CutPolishProtoTab.CUT,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                ) {
                    InfinitySegmentedControl(
                        segments = listOf(
                            InfinitySegment("preview", "预演"),
                            InfinitySegment("selected", "已批准候选"),
                        ),
                        selected = "preview",
                        onSelect = {},
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {}, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Add, contentDescription = "新建粗剪", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ProtoCutPlaceholderPlayer(
                    shotCode = "SC01-01",
                    shotTitle = "经过",
                    durationSeconds = 4.5,
                    clipIndex = 0,
                    totalClips = 4,
                    isPreviewMode = true,
                    playing = false,
                )
                ProtoCutTimelinePreview(
                    shots = ProtoSampleShots,
                    activeIndex = 0,
                    isPreviewMode = true,
                )
                ProductionSection(null) {
                    ProductionRow("缺口", value = "4 镜待补预演（当前占位）")
                    ProductionRow("规格", valueSegments = listOf("16:9", "H.264 · AAC"))
                }
            }
        }
    }
}

@Preview(name = "4. 浅色主题 + 360dp窄屏 + 1.3x大字号极限适配", widthDp = 360, heightDp = 1380, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun lightLargeFontAdaptPreview() = CutScriptPolishTheme(InfinityThemeMode.LIGHT, sendCount = 1) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProtoCaption("剪辑占位监视器 · 预演静止（360dp + 1.3x）")
            ProtoCutPlaceholderPlayer(
                shotCode = "SC01-01",
                shotTitle = "经过",
                durationSeconds = 4.5,
                clipIndex = 0,
                totalClips = 4,
                isPreviewMode = true,
                playing = false,
            )

            ProtoCaption("剪辑占位监视器 · 占位连播中")
            ProtoCutPlaceholderPlayer(
                shotCode = "SC01-02",
                shotTitle = "招呼",
                durationSeconds = 5.0,
                clipIndex = 1,
                totalClips = 4,
                isPreviewMode = true,
                playing = true,
                progressFraction = 0.42f,
            )

            ProtoCaption("剧本展开限高滚动卡 · 窄屏大字号底栏对齐")
            ProtoBoundedScriptCard(
                title = "陆昭晨间问候 20s 测试片",
                path = "AI-Assets/Productions/se…/screenplay.md",
                chip = ProductionChipSpec("已否决 · 未发送", ProductionTone.ERROR),
                expanded = true,
                scriptMarkdown = ProtoSampleScriptMarkdown,
                totalChars = 3636,
                rejectReason = "还是有点太急促了，正常的日常对话不应该全部都是这种短对话。",
                hasSavedVerdict = true,
                expandedMaxHeight = 300.dp,
                simulatedScrollFraction = 0.56f,
            )

            ProtoSceneListSection()
        }
    }
}
