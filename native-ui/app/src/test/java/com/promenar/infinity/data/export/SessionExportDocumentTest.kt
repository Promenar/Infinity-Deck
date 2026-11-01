package com.promenar.infinity.data.export

import com.promenar.infinity.data.model.*
import com.promenar.infinity.domain.export.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SessionExportDocumentTest {
    private val session = Session("s", "agent", title = "My/会话?", modelId = "session-model", createdAt = 1_700_000_000_000)
    private val document = MessageDocumentAttachment("doc", "notes.md", "text/markdown", "# 附件全文\n两行", 20, "hash", 5)
    private val messages = listOf(
        Message("u", MessageRole.USER, "正文", modelId = "user-model", createdAt = 100,
            quotes = listOf(MessageQuote("a", "引文", "批注")), userDocuments = listOf(document),
            userImages = listOf("data:image/png;base64,AQID")),
        Message("a", MessageRole.ASSISTANT, "回答", reasoning = "思考一\n思考二", createdAt = 200,
            toolCalls = listOf(ToolCall("t", "search", "SECRET_ARGS")), executionSteps = listOf(
                ExecutionStep("call", "tool_call", "search", "SECRET_ARGS", "t"),
                ExecutionStep("result", "tool_result", toolName = "search", toolCallId = "t", content = "结果\n" + "长".repeat(300)),
            )),
        Message("t", MessageRole.TOOL, "完整输出不应作为正文", toolCallId = "t", name = "search", createdAt = 300),
    )

    @Test fun `模型专用上下文和请求指令不进入展示导出正文`() {
        val hidden = messages.map { it.copy(turnContext = "MODEL_ONLY_CONTEXT", requestInstruction = "MODEL_ONLY_INSTRUCTION") }
        val baseline = ExportDocumentBuilder.build(session, messages, SessionExportOptions(true, true), 1000)
        val doc = ExportDocumentBuilder.build(session, hidden, SessionExportOptions(true, true), 1000)
        assertEquals(baseline, doc)
        listOf(SessionExportFormat.MARKDOWN, SessionExportFormat.TXT).forEach { format ->
            val rendered = ExportTextRenderer.render(doc, format, true)
            assertFalse(rendered.contains("MODEL_ONLY_CONTEXT"))
            assertFalse(rendered.contains("MODEL_ONLY_INSTRUCTION"))
            assertTrue(rendered.contains("正文"))
        }
    }

    @Test fun `文档保留元数据引用且思考和工具默认隐藏`() {
        val doc = ExportDocumentBuilder.build(session, messages, SessionExportOptions(), 1000)
        assertEquals(listOf("USER", "ASSISTANT"), doc.messages.map { it.role })
        assertEquals(100, doc.messages.first().timestamp)
        assertEquals("user-model", doc.messages.first().model)
        assertEquals("> 引文\n\n批注\n\n正文", doc.messages.first().body)
        assertTrue(doc.messages.all { it.thinking == null && it.tools.isEmpty() })
        assertEquals(2, doc.messages.first().attachments.size)
    }

    @Test fun `开关组合只输出思考和去重的单行工具摘要`() {
        listOf(false, true).forEach { thinking -> listOf(false, true).forEach { tools ->
            val doc = ExportDocumentBuilder.build(session, messages, SessionExportOptions(thinking, tools), 1000)
            val a = doc.messages.last()
            assertEquals(thinking, a.thinking != null)
            assertEquals(if (tools) 1 else 0, a.tools.size)
            a.tools.forEach {
                assertEquals("search", it.name)
                assertEquals("成功", it.status)
                assertFalse(it.summary.contains('\n'))
                assertTrue(it.summary.codePointCount(0, it.summary.length) <= 160)
                assertFalse(it.summary.contains("SECRET_ARGS"))
                assertFalse(it.summary.contains("完整输出"))
            }
        } }
    }

    @Test fun `读取文件和命令工具不泄露内容且独立工具消息有状态`() {
        val input = listOf(
            Message("a", MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("read", "read_file", "secret"))),
            Message("r", MessageRole.TOOL, "FILE_SECRET", toolCallId = "read", name = "read_file", isError = true),
            Message("orphan", MessageRole.TOOL, "失败\n细节", name = "search", isError = true),
        )
        val doc = ExportDocumentBuilder.build(session, input, SessionExportOptions(includeToolCalls = true), 1000)
        val rendered = ExportTextRenderer.render(doc, SessionExportFormat.TXT, true)
        assertFalse(rendered.contains("FILE_SECRET"))
        assertTrue(rendered.contains("失败"))
        assertEquals(2, doc.messages.sumOf { it.tools.size })
    }

    @Test fun `文本附件内联图片引用与归档随格式和开关变化`() {
        val doc = ExportDocumentBuilder.build(session, messages, SessionExportOptions(), 1000)
        listOf(SessionExportFormat.MARKDOWN, SessionExportFormat.TXT).forEach { format ->
            val on = ExportTextRenderer.render(doc, format, true)
            val off = ExportTextRenderer.render(doc, format, false)
            assertTrue(on.contains("# 附件全文\n两行"))
            assertTrue(on.contains("image-1.png"))
            assertTrue(off.contains("notes.md"))
            assertFalse(off.contains("# 附件全文"))
            assertTrue(exportArchiveAttachments(doc, format, true).size == 1)
            assertTrue(exportArchiveAttachments(doc, format, false).isEmpty())
        }
        assertTrue(ExportTextRenderer.render(doc, SessionExportFormat.MARKDOWN, true).contains("<!-- infinity-attachment name=\"notes.md\" mime=\"text/markdown\" -->"))
        assertTrue(ExportTextRenderer.render(doc, SessionExportFormat.TXT, true).contains("--- Attachment: notes.md (text/markdown) ---"))
        assertTrue(exportArchiveAttachments(doc, SessionExportFormat.HTML, true).isEmpty())
        assertTrue(exportArchiveAttachments(doc, SessionExportFormat.PDF, true).isEmpty())
        val textOnly = ExportDocumentBuilder.build(session, listOf(messages.first().copy(userImages = null)), SessionExportOptions(), 0)
        assertTrue(exportArchiveAttachments(textOnly, SessionExportFormat.TXT, true).isEmpty())
    }

    @Test fun `估算页数和图片数随内容增长且附件只在支持格式打包`() {
        val doc = ExportDocumentBuilder.build(session, messages, SessionExportOptions(), 0)
        val long = doc.copy(messages = List(100) { doc.messages.first().copy(body = "长文本".repeat(1000)) })
        listOf(SessionExportFormat.PDF, SessionExportFormat.PNG).forEach { format ->
            val small = estimateExportDocument(doc, format, true)
            val large = estimateExportDocument(long, format, true)
            assertTrue(large.approxBytes >= small.approxBytes)
            if (format == SessionExportFormat.PDF) assertTrue(large.approxPages!! > small.approxPages!!)
            else assertTrue(large.primaryFileCount > small.primaryFileCount)
            assertFalse(large.attachmentArchive)
        }
        assertTrue(estimateExportDocument(doc, SessionExportFormat.MARKDOWN, true).attachmentArchive)
        assertFalse(estimateExportDocument(doc, SessionExportFormat.MARKDOWN, false).attachmentArchive)
        assertTrue(exportBaseName(session.title, session.createdAt).startsWith("My_会话__"))
    }

    @Test fun `脚本工具的短输出也不能作为文件全文导出`() {
        val input = listOf(Message("t", MessageRole.TOOL, "Result: PRIVATE_FILE_BODY", name = "run_script"))
        val doc = ExportDocumentBuilder.build(session, input, SessionExportOptions(includeToolCalls = true), 0)
        assertFalse(ExportTextRenderer.render(doc, SessionExportFormat.TXT, true).contains("PRIVATE_FILE_BODY"))
    }

    @Test fun `工具媒体与旧图片字段在关闭工具开关时依然是附件`() {
        val input = listOf(
            Message("a", MessageRole.ASSISTANT, "结果", toolCalls = listOf(ToolCall("t", "image_gen", "args"))),
            Message("t", MessageRole.TOOL, "", toolCallId = "t", images = "data:image/png;base64,AQID"),
        )
        val doc = ExportDocumentBuilder.build(session, input, SessionExportOptions(), 0)
        assertEquals(1, doc.messages.size)
        assertTrue(doc.messages.single().tools.isEmpty())
        assertEquals(1, doc.messages.single().attachments.size)
    }

    @Test fun `兼容历史附件与文件清单且不把任意字符串当成本机路径`() {
        val legacy = messages.first().copy(userImages = null, userDocuments = null,
            legacyAttachmentsPayload = """[{"uri":"data:image/png;base64,AQID","mimeType":"image/png","fileName":"old.png"}]""",
            legacyFilesPayload = """[{"uri":"content://documents/1","mimeType":"application/pdf","name":"old.pdf"}]""")
        val doc = ExportDocumentBuilder.build(session, listOf(legacy), SessionExportOptions(), 0)
        assertEquals(listOf("old.png", "old.pdf"), doc.messages.single().attachments.map { it.name })
    }
}
