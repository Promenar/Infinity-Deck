package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DocumentReferenceExtractorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Before fun setupPdfResources() {
        val classResource = requireNotNull(com.tom_roush.pdfbox.pdmodel.PDDocument::class.java.getResource("PDDocument.class"))
        val dependencyJar = java.io.File((classResource.openConnection() as java.net.JarURLConnection).jarFileURL.toURI())
        val transforms = generateSequence(dependencyJar.parentFile) { it.parentFile }.first { it.name == "transforms" }
        val assetsRoot = transforms.listFiles().orEmpty().asSequence()
            .flatMap { java.io.File(it, "transformed").listFiles().orEmpty().asSequence() }
            .filter { it.name.contains("pdfbox") }.map { java.io.File(it, "assets") }
            .first { java.io.File(it, "com/tom_roush/pdfbox/resources/afm/Times-Roman.afm").isFile }
        val assets = mockk<android.content.res.AssetManager>()
        every { assets.open(any()) } answers { java.io.File(assetsRoot, firstArg<String>()).inputStream() }
        val context = mockk<android.content.Context>()
        every { context.applicationContext } returns context
        every { context.assets } returns assets
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
    }

    @Test fun `空白PDF识别为没有文字层`() {
        val root = temporary.newFolder("empty-pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { document ->
            document.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
            document.save(root.resolve("empty.pdf"))
        }
        val error = runCatching { DocumentReferenceExtractor(64, 0).extract(entry(root.path, "empty.pdf", "application/pdf")) }.exceptionOrNull()
        assertThat((error as? DocumentFormatException)?.code).isEqualTo(DocumentFormatError.PDF_NO_TEXT_LAYER)
    }

    @Test fun `编码识别支持UTF与GBK并拒绝二进制`() {
        val root = temporary.newFolder("encodings")
        val text = "虚构中文资料"
        val cases = listOf(
            "utf8" to text.toByteArray(Charsets.UTF_8),
            "bom" to (byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + text.toByteArray(Charsets.UTF_8)),
            "gbk" to text.toByteArray(java.nio.charset.Charset.forName("GBK")),
            "utf16" to (byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)),
        )
        cases.forEach { (name, bytes) ->
            root.resolve(name).writeBytes(bytes)
            assertThat(DocumentReferenceExtractor(64, 0).extract(entry(root.path, name, "text/plain")).graphText).isEqualTo(text)
        }
        root.resolve("binary").writeBytes(byteArrayOf(0, 1, 2, 0xff.toByte()))
        assertThat(runCatching { DocumentReferenceExtractor(64, 0).extract(entry(root.path, "binary", "text/plain")) }.exceptionOrNull()).isInstanceOf(DocumentFormatException::class.java)
    }

    @Test fun `GBK少量控制字符可识别而过多控制字符被拒绝`() {
        val root = temporary.newFolder("gbk-controls")
        val text = "虚构中文资料".repeat(30) + "\u0001"
        root.resolve("valid.txt").writeBytes(text.toByteArray(java.nio.charset.Charset.forName("GBK")))
        assertThat(DocumentReferenceExtractor(256, 0).extract(entry(root.path, "valid.txt", "text/plain")).graphText).isEqualTo(text)
        root.resolve("bad.txt").writeBytes("中文\u0001\u0002".toByteArray(java.nio.charset.Charset.forName("GBK")))
        assertThat(runCatching { DocumentReferenceExtractor(64, 0).extract(entry(root.path, "bad.txt", "text/plain")) }.exceptionOrNull()).isInstanceOf(DocumentFormatException::class.java)
    }

    @Test fun `文字PDF正常抽取`() {
        val root = temporary.newFolder("text-pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
            val page = com.tom_roush.pdfbox.pdmodel.PDPage()
            doc.addPage(page)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { stream ->
                stream.beginText()
                stream.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(40f, 700f)
                stream.showText("A fictional document has enough readable text for extraction.")
                stream.endText()
            }
            doc.save(root.resolve("text.pdf"))
        }
        assertThat(DocumentReferenceExtractor(128, 0).extract(entry(root.path, "text.pdf", "application/pdf")).graphText).contains("fictional document")
    }

    @Test fun `旧版Word样本可抽取正文`() {
        val sample = requireNotNull(javaClass.classLoader?.getResource("library/sample.doc")) { "主控需要提供 src/test/resources/library/sample.doc" }
        val root = temporary.newFolder("doc-fixture")
        sample.openStream().use { input -> root.resolve("sample.doc").outputStream().use { input.copyTo(it) } }
        val text = DocumentReferenceExtractor(256, 0).extract(entry(root.path, "sample.doc", "application/msword")).graphText
        assertThat(text).contains("林舟在码头的灯塔下等潮水退去")
        assertThat(text).contains("The harbor bell rang twice.")
    }

    @Test
    fun `菜单抽取资格只接受解析器支持的文件类型与体积`() {
        val file = entry("/tmp", "doc", "application/pdf").copy(sizeBytes = 300 * 1024)
        assertThat(DocumentReferenceExtractor.supports(file)).isTrue()
        assertThat(DocumentReferenceExtractor.supports(file.copy(mimeType = DocumentReferenceExtractor.DOCX_MIME))).isTrue()
        assertThat(DocumentReferenceExtractor.supports(file.copy(mimeType = "text/plain"))).isTrue()
        assertThat(DocumentReferenceExtractor.supports(file.copy(mimeType = "image/png"))).isFalse()
        assertThat(DocumentReferenceExtractor.supports(file.copy(isDirectory = true))).isFalse()
        assertThat(DocumentReferenceExtractor.supports(file.copy(mimeType = DocumentReferenceExtractor.DOCX_MIME, sizeBytes = 50L * 1024 * 1024 + 1))).isFalse()
    }

    @Test
    fun `UTF8文本按增量切块并保持重叠`() {
        val root = temporary.newFolder("root")
        root.resolve("note.txt").writeText("abcdefghijklmnop")

        val result = DocumentReferenceExtractor(chunkSize = 8, chunkOverlap = 2)
            .extract(entry(root.path, "note.txt", "text/plain"))

        assertThat(result.chunks).containsExactly("abcdefgh", "ghijklmn", "mnop").inOrder()
        assertThat(result.graphText).isEqualTo("abcdefghijklmnop")
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun `emoji跨读取与分块边界时每块都是完整Unicode`() {
        val root = temporary.newFolder("unicode-chunks")
        val source = "abc😀def😀末"
        root.resolve("emoji.txt").writeText(source)
        val chunks = DocumentReferenceExtractor(4, 0).extract(entry(root.path, "emoji.txt", "text/plain")).chunks
        assertThat(chunks.joinToString("")).isEqualTo(source)
        chunks.forEach { assertThat(it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)).isEqualTo(it) }
        for (overlap in 0..3) {
            val result = DocumentReferenceExtractor(4, overlap).extract(entry(root.path, "emoji.txt", "text/plain"))
            result.chunks.forEach { assertThat(it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)).isEqualTo(it) }
        }
        val boundary = "a".repeat(16_383) + "😀末"
        root.resolve("reader.txt").writeText(boundary)
        val result = DocumentReferenceExtractor(256, 16).extract(entry(root.path, "reader.txt", "text/plain"))
        assertThat(result.graphText).isEqualTo(boundary)
        result.chunks.forEach { assertThat(it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)).isEqualTo(it) }
    }

    @Test
    fun `分块和提取预算的非法参数立即拒绝`() {
        listOf(0 to 0, -1 to 0, 4 to -1, 4 to 4).forEach { (size, overlap) ->
            assertThat(runCatching { DocumentReferenceExtractor(size, overlap) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
        listOf(Long.MIN_VALUE, DocumentReferenceExtractor.MAX_EXTRACTED_UTF8_BYTES + 1).forEach { budget ->
            assertThat(runCatching { DocumentReferenceExtractor(4, 0, budget) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `非法UTF8文本失败关闭而不是替换字符继续索引`() {
        val root = temporary.newFolder("invalid")
        root.resolve("bad.txt").writeBytes(byteArrayOf(0x61, 0xff.toByte(), 0x28))

        val failure = runCatching {
            DocumentReferenceExtractor(8, 2).extract(entry(root.path, "bad.txt", "text/plain"))
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
    }

    @Test
    fun `HTML离线提取保留段落列表表格并移除非正文内容`() {
        val root = temporary.newFolder("html")
        root.resolve("article.html").writeText(
            """
                <!doctype html><html><head>
                <style>.secret { color: red }</style>
                <script>fetch('https://example.invalid/remote')</script>
                </head><body>
                <h1>标题 &amp; 中文</h1>
                <p>第一段😀<strong>强调</strong></p>
                <ul><li>条目甲</li><li>条目乙</li></ul>
                <table><tr><th>名称</th><th>值</th></tr><tr><td>温度</td><td>21</td></tr></table>
                <iframe src="https://example.invalid/frame">远程框架隐藏正文</iframe>
                <p>未闭合段落
                </body></html>
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val result = DocumentReferenceExtractor(256, 16)
            .extract(entry(root.path, "article.html", "text/html"))

        assertThat(result.graphText).contains("标题 & 中文")
        assertThat(result.graphText).contains("第一段😀强调")
        assertThat(result.graphText).contains("条目甲")
        assertThat(result.graphText).contains("条目乙")
        assertThat(result.graphText).contains("名称 | 值")
        assertThat(result.graphText).contains("温度 | 21")
        assertThat(result.graphText).contains("未闭合段落")
        assertThat(result.graphText).doesNotContain("fetch")
        assertThat(result.graphText).doesNotContain("secret")
        assertThat(result.graphText).doesNotContain("远程框架隐藏正文")
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun `HTML容器嵌套仍保持段落列表表格边界和行内文字`() {
        val html = """<main><article><h1>标题</h1><p>甲<strong>强调</strong>尾</p><p>乙</p><section><ul><li>丙</li><li>丁</li></ul><table><tr><td>戊</td><td>己</td></tr></table></section></article></main>"""
        val text = OfflineHtmlDocumentExtractor.extract(html)
        assertThat(text).contains("标题\n甲强调尾\n乙")
        assertThat(text).contains("- 丙\n- 丁")
        assertThat(text).contains("戊 | 己")
    }

    @Test
    fun `HTML表格块级单元格保持列分隔且pre换行不丢失`() {
        val html = """
            <table><tr><td><p>甲</p></td><td><div>乙</div></td></tr></table>
            <pre>保留第一行
保留第二行</pre>
        """.trimIndent()

        val text = OfflineHtmlDocumentExtractor.extract(html)

        assertThat(text).contains("甲 | 乙")
        assertThat(text).contains("保留第一行\n保留第二行")
    }

    @Test
    fun `HTML读取按实际字节拒绝超限并处理零长度读取`() {
        val source = "<p>中文</p>".toByteArray()
        val zeroReadStream = object : java.io.ByteArrayInputStream(source) {
            override fun read(buffer: ByteArray, off: Int, len: Int): Int = 0
        }
        assertThat(DocumentReferenceExtractor.readHtmlSource(zeroReadStream)).isEqualTo(source)
        val oversized = java.io.ByteArrayInputStream(ByteArray((DocumentReferenceExtractor.MAX_HTML_SOURCE_BYTES + 1).toInt()))
        val failure = runCatching { DocumentReferenceExtractor.readHtmlSource(oversized) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `HTML必须是合法UTF8且不会按平台默认编码替换`() {
        val root = temporary.newFolder("html-invalid")
        root.resolve("bad.html").writeBytes(
            "<p>ok".toByteArray() + byteArrayOf(0xff.toByte(), 0x28) + "</p>".toByteArray(),
        )

        val failure = runCatching {
            DocumentReferenceExtractor(64, 8).extract(entry(root.path, "bad.html", "text/html"))
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
    }

    @Test
    fun `HTML提取文本遵守统一十六MiB上限并报告截断`() {
        val root = temporary.newFolder("html-limit")
        val testLimit = 1_024L
        val payload = "a".repeat(testLimit.toInt() + 1)
        root.resolve("large.html").writeText("<html><body><p>$payload</p></body></html>", Charsets.UTF_8)

        val result = DocumentReferenceExtractor(
            chunkSize = 256,
            chunkOverlap = 0,
            maxExtractedUtf8Bytes = testLimit,
        )
            .extract(entry(root.path, "large.html", "text/html"))

        assertThat(result.truncated).isTrue()
        assertThat(result.graphText.toByteArray(Charsets.UTF_8).size.toLong())
            .isEqualTo(testLimit)
    }

    @Test
    fun `HTML源文件超过独立四MiB预算时在DOM解析前拒绝`() {
        val root = temporary.newFolder("html-source-limit")
        val file = root.resolve("oversized.html")
        file.outputStream().use { output ->
            val block = ByteArray(8 * 1024) { 'a'.code.toByte() }
            var remaining = DocumentReferenceExtractor.MAX_HTML_SOURCE_BYTES + 1
            while (remaining > 0) {
                val count = minOf(remaining, block.size.toLong()).toInt()
                output.write(block, 0, count)
                remaining -= count
            }
        }

        val failure = runCatching {
            DocumentReferenceExtractor(256, 16).extract(entry(root.path, file.name, "text/html"))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure?.message).contains("HTML 源文件超过安全阈值")
    }

    @Test
    fun `HTML标记数量预算在DOM构建前拒绝节点放大输入`() {
        val html = buildString {
            repeat(100_001) { append("<i>") }
        }

        val failure = runCatching { OfflineHtmlDocumentExtractor.extract(html) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure?.message).contains("HTML 标记数量超过安全阈值")
    }

    @Test
    fun `HTML与旧DOC属于文件引用索引白名单`() {
        assertThat(DocumentReferenceExtractor.SUPPORTED_MIME_TYPES).contains("text/html")
        assertThat(DocumentReferenceExtractor.SUPPORTED_MIME_TYPES).contains(DocumentReferenceExtractor.DOCX_MIME)
        assertThat(DocumentReferenceExtractor.SUPPORTED_MIME_TYPES).contains("application/msword")
    }

    private fun entry(root: String, name: String, mime: String) = FileEntry(
        uuid = "file",
        workspaceRootUuid = "root",
        parentUuid = "root",
        name = name,
        hash = "hash",
        mimeType = mime,
        sizeBytes = java.io.File(root, name).length(),
        physicalRootPath = root,
        materializedPath = "/$name",
        createdAt = 1,
        updatedAt = 1,
    )

}
