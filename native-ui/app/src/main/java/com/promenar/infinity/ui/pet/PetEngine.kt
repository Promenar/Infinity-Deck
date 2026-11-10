package com.promenar.infinity.ui.pet

import com.promenar.infinity.domain.pet.PetBehavior
import com.promenar.infinity.domain.pet.PetManifest
import com.promenar.infinity.domain.pet.PetReaction
import com.promenar.infinity.domain.pet.PetSignalResolver
import com.promenar.infinity.domain.pet.PetSignals
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.random.Random

/**
 * 宠物基座的状态机与平台物理，见规格第 3.3、5、9 节。
 *
 * 不依赖 Android：长度单位为 dp，时间为秒，坐标原点在宠物图层左上角。
 * 每帧先 [setPlatform]、再 [step]，然后由绘制层读取 [clip]、[x]、[y] 等结果。
 * 调度优先级：交互与物理事件高于任务状态，任务状态高于空闲行为。
 */
internal class PetEngine(
    private val manifest: PetManifest,
    private val random: Random = Random.Default,
) {
    enum class Mode { NORMAL, HELD, FALLING, ENTERING, EXITING, GONE }

    private class Timed(val clip: String, var left: Float, val mirrored: Boolean, val then: (() -> Unit)?)

    var mode = Mode.GONE
        private set

    /** 接触点的横坐标。 */
    var x = 0f
        private set

    /** 接触点高出平台的距离；被提起时为抓握点的高度。 */
    var y = 0f
        private set

    var rotation = 0f
        private set

    var alpha = 0f
        private set

    /** 为真时绘制层裁掉平台以下的部分（钻进、钻出浮岛）。 */
    var clipAtPlatform = false
        private set

    /** 当前片段名；包里没有可用片段时为 null。 */
    var clip: String? = null
        private set

    /** 正在淡出的上一个片段。 */
    var previousClip: String? = null
        private set

    /** 当前片段的淡入进度，0 到 1。 */
    var fade = 1f
        private set

    /** 当前片段已播放的时间。 */
    var clipTime = 0f
        private set

    /** 是否水平翻转绘制。 */
    var flip = false
        private set

    /** 当前信号要求的表情名；没有时为 null。 */
    var expression: String? = null
        private set

    /** 系统开启“移除动画”时为真：不走动、不插入偶发动作。 */
    var reducedMotion = false

    val pop = PetSpring(170f, 15f).apply { x = 1f }

    /** 待处理的台词请求（信号名），由图层取走。 */
    val sayRequests = ArrayDeque<String>()

    val gone: Boolean
        get() = mode == Mode.GONE

    private val rotationSpring = PetSpring(70f, 7f)
    private val defaultFacing = if (manifest.display.facing == "left") -1 else 1
    private var facing = defaultFacing
    private var platformLeft = 0f
    private var platformRight = 0f
    private var platformTop = 0f
    private var layerWidth = 0f
    private var platformReady = false
    private var settledWidth = 0f
    private var previousWidth = 0f
    private var settleLeft = 0f
    private var taskSignal = PetSignals.IDLE
    private var reaction: PetReaction? = PetSignalResolver.resolve(manifest, PetSignals.IDLE)?.reaction
    private var timed: Timed? = null
    private var behavior: PetBehavior? = null
    private var behaviorLeft = 0f
    private var targetX = 0f
    private var sometimesLeft = FloatArray(0)
    private var chatterLeft = 20f
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastDragTime = 0f
    private var sequenceClips: List<String> = emptyList()
    private var sequenceIntoIsland = false
    private var mirrored = false

    /** 任务状态或空闲信号变化；相同信号重复传入没有效果。 */
    fun setTaskSignal(signal: String) {
        if (signal == taskSignal) return
        taskSignal = signal
        val next = PetSignalResolver.resolve(manifest, signal)?.reaction
        reaction = next
        behavior = null
        sometimesLeft = FloatArray(next?.sometimes?.size ?: 0) { next?.sometimes?.get(it)?.everyS?.toFloat() ?: 0f }
        chatterLeft = 10f + random.nextFloat() * 10f
        if (next == null) return
        val enter = next.enter
        if (mode == Mode.NORMAL && enter != null && enter in manifest.clips) {
            timed = Timed(enter, (next.enterMs ?: DEFAULT_ENTER_MS) / 1000f, mirrored = false, then = null)
        }
        if (next.say) sayRequests.addLast(signal)
    }

    /** 更新图层宽度与浮岛平台的位置；浮岛边缘移动碰到角色时推着走。 */
    fun setPlatform(left: Float, right: Float, top: Float, width: Float, dt: Float) {
        layerWidth = width
        platformTop = top
        if (!platformReady) {
            platformLeft = left
            platformRight = right
            x = left + (right - left) * 0.68f
            settledWidth = right - left
            previousWidth = settledWidth
            platformReady = true
            if (mode == Mode.ENTERING) beginEnter()
            return
        }
        if (mode == Mode.NORMAL) {
            if (x < left + PUSH_MARGIN && left > platformLeft + 0.5f) {
                x = left + PUSH_MARGIN
                push(PetSignals.PUSH_LEFT, PetSignals.PUSH_RIGHT)
            } else if (x > right - PUSH_MARGIN && right < platformRight - 0.5f) {
                x = right - PUSH_MARGIN
                push(PetSignals.PUSH_RIGHT, PetSignals.PUSH_LEFT)
            }
        }
        platformLeft = left
        platformRight = right
        val width2 = right - left
        if (abs(width2 - previousWidth) > 0.5f) settleLeft = 0.2f
        previousWidth = width2
        if (settleLeft > 0f) {
            settleLeft -= dt
            if (settleLeft <= 0f && abs(width2 - settledWidth) > WIDTH_CHANGE) {
                if (mode == Mode.NORMAL && timed == null) {
                    playEvent(if (width2 > settledWidth) PetSignals.ISLAND_WIDER else PetSignals.ISLAND_NARROWER, 1600)
                }
                settledWidth = width2
            }
        }
        if (mode == Mode.NORMAL) x = petClamp(x, left + EDGE_MARGIN, right - EDGE_MARGIN)
    }

    fun step(dt: Float) {
        if (mode == Mode.GONE || !platformReady) return
        pop.step(1f, dt)
        fade = min(1f, fade + dt / FADE_SECONDS)
        if (fade >= 1f) previousClip = null
        clipTime += dt
        when (mode) {
            Mode.HELD -> {
                velocityX *= 0.02f.pow(dt)
                rotation = rotationSpring.step(-petClamp(velocityX * 0.0025f, -0.7f, 0.7f), dt)
            }
            Mode.FALLING -> stepFalling(dt)
            Mode.ENTERING -> stepEntering(dt)
            Mode.EXITING -> stepExiting(dt)
            Mode.NORMAL -> stepNormal(dt)
            Mode.GONE -> Unit
        }
        updateClip()
    }

    /** 点按。 */
    fun tap() {
        if (mode != Mode.NORMAL) return
        pop.x = 0.92f
        playEvent(PetSignals.TOUCH_TAP, 1600)
    }

    /** 提起；[px]、[py] 为指针在图层内的位置。进出场期间不能提起。 */
    fun grab(px: Float, py: Float, time: Float): Boolean {
        if (mode != Mode.NORMAL && mode != Mode.FALLING) return false
        mode = Mode.HELD
        timed = null
        behavior = null
        velocityX = 0f
        rotationSpring.x = 0f
        rotationSpring.v = 0f
        pop.x = 1.12f
        lastDragTime = time
        moveHeld(px, py)
        if (manifest.map[PetSignals.TOUCH_HELD]?.say == true) sayRequests.addLast(PetSignals.TOUCH_HELD)
        return true
    }

    fun dragTo(px: Float, py: Float, time: Float) {
        if (mode != Mode.HELD) return
        val previousX = x
        moveHeld(px, py)
        val elapsed = max(0.008f, time - lastDragTime)
        velocityX += ((x - previousX) / elapsed - velocityX) * 0.5f
        lastDragTime = time
    }

    /** 松手：按重力落回平台。 */
    fun release() {
        if (mode != Mode.HELD) return
        velocityX = petClamp(velocityX, -260f, 260f) * 0.35f
        velocityY = 0f
        y = max(0f, y - heldDrop())
        mode = Mode.FALLING
    }

    /** 入场：包声明了 `role.enter` 的序列时按序列，否则淡入。 */
    fun enter() {
        if (!platformReady) {
            // 平台尚未测量：先标记为入场，位置在第一次 setPlatform 时确定。
            mode = Mode.ENTERING
            alpha = 0f
            sequenceClips = emptyList()
            return
        }
        beginEnter()
    }

    /** 离场：播完后 [gone] 为真。 */
    fun exit() {
        if (mode == Mode.GONE || mode == Mode.EXITING) return
        if (!platformReady) {
            finishExit()
            return
        }
        val steps = manifest.map[PetSignals.ROLE_EXIT]?.sequence.orEmpty().filter { it.clip in manifest.clips }
        timed = null
        behavior = null
        rotation = 0f
        sequenceClips = steps.map { it.clip }
        sequenceIntoIsland = steps.any { it.intoIsland }
        if (steps.isNotEmpty()) {
            y = max(0f, y)
            velocityY = (steps.first().launch ?: DEFAULT_EXIT_LAUNCH).toFloat()
            clipAtPlatform = sequenceIntoIsland
        }
        if (manifest.map[PetSignals.ROLE_EXIT]?.say == true) sayRequests.addLast(PetSignals.ROLE_EXIT)
        mode = Mode.EXITING
    }

    private fun beginEnter() {
        val steps = manifest.map[PetSignals.ROLE_ENTER]?.sequence.orEmpty().filter { it.clip in manifest.clips }
        timed = null
        behavior = null
        rotation = 0f
        x = petClamp(x, platformLeft + PUSH_MARGIN, platformRight - PUSH_MARGIN)
        sequenceClips = steps.map { it.clip }
        sequenceIntoIsland = steps.any { it.intoIsland }
        if (steps.isEmpty()) {
            alpha = 0f
            y = 0f
        } else {
            alpha = 1f
            y = if (sequenceIntoIsland) -ENTER_DEPTH else 0f
            velocityY = (steps.first().launch ?: DEFAULT_ENTER_LAUNCH).toFloat()
            clipAtPlatform = sequenceIntoIsland
        }
        mode = Mode.ENTERING
    }

    private fun stepEntering(dt: Float) {
        if (sequenceClips.isEmpty()) {
            alpha = min(1f, alpha + dt / 0.3f)
            if (alpha >= 1f) finishEnter()
            return
        }
        velocityY -= GRAVITY * dt
        y += velocityY * dt
        if (velocityY < 0f && y <= 0f) {
            pop.x = 0.8f
            pop.v = 0f
            finishEnter()
        }
    }

    private fun finishEnter() {
        y = 0f
        velocityY = 0f
        alpha = 1f
        clipAtPlatform = false
        sequenceClips = emptyList()
        mode = Mode.NORMAL
        if (manifest.map[PetSignals.ROLE_ENTER]?.say == true) sayRequests.addLast(PetSignals.ROLE_ENTER)
    }

    private fun stepExiting(dt: Float) {
        if (sequenceClips.isEmpty()) {
            alpha = max(0f, alpha - dt / 0.3f)
            if (alpha <= 0f) finishExit()
            return
        }
        velocityY -= GRAVITY * dt
        y += velocityY * dt
        val sunk = if (sequenceIntoIsland) y < -EXIT_DEPTH else velocityY < 0f && y <= 0f
        if (sunk) finishExit()
    }

    private fun finishExit() {
        alpha = 0f
        clipAtPlatform = false
        sequenceClips = emptyList()
        clip = null
        previousClip = null
        mode = Mode.GONE
    }

    private fun stepFalling(dt: Float) {
        velocityY -= GRAVITY * dt
        y += velocityY * dt
        x += velocityX * dt
        // 落点引回平台范围。
        val inside = petClamp(x, platformLeft + PUSH_MARGIN, platformRight - PUSH_MARGIN)
        x += (inside - x) * min(1f, dt * 7f)
        rotation *= 0.01f.pow(dt)
        if (y > 0f) return
        val hard = velocityY < -HARD_LANDING_SPEED
        y = 0f
        velocityY = 0f
        velocityX = 0f
        rotation = 0f
        pop.x = if (hard) 0.7f else 0.88f
        pop.v = 0f
        mode = Mode.NORMAL
        playEvent(if (hard) PetSignals.TOUCH_LAND_HARD else PetSignals.TOUCH_LAND_SOFT, if (hard) 1500L else 400L)
    }

    private fun stepNormal(dt: Float) {
        val current = timed
        if (current != null) {
            current.left -= dt
            if (current.left <= 0f) {
                timed = null
                current.then?.invoke()
            }
            return
        }
        val active = reaction ?: return
        if (!reducedMotion) {
            for (index in active.sometimes.indices) {
                if (index >= sometimesLeft.size) break
                sometimesLeft[index] -= dt
                if (sometimesLeft[index] > 0f) continue
                val item = active.sometimes[index]
                sometimesLeft[index] = item.everyS.toFloat()
                if (item.clip in manifest.clips) {
                    timed = Timed(item.clip, item.forMs / 1000f, mirrored = false, then = null)
                    return
                }
            }
        }
        if (active.behaviors.isNotEmpty()) stepBehavior(dt, active.behaviors)
        if (active.say) {
            chatterLeft -= dt
            if (chatterLeft <= 0f) {
                chatterLeft = 25f + random.nextFloat() * 20f
                sayRequests.addLast(taskSignal)
            }
        }
    }

    private fun stepBehavior(dt: Float, behaviors: List<PetBehavior>) {
        val current = behavior
        if (current == null) {
            pickBehavior(behaviors)
            return
        }
        if (current.move) {
            val distance = targetX - x
            val stride = WALK_SPEED * dt
            if (abs(distance) <= stride) {
                x = targetX
                behavior = null
            } else {
                facing = sign(distance).toInt()
                x += facing * stride
            }
            return
        }
        behaviorLeft -= dt
        if (behaviorLeft <= 0f) behavior = null
    }

    private fun pickBehavior(behaviors: List<PetBehavior>) {
        val candidates = behaviors.filter { it.clip in manifest.clips && it.weight > 0.0 && !(reducedMotion && it.move) }
        if (candidates.isEmpty()) return
        var roll = random.nextDouble() * candidates.sumOf { it.weight }
        var chosen = candidates.last()
        for (candidate in candidates) {
            roll -= candidate.weight
            if (roll <= 0.0) {
                chosen = candidate
                break
            }
        }
        behavior = chosen
        if (chosen.move) {
            val low = platformLeft + PUSH_MARGIN
            val high = platformRight - PUSH_MARGIN
            var target = x
            repeat(8) {
                if (abs(target - x) < MIN_WALK && high > low) target = low + random.nextFloat() * (high - low)
            }
            targetX = petClamp(target, low, high)
            if (abs(targetX - x) < 1f) behavior = null
        } else {
            val shortest = (chosen.minS ?: 3.0).toFloat()
            val longest = max(shortest, (chosen.maxS ?: (shortest + 3.0)).toFloat())
            behaviorLeft = shortest + random.nextFloat() * (longest - shortest)
        }
    }

    /** 浮岛边缘推着走；边缘停下后摔倒，再起身。 */
    private fun push(signal: String, opposite: String) {
        val own = manifest.map[signal]?.clip?.takeIf { it in manifest.clips }
        val other = manifest.map[opposite]?.clip?.takeIf { it in manifest.clips }
        val pushClip = own ?: other
        val after = {
            playEvent(PetSignals.FALL_OVER, 1100) { playEvent(PetSignals.GET_UP, 1600) }
            Unit
        }
        behavior = null
        if (pushClip == null) {
            if (timed == null) after()
            return
        }
        // 边缘还在移动时每帧重新计时，停下 0.25 秒后才摔倒。
        timed = Timed(pushClip, 0.25f, mirrored = own == null, then = after)
    }

    /**
     * 播放事件信号对应的片段，时长取映射的 `enter_ms`（缺省 [defaultMs]）。
     * 包没有声明该事件时直接执行 [then] 并返回 false。
     */
    private fun playEvent(signal: String, defaultMs: Long, then: (() -> Unit)? = null): Boolean {
        val event = manifest.map[signal]
        val eventClip = event?.clip?.takeIf { it in manifest.clips }
        if (event == null || eventClip == null) {
            then?.invoke()
            return false
        }
        timed = Timed(eventClip, (event.enterMs ?: defaultMs) / 1000f, mirrored = false, then = then)
        if (event.say) sayRequests.addLast(signal)
        return true
    }

    private fun moveHeld(px: Float, py: Float) {
        x = petClamp(px, 24f, max(24f, layerWidth - 24f))
        y = max(0f, platformTop - py)
    }

    /** 被提起时抓握点到脚底的距离：松手时从这个高度落下。 */
    private fun heldDrop(): Float {
        val held = manifest.map[PetSignals.TOUCH_HELD]?.clip?.let { manifest.clips[it] } ?: return 0f
        val anchorV = held.anchor.getOrNull(1)?.toFloat() ?: return 0f
        return max(0f, 0.95f - anchorV) * manifest.display.heightDp * held.scale.toFloat()
    }

    private fun updateClip() {
        if (mode == Mode.GONE) return
        val current = timed
        val active = reaction
        val moving = behavior?.move == true
        val wanted: String? = when (mode) {
            Mode.HELD -> manifest.map[PetSignals.TOUCH_HELD]?.clip
            Mode.FALLING -> manifest.map[PetSignals.TOUCH_FALL]?.clip
            Mode.ENTERING -> sequenceClips.firstOrNull()
            Mode.EXITING -> if (velocityY > 0f) sequenceClips.firstOrNull() else sequenceClips.lastOrNull()
            Mode.NORMAL -> current?.clip ?: behavior?.clip ?: active?.clip
            Mode.GONE -> null
        }
        val desired = wanted?.takeIf { it in manifest.clips } ?: restingClip()
        mirrored = mode == Mode.NORMAL && current?.mirrored == true
        flip = mirrored || (mode == Mode.NORMAL && current == null && moving && facing != defaultFacing)
        expression = if (mode == Mode.NORMAL && current == null && behavior == null) active?.expression else null
        if (desired == clip) return
        previousClip = clip
        clip = desired
        fade = if (previousClip == null) 1f else 0f
        clipTime = 0f
        if (!moving && mode == Mode.NORMAL) {
            pop.x = min(pop.x, 0.92f)
        }
    }

    /** 没有指定片段时显示的片段：当前信号的片段，其次空闲片段，最后任取一个。 */
    private fun restingClip(): String? {
        val idle = PetSignalResolver.resolve(manifest, PetSignals.IDLE)?.reaction
        return listOfNotNull(
            reaction?.clip,
            idle?.clip,
            idle?.behaviors?.firstOrNull { !it.move }?.clip,
            manifest.expressions.baseClip,
        ).firstOrNull { it in manifest.clips } ?: manifest.clips.keys.firstOrNull()
    }

    private companion object {
        const val GRAVITY = 900f
        const val WALK_SPEED = 34f
        const val MIN_WALK = 60f
        const val PUSH_MARGIN = 44f
        const val EDGE_MARGIN = 18f
        const val WIDTH_CHANGE = 40f
        const val FADE_SECONDS = 0.18f
        const val HARD_LANDING_SPEED = 300f
        const val ENTER_DEPTH = 70f
        const val EXIT_DEPTH = 90f
        const val DEFAULT_ENTER_MS = 700L
        const val DEFAULT_ENTER_LAUNCH = 560L
        const val DEFAULT_EXIT_LAUNCH = 430L
    }
}
