package com.promenar.infinity.ui.pet

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.data.pet.InstalledPet
import com.promenar.infinity.domain.generation.GenerationPhase
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.awaitCancellation

internal const val PET_LAYER_TAG = "pet_layer"

/**
 * 会话页的宠物图层：叠在聊天区域之上，宠物站在输入框浮岛的上沿。
 *
 * 只有宠物响应界面：图层不修改浮岛，也不拦截宠物以外区域的触摸。宠物默认关闭，
 * 未启用且没有正在离场的角色时不创建任何内容。
 *
 * @param islandBounds 浮岛在根坐标系中的范围；尚未测量时为 [Rect.Zero]。
 * @param modelId 当前会话使用的模型，用于按模型关联切换角色。
 * @param toolName 正在执行的工具名，用于工具专属表现；没有时为 null。
 * @param lineData 交给台词的任务数据，见规格第 6 节。
 */
@Composable
fun PetOverlay(
    islandBounds: Rect,
    phase: GenerationPhase?,
    awaitingApproval: Boolean,
    awaitingAnswer: Boolean,
    contentLength: Int,
    reasoningLength: Int,
    modelId: String?,
    lineData: Map<String, String>,
    modifier: Modifier = Modifier,
    toolName: String? = null,
) {
    if (LocalInspectionMode.current) return
    val application = LocalContext.current.applicationContext as? InfinityApplication ?: return
    val enabled by application.petPreferenceStore.enabled.collectAsStateWithLifecycle()
    // 关闭后保留图层直到离场播完。
    var present by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (enabled) present = true
    }
    if (!enabled && !present) return
    PetLayer(
        application = application,
        enabled = enabled,
        islandBounds = islandBounds,
        inputs = PetLayerInputs(phase, awaitingApproval, awaitingAnswer, contentLength, reasoningLength, lineData, toolName),
        modelId = modelId,
        onGone = { present = false },
        modifier = modifier,
    )
}

private class PetLayerInputs(
    val phase: GenerationPhase?,
    val awaitingApproval: Boolean,
    val awaitingAnswer: Boolean,
    val contentLength: Int,
    val reasoningLength: Int,
    val lineData: Map<String, String>,
    val toolName: String?,
)

/** 按“模型关联 → 默认宠物 → 第一个已安装的包”选出当前会话应显示的宠物，见规格第 8 节。 */
internal fun resolveActivePet(
    pets: List<InstalledPet>,
    defaultPetId: String?,
    modelPets: Map<String, String>,
    modelId: String?,
): InstalledPet? {
    val associated = modelId?.let(modelPets::get)?.let { id -> pets.firstOrNull { it.id == id } }
    return associated ?: pets.firstOrNull { it.id == defaultPetId } ?: pets.firstOrNull()
}

@Composable
private fun PetLayer(
    application: InfinityApplication,
    enabled: Boolean,
    islandBounds: Rect,
    inputs: PetLayerInputs,
    modelId: String?,
    onGone: () -> Unit,
    modifier: Modifier,
) {
    val preferences = application.petPreferenceStore
    val pets by application.petPackageStore.pets.collectAsStateWithLifecycle()
    val defaultPetId by preferences.defaultPetId.collectAsStateWithLifecycle()
    val modelPets by preferences.modelPets.collectAsStateWithLifecycle()
    val videoEnabled by preferences.videoEnabled.collectAsStateWithLifecycle()
    val dismissed by preferences.dismissed.collectAsStateWithLifecycle()
    val flingEnabled by preferences.flingGestureEnabled.collectAsStateWithLifecycle()
    val motionEnabled by preferences.motionSensorEnabled.collectAsStateWithLifecycle()
    val hapticsEnabled by preferences.hapticsEnabled.collectAsStateWithLifecycle()
    val perchKind by preferences.perchKind.collectAsStateWithLifecycle()
    val blowEnabled by preferences.blowEnabled.collectAsStateWithLifecycle()
    // 被甩出收起时不显示，直到被唤回。
    val target = if (enabled && !dismissed) resolveActivePet(pets, defaultPetId, modelPets, modelId) else null

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current.density
    val dismissHint = stringResource(R.string.pet_dismiss_hint)
    // 外观里关闭触感反馈时，宠物也不振动。
    val latestHaptics by rememberUpdatedState(hapticsEnabled && LocalInfinityHaptics.current.interaction)
    // 下一个创建的运行时是否以“被摇出来”的方式入场。
    val summonByShake = remember { BooleanArray(1) }
    var shown by remember { mutableStateOf<InstalledPet?>(null) }
    val runtime = remember(shown) {
        shown?.let { pet ->
            val shaken = summonByShake[0]
            summonByShake[0] = false
            PetRuntime(
                pet = pet,
                scope = scope,
                lineGenerator = application.petLineGenerator,
                pxPerDp = density,
                preferences = preferences,
                haptic = { kind ->
                    if (latestHaptics) runCatching {
                        view.performHapticFeedback(
                            when (kind) {
                                "thud" -> HapticFeedbackConstants.CONTEXT_CLICK
                                "buzz" -> HapticFeedbackConstants.LONG_PRESS
                                else -> HapticFeedbackConstants.CLOCK_TICK
                            },
                        )
                    }
                },
                summonByShake = shaken,
                dismissHint = dismissHint,
            )
        }
    }
    DisposableEffect(runtime) {
        onDispose { runtime?.dispose() }
    }

    var layerOrigin by remember { mutableStateOf(Offset.Zero) }
    var layerWidth by remember { mutableStateOf(0f) }
    var layerHeight by remember { mutableStateOf(0f) }
    val frameDelta = remember { FloatArray(1) }
    val latestTarget by rememberUpdatedState(target)
    val latestInputs by rememberUpdatedState(inputs)
    val latestIsland by rememberUpdatedState(islandBounds)
    val latestVideoEnabled by rememberUpdatedState(videoEnabled)
    val latestOnGone by rememberUpdatedState(onGone)
    val latestRuntime by rememberUpdatedState(runtime)
    val latestDismissed by rememberUpdatedState(dismissed)
    val latestFling by rememberUpdatedState(flingEnabled)
    val latestMotion by rememberUpdatedState(motionEnabled)
    val latestPerchKind by rememberUpdatedState(perchKind)
    val lifecycleOwner = LocalLifecycleOwner.current
    val motion = remember { PetMotionDetector() }

    // 加速度计：倾斜打滑、摇晃发晕；宠物被收起时只用来识别“用力摇晃唤回”。只在页面处于前台时监听。
    val sensorWanted = enabled && (motionEnabled || (dismissed && flingEnabled))
    LaunchedEffect(sensorWanted, lifecycleOwner) {
        if (!sensorWanted) return@LaunchedEffect
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return@LaunchedEffect
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return@LaunchedEffect
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rotation = view.display?.rotation ?: 0
                val shake = motion.feed(event.values[0], event.values[1], event.values[2], event.timestamp / 1_000_000L, rotation)
                    ?: return
                if (latestDismissed) {
                    if (shake == PetShake.STRONG && latestFling) {
                        summonByShake[0] = true
                        preferences.setDismissed(false)
                    }
                } else if (latestMotion) {
                    latestRuntime?.shake()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            try {
                awaitCancellation()
            } finally {
                manager.unregisterListener(listener)
            }
        }
    }

    // 键盘弹出、收起时的反应。
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val keyboardSeen = remember { BooleanArray(1) }
    LaunchedEffect(keyboardUp) {
        if (keyboardSeen[0]) latestRuntime?.keyboard(keyboardUp)
        keyboardSeen[0] = true
    }

    // 吹散云朵：宠物在云朵上、页面在前台、键盘没有弹出时才监听麦克风。
    val listening = blowEnabled && runtime?.onCloud == true && !keyboardUp
    LaunchedEffect(listening, runtime, lifecycleOwner) {
        if (!listening || runtime == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            petListenForBlow(context) { runtime.blow() }
        }
    }

    // 任务状态、浮岛位置或目标角色变化时，降帧等待中的帧循环立即推进。
    LaunchedEffect(
        runtime, target, islandBounds, inputs.phase, inputs.awaitingApproval, inputs.awaitingAnswer,
        inputs.contentLength, inputs.reasoningLength, videoEnabled,
    ) {
        runtime?.wake()
    }

    // 没有正在显示的角色：有目标就创建，功能已关闭就收起图层；不占用帧循环。
    LaunchedEffect(runtime, target, enabled) {
        if (runtime != null) return@LaunchedEffect
        if (target != null) shown = target else if (!enabled) latestOnGone()
    }

    LaunchedEffect(runtime, lifecycleOwner) {
        if (runtime == null) return@LaunchedEffect
        // 页面不在前台时停帧。
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = 0L
            var environmentCheckedAt = 0L
            var reducedMotion = false
            var powerSave = false
            var dueAt = 0L
            while (true) {
                // 按目标帧率排下一次推进：间隔较长时先挂起等待，再在到点后的第一帧推进；触摸、任务状态与浮岛变化立即唤醒。
                val fps = runtime.targetFps
                var woken = runtime.consumeWake()
                if (fps > 0 && !woken) {
                    val sleep = (dueAt - System.nanoTime()) / 1_000_000L - EARLY_WAKE_MILLIS
                    if (sleep > 0L) woken = runtime.awaitWake(sleep)
                }
                withFrameNanos { now ->
                    if (fps > 0 && !woken && previous != 0L && now < dueAt - FRAME_TOLERANCE_NANOS) return@withFrameNanos
                    val interval = if (fps > 0) 1_000_000_000L / fps else 0L
                    dueAt = now + interval
                    // 间隔按实际时长推进计时，跨度上限随目标帧率放宽；物理按不超过 1/30 秒的子步积分。
                    val elapsed = if (previous == 0L) 0f else (now - previous) / 1_000_000_000f
                    val dt = min(max(MAX_FRAME_SECONDS, interval * 2 / 1_000_000_000f), elapsed)
                    previous = now
                    if (now - environmentCheckedAt > 2_000_000_000L || environmentCheckedAt == 0L) {
                        environmentCheckedAt = now
                        reducedMotion = animationsRemoved(context)
                        powerSave = powerSaveMode(context)
                    }
                    val wanted = latestTarget
                    if (runtime.pet != wanted && !runtime.engine.gone) runtime.exit()
                    val island = latestIsland
                    val platform = if (island == Rect.Zero || layerWidth <= 0f) {
                        null
                    } else {
                        PetPlatform(
                            left = island.left - layerOrigin.x,
                            right = island.right - layerOrigin.x,
                            top = island.top - layerOrigin.y,
                            layerWidth = layerWidth,
                            layerHeight = layerHeight,
                        )
                    }
                    val current = latestInputs
                    frameDelta[0] = min(dt, MAX_FRAME_SECONDS)
                    val inputs = PetInputs(
                        phase = current.phase,
                        awaitingApproval = current.awaitingApproval,
                        awaitingAnswer = current.awaitingAnswer,
                        contentLength = current.contentLength,
                        reasoningLength = current.reasoningLength,
                        lineData = current.lineData,
                        // 省电模式与“移除动画”同样处理：不走动、不呼吸、不眨眼，站定后每半秒推进一次。
                        reducedMotion = reducedMotion || powerSave,
                        videoAllowed = latestVideoEnabled && !reducedMotion && !powerSave &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                        toolName = current.toolName,
                        tilt = if (latestMotion && !reducedMotion) motion.tilt else 0f,
                        flingEnabled = latestFling,
                        perchKind = latestPerchKind,
                    )
                    var left = dt
                    do {
                        val step = min(left, PHYSICS_STEP_SECONDS)
                        runtime.tick(dt = step, inputs = inputs, platform = platform, pxPerDp = density)
                        left -= step
                    } while (left > 0f)
                    runtime.publishFrame()
                    // 离场播完：换成目标角色；没有目标时等下一帧收起图层。
                    // 收起提示和闪光还在显示时先不回收。
                    if (runtime.engine.gone && runtime.pet != wanted && !runtime.lingering) shown = wanted
                    // 离场途中目标又变回当前角色（例如马上重新打开开关，或刚收起就被唤回）：重新入场。
                    // 刚被甩出收起的这一帧 wanted 还是旧值，以偏好的当前值为准，避免立刻重新入场。
                    if (runtime.engine.gone && runtime.pet == wanted && !preferences.dismissed.value) {
                        if (summonByShake[0]) {
                            summonByShake[0] = false
                            runtime.summon()
                        } else {
                            runtime.enter()
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // 独立图形层：宠物每帧重绘只重录这一层，不让父层连带重画会话列表。列表是毛玻璃的背景来源，
            // 跟着重画会让浮岛与顶栏在下一帧重做模糊。
            .graphicsLayer()
            .onGloballyPositioned {
                layerOrigin = it.positionInRoot()
                layerWidth = it.size.width.toFloat()
                layerHeight = it.size.height.toFloat()
            }
            .testTag(PET_LAYER_TAG),
    ) {
        if (runtime == null) return@Box
        Spacer(
            Modifier
                .fillMaxSize()
                .petDraw(runtime) { drawIntoCanvas { runtime.draw(it.nativeCanvas, frameDelta[0]) } },
        )
        val video = runtime.video
        if (video != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            key(video) { PetVideoLayer(runtime = runtime, request = video) }
        }
        if (runtime.bubbleTappable) PetPerchTarget(runtime)
        PetTouchTarget(runtime)
        PetBubble(runtime)
    }
}

/** 七彩泡泡的触摸区域：点一下戳破，宠物落回浮岛。只在宠物坐在泡泡上时存在。 */
@Composable
private fun PetPerchTarget(runtime: PetRuntime) {
    val density = LocalDensity.current.density
    Box(
        Modifier
            .petFollow(runtime) { measurable, _ ->
                val size = (PetPerchPainter.BUBBLE_RADIUS * 2f * density).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(size, size))
                val left = runtime.perchCenterX - size / 2f
                // 触摸区域从落座线下方开始，不盖住宠物自己的触摸区域。
                val top = runtime.perchSeatY + (PetPerchPainter.BUBBLE_DROP - PetPerchPainter.BUBBLE_RADIUS + 12f) * density
                layout(0, 0) { placeable.place(left.roundToInt(), top.roundToInt()) }
            }
            .clearAndSetSemantics { }
            .pointerInput(runtime) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var released = false
                    while (!released) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        if (change == null) return@awaitEachGesture
                        if (!change.pressed) released = true
                        change.consume()
                    }
                    runtime.popPerch()
                }
            },
    )
}

/**
 * 覆盖宠物身体的触摸区域：点按，按住或拖动提起，松手后落回、反弹或被甩出；
 * 在头部来回滑动是抚摸。
 */
@Composable
private fun PetTouchTarget(runtime: PetRuntime) {
    val minimum = with(LocalDensity.current) { 48.dp.toPx() }
    val density = LocalDensity.current.density
    val origin = remember { FloatArray(2) }
    Box(
        Modifier
            .petFollow(runtime) { measurable, _ ->
                val width = max(minimum, runtime.bodyWidth).roundToInt()
                val height = max(minimum, runtime.feetY - runtime.headTop).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                origin[0] = runtime.headX - width / 2f
                origin[1] = runtime.feetY - height
                layout(0, 0) { placeable.place(origin[0].roundToInt(), origin[1].roundToInt()) }
            }
            .clearAndSetSemantics { }
            .pointerInput(runtime) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!runtime.touchable) return@awaitEachGesture
                    down.consume()
                    val start = down.position
                    val startX = origin[0] + start.x
                    val startY = origin[1] + start.y
                    val onHead = runtime.isHead(startX, startY)
                    // 280 毫秒内抬起算点按；按住不放或移动超过触摸阈值算提起；从头部横向滑出算抚摸的开始。
                    val first = withTimeoutOrNull(HOLD_MILLIS) {
                        var decided = 0
                        while (decided == 0) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                decided = GESTURE_TAP
                            } else {
                                val moved = change.position - start
                                if (moved.getDistance() > viewConfiguration.touchSlop) {
                                    decided = if (onHead && abs(moved.x) > abs(moved.y) * 1.5f) GESTURE_STROKE else GESTURE_GRAB
                                }
                            }
                        }
                        decided
                    } ?: GESTURE_GRAB
                    if (first == GESTURE_TAP) {
                        runtime.tap(startX, startY)
                        return@awaitEachGesture
                    }
                    if (first == GESTURE_STROKE) {
                        // 抚摸：横向来回滑动，折返一次就算。滑出头部附近时改为提起；没有折返就抬起则什么都不做。
                        val limit = STROKE_LIMIT_DP * density
                        var direction = 0
                        var turnX = start.x
                        var stroked = false
                        var toGrab = false
                        while (!toGrab) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val moved = change.position - start
                            if (!stroked && (abs(moved.y) > limit || abs(moved.x) > limit * 2f)) {
                                toGrab = true
                                continue
                            }
                            if (direction == 0) direction = if (moved.x > 0f) 1 else -1
                            val along = (change.position.x - turnX) * direction
                            if (along > 0f) {
                                turnX = change.position.x
                            } else if (-along > viewConfiguration.touchSlop) {
                                direction = -direction
                                turnX = change.position.x
                                if (!stroked) {
                                    stroked = true
                                    runtime.stroke()
                                }
                            }
                        }
                        if (!toGrab) return@awaitEachGesture
                    }
                    if (!runtime.grab(startX, startY)) return@awaitEachGesture
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        runtime.dragTo(origin[0] + change.position.x, origin[1] + change.position.y)
                    }
                    runtime.release()
                }
            },
    )
}

/** 头顶的台词气泡：纯文本，跟随宠物移动并保持在图层内。 */
@Composable
private fun PetBubble(runtime: PetRuntime) {
    val text = runtime.bubble ?: return
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val edge = with(LocalDensity.current) { 8.dp.roundToPx() }
    val maxWidth = with(LocalDensity.current) { 184.dp.roundToPx() }
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 3.dp,
        modifier = Modifier.petFollow(runtime) { measurable, constraints ->
            val placeable = measurable.measure(Constraints(maxWidth = min(maxWidth, constraints.maxWidth)))
            val limit = max(edge, constraints.maxWidth - placeable.width - edge)
            val x = (runtime.headX - placeable.width / 2f).roundToInt().coerceIn(edge, limit)
            val lowest = max(edge, constraints.maxHeight - placeable.height - edge)
            val y = (runtime.headTop - placeable.height - gap).roundToInt().coerceIn(edge, lowest)
            layout(0, 0) { placeable.place(x, y) }
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
        )
    }
}

/** 系统“移除动画”（动画时长缩放为 0）是否开启。 */
private fun animationsRemoved(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

private fun powerSaveMode(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

private const val HOLD_MILLIS = 280L
private const val MAX_FRAME_SECONDS = 0.05f
private const val EARLY_WAKE_MILLIS = 12L
private const val FRAME_TOLERANCE_NANOS = 4_000_000L
private const val PHYSICS_STEP_SECONDS = 1f / 30f
private const val STROKE_LIMIT_DP = 36f
private const val GESTURE_TAP = 1
private const val GESTURE_GRAB = 2
private const val GESTURE_STROKE = 3
