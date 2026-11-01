package com.promenar.infinity.ui.developer

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.navigation.isSettingsPaneRoute
import org.junit.Test

class DeveloperUnlockCounterTest {
    private fun DeveloperUnlockCounter.tapAt(vararg times: Long, enabled: Boolean = false) =
        times.map { tap(it, enabled) }

    @Test
    fun `五次快速连点开启，最后两次之前静默`() {
        val results = DeveloperUnlockCounter().tapAt(0, 300, 600, 900, 1200)
        assertThat(results).containsExactly(
            DeveloperUnlockResult.Silent,
            DeveloperUnlockResult.Silent,
            DeveloperUnlockResult.Remaining(2),
            DeveloperUnlockResult.Remaining(1),
            DeveloperUnlockResult.Unlocked,
        ).inOrder()
    }

    @Test
    fun `间隔超过窗口重新计数`() {
        val counter = DeveloperUnlockCounter()
        counter.tapAt(0, 300, 600, 900)
        // 停顿 2 秒后从第一下算起，再点四下才开启。
        val results = counter.tapAt(2900, 3000, 3100, 3200, 3300)
        assertThat(results.last()).isEqualTo(DeveloperUnlockResult.Unlocked)
        assertThat(results.dropLast(1)).doesNotContain(DeveloperUnlockResult.Unlocked)
    }

    @Test
    fun `开启后计数归零，再点要重新攒满`() {
        val counter = DeveloperUnlockCounter()
        counter.tapAt(0, 100, 200, 300, 400)
        assertThat(counter.tap(500, alreadyEnabled = false)).isEqualTo(DeveloperUnlockResult.Silent)
    }

    @Test
    fun `已开启时一轮连点只提示一次`() {
        val results = DeveloperUnlockCounter().tapAt(0, 200, 400, 600, 800, 1000, enabled = true)
        assertThat(results.first()).isEqualTo(DeveloperUnlockResult.AlreadyEnabled)
        assertThat(results.drop(1).toSet()).containsExactly(DeveloperUnlockResult.Silent)
    }

    @Test
    fun `开发者页与实验室目录在设置右列打开，版本页整页打开`() {
        assertThat(isSettingsPaneRoute(NavDestinations.DEVELOPER)).isTrue()
        assertThat(isSettingsPaneRoute("visual_lab")).isTrue()
        assertThat(isSettingsPaneRoute("visual_lab?path=chat%2Fconversation")).isTrue()
        assertThat(isSettingsPaneRoute("lab_version/chat.workflow.ade-json")).isFalse()
    }
}
