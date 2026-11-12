package com.promenar.infinity.recording

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.theme.InfinitySpacing

private fun Context.recordingShortcut() = ShortcutInfo.Builder(this, "open_recording")
    .setShortLabel(getString(R.string.recording_start))
    .setIcon(Icon.createWithResource(this, R.drawable.ic_recording_mic))
    .setIntent(recordingIntent(this, autoStart = true))
    .build()

internal fun registerRecordingShortcut(context: Context) {
    val manager = context.getSystemService(ShortcutManager::class.java) ?: return
    runCatching {
        if (manager.dynamicShortcuts.none { it.id == "open_recording" }) {
            manager.addDynamicShortcuts(listOf(context.recordingShortcut()))
        }
    }
}

@Composable
internal fun RecordingModeOptions(selected: RecordingMode, onSelect: (RecordingMode) -> Unit) {
    InfinitySegmentedControl(
        segments = RecordingMode.entries
            .filter { it != RecordingMode.Local || BuildConfig.LOCAL_INFERENCE_AVAILABLE }
            .map { mode ->
                InfinitySegment(
                    value = mode,
                    label = stringResource(when (mode) {
                        RecordingMode.Auto -> R.string.recording_mode_auto
                        RecordingMode.Local -> R.string.recording_mode_local
                        RecordingMode.Raw -> R.string.recording_mode_raw
                    }),
                )
            },
        selected = selected,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = InfinitySpacing.RowHorizontal),
    )
}

@Composable
internal fun RecordingSettingsScreen(
    onBack: () -> Unit,
    onQuickModels: () -> Unit,
    onLocalModels: () -> Unit,
) {
    val context = LocalContext.current
    val controller = context.recordingController()
    val state by controller.state.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(controller.store.mode) }
    var fallback by remember { mutableStateOf(controller.store.fallback) }
    val shortcuts = context.getSystemService(ShortcutManager::class.java)

    InfinitySettingsPageLayout(title = stringResource(R.string.recording_settings), onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                InfinitySettingsSection(title = stringResource(R.string.recording_settings)) {
                    RecordingModeOptions(mode) {
                        mode = it
                        controller.setMode(null, it)
                    }
                    if (mode == RecordingMode.Auto && BuildConfig.LOCAL_INFERENCE_AVAILABLE) {
                        SettingsToggle(
                            title = stringResource(R.string.recording_fallback),
                            checked = fallback,
                            onCheckedChange = {
                                fallback = it
                                controller.store.fallback = it
                            },
                        )
                    }
                }
            }
            item {
                InfinitySettingsSection(title = stringResource(R.string.recording_models)) {
                    InfinitySettingsItem(title = stringResource(R.string.settings_model_summary),
                        subtitle = state.cloudLabel ?: stringResource(R.string.recording_not_configured), onClick = onQuickModels)
                    InfinitySettingsItem(title = stringResource(R.string.settings_local_models),
                        subtitle = if (!BuildConfig.LOCAL_INFERENCE_AVAILABLE) stringResource(R.string.recording_local_unavailable)
                            else state.localLabel ?: stringResource(R.string.recording_not_configured),
                        onClick = onLocalModels, enabled = BuildConfig.LOCAL_INFERENCE_AVAILABLE)
                }
            }
            item {
                InfinitySettingsSection(title = stringResource(R.string.recording_shortcuts)) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        InfinitySettingsItem(title = stringResource(R.string.recording_add_tile), onClick = {
                            runCatching {
                                context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                                    ComponentName(context, RecordingTileService::class.java),
                                    context.getString(R.string.recording_title),
                                    Icon.createWithResource(context, R.drawable.ic_recording_mic),
                                    context.mainExecutor,
                                ) { }
                            }
                        })
                    } else {
                        InfinitySettingsItem(title = stringResource(R.string.recording_add_tile),
                            subtitle = stringResource(R.string.recording_tile_manual), onClick = {}, enabled = false)
                    }
                    if (shortcuts?.isRequestPinShortcutSupported == true) {
                        InfinitySettingsItem(title = stringResource(R.string.recording_pin_shortcut), onClick = {
                            runCatching { shortcuts.requestPinShortcut(context.recordingShortcut(), null) }
                        })
                    }
                }
            }
        }
    }
}
