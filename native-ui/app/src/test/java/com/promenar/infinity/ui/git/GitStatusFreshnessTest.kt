package com.promenar.infinity.ui.git

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GitStatusFreshnessTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `刷新中优先于读取时间`() {
        assertThat(gitStatusFreshness(true, now - 90_000, now)).isEqualTo(GitStatusFreshness.Refreshing)
        assertThat(gitStatusFreshness(true, null, now)).isEqualTo(GitStatusFreshness.Refreshing)
    }

    @Test
    fun `从未读取或不足十秒不显示`() {
        assertThat(gitStatusFreshness(false, null, now)).isNull()
        assertThat(gitStatusFreshness(false, now - 9_999, now)).isNull()
        // 时钟回拨视为刚读取。
        assertThat(gitStatusFreshness(false, now + 60_000, now)).isNull()
    }

    @Test
    fun `一分钟内按秒，之后按分钟`() {
        assertThat(gitStatusFreshness(false, now - 10_000, now)).isEqualTo(GitStatusFreshness.Seconds(10))
        assertThat(gitStatusFreshness(false, now - 59_999, now)).isEqualTo(GitStatusFreshness.Seconds(59))
        assertThat(gitStatusFreshness(false, now - 60_000, now)).isEqualTo(GitStatusFreshness.Minutes(1))
        assertThat(gitStatusFreshness(false, now - 30 * 60_000, now)).isEqualTo(GitStatusFreshness.Minutes(30))
    }
}
