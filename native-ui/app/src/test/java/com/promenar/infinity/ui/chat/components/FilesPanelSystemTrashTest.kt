package com.promenar.infinity.ui.chat.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FilesPanelSystemTrashTest {

    @Test
    fun `MediaStore 回收区与暂存改名条目被识别`() {
        assertThat(isSystemTrashName(".trashed-1730000000-IMG_0001.jpg")).isTrue()
        assertThat(isSystemTrashName(".pending-1730000000-report.pdf")).isTrue()
        assertThat(isSystemTrashName(".trashed-1-a")).isTrue()
        // 原名本身带连字符或点也照样识别。
        assertThat(isSystemTrashName(".trashed-1730000000-.hidden-file")).isTrue()
    }

    @Test
    fun `普通文件与形似但不完整的名字不被误判`() {
        assertThat(isSystemTrashName("IMG_0001.jpg")).isFalse()
        assertThat(isSystemTrashName("trashed-1730000000-IMG.jpg")).isFalse()
        assertThat(isSystemTrashName(".trashed-IMG.jpg")).isFalse()
        assertThat(isSystemTrashName(".trashed-1730000000-")).isFalse()
        assertThat(isSystemTrashName(".trashed-17300x0000-IMG.jpg")).isFalse()
        assertThat(isSystemTrashName(".Trashed-1730000000-IMG.jpg")).isFalse()
        assertThat(isSystemTrashName("a.trashed-1730000000-IMG.jpg")).isFalse()
        assertThat(isSystemTrashName(".thumbnails")).isFalse()
        assertThat(isSystemTrashName("")).isFalse()
    }

    @Test
    fun `过滤保留原有顺序并返回隐藏数量`() {
        val names = listOf("b.txt", ".trashed-1-a.txt", "a.txt", ".pending-2-c.txt", "c.txt")

        val partition = partitionSystemTrash(names) { it }

        assertThat(partition.visible).containsExactly("b.txt", "a.txt", "c.txt").inOrder()
        assertThat(partition.hiddenCount).isEqualTo(2)
    }

    @Test
    fun `没有回收区条目时隐藏数为零`() {
        val partition = partitionSystemTrash(listOf("a", "b")) { it }

        assertThat(partition.visible).containsExactly("a", "b").inOrder()
        assertThat(partition.hiddenCount).isEqualTo(0)
    }

    @Test
    fun `隐藏总数只累计根层级与已展开可见目录`() {
        val hiddenByParent = mapOf("docs" to 2, "collapsed" to 5, "media" to 1)

        val total = countHiddenSystemTrash(
            rootHidden = 1,
            hiddenByParent = hiddenByParent,
            expandedVisibleDirectoryIds = setOf("docs", "media", "unloaded"),
        )

        assertThat(total).isEqualTo(4)
    }

    @Test
    fun `子项数只在目录已加载时给出`() {
        val children = mapOf("docs" to listOf("a", "b", "c"), "empty" to emptyList<String>())

        assertThat(loadedChildCount("docs", children)).isEqualTo(3)
        assertThat(loadedChildCount("empty", children)).isEqualTo(0)
        assertThat(loadedChildCount("unloaded", children)).isNull()
    }
}
