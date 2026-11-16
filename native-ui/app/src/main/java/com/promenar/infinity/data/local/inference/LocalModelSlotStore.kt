package com.promenar.infinity.data.local.inference

import android.content.Context
import android.content.SharedPreferences

/**
 * 持久化本地推理三个槽位（主生成器、嵌入、重排序）上次成功加载的模型路径。
 * 属于设备本机状态（不随跨设备备份迁移），用于在重开本地推理或应用启动时自动恢复。
 */
internal class LocalModelSlotStore(
    private val settingsPrefs: SharedPreferences,
    private val legacyProviderPrefs: SharedPreferences? = null,
) {
    constructor(context: Context) : this(
        settingsPrefs = context.getSharedPreferences("infinity_settings", Context.MODE_PRIVATE),
        legacyProviderPrefs = context.getSharedPreferences("infinity_provider", Context.MODE_PRIVATE),
    )

    fun readSlotPath(slot: SlotType): String? {
        val current = settingsPrefs.getString(keyFor(slot), null)?.takeIf { it.isNotBlank() }
        if (current != null || slot != SlotType.MAIN) return current
        return legacyProviderPrefs?.getString(KEY_MAIN_MODEL, null)?.takeIf { it.isNotBlank() }
    }

    fun readAllSlotPaths(): Map<SlotType, String> = buildMap {
        SlotType.entries.forEach { slot ->
            readSlotPath(slot)?.let { put(slot, it) }
        }
    }

    fun readRestorableSlotPaths(
        localModelsEnabled: Boolean,
        pinnedMainPath: String? = null,
        fileExists: (String) -> Boolean = { true },
    ): Map<SlotType, String> {
        if (!localModelsEnabled) return emptyMap()
        val saved = readAllSlotPaths()
        return buildMap {
            val mainCandidate = pinnedMainPath?.takeIf { it.isNotBlank() } ?: saved[SlotType.MAIN]
            if (mainCandidate != null && fileExists(mainCandidate)) {
                put(SlotType.MAIN, mainCandidate)
            }
            listOf(SlotType.EMBEDDING, SlotType.RERANK).forEach { slot ->
                val path = saved[slot]
                if (path != null && fileExists(path)) {
                    put(slot, path)
                }
            }
        }
    }

    fun saveSlotPath(slot: SlotType, modelPath: String) {
        if (modelPath.isBlank()) return
        settingsPrefs.edit()
            .putString(keyFor(slot), modelPath)
            .putBoolean(KEY_LOCAL_AUTO_LOAD, true)
            .apply()
        if (slot == SlotType.MAIN) {
            legacyProviderPrefs?.edit()?.putString(KEY_MAIN_MODEL, modelPath)?.apply()
        }
    }

    fun clearSlot(slot: SlotType) {
        val remainingAny = SlotType.entries.any { it != slot && readSlotPath(it) != null }
        settingsPrefs.edit()
            .remove(keyFor(slot))
            .putBoolean(KEY_LOCAL_AUTO_LOAD, remainingAny)
            .apply()
        if (slot == SlotType.MAIN) {
            legacyProviderPrefs?.edit()?.remove(KEY_MAIN_MODEL)?.apply()
        }
    }

    fun clearModelPath(filePath: String) {
        if (filePath.isBlank()) return
        SlotType.entries.forEach { slot ->
            if (readSlotPath(slot) == filePath) {
                clearSlot(slot)
            }
        }
    }

    private fun keyFor(slot: SlotType): String = when (slot) {
        SlotType.MAIN -> KEY_MAIN_MODEL
        SlotType.EMBEDDING -> KEY_EMBEDDING_MODEL
        SlotType.RERANK -> KEY_RERANK_MODEL
    }

    companion object {
        const val KEY_LOCAL_AUTO_LOAD = "local_auto_load"
        const val KEY_MAIN_MODEL = "last_local_model"
        const val KEY_EMBEDDING_MODEL = "last_local_embedding_model"
        const val KEY_RERANK_MODEL = "last_local_rerank_model"
    }
}
