package com.promenar.infinity.ui.common

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InfinityNoticeControllerTest {

    @Test
    fun `show 规范化空白并顶替旧消息 dismiss 按标识精确关闭`() {
        val controller = InfinityNoticeController()
        assertThat(controller.current).isNull()
        assertThat(controller.show(InfinityNoticeSeverity.INFO, "   \n  ")).isEqualTo(-1L)
        assertThat(controller.current).isNull()

        val firstId = controller.show(InfinityNoticeSeverity.INFO, "  已撤回审批  ", subtitle = " 10 分钟内生效 ")
        assertThat(firstId).isGreaterThan(0L)
        assertThat(controller.current?.message).isEqualTo("已撤回审批")
        assertThat(controller.current?.subtitle).isEqualTo("10 分钟内生效")

        // 新通知直接顶替旧通知。
        val secondId = controller.showStatus(InfinityStatusKind.Success, "已保存修改")
        assertThat(secondId).isGreaterThan(firstId)
        assertThat(controller.current?.id).isEqualTo(secondId)
        assertThat(controller.current?.severity).isEqualTo(InfinityNoticeSeverity.SUCCESS)

        // 过期 id 调用 dismiss 不得误关新通知。
        controller.dismiss(firstId)
        assertThat(controller.current?.id).isEqualTo(secondId)

        controller.dismiss(secondId)
        assertThat(controller.current).isNull()
    }

    @Test
    fun `NoticeClearanceStack 按注册栈序后入优先与注销恢复`() {
        val stack = NoticeClearanceStack(fallback = DEFAULT_NOTICE_TOP_OFFSET)
        assertThat(stack.active).isEqualTo(DEFAULT_NOTICE_TOP_OFFSET)

        val chatToken = stack.register(76.dp)
        assertThat(stack.active).isEqualTo(76.dp)

        // 弹出全屏查看器覆盖顶栏。
        val lightboxToken = stack.register(12.dp)
        assertThat(stack.active).isEqualTo(12.dp)

        // 更新已压底的聊天页顶栏高度时，当前栈顶仍优先。
        stack.update(chatToken, 82.dp)
        assertThat(stack.active).isEqualTo(12.dp)

        // 关闭查看器后恢复聊天页最新上报高度；最后注销回退默认值。
        stack.unregister(lightboxToken)
        assertThat(stack.active).isEqualTo(82.dp)
        stack.unregister(chatToken)
        assertThat(stack.active).isEqualTo(DEFAULT_NOTICE_TOP_OFFSET)

        // 负值偏移会被夹紧到 0dp。
        val clampedToken = stack.register((-10).dp)
        assertThat(stack.active).isEqualTo(0.dp)
        stack.unregister(clampedToken)
    }

    @Test
    fun `内屏水平避让在剩余宽度足够时避让检视器不足 320dp 时回退居中`() {
        // 840dp 内屏展开检视器（左轨 72dp + 右侧 392dp => 剩余 376dp >= 320dp）：保留右避让。
        val split = computeNoticeHorizontalPlacement(
            maxWidth = 840.dp,
            startInset = 72.dp,
            rawEndInset = 392.dp,
        )
        assertThat(split.startInset).isEqualTo(72.dp)
        assertThat(split.endInset).isEqualTo(392.dp)
        assertThat(split.maxCapsuleWidth).isEqualTo(344.dp)

        // 680dp 窗口下右侧 360dp + 左 72dp => 剩余 248dp < 320dp：自动放弃右避让回退居中。
        val fallback = computeNoticeHorizontalPlacement(
            maxWidth = 680.dp,
            startInset = 72.dp,
            rawEndInset = 360.dp,
        )
        assertThat(fallback.startInset).isEqualTo(72.dp)
        assertThat(fallback.endInset).isEqualTo(0.dp)
        assertThat(fallback.maxCapsuleWidth).isEqualTo(420.dp)

        // 390dp 常规竖屏手机（无左右栏）：可用宽度 358dp。
        val phone = computeNoticeHorizontalPlacement(
            maxWidth = 390.dp,
            startInset = 0.dp,
            rawEndInset = 0.dp,
        )
        assertThat(phone.endInset).isEqualTo(0.dp)
        assertThat(phone.maxCapsuleWidth).isEqualTo(358.dp)
    }
}
