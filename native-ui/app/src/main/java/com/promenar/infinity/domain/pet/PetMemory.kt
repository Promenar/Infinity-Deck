package com.promenar.infinity.domain.pet

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

enum class PetTaskResult { DONE, ERROR, CANCELLED }

/** 会话记忆中可供台词使用的数据。 */
data class PetMemorySnapshot(
    val previousActiveMillis: Long?,
    val visitDays: Int,
    val lastResult: PetTaskResult?,
    val streakDays: Int,
)

/** 台词数据的显示文字由界面层提供。 */
data class PetMemoryLabels(
    val earlierToday: String,
    val yesterday: String,
    val daysAgo: (Int) -> String,
    val done: String,
    val error: String,
    val cancelled: String,
)

private fun day(millis: Long, zone: ZoneId) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

/** 生成有值的台词数据。 */
fun petMemoryLineData(snapshot: PetMemorySnapshot, nowMillis: Long, zone: ZoneId, labels: PetMemoryLabels): Map<String, String> = buildMap {
    snapshot.previousActiveMillis?.let { previous ->
        if (previous <= nowMillis) {
            val days = day(nowMillis, zone) - day(previous, zone)
            val label = when {
                days == 0L && Instant.ofEpochMilli(previous).plusSeconds(30 * 60) <= Instant.ofEpochMilli(nowMillis) -> labels.earlierToday
                days == 1L -> labels.yesterday
                days >= 2L -> labels.daysAgo(days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                else -> null
            }
            if (label != null) put("memory.session.last", label)
        }
    }
    if (snapshot.visitDays >= 2) put("memory.session.visits", snapshot.visitDays.toString())
    snapshot.lastResult?.let { result ->
        put("memory.last_result", when (result) {
            PetTaskResult.DONE -> labels.done
            PetTaskResult.ERROR -> labels.error
            PetTaskResult.CANCELLED -> labels.cancelled
        })
    }
    if (snapshot.streakDays >= 2) put("memory.streak.days", snapshot.streakDays.toString())
}

/** 单个会话的本机记忆。 */
@Serializable
data class PetSessionMemory(
    val lastActiveMillis: Long? = null,
    val previousActiveMillis: Long? = null,
    val visitDays: Int = 0,
    val lastVisitDay: Long? = null,
    val lastResult: PetTaskResult? = null,
)

/** 可序列化的记忆状态。 */
@Serializable
data class PetMemoryState(
    val sessions: Map<String, PetSessionMemory> = emptyMap(),
    val streakDays: Int = 0,
    val lastUseDay: Long? = null,
)

object PetMemoryLedger {
    private const val MAX_SESSIONS = 200

    /** 进入会话时更新访问及应用连续使用记录。 */
    fun visit(state: PetMemoryState, sessionId: String, nowMillis: Long, zone: ZoneId): PetMemoryState {
        val today = day(nowMillis, zone)
        val session = state.sessions[sessionId] ?: PetSessionMemory()
        val last = session.lastActiveMillis
        val forward = last == null || nowMillis >= last
        val newStay = last != null && forward &&
            Instant.ofEpochMilli(last).plusSeconds(30 * 60) <= Instant.ofEpochMilli(nowMillis)
        val newDay = session.lastVisitDay == null || today > session.lastVisitDay
        val updated = session.copy(
            lastActiveMillis = if (forward) nowMillis else last,
            previousActiveMillis = if (newStay) last else session.previousActiveMillis,
            visitDays = if (newDay) (session.visitDays.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else session.visitDays,
            lastVisitDay = if (newDay) today else session.lastVisitDay,
        )
        val useDay = state.lastUseDay
        val streak = when {
            useDay == null -> 1
            today <= useDay -> state.streakDays.coerceAtLeast(1)
            today - useDay == 1L -> (state.streakDays.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            else -> 1
        }
        return state.copy(sessions = trim(state.sessions + (sessionId to updated)), streakDays = streak,
            lastUseDay = if (useDay == null || today > useDay) today else useDay)
    }

    /** 记录任务结果及最近活动时间。 */
    fun result(state: PetMemoryState, sessionId: String, result: PetTaskResult, nowMillis: Long): PetMemoryState {
        val session = state.sessions[sessionId] ?: PetSessionMemory()
        return state.copy(sessions = trim(state.sessions + (sessionId to session.copy(
            lastResult = result, lastActiveMillis = maxOf(session.lastActiveMillis ?: nowMillis, nowMillis),
        ))))
    }

    fun snapshot(state: PetMemoryState, sessionId: String, nowMillis: Long, zone: ZoneId): PetMemorySnapshot {
        val session = state.sessions[sessionId] ?: PetSessionMemory()
        val lastDay = state.lastUseDay
        val streak = if (lastDay == null || day(nowMillis, zone) - lastDay > 1) 1 else state.streakDays.coerceAtLeast(1)
        return PetMemorySnapshot(session.previousActiveMillis, session.visitDays, session.lastResult, streak)
    }

    private fun trim(sessions: Map<String, PetSessionMemory>): Map<String, PetSessionMemory> =
        if (sessions.size <= MAX_SESSIONS) sessions else sessions.entries
            .sortedWith(compareBy<Map.Entry<String, PetSessionMemory>> { it.value.lastActiveMillis ?: Long.MIN_VALUE }.thenBy { it.key })
            .drop(sessions.size - MAX_SESSIONS).associate { it.key to it.value }
}
