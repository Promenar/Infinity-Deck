package com.promenar.infinity.ui.renderer

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HtmlWorkspaceMediaSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root by lazy { temporary.newFolder("workspace") }
    private fun source() = HtmlWorkspaceMediaSource({ root }, JvmBoundRealPathFileSystem())
    private fun url(path: String) = HtmlWorkspaceMediaSource.BASE_URL + path

    @Test fun `只允许媒体扩展名和严格单次编码的根内路径`() {
        listOf("photo.png", "clip.MP4", "audio/voice.mp3", "diagram.svg", "%E5%9B%BE%E7%89%87/a%20b.webp").forEach {
            assertThat(HtmlWorkspaceMediaSource.relativePath(url(it))).isNotNull()
        }
        listOf("", "../a.png", "%2e%2e/a.png", "a/%2Fsecret.png", "%252e%252e/a.png", "a%5cb.png",
            "a.png/", "script.js", "page.html", "x.txt", "a%00.png", "a.png?x=1").forEach {
            assertThat(HtmlWorkspaceMediaSource.relativePath(url(it))).isNull()
        }
        listOf("http://workspace-media.infinity.invalid/workspace/a.png",
            "https://workspace-media.infinity.invalid:8443/workspace/a.png",
            "https://user@workspace-media.infinity.invalid/workspace/a.png",
            "https://workspace-media.infinity.invalid.evil.test/workspace/a.png").forEach {
            assertThat(HtmlWorkspaceMediaSource.relativePath(it)).isNull()
        }
        assertThat(RichContentRequestPolicy.classify(url("a.png"))).isEqualTo(RichContentRequestPolicy.Kind.BLOCKED)
    }

    @Test fun `GET与HEAD只读且Range支持区间后缀开放结尾与416`() = runTest {
        root.resolve("clip.mp4").writeBytes(ByteArray(100) { it.toByte() })
        val source = source()
        source.respond(url("clip.mp4"), "GET", null).use {
            assertThat(it.status).isEqualTo(200)
            assertThat(it.body.readBytes()).hasLength(100)
        }
        mapOf("bytes=10-19" to (10..19), "bytes=95-" to (95..99), "bytes=-5" to (95..99),
            "bytes=98-200" to (98..99)).forEach { (range, expected) ->
            source.respond(url("clip.mp4"), "GET", range).use {
                assertThat(it.status).isEqualTo(206)
                assertThat(it.headers["Content-Range"]).isEqualTo("bytes ${expected.first}-${expected.last}/100")
                assertThat(it.body.readBytes().map(Byte::toInt)).containsExactlyElementsIn(expected).inOrder()
            }
        }
        listOf("bytes=100-", "bytes=-0", "bytes=9-2", "bytes=0-1,3-4", "bytes=999999999999999999999-", "bad").forEach { range ->
            source.respond(url("clip.mp4"), "GET", range).use {
                assertThat(it.status).isEqualTo(416)
                assertThat(it.headers["Content-Range"]).isEqualTo("bytes */100")
            }
        }
        source.respond(url("clip.mp4"), "HEAD", null).use {
            assertThat(it.status).isEqualTo(200)
            assertThat(it.body.readBytes()).isEmpty()
            assertThat(it.headers["Content-Length"]).isEqualTo("100")
        }
        source.respond(url("clip.mp4"), "POST", null).use { assertThat(it.status).isEqualTo(405) }
        assertThat(root.resolve("clip.mp4").length()).isEqualTo(100)
    }

    @Test fun `拒绝文件目录与根符号链接且工作区切换后不读取旧根`() = runTest {
        val outside = temporary.newFolder("outside")
        outside.resolve("secret.png").writeText("外部内容")
        Files.createSymbolicLink(root.resolve("linked.png").toPath(), outside.resolve("secret.png").toPath())
        Files.createSymbolicLink(root.resolve("linked-dir").toPath(), outside.toPath())
        root.resolve("directory.png").mkdir()
        for (path in listOf("linked.png", "linked-dir/secret.png", "directory.png", "missing.png")) {
            source().respond(url(path), "GET", null).use { assertThat(it.status).isEqualTo(403) }
        }
        val rootLink = temporary.root.resolve("root-link")
        Files.createSymbolicLink(rootLink.toPath(), root.toPath())
        HtmlWorkspaceMediaSource({ rootLink }, JvmBoundRealPathFileSystem()).respond(url("linked.png"), "GET", null).use {
            assertThat(it.status).isEqualTo(403)
        }
        root.resolve("safe.png").writeText("当前内容")
        var current = root
        val bound = HtmlWorkspaceMediaSource({ current }, JvmBoundRealPathFileSystem())
        bound.respond(url("safe.png"), "GET", null).close()
        current = outside
        bound.respond(url("secret.png"), "GET", null).use { assertThat(it.status).isEqualTo(403) }
    }
}
