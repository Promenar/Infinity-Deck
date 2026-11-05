package com.promenar.infinity.data.rag

import java.util.Locale

data class KgResolutionNode(
    val id: String,
    val collectionId: String,
    val name: String,
    val type: String,
    val description: String? = null,
    val aliases: List<String> = emptyList(),
    val quotes: List<String> = emptyList(),
)
data class KgResolutionPair(val first: KgResolutionNode, val second: KgResolutionNode)
data class KgResolutionEstimate(val candidateCount: Int, val calls: Int, val outputTokenLimit: Long)

/** 实体候选与只读归一规则，模型仅由显式运行入口调用。 */
object KgEntityResolution {
    private fun normalized(value: String) = value.trim().lowercase(Locale.ROOT)

    fun candidates(nodes: List<KgResolutionNode>): List<KgResolutionPair> {
        val sorted = nodes.sortedBy { it.id }
        return buildList {
            sorted.forEachIndexed { index, first ->
                sorted.drop(index + 1).forEach { second ->
                    if (first.collectionId != second.collectionId || normalized(first.type) != normalized(second.type)) return@forEach
                    val a = normalized(first.name)
                    val b = normalized(second.name)
                    val aliasesA = first.aliases.map(::normalized).filter { it.isNotEmpty() }.toSet()
                    val aliasesB = second.aliases.map(::normalized).filter { it.isNotEmpty() }.toSet()
                    val overlap = a.length >= 2 && b.length >= 2 &&
                        (a.contains(b) || b.contains(a) || (0..a.length - 2).any { b.contains(a.substring(it, it + 2)) })
                    if (overlap || aliasesA.intersect(aliasesB).isNotEmpty() || a in aliasesB || b in aliasesA) add(KgResolutionPair(first, second))
                }
            }
        }
    }

    fun estimate(candidateCount: Int, requestMaxOutputTokens: Int): KgResolutionEstimate {
        require(candidateCount >= 0 && requestMaxOutputTokens > 0)
        val calls = ((candidateCount.toLong() + 49) / 50).toInt()
        return KgResolutionEstimate(candidateCount, calls, calls.toLong() * requestMaxOutputTokens)
    }

    fun canonicalIds(nodes: List<KgResolutionNode>, links: List<Pair<String, String>>,
                     excludedPairs: Set<Pair<String, String>> = emptySet()): Map<String, String> {
        val byId = nodes.associateBy { it.id }
        val parents = byId.keys.associateWith { it }.toMutableMap()
        fun root(id: String): String {
            var current = id
            while (parents.getValue(current) != current) current = parents.getValue(current)
            return current
        }
        links.forEach { (a, b) ->
            val left = byId[a]
            val right = byId[b]
            if (left != null && right != null && left.collectionId == right.collectionId && normalized(left.type) == normalized(right.type)) {
                val first = root(a)
                val second = root(b)
                val conflicts = excludedPairs.any { (x, y) ->
                    x in parents && y in parents &&
                        ((root(x) == first && root(y) == second) || (root(x) == second && root(y) == first))
                }
                if (first != second && !conflicts) parents[maxOf(first, second)] = minOf(first, second)
            }
        }
        return parents.keys.associateWith(::root)
    }
}
