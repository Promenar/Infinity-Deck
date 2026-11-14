package com.promenar.infinity.recording

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.rememberInfinityWideWindow
import com.promenar.infinity.ui.theme.InfinityTypography

@Composable
internal fun RecordingDialog(
    title: String,
    dismiss: () -> Unit,
    icon: ImageVector = Icons.Rounded.Info,
    subtitle: String? = null,
    width: Dp = 464.dp,
    destructive: Boolean = false,
    dismissEnabled: Boolean = true,
    back: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    visible: Boolean = true,
    animationsEnabled: Boolean = true,
    onExit: () -> Unit = {},
    body: @Composable ColumnScope.() -> Unit,
) {
    val wide = rememberInfinityWideWindow()
    val close by rememberUpdatedState(back ?: dismiss)
    val canClose by rememberUpdatedState(dismissEnabled)
    val exit by rememberUpdatedState(onExit)
    val preview = LocalInspectionMode.current
    val entrance = remember { Animatable(if (preview) 1f else 0f) }
    LaunchedEffect(visible, animationsEnabled) {
        val target = if (visible) 1f else 0f
        if (animationsEnabled) entrance.animateTo(target, tween(190)) else entrance.snapTo(target)
        if (!visible) exit()
    }
    Dialog(
        onDismissRequest = { if (dismissEnabled) close() },
        properties = DialogProperties(dismissOnBackPress = dismissEnabled, dismissOnClickOutside = false,
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val scrim = MaterialTheme.colorScheme.scrim
        Box(Modifier.fillMaxSize().drawBehind { drawRect(scrim, alpha = .28f * entrance.value) }) {
            Box(Modifier.matchParentSize().pointerInput(Unit) {
                detectTapGestures { if (canClose) close() }
            })
            Box(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = if (wide) 22.dp else 8.dp, vertical = if (wide) 18.dp else 10.dp),
                contentAlignment = if (wide) Alignment.Center else Alignment.BottomCenter,
            ) {
                val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                Surface(
                    Modifier.widthIn(max = if (wide) width else Dp.Infinity).fillMaxWidth()
                        .graphicsLayer {
                            alpha = entrance.value
                            translationY = (1f - entrance.value) * 7.dp.toPx()
                        }.semantics { testTag = "recording_dialog" },
                    shape = RoundedCornerShape(if (wide) 26.dp else 23.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    val horizontal = if (wide) 20.dp else 15.dp
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(start = horizontal, end = 10.dp, top = 12.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(36.dp).background(accent.copy(alpha = .14f), RoundedCornerShape(11.dp)),
                                contentAlignment = Alignment.Center) {
                                Icon(icon, null, Modifier.size(20.dp), tint = accent)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(title, Modifier.semantics { heading() }, style = InfinityTypography.titleLarge)
                                if (subtitle != null) Text(subtitle, style = InfinityTypography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(close, enabled = dismissEnabled, modifier = Modifier.size(48.dp)) {
                                Icon(if (back == null) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                                    stringResource(if (back == null) R.string.recording_dialog_close else R.string.common_cd_back),
                                    Modifier.size(20.dp))
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(
                            Modifier.weight(1f, fill = false).fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = horizontal, vertical = 13.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            content = body,
                        )
                        if (footer != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Box(Modifier.fillMaxWidth().padding(horizontal = horizontal, vertical = 8.dp),
                                contentAlignment = Alignment.CenterEnd) { footer() }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingDialogActions(
    confirm: String,
    onConfirm: () -> Unit,
    dismiss: () -> Unit,
    cancel: String = stringResource(R.string.recording_cancel),
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    enabled: Boolean = true,
    extra: (@Composable () -> Unit)? = null,
) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        extra?.invoke()
        InfinityPillButton(cancel, dismiss, enabled = enabled)
        InfinityPillButton(confirm, onConfirm, enabled = enabled && confirmEnabled,
            tone = if (destructive) InfinityPillTone.DESTRUCTIVE else InfinityPillTone.PRIMARY)
    }
}

@Composable
internal fun RecordingDialogNote(text: String, warning: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val tint = if (warning) colors.error else colors.primary
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp), color = tint.copy(alpha = .07f),
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = .25f))) {
        Text(text, Modifier.padding(11.dp), style = InfinityTypography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
internal fun RecordingDialogGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title != null) Text(title, Modifier.padding(start = 2.dp, bottom = 3.dp).semantics { heading() },
            style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
internal fun RecordingDialogRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    selected: Boolean? = null,
    checked: Boolean? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val accent = if (destructive) colors.error else colors.primary
    val action = when {
        selected != null -> Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        checked != null -> Modifier.toggleable(checked, enabled, Role.Switch) { onClick() }
        else -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = if (selected == true) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        border = if (selected == true) androidx.compose.foundation.BorderStroke(1.dp, colors.primary.copy(alpha = .26f)) else null) {
        Row(Modifier.then(action).heightIn(min = 48.dp).padding(horizontal = 11.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, Modifier.size(19.dp), tint = accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = InfinityTypography.bodyMedium,
                    color = (if (destructive) accent else colors.onSurface).copy(alpha = if (enabled) 1f else .48f))
                if (subtitle != null) Text(subtitle, style = InfinityTypography.bodySmall, color = colors.onSurfaceVariant)
            }
            when {
                checked != null -> Switch(checked, onCheckedChange = null, enabled = enabled)
                selected == true -> Icon(Icons.Rounded.Check, null, Modifier.size(19.dp), tint = accent)
                selected == false -> Spacer(Modifier.width(19.dp))
                else -> Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = accent)
            }
        }
    }
}

@Composable
internal fun RecordingConfirm(
    title: String,
    message: String,
    confirm: String,
    dismiss: () -> Unit,
    action: () -> Unit,
    cancel: String = stringResource(R.string.recording_cancel),
    icon: ImageVector = Icons.Rounded.Info,
    destructive: Boolean = false,
    note: String? = null,
    warning: Boolean = false,
) {
    RecordingDialog(title, dismiss, icon = if (destructive) Icons.Rounded.Delete else icon, width = 412.dp,
        destructive = destructive, footer = { RecordingDialogActions(confirm, action, dismiss, cancel, destructive) }) {
        Text(message, style = InfinityTypography.bodyMedium)
        if (note != null) RecordingDialogNote(note, warning)
    }
}

@Composable
internal fun RecordingConfirm(
    title: Int, message: String, confirm: Int, dismiss: () -> Unit, action: () -> Unit,
    cancel: String = stringResource(R.string.recording_cancel), icon: ImageVector = Icons.Rounded.Info, note: String? = null,
) = RecordingConfirm(stringResource(title), message, stringResource(confirm), dismiss, action, cancel, icon, note = note)
