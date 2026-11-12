package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ProductionMediaBudgetReaderTest {
    private val store = PlanningStore()
    private val queue = PlanningQueue()
    private val source = ProductionSourceRef(store.production.id, "S1E01", "SC01-01").encode()
    private fun task(id: String, state: MediaTaskState, estimate: String?, actual: String? = null, currency: String = "CNY", ref: String = source) = MediaTask(id,
        MediaGenerationRequest("v", GenerationMode.TEXT, "画面", emptyList(), ref, sessionId = "s", outputDir = "takes"),
        estimate?.let { Money(BigDecimal(it), currency) }, state, actualCost = actual?.let { Money(BigDecimal(it), currency) })
    @Test fun `预算包含排队生成下载和查询未知的占用且完成按实际计费`() = runTest {
        queue.tasks.value = listOf(task("a", MediaTaskState.QUEUED, "1"), task("b", MediaTaskState.RUNNING, "2"), task("c", MediaTaskState.COMPLETED, "3", "1.5"),
            task("d", MediaTaskState.RESULT_UNKNOWN, "1"), task("e", MediaTaskState.DOWNLOAD_FAILED, "3", "0.5"),
            task("other", MediaTaskState.RUNNING, "99", ref = ProductionSourceRef("other", "S1E01", "SC01-01").encode()))
        val budget = ProductionMediaBudgetReader(store, queue).readBudget(source)!!
        assertEquals(BigDecimal("10.00"), budget.limit.amount)
        assertEquals(0, BigDecimal("6.0").compareTo(budget.spent.amount))
        assertFalse(WithinBudgetAutoSubmitPolicy(ProductionMediaBudgetReader(store, queue)).allowsAutoSubmit(source, Money(BigDecimal("4.01"), "CNY")))
    }
    @Test fun `已发生费用未知或异币种不自动放行`() = runTest {
        val reader = ProductionMediaBudgetReader(store, queue)
        queue.tasks.value = listOf(task("unknown", MediaTaskState.COMPLETED, "2"))
        assertNull(reader.readBudget(source))
        queue.tasks.value = listOf(task("usd", MediaTaskState.RUNNING, "2", currency = "USD"))
        assertNull(reader.readBudget(source))
        store.production = store.production.copy(budgetCents = null)
        assertNull(reader.readBudget(source))
        assertNull(reader.readBudget("invalid"))
    }
}
