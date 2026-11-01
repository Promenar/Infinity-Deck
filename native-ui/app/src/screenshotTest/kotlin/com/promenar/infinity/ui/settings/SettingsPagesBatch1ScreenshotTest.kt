package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.termux.TermuxAvailability
import com.promenar.infinity.data.termux.TermuxCommandResult
import com.promenar.infinity.domain.repository.CacheUsageAggregate
import com.promenar.infinity.domain.repository.DailyTokenStats
import com.promenar.infinity.domain.repository.plus
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.hub.UserSettingsHomeScreenActions
import com.promenar.infinity.ui.hub.UserSettingsHomeScreenContent
import com.promenar.infinity.ui.hub.UserSettingsHomeScreenState
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/** 截图只消费展示状态，不创建 ViewModel、连接服务或读写设备数据。 */
@Composable
private fun Batch1PagesTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

private val Batch1ToolsState = SkillsSettingsVisualState(
    loopLimit = 50,
    tokenLimit = 0,
    repeatLimit = 3,
)

/** 执行环境与连接各行已移到设置首页；非预算变体展示这一组。 */
@Composable
private fun Batch1ToolsContent(budgetOnly: Boolean = false) {
    InfinitySettingsPageLayout(title = stringResource(R.string.skills_title), onBack = {}) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
            if (budgetOnly) {
                AgentRuntimeSettingsCard(Batch1ToolsState, SkillsSettingsVisualActions())
            } else {
                InfinitySettingsSection(title = stringResource(R.string.settings_ia_section_environment)) {
                    EnvironmentConnectionRows(
                        state = EnvironmentConnectionState(
                            termuxAvailability = TermuxAvailability(false, null, false),
                            sshHostCount = 0,
                            networkHostCount = 0,
                            gitCredentialCount = 0,
                        ),
                        onOpen = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun Batch1TermuxContent(failed: Boolean = false) {
    InfinitySettingsPageLayout(title = stringResource(R.string.termux_title), onBack = {}) { padding ->
        TermuxSettingsContent(
            state = TermuxSettingsUiState(
                enabled = true,
                settingsLoaded = true,
                availability = TermuxAvailability(true, "0.119.1", true),
                diagnostic = if (failed) TermuxCommandResult(
                    stdout = "",
                    stderr = "Permission denied",
                    exitCode = 1,
                    errorCode = "TERMUX_COMMAND_FAILED",
                ) else TermuxCommandResult(stdout = "INFINITY_TERMUX_OK\n", exitCode = 0),
            ),
            contentPadding = padding,
        )
    }
}

private val Batch1PreviewModel = ModelInfo(
    name = "Infinity Workbench Chat",
    id = "preview::infinity-chat",
    description = "",
    enabled = true,
    type = "chat",
    contextLength = 128_000,
    capabilities = listOf("chat", "toolcalling", "vision"),
    providerName = "Infinity Workbench",
    providerId = "preview",
    remoteModelId = "infinity-chat",
)

private val Batch1ModelsActions = ProviderModelsScreenActions(
    onRefresh = {}, onAdd = { _, _ -> true }, onDisableAll = {}, onDeleteAll = {},
    onUpdate = {}, onToggle = {}, onTest = {}, onCancelTest = {}, onDelete = {}, onClearNotice = {},
)

@Composable
private fun Batch1ProviderModelsContent(populated: Boolean = false) {
    ProviderModelsScreenContent(
        state = ProviderModelsScreenState(
            providerName = "Infinity Workbench",
            providerId = "preview",
            isFetching = false,
            syncNotice = null,
            models = if (populated) listOf(Batch1PreviewModel) else emptyList(),
            modelTestStates = emptyMap(),
        ),
        actions = Batch1ModelsActions,
        onNavigateBack = {},
    )
}

@PreviewTest
@Preview(name = "设置首页 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "设置首页 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun settingsHomeBatch1() = Batch1PagesTheme {
    UserSettingsHomeScreenContent(UserSettingsHomeScreenState(userName = "Infinity Workbench"), UserSettingsHomeScreenActions())
}

@PreviewTest
@Preview(name = "工具设置顶部 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "工具设置顶部 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun toolsPageBatch1() = Batch1PagesTheme {
    Batch1ToolsContent()
}

@PreviewTest
@Preview(name = "工具执行预算 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "工具执行预算 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun toolsBudgetBatch1() = Batch1PagesTheme {
    Batch1ToolsContent(budgetOnly = true)
}

@PreviewTest
@Preview(name = "搜索配置 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "搜索配置 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun searchConfigBatch1() = Batch1PagesTheme {
    SearchConfigContent()
}

@PreviewTest
@Preview(name = "搜索配置 域名过滤 深色中文 411", widthDp = 411, heightDp = 1200, locale = "zh-rCN")
@Composable
fun searchConfigDomainsBatch1() = Batch1PagesTheme {
    SearchConfigContent(
        searchState = SearchConfigState(includeDomains = listOf("developer.android.com", "kotlinlang.org")),
    )
}

@PreviewTest
@Preview(name = "Termux 已就绪 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "Termux 已就绪 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun termuxReadyBatch1() = Batch1PagesTheme {
    Batch1TermuxContent()
}

@PreviewTest
@Preview(name = "Termux 诊断失败 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "Termux 诊断失败 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun termuxDiagnosticFailureBatch1() = Batch1PagesTheme {
    Batch1TermuxContent(failed = true)
}

@PreviewTest
@Preview(name = "SSH 默认 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "SSH 默认 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun sshPageBatch1() = Batch1PagesTheme {
    SshSettingsContent(SshSettingsVisualState(), SshSettingsVisualActions())
}

@PreviewTest
@Preview(name = "SSH 主机 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "SSH 主机 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun sshHostBatch1() = Batch1PagesTheme {
    SshSettingsContent(SshSettingsVisualState(hosts = listOf(SshHost(id = "preview", alias = "home", hostname = "server.example.com", username = "user"))), SshSettingsVisualActions())
}

@PreviewTest
@Preview(name = "备份默认 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "备份默认 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun backupPageBatch1() = Batch1PagesTheme {
    BackupSettingsScreenContent(BackupUiState())
}

@PreviewTest
@Preview(name = "用量默认 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "用量默认 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun tokenUsageBatch1() = Batch1PagesTheme {
    TokenUsageScreenContent(TokenStatsState())
}

private fun cachedTokenStatsState(): TokenStatsState {
    val claudeCache = CacheUsageAggregate(requests = 18, reportedRequests = 18, reportedInputTokens = 1_240_000, cacheReadTokens = 1_010_000, cacheWriteTokens = 96_000)
    val deepSeekCache = CacheUsageAggregate(requests = 9, reportedRequests = 7, reportedInputTokens = 410_000, cacheReadTokens = 288_000)
    val cache = claudeCache + deepSeekCache
    val days = listOf(
        "2026-10-27" to 0.62, "2026-10-28" to 0.71, "2026-10-29" to 0.0, "2026-10-30" to 0.78,
        "2026-10-31" to 0.83, "2026-11-01" to 0.80, "2026-11-02" to 0.86,
    ).mapIndexed { index, (day, rate) ->
        val input = 160_000L + index * 30_000L
        DailyTokenStats(
            day, input, input / 12,
            if (rate == 0.0) CacheUsageAggregate(requests = 2) else CacheUsageAggregate(requests = 4, reportedRequests = 4, reportedInputTokens = input, cacheReadTokens = (input * rate).toLong()),
        )
    }
    return TokenStatsState(
        globalInput = 1_650_000,
        globalOutput = 142_000,
        globalCostUSD = 1.42,
        globalCache = cache,
        globalCacheDiscountMissing = true,
        modelBreakdown = listOf(
            ModelCostInfo("Claude Sonnet 4.6", "claude-sonnet-4-6", 1_240_000, 98_000, 1.07, true, claudeCache),
            ModelCostInfo("DeepSeek Chat", "deepseek-chat", 410_000, 44_000, 0.35, true, deepSeekCache, cacheDiscountMissing = true),
        ),
        dailyTrend = days,
    )
}

@PreviewTest
@Preview(name = "用量缓存 深色中文 411", widthDp = 411, heightDp = 1400, locale = "zh-rCN")
@Preview(name = "用量缓存 深色中文 360", widthDp = 360, heightDp = 1400, locale = "zh-rCN")
@Preview(name = "用量缓存 深色英文 360 大字号", widthDp = 360, heightDp = 1600, locale = "en", fontScale = 1.3f)
@Composable
fun tokenUsageCacheBatch1() = Batch1PagesTheme {
    TokenUsageScreenContent(cachedTokenStatsState())
}

@PreviewTest
@Preview(name = "用量缓存 浅色中文 411", widthDp = 411, heightDp = 1400, locale = "zh-rCN")
@Composable
fun tokenUsageCacheLightBatch1() = Batch1PagesTheme(dark = false) {
    TokenUsageScreenContent(cachedTokenStatsState())
}

@PreviewTest
@Preview(name = "提供商列表默认 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "提供商列表默认 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun providerListBatch1() = Batch1PagesTheme {
    ProviderListScreenContent(ProviderListScreenState(), ProviderListScreenActions())
}

@PreviewTest
@Preview(name = "提供商已配置 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "提供商已配置 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun providerConfiguredBatch1() = Batch1PagesTheme {
    ProviderListScreenContent(ProviderListScreenState(providers = listOf(ProviderListItem(id = "preview", name = "Infinity Workbench", typeName = "OpenAI", baseUrl = "https://api.example.com", protocolType = ProtocolType.OpenAI_ChatCompletions, hasApiKey = true))), ProviderListScreenActions())
}

@PreviewTest
@Preview(name = "提供商表单 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "提供商表单 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun providerFormBatch1() = Batch1PagesTheme {
    ProviderFormContent(ProviderFormUiState(), ProviderFormActions())
}

@PreviewTest
@Preview(name = "模型列表默认 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "模型列表默认 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun providerModelsBatch1() = Batch1PagesTheme {
    Batch1ProviderModelsContent()
}

@PreviewTest
@Preview(name = "模型列表已配置 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "模型列表已配置 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun providerModelRowBatch1() = Batch1PagesTheme {
    Batch1ProviderModelsContent(populated = true)
}

@PreviewTest
@Preview(name = "默认模型 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "默认模型 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun defaultModelsBatch1() = Batch1PagesTheme {
    DefaultModelsScreenContent(DefaultModelsScreenState(), DefaultModelsScreenActions())
}

@PreviewTest
@Preview(name = "本地模型 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "本地模型 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun localModelsBatch1() = Batch1PagesTheme {
    LocalModelsScreenContent()
}

@PreviewTest
@Preview(name = "主题文字缩放开启 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "主题文字缩放开启 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun themeTextScaleBatch1() = Batch1PagesTheme {
    ThemeScreenContent(ThemeUiState(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, textScaleEnabled = true), dynamicColorAvailable = true), onModeSelect = {}, onColorSourceSelect = {}, onNavigateBack = {})
}

@PreviewTest
@Preview(name = "模型编辑 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "模型编辑 深色中文 360", widthDp = 360, heightDp = 914, locale = "zh-rCN")
@Composable
fun modelEditorBatch1() = Batch1PagesTheme {
    ModelEditorScreen(Batch1PreviewModel, ModelTestState.Idle, onClose = {}, onUpdate = {}, onTest = {}, onCancel = {}, onDelete = {})
}

@PreviewTest
@Preview(name = "设置首页 浅色英文", widthDp = 411, heightDp = 914, locale = "en")
@Composable
fun settingsHomeBatch1LightEnglish() = Batch1PagesTheme(dark = false) {
    UserSettingsHomeScreenContent(UserSettingsHomeScreenState(userName = "Infinity Workbench"), UserSettingsHomeScreenActions())
}

@PreviewTest
@Preview(name = "设置首页 深色中文 字号130", widthDp = 360, heightDp = 914, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun settingsHomeBatch1LargeText() = Batch1PagesTheme {
    UserSettingsHomeScreenContent(UserSettingsHomeScreenState(userName = "Infinity Workbench"), UserSettingsHomeScreenActions())
}

@PreviewTest
@Preview(name = "工具设置顶部 浅色英文", widthDp = 411, heightDp = 914, locale = "en")
@Composable
fun toolsPageBatch1LightEnglish() = Batch1PagesTheme(dark = false) {
    Batch1ToolsContent()
}

@PreviewTest
@Preview(name = "工具设置顶部 深色中文 字号130", widthDp = 360, heightDp = 914, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun toolsPageBatch1LargeText() = Batch1PagesTheme {
    Batch1ToolsContent()
}

