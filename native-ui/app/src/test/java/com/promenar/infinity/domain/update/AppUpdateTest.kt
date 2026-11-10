package com.promenar.infinity.domain.update

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.update.AppUpdateChecker
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AppUpdateTest {
    private fun body(tag: String, url: String = "https://github.com/Promenar/Infinity-Deck/releases/tag/$tag") =
        """{"tag_name":"$tag","html_url":"$url","prerelease":false}"""

    @Test fun `按主次补丁比较版本，后缀不影响数字比较`() {
        assertThat(AppUpdate.isNewer("0.45.0-beta", "0.45.1-beta")).isTrue()
        assertThat(AppUpdate.isNewer("0.45.0-beta", "0.46.0-beta")).isTrue()
        assertThat(AppUpdate.isNewer("0.9.9-beta", "0.10.0-beta")).isTrue()
        assertThat(AppUpdate.isNewer("0.45.0-beta", "0.45.0-beta")).isFalse()
        assertThat(AppUpdate.isNewer("0.45.0-beta", "0.43.3-beta")).isFalse()
    }

    @Test fun `数字相同时正式版比带后缀的新`() {
        assertThat(AppUpdate.isNewer("1.0.0-beta", "1.0.0")).isTrue()
        assertThat(AppUpdate.isNewer("1.0.0", "1.0.0-beta")).isFalse()
    }

    @Test fun `无法解析的版本不算更新`() {
        assertThat(AppUpdate.isNewer("0.45.0-beta", "nightly")).isFalse()
        assertThat(AppUpdate.isNewer("dev", "0.46.0")).isFalse()
    }

    @Test fun `解析发行版响应并去掉标签前缀`() {
        assertThat(AppUpdate.parseLatestRelease(body("v0.46.0-beta")))
            .isEqualTo(LatestRelease("0.46.0-beta", "https://github.com/Promenar/Infinity-Deck/releases/tag/v0.46.0-beta"))
    }

    @Test fun `页面地址不在本项目发行页下或响应不是 JSON 时判为失败`() {
        assertThat(AppUpdate.parseLatestRelease(body("v0.46.0", url = "https://example.com/x"))).isNull()
        assertThat(AppUpdate.evaluate("0.45.0-beta", "<html>")).isEqualTo(AppUpdateResult.Failed)
        assertThat(AppUpdate.evaluate("0.45.0-beta", null)).isEqualTo(AppUpdateResult.Failed)
    }

    @Test fun `检查器按当前版本给出有更新、已最新或失败`() = runBlocking<Unit> {
        assertThat(AppUpdateChecker { body("v0.46.0-beta") }.check("0.45.0-beta")).isInstanceOf(AppUpdateResult.Available::class.java)
        assertThat(AppUpdateChecker { body("v0.45.0-beta") }.check("0.45.0-beta")).isEqualTo(AppUpdateResult.UpToDate)
        assertThat(AppUpdateChecker { error("offline") }.check("0.45.0-beta")).isEqualTo(AppUpdateResult.Failed)
    }
}
