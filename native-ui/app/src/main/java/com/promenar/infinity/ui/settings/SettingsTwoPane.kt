package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.rememberNavController
import com.promenar.infinity.navigation.InfinityPageNavHost
import com.promenar.infinity.navigation.SettingsNavigator
import com.promenar.infinity.navigation.SettingsPaneEntriesSaver
import com.promenar.infinity.navigation.SettingsPaneEntry
import com.promenar.infinity.navigation.SettingsPaneRoots
import com.promenar.infinity.navigation.concreteRoute
import com.promenar.infinity.navigation.isPaneRootEntry
import com.promenar.infinity.navigation.trackSettingsPaneStack
import com.promenar.infinity.ui.MainTabsState
import com.promenar.infinity.ui.hub.UserSettingsHomeScreen

/** 双列时左列（设置目录）宽度。 */
internal val SettingsListPaneWidth = 320.dp

/** 去掉导航栏（80dp）后至少要有这么宽才分两列，右列不窄于约 380dp。 */
private const val SettingsTwoPaneMinBodyWidthDp = 700
private const val NavigationRailWidthDp = 80
private const val SettingsTwoPaneMinHeightDp = 480

/** 窗口足够宽（折叠屏内屏、平板）时设置页分两列；600–780dp 之间仍是单列整页。 */
internal fun settingsTwoPaneAvailable(widthDp: Int, heightDp: Int): Boolean =
    widthDp - NavigationRailWidthDp >= SettingsTwoPaneMinBodyWidthDp && heightDp >= SettingsTwoPaneMinHeightDp

/**
 * 宽屏设置页：左列为设置目录（当前根页面高亮），右列为所选设置页及其下层页面，层级在右列内推进与后退。
 * 右列层级记在 [state] 中，切换标签、窗口重建后按原层级恢复；打开终端、知识图谱等整页内容时经 [onNavigateToSecondary] 交给主导航。
 */
@Composable
internal fun SettingsTwoPane(
    state: MainTabsState,
    onNavigateToSecondary: (String) -> Unit,
    paneGraph: NavGraphBuilder.(SettingsNavigator) -> Unit,
) {
    val paneNav = rememberNavController()
    // 右列层级由栈顶变化推出；与右列导航一同跨 Activity 重建保存，条目 id 在恢复后不变。
    var paneEntries by rememberSaveable(stateSaver = SettingsPaneEntriesSaver) { mutableStateOf(emptyList<SettingsPaneEntry>()) }
    val openOutside by rememberUpdatedState(onNavigateToSecondary)
    val navigator = remember(paneNav) {
        SettingsNavigator(paneNav, outside = { openOutside(it) }, paneRootId = { paneEntries.firstOrNull()?.id })
    }
    // 首次组合时的层级：根页面作为起点，其余在图建立后依次压入。
    val initialStack = remember { state.settingsStack.ifEmpty { listOf(state.settingsLastRoot) } }
    DisposableEffect(paneNav) {
        val listener = NavController.OnDestinationChangedListener { controller, _, _ ->
            val entry = controller.currentBackStackEntry ?: return@OnDestinationChangedListener
            val route = entry.concreteRoute() ?: return@OnDestinationChangedListener
            paneEntries = trackSettingsPaneStack(
                paneEntries, SettingsPaneEntry(entry.id, route), controller.previousBackStackEntry?.id,
            )
            state.settingsStack = paneEntries.map { it.route }
            state.settingsLastRoot = paneEntries.first().route
        }
        paneNav.addOnDestinationChangedListener(listener)
        // 重建后右列导航已恢复原层级时不再重复压入。
        if (paneNav.previousBackStackEntry == null) initialStack.drop(1).forEach { paneNav.navigate(it) }
        onDispose { paneNav.removeOnDestinationChangedListener(listener) }
    }
    val selectRoot: (String) -> Unit = { route ->
        if (route == state.settingsStack.firstOrNull()) {
            // 再点当前条目：回到它的根页面。
            paneNav.popBackStack(route, inclusive = false)
        } else {
            paneNav.navigate(route) { popUpTo(paneNav.graph.id) { inclusive = true } }
        }
    }
    val isPaneRoot: (NavBackStackEntry) -> Boolean = { entry -> isPaneRootEntry(paneEntries, entry.id) }

    Row(modifier = Modifier.fillMaxSize().testTag(SettingsTwoPaneTags.ROOT)) {
        Box(modifier = Modifier.width(SettingsListPaneWidth).fillMaxHeight().testTag(SettingsTwoPaneTags.LIST)) {
            UserSettingsHomeScreen(
                onNavigateToSecondary = { route ->
                    if (route in SettingsPaneRoots) selectRoot(route) else onNavigateToSecondary(route)
                },
                selectedRoute = state.settingsStack.firstOrNull() ?: state.settingsLastRoot,
            )
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        // 右列内前进、后退用视差推入（以右列宽度为准并裁剪在右列内）；左列切换根页面时新页盖在旧页上叠化。
        InfinityPageNavHost(
            navController = paneNav,
            startDestination = initialStack.first(),
            modifier = Modifier.weight(1f).fillMaxHeight().testTag(SettingsTwoPaneTags.DETAIL),
            isSameLevel = isPaneRoot,
        ) {
            paneGraph(navigator)
        }
    }
}

object SettingsTwoPaneTags {
    const val ROOT = "settings_two_pane"
    const val LIST = "settings_two_pane_list"
    const val DETAIL = "settings_two_pane_detail"
}
