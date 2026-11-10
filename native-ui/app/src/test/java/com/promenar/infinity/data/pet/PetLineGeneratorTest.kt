package com.promenar.infinity.data.pet

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.data.rag.KgUsageRecord
import com.promenar.infinity.data.rag.KgUsageSnapshot
import com.promenar.infinity.data.rag.KgUsageStore
import com.promenar.infinity.domain.pet.PetLineLimiter
import com.promenar.infinity.domain.pet.PetPersona
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PetLineGeneratorTest {
    private fun preferences(): PetPreferenceStore {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } answers { secondArg() }
        every { prefs.getBoolean(any(), any()) } answers { secondArg() }
        every { prefs.edit() } returns editor
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return PetPreferenceStore(context)
    }
    private class Usage : KgUsageStore {
        val records = mutableListOf<KgUsageRecord>()
        override suspend fun record(record: KgUsageRecord) { records += record }
        override suspend fun snapshot() = KgUsageSnapshot(records)
        override suspend fun clear() { records.clear() }
    }
    private val persona = PetPersona(name = "小鱼")

    @Test fun `开关模型与限流控制请求`() = runBlocking {
        val prefs = preferences()
        val usage = Usage()
        var calls = 0
        val generator = PetLineGenerator(prefs, usage, { _, _ ->
            calls++
            "  “你好”\n" to TokenUsage(1, 2, 3)
        }, PetLineLimiter({ 100_000L }))
        assertThat(generator.generate(persona, "idle", emptyMap())).isNull()
        prefs.setLinesModelId("model")
        prefs.setLinesEnabled(false)
        assertThat(generator.generate(persona, "idle", emptyMap())).isNull()
        prefs.setLinesEnabled(true)
        assertThat(generator.generate(persona, "idle", emptyMap())).isEqualTo("你好")
        assertThat(generator.generate(persona, "idle", emptyMap())).isNull()
        assertThat(calls).isEqualTo(1)
        assertThat(usage.records.single().usage.inputTokens).isEqualTo(1)
    }

    @Test fun `异常空输出和兜底间隔`() = runBlocking {
        val prefs = preferences()
        prefs.setLinesModelId("model")
        var now = 100_000L
        val usage = Usage()
        val throwing = PetLineGenerator(prefs, usage, { _, callback ->
            callback(TokenUsage(3, 4, 7))
            error("失败")
        }, PetLineLimiter({ now }))
        assertThat(throwing.generate(persona, "idle", emptyMap())).isNull()
        assertThat(usage.records.single().usage.outputTokens).isEqualTo(4)
        val empty = PetLineGenerator(prefs, usage, { _, _ -> "  \n" to TokenUsage(1, 1, 2) }, PetLineLimiter({ now }))
        assertThat(empty.generate(persona, "idle", emptyMap())).isNull()
        now += 30_000
        empty.recordFallbackLine()
        assertThat(empty.generate(persona, "idle", emptyMap())).isNull()
    }
}
