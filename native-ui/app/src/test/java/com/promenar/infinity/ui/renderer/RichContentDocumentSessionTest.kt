package com.promenar.infinity.ui.renderer

import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class RichContentDocumentSessionTest {
    @Test
    fun `初始文档只对当前完整随机 HTTPS 主框架 URL 可读`() {
        val session = RichContentDocumentSession()
        val first = session.register("<p>第一份</p>")
        assertThat(first).startsWith("https://appassets.androidplatform.net/assets/preview-")
        assertThat(session.contentFor(first, true)).contains("第一份")
        assertThat(session.contentFor(first, false)).isNull()
        assertThat(session.contentFor("$first?other", true)).isNull()
        assertThat(session.contentFor("$first/other", true)).isNull()
        assertThat(session.register("<p>第一份</p>")).isEqualTo(first)
        val second = session.register("<p>第二份</p>")
        assertThat(second).isNotEqualTo(first)
        assertThat(session.contentFor(first, true)).isNull()
        assertThat(session.contentFor(second, true)).contains("第二份")
    }

    @Test
    fun `安全策略位于用户脚本之前且保留图表脚本样式和 SVG`() {
        val html = secureRichContentDocument("<html><head><script>window.ready = 1;</script><style>p { color:red; }</style></head><body><svg><text>图表</text></svg></body></html>")
        val document = Jsoup.parse(html)
        val meta = document.selectFirst("meta[http-equiv=Content-Security-Policy]")!!
        assertThat(meta.attr("content")).contains("worker-src 'none'")
        assertThat(meta.attr("content")).contains("frame-src 'none'")
        assertThat(meta.attr("content")).contains("media-src https://workspace-media.infinity.invalid;")
        assertThat(html.indexOf("Content-Security-Policy")).isLessThan(html.indexOf("<script>"))
        assertThat(document.selectFirst("script")!!.data()).isEqualTo("window.ready = 1;")
        assertThat(document.selectFirst("style")!!.data()).isEqualTo("p { color:red; }")
        assertThat(document.selectFirst("svg text")!!.text()).isEqualTo("图表")
    }

    @Test
    fun `允许 PlantUML 内嵌 SVG 和字体但拒绝脚本文档顶层与超大资源`() {
        val svg = "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="
        assertThat(richContentInlineResource(svg, false)!!.bytes.toString(Charsets.UTF_8)).isEqualTo("<svg></svg>")
        assertThat(richContentInlineResource("data:font/woff2;base64,AQID", false)!!.bytes).asList().containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
        assertThat(richContentInlineResource(svg, true)).isNull()
        for (url in listOf("data:text/html,<script>alert(1)</script>", "data:application/javascript,alert(1)", "data:image/svg+xml;base64,???", "data:image/png;base64," + "A".repeat(6 * 1024 * 1024))) {
            assertThat(richContentInlineResource(url, false)).isNull()
        }
        assertThat(RichContentRequestPolicy.classify(svg)).isEqualTo(RichContentRequestPolicy.Kind.INLINE_DATA)
    }

    @Test
    fun `百分号编码只接受两个十六进制字符且保留字面加号`() {
        val resource = richContentInlineResource("data:image/svg+xml,%3Csvg%3E+%3C/svg%3E", false)!!
        assertThat(resource.bytes.toString(Charsets.UTF_8)).isEqualTo("<svg>+</svg>")
        for (payload in listOf("%+A", "%-1", "%", "%3", "%GG")) {
            assertThat(richContentInlineResource("data:image/svg+xml,$payload", false)).isNull()
        }
    }
}
