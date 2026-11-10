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
    private fun PetEngine.run(
        seconds: Float,
        left: Float = 0f,
        right: Float = 300f,
        top: Float = 500f,
        height: Float = 0f,
    ) {
        val dt = 1f / 60f
        repeat((seconds * 60).toInt()) {
            setPlatform(left, right, top, width = 400f, dt = dt, height = height)
            step(dt)
        }
    }

    /** 图层高 800 dp 的入场：有高度时才提供栖息点。 */
    private fun enteredTall(map: Map<String, PetReaction> = emptyMap()): PetEngine {
        val engine = PetEngine(manifest(map), Random(7))
        engine.enter()
        engine.run(0.6f, height = TALL)
        return engine
    }

    /** 提起后慢慢拖到屏幕上部松手，落到栖息点上。 */
    private fun PetEngine.dropOnPerch() {
        grab(100f, 450f, time = 0f)
        dragTo(150f, 200f, time = 0.5f)
        release(time = 0.5f)
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
    fun `帧率按状态与片段类型限制`() {
        val normal = PetEngine.Mode.NORMAL
        // 走动、过渡与回弹：30 帧。
        assertThat(petTargetFps(normal, calmSeconds = 0.5f, reducedMotion = false, clipType = "pose", clipFps = null, videoActive = false)).isEqualTo(30)
        // 站定后只剩呼吸与眨眼：15 帧；移除动画或省电：2 帧。
        assertThat(petTargetFps(normal, calmSeconds = 1.2f, reducedMotion = false, clipType = "pose", clipFps = null, videoActive = false)).isEqualTo(15)
        assertThat(petTargetFps(normal, calmSeconds = 1.2f, reducedMotion = true, clipType = "pose", clipFps = null, videoActive = false)).isEqualTo(2)
        // 逐帧片段站定时按片段帧率，限制在 4 至 30 帧。
        assertThat(petTargetFps(normal, calmSeconds = 5f, reducedMotion = false, clipType = "frames", clipFps = 12.0, videoActive = false)).isEqualTo(12)
        assertThat(petTargetFps(normal, calmSeconds = 5f, reducedMotion = true, clipType = "frames", clipFps = 60.0, videoActive = false)).isEqualTo(30)
        assertThat(petTargetFps(normal, calmSeconds = 5f, reducedMotion = false, clipType = "frames", clipFps = null, videoActive = false)).isEqualTo(8)
        // 视频由播放器解码，图层 30 帧。
        assertThat(petTargetFps(normal, calmSeconds = 5f, reducedMotion = false, clipType = "video", clipFps = null, videoActive = true)).isEqualTo(30)
    }

    @Test
    fun `拖动、下落与进出场跟随屏幕刷新`() {
        listOf(PetEngine.Mode.HELD, PetEngine.Mode.FALLING, PetEngine.Mode.ENTERING, PetEngine.Mode.EXITING).forEach { mode ->
            assertThat(petTargetFps(mode, calmSeconds = 5f, reducedMotion = false, clipType = "pose", clipFps = null, videoActive = false)).isEqualTo(0)
        }
    }

    @Test
    fun `较快松手时抛出去撞边反弹再落回浮岛`() {
        val engine = entered()

        engine.grab(100f, 300f, time = 0f)
        engine.dragTo(130f, 280f, time = 0.016f)
        engine.release(time = 0.016f)

        assertThat(engine.mode).isEqualTo(PetEngine.Mode.FLYING)
        assertThat(engine.events).contains(PetSignals.TOUCH_THROW)

        engine.run(3f)
        assertThat(engine.mode).isEqualTo(PetEngine.Mode.NORMAL)
        assertThat(engine.y).isEqualTo(0f)
        assertThat(engine.x).isAtMost(282f)
        assertThat(engine.events).contains(PetSignals.TOUCH_BOUNCE)
        assertThat(engine.events).contains(PetSignals.TOUCH_LAND_HARD)
        assertThat(engine.dismissedByFling).isFalse()
    }

    @Test
    fun `用力甩出后飞出屏幕并标记为被收起`() {
        val engine = entered()

        engine.grab(100f, 300f, time = 0f)
        engine.dragTo(200f, 250f, time = 0.016f)
        engine.release(time = 0.016f)

        assertThat(engine.mode).isEqualTo(PetEngine.Mode.FLYING_OUT)
        assertThat(engine.events).contains(PetSignals.ROLE_DISMISS)

        engine.run(1f)
        assertThat(engine.gone).isTrue()
        assertThat(engine.dismissedByFling).isTrue()
        assertThat(engine.scale).isEqualTo(1f)
    }

    @Test
    fun `关闭甩出收起后用力甩出只反弹`() {
        val engine = entered()
        engine.flingEnabled = false

        engine.grab(100f, 300f, time = 0f)
        engine.dragTo(200f, 250f, time = 0.016f)
        engine.release(time = 0.016f)

        assertThat(engine.mode).isEqualTo(PetEngine.Mode.FLYING)
        engine.run(4f)
        assertThat(engine.mode).isEqualTo(PetEngine.Mode.NORMAL)
        assertThat(engine.dismissedByFling).isFalse()
    }

    @Test
    fun `拖动停住一会儿再松手按静止处理`() {
        val engine = entered()

        engine.grab(100f, 300f, time = 0f)
        engine.dragTo(200f, 250f, time = 0.016f)
        engine.release(time = 0.3f)

        assertThat(engine.mode).isEqualTo(PetEngine.Mode.FALLING)
    }

    @Test
    fun `被收起后唤回从上方落到浮岛并发出唤回事件`() {
        val engine = entered()
        engine.grab(100f, 300f, time = 0f)
        engine.dragTo(200f, 250f, time = 0.016f)
        engine.release(time = 0.016f)
        engine.run(1f)
        engine.events.clear()

        engine.summon()
        assertThat(engine.mode).isEqualTo(PetEngine.Mode.FALLING)
        assertThat(engine.alpha).isEqualTo(1f)
        assertThat(engine.dismissedByFling).isFalse()

        engine.run(1.2f)
        assertThat(engine.mode).isEqualTo(PetEngine.Mode.NORMAL)
        assertThat(engine.y).isEqualTo(0f)
        assertThat(engine.events).contains(PetSignals.ROLE_SUMMON)
        assertThat(engine.events).doesNotContain(PetSignals.TOUCH_LAND_HARD)
        // 包没有声明唤回片段时用摔倒再起身代替。
        assertThat(engine.clip).isEqualTo("over")
    }

    @Test
    fun `提起时出现栖息点提示并在拖到屏幕上部后可落座`() {
        val engine = enteredTall()

        engine.grab(100f, 450f, time = 0f)
        assertThat(engine.perchPhase).isEqualTo(PetEngine.PerchPhase.GHOST)
        assertThat(engine.perchHot).isFalse()

        engine.dragTo(150f, 200f, time = 0.5f)
        assertThat(engine.perchHot).isTrue()

        engine.release(time = 0.5f)
        assertThat(engine.place).isEqualTo(PetEngine.Place.PERCH)
        assertThat(engine.perchPhase).isEqualTo(PetEngine.PerchPhase.ACTIVE)
        assertThat(engine.mode).isEqualTo(PetEngine.Mode.NORMAL)
        assertThat(engine.x).isEqualTo(150f)
        assertThat(engine.y).isEqualTo(0f)
        assertThat(engine.perchY).isWithin(0.01f).of(271.5f)
        assertThat(engine.groundTop).isWithin(3.01f).of(271.5f)
        assertThat(engine.events).contains(PetSignals.PLACE_PERCH_ENTER)
    }

    @Test
    fun `没有图层高度时不提供栖息点`() {
        val engine = entered()

        engine.grab(100f, 450f, time = 0f)
        engine.dragTo(150f, 200f, time = 0.5f)
        engine.release(time = 0.5f)

        assertThat(engine.perchPhase).isEqualTo(PetEngine.PerchPhase.NONE)
        assertThat(engine.mode).isEqualTo(PetEngine.Mode.FALLING)
    }

    @Test
    fun `在栖息点上不走动`() {
        val walking = PetReaction(
            behaviors = listOf(
                PetBehavior(clip = "walk", weight = 100.0, move = true),
                PetBehavior(clip = "stand", weight = 1.0),
            ),
        )
        val engine = enteredTall(mapOf(PetSignals.IDLE to walking))
        engine.dropOnPerch()

        engine.run(5f, height = TALL)

        assertThat(engine.place).isEqualTo(PetEngine.Place.PERCH)
        assertThat(engine.x).isEqualTo(150f)
        assertThat(engine.clip).isEqualTo("stand")
    }

    @Test
    fun `在栖息点上优先使用带栖息点限定的映射`() {
        val engine = enteredTall(mapOf("${PetSignals.IDLE}@perch" to PetReaction(clip = "look")))
        engine.run(0.2f, height = TALL)
        assertThat(engine.clip).isEqualTo("stand")

        engine.dropOnPerch()
        engine.run(0.2f, height = TALL)

        assertThat(engine.clip).isEqualTo("look")
    }

    @Test
    fun `戳破泡泡后落回浮岛`() {
        val engine = enteredTall()
        engine.dropOnPerch()
        engine.run(0.5f, height = TALL)

        assertThat(engine.popPerch()).isTrue()
        assertThat(engine.mode).isEqualTo(PetEngine.Mode.FALLING)
        assertThat(engine.perchPhase).isEqualTo(PetEngine.PerchPhase.VANISHING)
        assertThat(engine.perchExit).isEqualTo(PetEngine.PerchExit.POP)

        engine.run(1.5f, height = TALL)
        assertThat(engine.mode).isEqualTo(PetEngine.Mode.NORMAL)
        assertThat(engine.place).isEqualTo(PetEngine.Place.ISLAND)
        assertThat(engine.perchPhase).isEqualTo(PetEngine.PerchPhase.NONE)
        assertThat(engine.y).isEqualTo(0f)
        assertThat(engine.events).containsAtLeast(PetSignals.PLACE_PERCH_POP, PetSignals.PLACE_PERCH_LEAVE)
    }

    @Test
    fun `吹散云朵只在栖息点上有效`() {
        val engine = enteredTall()
        assertThat(engine.blowPerch()).isFalse()

        engine.dropOnPerch()
        assertThat(engine.blowPerch()).isTrue()
        assertThat(engine.perchExit).isEqualTo(PetEngine.PerchExit.BLOWN)
        assertThat(engine.events).contains(PetSignals.PLACE_PERCH_BLOWN)
    }

    @Test
    fun `从栖息点提起后在下方松手落回浮岛`() {
        val engine = enteredTall()
        engine.dropOnPerch()
        engine.run(0.5f, height = TALL)

        engine.grab(150f, 271f, time = 1f)
        assertThat(engine.perchPhase).isEqualTo(PetEngine.PerchPhase.GHOST)
        engine.dragTo(150f, 450f, time = 1.5f)
        assertThat(engine.perchHot).isFalse()
        engine.release(time = 1.5f)

        assertThat(engine.mode).isEqualTo(PetEngine.Mode.FALLING)
        engine.run(1.5f, height = TALL)
        assertThat(engine.place).isEqualTo(PetEngine.Place.ISLAND)
        assertThat(engine.mode).isEqualTo(PetEngine.Mode.NORMAL)
    }

    @Test
    fun `恢复栖息点后入场直接出现在栖息点上`() {
        val engine = PetEngine(manifest(), Random(7))
        engine.restorePerch(120f, 200f)
        engine.enter()

        engine.run(0.6f, height = TALL)

        assertThat(engine.place).isEqualTo(PetEngine.Place.PERCH)
        assertThat(engine.mode).isEqualTo(PetEngine.Mode.NORMAL)
        assertThat(engine.x).isEqualTo(120f)
        assertThat(engine.perchY).isEqualTo(200f)
        assertThat(engine.alpha).isEqualTo(1f)
    }

    @Test
    fun `点按身体分区时依次回退到戳和点按`() {
        val engine = entered(
            mapOf(
                PetSignals.TOUCH_TAP to PetReaction(clip = "look"),
                PetSignals.TOUCH_POKE_HEAD to PetReaction(clip = "startle"),
            ),
        )

        engine.tap("head")
        engine.run(0.05f)
        assertThat(engine.clip).isEqualTo("startle")
        assertThat(engine.events).contains(PetSignals.TOUCH_POKE_HEAD)

        engine.tap("belly")
        engine.run(0.05f)
        assertThat(engine.clip).isEqualTo("look")
    }

    @Test
    fun `三秒内连点五次触发不耐烦`() {
        val engine = entered(
            mapOf(
                PetSignals.TOUCH_TAP to PetReaction(clip = "look"),
                PetSignals.TOUCH_ANNOYED to PetReaction(clip = "over"),
            ),
        )

        repeat(4) {
            engine.tap()
            engine.run(0.1f)
        }
        assertThat(engine.clip).isEqualTo("look")

        engine.tap()
        engine.run(0.1f)
        assertThat(engine.clip).isEqualTo("over")
        assertThat(engine.events.last()).isEqualTo(PetSignals.TOUCH_ANNOYED)
    }

    @Test
    fun `间隔较长的点按不触发不耐烦`() {
        val engine = entered(mapOf(PetSignals.TOUCH_ANNOYED to PetReaction(clip = "over")))

        repeat(6) {
            engine.tap()
            engine.run(1f)
        }

        assertThat(engine.events).doesNotContain(PetSignals.TOUCH_ANNOYED)
    }

    @Test
    fun `手机倾斜时沿浮岛滑到边缘`() {
        val engine = entered()

        engine.setTilt(0.8f)
        engine.run(0.5f)

        assertThat(engine.x).isWithin(0.01f).of(256f)
        assertThat(engine.events).contains(PetSignals.TILT_SLIDE_RIGHT)

        engine.setTilt(0.1f)
        engine.run(0.5f)
        assertThat(engine.x).isWithin(0.01f).of(256f)
    }

    @Test
    fun `摇晃后晃一会儿再站稳`() {
        val engine = entered()

        engine.shake()
        engine.run(0.5f)
        assertThat(engine.events).contains(PetSignals.SHAKE_DIZZY)
        assertThat(engine.rotation).isNotEqualTo(0f)

        engine.run(1.5f)
        assertThat(engine.rotation).isEqualTo(0f)
    }

    @Test
    fun `任务持续较久后换成带时长限定的表现且不重播进入反应`() {
        val engine = entered(
            mapOf("${PetSignals.TASK_GENERATING}@long" to PetReaction(clip = "dive", enter = "startle")),
        )
        engine.setTaskSignal(PetSignals.TASK_GENERATING)
        engine.run(0.1f)
        assertThat(engine.clip).isEqualTo("eat")

        engine.setContext(duration = "long", mood = null)
        engine.run(0.1f)

        assertThat(engine.clip).isEqualTo("dive")
    }

    @Test
    fun `工具信号依次回退到工具类别和通用工具表现`() {
        val engine = entered(
            mapOf(
                "${PetSignals.TASK_TOOL}.search" to PetReaction(clip = "look"),
                PetSignals.TASK_TOOL to PetReaction(clip = "dive"),
            ),
        )

        engine.setTaskSignal("${PetSignals.TASK_TOOL}.web_search")
        engine.run(0.1f)
        assertThat(engine.clip).isEqualTo("look")

        engine.setTaskSignal("${PetSignals.TASK_TOOL}.unknown_tool")
        engine.run(0.1f)
        assertThat(engine.clip).isEqualTo("dive")
    }

    private companion object {
        const val TALL = 800f
    }
}
