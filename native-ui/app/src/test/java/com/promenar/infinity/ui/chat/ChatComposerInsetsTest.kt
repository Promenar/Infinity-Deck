package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import androidx.compose.ui.unit.dp
import org.junit.Test

class ChatComposerInsetsTest {
    @Test
    fun `输入区高度统一驱动列表流式尾部与回底按钮避让`() {
        val insets = chatComposerInsets(132.dp)

        assertThat(insets.contentBottom).isEqualTo(260.dp)
        assertThat(insets.streamingOverlap).isEqualTo(292.dp)
        assertThat(insets.fabBottom).isEqualTo(148.dp)
    }

    @Test
    fun `未完成首次测量时仍提供安全避让`() {
        val insets = chatComposerInsets(0.dp)

        assertThat(insets.contentBottom).isEqualTo(224.dp)
        assertThat(insets.streamingOverlap).isEqualTo(256.dp)
        assertThat(insets.fabBottom).isEqualTo(112.dp)
    }

    @Test
    fun `生成中跟随的尾部留白在滚动到底时可以达到`() {
        val insets = chatComposerInsets(132.dp)
        val tailSpacer = chatItemSpacing(previous = null, current = ChatItemKind.ASSISTANT, isLast = true).afterDp.dp
        // 列表最多滚到：当前条目底边 = 视口底 - 内边距 - 末尾留白条目。
        val reachableGap = insets.contentBottom + ChatGeneratingExtraBottom + tailSpacer

        assertThat(reachableGap).isAtLeast(insets.streamingOverlap)
        // 生成中比静止时多出的留白，让增长中的思考与工具块离输入区至少 32dp。
        assertThat(insets.streamingOverlap - 132.dp).isAtLeast(32.dp)
    }

    @Test
    fun `最后一条消息与浮岛之间常驻留出宠物站立的高度`() {
        val insets = chatComposerInsets(132.dp)
        val tailSpacer = chatItemSpacing(previous = null, current = ChatItemKind.ASSISTANT, isLast = true).afterDp.dp
        // 默认显示高度 130dp 的宠物站在浮岛上，消息底边要在它头顶之上。
        assertThat(insets.contentBottom - 132.dp + tailSpacer).isAtLeast(140.dp)
        assertThat(ChatTailReserve).isEqualTo(128.dp)
    }
}
