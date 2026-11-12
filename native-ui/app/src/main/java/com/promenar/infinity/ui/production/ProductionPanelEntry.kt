package com.promenar.infinity.ui.production

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.ui.graphics.vector.ImageVector
import com.promenar.infinity.R
import com.promenar.infinity.data.production.ProductionPanelSource
import com.promenar.infinity.data.production.ProductionSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** 功能面板入口的名称与图标。 */
object ProductionPanelEntry {
    val titleRes: Int = R.string.production_panel_title
    val icon: ImageVector get() = Icons.Rounded.Movie
}

/**
 * 功能面板入口所需的制作面板状态。
 * [enabled] 为会话曾经绑定过项目；从未绑定时入口置灰、排在末尾。
 * [pending] 为待审镜头数，[pendingApprovals] 为待批准任务数，[attentionCount] 为入口角标；[running] 为有镜头正在生成（运行点），[title] 为入口摘要显示的项目名。
 */
data class ProductionEntryState(
    val enabled: Boolean,
    val hasProduction: Boolean,
    val pending: Int,
    val running: Boolean,
    val title: String? = null,
    val pendingApprovals: Int = 0,
) {
    val attentionCount: Int get() = pending + pendingApprovals
    companion object {
        val Disabled = ProductionEntryState(enabled = false, hasProduction = false, pending = 0, running = false)
    }
}

fun productionEntryState(snapshot: ProductionSnapshot?, everBound: Boolean, tasks: List<com.promenar.infinity.domain.mediagen.MediaTask> = emptyList()): ProductionEntryState {
    val production = snapshot?.production
    return ProductionEntryState(
        enabled = everBound || production != null,
        hasProduction = production != null,
        pending = production?.pendingCount() ?: 0,
        running = production != null && (production.isGenerating() || tasks.any { it.isActiveTask() }),
        title = production?.title,
        pendingApprovals = if (production == null) 0 else tasks.count { it.state == com.promenar.infinity.domain.mediagen.MediaTaskState.AWAITING_APPROVAL },
    )
}

fun ProductionPanelSource.observeEntry(sessionId: String): Flow<ProductionEntryState> =
    combine(observe(sessionId), observeEverBound(sessionId)) { snapshot, bound -> productionEntryState(snapshot, bound) }.distinctUntilChanged()

/** 宿主提供规划器使用的项目 sourceRef 前缀；只统计该项目的批准任务。 */
fun ProductionPanelSource.observeEntry(
    sessionId: String,
    queue: com.promenar.infinity.domain.mediagen.MediaGenerationQueue,
    projectSourcePrefix: (com.promenar.infinity.data.production.Production) -> String,
): Flow<ProductionEntryState> = combine(observe(sessionId), observeEverBound(sessionId), queue.observeTasks()) { snapshot, bound, tasks ->
    productionEntryState(snapshot, bound, projectTasks(tasks, snapshot?.production?.let(projectSourcePrefix)))
}.distinctUntilChanged()
