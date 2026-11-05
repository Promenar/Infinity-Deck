package com.promenar.infinity.data.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

private val vectorMetadataJson = Json { ignoreUnknownKeys = true }

/** 只为结构化 metadata 附加已核验的模型空间；未知历史格式保持原样。 */
internal fun withVectorSpaceMetadata(metadata: String?, spaceKey: String?): String? {
    if (spaceKey == null) return metadata
    val source = vectorMetadataObject(metadata) ?: return if (metadata?.startsWith("type=image&") == true) "$metadata&embeddingSpaceKey=$spaceKey" else metadata
    return JsonObject(source + ("embeddingSpaceKey" to JsonPrimitive(spaceKey))).toString()
}

internal fun vectorSpaceFromMetadata(metadata: String?): String? =
    (vectorMetadataObject(metadata)?.get("embeddingSpaceKey") as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
        ?: metadata?.takeIf { it.startsWith("type=image&") }?.substringAfterLast("&embeddingSpaceKey=", "")?.takeIf(String::isNotBlank)

/** 可核验时固定索引路由；旧适配器无法提供身份时保留原行为且不记录来源。 */
internal suspend fun EmbeddingClient.embedDocumentsForIndex(texts: List<String>): Pair<EmbeddingResult, String?> {
    val client = runCatching { frozenForIndex() }.getOrNull() ?: return embedDocuments(texts) to null
    val key = runCatching { client.vectorSpaceKey() }.getOrNull() ?: return client.embedDocuments(texts) to null
    val generation = client.localModelGeneration()
    val result = client.embedDocuments(texts)
    check(vectorSpaceKey() == key && localModelGeneration() == generation) { "向量模型在索引期间发生变化" }
    return result to key
}

internal fun documentVectorMetadata(
    fileUuid: String,
    chunkIndex: Int,
    documentTitle: String,
    contentHash: String? = null,
): String = buildJsonObject {
    // VectorDao 的跨 SQLite type 查询依赖根字段 type 位于紧凑 JSON 首位。
    put("type", "document")
    put("fileUuid", fileUuid)
    put("chunkIndex", chunkIndex)
    put("docTitle", documentTitle)
    contentHash?.let { put("contentHash", it) }
}.toString()

internal fun vectorMetadataType(metadata: String?): String? =
    vectorMetadataObject(metadata)?.get("type")?.jsonPrimitive?.content

internal fun documentTitleFromVectorMetadata(metadata: String?): String? =
    vectorMetadataObject(metadata)?.get("docTitle")?.jsonPrimitive?.content?.takeIf(String::isNotBlank)

internal fun documentReferenceSource(metadata: String?, fallbackDocumentId: String): String {
    val parsed = vectorMetadataObject(metadata)
    val title = parsed?.get("docTitle")?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
    val legacyFileId = parsed?.get("fileUuid")?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
    return "文档: ${title ?: legacyFileId?.take(8) ?: fallbackDocumentId.take(8)}"
}

private fun vectorMetadataObject(metadata: String?) = try {
    metadata?.let { vectorMetadataJson.parseToJsonElement(it).jsonObject }
} catch (_: Exception) {
    null
}

internal fun documentContentHashFromVectorMetadata(metadata: String?): String? =
    vectorMetadataObject(metadata)?.get("contentHash")?.jsonPrimitive?.content?.takeIf(String::isNotBlank)

internal fun documentChunkIndexFromVectorMetadata(metadata: String?): Int? =
    (vectorMetadataObject(metadata)?.get("chunkIndex") as? JsonPrimitive)?.intOrNull
