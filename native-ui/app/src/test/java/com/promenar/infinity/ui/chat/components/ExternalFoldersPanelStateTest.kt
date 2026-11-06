package com.promenar.infinity.ui.chat.components

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.storage.ExternalMount
import org.junit.jupiter.api.Test

class ExternalFoldersPanelStateTest {

    @Test
    fun `真实路径入口同时要求构建常量和发行能力`() {
        assertThat(availableExternalMountSources(false, false)).containsExactly(ExternalMountSource.Saf)
        assertThat(availableExternalMountSources(false, true)).containsExactly(ExternalMountSource.Saf)
        assertThat(availableExternalMountSources(true, false)).containsExactly(ExternalMountSource.Saf)
        assertThat(availableExternalMountSources(true, true)).containsExactly(
            ExternalMountSource.Saf,
            ExternalMountSource.RealPath,
        ).inOrder()
    }

    @Test
    fun `挂载面板只展示当前会话且空会话不显示路径`() {
        val mounts = listOf(mount("current", "session-a"), mount("other", "session-b"))

        assertThat(visibleExternalMounts(mounts, "session-a").map { it.id })
            .containsExactly("current")
        assertThat(visibleExternalMounts(mounts, null).map { it.id }).isEmpty()
        assertThat(visibleExternalMounts(mounts, "").map { it.id }).isEmpty()
    }

    @Test
    fun `会话可引用的资料库文件夹排除已挂同源且 Linux 会话只列真实路径`() {
        val saf = mount("lib-saf", "__library__")
        val attached = mount("lib-attached", "__library__")
        val real = mount("lib-real", "__library__").copy(sourceType = "real_path", sourceId = "/storage/emulated/0/Notes")
        // 会话里已有与 attached 同源的一行（另一个 id），不应再列出。
        val session = listOf(attached.copy(id = "session-copy", sessionId = "session-a"))

        assertThat(libraryMountChoices(listOf(saf, attached, real), session, linuxSession = false).map { it.id })
            .containsExactly("lib-saf", "lib-real").inOrder()
        assertThat(libraryMountChoices(listOf(saf, attached, real), session, linuxSession = true).map { it.id })
            .containsExactly("lib-real")
    }

    @Test
    fun `回退在挂载根停止且拒绝相似前缀和越界路径`() {
        assertThat(parentExternalFolderPath("/mnt/docs", "/mnt/docs/a/b")).isEqualTo("/mnt/docs/a")
        assertThat(parentExternalFolderPath("/mnt/docs", "/mnt/docs/a")).isEqualTo("/mnt/docs")
        assertThat(parentExternalFolderPath("/mnt/docs", "/mnt/docs")).isNull()
        assertThat(parentExternalFolderPath("/mnt/docs", "/mnt/docs-other/a")).isNull()
        assertThat(parentExternalFolderPath("/mnt/docs", "/mnt/docs/../private")).isNull()
        assertThat(parentExternalFolderPath("/mnt/docs", "/mnt/docs/a\\private")).isNull()
    }

    @Test
    fun `文本预览保留真实换行并严格识别无效UTF8和二进制`() {
        val text = "标题\n正文\t末尾\r\n"
        assertThat(decodeExternalTextPreview(text.toByteArray(), 100))
            .isEqualTo(ExternalTextPreview.Text(text))
        assertThat(decodeExternalTextPreview(byteArrayOf(0xC3.toByte(), 0x28), 100))
            .isEqualTo(ExternalTextPreview.Binary)
        assertThat(decodeExternalTextPreview(byteArrayOf(0, 65), 100))
            .isEqualTo(ExternalTextPreview.Binary)
        assertThat(decodeExternalTextPreview(byteArrayOf(1, 65), 100))
            .isEqualTo(ExternalTextPreview.Binary)
    }

    @Test
    fun `预览预算按字节限制且不会把超限数据当作完整文本`() {
        assertThat(decodeExternalTextPreview("中文".toByteArray(), 5))
            .isEqualTo(ExternalTextPreview.TooLarge)
        assertThat(decodeExternalTextPreview("中文".toByteArray(), 6))
            .isEqualTo(ExternalTextPreview.Text("中文"))
    }

    @Test
    fun `外部打开快照文件名去除目录并保留可用扩展名`() {
        assertThat(externalPreviewFileName("../../notes.txt")).isEqualTo("notes.txt")
        assertThat(externalPreviewFileName("..\\notes.txt")).isEqualTo("notes.txt")
        assertThat(externalPreviewFileName(".." )).isEqualTo("preview")
        assertThat(externalPreviewFileName("a\u0000b.pdf")).isEqualTo("a_b.pdf")
    }

    @Test
    fun `默认别名取第一个未占用的 external 序号`() {
        assertThat(nextGeneratedMountAlias(emptyList())).isEqualTo("external")
        assertThat(nextGeneratedMountAlias(listOf("external", "external2"))).isEqualTo("external3")
        assertThat(nextGeneratedMountAlias(listOf("docs"))).isEqualTo("external")
    }

    @Test
    fun `只有自动生成的默认别名会被目录名替换`() {
        assertThat(isGeneratedMountAlias("external")).isTrue()
        assertThat(isGeneratedMountAlias("external2")).isTrue()
        assertThat(isGeneratedMountAlias("external12")).isTrue()
        assertThat(isGeneratedMountAlias(" external3 ")).isTrue()
        assertThat(isGeneratedMountAlias("external1")).isFalse()
        assertThat(isGeneratedMountAlias("external-docs")).isFalse()
        assertThat(isGeneratedMountAlias("documents")).isFalse()
        assertThat(isGeneratedMountAlias("")).isFalse()
    }

    @Test
    fun `目录名转别名时规整大小写与空白并避开重名`() {
        assertThat(mountAliasFromFolderName("Documents", emptyList())).isEqualTo("documents")
        assertThat(mountAliasFromFolderName("My  Notes 2026", emptyList())).isEqualTo("my-notes-2026")
        assertThat(mountAliasFromFolderName("论文初稿", emptyList())).isEqualTo("论文初稿")
        assertThat(mountAliasFromFolderName("Documents", listOf("documents", "documents-2"))).isEqualTo("documents-3")
        assertThat(mountAliasFromFolderName("a".repeat(60), emptyList())).hasLength(40)
    }

    @Test
    fun `目录名不能作别名时不替换`() {
        assertThat(mountAliasFromFolderName("   ", emptyList())).isNull()
        assertThat(mountAliasFromFolderName("..", emptyList())).isNull()
        assertThat(mountAliasFromFolderName("mnt", emptyList())).isNull()
        assertThat(mountAliasFromFolderName("Skills", emptyList())).isNull()
        assertThat(mountAliasFromFolderName(".infinity", emptyList())).isNull()
    }

    @Test
    fun `真实路径目录名取末段且空路径视为未选`() {
        assertThat(pickedRealPathFolder("/storage/emulated/0/Documents"))
            .isEqualTo(PickedMountFolder("Documents", "/storage/emulated/0/Documents"))
        assertThat(pickedRealPathFolder("/storage/emulated/0/Music/")?.name).isEqualTo("Music")
        assertThat(pickedRealPathFolder("  ")).isNull()
    }

    @Test
    fun `外部存储授权位置转成可读路径而其它提供方不显示`() {
        val authority = "com.android.externalstorage.documents"
        assertThat(safTreeLocationLabel(authority, "primary:Documents/reports", "内部存储"))
            .isEqualTo("内部存储/Documents/reports")
        assertThat(safTreeLocationLabel(authority, "1A2B-3C4D:Music", "内部存储")).isEqualTo("1A2B-3C4D/Music")
        assertThat(safTreeLocationLabel(authority, "primary:", "内部存储")).isEqualTo("内部存储")
        assertThat(safTreeLocationLabel("com.termux.documents", "/data/data/com.termux/files/home", "内部存储")).isNull()
        assertThat(safTreeLocationLabel(authority, null, "内部存储")).isNull()
        assertThat(safTreeFallbackName("primary:Documents/reports")).isEqualTo("reports")
        assertThat(safTreeFallbackName("/data/data/com.termux/files/home")).isEqualTo("home")
        assertThat(safTreeFallbackName("primary:")).isNull()
    }

    @Test
    fun `资料库挂载行把真实路径和系统授权树转成可读位置`() {
        assertThat(mountSourceLocationLabel("real_path", "/storage/emulated/0/Documents/a b", "内部存储"))
            .isEqualTo("内部存储/Documents/a b")
        assertThat(mountSourceLocationLabel("real_path", "/storage/emulated/0", "内部存储")).isEqualTo("内部存储")
        assertThat(mountSourceLocationLabel("real_path", "/storage/1A2B-3C4D/Music", "内部存储"))
            .isEqualTo("/storage/1A2B-3C4D/Music")
        assertThat(mountSourceLocationLabel(
            "saf_tree",
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2F%E7%AC%94%E8%AE%B0%2Bx",
            "内部存储",
        )).isEqualTo("内部存储/Documents/笔记+x")
        assertThat(mountSourceLocationLabel("saf_tree", "content://com.termux.documents/tree/%2Fdata%2Fhome", "内部存储"))
            .isNull()
        assertThat(mountSourceLocationLabel("saf_tree", "not-a-uri", "内部存储")).isNull()
        assertThat(mountSourceLocationLabel("knowledge_base", "uuid", "内部存储")).isNull()
    }

    private fun mount(id: String, sessionId: String) = ExternalMount(
        id = id,
        alias = id,
        sourceType = "saf_tree",
        sourceId = "content://provider/tree/$id",
        sessionId = sessionId,
        createdAt = 0L,
    )
}
