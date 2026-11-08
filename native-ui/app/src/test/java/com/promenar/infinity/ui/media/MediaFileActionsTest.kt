package com.promenar.infinity.ui.media

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MediaFileActionsTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun `保存目标目录与 MIME 映射`() {
        assertThat(mediaSaveTarget(MediaKind.IMAGE, "image/png"))
            .isEqualTo(MediaSaveTarget(MediaSaveCollection.PICTURES, "Pictures/Infinity Deck", "image/png"))
        assertThat(mediaSaveTarget(MediaKind.VIDEO, "Video/MP4"))
            .isEqualTo(MediaSaveTarget(MediaSaveCollection.MOVIES, "Movies/Infinity Deck", "video/mp4"))
        assertThat(mediaSaveTarget(MediaKind.AUDIO, "audio/mpeg"))
            .isEqualTo(MediaSaveTarget(MediaSaveCollection.DOWNLOADS, "Download/Infinity Deck", "audio/mpeg"))
        assertThat(mediaSaveTarget(MediaKind.FILE, "application/pdf"))
            .isEqualTo(MediaSaveTarget(MediaSaveCollection.DOWNLOADS, "Download/Infinity Deck", "application/pdf"))
        // 类别与 MIME 不符、MIME 缺失或为通配时退到下载目录与通用类型。
        assertThat(mediaSaveTarget(MediaKind.IMAGE, "application/octet-stream").collection).isEqualTo(MediaSaveCollection.DOWNLOADS)
        assertThat(mediaSaveTarget(MediaKind.IMAGE, "").mimeType).isEqualTo("application/octet-stream")
        assertThat(mediaSaveTarget(MediaKind.IMAGE, "image/*").collection).isEqualTo(MediaSaveCollection.DOWNLOADS)
    }

    @Test
    fun `FileProvider 覆盖的文件直接分享，其它来源复制到缓存`() {
        val filesDir = File(temp, "files").apply { mkdirs() }
        val shareDir = File(temp, "cache/workspace-media-share").apply { mkdirs() }
        val roots = listOf(filesDir, shareDir)
        val privateFile = File(filesDir, "workspaces/s1/media/a.png").apply { parentFile!!.mkdirs(); writeText("x") }
        val sharedStorage = File(temp, "sdcard/DCIM/b.png").apply { parentFile!!.mkdirs(); writeText("x") }
        val sibling = File(temp, "files-other/c.png").apply { parentFile!!.mkdirs(); writeText("x") }
        val escaping = File(filesDir, "../sdcard/DCIM/b.png")

        assertThat(mediaShareRoute(MediaSource.LocalFile(privateFile), roots))
            .isEqualTo(MediaShareRoute.Direct(privateFile.canonicalFile))
        assertThat(mediaShareRoute(MediaSource.LocalFile(sharedStorage), roots)).isEqualTo(MediaShareRoute.CopyToCache)
        assertThat(mediaShareRoute(MediaSource.LocalFile(sibling), roots)).isEqualTo(MediaShareRoute.CopyToCache)
        assertThat(mediaShareRoute(MediaSource.LocalFile(escaping), roots)).isEqualTo(MediaShareRoute.CopyToCache)
        assertThat(mediaShareRoute(MediaSource.ContentUri("content://x/1"), roots)).isEqualTo(MediaShareRoute.CopyToCache)
    }

    @Test
    fun `分享缓存只清理过期副本`() {
        val old = File(temp, "old").apply { mkdirs(); setLastModified(1_000) }
        val fresh = File(temp, "fresh").apply { mkdirs(); setLastModified(9_500) }

        assertThat(staleShareEntries(listOf(old, fresh), nowMs = 10_000, maxAgeMs = 1_000)).containsExactly(old)
    }
}
