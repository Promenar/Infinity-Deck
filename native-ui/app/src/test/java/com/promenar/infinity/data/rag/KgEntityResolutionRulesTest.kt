package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class KgEntityResolutionRulesTest {
    @Test fun `召回限同合集同类型并支持片段和别名`() {
        val nodes = listOf(
            KgResolutionNode("1", "work-a", "青禾", "person", aliases = listOf("小禾")),
            KgResolutionNode("2", "work-a", "青禾先生", "person"),
            KgResolutionNode("3", "work-a", "顾青禾", "person", aliases = listOf("小禾")),
            KgResolutionNode("4", "work-b", "青禾", "person"),
            KgResolutionNode("5", "work-a", "青禾", "location"),
        )
        val pairs = KgEntityResolution.candidates(nodes).map { setOf(it.first.id, it.second.id) }
        assertThat(pairs).containsExactly(setOf("1", "2"), setOf("1", "3"), setOf("2", "3"))
    }

    @Test fun `估算每批最多五十对并提供输出上限`() {
        assertThat(KgEntityResolution.estimate(0, 2048).calls).isEqualTo(0)
        val estimate = KgEntityResolution.estimate(101, 2048)
        assertThat(estimate.calls).isEqualTo(3)
        assertThat(estimate.outputTokenLimit).isEqualTo(6144)
    }

    @Test fun `并查集按当前链接归并撤销后恢复并且不会跨合集`() {
        val nodes = listOf(
            KgResolutionNode("a", "work-a", "甲", "person"),
            KgResolutionNode("b", "work-a", "乙", "person"),
            KgResolutionNode("c", "work-a", "丙", "person"),
            KgResolutionNode("d", "work-b", "丁", "person"),
        )
        val links = listOf("a" to "b", "b" to "c", "c" to "d")
        val merged = KgEntityResolution.canonicalIds(nodes, links)
        assertThat(merged["a"]).isEqualTo(merged["c"])
        assertThat(merged["d"]).isNotEqualTo(merged["c"])
        val revoked = KgEntityResolution.canonicalIds(nodes, listOf("a" to "b"))
        assertThat(revoked["a"]).isEqualTo(revoked["b"])
        assertThat(revoked["c"]).isNotEqualTo(revoked["a"])
    }

    @Test fun `用户否决在传递合并中优先`() {
        val nodes = listOf("a", "b", "c").map { KgResolutionNode(it, "work-a", it, "person") }
        val result = KgEntityResolution.canonicalIds(nodes, listOf("a" to "c", "b" to "c"), setOf("a" to "b"))
        assertThat(result["a"]).isNotEqualTo(result["b"])
    }
}
