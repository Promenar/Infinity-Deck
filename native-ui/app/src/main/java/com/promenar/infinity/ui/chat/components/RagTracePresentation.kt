package com.promenar.infinity.ui.chat.components

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.promenar.infinity.R
import com.promenar.infinity.data.model.GraphBindingMode
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.RagReferenceKind
import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.RagRetrievalTrace
import com.promenar.infinity.ui.common.ScopeResource
import com.promenar.infinity.ui.common.labelRes
import kotlin.math.roundToInt

/**
 * 待本地化的一段文字：资源 ID 加格式参数。[quantity] 非空时 [resId] 是复数资源；
 * 参数本身也可以是 [RagText]（如档位名称），解析时先行本地化。
 */
data class RagText(val resId: Int, val args: List<Any> = emptyList(), val quantity: Int? = null)

private fun plural(@PluralsRes resId: Int, count: Int) = RagText(resId, listOf(count), quantity = count)

/** 检索结果的观感等级：命中、未命中（中性），以及未能检索（需要用户处理）。 */
enum class RagOutcomeTone { HIT, NEUTRAL, ATTENTION }

internal fun RagRetrievalTrace.isReranked(): Boolean =
    rerank == RagRetrievalTrace.RERANK_API || rerank == RagRetrievalTrace.RERANK_LLM

internal fun ragOutcomeTone(trace: RagRetrievalTrace): RagOutcomeTone = when (trace.outcome) {
    RagRetrievalTrace.OUTCOME_HIT -> RagOutcomeTone.HIT
    RagRetrievalTrace.OUTCOME_EMPTY -> RagOutcomeTone.NEUTRAL
    else -> RagOutcomeTone.ATTENTION
}

/** 检索卡上的摘要分段：是否命中、命中几条、重排是否生效。 */
internal fun ragCardSummary(trace: RagRetrievalTrace, referenceCount: Int): List<RagText> = when (trace.outcome) {
    RagRetrievalTrace.OUTCOME_HIT -> buildList {
        val count = referenceCount.takeIf { it > 0 } ?: trace.selectedCount
        add(RagText(R.string.chat_rag_summary_hit, listOf(count)))
        when {
            trace.isReranked() -> add(RagText(R.string.chat_rag_summary_reranked))
            trace.rerank == RagRetrievalTrace.RERANK_FAILED -> add(RagText(R.string.chat_rag_summary_rerank_failed))
        }
    }
    RagRetrievalTrace.OUTCOME_EMPTY -> listOf(RagText(R.string.chat_rag_summary_empty))
    RagRetrievalTrace.OUTCOME_UNAVAILABLE -> listOf(RagText(R.string.chat_rag_summary_unavailable))
    RagRetrievalTrace.OUTCOME_NO_SCOPE -> listOf(RagText(R.string.chat_rag_summary_no_scope))
    else -> listOf(RagText(R.string.chat_rag_summary_failed))
}

/** 检索过程说明中的一行：左侧标签，右侧一至多行取值，每行内为分段。 */
internal data class RagTraceRow(val label: Int, val lines: List<List<RagText>>)

/** 详情页的检索过程：先给结论与补充说明，再按来源、范围、排序、重排、图谱、用时分行列出。 */
internal data class RagTraceSummary(val headline: RagText, val hint: RagText?, val rows: List<RagTraceRow>)

internal fun ragTraceSummary(trace: RagRetrievalTrace): RagTraceSummary {
    val searched = trace.outcome == RagRetrievalTrace.OUTCOME_HIT || trace.outcome == RagRetrievalTrace.OUTCOME_EMPTY
    val headline = when (trace.outcome) {
        RagRetrievalTrace.OUTCOME_HIT -> RagText(R.string.rag_trace_headline_hit, listOf(trace.recallCount.coerceAtLeast(trace.selectedCount), trace.selectedCount))
        RagRetrievalTrace.OUTCOME_EMPTY -> RagText(R.string.rag_trace_headline_empty)
        RagRetrievalTrace.OUTCOME_UNAVAILABLE -> RagText(R.string.chat_rag_summary_unavailable)
        else -> RagText(R.string.chat_rag_summary_failed)
    }
    val hint = when (trace.outcome) {
        RagRetrievalTrace.OUTCOME_HIT -> null
        RagRetrievalTrace.OUTCOME_EMPTY -> RagText(R.string.rag_trace_empty_hint)
        RagRetrievalTrace.OUTCOME_UNAVAILABLE -> RagText(R.string.rag_trace_unavailable_hint)
        RagRetrievalTrace.OUTCOME_NO_SCOPE -> RagText(R.string.rag_trace_no_scope_hint)
        else -> RagText(R.string.rag_trace_failed_hint)
    }
    val rows = buildList {
        if (trace.outcome == RagRetrievalTrace.OUTCOME_HIT) {
            val sources = buildList {
                if (trace.memoryCount > 0) add(RagText(R.string.rag_trace_source_memory, listOf(trace.memoryCount)))
                if (trace.documentCount > 0) add(RagText(R.string.rag_trace_source_documents, listOf(trace.documentCount)))
            }
            if (sources.isNotEmpty()) add(RagTraceRow(R.string.rag_trace_label_sources, listOf(sources)))
        }
        val scopes = buildList {
            trace.memoryScope?.let { scope ->
                add(buildList {
                    add(RagText(R.string.rag_trace_scope_memory))
                    add(RagText(scope.labelRes(ScopeResource.Memory)))
                    trace.memorySessionCount?.let { add(plural(R.plurals.rag_trace_reach_sessions, it)) }
                    // 跨助手档才需要说明助手数；其余档位只涉及本助手。
                    if (scope == ConversationScope.ALL_AGENTS) trace.memoryAgentCount?.let { add(plural(R.plurals.rag_trace_reach_agents, it)) }
                })
            }
            if (trace.graphCollectionId != null) {
                add(buildList {
                    add(RagText(R.string.rag_trace_scope_bound_graph))
                    add(graphTitleText(trace.graphCollectionId, trace.graphTitle))
                    trace.graphBindingMode?.let { mode ->
                        add(RagText(if (mode == GraphBindingMode.READ_ONLY) R.string.ks_graph_mode_read_only else R.string.ks_graph_mode_read_write))
                    }
                    if (trace.graphRestriction != null) add(RagText(R.string.ks_graph_lock_agent))
                })
            } else {
                // 单图谱绑定之前的旧轨迹仍按范围档位显示。
                trace.graphScope?.let { add(listOf(RagText(R.string.rag_trace_scope_graph), RagText(it.labelRes(ScopeResource.Graph)))) }
            }
            trace.documentScope?.let {
                add(buildList {
                    add(RagText(R.string.rag_trace_scope_documents))
                    if (trace.effectiveCollectionIds.isNotEmpty()) {
                        add(plural(R.plurals.perm_docs_value_count, trace.effectiveCollectionIds.size))
                    } else {
                        add(RagText(it.labelRes()))
                    }
                })
            }
        }
        if (scopes.isNotEmpty()) add(RagTraceRow(R.string.rag_trace_label_scope, scopes))
        if (searched) {
            add(RagTraceRow(R.string.rag_trace_label_ranking, listOf(listOf(RagText(
                if (trace.scoring == RagRetrievalTrace.SCORING_HYBRID) R.string.rag_trace_ranking_hybrid else R.string.rag_trace_ranking_vector,
            )))))
            add(RagTraceRow(R.string.rag_trace_label_rerank, listOf(rerankValue(trace))))
        }
        if (trace.jitExtractionCount > 0) {
            add(RagTraceRow(R.string.rag_trace_label_graph, listOf(buildList {
                add(RagText(R.string.rag_trace_jit, listOf(trace.jitExtractionCount)))
                trace.jitModel?.takeIf(String::isNotBlank)?.let { add(RagText(R.string.rag_trace_jit_model, listOf(it.substringAfter("::", it)))) }
            })))
        }
        if (trace.searchTimeMs > 0) add(RagTraceRow(R.string.rag_trace_label_time, listOf(listOf(RagText(R.string.rag_trace_duration, listOf(trace.searchTimeMs))))))
    }
    return RagTraceSummary(headline, hint, rows)
}

/** 绑定图谱的显示名：通用知识用本地化名称，会话图谱标注“会话”。 */
private fun graphTitleText(collectionId: String, title: String?): RagText = when {
    collectionId == "shared" -> RagText(R.string.kg_spaces_shared_collection_title)
    collectionId.startsWith("session:") -> RagText(R.string.rag_trace_graph_session, listOf(title?.takeIf(String::isNotBlank) ?: collectionId.removePrefix("session:")))
    else -> RagText(R.string.rag_trace_graph_name, listOf(title?.takeIf(String::isNotBlank) ?: collectionId))
}

/** 检索卡摘要中的范围段：优先说明对话记忆档位，未检索记忆时说明文档档位。 */
internal fun ragScopeSegment(trace: RagRetrievalTrace?): RagText? {
    val label = trace?.memoryScope?.labelRes(ScopeResource.Memory) ?: trace?.documentScope?.labelRes() ?: return null
    return RagText(R.string.chat_rag_scope_segment, listOf(RagText(label)))
}

/**
 * 检索卡摘要下一行：按来源计数（对话记忆、文档、图谱路径），并标出图谱临时抽取次数。
 * 引用按是否关联文档区分来源，会话摘要计入记忆。没有任何条目，或只有一类引用（数目已在摘要的结果数中）时为空。
 */
internal fun ragSourceBreakdown(references: List<RagReference>, kgPathCount: Int, trace: RagRetrievalTrace?): List<RagText> {
    val documents = references.count { it.documentId != null || it.kind == RagReferenceKind.DOCUMENT }
    val memory = references.size - documents
    val jit = trace?.jitExtractionCount ?: 0
    val parts = buildList {
        if (memory > 0) add(RagText(R.string.chat_rag_breakdown_memory, listOf(memory)))
        if (documents > 0) add(RagText(R.string.chat_rag_breakdown_documents, listOf(documents)))
        if (kgPathCount > 0) add(RagText(R.string.chat_rag_breakdown_graph, listOf(kgPathCount)))
        if (jit > 0) add(RagText(R.string.chat_rag_breakdown_jit, listOf(jit)))
    }
    return if (parts.size == 1 && kgPathCount == 0 && jit == 0) emptyList() else parts
}

private fun rerankValue(trace: RagRetrievalTrace): List<RagText> = when (trace.rerank) {
    RagRetrievalTrace.RERANK_API -> listOf(
        RagText(R.string.rag_trace_rerank_value_api, listOf(trace.rerankModel ?: "—")),
        RagText(R.string.rag_trace_duration, listOf(trace.rerankTimeMs)),
    )
    RagRetrievalTrace.RERANK_LLM -> listOf(
        RagText(R.string.rag_trace_rerank_value_llm, listOf(trace.rerankModel ?: "—")),
        RagText(R.string.rag_trace_duration, listOf(trace.rerankTimeMs)),
    )
    RagRetrievalTrace.RERANK_SKIPPED -> listOf(RagText(R.string.rag_trace_rerank_value_skipped))
    RagRetrievalTrace.RERANK_FAILED -> listOf(RagText(R.string.rag_trace_rerank_value_failed))
    else -> listOf(RagText(R.string.rag_trace_rerank_value_off))
}

/** 单条引用的分数与名次说明：来源类型、对话记录的时间、召回分、重排分，以及重排前后的名次。 */
internal fun ragReferenceParts(reference: RagReference, scoring: String?): List<RagText> = buildList {
    when (reference.kind) {
        RagReferenceKind.MEMORY -> add(RagText(R.string.rag_details_kind_memory))
        RagReferenceKind.DOCUMENT -> add(RagText(R.string.rag_details_kind_document))
        RagReferenceKind.SUMMARY -> add(RagText(R.string.rag_details_kind_summary))
        RagReferenceKind.STATE -> add(RagText(R.string.rag_details_kind_state))
    }
    reference.occurredAt?.let { add(RagText(R.string.rag_details_occurred_at, listOf(it))) }
    if (reference.score > 0f) {
        add(
            RagText(
                if (scoring == RagRetrievalTrace.SCORING_HYBRID) {
                    R.string.rag_details_score_fused
                } else {
                    R.string.rag_details_score_similarity
                },
                listOf(percent(reference.score)),
            ),
        )
    }
    reference.rerankScore?.let { add(RagText(R.string.rag_details_score_rerank_value, listOf(percent(it)))) }
    val change = reference.rankChange
    val recallRank = reference.recallRank
    if (change != null && recallRank != null) {
        add(
            when {
                change > 0 -> RagText(R.string.rag_details_rank_from_up, listOf(recallRank, change))
                change < 0 -> RagText(R.string.rag_details_rank_from_down, listOf(recallRank, -change))
                else -> RagText(R.string.rag_details_rank_unchanged)
            },
        )
    }
}

private fun percent(value: Float): Int = (value.coerceIn(0f, 1f) * 100).roundToInt()
