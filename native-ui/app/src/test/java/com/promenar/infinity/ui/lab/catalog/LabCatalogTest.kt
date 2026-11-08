package com.promenar.infinity.ui.lab.catalog

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.lab.LabDeepLink
import com.promenar.infinity.ui.lab.LabScenes
import org.junit.Test

class LabCatalogTest {
    @Test
    fun `版本 id 全局唯一，同一目录内路径段唯一`() {
        val ids = LabCatalog.versionsWithPath().map { it.second.id }
        assertThat(ids).containsNoDuplicates()
        fun check(node: LabNode) {
            assertThat(node.children.map { it.segment }).containsNoDuplicates()
            node.children.filterIsInstance<LabNode>().forEach(::check)
        }
        check(LabCatalog.root)
    }

    @Test
    fun `按路径找目录，空串为根，未知路径为 null`() {
        assertThat(LabCatalog.node("")).isSameInstanceAs(LabCatalog.root)
        assertThat(LabCatalog.node("chat/conversation")?.segment).isEqualTo("conversation")
        assertThat(LabCatalog.node("/chat//inspector/")?.segment).isEqualTo("inspector")
        assertThat(LabCatalog.node("chat/missing")).isNull()
        // 版本不是目录。
        assertThat(LabCatalog.node("terminal/fastfetch")).isNull()
    }

    @Test
    fun `版本数含子目录，按 id 能找回版本与其目录`() {
        assertThat(LabCatalog.versionCount(LabCatalog.root)).isEqualTo(LabCatalog.versionsWithPath().size)
        assertThat(LabCatalog.versionCount(LabCatalog.node("chat")!!)).isEqualTo(20)
        val (path, version) = LabCatalog.versionsWithPath().first { it.second.id == "inspector.state.git-worktrees" }
        assertThat(path).isEqualTo("chat/inspector")
        assertThat(version.fit).isEqualTo(LabScreenFit.ANY)
        assertThat(LabCatalog.version("chat.workflow.ops-502")?.kind).isEqualTo(LabVersionKind.WORKFLOW)
        assertThat(LabCatalog.version("nope")).isNull()
    }

    @Test
    fun `已注册场景的版本都在目录里`() {
        val ids = LabCatalog.versionsWithPath().map { it.second.id }.toSet()
        assertThat(ids).containsAtLeastElementsIn(LabScenes.ids)
    }

    @Test
    fun `深链只在开发者模式开启且版本存在时生效`() {
        assertThat(LabDeepLink.resolve("chat.workflow.ade-json", record = true, developerModeEnabled = true))
            .isEqualTo(LabDeepLink("chat.workflow.ade-json", record = true))
        assertThat(LabDeepLink.resolve("chat.workflow.ade-json", record = false, developerModeEnabled = false)).isNull()
        assertThat(LabDeepLink.resolve("chat.workflow.missing", record = false, developerModeEnabled = true)).isNull()
        // 每个版本都能经深链解析。
        LabCatalog.versionsWithPath().forEach { (_, version) ->
            assertThat(LabDeepLink.resolve(version.id, record = false, developerModeEnabled = true)).isNotNull()
        }
    }
}
