package com.promenar.infinity.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * 铺满整屏的对话框：窗口延伸到系统栏下方、不压暗背后界面，并按 [systemBarColor] 的明暗
 * 设置状态栏与导航栏图标颜色——对话框窗口不继承主窗口的图标设置，不处理时浅色主题下会出现白底白字的状态栏。
 * 系统栏留白由内容自己处理：顶栏背景应延伸到状态栏下方，且与 [systemBarColor] 同色。
 */
@Composable
fun InfinityFullscreenDialog(
    onDismissRequest: () -> Unit,
    systemBarColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val lightBars = systemBarColor.luminance() > 0.5f
        DisposableEffect(view, lightBars) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
            onDispose {}
        }
        CompositionLocalProvider(LocalRowHeightGroup provides null) {
            content()
        }
    }
}
