package com.promenar.infinity.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.compose.rememberNavController
import com.promenar.infinity.navigation.InfinityPageNavHost
import com.promenar.infinity.navigation.SettingsNavigator

/** 媒体设置这组目的地的路由首段。 */
private val MediaSettingsRouteBases = setOf("media_models", "media_model", "comfy_host", "comfy_host_form", "comfy_template")

/** [route] 是否属于媒体设置这组目的地（媒体模型、模型详情、主机、主机表单、模板）。 */
internal fun isMediaSettingsRoute(route: String): Boolean =
    route.substringBefore('?').substringBefore('/') in MediaSettingsRouteBases

object MediaSettingsPanelHostTags {
    const val ROOT = "media_settings_panel_host"
}

/**
 * 制作面板内的媒体设置子页：独立导航栈，起点为媒体模型列表，主机、模板、主机表单都在栈内前进与后退。
 * 栈底返回（返回键或系统返回）调用 [onClose]；打开媒体设置以外的页面（例如新会话）时先关闭子页，再交给 [openOutside]。
 * 系统栏留白由外层容器负责，子页不再叠加。
 */
@Composable
internal fun MediaSettingsPanelHost(
    onClose: () -> Unit,
    openOutside: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nav = rememberNavController()
    val close by rememberUpdatedState(onClose)
    val outside by rememberUpdatedState(openOutside)
    val navigator = remember(nav) {
        SettingsNavigator(
            nav,
            outside = { route ->
                close()
                outside(route)
            },
            handles = ::isMediaSettingsRoute,
            onExhausted = { close() },
        )
    }
    // 先于导航栈注册：栈内有下层页面时由导航栈先消费返回，栈底才关闭子页。
    BackHandler { close() }
    InfinityPageNavHost(
        navController = nav,
        startDestination = MediaSettingsRoutes.MODELS,
        modifier = modifier.fillMaxSize().consumeWindowInsets(WindowInsets.systemBars).testTag(MediaSettingsPanelHostTags.ROOT),
    ) {
        mediaSettingsDestinations(navigator)
    }
}
