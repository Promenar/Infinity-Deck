package com.promenar.infinity.data.rag

import com.promenar.infinity.data.local.db.dao.VectorDao
import com.promenar.infinity.data.local.db.entity.VectorEntity
import kotlinx.coroutines.CancellationException

enum class KeywordSearchMode { Fts, LikeFallback }

data class KeywordSearchOutcome(
    val results: List<SearchResult>,
    val mode: KeywordSearchMode,
    val technical: String? = null,
)

class KeywordSearcher(
    private val vectorDao: VectorDao
) {
    data class SearchOptions(
        val sessionId: String? = null,
        val docIds: Set<String>? = null,
        val excludeDocs: Boolean = false,
        val workspaceRootUuid: String? = null,
    )

    suspend fun search(
        query: String,
        limit: Int = 5,
        options: SearchOptions = SearchOptions()
    ): List<SearchResult> = searchWithOutcome(query, limit, options).results

    suspend fun searchWithOutcome(
        query: String,
        limit: Int = 5,
        options: SearchOptions = SearchOptions(),
    ): KeywordSearchOutcome {
        if (query.isBlank()) return KeywordSearchOutcome(emptyList(), KeywordSearchMode.Fts)
        val effectiveQuery = if (query.length > 60) query.substring(0, 60) else query

        return try {
            KeywordSearchOutcome(
                results = ftsSearch(effectiveQuery, limit, options),
                mode = KeywordSearchMode.Fts,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            KeywordSearchOutcome(
                results = fallbackLikeSearch(effectiveQuery, limit, options),
                mode = KeywordSearchMode.LikeFallback,
                technical = failure::class.simpleName?.take(80),
            )
        }
    }

    /** 按检索范围分别检索对话行与文档行；FTS 不可用时在同一范围内退化为子串匹配。 */
    suspend fun searchScoped(
        query: String,
        limit: Int,
        memory: ConversationReach?,
        documents: DocumentReach?,
        expectedDimension: Int? = null,
    ): List<SearchResult> {
        if (query.isBlank() || (memory == null && documents == null)) return emptyList()
        val effectiveQuery = query.take(60)
        val rows = try {
            // 两类各自取上限，避免文档命中挤掉记忆命中。
            buildList {
                memory?.let { addAll(vectorDao.conversationFts(it, effectiveQuery).filter { row -> expectedDimension == null || row.embedding.size.toLong() == expectedDimension.toLong() * 4 }.take(limit)) }
                documents?.let { addAll(vectorDao.documentFts(it, effectiveQuery).filter { row -> expectedDimension == null || row.embedding.size.toLong() == expectedDimension.toLong() * 4 }.take(limit)) }
            }.map { rowToSearchResult(it, 1.0f) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val keywords = effectiveQuery.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
            if (keywords.isEmpty()) return emptyList()
            val candidates = buildList {
                memory?.let { reach -> CONVERSATION_TYPES.forEach { addAll(vectorDao.conversationRows(reach, it)) } }
                documents?.let { addAll(vectorDao.documentRows(it)) }
            }
            candidates.filter { row -> expectedDimension == null || row.embedding.size.toLong() == expectedDimension.toLong() * 4 }.mapNotNull { row ->
                val content = row.content.lowercase()
                val score = keywords.count { content.contains(it) }.toFloat()
                if (score > 0f) rowToSearchResult(row, score) else null
            }.sortedByDescending { it.similarity }.let { ranked ->
                ranked.filter { it.docId == null }.take(limit) + ranked.filter { it.docId != null }.take(limit)
            }
        }
        return rows.distinctBy { it.id }
    }

    private suspend fun ftsSearch(
        query: String,
        limit: Int,
        options: SearchOptions
    ): List<SearchResult> {
        val rows = when {
            options.workspaceRootUuid != null -> vectorDao.searchFtsByWorkspaceRoot(
                query,
                options.workspaceRootUuid,
            )
            options.excludeDocs -> vectorDao.searchFtsExcludeDocs(query)
            options.docIds != null && options.docIds.isNotEmpty() && options.docIds.size < 100 ->
                vectorDao.searchFtsByDocIds(query, options.docIds.toList())
            options.sessionId != null -> vectorDao.searchFtsBySession(query, options.sessionId)
            else -> vectorDao.searchFts(query)
        }

        var candidates = if (options.workspaceRootUuid != null) {
            rankByBm25(rows, query)
        } else {
            rows.map { row -> rowToSearchResult(row, 1.0f) }
        }

        if (options.docIds != null && options.docIds.size >= 100) {
            candidates = candidates.filter { it.docId != null && options.docIds.contains(it.docId) }
        }

        return candidates.take(limit)
    }

    /** FTS4 没有跨 Android SQLite 版本稳定可用的 bm25()，因此只让 FTS 缩小候选集，
     * 再对当前 workspace 的命中候选执行确定性的 BM25 排序。 */
    private fun rankByBm25(rows: List<VectorEntity>, query: String): List<SearchResult> {
        if (rows.isEmpty()) return emptyList()
        val queryTerms = tokenize(query).distinct()
        if (queryTerms.isEmpty()) return rows.map { rowToSearchResult(it, 0f) }
        val tokenized = rows.map { it to tokenize(it.content) }
        val averageLength = tokenized.map { it.second.size }.average().coerceAtLeast(1.0)
        val documentCount = rows.size.toDouble()
        val documentFrequencies = queryTerms.associateWith { term ->
            tokenized.count { (_, candidate) -> term in candidate }.toDouble()
        }
        return tokenized.map { (row, terms) ->
            val score = queryTerms.sumOf { term ->
                val frequency = terms.count { it == term }.toDouble()
                if (frequency == 0.0) return@sumOf 0.0
                val documentFrequency = documentFrequencies.getValue(term)
                val inverseFrequency = kotlin.math.ln(
                    1.0 + (documentCount - documentFrequency + 0.5) / (documentFrequency + 0.5),
                )
                val denominator = frequency + BM25_K1 * (
                    1.0 - BM25_B + BM25_B * terms.size / averageLength
                )
                inverseFrequency * frequency * (BM25_K1 + 1.0) / denominator
            }
            rowToSearchResult(row, score.toFloat())
        }.sortedWith(
            compareByDescending<SearchResult> { it.similarity }
                .thenBy { it.docId.orEmpty() }
                .thenBy { it.id },
        )
    }

    private fun tokenize(value: String): List<String> =
        TOKEN.findAll(value.lowercase()).map { it.value }.toList()

    private suspend fun fallbackLikeSearch(
        query: String,
        limit: Int,
        options: SearchOptions
    ): List<SearchResult> {
        val keywords = query.split(Regex("\\s+")).filter { it.length > 1 }
        if (keywords.isEmpty()) return emptyList()

        val rows = options.workspaceRootUuid?.let { vectorDao.getByWorkspaceRoot(it) }
            ?: vectorDao.getAll()
        var candidates = rows.mapNotNull { row ->
            val contentLower = row.content.lowercase()
            var score = 0f
            for (kw in keywords) {
                if (contentLower.contains(kw.lowercase())) score += 1.0f
            }
            if (score > 0f) rowToSearchResult(row, score) else null
        }

        if (options.sessionId != null) {
            candidates = candidates.filter { it.sessionId == options.sessionId }
        }
        if (options.excludeDocs) {
            candidates = candidates.filter { it.docId == null }
        } else if (options.docIds != null && options.docIds.isNotEmpty()) {
            if (options.docIds.size < 100) {
                candidates = candidates.filter { it.docId != null && options.docIds.contains(it.docId) }
            } else {
                candidates = candidates.filter { it.docId != null && options.docIds.contains(it.docId) }
            }
        }

        return candidates.sortedByDescending { it.similarity }.take(limit)
    }

    private fun rowToSearchResult(row: VectorEntity, similarity: Float): SearchResult {
        return SearchResult(
            id = row.id,
            docId = row.docId,
            sessionId = row.sessionId,
            content = row.content,
            embedding = FloatArray(0),
            metadata = row.metadata,
            startMessageId = row.startMessageId,
            endMessageId = row.endMessageId,
            createdAt = row.createdAt,
            similarity = similarity,
            fileUuid = row.fileUuid,
        )
    }

    private companion object {
        val CONVERSATION_TYPES = listOf("memory", "summary")
        val TOKEN = Regex("[\\p{L}\\p{N}_]+")
        const val BM25_K1 = 1.2
        const val BM25_B = 0.75
    }
}
