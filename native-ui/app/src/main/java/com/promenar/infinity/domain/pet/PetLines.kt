package com.promenar.infinity.domain.pet

/** 将数据通道值代入兜底模板，见规格第 6、7.1 节。 */
object PetLineTemplate {
    private val key = Regex("\\{([^{}]+)}")

    fun render(template: String, data: Map<String, String>): String? {
        if (key.findAll(template).any { it.groupValues[1] !in data }) return null
        return key.replace(template) { data.getValue(it.groupValues[1]) }
    }

    fun firstAvailable(templates: List<String>, data: Map<String, String>): String? =
        templates.firstNotNullOfOrNull { render(it, data) }
}

/** 记录台词间隔与滚动一小时内的请求次数，见规格第 7.2 节。 */
class PetLineLimiter(
    private val clock: () -> Long,
    private val minimumIntervalMillis: Long = 20_000,
    private val maxRequestsPerHour: Int = 30,
) {
    private val requests = ArrayDeque<Long>()
    private var lastLine: Long? = null

    init {
        require(minimumIntervalMillis >= 0 && maxRequestsPerHour >= 0)
    }

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        while (requests.isNotEmpty() && now >= requests.first() && now - requests.first() >= 3_600_000) {
            requests.removeFirst()
        }
        if (lastLine?.let { now < it || now - it < minimumIntervalMillis } == true ||
            requests.size >= maxRequestsPerHour
        ) return false
        requests.addLast(now)
        lastLine = now
        return true
    }

    @Synchronized
    fun recordLine() {
        lastLine = clock()
    }
}

/** 台词模型的系统消息与独立用户消息，见规格第 7.2 节。 */
data class PetLinePrompt(
    val system: String,
    val user: String,
)

/** 构造不包含会话正文的台词请求，见规格第 6、7.2 节。 */
object PetLinePromptBuilder {
    fun build(persona: PetPersona, signal: String, data: Map<String, String>, limits: PetLimits = PetLimits()): PetLinePrompt {
        fun text(value: String, max: Int) = value.takeCodePoints(max)
        fun items(values: List<String>) = values.take(limits.maxPersonaItems).map { text(it, limits.maxPersonaItemLength) }
        val system = listOf(
            "你是会话宠物。只输出一句不超过30字的纯文本。",
            "角色：${persona.name}",
            "自称：${persona.self}",
            "称呼用户：${persona.user}",
            "性格：${text(persona.personality, limits.maxPersonaText)}",
            "风格：${text(persona.style, limits.maxPersonaText)}",
            "口头禅：${items(persona.catchphrases)}",
            "避免：${items(persona.avoid)}",
            "示例：${items(persona.examples)}",
            "使用语言：${persona.languages.firstOrNull() ?: "zh-CN"}",
        ).joinToString("\n")
        val safeData = data.toSortedMap().entries.joinToString("\n") {
            "${it.key}: ${it.value.replace("\r", "").replace("\n", "").takeCodePoints(60)}"
        }
        return PetLinePrompt(system, "触发信号：$signal\n数据：\n$safeData")
    }
}

private fun String.takeCodePoints(max: Int): String =
    if (codePointCount(0, length) <= max) this else substring(0, offsetByCodePoints(0, max))

/** 去除换行和包围引号，按 Unicode 码点截取 30 字，见规格第 7.2 节。 */
fun cleanPetLine(value: String): String? {
    val cleaned = value.replace("\r", "").replace("\n", "").trim()
        .trim('"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』')
        .trim().takeCodePoints(30)
    return cleaned.takeIf { it.isNotBlank() }
}
