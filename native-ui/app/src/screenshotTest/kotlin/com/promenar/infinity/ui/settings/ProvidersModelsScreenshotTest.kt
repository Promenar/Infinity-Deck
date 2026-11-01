package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.local.ProviderConnectionOutcome
import com.promenar.infinity.data.local.ProviderConnectionRecord
import com.promenar.infinity.data.local.ProviderStatus
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.catalog.MetadataSource
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.provider.ProviderConnectionProbeFailure
import com.promenar.infinity.ui.common.UnavailableReasons
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/*
 * 提供商列表、提供商编辑、模型列表与模型编辑四页的截图预览。
 * 时间戳取固定的过去日期，相对时间统一落到“具体日期”档，截图稳定。
 */

private const val PreviewTimestamp = 1_767_225_600_000L

@Composable
private fun ProvidersModelsTheme(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

private val PreviewProviders = listOf(
    ProviderListItem(
        id = "deepseek",
        name = "DeepSeek",
        typeName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        protocolType = ProtocolType.DeepSeek,
        hasApiKey = true,
    ),
    ProviderListItem(
        id = "gpu-box",
        name = "GPU Box",
        typeName = "OpenAI Compatible",
        baseUrl = "https://gpu-box.tail3f9c.ts.net:8443/v1",
        protocolType = ProtocolType.Generic_OpenAI_Compat,
        hasApiKey = false,
    ),
    ProviderListItem(
        id = "anthropic",
        name = "Anthropic",
        typeName = "Anthropic",
        baseUrl = "https://api.anthropic.com",
        protocolType = ProtocolType.Anthropic_Messages,
        hasApiKey = true,
        enabled = false,
    ),
)

private val PreviewModels = listOf(
    ModelInfo(
        name = "DeepSeek V4",
        id = "deepseek::deepseek-chat",
        description = "",
        enabled = true,
        type = "chat",
        contextLength = 131_072,
        maxOutputTokens = 8_192,
        capabilities = listOf("chat", "tools", "structuredoutput"),
        providerId = "deepseek",
        remoteModelId = "deepseek-chat",
        metadataSourceByField = mapOf(
            "contextTokens" to MetadataSource.MODELS_DEV,
            "outputTokens" to MetadataSource.PROVIDER,
        ),
    ),
    ModelInfo(
        name = "DeepSeek V4 Reasoner",
        id = "deepseek::deepseek-reasoner",
        description = "",
        enabled = true,
        type = "reasoning",
        contextLength = 131_072,
        capabilities = listOf("chat", "reasoning", "tools"),
        providerId = "deepseek",
        remoteModelId = "deepseek-reasoner",
    ),
    ModelInfo(
        name = "Text Embedding",
        id = "deepseek::embedding",
        description = "",
        enabled = false,
        type = "embedding",
        capabilities = listOf("embedding"),
        providerId = "deepseek",
        remoteModelId = "embedding",
    ),
)

private val PreviewModelsActions = ProviderModelsScreenActions(
    onRefresh = {},
    onAdd = { _, _ -> true },
    onDisableAll = {},
    onDeleteAll = {},
    onUpdate = {},
    onToggle = {},
    onTest = {},
    onCancelTest = {},
    onDelete = {},
    onClearNotice = {},
)

@Composable
private fun ProviderListPreviewContent() {
    ProviderListScreenContent(
        state = ProviderListScreenState(
            providers = PreviewProviders,
            modelCounts = mapOf(
                "deepseek" to ProviderModelCount(enabled = 2, total = 3),
                "gpu-box" to ProviderModelCount(enabled = 1, total = 4),
            ),
            statuses = mapOf(
                "deepseek" to ProviderStatus(
                    lastConnection = ProviderConnectionRecord(ProviderConnectionOutcome.SUCCESS, testedAtMillis = PreviewTimestamp),
                ),
                "gpu-box" to ProviderStatus(
                    lastConnection = ProviderConnectionRecord(
                        ProviderConnectionOutcome.FAILED,
                        failure = ProviderConnectionProbeFailure.NETWORK_UNAVAILABLE,
                        testedAtMillis = PreviewTimestamp,
                    ),
                ),
            ),
            tailnetProviderIds = setOf("gpu-box"),
        ),
        actions = ProviderListScreenActions(),
    )
}

@PreviewTest
@Preview(name = "提供商列表 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "提供商列表 浅色英文 360", widthDp = 360, heightDp = 914, locale = "en")
@Composable
fun providerListProvidersModels() = ProvidersModelsTheme(dark = true) { ProviderListPreviewContent() }

@PreviewTest
@Preview(name = "提供商编辑 深色中文 411", widthDp = 411, heightDp = 1100, locale = "zh-rCN")
@Composable
fun providerFormProvidersModels() = ProvidersModelsTheme(dark = true) {
    ProviderFormContent(
        state = ProviderFormUiState(
            isEditing = true,
            isExistingProvider = true,
            name = "DeepSeek",
            selectedPreset = PROVIDER_PRESETS.first { it.name == "DeepSeek" },
            baseUrl = "https://api.deepseek.com",
            hasStoredCredential = true,
            connectionTestState = ProviderConnectionTestState.Success,
            apiSource = ProviderApiSourceState(
                selected = ProviderApiSource.PUBLIC,
                detected = ProviderApiSource.PUBLIC,
                hint = null,
                builtIn = BuiltInTailscaleStatus.CONNECTED,
            ),
            deleteEntry = UnavailableReasons.ProviderDeleteEntry.ENABLED,
        ),
        actions = ProviderFormActions(),
    )
}

@PreviewTest
@Preview(name = "主槽位提供商编辑 可删除 深色中文 411", widthDp = 411, heightDp = 1100, locale = "zh-rCN")
@Composable
fun providerFormMainProviderDeleteProvidersModels() = ProvidersModelsTheme(dark = true) {
    ProviderFormContent(
        state = ProviderFormUiState(
            isEditing = true,
            isExistingProvider = true,
            name = "DeepSeek",
            selectedPreset = PROVIDER_PRESETS.first { it.name == "DeepSeek" },
            baseUrl = "https://api.deepseek.com",
            hasStoredCredential = true,
            connectionTestState = ProviderConnectionTestState.Success,
            apiSource = ProviderApiSourceState(
                selected = ProviderApiSource.PUBLIC,
                detected = ProviderApiSource.PUBLIC,
                hint = null,
                builtIn = BuiltInTailscaleStatus.CONNECTED,
            ),
            deleteEntry = UnavailableReasons.ProviderDeleteEntry.ENABLED,
        ),
        actions = ProviderFormActions(),
    )
}

@PreviewTest
@Preview(name = "模型列表 深色中文 411", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "模型列表 浅色英文 360", widthDp = 360, heightDp = 914, locale = "en")
@Composable
fun providerModelsProvidersModels() = ProvidersModelsTheme(dark = true) {
    ProviderModelsScreenContent(
        state = ProviderModelsScreenState(
            providerName = "DeepSeek",
            providerId = "deepseek",
            isFetching = false,
            syncNotice = null,
            models = PreviewModels,
            modelTestStates = emptyMap(),
            lastSyncAtMillis = PreviewTimestamp,
        ),
        actions = PreviewModelsActions,
        onNavigateBack = {},
    )
}

@PreviewTest
@Preview(name = "模型编辑 深色中文 411", widthDp = 411, heightDp = 1400, locale = "zh-rCN")
@Composable
fun modelEditorProvidersModels() = ProvidersModelsTheme(dark = true) {
    ModelEditorScreen(
        model = PreviewModels.first(),
        testState = ModelTestState.Idle,
        onClose = {},
        onUpdate = {},
        onTest = {},
        onCancel = {},
        onDelete = {},
    )
}
