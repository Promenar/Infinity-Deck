package com.promenar.infinity.data.knowledge

import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.Session
import kotlinx.serialization.Serializable

data class KnowledgeBaseSummary(
    val rootUuid: String,
    val title: String,
    val documentCount: Int,
    val isDefault: Boolean = false,
)

data class KnowledgeCatalogGroup(
    val rootUuid: String?,
    val title: String,
    val documentCount: Int,
    val knowledgeBase: Boolean = false,
    val mounted: Boolean = false,
)

data class KnowledgeCatalogDocument(
    val documentId: String,
    val title: String,
    val updatedAt: Long,
)

data class KnowledgeCatalogHistory(
    val scope: ConversationScope,
    val sessionCount: Int,
    val indexedSessionCount: Int,
)

data class KnowledgeCatalogSnapshot(
    val groups: List<KnowledgeCatalogGroup> = emptyList(),
    val documents: List<KnowledgeCatalogDocument> = emptyList(),
    val history: KnowledgeCatalogHistory? = null,
) {
    val hasIndexedMaterial: Boolean
        get() = groups.any { it.documentCount > 0 } || (history?.indexedSessionCount ?: 0) > 0
}

@Serializable
enum class KnowledgeDocumentLocationKind {
    WORKSPACE, KNOWLEDGE_BASE_MOUNTED, KNOWLEDGE_BASE_UNMOUNTED, SNIPPET_ONLY,
}

@Serializable
data class KnowledgeDocumentLocation(
    val documentId: String,
    val title: String,
    val kind: KnowledgeDocumentLocationKind,
    val rootUuid: String? = null,
    val path: String? = null,
    val knowledgeBaseTitle: String? = null,
)

interface KnowledgeCatalogSource {
    suspend fun knowledgeBases(session: Session, options: RagOptions): List<KnowledgeBaseSummary>
    suspend fun snapshot(session: Session, options: RagOptions): KnowledgeCatalogSnapshot

    /** 探测当前检索范围内是否有有效索引。 */
    suspend fun hasIndexedMaterialAsync(session: Session, options: RagOptions): Boolean

    suspend fun documentLocation(sessionId: String, reference: RagReference): KnowledgeDocumentLocation?
}

/** 目录只描述可检索材料；标题数量与字符预算在全部来源之间共享。 */
fun renderKnowledgeCatalog(snapshot: KnowledgeCatalogSnapshot): String {
    if (!snapshot.hasIndexedMaterial) return ""
    val total = snapshot.groups.sumOf { it.documentCount.coerceAtLeast(0) }
    val history = snapshot.history?.let {
        val scope = when (it.scope) {
            ConversationScope.SESSION -> "本会话"
            ConversationScope.AGENT -> "本智能体"
            ConversationScope.ALL_AGENTS -> "全部可见智能体"
        }
        "历史对话：$scope，${it.sessionCount} 个会话"
    }
    val header = listOfNotNull("可检索资料：$total 个已索引文档", history).joinToString("\n")
    val documents = snapshot.documents.distinctBy { it.documentId }
        .sortedWith(compareByDescending<KnowledgeCatalogDocument> { it.updatedAt }.thenBy { it.documentId })
    val lines = mutableListOf(header)
    fun append(line: String, reserve: Int = 0): Boolean {
        if (lines.sumOf { it.length } + lines.size + line.length + reserve > CATALOG_MAX_CHARACTERS) return false
        lines += line
        return true
    }
    var displayedGroups = 0
    snapshot.groups.forEach { group ->
        val state = if (group.knowledgeBase) if (group.mounted) "，已挂载" else "，未挂载" else ""
        if (append("${if (group.knowledgeBase) "知识库" else "工作区"}《${catalogText(group.title)}》：${group.documentCount} 个文档$state", 60)) {
            displayedGroups++
        }
    }
    if (displayedGroups < snapshot.groups.size) append("另有 ${snapshot.groups.size - displayedGroups} 个资料来源", 40)
    var displayedTitles = 0
    for (document in documents.take(CATALOG_MAX_TITLES)) {
        if (!append("文档《${catalogText(document.title)}》", 40)) break
        displayedTitles++
    }
    val omitted = (total - displayedTitles).coerceAtLeast(0)
    if (omitted > 0) append("另有 $omitted 个已索引文档未列出标题")
    return lines.joinToString("\n")
}

private fun catalogText(value: String): String = value.replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ").take(100)
private const val CATALOG_MAX_CHARACTERS = 1500
private const val CATALOG_MAX_TITLES = 30
