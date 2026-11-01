package com.promenar.infinity.ui.chat.components

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.jobs.BackgroundJobExitAction
import com.promenar.infinity.domain.jobs.BackgroundJobLog
import com.promenar.infinity.domain.jobs.BackgroundJobRunner
import com.promenar.infinity.domain.jobs.BackgroundJobState
import org.junit.jupiter.api.Test

class BackgroundJobLogStateTest {

    private val log = BackgroundJobLog(text = "line 1\nline 2", truncated = true)

    @Test
    fun `首次读取未超时显示正在读取，超过五秒改为仍在读取`() {
        val reading = JobLogReadState().started(nowMillis = 1_000L)

        assertThat(resolveJobLogPhase(reading, nowMillis = 1_000L)).isEqualTo(JobLogPhase.Loading)
        assertThat(resolveJobLogPhase(reading, nowMillis = 5_999L)).isEqualTo(JobLogPhase.Loading)
        assertThat(resolveJobLogPhase(reading, nowMillis = 6_000L)).isEqualTo(JobLogPhase.SlowLoading)
    }

    @Test
    fun `读取失败不再停在读取中，重试后重新开始`() {
        val failed = JobLogReadState().started(1_000L).failedRead()

        assertThat(resolveJobLogPhase(failed, nowMillis = 60_000L)).isEqualTo(JobLogPhase.Failed)
        assertThat(failed.inFlightSinceMillis).isNull()

        val retried = failed.retried()
        assertThat(retried.failed).isFalse()
        assertThat(resolveJobLogPhase(retried.started(70_000L), nowMillis = 70_000L)).isEqualTo(JobLogPhase.Loading)
    }

    @Test
    fun `取消缓慢读取后显示已取消并允许重试`() {
        val cancelled = JobLogReadState().started(0L).cancelledRead()

        assertThat(resolveJobLogPhase(cancelled, nowMillis = 9_000L)).isEqualTo(JobLogPhase.Cancelled)
        assertThat(cancelled.retried().cancelled).isFalse()
    }

    @Test
    fun `已有内容时刷新失败仍显示内容并保留旧日志`() {
        val loaded = JobLogReadState().started(0L).succeeded(log, nowMillis = 500L)
        val refreshFailed = loaded.started(2_500L).failedRead()

        assertThat(resolveJobLogPhase(refreshFailed, nowMillis = 3_000L)).isEqualTo(JobLogPhase.Content)
        assertThat(refreshFailed.failed).isTrue()
        assertThat(refreshFailed.log).isEqualTo(log)
        assertThat(refreshFailed.updatedAtMillis).isEqualTo(500L)
    }

    @Test
    fun `刷新中的读取变慢不打断已显示的内容`() {
        val refreshing = JobLogReadState().succeeded(log, 0L).started(1_000L)

        assertThat(resolveJobLogPhase(refreshing, nowMillis = 20_000L)).isEqualTo(JobLogPhase.Content)
    }

    @Test
    fun `只有运行中且读取成功才按两秒节拍继续刷新`() {
        val ok = JobLogReadState().succeeded(log, 0L)

        assertThat(jobLogRefreshDelayMillis(jobRunning = true, state = ok)).isEqualTo(2_000L)
        assertThat(jobLogRefreshDelayMillis(jobRunning = false, state = ok)).isNull()
        assertThat(jobLogRefreshDelayMillis(jobRunning = true, state = ok.failedRead())).isNull()
        assertThat(jobLogRefreshDelayMillis(jobRunning = true, state = ok.cancelledRead())).isNull()
    }

    @Test
    fun `更新时间按整秒计算，时钟回拨不出现负数`() {
        val loaded = JobLogReadState().succeeded(log, nowMillis = 10_000L)

        assertThat(jobLogSecondsSinceUpdate(JobLogReadState(), 10_000L)).isNull()
        assertThat(jobLogSecondsSinceUpdate(loaded, 10_999L)).isEqualTo(0L)
        assertThat(jobLogSecondsSinceUpdate(loaded, 12_100L)).isEqualTo(2L)
        assertThat(jobLogSecondsSinceUpdate(loaded, 9_000L)).isEqualTo(0L)
    }

    @Test
    fun `状态胶囊取自任务记录`() {
        assertThat(resolveJobLogStatus(null)).isNull()
        assertThat(resolveJobLogStatus(job(BackgroundJobState.RUNNING))).isEqualTo(JobLogStatus.Live)
        assertThat(resolveJobLogStatus(job(BackgroundJobState.EXITED, exitCode = 0))).isEqualTo(JobLogStatus.Exited(0))
        assertThat((resolveJobLogStatus(job(BackgroundJobState.EXITED, exitCode = 0)) as JobLogStatus.Exited).isFailure).isFalse()
        assertThat((resolveJobLogStatus(job(BackgroundJobState.EXITED, exitCode = 2)) as JobLogStatus.Exited).isFailure).isTrue()
        assertThat((resolveJobLogStatus(job(BackgroundJobState.EXITED, exitCode = null)) as JobLogStatus.Exited).isFailure).isFalse()
        assertThat(resolveJobLogStatus(job(BackgroundJobState.STOPPED))).isEqualTo(JobLogStatus.Stopped)
        assertThat(resolveJobLogStatus(job(BackgroundJobState.TIMED_OUT))).isEqualTo(JobLogStatus.TimedOut)
        assertThat(resolveJobLogStatus(job(BackgroundJobState.INTERRUPTED))).isEqualTo(JobLogStatus.Interrupted)
        assertThat(resolveJobLogStatus(job(BackgroundJobState.FAILED))).isEqualTo(JobLogStatus.Failed)
    }

    @Test
    fun `标题优先用短标题，没有时用命令摘要`() {
        assertThat(jobLogDisplayName(null)).isNull()
        assertThat(jobLogDisplayName(job(BackgroundJobState.RUNNING, title = "复制 Personal-Archive"))).isEqualTo("复制 Personal-Archive")
        assertThat(jobLogDisplayName(job(BackgroundJobState.RUNNING, title = "  "))).isEqualTo("cp -r a b")
        assertThat(jobLogDisplayName(job(BackgroundJobState.RUNNING, title = null, summary = ""))).isNull()
    }

    @Test
    fun `停在末尾附近时继续贴底，向上翻阅时不打断`() {
        assertThat(shouldFollowLogTail(scrollValue = 1_000, maxScroll = 1_000, slackPx = 48)).isTrue()
        assertThat(shouldFollowLogTail(scrollValue = 960, maxScroll = 1_000, slackPx = 48)).isTrue()
        assertThat(shouldFollowLogTail(scrollValue = 500, maxScroll = 1_000, slackPx = 48)).isFalse()
        assertThat(shouldFollowLogTail(scrollValue = 0, maxScroll = 0, slackPx = 48)).isTrue()
    }

    private fun job(
        state: BackgroundJobState,
        exitCode: Int? = null,
        title: String? = null,
        summary: String = "cp -r a b",
    ) = BackgroundJob(
        id = "job-1",
        sessionId = "s",
        runner = BackgroundJobRunner.LINUX,
        summary = summary,
        state = state,
        exitCode = exitCode,
        startedAtMillis = 0L,
        endedAtMillis = null,
        maxRuntimeMinutes = 60,
        onExit = BackgroundJobExitAction.NONE,
        logWorkspacePath = "job-log:job-1",
        liveOutputKey = "live-1",
        title = title,
    )
}
