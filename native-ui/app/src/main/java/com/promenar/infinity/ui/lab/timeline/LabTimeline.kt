package com.promenar.infinity.ui.lab.timeline

import java.util.regex.Pattern
import kotlin.math.ceil
import kotlin.math.floor

/** 变换必须返回新状态，不能修改传入状态或捕获外部可变数据。 */
sealed interface LabKeyframe<S> {
    val atMs: Long
    val endMs: Long
    fun apply(state: S, timeMs: Long): S
}

class LabInstantKeyframe<S>(
    override val atMs: Long,
    private val transform: (S) -> S,
) : LabKeyframe<S> {
    init { require(atMs >= 0) { "关键帧时刻不能为负数" } }
    override val endMs: Long get() = atMs
    override fun apply(state: S, timeMs: Long): S = transform(state)
}

class LabStreamKeyframe<S>(
    override val atMs: Long,
    val charsPerSecond: Float,
    val text: String,
    private val transform: (S, String) -> S,
) : LabKeyframe<S> {
    private val characterEnds: List<Int>
    override val endMs: Long

    init {
        require(atMs >= 0) { "关键帧时刻不能为负数" }
        require(charsPerSecond.isFinite() && charsPerSecond > 0) { "流式速度必须是有限正数" }
        // 标准库的扩展字素簇覆盖组合字符、肤色、旗帜与连接符 emoji；不依赖 Android。
        val matcher = GRAPHEME.matcher(text)
        characterEnds = buildList { while (matcher.find()) add(matcher.end()) }
        val duration = ceil(characterEnds.size / charsPerSecond.toDouble() * 1000)
        require(duration <= Long.MAX_VALUE.toDouble()) { "流式时长超出范围" }
        endMs = Math.addExact(atMs, duration.toLong())
    }

    override fun apply(state: S, timeMs: Long): S {
        val count = if (timeMs >= endMs) characterEnds.size else {
            floor((timeMs - atMs).coerceAtLeast(0) * charsPerSecond.toDouble() / 1000)
                .toInt().coerceIn(0, characterEnds.size)
        }
        val partial = if (count == 0) "" else text.substring(0, characterEnds[count - 1])
        return transform(state, partial)
    }

    private companion object {
        val GRAPHEME: Pattern = Pattern.compile("\\X")
    }
}

/** 每次求值都从初态折叠；初态和变换的不可变性由脚本调用方保证。 */
class LabTimeline<S>(
    val initial: S,
    keyframes: List<LabKeyframe<S>>,
    val durationMs: Long = keyframes.maxOfOrNull { it.endMs } ?: 0,
) {
    // sortedBy 是稳定排序：同一时刻保留加入顺序。
    val keyframes: List<LabKeyframe<S>> = keyframes.sortedBy { it.atMs }

    init {
        require(durationMs >= 0 && this.keyframes.all { it.endMs <= durationMs }) { "总时长不能早于关键帧结束时刻" }
    }

    fun stateAt(tMs: Long): S {
        val time = tMs.coerceIn(0, durationMs)
        var state = initial
        for (frame in keyframes) {
            if (frame.atMs > time) break
            state = frame.apply(state, time)
        }
        return state
    }
}

@DslMarker
annotation class LabTimelineDsl

@LabTimelineDsl
class LabTimelineBuilder<S> internal constructor(private val initial: S) {
    private val frames = mutableListOf<LabKeyframe<S>>()
    private var lastEndMs = 0L
    private var holdMs = 0L

    fun at(atMs: Long, transform: (S) -> S) = add(LabInstantKeyframe(atMs, transform))

    fun stream(at: Long, charsPerSecond: Float, text: String, transform: (S, String) -> S) =
        add(LabStreamKeyframe(at, charsPerSecond, text, transform))

    /** 相对最后加入帧的结束时刻，和排序后的最大结束时刻无关。 */
    fun after(deltaMs: Long, transform: (S) -> S) {
        require(deltaMs >= 0) { "相对偏移不能为负数" }
        at(Math.addExact(lastEndMs, deltaMs), transform)
    }

    /** 累计尾部停留；不改变 after 的上一关键帧基准。 */
    fun hold(ms: Long) {
        require(ms >= 0) { "停留时长不能为负数" }
        holdMs = Math.addExact(holdMs, ms)
    }

    private fun add(frame: LabKeyframe<S>) {
        frames += frame
        lastEndMs = frame.endMs
    }

    internal fun build(): LabTimeline<S> = LabTimeline(
        initial, frames, Math.addExact(frames.maxOfOrNull { it.endMs } ?: 0, holdMs),
    )
}

fun <S> labTimeline(initial: S, build: LabTimelineBuilder<S>.() -> Unit): LabTimeline<S> =
    LabTimelineBuilder(initial).apply(build).build()
