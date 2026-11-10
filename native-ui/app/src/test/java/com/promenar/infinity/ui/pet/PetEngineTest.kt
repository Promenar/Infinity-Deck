package com.promenar.infinity.ui.pet

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.pet.PetBehavior
import com.promenar.infinity.domain.pet.PetClip
import com.promenar.infinity.domain.pet.PetDisplay
import com.promenar.infinity.domain.pet.PetLicense
import com.promenar.infinity.domain.pet.PetManifest
import com.promenar.infinity.domain.pet.PetReaction
import com.promenar.infinity.domain.pet.PetSequenceStep
import com.promenar.infinity.domain.pet.PetSignals
import kotlin.random.Random
import org.junit.Test

class PetEngineTest {
    private fun pose(name: String, anchorV: Double = 0.95) = name to PetClip(
        type = "pose",
        file = "clips/$name.webp",
        anchor = listOf(0.5, anchorV),
    )

    private fun manifest(map: Map<String, PetReaction> = emptyMap()) = PetManifest(
        contract = "0.2",
        id = "test.pet",
        name = "测试",
        version = "1.0.0",
        license = PetLicense("CC0", redistribute = true, commercial = true),
        display = PetDisplay(heightDp = 130, facing = "right"),
        clips = listOf(
            pose("stand"), pose("walk"), pose("eat"), pose("startle"), pose("hand"), pose("held", anchorV = 0.4),
            pose("fall"), pose("land"), pose("push"), pose("over"), pose("up"), pose("look"), pose("dive"),
        ).toMap(),
        map = mapOf(
            PetSignals.IDLE to PetReaction(behaviors = listOf(PetBehavior(clip = "stand", minS = 3.0, maxS = 6.0))),
            PetSignals.TASK_GENERATING to PetReaction(clip = "eat", say = true),
            PetSignals.TASK_APPROVAL to PetReaction(clip = "hand", enter = "startle", enterMs = 700),
            PetSignals.TOUCH_HELD to PetReaction(clip = "held"),
            PetSignals.TOUCH_FALL to PetReaction(clip = "fall"),
            PetSignals.TOUCH_LAND_HARD to PetReaction(clip = "land", enterMs = 1500),
            PetSignals.PUSH_LEFT to PetReaction(clip = "push"),
            PetSignals.FALL_OVER to PetReaction(clip = "over", enterMs = 1100),
            PetSignals.GET_UP to PetReaction(clip = "up", enterMs = 1600),
            PetSignals.ISLAND_WIDER to PetReaction(clip = "look", enterMs = 1600),
        ) + map,
    )

    /** 以 60 帧每秒推进 [seconds] 秒；平台左右边缘与顶部可指定。 */
    private fun PetEngine.run(seconds: Float, left: Float = 0f, right: Float = 300f, top: Float = 500f) {
        val dt = 1f / 60f
        repeat((seconds * 60).toInt()) {
            setPlatform(left, right, top, width = 400f, dt = dt)
            step(dt)
        }
    }

    private fun entered(map: Map<String, PetReaction> = emptyMap()): PetEngine {
        val engine = PetEngine(manifest(map), Random(7))
        engine.enter()
        engine.run(0.6f)
        return engine
    }

    @Test
    fun `平台测量前入场的角色在平台就绪后淡入并站在平台上`() {
        val engine = PetEngine(manifest(), Random(7))
        engine.enter()
        assertThat(engine.alpha).isEqualTo(0f)

        engine.run(0.6f)

        assertThat(engine.mode).isEqualTo(PetEngine.Mode.NORMAL)
        assertThat(engine.alpha).isEqualTo(1f)
        assertThat(engine.y).isEqualTo(0f)
        assertThat(engine.x).isWithin(0.01f).of(204f)
        assertThat(engine.clip).isEqualTo("stand")
    }

    @Test
    fun `任务信号切换后显示该信号声明的片段并请求台词`() {
        val engine = entered()

        engine.setTaskSignal(PetSignals.TASK_GENERATING)
        engine.run(0.1f)

        assertThat(engine.clip).isEqualTo("eat")
        assertThat(engine.sayRequests).contains(PetSignals.TASK_GENERATING)
    }

    @Test
    fun `带进入反应的信号先播反应片段再回到状态片段`() {
        val engine = entered()

        engine.setTaskSignal(PetSignals.TASK_APPROVAL)
        engine.run(0.3f)
        assertThat(engine.clip).isEqualTo("startle")

        engine.run(0.6f)
        assertThat(engine.clip).isEqualTo("hand")
    }

    @Test
    fun `包没有声明的任务信号回退到空闲表现`() {
        val engine = entered()

        engine.setTaskSignal(PetSignals.TASK_THINKING)
        engine.run(0.2f)

        assertThat(engine.clip).isEqualTo("stand")
    }

    @Test
    fun `提起后松手按重力落回平台并播放重落地片段`() {
        val engine = entered()

        assertThat(engine.grab(100f, 300f, time = 0f)).isTrue()
        engine.run(0.1f)
        assertThat(engine.mode).isEqualTo(PetEngine.Mode.HELD)
        assertThat(engine.clip).isEqualTo("held")
        assertThat(engine.y).isEqualTo(200f)

        engine.release()
        engine.run(0.1f)
        assertThat(engine.mode).isEqualTo(PetEngine.Mode.FALLING)
        assertThat(engine.clip).isEqualTo("fall")

        engine.run(1f)
        assertThat(engine.mode).isEqualTo(PetEngine.Mode.NORMAL)
        assertThat(engine.y).isEqualTo(0f)
        assertThat(engine.clip).isEqualTo("land")
        // 落点被引回平台范围内。
        assertThat(engine.x).isAtLeast(44f)

        engine.run(1.6f)
        assertThat(engine.clip).isEqualTo("stand")
    }

    @Test
    fun `进出场期间不能提起`() {
        val engine = PetEngine(manifest(), Random(7))
        engine.enter()
        engine.run(0.05f)

        assertThat(engine.grab(100f, 300f, time = 0f)).isFalse()
    }

    @Test
    fun `浮岛左缘移动推着角色走并在停下后摔倒再起身`() {
        val engine = entered()

        engine.run(0.1f, left = 200f)
        assertThat(engine.x).isWithin(0.01f).of(244f)
        assertThat(engine.clip).isEqualTo("push")

        engine.run(0.4f, left = 200f)
        assertThat(engine.clip).isEqualTo("over")

        engine.run(1.2f, left = 200f)
        assertThat(engine.clip).isEqualTo("up")

        engine.run(1.8f, left = 200f)
        assertThat(engine.clip).isEqualTo("stand")
    }

    @Test
    fun `浮岛变宽稳定后播放变宽反应`() {
        val engine = entered()

        engine.run(0.5f, right = 380f)

        assertThat(engine.clip).isEqualTo("look")
    }

    @Test
    fun `离场序列钻入浮岛后角色消失`() {
        val engine = entered(
            mapOf(
                PetSignals.ROLE_EXIT to PetReaction(
                    sequence = listOf(
                        PetSequenceStep(clip = "startle", launch = 430),
                        PetSequenceStep(clip = "dive", intoIsland = true),
                    ),
                ),
            ),
        )

        engine.exit()
        engine.run(0.2f)
        assertThat(engine.clip).isEqualTo("startle")
        assertThat(engine.clipAtPlatform).isTrue()

        engine.run(2f)
        assertThat(engine.gone).isTrue()
        assertThat(engine.alpha).isEqualTo(0f)
    }

    @Test
    fun `没有离场序列时淡出后消失`() {
        val engine = entered()

        engine.exit()
        engine.run(0.5f)

        assertThat(engine.gone).isTrue()
    }

    @Test
    fun `移除动画时不选择走动行为`() {
        val walking = PetReaction(
            behaviors = listOf(
                PetBehavior(clip = "walk", weight = 100.0, move = true),
                PetBehavior(clip = "stand", weight = 1.0),
            ),
        )
        val engine = PetEngine(manifest(mapOf(PetSignals.IDLE to walking)), Random(7))
        engine.reducedMotion = true
        engine.enter()

        engine.run(5f)

        assertThat(engine.clip).isEqualTo("stand")
        assertThat(engine.x).isWithin(0.01f).of(204f)
    }

    @Test
    fun `走动行为朝目标移动并在反方向时翻转`() {
        val walking = PetReaction(behaviors = listOf(PetBehavior(clip = "walk", move = true)))
        val engine = PetEngine(manifest(mapOf(PetSignals.IDLE to walking)), Random(7))
        engine.enter()
        engine.run(0.6f)
        val start = engine.x

        engine.run(1f)

        assertThat(engine.clip).isEqualTo("walk")
        assertThat(engine.x).isNotEqualTo(start)
        assertThat(engine.flip).isEqualTo(engine.x < start)
    }

    @Test
    fun `站定后进入平静状态，点按、走动与提起期间不平静`() {
        val engine = entered()
        engine.run(1f)
        assertThat(engine.calm).isTrue()

        engine.tap()
        engine.run(1f / 60f)
        assertThat(engine.calm).isFalse()
        engine.run(2f)
        assertThat(engine.calm).isTrue()

        assertThat(engine.grab(100f, 300f, time = 0f)).isTrue()
        engine.run(0.2f)
        assertThat(engine.calm).isFalse()

        val walker = PetEngine(manifest(mapOf(PetSignals.IDLE to PetReaction(behaviors = listOf(PetBehavior(clip = "walk", move = true))))), Random(7))
        walker.enter()
        walker.run(1.6f)
        assertThat(walker.clip).isEqualTo("walk")
        assertThat(walker.calm).isFalse()
    }

    @Test
    fun `平静一秒后降到约每秒 15 帧，移除动画或省电时每半秒一次`() {
        assertThat(petFrameWaitMillis(calmSeconds = 0.5f, reducedMotion = false, clipType = "pose", videoActive = false)).isEqualTo(0L)
        assertThat(petFrameWaitMillis(calmSeconds = 1.2f, reducedMotion = false, clipType = "pose", videoActive = false)).isEqualTo(66L)
        assertThat(petFrameWaitMillis(calmSeconds = 1.2f, reducedMotion = true, clipType = "pose", videoActive = false)).isEqualTo(500L)
    }

    @Test
    fun `逐帧片段与视频保持逐帧推进`() {
        assertThat(petFrameWaitMillis(calmSeconds = 5f, reducedMotion = false, clipType = "frames", videoActive = false)).isEqualTo(0L)
        assertThat(petFrameWaitMillis(calmSeconds = 5f, reducedMotion = true, clipType = "video", videoActive = true)).isEqualTo(0L)
    }
}
