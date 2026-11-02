package com.promenar.infinity.ui.settings

import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinityPureBlack
import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import kotlin.math.max
import kotlin.math.min
import com.promenar.infinity.ui.theme.forInput

/** 取色器里的颜色：色相 0..360，饱和度与明度 0..1。 */
internal data class HsvColor(val hue: Float, val saturation: Float, val value: Float) {
    fun toArgb(): Int = Color.hsv(hue.mod(360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f)).toArgb()

    companion object {
        fun fromArgb(argb: Int): HsvColor {
            val r = (argb shr 16 and 0xFF) / 255f
            val g = (argb shr 8 and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val maxC = max(r, max(g, b))
            val delta = maxC - min(r, min(g, b))
            val hue = when {
                delta < 1e-4f -> 0f
                maxC == r -> 60f * ((g - b) / delta).mod(6f)
                maxC == g -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            }
            return HsvColor(hue, if (maxC <= 0f) 0f else delta / maxC, maxC)
        }
    }
}

/** 解析 `#RRGGBB`、`RRGGBB` 或三位简写 `#RGB`，返回不透明的 ARGB；无法解析时返回 null。 */
internal fun parseHexColor(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    val full = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6 -> hex
        else -> return null
    }
    if (!full.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return (0xFF000000.toInt()) or full.toInt(16)
}

internal fun formatHexColor(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/**
 * 自定义主题色：饱和度/明度方块 + 色相条 + 十六进制输入 + 最近使用。
 * 选中的颜色作为种子按当前配色风格生成整套配色；点“应用”后才写入设置。
 */
@Composable
internal fun ThemeColorPickerSheet(
    show: Boolean,
    initialArgb: Int,
    recentColors: List<Int>,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    InfinityBottomSheet(show = show, title = stringResource(R.string.theme_custom_color_title), onDismiss = onDismiss, scrollContent = true) {
        var hsv by remember(show, initialArgb) { mutableStateOf(HsvColor.fromArgb(initialArgb)) }
        var hexText by remember(show, initialArgb) { mutableStateOf(formatHexColor(initialArgb)) }
        val argb = hsv.toArgb()
        // 拖动色盘时同步十六进制文本；手动输入时只在解析成功后改色盘，不打断输入。
        LaunchedEffect(argb) { if (parseHexColor(hexText) != argb) hexText = formatHexColor(argb) }
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SaturationValuePlane(hsv, onChange = { hsv = it })
            HueBar(hsv.hue, onChange = { hsv = hsv.copy(hue = it) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(argb))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { text ->
                        hexText = text.take(7)
                        parseHexColor(text)?.let { hsv = HsvColor.fromArgb(it) }
                    },
                    label = { Text(stringResource(R.string.theme_custom_color_hex)) },
                    singleLine = true,
                    isError = parseHexColor(hexText) == null,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = InfinityMonospace).forInput(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onApply(argb) }),
                    modifier = Modifier.weight(1f).testTag("theme_custom_color_hex"),
                )
            }
            if (recentColors.isNotEmpty()) {
                Text(
                    stringResource(R.string.theme_custom_color_recent),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentColors.forEach { color ->
                        val label = stringResource(R.string.settings_batch1_color_description, "%06X".format(color and 0xFFFFFF))
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(Color(color))
                                .semantics { contentDescription = label }
                                .selectable(selected = color == argb, role = Role.RadioButton) { hsv = HsvColor.fromArgb(color) }
                                .then(if (color == argb) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                        )
                    }
                }
            }
            InfinityPillRow(Modifier.fillMaxWidth()) {
                InfinityPillButton(text = stringResource(R.string.common_cancel), onClick = onDismiss)
                InfinityPillButton(
                    text = stringResource(R.string.theme_custom_color_apply),
                    onClick = { onApply(argb) },
                    tone = InfinityPillTone.PRIMARY,
                    modifier = Modifier.testTag("theme_custom_color_apply"),
                )
            }
        }
    }
}

/** 饱和度（横向）× 明度（纵向）方块：按下或拖动即取色。 */
@Composable
private fun SaturationValuePlane(hsv: HsvColor, onChange: (HsvColor) -> Unit) {
    val haptics = LocalInfinityHaptics.current
    val pure = Color.hsv(hsv.hue.mod(360f), 1f, 1f)
    val description = stringResource(R.string.theme_custom_color_plane_cd)
    val latest = remember { mutableStateOf(hsv) }
    latest.value = hsv
    Canvas(
        Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                fun pick(position: Offset) {
                    val s = (position.x / size.width).coerceIn(0f, 1f)
                    val v = 1f - (position.y / size.height).coerceIn(0f, 1f)
                    onChange(latest.value.copy(saturation = s, value = v))
                }
                // 按下与抬起都要消费：宽窗口里面板是居中对话框，外层点按会被当成点外部而关闭。
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    haptics.select(false)
                    pick(down.position)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        pick(change.position)
                    }
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(InfinityPureWhite, pure)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, InfinityPureBlack)))
        val center = Offset(hsv.saturation * size.width, (1 - hsv.value) * size.height)
        drawCircle(InfinityPureWhite, radius = 10.dp.toPx(), center = center, style = Stroke(2.5.dp.toPx()))
        drawCircle(InfinityPureBlack.copy(alpha = 0.35f), radius = 11.5.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
    }
}

/** 色相条：按下或拖动选色相。 */
@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val rainbow = remember { (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) } }
    val description = stringResource(R.string.theme_custom_color_hue_cd)
    Canvas(
        Modifier.fillMaxWidth().height(28.dp)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    onChange((down.position.x / size.width).coerceIn(0f, 1f) * 360f)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        onChange((change.position.x / size.width).coerceIn(0f, 1f) * 360f)
                    }
                }
            },
    ) {
        val radius = size.height / 2
        drawRoundRect(Brush.horizontalGradient(rainbow), cornerRadius = CornerRadius(radius))
        val x = (hue.mod(360f) / 360f * size.width).coerceIn(radius, size.width - radius)
        drawCircle(Color.hsv(hue.mod(360f), 1f, 1f), radius = radius - 3.dp.toPx(), center = Offset(x, radius))
        drawCircle(InfinityPureWhite, radius = radius - 2.dp.toPx(), center = Offset(x, radius), style = Stroke(2.5.dp.toPx()))
    }
}
