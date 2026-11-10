package com.promenar.infinity.data.pet

import android.content.Context
import com.promenar.infinity.domain.pet.PetMemoryLedger
import com.promenar.infinity.domain.pet.PetMemorySnapshot
import com.promenar.infinity.domain.pet.PetMemoryState
import com.promenar.infinity.domain.pet.PetTaskResult
import java.time.ZoneId
import kotlinx.serialization.json.Json

/** 仅保存在本机的会话记忆。 */
class PetMemoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("infinity_pet_memory", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private var state = runCatching {
        json.decodeFromString(PetMemoryState.serializer(), preferences.getString("state", null) ?: "{}")
    }.getOrDefault(PetMemoryState())

    @Synchronized
    fun visit(sessionId: String, nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        state = PetMemoryLedger.visit(state, sessionId, nowMillis, zone)
        persist()
    }

    @Synchronized
    fun result(sessionId: String, result: PetTaskResult, nowMillis: Long = System.currentTimeMillis()) {
        state = PetMemoryLedger.result(state, sessionId, result, nowMillis)
        persist()
    }

    @Synchronized
    fun snapshot(sessionId: String, nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): PetMemorySnapshot =
        PetMemoryLedger.snapshot(state, sessionId, nowMillis, zone)

    @Synchronized
    fun forget(sessionId: String) {
        state = state.copy(sessions = state.sessions - sessionId)
        persist()
    }

    @Synchronized
    fun clear() {
        state = PetMemoryState()
        preferences.edit().remove("state").apply()
    }

    private fun persist() {
        preferences.edit().putString("state", json.encodeToString(PetMemoryState.serializer(), state)).apply()
    }
}
