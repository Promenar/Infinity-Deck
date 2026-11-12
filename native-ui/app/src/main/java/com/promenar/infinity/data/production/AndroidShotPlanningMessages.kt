package com.promenar.infinity.data.production

import android.content.Context
import com.promenar.infinity.R

class AndroidShotPlanningMessages(private val context: Context) : ShotPlanningMessages {
    override fun text(key: String, detail: String): String = context.getString(when (key) {
        "missing_project" -> R.string.shot_plan_missing_project
        "missing_shot" -> R.string.shot_plan_missing_shot
        "unlocked" -> R.string.shot_plan_unlocked
        "no_channel" -> R.string.shot_plan_no_channel
        "no_channel_mode" -> R.string.shot_plan_no_channel_mode
        "missing_input" -> R.string.shot_plan_missing_input
        "overflow" -> R.string.shot_plan_overflow
        "invalid_plan" -> R.string.shot_plan_invalid
        "unavailable_backend" -> R.string.shot_plan_backend
        "reference_review" -> R.string.shot_plan_reference_review
        "missing_voice" -> R.string.shot_plan_missing_voice
        "workflow_timing" -> R.string.shot_plan_workflow_timing
        "workflow_timing_frames" -> R.string.shot_plan_workflow_frames
        "plan_changed" -> R.string.shot_plan_changed
        "candidate_capacity" -> R.string.shot_plan_candidate_capacity
        "audio_failed" -> R.string.shot_plan_audio_failed
        else -> R.string.shot_plan_failed
    }, detail)
}
