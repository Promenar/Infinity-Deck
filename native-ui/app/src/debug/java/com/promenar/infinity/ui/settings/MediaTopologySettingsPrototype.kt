package com.promenar.infinity.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.ScatterPlot
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.data.mediagen.cloud.BillingUnit
import com.promenar.infinity.data.mediagen.cloud.MediaRate
import com.promenar.infinity.data.mediagen.cloud.MediaTariff
import com.promenar.infinity.data.model.MediaModelSettings
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.remote.ModelTimeoutKind
import com.promenar.infinity.data.remote.ModelTimeoutSettings
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.domain.mediagen.CharacterReferenceMode
import com.promenar.infinity.domain.mediagen.GenerationMode
import com.promenar.infinity.domain.mediagen.MediaCapabilities
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.domain.mediagen.PromptFormat
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityChip
import com.promenar.infinity.ui.common.InfinityFieldRow
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityNumericSetting
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityScrollingLine
import com.promenar.infinity.ui.common.InfinitySearchBar
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.common.InfinityTabHeader
import com.promenar.infinity.ui.common.InfinityTagScrollRow
import com.promenar.infinity.ui.common.ModelBrandIcon
import com.promenar.infinity.ui.common.ModelBrandResolver
import com.promenar.infinity.ui.common.ModelBrandTile
import com.promenar.infinity.ui.common.brandAccentColor
import com.promenar.infinity.ui.production.ProductionStatusChip
import com.promenar.infinity.ui.production.ProductionTone
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 高保真隔离原型 v1：云端 API 媒体模型与 ComfyUI 独立兼容层解耦 — 设置层级（设置首页 / 默认模型 / 提供商管理 / 模型管理 / 云端媒体模型编辑）。
 * 仅位于 debug 源集供 Android Compose Preview 验收。
 */

@Composable
internal fun PrototypeThemeSurface(content: @Composable () -> Unit) {
    val mode = if (isSystemInDarkTheme()) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

@Composable
internal fun PrototypeMiniTag(
    text: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = if (emphasized) scheme.primaryContainer else scheme.surfaceContainerHighest,
        border = BorderStroke(
            0.5.dp,
            if (emphasized) scheme.primary.copy(alpha = 0.35f) else scheme.outlineVariant.copy(alpha = 0.65f),
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (emphasized) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Preview(name = "设置首页-浅色412", widthDp = 412, heightDp = 860, locale = "zh-rCN")
@Preview(name = "设置首页-深色412", widthDp = 412, heightDp = 860, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun settingsHomeTopologyPreview() {
    PrototypeThemeSurface {
        InfinitySettingsPageLayout(
            title = "设置",
            horizontalContentPadding = 0.dp,
            header = { InfinityTabHeader(title = "设置") },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
            ) {
                InfinitySettingsSection(
                    title = "常规",
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Palette,
                        title = "外观",
                        subtitle = "主题、字体与动效",
                        onClick = {},
                        showChevron = true,
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Pets,
                        title = "桌宠伙伴",
                        subtitle = "形象与交互行为",
                        onClick = {},
                        showChevron = true,
                    )
                }

                InfinitySettingsSection(
                    title = "模型与服务",
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Storage,
                        title = "提供商管理",
                        subtitle = "管理云端 API 提供商与模型",
                        onClick = {},
                        showChevron = true,
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.SmartToy,
                        title = "默认模型",
                        subtitle = "摘要、向量、重排与云端媒体单选",
                        onClick = {},
                        showChevron = true,
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Dns,
                        title = "ComfyUI",
                        subtitleSegments = listOf("1 台主机在线", "6 个工作流", "6 个提交器"),
                        onClick = {},
                        showChevron = true,
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Memory,
                        title = "本地推理",
                        subtitle = "端侧文本与向量模型",
                        onClick = {},
                        showChevron = true,
                    )
                }

                InfinitySettingsSection(
                    title = "智能体运行",
                    headerStartPadding = InfinitySpacing.ScreenHorizontal,
                ) {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Loop,
                        title = "执行预算",
                        subtitle = "最大步数与上下文阈值",
                        onClick = {},
                        showChevron = true,
                    )
                    InfinitySettingsItem(
                        icon = Icons.Rounded.AutoAwesome,
                        title = "技能管理",
                        subtitle = "已启用 8 项技能",
                        onClick = {},
                        showChevron = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun PrototypeDefaultSlotItem(
    icon: ImageVector,
    title: String,
    modelName: String?,
    providerName: String?,
    emptyHint: String = "未设置",
    onClear: () -> Unit = {},
) {
    InfinitySettingsItem(
        icon = icon,
        title = title,
        subtitle = if (modelName == null) emptyHint else null,
        subtitleSegments = if (modelName != null) listOfNotNull(modelName, providerName) else null,
        subtitleMaxLines = 1,
        onClick = {},
        trailingContent = if (modelName != null) {
            {
                IconButton(onClick = onClear, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "清除默认模型",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

@Preview(name = "默认模型-浅色412", widthDp = 412, heightDp = 1040, locale = "zh-rCN")
@Preview(name = "默认模型-窄屏大字360", widthDp = 360, heightDp = 1180, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun defaultModelsSingleSlotPreview() {
    PrototypeThemeSurface {
        InfinitySettingsPageLayout(title = "默认模型", onBack = {}) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
            ) {
                InfinitySettingsSection(
                    title = "文本与检索模型",
                    footer = "用于对话摘要、知识库向量化与重排检索，每项仅指定一个已启用模型。",
                ) {
                    PrototypeDefaultSlotItem(
                        icon = Icons.AutoMirrored.Rounded.ShortText,
                        title = "摘要模型",
                        modelName = "DeepSeek V4.1 Flash",
                        providerName = "DeepSeek",
                    )
                    PrototypeDefaultSlotItem(
                        icon = Icons.Rounded.Hub,
                        title = "嵌入模型",
                        modelName = "text-embedding-3-large",
                        providerName = "OpenAI",
                    )
                    PrototypeDefaultSlotItem(
                        icon = Icons.AutoMirrored.Rounded.Sort,
                        title = "重排模型",
                        modelName = null,
                        providerName = null,
                    )
                }

                InfinitySettingsSection(
                    title = "云端媒体默认模型",
                    footer = "每类最多设置一个云端默认模型，也可留空。",
                ) {
                    PrototypeDefaultSlotItem(
                        icon = Icons.Rounded.Image,
                        title = "图像模型",
                        modelName = "GPT Image 1.5",
                        providerName = "OpenAI",
                    )
                    PrototypeDefaultSlotItem(
                        icon = Icons.Rounded.Movie,
                        title = "视频模型",
                        modelName = null,
                        providerName = null,
                        emptyHint = "未设置",
                    )
                    PrototypeDefaultSlotItem(
                        icon = Icons.Rounded.RecordVoiceOver,
                        title = "语音模型",
                        modelName = "Speech 2.8 HD",
                        providerName = "MiniMax",
                    )
                }

                InfinitySettingsSection(
                    title = "等待响应超时",
                    footer = "仅作用于云端 API 请求与轮询超时。",
                ) {
                    val unit = "秒"
                    val timeouts = ModelTimeoutSettings()
                    InfinityNumericSetting(
                        title = "对话模型",
                        value = timeouts.seconds(ModelTimeoutKind.LLM),
                        spec = modelTimeoutSpec(ModelTimeoutKind.LLM, unit),
                        description = "单次流式或非流式响应最长等待时间。",
                        onValueChange = {},
                    )
                    InfinityNumericSetting(
                        title = "图像模型",
                        value = timeouts.seconds(ModelTimeoutKind.IMAGE),
                        spec = modelTimeoutSpec(ModelTimeoutKind.IMAGE, unit),
                        description = "云端图像生成单次提交等待时间。",
                        onValueChange = {},
                    )
                }
            }
        }
    }
}

private data class ProtoCloudProviderRow(
    val item: ProviderListItem,
    val enabledModels: Int,
    val totalModels: Int,
    val capabilityTags: List<String>,
    val isTailnet: Boolean = false,
)

@Preview(name = "提供商管理-浅色412", widthDp = 412, heightDp = 860, locale = "zh-rCN")
@Preview(name = "提供商管理-深色412", widthDp = 412, heightDp = 860, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun providerListCloudOnlyPreview() {
    PrototypeThemeSurface {
        val sampleProviders = listOf(
            ProtoCloudProviderRow(
                item = ProviderListItem(id = "p1", name = "OpenAI", baseUrl = "https://api.openai.com/v1", protocolType = ProtocolType.OpenAI_Responses, enabled = true, hasApiKey = true),
                enabledModels = 5,
                totalModels = 8,
                capabilityTags = listOf("对话 6", "图像 2"),
            ),
            ProtoCloudProviderRow(
                item = ProviderListItem(id = "p2", name = "火山方舟", baseUrl = "https://ark.cn-beijing.volces.com/api/v3", protocolType = ProtocolType.OpenAI_ChatCompletions, enabled = true, hasApiKey = true),
                enabledModels = 4,
                totalModels = 7,
                capabilityTags = listOf("对话 4", "视频 3"),
            ),
            ProtoCloudProviderRow(
                item = ProviderListItem(id = "p3", name = "MiniMax", baseUrl = "https://api.minimaxi.com/v1", protocolType = ProtocolType.Anthropic_Messages, enabled = true, hasApiKey = true),
                enabledModels = 5,
                totalModels = 8,
                capabilityTags = listOf("对话 2", "视频 4", "语音 2"),
            ),
            ProtoCloudProviderRow(
                item = ProviderListItem(id = "p4", name = "工作室聚合网关", baseUrl = "http://100.106.180.12:3000/v1", protocolType = ProtocolType.OpenAI_ChatCompletions, enabled = false, hasApiKey = false),
                enabledModels = 0,
                totalModels = 3,
                capabilityTags = listOf("对话 2", "嵌入 1"),
                isTailnet = true,
            ),
        )
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors

        InfinitySettingsPageLayout(
            title = "提供商管理",
            onBack = {},
            horizontalContentPadding = 0.dp,
            actions = {
                IconButton(onClick = {}, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget)) {
                    Icon(Icons.Rounded.Add, contentDescription = "添加云端 API 提供商")
                }
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
            ) {
                Text(
                    text = "共 4 个提供商 · 已启用 3 个", // wrap-check: allow 原型顶部紧凑摘要单行展示
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = InfinitySpacing.SectionHeaderBottom),
                )
                BettboxListGroup(Modifier.padding(horizontal = 16.dp)) {
                    sampleProviders.forEachIndexed { index, row ->
                        val p = row.item
                        val brand = ModelBrandResolver.brandKey(p.name, p.baseUrl)
                        val accent = brandAccentColor(brand)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button, onClick = {})
                                .padding(start = InfinitySpacing.RowHorizontal, end = 4.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(accent?.copy(alpha = 0.14f) ?: scheme.secondaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (brand != null) {
                                    ModelBrandIcon(
                                        modelId = "${p.name} ${p.baseUrl}",
                                        modifier = Modifier.size(22.dp),
                                        fallbackTint = accent ?: scheme.onSurfaceVariant,
                                    )
                                } else {
                                    Text(p.name.take(1), style = MaterialTheme.typography.titleMedium, color = scheme.onSecondaryContainer)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                InfinityTagScrollRow(spacing = 6.dp) {
                                    Text(
                                        text = p.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (p.enabled) scheme.onSurface else scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                    if (!p.enabled) PrototypeMiniTag("已停用")
                                    if (row.isTailnet) PrototypeMiniTag("Tailnet", emphasized = true)
                                }
                                InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                    Text(
                                        text = providerHostLabel(p.baseUrl),
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                                InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                    InfinityMetaLine(
                                        segments = listOf(
                                            if (p.hasApiKey) "凭据已配置" else "缺少 API Key",
                                            "${row.enabledModels}/${row.totalModels} 模型",
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (p.hasApiKey) scheme.onSurfaceVariant else domain.warning,
                                        maxLines = 1,
                                    )
                                }
                                InfinityTagScrollRow(spacing = 6.dp, modifier = Modifier.padding(top = 2.dp)) {
                                    row.capabilityTags.forEach { tag -> PrototypeMiniTag(tag) }
                                }
                            }
                            IconButton(onClick = {}, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget)) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "操作", tint = scheme.onSurfaceVariant)
                            }
                        }
                        if (index < sampleProviders.lastIndex) {
                            InfinityGroupDivider(startInset = InfinitySpacing.RowHorizontal + 40.dp + 12.dp)
                        }
                    }
                }
            }
        }
    }
}

private data class ProtoModelListItem(
    val info: ModelInfo,
    val typeLabel: String,
    val isDefaultRole: String? = null,
    val mediaCapsSummary: List<String> = emptyList(),
)

@Preview(name = "提供商模型管理-浅色412", widthDp = 412, heightDp = 980, locale = "zh-rCN")
@Composable
fun providerModelsCloudMediaPreview() {
    PrototypeThemeSurface {
        var search by remember { mutableStateOf("") }
        var selectedFilter by remember { mutableStateOf("全部") }
        val filters = listOf("全部 (8)", "对话 (3)", "图像 (2)", "视频 (2)", "语音 (1)", "嵌入")
        val scheme = MaterialTheme.colorScheme

        val sampleModels = listOf(
            ProtoModelListItem(
                info = ModelInfo(id = "ark::doubao-seedance-2-0", remoteModelId = "doubao-seedance-2-0", name = "Seedance 2.0", description = "", type = "video", enabled = true, capabilities = listOf("videounderstanding", "audioinput")),
                typeLabel = "视频",
                isDefaultRole = "默认视频",
                mediaCapsSummary = listOf("文生/首尾帧/参考", "9图 3视频", "4K"),
            ),
            ProtoModelListItem(
                info = ModelInfo(id = "ark::doubao-seedance-2-5-fast", remoteModelId = "doubao-seedance-2-5-fast", name = "Seedance 2.5 Fast", description = "", type = "video", enabled = true, capabilities = listOf("audioinput")),
                typeLabel = "视频",
                mediaCapsSummary = listOf("参考生视频", "30图", "原生音轨"),
            ),
            ProtoModelListItem(
                info = ModelInfo(id = "ark::doubao-seedream-4-0", remoteModelId = "doubao-seedream-4-0", name = "Seedream 4.0", description = "", type = "image", enabled = true),
                typeLabel = "图像",
                isDefaultRole = "默认图像",
                mediaCapsSummary = listOf("文生图", "图生图", "角色整卡"),
            ),
            ProtoModelListItem(
                info = ModelInfo(id = "ark::deepseek-v4-1-flash", remoteModelId = "deepseek-v4-1-flash", name = "DeepSeek V4.1 Flash", description = "", type = "chat", enabled = true, contextLength = 128000, capabilities = listOf("toolcalling", "structuredoutput")),
                typeLabel = "对话",
            ),
            ProtoModelListItem(
                info = ModelInfo(id = "ark::speech-2-8-hd", remoteModelId = "speech-2-8-hd", name = "Speech 2.8 HD", type = "audio", enabled = false, description = "MiniMax TTS"),
                typeLabel = "语音",
                mediaCapsSummary = listOf("TTS 语音合成", "MP3 / WAV"),
            ),
        )

        InfinitySettingsPageLayout(
            title = "火山方舟 · 模型管理", // wrap-check: allow 原型顶栏标题短语
            onBack = {},
            actions = {
                IconButton(onClick = {}, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget)) {
                    Icon(Icons.Rounded.Add, contentDescription = "添加模型")
                }
                IconButton(onClick = {}, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多操作")
                }
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfinitySearchBar(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = "搜索模型名称或 ID",
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = scheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("4 / 8 个已启用", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                            Text("上次同步：3 分钟前", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                        FilledTonalButton(onClick = {}, modifier = Modifier.height(44.dp)) {
                            Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("同步", maxLines = 1)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    filters.forEach { label ->
                        val active = label.startsWith(selectedFilter)
                        InfinityChip(
                            label = label,
                            selected = active,
                            onClick = { selectedFilter = label.substringBefore(" ") },
                        )
                    }
                }

                BettboxListGroup {
                    sampleModels.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button, onClick = {})
                                .padding(start = InfinitySpacing.RowHorizontal, end = 8.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ModelBrandTile(
                                modelId = item.info.remoteModelId,
                                size = 36.dp,
                                shape = RoundedCornerShape(10.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                InfinityTagScrollRow(spacing = 6.dp) {
                                    Text(
                                        text = item.info.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = scheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                    item.isDefaultRole?.let { PrototypeMiniTag(it, emphasized = true) }
                                }
                                InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                    Text(
                                        text = item.info.remoteModelId,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PrototypeMiniTag(item.typeLabel, emphasized = item.info.type in setOf("image", "video", "audio"))
                                    if (item.mediaCapsSummary.isNotEmpty()) {
                                        InfinityScrollingLine(Modifier.weight(1f)) {
                                            InfinityMetaLine(
                                                segments = item.mediaCapsSummary,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = scheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                    } else if (item.info.contextLength > 0) {
                                        Text("128K", style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace), color = scheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            InfinitySwitch(
                                checked = item.info.enabled,
                                onCheckedChange = {},
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                        if (index < sampleModels.lastIndex) {
                            InfinityGroupDivider(startInset = InfinitySpacing.RowHorizontal + 36.dp + 12.dp)
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "云端媒体模型编辑-浅色412", widthDp = 412, heightDp = 1280, locale = "zh-rCN")
@Composable
fun cloudMediaModelEditorPreview() {
    PrototypeThemeSurface {
        var displayName by remember { mutableStateOf("Seedance 2.0") }
        var remoteId by remember { mutableStateOf("doubao-seedance-2-0") }
        var selectedType by remember { mutableStateOf("video") }
        var refMode by remember { mutableStateOf(CharacterReferenceMode.SEPARATE) }

        val types = listOf(
            InfinitySegment("chat", "对话", leading = { Icon(Icons.Rounded.ChatBubbleOutline, null, Modifier.size(16.dp)) }),
            InfinitySegment("reasoning", "推理", leading = { Icon(Icons.Rounded.Psychology, null, Modifier.size(16.dp)) }),
            InfinitySegment("image", "图像", leading = { Icon(Icons.Rounded.Image, null, Modifier.size(16.dp)) }),
            InfinitySegment("video", "视频", leading = { Icon(Icons.Rounded.Movie, null, Modifier.size(16.dp)) }),
            InfinitySegment("audio", "语音", leading = { Icon(Icons.Rounded.GraphicEq, null, Modifier.size(16.dp)) }),
            InfinitySegment("embedding", "嵌入", leading = { Icon(Icons.Rounded.ScatterPlot, null, Modifier.size(16.dp)) }),
            InfinitySegment("rerank", "重排", leading = { Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(16.dp)) }),
        )

        val caps = MediaCapabilities(
            modes = setOf(GenerationMode.TEXT, GenerationMode.FIRST_FRAME, GenerationMode.FIRST_LAST_FRAME, GenerationMode.REFERENCE),
            maxImages = 9,
            maxVideos = 3,
            maxAudio = 3,
            nativeAudio = true,
            resolutions = listOf("480p", "720p", "1080p", "4K"),
            minDurationSeconds = 4,
            maxDurationSeconds = 15,
            promptFormat = PromptFormat.SEEDANCE,
            characterReferenceMode = refMode,
        )
        val settings = MediaModelSettings(
            group = MediaGroup.VIDEO,
            capabilities = caps,
            tariff = MediaTariff(currency = "CNY", rates = listOf(MediaRate(BillingUnit.OUTPUT_SECONDS, amount = "0.80", perUnits = 1))),
        )

        InfinitySettingsPageLayout(title = "编辑模型", onBack = {}) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
            ) {
                InfinitySettingsSection(
                    title = "身份",
                    footer = "规范 ID：volcengine/doubao-seedance-2-0",
                ) {
                    InfinityFieldRow(label = "显示名称", value = displayName, onValueChange = { displayName = it })
                    InfinityGroupDivider()
                    InfinityFieldRow(label = "模型 ID", value = remoteId, onValueChange = { remoteId = it }, monospace = true)
                }

                InfinitySettingsSection(title = "模型类型") {
                    InfinitySegmentedControl(
                        segments = types,
                        selected = selectedType,
                        onSelect = { selectedType = it },
                        compact = true,
                        contentPadding = PaddingValues(horizontal = InfinitySpacing.Small),
                        modifier = Modifier.padding(vertical = InfinitySpacing.Small),
                    )
                }

                InfinitySettingsSection(title = "云端媒体规格") {
                    ProtoSingleLineValueRow("接口协议", "火山 Seedance API")
                    InfinityGroupDivider()
                    ProtoSingleLineValueRow("支持模式", listOf("文生视频", "首帧", "首尾帧", "多模态参考"))
                    InfinityGroupDivider()
                    ProtoSingleLineValueRow("参考上限", "9 图 · 3 视频 · 3 音频") // wrap-check: allow 单行规格值固定短语
                    InfinityGroupDivider()
                    ProtoSingleLineValueRow("分辨率与时长", listOf("480p", "720p", "1080p", "4K", "4–15 秒"))
                    InfinityGroupDivider()
                    ProtoSingleLineValueRow("原生音轨", "支持")
                }

                InfinitySettingsSection(title = "角色参考输入") {
                    InfinitySegmentedControl(
                        segments = CharacterReferenceMode.entries.map {
                            InfinitySegment(it, if (it == CharacterReferenceMode.COMPOSITE) "角色整卡" else "分张参考")
                        },
                        selected = refMode,
                        onSelect = { refMode = it },
                        fillWidth = true,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    )
                    if (refMode == CharacterReferenceMode.SEPARATE) {
                        InfinityGroupDivider()
                        ProtoSingleLineValueRow("分张顺序", listOf("全身正面", "全身侧面", "全身背面", "面部特写"))
                    }
                }

                InfinitySettingsSection(title = "计费与费率") {
                    ProtoSingleLineValueRow("结算币种", settings.tariff?.currency ?: "CNY")
                    InfinityGroupDivider()
                    ProtoSingleLineValueRow("按产出秒数", "0.80 / 1 秒")
                    Spacer(Modifier.height(4.dp))
                    InfinityPillButton(
                        text = "编辑费率",
                        onClick = {},
                        tone = InfinityPillTone.NEUTRAL,
                        compact = true,
                        icon = Icons.Rounded.Edit,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                InfinitySettingsSection(title = "连接与操作") {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.CheckCircleOutline, null, tint = MaterialTheme.infinityDomainColors.success, modifier = Modifier.size(18.dp))
                            Text("测试通过 · 214ms", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.infinityDomainColors.success) // wrap-check: allow 测试耗时状态单行展示
                        }
                        FilledTonalButton(onClick = {}, modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget)) {
                            Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("测试")
                        }
                    }
                    InfinityGroupDivider()
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Delete,
                        title = "删除模型",
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = {},
                    )
                }
            }
        }
    }
}
