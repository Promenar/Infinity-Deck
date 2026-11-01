package com.promenar.infinity.ui.lab.stage

import android.content.res.Resources
import androidx.compose.runtime.Composable
import com.promenar.infinity.ui.lab.timeline.LabTimeline

/** 版本页给场景的出口：场景里真实组件的返回键都接到 [onExit]。 */
class LabSceneScope(val onExit: () -> Unit)

/** 版本的渲染方式：状态是一屏静止画面，工作流按时间轴回放脚本状态。 */
sealed interface LabScene

class LabStateScene(val content: @Composable LabSceneScope.() -> Unit) : LabScene

/** [timeline] 按当前语言的资源构建，语言或配置变化时重建；[content] 只按传入状态渲染。 */
class LabWorkflowScene<S>(
    val timeline: (Resources) -> LabTimeline<S>,
    val content: @Composable LabSceneScope.(S) -> Unit,
) : LabScene
