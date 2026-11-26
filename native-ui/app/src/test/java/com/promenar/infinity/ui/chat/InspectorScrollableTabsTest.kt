package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.common.panelTabScrollTarget
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/** 检视器底栏横滑页签、选中自动定位与短标题资源长度防回归测试。 */
class InspectorScrollableTabsTest {
    private fun appPath(relative: String): Path {
        val fromModuleRoot = Path.of("app").resolve(relative)
        return if (Files.exists(fromModuleRoot)) fromModuleRoot else Path.of(relative)
    }

    private fun loadStrings(valuesDir: String): Map<String, String> {
        val dir = appPath("src/main/res/$valuesDir")
        val factory = DocumentBuilderFactory.newInstance()
        val result = linkedMapOf<String, String>()
        Files.list(dir).use { stream ->
            stream.filter { it.toString().endsWith(".xml") }.forEach { xml ->
                val doc = factory.newDocumentBuilder().parse(xml.toFile())
                val nodes = doc.getElementsByTagName("string")
                for (i in 0 until nodes.length) {
                    val el = nodes.item(i) as? Element ?: continue
                    val key = el.getAttribute("name")
                    if (key.isNotEmpty()) result[key] = el.textContent.trim()
                }
            }
        }
        return result
    }

    @Test fun `安全区内选中页签不触发多余滚动，侵入渐隐边缘时准确滚入视野`() {
        val viewport = 250
        val fade = 22
        val tabWidth = 76
        val safeEdge = inspectorTabScrollSafeEdge(viewport, tabWidth, fade)
        assertThat(safeEdge).isEqualTo(22)

        assertThat(panelTabScrollTarget(left = 60, right = 60 + tabWidth, scroll = 0, viewport = viewport, edge = safeEdge))
            .isNull()

        assertThat(panelTabScrollTarget(left = 200, right = 260, scroll = 0, viewport = viewport, edge = safeEdge))
            .isEqualTo(260 + 22 - viewport)

        assertThat(panelTabScrollTarget(left = 10, right = 86, scroll = 20, viewport = viewport, edge = safeEdge))
            .isEqualTo(0)

        assertThat(panelTabScrollTarget(left = 120, right = 196, scroll = 140, viewport = viewport, edge = safeEdge))
            .isEqualTo(120 - 22)

        assertThat(panelTabScrollTarget(left = 200, right = 260, scroll = 0, viewport = 0, edge = safeEdge))
            .isNull()
    }

    @Test fun `极窄视口或宽胶囊下安全边自适应收敛且不震荡`() {
        assertThat(inspectorTabScrollSafeEdge(viewport = 90, tabWidth = 70, fadePx = 22)).isEqualTo(10)
        assertThat(inspectorTabScrollSafeEdge(viewport = 60, tabWidth = 90, fadePx = 22)).isEqualTo(0)
        val narrowEdge = inspectorTabScrollSafeEdge(viewport = 60, tabWidth = 90, fadePx = 22)
        assertThat(panelTabScrollTarget(left = 100, right = 190, scroll = 0, viewport = 60, edge = narrowEdge))
            .isEqualTo(130)
    }

    @Test fun `底栏全部 8 个页签均有映射，且中英文短标题受长度门禁约束`() {
        val hubUi = Files.readString(appPath("src/main/java/com/promenar/infinity/ui/chat/ChatPanelHubUi.kt"))
        val block = hubUi
            .substringAfter("internal fun ChatPage.inspectorTabLabelRes(): Int = when (this) {")
            .substringBefore("\n}")

        val mapped = Regex("""ChatPage\.(\w+)\s*->\s*R\.string\.([a-z0-9_]+)""")
            .findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2] }

        assertThat(mapped.keys).containsExactlyElementsIn(ChatPage.entries.map { it.name })

        val zhStrings = loadStrings("values-zh-rCN")
        val enStrings = loadStrings("values")

        val expectedZh = mapOf(
            "Run" to "任务",
            "Explorer" to "文件",
            "Git" to "Git",
            "Browser" to "浏览器",
            "Remote" to "远程",
            "Canvas" to "产物",
            "State" to "状态",
            "Production" to "制作",
        )
        val expectedEn = mapOf(
            "Run" to "Tasks",
            "Explorer" to "Files",
            "Git" to "Git",
            "Browser" to "Browser",
            "Remote" to "Remote",
            "Canvas" to "Artifacts",
            "State" to "State",
            "Production" to "Production",
        )

        mapped.forEach { (page, key) ->
            val zh = zhStrings[key]
            val en = enStrings[key]
            assertThat(zh).isEqualTo(expectedZh.getValue(page))
            assertThat(en).isEqualTo(expectedEn.getValue(page))
            // 中文严格不超过 3 字，禁止回退到四字长名；英文保持单个短词无空格。
            assertThat(zh!!.length).isAtMost(3)
            assertThat(en!!).doesNotContain(" ")
            assertThat(en.length).isAtMost(10)
        }
    }

    @Test fun `检视器源码已移除三点收纳并接入横滑渐隐与动画减弱支持`() {
        val inspector = Files.readString(appPath("src/main/java/com/promenar/infinity/ui/chat/ChatInspector.kt"))
        assertThat(inspector).doesNotContain("InspectorOverflowTabs")
        assertThat(inspector).doesNotContain("inspectorTabFit")
        assertThat(inspector).doesNotContain("ChatInspectorTags.MORE")
        assertThat(inspector).doesNotContain("chat_inspector_more_tabs")
        assertThat(inspector).contains(".horizontalScroll(scrollState)")
        assertThat(inspector).contains(".horizontalFadingEdges(scrollState")
        assertThat(inspector).contains("rememberAnimationsEnabled()")
        assertThat(inspector).contains("LocalInspectionMode.current")
        assertThat(inspector).contains("inspectorTabLabel()")
        assertThat(inspector).contains("softWrap = false")
        assertThat(inspector).contains("R.string.chat_inspector_badge_send")
    }

    @Test fun `检视器底栏选中制作页签或存在待发送项时并列内联双角标其余保持图标角标`() {
        val prodBoth = PanelHubEntry(ChatPage.Production, summary = "雾港", pending = 1, running = false, available = true, onOpen = {}, secondaryPending = 4)
        val prodReviewOnly = prodBoth.copy(secondaryPending = 0)
        val prodSendOnly = prodBoth.copy(pending = 0)
        val prodNone = prodBoth.copy(pending = 0, secondaryPending = 0)
        val runPending = PanelHubEntry(ChatPage.Run, summary = null, pending = 2, running = true, available = true, onOpen = {})

        assertThat(shouldShowInlineInspectorBadges(prodBoth, selected = true)).isTrue()
        assertThat(shouldShowInlineInspectorBadges(prodReviewOnly, selected = true)).isTrue()
        assertThat(shouldShowInlineInspectorBadges(prodSendOnly, selected = true)).isTrue()
        assertThat(shouldShowInlineInspectorBadges(prodNone, selected = true)).isFalse()
        assertThat(shouldShowInlineInspectorBadges(prodBoth, selected = false)).isFalse()
        assertThat(shouldShowInlineInspectorBadges(prodBoth.copy(available = false), selected = true)).isFalse()
        assertThat(shouldShowInlineInspectorBadges(runPending, selected = true)).isFalse()
    }
}
