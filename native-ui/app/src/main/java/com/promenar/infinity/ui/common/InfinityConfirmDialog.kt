package com.promenar.infinity.ui.common

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.InfinityShapes
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlinx.coroutines.launch

/**
 * 全站唯一的对话框：确认、提示与单字段输入都用它，自带窗口，不再外包 `Dialog`。
 *
 * 点外部与返回调用 [onDismissRequest]（默认同取消）；[content] 放在正文与按钮之间，用于输入框或补充说明。
 * [cancelText] 为 null 时只有确认按钮（纯提示）；[title] 为空时只显示正文。
 * [icon] 显示在标题上方；危险操作未指定时用警示图标。选择列表不放进对话框，用贴着触发行的 [InfinityDropdownMenu]。
 *
 * 窗口背后的界面变暗并虚化（Android 12 起，系统支持跨窗口模糊时），卡片以弹性放大淡入。
 */
@Composable
fun InfinityConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    cancelText: String? = stringResource(R.string.common_btn_cancel),
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    isDestructive: Boolean = false,
    confirmButtonModifier: Modifier = Modifier,
    cancelButtonModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
    onDismissRequest: () -> Unit = onCancel,
    properties: DialogProperties = DialogProperties(),
    icon: ImageVector? = null,
    content: @Composable (() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        DialogWindowBackdrop()
        // 预览与截图只渲染首帧，直接从终态开始。
        val settled = LocalInspectionMode.current
        val alpha = remember { Animatable(if (settled) 1f else 0f) }
        val scale = remember { Animatable(if (settled) 1f else DIALOG_ENTER_SCALE) }
        LaunchedEffect(Unit) {
            launch { alpha.animateTo(1f, tween(DIALOG_FADE_MILLIS)) }
            launch { scale.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) }
        }
        InfinityConfirmDialogCard(
            title = title,
            message = message,
            confirmText = confirmText,
            cancelText = cancelText,
            onConfirm = onConfirm,
            onCancel = onCancel,
            isDestructive = isDestructive,
            confirmButtonModifier = confirmButtonModifier,
            cancelButtonModifier = cancelButtonModifier,
            modifier = modifier.graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            },
            confirmEnabled = confirmEnabled,
            cancelEnabled = cancelEnabled,
            icon = icon,
            content = content,
        )
    }
}

/** 对话框窗口背后的界面：加深遮罩，系统支持时再虚化。 */
@Composable
private fun DialogWindowBackdrop() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    val radius = with(LocalDensity.current) { DIALOG_BLUR_BEHIND.roundToPx() }
    SideEffect {
        window.setDimAmount(DIALOG_DIM_AMOUNT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = radius }
        }
    }
}

/** 对话框卡片本身，不含窗口；截图测试与预览直接渲染它。 */
@Composable
internal fun InfinityConfirmDialogCard(
    title: String,
    message: String,
    confirmText: String,
    cancelText: String? = stringResource(R.string.common_btn_cancel),
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    isDestructive: Boolean = false,
    confirmButtonModifier: Modifier = Modifier,
    cancelButtonModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
    /** 多段元数据正文（如“3 个文件 · 1.2 MB”）：非空时以 [InfinityMetaLine] 排版并取代 [message]。 */
    messageSegments: List<String>? = null,
    icon: ImageVector? = null,
    content: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (isDestructive) scheme.error else scheme.primary
    val badge = icon ?: Icons.Rounded.WarningAmber.takeIf { isDestructive }
    Surface(
        modifier = modifier
            .clip(InfinityShapes.extraLarge)
            .border(0.5.dp, scheme.outlineVariant, InfinityShapes.extraLarge),
        color = scheme.surfaceContainerHigh.copy(alpha = DIALOG_CARD_ALPHA),
        shape = InfinityShapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
        ) {
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(accent.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(badge, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // 纯提示可以没有标题，此时正文直接在顶部。
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = InfinityTypography.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                )
            }

            if (!messageSegments.isNullOrEmpty()) {
                if (title.isNotBlank()) Spacer(modifier = Modifier.height(6.dp))
                InfinityMetaLine(
                    segments = messageSegments,
                    style = InfinityTypography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            } else if (message.isNotEmpty()) {
                if (title.isNotBlank()) Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = message,
                    style = InfinityTypography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }

            if (content != null) {
                Spacer(modifier = Modifier.height(14.dp))
                content()
            }

            Spacer(modifier = Modifier.height(20.dp))

            DialogButtons(
                confirmText = confirmText,
                cancelText = cancelText,
                onConfirm = onConfirm,
                onCancel = onCancel,
                isDestructive = isDestructive,
                confirmEnabled = confirmEnabled,
                cancelEnabled = cancelEnabled,
                confirmButtonModifier = confirmButtonModifier,
                cancelButtonModifier = cancelButtonModifier,
            )
        }
    }
}

/**
 * 按钮组：取消在左、确认在右，两个等宽胶囊并排。任一文字在半宽内单行放不下时整组竖排，确认在上。
 * 只有确认时占满整行。
 */
@Composable
private fun DialogButtons(
    confirmText: String,
    cancelText: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    isDestructive: Boolean,
    confirmEnabled: Boolean,
    cancelEnabled: Boolean,
    confirmButtonModifier: Modifier,
    cancelButtonModifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val style = InfinityTypography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val half = (maxWidth - DIALOG_BUTTON_GAP) / 2 - DIALOG_BUTTON_PADDING * 2
        val widest = with(LocalDensity.current) {
            listOfNotNull(confirmText, cancelText).maxOf { measurer.measure(it, style, maxLines = 1).size.width }.toDp()
        }
        val stacked = cancelText != null && widest > half
        val confirm: @Composable (Modifier) -> Unit = { slot ->
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled,
                modifier = slot.height(DIALOG_BUTTON_HEIGHT).then(confirmButtonModifier),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDestructive) scheme.error else scheme.primary,
                    contentColor = if (isDestructive) scheme.onError else scheme.onPrimary,
                ),
                shape = CircleShape,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DIALOG_BUTTON_PADDING),
            ) {
                Text(confirmText, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val cancel: @Composable (Modifier) -> Unit = { slot ->
            if (cancelText != null) {
                Button(
                    onClick = onCancel,
                    enabled = cancelEnabled,
                    modifier = slot.height(DIALOG_BUTTON_HEIGHT).then(cancelButtonModifier),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.onSurface.copy(alpha = 0.08f),
                        contentColor = scheme.onSurface,
                    ),
                    shape = CircleShape,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DIALOG_BUTTON_PADDING),
                ) {
                    Text(cancelText, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(DIALOG_BUTTON_GAP)) {
                confirm(Modifier.fillMaxWidth())
                cancel(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(DIALOG_BUTTON_GAP)) {
                cancel(Modifier.weight(1f))
                confirm(Modifier.weight(1f))
            }
        }
    }
}

private const val DIALOG_ENTER_SCALE = 0.92f
private const val DIALOG_FADE_MILLIS = 120
private const val DIALOG_DIM_AMOUNT = 0.56f
private const val DIALOG_CARD_ALPHA = 0.94f
private val DIALOG_BLUR_BEHIND: Dp = 16.dp
private val DIALOG_BUTTON_HEIGHT: Dp = 46.dp
private val DIALOG_BUTTON_GAP: Dp = 10.dp
private val DIALOG_BUTTON_PADDING: Dp = 16.dp

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun InfinityConfirmDialogPreview() {
    InfinityTheme {
        InfinityConfirmDialogCard(
            title = "Delete Session",
            message = "Are you sure you want to delete this session? This action cannot be undone.",
            confirmText = "Delete",
            onConfirm = {},
            onCancel = {},
            isDestructive = true
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun InfinityConfirmDialogNormalPreview() {
    InfinityTheme {
        InfinityConfirmDialogCard(
            title = "Clear Cache",
            message = "This will clear all cached data. Your sessions will not be affected.",
            confirmText = "Clear",
            onConfirm = {},
            onCancel = {},
            isDestructive = false
        )
    }
}
