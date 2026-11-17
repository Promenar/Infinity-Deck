package com.promenar.infinity.data.local.inference

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderConfig
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.ModelMetadataOverride
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.stableModelId
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 将内存中已加载的本地推理主模型（SlotType.MAIN）动态桥接到会话模型选择器与请求路由；
 * 未加载或总开关关闭时不返回模型条目，从而在会话模型选择列表中隐藏。
 */
internal object LocalSessionModelBridge {
    const val LOCAL_RUNTIME_PROVIDER_ID = "local"
    const val LOCAL_RUNTIME_PROVIDER_NAME = "本地推理"
    private const val PREF_LOCAL_MODELS_ENABLED = "local_models_enabled"

    @Volatile
    private var boundSlotReader: (() -> SlotState?)? = null

    @Volatile
    private var boundEnabledReader: (() -> Boolean)? = null

    internal fun bindRuntime(slotReader: () -> SlotState?, enabledReader: () -> Boolean) {
        boundSlotReader = slotReader
        boundEnabledReader = enabledReader
    }

    internal fun clearBindingForTest() {
        boundSlotReader = null
        boundEnabledReader = null
    }

    fun localModelId(modelName: String): String =
        stableModelId(LOCAL_RUNTIME_PROVIDER_ID, modelName)

    fun isRuntimeLocalModelId(modelId: String?): Boolean =
        !modelId.isNullOrBlank() && modelId.startsWith("$LOCAL_RUNTIME_PROVIDER_ID::")

    fun activeLocalModelInfo(
        slot: SlotState?,
        localInferenceAvailable: Boolean = BuildConfig.LOCAL_INFERENCE_AVAILABLE,
        localModelsEnabled: Boolean = true,
    ): ModelInfo? {
        if (!localInferenceAvailable || !localModelsEnabled || slot == null || !slot.isLoaded || slot.modelName.isBlank()) {
            return null
        }
        val contextTokens = slot.contextSize.takeIf { it > 0 } ?: 8192
        val maxOutput = (contextTokens / 4).coerceIn(1024, 4096)
        val displayName = slot.modelName.removeSuffix(".gguf")
        return ModelInfo(
            name = displayName,
            id = localModelId(slot.modelName),
            description = listOfNotNull(
                slot.modelSize.takeIf(String::isNotBlank),
                slot.quantization.takeIf { it.isNotBlank() && it != "Unknown" },
            ).joinToString(" · "),
            enabled = true,
            type = "reasoning",
            contextLength = contextTokens,
            capabilities = listOf("chat", "reasoning", "toolcalling"),
            providerName = LOCAL_RUNTIME_PROVIDER_NAME,
            providerId = LOCAL_RUNTIME_PROVIDER_ID,
            remoteModelId = slot.modelName,
            maxOutputTokens = maxOutput,
            chatEndpointCompatible = SupportState.SUPPORTED,
            providerMetadata = ModelMetadataOverride(
                displayName = displayName,
                workload = ModelWorkload.GENERATIVE_TEXT,
                capabilities = mapOf(
                    ModelCapability.CHAT_ENDPOINT to SupportState.SUPPORTED,
                    ModelCapability.REASONING to SupportState.SUPPORTED,
                    ModelCapability.TOOL_CALLING to SupportState.SUPPORTED,
                ),
                contextTokens = contextTokens,
                outputTokens = maxOutput,
            ),
        )
    }

    fun activeLocalProviderItem(
        slot: SlotState?,
        localInferenceAvailable: Boolean = BuildConfig.LOCAL_INFERENCE_AVAILABLE,
        localModelsEnabled: Boolean = true,
    ): ProviderListItem? {
        if (!localInferenceAvailable || !localModelsEnabled || slot == null || !slot.isLoaded || slot.modelName.isBlank()) {
            return null
        }
        return ProviderListItem(
            id = LOCAL_RUNTIME_PROVIDER_ID,
            name = LOCAL_RUNTIME_PROVIDER_NAME,
            baseUrl = "",
            model = slot.modelName,
            protocolType = ProtocolType.Local,
            hasApiKey = false,
            hasVertexCredentials = false,
            enabled = true,
        )
    }

    fun activeLocalProviderConfig(
        slot: SlotState?,
        localInferenceAvailable: Boolean = BuildConfig.LOCAL_INFERENCE_AVAILABLE,
        localModelsEnabled: Boolean = true,
    ): ProviderConfig? {
        if (!localInferenceAvailable || !localModelsEnabled || slot == null || !slot.isLoaded || slot.modelName.isBlank()) {
            return null
        }
        return ProviderConfig(
            protocolType = ProtocolType.Local,
            baseUrl = "",
            apiKey = "",
            model = slot.modelName,
            name = LOCAL_RUNTIME_PROVIDER_NAME,
        )
    }

    fun currentActiveModelInfo(
        localInferenceAvailable: Boolean = BuildConfig.LOCAL_INFERENCE_AVAILABLE,
    ): ModelInfo? {
        if (!localInferenceAvailable) return null
        return activeLocalModelInfo(
            slot = boundSlotReader?.invoke(),
            localInferenceAvailable = localInferenceAvailable,
            localModelsEnabled = boundEnabledReader?.invoke() ?: false,
        )
    }

    fun currentActiveProviderItem(
        localInferenceAvailable: Boolean = BuildConfig.LOCAL_INFERENCE_AVAILABLE,
    ): ProviderListItem? {
        if (!localInferenceAvailable) return null
        return activeLocalProviderItem(
            slot = boundSlotReader?.invoke(),
            localInferenceAvailable = localInferenceAvailable,
            localModelsEnabled = boundEnabledReader?.invoke() ?: false,
        )
    }

    fun runtimeContextTokens(
        modelId: String?,
        localInferenceAvailable: Boolean = BuildConfig.LOCAL_INFERENCE_AVAILABLE,
    ): Int? {
        if (!isRuntimeLocalModelId(modelId)) return null
        val active = currentActiveModelInfo(localInferenceAvailable) ?: return null
        return active.contextLength.takeIf { active.id == modelId && it > 0 }
    }

    /**
     * 合并会话可用模型列表：
     * - 当运行期已有已加载的本地主模型时，仅保留该已加载模型并注入实时条目；
     * - 未加载到本地内存的任意 ProtocolType.Local 模型一律从列表隐藏。
     */
    fun mergeVisibleModels(
        persistedModels: List<ModelInfo>,
        loadedLocalModel: ModelInfo?,
        localProviderIds: Set<String>,
        localInferenceAvailable: Boolean = BuildConfig.LOCAL_INFERENCE_AVAILABLE,
    ): List<ModelInfo> {
        val allLocalProviders = localProviderIds + LOCAL_RUNTIME_PROVIDER_ID
        val filtered = persistedModels.filterNot { model ->
            val pid = model.providerId ?: model.id.substringBefore("::").takeIf { "::" in model.id }
            pid in allLocalProviders &&
                (!localInferenceAvailable || loadedLocalModel == null || model.remoteModelId != loadedLocalModel.remoteModelId)
        }
        return if (localInferenceAvailable && loadedLocalModel != null && filtered.none { it.id == loadedLocalModel.id }) {
            filtered + loadedLocalModel
        } else {
            filtered
        }
    }

    @Composable
    fun rememberActiveLocalModelInfo(): ModelInfo? {
        if (!BuildConfig.LOCAL_INFERENCE_AVAILABLE) return null
        val context = LocalContext.current
        val app = context.applicationContext as? InfinityApplication
        val engine = remember(app) {
            app?.takeIf { it.localInferenceRuntimeGate.isAvailable }?.localInferenceEngine
        }
        val mainSlot by (engine?.mainSlot ?: remember { MutableStateFlow(SlotState()) })
            .collectAsStateWithLifecycle()
        var localEnabled by remember(context) {
            mutableStateOf(
                context.getSharedPreferences("infinity_settings", Context.MODE_PRIVATE)
                    .getBoolean(PREF_LOCAL_MODELS_ENABLED, false),
            )
        }
        DisposableEffect(context) {
            val prefs = context.getSharedPreferences("infinity_settings", Context.MODE_PRIVATE)
            localEnabled = prefs.getBoolean(PREF_LOCAL_MODELS_ENABLED, false)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
                if (key == PREF_LOCAL_MODELS_ENABLED) {
                    localEnabled = shared.getBoolean(PREF_LOCAL_MODELS_ENABLED, false)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return remember(mainSlot, localEnabled) {
            activeLocalModelInfo(
                slot = mainSlot,
                localInferenceAvailable = BuildConfig.LOCAL_INFERENCE_AVAILABLE,
                localModelsEnabled = localEnabled,
            )
        }
    }
}
