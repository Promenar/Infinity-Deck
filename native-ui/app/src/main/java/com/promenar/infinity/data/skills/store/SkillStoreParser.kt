package com.promenar.infinity.data.skills.store

import com.promenar.infinity.data.skills.AgentSkillStore
import com.promenar.infinity.data.skills.SkillMarkdownParser
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.*

internal class StoreException(val failure: StoreFailure, val httpStatus: Int? = null) : Exception(failure.name)
internal data class ParsedStoreCatalog(val title: String?, val categories: List<SkillStoreCategory>, val skills: List<SkillStoreEntry>)

internal object SkillStoreParser {
    private val versionPattern = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([A-Za-z0-9.-]+))?$")
    private val scriptExtensions = setOf("py", "sh", "bash", "zsh", "js", "mjs", "cjs", "ts", "rb", "pl", "php", "ps1", "bat", "cmd", "exe", "bin", "so", "dylib", "jar")
    private fun JsonObject.string(key: String): String = (get(key) as JsonPrimitive).let { require(it.isString); it.content }
    private fun JsonObject.long(key: String): Long = (get(key) as JsonPrimitive).let { require(!it.isString); it.long }
    private fun JsonObject.optionalString(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    fun parse(bytes: ByteArray, url: String, locale: Locale): ParsedStoreCatalog {
        if (bytes.size > 1024 * 1024) throw StoreException(StoreFailure.TOO_LARGE)
        val json = try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject } catch (_: Exception) { throw StoreException(StoreFailure.INVALID_CATALOG) }
        if (runCatching { json.long("schema") }.getOrNull() != 1L) throw StoreException(StoreFailure.UNSUPPORTED_SCHEMA)
        val categories = (json["categories"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.optionalString("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SkillStoreCategory(id, localized(item.obj("title"), locale) ?: id)
        }.distinctBy { it.id }
        val skills = json["skills"] as? JsonArray ?: throw StoreException(StoreFailure.INVALID_CATALOG)
        val entries = skills.mapNotNull { item -> runCatching { parseEntry(item as JsonObject, url, locale, categories.map { it.id }.toSet()) }.getOrNull() }.distinctBy { it.name }
        return ParsedStoreCatalog(localized(json.obj("title"), locale), categories, entries)
    }
    private fun parseEntry(obj: JsonObject, url: String, locale: Locale, categoryIds: Set<String>): SkillStoreEntry {
        val name = obj.string("name"); SkillMarkdownParser.validateName(name)
        val version = obj.string("version"); require(versionPattern.matches(version))
        val hash = obj.string("sha256"); require(Regex("^[0-9a-fA-F]{64}$").matches(hash))
        val size = obj.long("size"); require(size in 1..AgentSkillStore.MAX_TOTAL_BYTES)
        val archive = URI(url).resolve(obj.string("archive")); require(archive.scheme.equals("https", true) && !archive.host.isNullOrBlank() && archive.userInfo == null)
        val array = obj["files"] as JsonArray; require(array.size in 1..AgentSkillStore.MAX_FILES)
        val files = array.map { element ->
            val f = element as JsonObject; val raw = f.string("path")
            val path = AgentSkillStore.normalizeRelativePath(raw); require(raw == path)
            val length = f.long("size"); require(length in 0..AgentSkillStore.MAX_FILE_BYTES.toLong())
            SkillStoreFile(path, length)
        }
        require(files.map { it.path }.toSet().size == files.size && files.any { it.path == "SKILL.md" })
        require(files.sumOf { it.size } <= AgentSkillStore.MAX_TOTAL_BYTES)
        val channelArray = obj["channels"] as JsonArray; require(channelArray.isNotEmpty())
        val channels = channelArray.map { when ((it as JsonPrimitive).content) {
            "sideload" -> SkillChannel.SIDELOAD
            "play" -> SkillChannel.PLAY
            else -> error("未知渠道")
        } }.toSet()
        val min = obj["minAppVersionCode"]?.takeUnless { it is JsonNull }?.let { obj.long("minAppVersionCode").also { value -> require(value in 0..Int.MAX_VALUE.toLong()) }.toInt() }
        return SkillStoreEntry(name, version, localized(obj.obj("title"), locale) ?: name,
            localized(obj.obj("summary"), locale).orEmpty(), obj.optionalString("category")?.takeIf { it in categoryIds },
            obj.optionalString("author").orEmpty(), obj.optionalString("license").orEmpty(), archive.toString(), size, hash.lowercase(Locale.ROOT), files,
            files.any { it.path.startsWith("scripts/") || it.path.substringAfterLast('.', "").lowercase(Locale.ROOT) in scriptExtensions }, channels, min)
    }
    private fun localized(obj: JsonObject?, locale: Locale): String? {
        obj ?: return null
        val keys = obj.keys.toList()
        val key = keys.firstOrNull { it.equals(locale.toLanguageTag(), true) }
            ?: keys.firstOrNull { it.equals(locale.language, true) }
            ?: keys.firstOrNull { it.substringBefore('-').equals(locale.language, true) }
            ?: keys.firstOrNull { it.equals("en", true) } ?: keys.firstOrNull()
        return key?.let { obj.optionalString(it) }
    }
    fun compareVersions(a: String, b: String): Int {
        val x = requireNotNull(versionPattern.matchEntire(a)); val y = requireNotNull(versionPattern.matchEntire(b))
        for (i in 1..3) {
            val left = x.groupValues[i].trimStart('0').ifEmpty { "0" }; val right = y.groupValues[i].trimStart('0').ifEmpty { "0" }
            val comparison = left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
            if (comparison != 0) return comparison
        }
        val left = x.groupValues[4]; val right = y.groupValues[4]
        return when { left.isEmpty() && right.isNotEmpty() -> 1; left.isNotEmpty() && right.isEmpty() -> -1; else -> left.compareTo(right) }
    }
}
