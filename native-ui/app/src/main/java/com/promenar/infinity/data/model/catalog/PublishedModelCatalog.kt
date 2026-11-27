package com.promenar.infinity.data.model.catalog

import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

class PublishedModelCatalog private constructor(records: List<ModelMetadataRecord>) {
    val records: List<ModelMetadataRecord> = Collections.unmodifiableList(
        records.map { record ->
            record.copy(
                exactAliases = Collections.unmodifiableSet(LinkedHashSet(record.exactAliases)),
                capabilities = Collections.unmodifiableMap(LinkedHashMap(record.capabilities)),
            )
        },
    )

    companion object {
        private const val SCHEMA_VERSION = 3
        private const val MAX_BYTES = 16 * 1024 * 1024
        private const val MAX_RECORDS = 30_000
        private val json = Json { ignoreUnknownKeys = true }
        private val sha256 = Regex("[0-9a-f]{64}")

        fun fromJson(source: String): PublishedModelCatalog {
            require(source.encodeToByteArray().size <= MAX_BYTES) { "模型目录超过大小上限" }
            val root = json.parseToJsonElement(source) as? JsonObject
                ?: throw IllegalArgumentException("模型目录顶层必须是对象")
            require(root.requiredInt("schemaVersion") == SCHEMA_VERSION) { "模型目录 schemaVersion 不受支持" }
            root.requiredString("generatedAt")
            validateSources(root.requiredArray("sources"))
            val recordsJson = root.requiredArray("records")
            require(recordsJson.isNotEmpty()) { "模型目录不得为空" }
            require(recordsJson.size <= MAX_RECORDS) { "模型目录记录数超过上限" }
            val records = recordsJson.mapIndexed { index, element -> parseRecord(index, element) }
            validateIdentity(records)
            return PublishedModelCatalog(records)
        }

        private fun validateSources(sources: JsonArray) {
            val ids = mutableSetOf<String>()
            sources.forEachIndexed { index, element ->
                val source = element as? JsonObject
                    ?: throw IllegalArgumentException("sources[$index] 必须是对象")
                val id = source.requiredNonBlankString("id")
                require(ids.add(id)) { "sources 存在重复 id: $id" }
                source.requiredNonBlankString("url")
                source.requiredNonBlankString("fetchedAt")
                require(sha256.matches(source.requiredString("sha256"))) { "sources[$index].sha256 非法" }
                source.requiredNonBlankString("license")
            }
        }

        private fun parseRecord(index: Int, element: JsonElement): ModelMetadataRecord {
            val record = element as? JsonObject
                ?: throw IllegalArgumentException("records[$index] 必须是对象")
            val canonicalId = record.requiredNonBlankString("canonicalModelId")
            val aliases = record.optionalStringArray("exactAliases")
            require(aliases.size == aliases.toSet().size) {
                "records[$index].exactAliases 存在重复项"
            }
            val source = record.requiredEnum<MetadataSource>("source")
            require(source in setOf(
                MetadataSource.MODELS_DEV,
                MetadataSource.LITELLM,
                MetadataSource.OPENROUTER,
                MetadataSource.INFINITY_OVERRIDE,
            )) { "records[$index].source 不允许发布" }
            val providerScope = record.optionalString("providerScope")
            if (source == MetadataSource.LITELLM || source == MetadataSource.OPENROUTER) {
                require(providerScope != null) { "records[$index] 的 ${source.name} 记录必须声明 providerScope" }
            }
            val workload = parsePublishedWorkload(record.optionalString("workload"), canonicalId)
            val capabilities = linkedMapOf<ModelCapability, SupportState>()
            if (workload == ModelWorkload.RERANK) {
                capabilities[ModelCapability.CHAT_ENDPOINT] = SupportState.UNSUPPORTED
            }
            record.optionalBoolean("reasoning")?.let {
                capabilities[ModelCapability.REASONING] = it.toSupportState()
            }
            record.optionalBoolean("tool_call")?.let {
                capabilities[ModelCapability.TOOL_CALLING] = it.toSupportState()
            }
            record.optionalBoolean("structured_output")?.let {
                capabilities[ModelCapability.STRUCTURED_OUTPUT] = it.toSupportState()
            }
            record.optionalBoolean("prompt_caching")?.let {
                capabilities[ModelCapability.PROMPT_CACHING] = it.toSupportState()
            }
            record.optionalBoolean("web_search")?.let {
                capabilities[ModelCapability.WEB_ACCESS] = it.toSupportState()
            }
            record.optionalBoolean("computer_use")?.let {
                capabilities[ModelCapability.COMPUTER_USE] = it.toSupportState()
            }
            record.optionalModalities().forEach { (direction, modalities) ->
                modalities.forEach { modality -> when ("$direction:${modality.lowercase()}") {
                    "input:image", "input:image_input", "input:vision" -> capabilities[ModelCapability.VISION_INPUT] = SupportState.SUPPORTED
                    "input:audio", "input:audio_input" -> capabilities[ModelCapability.AUDIO_INPUT] = SupportState.SUPPORTED
                    "output:audio", "output:audio_output" -> capabilities[ModelCapability.AUDIO_OUTPUT] = SupportState.SUPPORTED
                    "input:video", "input:video_input" -> capabilities[ModelCapability.VIDEO_INPUT] = SupportState.SUPPORTED
                } }
            }
            val releaseDate = record.optionalString("release_date")
            val lastUpdated = record.optionalString("last_updated")
            val deprecationDate = record.optionalString("deprecation_date")
            val status = record.optionalString("status")
            return ModelMetadataRecord(
                canonicalModelId = canonicalId,
                exactAliases = aliases.toSet(),
                displayName = record.requiredNonBlankString("displayName"),
                familyName = record.optionalString("family"),
                workload = workload,
                capabilities = capabilities,
                contextTokens = record.optionalPositiveInt("contextTokens"),
                inputTokens = record.optionalPositiveInt("inputTokens"),
                outputTokens = record.optionalPositiveInt("outputTokens"),
                knowledgeCutoff = record.optionalString("knowledge"),
                source = source,
                providerScope = providerScope,
                releaseDate = releaseDate,
                lastUpdated = lastUpdated,
                deprecationDate = deprecationDate,
                status = status,
            )
        }

        private fun validateIdentity(records: List<ModelMetadataRecord>) {
            val identities = mutableSetOf<Triple<MetadataSource, String?, String>>()
            records.forEach { record ->
                val identity = Triple(
                    record.source,
                    record.providerScope,
                    record.canonicalModelId,
                )
                require(identities.add(identity)) { "模型目录存在重复 identity/scope: ${record.canonicalModelId}" }
            }
        }

        private fun Boolean.toSupportState() = if (this) SupportState.SUPPORTED else SupportState.UNSUPPORTED

        private val liveSegmentPattern = Regex("""(?:^|[-_])live(?:$|[-_])""")

        /** 兼容线上 schema v3 目录仍发布的旧版 AUDIO 类型，按规范 ID 细分至实时、音乐或语音。 */
        private fun parsePublishedWorkload(raw: String?, canonicalId: String): ModelWorkload {
            if (raw == null) return ModelWorkload.UNKNOWN
            if (raw == "AUDIO") {
                val id = canonicalId.lowercase().substringAfterLast('/')
                return when {
                    "realtime" in id || "native-audio" in id || liveSegmentPattern.containsMatchIn(id) ->
                        ModelWorkload.REALTIME
                    id.startsWith("lyria") || "music" in id -> ModelWorkload.MUSIC
                    else -> ModelWorkload.SPEECH
                }
            }
            return enumValueOf(raw)
        }

        private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(name: String): T =
            enumValueOf(requiredString(name))

        private fun JsonObject.requiredArray(name: String): JsonArray = this[name] as? JsonArray
            ?: throw IllegalArgumentException("$name 必须是数组")

        private fun JsonObject.requiredInt(name: String): Int =
            (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: throw IllegalArgumentException("$name 必须是整数")

        private fun JsonObject.requiredString(name: String): String =
            (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: throw IllegalArgumentException("$name 必须是字符串")

        private fun JsonObject.requiredNonBlankString(name: String): String =
            requiredString(name).also { require(it.isNotBlank()) { "$name 不得为空" } }

        private fun JsonObject.optionalString(name: String): String? {
            val value = this[name] ?: return null
            if (value is JsonNull) return null
            return (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.also { require(it.isNotBlank()) { "$name 不得为空" } }
                ?: throw IllegalArgumentException("$name 必须是字符串或 null")
        }

        private fun JsonObject.optionalBoolean(name: String): Boolean? {
            val value = this[name] ?: return null
            if (value is JsonNull) return null
            return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: throw IllegalArgumentException("$name 必须是布尔值或 null")
        }

        private fun JsonObject.optionalPositiveInt(name: String): Int? {
            val value = this[name] ?: return null
            if (value is JsonNull) return null
            val parsed = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: throw IllegalArgumentException("$name 必须是 Int 范围整数或 null")
            require(parsed > 0) { "$name 必须为正整数" }
            return parsed
        }

        private fun JsonObject.optionalStringArray(name: String): List<String> {
            val value = this[name] ?: return emptyList()
            if (value is JsonNull) return emptyList()
            val array = value as? JsonArray ?: throw IllegalArgumentException("$name 必须是字符串数组或 null")
            return array.mapIndexed { index, item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    ?.also { require(it.isNotBlank()) { "$name[$index] 不得为空" } }
                    ?: throw IllegalArgumentException("$name[$index] 必须是字符串")
            }
        }

        private fun JsonObject.optionalModalities(): Map<String, List<String>> {
            val value = this["modalities"] ?: return emptyMap()
            if (value is JsonNull) return emptyMap()
            val modalities = value as? JsonObject
                ?: throw IllegalArgumentException("modalities 必须是对象或 null")
            return listOf("input", "output").mapNotNull { direction ->
                if (direction !in modalities) return@mapNotNull null
                val values = modalities.optionalStringArray(direction)
                require(values.size == values.toSet().size) { "modalities.$direction 存在重复项" }
                direction to values
            }.toMap()
        }
    }
}
