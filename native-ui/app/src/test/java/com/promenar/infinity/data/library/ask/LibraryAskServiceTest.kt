package com.promenar.infinity.data.library.ask

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.ApproximateTokenEstimator
import com.promenar.infinity.data.rag.DocumentReach
import com.promenar.infinity.domain.library.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LibraryAskServiceTest {
    @Test fun settingsSelectTheThreeModes() {
        assertThat(determineAskMode("", false)).isEqualTo(AskMode.KEYWORD_ONLY)
        assertThat(determineAskMode("quick", false)).isEqualTo(AskMode.KEYWORD_ONLY)
        assertThat(determineAskMode("", true)).isEqualTo(AskMode.NO_QUICK_MODEL)
        assertThat(determineAskMode("quick", true)).isEqualTo(AskMode.FULL)
    }

    @Test fun documentScopesDoNotBroadenAnEmptyLibrary() {
        assertThat(documentReach(AskScope.AllIndexed, "library")).isEqualTo(DocumentReach.All)
        assertThat(documentReach(AskScope.Library, "library")).isEqualTo(DocumentReach.Scoped(setOf("library"), emptySet()))
        assertThat(documentReach(AskScope.Library, null)).isEqualTo(DocumentReach.Scoped(emptySet(), emptySet()))
        assertThat(documentReach(AskScope.Workspace("workspace", "工作区"), "library"))
            .isEqualTo(DocumentReach.Scoped(setOf("workspace"), emptySet()))
    }
    private fun doc(id: String, document: String = id, similarity: Float? = null) = AskHit(
        key = id, kind = AskHitKind.DOCUMENT, title = document, location = "存储空间",
        snippet = "相关资料", highlightTerms = listOf("资料"),
        match = if (similarity == null) AskMatch.KEYWORD else AskMatch.SEMANTIC, similarity = similarity,
        documentId = document, chunkIndex = 0, chunkContent = "相关资料",
    )

    private class FakeBackend(var mode: AskMode, val hits: List<AskHit>) : LibraryAskBackend {
        var retrievals = 0
        var answers = 0
        var recorded: List<Int> = emptyList()
        var parts = listOf("答案 [", "1] [9] [1]")
        var response: Flow<String>? = null
        override fun observeMode() = flowOf(mode)
        override fun settings() = AskSettings(mode, "quick")
        override suspend fun keywords(query: String, scope: AskScope, limit: Int) = hits
        override suspend fun retrieve(query: String, scope: AskScope, keywords: List<AskHit>): List<AskHit> {
            retrievals++
            return hits
        }
        override fun answer(modelId: String, prompt: AskPrompt): Flow<String> {
            answers++
            return response ?: parts.asFlow()
        }
        override fun modelLabel(modelId: String) = "快速模型"
        override suspend fun recordAnswer(query: String, hits: List<AskHit>, indexes: List<Int>) { recorded = indexes }
    }

    @Test fun keywordModeAvoidsEmbeddingAndModel() = runBlocking {
        val backend = FakeBackend(AskMode.KEYWORD_ONLY, listOf(doc("a")))
        val events = DefaultLibraryAskService(backend).ask("资料", AskScope.AllIndexed).toList()
        assertThat(events.first()).isEqualTo(AskEvent.Retrieving)
        assertThat((events[1] as AskEvent.Hits).mode).isEqualTo(AskMode.KEYWORD_ONLY)
        assertThat(events.filterIsInstance<AskEvent.AnswerDelta>()).isEmpty()
        assertThat(backend.retrievals).isEqualTo(0)
        assertThat(backend.answers).isEqualTo(0)
    }

    @Test fun noQuickModelRetrievesWithoutAnswer() = runBlocking {
        val backend = FakeBackend(AskMode.NO_QUICK_MODEL, listOf(doc("a")))
        val events = DefaultLibraryAskService(backend).ask("资料", AskScope.AllIndexed).toList()
        assertThat(events.filterIsInstance<AskEvent.Hits>().single().mode).isEqualTo(AskMode.NO_QUICK_MODEL)
        assertThat(events.filterIsInstance<AskEvent.AnswerDelta>()).isEmpty()
        assertThat(backend.retrievals).isEqualTo(1)
        assertThat(backend.answers).isEqualTo(0)
    }

    @Test fun fullModeSanitizesAndRecordsActualCitations() = runBlocking {
        val backend = FakeBackend(AskMode.FULL, listOf(doc("a")))
        val events = DefaultLibraryAskService(backend).ask("资料", AskScope.Library).toList()
        val done = events.filterIsInstance<AskEvent.AnswerDone>().single()
        assertThat(done.text).isEqualTo("答案 [1]  [1]")
        assertThat(events.filterIsInstance<AskEvent.AnswerDelta>().joinToString("") { it.text }).isEqualTo(done.text)
        assertThat(done.citedHitIndexes).containsExactly(0)
        assertThat(done.modelLabel).isEqualTo("快速模型")
        assertThat(backend.recorded).containsExactly(0)
        assertThat(backend.retrievals).isEqualTo(1)
        assertThat(backend.answers).isEqualTo(1)
    }

    @Test fun noAnswerMarkerCanCrossStreamChunks() = runBlocking {
        val backend = FakeBackend(AskMode.FULL, listOf(doc("a"))).apply {
            parts = listOf("[[NO_", "ANSWER]]资料不足")
        }
        val done = DefaultLibraryAskService(backend).ask("问题", AskScope.AllIndexed).toList()
            .filterIsInstance<AskEvent.AnswerDone>().single()
        assertThat(done.noAnswer).isTrue()
        assertThat(done.text).isEqualTo("资料不足")
        assertThat(done.citedHitIndexes).isEmpty()
    }

    @Test fun unnumberedAnswerHasNoCitations() {
        val parser = AnswerParser(setOf(0, 1))
        parser.append("没有编号")
        parser.finish()
        assertThat(parser.citedIndexes).isEmpty()
        assertThat(parser.noAnswer).isFalse()
    }

    @Test fun modelFailureKeepsPreviouslyEmittedHits() = runBlocking {
        val backend = FakeBackend(AskMode.FULL, listOf(doc("a")))
        backend.response = flow { error("模拟模型失败") }
        val events = DefaultLibraryAskService(backend).ask("问题", AskScope.AllIndexed).toList()
        assertThat(events.filterIsInstance<AskEvent.Hits>().single().hits).hasSize(1)
        assertThat(events.last()).isInstanceOf(AskEvent.Failed::class.java)
        assertThat(events.filterIsInstance<AskEvent.AnswerDone>()).isEmpty()
    }

    @Test fun cancellationStopsTheModelFlow() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val backend = FakeBackend(AskMode.FULL, listOf(doc("a")))
        backend.response = flow {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally { stopped.complete(Unit) }
        }
        val job = launch { DefaultLibraryAskService(backend).ask("问题", AskScope.AllIndexed).collect {} }
        started.await()
        job.cancelAndJoin()
        stopped.await()
        assertThat(backend.recorded).isEmpty()
    }

    @Test fun citationOrderFollowsFirstAppearance() {
        val parser = AnswerParser(setOf(0, 1))
        parser.append("[2] [1] [2] [0] [999]")
        parser.finish()
        assertThat(parser.text).isEqualTo("[2] [1] [2]  ")
        assertThat(parser.citedIndexes).containsExactly(1, 0).inOrder()
    }

    @Test fun groupedCitationsSplitIntoSingleNumbers() {
        val parser = AnswerParser(setOf(0, 1, 2))
        parser.append("甲 [2, 3]，乙 [1，9]、丙 [3、1] 丁 [a, 1] 戊 [2 ]")
        parser.finish()
        assertThat(parser.text).isEqualTo("甲 [2][3]，乙 [1]、丙 [3][1] 丁 [a, 1] 戊 [2]")
        assertThat(parser.citedIndexes).containsExactly(1, 2, 0).inOrder()
    }

    @Test fun groupedCitationSplitAcrossChunksWaitsForClosingBracket() {
        val parser = AnswerParser(setOf(0, 1))
        val out = listOf("结论 [1", ", ", "2] 完").joinToString("|") { parser.append(it) }
        parser.finish()
        assertThat(out).isEqualTo("结论 ||[1][2] 完")
        assertThat(parser.citedIndexes).containsExactly(0, 1).inOrder()
    }

    @Test fun unclosedBracketAtEndStaysLiteral() {
        val parser = AnswerParser(setOf(0))
        parser.append("数组 [1, 2")
        parser.finish()
        assertThat(parser.text).isEqualTo("数组 [1, 2")
        assertThat(parser.citedIndexes).isEmpty()
    }

    @Test fun fusionRetainsBothSimilarityAndCapsDocuments() {
        val shared = doc("shared", "d", 0.82f)
        val otherChunks = (1..4).map { doc("d$it", "d", 0.7f) }
        val memory = AskHit("memory:m", AskHitKind.MEMORY, "记忆", "", "记忆", emptyList(), AskMatch.SEMANTIC, 0.8f, memoryId = "m")
        val entity = AskHit("entity:e", AskHitKind.ENTITY, "实体", "", "实体", emptyList(), AskMatch.KEYWORD, null, entityId = "e")
        val more = (1..20).map { doc("other$it", "other$it", 0.6f) }
        val hits = mergeAskHits(listOf(shared, memory) + otherChunks + more, listOf(shared.copy(similarity = null), entity)) { vector, keyword ->
            listOf(vector.first(), vector[1], keyword.last()) + vector.drop(2) + keyword
        }
        assertThat(hits).hasSize(12)
        assertThat(hits.count { it.documentId == "d" }).isEqualTo(2)
        assertThat(hits.first().match).isEqualTo(AskMatch.BOTH)
        assertThat(hits.first().similarity).isEqualTo(0.82f)
        assertThat(hits.map { it.kind }).containsAtLeast(AskHitKind.MEMORY, AskHitKind.ENTITY)
    }

    @Test fun thirdKeywordChunkCanBecomeBestCombinedHit() {
        val first = doc("first", "same", 0.4f)
        val second = doc("second", "same", 0.5f)
        val third = doc("third", "same", 0.9f)
        val hits = mergeAskHits(listOf(third), listOf(first, second, third)) { vector, keyword ->
            listOf(vector.first()) + keyword
        }
        assertThat(hits.first().key).isEqualTo("third")
        assertThat(hits.first().match).isEqualTo(AskMatch.BOTH)
        assertThat(hits.first().similarity).isEqualTo(0.9f)
        assertThat(hits.count { it.documentId == "same" }).isEqualTo(2)
    }

    @Test fun keywordSourcesRemainVisibleWhenOneDocumentHasManyChunks() {
        val chunks = (1..20).map { doc("chunk-$it", "same") }
        val file = doc("file:other", "other")
        val memory = AskHit("memory:m", AskHitKind.MEMORY, "记忆", "", "相关记忆", emptyList(), AskMatch.KEYWORD, null, memoryId = "m")
        val hits = limitAskHits(interleaveKeywordCandidates(chunks, listOf(file), listOf(memory)), 20)
        assertThat(hits.count { it.documentId == "same" }).isEqualTo(2)
        assertThat(hits.map { it.key }).containsAtLeast("file:other", "memory:m")
    }

    @Test fun promptUsesAtMostEightSourcesAndBudget() {
        val hits = (1..12).map { doc("d$it").copy(chunkContent = "长".repeat(1600)) }
        val prompt = buildAskPrompt("问题", hits, 3000)
        assertThat(prompt.allowedIndexes.size).isAtMost(8)
        assertThat(ApproximateTokenEstimator.estimate(prompt.system) + ApproximateTokenEstimator.estimate(prompt.user)).isAtMost(3000)
        assertThat(prompt.user).doesNotContain("[9]")
    }
}
