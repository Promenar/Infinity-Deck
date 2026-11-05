package com.promenar.infinity.ui.library

import com.promenar.infinity.domain.library.AskErrorCode
import com.promenar.infinity.domain.library.AskEvent
import com.promenar.infinity.domain.library.AskHit
import com.promenar.infinity.domain.library.AskHitKind
import com.promenar.infinity.domain.library.AskMatch
import com.promenar.infinity.domain.library.AskMode
import com.promenar.infinity.domain.library.AskScope
import com.promenar.infinity.domain.library.LibraryAskService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryAskViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun hit(key: String, kind: AskHitKind = AskHitKind.DOCUMENT, doc: String? = "d1") = AskHit(
        key = key, kind = kind, title = key, location = "示例位置", snippet = "示例摘录", highlightTerms = emptyList(),
        match = AskMatch.SEMANTIC, similarity = 0.8f, documentId = doc,
    )

    private class FakeService(
        var events: List<AskEvent> = emptyList(),
        val keyword: (String) -> List<AskHit> = { emptyList() },
        val hang: Boolean = false,
    ) : LibraryAskService {
        val keywordQueries = mutableListOf<String>()
        val asked = mutableListOf<Pair<String, AskScope>>()
        override fun observeMode(): Flow<AskMode> = MutableStateFlow(AskMode.FULL)
        override suspend fun keywordSearch(query: String, scope: AskScope, limit: Int): List<AskHit> {
            keywordQueries += query
            return keyword(query)
        }
        override fun ask(query: String, scope: AskScope): Flow<AskEvent> = flow {
            asked += query to scope
            events.forEach { emit(it) }
            if (hang) awaitCancellation()
        }
    }

    @Test
    fun `输入防抖后只查最后一次关键词`() = runTest(dispatcher) {
        val service = FakeService(keyword = { listOf(hit("k:$it")) })
        val vm = LibraryAskViewModel(service)
        vm.onQueryChange("离")
        advanceTimeBy(100)
        vm.onQueryChange("离线")
        advanceUntilIdle()
        assertEquals(listOf("离线"), service.keywordQueries)
        assertEquals(listOf("k:离线"), vm.state.value.listedHits.map { it.key })
        assertEquals(AskPhase.TYPING, vm.state.value.phase)
    }

    @Test
    fun `按搜索键后依次检索、作答、完成`() = runTest(dispatcher) {
        val hits = listOf(hit("a"), hit("b"), hit("m", AskHitKind.MEMORY, null))
        val service = FakeService(
            events = listOf(
                AskEvent.Retrieving,
                AskEvent.Hits(hits, AskMode.FULL),
                AskEvent.AnswerDelta("离线写入"),
                AskEvent.AnswerDelta("先排队[1]"),
                AskEvent.AnswerDone("离线写入先排队[1][3]", listOf(0, 2, 9), "示例模型", noAnswer = false),
            ),
        )
        val vm = LibraryAskViewModel(service)
        vm.onQueryChange("离线写入")
        vm.submit()
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(AskPhase.DONE, state.phase)
        assertEquals("离线写入", state.askedQuery)
        assertEquals("离线写入先排队[1][3]", state.answer)
        // 越界编号由界面再兜底丢弃一次。
        assertEquals(listOf(0, 2), state.citedHitIndexes)
        assertEquals(listOf("a", "b"), state.hitsForDocument("d1").map { it.key })
        assertTrue(service.keywordQueries.isEmpty())
    }

    @Test
    fun `未设快速模型时只有检索结果`() = runTest(dispatcher) {
        val vm = LibraryAskViewModel(FakeService(events = listOf(AskEvent.Retrieving, AskEvent.Hits(listOf(hit("a")), AskMode.NO_QUICK_MODEL))))
        vm.onQueryChange("问题")
        vm.submit()
        advanceUntilIdle()
        assertEquals(AskPhase.DONE, vm.state.value.phase)
        assertEquals(AskMode.NO_QUICK_MODEL, vm.state.value.mode)
        assertEquals("", vm.state.value.answer)
    }

    @Test
    fun `改动输入取消正在进行的提问并回到关键词结果`() = runTest(dispatcher) {
        val service = FakeService(events = listOf(AskEvent.Retrieving, AskEvent.Hits(listOf(hit("a")), AskMode.FULL)), hang = true)
        val vm = LibraryAskViewModel(service)
        vm.onQueryChange("问题")
        vm.submit()
        runCurrent()
        assertEquals(AskPhase.ANSWERING, vm.state.value.phase)
        vm.onQueryChange("问题二")
        runCurrent()
        assertEquals(AskPhase.TYPING, vm.state.value.phase)
        assertEquals("问题", vm.state.value.askedQuery)
    }

    @Test
    fun `失败保留已发出的结果，取消不算失败`() {
        val base = AskUiState(query = "q").reduce(AskEvent.Retrieving).reduce(AskEvent.Hits(listOf(hit("a")), AskMode.FULL))
        val failed = base.reduce(AskEvent.Failed(AskErrorCode.MODEL_FAILED, null))
        assertEquals(AskPhase.FAILED, failed.phase)
        assertEquals(1, failed.hits.size)
        assertEquals(base, base.reduce(AskEvent.Failed(AskErrorCode.CANCELLED, null)))
    }

    @Test
    fun `空结果的完整模式直接完成，不等答案`() {
        val state = AskUiState().reduce(AskEvent.Retrieving).reduce(AskEvent.Hits(emptyList(), AskMode.FULL))
        assertEquals(AskPhase.DONE, state.phase)
    }

    @Test
    fun `切换范围后用原问题重问`() = runTest(dispatcher) {
        val service = FakeService(events = listOf(AskEvent.Retrieving, AskEvent.Hits(listOf(hit("a")), AskMode.NO_QUICK_MODEL)))
        val vm = LibraryAskViewModel(service)
        vm.onQueryChange("问题")
        vm.submit()
        advanceUntilIdle()
        vm.setScope(AskScope.Library)
        advanceUntilIdle()
        assertEquals(listOf("问题" to AskScope.AllIndexed, "问题" to AskScope.Library), service.asked)
    }

    @Test
    fun `点编号清除筛选并选中，清空回到初始`() = runTest(dispatcher) {
        val vm = LibraryAskViewModel(FakeService())
        vm.setFilter(AskHitKind.MEMORY)
        vm.selectHit(1)
        assertEquals(1, vm.state.value.selectedHit)
        assertNull(vm.state.value.filter)
        vm.setScope(AskScope.Library)
        vm.clear()
        assertEquals(AskUiState(scope = AskScope.Library), vm.state.value)
    }
}
