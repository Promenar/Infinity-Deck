package com.promenar.infinity.navigation

import androidx.navigation.NavHostController
import com.promenar.infinity.onboarding.OnboardingStep

/**
 * 启动目的地只由 Activity 创建时的持久化快照决定，不能随引导状态流变化而重建导航图。
 */
internal fun initialOnboardingDestination(step: OnboardingStep): String =
    when (step) {
        OnboardingStep.COMPLETED -> NavDestinations.MAIN_TAB_SCAFFOLD
        OnboardingStep.RECORDING_SETUP -> NavDestinations.RECORDING_SETUP
        else -> NavDestinations.WELCOME
    }

/**
 * 首聊必须以主界面作为返回栈基座。首聊成功后只更新持久化状态，不再改写当前目的地。
 */
internal fun openOnboardingFirstChat(
    navController: NavHostController,
    sessionId: String,
) {
    navController.navigate(NavDestinations.MAIN_TAB_SCAFFOLD) {
        popUpTo(NavDestinations.WELCOME) { inclusive = true }
        launchSingleTop = true
    }
    navController.navigate(NavDestinations.chatHero(sessionId)) {
        launchSingleTop = true
    }
}
