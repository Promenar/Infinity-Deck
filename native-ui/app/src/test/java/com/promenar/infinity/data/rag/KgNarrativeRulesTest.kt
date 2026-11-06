package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class KgNarrativeRulesTest {
    @Test fun `持有槽位按物品区分且倒叙不改变状态`() {
        fun possession(edge: String, owner: String, item: String, chapter: Int, flashback: Boolean = false) =
            KgNarrativeMention(edge, "work", owner, item, "state", "possession", "doc$chapter", chapter, 0, 0, flashback)
        val timeline = KgNarrativeTimeline.materialize(listOf(
            possession("sword-a", "a", "sword", 1), possession("book-a", "a", "book", 2),
            possession("sword-b", "b", "sword", 3), possession("sword-a", "a", "sword", 4, true),
        ))
        val current = KgNarrativeTimeline.at(timeline, 4)
        assertThat(current.map { it.edgeId }).containsExactly("book-a", "sword-b")
        val sword = current.single { it.edgeId == "sword-b" }
        assertThat(sword.history.single().edgeId).isEqualTo("sword-a")
        assertThat(sword.history.single().toExclusive?.order).isEqualTo(3)
        assertThat(sword.flashbacks.single().edgeId).isEqualTo("sword-a")
    }
    private fun mention(edge: String, chapter: Int, flashback: Boolean = false, kind: String = "state") =
        KgNarrativeMention(edge, "work", "a", "b", kind, "relationship", "doc$chapter", chapter, 0, 0, flashback)

    @Test fun `自然排序识别阿拉伯与中文章序且无数字名称靠后`() {
        assertThat(listOf("第2章", "第10章", "第1章", "第十二章", "前言").sortedWith(KgNaturalOrder))
            .containsExactly("第1章", "第2章", "第10章", "第十二章", "前言").inOrder()
        assertThat(listOf("chapter-10", "chapter-2").sortedWith(KgNaturalOrder))
            .containsExactly("chapter-2", "chapter-10").inOrder()
        assertThat(listOf("卷十·第2章", "卷二·第3章").sortedWith(KgNaturalOrder))
            .containsExactly("卷二·第3章", "卷十·第2章").inOrder()
        assertThat(KgNaturalOrder.chapterNumber("第二卷")).isEqualTo(KgNaturalOrder.chapterNumber("卷二"))
        assertThat(KgNaturalOrder.chapterNumbers("卷二·第3章")).contains(KgNaturalOrder.chapterNumber("第二卷"))
    }

    @Test fun `二十篇按范围与移动后的阅读顺序计算状态和历史`() {
        val mentions = listOf(mention("ally", 3), mention("enemy", 15))
        val timeline = KgNarrativeTimeline.materialize(mentions)
        assertThat(KgNarrativeTimeline.at(timeline, 10).single().edgeId).isEqualTo("ally")
        val all = KgNarrativeTimeline.at(timeline, 20).single()
        assertThat(all.edgeId).isEqualTo("enemy")
        assertThat(all.history.single().edgeId).isEqualTo("ally")
        assertThat(all.history.single().from.order).isEqualTo(3)
        assertThat(all.history.single().toExclusive?.order).isEqualTo(15)
        val moved = listOf(mention("ally", 4), mention("enemy", 3))
        val after = KgNarrativeTimeline.at(KgNarrativeTimeline.materialize(moved), 20).single()
        assertThat(after.edgeId).isEqualTo("ally")
        assertThat(after.history.single().edgeId).isEqualTo("enemy")
    }

    @Test fun `倒叙只进入历史不改变状态`() {
        val timeline = KgNarrativeTimeline.materialize(listOf(
            mention("ally", 3), mention("enemy", 15), mention("ally", 19, flashback = true),
        ))
        val current = KgNarrativeTimeline.at(timeline, 20).single()
        assertThat(current.edgeId).isEqualTo("enemy")
        assertThat(current.flashbacks.map { it.edgeId }).containsExactly("ally")
    }

    @Test fun `同一关系重复出现不产生新区间且关系方向共享槽位`() {
        val reverse = mention("enemy", 15).copy(sourceId = "b", targetId = "a")
        val timeline = KgNarrativeTimeline.materialize(listOf(mention("ally", 3), mention("ally", 8), reverse))
        assertThat(timeline.intervals).hasSize(2)
        assertThat(KgNarrativeTimeline.at(timeline, 20).single().edgeId).isEqualTo("enemy")
    }

    @Test fun `事件不会覆盖状态且按阅读位置排列`() {
        val timeline = KgNarrativeTimeline.materialize(listOf(
            mention("ally", 3), mention("rescue", 9, kind = "event"), mention("meeting", 12, kind = "event"),
        ))
        assertThat(KgNarrativeTimeline.at(timeline, 20).single().edgeId).isEqualTo("ally")
        assertThat(timeline.events.sortedByDescending { it.position }.map { it.edgeId }).containsExactly("meeting", "rescue").inOrder()
    }

    @Test fun `上下文限制三段历史且详情可读取全部历史`() {
        val timeline = KgNarrativeTimeline.materialize((1..6).map { mention(if (it % 2 == 0) "enemy" else "ally", it) })
        assertThat(KgNarrativeTimeline.at(timeline, 6).single().history).hasSize(3)
        assertThat(KgNarrativeTimeline.at(timeline, 6, historyLimit = Int.MAX_VALUE).single().history).hasSize(5)
    }

    @Test fun `旧抽取响应仍然可解析且位置未知`() {
        val result = KgGraphValidation.parse("""{"nodes":[{"name":"甲","type":"person"},{"name":"乙","type":"person"}],"edges":[{"source":"甲","target":"乙","relation":"盟友"}]}""")
        assertThat(result.isSuccess).isTrue()
        val edge = result.edges.single()
        assertThat(edge.windowIndex).isNull()
        assertThat(edge.evidence).isNull()
        assertThat(edge.kind).isNull()
        assertThat(edge.family).isNull()
    }
}
