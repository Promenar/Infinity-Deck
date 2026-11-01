package com.promenar.infinity.ui.lab.timeline

/** 位置对外以毫秒表示，内部保留不足一毫秒的四分之一纳秒，避免逐帧舍入漂移。 */
data class LabPlaybackState(
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val speed: Float = 1f,
    internal val remainderQuarterNanos: Long = 0,
) {
    init {
        require(positionMs >= 0) { "播放位置不能为负数" }
        require(speed in SUPPORTED_LAB_SPEEDS) { "不支持的播放速度" }
        require(remainderQuarterNanos in 0 until QUARTER_NANOS_PER_MILLI) { "播放零头超出范围" }
    }
}

val SUPPORTED_LAB_SPEEDS: List<Float> = listOf(0.25f, 0.5f, 1f, 1.5f, 2f)
private const val QUARTER_NANOS_PER_MILLI = 4_000_000L

fun advance(state: LabPlaybackState, deltaNanos: Long, durationMs: Long): LabPlaybackState {
    require(deltaNanos >= 0) { "推进时间不能为负数" }
    require(durationMs >= 0) { "总时长不能为负数" }
    if (state.positionMs >= durationMs) return state.copy(positionMs = durationMs, playing = false, remainderQuarterNanos = 0)
    if (!state.playing) return state
    val quarters = (state.speed * 4).toLong()
    // 先除后乘，Long.MAX_VALUE 纳秒也不会溢出。
    val remainder = deltaNanos % QUARTER_NANOS_PER_MILLI * quarters + state.remainderQuarterNanos
    val elapsedMs = deltaNanos / QUARTER_NANOS_PER_MILLI * quarters + remainder / QUARTER_NANOS_PER_MILLI
    return if (elapsedMs >= durationMs - state.positionMs) {
        state.copy(positionMs = durationMs, playing = false, remainderQuarterNanos = 0)
    } else {
        state.copy(positionMs = state.positionMs + elapsedMs, remainderQuarterNanos = remainder % QUARTER_NANOS_PER_MILLI)
    }
}

fun seek(state: LabPlaybackState, positionMs: Long, durationMs: Long): LabPlaybackState {
    require(durationMs >= 0) { "总时长不能为负数" }
    val position = positionMs.coerceIn(0, durationMs)
    return state.copy(positionMs = position, playing = state.playing && position < durationMs, remainderQuarterNanos = 0)
}

fun restart(state: LabPlaybackState, durationMs: Long): LabPlaybackState {
    require(durationMs >= 0) { "总时长不能为负数" }
    return state.copy(positionMs = 0, playing = durationMs > 0, remainderQuarterNanos = 0)
}

fun togglePlay(state: LabPlaybackState, durationMs: Long): LabPlaybackState {
    val bounded = seek(state, state.positionMs, durationMs)
    return when {
        durationMs == 0L -> bounded
        bounded.positionMs == durationMs -> restart(bounded, durationMs)
        else -> bounded.copy(playing = !bounded.playing)
    }
}
