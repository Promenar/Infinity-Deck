package com.promenar.infinity.data.local.inference

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class LocalModelSlotStoreTest {
    private fun fakePrefs(initial: Map<String, Any?> = emptyMap()): SharedPreferences {
        val data = initial.toMutableMap()
        return object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = data.toMutableMap()
            override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
            override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
            override fun contains(key: String?): Boolean = data.containsKey(key)
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                private val pending = mutableMapOf<String, Any?>()
                private val removed = mutableSetOf<String>()
                private var cleared = false

                override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
                override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key] = values }
                override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
                override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
                override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
                override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
                override fun remove(key: String): SharedPreferences.Editor = apply { removed += key }
                override fun clear(): SharedPreferences.Editor = apply { cleared = true }
                override fun commit(): Boolean {
                    apply()
                    return true
                }

                override fun apply() {
                    if (cleared) data.clear()
                    removed.forEach { data.remove(it) }
                    pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
                }
            }
        }
    }

    @Test
    fun `保存与读取三个槽位并自动开启重启加载标记`() {
        val settings = fakePrefs()
        val legacy = fakePrefs()
        val store = LocalModelSlotStore(settings, legacy)

        store.saveSlotPath(SlotType.MAIN, "/models/qwen.gguf")
        store.saveSlotPath(SlotType.EMBEDDING, "/models/bge-m3.gguf")
        store.saveSlotPath(SlotType.RERANK, "/models/bge-reranker.gguf")

        assertThat(settings.getBoolean(LocalModelSlotStore.KEY_LOCAL_AUTO_LOAD, false)).isTrue()
        assertThat(store.readAllSlotPaths()).containsExactly(
            SlotType.MAIN, "/models/qwen.gguf",
            SlotType.EMBEDDING, "/models/bge-m3.gguf",
            SlotType.RERANK, "/models/bge-reranker.gguf",
        )
    }

    @Test
    fun `卸载槽位与删除文件时同步清理对应持久化记录`() {
        val settings = fakePrefs()
        val legacy = fakePrefs(mapOf(LocalModelSlotStore.KEY_MAIN_MODEL to "/models/legacy.gguf"))
        val store = LocalModelSlotStore(settings, legacy)

        assertThat(store.readSlotPath(SlotType.MAIN)).isEqualTo("/models/legacy.gguf")
        store.clearSlot(SlotType.MAIN)
        assertThat(store.readSlotPath(SlotType.MAIN)).isNull()
        assertThat(legacy.contains(LocalModelSlotStore.KEY_MAIN_MODEL)).isFalse()

        store.saveSlotPath(SlotType.EMBEDDING, "/models/shared.gguf")
        store.saveSlotPath(SlotType.RERANK, "/models/rerank.gguf")
        store.clearModelPath("/models/shared.gguf")
        assertThat(store.readSlotPath(SlotType.EMBEDDING)).isNull()
        assertThat(store.readSlotPath(SlotType.RERANK)).isEqualTo("/models/rerank.gguf")
        assertThat(settings.getBoolean(LocalModelSlotStore.KEY_LOCAL_AUTO_LOAD, false)).isTrue()

        store.clearSlot(SlotType.RERANK)
        assertThat(settings.getBoolean(LocalModelSlotStore.KEY_LOCAL_AUTO_LOAD, true)).isFalse()
    }

    @Test
    fun `重启恢复按总开关与固定版本及文件存在性解析待加载槽位`() {
        val settings = fakePrefs()
        val store = LocalModelSlotStore(settings)
        store.saveSlotPath(SlotType.MAIN, "/models/main.gguf")
        store.saveSlotPath(SlotType.EMBEDDING, "/models/embed.gguf")
        store.saveSlotPath(SlotType.RERANK, "/models/missing.gguf")

        assertThat(store.readRestorableSlotPaths(localModelsEnabled = false)).isEmpty()

        val existing = setOf("/models/pinned.gguf", "/models/embed.gguf")
        assertThat(
            store.readRestorableSlotPaths(
                localModelsEnabled = true,
                pinnedMainPath = "/models/pinned.gguf",
                fileExists = { File(it).path in existing },
            ),
        ).containsExactly(
            SlotType.MAIN, "/models/pinned.gguf",
            SlotType.EMBEDDING, "/models/embed.gguf",
        )
    }
}
