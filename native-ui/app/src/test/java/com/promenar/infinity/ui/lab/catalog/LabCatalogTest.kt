package com.promenar.infinity.ui.lab.catalog

import com.google.common.truth.Truth.assertThat
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
        assertThat(LabCatalog.versionCount(LabCatalog.node("chat")!!)).isEqualTo(7)
        val (path, version) = LabCatalog.versionsWithPath().first { it.second.id == "inspector.state.git-worktrees" }
        assertThat(path).isEqualTo("chat/inspector")
        assertThat(version.fit).isEqualTo(LabScreenFit.INNER)
        assertThat(LabCatalog.version("chat.workflow.ops-502")?.kind).isEqualTo(LabVersionKind.WORKFLOW)
        assertThat(LabCatalog.version("nope")).isNull()
    }
}
