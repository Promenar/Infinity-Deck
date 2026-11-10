package com.promenar.infinity.ui.pet

import com.promenar.infinity.domain.pet.PetBehavior
import com.promenar.infinity.domain.pet.PetManifest
import com.promenar.infinity.domain.pet.PetQualifiers
import com.promenar.infinity.domain.pet.PetReaction
import com.promenar.infinity.domain.pet.PetSignalResolver
import com.promenar.infinity.domain.pet.PetSignals
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * 宠物基座的状态机与平台物理，见规格第 3.3、5、9 节与 v0.3 增补第 1 到 4 节。
 *
 * 不依赖 Android：长度单位为 dp，时间为秒，坐标原点在宠物图层左上角。
 * 每帧先 [setPlatform]、再 [step]，然后由绘制层读取 [clip]、[x]、[y] 等结果。
 * 调度优先级：交互与物理事件高于任务状态，任务状态高于空闲行为。
 */
internal class PetEngine(
    private val manifest: PetManifest,
    private val random: Random = Random.Default,
) {
    enum class Mode { NORMAL, HELD, FALLING, FLYING, FLYING_OUT, ENTERING, EXITING, GONE }

    /** 宠物所在的平台：浮岛上沿，或屏幕上部的栖息点。 */
    enum class Place { ISLAND, PERCH }

    /** 栖息点的显示阶段：没有、被提起时的提示、已落座、正在消失。 */
    enum class PerchPhase { NONE, GHOST, ACTIVE, VANISHING }

    /** 栖息点消失的方式：宠物被拖走、泡泡被戳破、云朵被吹散。 */
    enum class PerchExit { DRAG, POP, BLOWN }

    private class Timed(
        val clip: String,
        var left: Float,
        val mirrored: Boolean,
        val then: (() -> Unit)?,
        val slide: Boolean = false,
    )

    var mode = Mode.GONE
        private set

    /** 接触点的横坐标。 */
    var x = 0f
        private set

    /** 接触点高出当前平台（[groundTop]）的距离；被提起时为抓握点的高度。 */
    var y = 0f
        private set

    /** 整体缩放；被甩出屏幕时逐渐变小。 */
    var scale = 1f
        private set

    var place = Place.ISLAND
        private set

    var perchPhase = PerchPhase.NONE
        private set

    /** 栖息点落座点的横坐标与距图层顶部的距离。 */
    var perchX = 0f
        private set
    var perchY = 0f
        private set

    /** 被提起的宠物已进入可落座的范围。 */
    var perchHot = false
        private set

    /** 栖息点出现与消失的进度，0 到 1。 */
    var perchAppear = 0f
        private set
    var perchVanish = 0f
        private set
    var perchExit = PerchExit.DRAG
        private set

    /** 被用力甩出屏幕后为真，直到下一次入场或唤回。 */
    var dismissedByFling = false
        private set

    /** 为假时用力甩出只反弹，不收起。 */
    var flingEnabled = true

    /** 当前平台的顶部：在栖息点上时是栖息点（带轻微浮动），否则是浮岛上沿。 */
    val groundTop: Float
        get() = if (place == Place.PERCH) perchY + sin(clock * 1.3f) * PERCH_BOB else platformTop

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

    /** 本帧发生的事件信号，由运行时取走，用于触感、心情和状态保存。 */
    val events = ArrayDeque<String>()

    val gone: Boolean
        get() = mode == Mode.GONE

    /**
     * 角色站定且画面只剩呼吸、眨眼这类缓慢变化：没有走动、跳跃、拖动或进出场，片段淡入已完成，
     * 缩放弹簧已回稳。图层据此降低帧率。
     */
    val calm: Boolean
        get() = mode == Mode.NORMAL && platformReady && behavior?.move != true && fade >= 1f &&
            abs(pop.x - 1f) < CALM_POP_EPSILON && abs(pop.v) < CALM_POP_VELOCITY && rotation == 0f && !sliding

    private val rotationSpring = PetSpring(70f, 7f)
    private val defaultFacing = if (manifest.display.facing == "left") -1 else 1
    private var facing = defaultFacing
    private var platformLeft = 0f
    private var platformRight = 0f
    private var platformTop = 0f
    private var layerWidth = 0f
    private var layerHeight = 0f
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
    private var clock = 0f
    private var qualifiers = PetQualifiers.NONE
    private var contextDuration: String? = null
    private var contextMood: String? = null
    private var spin = 0f
    private var flyTime = 0f
    private var summonLanding = false

    /** 下落途中改用这个信号的片段（戳破、吹散、被摇出来）；为 null 时用 `touch.fall`。 */
    private var fallSignal: String? = null

    /** 撞边后短暂显示 `touch.bounce` 片段的剩余时间。 */
    private var bounceLeft = 0f
    private var pendingSummon = false
    private var pendingPerchX = 0f
    private var pendingPerchY = 0f
    private var pendingPerch = false
    private var tilt = 0f
    private var stage = false
    private var sliding = false
    private var wobbleLeft = 0f
    private val taps = ArrayDeque<Float>()

    /** 任务状态或空闲信号变化；相同信号重复传入没有效果。 */
    fun setTaskSignal(signal: String) {
        if (signal == taskSignal) return
        // 连续执行不同工具且表现相同时，不重播进入反应。
        val toolToTool = taskSignal.startsWith(PetSignals.TASK_TOOL) && signal.startsWith(PetSignals.TASK_TOOL)
        taskSignal = signal
        val same = toolToTool && PetSignalResolver.resolve(manifest, signal, qualifiers)?.reaction === reaction
        applyReaction(entering = !same)
    }

    /** 时长与心情限定（v0.3 第 1 节）；变化时只换表现，不重播进入反应。 */
    fun setContext(duration: String?, mood: String?) {
        if (duration == contextDuration && mood == contextMood) return
        contextDuration = duration
        contextMood = mood
        refreshQualifiers()
    }

    /**
     * 会话还没有任何消息（空会话页舞台，v0.3 第 2 节）。平台仍是浮岛上沿，可以走动；
     * 映射优先使用 `@stage` 限定，进入和离开时发出 `place.stage.enter`、`place.stage.leave`。
     */
    fun setStage(on: Boolean) {
        if (on == stage) return
        stage = on
        refreshQualifiers()
        val signal = if (on) PetSignals.PLACE_STAGE_ENTER else PetSignals.PLACE_STAGE_LEAVE
        events.addLast(signal)
        if (mode == Mode.NORMAL && place == Place.ISLAND && timed == null) playEvent(signal, 1500)
    }

    /** 手机左右倾斜的程度，-1 到 1；正值向右滑。 */
    fun setTilt(value: Float) {
        tilt = petClamp(value, -1f, 1f)
    }

    private fun refreshQualifiers() {
        val next = PetQualifiers(
            place = if (place == Place.PERCH) PLACE_PERCH else if (stage) PLACE_STAGE else null,
            duration = contextDuration,
            mood = contextMood,
        )
        if (next == qualifiers) return
        qualifiers = next
        applyReaction(entering = false)
    }

    private fun applyReaction(entering: Boolean) {
        val next = PetSignalResolver.resolve(manifest, taskSignal, qualifiers)?.reaction
        if (!entering && next === reaction) return
        reaction = next
        behavior = null
        sometimesLeft = FloatArray(next?.sometimes?.size ?: 0) { next?.sometimes?.get(it)?.everyS?.toFloat() ?: 0f }
        if (!entering) return
        chatterLeft = 10f + random.nextFloat() * 10f
        if (next == null) return
        val enter = next.enter
        if (mode == Mode.NORMAL && enter != null && enter in manifest.clips) {
            timed = Timed(enter, (next.enterMs ?: DEFAULT_ENTER_MS) / 1000f, mirrored = false, then = null)
        }
        if (next.say) sayRequests.addLast(taskSignal)
    }

    /**
     * 更新图层尺寸与浮岛平台的位置；浮岛边缘移动碰到角色时推着走。
     * [height] 为 0 时不提供栖息点。
     */
    fun setPlatform(left: Float, right: Float, top: Float, width: Float, dt: Float, height: Float = 0f) {
        layerWidth = width
        layerHeight = height
        platformTop = top
        if (!platformReady) {
            platformLeft = left
            platformRight = right
            x = left + (right - left) * 0.68f
            settledWidth = right - left
            previousWidth = settledWidth
            platformReady = true
            if (mode == Mode.ENTERING) {
                if (pendingSummon) beginSummon() else beginEnter()
            }
            return
        }
        if (mode == Mode.NORMAL && place == Place.ISLAND) {
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
                if (mode == Mode.NORMAL && place == Place.ISLAND && timed == null) {
                    playEvent(if (width2 > settledWidth) PetSignals.ISLAND_WIDER else PetSignals.ISLAND_NARROWER, 1600)
                }
                settledWidth = width2
            }
        }
        if (mode == Mode.NORMAL && place == Place.ISLAND) x = petClamp(x, left + EDGE_MARGIN, right - EDGE_MARGIN)
    }

    fun step(dt: Float) {
        if (mode == Mode.GONE || !platformReady) return
        clock += dt
        pop.step(1f, dt)
        stepPerch(dt)
        fade = min(1f, fade + dt / FADE_SECONDS)
        if (fade >= 1f) previousClip = null
        clipTime += dt
        when (mode) {
            Mode.HELD -> {
                velocityX *= 0.02f.pow(dt)
                rotation = rotationSpring.step(-petClamp(velocityX * 0.0025f, -0.7f, 0.7f), dt)
            }
            Mode.FALLING -> stepFalling(dt)
            Mode.FLYING -> stepFlying(dt)
            Mode.FLYING_OUT -> stepFlyingOut(dt)
            Mode.ENTERING -> stepEntering(dt)
            Mode.EXITING -> stepExiting(dt)
            Mode.NORMAL -> stepNormal(dt)
            Mode.GONE -> Unit
        }
        updateClip()
    }

    /**
     * 点按。[zone] 为点中的身体分区（`head`、`belly`、`tail`），包没有声明分区时为 null。
     * 3 秒内点按 5 次触发 `touch.annoyed`。
     */
    fun tap(zone: String? = null) {
        if (mode != Mode.NORMAL) return
        while (taps.isNotEmpty() && clock - taps.first() > ANNOYED_WINDOW) taps.removeFirst()
        taps.addLast(clock)
        val signal = when {
            taps.size >= ANNOYED_TAPS -> {
                taps.clear()
                PetSignals.TOUCH_ANNOYED
            }
            zone == "head" -> PetSignals.TOUCH_POKE_HEAD
            zone == "belly" -> PetSignals.TOUCH_POKE_BELLY
            zone == "tail" -> PetSignals.TOUCH_POKE_TAIL
            else -> PetSignals.TOUCH_TAP
        }
        pop.x = 0.92f
        events.addLast(signal)
        playEvent(signal, 1600)
    }

    /** 抚摸：在头部来回滑动。 */
    fun stroke() {
        if (mode != Mode.NORMAL) return
        events.addLast(PetSignals.TOUCH_PET)
        playEvent(PetSignals.TOUCH_PET, 1600)
    }

    /** 摇晃手机：晕一下。 */
    fun shake() {
        if (mode != Mode.NORMAL) return
        wobbleLeft = DIZZY_SECONDS
        pop.x = 0.86f
        events.addLast(PetSignals.SHAKE_DIZZY)
        playEvent(PetSignals.SHAKE_DIZZY, 1600)
    }

    /** 键盘弹出或收起。 */
    fun keyboard(up: Boolean) {
        if (mode != Mode.NORMAL || place != Place.ISLAND) return
        val signal = if (up) PetSignals.KEYBOARD_UP else PetSignals.KEYBOARD_DOWN
        pop.x = 0.8f
        pop.v = 0f
        events.addLast(signal)
        playEvent(signal, 1300)
    }

    /** 泡泡被戳破：宠物落回浮岛。 */
    fun popPerch(): Boolean = leavePerch(PerchExit.POP, PetSignals.PLACE_PERCH_POP)

    /** 云朵被吹散：宠物落回浮岛。 */
    fun blowPerch(): Boolean = leavePerch(PerchExit.BLOWN, PetSignals.PLACE_PERCH_BLOWN)

    /** 下一次入场时直接出现在栖息点上；[xDp]、[yDp] 为落座点在图层内的位置。 */
    fun restorePerch(xDp: Float, yDp: Float) {
        pendingPerch = true
        pendingPerchX = xDp
        pendingPerchY = yDp
    }

    /** 提起；[px]、[py] 为指针在图层内的位置。进出场期间不能提起。 */
    fun grab(px: Float, py: Float, time: Float): Boolean {
        if (mode != Mode.NORMAL && mode != Mode.FALLING && mode != Mode.FLYING) return false
        if (place == Place.PERCH) {
            // 原来的栖息点留作提示，松手在可落座范围内时落回原处附近。
            place = Place.ISLAND
            perchPhase = PerchPhase.GHOST
            perchHot = true
            events.addLast(PetSignals.PLACE_PERCH_LEAVE)
            refreshQualifiers()
        } else if (layerHeight > 0f) {
            perchPhase = PerchPhase.GHOST
            perchAppear = 0f
            perchVanish = 0f
            perchHot = false
            perchX = px
            perchY = clampPerchY(GHOST_REST_TOP)
        }
        mode = Mode.HELD
        timed = null
        behavior = null
        wobbleLeft = 0f
        sliding = false
        summonLanding = false
        fallSignal = null
        bounceLeft = 0f
        velocityX = 0f
        velocityY = 0f
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
        val previousY = y
        moveHeld(px, py)
        val elapsed = max(0.008f, time - lastDragTime)
        velocityX += ((x - previousX) / elapsed - velocityX) * 0.5f
        velocityY += ((y - previousY) / elapsed - velocityY) * 0.5f
        lastDragTime = time
        if (perchPhase == PerchPhase.GHOST) {
            val feet = platformTop - (y - heldDrop())
            perchHot = feet < layerHeight * PERCH_ZONE
            perchX = x
            perchY = if (perchHot) clampPerchY(feet) else perchY + (clampPerchY(GHOST_REST_TOP) - perchY) * 0.2f
        }
    }

    /**
     * 松手。按松手速度分三档（v0.3 第 3.1 节）：慢的落回平台或落到栖息点，快的飞出去撞边反弹，
     * 更快的飞出屏幕被收起。[time] 距最后一次拖动超过 90 毫秒时视为静止松手。
     */
    fun release(time: Float = lastDragTime) {
        if (mode != Mode.HELD) return
        if (time - lastDragTime > STALE_DRAG) {
            velocityX = 0f
            velocityY = 0f
        }
        val speed = hypot(velocityX, velocityY)
        y = max(0f, y - heldDrop())
        flyTime = 0f
        when {
            speed >= FLING_OUT_SPEED && flingEnabled -> {
                val boost = max(1f, FLING_OUT_MIN / speed)
                velocityX *= boost
                velocityY *= boost
                spin = FLING_OUT_SPIN * if (velocityX < 0f) -1f else 1f
                dropGhost()
                mode = Mode.FLYING_OUT
                events.addLast(PetSignals.ROLE_DISMISS)
                if (manifest.map[PetSignals.ROLE_DISMISS]?.say == true) sayRequests.addLast(PetSignals.ROLE_DISMISS)
            }
            speed >= THROW_SPEED -> {
                velocityX = petClamp(velocityX, -MAX_THROW_X, MAX_THROW_X)
                velocityY = petClamp(velocityY, -MAX_THROW_Y, MAX_THROW_Y)
                spin = petClamp(velocityX * 0.004f, -9f, 9f)
                dropGhost()
                mode = Mode.FLYING
                events.addLast(PetSignals.TOUCH_THROW)
                if (manifest.map[PetSignals.TOUCH_THROW]?.say == true) sayRequests.addLast(PetSignals.TOUCH_THROW)
            }
            perchPhase == PerchPhase.GHOST && perchHot -> landOnPerch()
            else -> {
                velocityX = petClamp(velocityX, -260f, 260f) * 0.35f
                velocityY = 0f
                dropGhost()
                mode = Mode.FALLING
            }
        }
    }

    /** 被收起后用力摇晃手机唤回：从图层上方翻滚着落到浮岛。 */
    fun summon() {
        if (mode != Mode.GONE && mode != Mode.ENTERING) return
        if (!platformReady) {
            pendingSummon = true
            mode = Mode.ENTERING
            alpha = 0f
            sequenceClips = emptyList()
            return
        }
        beginSummon()
    }

    private fun beginSummon() {
        pendingSummon = false
        pendingPerch = false
        dismissedByFling = false
        place = Place.ISLAND
        perchPhase = PerchPhase.NONE
        scale = 1f
        alpha = 1f
        clipAtPlatform = false
        timed = null
        behavior = null
        sequenceClips = emptyList()
        val low = platformLeft + PUSH_MARGIN
        val high = max(low, platformRight - PUSH_MARGIN)
        x = low + random.nextFloat() * (high - low)
        y = platformTop + SUMMON_HEIGHT
        velocityX = 0f
        velocityY = -SUMMON_SPEED
        rotation = SUMMON_ROTATION
        summonLanding = true
        fallSignal = PetSignals.TOUCH_THROW
        mode = Mode.FALLING
        refreshQualifiers()
    }

    private fun landOnPerch() {
        place = Place.PERCH
        perchPhase = PerchPhase.ACTIVE
        perchVanish = 0f
        x = perchX
        y = 0f
        velocityX = 0f
        velocityY = 0f
        rotation = 0f
        pop.x = 0.85f
        pop.v = 0f
        timed = null
        behavior = null
        mode = Mode.NORMAL
        events.addLast(PetSignals.PLACE_PERCH_ENTER)
        refreshQualifiers()
        playEvent(PetSignals.PLACE_PERCH_ENTER, 1200)
    }

    private fun leavePerch(exit: PerchExit, signal: String): Boolean {
        if (place != Place.PERCH || mode != Mode.NORMAL) return false
        y = max(0f, platformTop - perchY)
        place = Place.ISLAND
        perchPhase = PerchPhase.VANISHING
        perchVanish = 0f
        perchExit = exit
        timed = null
        behavior = null
        velocityX = 0f
        velocityY = 0f
        fallSignal = signal
        mode = Mode.FALLING
        events.addLast(signal)
        events.addLast(PetSignals.PLACE_PERCH_LEAVE)
        if (PetSignalResolver.resolveEvent(manifest, signal, qualifiers)?.reaction?.say == true) sayRequests.addLast(signal)
        refreshQualifiers()
        return true
    }

    private fun dropGhost() {
        if (perchPhase != PerchPhase.GHOST) return
        perchPhase = PerchPhase.VANISHING
        perchVanish = 0f
        perchExit = PerchExit.DRAG
    }

    private fun stepPerch(dt: Float) {
        when (perchPhase) {
            PerchPhase.GHOST, PerchPhase.ACTIVE -> perchAppear = min(1f, perchAppear + dt * 4f)
            PerchPhase.VANISHING -> {
                perchVanish += dt * if (perchExit == PerchExit.POP) 5f else 1.6f
                if (perchVanish >= 1f) perchPhase = PerchPhase.NONE
            }
            PerchPhase.NONE -> Unit
        }
    }

    private fun clampPerchY(value: Float): Float =
        petClamp(value, PERCH_MIN_TOP, max(PERCH_MIN_TOP, layerHeight * PERCH_MAX_ZONE))

    /** 入场：包声明了 `role.enter` 的序列时按序列，否则淡入。 */
    fun enter() {
        pendingSummon = false
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
        // 在栖息点上时原地淡出，不播放钻进浮岛的序列。
        val steps = if (place == Place.PERCH) {
            emptyList()
        } else {
            manifest.map[PetSignals.ROLE_EXIT]?.sequence.orEmpty().filter { it.clip in manifest.clips }
        }
        if (place != Place.PERCH && perchPhase != PerchPhase.NONE) dropGhost()
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
        dismissedByFling = false
        scale = 1f
        val onPerch = pendingPerch && layerHeight > 0f
        pendingPerch = false
        val steps = if (onPerch) {
            emptyList()
        } else {
            manifest.map[PetSignals.ROLE_ENTER]?.sequence.orEmpty().filter { it.clip in manifest.clips }
        }
        timed = null
        behavior = null
        rotation = 0f
        if (onPerch) {
            place = Place.PERCH
            perchPhase = PerchPhase.ACTIVE
            perchAppear = 0f
            perchVanish = 0f
            perchX = petClamp(pendingPerchX, 24f, max(24f, layerWidth - 24f))
            perchY = clampPerchY(pendingPerchY)
            x = perchX
        } else {
            place = Place.ISLAND
            perchPhase = PerchPhase.NONE
            x = petClamp(x, platformLeft + PUSH_MARGIN, platformRight - PUSH_MARGIN)
        }
        refreshQualifiers()
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
        // 入场时已经在空会话里：入场完成后补播进入舞台的反应。
        if (stage && place == Place.ISLAND) playEvent(PetSignals.PLACE_STAGE_ENTER, 1500)
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
        scale = 1f
        rotation = 0f
        place = Place.ISLAND
        perchPhase = PerchPhase.NONE
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
        land(hard = velocityY < -HARD_LANDING_SPEED)
    }

    /** 飞行：受重力，撞图层左右边缘和顶部反弹，下落到低处时被引回浮岛范围。 */
    private fun stepFlying(dt: Float) {
        velocityY -= GRAVITY * dt
        y += velocityY * dt
        x += velocityX * dt
        rotation += spin * dt
        bounceLeft = max(0f, bounceLeft - dt)
        val right = max(FLY_MARGIN, layerWidth - FLY_MARGIN)
        if (x < FLY_MARGIN) {
            x = FLY_MARGIN
            velocityX = abs(velocityX) * RESTITUTION
            bounce()
        } else if (x > right) {
            x = right
            velocityX = -abs(velocityX) * RESTITUTION
            bounce()
        }
        val ceiling = max(0f, platformTop - FLY_CEILING)
        if (y > ceiling) {
            y = ceiling
            velocityY = -abs(velocityY) * RESTITUTION
            bounce()
        }
        if (y < FLY_STEER_HEIGHT && velocityY < 0f) {
            val inside = petClamp(x, platformLeft + PUSH_MARGIN, platformRight - PUSH_MARGIN)
            x += (inside - x) * min(1f, dt * 6f)
        }
        if (y <= 0f && velocityY < 0f) land(hard = true)
    }

    private fun bounce() {
        pop.x = 0.8f
        pop.v = 0f
        bounceLeft = BOUNCE_CLIP_SECONDS
        events.addLast(PetSignals.TOUCH_BOUNCE)
    }

    /** 被甩出屏幕：不受重力，越飞越小，飞出图层或超时后消失。 */
    private fun stepFlyingOut(dt: Float) {
        x += velocityX * dt
        y += velocityY * dt
        rotation += spin * dt
        scale = max(0.06f, scale - dt * 1.15f)
        flyTime += dt
        val outside = x < -FLY_OUT_MARGIN || x > layerWidth + FLY_OUT_MARGIN || y > platformTop + FLY_OUT_MARGIN ||
            y < -FLY_OUT_MARGIN * 4f
        if (flyTime < FLING_OUT_SECONDS && !outside) return
        finishExit()
        dismissedByFling = true
    }

    private fun land(hard: Boolean) {
        y = 0f
        velocityY = 0f
        velocityX = 0f
        rotation = 0f
        fallSignal = null
        bounceLeft = 0f
        pop.x = if (hard) 0.7f else 0.88f
        pop.v = 0f
        mode = Mode.NORMAL
        val signal = if (hard) PetSignals.TOUCH_LAND_HARD else PetSignals.TOUCH_LAND_SOFT
        if (summonLanding) {
            // 被摇回来的落地不算被摔，只发唤回事件。
            summonLanding = false
            events.addLast(PetSignals.ROLE_SUMMON)
            if (!playEvent(PetSignals.ROLE_SUMMON, 2000)) {
                playEvent(PetSignals.FALL_OVER, 1100) { playEvent(PetSignals.GET_UP, 1600) }
            }
            return
        }
        events.addLast(signal)
        playEvent(signal, if (hard) 1500L else 400L)
    }

    private fun stepNormal(dt: Float) {
        if (wobbleLeft > 0f) {
            wobbleLeft -= dt
            rotation = if (wobbleLeft > 0f) sin(clock * 22f) * 0.14f * min(1f, wobbleLeft) else 0f
        }
        if (stepSlide(dt)) return
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

    /** 手机倾斜时沿浮岛打滑；返回真表示本帧在打滑，其它行为暂停。 */
    private fun stepSlide(dt: Float): Boolean {
        val running = timed
        val low = platformLeft + PUSH_MARGIN
        val high = max(low, platformRight - PUSH_MARGIN)
        // 已经滑到边缘时不再算打滑，空闲行为照常继续。
        val blocked = if (tilt > 0f) x >= high - 0.01f else x <= low + 0.01f
        val active = place == Place.ISLAND && !reducedMotion && abs(tilt) > TILT_THRESHOLD && !blocked &&
            (running == null || running.slide)
        if (!active) {
            if (running?.slide == true) timed = null
            sliding = false
            return false
        }
        val right = tilt > 0f
        val signal = if (right) PetSignals.TILT_SLIDE_RIGHT else PetSignals.TILT_SLIDE_LEFT
        val opposite = if (right) PetSignals.TILT_SLIDE_LEFT else PetSignals.TILT_SLIDE_RIGHT
        val own = manifest.map[signal]?.clip?.takeIf { it in manifest.clips }
        val other = manifest.map[opposite]?.clip?.takeIf { it in manifest.clips }
        if (!sliding) {
            sliding = true
            behavior = null
            events.addLast(signal)
            if ((manifest.map[signal] ?: manifest.map[opposite])?.say == true) sayRequests.addLast(signal)
        }
        val slideClip = own ?: other
        timed = slideClip?.let { Timed(it, 0.2f, mirrored = own == null, then = null, slide = true) }
        x = petClamp(x + tilt * SLIDE_SPEED * dt, low, high)
        return true
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
        val still = reducedMotion || place == Place.PERCH
        val candidates = behaviors.filter { it.clip in manifest.clips && it.weight > 0.0 && !(still && it.move) }
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
        val event = PetSignalResolver.resolveEvent(manifest, signal, qualifiers)?.reaction
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
            Mode.FALLING -> fallSignal?.let { manifest.map[it]?.clip?.takeIf { name -> name in manifest.clips } }
                ?: manifest.map[PetSignals.TOUCH_FALL]?.clip
            Mode.FLYING -> (if (bounceLeft > 0f) ownClip(PetSignals.TOUCH_BOUNCE) else null)
                ?: flightClip(PetSignals.TOUCH_THROW)
            Mode.FLYING_OUT -> ownClip(PetSignals.ROLE_DISMISS) ?: flightClip(PetSignals.TOUCH_THROW)
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

    /** 信号自己声明的片段；没有声明或片段不存在时为 null。 */
    private fun ownClip(signal: String): String? = manifest.map[signal]?.clip?.takeIf { it in manifest.clips }

    /** 飞行中的片段：事件自己的片段，其次下落片段。 */
    private fun flightClip(signal: String): String? =
        manifest.map[signal]?.clip?.takeIf { it in manifest.clips } ?: manifest.map[PetSignals.TOUCH_FALL]?.clip

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
        const val CALM_POP_EPSILON = 0.002f
        const val CALM_POP_VELOCITY = 0.02f

        const val PLACE_PERCH = "perch"
        const val PLACE_STAGE = "stage"

        // 抛掷（v0.3 第 3.1 节），速度单位 dp 每秒，数值待真机调校。
        const val THROW_SPEED = 900f
        const val FLING_OUT_SPEED = 2600f
        const val FLING_OUT_MIN = 1500f
        const val FLING_OUT_SPIN = 14f
        const val FLING_OUT_SECONDS = 0.8f
        const val FLY_OUT_MARGIN = 30f
        const val MAX_THROW_X = 2200f
        const val MAX_THROW_Y = 1800f
        const val STALE_DRAG = 0.09f
        const val RESTITUTION = 0.62f
        const val FLY_MARGIN = 24f
        const val FLY_CEILING = 64f
        const val FLY_STEER_HEIGHT = 170f
        const val BOUNCE_CLIP_SECONDS = 0.22f

        // 栖息点（v0.3 第 2 节）。
        const val PERCH_ZONE = 0.42f
        const val PERCH_MAX_ZONE = 0.36f
        const val PERCH_MIN_TOP = 72f
        const val GHOST_REST_TOP = 110f
        const val PERCH_BOB = 3f

        // 唤回、摇晃、倾斜、连戳。
        const val SUMMON_HEIGHT = 40f
        const val SUMMON_SPEED = 120f
        const val SUMMON_ROTATION = 2.6f
        const val DIZZY_SECONDS = 1.6f
        const val TILT_THRESHOLD = 0.3f
        const val SLIDE_SPEED = 150f
        const val ANNOYED_TAPS = 5
        const val ANNOYED_WINDOW = 3f
    }
}
