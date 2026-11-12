package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.navigation.NavDestinations
import org.junit.Test

class MediaSettingsPanelHostTest {
    @Test
    fun `媒体设置这组目的地留在面板内的导航栈`() {
        listOf(
            MediaSettingsRoutes.MODELS,
            // JVM 单测没有 android.net.Uri，按 Uri.encode 的结果直接写出实际路由与登记的路由模式。
            MediaSettingsRoutes.MODEL,
            MediaSettingsRoutes.HOST,
            MediaSettingsRoutes.HOST_FORM,
            MediaSettingsRoutes.TEMPLATE,
            "media_model/cloud%3Aseedance",
            "comfy_host/main",
            "comfy_host_form",
            "comfy_host_form?hostId=main",
            "comfy_template/main%2Fqwen-image",
        ).forEach { assertThat(isMediaSettingsRoute(it)).isTrue() }
    }

    @Test
    fun `其它页面交给主导航`() {
        listOf(
            NavDestinations.chatHero("session-1"),
            NavDestinations.PROVIDER_LIST,
            NavDestinations.DEFAULT_MODELS,
        ).forEach { assertThat(isMediaSettingsRoute(it)).isFalse() }
    }
}
