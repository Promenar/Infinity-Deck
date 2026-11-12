package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatPagesTest {
    @Test fun `会话入口以该页为根，替换已有层级`() {
        assertThat(openChatPageRoot(ChatPage.Git)).containsExactly(ChatPage.Git)
        assertThat(openChatPageRoot(ChatPage.Explorer)).containsExactly(ChatPage.Explorer)
    }

    @Test fun `资源管理器上打开 Git 叠为第二层，返回逐层弹出`() {
        val stacked = pushChatPage(openChatPageRoot(ChatPage.Explorer), ChatPage.Git)
        assertThat(stacked).containsExactly(ChatPage.Explorer, ChatPage.Git).inOrder()
        assertThat(pushChatPage(stacked, ChatPage.Git)).isEqualTo(stacked)
        assertThat(popChatPage(stacked)).containsExactly(ChatPage.Explorer)
        assertThat(popChatPage(popChatPage(stacked))).isEmpty()
    }

    @Test fun `已在层级中的页移到最上层，不重复叠放`() {
        val reordered = pushChatPage(listOf(ChatPage.Explorer, ChatPage.Git), ChatPage.Explorer)
        assertThat(reordered).containsExactly(ChatPage.Git, ChatPage.Explorer).inOrder()
    }

    @Test fun `层级可保存恢复，未知与重复片段被忽略`() {
        val pages = listOf(ChatPage.Explorer, ChatPage.Git)
        assertThat(restoreChatPages(saveChatPages(pages))).isEqualTo(pages)
        assertThat(restoreChatPages("")).isEmpty()
        assertThat(restoreChatPages("explorer,unknown,git,git")).isEqualTo(pages)
    }

    @Test fun `任务页签只属于检视器，窄窗口产物与文件、浏览器一样整页打开`() {
        val pages = listOf(ChatPage.Run)
        assertThat(restoreChatPages(saveChatPages(pages))).isEqualTo(pages)
        assertThat(restoreChatPages("canvas")).containsExactly(ChatPage.Canvas)
        assertThat(chatPageStack(listOf(ChatPage.Run), wideWindow = false)).isEmpty()
        assertThat(chatPageStack(listOf(ChatPage.Canvas), wideWindow = false)).containsExactly(ChatPage.Canvas)
        assertThat(chatPageStack(listOf(ChatPage.Explorer, ChatPage.Git), wideWindow = false)).containsExactly(ChatPage.Explorer, ChatPage.Git).inOrder()
    }

    @Test fun `宽窗口的产物由检视器页签承载，不进整页层级`() {
        assertThat(chatPageStack(listOf(ChatPage.Canvas), wideWindow = true)).isEmpty()
        assertThat(chatPageStack(listOf(ChatPage.Run), wideWindow = true)).isEmpty()
        assertThat(chatPageStack(listOf(ChatPage.Explorer, ChatPage.Git), wideWindow = true)).containsExactly(ChatPage.Explorer, ChatPage.Git).inOrder()
    }

    @Test fun `顶栏图标在已打开的根层页签上收起，否则以该页签为根打开`() {
        assertThat(toggleChatInspector(emptyList(), ChatPage.Explorer)).containsExactly(ChatPage.Explorer)
        assertThat(toggleChatInspector(listOf(ChatPage.Explorer), ChatPage.Explorer)).isEmpty()
        assertThat(toggleChatInspector(listOf(ChatPage.Run), ChatPage.Explorer)).containsExactly(ChatPage.Explorer)
        // 资源管理器上叠着 Git 时点文件图标：回到文件根层，而不是收起。
        assertThat(toggleChatInspector(listOf(ChatPage.Explorer, ChatPage.Git), ChatPage.Explorer)).containsExactly(ChatPage.Explorer)
    }

    @Test fun `展开检视器沿用上次页签，没有时按是否有运行内容选择`() {
        assertThat(defaultChatInspectorRoot(ChatPage.Git, hasRunContent = true)).isEqualTo(ChatPage.Git)
        assertThat(defaultChatInspectorRoot(null, hasRunContent = true)).isEqualTo(ChatPage.Run)
        assertThat(defaultChatInspectorRoot(null, hasRunContent = false)).isEqualTo(ChatPage.Explorer)
    }

    @Test fun `展开检视器时上次的页签已不可用就按没有上次处理`() {
        assertThat(defaultChatInspectorRoot(ChatPage.State, hasRunContent = false, available = setOf(ChatPage.Run, ChatPage.Explorer)))
            .isEqualTo(ChatPage.Explorer)
        assertThat(defaultChatInspectorRoot(ChatPage.State, hasRunContent = false, available = setOf(ChatPage.State))).isEqualTo(ChatPage.State)
    }

    @Test fun `状态面板只是检视器页签，窄窗口由运行详情承载，不进整页层级`() {
        assertThat(chatPageStack(listOf(ChatPage.State), wideWindow = false)).isEmpty()
        assertThat(chatPageStack(listOf(ChatPage.State), wideWindow = true)).isEmpty()
        assertThat(restoreChatPages(saveChatPages(listOf(ChatPage.State)))).containsExactly(ChatPage.State)
        assertThat(ChatPage.State.inspectorGroup).isEqualTo(InspectorContentGroup.State)
    }

    @Test fun `制作面板窄窗口整页进入层级，宽窗口只是检视器页签`() {
        assertThat(chatPageStack(listOf(ChatPage.Production), wideWindow = false)).containsExactly(ChatPage.Production)
        assertThat(chatPageStack(listOf(ChatPage.Production, ChatPage.Explorer), wideWindow = false))
            .containsExactly(ChatPage.Production, ChatPage.Explorer).inOrder()
        assertThat(chatPageStack(listOf(ChatPage.Production), wideWindow = true)).isEmpty()
        assertThat(restoreChatPages(saveChatPages(listOf(ChatPage.Production)))).containsExactly(ChatPage.Production)
        assertThat(ChatPage.Production.inspectorGroup).isEqualTo(InspectorContentGroup.Production)
    }
}
