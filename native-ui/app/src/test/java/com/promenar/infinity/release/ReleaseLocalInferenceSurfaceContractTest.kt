package com.promenar.infinity.release

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class ReleaseLocalInferenceSurfaceContractTest {
    @Test
    fun `设置入口和 Provider 预设均受本地推理构建能力约束`() {
        val settings = source("ui/hub/UserSettingsHomeScreen.kt")
        val providerForm = source("ui/settings/ProviderFormScreen.kt")
        val application = source("InfinityApplication.kt")
        val mainActivity = source("MainActivity.kt")
        val buildScript = Files.readAllBytes(Path.of("app/build.gradle.kts"))
            .toString(Charsets.UTF_8)
        val minifiedTestBuildType = buildScript
            .substringAfter("create(\"minifiedTest\") {")
            .substringBefore("        release {")
        val releaseBuildType = buildScript.substringAfter("        release {")
        val navGraph = source("navigation/NavGraph.kt")
        val chatViewModel = source("ui/chat/ChatViewModel.kt")
        val sessionSettings = source("ui/chat/SessionSettingsSheet.kt")

        assertThat(settings).contains("BuildConfig.LOCAL_INFERENCE_AVAILABLE")
        assertThat(providerForm).contains("availableProviderPresets")
        assertThat(providerForm).contains("localInferenceRuntimeGate.isAvailable")
        assertThat(application).contains("buildUnavailableLocalPlaceholderProvider")
        assertThat(mainActivity).contains("isDebugBuild = BuildConfig.DEBUG")
        assertThat(mainActivity).doesNotContain(
            "BuildConfig.DEBUG && BuildConfig.LOCAL_INFERENCE_AVAILABLE",
        )
        assertThat(minifiedTestBuildType).contains("isDebuggable = false")
        assertThat(minifiedTestBuildType).contains(
            "buildConfigField(\"boolean\", \"LOCAL_INFERENCE_AVAILABLE\", \"false\")",
        )
        assertThat(releaseBuildType).contains("isDebuggable = false")
        assertThat(releaseBuildType).contains(
            "buildConfigField(\"boolean\", \"LOCAL_INFERENCE_AVAILABLE\", \"false\")",
        )
        assertThat(buildScript).contains("variant.buildType == \"release\" && (\"distribution\" to \"sideload\")")
        assertThat(buildScript).contains("BuildConfigField(\"boolean\", \"true\", null)")
        assertThat(navGraph).contains("if (BuildConfig.LOCAL_INFERENCE_AVAILABLE)")
        assertThat(chatViewModel).contains("ProviderResolutionError.LOCAL_INFERENCE_UNAVAILABLE")
        assertThat(chatViewModel).contains("R.string.local_inference_release_unavailable")
        assertThat(sessionSettings).contains("filterLocalInferenceModels")
    }

    private fun source(relative: String): String = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/infinity/$relative"),
    ).toString(Charsets.UTF_8)
}
