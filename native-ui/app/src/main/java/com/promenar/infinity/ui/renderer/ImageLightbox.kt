package com.promenar.infinity.ui.renderer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.promenar.infinity.R
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.hypot

/** 查看器底部操作；提供时替代按地址重新下载图片的旧保存与分享。 */
data class ImageLightboxActions(
    val onSave: () -> Unit,
    val onShare: () -> Unit,
    val onOpenWith: (() -> Unit)? = null,
    /** 进画板标注这张图；为 null 时不显示。 */
    val onAnnotate: (() -> Unit)? = null,
)

/** 多图大图查看器的一页数据。 */
data class ImageLightboxPage(
    val imageUrl: String,
    val model: Any? = null,
    val title: String? = null,
    val subtitle: String? = null,
)

/** 大图查看器缩放、边界夹紧拖拽与 90° 旋转纯数学计算辅助（便于独立单测验证）。 */
internal object ImageLightboxGestureMath {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 5f
    const val DOUBLE_TAP_SCALE = 2.5f
    const val ZOOM_EPSILON = 1.02f

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    fun isZoomed(scale: Float): Boolean = scale > ZOOM_EPSILON

    fun nextQuarterTurns(quarterTurns: Int): Int = quarterTurns + 1

    fun displayDegrees(quarterTurns: Int): Int = (((quarterTurns % 4) + 4) % 4) * 90

    fun isTransformed(scale: Float, quarterTurns: Int): Boolean =
        isZoomed(scale) || displayDegrees(quarterTurns) != 0

    fun clampOffset(offset: Offset, viewportSize: IntSize, scale: Float): Offset {
        if (scale <= MIN_SCALE || viewportSize.width <= 0 || viewportSize.height <= 0) return Offset.Zero
        val maxX = ((scale - 1f) * viewportSize.width / 2f).coerceAtLeast(0f)
        val maxY = ((scale - 1f) * viewportSize.height / 2f).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    fun formatTransformSummary(scale: Float, quarterTurns: Int): String? {
        val deg = displayDegrees(quarterTurns).takeIf { it != 0 }?.let { "${it}°" }
        val zoom = scale.takeIf { isZoomed(it) }?.let { String.format(Locale.ROOT, "%.1f×", it) }
        val parts = listOfNotNull(deg, zoom)
        return if (parts.isEmpty()) null else parts.joinToString(" · ") // wrap-check: allow 顶部固定单行变换摘要标签
    }
}

/** 查看器缩放、旋转状态，供单张与多图看图复用。 */

private data class LightboxActionItem(
    val icon: ImageVector,
    val shortLabel: String,
    val description: String,
    val enabled: Boolean = true,
    val testTag: String? = null,
    val onClick: () -> Unit,
)

/**
 * 单张图片放大查看。[model] 为交给 Coil 的数据，缺省时使用 [imageUrl]。
 */
@Composable
fun ImageLightbox(
    imageUrl: String,
    onDismiss: () -> Unit,
    model: Any? = null,
    actions: ImageLightboxActions? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val page = remember(imageUrl, model) { ImageLightboxPage(imageUrl = imageUrl, model = model) }
    ImageLightboxGallery(
        pages = listOf(page),
        startIndex = 0,
        onDismiss = onDismiss,
        actionsForPage = actions?.let { fixed -> { fixed } },
        overlay = overlay?.let { content -> { _ -> content() } },
    )
}

/**
 * 全站统一多图大图查看器：
 * - 未放大态左右滑动切换上下张，双指捏合或双击平滑缩放（1×–5×），放大态锁定翻页并做边界夹紧拖拽；
 * - 移除触摸无级旋转，改为底部按钮每次顺时针 90° 旋转，并提供一键复位与顶栏状态提示；
 * - 宽屏（>=600dp）保留左右辅助翻页按钮，360dp 大字号下工具栏自适应纯图标单行展示。
 */
@Composable
fun ImageLightboxGallery(
    pages: List<ImageLightboxPage>,
    startIndex: Int = 0,
    onDismiss: () -> Unit,
    actionsForPage: ((Int) -> ImageLightboxActions?)? = null,
    onPageChanged: ((Int) -> Unit)? = null,
    overlay: (@Composable BoxScope.(Int) -> Unit)? = null,
) {
    if (pages.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }

    val safeStart = startIndex.coerceIn(0, pages.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeStart, pageCount = { pages.size })
    val currentIndex = pagerState.currentPage.coerceIn(0, pages.lastIndex)
    val currentPage = pages[currentIndex]

    LaunchedEffect(currentIndex) {
        onPageChanged?.invoke(currentIndex)
    }

    var currentScale by remember { mutableFloatStateOf(ImageLightboxGestureMath.MIN_SCALE) }
    var currentOffset by remember { mutableStateOf(Offset.Zero) }
    var quarterTurns by remember { mutableIntStateOf(0) }
    var showChrome by remember { mutableStateOf(true) }
    var resetSignal by remember { mutableIntStateOf(0) }

    val resetTransform: () -> Unit = {
        currentScale = ImageLightboxGestureMath.MIN_SCALE
        currentOffset = Offset.Zero
        quarterTurns = 0
        resetSignal++
    }

    LaunchedEffect(currentIndex) {
        resetTransform()
    }

    val currentActions = actionsForPage?.invoke(currentIndex)
    val isZoomed = ImageLightboxGestureMath.isZoomed(currentScale)
    val transformSummary = ImageLightboxGestureMath.formatTransformSummary(currentScale, quarterTurns)
    val resetEnabled = ImageLightboxGestureMath.isTransformed(currentScale, quarterTurns)
    val compactLabels = with(LocalDensity.current) { fontScale >= 1.25f }

    val animatedRotation by animateFloatAsState(
        targetValue = quarterTurns * 90f,
        animationSpec = if (reduceMotion) snap() else tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate),
        label = "lightbox_rotation",
    )

    val scheme = MaterialTheme.colorScheme
    val overlayTint = MaterialTheme.infinityDomainColors.overlayContent
    val pillSurface = scheme.scrim.copy(alpha = 0.6f)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        com.promenar.infinity.ui.common.ReportInfinityNoticeTopOffset(12.dp)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.scrim.copy(alpha = 0.96f)),
        ) {
            val isWide = maxWidth >= 600.dp

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !isZoomed,
                modifier = Modifier.fillMaxSize(),
            ) { pageIndex ->
                val pageItem = pages[pageIndex]
                val isActive = pageIndex == currentIndex
                LightboxZoomablePage(
                    page = pageItem,
                    isActive = isActive,
                    rotationDegrees = if (isActive) animatedRotation else 0f,
                    resetSignal = resetSignal,
                    reduceMotion = reduceMotion,
                    onTap = { showChrome = !showChrome },
                    onTransformChanged = { nextScale, nextOffset ->
                        if (isActive) {
                            currentScale = nextScale
                            currentOffset = nextOffset
                        }
                    },
                )
            }

            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(if (reduceMotion) snap() else tween(InfinityMotion.DurationShort)),
                exit = fadeOut(if (reduceMotion) snap() else tween(InfinityMotion.DurationShort)),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                LightboxTopBar(
                    currentIndex = currentIndex,
                    pageCount = pages.size,
                    title = currentPage.title,
                    subtitle = currentPage.subtitle,
                    transformSummary = transformSummary,
                    onDismiss = onDismiss,
                )
            }

            if (isWide && pages.size > 1) {
                AnimatedVisibility(
                    visible = showChrome && currentIndex > 0,
                    enter = fadeIn(if (reduceMotion) snap() else tween(InfinityMotion.DurationShort)),
                    exit = fadeOut(if (reduceMotion) snap() else tween(InfinityMotion.DurationShort)),
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage((currentIndex - 1).coerceAtLeast(0)) } },
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = pillSurface,
                            contentColor = overlayTint,
                        ),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = stringResource(R.string.media_action_previous))
                    }
                }
                AnimatedVisibility(
                    visible = showChrome && currentIndex < pages.lastIndex,
                    enter = fadeIn(if (reduceMotion) snap() else tween(InfinityMotion.DurationShort)),
                    exit = fadeOut(if (reduceMotion) snap() else tween(InfinityMotion.DurationShort)),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage((currentIndex + 1).coerceAtMost(pages.lastIndex)) } },
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = pillSurface,
                            contentColor = overlayTint,
                        ),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(R.string.media_action_next))
                    }
                }
            }

            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(if (reduceMotion) snap() else tween(InfinityMotion.DurationShort)),
                exit = fadeOut(if (reduceMotion) snap() else tween(InfinityMotion.DurationShort)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val actionItems = buildList {
                    add(
                        LightboxActionItem(
                            icon = Icons.Rounded.RestartAlt,
                            shortLabel = stringResource(R.string.media_lightbox_action_reset),
                            description = stringResource(R.string.media_action_reset_zoom),
                            enabled = resetEnabled,
                            onClick = resetTransform,
                        ),
                    )
                    add(
                        LightboxActionItem(
                            icon = Icons.Rounded.RotateRight,
                            shortLabel = stringResource(R.string.media_lightbox_action_rotate),
                            description = stringResource(R.string.media_action_rotate),
                            onClick = { quarterTurns = ImageLightboxGestureMath.nextQuarterTurns(quarterTurns) },
                        ),
                    )
                    add(
                        LightboxActionItem(
                            icon = Icons.Rounded.SaveAlt,
                            shortLabel = stringResource(R.string.media_lightbox_action_save),
                            description = stringResource(R.string.media_action_save),
                            onClick = {
                                if (currentActions != null) currentActions.onSave()
                                else scope.launch { saveImageToGallery(context, currentPage.imageUrl) }
                            },
                        ),
                    )
                    add(
                        LightboxActionItem(
                            icon = Icons.Rounded.Share,
                            shortLabel = stringResource(R.string.media_lightbox_action_share),
                            description = stringResource(R.string.media_action_share),
                            onClick = {
                                if (currentActions != null) currentActions.onShare()
                                else scope.launch { shareImage(context, currentPage.imageUrl) }
                            },
                        ),
                    )
                    currentActions?.onAnnotate?.let { annotate ->
                        add(
                            LightboxActionItem(
                                icon = Icons.Rounded.Draw,
                                shortLabel = stringResource(R.string.media_lightbox_action_annotate),
                                description = stringResource(R.string.annotation_cd_edit_image),
                                testTag = UiTags.IMAGE_LIGHTBOX_ANNOTATE,
                                onClick = annotate,
                            ),
                        )
                    }
                    currentActions?.onOpenWith?.let { openWith ->
                        add(
                            LightboxActionItem(
                                icon = Icons.Rounded.OpenInNew,
                                shortLabel = stringResource(R.string.media_lightbox_action_open_with),
                                description = stringResource(R.string.media_action_open_with),
                                onClick = openWith,
                            ),
                        )
                    }
                }
                LightboxBottomBar(
                    currentIndex = currentIndex,
                    pageCount = pages.size,
                    actions = actionItems,
                    compactLabels = compactLabels,
                )
            }

            overlay?.invoke(this, currentIndex)
        }
    }
}

@Composable
private fun LightboxZoomablePage(
    page: ImageLightboxPage,
    isActive: Boolean,
    rotationDegrees: Float,
    resetSignal: Int,
    reduceMotion: Boolean,
    onTap: () -> Unit,
    onTransformChanged: (Float, Offset) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewConfig = LocalViewConfiguration.current
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(page.model ?: page.imageUrl) { mutableFloatStateOf(ImageLightboxGestureMath.MIN_SCALE) }
    var offset by remember(page.model ?: page.imageUrl) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(isActive, resetSignal) {
        if (!isActive || resetSignal > 0) {
            scale = ImageLightboxGestureMath.MIN_SCALE
            offset = Offset.Zero
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
            .pointerInput(isActive, viewportSize) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (!isActive) return@detectTapGestures
                        val targetScale = if (ImageLightboxGestureMath.isZoomed(scale)) {
                            ImageLightboxGestureMath.MIN_SCALE
                        } else {
                            ImageLightboxGestureMath.DOUBLE_TAP_SCALE
                        }
                        val targetOffset = if (targetScale <= ImageLightboxGestureMath.MIN_SCALE || viewportSize.width <= 0 || viewportSize.height <= 0) {
                            Offset.Zero
                        } else {
                            val center = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
                            val desired = (center - tap) * (targetScale - 1f)
                            ImageLightboxGestureMath.clampOffset(desired, viewportSize, targetScale)
                        }
                        if (reduceMotion) {
                            scale = targetScale
                            offset = targetOffset
                            onTransformChanged(scale, offset)
                        } else {
                            val startScale = scale
                            val startOffset = offset
                            scope.launch {
                                val progress = Animatable(0f)
                                progress.animateTo(1f, tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)) {
                                    val t = value
                                    scale = startScale + (targetScale - startScale) * t
                                    offset = Offset(
                                        startOffset.x + (targetOffset.x - startOffset.x) * t,
                                        startOffset.y + (targetOffset.y - startOffset.y) * t,
                                    )
                                    onTransformChanged(scale, offset)
                                }
                            }
                        }
                    },
                )
            }
            .pointerInput(isActive, viewportSize) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var passedSlop = false
                    var accumulatedPan = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount == 0) break
                        if (!isActive) continue
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (pressedCount >= 2) {
                            // 双指捏合只响应缩放与跟随平移，绝不响应旋转。
                            scale = ImageLightboxGestureMath.clampScale(scale * zoomChange)
                            offset = ImageLightboxGestureMath.clampOffset(offset + panChange, viewportSize, scale)
                            onTransformChanged(scale, offset)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            passedSlop = true
                        } else if (ImageLightboxGestureMath.isZoomed(scale)) {
                            if (!passedSlop) {
                                accumulatedPan += panChange
                                if (hypot(accumulatedPan.x.toDouble(), accumulatedPan.y.toDouble()) >= viewConfig.touchSlop) {
                                    passedSlop = true
                                }
                            }
                            if (passedSlop && panChange != Offset.Zero) {
                                offset = ImageLightboxGestureMath.clampOffset(offset + panChange, viewportSize, scale)
                                onTransformChanged(scale, offset)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                        // 未放大且单指触摸：不消费事件，完全交还外层 HorizontalPager 翻页。
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = page.model ?: page.imageUrl,
            contentDescription = page.title,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    rotationZ = rotationDegrees
                },
        )
    }
}

@Composable
private fun LightboxTopBar(
    currentIndex: Int,
    pageCount: Int,
    title: String?,
    subtitle: String?,
    transformSummary: String?,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val overlayTint = MaterialTheme.infinityDomainColors.overlayContent
    val pillSurface = scheme.scrim.copy(alpha = 0.6f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(scheme.scrim.copy(alpha = 0.85f), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (pageCount > 1) {
                Surface(shape = CircleShape, color = pillSurface) {
                    Text(
                        text = stringResource(R.string.media_carousel_page, currentIndex + 1, pageCount),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = overlayTint,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                title?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = overlayTint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = overlayTint.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            transformSummary?.let { summary ->
                Surface(
                    shape = CircleShape,
                    color = scheme.primaryContainer.copy(alpha = 0.92f),
                ) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onPrimaryContainer,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            FilledTonalIconButton(
                onClick = onDismiss,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = pillSurface,
                    contentColor = overlayTint,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.media_action_close),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun LightboxBottomBar(
    currentIndex: Int,
    pageCount: Int,
    actions: List<LightboxActionItem>,
    compactLabels: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val overlayTint = MaterialTheme.infinityDomainColors.overlayContent
    val toolbarSurface = scheme.scrim.copy(alpha = 0.82f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, scheme.scrim.copy(alpha = 0.86f))))
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (pageCount > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dotCount = pageCount.coerceAtMost(9)
                val mappedActive = if (pageCount <= 9) currentIndex else (currentIndex * (dotCount - 1) / (pageCount - 1).coerceAtLeast(1))
                for (i in 0 until dotCount) {
                    val active = i == mappedActive
                    Box(
                        Modifier
                            .height(5.dp)
                            .width(if (active) 16.dp else 5.dp)
                            .clip(CircleShape)
                            .background(if (active) overlayTint else overlayTint.copy(alpha = 0.38f)),
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = toolbarSurface,
            border = BorderStroke(0.5.dp, overlayTint.copy(alpha = 0.16f)),
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = if (compactLabels) 6.dp else 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEach { item ->
                    val tint = if (item.enabled) overlayTint else overlayTint.copy(alpha = 0.38f)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        onClick = item.onClick,
                        enabled = item.enabled,
                        modifier = Modifier.weight(1f).let { mod -> item.testTag?.let { mod.testTag(it) } ?: mod },
                    ) {
                        Column(
                            Modifier.padding(horizontal = 2.dp, vertical = if (compactLabels) 8.dp else 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.description,
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                            if (!compactLabels) {
                                Text(
                                    text = item.shortLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tint,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun loadImageBitmap(context: Context, url: String): Bitmap? {
    return withContext(Dispatchers.IO) {
        try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .build()
            val result = loader.execute(request)
            result.image?.toBitmap()
        } catch (_: Exception) {
            null
        }
    }
}

private suspend fun saveImageToGallery(context: Context, imageUrl: String) {
    val bitmap = loadImageBitmap(context, imageUrl)
    if (bitmap == null) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
        }
        return
    }

    withContext(Dispatchers.IO) {
        val filename = "infinity_${System.currentTimeMillis()}.png"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Infinity Deck",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Saved to Pictures/Infinity Deck", Toast.LENGTH_SHORT).show()
            }
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to save", Toast.LENGTH_SHORT).show()
            }
        }
        bitmap.recycle()
    }
}

private suspend fun shareImage(context: Context, imageUrl: String) {
    val bitmap = loadImageBitmap(context, imageUrl)
    if (bitmap == null) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
        }
        return
    }

    withContext(Dispatchers.IO) {
        val filename = "infinity_share_${System.currentTimeMillis()}.png"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Infinity Deck",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(
                    Intent.createChooser(shareIntent, "Share image")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        } else {
            bitmap.recycle()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to share", Toast.LENGTH_SHORT).show()
            }
            return@withContext
        }
        bitmap.recycle()
    }
}
