package com.promenar.infinity.data.pet

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.pet.PetTaskResult
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import java.time.ZoneId

class PetMemoryStoreTest {
    private val values = mutableMapOf<String, String>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true).also { editor ->
        every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg(); editor }
        every { editor.remove(any()) } answers { values.remove(firstArg<String>()); editor }
    }
    private val prefs = mockk<SharedPreferences>(relaxed = true).also { prefs ->
        every { prefs.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { prefs.edit() } returns editor
    }
    private val context = mockk<Context>().also { context ->
        every { context.applicationContext } returns context
        every { context.getSharedPreferences("infinity_pet_memory", Context.MODE_PRIVATE) } returns prefs
    }
    private val zone = ZoneId.of("UTC")

    @Test fun `写入后新实例读取并删除单个会话`() {
        val store = PetMemoryStore(context)
        store.visit("a", 0, zone)
        store.result("a", PetTaskResult.DONE, 100)
        store.visit("b", 200, zone)
        val restored = PetMemoryStore(context)
        assertThat(restored.snapshot("a", 200, zone).lastResult).isEqualTo(PetTaskResult.DONE)
        assertThat(restored.snapshot("a", 200, zone).visitDays).isEqualTo(1)
        restored.forget("a")
        val after = PetMemoryStore(context)
        assertThat(after.snapshot("a", 200, zone).lastResult).isNull()
        assertThat(after.snapshot("b", 200, zone).visitDays).isEqualTo(1)
        after.clear()
        assertThat(PetMemoryStore(context).snapshot("b", 200, zone).visitDays).isEqualTo(0)
        assertThat(values).isEmpty()
    }

    @Test fun `损坏内容回退为空且未知字段可读取`() {
        values["state"] = "{"
        assertThat(PetMemoryStore(context).snapshot("a", 0, zone).visitDays).isEqualTo(0)
        values["state"] = """{"unknown":true,"sessions":{"a":{"visitDays":3,"future":1}}}"""
        assertThat(PetMemoryStore(context).snapshot("a", 0, zone).visitDays).isEqualTo(3)
    }
}
