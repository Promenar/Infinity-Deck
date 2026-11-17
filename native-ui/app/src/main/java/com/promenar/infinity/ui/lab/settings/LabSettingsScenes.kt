package com.promenar.infinity.ui.lab.settings

import android.text.format.Formatter
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.local.inference.BackendType
import com.promenar.infinity.data.local.inference.SlotState
import com.promenar.infinity.data.local.inference.StoredModel
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.tailnet.TailnetEndpoints
import com.promenar.infinity.data.tailnet.TailnetPaths
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.data.tailnet.TailnetPingResult
import com.promenar.infinity.data.tailnet.TailnetSettings
import com.promenar.infinity.data.tailnet.TailnetState
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.lab.chat.isChinese
import com.promenar.infinity.ui.lab.stage.LabScene
import com.promenar.infinity.ui.lab.stage.LabSceneScope
import com.promenar.infinity.ui.lab.stage.LabStateScene
import com.promenar.infinity.ui.lab.stage.LabWorkflowScene
import com.promenar.infinity.ui.lab.tailnet.LabTailnet
import com.promenar.infinity.ui.lab.timeline.LabTimeline
import com.promenar.infinity.ui.lab.timeline.labTimeline
import com.promenar.infinity.ui.settings.BuiltInTailscaleStatus
import com.promenar.infinity.ui.settings.LocalModelsScreenContent
import com.promenar.infinity.ui.settings.PROVIDER_PRESETS
import com.promenar.infinity.ui.settings.ProviderApiSource
import com.promenar.infinity.ui.settings.ProviderApiSourceHint
import com.promenar.infinity.ui.settings.ProviderApiSourceState
import com.promenar.infinity.ui.settings.ProviderConnectionTestState
import com.promenar.infinity.ui.settings.ProviderFormActions
import com.promenar.infinity.ui.settings.ProviderFormContent
import com.promenar.infinity.ui.settings.ProviderFormUiState
import com.promenar.infinity.ui.settings.ProviderListScreenActions
import com.promenar.infinity.ui.settings.ProviderListScreenContent
import com.promenar.infinity.ui.settings.ProviderListScreenState
import com.promenar.infinity.ui.settings.ProviderModelCount
import com.promenar.infinity.ui.settings.ProviderTailnetInfo
import com.promenar.infinity.ui.settings.TailscalePingRun
import com.promenar.infinity.ui.settings.TailscaleSettingsContent
import com.promenar.infinity.ui.settings.TailscaleSettingsUiState

/** 设置目录下的版本：内置 Tailscale 的设备与持续 Ping，以及把 gpu-box 上的网关登记为提供商。 */
internal val labSettingsScenes: Map<String, () -> LabScene> = mapOf(
    // 设备列表停留片刻后打开 gpu-box：每秒一次 Ping，前几次经东京 DERP 中继，打洞成功后转为直连。
    "settings.workflow.tailscale-peers" to {
        LabWorkflowScene(
            timeline = { _ -> peersTimeline() },
            content = { state: PeersState -> TailscalePeersPage(state) },
        )
    },
    // 提供商表单：API 源选 Tailscale 内网，设备行显示 gpu-box，连接测试已通过。
    "settings.state.provider-tailnet" to {
        LabStateScene { ProviderGatewayForm(pickerOpen = false) }
    },
    // 同一表单打开 tailnet 设备面板，gpu-box 已选中，离线的 nas 排在后面。
    "settings.state.provider-gateway-picker" to {
        LabStateScene { ProviderGatewayForm(pickerOpen = true) }
    },
    // 本地模型三槽位：Main、Embedding、Rerank 均已在设备端加载 GGUF。
    "settings.state.local-models" to {
        LabStateScene { LocalModelsScene() }
    },
    // 多品牌云端服务与 Tailscale 私有网关统一列表。
    "settings.state.providers" to {
        LabStateScene { ProvidersListScene() }
    },
)

// —— Tailscale 设备与持续 Ping ——

/** [open] 为真时 gpu-box 的设备面板已打开；[samples] 是面板打开后按发送顺序得到的结果。 */
internal data class PeersState(
    val open: Boolean = false,
    val samples: List<TailnetPingResult> = emptyList(),
)

private const val OPEN_AT = 2_500L
private const val FIRST_PING_AT = 3_000L
private const val RELAYED_PINGS = 3

/** 东京 DERP 中继的三次，之后是直连；数值为毫秒，按顺序逐秒出现。 */
private val RELAY_LATENCIES = listOf(71.4, 68.9, 73.2)
private val DIRECT_LATENCIES = listOf(
    24.6, 19.8, 18.3, 21.7, 17.9, 18.8, 23.4, 19.1, 18.6, 20.2,
    17.5, 18.9, 26.8, 19.4, 18.1, 18.7, 20.9, 17.8, 19.6, 18.4,
)

private fun pingSample(index: Int): TailnetPingResult = if (index < RELAYED_PINGS) {
    TailnetPingResult(path = TailnetPaths.DERP, relayRegion = "tok", relayRegionName = "Tokyo", latencyMs = RELAY_LATENCIES[index])
} else {
    TailnetPingResult(path = TailnetPaths.DIRECT, latencyMs = DIRECT_LATENCIES[index - RELAYED_PINGS])
}

private fun peersTimeline(): LabTimeline<PeersState> = labTimeline(PeersState()) {
    at(OPEN_AT) { it.copy(open = true) }
    repeat(RELAYED_PINGS + DIRECT_LATENCIES.size) { index ->
        at(FIRST_PING_AT + index * 1_000L) { it.copy(samples = it.samples + pingSample(index)) }
    }
    hold(3_000)
}

private fun peersUiState(state: PeersState): TailscaleSettingsUiState {
    val last = state.samples.lastOrNull()
    val gpuBoxPath = last?.path ?: TailnetPaths.DERP
    return TailscaleSettingsUiState(
        available = true,
        settings = TailnetSettings(enabled = true, hasAuthKey = true),
        tailnet = TailnetState(phase = TailnetPhase.RUNNING, summary = LabTailnet.summary, peers = LabTailnet.peers(gpuBoxPath)),
        ping = if (state.open) TailscalePingRun(LabTailnet.GPU_BOX, state.samples) else null,
        lastPing = last?.let { mapOf(LabTailnet.GPU_BOX to it) }.orEmpty(),
    )
}

@Composable
private fun LabSceneScope.TailscalePeersPage(state: PeersState) {
    val listState = rememberLazyListState()
    InfinitySettingsPageLayout(title = stringResource(R.string.tailscale_title), onBack = onExit) { contentPadding ->
        // 面板只在首次组合时按 initialOpenPeer 打开；开合切换时重建内容，列表位置由外层保留。
        key(state.open) {
            TailscaleSettingsContent(
                state = peersUiState(state),
                contentPadding = contentPadding,
                listState = listState,
                initialOpenPeer = LabTailnet.GPU_BOX.takeIf { state.open },
            )
        }
    }
}

// —— 提供商：经 Tailscale 访问的模型网关 ——

@Composable
private fun LabSceneScope.ProviderGatewayForm(pickerOpen: Boolean) {
    val zh = isChinese(LocalContext.current.resources)
    val peers = remember { LabTailnet.magicDnsPeers() }
    var state by remember(zh) {
        mutableStateOf(ProviderFormUiState(
            isEditing = true,
            isExistingProvider = true,
            name = if (zh) "家里的网关" else "Home gateway",
            selectedPreset = PROVIDER_PRESETS.first { it.name == "Custom" },
            baseUrl = LabTailnet.GATEWAY_URL,
            hasStoredCredential = true,
            connectionTestState = ProviderConnectionTestState.Success,
            apiSource = ProviderApiSourceState(
                selected = ProviderApiSource.TAILNET,
                detected = ProviderApiSource.TAILNET,
                hint = ProviderApiSourceHint.RECOGNIZED_TAILNET,
                builtIn = BuiltInTailscaleStatus.CONNECTED,
            ),
            tailnetInfo = ProviderTailnetInfo(selfName = LabTailnet.SELF, tailnetName = LabTailnet.NAME, peers = peers),
        ))
    }
    val actions = remember(zh) {
        ProviderFormActions(
            onBack = onExit,
            // 与正式表单相同：选中设备后接口地址换成该设备，连接测试回到未测。
            onTailnetPeerSelected = { peer ->
                TailnetEndpoints.addressForPeer(peer, state.baseUrl)?.let { address ->
                    state = state.copy(baseUrl = address, connectionTestState = ProviderConnectionTestState.Idle)
                }
            },
        )
    }
    // 打开态复用表单里真实的设备行与面板：量到设备行位置后打开，宽窗口贴着设备行弹出。
    ProviderFormContent(state = state, actions = actions, initialPeerPickerOpen = pickerOpen)
}

// —— 本地模型（llama.cpp 三槽位） ——

@Composable
private fun LabSceneScope.LocalModelsScene() {
    val context = LocalContext.current
    val mainPath = "/data/user/0/com.promenar.infinity/files/models/Qwen3-4B-Q8_0.gguf"
    val embedPath = "/data/user/0/com.promenar.infinity/files/models/bge-small-zh-v1.5-f16.gguf"
    val rerankPath = "/data/user/0/com.promenar.infinity/files/models/bge-reranker-v2-m3-q8_0.gguf"
    val models = remember {
        listOf(
            StoredModel(
                id = "qwen3-4b",
                fileName = "Qwen3-4B-Q8_0.gguf",
                filePath = mainPath,
                sizeBytes = 4_290_000_000L,
                format = "GGUF",
                addedAt = 1_763_000_000_000L,
                architecture = "qwen3",
                quantization = "Q8_0",
                contextLength = 32_768,
                parameterCount = "4B",
                modelName = "Qwen3-4B",
            ),
            StoredModel(
                id = "bge-small-zh",
                fileName = "bge-small-zh-v1.5-f16.gguf",
                filePath = embedPath,
                sizeBytes = 48_200_000L,
                format = "GGUF",
                addedAt = 1_762_990_000_000L,
                architecture = "bert",
                quantization = "F16",
                contextLength = 512,
                embeddingLength = 512,
                parameterCount = "24M",
                modelName = "bge-small-zh-v1.5",
            ),
            StoredModel(
                id = "bge-reranker-v2-m3",
                fileName = "bge-reranker-v2-m3-q8_0.gguf",
                filePath = rerankPath,
                sizeBytes = 635_000_000L,
                format = "GGUF",
                addedAt = 1_762_980_000_000L,
                architecture = "xlm-roberta",
                quantization = "Q8_0",
                contextLength = 8_192,
                parameterCount = "568M",
                modelName = "bge-reranker-v2-m3",
            ),
        )
    }
    LocalModelsScreenContent(
        availableModels = models,
        engineEnabled = true,
        gpuAvailable = true,
        mainSlot = SlotState(
            modelPath = mainPath,
            isLoaded = true,
            backendType = BackendType.LLAMA_VULKAN,
            modelName = "Qwen3-4B-Q8_0.gguf",
            quantization = "Q8_0",
            contextSize = 8_192,
        ),
        embeddingSlot = SlotState(
            modelPath = embedPath,
            isLoaded = true,
            backendType = BackendType.LLAMA_CPU,
            modelName = "bge-small-zh-v1.5-f16.gguf",
            quantization = "F16",
            contextSize = 512,
        ),
        rerankSlot = SlotState(
            modelPath = rerankPath,
            isLoaded = true,
            backendType = BackendType.LLAMA_CPU,
            modelName = "bge-reranker-v2-m3-q8_0.gguf",
            quantization = "Q8_0",
            contextSize = 8_192,
        ),
        onNavigateBack = onExit,
        formatFileSize = { bytes -> Formatter.formatShortFileSize(context, bytes) },
    )
}

// —— 多品牌云端与 Tailnet 提供商 ——

@Composable
private fun LabSceneScope.ProvidersListScene() {
    val zh = isChinese(LocalContext.current.resources)
    val state = remember(zh) {
        ProviderListScreenState(
            providers = listOf(
                ProviderListItem(
                    id = "deepseek",
                    name = "DeepSeek",
                    baseUrl = "https://api.deepseek.com",
                    model = "deepseek-chat",
                    protocolType = ProtocolType.DeepSeek,
                    hasApiKey = true,
                ),
                ProviderListItem(
                    id = "anthropic",
                    name = "Anthropic",
                    baseUrl = "https://api.anthropic.com",
                    model = "claude-sonnet-4-6",
                    protocolType = ProtocolType.Anthropic_Messages,
                    hasApiKey = true,
                ),
                ProviderListItem(
                    id = "gemini",
                    name = "Google Gemini",
                    baseUrl = "https://generativelanguage.googleapis.com",
                    model = "gemini-3.1-pro",
                    protocolType = ProtocolType.Google_Gemini,
                    hasApiKey = true,
                ),
                ProviderListItem(
                    id = "openai",
                    name = "OpenAI",
                    baseUrl = "https://api.openai.com",
                    model = "gpt-5.2",
                    protocolType = ProtocolType.OpenAI_Responses,
                    hasApiKey = true,
                ),
                ProviderListItem(
                    id = "gpu-box",
                    name = if (zh) "家里的网关 · Qwen" else "Home gateway · Qwen",
                    baseUrl = LabTailnet.GATEWAY_URL,
                    model = "qwen3.5-32b",
                    protocolType = ProtocolType.Generic_OpenAI_Compat,
                    hasApiKey = true,
                ),
            ),
            modelCounts = mapOf(
                "deepseek" to ProviderModelCount(3, 4),
                "anthropic" to ProviderModelCount(3, 3),
                "gemini" to ProviderModelCount(4, 5),
                "openai" to ProviderModelCount(4, 6),
                "gpu-box" to ProviderModelCount(2, 2),
            ),
            tailnetProviderIds = setOf("gpu-box"),
        )
    }
    ProviderListScreenContent(
        state = state,
        actions = ProviderListScreenActions(onNavigateBack = onExit),
    )
}
