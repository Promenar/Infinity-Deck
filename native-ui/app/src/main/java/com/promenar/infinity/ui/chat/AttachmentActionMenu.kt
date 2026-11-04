package com.promenar.infinity.ui.chat

import com.promenar.infinity.ui.common.InfinityHapticEvent
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.R
import com.promenar.infinity.ui.testing.UiTags

private const val MenuAnimationDurationMillis = 180

/** 锚定在父布局内的附件动作菜单；位置参数均使用父布局局部坐标。 */
@Composable
internal fun AttachmentActionMenu(
    expanded: Boolean,
    anchorBounds: Rect,
    maximumBottom: Float,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPickDocument: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSketch: (() -> Unit)? = null,
) {
    val visibilityState = remember { MutableTransitionState(expanded) }
    visibilityState.targetState = expanded
    val interceptTouches = visibilityState.currentState || visibilityState.targetState
    val layoutDirection = LocalLayoutDirection.current
    val placementBottom = minOf(anchorBounds.top, maximumBottom)
    val anchorTravel = (anchorBounds.top - placementBottom).toInt().coerceAtLeast(0)
    val transformOrigin = TransformOrigin(
        pivotFractionX = if (layoutDirection == LayoutDirection.Ltr) 0f else 1f,
        pivotFractionY = 1f,
    )

    BackHandler(enabled = expanded, onBack = onDismiss)

    Box(
        modifier = modifier
            .fillMaxSize(),
    ) {
        if (interceptTouches) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(UiTags.CHAT_ATTACHMENT_DISMISS_LAYER)
                    .pointerInput(onDismiss) {
                        detectTapGestures { onDismiss() }
                    },
            )
        }

        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                AnimatedVisibility(
                    visibleState = visibilityState,
                    enter = fadeIn(tween(MenuAnimationDurationMillis)) + scaleIn(
                        animationSpec = tween(MenuAnimationDurationMillis, easing = InfinityMotion.EmphasizedDecelerate),
                        initialScale = 0.86f,
                        transformOrigin = transformOrigin,
                    ) + slideInVertically(tween(MenuAnimationDurationMillis)) { anchorTravel },
                    exit = fadeOut(tween(MenuAnimationDurationMillis)) + scaleOut(
                        animationSpec = tween(MenuAnimationDurationMillis, easing = InfinityMotion.EmphasizedAccelerate),
                        targetScale = 0.94f,
                        transformOrigin = transformOrigin,
                    ) + slideOutVertically(tween(MenuAnimationDurationMillis)) { anchorTravel },
                ) {
                    Surface(
                        modifier = Modifier
                            .widthIn(min = 220.dp, max = 320.dp)
                            .testTag(UiTags.CHAT_ATTACHMENT_MENU),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        shadowElevation = 8.dp,
                        tonalElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .width(IntrinsicSize.Max)
                                .padding(6.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            AttachmentAction(
                                label = stringResource(R.string.chat_menu_attach_image),
                                hint = stringResource(R.string.chat_menu_attach_image_hint),
                                icon = Icons.Rounded.Image,
                                tileColor = MaterialTheme.colorScheme.primaryContainer,
                                iconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                enabled = enabled,
                                modifier = Modifier.testTag(UiTags.CHAT_ATTACH_MENU_IMAGE),
                                onClick = {
                                    onDismiss()
                                    onPickImage()
                                },
                            )
                            if (onSketch != null) {
                                AttachmentAction(
                                    label = stringResource(R.string.annotation_menu_sketch),
                                    hint = stringResource(R.string.annotation_menu_sketch_hint),
                                    icon = Icons.Rounded.Draw,
                                    tileColor = MaterialTheme.colorScheme.secondaryContainer,
                                    iconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    enabled = enabled,
                                    modifier = Modifier.testTag(UiTags.CHAT_MENU_SKETCH),
                                    onClick = {
                                        onDismiss()
                                        onSketch()
                                    },
                                )
                            }
                            AttachmentAction(
                                label = stringResource(R.string.chat_menu_attach_document),
                                hint = stringResource(R.string.chat_menu_attach_document_hint),
                                icon = Icons.Rounded.Description,
                                tileColor = MaterialTheme.colorScheme.tertiaryContainer,
                                iconColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                enabled = enabled,
                                modifier = Modifier.testTag(UiTags.CHAT_ATTACH_MENU_DOCUMENT),
                                onClick = {
                                    onDismiss()
                                    onPickDocument()
                                },
                            )
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            if (measurables.isEmpty()) {
                layout(constraints.maxWidth, constraints.maxHeight) {}
            } else {
                val margin = 8.dp.roundToPx()
                val gap = 8.dp.roundToPx()
                val maxMenuWidth = (constraints.maxWidth - margin * 2).coerceAtLeast(0)
                val maxMenuHeight = (placementBottom.toInt() - margin - gap).coerceAtLeast(0)
                val placeable = measurables.first().measure(
                    constraints.copy(
                        minWidth = 0,
                        minHeight = 0,
                        maxWidth = maxMenuWidth,
                        maxHeight = minOf(constraints.maxHeight, maxMenuHeight),
                    )
                )
                val maxX = (constraints.maxWidth - placeable.width - margin).coerceAtLeast(margin)
                val anchoredX = if (layoutDirection == LayoutDirection.Ltr) {
                    anchorBounds.left.toInt()
                } else {
                    anchorBounds.right.toInt() - placeable.width
                }
                val x = anchoredX.coerceIn(margin, maxX)
                val y = (placementBottom.toInt() - placeable.height - gap).coerceAtLeast(margin)

                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(x, y)
                }
            }
        }
    }
}

/** 一项附件来源：彩色图标块、名称与一行说明，10dp 圆角按压高亮。 */
@Composable
private fun AttachmentAction(
    label: String,
    hint: String,
    icon: ImageVector,
    tileColor: Color,
    iconColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalInfinityHaptics.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button) {
                haptics.perform(InfinityHapticEvent.SELECTION)
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(tileColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(start = 12.dp, end = 8.dp)) {
            Text(text = label, style = InfinityTypography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text = hint, style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
