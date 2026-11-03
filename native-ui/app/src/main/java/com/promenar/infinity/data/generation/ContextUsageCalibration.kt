package com.promenar.infinity.data.generation

/** 最大余数法按本地叶子占比分配校准总量；保留叶子 ID，不创建差额行。 */
object ContextUsageCalibration {
    fun scale(local: ContextUsageEstimate, total: Int): ContextUsageEstimate {
        val target = total.coerceAtLeast(0)
        val leaves = local.categories.flatMap { it.children }
        val denominator = leaves.sumOf { it.tokens.toLong() }
        if (leaves.isEmpty() || target == local.totalTokens) return local
        val scaled = leaves.map { if (denominator == 0L) 0 else (it.tokens.toLong() * target / denominator).toInt() }.toMutableList()
        if (denominator == 0L) scaled[leaves.indexOfFirst { it.id == "assistant_prompt" }.coerceAtLeast(0)] = target
        val order = leaves.indices.sortedByDescending { if (denominator == 0L) 0L else leaves[it].tokens.toLong() * target % denominator }
        val remainder = target.toLong() - scaled.sumOf(Int::toLong)
        repeat(remainder.toInt()) { scaled[order[it]]++ }
        var index = 0
        val groups = local.categories.map { group ->
            val children = group.children.map { it.copy(tokens = scaled[index++]) }
            group.copy(tokens = children.sumOf { it.tokens }, children = children)
        }
        val summary = groups[0].children.single { it.id == "history_summary" }.tokens
        val conversation = groups[2].tokens
        val rag = if (local.categories[2].tokens == 0) 0 else
            (local.ragTokens.toLong() * conversation / local.categories[2].tokens).toInt().coerceAtMost(conversation)
        return local.copy(
            systemTokens = groups[0].tokens - summary + groups[1].tokens,
            summaryTokens = summary, conversationTokens = conversation - rag, ragTokens = rag, categories = groups,
        )
    }
}
