package com.promenar.infinity.ui.media

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.common.MarkdownMediaReference
import org.junit.Test

class WorkspaceMediaGroupsTest {
    private fun ref(path: String) = MarkdownMediaReference(path, null, isLink = false)

    @Test
    fun `矩阵最多四格，超出部分计入最后一格的加号数量`() {
        assertThat((1..4).map(::mediaGalleryOverflow)).containsExactly(0, 0, 0, 0).inOrder()
        assertThat(mediaGalleryOverflow(5)).isEqualTo(1)
        assertThat(mediaGalleryOverflow(6)).isEqualTo(2)
        assertThat(mediaGalleryOverflow(0)).isEqualTo(0)
    }

    @Test
    fun `全屏查看器只在组内图片之间切换，视频格不进入图片序列`() {
        val items = listOf(ref("media/a.png"), ref("media/clip.mp4"), ref("media/b.jpg"), ref("media/c.webp"))
        assertThat(mediaGroupImagePaths(items)).containsExactly("media/a.png", "media/b.jpg", "media/c.webp").inOrder()
        assertThat(mediaGroupImageViewerStart(items, 0)).isEqualTo(0)
        assertThat(mediaGroupImageViewerStart(items, 1)).isEqualTo(-1)
        assertThat(mediaGroupImageViewerStart(items, 2)).isEqualTo(1)
        assertThat(mediaGroupImageViewerStart(items, 3)).isEqualTo(2)
        assertThat(mediaGroupImageViewerStart(items, 4)).isEqualTo(-1)
    }

    @Test
    fun `同一路径重复出现时按位置定位`() {
        val items = listOf(ref("media/a.png"), ref("media/a.png"), ref("media/b.png"))
        assertThat(mediaGroupImageViewerStart(items, 1)).isEqualTo(1)
        assertThat(mediaGroupImagePaths(items)).hasSize(3)
    }
}
