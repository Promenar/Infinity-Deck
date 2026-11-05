package com.promenar.infinity.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import com.promenar.infinity.ui.common.motion.IndexIndicatorPainter
import com.promenar.infinity.ui.common.motion.IndexMotionState
import com.promenar.infinity.ui.common.motion.drawIndexingIndicator
import com.promenar.infinity.ui.common.motion.indexIndicatorDesignSize
import com.promenar.infinity.ui.common.motion.indexingKind
import com.promenar.infinity.ui.common.motion.rememberMotionClock
import com.promenar.infinity.ui.common.motion.rememberMotionPalette
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.theme.GenerationIndicatorStyle
import com.promenar.infinity.ui.theme.LocalInfinityMotionStyles
import com.promenar.infinity.ui.theme.infinityDomainColors

/**
 * 资料索引指示：样式跟随外观设置里的生成状态指示，按 [state] 的阶段与进度绘制。
 * 它总与一行状态文字并排出现，读屏读那行文字，这里不再重复描述。
 * 系统关闭动画或静态预览时停在固定时刻，只保留进度。
 */
@Composable
fun IndexingIndicator(
    state: IndexMotionState,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    style: GenerationIndicatorStyle = LocalInfinityMotionStyles.current.indicator,
) {
    val kind = style.indexingKind()
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val clock = rememberMotionClock(running = animate)
    val palette = rememberMotionPalette()
    val painter = remember { IndexIndicatorPainter() }
    val amber = MaterialTheme.infinityDomainColors.warning
    val designSize = indexIndicatorDesignSize(kind)
    Canvas(modifier.size(designSize.width * scale, designSize.height * scale)) {
        val time = if (animate) clock.time else 0.37f
        drawIndexingIndicator(kind, state, time, density * scale, palette, painter, amber)
    }
}
