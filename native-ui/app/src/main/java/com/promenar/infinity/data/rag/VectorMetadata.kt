package com.promenar.infinity.data.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

private val vectorMetadataJson = Json { ignoreUnknownKeys = true }

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
