package com.promenar.infinity.ui.common

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class InfinityInspectorLayoutTest {
    @Test fun `折叠屏内屏与平板启用检视器，竖屏与横屏手机仍按窄窗口`() {
        assertThat(infinityInspectorAvailable(814.dp, 898.dp)).isTrue()
        assertThat(infinityInspectorAvailable(1280.dp, 800.dp)).isTrue()
        assertThat(infinityInspectorAvailable(600.dp, 480.dp)).isTrue()
        assertThat(infinityInspectorAvailable(411.dp, 892.dp)).isFalse()
        assertThat(infinityInspectorAvailable(892.dp, 411.dp)).isFalse()
        assertThat(infinityInspectorAvailable(599.dp, 900.dp)).isFalse()
    }

    @Test fun `加宽至多 560dp，并给会话留出至少 48dp`() {
        assertThat(infinityInspectorWidth(814.dp, widened = false)).isEqualTo(360.dp)
        assertThat(infinityInspectorWidth(814.dp, widened = true)).isEqualTo(560.dp)
        assertThat(infinityInspectorWidth(600.dp, widened = true)).isEqualTo(552.dp)
        // 再窄也不比常规宽度更窄。
        assertThat(infinityInspectorWidth(380.dp, widened = true)).isEqualTo(360.dp)
    }

    @Test fun `折叠屏内屏默认宽度固定分栏，仅加宽或窄宽窗不足 320dp 时转为覆盖抽屉`() {
        assertThat(infinityInspectorCanDock(814.dp, 360.dp)).isTrue()
        assertThat(infinityInspectorPlacement(814.dp, 360.dp)).isEqualTo(InfinityInspectorPlacement.Shift)
        assertThat(infinityInspectorCanDock(814.dp, 560.dp)).isFalse()
        assertThat(infinityInspectorPlacement(814.dp, 560.dp)).isEqualTo(InfinityInspectorPlacement.Float)
        assertThat(infinityInspectorPlacement(1280.dp, 560.dp)).isEqualTo(InfinityInspectorPlacement.Shift)
        // 会话宽 = 窗口 − 检视器 − 右缘 8dp，至少 320dp。
        assertThat(infinityInspectorPlacement(688.dp, 360.dp)).isEqualTo(InfinityInspectorPlacement.Shift)
        assertThat(infinityInspectorPlacement(687.dp, 360.dp)).isEqualTo(InfinityInspectorPlacement.Float)
    }

    @Test fun `边缘条常驻时正文右缘距窗口右缘至少 64dp，两侧空白够时不加`() {
        // 折叠屏内屏会话区约 742dp：限宽 720dp 居中后右侧空白 11dp，正文自带 20dp，再加 33dp。
        assertThat(infinityInspectorStripInset(742.dp, 720.dp, 20.dp)).isEqualTo(33.dp)
        assertThat(infinityInspectorStripInset(800.dp, 720.dp, 20.dp)).isEqualTo(4.dp)
        // 空白与正文内边距合计已有 64dp：不加。
        assertThat(infinityInspectorStripInset(808.dp, 720.dp, 20.dp)).isEqualTo(0.dp)
        assertThat(infinityInspectorStripInset(1280.dp, 720.dp, 20.dp)).isEqualTo(0.dp)
        // 窗口比限宽还窄时没有居中空白，全部由正文末端留出。
        assertThat(infinityInspectorStripInset(700.dp, 720.dp, 20.dp)).isEqualTo(44.dp)
    }

    @Test fun `检视器覆盖抽屉底栏分割线采用透明度相乘且顶栏无图钉切换`() {
        val inspector = Files.readString(Path.of("app/src/main/java/com/promenar/infinity/ui/chat/ChatInspector.kt"))
        val chatScreen = Files.readString(Path.of("app/src/main/java/com/promenar/infinity/ui/chat/ChatScreen.kt"))

        assertThat(inspector).contains("chrome?.edgeDivider?.let { it.copy(alpha = it.alpha * 0.7f) }")
        assertThat(inspector).contains("chrome?.edgeDivider?.let { it.copy(alpha = it.alpha * 0.55f) }")
        assertThat(inspector).doesNotContain("edgeDivider?.copy(alpha = 0.7f)")
        assertThat(inspector).doesNotContain("rememberChatInspectorPinned")
        assertThat(chatScreen).doesNotContain("ChatTopBarInspector")
        assertThat(chatScreen).contains("ChatInspectorTags.SCRIM")
        assertThat(inspector).contains("FrostedChromeRole.DOCKED_INSPECTOR")
        assertThat(inspector).contains("FrostedChromeRole.PORTRAIT_DRAWER")
        assertThat(chatScreen).contains("ChatPortraitObsidianDrawer(")
        assertThat(chatScreen).contains("androidx.compose.foundation.pager.HorizontalPager(")
    }

    @Test fun `设置子页在检视器内屏蔽系统栏安全区且两档面板页头坐标常量统一`() {
        val settingsLayout = Files.readString(Path.of("app/src/main/java/com/promenar/infinity/ui/common/InfinitySettingsPageLayout.kt"))
        val paneHeader = Files.readString(Path.of("app/src/main/java/com/promenar/infinity/ui/common/InfinityPaneHeader.kt"))
        val runDetail = Files.readString(Path.of("app/src/main/java/com/promenar/infinity/ui/chat/components/RunDetailSheet.kt"))

        assertThat(settingsLayout).contains("LocalInfinityPanePresentation.current == InfinityPanePresentation.Inspector")
        assertThat(settingsLayout).contains("if (inInspector) WindowInsets(0) else WindowInsets.systemBars")
        assertThat(paneHeader).contains("val InspectorPanelTopGap = 4.dp")
        assertThat(paneHeader).contains("val InspectorPanelHeaderHeight = 56.dp")
        assertThat(runDetail).contains("contentInset = InfinitySpacing.ScreenHorizontal")
    }
}
