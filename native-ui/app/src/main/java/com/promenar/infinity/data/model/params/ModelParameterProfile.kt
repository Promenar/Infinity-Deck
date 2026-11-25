package com.promenar.infinity.data.model.params

import com.promenar.infinity.data.remote.protocol.ProtocolType

/** 推理强度档位，取值即各厂商请求体里的字符串。 */
enum class ReasoningEffort(val wireName: String) {
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    companion object {
        fun fromWireName(value: String?): ReasoningEffort? = entries.firstOrNull { it.wireName == value }

        /** 会话显式选择“模型默认”、不沿用助手档位时保存的取值；解析为 null，请求不发送推理字段。 */
        const val MODEL_DEFAULT_WIRE_NAME = "default"
    }
}

enum class SamplingParam {
    TEMPERATURE,
    TOP_P,
    TOP_K,
    MAX_TOKENS,
    FREQUENCY_PENALTY,
    PRESENCE_PENALTY,
    REPETITION_PENALTY,
}

/** 推理字段的写法；字段外形（顶层、嵌套、Anthropic、Gemini）由协议决定。 */
enum class ReasoningDialect {
    /** `reasoning_effort` / `reasoning.effort`，关闭时发 none。 */
    EFFORT,
    /** `thinking` 自适应 + `output_config.effort`。 */
    CLAUDE,
    /** 旧版 Claude：`thinking.enabled` + `budget_tokens`，只能开关。 */
    CLAUDE_BUDGET,
    /** `thinkingConfig.thinkingLevel`。 */
    GEMINI_LEVEL,
    /** `thinkingConfig.thinkingBudget`（Gemini 2.5）。 */
    GEMINI_BUDGET,
    /** `thinking.type` 开关，可另带强度字段（DeepSeek、GLM、MiniMax、Kimi K2.6）。 */
    THINKING_TYPE,
    /** `enable_thinking` 开关（通义千问混合思考模型）。 */
    ENABLE_THINKING,
}

data class ReasoningSupport(
    val dialect: ReasoningDialect,
    /** 可选强度，从低到高；为空表示只能开关。 */
    val levels: List<ReasoningEffort>,
    val canDisable: Boolean,
    /** 不发送推理字段时模型的行为：NONE 为默认不思考，null 为文档未写明。 */
    val defaultEffort: ReasoningEffort?,
) {
    val isToggleOnly: Boolean get() = levels.isEmpty()

    /**
     * 把会话里保存的档位换成本模型可发送的值，null 表示按模型默认、不发送推理字段。
     * 只能开关的模型以 [ReasoningEffort.HIGH] 表示“开启”；不支持的档位取最接近的一档，距离相同取较高一档。
     */
    fun effective(stored: ReasoningEffort?): ReasoningEffort? = when {
        stored == null -> null
        stored == ReasoningEffort.NONE -> if (canDisable) ReasoningEffort.NONE else null
        isToggleOnly -> ReasoningEffort.HIGH
        else -> levels.minWith(compareBy<ReasoningEffort> { kotlin.math.abs(it.ordinal - stored.ordinal) }.thenByDescending { it.ordinal })
    }

    /** 这一档实际是否在思考；模型默认未写明时按思考处理，以便保守地剔除采样参数。 */
    fun thinks(effective: ReasoningEffort?): Boolean = (effective ?: defaultEffort) != ReasoningEffort.NONE
}

/** 某个采样参数在当前模型与接口上的可用程度，决定界面怎样显示、请求是否发送。 */
enum class ParamAvailability {
    SUPPORTED,
    /** 资料说该模型不支持，但接口不是厂商官方地址，服务端可能另有实现：照常显示和发送，标注提醒。 */
    MAY_BE_IGNORED,
    /** 官方接口在思考时不接受或忽略：照常显示，标注提醒，思考时不发送。 */
    NOT_SENT_WHILE_THINKING,
    /** 官方接口对该模型不接受或忽略：收进“不适用”分组，不发送。 */
    NOT_APPLICABLE,
    /** 协议请求体本来就没有这个字段：不显示，不发送。 */
    NOT_IN_PROTOCOL;

    val isSent: Boolean get() = this == SUPPORTED || this == MAY_BE_IGNORED
}

/**
 * 某个模型在某个协议与接口上的参数能力：推理控件形态，以及哪些采样参数会报错或被忽略。
 * 厂商官方接口按文档剔除不支持的参数；第三方、自建或本地接口同名模型的支持情况不确定，只提醒不剔除。
 */
data class ModelParameterProfile(
    /** null 表示不提供推理控件（模型不支持，或当前协议无法表达其推理字段）。 */
    val reasoning: ReasoningSupport?,
    /** 模型本身是否默认在思考；推理控件隐藏时也用于判断采样参数是否可用。 */
    val thinksByDefault: Boolean,
    private val protocolUnsupported: Set<SamplingParam>,
    private val unsupported: Set<SamplingParam>,
    private val unsupportedWhileThinking: Set<SamplingParam>,
    /** 接口是该模型厂商的官方地址（或无法判断地址时按官方处理）。 */
    val officialEndpoint: Boolean = true,
) {
    fun isThinking(stored: ReasoningEffort?): Boolean =
        reasoning?.let { it.thinks(it.effective(stored)) } ?: thinksByDefault

    fun availability(param: SamplingParam, stored: ReasoningEffort?): ParamAvailability = when {
        param in protocolUnsupported -> ParamAvailability.NOT_IN_PROTOCOL
        param in unsupported ->
            if (officialEndpoint) ParamAvailability.NOT_APPLICABLE else ParamAvailability.MAY_BE_IGNORED
        param in unsupportedWhileThinking && isThinking(stored) ->
            if (officialEndpoint) ParamAvailability.NOT_SENT_WHILE_THINKING else ParamAvailability.MAY_BE_IGNORED
        else -> ParamAvailability.SUPPORTED
    }

    /** 请求层要剔除的参数。 */
    fun unsupportedParams(stored: ReasoningEffort?): Set<SamplingParam> =
        SamplingParam.entries.filterTo(mutableSetOf()) { !availability(it, stored).isSent }

    fun supports(param: SamplingParam, stored: ReasoningEffort?): Boolean = availability(param, stored).isSent
}

/**
 * [baseUrl] 为空时取协议的默认地址；为 null 表示调用方不知道地址，按官方接口处理。
 * Gemini 与 Vertex 协议只有 Google 自己的接口，也按官方处理。
 */
fun resolveModelParameterProfile(
    protocolType: ProtocolType,
    remoteModelId: String,
    baseUrl: String? = null,
): ModelParameterProfile {
    val id = normalizeModelId(remoteModelId)
    val family = MODEL_FAMILIES.firstOrNull { it.pattern.containsMatchIn(id) }
    val support = family?.reasoning?.takeIf { protocolType.canExpress(it.dialect) }
    val protocolUnsupported = protocolType.unsupportedSampling()
    return ModelParameterProfile(
        reasoning = support,
        thinksByDefault = family?.reasoning?.let { it.thinks(null) } ?: false,
        protocolUnsupported = protocolUnsupported,
        unsupported = family?.unsupported.orEmpty() - protocolUnsupported,
        unsupportedWhileThinking = family?.unsupportedWhileThinking.orEmpty() - protocolUnsupported,
        officialEndpoint = protocolType == ProtocolType.Google_Gemini || protocolType == ProtocolType.Google_VertexAI ||
            family == null || baseUrl == null ||
            isVendorEndpoint(baseUrl.trim().ifEmpty { protocolType.defaultBaseUrl }, family.domains),
    )
}

/** 主机名等于厂商域名或是其子域名；空地址（自定义兼容接口、本地推理）不算官方。 */
internal fun isVendorEndpoint(baseUrl: String, domains: Set<String>): Boolean {
    val url = baseUrl.trim()
    if (url.isEmpty()) return false
    val host = runCatching { java.net.URI(if ("://" in url) url else "https://$url").host }.getOrNull()
        ?.lowercase()?.trimEnd('.') ?: return false
    return domains.any { host == it || host.endsWith(".$it") }
}

private fun normalizeModelId(remoteModelId: String): String =
    remoteModelId.substringAfter("::").lowercase().substringAfterLast('/')

private fun ProtocolType.canExpress(dialect: ReasoningDialect): Boolean = when (dialect) {
    ReasoningDialect.CLAUDE, ReasoningDialect.CLAUDE_BUDGET -> this == ProtocolType.Anthropic_Messages
    ReasoningDialect.GEMINI_LEVEL, ReasoningDialect.GEMINI_BUDGET ->
        this == ProtocolType.Google_Gemini || this == ProtocolType.Google_VertexAI
    ReasoningDialect.EFFORT -> this != ProtocolType.Anthropic_Messages && isOpenAIShaped()
    ReasoningDialect.THINKING_TYPE -> this == ProtocolType.Anthropic_Messages || isOpenAIShaped()
    ReasoningDialect.ENABLE_THINKING -> isOpenAIShaped() && this != ProtocolType.OpenAI_Responses
}

private fun ProtocolType.isOpenAIShaped(): Boolean = when (this) {
    ProtocolType.Anthropic_Messages, ProtocolType.Google_Gemini, ProtocolType.Google_VertexAI,
    ProtocolType.Local, ProtocolType.Cohere_Chat, ProtocolType.Yi_ZeroOne -> false
    else -> true
}

/** 协议请求体本来就不写的参数。 */
private fun ProtocolType.unsupportedSampling(): Set<SamplingParam> = when (this) {
    ProtocolType.Anthropic_Messages -> setOf(
        SamplingParam.FREQUENCY_PENALTY, SamplingParam.PRESENCE_PENALTY, SamplingParam.REPETITION_PENALTY,
    )
    ProtocolType.OpenAI_Responses -> setOf(
        SamplingParam.TOP_K, SamplingParam.FREQUENCY_PENALTY, SamplingParam.PRESENCE_PENALTY, SamplingParam.REPETITION_PENALTY,
    )
    ProtocolType.Google_Gemini, ProtocolType.Google_VertexAI -> setOf(SamplingParam.REPETITION_PENALTY)
    else -> emptySet()
}

private class ModelFamily(
    val pattern: Regex,
    val reasoning: ReasoningSupport?,
    val unsupported: Set<SamplingParam> = emptySet(),
    val unsupportedWhileThinking: Set<SamplingParam> = emptySet(),
    /** 厂商官方接口的域名；只有这些地址按文档剔除参数。 */
    val domains: Set<String> = emptySet(),
)

private val L = ReasoningEffort.LOW
private val M = ReasoningEffort.MEDIUM
private val H = ReasoningEffort.HIGH
private val XH = ReasoningEffort.XHIGH
private val MX = ReasoningEffort.MAX
private val MIN = ReasoningEffort.MINIMAL
private val OFF = ReasoningEffort.NONE
private val ALL_FIVE = listOf(L, M, H, XH, MX)

private val TEMP = SamplingParam.TEMPERATURE
private val TOP_P = SamplingParam.TOP_P
private val TOP_K = SamplingParam.TOP_K
private val FREQ = SamplingParam.FREQUENCY_PENALTY
private val PRES = SamplingParam.PRESENCE_PENALTY
private val REP = SamplingParam.REPETITION_PENALTY

private fun family(
    pattern: String,
    dialect: ReasoningDialect?,
    levels: List<ReasoningEffort> = emptyList(),
    canDisable: Boolean = false,
    default: ReasoningEffort? = null,
    unsupported: Set<SamplingParam> = emptySet(),
    whileThinking: Set<SamplingParam> = emptySet(),
) = ModelFamily(
    pattern = Regex(pattern),
    reasoning = dialect?.let { ReasoningSupport(it, levels, canDisable, default) },
    unsupported = unsupported,
    unsupportedWhileThinking = whileThinking,
)

/** 同一厂商的型号共用官方域名。 */
private fun vendor(domains: Set<String>, vararg families: ModelFamily): List<ModelFamily> =
    families.map { ModelFamily(it.pattern, it.reasoning, it.unsupported, it.unsupportedWhileThinking, domains) }

/** 版本号分隔符：官方 id 用连字符（claude-opus-5-5），部分网关用点号。 */
private const val V = "[-.]"
/** 版本号之后：结束、日期或其它后缀，但不能再接一位小版本号。 */
private const val END = """(?![-.]?\d(?!\d))"""

/**
 * 依据各厂商官方文档（2026-10-21 核对）。按顺序取第一个匹配项，具体型号写在系列通配之前。
 */
private val MODEL_FAMILIES: List<ModelFamily> = listOf(
    // OpenAI：effort 不为 none 时不接受 temperature / top_p；官方 API 不接受 top_k 与 repetition_penalty。
    vendor(
        setOf("openai.com", "openai.azure.com"),
        family("^gpt-[0-9.]+(-[a-z]+)?-chat", null, unsupported = setOf(TOP_K, REP)),
        family("^gpt-6-astra", ReasoningDialect.EFFORT, ALL_FIVE, default = M, unsupported = setOf(TOP_K, REP), whileThinking = setOf(TEMP, TOP_P)),
        family("^gpt-6\\.1-sol", ReasoningDialect.EFFORT, ALL_FIVE, default = M, unsupported = setOf(TOP_K, REP), whileThinking = setOf(TEMP, TOP_P)),
        family("^(gpt-6-luna|gpt-5\\.6)", ReasoningDialect.EFFORT, ALL_FIVE, canDisable = true, default = M, unsupported = setOf(TOP_K, REP), whileThinking = setOf(TEMP, TOP_P)),
        family("^gpt-5\\.5", ReasoningDialect.EFFORT, listOf(L, M, H, XH), canDisable = true, default = M, unsupported = setOf(TOP_K, REP), whileThinking = setOf(TEMP, TOP_P)),
        family("^gpt-5\\.4", ReasoningDialect.EFFORT, listOf(L, M, H, XH), canDisable = true, default = OFF, unsupported = setOf(TOP_K, REP), whileThinking = setOf(TEMP, TOP_P)),
        family("^(gpt-6|gpt-5)", ReasoningDialect.EFFORT, listOf(L, M, H), default = M, unsupported = setOf(TOP_K, REP), whileThinking = setOf(TEMP, TOP_P)),
        family("^o[1-9](-|$)", ReasoningDialect.EFFORT, listOf(L, M, H), default = M, unsupported = setOf(TOP_K, REP), whileThinking = setOf(TEMP, TOP_P)),
        family("^gpt-", null, unsupported = setOf(TOP_K, REP)),
    ),

    // Anthropic：4.7 起 temperature / top_p / top_k 偏离默认即 400；更早的型号只在思考时受限。
    vendor(
        setOf("anthropic.com"),
        family("claude-opus${V}5${V}5", ReasoningDialect.CLAUDE, ALL_FIVE, default = M, unsupported = setOf(TEMP, TOP_P, TOP_K)),
        family("claude-(fable|mythos)${V}5", ReasoningDialect.CLAUDE, ALL_FIVE, default = H, unsupported = setOf(TEMP, TOP_P, TOP_K)),
        family("claude-mythos-preview", ReasoningDialect.CLAUDE, listOf(L, M, H, MX), default = H, unsupported = setOf(TEMP, TOP_P, TOP_K)),
        family("claude-haiku${V}5${V}5", ReasoningDialect.CLAUDE, ALL_FIVE, canDisable = true, default = M, unsupported = setOf(TEMP, TOP_P, TOP_K)),
        family("claude-sonnet${V}5${V}5", ReasoningDialect.CLAUDE, ALL_FIVE, default = H, unsupported = setOf(TEMP, TOP_P, TOP_K)),
        family("claude-(opus|sonnet)${V}5$END", ReasoningDialect.CLAUDE, ALL_FIVE, canDisable = true, default = H, unsupported = setOf(TEMP, TOP_P, TOP_K)),
        family("claude-opus${V}4${V}[78]$END", ReasoningDialect.CLAUDE, ALL_FIVE, canDisable = true, default = OFF, unsupported = setOf(TEMP, TOP_P, TOP_K)),
        family("claude-(opus|sonnet)${V}4${V}6$END", ReasoningDialect.CLAUDE, listOf(L, M, H, MX), canDisable = true, default = OFF, whileThinking = setOf(TEMP, TOP_P, TOP_K)),
        family("claude-((opus|sonnet|haiku)${V}4|3${V}7-sonnet)", ReasoningDialect.CLAUDE_BUDGET, canDisable = true, default = OFF, whileThinking = setOf(TEMP, TOP_P, TOP_K)),
    ),

    // Google：3.6 Flash 及以后静默忽略 temperature / topP / topK；3.x 起不可关闭思考。
    vendor(
        setOf("googleapis.com"),
        family("^gemini-(4|3\\.8-flash)", ReasoningDialect.GEMINI_LEVEL, listOf(L, M, H), default = M, unsupported = setOf(TEMP, TOP_P, TOP_K)),
        family("^gemini-3\\.6-flash", ReasoningDialect.GEMINI_LEVEL, listOf(MIN, L, M, H), default = M, unsupported = setOf(TEMP, TOP_P, TOP_K)),
        family("^gemini-3\\.[15]-flash-lite", ReasoningDialect.GEMINI_LEVEL, listOf(MIN, L, M, H), default = MIN),
        family("^gemini-3(\\.\\d)?-flash", ReasoningDialect.GEMINI_LEVEL, listOf(MIN, L, M, H), default = H),
        family("^gemini-3(\\.\\d)?-pro", ReasoningDialect.GEMINI_LEVEL, listOf(L, M, H), default = H),
        family("^gemini-3", ReasoningDialect.GEMINI_LEVEL, listOf(L, M, H)),
        family("^gemini-2\\.5-pro", ReasoningDialect.GEMINI_BUDGET, listOf(L, M, H)),
        family("^gemini-2\\.5-flash-lite", ReasoningDialect.GEMINI_BUDGET, listOf(L, M, H), canDisable = true, default = OFF),
        family("^gemini-2\\.5-flash", ReasoningDialect.GEMINI_BUDGET, listOf(L, M, H), canDisable = true),
    ),

    // DeepSeek：思考模式下 temperature 无效；两个惩罚参数已弃用。
    vendor(
        setOf("deepseek.com"),
        family("^deepseek-(chat|reasoner)", null, unsupported = setOf(TOP_K, FREQ, PRES, REP)),
        family("^deepseek-(flash|v4)", ReasoningDialect.THINKING_TYPE, listOf(L, H, MX), canDisable = true, default = H, unsupported = setOf(TOP_K, FREQ, PRES, REP), whileThinking = setOf(TEMP, TOP_P)),
    ),

    // 智谱：文档没有惩罚参数与 top_k；5.3 系列不可关闭思考。
    vendor(
        setOf("bigmodel.cn", "z.ai"),
        family("^glm-5\\.3", ReasoningDialect.THINKING_TYPE, listOf(L, H, MX), default = MX, unsupported = setOf(TOP_K, FREQ, PRES, REP)),
        family("^glm-5\\.2", ReasoningDialect.THINKING_TYPE, listOf(L, H, MX), canDisable = true, unsupported = setOf(TOP_K, FREQ, PRES, REP)),
        family("^glm-5(\\.1)?$END", ReasoningDialect.THINKING_TYPE, canDisable = true, unsupported = setOf(TOP_K, FREQ, PRES, REP)),
    ),

    // xAI：推理模型带惩罚参数会报错。
    vendor(
        setOf("x.ai"),
        family("^grok-4\\.20.*non-reasoning", null, unsupported = setOf(TOP_K, REP)),
        family("^grok-4\\.20", null, unsupported = setOf(TOP_K, REP, FREQ, PRES)),
        family("^grok-4\\.[67]", ReasoningDialect.EFFORT, listOf(L, M, H, XH), default = H, unsupported = setOf(TOP_K, REP), whileThinking = setOf(FREQ, PRES)),
        family("^grok-4\\.5", ReasoningDialect.EFFORT, listOf(L, M, H), default = H, unsupported = setOf(TOP_K, REP), whileThinking = setOf(FREQ, PRES)),
        family("^grok-4\\.3", ReasoningDialect.EFFORT, listOf(L, M, H, XH), canDisable = true, default = L, unsupported = setOf(TOP_K, REP), whileThinking = setOf(FREQ, PRES)),
        family("^grok-", null, unsupported = setOf(TOP_K, REP)),
    ),

    // MiniMax：temperature、top_p 固定，其余采样参数被忽略。
    vendor(
        setOf("minimax.io", "minimaxi.com", "minimax.chat", "minimaxi.chat", "minimax.cn"),
        family("^minimax-m3\\.1", ReasoningDialect.THINKING_TYPE, ALL_FIVE, default = MX, unsupported = setOf(TEMP, TOP_P, TOP_K, FREQ, PRES, REP)),
        family("^minimax-m3$END", ReasoningDialect.THINKING_TYPE, canDisable = true, unsupported = setOf(TEMP, TOP_P, TOP_K, FREQ, PRES, REP)),
        family("^minimax-", null, unsupported = setOf(TEMP, TOP_P, TOP_K, FREQ, PRES, REP)),
    ),

    // 通义千问：兼容模式文档没有 frequency_penalty。
    vendor(
        setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com"),
        family("^qwen3\\.8", ReasoningDialect.EFFORT, listOf(L, M, XH), canDisable = true, default = XH, unsupported = setOf(FREQ)),
        family("^(qwq|qvq)|^qwen3.*-(thinking|instruct|coder)", null, unsupported = setOf(FREQ)),
        family("^qwen3\\.[5-7]", ReasoningDialect.ENABLE_THINKING, canDisable = true, default = H, unsupported = setOf(FREQ)),
        family("^qwen3$END", ReasoningDialect.ENABLE_THINKING, canDisable = true, unsupported = setOf(FREQ)),
        family("^qwen-(plus|flash)", ReasoningDialect.ENABLE_THINKING, canDisable = true, default = OFF, unsupported = setOf(FREQ)),
    ),

    // Kimi：temperature、top_p、惩罚参数固定，传入其它值报错。
    vendor(
        setOf("moonshot.cn", "moonshot.ai"),
        family("^kimi-k3", ReasoningDialect.EFFORT, listOf(L, H, MX), default = MX, unsupported = setOf(TEMP, TOP_P, TOP_K, FREQ, PRES, REP)),
        family("^kimi-k2\\.6", ReasoningDialect.THINKING_TYPE, canDisable = true, default = H, unsupported = setOf(TEMP, TOP_P, TOP_K, FREQ, PRES, REP)),
        family("^kimi-k", null, unsupported = setOf(TEMP, TOP_P, TOP_K, FREQ, PRES, REP)),
    ),

    // 商汤：文档没有 top_k 与 repetition_penalty。
    vendor(
        setOf("sensenova.cn"),
        family("^sensenova-6", ReasoningDialect.EFFORT, listOf(L, M, H, MX), canDisable = true, default = H, unsupported = setOf(TOP_K, REP)),
    ),
).flatten()

