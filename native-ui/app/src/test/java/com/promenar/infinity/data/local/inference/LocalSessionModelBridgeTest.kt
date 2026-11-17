package com.promenar.infinity.data.local.inference

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.PreparedPromptBudgetGate
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.data.remote.DefaultProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ProviderResolutionError
import com.promenar.infinity.data.remote.UnifiedLlmClient
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.model.Session
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Test

class LocalSessionModelBridgeTest {

    @After
    fun tearDown() {
        LocalSessionModelBridge.clearBindingForTest()
    }

    private fun loadedSlot(
        modelName: String = "Qwen3-0.6B-Q4_K_M.gguf",
        contextSize: Int = 16_384,
        isLoaded: Boolean = true,
    ) = SlotState(
        modelName = modelName,
        modelSize = "639 MB",
        quantization = "Q4_K_M",
        contextSize = contextSize,
        isLoaded = isLoaded,
    )

    @Test
    fun `activeLocalModelInfo only exposes model when available enabled and loaded`() {
        val slot = loadedSlot()
        val info = LocalSessionModelBridge.activeLocalModelInfo(
            slot = slot,
            localInferenceAvailable = true,
            localModelsEnabled = true,
        )
        assertThat(info).isNotNull()
        assertThat(info!!.id).isEqualTo("local::Qwen3-0.6B-Q4_K_M.gguf")
        assertThat(info.name).isEqualTo("Qwen3-0.6B-Q4_K_M")
        assertThat(info.contextLength).isEqualTo(16_384)
        assertThat(info.maxOutputTokens).isEqualTo(4096)
        assertThat(info.capabilities).containsAtLeast("chat", "reasoning", "toolcalling")
        assertThat(info.providerMetadata?.capabilities?.get(ModelCapability.REASONING))
            .isEqualTo(SupportState.SUPPORTED)
        assertThat(info.providerMetadata?.capabilities?.get(ModelCapability.TOOL_CALLING))
            .isEqualTo(SupportState.SUPPORTED)

        assertThat(
            LocalSessionModelBridge.activeLocalModelInfo(
                slot = slot.copy(isLoaded = false),
                localInferenceAvailable = true,
                localModelsEnabled = true,
            ),
        ).isNull()
        assertThat(
            LocalSessionModelBridge.activeLocalModelInfo(
                slot = slot,
                localInferenceAvailable = true,
                localModelsEnabled = false,
            ),
        ).isNull()
        assertThat(
            LocalSessionModelBridge.activeLocalModelInfo(
                slot = slot,
                localInferenceAvailable = false,
                localModelsEnabled = true,
            ),
        ).isNull()
    }

    @Test
    fun `mergeVisibleModels hides unloaded local models and keeps remote models`() {
        val remoteModel = ModelInfo(
            name = "GPT-5",
            id = "openai::gpt-5",
            description = "",
            enabled = true,
            providerId = "openai",
            remoteModelId = "gpt-5",
        )
        val staleLocalModel = ModelInfo(
            name = "OldLocal",
            id = "legacy-local::old.gguf",
            description = "",
            enabled = true,
            providerId = "legacy-local",
            remoteModelId = "old.gguf",
        )
        val runtimeLocalModel = ModelInfo(
            name = "StaleRuntime",
            id = "local::stale.gguf",
            description = "",
            enabled = true,
            providerId = "local",
            remoteModelId = "stale.gguf",
        )
        val activeLocal = LocalSessionModelBridge.activeLocalModelInfo(
            slot = loadedSlot(modelName = "Qwen3.gguf", contextSize = 8192),
            localInferenceAvailable = true,
            localModelsEnabled = true,
        )

        val hiddenWhenUnloaded = LocalSessionModelBridge.mergeVisibleModels(
            persistedModels = listOf(remoteModel, staleLocalModel, runtimeLocalModel),
            loadedLocalModel = null,
            localProviderIds = setOf("legacy-local"),
            localInferenceAvailable = true,
        )
        assertThat(hiddenWhenUnloaded).containsExactly(remoteModel)

        val shownWhenLoaded = LocalSessionModelBridge.mergeVisibleModels(
            persistedModels = listOf(remoteModel, staleLocalModel, runtimeLocalModel),
            loadedLocalModel = activeLocal,
            localProviderIds = setOf("legacy-local"),
            localInferenceAvailable = true,
        )
        assertThat(shownWhenLoaded).containsExactly(remoteModel, activeLocal).inOrder()

        val hiddenOnPlay = LocalSessionModelBridge.mergeVisibleModels(
            persistedModels = listOf(remoteModel, staleLocalModel, runtimeLocalModel),
            loadedLocalModel = activeLocal,
            localProviderIds = setOf("legacy-local"),
            localInferenceAvailable = false,
        )
        assertThat(hiddenOnPlay).containsExactly(remoteModel)
    }

    @Test
    fun `router resolves runtime loaded local model when bound and hides unloaded`() {
        val slotHolder = arrayOf(loadedSlot(modelName = "Qwen3-0.6B.gguf", contextSize = 16_384, isLoaded = true))
        var enabled = true
        LocalSessionModelBridge.bindRuntime(
            slotReader = { slotHolder[0] },
            enabledReader = { enabled },
        )

        val router = DefaultProviderRequestRouter(
            modelResolver = { id ->
                LocalSessionModelBridge.currentActiveModelInfo(localInferenceAvailable = true)?.takeIf { it.id == id }
            },
            providerResolver = { pid ->
                LocalSessionModelBridge.currentActiveProviderItem(localInferenceAvailable = true)?.takeIf { it.id == pid }
            },
            configResolver = { pid ->
                if (pid == LocalSessionModelBridge.LOCAL_RUNTIME_PROVIDER_ID) {
                    LocalSessionModelBridge.activeLocalProviderConfig(
                        slot = slotHolder[0],
                        localInferenceAvailable = true,
                        localModelsEnabled = enabled,
                    )
                } else {
                    null
                }
            },
            localInferenceAvailable = { true },
            clientFactory = { cfg -> UnifiedLlmClient(providerConfigResolver = { cfg }) },
        )

        val success = router.resolve("local::Qwen3-0.6B.gguf") as ProviderResolution.Success
        assertThat(success.value.config.protocolType).isEqualTo(ProtocolType.Local)
        assertThat(success.value.remoteModelId).isEqualTo("Qwen3-0.6B.gguf")

        slotHolder[0] = slotHolder[0].copy(isLoaded = false)
        val unloadedFailure = router.resolve("local::Qwen3-0.6B.gguf") as ProviderResolution.Failure
        assertThat(unloadedFailure.reason).isEqualTo(ProviderResolutionError.MODEL_NOT_FOUND)

        slotHolder[0] = slotHolder[0].copy(isLoaded = true)
        enabled = false
        val disabledFailure = router.resolve("local::Qwen3-0.6B.gguf") as ProviderResolution.Failure
        assertThat(disabledFailure.reason).isEqualTo(ProviderResolutionError.MODEL_NOT_FOUND)
    }

    @Test
    fun `budget gate resolves adaptive context size and reserve for runtime local model`() {
        LocalSessionModelBridge.bindRuntime(
            slotReader = { loadedSlot(modelName = "Qwen3-1.7B.gguf", contextSize = 16_384, isLoaded = true) },
            enabledReader = { true },
        )
        val prefs = mockk<SharedPreferences> {
            every { getInt(any(), any()) } returns 0
        }
        val modelId = "local::Qwen3-1.7B.gguf"
        val session = Session(
            id = "s1",
            agentId = "agent-1",
            title = "本地推理测试",
            modelId = modelId,
        )
        val request = PromptRequest(
            messages = listOf(ProtocolMessage("user", "总结当前文档")),
            model = "Qwen3-1.7B.gguf",
        )
        val window = PreparedPromptBudgetGate.resolveWindow(
            session = session,
            prompt = request,
            settings = prefs,
            stableModelId = modelId,
        )
        assertThat(window.modelContextTokens).isEqualTo(16_384)
        assertThat(window.outputReserveTokens).isIn(1_024..4_096)
    }
}
