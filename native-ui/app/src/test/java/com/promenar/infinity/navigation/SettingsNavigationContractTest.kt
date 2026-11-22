package com.promenar.infinity.navigation

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class SettingsNavigationContractTest {
    private val navGraphSource = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/infinity/navigation/NavGraph.kt"),
    ).toString(Charsets.UTF_8)

    @Test
    fun `navigation targets contain provider_list and default_models`() {
        assertThat(navGraphSource).contains("const val THEME_CONFIG = \"theme_config\"")
        assertThat(navGraphSource).contains("const val PROVIDER_LIST = \"provider_list\"")
        assertThat(navGraphSource).contains("const val DEFAULT_MODELS = \"default_models\"")
        assertThat(navGraphSource).contains("const val COMFY_HUB = \"comfy_hub\"")
        assertThat(navGraphSource).contains("const val PROVIDER_FORM = \"provider_form?providerId={providerId}\"")
        assertThat(navGraphSource).contains("const val PROVIDER_MODELS = \"provider_models/{providerId}\"")
        assertThat(navGraphSource).contains("NavDestinations.PROVIDER_LIST")
        assertThat(navGraphSource).contains("NavDestinations.DEFAULT_MODELS")
    }

    @Test
    fun `navigation graph registers provider_list and default_models composables`() {
        assertThat(navGraphSource).contains("composablePage(NavDestinations.PROVIDER_LIST)")
        assertThat(navGraphSource).contains("composablePage(NavDestinations.DEFAULT_MODELS)")
    }

    @Test
    fun `navigation graph wires provider_list destinations properly`() {
        assertThat(navGraphSource).contains("ProviderListScreen(")
        assertThat(navGraphSource).contains("DefaultModelsScreen(")
    }
}
