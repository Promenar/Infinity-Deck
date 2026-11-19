package com.promenar.infinity.startup.diagnostics

import com.promenar.infinity.utils.SensitiveDataRedactor
import com.promenar.infinity.utils.SensitiveDataRedactor.OMITTED_SECRET
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class DiagnosticTraceMode { STRICT_SHAREABLE, ADB_LOCAL }

internal object AdbTraceRedactor {
    private const val MAX_SOURCE_CHARS = 64 * 1024
    private const val MAX_PARSE_DEPTH = 32
    private const val MAX_ARGUMENT_BYTES = 6 * 1024
    private const val MAX_DEPTH = 5
    private const val MAX_ENTRIES = 40
    private const val MAX_KEY_CHARS = 128
    private const val MAX_VALUE_POINTS = 500
    private val omitted = JsonPrimitive(OMITTED_SECRET)
    private val truncated = JsonPrimitive("[truncated]")
    private val lineControls = Regex("""[\r\n\t\p{Zl}\p{Zp}]+""")
    private val headerListFields = setOf("namesandvalues", "entries", "pairs")

    fun title(text: String): String = preview(text, 80)
    fun error(text: String): String = preview(text, 600)
    fun errorPreview(text: String): String = preview(text, 300)

    fun arguments(source: String?): JsonElement {
        if (source == null) return JsonNull
        if (source.length > MAX_SOURCE_CHARS || !hasBoundedNesting(source)) return omitted
        val parsed = runCatching { diagnosticJson.parseToJsonElement(source) }.getOrNull()
        return when (parsed) {
            is JsonObject, is JsonArray -> ArgumentProjection().project(parsed, depth = 0) ?: omitted
            else -> JsonNull
        }
    }

    private fun preview(text: String, limit: Int): String =
        if (text.length > MAX_SOURCE_CHARS) OMITTED_SECRET
        else limitCodePoints(lineControls.replace(SensitiveDataRedactor.redactSecretsOnly(text), " "), limit)

    // 解析库仍需读取整份入参，先限制输入嵌套，再限制投影范围。
    fun hasBoundedNesting(source: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in source) {
            when {
                escaped -> escaped = false
                inString && character == '\\' -> escaped = true
                character == '"' -> inString = !inString
                !inString && character in "[{" -> if (++depth > MAX_PARSE_DEPTH) return false
                !inString && character in "]}" -> if (--depth < 0) return false
            }
        }
        return depth == 0 && !inString
    }

    private fun limitCodePoints(value: String, limit: Int): String =
        if (value.codePointCount(0, value.length) <= limit) value
        else value.substring(0, value.offsetByCodePoints(0, limit))

    private fun hasHeaderSecret(value: JsonElement): Boolean = when (value) {
        is JsonPrimitive -> SensitiveDataRedactor.redactSecretsOnly(value.content) != value.content
        is JsonArray -> value.size > MAX_ENTRIES || value.any { it !is JsonPrimitive || hasHeaderSecret(it) }
        is JsonObject -> true
    }

    private fun sanitizeValue(value: JsonPrimitive, field: String?): JsonPrimitive {
        if (value === JsonNull) return value
        val text = value.content
        val cleaned = SensitiveDataRedactor.redactSecretsOnly(text)
        return when {
            !value.isString -> {
                // JSON 树解析器允许未加引号的非标准字面量，输出端只保留合法 JSON 标量。
                val valid = text.length <= MAX_VALUE_POINTS && SensitiveDataRedactor.isJsonScalar(text)
                if (valid && cleaned == text) value else omitted
            }
            field == "path" && (cleaned != text || SensitiveDataRedactor.isSensitivePath(text)) -> omitted
            field == "url" -> JsonPrimitive(limitCodePoints(SensitiveDataRedactor.stripUrlSuffix(cleaned), MAX_VALUE_POINTS))
            else -> JsonPrimitive(limitCodePoints(cleaned, MAX_VALUE_POINTS))
        }
    }

    private fun fieldKind(key: String): String? {
        val normalized = normalizeField(key)
        return when {
            normalized == "path" -> "path"
            normalized.endsWith("headers") -> "headers"
            normalized.endsWith("url") || normalized.endsWith("uri") -> "url"
            else -> null
        }
    }

    private fun normalizeField(key: String): String = key.lowercase().replace("_", "").replace("-", "")

    private fun usesHeaderPairs(source: JsonObject): Boolean {
        val keys = source.keys.mapTo(mutableSetOf(), ::normalizeField)
        return ("name" in keys && "value" in keys) || ("names" in keys && "values" in keys)
    }

    private class ArgumentProjection {
        private var remaining = MAX_ARGUMENT_BYTES

        fun project(value: JsonElement, depth: Int, field: String? = null): JsonElement? = when {
            depth >= MAX_DEPTH -> fit(truncated)
            field == "headers" && value !is JsonObject && value !== JsonNull -> fit(omitted)
            value is JsonObject -> projectObject(value, depth, headers = field == "headers")
            value is JsonArray -> projectArray(value, depth, field)
            value is JsonPrimitive -> fit(sanitizeValue(value, field))
            else -> null
        }

        private fun projectObject(source: JsonObject, depth: Int, headers: Boolean): JsonElement? {
            if (headers && usesHeaderPairs(source)) return fit(omitted)
            if (!reserve(2)) return null
            val fields = linkedMapOf<String, JsonElement>()
            for ((key, value) in source.asSequence().take(MAX_ENTRIES)) {
                if (key.length > MAX_KEY_CHARS || SensitiveDataRedactor.redactSecretsOnly(key) != key) continue
                val overhead = serializedBytes(JsonPrimitive(key)) + 1 + if (fields.isEmpty()) 0 else 1
                if (!reserve(overhead)) break
                val projected = if (SensitiveDataRedactor.isSecretKey(key) ||
                    (headers && (normalizeField(key) in headerListFields || hasHeaderSecret(value)))
                ) {
                    fit(omitted)
                } else {
                    project(value, depth + 1, fieldKind(key))
                }
                if (projected == null) {
                    remaining += overhead
                    break
                }
                fields[key] = projected
            }
            return JsonObject(fields)
        }

        private fun projectArray(source: JsonArray, depth: Int, field: String?): JsonArray? {
            if (!reserve(2)) return null
            val items = mutableListOf<JsonElement>()
            for (value in source.asSequence().take(MAX_ENTRIES)) {
                val overhead = if (items.isEmpty()) 0 else 1
                if (!reserve(overhead)) break
                val projected = project(value, depth + 1, field)
                if (projected == null) {
                    remaining += overhead
                    break
                }
                items += projected
            }
            return JsonArray(items)
        }

        // 与最终 JSONL 使用相同的转义形式，同时计入 UTF-8、多字节标点和容器结构。
        private fun serializedBytes(value: JsonPrimitive): Int =
            value.toString().toByteArray(Charsets.UTF_8).size

        private fun fit(value: JsonPrimitive): JsonPrimitive? =
            value.takeIf { reserve(serializedBytes(it)) }

        private fun reserve(bytes: Int): Boolean {
            if (bytes > remaining) return false
            remaining -= bytes
            return true
        }
    }
}
