package com.promenar.infinity.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinitySpacing

internal data class ChatComposerInsets(
    /** 静止时列表底部内边距：输入区高度加 [ChatTailReserve]；末尾留白条目再隔开 16dp。 */
    val contentBottom: Dp,
    /** 自动跟随时目标条目底边距视口底部的距离，即在尾部留白上方再留出 32dp。 */
    val streamingOverlap: Dp,
    val fabBottom: Dp,
)

/**
 * 最后一条消息与输入浮岛之间的常驻留白。站在浮岛上的宠物占据这段高度，不遮挡消息底部的操作行；
 * 没有宠物时作为消息与输入区之间的呼吸空间。
 */
internal val ChatTailReserve: Dp = 128.dp

/** 生成中列表底部额外的内边距，使 [ChatComposerInsets.streamingOverlap] 在滚动到底时也能达到。 */
internal val ChatGeneratingExtraBottom: Dp = InfinitySpacing.Large

internal fun chatComposerInsets(composerHeight: Dp): ChatComposerInsets {
    val measuredHeight = if (composerHeight > 0.dp) composerHeight else 96.dp
    return ChatComposerInsets(
        contentBottom = measuredHeight + ChatTailReserve,
        streamingOverlap = measuredHeight + ChatTailReserve + InfinitySpacing.XXLarge,
        fabBottom = measuredHeight + InfinitySpacing.Large,
    )
}
