package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Test

class MarkdownMediaGroupsTest {
    private val legacyRoots = listOf("/data/files/generated_images")

    private fun image(path: String, label: String?) = MarkdownMediaReference(path, label, isLink = false)

    private fun parse(text: String) = parseMarkdownMediaGroup(text, legacyRoots)

    private fun firstFence(node: ASTNode): ASTNode? =
        if (node.type == MarkdownElementTypes.CODE_FENCE) node else node.children.firstNotNullOfOrNull(::firstFence)

    private fun groupOfMarkdown(markdown: String, visibleEnd: Int = markdown.length): MarkdownMediaGroup? {
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
        val fence = firstFence(visibleMarkdownNode(root, visibleEnd)) ?: return null
        return markdownMediaGroupOf(fence, markdown, legacyRoots)
    }

    @Test
    fun `识别gallery与carousel信息串，大小写与附加词不影响`() {
        assertThat(markdownMediaGroupLayout("gallery")).isEqualTo(MarkdownMediaGroupLayout.GALLERY)
        assertThat(markdownMediaGroupLayout(" Gallery ")).isEqualTo(MarkdownMediaGroupLayout.GALLERY)
        assertThat(markdownMediaGroupLayout("CAROUSEL 旅行")).isEqualTo(MarkdownMediaGroupLayout.CAROUSEL)
        assertThat(markdownMediaGroupLayout("kotlin")).isNull()
        assertThat(markdownMediaGroupLayout("galleryx")).isNull()
        assertThat(markdownMediaGroupLayout("")).isNull()
        assertThat(markdownMediaGroupLayout(null)).isNull()
    }

    @Test
    fun `块内每行一个媒体引用，按顺序解析说明与路径`() {
        val group = parse(
            """
            ```gallery
            ![海边](media/a.png)
            ![](/media/b.jpg)
            ![含空格](</media/c d.webp>)
            ![带标题](media/e.png "标题")
            ```
            """.trimIndent(),
        )!!
        assertThat(group.layout).isEqualTo(MarkdownMediaGroupLayout.GALLERY)
        assertThat(group.items).containsExactly(
            image("media/a.png", "海边"),
            image("/media/b.jpg", null),
            image("/media/c d.webp", "含空格"),
            image("media/e.png", "带标题"),
        ).inOrder()
        assertThat(group.trailing).isEmpty()
        assertThat(group.rendersAsColumn).isFalse()
    }

    @Test
    fun `无法解析、远程、旧生图与被拦截的行忽略`() {
        val group = parse(
            """
            ```carousel
            这是一段说明
            ![远程](https://example.com/a.png)
            ![旧生图](/data/files/generated_images/a.png)
            ![拦截](file:///sdcard/a.png)
            [普通链接](media/x.png)
            ![a](media/a.png) 后面还有字
            ![第一张](media/1.png)

            ![第二张](media/2.mp4)
            ```
            """.trimIndent(),
        )!!
        assertThat(group.layout).isEqualTo(MarkdownMediaGroupLayout.CAROUSEL)
        assertThat(group.items).containsExactly(image("media/1.png", "第一张"), image("media/2.mp4", "第二张")).inOrder()
    }

    @Test
    fun `音频与非媒体文件拆到块下方，视频留在矩阵中`() {
        val group = parse(
            """
            ```gallery
            ![图](media/a.png)
            ![旁白](media/voice.m4a)
            ![片段](media/clip.mp4)
            ![报告](docs/report.pdf)
            ![配乐](media/bgm.mp3)
            ```
            """.trimIndent(),
        )!!
        assertThat(group.items).containsExactly(image("media/a.png", "图"), image("media/clip.mp4", "片段")).inOrder()
        assertThat(group.trailing).containsExactly(
            image("media/voice.m4a", "旁白"),
            image("docs/report.pdf", "报告"),
            image("media/bgm.mp3", "配乐"),
        ).inOrder()
    }

    @Test
    fun `只有一个可视项时回退为默认单列`() {
        val single = parse("```gallery\n![唯一](media/a.png)\n![旁白](media/v.m4a)\n```")!!
        assertThat(single.items).hasSize(1)
        assertThat(single.rendersAsColumn).isTrue()
        val empty = parse("```carousel\n```")!!
        assertThat(empty.items).isEmpty()
        assertThat(empty.rendersAsColumn).isTrue()
    }

    @Test
    fun `普通代码块与非围栏文本不受影响`() {
        assertThat(parse("```kotlin\n![a](media/a.png)\n```")).isNull()
        assertThat(parse("```\n![a](media/a.png)\n```")).isNull()
        assertThat(parse("![a](media/a.png)")).isNull()
        assertThat(parse("``gallery\n![a](media/a.png)")).isNull()
        assertThat(groupOfMarkdown("```python\nprint(1)\n```")).isNull()
    }

    @Test
    fun `结尾围栏之后的行不进入分组，波浪线围栏同样识别`() {
        val group = parse("~~~gallery\n![a](media/a.png)\n![b](media/b.png)\n~~~\n![c](media/c.png)")!!
        assertThat(group.items.map { it.path }).containsExactly("media/a.png", "media/b.png").inOrder()
    }

    @Test
    fun `未闭合围栏按已写完的行渐进解析，末行未写完时忽略`() {
        val partial = parse("```gallery\n![a](media/a.png)\n![b](media/b.png)\n![c](media/c.pn")!!
        assertThat(partial.items.map { it.path }).containsExactly("media/a.png", "media/b.png").inOrder()
        val headerOnly = parse("```carousel")!!
        assertThat(headerOnly.items).isEmpty()
    }

    @Test
    fun `语法树中的未闭合围栏也渐进解析`() {
        val markdown = "前文\n\n```gallery\n![a](media/a.png)\n![b](media/b.png)\n![c](media/c"
        val group = groupOfMarkdown(markdown)!!
        assertThat(group.layout).isEqualTo(MarkdownMediaGroupLayout.GALLERY)
        assertThat(group.items.map { it.path }).containsExactly("media/a.png", "media/b.png").inOrder()
    }

    @Test
    fun `逐字放出时信息串未放完也识别为媒体块，块内只取已放出的行`() {
        val markdown = "```gallery\n![a](media/a.png)\n![b](media/b.png)\n```"
        val headerCut = groupOfMarkdown(markdown, visibleEnd = "```gal".length)!!
        assertThat(headerCut.layout).isEqualTo(MarkdownMediaGroupLayout.GALLERY)
        assertThat(headerCut.items).isEmpty()
        val bodyCut = groupOfMarkdown(markdown, visibleEnd = markdown.indexOf("![b]") + 6)!!
        assertThat(bodyCut.items.map { it.path }).containsExactly("media/a.png")
    }

    @Test
    fun `列表与引用块内的媒体围栏去掉容器前缀后解析`() {
        val inList = groupOfMarkdown("- 素材：\n\n  ```carousel\n  ![a](media/a.png)\n  ![b](media/b.png)\n  ```\n")!!
        assertThat(inList.items.map { it.path }).containsExactly("media/a.png", "media/b.png").inOrder()
        val inQuote = groupOfMarkdown("> ```gallery\n> ![a](media/a.png)\n> ![b](media/b.png)\n> ```\n")!!
        assertThat(inQuote.items.map { it.path }).containsExactly("media/a.png", "media/b.png").inOrder()
    }

    @Test
    fun `只读首行判断是否为媒体围栏`() {
        val markdown = "```carousel\n![a](media/a.png)\n```\n\n```js\nx\n```"
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
        val fences = root.children.filter { it.type == MarkdownElementTypes.CODE_FENCE }
        assertThat(fences.map { isMarkdownMediaGroupFence(it, markdown) }).containsExactly(true, false).inOrder()
    }
}
