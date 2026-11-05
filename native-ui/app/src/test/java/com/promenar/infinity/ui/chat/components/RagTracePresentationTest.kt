package com.promenar.infinity.ui.chat.components

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.DocumentScope
import com.promenar.infinity.data.model.RagMetadata
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.RagReferenceKind
import com.promenar.infinity.data.model.RagRetrievalTrace
import com.promenar.infinity.data.model.json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Test

class RagTracePresentationTest {
    private val hit = RagRetrievalTrace(
        outcome = RagRetrievalTrace.OUTCOME_HIT,
        recallCount = 12,
        selectedCount = 5,
        scoring = RagRetrievalTrace.SCORING_HYBRID,
        rerank = RagRetrievalTrace.RERANK_API,
        rerankModel = "bge-reranker",
        searchTimeMs = 850,
        rerankTimeMs = 320,
        memoryCount = 2,
        documentCount = 3,
    )

    @Test
    fun `卡片摘要区分命中、重排、未命中与未能检索`() {
        assertThat(ragCardSummary(hit, 5)).containsExactly(
            RagText(R.string.chat_rag_summary_hit, listOf(5)),
            RagText(R.string.chat_rag_summary_reranked),
        ).inOrder()
        assertThat(ragCardSummary(hit.copy(rerank = RagRetrievalTrace.RERANK_OFF), 5))
            .containsExactly(RagText(R.string.chat_rag_summary_hit, listOf(5)))
        assertThat(ragCardSummary(hit.copy(rerank = RagRetrievalTrace.RERANK_FAILED), 5).last().resId)
            .isEqualTo(R.string.chat_rag_summary_rerank_failed)
        assertThat(ragCardSummary(hit.copy(outcome = RagRetrievalTrace.OUTCOME_EMPTY), 0).single().resId)
            .isEqualTo(R.string.chat_rag_summary_empty)
        assertThat(ragCardSummary(hit.copy(outcome = RagRetrievalTrace.OUTCOME_UNAVAILABLE), 0).single().resId)
            .isEqualTo(R.string.chat_rag_summary_unavailable)
        assertThat(ragOutcomeTone(hit)).isEqualTo(RagOutcomeTone.HIT)
        assertThat(ragOutcomeTone(hit.copy(outcome = RagRetrievalTrace.OUTCOME_EMPTY))).isEqualTo(RagOutcomeTone.NEUTRAL)
        assertThat(ragOutcomeTone(hit.copy(outcome = RagRetrievalTrace.OUTCOME_FAILED))).isEqualTo(RagOutcomeTone.ATTENTION)
    }

    @Test
    fun `检索过程先给结论，再按来源、排序、重排与用时分行`() {
        val summary = ragTraceSummary(hit)
        assertThat(summary.headline).isEqualTo(RagText(R.string.rag_trace_headline_hit, listOf(12, 5)))
        assertThat(summary.hint).isNull()
        assertThat(summary.rows).containsExactly(
            RagTraceRow(R.string.rag_trace_label_sources, listOf(listOf(
                RagText(R.string.rag_trace_source_memory, listOf(2)),
                RagText(R.string.rag_trace_source_documents, listOf(3)),
            ))),
            RagTraceRow(R.string.rag_trace_label_ranking, listOf(listOf(RagText(R.string.rag_trace_ranking_hybrid)))),
            RagTraceRow(R.string.rag_trace_label_rerank, listOf(listOf(
                RagText(R.string.rag_trace_rerank_value_api, listOf("bge-reranker")),
                RagText(R.string.rag_trace_duration, listOf(320L)),
            ))),
            RagTraceRow(R.string.rag_trace_label_time, listOf(listOf(RagText(R.string.rag_trace_duration, listOf(850L))))),
        ).inOrder()

        // 未命中：结论与原因分开，不列全为零的来源行。
        val empty = ragTraceSummary(hit.copy(outcome = RagRetrievalTrace.OUTCOME_EMPTY, recallCount = 0, selectedCount = 0))
        assertThat(empty.headline.resId).isEqualTo(R.string.rag_trace_headline_empty)
        assertThat(empty.hint?.resId).isEqualTo(R.string.rag_trace_empty_hint)
        assertThat(empty.rows.map { it.label }).doesNotContain(R.string.rag_trace_label_sources)
        // 未能检索：只给结论与处理提示，不列排序与重排。
        val unavailable = ragTraceSummary(hit.copy(outcome = RagRetrievalTrace.OUTCOME_UNAVAILABLE))
        assertThat(unavailable.hint?.resId).isEqualTo(R.string.rag_trace_unavailable_hint)
        assertThat(unavailable.rows.map { it.label }).containsNoneOf(R.string.rag_trace_label_ranking, R.string.rag_trace_label_rerank)
    }

    @Test
    fun `单条引用展示来源类型、召回分、重排分与重排前名次`() {
        val reference = RagReference(
            id = "r",
            score = 0.6f,
            rerankScore = 0.91f,
            rankChange = 2,
            recallRank = 3,
            kind = RagReferenceKind.DOCUMENT,
        )

        assertThat(ragReferenceParts(reference, RagRetrievalTrace.SCORING_VECTOR)).containsExactly(
            RagText(R.string.rag_details_kind_document),
            RagText(R.string.rag_details_score_similarity, listOf(60)),
            RagText(R.string.rag_details_score_rerank_value, listOf(91)),
            RagText(R.string.rag_details_rank_from_up, listOf(3, 2)),
        ).inOrder()
        assertThat(ragReferenceParts(reference.copy(rankChange = 0), RagRetrievalTrace.SCORING_HYBRID))
            .containsAtLeast(
                RagText(R.string.rag_details_score_fused, listOf(60)),
                RagText(R.string.rag_details_rank_unchanged),
            )
    }

    @Test
    fun `对话状态与带时间的记录在类型后标出时间`() {
        val state = RagReference(id = "st", content = "用户 · 编辑器: VS Code", kind = RagReferenceKind.STATE, occurredAt = 1_700_000_000_000L)

        assertThat(ragReferenceParts(state, RagRetrievalTrace.SCORING_VECTOR)).containsExactly(
            RagText(R.string.rag_details_kind_state),
            RagText(R.string.rag_details_occurred_at, listOf(1_700_000_000_000L)),
        ).inOrder()
    }

    @Test
    fun `详情说明列出实际执行的范围、覆盖的会话与助手，以及临时抽取`() {
        val scoped = hit.copy(
            memoryScope = ConversationScope.ALL_AGENTS,
            graphScope = ConversationScope.AGENT,
            documentScope = DocumentScope.KNOWLEDGE_BASE,
            memorySessionCount = 12,
            memoryAgentCount = 3,
            jitExtractionCount = 1,
            jitModel = "provider::glm-flash",
        )
        val rows = ragTraceSummary(scoped).rows
        assertThat(rows.first { it.label == R.string.rag_trace_label_scope }.lines).containsExactly(
            listOf(
                RagText(R.string.rag_trace_scope_memory),
                RagText(R.string.memory_scope_all_agents),
                RagText(R.plurals.rag_trace_reach_sessions, listOf(12), quantity = 12),
                RagText(R.plurals.rag_trace_reach_agents, listOf(3), quantity = 3),
            ),
            listOf(RagText(R.string.rag_trace_scope_graph), RagText(R.string.graph_scope_agent)),
            listOf(RagText(R.string.rag_trace_scope_documents), RagText(R.string.settings_ia_doc_scope_kb)),
        ).inOrder()
        assertThat(rows.first { it.label == R.string.rag_trace_label_graph }.lines.single())
            .containsExactly(RagText(R.string.rag_trace_jit, listOf(1)), RagText(R.string.rag_trace_jit_model, listOf("glm-flash"))).inOrder()
        // 本助手档只涉及一个助手，不重复说明助手数；未参与的资源与未调用的临时抽取不出现。
        val agentOnly = ragTraceSummary(hit.copy(memoryScope = ConversationScope.AGENT, memorySessionCount = 4, memoryAgentCount = 1)).rows
        assertThat(agentOnly.first { it.label == R.string.rag_trace_label_scope }.lines).containsExactly(listOf(
            RagText(R.string.rag_trace_scope_memory),
            RagText(R.string.memory_scope_agent),
            RagText(R.plurals.rag_trace_reach_sessions, listOf(4), quantity = 4),
        ))
        assertThat(agentOnly.map { it.label }).doesNotContain(R.string.rag_trace_label_graph)
    }

    @Test
    fun `检索卡摘要显示范围档位，下一行按来源计数并标出临时抽取`() {
        assertThat(ragScopeSegment(hit.copy(memoryScope = ConversationScope.SESSION, documentScope = DocumentScope.WORKSPACE)))
            .isEqualTo(RagText(R.string.chat_rag_scope_segment, listOf(RagText(R.string.memory_scope_session))))
        assertThat(ragScopeSegment(hit.copy(documentScope = DocumentScope.ALL_INDEXED)))
            .isEqualTo(RagText(R.string.chat_rag_scope_segment, listOf(RagText(R.string.document_scope_all))))
        assertThat(ragScopeSegment(hit)).isNull()

        val references = listOf(
            RagReference(id = "m1", kind = RagReferenceKind.MEMORY),
            RagReference(id = "s1", kind = RagReferenceKind.SUMMARY),
            RagReference(id = "d1", documentId = "doc", kind = RagReferenceKind.DOCUMENT),
            RagReference(id = "legacy-doc", documentId = "doc-2"),
        )
        assertThat(ragSourceBreakdown(references, kgPathCount = 3, trace = hit.copy(jitExtractionCount = 1))).containsExactly(
            RagText(R.string.chat_rag_breakdown_memory, listOf(2)),
            RagText(R.string.chat_rag_breakdown_documents, listOf(2)),
            RagText(R.string.chat_rag_breakdown_graph, listOf(3)),
            RagText(R.string.chat_rag_breakdown_jit, listOf(1)),
        ).inOrder()
        assertThat(ragSourceBreakdown(emptyList(), kgPathCount = 0, trace = hit)).isEmpty()
        // 只有一类引用时，数目已在摘要的结果数里，不再单独占一行。
        assertThat(ragSourceBreakdown(references.take(2), kgPathCount = 0, trace = hit)).isEmpty()
        assertThat(ragSourceBreakdown(references.take(2), kgPathCount = 1, trace = hit)).containsExactly(
            RagText(R.string.chat_rag_breakdown_memory, listOf(2)),
            RagText(R.string.chat_rag_breakdown_graph, listOf(1)),
        ).inOrder()
    }

    @Test
    fun `旧版检索元数据缺少轨迹字段时仍可读取`() {
        val legacy = """{"chunkCount":2,"totalTokens":10,"retrievalTimeMs":0}"""

        val decoded = json.decodeFromString<RagMetadata>(legacy)

        assertThat(decoded.trace).isNull()
        val roundTrip = json.decodeFromString<RagMetadata>(json.encodeToString(decoded.copy(trace = hit)))
        assertThat(roundTrip.trace).isEqualTo(hit)
    }

    @Test
    fun `绑定图谱轨迹显示图谱名与读写模式且合集计数取代旧文档档位`() {
        val bound = hit.copy(
            graphCollectionId = "work:a",
            graphTitle = "长夜行",
            graphBindingMode = com.promenar.infinity.data.model.GraphBindingMode.READ_ONLY,
            graphScope = ConversationScope.AGENT,
            documentScope = DocumentScope.KNOWLEDGE_BASE,
            effectiveCollectionIds = listOf("work:a", "shared"),
        )
        val lines = ragTraceSummary(bound).rows.first { it.label == R.string.rag_trace_label_scope }.lines
        assertThat(lines).contains(listOf(
            RagText(R.string.rag_trace_scope_bound_graph),
            RagText(R.string.rag_trace_graph_name, listOf("长夜行")),
            RagText(R.string.ks_graph_mode_read_only),
        ))
        assertThat(lines).contains(listOf(
            RagText(R.string.rag_trace_scope_documents),
            RagText(R.plurals.perm_docs_value_count, listOf(2), quantity = 2),
        ))
        assertThat(lines.flatten()).doesNotContain(RagText(R.string.rag_trace_scope_graph))

        val restricted = ragTraceSummary(bound.copy(graphCollectionId = "session:s", graphTitle = "周报", graphRestriction = "受智能体资料限制"))
            .rows.first { it.label == R.string.rag_trace_label_scope }.lines.first { it.first() == RagText(R.string.rag_trace_scope_bound_graph) }
        assertThat(restricted).containsExactly(
            RagText(R.string.rag_trace_scope_bound_graph),
            RagText(R.string.rag_trace_graph_session, listOf("周报")),
            RagText(R.string.ks_graph_mode_read_only),
            RagText(R.string.ks_graph_lock_agent),
        ).inOrder()
    }
}
