package com.promenar.infinity.data.pet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 会话宠物的用户偏好。 */
class PetPreferenceStore(context: Context) {
    companion object {
        const val ENABLED_KEY = "pet_enabled"
        const val DEFAULT_PET_ID_KEY = "pet_default_id"
        const val MODEL_MAP_KEY = "pet_model_map"
        const val LINES_ENABLED_KEY = "pet_lines_enabled"
        const val LINES_MODEL_KEY = "pet_lines_model"
        const val VIDEO_ENABLED_KEY = "pet_video_enabled"
        private const val PREFERENCES_NAME = "infinity_settings"
    }

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val _enabled = MutableStateFlow(preferences.getBoolean(ENABLED_KEY, false))
    private val _defaultPetId = MutableStateFlow(preferences.getString(DEFAULT_PET_ID_KEY, null))
    private val _modelPets = MutableStateFlow(runCatching {
        Json.decodeFromString(mapSerializer, preferences.getString(MODEL_MAP_KEY, null) ?: "{}")
    }.getOrDefault(emptyMap()))
    private val _linesEnabled = MutableStateFlow(preferences.getBoolean(LINES_ENABLED_KEY, true))
    private val _linesModelId = MutableStateFlow(preferences.getString(LINES_MODEL_KEY, "").orEmpty())
    private val _videoEnabled = MutableStateFlow(preferences.getBoolean(VIDEO_ENABLED_KEY, true))

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val defaultPetId: StateFlow<String?> = _defaultPetId.asStateFlow()
    val modelPets: StateFlow<Map<String, String>> = _modelPets.asStateFlow()
    val linesEnabled: StateFlow<Boolean> = _linesEnabled.asStateFlow()
    val linesModelId: StateFlow<String> = _linesModelId.asStateFlow()
    val videoEnabled: StateFlow<Boolean> = _videoEnabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        preferences.edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    fun setDefaultPetId(id: String?) {
        _defaultPetId.value = id
        preferences.edit().putString(DEFAULT_PET_ID_KEY, id).apply()
    }

    @Synchronized
    fun setModelPet(modelId: String, petId: String?) {
        val updated = _modelPets.value.toMutableMap()
        if (petId == null) updated.remove(modelId) else updated[modelId] = petId
        _modelPets.value = updated.toMap()
        preferences.edit().putString(MODEL_MAP_KEY, Json.encodeToString(mapSerializer, updated)).apply()
    }

    fun setLinesEnabled(enabled: Boolean) {
        _linesEnabled.value = enabled
        preferences.edit().putBoolean(LINES_ENABLED_KEY, enabled).apply()
    }

    fun setLinesModelId(modelId: String) {
        _linesModelId.value = modelId
        preferences.edit().putString(LINES_MODEL_KEY, modelId).apply()
    }

    fun setVideoEnabled(enabled: Boolean) {
        _videoEnabled.value = enabled
        preferences.edit().putBoolean(VIDEO_ENABLED_KEY, enabled).apply()
    }
}
