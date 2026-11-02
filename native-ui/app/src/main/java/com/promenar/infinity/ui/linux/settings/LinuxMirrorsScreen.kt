package com.promenar.infinity.ui.linux.settings

import com.promenar.infinity.ui.theme.InfinityMonospace
import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.domain.linux.LinuxMirrorChoice
import com.promenar.infinity.domain.linux.LinuxMirrorId
import com.promenar.infinity.domain.linux.LinuxMirrorSettings
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.promenar.infinity.ui.theme.forInput

data class LinuxMirrorsUiState(
    val selected: LinuxMirrorChoice,
    /** 键不存在表示未测；值为 null 表示测过但不可达。 */
    val latencies: Map<LinuxMirrorId, Long?>,
    val testing: Boolean = false,
    val saveFailed: Boolean = false,
)

class LinuxMirrorsViewModel(private val mirrors: LinuxMirrorSettings) : ViewModel() {
    private val testing = MutableStateFlow(false)
    private val saveFailed = MutableStateFlow(false)
    private var speedTestJob: Job? = null

    val uiState: StateFlow<LinuxMirrorsUiState> = combine(mirrors.selected, mirrors.latencies, testing, saveFailed) { selected, latencies, testing, failed ->
        LinuxMirrorsUiState(selected, latencies, testing, failed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LinuxMirrorsUiState(mirrors.selected.value, mirrors.latencies.value))

    fun select(choice: LinuxMirrorChoice) {
        if (choice.id == LinuxMirrorId.CUSTOM && !isValidCustomMirror(choice.customUrl.orEmpty())) return
        viewModelScope.launch {
            saveFailed.value = false
            try {
                mirrors.select(choice)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveFailed.value = true
            }
        }
    }

    fun speedTest() {
        if (speedTestJob?.isActive == true) return
        speedTestJob = viewModelScope.launch {
            testing.value = true
            try {
                mirrors.speedTest().collect {}
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 单个镜像的失败已在结果中记为 null；整体异常只结束测速状态。
            } finally {
                testing.value = false
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { LinuxMirrorsViewModel((application as InfinityApplication).linux.mirrors) }
        }
    }
}

@Composable
fun LinuxMirrorsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: LinuxMirrorsViewModel = viewModel(factory = LinuxMirrorsViewModel.factory(context.applicationContext as Application))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (state.latencies.isEmpty()) viewModel.speedTest() }
    InfinitySettingsPageLayout(
        title = stringResource(R.string.linux_mirrors_title),
        onBack = onBack,
        actions = {
            TextButton(onClick = viewModel::speedTest, enabled = !state.testing, modifier = Modifier.testTag(UiTags.LINUX_MIRROR_SPEED_TEST)) {
                Text(stringResource(if (state.testing) R.string.linux_mirrors_testing else R.string.linux_mirrors_speed_test))
            }
        },
    ) { padding ->
        LinuxMirrorsContent(state, onSelect = viewModel::select, onDone = onBack, modifier = Modifier.padding(padding))
    }
}

@Composable
internal fun LinuxMirrorsContent(
    state: LinuxMirrorsUiState,
    onSelect: (LinuxMirrorChoice) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var customDraft by rememberSaveable { mutableStateOf(state.selected.customUrl.orEmpty()) }
    var customOpen by rememberSaveable { mutableStateOf(state.selected.id == LinuxMirrorId.CUSTOM) }
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = InfinitySpacing.Large),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
        ) {
            Text(
                stringResource(R.string.linux_mirrors_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = InfinitySpacing.XSmall),
            )
            InfinitySettingsSection(
                title = stringResource(R.string.linux_mirror_title),
                footer = stringResource(R.string.linux_mirrors_note),
                modifier = Modifier.selectableGroup(),
            ) {
                LinuxMirrorId.entries.filter { it != LinuxMirrorId.CUSTOM }.forEach { id ->
                    val selected = state.selected.id == id
                    InfinitySettingsItem(
                        title = stringResource(linuxMirrorName(id)),
                        subtitle = id.host,
                        subtitleIsIdentifier = true,
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { customOpen = false; onSelect(LinuxMirrorChoice(id)) },
                        leadingContent = { RadioButton(selected = selected, onClick = null) },
                        trailingContent = { LinuxMirrorLatency(state, id) },
                        modifier = Modifier.testTag(UiTags.linuxMirrorRow(id.name)),
                    )
                }
                val customSelected = state.selected.id == LinuxMirrorId.CUSTOM
                InfinitySettingsItem(
                    title = stringResource(R.string.linux_mirror_custom),
                    subtitle = customMirrorHost(state.selected.customUrl).takeIf { customSelected }
                        ?: stringResource(R.string.linux_mirror_custom_hint),
                    subtitleMaxLines = 2,
                    selected = customSelected,
                    role = Role.RadioButton,
                    onClick = { customOpen = true },
                    leadingContent = { RadioButton(selected = customSelected || customOpen, onClick = null) },
                    trailingContent = if (customSelected) ({ LinuxMirrorLatency(state, LinuxMirrorId.CUSTOM) }) else null,
                    modifier = Modifier.testTag(UiTags.linuxMirrorRow(LinuxMirrorId.CUSTOM.name)),
                )
                if (customOpen) {
                    val invalid = customDraft.isNotBlank() && !isValidCustomMirror(customDraft)
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
                        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                    ) {
                        OutlinedTextField(
                            value = customDraft,
                            onValueChange = { customDraft = it },
                            label = { Text(stringResource(R.string.linux_mirror_custom_label)) },
                            placeholder = { Text("https://") },
                            singleLine = true,
                            isError = invalid,
                            supportingText = if (invalid) ({ Text(stringResource(R.string.linux_mirror_custom_invalid)) }) else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace).forInput(),
                            modifier = Modifier.fillMaxWidth().testTag(UiTags.LINUX_MIRROR_CUSTOM_FIELD),
                        )
                        InfinityPillButton(
                            text = stringResource(R.string.linux_mirror_custom_save),
                            onClick = { onSelect(LinuxMirrorChoice(LinuxMirrorId.CUSTOM, customDraft.trim())) },
                            enabled = isValidCustomMirror(customDraft),
                            tone = InfinityPillTone.PRIMARY,
                        )
                    }
                }
            }
            if (state.saveFailed) {
                Text(
                    stringResource(R.string.linux_operation_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = InfinitySpacing.XSmall),
                )
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(bottom = InfinitySpacing.Large)) {
            Text(stringResource(R.string.linux_mirrors_done))
        }
    }
}

@Composable
private fun LinuxMirrorLatency(state: LinuxMirrorsUiState, id: LinuxMirrorId) {
    Box(Modifier.heightIn(min = 24.dp)) {
        when {
            state.testing && id !in state.latencies -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            id !in state.latencies -> Text(
                stringResource(R.string.linux_mirror_latency_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val latency = state.latencies[id]
                Text(
                    latency?.let { linuxDuration(it) } ?: stringResource(R.string.linux_mirror_latency_failed),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                    color = if (latency == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
