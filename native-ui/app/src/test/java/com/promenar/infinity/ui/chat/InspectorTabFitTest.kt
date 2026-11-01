package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 检视器底栏页签按宽度排布：不再横向滚动出半截图标，放不下的收进“更多”。 */
class InspectorTabFitTest {
    private val five = listOf(ChatPage.Run, ChatPage.Explorer, ChatPage.Canvas, ChatPage.Browser, ChatPage.Remote)
    private val six = listOf(ChatPage.Run, ChatPage.Explorer, ChatPage.Git, ChatPage.Canvas, ChatPage.Browser, ChatPage.Remote)

    @Test fun `约 400dp 的侧栏全部显示且当前页签带名称`() {
        // 400dp 检视器去掉边框、内边距、加宽与收起后约 287dp；“任务”名称多占约 32dp。
        val fit = inspectorTabFit(five, ChatPage.Run, availableWidth = 287f, selectedLabelExtra = 32f)
        assertThat(fit).isEqualTo(InspectorTabFit(five, emptyList(), showSelectedLabel = true))
    }

    @Test fun `360dp 检视器名称放不下时只显示图标，五个图标完整放下`() {
        // 360dp 检视器约 247dp：五个 48dp 图标共 240dp 放得下，带名称放不下。
        val fit = inspectorTabFit(five, ChatPage.Run, availableWidth = 247f, selectedLabelExtra = 32f)
        assertThat(fit).isEqualTo(InspectorTabFit(five, emptyList(), showSelectedLabel = false))
        assertThat(inspectorTabFit(five, ChatPage.Run, availableWidth = 240f, selectedLabelExtra = 32f).overflow).isEmpty()
        assertThat(inspectorTabFit(five, ChatPage.Run, availableWidth = 239f, selectedLabelExtra = 32f).overflow).isNotEmpty()
    }

    @Test fun `图标也放不下时末尾留更多按钮，其余页签按顺序收进菜单`() {
        // 生成中多出触摸锁、又有 Git 页签时约 201dp：3 个页签加“更多”按钮。
        val fit = inspectorTabFit(six, ChatPage.Run, availableWidth = 201f, selectedLabelExtra = 36f)
        assertThat(fit.visible).containsExactly(ChatPage.Run, ChatPage.Explorer, ChatPage.Git).inOrder()
        assertThat(fit.overflow).containsExactly(ChatPage.Canvas, ChatPage.Browser, ChatPage.Remote).inOrder()
        assertThat(fit.showSelectedLabel).isFalse()
        // 留在底栏的页签与“更多”按钮都不小于 48dp，总宽不超出可用宽度。
        val used = (fit.visible.size + 1) * 48f
        assertThat(used).isAtMost(201f)
    }

    @Test fun `当前页签被挤出时仍留在底栏，替换最后一个可见页签`() {
        val fit = inspectorTabFit(six, ChatPage.Remote, availableWidth = 201f, selectedLabelExtra = 36f)
        assertThat(fit.visible).containsExactly(ChatPage.Run, ChatPage.Explorer, ChatPage.Remote).inOrder()
        assertThat(fit.overflow).containsExactly(ChatPage.Git, ChatPage.Canvas, ChatPage.Browser).inOrder()
    }

    @Test fun `极窄时至少保留当前页签与更多按钮`() {
        val fit = inspectorTabFit(six, ChatPage.Canvas, availableWidth = 60f, selectedLabelExtra = 36f)
        assertThat(fit.visible).containsExactly(ChatPage.Canvas)
        assertThat(fit.overflow).hasSize(5)
    }

    @Test fun `每个页签都恰好出现一次`() {
        listOf(60f, 120f, 201f, 251f, 400f).forEach { width ->
            ChatPage.entries.forEach { selected ->
                val fit = inspectorTabFit(six, selected, width, selectedLabelExtra = 36f)
                assertThat(fit.visible + fit.overflow).containsExactlyElementsIn(six)
                assertThat(selected in fit.visible).isTrue()
            }
        }
    }
}
