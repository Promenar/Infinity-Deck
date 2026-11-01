package com.promenar.infinity.data.remote.protocol

import kotlinx.coroutines.flow.Flow
import com.promenar.infinity.R
import com.promenar.infinity.data.remote.parser.NormalizedError
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.CompletionReason
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import com.promenar.infinity.domain.tool.ToolRisk
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI

@Serializable
data class ImageInput(
    val url: String? = null,
    val base64: String? = null,
    val mimeType: String = "image/png"
)

@Serializable
data class AudioInput(
    val base64: String,
    val mimeType: String = "audio/wav"
)

@Serializable
data class VideoInput(
    val base64: String,
    val mimeType: String = "video/mp4"
)

@Serializable
data class DocumentInput(
    val url: String? = null,
    val base64: String? = null,
    val name: String? = null,
    val mimeType: String = "application/pdf"
)

@Serializable
data class ProtocolMessage(
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ProtocolToolCall>? = null,
    val thoughtSignature: String? = null,
    val files: List<ProtocolFileAttachment>? = null,
    val imageUrls: List<ImageInput>? = null,
    val audioData: List<AudioInput>? = null,
    val documentData: List<DocumentInput>? = null,
    /** 仅当该消息由当前路由的同一模型产生时才填入；协议按原生格式回传。 */
    val reasoningReplay: ReasoningReplay? = null,
    val videoData: List<VideoInput>? = null,
    /** 成功查看的工作区路径，顺序与工具结果一致。 */
    val mediaPaths: List<String>? = null,
    /** 用户轮次固定的上下文；由协议放在用户原文之前，原文仍保存在 [content]。 */
    val turnContext: String? = null,
    /** 尾部运行提醒不代表新用户轮；仅用于客户端的媒体与签名范围判断。 */
    @Transient val isRuntimeInstruction: Boolean = false,
)

@Serializable
data class ProtocolToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

@Serializable
data class ProtocolFileAttachment(
    val uri: String,
    val mimeType: String,
    val name: String? = null
)

@Serializable
data class ProtocolTool(
    val type: String = "function",
    val function: ProtocolToolFunction,
    @Transient val risk: ToolRisk = ToolRisk.UNKNOWN,
    @Transient val runtimeToolId: String = "",
    @Transient val sourceId: String = "builtin",
    @Transient val mcpServerId: String? = null,
    /** 会话说明转换前的底层定义指纹，不发送给模型。 */
    @Transient val baseDefinitionDigest: String? = null,
)

@Serializable
data class ProtocolToolFunction(
    val name: String,
    val description: String,
    val parameters: String
)

@Serializable
data class ProtocolUsage(
    /** 本轮全部输入 Token，含缓存读取与缓存写入部分。 */
    val input: Int = 0,
    /** 本轮全部输出 Token，含推理部分。 */
    val output: Int = 0,
    val total: Int = 0,
    /** [input] 中命中缓存读取的部分；null 表示服务商未报告。 */
    val cacheReadInput: Int? = null,
    /** [input] 中写入缓存的部分；null 表示服务商未报告。 */
    val cacheWriteInput: Int? = null,
    /** [output] 中的推理 Token；null 表示服务商未报告。 */
    val reasoningOutput: Int? = null,
    /** [cacheWriteInput] 中长 TTL 档的写入部分；null 表示服务商未报告。 */
    val cacheWriteLongTtlInput: Int? = null,
)

/** 是否允许模型在本次回复中调用已声明的工具；NONE 时仍声明工具以保持历史中的调用可被解析。 */
@Serializable
enum class ProtocolToolChoice { AUTO, NONE }

@Serializable
data class PromptRequest(
    val messages: List<ProtocolMessage>,
    val model: String,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val topK: Int? = null,
    val repetitionPenalty: Double? = null,
    val tools: List<ProtocolTool>? = null,
    val toolChoice: ProtocolToolChoice = ProtocolToolChoice.AUTO,
    val stream: Boolean = true,
    val reasoning: Boolean? = null,
    /** 会话选择的推理强度；null 为模型默认，不发送推理字段。 */
    val reasoningEffort: com.promenar.infinity.data.model.params.ReasoningEffort? = null,
    val webSearch: Boolean? = null,
    val enableGeminiSearch: Boolean? = null,
    val streamTimeout: Long? = null,
    val images: List<ImageInput>? = null,
    val audio: List<AudioInput>? = null,
    val documents: List<DocumentInput>? = null,
    @Transient val sessionId: String? = null,
    @Transient val sessionHeaders: com.promenar.infinity.data.remote.SessionRequestHeaders? = null,
)

@Serializable
data class PromptResponse(
    val content: String,
    val reasoning: String? = null,
    val toolCalls: List<ProtocolToolCall>? = null,
    val usage: ProtocolUsage? = null,
    val citations: List<ProtocolCitation>? = null
)

@Serializable
data class ProtocolCitation(
    val title: String,
    val url: String,
    val source: String? = null
)

sealed class StreamChunk {
    data class TextDelta(
        val content: String,
        val reasoning: String? = null
    ) : StreamChunk()

    data class ToolCallDelta(
        val id: String,
        val name: String,
        val arguments: String,
        val index: Int
    ) : StreamChunk()

    data class Thinking(
        val content: String
    ) : StreamChunk()

    data class Usage(
        val usage: ProtocolUsage
    ) : StreamChunk()

    data class Citations(
        val citations: List<ProtocolCitation>
    ) : StreamChunk()

    /** 本次响应的协议原生思考状态，在显式成功终态之前发出一次。 */
    data class ReasoningState(
        val replay: ReasoningReplay
    ) : StreamChunk()

    /**
     * 协议层错误分片。
     *
     * 结构化失败契约：
     * - [code] 是稳定内部码，协议实现**只应**填 code + 类型化参数（[retryAfterSeconds]）+ 诊断 [technical]；
     * - 协议实现禁止再把自然语言展示文案、provider 原文写入 [message]；该字段仅为向后兼容保留，
     *   默认空串，调用方（runtime）一律以 [code] 为准。
     */
    data class Error(
        val code: GenerationFailureCode = GenerationFailureCode.UNKNOWN,
        val retryable: Boolean = false,
        val retryAfterSeconds: Int? = null,
        val technical: String? = null,
        val cause: Throwable? = null,
        val message: String = "",
        val category: String? = null,
        /** 已清洗的服务商原因，见 ProviderErrorMessage。 */
        val providerMessage: String? = null,
    ) : StreamChunk() {
        override fun toString(): String =
            "Error(code=$code, retryable=$retryable, retryAfterSeconds=$retryAfterSeconds)"
    }

    data class ToolCallLifecycle(
        val type: ToolChunkType,
        val events: List<ToolCallLifecycleEvent>
    ) : StreamChunk()

    /** 仅由 Provider 的显式成功终态产生；EOF 不能映射为 Completed。 */
    data class Completed(
        val reason: CompletionReason,
        val completedToolCallIds: List<String> = emptyList(),
    ) : StreamChunk()
}

sealed class ProtocolType(
    /** 与构建混淆无关的存储标识；发布后保持不变。 */
    val persistedId: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultPath: String,
    val iconRes: Int? = null
) {
    data object OpenAI_ChatCompletions : ProtocolType(
        "OpenAI_ChatCompletions", "OpenAI Chat Completions", "https://api.openai.com", "/v1/chat/completions", R.drawable.ic_provider_openai
    )
    data object OpenAI_Responses : ProtocolType(
        "OpenAI_Responses", "OpenAI Responses", "https://api.openai.com", "/v1/responses", R.drawable.ic_provider_openai
    )
    data object Anthropic_Messages : ProtocolType(
        "Anthropic_Messages", "Anthropic Messages", "https://api.anthropic.com", "/v1/messages", R.drawable.ic_provider_anthropic
    )
    data object Google_VertexAI : ProtocolType(
        "Google_VertexAI", "Google Vertex AI", "https://us-central1-aiplatform.googleapis.com", "", R.drawable.ic_provider_gemini
    )
    data object Google_Gemini : ProtocolType(
        "Google_Gemini", "Google Gemini API", "https://generativelanguage.googleapis.com", "/v1beta", R.drawable.ic_provider_gemini
    )
    data object Cohere_Chat : ProtocolType(
        "Cohere_Chat", "Cohere Chat", "https://api.cohere.ai", "/v2/chat", R.drawable.ic_provider_cohere
    )
    data object Mistral_Chat : ProtocolType(
        "Mistral_Chat", "Mistral Chat", "https://api.mistral.ai", "/v1/chat/completions", R.drawable.ic_provider_mistral
    )
    data object DeepSeek : ProtocolType(
        "DeepSeek", "DeepSeek", "https://api.deepseek.com", "/chat/completions", R.drawable.ic_provider_deepseek
    )
    data object Generic_OpenAI_Compat : ProtocolType(
        "Generic_OpenAI_Compat", "OpenAI 兼容 (通用)", "", "/v1/chat/completions", R.drawable.ic_provider_custom
    )

    // ── 国产提供商（均兼容 OpenAI Chat Completions 协议） ──
    data object Moonshot_Kimi : ProtocolType(
        "Moonshot_Kimi", "Moonshot (Kimi)", "https://api.moonshot.cn", "/v1/chat/completions", R.drawable.ic_provider_kimi
    )
    data object Qwen_DashScope : ProtocolType(
        "Qwen_DashScope", "通义千问 (Qwen)", "https://dashscope.aliyuncs.com/compatible-mode", "/v1/chat/completions", R.drawable.ic_provider_qwen
    )
    data object Zhipu_GLM : ProtocolType(
        "Zhipu_GLM", "智谱 (GLM)", "https://open.bigmodel.cn/api/paas", "/v4/chat/completions", R.drawable.ic_provider_zhipu
    )
    data object Doubao_ByteDance : ProtocolType(
        "Doubao_ByteDance", "豆包 (Doubao)", "https://ark.cn-beijing.volces.com/api", "/v3/chat/completions", R.drawable.ic_provider_doubao
    )
    data object Yi_ZeroOne : ProtocolType(
        "Yi_ZeroOne", "零一万物 (Yi)", "https://api.lingyiwanwu.com", "/v1/chat/completions", R.drawable.ic_provider_yi
    )
    data object Baichuan : ProtocolType(
        "Baichuan", "百川 (Baichuan)", "https://api.baichuan-ai.com", "/v1/chat/completions", R.drawable.ic_provider_baichuan
    )

    data object Local : ProtocolType("Local", "本地推理", "", "", R.drawable.ic_provider_local)

    companion object {
        // ── 向后兼容别名 (旧 ProtocolId 枚举名) ──────────────────
        @Deprecated("Use OpenAI_ChatCompletions", ReplaceWith("OpenAI_ChatCompletions"))
        val OPENAI: ProtocolType get() = OpenAI_ChatCompletions
        @Deprecated("Use Anthropic_Messages", ReplaceWith("Anthropic_Messages"))
        val ANTHROPIC: ProtocolType get() = Anthropic_Messages
        @Deprecated("Use Google_VertexAI", ReplaceWith("Google_VertexAI"))
        val VERTEX_AI: ProtocolType get() = Google_VertexAI
        @Deprecated("Use Local", ReplaceWith("Local"))
        val LOCAL: ProtocolType get() = Local

        /** 无上下文解析仅接受稳定标识与旧枚举别名。 */
        fun fromLegacyName(name: String): ProtocolType {
            entries.firstOrNull { it.persistedId.equals(name, ignoreCase = true) }?.let { return it }
            return when (name.uppercase()) {
                "OPENAI" -> OpenAI_ChatCompletions
                "ANTHROPIC" -> Anthropic_Messages
                "VERTEX_AI" -> Google_VertexAI
                "LOCAL" -> Local
                else -> throw UnsupportedPersistedProtocolException()
            }
        }

        /** 按稳定标识、旧枚举别名、显示名、发布混淆表的顺序解析历史存储。 */
        fun fromPersistedName(name: String, baseUrl: String, displayName: String? = null): ProtocolType {
            val known = try {
                fromLegacyName(name)
            } catch (_: UnsupportedPersistedProtocolException) {
                null
            }
            known?.let { return it }
            val protocols = entries
            protocols.firstOrNull { it.displayName == displayName }?.let { return it }

            val candidateIds = legacyObfuscatedIdsByRelease.mapNotNull { it[name] }.toSet()
            val candidates = protocols.filter { it.persistedId in candidateIds }
            if (candidates.size == 1) return candidates.single()
            val compatible = candidates.filter { (it == Local) == baseUrl.isBlank() }
            if (compatible.size == 1) return compatible.single()

            val host = urlHost(baseUrl)
            val hostMatches = compatible.filter {
                host != null && host.equals(urlHost(it.defaultBaseUrl), ignoreCase = true)
            }
            // 主机不属于任何候选预设时，用户填的是自建或第三方网关，通用兼容协议是唯一合理解释。
            if (hostMatches.isEmpty() && host != null && Generic_OpenAI_Compat in compatible) {
                return Generic_OpenAI_Compat
            }
            val preferred = hostMatches.ifEmpty { compatible }
            if (preferred.size == 1) return preferred.single()
            return preferred.firstOrNull { it.persistedId == legacyObfuscatedIdsByRelease.last()[name] }
                ?: throw UnsupportedPersistedProtocolException()
        }

        private fun urlHost(url: String): String? = try {
            URI(url.trim()).host
        } catch (_: java.net.URISyntaxException) {
            null
        }

        // 来源：已发布签名包 b86ebeaf、fcf9750c、83622a1a 的 ProtocolType 子类字节码，按发布顺序排列。
        // 仅用于读取旧数据；基类混淆名不代表协议，所有写入均使用 persistedId。
        private val legacyObfuscatedIdsByRelease = listOf(
            mapOf(
                "tr4" to "Anthropic_Messages", "ur4" to "Baichuan", "vr4" to "Cohere_Chat",
                "wr4" to "DeepSeek", "xr4" to "Doubao_ByteDance", "yr4" to "Generic_OpenAI_Compat",
                "zr4" to "Google_VertexAI", "as4" to "Local", "bs4" to "Mistral_Chat",
                "cs4" to "Moonshot_Kimi", "ds4" to "OpenAI_ChatCompletions", "es4" to "OpenAI_Responses",
                "fs4" to "Qwen_DashScope", "gs4" to "Yi_ZeroOne", "hs4" to "Zhipu_GLM",
            ),
            mapOf(
                "ds4" to "Anthropic_Messages", "es4" to "Baichuan", "fs4" to "Cohere_Chat",
                "gs4" to "DeepSeek", "hs4" to "Doubao_ByteDance", "is4" to "Generic_OpenAI_Compat",
                "js4" to "Google_VertexAI", "ks4" to "Local", "ls4" to "Mistral_Chat",
                "ms4" to "Moonshot_Kimi", "ns4" to "OpenAI_ChatCompletions", "os4" to "OpenAI_Responses",
                "ps4" to "Qwen_DashScope", "qs4" to "Yi_ZeroOne", "rs4" to "Zhipu_GLM",
            ),
            mapOf(
                "fs4" to "Anthropic_Messages", "gs4" to "Baichuan", "hs4" to "Cohere_Chat",
                "is4" to "DeepSeek", "js4" to "Doubao_ByteDance", "ks4" to "Generic_OpenAI_Compat",
                "ls4" to "Google_VertexAI", "ms4" to "Local", "ns4" to "Mistral_Chat",
                "os4" to "Moonshot_Kimi", "ps4" to "OpenAI_ChatCompletions", "qs4" to "OpenAI_Responses",
                "rs4" to "Qwen_DashScope", "ss4" to "Yi_ZeroOne", "ts4" to "Zhipu_GLM",
            ),
        )

        val entries: List<ProtocolType> get() = listOfNotNull(
            OpenAI_ChatCompletions, OpenAI_Responses, Anthropic_Messages,
            Google_VertexAI, Google_Gemini, Cohere_Chat, Mistral_Chat, DeepSeek,
            Moonshot_Kimi, Qwen_DashScope, Zhipu_GLM, Doubao_ByteDance,
            Yi_ZeroOne, Baichuan,
            Generic_OpenAI_Compat, Local
        )
    }
}

class UnsupportedPersistedProtocolException : UnsupportedOperationException(
    "PERSISTED_PROVIDER_PROTOCOL_UNSUPPORTED",
)

/**
 * 向后兼容别名 — 旧代码中的 ProtocolId 引用使用此别名过渡。
 * 新代码请直接使用 ProtocolType。
 */
typealias ProtocolId = ProtocolType

interface LlmProtocol {
    val protocolType: ProtocolType

    suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk>

    suspend fun sendPromptSync(request: PromptRequest): PromptResponse
    suspend fun listModels(): List<String> = emptyList()
    suspend fun listModelDescriptors(): List<RemoteModelDescriptor> =
        listModels().map { RemoteModelDescriptor(id = it) }
    fun cancel()
}

enum class ToolChunkType {
    MCP_TOOL_STREAMING,
    MCP_TOOL_PENDING,
    MCP_TOOL_COMPLETE
}

enum class ToolType {
    BUILTIN,
    PROVIDER,
    MCP
}

sealed interface ToolCallLifecycleEvent {
    val toolCallId: String
    val toolName: String
    val toolType: ToolType

    data class Streaming(
        override val toolCallId: String,
        override val toolName: String,
        override val toolType: ToolType,
        val arguments: Map<String, JsonElement>? = null,
        val partialArguments: String? = null
    ) : ToolCallLifecycleEvent

    data class Pending(
        override val toolCallId: String,
        override val toolName: String,
        override val toolType: ToolType,
        val arguments: Map<String, JsonElement>?
    ) : ToolCallLifecycleEvent

    data class Complete(
        override val toolCallId: String,
        override val toolName: String,
        override val toolType: ToolType,
        val arguments: Map<String, JsonElement>?,
        val response: JsonElement?,
        val status: String = "done",
        val imageBase64List: List<String> = emptyList()
    ) : ToolCallLifecycleEvent
}

/**
 * 把协议层分类结果转为结构化 [StreamChunk.Error]：
 * 只携带稳定 [GenerationFailureCode] + 类型化重试参数 + 诊断 technical，
 * 不再产出任何自然语言展示文案。
 */
fun NormalizedError.toStreamChunkError(cause: Throwable? = null): StreamChunk.Error {
    val failure = toFailure(cause)
    return StreamChunk.Error(
        code = failure.code,
        retryable = retryable,
        retryAfterSeconds = failure.retryAfterSeconds,
        technical = failure.technical,
        cause = failure.cause,
        providerMessage = failure.providerMessage,
    )
}

internal fun truncatedStreamError(technical: String): StreamChunk.Error = StreamChunk.Error(
    code = GenerationFailureCode.NETWORK,
    retryable = true,
    technical = technical,
)

internal fun streamContractError(technical: String): StreamChunk.Error = StreamChunk.Error(
    code = GenerationFailureCode.SERVER,
    retryable = false,
    technical = technical,
)

/**
 * 提供商以非正常原因结束输出时的失败：截断（输出上限）、内容过滤与服务端中断。
 * 返回 null 表示不是已知的非正常结束原因，由调用方按协议合同错误处理。
 * 截断时工具参数常常只写了一半，因此在核验工具调用完整性之前判定。
 */
internal fun abnormalFinishError(label: String, reason: String): StreamChunk.Error? {
    val (code, retryable) = when (reason) {
        "length", "max_tokens", "MAX_TOKENS", "max_output_tokens" -> GenerationFailureCode.OUTPUT_LIMIT to false
        "content_filter", "refusal", "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY" ->
            GenerationFailureCode.CONTENT_FILTERED to false
        // DeepSeek：推理资源不足或生成被中断，原样重发通常可以成功。
        "insufficient_system_resource", "aborted" -> GenerationFailureCode.SERVER to true
        else -> return null
    }
    return StreamChunk.Error(code = code, retryable = retryable, technical = "$label finished with reason: $reason")
}

/** 流解析过程中遇到非正常结束原因时携带对应失败，由流循环直接发出。 */
internal class AbnormalFinishException(val error: StreamChunk.Error) : Exception(error.technical)

/**
 * 流内错误事件（HTTP 200 后在 SSE 里返回 `error` 对象）：按事件自带的状态码与说明分类并保留服务商原因。
 * 无法归类的按可重试的服务端错误处理，由生成层自动重发本轮。
 */
internal fun streamErrorEvent(chunk: kotlinx.serialization.json.JsonObject): StreamChunk.Error {
    val error = chunk["error"]
    val status = (error as? kotlinx.serialization.json.JsonObject)?.let { obj ->
        listOf("status", "code", "status_code").firstNotNullOfOrNull { key ->
            (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it in 400..599 }
        }
    } ?: 0
    val normalized = com.promenar.infinity.data.remote.parser.ErrorNormalizer.normalize(
        com.promenar.infinity.data.remote.parser.HttpStatusException(status, chunk.toString()),
    )
    val chunkError = normalized.toStreamChunkError()
    return if (normalized is com.promenar.infinity.data.remote.parser.NormalizedError.Unknown) {
        chunkError.copy(code = GenerationFailureCode.SERVER, retryable = true)
    } else chunkError
}

/** 在发出成功 terminal 前集中核验工具调用的完整性。 */
internal fun validatedCompletion(
    reason: CompletionReason,
    calls: List<ProtocolToolCall>,
): StreamChunk {
    if (reason == CompletionReason.END_TURN && calls.isEmpty()) return StreamChunk.Completed(reason)
    val ids = calls.map { it.id }
    val valid = calls.isNotEmpty() &&
        ids.all(String::isNotBlank) &&
        ids.distinct().size == ids.size &&
        calls.all { call -> call.name.isNotBlank() && isToolArgumentsObject(call.arguments) }
    // 部分兼容网关（如 Gemini OpenAI 兼容层）以 stop 结束仍携带完整工具调用；完整时按 TOOL_CALLS 处理。
    return if (valid) StreamChunk.Completed(CompletionReason.TOOL_CALLS, ids)
    else if (reason == CompletionReason.END_TURN) streamContractError("END_TURN contained incomplete tool calls")
    else streamContractError("TOOL_CALLS terminal contained incomplete calls")
}

/** 无参数工具允许空参数串，语义等同 `{}`；其余必须是 JSON object。 */
internal fun isToolArgumentsObject(arguments: String): Boolean =
    arguments.isBlank() || runCatching { Json.parseToJsonElement(arguments) is JsonObject }.getOrDefault(false)

/** 空参数串规范化为 `{}`，保证后续回传与执行使用合法 JSON object。 */
fun normalizeToolArguments(arguments: String): String = arguments.ifBlank { "{}" }

/** 同步响应也必须服从与流式终态一致的工具调用合同。 */
internal fun requireValidSyncCompletion(
    reason: CompletionReason,
    calls: List<ProtocolToolCall>,
) {
    check(validatedCompletion(reason, calls) is StreamChunk.Completed) {
        "Sync completion contained inconsistent or incomplete tool calls"
    }
}
