package com.promenar.infinity.data.model

import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.ModelCatalogRuntime
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.model.catalog.ResolvedModelMetadata
import com.promenar.infinity.data.model.catalog.SupportState

enum class ModelType {
    CHAT, REASONING, IMAGE, EMBEDDING, RERANK
}

data class ModelCapabilities(
    val vision: Boolean = false,
    val internet: Boolean = false,
    val reasoning: Boolean = false,
    val image: Boolean = false,
    val embedding: Boolean = false,
    val rerank: Boolean = false,
    val audioInput: Boolean = false,
    val audioOutput: Boolean = false,
    val videoUnderstanding: Boolean = false,
    val structuredOutput: Boolean = false,
    val promptCaching: Boolean = false,
    val computerUse: Boolean = false
)

sealed class ModelPattern {
    data class StringPattern(val value: String) : ModelPattern()
    data class RegexPattern(val regex: Regex) : ModelPattern()

    fun matches(modelId: String): Boolean = when (this) {
        is StringPattern -> modelId.lowercase().contains(value.lowercase())
        is RegexPattern -> regex.containsMatchIn(modelId)
    }

    /**
     * 匹配优先级不依赖 MODEL_SPECS 的声明顺序：
     * 精确字符串 > 整体正则 > 具体度更高的部分匹配 > 通用家族回退。
     */
    fun rank(modelId: String): ModelPatternRank? {
        if (!matches(modelId)) return null
        return when (this) {
            is StringPattern -> ModelPatternRank(
                exactness = if (modelId.equals(value, ignoreCase = true)) 2 else 0,
                specificity = value.count { it.isLetterOrDigit() },
            )
            is RegexPattern -> ModelPatternRank(
                exactness = if (regex.matches(modelId)) 1 else 0,
                specificity = regex.pattern.count { it.isLetterOrDigit() },
            )
        }
    }
}

data class ModelPatternRank(
    val exactness: Int,
    val specificity: Int,
) : Comparable<ModelPatternRank> {
    override fun compareTo(other: ModelPatternRank): Int =
        compareValuesBy(this, other, ModelPatternRank::exactness, ModelPatternRank::specificity)
}

data class ModelSpec(
    val pattern: ModelPattern,
    val contextLength: Int,
    val type: ModelType? = null,
    val capabilities: ModelCapabilities? = null,
    val forcedReasoning: Boolean = false,
    val icon: String? = null,
    val note: String? = null,
    /** 最大输出 token 数，0 表示未指定 */
    val maxOutputTokens: Int = 0,
    /** 知识截止日期（YYYYMM），如 202604 */
    val knowledgeCutoff: String? = null
)

val MODEL_SPECS: List<ModelSpec> = listOf(

    // ==================== OpenAI (2025-2026) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-4.1"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, structuredOutput = true, promptCaching = true),
        icon = "openai",
        note = "GPT-4.1 (1M context)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-4.1-mini"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, structuredOutput = true),
        icon = "openai",
        note = "GPT-4.1 Mini"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-4.1-nano"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(structuredOutput = true),
        icon = "openai",
        note = "GPT-4.1 Nano"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-4o-mini"),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "openai",
        note = "GPT-4o Mini"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-4o"),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, audioInput = true, audioOutput = true, structuredOutput = true),
        icon = "openai",
        note = "GPT-4o series"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-4-turbo"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "openai",
        note = "GPT-4 Turbo"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-4"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "openai",
        note = "GPT-4 Generic"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-3.5"),
        contextLength = 16385,
        type = ModelType.CHAT,
        icon = "openai",
        note = "GPT-3.5"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("openai"),
        contextLength = 4096,
        icon = "openai",
        note = "OpenAI Generic"
    ),

    // ==================== OpenAI (2025-2026) ====================
    // O3/O4 series (reasoning models)
    ModelSpec(
        pattern = ModelPattern.StringPattern("o4-mini"),
        contextLength = 200000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true, vision = true, structuredOutput = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O4 Mini (2025)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("o3"),
        contextLength = 200000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true, vision = true, structuredOutput = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O3 (2025)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("o3-mini"),
        contextLength = 200000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O3 Mini"
    ),
    // O1 series (reasoning models, cannot disable reasoning)
    ModelSpec(
        pattern = ModelPattern.StringPattern("o1-pro"),
        contextLength = 200000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O1 Pro"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("o1-preview"),
        contextLength = 128000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O1 Preview"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("o1-mini"),
        contextLength = 128000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O1 Mini"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("o1"),
        contextLength = 200000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O1"
    ),

    // ==================== Anthropic (2025-2026) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-4-opus"),
        contextLength = 200000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, computerUse = true, promptCaching = true, structuredOutput = true),
        icon = "claude",
        note = "Claude 4 Opus (2025)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-4-sonnet"),
        contextLength = 200000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, computerUse = true, promptCaching = true, structuredOutput = true),
        icon = "claude",
        note = "Claude 4 Sonnet (2025)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-3.7-sonnet"),
        contextLength = 200000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, promptCaching = true, computerUse = true),
        icon = "claude",
        note = "Claude 3.7 Sonnet"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-3.5-haiku"),
        contextLength = 200000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, promptCaching = true),
        icon = "claude",
        note = "Claude 3.5 Haiku"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-3-5-sonnet"),
        contextLength = 200000,
        icon = "claude",
        note = "Claude 3.5 Sonnet"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-3-5"),
        contextLength = 200000,
        icon = "claude",
        note = "Claude 3.5"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-3"),
        contextLength = 200000,
        icon = "claude",
        note = "Claude 3"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude"),
        contextLength = 100000,
        icon = "claude",
        note = "Claude Generic"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("anthropic"),
        contextLength = 100000,
        icon = "anthropic",
        note = "Anthropic Generic"
    ),

    // ==================== Google Gemini 阵营 (2025-2026) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-3.1-pro"),
        contextLength = 2000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, audioInput = true, audioOutput = true, videoUnderstanding = true, structuredOutput = true, promptCaching = true),
        icon = "gemini",
        note = "Gemini 3.1 Pro (Apr 2026)",
        maxOutputTokens = 65536,
        knowledgeCutoff = "202604"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-3.1-flash-lite"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, audioInput = true, structuredOutput = true),
        icon = "gemini",
        note = "Gemini 3.1 Flash-Lite (Apr 2026)",
        maxOutputTokens = 65536,
        knowledgeCutoff = "202604"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-3.1-flash"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, audioInput = true, videoUnderstanding = true, structuredOutput = true),
        icon = "gemini",
        note = "Gemini 3.1 Flash (Apr 2026)",
        maxOutputTokens = 65536,
        knowledgeCutoff = "202604"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-3-pro"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, audioInput = true, videoUnderstanding = true, structuredOutput = true),
        icon = "gemini",
        note = "Gemini 3 Pro",
        maxOutputTokens = 65536,
        knowledgeCutoff = "202512"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-3-flash"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, audioInput = true, videoUnderstanding = true, structuredOutput = true),
        icon = "gemini",
        note = "Gemini 3 Flash",
        maxOutputTokens = 65536,
        knowledgeCutoff = "202512"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-2.5-pro"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, structuredOutput = true, audioInput = true, videoUnderstanding = true),
        icon = "gemini",
        note = "Gemini 2.5 Pro"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-2.5-flash"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, audioInput = true),
        icon = "gemini",
        note = "Gemini 2.5 Flash"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-2.5"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "gemini",
        note = "Gemini 2.5"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-2.0-flash-thinking"),
        contextLength = 1000000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "gemini",
        note = "Gemini 2.0 Flash Thinking"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-2.0"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "gemini",
        note = "Gemini 2.0"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-2.0-pro"),
        contextLength = 2000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "gemini",
        note = "Gemini 2.0 Pro"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-2.0-flash"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "gemini",
        note = "Gemini 2.0 Flash"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-1.5-pro"),
        contextLength = 2000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "gemini",
        note = "Gemini 1.5 Pro"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-1.5-flash"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "gemini",
        note = "Gemini 1.5 Flash"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini-1.0-pro"),
        contextLength = 32768,
        type = ModelType.CHAT,
        icon = "gemini",
        note = "Gemini 1.0 Pro"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""gemini-3[.-]""", RegexOption.IGNORE_CASE)),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "gemini",
        note = "Gemini 3 Series"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemini"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        icon = "gemini",
        note = "Gemini Generic"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("google"),
        contextLength = 32768,
        icon = "google",
        note = "Google Generic"
    ),

    // ==================== DeepSeek (2025) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("deepseek-r2"),
        contextLength = 128000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true, vision = true),
        forcedReasoning = true,
        icon = "deepseek",
        note = "DeepSeek R2 (2025)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("deepseek-v3.1"),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "deepseek",
        note = "DeepSeek V3.1 (2025)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("deepseek-reasoner"),
        contextLength = 64000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        forcedReasoning = true,
        icon = "deepseek",
        note = "DeepSeek R1 (Native Reasoning)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("deepseek-r1"),
        contextLength = 64000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        forcedReasoning = true,
        icon = "deepseek",
        note = "DeepSeek R1"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("deepseek-v3"),
        contextLength = 64000,
        type = ModelType.CHAT,
        icon = "deepseek",
        note = "DeepSeek V3"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("deepseek"),
        contextLength = 64000,
        icon = "deepseek",
        note = "DeepSeek"
    ),

    // ==================== Zhipu AI (GLM) ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""glm-?4[.-]?9b""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "zhipu",
        note = "GLM-4-9B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""glm-?4[.-]?v""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "zhipu",
        note = "GLM-4V"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""glm-?4""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "zhipu",
        note = "GLM-4 Series"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("glm-edge"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "zhipu",
        note = "GLM-Edge"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("cogview"),
        contextLength = 1,
        type = ModelType.IMAGE,
        capabilities = ModelCapabilities(image = true),
        icon = "zhipu",
        note = "CogView (Image Generation)"
    ),

    // ==================== Alibaba (Qwen) (2025) ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen3-235b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "qwen",
        note = "Qwen3 235B (MoE)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen3-72b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "qwen",
        note = "Qwen3 72B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen3-32b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "qwen",
        note = "Qwen3 32B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen3-14b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        type = ModelType.CHAT,
        icon = "qwen",
        note = "Qwen3 14B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen3-8b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        type = ModelType.CHAT,
        icon = "qwen",
        note = "Qwen3 8B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen3-vl""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, videoUnderstanding = true),
        icon = "qwen",
        note = "Qwen3 VL"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen3-coder""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        type = ModelType.CHAT,
        icon = "qwen",
        note = "Qwen3 Coder"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen-?2\.5-?math""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "qwen",
        note = "Qwen 2.5 Math"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen-?2\.5-?coder""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "qwen",
        note = "Qwen 2.5 Coder"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen2\.5-72b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        icon = "qwen",
        note = "Qwen2.5 72B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen2\.5-32b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        icon = "qwen",
        note = "Qwen2.5 32B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen2\.5-14b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        icon = "qwen",
        note = "Qwen2.5 14B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen2\.5-7b""", RegexOption.IGNORE_CASE)),
        contextLength = 131072,
        icon = "qwen",
        note = "Qwen2.5 7B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen-?2\.5""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "qwen",
        note = "Qwen 2.5"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen-?vl""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "qwen",
        note = "Qwen-VL"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("qwen"),
        contextLength = 32768,
        type = ModelType.CHAT,
        icon = "qwen"
    ),

    // ==================== Meta (Llama) (2025) ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-?4-maverick""", RegexOption.IGNORE_CASE)),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "meta",
        note = "Llama 4 Maverick (1M)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-?4-scout""", RegexOption.IGNORE_CASE)),
        contextLength = 10000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "meta",
        note = "Llama 4 Scout (10M context)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-?4-behemoth""", RegexOption.IGNORE_CASE)),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "meta",
        note = "Llama 4 Behemoth"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-?4""", RegexOption.IGNORE_CASE)),
        contextLength = 1000000,
        type = ModelType.CHAT,
        icon = "meta",
        note = "Llama 4 Series"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-?3\.2-?vision""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "meta",
        note = "Llama 3.2 Vision"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-?3\.2""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "meta",
        note = "Llama 3.2"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-?3\.1""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "meta",
        note = "Llama 3.1"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("llama-3"),
        contextLength = 8192,
        type = ModelType.CHAT,
        icon = "meta"
    ),

    // ==================== Mistral AI (2025) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("mistral-large-2"),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, structuredOutput = true),
        icon = "mistral",
        note = "Mistral Large 2"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("codestral"),
        contextLength = 256000,
        type = ModelType.CHAT,
        icon = "mistral",
        note = "Codestral (256K, code-focused)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("pixtral"),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "mistral",
        note = "Pixtral (Vision)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("mistral-large"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "mistral"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("mistral"),
        contextLength = 32768,
        type = ModelType.CHAT,
        icon = "mistral"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("mixtral"),
        contextLength = 32768,
        type = ModelType.CHAT,
        icon = "mistral"
    ),

    // ==================== 01.AI (Yi) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("yi-lightning"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "yi"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("yi-vision"),
        contextLength = 16384,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "yi"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("yi"),
        contextLength = 32768,
        type = ModelType.CHAT,
        icon = "yi"
    ),

    // ==================== Moonshot (Kimi) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("kimi"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "kimi"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("moonshot"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "kimi"
    ),

    // ==================== ByteDance (Doubao) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("doubao"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "bytedance"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("skylark"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "bytedance"
    ),

    // ==================== MiniMax ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("MiniMax-M3"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(
            vision = true,
            reasoning = true,
            videoUnderstanding = true,
        ),
        icon = "minimax",
        note = "MiniMax M3",
        maxOutputTokens = 524288,
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("MiniMax-M2.7-highspeed"),
        contextLength = 204800,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "minimax",
        note = "MiniMax M2.7 Highspeed",
        maxOutputTokens = 204800,
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("MiniMax-M2.7"),
        contextLength = 204800,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "minimax",
        note = "MiniMax M2.7",
        maxOutputTokens = 204800,
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("abab6.5"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "minimax"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("abab7"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "minimax"
    ),

    // ==================== Baichuan ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("baichuan"),
        contextLength = 32768,
        type = ModelType.CHAT,
        icon = "baichuan"
    ),

    // ==================== StepFun (阶跃星辰) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("step-"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "stepfun"
    ),

    // ==================== SenseTime (商汤日日新) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("sensechat"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "sensetime"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("sensenova"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "sensetime"
    ),

    // ==================== xAI (Grok) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("grok-3"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true, vision = true, internet = true),
        icon = "xai",
        note = "Grok 3 (1M context)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("grok-2"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "xai"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("grok"),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "xai"
    ),

    // ==================== Cohere ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""command-r-plus""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(rerank = true, internet = true),
        icon = "cohere",
        note = "Command R+ (128K)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""command-r""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "cohere",
        note = "Command R"
    ),

    // ==================== Microsoft (Phi) ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""phi-4-multimodal""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, audioInput = true, reasoning = true),
        icon = "microsoft",
        note = "Phi-4 Multimodal (2025)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""phi-4""", RegexOption.IGNORE_CASE)),
        contextLength = 16384,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "microsoft",
        note = "Phi-4"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""phi-3""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "microsoft",
        note = "Phi-3 Series"
    ),

    // ==================== IBM (Granite) ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""granite-3\.2""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "ibm",
        note = "Granite 3.2"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""granite""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        icon = "ibm",
        note = "Granite Series"
    ),

    // ==================== Local / Other ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        type = ModelType.CHAT
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""mistral""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        type = ModelType.CHAT
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""gemma""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        type = ModelType.CHAT
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""command-r""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("kimi-k1.5"),
        contextLength = 128000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "kimi"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("kimi"),
        contextLength = 128000,
        icon = "kimi"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("moonshot"),
        contextLength = 128000,
        icon = "moonshot"
    ),

    // ==================== Baichuan ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""baichuan-4""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "baichuan",
        note = "Baichuan 4"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""baichuan-3-turbo""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "baichuan",
        note = "Baichuan 3 Turbo"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""baichuan-2-turbo""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "baichuan",
        note = "Baichuan 2 Turbo"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""baichuan""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "baichuan",
        note = "Baichuan"
    ),

    // ==================== Qwen ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen-max""", RegexOption.IGNORE_CASE)),
        contextLength = 8000,
        icon = "qwen",
        note = "Qwen Max"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen-plus""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "qwen",
        note = "Qwen Plus"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen-turbo""", RegexOption.IGNORE_CASE)),
        contextLength = 8000,
        icon = "qwen",
        note = "Qwen Turbo"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen2-72b""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "qwen",
        note = "Qwen2 72B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen""", RegexOption.IGNORE_CASE)),
        contextLength = 8000,
        icon = "qwen",
        note = "Qwen"
    ),

    // ==================== ERNIE ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""ernie-4\.0""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "wenxin",
        note = "ERNIE 4.0"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""ernie-3\.5""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "wenxin",
        note = "ERNIE 3.5"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""ernie-turbo""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "wenxin",
        note = "ERNIE Turbo"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""ernie-speed""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "wenxin",
        note = "ERNIE Speed"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""ernie""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "wenxin",
        note = "ERNIE"
    ),

    // ==================== Doubao ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""doubao-pro-32k""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "doubao",
        note = "Doubao Pro 32K"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""doubao-pro-4k""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        icon = "doubao",
        note = "Doubao Pro 4K"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""doubao-lite-32k""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "doubao",
        note = "Doubao Lite 32K"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""doubao""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "doubao",
        note = "Doubao"
    ),

    // ==================== Yi ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""yi-large""", RegexOption.IGNORE_CASE)),
        contextLength = 32768,
        icon = "yi",
        note = "Yi Large"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""yi-medium""", RegexOption.IGNORE_CASE)),
        contextLength = 16384,
        icon = "yi",
        note = "Yi Medium"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""yi-34b-chat""", RegexOption.IGNORE_CASE)),
        contextLength = 200000,
        icon = "yi",
        note = "Yi 34B Chat 200K"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""yi-6b""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        icon = "yi",
        note = "Yi 6B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""yi-""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        icon = "yi",
        note = "Yi series"
    ),

    // ==================== MiniMax ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""abab6\.5""", RegexOption.IGNORE_CASE)),
        contextLength = 245760,
        icon = "minimax",
        note = "ABAB 6.5 (245K)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""abab6""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "minimax",
        note = "ABAB 6"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""abab5\.5""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "minimax",
        note = "ABAB 5.5"
    ),

    // ==================== Open Source Models ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-3\.1-405b""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        icon = "meta",
        note = "Llama 3.1 405B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-3\.1-70b""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        icon = "meta",
        note = "Llama 3.1 70B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-3\.1""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        icon = "meta",
        note = "Llama 3.1"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-3-70b""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "meta",
        note = "Llama 3 70B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-3""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "meta",
        note = "Llama 3"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-2-70b""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        icon = "meta",
        note = "Llama 2 70B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""llama-2""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        icon = "meta",
        note = "Llama 2"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""mistral-large""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        icon = "mistral",
        note = "Mistral Large"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""mistral-medium""", RegexOption.IGNORE_CASE)),
        contextLength = 32000,
        icon = "mistral",
        note = "Mistral Medium"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""mistral-small""", RegexOption.IGNORE_CASE)),
        contextLength = 32000,
        icon = "mistral",
        note = "Mistral Small"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""mixtral-8x7b""", RegexOption.IGNORE_CASE)),
        contextLength = 32000,
        icon = "mistral",
        note = "Mixtral 8x7B"
    ),

    // ==================== Gemma ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""gemma-2-27b""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "gemma",
        note = "Gemma 2 27B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""gemma-2-9b""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "gemma",
        note = "Gemma 2 9B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""gemma""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        icon = "gemma",
        note = "Gemma Series"
    ),

    // ==================== Rerank Models ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""bge-reranker""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        type = ModelType.RERANK,
        capabilities = ModelCapabilities(rerank = true),
        icon = "rerank",
        note = "BGE Reranker"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""jina-reranker""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        type = ModelType.RERANK,
        capabilities = ModelCapabilities(rerank = true),
        icon = "rerank",
        note = "Jina Reranker"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""cohere-rerank""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        type = ModelType.RERANK,
        capabilities = ModelCapabilities(rerank = true),
        icon = "rerank",
        note = "Cohere Rerank"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""rerank""", RegexOption.IGNORE_CASE)),
        contextLength = 4096,
        type = ModelType.RERANK,
        capabilities = ModelCapabilities(rerank = true),
        icon = "rerank",
        note = "Generic Rerank Model"
    ),

    // ==================== Embedding Models ====================

    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""embedding""", RegexOption.IGNORE_CASE)),
        contextLength = 8192,
        type = ModelType.EMBEDDING,
        capabilities = ModelCapabilities(embedding = true),
        icon = "embedding",
        note = "Generic Embedding Model"
    ),

    // ==================== Image Models ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""dall-e""", RegexOption.IGNORE_CASE)),
        contextLength = 1,
        type = ModelType.IMAGE,
        capabilities = ModelCapabilities(image = true),
        icon = "image",
        note = "DALL-E Series"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""flux""", RegexOption.IGNORE_CASE)),
        contextLength = 1,
        type = ModelType.IMAGE,
        capabilities = ModelCapabilities(image = true),
        icon = "image",
        note = "Flux Series"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""stable-diffusion""", RegexOption.IGNORE_CASE)),
        contextLength = 1,
        type = ModelType.IMAGE,
        capabilities = ModelCapabilities(image = true),
        icon = "image",
        note = "Stable Diffusion"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""sdxl""", RegexOption.IGNORE_CASE)),
        contextLength = 1,
        type = ModelType.IMAGE,
        capabilities = ModelCapabilities(image = true),
        icon = "image",
        note = "SDXL"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""kolors""", RegexOption.IGNORE_CASE)),
        contextLength = 1,
        type = ModelType.IMAGE,
        capabilities = ModelCapabilities(image = true),
        icon = "image",
        note = "Kolors"
    ),

    // ==================== 2026 April — OpenAI GPT-5 系列 ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-5.5"),
        contextLength = 400000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, structuredOutput = true, promptCaching = true),
        icon = "openai",
        note = "GPT-5.5 (Spud, Apr 2026)",
        maxOutputTokens = 128000,
        knowledgeCutoff = "202604"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-5.4"),
        contextLength = 400000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, structuredOutput = true),
        icon = "openai",
        note = "GPT-5.4 (Mar 2026)",
        maxOutputTokens = 128000,
        knowledgeCutoff = "202603"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-5.3"),
        contextLength = 400000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, structuredOutput = true),
        icon = "openai",
        note = "GPT-5.3",
        maxOutputTokens = 128000
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-5.2"),
        contextLength = 400000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, structuredOutput = true),
        icon = "openai",
        note = "GPT-5.2",
        maxOutputTokens = 128000
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-5.1"),
        contextLength = 400000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, structuredOutput = true),
        icon = "openai",
        note = "GPT-5.1",
        maxOutputTokens = 128000
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-5-pro"),
        contextLength = 400000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, structuredOutput = true, promptCaching = true),
        icon = "openai",
        note = "GPT-5 Pro",
        maxOutputTokens = 128000
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gpt-5-nano"),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, structuredOutput = true),
        icon = "openai",
        note = "GPT-5 Nano",
        maxOutputTokens = 32768
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""gpt-5\.\d-codex""", RegexOption.IGNORE_CASE)),
        contextLength = 400000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, structuredOutput = true, reasoning = true),
        icon = "openai",
        note = "GPT-5.x Codex",
        maxOutputTokens = 128000
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""gpt-5[.-]""", RegexOption.IGNORE_CASE)),
        contextLength = 400000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, structuredOutput = true),
        icon = "openai",
        note = "GPT-5 Series"
    ),

    // ==================== 2026 April — OpenAI O 推理系列补充 ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("o3-pro"),
        contextLength = 200000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true, vision = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O3 Pro (2026)",
        maxOutputTokens = 100000
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("o3-deep-research"),
        contextLength = 200000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true, vision = true),
        forcedReasoning = true,
        icon = "openai",
        note = "O3 Deep Research"
    ),

    // ==================== 2026 April — Anthropic Claude 新系列 ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-sonnet-5"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, computerUse = true, promptCaching = true, structuredOutput = true),
        icon = "claude",
        note = "Claude Sonnet 5 (Apr 2026, 1M)",
        maxOutputTokens = 128000,
        knowledgeCutoff = "202604"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-sonnet-4-6"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, computerUse = true, promptCaching = true),
        icon = "claude",
        note = "Claude Sonnet 4.6 (1M)",
        maxOutputTokens = 128000
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-opus-4-7"),
        contextLength = 200000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, computerUse = true, promptCaching = true, structuredOutput = true),
        icon = "claude",
        note = "Claude Opus 4.7 (2026)",
        maxOutputTokens = 32768
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-opus-4-6"),
        contextLength = 200000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, computerUse = true, promptCaching = true),
        icon = "claude",
        note = "Claude Opus 4.6",
        maxOutputTokens = 32768
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("claude-haiku-4-5"),
        contextLength = 200000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true, promptCaching = true),
        icon = "claude",
        note = "Claude Haiku 4.5 (2026)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""claude-sonnet-4[.-]""", RegexOption.IGNORE_CASE)),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, promptCaching = true),
        icon = "claude",
        note = "Claude Sonnet 4 Series"
    ),

    // ==================== Google Gemma 4 (2026 Apr) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemma-4-31b"),
        contextLength = 256000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "gemma",
        note = "Gemma 4 31B (Apr 2026, Apache 2.0)",
        maxOutputTokens = 8192,
        knowledgeCutoff = "202603"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemma-4-27b"),
        contextLength = 256000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, reasoning = true),
        icon = "gemma",
        note = "Gemma 4 27B",
        maxOutputTokens = 8192
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("gemma-4-9b"),
        contextLength = 256000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true),
        icon = "gemma",
        note = "Gemma 4 9B"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""gemma-4""", RegexOption.IGNORE_CASE)),
        contextLength = 256000,
        type = ModelType.CHAT,
        icon = "gemma",
        note = "Gemma 4 Series"
    ),

    // ==================== DeepSeek V4 (2026 Apr) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("deepseek-v4-pro"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true, structuredOutput = true),
        icon = "deepseek",
        note = "DeepSeek V4 Pro (Apr 2026, 1.6T MoE, MIT)",
        maxOutputTokens = 384000,
        knowledgeCutoff = "202604"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("deepseek-v4-flash"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true, structuredOutput = true),
        icon = "deepseek",
        note = "DeepSeek V4 Flash",
        maxOutputTokens = 384000,
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""deepseek-v4""", RegexOption.IGNORE_CASE)),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true, structuredOutput = true),
        icon = "deepseek",
        note = "DeepSeek V4 Series",
        maxOutputTokens = 384000,
    ),

    // ==================== Alibaba Qwen 3.6 (2026 Apr) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("qwen-3.6-plus"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true, vision = true),
        icon = "qwen",
        note = "Qwen 3.6 Plus (Apr 2026)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""qwen3\.6""", RegexOption.IGNORE_CASE)),
        contextLength = 1000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "qwen",
        note = "Qwen 3.6 Series"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("qwen-flash"),
        contextLength = 1000000,
        type = ModelType.CHAT,
        icon = "qwen",
        note = "Qwen Flash (1M context)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("qwen-long"),
        contextLength = 10000000,
        type = ModelType.CHAT,
        icon = "qwen",
        note = "Qwen Long (10M context)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("qwen-omni-turbo"),
        contextLength = 32768,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, audioInput = true, audioOutput = true, videoUnderstanding = true, image = true),
        icon = "qwen",
        note = "Qwen Omni Turbo (Full Multimodal)"
    ),

    // ==================== xAI Grok 4 系列 (2026) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("grok-4.1"),
        contextLength = 2000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true, vision = true, internet = true),
        icon = "xai",
        note = "Grok 4.1 (Apr 2026, 2M context)",
        maxOutputTokens = 65536
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""grok-4-?fast""", RegexOption.IGNORE_CASE)),
        contextLength = 2000000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true, vision = true),
        icon = "xai",
        note = "Grok 4 Fast (2M)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""grok-4[.-]""", RegexOption.IGNORE_CASE)),
        contextLength = 256000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true, vision = true),
        icon = "xai",
        note = "Grok 4 Series"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("grok-code-fast"),
        contextLength = 256000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "xai",
        note = "Grok Code Fast"
    ),

    // ==================== GLM-5 系列 (2026 Apr) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("glm-5.1"),
        contextLength = 200000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true, vision = true, structuredOutput = true),
        icon = "zhipu",
        note = "GLM-5.1 (Apr 2026, 744B MoE, MIT)",
        maxOutputTokens = 32768,
        knowledgeCutoff = "202604"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""glm-5[.-]""", RegexOption.IGNORE_CASE)),
        contextLength = 200000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true, vision = true),
        icon = "zhipu",
        note = "GLM-5 Series"
    ),

    // ==================== Doubao 1.5 系列 (2026) ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""doubao-1\.5-pro""", RegexOption.IGNORE_CASE)),
        contextLength = 256000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true, vision = true),
        icon = "bytedance",
        note = "Doubao 1.5 Pro (2026)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""doubao-1\.5-thinking""", RegexOption.IGNORE_CASE)),
        contextLength = 256000,
        type = ModelType.REASONING,
        capabilities = ModelCapabilities(reasoning = true, vision = true),
        forcedReasoning = true,
        icon = "bytedance",
        note = "Doubao 1.5 Thinking"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""doubao-1\.5""", RegexOption.IGNORE_CASE)),
        contextLength = 256000,
        type = ModelType.CHAT,
        icon = "bytedance",
        note = "Doubao 1.5 Series"
    ),

    // ==================== Kimi K2 (2026) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("kimi-k2"),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true, vision = true),
        icon = "kimi",
        note = "Kimi K2 (2026)"
    ),

    // ==================== Mistral 3 系列 (2026) ====================
    ModelSpec(
        pattern = ModelPattern.StringPattern("mistral-3-large"),
        contextLength = 256000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, structuredOutput = true, reasoning = true),
        icon = "mistral",
        note = "Mistral 3 Large (2026)"
    ),
    ModelSpec(
        pattern = ModelPattern.StringPattern("mistral-3-small"),
        contextLength = 256000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(vision = true, structuredOutput = true),
        icon = "mistral",
        note = "Mistral 3 Small (2026)"
    ),
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""mistral-3""", RegexOption.IGNORE_CASE)),
        contextLength = 256000,
        type = ModelType.CHAT,
        icon = "mistral",
        note = "Mistral 3 Series"
    ),

    // ==================== IBM Granite 4 (2026 Apr) ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""granite-4[.-]""", RegexOption.IGNORE_CASE)),
        contextLength = 128000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(reasoning = true),
        icon = "ibm",
        note = "Granite 4.0 (Apr 2026, Apache 2.0)",
        knowledgeCutoff = "202604"
    ),

    // ==================== Cohere Command A (2025-2026) ====================
    ModelSpec(
        pattern = ModelPattern.RegexPattern(Regex("""command-a""", RegexOption.IGNORE_CASE)),
        contextLength = 256000,
        type = ModelType.CHAT,
        capabilities = ModelCapabilities(rerank = true, internet = true, reasoning = true),
        icon = "cohere",
        note = "Command A (2025)"
    )
)

fun findContextLength(modelId: String): Int? {
    return findModelSpec(modelId)?.contextLength?.takeIf { it > 0 }
}

fun extractContextLengthFromName(text: String): Int? {
    val normalized = text.lowercase()
    val kMatch = Regex("""(\d+)k\b""").find(normalized)
    if (kMatch != null) {
        return kMatch.groupValues[1].toInt() * 1000
    }
    val mMatch = Regex("""(\d+)m\b""").find(normalized)
    if (mMatch != null) {
        return mMatch.groupValues[1].toInt() * 1000000
    }
    return null
}

/** 兼容旧调用点的持久化选择 ID；实际请求 ID 必须使用 findRemoteModelSpec。 */
fun findModelSpec(modelId: String): ModelSpec? =
    findRemoteModelSpec(modelId.substringAfter("::", modelId))

fun findRemoteModelSpec(remoteModelId: String): ModelSpec? =
    ModelCatalogRuntime.resolver.resolveExactOrNull(remoteModelId)?.toLegacyModelSpec()

private fun ResolvedModelMetadata.toLegacyModelSpec(): ModelSpec = ModelSpec(
    pattern = ModelPattern.StringPattern(canonicalModelId ?: remoteModelId),
    contextLength = contextTokens ?: 0,
    type = when (workload) {
        ModelWorkload.GENERATIVE_TEXT ->
            if (capabilities[ModelCapability.REASONING] == SupportState.SUPPORTED) {
                ModelType.REASONING
            } else {
                ModelType.CHAT
            }
        ModelWorkload.EMBEDDING -> ModelType.EMBEDDING
        ModelWorkload.RERANK -> ModelType.RERANK
        ModelWorkload.IMAGE_GENERATION -> ModelType.IMAGE
        ModelWorkload.AUDIO,
        ModelWorkload.VIDEO,
        ModelWorkload.UNKNOWN,
        -> null
    },
    capabilities = ModelCapabilities(
        vision = capabilities[ModelCapability.VISION_INPUT] == SupportState.SUPPORTED,
        internet = capabilities[ModelCapability.WEB_ACCESS] == SupportState.SUPPORTED,
        reasoning = capabilities[ModelCapability.REASONING] == SupportState.SUPPORTED,
        image = workload == ModelWorkload.IMAGE_GENERATION,
        embedding = workload == ModelWorkload.EMBEDDING,
        rerank = workload == ModelWorkload.RERANK,
        audioInput = capabilities[ModelCapability.AUDIO_INPUT] == SupportState.SUPPORTED,
        audioOutput = capabilities[ModelCapability.AUDIO_OUTPUT] == SupportState.SUPPORTED,
        videoUnderstanding = capabilities[ModelCapability.VIDEO_INPUT] == SupportState.SUPPORTED,
        structuredOutput = capabilities[ModelCapability.STRUCTURED_OUTPUT] == SupportState.SUPPORTED,
        promptCaching = capabilities[ModelCapability.PROMPT_CACHING] == SupportState.SUPPORTED,
        computerUse = capabilities[ModelCapability.COMPUTER_USE] == SupportState.SUPPORTED,
    ),
    forcedReasoning = capabilities[ModelCapability.REASONING] == SupportState.SUPPORTED,
    icon = when (workload) {
        ModelWorkload.EMBEDDING -> "embedding"
        ModelWorkload.RERANK -> "rerank"
        else -> null
    },
    note = displayName,
    maxOutputTokens = outputTokens ?: 0,
    knowledgeCutoff = knowledgeCutoff,
)

data class ModelPricing(
    val inputPerMillion: Double,
    val outputPerMillion: Double,
    val cacheReadPerMillion: Double? = null,
    val cacheWritePerMillion: Double? = null,
    val cacheWriteLongTtlPerMillion: Double? = null,
)

// 缓存单价按官方标准文本短上下文档填写，单位为美元/百万 Token；未能唯一对应的家族或分档保留 null。
// 核对日期：2026-11-02。来源：OpenAI 模型页、Claude pricing、Gemini API pricing、Z.AI pricing。
private val MODEL_PRICING: List<Pair<ModelPattern, ModelPricing>> = listOf(
    ModelPattern.StringPattern("gpt-4.1") to ModelPricing(2.0, 8.0, 0.50),
    ModelPattern.StringPattern("gpt-4.1-mini") to ModelPricing(0.40, 1.60, 0.10),
    ModelPattern.StringPattern("gpt-4.1-nano") to ModelPricing(0.10, 0.40, 0.025),
    ModelPattern.StringPattern("gpt-4o") to ModelPricing(2.50, 10.0, 1.25),
    ModelPattern.StringPattern("gpt-4o-mini") to ModelPricing(0.15, 0.60, 0.075),
    ModelPattern.StringPattern("o3") to ModelPricing(2.0, 8.0, 0.50),
    ModelPattern.StringPattern("o4-mini") to ModelPricing(1.10, 4.40, 0.275),
    ModelPattern.StringPattern("o1") to ModelPricing(15.0, 60.0, 7.50),
    ModelPattern.StringPattern("o1-mini") to ModelPricing(3.0, 12.0, 0.55),
    ModelPattern.StringPattern("claude-sonnet") to ModelPricing(3.0, 15.0),
    ModelPattern.StringPattern("claude-opus") to ModelPricing(15.0, 75.0),
    ModelPattern.StringPattern("claude-haiku") to ModelPricing(0.80, 4.0),
    ModelPattern.StringPattern("claude-3.5-sonnet") to ModelPricing(3.0, 15.0),
    ModelPattern.StringPattern("claude-3.5-haiku") to ModelPricing(0.80, 4.0, 0.08, 1.0, 1.60),
    ModelPattern.StringPattern("deepseek-chat") to ModelPricing(0.27, 1.10),
    ModelPattern.StringPattern("deepseek-reasoner") to ModelPricing(0.55, 2.19),
    ModelPattern.RegexPattern(Regex("""deepseek-r1""", RegexOption.IGNORE_CASE)) to ModelPricing(0.55, 2.19),
    ModelPattern.StringPattern("gemini-2.5-pro") to ModelPricing(1.25, 10.0, 0.125),
    ModelPattern.StringPattern("gemini-2.5-flash") to ModelPricing(0.15, 0.60, 0.03),
    ModelPattern.StringPattern("gemini-2.0-flash") to ModelPricing(0.10, 0.40),
    ModelPattern.StringPattern("gemini-1.5-pro") to ModelPricing(1.25, 5.0),
    ModelPattern.StringPattern("gemini-1.5-flash") to ModelPricing(0.075, 0.30),
    ModelPattern.StringPattern("glm-4") to ModelPricing(1.50, 5.0),
    ModelPattern.StringPattern("moonshot") to ModelPricing(0.50, 2.0),
    ModelPattern.StringPattern("minimax") to ModelPricing(0.40, 1.60),
    ModelPattern.StringPattern("qwen") to ModelPricing(0.30, 0.90),

    // ==================== 2026 年定价更新 ====================
    ModelPattern.StringPattern("gpt-5.5") to ModelPricing(7.50, 30.0, 0.50),
    ModelPattern.StringPattern("gpt-5.4") to ModelPricing(5.0, 20.0, 0.25),
    ModelPattern.StringPattern("gpt-5.3") to ModelPricing(3.75, 15.0),
    ModelPattern.StringPattern("gpt-5.2") to ModelPricing(3.75, 15.0, 0.175),
    ModelPattern.StringPattern("gpt-5.1") to ModelPricing(3.75, 15.0, 0.125),
    ModelPattern.StringPattern("gpt-5-pro") to ModelPricing(15.0, 60.0),
    ModelPattern.StringPattern("gpt-5-nano") to ModelPricing(0.075, 0.30, 0.005),
    ModelPattern.StringPattern("gpt-5-codex") to ModelPricing(3.75, 15.0, 0.125),
    ModelPattern.StringPattern("o3-pro") to ModelPricing(10.0, 40.0),
    ModelPattern.StringPattern("claude-sonnet-5") to ModelPricing(5.0, 25.0, 0.20, 2.50, 4.0),
    ModelPattern.StringPattern("claude-sonnet-4-6") to ModelPricing(3.75, 18.75, 0.30, 3.75, 6.0),
    ModelPattern.StringPattern("claude-opus-4-7") to ModelPricing(18.75, 93.75, 0.50, 6.25, 10.0),
    ModelPattern.StringPattern("claude-haiku-4-5") to ModelPricing(1.0, 5.0, 0.10, 1.25, 2.0),
    ModelPattern.StringPattern("gemini-3.1-pro") to ModelPricing(1.875, 7.50, 0.20),
    ModelPattern.StringPattern("gemini-3.1-flash") to ModelPricing(0.25, 0.75),
    ModelPattern.StringPattern("gemini-3.1-flash-lite") to ModelPricing(0.25, 1.50, 0.025),
    ModelPattern.StringPattern("gemini-3-pro") to ModelPricing(1.25, 5.0),
    ModelPattern.StringPattern("gemini-3-flash") to ModelPricing(0.15, 0.60, 0.05),
    ModelPattern.StringPattern("deepseek-v4-pro") to ModelPricing(0.60, 2.40),
    ModelPattern.StringPattern("grok-4.1") to ModelPricing(2.50, 10.0),
    ModelPattern.StringPattern("grok-4-fast") to ModelPricing(1.0, 4.0),
    ModelPattern.StringPattern("glm-5.1") to ModelPricing(0.70, 2.80, 0.26),
    ModelPattern.StringPattern("qwen-3.6-plus") to ModelPricing(0.28, 1.12),
    ModelPattern.StringPattern("qwen-flash") to ModelPricing(0.082, 0.32),
    ModelPattern.StringPattern("mistral-3-large") to ModelPricing(2.0, 6.0),
    ModelPattern.StringPattern("mistral-3-small") to ModelPricing(0.20, 0.60),
)

fun findModelPricing(modelId: String): ModelPricing? {
    val matched = MODEL_PRICING.mapNotNull { (pattern, pricing) -> pattern.rank(modelId)?.let { Triple(it, pattern, pricing) } }
        .maxByOrNull { it.first } ?: return null
    val pattern = matched.second as? ModelPattern.StringPattern ?: return matched.third
    val suffix = modelId.lowercase().substringAfter(pattern.value.lowercase())
    // 普通输入输出保留家族回退；缓存价仅适用于已核实别名，不能扩散到 mini/pro/lite 等变体。
    return if (suffix.isEmpty() || suffix in VERIFIED_CACHE_PRICE_SUFFIXES[pattern.value].orEmpty()) matched.third
    else matched.third.copy(cacheReadPerMillion = null, cacheWritePerMillion = null, cacheWriteLongTtlPerMillion = null)
}

/** 官方模型页列明的同价快照或预览别名；未核实的后缀保持未知。 */
private val VERIFIED_CACHE_PRICE_SUFFIXES = mapOf(
    "gpt-4.1" to setOf("-2025-04-14"),
    "gpt-4.1-mini" to setOf("-2025-04-14"),
    "gpt-4.1-nano" to setOf("-2025-04-14"),
    "gpt-5.4" to setOf("-2026-03-05"),
    "gpt-5.5" to setOf("-2026-04-23"),
    "gemini-3.1-pro" to setOf("-preview", "-preview-customtools"),
    "gemini-3-flash" to setOf("-preview"),
)
