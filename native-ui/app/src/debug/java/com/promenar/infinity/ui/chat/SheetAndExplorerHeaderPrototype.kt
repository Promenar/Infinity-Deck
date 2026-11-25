package com.promenar.infinity.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.InfinityPaneHeaderTitle
import com.promenar.infinity.ui.common.InspectorPanelHeaderHeight
import com.promenar.infinity.ui.common.InspectorPanelTopGap
import com.promenar.infinity.ui.common.obsidianTopGlint
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityObsidianSolidContainer
import com.promenar.infinity.ui.theme.InfinityPureBlack
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 高保真隔离视觉原型 v1：
 * 1. 外屏大内容上滑面板取消 100% Full 模式，统一收口为 93% 高度（与竖屏检视器抽屉一致，顶部保留 7% 背景暗幕与抽屉层次感）；
 * 2. 侧边栏「文件」页头移除灰底 FileGitHeader 卡片，统一收口为 B 档 56dp 大小字双行轻页头（与 Git / 任务 / 状态 / 制作页头逐像素对齐）：
 *    - Git 仓库：上行工作区/仓库名，下行「⑂ 分支名 · N 处改动 ›」（或「工作区干净 ›」），整块点按跳转 Git 页签；
 *    - 非 Git 普通文件夹：上行文件夹/工作区名，下行柔和目录来源（如「外部文件夹」「项目工作区」「本地工作区」）；
 *    - 多位置挂载：上行名称旁带主色下拉指示 ▾ 切换位置，下行仍优雅承载 Git 跳转或目录来源，不额外挤占列表空间；
 *    - 根目录分隔线下方改显「N 个文件夹 · M 个文件」+ 排序按钮，消除重复打印一遍工作区名；进入子目录自动切换为可点面包屑。
 */

private data class ExplorerHeaderProtoSpec(
    val sectionLabel: String,
    val title: String,
    val switchable: Boolean = false,
    val gitBranch: String? = null,
    val gitChanges: Int? = null,
    val plainSubtitle: String? = null,
    val subRowLeft: String,
    val sortLabel: String,
    val sampleRows: List<Pair<Boolean, String>>,
)

@Composable
private fun ExplorerHeaderProtoCard(
    spec: ExplorerHeaderProtoSpec,
    english: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val dark = scheme.surface.luminance() < 0.5f
    val cardBg = if (dark) Color(0xFF070709) else scheme.surfaceContainerLowest
    val borderColor = if (dark) InfinityPureWhite.copy(alpha = 0.12f) else scheme.outlineVariant.copy(alpha = 0.5f)
    val dividerColor = if (dark) InfinityPureWhite.copy(alpha = 0.09f) else scheme.outlineVariant.copy(alpha = 0.45f)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = spec.sectionLabel,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = cardBg,
            contentColor = scheme.onSurface,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .obsidianTopGlint(
                        color = if (dark) InfinityPureWhite.copy(alpha = 0.14f) else Color.Transparent,
                        cornerRadius = 22.dp,
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = InspectorPanelTopGap)
                        .heightIn(min = InspectorPanelHeaderHeight)
                        .padding(start = InfinitySpacing.ScreenHorizontal, end = InfinitySpacing.XSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val hasGit = spec.gitBranch != null
                    if (spec.switchable && hasGit) {
                        // 多位置 + Git：上行切位置，下行跳 Git，各司其职且维持 56dp B 档坐标。
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 32.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            ) {
                                Text(
                                    text = spec.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Rounded.ArrowDropDown,
                                    contentDescription = if (english) "Switch location" else "切换位置",
                                    tint = scheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 24.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                            ) {
                                ExplorerGitMetaContent(
                                    branch = spec.gitBranch,
                                    changes = spec.gitChanges,
                                    english = english,
                                    warningColor = domain.onWarningContainer,
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                                .clip(RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            InfinityPaneHeaderTitle(
                                title = spec.title,
                                modifier = Modifier.fillMaxWidth(),
                                meta = when {
                                    hasGit -> ({
                                        ExplorerGitMetaContent(
                                            branch = spec.gitBranch,
                                            changes = spec.gitChanges,
                                            english = english,
                                            warningColor = domain.onWarningContainer,
                                        )
                                    })
                                    spec.plainSubtitle != null -> ({
                                        Text(
                                            text = spec.plainSubtitle,
                                            color = if (spec.switchable) scheme.primary else scheme.onSurfaceVariant,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                        if (spec.switchable) {
                                            Icon(
                                                Icons.Rounded.ArrowDropDown,
                                                contentDescription = null,
                                                tint = scheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    })
                                    else -> null
                                },
                            )
                        }
                    }
                    IconButton(onClick = {}) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = if (english) "Search" else "搜索",
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {}) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = if (english) "More" else "更多",
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = dividerColor)
                // 分隔线下方：根目录显示统计，子目录显示面包屑，避免重复标题。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 42.dp)
                        .padding(start = InfinitySpacing.ScreenHorizontal, end = InfinitySpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = spec.subRowLeft,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Rounded.Sort, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Text(
                            text = spec.sortLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                spec.sampleRows.forEach { (isDir, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = if (isDir) Icons.Rounded.Folder else Icons.Rounded.Description,
                            contentDescription = null,
                            tint = if (isDir) scheme.primary else scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun ExplorerGitMetaContent(
    branch: String,
    changes: Int?,
    english: Boolean,
    warningColor: Color,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Rounded.AccountTree,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = branch,
            style = InfinityTypography.labelMedium.copy(fontFamily = InfinityMonospace),
            color = scheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
        if (changes != null) {
            Text(
                text = "·",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.outline,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = when {
                    changes > 0 && english -> "$changes changes"
                    changes > 0 -> "$changes 处改动"
                    english -> "Clean"
                    else -> "工作区干净"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (changes > 0) warningColor else scheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = if (english) "Open Git tab" else "打开 Git 页签",
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun GitHeaderReferenceCard(english: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val cardBg = if (dark) Color(0xFF070709) else scheme.surfaceContainerLowest
    val borderColor = if (dark) InfinityPureWhite.copy(alpha = 0.12f) else scheme.outlineVariant.copy(alpha = 0.5f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (english) "Reference: Git Tab Header (1:1 alignment)" else "对照：Git 页签原版 B 档双行页头（位置与字号 1:1 对齐）",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = cardBg,
            contentColor = scheme.onSurface,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = InspectorPanelTopGap)
                    .heightIn(min = InspectorPanelHeaderHeight)
                    .padding(start = InfinitySpacing.ScreenHorizontal, end = InfinitySpacing.XSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InfinityPaneHeaderTitle(
                    title = "Personal-Archive",
                    modifier = Modifier.weight(1f),
                    meta = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                            Text(
                                text = "main",
                                style = InfinityTypography.labelMedium.copy(fontFamily = InfinityMonospace),
                                color = scheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Text("·", color = scheme.outline, maxLines = 1, softWrap = false)
                            Text("origin/main", color = scheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun OuterSheet93PercentProto(dark: Boolean, english: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val sheetBg = if (dark) InfinityObsidianSolidContainer else scheme.surfaceContainerLowest
    val scrim = InfinityPureBlack.copy(alpha = if (dark) 0.60f else 0.32f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        // 底层对话顶栏，便于直观感受 93% 上滑卡片顶部留出的 7% 暗幕呼吸区。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp, start = 16.dp, end = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (english) "Infinity Deck Chat" else "仙凡纪元短片创作筹备",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                )
                Text(
                    text = "93% Sheet",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Linux · Personal-Archive · main",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        // 暗幕层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrim),
        )
        // 93% 高度统一大内容上滑卡片（取消 100% Full 模式）
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = sheetBg,
            contentColor = scheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.93f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .obsidianTopGlint(
                        color = if (dark) InfinityPureWhite.copy(alpha = 0.14f) else Color.Transparent,
                        cornerRadius = 24.dp,
                    )
                    .padding(horizontal = 24.dp),
            ) {
                // 标准拖拽把手
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(scheme.onSurfaceVariant.copy(alpha = 0.36f)),
                    )
                }
                Text(
                    text = if (english) "Select Model" else "选择模型",
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.surfaceContainerHigh)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(
                        text = if (english) "Search models or providers" else "搜索模型或提供商",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(18.dp))
                val groups = listOf(
                    "OpenCode Go" to listOf("DeepSeek V4.1 Flash 1017" to true, "GLM-5.3 Flash" to false, "Kimi K2.5" to false),
                    "Google Gemini" to listOf("Gemini 3.5 Flash Lite" to false, "Gemini 3.5 Pro" to false),
                    "MiniMax" to listOf("MiniMax M3" to false, "MiniMax M3 Fast" to false),
                )
                groups.forEach { (provider, items) ->
                    Text(
                        text = provider,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                    )
                    items.forEach { (modelName, selected) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) scheme.primaryContainer.copy(alpha = 0.35f) else scheme.surfaceContainer)
                                .border(
                                    width = if (selected) 1.dp else 0.dp,
                                    color = if (selected) scheme.primary.copy(alpha = 0.6f) else Color.Transparent,
                                    shape = RoundedCornerShape(14.dp),
                                )
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = modelName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = scheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (selected) {
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExplorerHeaderGallery(dark: Boolean, english: Boolean) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        val specs = if (english) {
            listOf(
                ExplorerHeaderProtoSpec(
                    sectionLabel = "1. Git Repository (dirty, root)",
                    title = "Personal-Archive",
                    gitBranch = "main",
                    gitChanges = 48,
                    subRowLeft = "4 folders · 12 files",
                    sortLabel = "Modified",
                    sampleRows = listOf(true to "indexes", false to "README.md"),
                ),
                ExplorerHeaderProtoSpec(
                    sectionLabel = "2. Git Repository (clean, subfolder)",
                    title = "Personal-Archive",
                    gitBranch = "main",
                    gitChanges = 0,
                    subRowLeft = "Personal-Archive › indexes",
                    sortLabel = "Modified",
                    sampleRows = listOf(false to "catalog.json"),
                ),
                ExplorerHeaderProtoSpec(
                    sectionLabel = "3. Non-Git Plain Folder (external / session workspace)",
                    title = "Session Workspace",
                    plainSubtitle = "Local workspace",
                    subRowLeft = "3 folders · 8 files",
                    sortLabel = "Modified",
                    sampleRows = listOf(true to "drafts", false to "notes.md"),
                ),
                ExplorerHeaderProtoSpec(
                    sectionLabel = "4. Multi-Location Mounted (switch location + Git)",
                    title = "Personal-Archive",
                    switchable = true,
                    gitBranch = "main",
                    gitChanges = 48,
                    subRowLeft = "4 folders · 12 files",
                    sortLabel = "Modified",
                    sampleRows = listOf(true to "indexes"),
                ),
            )
        } else {
            listOf(
                ExplorerHeaderProtoSpec(
                    sectionLabel = "1. Git 仓库工作区（有改动，根目录）—— 整块点按跳 Git 页签",
                    title = "Personal-Archive",
                    gitBranch = "main",
                    gitChanges = 48,
                    subRowLeft = "4 个文件夹 · 12 个文件",
                    sortLabel = "修改时间",
                    sampleRows = listOf(true to "indexes", false to "README.md"),
                ),
                ExplorerHeaderProtoSpec(
                    sectionLabel = "2. Git 仓库工作区（干净，子目录）",
                    title = "Personal-Archive",
                    gitBranch = "main",
                    gitChanges = 0,
                    subRowLeft = "Personal-Archive › indexes",
                    sortLabel = "修改时间",
                    sampleRows = listOf(false to "catalog.json"),
                ),
                ExplorerHeaderProtoSpec(
                    sectionLabel = "3a. 非 Git 普通文件夹（外部目录 / 项目工作区）",
                    title = "产品方案草稿",
                    plainSubtitle = "外部文件夹 · 只读",
                    subRowLeft = "2 个文件夹 · 9 个文件",
                    sortLabel = "修改时间",
                    sampleRows = listOf(true to "2026-Q4", false to "brief.md"),
                ),
                ExplorerHeaderProtoSpec(
                    sectionLabel = "3b. 非 Git 默认会话工作区（避免双行同名）",
                    title = "会话工作区",
                    plainSubtitle = "本地工作区",
                    subRowLeft = "3 个文件夹 · 8 个文件",
                    sortLabel = "修改时间",
                    sampleRows = listOf(true to "outputs", false to "summary.md"),
                ),
                ExplorerHeaderProtoSpec(
                    sectionLabel = "4. 多位置挂载（上行切位置 ▾ + 下行跳 Git ›）",
                    title = "Personal-Archive",
                    switchable = true,
                    gitBranch = "main",
                    gitChanges = 48,
                    subRowLeft = "4 个文件夹 · 12 个文件",
                    sortLabel = "修改时间",
                    sampleRows = listOf(true to "indexes"),
                ),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            specs.forEach { ExplorerHeaderProtoCard(it, english = english) }
            GitHeaderReferenceCard(english = english)
        }
    }
}

@Preview(name = "外屏 93% 模型选择卡片（深色）", widthDp = 411, heightDp = 891, showBackground = true)
@Composable
fun OuterSheet93PercentDarkPhone() {
    InfinityTheme(
        preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY),
    ) {
        OuterSheet93PercentProto(dark = true, english = false)
    }
}

@Preview(name = "文件页头 B 档双行全态（深色 360dp）", widthDp = 360, heightDp = 1120, showBackground = true)
@Composable
fun ExplorerHeaderStatesDarkInspector() {
    ExplorerHeaderGallery(dark = true, english = false)
}

@Preview(name = "文件页头 B 档双行（浅色 360dp）", widthDp = 360, heightDp = 1120, showBackground = true)
@Composable
fun ExplorerHeaderStatesLightInspector() {
    ExplorerHeaderGallery(dark = false, english = false)
}

@Preview(name = "Explorer Header EN 1.3x Font", widthDp = 360, heightDp = 960, fontScale = 1.3f, showBackground = true)
@Composable
fun ExplorerHeaderEnglishLargeFont() {
    ExplorerHeaderGallery(dark = true, english = true)
}
