package com.promenar.infinity.ui.avatar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "头像编辑器 深色中文 411 拖动网格", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Composable
fun avatarEditorDarkChinesePreview() = AvatarEditorPreview(InfinityThemeMode.DARK, dragging = true) { geometry ->
    geometry.pan(geometry.withZoom(geometry.initialState(), 1.4f), 0f, 0.08f)
}

@PreviewTest
@Preview(name = "Avatar editor light EN 411", widthDp = 411, heightDp = 914, locale = "en")
@Composable
fun avatarEditorLightEnglishPreview() = AvatarEditorPreview(InfinityThemeMode.LIGHT) { geometry ->
    geometry.mirrorHorizontally(geometry.rotateClockwise(geometry.initialState()))
}

@PreviewTest
@Preview(name = "Avatar editor light EN 360 font130", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Preview(name = "头像编辑器 浅色中文 360 大字号200", widthDp = 360, heightDp = 1000, locale = "zh-rCN", fontScale = 2f)
@Composable
fun avatarEditorLargeFontPreview() = AvatarEditorPreview(InfinityThemeMode.LIGHT) { it.initialState() }

@Composable
private fun AvatarEditorPreview(
    mode: InfinityThemeMode,
    dragging: Boolean = false,
    state: (AvatarCropGeometry) -> AvatarCropState,
) {
    val image = remember { sampleImage() }
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        AvatarEditorContent(
            image = image,
            state = state(image.geometry),
            saving = false,
            actions = AvatarEditorActions(onClose = {}, onDone = {}, onChange = {}, onReplace = {}),
            initiallyDragging = dragging,
        )
    }
}

/** 600×800 的示例人像：渐变天空、太阳、远山与简化人脸，直接绘制，不依赖图片解码。 */
private fun sampleImage(): AvatarEditorImage {
    val width = 600
    val height = 800
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    Canvas(bitmap).apply {
        paint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(0xFFF7B267.toInt(), 0xFFF4845F.toInt(), 0xFF7B3F61.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = 0xE6FFE8B8.toInt()
        drawCircle(430f, 220f, 74f, paint)
        paint.color = 0xFF5A2F4E.toInt()
        drawPath(
            Path().apply {
                moveTo(0f, 547f)
                quadTo(147f, 467f, 280f, 527f)
                quadTo(413f, 587f, 600f, 507f)
                lineTo(600f, 800f)
                lineTo(0f, 800f)
                close()
            },
            paint,
        )
        paint.color = 0xFF2D1E3E.toInt()
        drawOval(RectF(87f, 633f, 487f, 940f), paint)
        paint.color = 0xFFE8B48F.toInt()
        drawRoundRect(RectF(260f, 547f, 313f, 627f), 20f, 20f, paint)
        paint.color = 0xFFF2C39F.toInt()
        drawOval(RectF(187f, 347f, 387f, 587f), paint)
        paint.color = 0xFF2B1B2F.toInt()
        drawArc(RectF(180f, 327f, 393f, 467f), 180f, 180f, true, paint)
        drawCircle(252f, 477f, 8f, paint)
        drawCircle(321f, 477f, 8f, paint)
        paint.color = 0xFFA5574A.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.strokeCap = Paint.Cap.ROUND
        drawArc(RectF(262f, 505f, 312f, 540f), 20f, 140f, false, paint)
    }
    return AvatarEditorImage(bitmap.asImageBitmap(), AvatarCropGeometry(width, height))
}
