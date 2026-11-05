package com.promenar.infinity.data.rag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@Serializable
internal data class GraphCheckpointIdentity(
    val contentHash: String,
    val textHash: String,
    val modelId: String?,
    val promptHash: String,
    val chunkSize: Int,
    val chunkOverlap: Int,
    val validationVersion: Int = 1,
)

@Serializable
internal data class GraphChunkCheckpoint(
    val identity: GraphCheckpointIdentity,
    val chunkHash: String,
    val result: ExtractionResult? = null,
    val splitPaths: Set<String> = emptySet(),
    val parts: Map<String, ExtractionResult> = emptyMap(),
)

@Serializable
private data class GraphBudgetCheckpoint(val contentHash: String, val state: GraphExtractionBudgetState)

/** 路径不接受原始文件名；正文和配置身份必须同时匹配才能恢复。 */
internal class GraphExtractionCheckpoints(private val root: File, private val budgetRoot: File = root) {
    fun directory(docId: String): File = File(root, kgSha256(docId))

    fun loadCheckpoint(docId: String, identity: GraphCheckpointIdentity, index: Int, chunk: String): GraphChunkCheckpoint? = try {
        val file = chunkFile(docId, identity, index)
        if (!file.isFile) null else {
            val saved = Json.decodeFromString<GraphChunkCheckpoint>(file.readText())
            if (saved.identity == identity && saved.chunkHash == kgSha256(chunk) &&
                (saved.result == null || saved.result.isSuccess) && saved.parts.values.all { it.isSuccess }) saved
            else { file.delete(); null }
        }
    } catch (_: Exception) { null }

    fun load(docId: String, identity: GraphCheckpointIdentity, index: Int, chunk: String): ExtractionResult? =
        loadCheckpoint(docId, identity, index, chunk)?.result

    fun prepare(docId: String, identity: GraphCheckpointIdentity) {
        legacyDirectory(docId)?.deleteRecursively()
        val dir = directory(docId)
        val current = identityKey(identity)
        // 配置变化只让分块候选失效，同一文档内容的已发生费用不能返还。
        dir.listFiles()?.filter { it.isDirectory && it.name != current }?.forEach { it.deleteRecursively() }
    }

    fun save(docId: String, identity: GraphCheckpointIdentity, index: Int, chunk: String, result: ExtractionResult) {
        val file = chunkFile(docId, identity, index)
        writeAtomically(file, Json.encodeToString(GraphChunkCheckpoint.serializer(), GraphChunkCheckpoint(identity, kgSha256(chunk), result)))
    }

    fun saveCheckpoint(docId: String, identity: GraphCheckpointIdentity, index: Int, checkpoint: GraphChunkCheckpoint) {
        writeAtomically(chunkFile(docId, identity, index), Json.encodeToString(GraphChunkCheckpoint.serializer(), checkpoint))
    }

    fun loadBudget(docId: String, identity: GraphCheckpointIdentity): GraphExtractionBudgetState? {
        val file = budgetFile(docId, identity)
        if (!file.isFile) return null
        return try {
            val saved = Json.decodeFromString<GraphBudgetCheckpoint>(file.readText())
            check(saved.contentHash == identity.contentHash)
            saved.state.also { check(it.calls >= 0 && it.outputTokens >= 0 && it.elapsedMs >= 0) }
        } catch (_: Exception) {
            throw IllegalStateException("知识图谱预算账本损坏，无法安全恢复")
        }
    }

    fun saveBudget(docId: String, identity: GraphCheckpointIdentity, state: GraphExtractionBudgetState) {
        writeAtomically(budgetFile(docId, identity), Json.encodeToString(GraphBudgetCheckpoint.serializer(), GraphBudgetCheckpoint(identity.contentHash, state)))
    }

    private fun writeAtomically(file: File, content: String) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建图谱断点目录" }
        val temp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.writeText(content)
            check(temp.renameTo(file)) { "无法写入图谱断点" }
        } finally { temp.delete() }
    }

    fun clear(docId: String) {
        directory(docId).deleteRecursively()
        budgetDirectory(docId).deleteRecursively()
        legacyDirectory(docId)?.deleteRecursively()
    }

    /** 成功提交只释放该候选身份与文档版本，不能删除新版本的账本。 */
    fun clear(docId: String, identity: GraphCheckpointIdentity) {
        File(directory(docId), identityKey(identity)).deleteRecursively()
        val budget = budgetFile(docId, identity)
        check(!budget.exists() || budget.delete()) { "无法清理已提交的图谱预算账本" }
        listOf(directory(docId), budgetDirectory(docId)).distinct().forEach { dir ->
            if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) dir.delete()
        }
    }

    private fun legacyDirectory(docId: String): File? = File(root, docId).canonicalFile
        .takeIf { it.parentFile == root.canonicalFile && it.name != kgSha256(docId) }

    private fun identityKey(identity: GraphCheckpointIdentity) = kgSha256(Json.encodeToString(GraphCheckpointIdentity.serializer(), identity))
    private fun chunkFile(docId: String, identity: GraphCheckpointIdentity, index: Int) =
        File(File(directory(docId), identityKey(identity)), "chunk_$index.json")
    private fun budgetFile(docId: String, identity: GraphCheckpointIdentity) =
        File(budgetDirectory(docId), "budget_${kgSha256(identity.contentHash)}.json")
    private fun budgetDirectory(docId: String) = File(budgetRoot, kgSha256(docId))
}

internal fun kgSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
