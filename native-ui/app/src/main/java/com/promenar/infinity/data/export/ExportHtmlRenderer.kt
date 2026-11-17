package com.promenar.infinity.data.export

import com.promenar.infinity.domain.export.SessionExportFormat
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes as Elements
import org.intellij.markdown.MarkdownTokenTypes as Tokens
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser
import java.net.URI
import java.util.Base64
import java.util.UUID

internal class ExportAssets(private val read: (String) -> ByteArray) {
    private val cache = mutableMapOf<String, String>()
    @Synchronized fun text(path: String): String = cache.getOrPut(path) { read(path).toString(Charsets.UTF_8) }
    @Synchronized fun mathCss(): String = cache.getOrPut("inline-math-css") {
        Regex("src:[^;}]+[;}]", RegexOption.IGNORE_CASE).replace(text("katex/katex.min.css")) { declaration ->
            val font = Regex("url\\([\"']?([^)'\"]+\\.woff2)[\"']?\\)").find(declaration.value)?.groupValues?.get(1)
            val end = if (declaration.value.endsWith('}')) "}" else ";"
            if (font == null) "src:none$end" else {
                require(font.matches(Regex("fonts/[A-Za-z0-9_.-]+\\.woff2")))
                "src:url(data:font/woff2;base64,${Base64.getEncoder().encodeToString(mathFont(font))}) format('woff2')$end"
            }
        }
    }

    private fun mathFont(font: String): ByteArray = try {
        read("katex/$font")
    } catch (missing: java.io.FileNotFoundException) {
        // 捆绑资源缺少常规书法字体，使用相同字形的现有粗体；不引用网络字体。
        if (font != "fonts/KaTeX_Caligraphic-Regular.woff2") throw missing
        read("katex/fonts/KaTeX_Caligraphic-Bold.woff2")
    }
}

internal data class RecordingExportDocument(
    val title: String,
    val createdAt: Long,
    val exportedAt: Long,
    val durationMs: Long,
    val speakerCount: Int,
    val modelSummary: String?,
    val organizedMarkdown: String?,
    val rawSegments: List<RecordingExportSegment> = emptyList(),
)

internal data class RecordingExportSegment(val timestamp: String, val speaker: String?, val text: String)

/** 所有会话内容经 Markdown AST 或文本转义；只有捆绑的渲染器代码可执行。 */
internal class ExportHtmlRenderer(private val assets: ExportAssets, private val labels: () -> ExportLabels = { ExportLabels() }) {
    fun render(doc: ExportDocument, format: SessionExportFormat, includeAttachments: Boolean): String {
        val libraries = linkedSetOf<String>()
        val labels = labels()
        val snapshot = format == SessionExportFormat.PDF || format == SessionExportFormat.PNG
        val showImages = snapshot || includeAttachments
        fun markdown(text: String, attachments: List<ExportAttachment>): String = markdownHtml(text, libraries, attachments, showImages, labels.imageAlt)
        val body = buildString {
            append("<header class=\"document-head\"><h1>${escapeExportHtml(doc.title)}</h1>")
            val meta = listOfNotNull(
                doc.model.takeIf { it.isNotBlank() }?.let { "${labels.model}：${exportModelName(it, doc)}" },
                "${labels.exportedAt}：${exportTimestamp(doc.exportedAt)}",
            ).joinToString(" · ")
            append("<p>${escapeExportHtml(meta)}</p></header>")
            if (doc.messages.isEmpty()) append("<p>${escapeExportHtml(labels.noMessages)}</p>")
            doc.messages.forEach { message ->
                append("<article class=\"message ${if (message.role == "USER") "user" else "assistant"}\">")
                append("<h2 class=\"message-head\">${escapeExportHtml(exportPresentationHeading(message, doc, labels))}</h2>")
                message.thinking?.let {
                    append("<details class=\"thinking\"${if (snapshot) " open" else ""}><summary>${escapeExportHtml(labels.thinking)}</summary>${markdown(it, message.attachments)}</details>")
                }
                append("<div class=\"body\">${markdown(message.body, message.attachments)}</div>")
                message.tools.forEach { tool -> append("<p class=\"tool\">${escapeExportHtml("${tool.name} · ${tool.status} · ${tool.summary}")}</p>") }
                if (!showImages && message.attachments.isNotEmpty()) {
                    append("<p class=\"attachments\">${escapeExportHtml(labels.attachments)}：${escapeExportHtml(message.attachments.joinToString("、") { it.name })}</p>")
                } else message.attachments.forEach { attachment ->
                    val name = escapeExportHtml(attachment.name)
                    when {
                        attachment.isImage && attachment.dataUri != null -> append("<figure><img src=\"${escapeExportHtml(attachment.dataUri)}\" alt=\"$name\"><figcaption>$name</figcaption></figure>")
                        attachment.text != null && !snapshot -> append("<details class=\"attachment\"><summary>$name (${escapeExportHtml(attachment.mimeType)})</summary><pre><code>${escapeExportHtml(attachment.text)}</code></pre></details>")
                        else -> append("<p class=\"attachments\">${escapeExportHtml(labels.attachments)}：$name${if (attachment.isImage && attachment.dataUri == null) escapeExportHtml(labels.unreadable) else ""}</p>")
                    }
                }
                append("</article>")
            }
        }
        val bodyClass = if (snapshot) "snapshot${if (format == SessionExportFormat.PDF) " pdf" else ""}" else ""
        return renderPage(doc.title, body, labels.htmlLang, bodyClass, libraries)
    }

    fun renderRecording(doc: RecordingExportDocument, format: SessionExportFormat): String {
        val libraries = linkedSetOf<String>()
        val snapshot = format == SessionExportFormat.PDF || format == SessionExportFormat.PNG
        val body = buildString {
            append("<header class=\"document-head\"><h1>${escapeExportHtml(doc.title)}</h1>")
            recordingExportMetadata(doc).forEach { append("<p>${escapeExportHtml(it)}</p>") }
            append("</header>")
            doc.organizedMarkdown?.takeIf(String::isNotBlank)?.let {
                append("<section><h2>整理稿</h2>")
                append(markdownHtml(it, libraries, emptyList(), showImages = true, imageAlt = labels().imageAlt))
                append("</section>")
            }
            if (doc.rawSegments.isNotEmpty()) {
                append("<section><h2>原始转写</h2><ul class=\"recording-segments\">")
                doc.rawSegments.forEach { segment ->
                    append("<li><span class=\"seek-stamp\">${escapeExportHtml(segment.timestamp)}</span>")
                    segment.speaker?.let { append(" <strong>${escapeExportHtml(it)}</strong>") }
                    append("<p>${escapeExportHtml(segment.text)}</p></li>")
                }
                append("</ul></section>")
            }
        }
        val bodyClass = if (snapshot) "snapshot recording-export unbounded" else "recording-export"
        return renderPage(doc.title, body, labels().htmlLang, bodyClass, libraries)
    }

    private fun renderPage(title: String, body: String, htmlLang: String, bodyClass: String, libraries: Set<String>): String {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val viewport = if ("unbounded" in bodyClass.split(' ')) "width=720" else "width=device-width,initial-scale=1"
        fun script(code: String, library: String? = null): String = "<script nonce=\"$nonce\"${library?.let { " data-export-library=\"$it\"" }.orEmpty()}>" +
            code.replace(Regex("</script", RegexOption.IGNORE_CASE), "<\\/script") + "</script>"
        return buildString {
            append("<!DOCTYPE html><html lang=\"${escapeExportHtml(htmlLang)}\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"$viewport\">")
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; font-src data:; style-src 'unsafe-inline'; script-src 'nonce-$nonce'; connect-src 'none'; base-uri 'none'; form-action 'none'\">")
            append("<title>${escapeExportHtml(title)}</title><style>$exportCss\n$recordingExportCss</style>")
            if ("katex" in libraries) append("<style>${assets.mathCss()}</style>")
            append("</head><body${if (bodyClass.isNotEmpty()) " class=\"$bodyClass\"" else ""}><main>$body</main>")
            libraries.forEach { kind -> append(script(assets.text("$kind/$kind.min.js"), kind)) }
            append(script(exportRenderScript))
            append("</body></html>")
        }
    }

    private fun markdownHtml(text: String, libraries: MutableSet<String>, attachments: List<ExportAttachment>, showImages: Boolean, imageAlt: String): String {
        val fragments = linkedMapOf<String, String>()
        val prefix = "IWEXPORT${UUID.randomUUID().toString().replace("-", "")}TOKEN"
        fun fragment(html: String): String = (prefix + fragments.size + "END").also { fragments[it] = html }
        val ranges = exportLiteralRanges(text)
        val math = Regex("""\$\$([\s\S]+?)\$\$|\\\[([\s\S]*?)\\]|\\\(([^\n]*?)\\\)|(?<![\\\$])\$(?!\$)(?!\s)([^\$\n]+?)(?<!\s)(?<!\\)\$(?!\$)(?!\d)""")
        val matches = mutableListOf<MatchResult>()
        var offset = 0
        while (offset < text.length) {
            val match = math.find(text, offset) ?: break
            if (ranges.any { it.first <= match.range.last && match.range.first <= it.last }) offset = match.range.first + 1
            else { matches += match; offset = match.range.last + 1 }
        }
        val source = buildString {
            var cursor = 0
            matches.forEach { match ->
                append(text, cursor, match.range.first)
                val latex = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
                if (latex == null) {
                    append(escapeExportHtml(match.value))
                    cursor = match.range.last + 1
                    return@forEach
                }
                val display = match.value.startsWith("$$") || match.value.startsWith("\\[")
                libraries += "katex"
                append(fragment("<span class=\"math${if (display) " display" else ""}\" data-math=\"${if (display) "display" else "inline"}\">${escapeExportHtml(latex)}</span>"))
                cursor = match.range.last + 1
            }
            append(text, cursor, text.length)
        }
        val flavour = object : GFMFlavourDescriptor() {
            override fun createHtmlGeneratingProviders(linkMap: LinkMap, baseURI: URI?): Map<IElementType, GeneratingProvider> {
                val providers = super.createHtmlGeneratingProviders(linkMap, baseURI).toMutableMap()
                val raw = object : GeneratingProvider {
                    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
                        visitor.consumeHtml(escapeExportHtml(text.substring(node.startOffset, node.endOffset)))
                    }
                }
                providers[Elements.HTML_BLOCK] = raw
                providers[Tokens.HTML_TAG] = raw
                val code = object : GeneratingProvider {
                    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
                        val language = node.children.firstOrNull { it.type == Tokens.FENCE_LANG }?.let { text.substring(it.startOffset, it.endOffset).trim().substringBefore(' ').lowercase() }.orEmpty()
                        val value = if (node.type == Elements.CODE_FENCE) {
                            val content = node.children.filter { it.type == Tokens.CODE_FENCE_CONTENT || it.type == Tokens.EOL }
                            content.joinToString("") { text.substring(it.startOffset, it.endOffset) }.trim('\n')
                        } else text.substring(node.startOffset, node.endOffset).trimIndent()
                        val lang = language.takeIf { it.matches(Regex("[a-z0-9_+-]{1,32}")) } ?: "text"
                        val block = "<pre><code class=\"language-$lang\">${highlightExportCode(value)}</code></pre>"
                        if (lang in setOf("echarts", "mermaid")) {
                            libraries += lang
                            visitor.consumeHtml("<div class=\"diagram\" data-diagram=\"$lang\"><div class=\"diagram-target\"></div><div class=\"diagram-source\">$block</div></div>")
                        } else visitor.consumeHtml(block)
                    }
                }
                providers[Elements.CODE_FENCE] = code
                providers[Elements.CODE_BLOCK] = code
                providers[Elements.IMAGE] = object : GeneratingProvider {
                    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
                        fun find(n: ASTNode, type: IElementType): ASTNode? = if (n.type == type) n else n.children.firstNotNullOfOrNull { find(it, type) }
                        val destination = find(node, Elements.LINK_DESTINATION)?.let { text.substring(it.startOffset, it.endOffset).trim('<', '>') }
                        val resolved = attachments.find { it.source == destination || it.name == destination }?.dataUri
                            ?: destination?.takeIf(::isExportImageDataUri)
                        if (showImages && resolved != null) visitor.consumeHtml("<img src=\"${escapeExportHtml(resolved)}\" alt=\"${escapeExportHtml(imageAlt)}\">")
                        else visitor.consumeHtml(escapeExportHtml(text.substring(node.startOffset, node.endOffset)))
                    }
                }
                // 导出文件中的链接只作为文本，禁止 javascript、file、content 和网络导航。
                listOf(Elements.INLINE_LINK, Elements.FULL_REFERENCE_LINK, Elements.SHORT_REFERENCE_LINK, Elements.AUTOLINK).forEach { providers[it] = raw }
                providers[Elements.INLINE_LINK] = object : GeneratingProvider {
                    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
                        val full = text.substring(node.startOffset, node.endOffset)
                        val label = recordingSeekLink.matchEntire(full)?.groupValues?.get(1)
                        if (label == null) raw.processNode(visitor, text, node)
                        else visitor.consumeHtml("<span class=\"seek-stamp\">${escapeExportHtml(label)}</span>")
                    }
                }
                return providers
            }
        }
        var html = HtmlGenerator(source, MarkdownParser(flavour).buildMarkdownTreeFromString(source), flavour).generateHtml()
        fragments.forEach { (token, value) -> html = html.replace(token, value) }
        return html.replace("<table>", "<div class=\"table-wrap\"><table>").replace("</table>", "</table></div>")
    }
}

internal fun recordingExportMetadata(doc: RecordingExportDocument): List<String> = listOfNotNull(
    "录制时间：${exportTimestamp(doc.createdAt)}",
    "时长：${com.promenar.infinity.recording.recordingTimestamp(doc.durationMs)}",
    "说话人数：${doc.speakerCount}",
    doc.modelSummary?.takeIf(String::isNotBlank)?.let { "整理模型：$it" },
    "导出时间：${exportTimestamp(doc.exportedAt)}",
)

private val recordingSeekLink = Regex("""\[(.*?)\]\(recording://seek/[0-9]+\)""")

private fun exportLiteralRanges(text: String): List<IntRange> {
    val types = setOf(Elements.CODE_FENCE, Elements.CODE_BLOCK, Elements.CODE_SPAN, Elements.INLINE_LINK, Elements.LINK_DEFINITION, Elements.AUTOLINK, Elements.IMAGE)
    val ranges = mutableListOf<IntRange>()
    fun visit(node: ASTNode) {
        if (node.type in types) ranges += node.startOffset until node.endOffset else node.children.forEach(::visit)
    }
    visit(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text))
    return ranges
}

/** 轻量词法着色，绝不把代码当 HTML 或 JavaScript 执行。 */
private fun highlightExportCode(text: String): String {
    val tokens = Regex("""("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|(//[^\n]*|\#[^\n]*)|\b(fun|val|var|class|const|let|def|return|if|else|true|false|null|import|public|private)\b|\b\d+(?:\.\d+)?\b""")
    return buildString {
        var cursor = 0
        tokens.findAll(text).forEach { match ->
            append(escapeExportHtml(text.substring(cursor, match.range.first)))
            val color = when { match.groups[1] != null -> "#126b36"; match.groups[2] != null -> "#667085"; else -> "#7e22ce" }
            append("<span style=\"color:$color\">${escapeExportHtml(match.value)}</span>")
            cursor = match.range.last + 1
        }
        append(escapeExportHtml(text.substring(cursor)))
    }
}

private val exportCss = """
*{box-sizing:border-box}html{background:white;color:#202632;font:16px/1.65 sans-serif}body{margin:0;padding:28px}main{max-width:760px;margin:auto}h1{font-size:28px;line-height:1.3;overflow-wrap:anywhere}.document-head{border-bottom:2px solid #ccd5e0;margin-bottom:28px}.document-head p,.message-head{color:#536174;font-size:13px}.message{border-bottom:1px solid #dbe2ea;padding:16px 20px;margin-bottom:16px;overflow-wrap:anywhere}.user{background:#f3f7fc;border-radius:8px}.message-head{margin:0 0 16px;break-after:avoid;break-inside:avoid}.thinking{color:#566274;border-left:3px solid #b9c7d8;padding:8px 12px}.tool,.attachments,figcaption{font-size:13px;color:#566274}pre{padding:14px;background:#f5f6f8;white-space:pre-wrap;overflow-wrap:anywhere;break-inside:avoid}code{font-family:monospace;font-size:.9em}.table-wrap{overflow-x:auto}table{border-collapse:collapse;width:100%}td,th{border:1px solid #cbd5e1;padding:8px 12px;text-align:left}tr:nth-child(even){background:#f3f6fa}tr{break-inside:avoid}img{max-width:100%;height:auto;break-inside:avoid}figure{margin:16px 0;break-inside:avoid}blockquote{margin-left:0;padding-left:16px;border-left:3px solid #cbd5e1}.math.display{display:block;text-align:center;margin:16px 0}.diagram-target{width:100%;min-height:0}.diagram[data-rendered] .diagram-target{min-height:320px}.diagram[data-diagram=mermaid][data-rendered] .diagram-target{min-height:0}.diagram[data-rendered] .diagram-source{display:none}.snapshot *{animation:none!important;transition:none!important}.pdf{padding:0}.snapshot .table-wrap{overflow:visible}.snapshot table{table-layout:fixed}.snapshot td,.snapshot th{overflow-wrap:anywhere}.snapshot details>summary{display:block}.snapshot details>*{display:block}@page{size:A4;margin:15mm}@media print{body{padding:0}.table-wrap{overflow:visible}table{table-layout:fixed}td,th{overflow-wrap:anywhere}a{color:inherit}details>*{display:block}}
""".trimIndent()

private val recordingExportCss = """
.seek-stamp{display:inline-block;padding:.08em .55em;border:1px solid #9aadc4;border-radius:1em;background:#edf3f9;color:#24364b;font:600 .85em/1.5 monospace}
.recording-export .document-head p{margin:.35rem 0;color:#38475a}
.recording-export .recording-segments{margin:0;padding:0;list-style:none}
.recording-export .recording-segments li{margin:0 0 .9rem;padding:0 0 .6rem;border-bottom:1px solid #dbe2ea;overflow-wrap:anywhere}
.recording-export .recording-segments p{margin:.3rem 0;white-space:pre-wrap}
.unbounded main{max-inline-size:664px;margin-inline:0}
.unbounded .table-wrap{overflow:visible}
.unbounded table{inline-size:max-content;min-inline-size:100%;table-layout:auto}
.unbounded td,.unbounded th{overflow-wrap:normal}
.unbounded pre{inline-size:max-content;min-inline-size:100%;white-space:pre;overflow-wrap:normal}
.unbounded .diagram svg{display:block;max-inline-size:none!important}
.unbounded .katex-display{inline-size:max-content;min-inline-size:100%;overflow:visible}
""".trimIndent()

private val exportRenderScript = """
(async function(){
 window.__sessionExportReady=false;window.__sessionExportErrors=[];
 const tasks=[];
 const unbounded=document.body.classList.contains('unbounded');
 document.querySelectorAll('[data-math]').forEach(el=>{try{if(window.katex)katex.render(el.textContent,el,{displayMode:el.dataset.math==='display',throwOnError:false,trust:false});}catch(e){window.__sessionExportErrors.push('katex');}});
 if(window.mermaid)mermaid.initialize({
  startOnLoad:false,securityLevel:'strict',theme:'default',suppressErrorRendering:true,
  ...(unbounded?{flowchart:{useMaxWidth:false},sequence:{useMaxWidth:false},state:{useMaxWidth:false}}:{})
 });
 document.querySelectorAll('[data-diagram]').forEach((el,i)=>{
  const code=el.querySelector('code').textContent,target=el.querySelector('.diagram-target');
  if(el.dataset.diagram==='mermaid'&&window.mermaid){tasks.push((async()=>{try{
   const r=await mermaid.render('export-diagram-'+i,code);
   target.innerHTML=r.svg;
   const svg=target.querySelector('svg');
   if(unbounded&&svg&&svg.viewBox.baseVal.width>0)svg.style.width=svg.viewBox.baseVal.width+'px';
   if(r.bindFunctions)r.bindFunctions(target);
   el.dataset.rendered='true';
  }catch(e){target.textContent='';window.__sessionExportErrors.push('mermaid');}})());}
  if(el.dataset.diagram==='echarts'&&window.echarts){try{const option=JSON.parse(code);option.animation=false;target.style.height='360px';const chart=echarts.init(target,null,{renderer:'svg'});chart.setOption(option);el.dataset.rendered='true';window.addEventListener('resize',()=>chart.resize());}catch(e){target.textContent='';window.__sessionExportErrors.push('echarts');}}
 });
 await Promise.allSettled(tasks);
 await Promise.all(Array.from(document.images).map(img=>img.complete?Promise.resolve():new Promise(resolve=>{img.onload=resolve;img.onerror=resolve;})));
 if(document.fonts&&document.fonts.ready)await document.fonts.ready;
 // 不依赖离屏页面的 requestAnimationFrame，WebView 未附着窗口时也能完成。
 await new Promise(resolve=>setTimeout(resolve,60));
 window.__sessionExportMetrics=function(){
  const protectedBlocks='.message-head,pre,tr,figure,img'+(unbounded?',.diagram,.math.display,blockquote,h2,h3':'');
  return {
   height:unbounded?document.body.offsetHeight:Math.ceil(Math.max(document.documentElement.scrollHeight,document.body.scrollHeight)),
   width:window.innerWidth,
   pixelScale:(window.visualViewport?window.visualViewport.scale:1)*window.devicePixelRatio,
   scrollWidth:Math.ceil(Math.max(document.documentElement.scrollWidth,document.body.scrollWidth,window.innerWidth)),
   boundaries:Array.from(document.querySelectorAll('.message')).map(el=>Math.floor(el.getBoundingClientRect().top+window.scrollY)),
   blocks:Array.from(document.querySelectorAll(protectedBlocks)).filter(el=>!unbounded||el.getClientRects().length>0).map(el=>{
    const rect=el.getBoundingClientRect();
    return {top:Math.floor(rect.top+window.scrollY),bottom:Math.ceil(rect.bottom+window.scrollY)};
   }),
   errors:window.__sessionExportErrors
  };
 };
 window.__sessionExportReady=true;
})().catch(()=>{window.__sessionExportErrors.push('document');});
""".trimIndent()
