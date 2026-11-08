package com.promenar.infinity.ui.hub

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppVersionInfoTest {
    private val info = AppVersionInfo(versionName = "0.3.0-beta", versionCode = 5, channel = "sideload", commit = "abc1234")

    @Test
    fun `关于行副标题按模板拼出版本名、versionCode 与渠道`() {
        assertThat(appVersionLine(info, "%1\$s（%2\$d）· %3\$s", "侧载版"))
            .isEqualTo("0.3.0-beta（5）· 侧载版")
        assertThat(appVersionLine(info.copy(channel = "play"), "%1\$s (%2\$d) · %3\$s", "Play"))
            .isEqualTo("0.3.0-beta (5) · Play")
    }

    @Test
    fun `复制文本依次包含版本名、versionCode、渠道与提交短 SHA`() {
        val text = appVersionCopyText(info, "Infinity Deck %1\$s\nversionCode: %2\$d\nChannel: %3\$s\nCommit: %4\$s", "Sideload")
        assertThat(text.lines()).containsExactly(
            "Infinity Deck 0.3.0-beta",
            "versionCode: 5",
            "Channel: Sideload",
            "Commit: abc1234",
        ).inOrder()
    }

    @Test
    fun `取不到提交时回退为 unknown`() {
        assertThat(info.copy(commit = "").commitOrUnknown).isEqualTo("unknown")
        assertThat(info.copy(commit = "  ").commitOrUnknown).isEqualTo("unknown")
        assertThat(info.copy(commit = "unknown").commitOrUnknown).isEqualTo("unknown")
        val text = appVersionCopyText(info.copy(commit = ""), "%1\$s|%2\$d|%3\$s|%4\$s", "侧载版")
        assertThat(text).isEqualTo("0.3.0-beta|5|侧载版|unknown")
    }

    @Test
    fun `渠道由 BuildConfig 的发行值判定`() {
        assertThat(info.isSideload).isTrue()
        assertThat(info.copy(channel = "play").isSideload).isFalse()
    }
}
