package com.promenar.infinity.data.document.external

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.document.DocumentTextDecoding
import com.promenar.infinity.share.core.ShareIndexReceipt
import com.promenar.infinity.share.core.ShareIndexScheduler
import com.promenar.infinity.share.core.ShareWorkspaceFixture
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalDocumentLibrarySaverTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun document(text: String, name: String = "周报.md"): ExternalDocument {
        val bytes = text.toByteArray()
        val file = File(temporary.newFolder(), name).apply { writeBytes(bytes) }
        return ExternalDocument(
            id = java.util.UUID.randomUUID().toString(),
            displayName = name,
            file = file,
            sizeBytes = bytes.size.toLong(),
            sha256 = DocumentTextDecoding.sha256(bytes),
            text = text,
        )
    }

    @Test
    fun `存入所选文件夹并排入索引`() = runTest {
        val fixture = ShareWorkspaceFixture(temporary.newFolder("workspace"))
        val root = fixture.root("kb")
        val folder = fixture.folder(root.uuid, "/工作")
        val indexed = mutableListOf<String>()
        val saver = ExternalDocumentLibrarySaver(fixture.repo, ShareIndexScheduler { _, entry ->
            indexed += entry.uuid
            ShareIndexReceipt(taskId = "task-${entry.uuid}", fileUuid = entry.uuid)
        })

        val result = saver.save(document("# 周报"), root.uuid, folder.uuid) as ExternalLibrarySaveResult.Saved

        assertThat(result.path).isEqualTo("/工作/周报.md")
        assertThat(result.name).isEqualTo("周报.md")
        val stored = File(root.physicalRootPath, "工作/周报.md")
        assertThat(stored.readText()).isEqualTo("# 周报")
        assertThat(indexed).containsExactly(result.documentId)
    }

    @Test
    fun `同名同内容复用已有文件 同名不同内容另起名字`() = runTest {
        val fixture = ShareWorkspaceFixture(temporary.newFolder("workspace"))
        val root = fixture.root("kb")
        val saver = ExternalDocumentLibrarySaver(fixture.repo)

        val first = saver.save(document("一"), root.uuid, root.uuid) as ExternalLibrarySaveResult.Saved
        val again = saver.save(document("一"), root.uuid, root.uuid) as ExternalLibrarySaveResult.Saved
        val other = saver.save(document("二"), root.uuid, root.uuid) as ExternalLibrarySaveResult.Saved

        assertThat(first.path).isEqualTo("/周报.md")
        assertThat(again.documentId).isEqualTo(first.documentId)
        assertThat(other.path).isNotEqualTo(first.path)
        assertThat(other.path).endsWith(".md")
    }

    @Test
    fun `目标文件夹不存在时失败`() = runTest {
        val fixture = ShareWorkspaceFixture(temporary.newFolder("workspace"))
        val root = fixture.root("kb")
        val saver = ExternalDocumentLibrarySaver(fixture.repo)

        val result = saver.save(document("# 周报"), root.uuid, "missing-folder")

        assertThat(result).isInstanceOf(ExternalLibrarySaveResult.Failed::class.java)
    }
}
