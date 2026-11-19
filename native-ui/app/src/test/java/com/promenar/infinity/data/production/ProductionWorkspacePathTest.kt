package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProductionWorkspacePathTest {
    private fun path(raw: String, linux: Boolean = false, directory: String? = null, root: String? = null) =
        normalizeProductionWorkspacePath(raw, linux, directory, root, setOf("film", "other"))

    @Test fun `普通会话接受虚拟根与相对路径`() {
        assertThat(path("/productions/demo/ep1.md")).isEqualTo("productions/demo/ep1.md")
        assertThat(path("productions/demo/ep1.md")).isEqualTo("productions/demo/ep1.md")
        assertThat(path("/session/media/a.png")).isEqualTo(".infinity-production-private/media/a.png")
    }

    @Test fun `Linux 仅去掉所属根前缀并保留独立私有目录归属`() {
        assertThat(path("/home/session/productions/demo/ep1.md", linux = true)).isEqualTo("productions/demo/ep1.md")
        assertThat(path("/workspace/productions/demo/ep1.md", linux = true)).isEqualTo("productions/demo/ep1.md")
        assertThat(path("/workspace/media/a.png", true, "/home/project")).isEqualTo("media/a.png")
        assertThat(path("/home/project/media/a.png", true, "/home/project")).isEqualTo("media/a.png")
        assertThat(path("/home/session/media/a.png", true, "/home/project")).isEqualTo(".infinity-production-private/media/a.png")
        assertThat(path("/home/session/mnt/film/a.png", true)).isEqualTo(".infinity-production-private/mnt/film/a.png")
        assertThat(path("/home/session/session/media/a.png", true, "/home/project")).isEqualTo("session/media/a.png")
        assertThat(path("session/media/a.png", true, "/home/project")).isEqualTo("session/media/a.png")
        assertThat(path(".infinity-production-private/media/a.png", true, "/home/project")).isEqualTo(".infinity-production-private/media/a.png")
    }

    @Test fun `拒绝越界保留名 URI 系统根及相似前缀`() {
        listOf("https://example.test/a", "content://a/b", "file:/media/a.png", "//media/a", "/media//a", "media/./a",
            "/media/../a", "/media/a/", "media\\a", "/mnt/unknown/a", "/workspace/mnt/film/a",
            "/workspace/.l2s/a", "/.infinity-production-private/media/a.png", ".infinity-production-private/.infinity/a", "/tmp/a", "/etc/a", "/home/project-other/a", "/storage/emulated/0/a").forEach { raw ->
            val error = assertThrows(IllegalArgumentException::class.java) { path(raw, true, "/home/project") }
            assertThat(error.message).contains("路径")
        }
    }

    @Test fun `只允许项目所属的已登记外部挂载并保留路径前缀`() {
        assertThat(path("/mnt/film/demo/shot.mp4", true, "/home/project", "mnt/film/demo"))
            .isEqualTo("mnt/film/demo/shot.mp4")
        listOf("/mnt/other/demo/shot.mp4", "/mnt/film/demo-other/shot.mp4").forEach { raw ->
            assertThat(assertThrows(IllegalArgumentException::class.java) {
                path(raw, true, "/home/project", "mnt/film/demo")
            }.message).contains("当前项目")
        }
    }
}
