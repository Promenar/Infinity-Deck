package com.promenar.infinity.ui.chat.runmode

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.linux.LinuxRecoveryAction
import com.promenar.infinity.data.linux.LinuxRecoveryEvent
import com.promenar.infinity.data.linux.LinuxResetLossSummary
import com.promenar.infinity.data.linux.LinuxRunModeExportFailureReason
import com.promenar.infinity.data.linux.LinuxRunModeExportReport
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.session.SessionRunModeCheck
import com.promenar.infinity.data.session.SessionRunModeSwitchResult
import com.promenar.infinity.domain.linux.LinuxFailure
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.UnavailableReasons
import com.promenar.infinity.ui.linux.settings.linuxBytes
import com.promenar.infinity.ui.linux.settings.linuxFailureText
import com.promenar.infinity.ui.linux.settings.linuxStepTitle
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.theme.breakableIdentifier

object RunModeTags {
    const val CHIP = "run_mode_chip"
    const val PICKER = "run_mode_picker"
    const val CHECKPOINT = "run_mode_checkpoint"
    const val PRIMARY = "run_mode_checkpoint_primary"
    const val BUILTIN = "run_mode_checkpoint_builtin"
    const val RESET = "run_mode_checkpoint_reset"
    const val EXPORT = "run_mode_checkpoint_export"
    fun option(mode: SessionRunMode) = "run_mode_option_${mode.name.lowercase()}"
}

/** 顶栏模式标识的数据；Play 版没有 Linux，不显示。 */
data class RunModeBadge(val mode: SessionRunMode?, val unavailable: Boolean)

/**
 * 用户显式切换模式。返回仍需处理的检查点阶段：切到 Linux 但环境不可用时进入修复，
 * 从 Linux 主目录工作区切到内置时提示导出；无需处理返回 null，切换失败抛出异常。
 */
suspend fun switchRunMode(application: InfinityApplication, sessionId: String, mode: SessionRunMode): RunModeCheckpointStage? =
    withContext(Dispatchers.IO) {
        when (val result = application.sessionRunModes.switchMode(sessionId, mode)) {
            is SessionRunModeSwitchResult.Success -> when {
                mode == SessionRunMode.LINUX -> initialStage(application.sessionRunModes.check(sessionId))
                result.exportWorkspaceId != null -> RunModeCheckpointStage.ExportOffer
                else -> null
            }
            SessionRunModeSwitchResult.LinuxUnsupported, SessionRunModeSwitchResult.SessionMissing ->
                throw IllegalStateException("运行模式切换失败：$result")
        }
    }

@StringRes
fun SessionRunMode?.labelRes(): Int = when (this) {
    SessionRunMode.LINUX -> R.string.run_mode_linux
    SessionRunMode.BUILTIN -> R.string.run_mode_builtin
    null -> R.string.run_mode_unset
}

/**
 * 顶栏状态行开头的模式标识：图标加文字（状态行放不下时只留图标），不画边框，与其后的工作区、Git 信息同一行同一字号。
 * Linux 用主色；内置用次要文字色；Linux 不可用时换成警示图标与警示色（读屏说明不可用），点按回到检查点。
 */
@Composable
fun RunModeChip(
    mode: SessionRunMode?,
    unavailable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val label = stringResource(mode.labelRes())
    val description = if (unavailable) stringResource(R.string.run_mode_chip_unavailable_cd, label)
        else stringResource(R.string.run_mode_chip_cd, label)
    val colors = MaterialTheme.colorScheme
    val tint = when {
        unavailable -> colors.tertiary
        mode == SessionRunMode.LINUX -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .heightIn(min = 24.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = description
            }
            .testTag(RunModeTags.CHIP),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                unavailable -> Icons.Rounded.WarningAmber
                mode == SessionRunMode.LINUX -> Icons.Rounded.Terminal
                else -> Icons.Rounded.Inventory2
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        if (showLabel) Text(
            text = label,
            style = InfinityTypography.labelMedium,
            color = tint,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 会话菜单与首次选择共用的两项模式列表；Play 版没有 Linux，该项保留并灰显、写明原因。 */
@Composable
fun RunModeOptions(selected: SessionRunMode?, linuxSupported: Boolean, onSelect: (SessionRunMode) -> Unit) {
    SessionRunMode.entries.forEach { mode ->
        val unavailableReason = if (mode == SessionRunMode.LINUX) UnavailableReasons.linuxRunMode(linuxSupported) else null
        InfinitySettingsItem(
            horizontalPadding = 0.dp,
            title = stringResource(mode.labelRes()),
            subtitle = stringResource(
                unavailableReason ?: if (mode == SessionRunMode.LINUX) R.string.run_mode_linux_desc else R.string.run_mode_builtin_desc,
            ),
            enabled = unavailableReason == null,
            subtitleMaxLines = 3,
            role = Role.RadioButton,
            selected = mode == selected,
            modifier = Modifier.testTag(RunModeTags.option(mode)),
            onClick = { onSelect(mode) },
            trailingContent = {
                if (mode == selected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
            },
        )
    }
    Text(
        stringResource(R.string.run_mode_switch_note),
        style = InfinityTypography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = InfinitySpacing.Small),
    )
}

/** 会话菜单里的手动切换；切换不搬文件，从 Linux 主目录工作区切到内置时提示导出。 */
@Composable
fun RunModePickerSheet(
    show: Boolean,
    selected: SessionRunMode?,
    linuxSupported: Boolean,
    onDismiss: () -> Unit,
    onSelect: (SessionRunMode) -> Unit,
    /** 宽窗口下贴着点按的运行模式标识或顶栏菜单弹出。 */
    popover: com.promenar.infinity.ui.common.InfinityPopoverSpec? = null,
) {
    InfinityBottomSheet(show = show, onDismiss = onDismiss, title = stringResource(R.string.run_mode_title), scrollContent = true,
        modifier = Modifier.testTag(RunModeTags.PICKER), popover = popover) {
        RunModeOptions(selected, linuxSupported) { mode -> onDismiss(); if (mode != selected) onSelect(mode) }
    }
}

enum class RunModeUnavailableReason { NOT_INSTALLED, INTERRUPTED, BROKEN, BUSY, UNSUPPORTED }

internal fun unavailableReason(state: LinuxRuntimeState): RunModeUnavailableReason = when (state) {
    is LinuxRuntimeState.NotInstalled -> RunModeUnavailableReason.NOT_INSTALLED
    is LinuxRuntimeState.InstallInterrupted -> RunModeUnavailableReason.INTERRUPTED
    is LinuxRuntimeState.Broken -> RunModeUnavailableReason.BROKEN
    is LinuxRuntimeState.Installing, is LinuxRuntimeState.Maintaining -> RunModeUnavailableReason.BUSY
    is LinuxRuntimeState.Unavailable -> RunModeUnavailableReason.UNSUPPORTED
    // Ready 不会进入检查点；保持完整分支以免新增状态时静默归类。
    is LinuxRuntimeState.Ready -> RunModeUnavailableReason.BUSY
}

/** 检查点的界面阶段：修复 → 重置或导出 → 改用内置，每一步都可直接改用内置。 */
sealed interface RunModeCheckpointStage {
    data class Select(val canChooseLinux: Boolean) : RunModeCheckpointStage
    data class Unavailable(val reason: RunModeUnavailableReason) : RunModeCheckpointStage
    /** [install] 表示修复动作是从“尚未安装”发起的首次安装，标题据此写成安装。 */
    data class Working(
        val action: LinuxRecoveryAction,
        @param:StringRes val step: Int? = null,
        val fraction: Float? = null,
        val install: Boolean = false,
    ) : RunModeCheckpointStage
    /** [canReset] 为 false 表示重置也失败，只剩导出与改用内置。 */
    data class RepairFailed(val failure: LinuxFailure?, val loss: LinuxResetLossSummary?, val canReset: Boolean) : RunModeCheckpointStage
    data object ExportChoice : RunModeCheckpointStage
    data object ExportOffer : RunModeCheckpointStage
    data class Exported(val report: LinuxRunModeExportReport?, val privateTarget: Boolean) : RunModeCheckpointStage
}

internal fun initialStage(check: SessionRunModeCheck): RunModeCheckpointStage? = when (check) {
    is SessionRunModeCheck.SelectionRequired -> RunModeCheckpointStage.Select(check.canChooseLinux)
    is SessionRunModeCheck.EnvironmentUnavailable -> RunModeCheckpointStage.Unavailable(unavailableReason(check.linuxState))
    is SessionRunModeCheck.Available, SessionRunModeCheck.SessionMissing -> null
}

@StringRes
internal fun workingTitle(stage: RunModeCheckpointStage.Working): Int = when (stage.action) {
    LinuxRecoveryAction.REPAIR -> if (stage.install) R.string.run_mode_working_install else R.string.run_mode_working_repair
    LinuxRecoveryAction.RESET -> R.string.run_mode_working_reset
    LinuxRecoveryAction.EXPORT -> R.string.run_mode_working_export
}

/** 恢复进度事件映射为界面阶段；完成事件由调用方按动作分别处理。 */
internal fun progressStage(event: LinuxRecoveryEvent): RunModeCheckpointStage.Working? = when (event) {
    is LinuxRecoveryEvent.Started -> RunModeCheckpointStage.Working(event.action)
    is LinuxRecoveryEvent.Progress -> RunModeCheckpointStage.Working(
        action = event.action,
        step = event.install?.current?.step?.let(::linuxStepTitle),
        fraction = event.install?.fraction,
    )
    is LinuxRecoveryEvent.Finished -> null
}

/**
 * 进入会话的检查点：Linux 会话环境不可用或旧会话尚未选择模式时出现。只有用户显式选择才修复、重置、导出或切换，
 * 不在失败后自动降级；关闭面板不改变会话模式。
 */
@Composable
fun RunModeCheckpointSheet(
    application: InfinityApplication,
    sessionId: String,
    initial: RunModeCheckpointStage,
    workspaceId: String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var stage by remember(sessionId, initial) { mutableStateOf(initial) }
    var confirmReset by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var canExport by remember(workspaceId) { mutableStateOf(false) }
    LaunchedEffect(workspaceId) {
        canExport = workspaceId != null && withContext(Dispatchers.IO) {
            application.database.workspaceDao().getById(workspaceId)?.kind == WorkspaceEntity.KIND_LINUX_HOME
        }
    }
    val failedText = stringResource(R.string.run_mode_action_failed)
    val switchFailedText = stringResource(R.string.run_mode_switch_failed)

    fun failed(error: Exception, text: String = failedText) {
        if (error is CancellationException) throw error
        message = text
    }
    fun switchTo(mode: SessionRunMode) = scope.launch {
        message = null
        try {
            switchRunMode(application, sessionId, mode)?.let { stage = it } ?: onDismiss()
        } catch (error: Exception) { failed(error, switchFailedText) }
    }
    suspend fun lossSummary(): LinuxResetLossSummary? = try {
        application.linuxRunModeRecovery.resetLossSummary()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }
    fun repair() = scope.launch {
        message = null
        val install = (stage as? RunModeCheckpointStage.Unavailable)?.reason == RunModeUnavailableReason.NOT_INSTALLED
        try {
            application.linuxRunModeRecovery.repair().collect { event ->
                progressStage(event)?.let { stage = it.copy(install = install) }
                if (event is LinuxRecoveryEvent.Finished) {
                    if (event.result.success) onDismiss()
                    else stage = RunModeCheckpointStage.RepairFailed(event.result.failure, lossSummary(), canReset = true)
                }
            }
        } catch (error: Exception) {
            failed(error)
            stage = RunModeCheckpointStage.RepairFailed(null, lossSummary(), canReset = true)
        }
    }
    fun reset() = scope.launch {
        message = null
        try {
            application.linuxRunModeRecovery.reset().collect { event ->
                progressStage(event)?.let { stage = it }
                if (event is LinuxRecoveryEvent.Finished) {
                    if (event.result.success) onDismiss()
                    else stage = RunModeCheckpointStage.RepairFailed(event.result.failure, null, canReset = false)
                }
            }
        } catch (error: Exception) {
            failed(error)
            stage = RunModeCheckpointStage.RepairFailed(null, null, canReset = false)
        }
    }
    val returnStage = remember { mutableStateOf<RunModeCheckpointStage>(initial) }
    fun export(treeUri: android.net.Uri?) = scope.launch {
        message = null
        try {
            application.exportLinuxSessionHome(sessionId, treeUri).collect { event ->
                progressStage(event)?.let { stage = it }
                if (event is LinuxRecoveryEvent.Finished) {
                    stage = RunModeCheckpointStage.Exported(event.result.export, privateTarget = treeUri == null)
                }
            }
        } catch (error: Exception) {
            failed(error)
            stage = returnStage.value
        }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) export(uri)
    }
    val working = stage is RunModeCheckpointStage.Working
    InfinityBottomSheet(show = true, onDismiss = onDismiss, dismissEnabled = !working, scrollContent = true,
        modifier = Modifier.testTag(RunModeTags.CHECKPOINT)) {
        RunModeCheckpointContent(
            stage = stage,
            canExport = canExport,
            message = message,
            actions = RunModeCheckpointActions(
                onChoose = ::switchTo,
                onRepair = ::repair,
                onUseBuiltin = { switchTo(SessionRunMode.BUILTIN) },
                onLater = onDismiss,
                onReset = { confirmReset = true },
                onExport = { returnStage.value = stage; stage = RunModeCheckpointStage.ExportChoice },
                onExportPrivate = { export(null) },
                onExportExternal = { runCatching { treePicker.launch(null) }.onFailure { message = failedText } },
                onBack = { stage = returnStage.value },
                onDone = { if (returnStage.value is RunModeCheckpointStage.ExportOffer) onDismiss() else stage = returnStage.value },
            ),
        )
    }
    if (confirmReset) {
        InfinityConfirmDialog(
            onDismissRequest = { confirmReset = false },
            title = stringResource(R.string.run_mode_reset_confirm_title),
            message = stringResource(R.string.run_mode_reset_confirm_message),
            confirmText = stringResource(R.string.run_mode_action_reset),
            isDestructive = true,
            onConfirm = { confirmReset = false; reset() },
            onCancel = { confirmReset = false },
        )
    }
}

data class RunModeCheckpointActions(
    val onChoose: (SessionRunMode) -> Unit = {},
    val onRepair: () -> Unit = {},
    val onUseBuiltin: () -> Unit = {},
    val onLater: () -> Unit = {},
    val onReset: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onExportPrivate: () -> Unit = {},
    val onExportExternal: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onDone: () -> Unit = {},
)

@Composable
fun RunModeCheckpointContent(
    stage: RunModeCheckpointStage,
    canExport: Boolean,
    message: String?,
    actions: RunModeCheckpointActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Large),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        when (stage) {
            is RunModeCheckpointStage.Select -> {
                Heading(R.string.run_mode_select_title, stringResource(R.string.run_mode_select_body))
                RunModeOptions(selected = null, linuxSupported = stage.canChooseLinux, onSelect = actions.onChoose)
            }
            is RunModeCheckpointStage.Unavailable -> {
                Heading(R.string.run_mode_unavailable_title,
                    stringResource(R.string.run_mode_unavailable_body, stringResource(stage.reason.textRes())))
                RunModeActions(runModeCheckpointActions(stage, canExport), actions)
            }
            is RunModeCheckpointStage.Working -> {
                Heading(workingTitle(stage), stage.step?.let { stringResource(it) }.orEmpty())
                val fraction = stage.fraction
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is RunModeCheckpointStage.RepairFailed -> {
                Heading(
                    if (stage.canReset) R.string.run_mode_repair_failed_title else R.string.run_mode_rebuild_failed_title,
                    stringResource(if (stage.canReset) R.string.run_mode_repair_failed_body else R.string.run_mode_rebuild_failed_body),
                )
                stage.failure?.let { failure ->
                    Text(stringResource(linuxFailureText(failure.code)), style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (stage.canReset) stage.loss?.let { LossSummary(it) }
                RunModeActions(runModeCheckpointActions(stage, canExport), actions)
            }
            RunModeCheckpointStage.ExportChoice -> {
                Heading(R.string.run_mode_action_export, "")
                RunModeActions(runModeCheckpointActions(stage, canExport), actions)
            }
            RunModeCheckpointStage.ExportOffer -> {
                Heading(R.string.run_mode_export_offer_title, stringResource(R.string.run_mode_export_offer_body))
                RunModeActions(runModeCheckpointActions(stage, canExport), actions)
            }
            is RunModeCheckpointStage.Exported -> {
                val report = stage.report
                val complete = report != null && report.complete
                Heading(if (complete) R.string.run_mode_export_done_title else R.string.run_mode_export_partial_title, "")
                if (report == null) {
                    Body(stringResource(R.string.run_mode_export_none))
                } else {
                    val location = if (stage.privateTarget) stringResource(R.string.run_mode_export_private_location) else report.destination
                    Body(pluralStringResource(R.plurals.run_mode_export_files, report.filesCopied.toInt().coerceAtLeast(0),
                        report.filesCopied, linuxBytes(report.bytesCopied), location))
                    if (report.failures.isNotEmpty()) {
                        Body(stringResource(R.string.run_mode_export_failures))
                        report.failures.take(MAX_FAILURES_SHOWN).forEach { failure ->
                            InfinityMetaLine(listOf(failure.path.breakableIdentifier(), stringResource(failure.reason.textRes())),
                                style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (report.failures.size > MAX_FAILURES_SHOWN) Text("…", style = InfinityTypography.bodySmall)
                    }
                }
                RunModeActions(runModeCheckpointActions(stage, canExport), actions)
            }
        }
        message?.let { Text(it, style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

private const val MAX_FAILURES_SHOWN = 20

@StringRes
private fun RunModeUnavailableReason.textRes(): Int = when (this) {
    RunModeUnavailableReason.NOT_INSTALLED -> R.string.run_mode_reason_not_installed
    RunModeUnavailableReason.INTERRUPTED -> R.string.run_mode_reason_interrupted
    RunModeUnavailableReason.BROKEN -> R.string.run_mode_reason_broken
    RunModeUnavailableReason.BUSY -> R.string.run_mode_reason_busy
    RunModeUnavailableReason.UNSUPPORTED -> R.string.run_mode_reason_unsupported
}

@StringRes
private fun LinuxRunModeExportFailureReason.textRes(): Int = when (this) {
    LinuxRunModeExportFailureReason.LINK_SKIPPED -> R.string.run_mode_export_reason_link
    LinuxRunModeExportFailureReason.UNSUPPORTED_TYPE -> R.string.run_mode_export_reason_type
    LinuxRunModeExportFailureReason.IO -> R.string.run_mode_export_reason_io
    LinuxRunModeExportFailureReason.CHANGED -> R.string.run_mode_export_reason_changed
    LinuxRunModeExportFailureReason.SOURCE_UNAVAILABLE -> R.string.run_mode_export_reason_source
}

@Composable
private fun Heading(@StringRes title: Int, body: String) {
    Text(stringResource(title), style = InfinityTypography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    if (body.isNotEmpty()) Body(body)
}

@Composable
private fun Body(text: String) {
    Text(text, style = InfinityTypography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LossSummary(loss: LinuxResetLossSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
        Body(stringResource(R.string.run_mode_loss_summary, linuxBytes(loss.rootfsBytes), linuxBytes(loss.rootHomeBytes), linuxBytes(loss.otherHomeBytes)))
        if (loss.installedPackages.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.run_mode_loss_packages, loss.installedPackages.size, loss.installedPackages.size),
                style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loss.unreadablePaths.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.run_mode_loss_unreadable, loss.unreadablePaths.size, loss.unreadablePaths.size),
                style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 检查点操作区里的单个操作；重置是唯一的危险操作。 */
internal enum class RunModeCheckpointAction {
    INSTALL, REPAIR, USE_BUILTIN, EXPORT, LATER, RESET, EXPORT_PRIVATE, EXPORT_EXTERNAL, CANCEL, DONE,
}

/** [alternatives] 为上组替代方案，[dismiss] 为“稍后/取消”，[primary] 为主操作；选择与进行中阶段三者皆空。 */
internal data class RunModeCheckpointActionSet(
    val alternatives: List<RunModeCheckpointAction> = emptyList(),
    val dismiss: RunModeCheckpointAction? = null,
    val primary: RunModeCheckpointAction? = null,
)

/** 各阶段操作区的按钮：除选择、进行中与已导出外都能“稍后/取消”离开；重置只在仍可重置时出现。 */
internal fun runModeCheckpointActions(stage: RunModeCheckpointStage, canExport: Boolean): RunModeCheckpointActionSet {
    val export = RunModeCheckpointAction.EXPORT.takeIf { canExport }
    return when (stage) {
        is RunModeCheckpointStage.Select, is RunModeCheckpointStage.Working -> RunModeCheckpointActionSet()
        is RunModeCheckpointStage.Unavailable -> RunModeCheckpointActionSet(
            alternatives = listOfNotNull(RunModeCheckpointAction.USE_BUILTIN, export),
            dismiss = RunModeCheckpointAction.LATER,
            primary = when (stage.reason) {
                RunModeUnavailableReason.NOT_INSTALLED -> RunModeCheckpointAction.INSTALL
                RunModeUnavailableReason.UNSUPPORTED, RunModeUnavailableReason.BUSY -> null
                RunModeUnavailableReason.INTERRUPTED, RunModeUnavailableReason.BROKEN -> RunModeCheckpointAction.REPAIR
            },
        )
        is RunModeCheckpointStage.RepairFailed -> RunModeCheckpointActionSet(
            alternatives = listOfNotNull(export, RunModeCheckpointAction.USE_BUILTIN),
            dismiss = RunModeCheckpointAction.LATER,
            primary = RunModeCheckpointAction.RESET.takeIf { stage.canReset },
        )
        RunModeCheckpointStage.ExportChoice -> RunModeCheckpointActionSet(
            alternatives = listOf(RunModeCheckpointAction.EXPORT_EXTERNAL),
            dismiss = RunModeCheckpointAction.CANCEL,
            primary = RunModeCheckpointAction.EXPORT_PRIVATE,
        )
        RunModeCheckpointStage.ExportOffer -> RunModeCheckpointActionSet(
            dismiss = RunModeCheckpointAction.LATER,
            primary = RunModeCheckpointAction.EXPORT,
        )
        is RunModeCheckpointStage.Exported -> RunModeCheckpointActionSet(primary = RunModeCheckpointAction.DONE)
    }
}

@StringRes
private fun RunModeCheckpointAction.textRes(): Int = when (this) {
    RunModeCheckpointAction.INSTALL -> R.string.run_mode_action_install
    RunModeCheckpointAction.REPAIR -> R.string.run_mode_action_repair
    RunModeCheckpointAction.USE_BUILTIN -> R.string.run_mode_action_builtin
    RunModeCheckpointAction.EXPORT -> R.string.run_mode_action_export
    RunModeCheckpointAction.LATER -> R.string.run_mode_action_later
    RunModeCheckpointAction.RESET -> R.string.run_mode_action_reset
    RunModeCheckpointAction.EXPORT_PRIVATE -> R.string.run_mode_export_private
    RunModeCheckpointAction.EXPORT_EXTERNAL -> R.string.run_mode_export_external
    RunModeCheckpointAction.CANCEL -> R.string.common_btn_cancel
    RunModeCheckpointAction.DONE -> R.string.run_mode_done
}

private fun RunModeCheckpointAction.tag(): String? = when (this) {
    RunModeCheckpointAction.INSTALL, RunModeCheckpointAction.REPAIR,
    RunModeCheckpointAction.EXPORT_PRIVATE, RunModeCheckpointAction.DONE -> RunModeTags.PRIMARY
    RunModeCheckpointAction.USE_BUILTIN -> RunModeTags.BUILTIN
    RunModeCheckpointAction.EXPORT -> RunModeTags.EXPORT
    RunModeCheckpointAction.RESET -> RunModeTags.RESET
    RunModeCheckpointAction.LATER, RunModeCheckpointAction.EXPORT_EXTERNAL, RunModeCheckpointAction.CANCEL -> null
}

private fun RunModeCheckpointAction.onClick(actions: RunModeCheckpointActions): () -> Unit = when (this) {
    RunModeCheckpointAction.INSTALL, RunModeCheckpointAction.REPAIR -> actions.onRepair
    RunModeCheckpointAction.USE_BUILTIN -> actions.onUseBuiltin
    RunModeCheckpointAction.EXPORT -> actions.onExport
    RunModeCheckpointAction.LATER -> actions.onLater
    RunModeCheckpointAction.RESET -> actions.onReset
    RunModeCheckpointAction.EXPORT_PRIVATE -> actions.onExportPrivate
    RunModeCheckpointAction.EXPORT_EXTERNAL -> actions.onExportExternal
    RunModeCheckpointAction.CANCEL -> actions.onBack
    RunModeCheckpointAction.DONE -> actions.onDone
}

/**
 * 操作区分两组：上组是替代方案（中性胶囊），下组是“稍后/取消”与主操作，主操作在右。
 * 每组能以单行文字等分并排时并排，否则竖排各占满宽；按钮文字不折行。
 */
@Composable
private fun RunModeActions(set: RunModeCheckpointActionSet, actions: RunModeCheckpointActions) {
    @Composable
    fun Pill(action: RunModeCheckpointAction, tone: InfinityPillTone) {
        val tag = action.tag()
        InfinityPillButton(
            text = stringResource(action.textRes()),
            onClick = action.onClick(actions),
            tone = tone,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
        if (set.alternatives.isNotEmpty()) {
            InfinityPillRow(Modifier.fillMaxWidth(), reverseWhenStacked = false) {
                set.alternatives.forEach { Pill(it, InfinityPillTone.NEUTRAL) }
            }
        }
        if (set.dismiss != null || set.primary != null) {
            InfinityPillRow(Modifier.fillMaxWidth()) {
                set.dismiss?.let { Pill(it, InfinityPillTone.NEUTRAL) }
                set.primary?.let {
                    Pill(it, if (it == RunModeCheckpointAction.RESET) InfinityPillTone.DESTRUCTIVE else InfinityPillTone.PRIMARY)
                }
            }
        }
    }
}
