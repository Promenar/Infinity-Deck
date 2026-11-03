package com.promenar.infinity.data.network

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.tailnet.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DefaultAgentNetworkAccessTest {
    @Test fun `快照跟随内置Tailscale状态与设置`() = runTest {
        val state = MutableStateFlow(TailnetState(TailnetPhase.STOPPED))
        val settings = MutableStateFlow(TailnetSettings(enabled = true))
        val controller = mockk<TailnetController> {
            every { available } returns true
            every { this@mockk.state } returns state
            every { this@mockk.settings } returns settings
        }
        val access = DefaultAgentNetworkAccess(controller, backgroundScope)
        assertThat(access.snapshot.value.tailnetEnabled).isTrue()
        assertThat(access.snapshot.value.tailnetAvailable).isTrue()
        assertThat(access.snapshot.value.tailnetRunning).isFalse()
        runCurrent()
        state.value = TailnetState(TailnetPhase.RUNNING)
        runCurrent()
        assertThat(access.snapshot.value.tailnetRunning).isTrue()
        assertThat(access.snapshot.value.rows).isEqualTo(com.promenar.infinity.domain.network.AgentNetworkPolicy.rows)
        settings.value = TailnetSettings(enabled = false)
        runCurrent()
        assertThat(access.snapshot.value.tailnetEnabled).isFalse()
    }
}
