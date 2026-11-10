package com.promenar.infinity.domain.pet

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Test

class PetMemoryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val labels = PetMemoryLabels("今天", "昨天", { "${it}天前" }, "完成", "失败", "取消")
    private fun time(value: String) = Instant.parse(value).toEpochMilli()
    private fun data(previous: Long?, now: Long) = petMemoryLineData(PetMemorySnapshot(previous, 1, null, 1), now, zone, labels)

    @Test fun `台词键按自然日阈值和结果显示`() {
        val now = time("2026-03-01T16:10:00Z")
        assertThat(data(null, now)).isEmpty()
        assertThat(data(now - 30 * 60_000, now)).containsExactly("memory.session.last", "昨天")
        assertThat(data(now - 11 * 60_000, now)).containsExactly("memory.session.last", "昨天")
        assertThat(data(time("2026-02-28T15:00:00Z"), now)).containsExactly("memory.session.last", "2天前")
        assertThat(data(now + 60_000, now)).isEmpty()
        val noon = time("2026-03-01T04:00:00Z")
        assertThat(data(noon - 30 * 60_000, noon)).containsExactly("memory.session.last", "今天")
        assertThat(data(noon - 30 * 60_000 + 1, noon)).isEmpty()
        for ((result, label) in mapOf(PetTaskResult.DONE to "完成", PetTaskResult.ERROR to "失败", PetTaskResult.CANCELLED to "取消")) {
            val all = petMemoryLineData(PetMemorySnapshot(noon - 30 * 60_000, 2, result, 3), noon, zone, labels)
            assertThat(all).containsExactly("memory.session.last", "今天", "memory.session.visits", "2", "memory.last_result", label, "memory.streak.days", "3")
        }
    }

    @Test fun `重复进入与间隔进入保留上一次活动`() {
        val first = time("2026-01-01T02:00:00Z")
        var state = PetMemoryLedger.visit(PetMemoryState(), "a", first, zone)
        state = PetMemoryLedger.visit(state, "a", first + 10 * 60_000, zone)
        assertThat(PetMemoryLedger.snapshot(state, "a", first, zone).previousActiveMillis).isNull()
        state = PetMemoryLedger.visit(state, "a", first + 40 * 60_000, zone)
        state = PetMemoryLedger.visit(state, "a", first + 45 * 60_000, zone)
        var snapshot = PetMemoryLedger.snapshot(state, "a", first, zone)
        assertThat(snapshot.previousActiveMillis).isEqualTo(first + 10 * 60_000)
        assertThat(snapshot.visitDays).isEqualTo(1)
        state = PetMemoryLedger.result(state, "a", PetTaskResult.ERROR, first + 46 * 60_000)
        snapshot = PetMemoryLedger.snapshot(state, "a", first, zone)
        assertThat(snapshot.lastResult).isEqualTo(PetTaskResult.ERROR)
        state = PetMemoryLedger.visit(state, "a", first + 86_400_000, zone)
        snapshot = PetMemoryLedger.snapshot(state, "a", first + 86_400_000, zone)
        assertThat(snapshot.visitDays).isEqualTo(2)
        assertThat(snapshot.previousActiveMillis).isEqualTo(first + 46 * 60_000)
        assertThat(snapshot.streakDays).isEqualTo(2)
    }

    @Test fun `连续天数保持递增重置且回拨不减少`() {
        val first = time("2026-01-01T02:00:00Z")
        var state = PetMemoryLedger.visit(PetMemoryState(), "a", first, zone)
        state = PetMemoryLedger.visit(state, "b", first + 86_400_000, zone)
        state = PetMemoryLedger.visit(state, "b", first + 86_400_000 + 1000, zone)
        assertThat(state.streakDays).isEqualTo(2)
        val beforeRollback = state
        state = PetMemoryLedger.visit(state, "b", first, zone)
        assertThat(state.streakDays).isEqualTo(2)
        assertThat(state.lastUseDay).isEqualTo(beforeRollback.lastUseDay)
        assertThat(state.sessions.getValue("b")).isEqualTo(beforeRollback.sessions.getValue("b"))
        assertThat(PetMemoryLedger.snapshot(state, "b", first + 3 * 86_400_000L, zone).streakDays).isEqualTo(1)
        state = PetMemoryLedger.visit(state, "a", first + 3 * 86_400_000L, zone)
        assertThat(state.streakDays).isEqualTo(1)
    }

    @Test fun `保留最近活动的二百个会话`() {
        var state = PetMemoryState()
        repeat(201) { state = PetMemoryLedger.visit(state, "s$it", it.toLong() * 1000, zone) }
        assertThat(state.sessions).hasSize(200)
        assertThat(state.sessions).doesNotContainKey("s0")
        assertThat(state.sessions).containsKey("s200")
        state = PetMemoryLedger.result(state, "new", PetTaskResult.DONE, 500_000)
        assertThat(state.sessions).hasSize(200)
        assertThat(state.sessions).doesNotContainKey("s1")
    }
}
