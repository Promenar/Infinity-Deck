package com.promenar.infinity.ui.lab.library

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.library.ask.DefaultChunkLocator
import com.promenar.infinity.domain.library.AskHitKind
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LabLibraryTextTest {
    private fun text(zh: Boolean) = LabLibraryText(zh, LocationTitles("Storage", "Sessions", "Linux"))

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `文档命中都能在正文里精确定位，问资料打开的文档有两处命中`(zh: Boolean) {
        val text = text(zh)
        val documentHits = text.hits().filter { it.kind == AskHitKind.DOCUMENT }
        documentHits.forEach { hit ->
            val body = text.body(hit.documentId!!)!!.text
            assertThat(body.indexOf(hit.chunkContent!!)).isAtLeast(0)
            assertThat(DefaultChunkLocator.locate(body, hit.chunkContent!!, hit.highlightTerms)).isNotNull()
        }
        val opened = documentHits.first().documentId
        assertThat(documentHits.count { it.documentId == opened }).isEqualTo(2)
        assertThat(text.insight(opened!!)).isNotNull()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `答案里的编号按首次出现顺序对应引用下标`(zh: Boolean) {
        val text = text(zh)
        val markers = Regex("\\[(\\d+)]").findAll(text.answer).map { it.groupValues[1].toInt() - 1 }.distinct().toList()
        assertThat(text.citedHitIndexes).isEqualTo(markers)
        assertThat(markers.all { it in text.hits().indices }).isTrue()
        assertThat(text.hits().map { it.key }.toSet()).hasSize(text.hits().size)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `最近与待处理的文档都能打开详情，位置与固定文件夹一致`(zh: Boolean) {
        val text = text(zh)
        (text.recent.map { it.documentId } + text.pending.map { it.documentId }).forEach { id ->
            assertThat(text.insight(id)).isNotNull()
        }
        text.recent.forEach { assertThat(text.documentIdFor(it.ref)).isEqualTo(it.documentId) }
        val ids = text.locations.map { it.id }.toSet()
        assertThat(text.pins.all { it.locationId in ids }).isTrue()
        // 文档页的合集两行显示作品名与篇序，不露出文档 id。
        (text.recent.map { it.documentId }).forEach { id ->
            val info = text.collectionInfo(id)
            assertThat(info.title).doesNotContain(id)
            assertThat(info.ordinal).isNotNull()
        }
        assertThat(text.collectionAssignment.folderCollectionTitle).isEqualTo(text.collectionInfo(text.recent.first().documentId).title)
    }
}
