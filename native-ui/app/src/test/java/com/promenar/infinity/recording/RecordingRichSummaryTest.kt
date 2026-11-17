package com.promenar.infinity.recording

import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.backup.BackupStartupState
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.export.SessionExportFormat
import com.promenar.infinity.domain.repository.WriteResult
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class RecordingRichSummaryTest {
    private val fence = "~~~".replace('~', '`')
    private val chart = """{"tooltip":{},"series":[{"type":"bar","data":[2,3]}]}"""
    private val rich = "${fence}echarts\n$chart\n$fence\n\n" +
        "${fence}mermaid\nflowchart TD\nA --> B[Very lengthy English technical labels]\n$fence\n\n" +
        "\$\$\\mathrm{LengthyEnglishEquation} = x + y\$\$"

    @Test fun 富格式中的代码公式与链接目标不干扰语言判定() {
        assertTrue(recordingLanguageMatches(RecordingLanguage.Chinese, "会议结论\n\n$rich\n\n[核对记录](https://example.org/LongEnglishUrl)\n`veryLongCode`"))
        assertTrue(recordingLanguageMatches(RecordingLanguage.Japanese, "会議の概要です\n\n$rich"))
        assertFalse(recordingLanguageMatches(RecordingLanguage.Chinese, "This is an English recording summary."))
        assertTrue(recordingLanguageMatches(RecordingLanguage.English, "`EnglishOnly`"))
    }

    @Test fun 只将非法图表围栏降级并闭合最后的围栏() {
        val valid = "${fence}echarts\n$chart\n$fence"
        assertEquals(valid, sanitizeRecordingRichMarkdown(valid))
        assertEquals("${fence}text\n{\"series\":[],}\n$fence", sanitizeRecordingRichMarkdown("${fence}echarts\n{\"series\":[],}"))
        assertEquals("${fence}text\n[]\n$fence", sanitizeRecordingRichMarkdown("${fence}echarts\n[]\n$fence"))
        assertEquals("${fence}text\n{\"series\":invalid}\n$fence", sanitizeRecordingRichMarkdown("${fence}echarts\n{\"series\":invalid}\n$fence"))
        assertEquals("甲\n${fence}mermaid\nflowchart TD\n$fence", sanitizeRecordingRichMarkdown("甲\n${fence}mermaid\nflowchart TD"))
    }

    @Test fun 解析仅移除文档外层标题并保留围栏内文字() {
        val organized = "$rich\n\n${fence}text\n## 原始转写\n$fence\n\n正文结尾"
        val full = "\n# 编辑后标题\n\n## 整理稿 \n\n$organized\n\n## 原始转写 \t\n\n- 原文"
        assertEquals(ParsedRecordingMarkdown("编辑后标题", organized), parseRecordingMarkdown(full.replace("\n", "\r\n"), "旧标题"))
        assertEquals(ParsedRecordingMarkdown("旧标题", "正文"), parseRecordingMarkdown("#  \n\n正文", "旧标题"))
        assertEquals(ParsedRecordingMarkdown("旧标题", "无标题正文"), parseRecordingMarkdown("无标题正文", "旧标题"))
    }

    @Test fun 外部编辑保存后可基于资料库最新版本重新发布() = runBlocking<Unit> {
        val root = Files.createTempDirectory("recording-rich-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val app = mockk<InfinityApplication>(relaxed = true)
            val context = ApplicationProvider.getApplicationContext<Application>()
            every { app.filesDir } returns root
            every { app.noBackupFilesDir } returns root
            every { app.assets } returns context.assets
            every { app.getSharedPreferences(any(), any()) } answers { context.getSharedPreferences(root.name + firstArg<String>(), 0) }
            every { app.startupState } returns MutableStateFlow(BackupStartupState.Ready)
            val session = RecordingSession(UUID.randomUUID().toString(), 1, "原题", phase = RecordingPhase.Finished,
                mode = RecordingMode.Raw, segments = listOf(RecordingSegment(0, 100, "原始内容")),
                organized = "旧稿", documentHash = "旧版本")
            val store = RecordingStore(app)
            store.write(session)
            val controller = RecordingController(app, scope, organizationEnabled = false,
                publishDocument = { _, _ -> RecordingDocument("/录音.md", "旧版本") })
            withTimeout(5000) { controller.state.first { it.ready } }
            controller.syncFromLibraryDocument(session.id, "# 正文标题\n\n## 整理稿\n\n新稿\n\n## 原始转写\n忽略", "外部版本")
            controller.syncFromLibraryDocument(session.id, "# 正文标题\n\n## 整理稿\n\n新稿", "外部版本", "重命名")
            val updated = controller.state.value.sessions.single()
            assertEquals("重命名", updated.title)
            assertEquals("新稿", updated.organized)
            assertEquals("外部版本", updated.documentHash)
            assertNull(updated.pendingDocumentHash)
            assertTrue(updated.manualEdit)
            assertEquals(updated.segments.size, updated.organizedThrough)
            assertEquals(updated, store.restore().single())

            val workspaceRoot = mockk<FileEntry> { every { uuid } returns "知识库" }
            coEvery { app.globalKnowledgeWorkspaceProvisioner.ensureRoot() } returns workspaceRoot
            val entry = mockk<FileEntry> {
                every { hash } returns "当前资料库版本"
                every { uuid } returns session.id
                every { materializedPath } returns "/录音.md"
            }
            coEvery { app.workspaceRepository.getByUuid("知识库", session.id) } returns entry
            coEvery { app.fileOperationRepository.writeFileAtomicExact("知识库", session.id, any(), "recording", "当前资料库版本") } answers {
                WriteResult.Success(com.promenar.infinity.infra.util.Sha256Utils.hash(thirdArg<String>().toByteArray()))
            }
            var prepared: Pair<String?, String>? = null
            val published = RecordingLibrary(app).publish(updated.copy(organized = "再次发布")) { base, target -> prepared = base to target }
            assertEquals("当前资料库版本" to published.hash, prepared)
            assertEquals("/录音.md", published.path)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            root.deleteRecursively()
        }
    }

    @Test fun 导出范围和格式估算保持单文件() {
        val assets = mockk<AssetManager>()
        every { assets.open(any()) } answers { File("app/src/main/assets", firstArg<String>()).inputStream() }
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.assets } returns assets
        val exporter = RecordingExporter(context)
        val session = RecordingSession("id", 1, "会议", audioBytes = 32_000, organized = rich,
            segments = listOf(RecordingSegment(0, 1000, "原始内容")))
        for (scope in RecordingExportScope.entries) {
            val document = exporter.buildDocument(session, scope, 2)
            assertEquals(1000L, document.durationMs)
            assertEquals(2L, document.exportedAt)
            assertEquals(scope != RecordingExportScope.RAW_ONLY, document.organizedMarkdown != null)
            assertEquals(scope != RecordingExportScope.ORGANIZED_ONLY, document.rawSegments.isNotEmpty())
            for (format in SessionExportFormat.entries) {
                val estimate = exporter.estimate(session, format, scope)
                assertEquals(1, estimate.primaryFileCount)
                assertFalse(estimate.attachmentArchive)
                assertTrue(estimate.approxBytes > 0)
                assertEquals(format == SessionExportFormat.PDF, estimate.approxPages != null)
            }
        }
    }
}
