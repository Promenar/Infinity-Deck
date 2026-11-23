package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.domain.storage.ExternalMount
import org.junit.Test

class SystemPromptMountsTest {
    private fun mount(alias: String, type: String, source: String, unavailable: String? = null) =
        ExternalMount("id-$alias", alias, type, source, "s1", createdAt = 1, unavailableReason = unavailable)

    @Test fun `挂载段落区分命令可见性并隐藏技能目录`() {
        val text = SystemPromptSections.toolGuidance(
            setOf("read_file", "shell_exec"),
            generic = false,
            mounts = listOf(
                mount("termux", "saf_tree", "content://tree"),
                mount("docs", "real_path", "/storage/emulated/0/Documents"),
                mount("gone", "real_path", "/storage/emulated/0/Old", unavailable = "missing"),
                ExternalMount(ExternalMountPaths.SKILLS_MOUNT_ID, ExternalMountPaths.SKILLS_ALIAS, ExternalMountPaths.SKILLS_SOURCE, "", "s1", 1),
            ),
        )
        assertThat(text).contains("### Mounted Folders")
        assertThat(text).contains("`/storage/emulated/0/Documents`（挂载 `docs`）：文件工具与命令共用该真实路径")
        assertThat(text).contains("`/mnt/termux/`: file tools only.")
        assertThat(text).contains("`/mnt/gone/`: currently unavailable")
        assertThat(text).doesNotContain("/storage/emulated/0/Old")
        assertThat(text).doesNotContain("/mnt/skills/")
    }

    @Test fun `没有挂载或没有工作区工具时不输出挂载段落`() {
        assertThat(SystemPromptSections.toolGuidance(setOf("read_file"), generic = false)).doesNotContain("Mounted Folders")
        val docs = listOf(mount("docs", "real_path", "/storage/emulated/0/Documents"))
        assertThat(SystemPromptSections.toolGuidance(setOf("web_search"), generic = false, mounts = docs)).doesNotContain("Mounted Folders")
    }

    @Test fun `知识库在普通与Linux提示中均显示只读且不暴露物理路径`() {
        val knowledge = mount("knowledge", ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, "root-uuid").copy(readOnly = true)
        for (linux in listOf(false, true)) {
            val text = SystemPromptSections.toolGuidance(setOf("read_file"), generic = false, mounts = listOf(knowledge),
                runMode = if (linux) com.promenar.infinity.domain.model.SessionRunMode.LINUX else com.promenar.infinity.domain.model.SessionRunMode.BUILTIN)
            assertThat(text).contains("`/mnt/knowledge/`：只读")
            assertThat(text).contains("命令无法访问知识库挂载")
            assertThat(text).doesNotContain("root-uuid")
            assertThat(text).doesNotContain("may read, write and delete")
        }
    }

    @Test fun `资料库提示区分读写档位与录音编辑例外`() {
        val readOnly = mount("reference", ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, "kb-ro").copy(readOnly = true)
        val readWrite = mount("working", ExternalMountPaths.KNOWLEDGE_BASE_RW_SOURCE, "kb-rw")
        for (mode in com.promenar.infinity.domain.model.SessionRunMode.entries) {
            val text = SystemPromptSections.toolGuidance(setOf("read_file", "write_file"), generic = false,
                mounts = listOf(readOnly, readWrite), runMode = mode)
            assertThat(text).contains("`/mnt/reference/`：只读知识库")
            assertThat(text).contains("录音整理稿 .md 可带 expectedHash 版本化编辑")
            assertThat(text).contains("`/mnt/working/`：知识库读写")
            assertThat(text).contains("经审批删除资料文档并同步索引")
            assertThat(text).contains("原始录音 .wav 只读")
            assertThat(text).doesNotContain("kb-rw")
        }
    }
}
