package com.promenar.infinity.ui.pet

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.promenar.infinity.data.pet.InstalledPet
import com.promenar.infinity.data.pet.PetLineGenerator
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.pet.PetClip
import com.promenar.infinity.domain.pet.PetIdleSignalMapper
import com.promenar.infinity.domain.pet.PetLineTemplate
import com.promenar.infinity.domain.pet.PetSignals
import com.promenar.infinity.domain.pet.PetTaskSignalMapper
import com.promenar.infinity.domain.pet.PetValues
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 内核每帧提供给宠物的状态，见规格第 4、6 节。 */
internal class PetInputs(
    val phase: GenerationPhase?,
    val awaitingApproval: Boolean,
    val awaitingAnswer: Boolean,
    val contentLength: Int,
    val reasoningLength: Int,
    val lineData: Map<String, String>,
    val reducedMotion: Boolean,
    val videoAllowed: Boolean,
)

/** 浮岛平台在宠物图层内的位置（像素）。 */
internal class PetPlatform(val left: Float, val right: Float, val top: Float, val layerWidth: Float)

/** 当前正在播放的视频片段及其在图层内的位置。 */
internal data class PetVideoRequest(val clipName: String, val clip: PetClip)

/**
 * 一个已显示宠物的运行时：驱动 [PetEngine]，把结果画到画布上，并管理气泡台词。
 *
 * 由宠物图层每帧调用 [tick]，绘制阶段调用 [draw]。除位图解码与台词请求外都在主线程执行。
 */
internal class PetRuntime(
    val pet: InstalledPet,
    private val scope: CoroutineScope,
    private val lineGenerator: PetLineGenerator,
    private val random: Random = Random.Default,
) {
    val engine = PetEngine(pet.manifest, random).also { it.enter() }
    private val assets = PetAssets(pet, scope)
    private val rigState = PetRigState()
    private val motion = PetMotion()
    private val still = PetMotion()
    private val vertices = FloatArray(PET_MESH_VERTICES * 2)
    private val textureCoordinates = FloatArray(PET_MESH_VERTICES * 2)
    private val indices = petMeshIndices()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** 每帧递增；布局与绘制读取它来跟随宠物移动，不触发重组。 */
    var frame by mutableLongStateOf(0L)
        private set

    /** 气泡里的台词；没有台词时为 null。 */
    var bubble by mutableStateOf<String?>(null)
        private set

    /** 需要由视频图层播放的片段；没有时为 null。 */
    var video by mutableStateOf<PetVideoRequest?>(null)
        private set

    /** 视频图层已渲染出首帧；为假时画布继续画替补片段。 */
    var videoReady = false

    /** 头顶在图层内的位置（像素），供气泡与触摸区域使用。 */
    var headX = 0f
        private set
    var headTop = 0f
        private set
    var feetY = 0f
        private set
    var bodyWidth = 0f
        private set

    private var time = 0f
    private var density = 1f
    private var platformTop = 0f
    private var idleSeconds = 0f
    private var lastContentLength = 0
    private var lastReasoningLength = 0
    private var contentGrewAt = -10f
    private var reasoningGrewAt = -10f
    private var bubbleLeft = 0f
    private var lastLineAt = -100f
    private var blinkLeft = 0f
    private var nextBlink = 2.5f
    private var videoAllowed = false
    private var lineData: Map<String, String> = emptyMap()

    /** 推进一帧。[dt] 为秒，[pxPerDp] 为屏幕密度。 */
    fun tick(dt: Float, inputs: PetInputs, platform: PetPlatform?, pxPerDp: Float) {
        time += dt
        density = pxPerDp
        videoAllowed = inputs.videoAllowed
        lineData = inputs.lineData
        engine.reducedMotion = inputs.reducedMotion
        if (platform != null) {
            platformTop = platform.top
            engine.setPlatform(
                left = platform.left / pxPerDp,
                right = platform.right / pxPerDp,
                top = platform.top / pxPerDp,
                width = platform.layerWidth / pxPerDp,
                dt = dt,
            )
        }
        engine.setTaskSignal(taskSignal(dt, inputs))
        engine.step(dt)
        stepBlink(dt)
        updateVideo()
        updateMetrics()
        while (engine.sayRequests.isNotEmpty()) requestLine(engine.sayRequests.removeFirst())
        if (bubbleLeft > 0f) {
            bubbleLeft -= dt
            if (bubbleLeft <= 0f) bubble = null
        }
        frame++
    }

    /** 画出当前帧。坐标系与宠物图层一致。 */
    fun draw(canvas: Canvas, dt: Float) {
        if (engine.gone || engine.alpha <= 0f) return
        val clipping = engine.clipAtPlatform
        if (clipping) {
            canvas.save()
            canvas.clipRect(0f, 0f, canvas.width.toFloat(), platformTop + FOOT_SINK_DP * density)
        }
        val previous = engine.previousClip
        if (previous != null && engine.fade < 1f) drawClip(canvas, previous, 1f - engine.fade, current = false, dt = dt)
        val current = engine.clip
        if (current != null) drawClip(canvas, current, engine.fade, current = true, dt = dt)
        if (clipping) canvas.restore()
    }

    fun tap() {
        idleSeconds = 0f
        engine.tap()
    }

    fun grab(x: Float, y: Float): Boolean {
        idleSeconds = 0f
        return engine.grab(x / density, y / density, time)
    }

    fun dragTo(x: Float, y: Float) = engine.dragTo(x / density, y / density, time)

    fun release() = engine.release()

    fun exit() {
        bubble = null
        engine.exit()
    }

    fun dispose() {
        assets.release()
    }

    /** 把内核状态换算成任务信号；没有任务时按空闲时长给出空闲、长时间空闲或睡觉。 */
    private fun taskSignal(dt: Float, inputs: PetInputs): String {
        if (inputs.contentLength > lastContentLength) contentGrewAt = time
        if (inputs.reasoningLength > lastReasoningLength) reasoningGrewAt = time
        lastContentLength = inputs.contentLength
        lastReasoningLength = inputs.reasoningLength
        val signal = PetTaskSignalMapper.map(
            phase = inputs.phase,
            awaitingApproval = inputs.awaitingApproval,
            awaitingAnswer = inputs.awaitingAnswer,
            contentGrowing = time - contentGrewAt < GROWTH_WINDOW,
            reasoningGrowing = time - reasoningGrewAt < GROWTH_WINDOW,
        )
        if (signal != PetSignals.IDLE) {
            idleSeconds = 0f
            return signal
        }
        idleSeconds += dt
        return PetIdleSignalMapper.map(idleSeconds.toLong())
    }

    private fun stepBlink(dt: Float) {
        blinkLeft = max(0f, blinkLeft - dt)
        nextBlink -= dt
        if (nextBlink > 0f) return
        blinkLeft = BLINK_SECONDS
        nextBlink = if (random.nextFloat() < 0.25f) 0.28f else 2.2f + random.nextFloat() * 3.2f
    }

    private fun updateVideo() {
        val name = engine.clip
        val clip = name?.let { pet.manifest.clips[it] }
        val wanted = if (clip != null && clip.type == PetValues.VIDEO && videoAllowed && engine.fade >= 1f) {
            PetVideoRequest(name, clip)
        } else {
            null
        }
        if (wanted != video) {
            videoReady = false
            video = wanted
        }
    }

    private fun updateMetrics() {
        val clip = engine.clip?.let { resolveDrawable(it) } ?: return
        val height = clipHeight(clip)
        val anchorV = clip.anchor.getOrNull(1)?.toFloat() ?: 1f
        feetY = platformTop + FOOT_SINK_DP * density - engine.y * density
        headX = engine.x * density
        headTop = feetY - anchorV * height
        bodyWidth = height * 0.55f
    }

    /** 视频片段在不能播放时换成它的替补片段。 */
    private fun resolveDrawable(name: String): PetClip? {
        val clip = pet.manifest.clips[name] ?: return null
        if (clip.type != PetValues.VIDEO) return clip
        return clip.fallback?.let { pet.manifest.clips[it] }?.takeIf { it.type != PetValues.VIDEO }
    }

    private fun clipHeight(clip: PetClip): Float = pet.manifest.display.heightDp * clip.scale.toFloat() * density

    private fun drawClip(canvas: Canvas, name: String, alpha: Float, current: Boolean, dt: Float) {
        val declared = pet.manifest.clips[name] ?: return
        var textureName = name
        var clip = declared
        if (declared.type == PetValues.VIDEO) {
            if (current && video?.clipName == name && videoReady) return
            textureName = declared.fallback ?: return
            clip = pet.manifest.clips[textureName]?.takeIf { it.type != PetValues.VIDEO } ?: return
        }
        val texture = expressionTexture(textureName, current) ?: assets.texture(textureName) ?: return
        val bitmap = texture.bitmap
        var cellLeft = 0f
        var cellTop = 0f
        var cellWidth = bitmap.width.toFloat()
        var cellHeight = bitmap.height.toFloat()
        if (clip.type == PetValues.FRAMES) {
            val columns = max(1, clip.cols ?: 1)
            val rows = max(1, clip.rows ?: 1)
            val count = max(1, min(clip.count ?: (columns * rows), columns * rows))
            val elapsed = if (current && !engine.reducedMotion) engine.clipTime else 0f
            val raw = (elapsed * (clip.fps ?: 8.0).toFloat()).toInt()
            val index = if (clip.loop != false) raw % count else min(raw, count - 1)
            cellWidth = bitmap.width / columns.toFloat()
            cellHeight = bitmap.height / rows.toFloat()
            cellLeft = (index % columns) * cellWidth
            cellTop = (index / columns) * cellHeight
        }
        val height = clipHeight(clip)
        val width = height * cellWidth / cellHeight
        val preset = if (!current || engine.reducedMotion) {
            "none"
        } else {
            clip.motion ?: if (clip.type == PetValues.POSE) "breathe" else "none"
        }
        val applied = if (preset == "none") still else motion
        if (applied === motion) {
            petMotion(preset, time, motion)
            rigState.step(motion, engine.x, time, dt)
        }
        deformPetMesh(
            out = vertices,
            width = width,
            height = height,
            anchorU = clip.anchor.getOrNull(0)?.toFloat() ?: 0.5f,
            anchorV = clip.anchor.getOrNull(1)?.toFloat() ?: 1f,
            rig = clip.rig,
            motion = applied,
            rigState = if (applied === motion && clip.rig != null) rigState else null,
            baseX = engine.x * density,
            baseY = platformTop + FOOT_SINK_DP * density - engine.y * density,
            pop = engine.pop.x,
            rotation = engine.rotation,
            flip = engine.flip,
            dpToPx = density,
        )
        petMeshTextureCoordinates(textureCoordinates, cellLeft, cellTop, cellWidth, cellHeight)
        paint.shader = texture.shader
        paint.alpha = (alpha * engine.alpha * 255f).toInt().coerceIn(0, 255)
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES, vertices.size, vertices, 0, textureCoordinates, 0, null, 0,
            indices, 0, indices.size, paint,
        )
    }

    /** 当前片段是表情底图时，返回表情或眨眼合成后的纹理。 */
    private fun expressionTexture(clipName: String, current: Boolean): PetAssets.Texture? {
        if (!current || clipName != pet.manifest.expressions.baseClip) return null
        val expression = engine.expression
        if (expression != null && expression != NEUTRAL) return assets.expressionTexture(expression)
        if (blinkLeft > 0f && !engine.reducedMotion) return assets.expressionTexture(BLINK)
        return null
    }

    private fun requestLine(signal: String) {
        val persona = pet.persona ?: return
        val gap = if (signal.startsWith("task.") || signal == PetSignals.ROLE_ENTER) TASK_LINE_GAP else EVENT_LINE_GAP
        if (time - lastLineAt < gap) return
        lastLineAt = time
        val data = lineData + mapOf("idle.s" to idleSeconds.toInt().toString(), "daypart" to daypart())
        scope.launch {
            val generated = lineGenerator.generate(persona, signal, data)
            val line = generated ?: fallbackLine(signal, data)?.also { lineGenerator.recordFallbackLine() }
            if (line != null && !engine.gone) {
                bubble = line
                bubbleLeft = BUBBLE_SECONDS
            }
        }
    }

    /** 兜底台词：依次查找信号自身、工具大类和所属大类的模板。 */
    private fun fallbackLine(signal: String, data: Map<String, String>): String? {
        val fallback = pet.persona?.fallback ?: return null
        val keys = buildList {
            add(signal)
            if (signal.startsWith(PetSignals.TASK_TOOL + ".")) add(PetSignals.TASK_TOOL)
            PetSignals.categories[last()]?.let { add(it) }
        }
        for (key in keys) {
            val templates = fallback[key].orEmpty()
            if (templates.isEmpty()) continue
            return PetLineTemplate.firstAvailable(templates.shuffled(random), data)
        }
        return null
    }

    private fun daypart(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..10 -> "早上"
        in 11..13 -> "中午"
        in 14..17 -> "下午"
        in 18..22 -> "晚上"
        else -> "深夜"
    }

    private companion object {
        const val FOOT_SINK_DP = 2f
        const val GROWTH_WINDOW = 1f
        const val BLINK_SECONDS = 0.16f
        const val BUBBLE_SECONDS = 3.2f
        const val TASK_LINE_GAP = 6f
        const val EVENT_LINE_GAP = 3f
        const val NEUTRAL = "neutral"
        const val BLINK = "blink"
    }
}
