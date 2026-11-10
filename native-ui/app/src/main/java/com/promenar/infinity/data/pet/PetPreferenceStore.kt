package com.promenar.infinity.data.pet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

data class PetPerchPlacement(val xFraction: Float, val yDp: Float)
data class PetMoodSnapshot(val value: Float, val atMillis: Long)

/** 会话宠物的用户偏好。 */
class PetPreferenceStore(context: Context) {
    companion object {
        const val ENABLED_KEY = "pet_enabled"
        const val DEFAULT_PET_ID_KEY = "pet_default_id"
        const val MODEL_MAP_KEY = "pet_model_map"
        const val LINES_ENABLED_KEY = "pet_lines_enabled"
        const val LINES_MODEL_KEY = "pet_lines_model"
        const val VIDEO_ENABLED_KEY = "pet_video_enabled"
        const val DISMISSED_KEY = "pet_dismissed"
        const val DISMISS_HINT_COUNT_KEY = "pet_dismiss_hint_count"
        const val FLING_GESTURE_ENABLED_KEY = "pet_fling_gesture_enabled"
        const val MOTION_SENSOR_ENABLED_KEY = "pet_motion_sensor_enabled"
        const val HAPTICS_ENABLED_KEY = "pet_haptics_enabled"
        const val PERCH_KIND_KEY = "pet_perch_kind"
        const val BLOW_ENABLED_KEY = "pet_blow_enabled"
        const val PERCH_X_KEY = "pet_perch_x_fraction"
        const val PERCH_Y_KEY = "pet_perch_y_dp"
        const val MOOD_VALUE_KEY = "pet_mood_value"
        const val MOOD_AT_KEY = "pet_mood_at_millis"
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

    private val _dismissed = MutableStateFlow(preferences.getBoolean(DISMISSED_KEY, false))
    private val _dismissHintCount = MutableStateFlow(preferences.getInt(DISMISS_HINT_COUNT_KEY, 0))
    private val _flingGestureEnabled = MutableStateFlow(preferences.getBoolean(FLING_GESTURE_ENABLED_KEY, true))
    private val _motionSensorEnabled = MutableStateFlow(preferences.getBoolean(MOTION_SENSOR_ENABLED_KEY, true))
    private val _hapticsEnabled = MutableStateFlow(preferences.getBoolean(HAPTICS_ENABLED_KEY, true))
    private val _perchKind = MutableStateFlow(normalizePerchKind(preferences.getString(PERCH_KIND_KEY, "cloud")))
    private val _blowEnabled = MutableStateFlow(preferences.getBoolean(BLOW_ENABLED_KEY, false))
    private val _perch = MutableStateFlow(if (preferences.contains(PERCH_X_KEY) && preferences.contains(PERCH_Y_KEY))
        PetPerchPlacement(preferences.getFloat(PERCH_X_KEY, 0f), preferences.getFloat(PERCH_Y_KEY, 0f)) else null)
    private val _mood = MutableStateFlow(PetMoodSnapshot(preferences.getFloat(MOOD_VALUE_KEY, 0f), preferences.getLong(MOOD_AT_KEY, 0L)))

    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()
    val dismissHintCount: StateFlow<Int> = _dismissHintCount.asStateFlow()
    val flingGestureEnabled: StateFlow<Boolean> = _flingGestureEnabled.asStateFlow()
    val motionSensorEnabled: StateFlow<Boolean> = _motionSensorEnabled.asStateFlow()
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()
    val perchKind: StateFlow<String> = _perchKind.asStateFlow()
    val blowEnabled: StateFlow<Boolean> = _blowEnabled.asStateFlow()
    val perch: StateFlow<PetPerchPlacement?> = _perch.asStateFlow()
    val mood: StateFlow<PetMoodSnapshot> = _mood.asStateFlow()

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val defaultPetId: StateFlow<String?> = _defaultPetId.asStateFlow()
    val modelPets: StateFlow<Map<String, String>> = _modelPets.asStateFlow()
    val linesEnabled: StateFlow<Boolean> = _linesEnabled.asStateFlow()
    val linesModelId: StateFlow<String> = _linesModelId.asStateFlow()
    val videoEnabled: StateFlow<Boolean> = _videoEnabled.asStateFlow()

    private fun normalizePerchKind(kind: String?): String = if (kind in setOf("cloud", "bubble")) kind!! else "cloud"
    fun setDismissed(dismissed: Boolean) { _dismissed.value = dismissed; preferences.edit().putBoolean(DISMISSED_KEY, dismissed).apply() }
    fun setDismissHintCount(count: Int) { _dismissHintCount.value = count; preferences.edit().putInt(DISMISS_HINT_COUNT_KEY, count).apply() }
    fun setFlingGestureEnabled(enabled: Boolean) { _flingGestureEnabled.value = enabled; preferences.edit().putBoolean(FLING_GESTURE_ENABLED_KEY, enabled).apply() }
    fun setMotionSensorEnabled(enabled: Boolean) { _motionSensorEnabled.value = enabled; preferences.edit().putBoolean(MOTION_SENSOR_ENABLED_KEY, enabled).apply() }
    fun setHapticsEnabled(enabled: Boolean) { _hapticsEnabled.value = enabled; preferences.edit().putBoolean(HAPTICS_ENABLED_KEY, enabled).apply() }
    fun setPerchKind(kind: String) { val safe = normalizePerchKind(kind); _perchKind.value = safe; preferences.edit().putString(PERCH_KIND_KEY, safe).apply() }
    fun setBlowEnabled(enabled: Boolean) { _blowEnabled.value = enabled; preferences.edit().putBoolean(BLOW_ENABLED_KEY, enabled).apply() }
    fun setPerch(placement: PetPerchPlacement?) {
        _perch.value = placement
        val editor = preferences.edit()
        if (placement == null) editor.remove(PERCH_X_KEY).remove(PERCH_Y_KEY)
        else editor.putFloat(PERCH_X_KEY, placement.xFraction).putFloat(PERCH_Y_KEY, placement.yDp)
        editor.apply()
    }
    fun setMood(value: Float, atMillis: Long) {
        _mood.value = PetMoodSnapshot(value, atMillis)
        preferences.edit().putFloat(MOOD_VALUE_KEY, value).putLong(MOOD_AT_KEY, atMillis).apply()
    }

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
