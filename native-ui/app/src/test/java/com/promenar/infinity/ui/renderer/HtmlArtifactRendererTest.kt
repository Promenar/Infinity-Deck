package com.promenar.infinity.ui.renderer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HtmlArtifactRendererTest {
    @Test
    fun `isLikelyRenderableHtml accepts inline media tags and rejects tool call tags`() {
        val videoSnippet = """<video controls playsinline src="https://workspace-media.infinity.invalid/workspace/media/2026-11-22/demo.mp4"></video>"""
        assertThat(isLikelyRenderableHtml(videoSnippet)).isTrue()
        assertThat(isLikelyRenderableHtml("""<audio controls src="https://workspace-media.infinity.invalid/workspace/media/a.wav"></audio>""")).isTrue()
        assertThat(isLikelyRenderableHtml("""<figure><img src="https://workspace-media.infinity.invalid/workspace/media/a.png"></figure>""")).isTrue()
        assertThat(isLikelyRenderableHtml("""<tool_call><p>ignore</p></tool_call>""")).isFalse()
    }

    @Test
    fun `ensureHtmlArtifactDocument wraps media fragments without escaping while keeping full documents untouched`() {
        val videoFragment = """<video controls playsinline src="https://workspace-media.infinity.invalid/workspace/media/demo.mp4"></video>"""
        val wrapped = ensureHtmlArtifactDocument(videoFragment)
        assertThat(wrapped).startsWith("<!DOCTYPE html>")
        assertThat(wrapped).contains("<body>$videoFragment</body>")
        assertThat(wrapped).doesNotContain("&lt;video")

        val fullDoc = "<!DOCTYPE html><html><head></head><body><p>ok</p></body></html>"
        assertThat(ensureHtmlArtifactDocument(fullDoc)).isEqualTo(fullDoc)
    }
}
