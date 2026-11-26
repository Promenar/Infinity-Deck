package com.promenar.infinity.ui.canvas

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.artifact.ArtifactKind
import com.promenar.infinity.data.canvas.CanvasDocument
import com.promenar.infinity.data.canvas.CanvasKind
import com.promenar.infinity.data.canvas.CanvasMetadata
import com.promenar.infinity.data.canvas.CanvasScreenLinks
import com.promenar.infinity.data.canvas.CanvasScreenSource
import org.junit.Test

class PrototypeViewerLogicTest {
    @Test fun `屏间链接只接受合法屏幕ID`() {
        assertThat(CanvasScreenLinks.screenId("infinity-screen:home")).isEqualTo("home")
        assertThat(CanvasScreenLinks.screenId("infinity-screen://bill-2/")).isEqualTo("bill-2")
        assertThat(CanvasScreenLinks.screenId("infinity-screen:Home")).isNull()
        assertThat(CanvasScreenLinks.screenId("infinity-screen:-x")).isNull()
        assertThat(CanvasScreenLinks.screenId("https://example.com")).isNull()
        assertThat(CanvasScreenLinks.screenId("infinity-screen:")).isNull()
    }

    @Test fun `画板列数与缩放档位`() {
        assertThat(prototypeBoardColumns(1)).isEqualTo(1)
        assertThat(prototypeBoardColumns(2)).isEqualTo(2)
        assertThat(prototypeBoardColumns(7)).isEqualTo(3)
        assertThat(prototypeZoomStep(0.42f, zoomIn = true)).isEqualTo(0.6f)
        assertThat(prototypeZoomStep(0.42f, zoomIn = false)).isEqualTo(0.4f)
        assertThat(prototypeZoomStep(1.5f, zoomIn = true)).isEqualTo(1.5f)
        assertThat(prototypeZoomStep(0.25f, zoomIn = false)).isEqualTo(0.25f)
    }

    @Test fun `复制原型源码时各屏按顺序带注释拼接，页面原样复制`() {
        val metadata = CanvasMetadata("c", "原型", 0, 0, 1, "m", listOf(1), kind = CanvasKind.PROTOTYPE)
        val prototype = CanvasDocument(metadata, "<p>a</p>", 1, listOf(
            CanvasScreenSource("home", "首页", "<p>a</p>"), CanvasScreenSource("add", "记一笔", "<p>b</p>"),
        ))
        assertThat(prototypeSource(prototype)).isEqualTo("<!-- screen: home · 首页 -->\n<p>a</p>\n\n<!-- screen: add · 记一笔 -->\n<p>b</p>")
        assertThat(prototypeSource(prototype.copy(screens = emptyList()))).isEqualTo("<p>a</p>")
    }

    @Test fun `文件产物按类型与大小选择预览方式`() {
        assertThat(fileArtifactPreview(ArtifactKind.IMAGE, "a.png", 10)).isEqualTo(FileArtifactPreview.IMAGE)
        assertThat(fileArtifactPreview(ArtifactKind.IMAGE, "a.svg", 10)).isEqualTo(FileArtifactPreview.PAGE)
        assertThat(fileArtifactPreview(ArtifactKind.OTHER, "speech.wav", 68_900)).isEqualTo(FileArtifactPreview.AUDIO)
        assertThat(fileArtifactPreview(ArtifactKind.OTHER, "score.mp3", 2_400_000)).isEqualTo(FileArtifactPreview.AUDIO)
        assertThat(fileArtifactPreview(ArtifactKind.OTHER, "voice.m4a", 120_000)).isEqualTo(FileArtifactPreview.AUDIO)
        assertThat(fileArtifactPreview(ArtifactKind.OTHER, "bgm.flac", 8_000_000)).isEqualTo(FileArtifactPreview.AUDIO)
        assertThat(fileArtifactPreview(ArtifactKind.DOCUMENT, "a.md", 10)).isEqualTo(FileArtifactPreview.MARKDOWN)
        assertThat(fileArtifactPreview(ArtifactKind.DOCUMENT, "a.md", 600_000)).isEqualTo(FileArtifactPreview.EXTERNAL)
        assertThat(fileArtifactPreview(ArtifactKind.OTHER, "a.csv", 10)).isEqualTo(FileArtifactPreview.TEXT)
        assertThat(fileArtifactPreview(ArtifactKind.PAGE, "index.html", 10)).isEqualTo(FileArtifactPreview.PAGE)
        assertThat(fileArtifactPreview(ArtifactKind.DOCUMENT, "a.pdf", 10)).isEqualTo(FileArtifactPreview.EXTERNAL)
    }
}
