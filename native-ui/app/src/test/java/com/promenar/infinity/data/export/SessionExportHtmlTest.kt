package com.promenar.infinity.data.export

import com.promenar.infinity.domain.export.SessionExportFormat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class SessionExportHtmlTest {
    private val assets = ExportAssets { path -> File("app/src/main/assets", path).readBytes() }
    private fun doc(body: String, attachments: List<ExportAttachment> = emptyList()) = ExportDocument("标题", "model", 1, 2,
        listOf(ExportMessage("ASSISTANT", 1, "actual-model", body, attachments = attachments)))

    @Test fun `展示格式消息头用你和智能体名及模型显示名而不是原始角色与模型ID`() {
        val document = ExportDocument("标题", "default::qwen-flash", 1, 2, listOf(
            ExportMessage("USER", 1, "default::qwen-flash", "你好"),
            ExportMessage("ASSISTANT", 2, "default::qwen-flash", "嗨"),
            ExportMessage("ASSISTANT", 3, "other::unknown-model", "再见"),
        ), agentName = "Misty", modelNames = mapOf("default::qwen-flash" to "Qwen Flash"))
        val html = ExportHtmlRenderer(assets) { ExportLabels(user = "You", model = "Model", exportedAt = "Exported", htmlLang = "en") }
            .render(document, SessionExportFormat.HTML, true)
        assertTrue(html.contains("<html lang=\"en\">"))
        assertTrue(html.contains("Model：Qwen Flash"))
        assertTrue(html.contains(">You · ${exportTimestamp(1)}</h2>"))
        assertTrue(html.contains(">Misty · ${exportTimestamp(2)} · Qwen Flash</h2>"))
        assertTrue(html.contains(">Misty · ${exportTimestamp(3)} · unknown-model</h2>"))
        assertFalse(html.contains("ASSISTANT"))
        assertFalse(html.contains("default::"))
    }

    @Test fun `没有智能体名时回退为助手文案`() {
        val heading = exportPresentationHeading(ExportMessage("ASSISTANT", 1, null, "x"), ExportDocument("t", "", 1, 2, emptyList()), ExportLabels())
        assertEquals("助手 · ${exportTimestamp(1)}", heading)
    }

    @Test fun `普通HTML不加载富内容脚本且原始HTML及危险链接不可执行`() {
        val html = ExportHtmlRenderer(assets).render(doc("<script>alert(1)</script>\n<img src=x onerror=alert(2)>\n\n[危险](javascript:alert(3))\n\n![网络图片](https://invalid.example/a.png)"), SessionExportFormat.HTML, true)
        assertTrue(html.contains("&lt;script&gt;"))
        assertFalse(html.contains("<script>alert(1)"))
        assertFalse(html.contains("<img src=x"))
        assertFalse(Regex("(?i)(?:src|href)=[\"'](?:https?:|javascript:)").containsMatchIn(html))
        assertFalse(html.contains("data-export-library"))
        assertFalse(Regex("(?i)<script[^>]+src=").containsMatchIn(html))
    }

    @Test fun `GFM表格代码高亮和各类公式标记存在且忽略代码链接中的公式`() {
        val body = """
            |列一|列二|
            |---|---|
            |a|b|

            ```kotlin
            val text = "<tag>"
            ```

        """.trimIndent() + "\n数学：\$x^2\$，\$\$y=2\$\$，\\(z\\)，\\[a+b\\]"
        val html = ExportHtmlRenderer(assets).render(doc(body), SessionExportFormat.HTML, true)
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("language-kotlin"))
        assertTrue(html.contains("<span style=\"color:"))
        assertTrue(html.contains("data-math"))
        assertTrue(html.contains("data-export-library=\"katex\""))
        assertTrue(html.contains("data:font/woff2;base64,"))
        assertFalse(Regex("url\\([^)]*\\.(?:woff|ttf)[\"']?\\)").containsMatchIn(html))
        val literal = ExportHtmlRenderer(assets).render(doc("`\$x\$`\n\n```text\n\$\$x\$\$\n```\n\n[link](https://example.org/\$x\$)"), SessionExportFormat.HTML, true)
        assertFalse(literal.contains("data-export-library=\"katex\""))
    }

    @Test fun `图表按需内嵌且保留原始代码回退不用执行用户JS`() {
        listOf("mermaid" to "graph TD\nA-->B", "echarts" to "{\"series\":[{\"type\":\"bar\",\"data\":[1,2]}]}").forEach { (kind, code) ->
            val html = ExportHtmlRenderer(assets).render(doc("```$kind\n$code\n```"), SessionExportFormat.HTML, true)
            assertTrue(html.contains("data-export-library=\"$kind\""))
            assertTrue(html.contains("data-diagram=\"$kind\""))
            assertTrue(html.contains("window.__sessionExportReady"))
            assertFalse(html.contains("data-export-library=\"katex\""))
            assertFalse(Regex("(?i)<script[^>]+src=").containsMatchIn(html))
        }
    }

    @Test fun `空公式保留字面量且不使整份导出失败`() {
        val html = ExportHtmlRenderer(assets).render(doc("空公式：\\(\\) 与 \\[\\]"), SessionExportFormat.HTML, true)
        assertTrue(html.contains("空公式"))
        assertFalse(html.contains("data-export-library=\"katex\""))
    }

    @Test fun `HTML附件图片内嵌文本折叠而快照仅列文本名`() {
        val input = doc("正文", listOf(
            ExportAttachment("a.png", "image/png", source = "data:image/png;base64,AQID", dataUri = "data:image/png;base64,AQID"),
            ExportAttachment("notes.txt", "text/plain", text = "秘密全文"),
            ExportAttachment("a.pdf", "application/pdf", source = "content://docs/1")))
        val renderer = ExportHtmlRenderer(assets)
        val html = renderer.render(input, SessionExportFormat.HTML, true)
        assertTrue(html.contains("<img src=\"data:image/png;base64,AQID\""))
        assertTrue(html.contains("<details"))
        assertTrue(html.contains("秘密全文"))
        val off = renderer.render(input, SessionExportFormat.HTML, false)
        assertFalse(off.contains("秘密全文"))
        assertFalse(off.contains("<img"))
        listOf(SessionExportFormat.PDF, SessionExportFormat.PNG).forEach { format ->
            val snapshot = renderer.render(input, format, false)
            assertTrue(snapshot.contains("<img src=\"data:image/png;base64,AQID\""))
            assertFalse(snapshot.contains("秘密全文"))
            assertTrue(snapshot.contains("notes.txt"))
        }
    }

    @Test fun 录音导出渲染富格式和时间戳并转义其它链接() {
        val fence = "~~~".replace('~', '`')
        val markdown = "[01:23](recording://seek/83000) [dangerous](javascript:alert(1))\n\n" +
            "|项目|数量|\n|---|---|\n|样本|2|\n\n公式：\$a^2+b^2\$" +
            "\n\n${fence}mermaid\nflowchart TD\n A --> B\n$fence" +
            "\n\n${fence}echarts\n{\"tooltip\":{},\"series\":[{\"type\":\"bar\",\"data\":[2]}]}\n$fence"
        val renderer = ExportHtmlRenderer(assets)
        val document = RecordingExportDocument("访谈 & 会议", 1, 2, 83_000, 1, "Qwen 整理", markdown,
            listOf(RecordingExportSegment("01:23", "主持人", "听写 <全文>")))
        val html = renderer.renderRecording(document, SessionExportFormat.PNG)
        for (snippet in listOf("访谈 &amp; 会议", "01:23", "Qwen 整理", "整理稿", "原始转写", "主持人",
            "听写 &lt;全文&gt;", "<table>", "data-math", "data-diagram=\"mermaid\"", "data-diagram=\"echarts\"",
            "<span class=\"seek-stamp\">01:23</span>", "recording-export", "unbounded", "scrollWidth")) {
            assertTrue(html.contains(snippet), snippet)
        }
        assertFalse(html.contains("href=\"javascript:"))
        assertTrue(html.contains("[dangerous](javascript:alert(1))"))
        assertTrue(renderer.render(doc("[01:23](recording://seek/83000)"), SessionExportFormat.HTML, true)
            .contains("<span class=\"seek-stamp\">01:23</span>"))
    }

    @Test fun 超过标准页高的完整块获得加长页() {
        val blocks = listOf(ExportSlice(90, 280), ExportSlice(150, 305), ExportSlice(350, 380))
        val pages = splitUnboundedExportPdfHeight(415, blocks, 100)
        assertEquals(listOf(ExportSlice(0, 90), ExportSlice(90, 305), ExportSlice(305, 405), ExportSlice(405, 415)), pages)
    }
}
