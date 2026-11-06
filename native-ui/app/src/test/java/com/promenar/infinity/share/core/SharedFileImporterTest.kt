package com.promenar.infinity.share.core

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ShareRequest
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class SharedFileImporterTest {
    @Test
    fun `DOCX嗅探跳过条目时也限制实际解压量`() = runTest {
        for (expandedMiB in listOf(1, 65)) {
            val uri = Uri.parse("content://fixture/skipped-$expandedMiB.docx")
            val output = ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(output).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("padding.bin"))
                val block = ByteArray(64 * 1024)
                repeat(expandedMiB * 16) { zip.write(block) }
                zip.closeEntry()
                listOf("[Content_Types].xml", "word/document.xml").forEach { name ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write("<fixture/>".toByteArray())
                    zip.closeEntry()
                }
            }
            val bytes = output.toByteArray()
            val mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            val source = FakeSource(mapOf(uri to meta("fixture.docx", mime, bytes.size.toLong())), mapOf(uri to bytes))
            val repo = repository()
            val result = SharedFileImporter(source, repo).import(request(listOf(uri), mime), ROOT)
            if (expandedMiB == 1) assertThat(result.created).hasSize(1)
            else {
                assertThat(result.created).isEmpty()
                assertThat(result.rejected).hasSize(1)
                coVerify(exactly = 0) { repo.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
            }
        }
    }

    @Test
    fun `DOCX标志条目不能让后续解压量与条目数绕过预算`() = runTest {
        for ((paddingMiB, extras) in listOf(1 to 0, 0 to 2_046, 65 to 0, 0 to 2_047)) {
            val uri = Uri.parse("content://fixture/markers-first-$paddingMiB-$extras.docx")
            val output = ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(output).use { zip ->
                listOf("[Content_Types].xml", "word/document.xml").forEach { name ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write("<fixture/>".toByteArray())
                    zip.closeEntry()
                }
                if (paddingMiB > 0) {
                    zip.putNextEntry(java.util.zip.ZipEntry("padding.bin"))
                    val block = ByteArray(64 * 1024)
                    repeat(paddingMiB * 16) { zip.write(block) }
                    zip.closeEntry()
                }
                repeat(extras) { index ->
                    zip.putNextEntry(java.util.zip.ZipEntry("extra-$index.bin"))
                    zip.closeEntry()
                }
            }
            val bytes = output.toByteArray()
            val mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            val source = FakeSource(mapOf(uri to meta("fixture.docx", mime, bytes.size.toLong())), mapOf(uri to bytes))
            val repo = repository()
            val result = SharedFileImporter(source, repo).import(request(listOf(uri), mime), ROOT)
            if (paddingMiB == 1 || extras == 2_046) assertThat(result.created).hasSize(1)
            else {
                assertThat(result.created).isEmpty()
                assertThat(result.rejected).hasSize(1)
                coVerify(exactly = 0) { repo.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
            }
        }
    }

    @Test
    fun `inspect直接呈现staging预检拒绝而不伪装成可导入`() = runTest {
        val uri = Uri.parse("content://fixture/unreadable.pdf")
        val source = FakeSource(
            metadata = mapOf(uri to meta("unreadable.pdf", "application/pdf", 10)),
            bytes = emptyMap(),
            preflightReasons = mapOf(uri to ShareRejectReason.ReadFailed),
        )

        val item = SharedFileImporter(source, repository()).inspect(
            request(listOf(uri), "application/pdf")
        ).single()

        assertThat(item.status).isEqualTo(ShareImportStatus.Rejected)
        assertThat(item.reason).isEqualTo(ShareRejectReason.ReadFailed)
    }

    @Test
    fun `部分成功会保留逐项拒绝且只调度已创建文件`() = runTest {
        val good = Uri.parse("content://fixture/good.txt")
        val denied = Uri.parse("content://fixture/denied.txt")
        val source = FakeSource(
            metadata = mapOf(good to meta("good.txt", "text/plain", 5)),
            bytes = mapOf(good to "hello".toByteArray()),
            denied = setOf(denied),
        )
        val scheduled = mutableListOf<FileEntry>()
        val importer = SharedFileImporter(source, repository(), ShareIndexScheduler { _, entry ->
            scheduled += entry
            ShareIndexReceipt("task-${entry.uuid}", entry.uuid)
        })

        val result = importer.import(request(listOf(good, denied)), ROOT)

        assertThat(result.created.map { it.displayName }).containsExactly("good.txt")
        assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.PermissionDenied)
        assertThat(scheduled.map { it.name }).containsExactly("good.txt")
    }

    @Test
    fun `内容嗅探不匹配与空文件均拒绝且不创建可见结果`() = runTest {
        val fakePdf = Uri.parse("content://fixture/fake.pdf")
        val empty = Uri.parse("content://fixture/empty.txt")
        val source = FakeSource(
            metadata = mapOf(
                fakePdf to meta("fake.pdf", "application/pdf", 4),
                empty to meta("empty.txt", "text/plain", 0),
            ),
            bytes = mapOf(fakePdf to "nope".toByteArray(), empty to byteArrayOf()),
        )
        val importer = SharedFileImporter(source, repository())

        val pdfResult = importer.import(request(listOf(fakePdf), "application/pdf"), ROOT)
        val emptyResult = importer.import(request(listOf(empty)), ROOT)

        assertThat(pdfResult.rejected.single().reason).isEqualTo(ShareRejectReason.MimeMismatch)
        assertThat(emptyResult.rejected.single().reason).isEqualTo(ShareRejectReason.EmptyFile)
    }

    @Test
    fun `采样上限落在中文或emoji中间时继续读取并接受完整UTF8`() = runTest {
        val chinese = Uri.parse("content://fixture/chinese.txt")
        val emoji = Uri.parse("content://fixture/emoji.txt")
        val chineseBytes = ("a".repeat(8_191) + "中文").toByteArray(Charsets.UTF_8)
        val emojiBytes = ("b".repeat(8_190) + "😀结尾").toByteArray(Charsets.UTF_8)
        val source = FakeSource(
            metadata = mapOf(
                chinese to meta("chinese.txt", "text/plain", chineseBytes.size.toLong()),
                emoji to meta("emoji.txt", "text/plain", emojiBytes.size.toLong()),
            ),
            bytes = mapOf(chinese to chineseBytes, emoji to emojiBytes),
        )

        val result = SharedFileImporter(source, repository()).import(
            request(listOf(chinese, emoji)),
            ROOT,
        )

        assertThat(result.created.map { it.displayName })
            .containsExactly("chinese.txt", "emoji.txt")
            .inOrder()
    }

    @Test
    fun `每次只返回一个字节的短读不会把完整UTF8误判为非法`() = runTest {
        val uri = Uri.parse("content://fixture/short-read.txt")
        val bytes = "中文与 emoji 😀".toByteArray(Charsets.UTF_8)
        val source = FakeSource(
            metadata = mapOf(uri to meta("short-read.txt", "text/plain", bytes.size.toLong())),
            bytes = mapOf(uri to bytes),
            maxReadBytes = 1,
        )

        val result = SharedFileImporter(source, repository()).import(request(listOf(uri)), ROOT)

        assertThat(result.created.single().displayName).isEqualTo("short-read.txt")
    }

    @Test
    fun `零长度批量读取通过单字节回退完成嗅探和文本复制`() = runTest {
        val uri = Uri.parse("content://fixture/zero-read.txt")
        val bytes = "中文😀".toByteArray(Charsets.UTF_8)
        val source = FakeSource(
            metadata = mapOf(uri to meta("zero-read.txt", "text/plain", bytes.size.toLong())),
            bytes = mapOf(uri to bytes),
            maxReadBytes = 0,
        )
        val result = SharedFileImporter(source, repository()).import(request(listOf(uri)), ROOT)
        assertThat(result.created.single().displayName).isEqualTo("zero-read.txt")
    }

    @Test
    fun `真EOF留下不完整UTF8时仍拒绝`() = runTest {
        val uri = Uri.parse("content://fixture/truncated.txt")
        val truncated = byteArrayOf(0xE4.toByte())
        val source = FakeSource(
            metadata = mapOf(uri to meta("truncated.txt", "text/plain", truncated.size.toLong())),
            bytes = mapOf(uri to truncated),
        )

        val result = SharedFileImporter(source, repository()).import(request(listOf(uri)), ROOT)

        assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.MimeMismatch)
    }

    @Test
    fun `采样区之后出现非法UTF8时由完整文件校验拒绝`() = runTest {
        val uri = Uri.parse("content://fixture/late-invalid.txt")
        val bytes = ByteArray(8_193) { 'a'.code.toByte() }.also {
            it[it.lastIndex] = 0xFF.toByte()
        }
        val source = FakeSource(
            metadata = mapOf(uri to meta("late-invalid.txt", "text/plain", bytes.size.toLong())),
            bytes = mapOf(uri to bytes),
        )

        val result = SharedFileImporter(source, repository()).import(request(listOf(uri)), ROOT)

        assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.MimeMismatch)
    }

    @Test
    fun `非法UTF8 NUL PDF与ZIP魔数不能伪装成文本`() = runTest {
        val fixtures = linkedMapOf(
            Uri.parse("content://fixture/invalid.txt") to byteArrayOf(0x61, 0xff.toByte(), 0x28),
            Uri.parse("content://fixture/embedded-nul.txt") to byteArrayOf(0x61, 0x00, 0x62),
            Uri.parse("content://fixture/pdf.txt") to "%PDF-1.7".toByteArray(),
            Uri.parse("content://fixture/zip.txt") to byteArrayOf(0x50, 0x4B, 0x03, 0x04),
        )
        val source = FakeSource(
            metadata = fixtures.mapValues { (uri, bytes) ->
                meta(uri.lastPathSegment!!, "text/plain", bytes.size.toLong())
            },
            bytes = fixtures,
        )

        val result = SharedFileImporter(source, repository()).import(request(fixtures.keys.toList()), ROOT)

        assertThat(result.rejected).hasSize(fixtures.size)
        assertThat(result.rejected.map { it.reason }).containsExactlyElementsIn(
            List(fixtures.size) { ShareRejectReason.MimeMismatch },
        )
    }

    @Test
    fun `分享导入GBK文本保留原字节`() = runTest {
        val uri = Uri.parse("content://fixture/gbk.txt")
        val bytes = "虚构中文资料".toByteArray(java.nio.charset.Charset.forName("GBK"))
        val source = FakeSource(metadata = mapOf(uri to meta("gbk.txt", "text/plain", bytes.size.toLong())), bytes = mapOf(uri to bytes))
        val result = SharedFileImporter(source, repository()).import(request(listOf(uri)), ROOT)
        assertThat(result.created).hasSize(1)
    }

    @Test
    fun `完整HTML以UTF8离线文件导入并进入索引调度`() = runTest {
        val uri = Uri.parse("content://fixture/article.html")
        val bytes = "<!doctype html><html><body><p>中文 &amp; emoji 😀</p></body></html>"
            .toByteArray(Charsets.UTF_8)
        val source = FakeSource(
            metadata = mapOf(uri to meta("article.html", "text/html", bytes.size.toLong())),
            bytes = mapOf(uri to bytes),
        )
        val scheduled = mutableListOf<FileEntry>()
        val importer = SharedFileImporter(source, repository(), ShareIndexScheduler { _, entry ->
            scheduled += entry
            ShareIndexReceipt("task-html", entry.uuid)
        })

        val result = importer.import(request(listOf(uri), "text/html"), ROOT)

        assertThat(result.created.single().mimeType).isEqualTo("text/html")
        assertThat(scheduled.single().name).isEqualTo("article.html")
    }

    @Test
    fun `单项和批次一百MiB边界按元数据提前拒绝`() = runTest {
        val oversized = Uri.parse("content://fixture/oversized.txt")
        val first = Uri.parse("content://fixture/first.txt")
        val second = Uri.parse("content://fixture/second.txt")
        val source = FakeSource(
            metadata = mapOf(
                oversized to meta("oversized.txt", "text/plain", SharedFileImporter.MAX_ITEM_BYTES + 1),
                first to meta("first.txt", "text/plain", SharedFileImporter.MAX_BATCH_BYTES),
                second to meta("second.txt", "text/plain", 1),
            ),
            bytes = mapOf(first to "a".toByteArray(), second to "b".toByteArray()),
        )
        val importer = SharedFileImporter(
            source,
            repository(reportedSizes = mapOf("first.txt" to SharedFileImporter.MAX_BATCH_BYTES)),
        )

        val itemResult = importer.import(request(listOf(oversized)), ROOT)
        val batchResult = importer.import(request(listOf(first, second)), ROOT)

        assertThat(itemResult.rejected.single().reason).isEqualTo(ShareRejectReason.ItemTooLarge)
        assertThat(batchResult.items.last().reason).isEqualTo(ShareRejectReason.BatchTooLarge)
    }

    @Test
    fun `已有同名文件使用稳定编号且不覆盖`() = runTest {
        val uri = Uri.parse("content://fixture/report.md")
        val source = FakeSource(
            metadata = mapOf(uri to meta("report.md", "text/markdown", 4)),
            bytes = mapOf(uri to "body".toByteArray()),
        )
        val repository = repository(existingPaths = setOf("/report.md", "/report (1).md"))

        val result = SharedFileImporter(source, repository).import(
            request(listOf(uri), "text/markdown"),
            ROOT,
        )

        assertThat(result.created.single().displayName).isEqualTo("report (2).md")
    }

    @Test
    fun `指定文件夹时使用父目录路径创建并调度文件引用`() = runTest {
        val uri = Uri.parse("content://fixture/note.txt")
        val folder = folder("docs", "/docs")
        val source = FakeSource(
            metadata = mapOf(uri to meta("note.txt", "text/plain", 4)),
            bytes = mapOf(uri to "body".toByteArray()),
        )
        val repository = repository(folder = folder)
        val scheduled = mutableListOf<FileEntry>()
        val importer = SharedFileImporter(source, repository, ShareIndexScheduler { root, entry ->
            assertThat(root).isEqualTo(ROOT)
            scheduled += entry
            ShareIndexReceipt("task-${entry.uuid}", entry.uuid)
        })

        val result = importer.import(
            request = request(listOf(uri)),
            workspaceRootUuid = ROOT,
            parentUuid = folder.uuid,
        )

        assertThat(result.created.single().created?.parentUuid).isEqualTo(folder.uuid)
        assertThat(result.created.single().created?.materializedPath).isEqualTo("/docs/note.txt")
        assertThat(scheduled.single().materializedPath).isEqualTo("/docs/note.txt")
        coVerify(exactly = 1) {
            repository.createFileInWorkspaceStreaming(
                workspaceRootUuid = ROOT,
                uuid = any(),
                name = "note.txt",
                mimeType = "text/plain",
                parentUuid = folder.uuid,
                materializedPath = "/docs/note.txt",
                maxBytes = any(),
                writer = any(),
            )
        }
    }

    @Test
    fun `索引排队失败会回滚指定文件夹内本轮新建文件`() = runTest {
        val uri = Uri.parse("content://fixture/rollback.txt")
        val folder = folder("docs", "/docs")
        val source = FakeSource(
            metadata = mapOf(uri to meta("rollback.txt", "text/plain", 4)),
            bytes = mapOf(uri to "body".toByteArray()),
        )
        val repository = repository(folder = folder)
        val importer = SharedFileImporter(source, repository, ShareIndexScheduler { _, _ ->
            throw IOException("queue unavailable")
        })

        val result = importer.import(
            request = request(listOf(uri)),
            workspaceRootUuid = ROOT,
            parentUuid = folder.uuid,
        )

        assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.IndexScheduleFailed)
        coVerify(exactly = 1) { repository.rollbackCreatedEntry(ROOT, "entry-rollback.txt") }
    }

    @Test
    fun `索引失败成功回滚后释放预算让后续文件可导入`() = runTest {
        val first = Uri.parse("content://fixture/failed.txt")
        val second = Uri.parse("content://fixture/next.txt")
        val firstSize = 60L * 1024 * 1024
        val secondSize = 50L * 1024 * 1024
        val source = FakeSource(
            metadata = mapOf(first to meta("failed.txt", "text/plain", firstSize), second to meta("next.txt", "text/plain", secondSize)),
            bytes = mapOf(first to "first".toByteArray(), second to "next".toByteArray()),
        )
        val repo = repository(reportedSizes = mapOf("failed.txt" to firstSize, "next.txt" to secondSize))
        val importer = SharedFileImporter(source, repo, ShareIndexScheduler { _, entry ->
            if (entry.name == "failed.txt") throw IOException("synthetic queue failure")
            ShareIndexReceipt("task-${entry.uuid}", entry.uuid)
        })
        val result = importer.import(request(listOf(first, second)), ROOT)
        assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.IndexScheduleFailed)
        assertThat(result.created.single().displayName).isEqualTo("next.txt")
        coVerify(exactly = 1) { repo.rollbackCreatedEntry(ROOT, "entry-failed.txt") }
    }

    @Test
    fun `索引失败且回滚未完成时保留预算不把残留文件忽略`() = runTest {
        val first = Uri.parse("content://fixture/retained.txt")
        val second = Uri.parse("content://fixture/next.txt")
        val size = 60L * 1024 * 1024
        val source = FakeSource(
            metadata = mapOf(first to meta("retained.txt", "text/plain", size), second to meta("next.txt", "text/plain", size)),
            bytes = mapOf(first to "first".toByteArray(), second to "next".toByteArray()),
        )
        val repo = repository(reportedSizes = mapOf("retained.txt" to size))
        coEvery { repo.rollbackCreatedEntry(ROOT, "entry-retained.txt") } throws IOException("synthetic rollback failure")
        val result = SharedFileImporter(source, repo, ShareIndexScheduler { _, _ ->
            throw IOException("synthetic scheduling failure")
        }).import(request(listOf(first, second)), ROOT)
        assertThat(result.created).isEmpty()
        assertThat(result.rejected.map { it.reason }).containsExactly(ShareRejectReason.IndexScheduleFailed, ShareRejectReason.BatchTooLarge).inOrder()
    }

    @Test
    fun `创建确认失败只生成一条拒绝结果并回滚`() = runTest {
        val uri = Uri.parse("content://fixture/confirm.txt")
        val source = FakeSource(mapOf(uri to meta("confirm.txt", "text/plain", 4)), mapOf(uri to "body".toByteArray()))
        val repo = repository()
        coEvery { repo.confirmCreatedEntry(ROOT, "entry-confirm.txt") } throws IOException("synthetic confirmation failure")
        val result = SharedFileImporter(source, repo, ShareIndexScheduler { _, entry ->
            ShareIndexReceipt("task-${entry.uuid}", entry.uuid)
        }).import(request(listOf(uri)), ROOT)
        assertThat(result.created).isEmpty()
        assertThat(result.rejected).hasSize(1)
        coVerify(exactly = 1) { repo.rollbackCreatedEntry(ROOT, "entry-confirm.txt") }
    }

    @Test
    fun `不存在或非目录的导入目标会在读取前被拒绝`() = runTest {
        val uri = Uri.parse("content://fixture/note.txt")
        val source = FakeSource(
            metadata = mapOf(uri to meta("note.txt", "text/plain", 4)),
            bytes = mapOf(uri to "body".toByteArray()),
        )
        val repository = repository()

        val result = SharedFileImporter(source, repository).import(
            request = request(listOf(uri)),
            workspaceRootUuid = ROOT,
            parentUuid = "missing-folder",
        )

        assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.TargetRequired)
        coVerify(exactly = 0) { repository.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `ack重放时按staged哈希复用已创建文件而不重复写入`() = runTest {
        val uri = Uri.parse("content://fixture/replay.txt")
        val source = FakeSource(
            metadata = mapOf(uri to meta("replay.txt", "text/plain", 4)),
            bytes = mapOf(uri to "body".toByteArray()),
            hashes = mapOf(uri to "hash"),
        )
        val repository = repository(existingPaths = setOf("/replay.txt"))
        val result = SharedFileImporter(source, repository).import(request(listOf(uri)), ROOT)

        assertThat(result.created.single().displayName).isEqualTo("replay.txt")
        coVerify(exactly = 0) {
            repository.createFileInWorkspaceStreaming(
                workspaceRootUuid = any(),
                uuid = any(),
                name = any(),
                mimeType = any(),
                parentUuid = any(),
                materializedPath = any(),
                maxBytes = any(),
                writer = any(),
            )
        }
    }

    @Test
    fun `复用同SHA既有文件时调度失败绝不删除用户文件`() = runTest {
        val uri = Uri.parse("content://fixture/existing.txt")
        val source = FakeSource(
            metadata = mapOf(uri to meta("existing.txt", "text/plain", 4)),
            bytes = mapOf(uri to "body".toByteArray()),
            hashes = mapOf(uri to "hash"),
        )
        val repository = repository(existingPaths = setOf("/existing.txt"))
        val importer = SharedFileImporter(source, repository, ShareIndexScheduler { _, _ ->
            throw java.io.IOException("schedule failed")
        })

        val result = importer.import(request(listOf(uri)), ROOT)

        assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.IndexScheduleFailed)
        coVerify(exactly = 0) { repository.permanentDelete(any(), any()) }
    }

    @Test
    fun `复用同SHA既有文件时取消必须传播且绝不删除用户文件`() = runTest {
        val uri = Uri.parse("content://fixture/cancel.txt")
        val source = FakeSource(
            metadata = mapOf(uri to meta("cancel.txt", "text/plain", 4)),
            bytes = mapOf(uri to "body".toByteArray()),
            hashes = mapOf(uri to "hash"),
        )
        val repository = repository(existingPaths = setOf("/cancel.txt"))
        val cancellation = CancellationException("cancelled")
        val importer = SharedFileImporter(source, repository, ShareIndexScheduler { _, _ ->
            throw cancellation
        })

        val failure = try {
            importer.import(request(listOf(uri)), ROOT)
            null
        } catch (caught: CancellationException) {
            caught
        }

        assertThat(failure).isNotNull()
        assertThat(failure?.message).isEqualTo(cancellation.message)
        assertThat(failure === cancellation || failure?.cause === cancellation).isTrue()
        coVerify(exactly = 0) { repository.permanentDelete(any(), any()) }
    }

    @Test
    fun `本轮新建文件遇到调度SecurityException先删除再映射权限拒绝`() = runTest {
        val uri = Uri.parse("content://fixture/new-security.txt")
        val source = FakeSource(
            metadata = mapOf(uri to meta("new-security.txt", "text/plain", 4)),
            bytes = mapOf(uri to "body".toByteArray()),
        )
        val repository = repository()
        val importer = SharedFileImporter(source, repository, ShareIndexScheduler { _, _ ->
            throw SecurityException("scheduler denied")
        })

        val result = importer.import(request(listOf(uri)), ROOT)

        assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.PermissionDenied)
        coVerify(exactly = 1) { repository.rollbackCreatedEntry(ROOT, "entry-new-security.txt") }
    }

    @Test
    fun `复用既有文件遇到调度SecurityException不删除并映射权限拒绝`() = runTest {
        val uri = Uri.parse("content://fixture/existing-security.txt")
        val source = FakeSource(
            metadata = mapOf(uri to meta("existing-security.txt", "text/plain", 4)),
            bytes = mapOf(uri to "body".toByteArray()),
            hashes = mapOf(uri to "hash"),
        )
        val repository = repository(existingPaths = setOf("/existing-security.txt"))
        val importer = SharedFileImporter(source, repository, ShareIndexScheduler { _, _ ->
            throw SecurityException("scheduler denied")
        })

        val result = importer.import(request(listOf(uri)), ROOT)

        assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.PermissionDenied)
        coVerify(exactly = 0) { repository.permanentDelete(any(), any()) }
    }

    @Test
    fun `任意二进制格式与通配声明原样保存且不排入索引`() = runTest {
        val names = listOf("data.sqlite", "book.epub", "photo.png", "raw.bin")
        val mimes = listOf("application/vnd.sqlite3", "application/epub+zip", "image/png", "application/octet-stream")
        val bytes = byteArrayOf(0, 1, 0xff.toByte(), 3)
        for (declared in listOf("*/*", "application/*", "", "application/octet-stream")) {
            for ((name, mime) in names.zip(mimes)) {
                val uri = Uri.parse("content://fixture/$name")
                val scheduler = mockk<ShareIndexScheduler>()
                val source = FakeSource(mapOf(uri to meta(name, mime, bytes.size.toLong())), mapOf(uri to bytes))
                val importer = SharedFileImporter(source, repository(), scheduler)
                val request = request(listOf(uri), declared)
                val result = importer.import(request, ROOT)
                assertThat(result.created).hasSize(1)
                assertThat(result.created.single().parseable).isFalse()
                assertThat(importer.inspect(request).single().parseable).isFalse()
                assertThat(result.created.single().indexTaskId).isNull()
                assertThat(result.created.single().indexStatus).isNull()
                coVerify(exactly = 0) { scheduler.schedule(any(), any()) }
            }
        }
    }

    @Test
    fun `未知大小二进制写入限额与目标IO失败保留逐项错误类型`() = runTest {
        val uri = Uri.parse("content://fixture/data.sqlite")
        val bytes = byteArrayOf(0, 1, 2)
        val source = FakeSource(mapOf(uri to SharedContentMetadata("data.sqlite", "application/octet-stream", null)), mapOf(uri to bytes))
        for (tooLarge in listOf(true, false)) {
            val repo = repository()
            coEvery { repo.createFileInWorkspaceStreaming(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                arg<(java.io.OutputStream) -> Unit>(7)(object : java.io.OutputStream() {
                    override fun write(value: Int) {
                        if (tooLarge) throw com.promenar.infinity.data.repository.WorkspaceFileTooLargeException(SharedFileImporter.MAX_ITEM_BYTES)
                        else throw IOException("目标磁盘写入失败")
                    }
                })
                throw AssertionError("写入应失败")
            }
            val item = SharedFileImporter(source, repo).import(request(listOf(uri), "*/*"), ROOT).rejected.single()
            assertThat(item.reason).isEqualTo(if (tooLarge) ShareRejectReason.ItemTooLarge else ShareRejectReason.WriteFailed)
        }
    }

    @Test
    fun `通用元数据依扩展名识别七种格式并调度索引`() = runTest {
        val docx = ByteArrayOutputStream().also { output ->
            java.util.zip.ZipOutputStream(output).use { zip ->
                for (name in listOf("[Content_Types].xml", "word/document.xml")) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write("<xml/>".toByteArray()); zip.closeEntry()
                }
            }
        }.toByteArray()
        val fixtures = listOf("a.TXT" to "文本".toByteArray(), "a.md" to "# 标题".toByteArray(),
            "a.csv" to "a,b".toByteArray(), "a.html" to "<p>文本</p>".toByteArray(), "a.json" to "{}".toByteArray(),
            "a.pdf" to "%PDF-1.7".toByteArray(), "a.docx" to docx)
        for ((name, bytes) in fixtures) {
            val uri = Uri.parse("content://fixture/$name")
            val source = FakeSource(mapOf(uri to meta(name, "application/octet-stream", bytes.size.toLong())), mapOf(uri to bytes))
            val scheduler = mockk<ShareIndexScheduler>()
            coEvery { scheduler.schedule(any(), any()) } coAnswers { ShareIndexReceipt("task", secondArg<FileEntry>().uuid) }
            val importer = SharedFileImporter(source, repository(), scheduler)
            val request = request(listOf(uri), "*/*")
            assertThat(importer.inspect(request).single().parseable).isTrue()
            val result = importer.import(request, ROOT)
            assertThat(result.created.single().parseable).isTrue()
            assertThat(result.created.single().indexTaskId).isEqualTo("task")
            coVerify(exactly = 1) { scheduler.schedule(ROOT, any()) }
        }
        assertThat(SharedFileImporter.isParseable(" APPLICATION/PDF; charset=x ", "any.bin")).isTrue()
        assertThat(SharedFileImporter.isParseable("application/octet-stream", "asset.sqlite")).isFalse()
    }

    @Test
    fun `扩展名要求内容校验且声明的不同可解析族被拒绝`() = runTest {
        val cases = listOf(
            Triple("report.pdf", "application/octet-stream", "*/*"),
            Triple("report.docx", "application/octet-stream", "application/octet-stream"),
            Triple("report.txt", "application/octet-stream", "*/*"),
            Triple("report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/pdf"),
        )
        for ((name, resolved, declared) in cases) {
            val uri = Uri.parse("content://fixture/$name")
            val bytes = byteArrayOf(0, 1, 2, 3)
            val source = FakeSource(mapOf(uri to meta(name, resolved, 4)), mapOf(uri to bytes))
            val result = SharedFileImporter(source, repository()).import(request(listOf(uri), declared), ROOT)
            assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.MimeMismatch)
        }
    }

    private fun repository(
        existingPaths: Set<String> = emptySet(),
        reportedSizes: Map<String, Long> = emptyMap(),
        folder: FileEntry? = null,
    ): IWorkspaceRepository {
        val repository = mockk<IWorkspaceRepository>()
        coEvery { repository.getByUuid(ROOT, any()) } answers {
            folder?.takeIf { it.uuid == secondArg<String>() }
        }
        coEvery { repository.getByMaterializedPath(ROOT, any()) } answers {
            val path = secondArg<String>()
            if (path in existingPaths) entry(path.substringAfterLast('/'), 1, materializedPath = path) else null
        }
        coEvery {
            repository.createFileInWorkspaceStreaming(
                workspaceRootUuid = ROOT,
                uuid = any(),
                name = any(),
                mimeType = any(),
                parentUuid = any(),
                materializedPath = any(),
                maxBytes = any(),
                writer = any(),
            )
        } coAnswers {
            val name = arg<String>(2)
            val parentUuid = arg<String?>(4)
            val materializedPath = arg<String>(5)
            val output = ByteArrayOutputStream()
            arg<(java.io.OutputStream) -> Unit>(7)(output)
            entry(
                name = name,
                size = reportedSizes[name] ?: output.size().toLong(),
                parentUuid = parentUuid,
                materializedPath = materializedPath,
            )
        }
        coJustRun { repository.rollbackCreatedEntry(any(), any()) }
        coJustRun { repository.confirmCreatedEntry(any(), any()) }
        return repository
    }

    private fun request(uris: List<Uri>, mime: String = "text/plain") = ShareRequest(
        uris = uris,
        mimeType = mime,
        fingerprint = "0".repeat(64),
        canonicalSizeBytes = 1,
        requestId = "request",
    )

    private fun entry(
        name: String,
        size: Long,
        parentUuid: String? = ROOT,
        materializedPath: String = "/$name",
    ) = FileEntry(
        uuid = "entry-$name",
        workspaceRootUuid = ROOT,
        parentUuid = parentUuid,
        name = name,
        hash = "hash",
        mimeType = if (name.endsWith(".md")) "text/markdown" else "text/plain",
        sizeBytes = size,
        physicalRootPath = "/workspace",
        materializedPath = materializedPath,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun folder(name: String, path: String) = FileEntry(
        uuid = "folder-$name",
        workspaceRootUuid = ROOT,
        parentUuid = ROOT,
        name = name,
        hash = "",
        isDirectory = true,
        physicalRootPath = "/workspace",
        materializedPath = path,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun meta(name: String, mime: String, size: Long) =
        SharedContentMetadata(name, mime, size)

    private class FakeSource(
        private val metadata: Map<Uri, SharedContentMetadata>,
        private val bytes: Map<Uri, ByteArray>,
        private val denied: Set<Uri> = emptySet(),
        private val hashes: Map<Uri, String> = emptyMap(),
        private val preflightReasons: Map<Uri, ShareRejectReason> = emptyMap(),
        private val maxReadBytes: Int? = null,
    ) : SharedContentSource {
        override fun metadata(uri: Uri): SharedContentMetadata {
            if (uri in denied) throw SecurityException("denied")
            return checkNotNull(metadata[uri])
        }

        override fun open(uri: Uri): InputStream {
            val delegate = ByteArrayInputStream(checkNotNull(bytes[uri]))
            val limit = maxReadBytes ?: return delegate
            return object : InputStream() {
                override fun read(): Int = delegate.read()
                override fun read(target: ByteArray, offset: Int, length: Int): Int =
                    delegate.read(target, offset, minOf(length, limit))
            }
        }
        override fun preflightReason(uri: Uri): ShareRejectReason? = preflightReasons[uri]
        override fun contentSha256(uri: Uri): String? = hashes[uri]
    }

    private companion object {
        const val ROOT = "workspace-root"
    }
}
