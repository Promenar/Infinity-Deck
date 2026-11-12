package com.promenar.infinity.ui.media

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import io.mockk.mockk
import org.junit.jupiter.api.Test

class WorkspaceMediaModelTest {
    @Test
    fun `URI 编码往返保留中文、空格、井号与问号`() {
        val paths = listOf(
            "/media/2026-10-20/gen-1432-a1b2c3.png",
            "/mnt/相册/夏天 合影.jpg",
            "/a#b/c?d=1&e.png",
            "相对/路径 有空格+加号%25.mp4",
        )
        paths.forEach { path ->
            val model = WorkspaceMediaModel("会话 #1?", path, identity = "sha256:ab cd")
            val uri = model.toUri()
            assertThat(uri).startsWith("infinity-workspace://")
            assertThat(uri.substringAfter("://")).doesNotContain(" ")
            assertThat(uri.substringAfter("://").substringBefore("?v=")).doesNotContain("#")
            assertThat(WorkspaceMediaModel.parse(uri)).isEqualTo(model)
        }
        val withoutIdentity = WorkspaceMediaModel("s1", "/a b.png")
        assertThat(WorkspaceMediaModel.parse(withoutIdentity.toUri())).isEqualTo(withoutIdentity)
    }

    @Test
    fun `非工作区地址或缺少路径时解析失败`() {
        assertThat(WorkspaceMediaModel.parse("https://example.com/a.png")).isNull()
        assertThat(WorkspaceMediaModel.parse("infinity-workspace://")).isNull()
        assertThat(WorkspaceMediaModel.parse("infinity-workspace://s1/")).isNull()
        assertThat(WorkspaceMediaModel.parse("infinity-workspace:///a.png")).isNull()
    }

    @Test
    fun `缓存键随会话、路径与内容身份变化`() {
        val keyer = WorkspaceMediaKeyer()
        val options = mockk<coil3.request.Options>(relaxed = true)
        val base = WorkspaceMediaModel("s1", "/media/a.png", "sha256:1")
        val keys = listOf(
            base,
            base.copy(identity = "sha256:2"),
            base.copy(sessionId = "s2"),
            base.copy(path = "/media/b.png"),
        ).map { keyer.key(it, options) }
        assertThat(keys.toSet()).hasSize(4)
        assertThat(keyer.key(base.copy(), options)).isEqualTo(keys.first())
        // 没有内容身份时不进入内存缓存，避免替换后显示旧图。
        assertThat(keyer.key(base.copy(identity = null), options)).isNull()
    }

    @Test
    fun `内容身份优先 sha256 否则取大小与修改时间`() {
        val ref = MediaRef("/a.png", "image/png", MediaKind.IMAGE, sizeBytes = 10, sha256 = "abc")
        assertThat(mediaContentIdentity(ref, 99)).isEqualTo("sha256:abc")
        assertThat(mediaContentIdentity(ref.copy(sha256 = null), 99)).isEqualTo("size:10-mtime:99")
        assertThat(mediaContentIdentity(ref.copy(sha256 = " "), null)).isEqualTo("size:10")
        assertThat(mediaContentIdentity(ref.copy(sha256 = null, sizeBytes = 11), 99))
            .isNotEqualTo(mediaContentIdentity(ref.copy(sha256 = null), 99))
    }

    @Test
    fun `文件名、父目录与定位路径`() {
        assertThat(mediaDisplayName("/media/2026/a b.png")).isEqualTo("a b.png")
        assertThat(mediaDisplayName("a.png")).isEqualTo("a.png")
        assertThat(mediaParentPath("/media/2026/a.png")).isEqualTo("/media/2026")
        assertThat(mediaParentPath("/a.png")).isEqualTo("/")
        assertThat(mediaParentPath("media/a.png")).isEqualTo("/media")
        assertThat(mediaParentPath("")).isNull()
        assertThat(normalizeLocatePath("media/2026/")).isEqualTo("/media/2026")
        assertThat(normalizeLocatePath("")).isEqualTo("/")
        assertThat(canLocateInExplorer("/media/a.png")).isTrue()
        assertThat(canLocateInExplorer("media/a.png")).isTrue()
        assertThat(canLocateInExplorer("/mnt/相册/a.png")).isFalse()
        assertThat(canLocateInExplorer("/skills/x/a.png")).isFalse()
    }

    @Test
    fun `扩展名推断媒体类别`() {
        assertThat(mediaKindForFileName("A.PNG")).isEqualTo(MediaKind.IMAGE)
        assertThat(mediaKindForFileName("clip.mp4")).isEqualTo(MediaKind.VIDEO)
        assertThat(mediaKindForFileName("voice.m4a")).isEqualTo(MediaKind.AUDIO)
        assertThat(mediaKindForFileName("report.pdf")).isEqualTo(MediaKind.FILE)
        assertThat(mediaKindForFileName("noext")).isEqualTo(MediaKind.FILE)
    }

    @Test
    fun `时长、大小与宽高比格式`() {
        assertThat(formatMediaDuration(null)).isNull()
        assertThat(formatMediaDuration(65_000)).isEqualTo("01:05")
        assertThat(formatMediaDuration(920)).isEqualTo("00:01")
        assertThat(formatMediaDuration(0)).isEqualTo("00:00")
        assertThat(formatMediaDuration(3_725_000)).isEqualTo("1:02:05")
        assertThat(formatMediaSize(512)).isEqualTo("512 B")
        assertThat(formatMediaSize(1536)).isEqualTo("1.5 KB")
        assertThat(mediaAspectRatio(1920, 1080, 1f)).isWithin(0.001f).of(16f / 9f)
        assertThat(mediaAspectRatio(null, 1080, 1.25f)).isEqualTo(1.25f)
        assertThat(mediaAspectRatio(100, 1000, 1f)).isEqualTo(0.5f)
    }
}
