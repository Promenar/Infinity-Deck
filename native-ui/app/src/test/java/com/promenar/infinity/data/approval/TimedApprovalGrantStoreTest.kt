package com.promenar.infinity.data.approval

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.tool.ApprovalGrantCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimedApprovalGrantStoreTest {
    @Test fun `删除授权仅在当前会话十分钟内生效`() = runTest {
        val grants = TimedApprovalGrantStore(backgroundScope) { 1_000L + testScheduler.currentTime }
        grants.grant("one", ApprovalGrantCategory.DELETE)
        assertThat(grants.isValid("one", ApprovalGrantCategory.DELETE)).isTrue()
        assertThat(grants.isValid("two", ApprovalGrantCategory.DELETE)).isFalse()
        assertThat(grants.isValid("one", ApprovalGrantCategory.COMMAND)).isFalse()
        advanceTimeBy(TimedApprovalGrantStore.GRANT_DURATION_MILLIS)
        runCurrent()
        assertThat(grants.isValid("one", ApprovalGrantCategory.DELETE)).isFalse()
    }

    @Test fun `授权按会话类别隔离续期且到期无需读取自动消失`() = runTest {
        val grants = TimedApprovalGrantStore(backgroundScope) { 1_000L + testScheduler.currentTime }
        val current = grants.observe("one")
        assertThat(current.value).isEmpty()
        assertThat(grants.grant("one", ApprovalGrantCategory.SKILLS)).isEqualTo(601_000L)
        grants.grant("two", ApprovalGrantCategory.COMMAND)
        assertThat(grants.isValid("one", ApprovalGrantCategory.SKILLS)).isTrue()
        assertThat(grants.isValid("one", ApprovalGrantCategory.COMMAND)).isFalse()
        assertThat(grants.isValid("two", ApprovalGrantCategory.SKILLS)).isFalse()
        advanceTimeBy(300_000); runCurrent()
        grants.grant("one", ApprovalGrantCategory.SKILLS)
        assertThat(current.value).containsExactly(ApprovalGrantCategory.SKILLS, 901_000L)
        advanceTimeBy(300_000); runCurrent()
        assertThat(grants.observe("two").value).isEmpty()
        assertThat(current.value).isNotEmpty()
        advanceTimeBy(300_000); runCurrent()
        assertThat(current.value).isEmpty()
    }

    @Test fun `读取清理时钟已过期授权且撤销不影响其它会话并且新实例不继承授权`() = runTest {
        var now = 10L
        val grants = TimedApprovalGrantStore(backgroundScope) { now }
        grants.grant("one", ApprovalGrantCategory.SKILLS)
        grants.grant("one", ApprovalGrantCategory.COMMAND)
        grants.grant("two", ApprovalGrantCategory.SKILLS)
        grants.revokeAll("one")
        assertThat(grants.observe("one").value).isEmpty()
        assertThat(grants.isValid("two", ApprovalGrantCategory.SKILLS)).isTrue()
        assertThat(TimedApprovalGrantStore(backgroundScope) { now }.observe("two").value).isEmpty()
        now += TimedApprovalGrantStore.GRANT_DURATION_MILLIS
        assertThat(grants.isValid("two", ApprovalGrantCategory.SKILLS)).isFalse()
        assertThat(grants.observe("two").value).isEmpty()
    }
}
