package com.promenar.infinity.ui.lab.stage

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.promenar.infinity.ui.lab.timeline.LabTimeline

/**
 * 版本页给场景的出口：场景里真实组件的返回键都接到 [onExit]。
 * 真实组件按墙钟显示运行时长，脚本里的时间点（相对脚本开头的毫秒，可为负）经 [wallClock] 换算成墙钟。
 * 静态版本以进入时刻为脚本零点；工作流的零点是“当前墙钟 − 播放位置”，拖动或暂停后继续时重新对齐。
 */
class LabSceneScope(val onExit: () -> Unit) {
    internal var anchorMs by mutableLongStateOf(System.currentTimeMillis())

    fun wallClock(scriptMs: Long): Long = anchorMs + scriptMs
}

/** 版本的渲染方式：状态是一屏静止画面，工作流按时间轴回放脚本状态。 */
sealed interface LabScene

class LabStateScene(val content: @Composable LabSceneScope.() -> Unit) : LabScene

/** [timeline] 按当前语言的资源构建，语言或配置变化时重建；[content] 只按传入状态渲染。 */
class LabWorkflowScene<S>(
    val timeline: (Resources) -> LabTimeline<S>,
    val content: @Composable LabSceneScope.(S) -> Unit,
) : LabScene
