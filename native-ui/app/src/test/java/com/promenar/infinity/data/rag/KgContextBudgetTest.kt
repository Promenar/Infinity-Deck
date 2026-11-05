package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.KgIdentity
import com.promenar.infinity.data.model.KgEdge
import com.promenar.infinity.data.model.KgNode
import com.promenar.infinity.data.model.KgPath
import org.junit.Test

class KgContextBudgetTest {
    @Test fun `融合按规范实体类型及关系去重并合并来源`() {
        val merged = KgContextBudget.fuse(listOf(path("Ａlice", "北京", " LIVES IN ", 0.8, "persistent")),
            listOf(path("alice", "北京", "lives in", 0.9, "jit")))
        assertThat(merged).hasSize(1)
        assertThat(merged.single().origins).containsExactly("persistent", "jit")
        assertThat(merged.single().edges.single().weight).isEqualTo(0.9)
    }

    @Test fun `预算优先保留高分完整关系且快照文本严格一致`() {
        val high = path("甲", "乙", "高分", 0.9)
        val low = path("甲", "丙", "低分".repeat(100), 0.1)
        val budget = KgContextBudget.tokens(KgContextBudget.render(listOf(high))) + 20
        val fitted = KgContextBudget.fit(listOf(low, high), budget)
        assertThat(fitted.paths).hasSize(1)
        assertThat(fitted.paths.single().edges.single().relation).isEqualTo("高分")
        assertThat(fitted.paths.single().truncated).isTrue()
        assertThat(fitted.paths.single().omittedPathCount).isEqualTo(1)
        assertThat(fitted.context).isEqualTo(KgContextBudget.render(fitted.paths))
        assertThat(KgContextBudget.tokens(fitted.context)).isAtMost(budget)
    }

    @Test fun `空预算保留裁剪标记且不注入任何关系`() {
        val fitted = KgContextBudget.fit(listOf(path("甲", "乙", "关系", 0.8)), 0)
        assertThat(fitted.context).isEmpty()
        assertThat(fitted.paths.single().edges).isEmpty()
        assertThat(fitted.paths.single().omittedPathCount).isEqualTo(1)
    }

    @Test fun `事件上下文按出现位置倒序并带事件标识`() {
        val early = path("甲", "乙", "相遇", 0.9).copy(narrativeEventOrdinal = 3)
        val late = path("甲", "乙", "救援", 0.2).copy(narrativeEventOrdinal = 9)
        val fitted = KgContextBudget.fit(listOf(early, late), 2048)
        assertThat(fitted.paths.map { it.edges.single().relation }).containsExactly("救援", "相遇").inOrder()
        assertThat(fitted.context.lines().all { it.contains("事件：") }).isTrue()
    }

    @Test fun `同一关系的不同事件出现不被融合`() {
        val early = path("甲", "乙", "救出", 0.9).copy(narrativeEventOrdinal = 3, narrativeEventDocId = "d3", narrativeEventWindow = 0, narrativeEventOffset = 1)
        val late = path("甲", "乙", "救出", 0.9).copy(narrativeEventOrdinal = 9, narrativeEventDocId = "d9", narrativeEventWindow = 0, narrativeEventOffset = 2)
        assertThat(KgContextBudget.fuse(listOf(early, late), emptyList()).map { it.narrativeEventOrdinal }).containsExactly(9, 3).inOrder()
    }

    @Test fun `重复标签不同类型不合并且结果输入重排确定`() {
        val a = path("甲", "乙", "关系", 0.8)
        val b = a.copy(nodes = a.nodes.map { it.copy(type = "person") })
        assertThat(KgContextBudget.fuse(listOf(a, b), emptyList())).hasSize(2)
        assertThat(KgContextBudget.fit(listOf(a, b), 2048))
            .isEqualTo(KgContextBudget.fit(listOf(b, a), 2048))
        assertThat(KgIdentity.normalize("ＡLICE")).isEqualTo("alice")
    }

    private fun path(a: String, b: String, relation: String, score: Double, origin: String = "persistent") = KgPath(
        nodes = listOf(KgNode("a", a, "concept"), KgNode("b", b, "concept")),
        edges = listOf(KgEdge("a", "b", relation, score)), origins = listOf(origin), score = score,
    )
}
