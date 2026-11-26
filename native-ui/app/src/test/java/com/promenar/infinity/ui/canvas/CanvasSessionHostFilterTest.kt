package com.promenar.infinity.ui.canvas

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.artifact.WorkspaceChangedFile
import org.junit.Test

class CanvasSessionHostFilterTest {
    @Test fun `排除前缀过滤掉绑定制作项目文件并保留兄弟目录与普通工作区文件`() {
        val changes = listOf(
            WorkspaceChangedFile("/workspace/productions/e2e-qa-lantern/S1E01/script.md", "m1", 1),
            WorkspaceChangedFile("/home/session/productions/e2e-qa-lantern/assets/hero.png", "m1", 2),
            WorkspaceChangedFile("/session/productions/e2e-qa-lantern/audio/bgm.mp3", "m1", 3),
            WorkspaceChangedFile("productions/e2e-qa-lantern-2/foo.md", "m2", 4),
            WorkspaceChangedFile("/notes/plan.md", "m2", 5),
        )

        val filtered = filterWorkspaceChangesByExcludedPrefixes(changes, setOf("productions/e2e-qa-lantern"))
        assertThat(filtered.map { it.path }).containsExactly(
            "productions/e2e-qa-lantern-2/foo.md",
            "/notes/plan.md",
        ).inOrder()
    }

    @Test fun `空排除集合保持全部工作区改动不变`() {
        val changes = listOf(
            WorkspaceChangedFile("productions/e2e-qa-lantern/S1E01/script.md", "m1", 1),
            WorkspaceChangedFile("/notes/plan.md", "m2", 2),
        )

        assertThat(filterWorkspaceChangesByExcludedPrefixes(changes, emptySet())).isEqualTo(changes)
    }
}
