package com.promenar.infinity.ui.git

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.git.GitChangeKind
import com.promenar.infinity.data.git.GitFileChange
import com.promenar.infinity.data.git.GitTracking
import org.junit.Test

class GitTrackingDisplayTest {
    private val now = 1_800_000_000_000L

    @Test fun `抓取时间按分钟、小时、天取整，没有记录与时钟回拨分别处理`() {
        val nowSeconds = now / 1000
        assertThat(gitFetchAge(null, now)).isEqualTo(GitFetchAge.Never)
        assertThat(gitFetchAge(nowSeconds - 30, now)).isEqualTo(GitFetchAge.JustNow)
        assertThat(gitFetchAge(nowSeconds + 600, now)).isEqualTo(GitFetchAge.JustNow)
        assertThat(gitFetchAge(nowSeconds - 5 * 60, now)).isEqualTo(GitFetchAge.Minutes(5))
        assertThat(gitFetchAge(nowSeconds - 59 * 60 - 59, now)).isEqualTo(GitFetchAge.Minutes(59))
        assertThat(gitFetchAge(nowSeconds - 3 * 3600 - 10, now)).isEqualTo(GitFetchAge.Hours(3))
        assertThat(gitFetchAge(nowSeconds - 2 * 86_400, now)).isEqualTo(GitFetchAge.Days(2))
    }

    @Test fun `领先落后只显示正数，截断到上限时加号提示`() {
        assertThat(gitTrackingCount(null, false)).isNull()
        assertThat(gitTrackingCount(0, false)).isNull()
        assertThat(gitTrackingCount(2, false)).isEqualTo("2")
        assertThat(gitTrackingCount(GIT_TRACKING_COUNT_CAP, true)).isEqualTo("1000+")
        assertThat(gitTrackingCount(GIT_TRACKING_COUNT_CAP, false)).isEqualTo("1000")
    }

    @Test fun `上游与本地同名时只显示远端名，否则原样显示`() {
        assertThat(gitUpstreamLabel("main", "origin/main")).isEqualTo("origin")
        assertThat(gitUpstreamLabel("claude/tab-redesign", "origin/claude/tab-redesign")).isEqualTo("origin")
        assertThat(gitUpstreamLabel("main", "upstream/develop")).isEqualTo("upstream/develop")
        assertThat(gitUpstreamLabel("main", "origin/main-old")).isEqualTo("origin/main-old")
        assertThat(gitUpstreamLabel("main", "main")).isEqualTo("main")
        assertThat(gitUpstreamLabel(null, "origin/main")).isEqualTo("origin/main")
    }

    @Test fun `计数未知只指有上游但遍历截断且两项都为空`() {
        assertThat(GitTracking("origin/main", null, null, false, true).countsUnknown).isTrue()
        assertThat(GitTracking("origin/main", 1000, 3, false, true).countsUnknown).isFalse()
        assertThat(GitTracking(null, null, null, false, true).countsUnknown).isFalse()
        assertThat(GitTracking("origin/gone", null, null, true, false).countsUnknown).isFalse()
    }

    @Test fun `长分支名从中间缩短到上限且保留首尾`() {
        assertThat(shortenMiddle("main", 22)).isEqualTo("main")
        val short = shortenMiddle("feature/a-very-long-branch-name", 22)
        assertThat(short).hasLength(22)
        assertThat(short).startsWith("feature/a-")
        assertThat(short).endsWith("anch-name")
        assertThat(short).contains("\u2026")
    }

    @Test fun `空文件增删都为 0 时不显示 +0，改显示箭头；二进制与有增删时显示`() {
        fun change(added: Int?, removed: Int?, skipped: String? = null) =
            GitFileChange("a.txt", GitChangeKind.DELETED, addedLines = added, removedLines = removed, linesSkipped = skipped)
        assertThat(change(0, 0).showsLineCounts()).isFalse()
        assertThat(change(null, null).showsLineCounts()).isFalse()
        assertThat(change(null, null, "too_large").showsLineCounts()).isFalse()
        assertThat(change(null, null, "binary").showsLineCounts()).isTrue()
        assertThat(change(0, 1).showsLineCounts()).isTrue()
        assertThat(change(3, 0).showsLineCounts()).isTrue()
    }
}
