package com.promenar.infinity.ui.lab.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.promenar.infinity.data.generation.TailnetWaitState
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.data.network.PublicNetworkStatus
import com.promenar.infinity.data.tailnet.TailnetConnectProgress
import com.promenar.infinity.data.tailnet.TailnetConnectStep
import com.promenar.infinity.domain.ask.AskUserOption
import com.promenar.infinity.domain.ask.AskUserQuestion
import com.promenar.infinity.domain.ask.AskUserRequest
import com.promenar.infinity.domain.ask.PendingQuestion
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.secret.PendingSecretInput
import com.promenar.infinity.domain.secret.SecretDestination
import com.promenar.infinity.domain.secret.SecretExposure
import com.promenar.infinity.domain.secret.SecretInputRequest
import com.promenar.infinity.domain.secret.SecretKind
import com.promenar.infinity.ui.chat.ChatScreenActions
import com.promenar.infinity.ui.chat.ChatScreenState
import com.promenar.infinity.ui.chat.ChatUiState
import com.promenar.infinity.ui.chat.ComposerQueueUiState
import com.promenar.infinity.ui.chat.GenerationStatus
import com.promenar.infinity.ui.chat.QueuedMessageUi
import com.promenar.infinity.ui.common.ModelPicker
import com.promenar.infinity.ui.common.ModelSelectionUiModel
import com.promenar.infinity.ui.common.network.NetworkLightsState
import com.promenar.infinity.ui.common.network.TailscaleLightStatus
import com.promenar.infinity.ui.lab.stage.LabScene
import com.promenar.infinity.ui.lab.stage.LabSceneScope
import com.promenar.infinity.ui.lab.stage.LabStateScene
import com.promenar.infinity.ui.lab.tailnet.LabTailnet
import kotlinx.serialization.json.Json

/**
 * 会话页的单一状态版本：触摸锁、排队托盘、提问卡、保密输入卡、模型选择器网络行与 Tailscale 接入等待。
 * 前四个沿用 starlog 开发脚本在某一阶段的画面再叠加该状态，后两个接入家里 gpu-box 上的模型网关。
 */
internal val labChatStateScenes: Map<String, () -> LabScene> = mapOf(
    // 后台完整测试运行中锁定触摸，屏幕只剩运行状态与进度。
    // 长按解锁后回到会话页，返回键再退出版本页；会话页顶栏的锁按钮可以重新锁上。
    "chat.state.touch-lock" to {
        LabStateScene {
            var locked by remember { mutableStateOf(true) }
            val actions = remember { ChatScreenActions(onLockTouch = { locked = true }, onUnlockTouch = { locked = false }) }
            AdeOverlay(ADE_PLAN_UPDATE, AdeTimes.PLAN_UPDATE + 95_000, actions = actions, key = locked) {
                it.copy(screen = it.screen.copy(touchLocked = locked))
            }
        }
    },
    // 生成中写下的两条后续消息排在输入框上方，第二条已请求插队。
    "chat.state.queue" to {
        LabStateScene {
            val text = rememberStatesText()
            AdeOverlay(ADE_BG_TOOLS, AdeTimes.BG_TOOLS + 3_000) { frame ->
                frame.copy(
                    screen = frame.screen.copy(
                        composerQueue = ComposerQueueUiState(
                            items = listOf(
                                QueuedMessageUi("lab-queue-1", text.queueReadme),
                                QueuedMessageUi("lab-queue-2", text.queueIso, steering = true),
                            ),
                        ),
                    ),
                )
            }
        }
    },
    // 动手前用提问卡确认 JSON 的形状与字段。
    "chat.state.ask-user" to {
        LabStateScene {
            val text = rememberStatesText()
            AdeOverlay(ADE_EXPLORED, ASK_AT + 4_000) { frame -> frame.withQuestion(text) }
        }
    },
    // 提交后推送需要 github.com 的访问令牌，令牌经保密输入卡直接存进 Git 凭据。
    "chat.state.secret-input" to {
        LabStateScene {
            val text = rememberStatesText()
            val expiresAt = remember { System.currentTimeMillis() + 9 * 60_000L }
            AdeOverlay(ADE_COMMITTED, SECRET_AT + 6_000) { frame -> frame.withSecretInput(text, expiresAt) }
        }
    },
    // 会话结束后打开模型选择器：内网网关的模型带 Tailscale 灯，公网模型带公网灯。
    "chat.state.model-network" to {
        LabStateScene {
            val text = rememberStatesText()
            AdeOverlay(ADE_DONE, AdeTimes.DONE + 60_000) { it }
            val models = remember(text) { pickerModels(text) }
            ModelPicker(
                show = true,
                onDismiss = onExit,
                onSelect = { _, _ -> },
                currentModelId = models.first().selectionId,
                models = models,
                onOpenTailscaleSettings = {},
                networkLights = NetworkLightsState(
                    public = PublicNetworkStatus.CONNECTED,
                    tailscale = TailscaleLightStatus.CONNECTED,
                    tailnetProviderIds = setOf(GATEWAY_PROVIDER),
                ),
                onRetryTailscale = {},
            )
        }
    },
    // 发往 gpu-box 上的模型前，内置 Tailscale 还在接入 tailnet。
    "chat.state.tailnet-wait" to {
        LabStateScene {
            val text = rememberStatesText()
            val frame = remember(text) { tailnetWaitFrame(text) }
            LabChatScene(frame, nowScriptMs = 12_000, hub = LabHubStory.OPS)
        }
    },
)

private const val ASK_AT = AdeTimes.EXPLORED + 3_000
private const val SECRET_AT = AdeTimes.COMMITTED + 600
private const val GATEWAY_PROVIDER = "lab-gateway"
private const val GATEWAY_MODEL = "lab-gateway::qwen3.8-32b"

@Composable
private fun rememberStatesText(): LabStatesText {
    val zh = isChinese(LocalContext.current.resources)
    return remember(zh) { LabStatesText(zh) }
}

/** starlog 开发脚本停在 [stage]，经 [overlay] 叠加单一状态后渲染；内屏带首页三列。 */
@Composable
private fun LabSceneScope.AdeOverlay(
    stage: Int,
    nowScriptMs: Long,
    actions: ChatScreenActions = ChatScreenActions(),
    /** 叠加内容依赖的场景状态；变化时重新叠加。 */
    key: Any? = null,
    overlay: (LabChatFrame) -> LabChatFrame,
) {
    val zh = isChinese(LocalContext.current.resources)
    val base = remember(zh, stage) { adeFrame(AdeState(LabAdeText(zh), stage = stage, explorerSteps = listOf(5, 7))) }
    val frame = remember(base, key) { overlay(base) }
    LabChatScene(frame, nowScriptMs = nowScriptMs, actions = actions, hub = LabHubStory.ADE)
}

private fun LabChatFrame.withQuestion(text: LabStatesText): LabChatFrame {
    val request = AskUserRequest(
        listOf(
            AskUserQuestion(
                header = text.askFormatHeader,
                question = text.askFormatQuestion,
                options = listOf(
                    AskUserOption(text.askJsonLines, text.askJsonLinesDesc),
                    AskUserOption(text.askJsonArray, text.askJsonArrayDesc),
                ),
            ),
            AskUserQuestion(
                header = text.askFieldsHeader,
                question = text.askFieldsQuestion,
                multiSelect = true,
                options = text.askFields.map { AskUserOption(it) },
            ),
        ),
    )
    val call = LabCall("call_ask", "ask_user", Json.encodeToString(AskUserRequest.serializer(), request), ASK_AT)
    val message = assistantMessage("ade_ask", text.askLead, ASK_AT, listOf(call))
    return copy(
        screen = screen.copy(
            uiState = screen.uiState.copy(messages = screen.uiState.messages + message, status = GenerationStatus.THINKING),
            generationPhase = GenerationPhase.WAITING_INPUT,
            pendingQuestion = PendingQuestion(LAB_SESSION_ID, message.id, call.callId, request),
        ),
    )
}

private fun LabChatFrame.withSecretInput(text: LabStatesText, expiresAt: Long): LabChatFrame {
    val request = SecretInputRequest(
        purpose = text.secretPurpose,
        kind = SecretKind.TOKEN,
        destination = SecretDestination.GitCredential(host = "github.com"),
    )
    val call = LabCall("call_secret", "request_secret", Json.encodeToString(SecretInputRequest.serializer(), request), SECRET_AT)
    val message = assistantMessage("ade_secret", text.secretLead, SECRET_AT, listOf(call))
    return copy(
        screen = screen.copy(
            uiState = screen.uiState.copy(messages = screen.uiState.messages + message, status = GenerationStatus.THINKING),
            generationPhase = GenerationPhase.WAITING_INPUT,
            pendingSecretInput = PendingSecretInput(
                sessionId = LAB_SESSION_ID,
                assistantMessageId = message.id,
                toolCallId = call.callId,
                request = request,
                destinationLabel = text.secretDestination,
                exposure = SecretExposure.APP_ONLY,
                needsUsername = true,
                offersGitHubBrowserLogin = false,
                expiresAtMillis = expiresAt,
            ),
        ),
    )
}

private fun pickerModel(id: String, name: String, provider: String, providerId: String) = ModelSelectionUiModel(
    selectionId = "$providerId::$id",
    remoteModelId = id,
    displayName = name,
    providerName = provider,
    contextTokens = 131_072,
    workload = ModelWorkload.GENERATIVE_TEXT,
    capabilityStates = mapOf(ModelCapability.REASONING to SupportState.SUPPORTED),
    chatEndpointCompatible = SupportState.SUPPORTED,
    providerId = providerId,
)

private fun pickerModels(text: LabStatesText) = listOf(
    pickerModel("qwen3.8-32b", "Qwen3.8 32B", text.gatewayName, GATEWAY_PROVIDER),
    pickerModel("gemma-4-27b", "Gemma 4 27B", text.gatewayName, GATEWAY_PROVIDER),
    pickerModel(LAB_MODEL, LAB_MODEL, "DeepSeek", "lab-deepseek"),
)

/** 新开的会话：问题已发出，请求要经内置 Tailscale 发往 gpu-box，节点正在接入 tailnet。 */
private fun tailnetWaitFrame(text: LabStatesText): LabChatFrame = LabChatFrame(
    screen = ChatScreenState(
        uiState = ChatUiState(
            session = labSession(text.tailnetSessionTitle, "workspace").copy(modelId = GATEWAY_MODEL),
            agentName = LAB_AGENT,
            messages = listOf(userMessage("tailnet_user", text.tailnetPrompt, 0)),
            isGenerating = true,
            status = GenerationStatus.THINKING,
        ),
        modelDisplayNames = mapOf(GATEWAY_MODEL to "Qwen3.8 32B"),
        generationStartedAt = 0L,
        generationPhase = GenerationPhase.CONNECTING,
        tailnetWait = TailnetWaitState.Waiting(
            TailnetConnectProgress(host = LabTailnet.GPU_BOX_HOST, step = TailnetConnectStep.JOINING, timeoutMillis = 30_000),
        ),
        collapseFinishedTurns = false,
    ),
)

/** 单一状态版本的文案；模型名、主机名与命令不随语言变化。 */
internal class LabStatesText(zh: Boolean) {
    val queueReadme = if (zh) "测试通过后顺便更新 README 里的用法示例" else "Once the tests pass, update the usage example in the README too"
    val queueIso = if (zh) "JSON 里的时间戳用 ISO 8601" else "Use ISO 8601 for the timestamps in the JSON"

    val askLead = if (zh) "动手前先确认两点。" else "Two things to confirm before I start."
    val askFormatHeader = if (zh) "格式" else "Format"
    val askFormatQuestion = if (zh) "JSON 输出用哪种形状？" else "What shape should the JSON output take?"
    val askJsonLines = "JSON Lines"
    val askJsonLinesDesc = if (zh) "每行一个对象，便于 jq 和流式处理" else "One object per line, easy to pipe into jq and stream"
    val askJsonArray = if (zh) "单个 JSON 数组" else "A single JSON array"
    val askJsonArrayDesc = if (zh) "整份读完再解析，适合小文件" else "Parsed in one go, fine for small files"
    val askFieldsHeader = if (zh) "字段" else "Fields"
    val askFieldsQuestion = if (zh) "每条记录包含哪些字段？" else "Which fields should each record include?"
    val askFields = if (zh) listOf("时间戳", "级别", "目标", "原始行") else listOf("Timestamp", "Level", "Target", "Raw line")

    val secretLead = if (zh) {
        "已提交。推送到 GitHub 需要访问令牌，我不经手它：请在下面的卡片里填写，令牌直接存进 Git 凭据。"
    } else {
        "Committed. Pushing to GitHub needs an access token, and I won't handle it myself: enter it in the card below and it goes straight into your Git credentials."
    }
    val secretPurpose = if (zh) "推送 starlog 到 github.com" else "Push starlog to github.com"
    val secretDestination = if (zh) "Git 凭据 · github.com" else "Git credential · github.com"

    val gatewayName = if (zh) "家里的网关" else "Home gateway"
    val tailnetSessionTitle = if (zh) "问家里的模型" else "Ask the home model"
    val tailnetPrompt = if (zh) {
        "用一句话说清 JSON Lines 和 JSON 数组的区别。"
    } else {
        "In one sentence, what's the difference between JSON Lines and a JSON array?"
    }
}
