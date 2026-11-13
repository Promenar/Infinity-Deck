package com.promenar.infinity.recording

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.LocalInfinitySettingsPaneRoot
import com.promenar.infinity.ui.common.ProvideUniformRowHeight
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

/** 当前发行渠道可选的整理方式。 */
private val recordingModes get() = RecordingMode.entries
    .filter { it != RecordingMode.Local || BuildConfig.LOCAL_INFERENCE_AVAILABLE }

private fun recordingModeLabel(mode: RecordingMode): Int = when (mode) {
    RecordingMode.Auto -> R.string.recording_mode_auto
    RecordingMode.Local -> R.string.recording_mode_local
    RecordingMode.Raw -> R.string.recording_mode_raw
}

/** 录音页弹层里的整理方式：每项写明实际使用的模型，行文字与弹层标题左对齐。 */
@Composable
internal fun RecordingModeSheetContent(
    selected: RecordingMode,
    cloudLabel: String?,
    localLabel: String?,
    cloudAllowed: Boolean,
    onSelect: (RecordingMode) -> Unit,
    onRevoke: () -> Unit,
) {
    val unavailable = stringResource(R.string.recording_not_configured)
    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        ProvideUniformRowHeight {
            Column(Modifier.selectableGroup()) {
                recordingModes.forEach { mode ->
                    val chosen = mode == selected
                    InfinitySettingsItem(
                        title = stringResource(recordingModeLabel(mode)),
                        subtitle = when (mode) {
                            RecordingMode.Auto -> cloudLabel ?: unavailable
                            RecordingMode.Local -> localLabel ?: unavailable
                            RecordingMode.Raw -> null
                        },
                        onClick = { onSelect(mode) },
                        horizontalPadding = 0.dp,
                        role = Role.RadioButton,
                        selected = chosen,
                        trailingContent = {
                            if (chosen) {
                                androidx.compose.material3.Icon(
                                    Icons.Rounded.Check, contentDescription = null,
                                    modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                }
            }
        }
        if (cloudAllowed) {
            InfinityPillButton(
                text = stringResource(R.string.recording_ui_revoke_cloud),
                onClick = onRevoke,
                modifier = Modifier.fillMaxWidth(),
                tone = InfinityPillTone.DANGER,
            )
        }
    }
}

@Composable
internal fun RecordingModeOptions(selected: RecordingMode, onSelect: (RecordingMode) -> Unit) {
    InfinitySegmentedControl(
        segments = recordingModes.map { mode -> InfinitySegment(value = mode, label = stringResource(recordingModeLabel(mode))) },
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
    var showHotwords by rememberSaveable { mutableStateOf(false) }
    var showVoice by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showHotwords || showVoice) { showHotwords = false; showVoice = false }
    if (showVoice) {
        CompositionLocalProvider(LocalInfinitySettingsPaneRoot provides false) {
            RecordingVoiceLibrary(controller, onBack = { showVoice = false })
        }
        return
    }
    if (showHotwords) {
        CompositionLocalProvider(LocalInfinitySettingsPaneRoot provides false) {
            RecordingHotwordLibrary(controller, onBack = { showHotwords = false })
        }
        return
    }

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
                InfinitySettingsSection(title = stringResource(R.string.recording_asr_section)) {
                    RecordingModelDownloads(controller)
                }
            }
            item {
                InfinitySettingsSection(title = stringResource(R.string.recording_speaker_recognition_settings)) {
                    RecordingModelSettings(controller, onHotwords = { showHotwords = true }, onVoice = { showVoice = true })
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
                val tileLabel = stringResource(R.string.recording_title)
                InfinitySettingsSection(title = stringResource(R.string.recording_shortcuts)) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        InfinitySettingsItem(title = stringResource(R.string.recording_add_tile), onClick = {
                            runCatching {
                                context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                                    ComponentName(context, RecordingTileService::class.java),
                                    tileLabel,
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

@Composable
internal fun RecordingModelSettings(
    controller: RecordingController,
    onHotwords: () -> Unit,
    onVoice: () -> Unit,
    session: RecordingSession? = null,
) {
    val hotwords by controller.hotwords.collectAsStateWithLifecycle()
    val people by controller.voicePeople.collectAsStateWithLifecycle()
    var defaultSpeakers by remember { mutableStateOf(controller.store.distinguishSpeakers) }
    InfinitySettingsItem(
        title = stringResource(R.string.recording_hotword_library),
        subtitle = stringResource(R.string.recording_hotword_count, hotwords.count { it.enabled }, hotwords.size),
        onClick = onHotwords,
        showChevron = true,
        subtitleMaxLines = 2,
    )
    InfinitySettingsItem(
        title = stringResource(R.string.recording_speaker_library),
        subtitle = pluralStringResource(R.plurals.recording_speaker_count, people.size, people.size),
        onClick = onVoice, showChevron = true,
    )
    SettingsToggle(
        title = stringResource(R.string.recording_speaker_distinguish),
        checked = session?.distinguishSpeakers ?: defaultSpeakers,
        onCheckedChange = {
            if (session == null) defaultSpeakers = it
            controller.distinguishSpeakers(session?.id, it)
        },
    )
}


@Composable
internal fun RecordingModelDownloads(controller: RecordingController) {
    val asr by controller.asrModel.collectAsStateWithLifecycle()
    val speakerModel by controller.speakerModel.collectAsStateWithLifecycle()
    RecordingAsrControls(asr, controller::downloadAsrModel, controller::cancelAsrModelDownload)
    RecordingAsrControls(speakerModel, controller::downloadSpeakerModel, controller::cancelSpeakerModel, speaker = true)
}
