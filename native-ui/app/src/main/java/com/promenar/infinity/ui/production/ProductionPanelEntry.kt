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
 * [pending] 为待用户处理数（角标），[running] 为有镜头正在生成（运行点），[title] 为入口摘要显示的项目名。
 */
data class ProductionEntryState(
    val enabled: Boolean,
    val hasProduction: Boolean,
    val pending: Int,
    val running: Boolean,
    val title: String? = null,
) {
    companion object {
        val Disabled = ProductionEntryState(enabled = false, hasProduction = false, pending = 0, running = false)
    }
}

fun productionEntryState(snapshot: ProductionSnapshot?, everBound: Boolean): ProductionEntryState {
    val production = snapshot?.production
    return ProductionEntryState(
        enabled = everBound || production != null,
        hasProduction = production != null,
        pending = production?.pendingCount() ?: 0,
        running = production?.isGenerating() ?: false,
        title = production?.title,
    )
}

fun ProductionPanelSource.observeEntry(sessionId: String): Flow<ProductionEntryState> =
    combine(observe(sessionId), observeEverBound(sessionId), ::productionEntryState).distinctUntilChanged()
