package com.promenar.infinity.domain.pet

enum class PetMoodEvent { TASK_DONE, PETTED, TASK_ERROR, HARD_LANDING, ANNOYED, DISMISSED }

class PetMood(value: Float = 0f) {
    companion object {
        private const val TASK_DONE_DELTA = 8f
        private const val PETTED_DELTA = 5f
        private const val TASK_ERROR_DELTA = -10f
        private const val HARD_LANDING_DELTA = -6f
        private const val ANNOYED_DELTA = -8f
        private const val DISMISSED_DELTA = -4f
        private const val DECAY_PER_HOUR = 20f
    }
    var value: Float = if (value.isFinite()) value.coerceIn(-100f, 100f) else 0f
        private set
    fun apply(event: PetMoodEvent) {
        val delta = when (event) {
            PetMoodEvent.TASK_DONE -> TASK_DONE_DELTA
            PetMoodEvent.PETTED -> PETTED_DELTA
            PetMoodEvent.TASK_ERROR -> TASK_ERROR_DELTA
            PetMoodEvent.HARD_LANDING -> HARD_LANDING_DELTA
            PetMoodEvent.ANNOYED -> ANNOYED_DELTA
            PetMoodEvent.DISMISSED -> DISMISSED_DELTA
        }
        value = (value + delta).coerceIn(-100f, 100f)
    }
    fun decay(seconds: Float) {
        if (!seconds.isFinite() || seconds <= 0f) return
        val amount = seconds * (DECAY_PER_HOUR / 3600f)
        value = if (value > 0f) (value - amount).coerceAtLeast(0f) else (value + amount).coerceAtMost(0f)
    }
    val qualifier: String? get() = when { value > 40f -> "mood.high"; value < -40f -> "mood.low"; else -> null }
    val dataValue: String get() = when (qualifier) { "mood.high" -> "high"; "mood.low" -> "low"; else -> "normal" }
}
