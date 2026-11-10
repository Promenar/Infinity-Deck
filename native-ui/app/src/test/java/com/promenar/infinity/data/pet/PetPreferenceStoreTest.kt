package com.promenar.infinity.data.pet

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class PetPreferenceStoreTest {
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true).also {
        every { it.putBoolean(any(), any()) } returns it
    }

    private fun storeWith(stored: Boolean?): PetPreferenceStore {
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { getBoolean("pet_enabled", any()) } answers { stored ?: secondArg() }
            every { edit() } returns editor
        }
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences("infinity_settings", Context.MODE_PRIVATE) } returns prefs
        return PetPreferenceStore(context)
    }

    @Test
    fun `没有保存过偏好时宠物默认关闭`() {
        assertThat(storeWith(stored = null).enabled.value).isFalse()
    }

    @Test
    fun `已启用的偏好在重建后恢复`() {
        assertThat(storeWith(stored = true).enabled.value).isTrue()
    }

    @Test
    fun `切换开关立即更新状态并写入偏好`() {
        val store = storeWith(stored = null)

        store.setEnabled(true)

        assertThat(store.enabled.value).isTrue()
        verify { editor.putBoolean("pet_enabled", true) }
        verify { editor.apply() }
    }
}
