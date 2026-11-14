package com.promenar.infinity.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingEndToEndTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(OnboardingStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("infinity_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun 首次引导_重建后恢复并且只有成功首聊才完成() {
        var store = OnboardingStateStore(context, linuxOffered = true)
        assertTrue(store.selectLanguage("zh"))

        // 模拟 Activity 旋转或进程重建：必须恢复到尚未完成的 Linux 步骤。
        store = OnboardingStateStore(context, linuxOffered = true)
        assertEquals(OnboardingStep.LINUX, store.state.value.step)
        assertTrue(store.completeLinuxStep())

        store = OnboardingStateStore(context, linuxOffered = true)
        assertEquals(OnboardingStep.PROVIDER, store.state.value.step)
        assertTrue(store.recordProviderConnected("default"))
        assertFalse(store.selectModel(null))
        assertEquals(OnboardingStep.MODEL, store.state.value.step)
        assertTrue(store.selectModel("default::model"))
        assertTrue(store.recordAgentCreated("agent-1", "session-1"))

        assertFalse(store.recordFirstChatResult("session-1", assistantSucceeded = false))
        assertEquals(OnboardingStep.FIRST_CHAT, store.state.value.step)
        assertTrue(store.recordFirstChatResult("session-1", assistantSucceeded = true))
        val restored = OnboardingStateStore(context, linuxOffered = true)
        assertEquals(OnboardingStep.RECORDING_SETUP, restored.state.value.step)
        assertTrue(restored.completeRecordingSetup())
        assertEquals(OnboardingStep.COMPLETED, OnboardingStateStore(context, linuxOffered = true).state.value.step)
    }
}
