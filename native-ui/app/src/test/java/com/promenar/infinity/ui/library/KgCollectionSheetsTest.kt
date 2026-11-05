package com.promenar.infinity.ui.library

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.rag.KgBatchDocument
import com.promenar.infinity.data.rag.KgBatchPlanner
import com.promenar.infinity.data.rag.KgCollectionArticle
import com.promenar.infinity.data.rag.KgExtractionEstimate
import com.promenar.infinity.data.rag.KgTimelineHistory
import com.promenar.infinity.ui.rag.kgHistoryLines
import org.junit.jupiter.api.Test

class KgCollectionSheetsTest {
    private fun doc(id: String, extracted: Boolean, calls: Int) =
        KgBatchDocument(id, id, "root", "h", 1, extracted, KgExtractionEstimate(calls, 100L * calls, 16384L * calls, calls, true, calls))

    @Test fun `一次全量估算拆出只抽未抽取与全部重抽两种选择`() {
        val all = KgBatchPlanner.aggregate(listOf(doc("a", true, 2), doc("b", false, 3), doc("c", false, 1)), onlyUnextracted = false)
        val choices = kgBatchChoices(all)
        assertThat(choices.pending.documents.map { it.docId }).containsExactly("b", "c")
        assertThat(choices.pending.skippedCount).isEqualTo(1)
        assertThat(choices.pending.calls).isEqualTo(4)
        assertThat(choices.pending.onlyUnextracted).isTrue()
        assertThat(choices.all.documentCount).isEqualTo(3)
        assertThat(choices.all.calls).isEqualTo(6)
        assertThat(choices.offersChoice).isTrue()
        assertThat(choices.defaultOnlyPending()).isTrue()
    }

    @Test fun `全部已抽取时默认全部重抽且不再提供选择`() {
        val choices = kgBatchChoices(KgBatchPlanner.aggregate(listOf(doc("a", true, 2)), onlyUnextracted = false))
        assertThat(choices.offersChoice).isFalse()
        assertThat(choices.defaultOnlyPending()).isFalse()
        assertThat(choices.all.documentCount).isEqualTo(1)
    }

    @Test fun `只有存在手动篇序时按名称插入的篇目标为新加入`() {
        val natural = listOf(KgCollectionArticle("a", "第1章", "1", "natural", 1), KgCollectionArticle("b", "第2章", "2", "natural", 2))
        assertThat(natural.none { it.isNewlyAdded(natural) }).isTrue()
        val manual = listOf(KgCollectionArticle("b", "第2章", "1", "manual", 1), KgCollectionArticle("c", "第3章", "2", "natural", 2),
            KgCollectionArticle("a", "第1章", "3", "manual", 3))
        assertThat(manual.filter { it.isNewlyAdded(manual) }.map { it.docId }).containsExactly("c")
    }

    @Test fun `状态变化按关系两端名字显示并去重`() {
        val rows = listOf(
            KgTimelineHistory("e1", "同盟", 3, null, "d3"),
            KgTimelineHistory("e1", "同盟", 3, null, "d3"),
            KgTimelineHistory("e2", "所在地", 1, 5, "d1"),
            KgTimelineHistory("e3", "回忆", 10, 10, "d10", flashback = true),
            KgTimelineHistory("gone", "认识", 2, 2, "d2"),
        )
        val lines = kgHistoryLines(rows, mapOf("e1" to ("沈砚" to "林照"), "e2" to ("沈砚" to "北原"), "e3" to ("沈砚" to "老周")))
        assertThat(lines.map { "${it.source}-${it.relation}-${it.target}:${it.startOrdinal}-${it.endOrdinal}:${it.flashback}" }).containsExactly(
            "沈砚-同盟-林照:3-null:false", "沈砚-所在地-北原:1-5:false", "沈砚-回忆-老周:10-10:true",
        ).inOrder()
    }
}
