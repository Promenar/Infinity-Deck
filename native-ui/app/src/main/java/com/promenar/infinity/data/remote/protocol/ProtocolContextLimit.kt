package com.promenar.infinity.data.remote.protocol

import com.promenar.infinity.domain.generation.GenerationFailedException
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 协议层上下文超限补充识别。机器码交给 [ProtocolErrorClassifier]，固定错误句式集中在此处。
 * 只读取错误对象的 message，不扫描请求内容、HTTP 页面或任意异常栈。
 */
internal object ProtocolContextLimit {
    /** 同步接口沿用原错误合同，仅把已经确认的上下文超限转为既有结构化异常。 */
    fun throwIfPresent(error: StreamChunk.Error?) {
        if (error?.code == GenerationFailureCode.CONTEXT_LIMIT) {
            throw GenerationFailedException(GenerationFailure(
                code = error.code,
                formatArgs = mapOf(GenerationFailure.KEY_CONTEXT_LIMIT_REASON to "UPSTREAM"),
                technical = error.technical,
                retryable = false,
            ))
        }
    }

    fun fromHttp(protocolType: ProtocolType, statusCode: Int, body: String): StreamChunk.Error? {
        val payload = runCatching { Json.parseToJsonElement(body) }.getOrNull()
        val root = when (payload) {
            is JsonObject -> payload
            is JsonArray -> payload.singleOrNull() as? JsonObject
            else -> null
        } ?: return null
        val error = root["error"]
        return when (error) {
            is JsonObject -> fromError(protocolType, error, statusCode)
            is JsonPrimitive -> classify(protocolType, statusCode, null, null, error.contentOrNull)
            else -> fromError(protocolType, root, statusCode)
        }
    }

    fun fromError(
        protocolType: ProtocolType,
        error: JsonObject,
        statusCode: Int? = null,
    ): StreamChunk.Error? = classify(
        protocolType = protocolType,
        statusCode = statusCode ?: error.field("code")?.toIntOrNull()
            ?: error.field("status_code")?.toIntOrNull(),
        type = error.field("type") ?: error.field("status"),
        code = error.field("code"),
        message = error.field("message"),
    )

    private fun classify(
        protocolType: ProtocolType,
        statusCode: Int?,
        type: String?,
        code: String?,
        message: String?,
    ): StreamChunk.Error? {
        val classified = ProtocolErrorClassifier.classify(statusCode = statusCode, type = type, code = code, technical = message)
        if (classified.code == GenerationFailureCode.CONTEXT_LIMIT) return classified
        if (classified.code !in setOf(GenerationFailureCode.INVALID_REQUEST, GenerationFailureCode.UNKNOWN)) return null
        val requestError = statusCode in setOf(400, 413, 422) ||
            (statusCode == null && type?.lowercase() in setOf("invalid_request_error", "badrequesterror", "invalid_argument"))
        if (!requestError) return null
        val text = message?.trim() ?: return null
        val matches = when (protocolType) {
            ProtocolType.Anthropic_Messages ->
                (statusCode == 400 || (statusCode == null && type == "invalid_request_error")) &&
                    ANTHROPIC_PROMPT_TOO_LONG.containsMatchIn(text)
            ProtocolType.Google_Gemini, ProtocolType.Google_VertexAI ->
                statusCode == 400 && type?.uppercase() in setOf("INVALID_ARGUMENT", "FAILED_PRECONDITION") &&
                    (text == GOOGLE_INPUT_LIMIT_MESSAGE ||
                        (type?.uppercase() == "INVALID_ARGUMENT" && GOOGLE_INPUT_TOKEN_COUNT.matches(text)))
            else -> VLLM_INPUT_LENGTH.containsMatchIn(text) || VLLM_PROMPT_TOKENS.containsMatchIn(text) ||
                text in OLLAMA_CONTEXT_MESSAGES
        }
        return if (matches) StreamChunk.Error(
            code = GenerationFailureCode.CONTEXT_LIMIT,
            retryable = false,
            technical = text,
        ) else null
    }

    private fun JsonObject.field(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    // Anthropic 官方上下文说明：输入超限为 400 invalid_request_error，固定前缀为 prompt is too long。
    // https://platform.claude.com/docs/en/build-with-claude/context-windows#context-window-overflow-behavior
    private val ANTHROPIC_PROMPT_TOO_LONG = Regex("""^prompt is too long(?:[.:]|$)""", RegexOption.IGNORE_CASE)

    // vLLM 官方输入校验：只匹配明确说明输入超出上下文的完整句式。
    // https://github.com/vllm-project/vllm/blob/main/vllm/entrypoints/serve/utils/api_utils.py
    private val VLLM_INPUT_LENGTH = Regex("""^Input length \(\d+\) exceeds model's maximum context length \(\d+\)\.""")
    // https://github.com/vllm-project/vllm/blob/main/vllm/renderers/params.py
    private val VLLM_PROMPT_TOKENS = Regex(
        """^This model's maximum context length is \d+ tokens\. However, your prompt contains (?:at least )?\d+ input tokens, which exceeds this limit even without any output tokens\.""",
    )

    // Ollama 官方后端会在部分输入无法截断时拒绝请求；只匹配这些固定消息，不把普通 413 当成 token 超限。
    // https://github.com/ollama/ollama/blob/main/server/routes.go
    private val OLLAMA_CONTEXT_MESSAGES = setOf(
        "the input length exceeds the context length",
        "input after truncation exceeds maximum context length",
        "input exceeds maximum context length and cannot be truncated further",
    )
    // Google 官方 API 错误表的输入限制例句。INVALID_ARGUMENT 本身还覆盖其它参数错误。
    // https://cloud.google.com/vertex-ai/generative-ai/docs/model-reference/api-errors
    private const val GOOGLE_INPUT_LIMIT_MESSAGE = "Request exceeds the model's input token limit."
    // Google 官方仓库 issue 中的真实错误回执，是窄兼容匹配证据，不代表后端永久保证该自然语言格式。
    // https://github.com/google-gemini/gemini-cli/issues/4692
    private val GOOGLE_INPUT_TOKEN_COUNT = Regex(
        """^The input token count \(\d+\) exceeds the maximum number of tokens allowed \(\d+\)\.?$""",
    )
}
