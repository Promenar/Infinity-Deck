package com.promenar.infinity.domain.pet

import com.promenar.infinity.domain.generation.GenerationPhase

/** 信号名称与所属类别，见规格第 3.3、4、5 节。 */
object PetSignals {
    const val IDLE = "idle"
    const val IDLE_LONG = "idle.long"
    const val SLEEP = "sleep"
    const val TASK_WAITING = "task.waiting"
    const val TASK_WORKING = "task.working"
    const val TASK_NEEDS_USER = "task.needs_user"
    const val TASK_QUEUED = "task.queued"
    const val TASK_THINKING = "task.thinking"
    const val TASK_GENERATING = "task.generating"
    const val TASK_TOOL = "task.tool"
    const val TASK_APPROVAL = "task.approval"
    const val TASK_ASK = "task.ask"
    const val TASK_FINISHING = "task.finishing"
    const val TASK_DONE = "task.done"
    const val TASK_ERROR = "task.error"
    const val TASK_CANCELLED = "task.cancelled"
    const val ENV_REDUCED_MOTION = "env.reduced_motion"
    const val ENV_POWER_SAVE = "env.power_save"
    const val ENV_THEME_DARK = "env.theme.dark"
    const val ENV_THEME_LIGHT = "env.theme.light"
    const val ISLAND_FX_WAIT = "island.fx.wait"
    const val ISLAND_FX_STREAM = "island.fx.stream"
    const val ISLAND_FX_TOOL = "island.fx.tool"
    const val ISLAND_FX_APPROVAL = "island.fx.approval"
    const val ISLAND_FX_RETRY = "island.fx.retry"
    const val ISLAND_WIDER = "island.wider"
    const val ISLAND_NARROWER = "island.narrower"
    const val ISLAND_FOCUS = "island.focus"
    const val ISLAND_BLUR = "island.blur"
    const val ISLAND_SEND = "island.send"
    const val DEVICE_UNFOLD = "device.unfold"
    const val DEVICE_FOLD = "device.fold"
    const val PUSH_LEFT = "push.left"
    const val PUSH_RIGHT = "push.right"
    const val TOUCH_TAP = "touch.tap"
    const val TOUCH_HELD = "touch.held"
    const val TOUCH_FALL = "touch.fall"
    const val TOUCH_LAND_SOFT = "touch.land.soft"
    const val TOUCH_LAND_HARD = "touch.land.hard"
    const val FALL_OVER = "fall.over"
    const val GET_UP = "get.up"
    const val ROLE_ENTER = "role.enter"
    const val ROLE_EXIT = "role.exit"
    const val SESSION_OTHER_ACTIVE = "session.other.active"
    const val TOUCH_THROW = "touch.throw"
    const val TOUCH_BOUNCE = "touch.bounce"
    const val TOUCH_PET = "touch.pet"
    const val TOUCH_POKE = "touch.poke"
    const val TOUCH_POKE_HEAD = "touch.poke.head"
    const val TOUCH_POKE_BELLY = "touch.poke.belly"
    const val TOUCH_POKE_TAIL = "touch.poke.tail"
    const val TOUCH_ANNOYED = "touch.annoyed"
    const val TILT_SLIDE_LEFT = "tilt.slide.left"
    const val TILT_SLIDE_RIGHT = "tilt.slide.right"
    const val SHAKE_DIZZY = "shake.dizzy"
    const val ROLE_DISMISS = "role.dismiss"
    const val ROLE_SUMMON = "role.summon"
    const val KEYBOARD_UP = "keyboard.up"
    const val KEYBOARD_DOWN = "keyboard.down"
    const val PLACE_PERCH_ENTER = "place.perch.enter"
    const val PLACE_PERCH_LEAVE = "place.perch.leave"
    const val PLACE_PERCH_POP = "place.perch.pop"
    const val PLACE_PERCH_BLOWN = "place.perch.blown"
    val categories = mapOf(
        TASK_QUEUED to TASK_WAITING,
        TASK_THINKING to TASK_WORKING,
        TASK_GENERATING to TASK_WORKING,
        TASK_TOOL to TASK_WORKING,
        TASK_FINISHING to TASK_WORKING,
        TASK_APPROVAL to TASK_NEEDS_USER,
        TASK_ASK to TASK_NEEDS_USER,
        IDLE_LONG to IDLE,
    )
}

/** 信号回退后命中的映射，见规格第 3.3 节。 */
data class PetResolvedReaction(
    val signal: String,
    val reaction: PetReaction,
)
data class PetQualifiers(val place: String? = null, val duration: String? = null, val mood: String? = null) {
    companion object {
        val NONE = PetQualifiers()
        fun durationOf(seconds: Float): String? = when {
            seconds >= 300f -> "verylong"
            seconds >= 60f -> "long"
            else -> null
        }
    }
}

object PetSignalResolver {
    fun resolve(manifest: PetManifest, signal: String, qualifiers: PetQualifiers = PetQualifiers.NONE): PetResolvedReaction? {
        val chain = mutableListOf(signal)
        if (signal.startsWith("${PetSignals.TASK_TOOL}.")) {
            chain.add("${PetSignals.TASK_TOOL}.${PetToolCategories.categoryOf(signal.removePrefix("${PetSignals.TASK_TOOL}."))}")
            chain.add(PetSignals.TASK_TOOL)
        }
        PetSignals.categories[chain.last()]?.let { chain.add(it) }
        chain.add(PetSignals.IDLE)
        return lookup(manifest, chain, qualifiers)
    }

    fun resolveEvent(manifest: PetManifest, signal: String, qualifiers: PetQualifiers = PetQualifiers.NONE): PetResolvedReaction? {
        val chain = mutableListOf(signal)
        if (signal in setOf(PetSignals.TOUCH_POKE_HEAD, PetSignals.TOUCH_POKE_BELLY, PetSignals.TOUCH_POKE_TAIL)) chain.add(PetSignals.TOUCH_POKE)
        if (signal in setOf(PetSignals.TOUCH_POKE_HEAD, PetSignals.TOUCH_POKE_BELLY, PetSignals.TOUCH_POKE_TAIL, PetSignals.TOUCH_PET, PetSignals.TOUCH_ANNOYED)) chain.add(PetSignals.TOUCH_TAP)
        return lookup(manifest, chain, qualifiers)
    }

    private fun lookup(manifest: PetManifest, chain: List<String>, q: PetQualifiers): PetResolvedReaction? {
        val suffixes = listOfNotNull(q.place, q.duration, if (q.duration == "verylong") "long" else null, q.mood)
        for (key in chain.distinct()) {
            for (candidate in suffixes.map { "$key@$it" } + key) {
                manifest.map[candidate]?.let { return PetResolvedReaction(candidate, it) }
            }
        }
        return null
    }
}

object PetToolCategories {
    fun categoryOf(toolName: String): String = when (toolName) {
        "read_file", "list_files", "diff_file", "read_tool_output", "read_skill_file", "canvas_read", "canvas_list" -> "read"
        "write_file", "patch_file", "create_file", "create_directory", "move_file", "delete_file", "canvas_create", "canvas_update" -> "write"
        "search_files", "web_search", "knowledge_search", "memory_search" -> "search"
        "web_fetch", "browser_open", "browser_snapshot", "browser_click", "browser_type" -> "web"
        "shell_exec", "python_exec", "python_sandbox", "exec_js", "run_script", "termux_exec", "ssh_exec", "ssh_session_open", "ssh_session_send", "ssh_session_read", "ssh_session_close" -> "shell"
        "generate_image", "view_media", "media_fetch" -> "image"
        "schedule_create", "schedule_list", "schedule_pause", "schedule_resume", "schedule_cancel" -> "schedule"
        "agent_task", "agent_wait" -> "subagent"
        else -> "other"
    }
}

/** 内核任务阶段映射，见规格第 4.1 节。 */
object PetTaskSignalMapper {
    fun map(
        phase: GenerationPhase?,
        awaitingApproval: Boolean = false,
        awaitingAnswer: Boolean = false,
        contentGrowing: Boolean = false,
        reasoningGrowing: Boolean = false,
        toolName: String? = null,
    ): String {
        if (phase == null) return PetSignals.IDLE
        if (awaitingApproval) return PetSignals.TASK_APPROVAL
        if (awaitingAnswer) return PetSignals.TASK_ASK
        return when (phase) {
            GenerationPhase.QUEUED,
            GenerationPhase.PREPARING,
            GenerationPhase.BUILDING_CONTEXT,
            GenerationPhase.CONNECTING,
            -> PetSignals.TASK_QUEUED
            GenerationPhase.THINKING -> PetSignals.TASK_THINKING
            GenerationPhase.STREAMING -> {
                if (!contentGrowing && reasoningGrowing) {
                    PetSignals.TASK_THINKING
                } else {
                    PetSignals.TASK_GENERATING
                }
            }
            GenerationPhase.EXECUTING_TOOLS ->
                toolName?.takeIf { it.isNotBlank() }?.let { "${PetSignals.TASK_TOOL}.$it" } ?: PetSignals.TASK_TOOL
            GenerationPhase.WAITING_APPROVAL -> PetSignals.TASK_APPROVAL
            GenerationPhase.WAITING_INPUT -> PetSignals.TASK_ASK
            GenerationPhase.POST_PROCESSING -> PetSignals.TASK_FINISHING
            GenerationPhase.COMPLETED -> PetSignals.TASK_DONE
            GenerationPhase.FAILED, GenerationPhase.PERSISTENCE_FAILED -> PetSignals.TASK_ERROR
            GenerationPhase.CANCELLED -> PetSignals.TASK_CANCELLED
        }
    }
}

/** 空闲时间映射，见规格第 4.2 节。 */
object PetIdleSignalMapper {
    fun map(seconds: Long, idleLongSeconds: Long = 120, sleepSeconds: Long = 600): String {
        require(idleLongSeconds >= 0 && sleepSeconds >= idleLongSeconds)
        return when {
            seconds > sleepSeconds -> PetSignals.SLEEP
            seconds > idleLongSeconds -> PetSignals.IDLE_LONG
            else -> PetSignals.IDLE
        }
    }
}
