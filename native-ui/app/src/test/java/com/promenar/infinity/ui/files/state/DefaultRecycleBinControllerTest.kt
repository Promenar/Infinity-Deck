package com.promenar.infinity.ui.files.state

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.files.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRecycleBinControllerTest {
    private fun item(id: String, time: Long) = RecycledItem(id, "library", "/$id", id, false, 1, time, null)

    @Test fun `按删除时间倒序并汇总部分恢复失败`() = runTest {
        val services = FakeFileServices()
        val old = item("old", 1)
        val recent = item("recent", 2)
        services.operations.recycle.value = listOf(old, recent)
        services.operations.restoreHandler = {
            FileOpOutcome(listOf(FileRef("library", "/recent")), listOf(FileOpFailure(FileRef("library", "/old"), FileOpCode.CONFLICT)))
        }
        val controller = DefaultRecycleBinController(backgroundScope, services, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertThat(controller.state.value.items).containsExactly(recent, old).inOrder()
        controller.restore(listOf(old, recent))
        runCurrent()
        assertThat(controller.state.value.notice).isEqualTo(FileManagerNotice.Failed(FileManagerNotice.Kind.RESTORE, FileOpCode.CONFLICT, 1, 2))
    }

    @Test fun `运行中忽略重复操作并处理清空失败`() = runTest {
        val services = FakeFileServices()
        val gate = CompletableDeferred<Unit>()
        var purgeCalls = 0
        val recycled = item("entry", 1)
        services.operations.recycle.value = listOf(recycled)
        services.operations.purgeHandler = { purgeCalls++; gate.await() }
        services.operations.emptyHandler = { throw IllegalStateException("synthetic") }
        val controller = DefaultRecycleBinController(backgroundScope, services, StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.purge(listOf(recycled))
        controller.purge(listOf(recycled))
        controller.emptyAll()
        runCurrent()
        assertThat(controller.state.value.running).isTrue()
        assertThat(purgeCalls).isEqualTo(1)
        gate.complete(Unit)
        runCurrent()
        assertThat(controller.state.value.running).isFalse()
        assertThat(controller.state.value.notice).isEqualTo(FileManagerNotice.Done(FileManagerNotice.Kind.PURGE, 1))
        controller.emptyAll()
        runCurrent()
        assertThat(controller.state.value.notice).isEqualTo(FileManagerNotice.Failed(FileManagerNotice.Kind.PURGE, FileOpCode.IO, 1, 1))
    }
}
