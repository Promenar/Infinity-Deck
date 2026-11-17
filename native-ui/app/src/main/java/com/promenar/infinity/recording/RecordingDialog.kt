package com.promenar.infinity.recording

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityEmbeddedNoteCard
import com.promenar.infinity.ui.common.InfinityEmbeddedNoteRow
import com.promenar.infinity.ui.common.InfinityNoteTone
import com.promenar.infinity.ui.common.InfinityOptionIconBadge
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinityWindowBackScope
import com.promenar.infinity.ui.common.SettingsSectionHeader
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.rememberInfinityWideWindow
import com.promenar.infinity.ui.theme.InfinitySpacing
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
        properties = DialogProperties(
            dismissOnBackPress = dismissEnabled,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        InfinityWindowBackScope {
            val scheme = MaterialTheme.colorScheme
            val scrim = scheme.scrim
            Box(Modifier.fillMaxSize().drawBehind { drawRect(scrim, alpha = .56f * entrance.value) }) {
                Box(Modifier.matchParentSize().pointerInput(Unit) {
                    detectTapGestures { if (canClose) close() }
                })
                Box(
                    modifier = if (wide) {
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    } else {
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                            .padding(top = 24.dp)
                    },
                    contentAlignment = if (wide) Alignment.Center else Alignment.BottomCenter,
                ) {
                    val sheetShape = if (wide) {
                        RoundedCornerShape(28.dp)
                    } else {
                        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
                    }
                    Surface(
                        modifier = Modifier
                            .then(if (wide) Modifier.widthIn(max = width) else Modifier)
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = entrance.value
                                val offsetDp = if (wide) 7.dp else 20.dp
                                translationY = (1f - entrance.value) * offsetDp.toPx()
                            }
                            .semantics { testTag = "recording_dialog" },
                        shape = sheetShape,
                        color = scheme.surfaceContainer,
                        contentColor = scheme.onSurface,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (wide) Modifier
                                    else Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (!wide) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 14.dp, bottom = 10.dp)
                                        .width(40.dp)
                                        .height(4.dp)
                                        .background(scheme.outlineVariant, CircleShape),
                                )
                            }
                            val horizontal = 20.dp
                            if (wide) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = horizontal, end = 12.dp, top = 16.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    InfinityOptionIconBadge(
                                        icon = icon,
                                        selected = !destructive,
                                        destructive = destructive,
                                    )
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = title,
                                            modifier = Modifier.semantics { heading() },
                                            style = InfinityTypography.titleLarge,
                                            color = scheme.onSurface,
                                        )
                                        if (subtitle != null) {
                                            Text(
                                                text = subtitle,
                                                style = InfinityTypography.bodySmall,
                                                color = scheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    IconButton(onClick = close, enabled = dismissEnabled, modifier = Modifier.size(40.dp)) {
                                        Icon(
                                            imageVector = if (back == null) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                                            contentDescription = stringResource(
                                                if (back == null) R.string.recording_dialog_close else R.string.common_cd_back,
                                            ),
                                            tint = scheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = if (back != null) 8.dp else horizontal, end = horizontal, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (back != null) {
                                        IconButton(onClick = back, enabled = dismissEnabled, modifier = Modifier.size(40.dp)) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                                contentDescription = stringResource(R.string.common_cd_back),
                                                tint = scheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = title,
                                            modifier = Modifier.semantics { heading() },
                                            style = InfinityTypography.titleLarge,
                                            color = scheme.onSurface,
                                        )
                                        if (subtitle != null) {
                                            Text(
                                                text = subtitle,
                                                style = InfinityTypography.bodySmall,
                                                color = scheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = horizontal, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                content = body,
                            )
                            if (footer != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = horizontal, end = horizontal, top = 4.dp, bottom = 18.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    footer()
                                }
                            } else {
                                Spacer(Modifier.height(10.dp))
                            }
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
    val scheme = MaterialTheme.colorScheme
    val labelStyle = InfinityTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        extra?.invoke()
        FilledTonalButton(
            onClick = dismiss,
            enabled = enabled,
            shape = CircleShape,
            modifier = Modifier.height(44.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            Text(cancel, style = labelStyle)
        }
        Button(
            onClick = onConfirm,
            enabled = enabled && confirmEnabled,
            shape = CircleShape,
            modifier = Modifier.height(44.dp),
            contentPadding = PaddingValues(horizontal = 22.dp),
            colors = if (destructive) {
                ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(confirm, style = labelStyle)
        }
    }
}

@Composable
internal fun RecordingDialogNote(text: String, warning: Boolean = false) {
    InfinityEmbeddedNoteCard(
        rows = listOf(
            InfinityEmbeddedNoteRow(
                icon = if (warning) Icons.Rounded.WarningAmber else Icons.Rounded.Info,
                text = text,
                tone = if (warning) InfinityNoteTone.WARNING else InfinityNoteTone.DEFAULT,
            ),
        ),
    )
}

@Composable
internal fun RecordingDialogGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (title != null) {
            SettingsSectionHeader(
                title = title,
                titleStyle = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(
                    start = InfinitySpacing.RowHorizontal,
                    end = InfinitySpacing.RowHorizontal,
                    bottom = 4.dp,
                ),
            )
        }
        BettboxListGroup(modifier = Modifier.fillMaxWidth().then(modifier), content = content)
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
    chevron: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    if (checked != null) {
        SettingsToggle(
            title = title,
            description = subtitle,
            checked = checked,
            onCheckedChange = { onClick() },
            icon = icon,
            enabled = enabled,
            descriptionMaxLines = 2,
        )
    } else {
        InfinitySettingsItem(
            icon = icon,
            title = title,
            subtitle = subtitle,
            subtitleMaxLines = 2,
            titleColor = if (destructive) scheme.error else null,
            onClick = onClick,
            enabled = enabled,
            role = if (selected != null) Role.RadioButton else Role.Button,
            selected = selected,
            showChevron = chevron && selected == null,
            trailingContent = if (selected == true) {
                {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (destructive) scheme.error else scheme.primary,
                    )
                }
            } else null,
        )
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
    InfinityConfirmDialog(
        title = title,
        message = message,
        confirmText = confirm,
        cancelText = cancel,
        onConfirm = action,
        onCancel = dismiss,
        isDestructive = destructive,
        modifier = Modifier.semantics { testTag = "recording_dialog" },
        icon = if (destructive) Icons.Rounded.Delete else icon,
        content = note?.let { { RecordingDialogNote(it, warning) } },
    )
}

@Composable
internal fun RecordingConfirm(
    title: Int, message: String, confirm: Int, dismiss: () -> Unit, action: () -> Unit,
    cancel: String = stringResource(R.string.recording_cancel), icon: ImageVector = Icons.Rounded.Info, note: String? = null,
) = RecordingConfirm(stringResource(title), message, stringResource(confirm), dismiss, action, cancel, icon, note = note)

