package com.promenar.infinity.ui.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibraryCollectionStripDataTest {
    private val data = LibraryCollectionStripData(
        folderId = "f", collectionTitle = "雾港纪事", kind = CollectionStripKind.WORK,
        segments = listOf(true, false, true, false), documentIds = listOf("a", "b", "c", "d"),
    )

    @Test fun `索引失败的文档不计入合集进度且保持篇序`() {
        val filtered = data.withoutDocuments(setOf("b"))
        assertThat(filtered.documentIds).containsExactly("a", "c", "d").inOrder()
        assertThat(filtered.segments).containsExactly(true, true, false).inOrder()
        assertThat(filtered.extracted).isEqualTo(2)
        assertThat(filtered.total).isEqualTo(3)
    }

    @Test fun `没有失败文档或缺少对应 id 时原样返回`() {
        assertThat(data.withoutDocuments(emptySet())).isSameInstanceAs(data)
        val legacy = data.copy(documentIds = emptyList())
        assertThat(legacy.withoutDocuments(setOf("a"))).isSameInstanceAs(legacy)
    }
}
