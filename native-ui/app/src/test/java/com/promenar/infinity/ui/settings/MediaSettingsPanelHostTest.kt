package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.navigation.NavDestinations
import org.junit.Test

class MediaSettingsPanelHostTest {
    @Test
    fun `媒体设置这组目的地留在面板内的导航栈`() {
        listOf(
            MediaSettingsRoutes.MODELS,
            MediaSettingsRoutes.model("cloud:seedance"),
            MediaSettingsRoutes.host("main"),
            MediaSettingsRoutes.hostForm(),
            MediaSettingsRoutes.hostForm("main"),
            MediaSettingsRoutes.template("main/qwen-image"),
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
