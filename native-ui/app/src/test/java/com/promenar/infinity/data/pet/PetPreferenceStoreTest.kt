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
        every { it.putString(any(), any()) } returns it
    }

    private fun storeWith(stored: Boolean?): PetPreferenceStore {
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { getBoolean(any(), any()) } answers { secondArg() }
            every { getString(any(), any()) } answers { secondArg() }
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
    @Test
    fun `宠物关联与台词视频偏好读写`() {
        val store = storeWith(stored = null)
        assertThat(store.defaultPetId.value).isNull()
        assertThat(store.modelPets.value).isEmpty()
        assertThat(store.linesEnabled.value).isTrue()
        assertThat(store.linesModelId.value).isEmpty()
        assertThat(store.videoEnabled.value).isTrue()
        store.setDefaultPetId("fish")
        store.setModelPet("model", "fish")
        store.setModelPet("model2", "fish")
        store.setModelPet("model", null)
        store.setLinesEnabled(false)
        store.setLinesModelId("stable-model")
        store.setVideoEnabled(false)
        assertThat(store.defaultPetId.value).isEqualTo("fish")
        assertThat(store.modelPets.value).containsExactly("model2", "fish")
        assertThat(store.linesEnabled.value).isFalse()
        assertThat(store.linesModelId.value).isEqualTo("stable-model")
        assertThat(store.videoEnabled.value).isFalse()
        verify { editor.putString("pet_default_id", "fish") }
        verify { editor.putString("pet_model_map", any()) }
        verify { editor.putBoolean("pet_lines_enabled", false) }
        verify { editor.putString("pet_lines_model", "stable-model") }
        verify { editor.putBoolean("pet_video_enabled", false) }
    }
    @Test
    fun `持久化的模型映射可以重新读取且损坏数据回退为空`() {
        fun load(value: String): PetPreferenceStore {
            val prefs = mockk<SharedPreferences>(relaxed = true) {
                every { getBoolean(any(), any()) } answers { secondArg() }
                every { getString(any(), any()) } answers { secondArg() }
                every { getString("pet_model_map", any()) } returns value
            }
            val context = mockk<Context>()
            every { context.applicationContext } returns context
            every { context.getSharedPreferences(any(), any()) } returns prefs
            return PetPreferenceStore(context)
        }
        assertThat(load("{\"m\":\"pet\"}").modelPets.value).containsExactly("m", "pet")
        assertThat(load("{").modelPets.value).isEmpty()
    }
}
