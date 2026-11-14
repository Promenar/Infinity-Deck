package com.promenar.infinity.navigation

import com.promenar.infinity.onboarding.OnboardingStep
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class OnboardingNavigationContractTest {
    private val navGraph = read("app/src/main/java/com/promenar/infinity/navigation/NavGraph.kt")
    private val mainActivity = read("app/src/main/java/com/promenar/infinity/MainActivity.kt")
    private val onboardingRoute = read("app/src/main/java/com/promenar/infinity/ui/welcome/OnboardingRoute.kt")

    @Test
    fun `启动目的地只取Activity创建时的引导快照而不随首聊完成重建导航图`() {
        assertThat(initialOnboardingDestination(OnboardingStep.LANGUAGE))
            .isEqualTo(NavDestinations.WELCOME)
        assertThat(initialOnboardingDestination(OnboardingStep.COMPLETED))
            .isEqualTo(NavDestinations.MAIN_TAB_SCAFFOLD)
        assertThat(mainActivity).contains("remember(onboardingStore)")
        assertThat(mainActivity).contains("onboardingStore.state.value.step")
        assertThat(mainActivity).doesNotContain("onboardingState.step == OnboardingStep.COMPLETED")
    }

    @Test
    fun `首聊成功打开离线准备且保留聊天返回栈`() {
        assertThat(initialOnboardingDestination(OnboardingStep.RECORDING_SETUP)).isEqualTo(NavDestinations.RECORDING_SETUP)
        val setup = functionBody(navGraph, "LaunchedEffect(onboardingState.step)")
        assertThat(setup).contains("navController.navigate(NavDestinations.RECORDING_SETUP)")
        assertThat(setup).doesNotContain("popUpTo")
    }

    @Test
    fun `打开首次聊天前先建立主界面作为返回栈基座`() {
        val firstChatHandler = functionBody(onboardingRoute, "onStartChat =")

        assertThat(firstChatHandler).contains("openOnboardingFirstChat")
        assertThat(firstChatHandler).doesNotContain("navController.navigate(")
    }

    private fun read(path: String) = Files.readAllBytes(Path.of(path)).toString(Charsets.UTF_8)

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val brace = source.indexOf('{', start)
        var depth = 0
        for (index in brace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(brace + 1, index)
            }
        }
        error("函数未闭合: $signature")
    }
}
