package com.promenar.infinity.ui.pet

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.pet.InstalledPet
import com.promenar.infinity.domain.generation.GenerationPhase
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val PET_LAYER_TAG = "pet_layer"

/**
 * 会话页的宠物图层：叠在聊天区域之上，宠物站在输入框浮岛的上沿。
 *
 * 只有宠物响应界面：图层不修改浮岛，也不拦截宠物以外区域的触摸。宠物默认关闭，
 * 未启用且没有正在离场的角色时不创建任何内容。
 *
 * @param islandBounds 浮岛在根坐标系中的范围；尚未测量时为 [Rect.Zero]。
 * @param modelId 当前会话使用的模型，用于按模型关联切换角色。
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
        inputs = PetLayerInputs(phase, awaitingApproval, awaitingAnswer, contentLength, reasoningLength, lineData),
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
    val target = if (enabled) resolveActivePet(pets, defaultPetId, modelPets, modelId) else null

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current.density
    var shown by remember { mutableStateOf<InstalledPet?>(null) }
    val runtime = remember(shown) {
        shown?.let { PetRuntime(it, scope, application.petLineGenerator) }
    }
    DisposableEffect(runtime) {
        onDispose { runtime?.dispose() }
    }

    var layerOrigin by remember { mutableStateOf(Offset.Zero) }
    var layerWidth by remember { mutableStateOf(0f) }
    val frameDelta = remember { FloatArray(1) }
    val latestTarget by rememberUpdatedState(target)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestInputs by rememberUpdatedState(inputs)
    val latestIsland by rememberUpdatedState(islandBounds)
    val latestVideoEnabled by rememberUpdatedState(videoEnabled)
    val latestOnGone by rememberUpdatedState(onGone)
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(runtime, lifecycleOwner) {
        // 页面不在前台时停帧。
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = 0L
            var environmentCheckedAt = 0L
            var reducedMotion = false
            var powerSave = false
            while (true) {
                withFrameNanos { now ->
                    val dt = if (previous == 0L) 0f else min(0.05f, (now - previous) / 1_000_000_000f)
                    previous = now
                    if (now - environmentCheckedAt > 2_000_000_000L || environmentCheckedAt == 0L) {
                        environmentCheckedAt = now
                        reducedMotion = animationsRemoved(context)
                        powerSave = powerSaveMode(context)
                    }
                    val wanted = latestTarget
                    if (runtime == null) {
                        if (wanted != null) shown = wanted else if (!latestEnabled) latestOnGone()
                        return@withFrameNanos
                    }
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
                        )
                    }
                    val current = latestInputs
                    frameDelta[0] = dt
                    runtime.tick(
                        dt = dt,
                        inputs = PetInputs(
                            phase = current.phase,
                            awaitingApproval = current.awaitingApproval,
                            awaitingAnswer = current.awaitingAnswer,
                            contentLength = current.contentLength,
                            reasoningLength = current.reasoningLength,
                            lineData = current.lineData,
                            reducedMotion = reducedMotion,
                            videoAllowed = latestVideoEnabled && !reducedMotion && !powerSave &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                        ),
                        platform = platform,
                        pxPerDp = density,
                    )
                    // 离场播完：换成目标角色；没有目标时等下一帧收起图层。
                    if (runtime.engine.gone && runtime.pet != wanted) shown = wanted
                    if (runtime.engine.gone && wanted == null) shown = null
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                layerOrigin = it.positionInRoot()
                layerWidth = it.size.width.toFloat()
            }
            .testTag(PET_LAYER_TAG),
    ) {
        if (runtime == null) return@Box
        Canvas(Modifier.fillMaxSize()) {
            // 读取帧计数，让每帧只重绘、不重组。
            runtime.frame
            drawIntoCanvas { runtime.draw(it.nativeCanvas, frameDelta[0]) }
        }
        val video = runtime.video
        if (video != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PetVideoLayer(runtime = runtime, request = video)
        }
        PetTouchTarget(runtime)
        PetBubble(runtime)
    }
}

/** 覆盖宠物身体的触摸区域：点按摸头，按住或拖动提起，松手落回。 */
@Composable
private fun PetTouchTarget(runtime: PetRuntime) {
    val minimum = with(LocalDensity.current) { 48.dp.toPx() }
    val origin = remember { FloatArray(2) }
    Box(
        Modifier
            .layout { measurable, _ ->
                runtime.frame
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
                    down.consume()
                    val start = down.position
                    // 280 毫秒内抬起算点按；按住不放或移动超过触摸阈值算提起。
                    val quick = withTimeoutOrNull(HOLD_MILLIS) {
                        var released: Boolean? = null
                        while (released == null) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                released = true
                            } else if ((change.position - start).getDistance() > viewConfiguration.touchSlop) {
                                released = false
                            }
                        }
                        released
                    }
                    if (quick == true) {
                        runtime.tap()
                        return@awaitEachGesture
                    }
                    if (!runtime.grab(origin[0] + start.x, origin[1] + start.y)) return@awaitEachGesture
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
        modifier = Modifier.layout { measurable, constraints ->
            runtime.frame
            val placeable = measurable.measure(Constraints(maxWidth = min(maxWidth, constraints.maxWidth)))
            val limit = max(edge, constraints.maxWidth - placeable.width - edge)
            val x = (runtime.headX - placeable.width / 2f).roundToInt().coerceIn(edge, limit)
            val y = max(edge, (runtime.headTop - placeable.height - gap).roundToInt())
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
