package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.SettingsSectionHeader
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.bettboxListGroupItem
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/*
 * 设置 › 本地模型：新增「启动时自动加载模型」开关与内存挤压提醒高保真 Compose 校准原型（v1，仅 debug 源码）。
 *
 * 确认范围：
 * 1. LocalModelsAutoLoadOffDarkPreview：外屏 360dp 深色中文 · 引擎开启 + 启动自动加载关闭（默认推荐态）+ 内存挤压提醒页脚 + 槽位「已保存」与动态页脚文案
 * 2. LocalModelsAutoLoadOnLightPreview：外屏 360dp 浅色中文 · 引擎开启 + 启动自动加载开启 + 模型「已加载」与开启态内存提醒页脚
 * 3. LocalModelsEngineOffDarkPreview：外屏 360dp 深色中文 · 引擎关闭态，核验第二行开关置灰禁用且布局无跳变
 * 4. LocalModelsAutoLoadLargeFontEnPreview：外屏 360dp 浅色英文 + 1.3× 大字号压测，核验双开关、双行副标题、提醒页脚与槽位行零裁切
 * 5. LocalModelsAutoLoadInnerPaneDarkPreview：内屏 840dp 深色中文双栏详情宽度适配
 */

private data class ProtoSlot(
    val titleZh: String,
    val titleEn: String,
    val subtitleZh: String,
    val subtitleEn: String,
    val statusZh: String,
    val statusEn: String,
    val active: Boolean,
    val icon: ImageVector,
)

@Composable
private fun LocalModelsAutoLoadProtoScreen(
    engineEnabled: Boolean,
    autoLoadOnStartup: Boolean,
    dark: Boolean,
    english: Boolean = false,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        val loaded = engineEnabled && autoLoadOnStartup
        val topFooter = when {
            !engineEnabled -> null
            autoLoadOnStartup -> if (english) {
                "Auto-load is on. Resident models occupy hundreds of MB to several GB of RAM and may squeeze heavy Linux or Git tasks. Turn off if used infrequently."
            } else {
                "已开启启动自动加载：模型常驻会占用数百 MB 至数 GB 内存，非高频使用建议关闭，以免挤压 Linux 编译或大型 Git 等重负载任务空间。"
            }
            else -> if (english) {
                "Recommended off unless used daily. Resident models occupy hundreds of MB to several GB of RAM and may squeeze heavy Linux or Git tasks."
            } else {
                "非高频使用建议关闭：模型常驻会占用数百 MB 至数 GB 内存，可能挤压 Linux 编译或大型 Git 等重负载任务空间。"
            }
        }
        val assignmentFooter = when {
            !engineEnabled -> if (english) "Enable local inference to load models." else "开启本地推理后可加载模型。"
            autoLoadOnStartup -> if (english) {
                "Assigned model slots are saved locally and restored automatically on app launch or when re-enabling local inference."
            } else {
                "已分配的模型槽位保存在本机，将在应用启动或重新启用本地推理时自动恢复加载。"
            }
            else -> if (english) {
                "Assigned model slots are saved locally without auto-loading on startup. Tap a slot above to load on demand."
            } else {
                "已分配的模型槽位仅保存在本机，不会在启动时自动加载；需要时点按上方槽位按需加载。"
            }
        }
        val slots = listOf(
            ProtoSlot(
                titleZh = "文本生成",
                titleEn = "Text generation",
                subtitleZh = "Qwen3-0.6B-Q8_0",
                subtitleEn = "Qwen3-0.6B-Q8_0",
                statusZh = if (loaded) "已加载" else "已保存",
                statusEn = if (loaded) "Loaded" else "Saved",
                active = loaded,
                icon = Icons.AutoMirrored.Rounded.Subject,
            ),
            ProtoSlot(
                titleZh = "向量嵌入",
                titleEn = "Embeddings",
                subtitleZh = "将资料转换为向量",
                subtitleEn = "Convert material to vectors",
                statusZh = "未分配",
                statusEn = "Unassigned",
                active = false,
                icon = Icons.Rounded.Hub,
            ),
            ProtoSlot(
                titleZh = "结果重排",
                titleEn = "Reranking",
                subtitleZh = "重新排序检索结果",
                subtitleEn = "Reorder search results",
                statusZh = "未分配",
                statusEn = "Unassigned",
                active = false,
                icon = Icons.AutoMirrored.Rounded.Sort,
            ),
        )

        InfinitySettingsPageLayout(
            title = if (english) "Local Models" else "本地模型",
            onBack = {},
        ) { padding ->
            LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = padding) {
                item {
                    InfinitySettingsGroup(footer = topFooter) {
                        SettingsToggle(
                            title = if (english) "Local inference" else "本地推理",
                            description = "llama.cpp · CPU",
                            icon = Icons.Rounded.Memory,
                            checked = engineEnabled,
                            onCheckedChange = {},
                        )
                        HorizontalDivider()
                        SettingsToggle(
                            title = if (english) "Auto-load on startup" else "启动时自动加载模型",
                            description = if (autoLoadOnStartup) {
                                if (english) "Restore saved slots on launch" else "冷启动或重开引擎时自动恢复"
                            } else {
                                if (english) "Load manually or on demand" else "仅在手动加载或按需使用时载入"
                            },
                            icon = Icons.Rounded.RestartAlt,
                            checked = autoLoadOnStartup,
                            enabled = engineEnabled,
                            descriptionMaxLines = 2,
                            onCheckedChange = {},
                        )
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = InfinitySpacing.RowHorizontal),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsSectionHeader(
                            title = if (english) "Model library" else "模型库",
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            onClick = {},
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .padding(end = InfinitySpacing.Small)
                                .minimumInteractiveComponentSize()
                                .heightIn(min = 36.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
                            ) {
                                Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                                Text(
                                    if (english) "Import model" else "导入模型",
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                item {
                    Column(Modifier.bettboxListGroupItem(0, 1)) {
                        InfinitySettingsRowLayout(
                            title = "Qwen3-0.6B-Q8_0",
                            icon = Icons.Rounded.Layers,
                            titleMaxLines = 2,
                            subtitleSegments = listOf(
                                "639 MB",
                                "Q8_0",
                                if (english) "Pinned for recording cleanup" else "已固定用于录音整理",
                            ),
                            subtitleMaxLines = 1,
                            trailingContent = {
                                IconButton(onClick = {}) {
                                    Icon(
                                        Icons.Rounded.MoreHoriz,
                                        contentDescription = if (english) "Actions for Qwen3-0.6B-Q8_0" else "Qwen3-0.6B-Q8_0 的操作",
                                    )
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(InfinitySpacing.SectionGap))
                }
                item {
                    SettingsSectionHeader(
                        if (english) "Model assignment" else "模型分配",
                        modifier = Modifier.padding(
                            start = InfinitySpacing.RowHorizontal,
                            bottom = InfinitySpacing.SectionHeaderBottom,
                        ),
                    )
                    InfinitySettingsGroup(footer = assignmentFooter) {
                        slots.forEachIndexed { index, slot ->
                            if (index > 0) HorizontalDivider()
                            InfinitySettingsItem(
                                title = if (english) slot.titleEn else slot.titleZh,
                                subtitle = if (english) slot.subtitleEn else slot.subtitleZh,
                                icon = slot.icon,
                                titleMaxLines = 2,
                                subtitleMaxLines = 2,
                                onClick = {},
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (english) slot.statusEn else slot.statusZh,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (slot.active) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                        Icon(
                                            Icons.Rounded.ChevronRight,
                                            null,
                                            Modifier.padding(start = 4.dp).size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "本地模型 自动加载关闭 深中360", widthDp = 360, heightDp = 860, locale = "zh-rCN")
@Composable
fun LocalModelsAutoLoadOffDarkPreview() = LocalModelsAutoLoadProtoScreen(
    engineEnabled = true,
    autoLoadOnStartup = false,
    dark = true,
)

@Preview(name = "本地模型 自动加载开启 浅中360", widthDp = 360, heightDp = 860, locale = "zh-rCN")
@Composable
fun LocalModelsAutoLoadOnLightPreview() = LocalModelsAutoLoadProtoScreen(
    engineEnabled = true,
    autoLoadOnStartup = true,
    dark = false,
)

@Preview(name = "本地模型 引擎关闭 深中360", widthDp = 360, heightDp = 820, locale = "zh-rCN")
@Composable
fun LocalModelsEngineOffDarkPreview() = LocalModelsAutoLoadProtoScreen(
    engineEnabled = false,
    autoLoadOnStartup = false,
    dark = true,
)

@Preview(name = "本地模型 自动加载 浅英360 大字", widthDp = 360, heightDp = 1080, locale = "en", fontScale = 1.3f)
@Composable
fun LocalModelsAutoLoadLargeFontEnPreview() = LocalModelsAutoLoadProtoScreen(
    engineEnabled = true,
    autoLoadOnStartup = true,
    dark = false,
    english = true,
)

@Preview(name = "本地模型 自动加载关闭 内屏深中840", widthDp = 840, heightDp = 840, locale = "zh-rCN")
@Composable
fun LocalModelsAutoLoadInnerPaneDarkPreview() = LocalModelsAutoLoadProtoScreen(
    engineEnabled = true,
    autoLoadOnStartup = false,
    dark = true,
)
