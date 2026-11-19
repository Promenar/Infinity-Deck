package com.promenar.infinity.ui.developer

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.startup.diagnostics.SessionTraceExporter
import com.promenar.infinity.startup.diagnostics.TraceSessionSummary
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySectionFooterCard
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.utils.InfinityLogger
import java.io.File
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 导航入口：装载最近会话轨迹清单，处理 ADB 调试桥开关与诊断/轨迹分享。 */
@Composable
fun DeveloperRoute(
    onNavigateBack: () -> Unit,
    onOpenVisualLab: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as? InfinityApplication
    val store = remember(context) { DeveloperModeStore.get(context) }
    val adbBridgeEnabled by store.adbBridgeEnabled.collectAsState()
    val scope = rememberCoroutineScope()
    var traceSessions by remember { mutableStateOf<List<TraceSessionSummary>>(emptyList()) }
    var exporting by remember { mutableStateOf(false) }

    suspend fun loadTraceSessions(): List<TraceSessionSummary> = withContext(Dispatchers.IO) {
        if (app == null) return@withContext emptyList()
        app.database.withTransaction {
            SessionTraceExporter(
                app.database.sessionDao(),
                app.database.messageDao(),
                app.database.toolExecutionLedgerDao(),
            ).recentSessionSummaries(20)
        }
    }

    LaunchedEffect(app) {
        traceSessions = try {
            loadTraceSessions()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    val traceChooserTitle = stringResource(R.string.devmode_trace_chooser)
    val diagChooserTitle = stringResource(R.string.devmode_diag_chooser)

    DeveloperScreen(
        onNavigateBack = onNavigateBack,
        onOpenVisualLab = onOpenVisualLab,
        onDisable = {
            store.setEnabled(false)
            android.widget.Toast.makeText(context, R.string.devmode_disabled, android.widget.Toast.LENGTH_SHORT).show()
            onNavigateBack()
        },
        packageName = context.packageName,
        adbBridgeEnabled = adbBridgeEnabled,
        onAdbBridgeToggle = store::setAdbBridgeEnabled,
        traceSessions = traceSessions,
        exporting = exporting,
        onRefreshTraceSessions = {
            scope.launch {
                traceSessions = try {
                    loadTraceSessions()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
        },
        onExportSessionTrace = { sessionId ->
            if (!exporting) {
                exporting = true
                scope.launch {
                    try {
                        val file = InfinityLogger.prepareSessionTraceExport(context, sessionId)
                        if (file != null) {
                            shareDiagnosticJsonl(context, file, traceChooserTitle)
                        } else {
                            android.widget.Toast.makeText(context, R.string.devmode_trace_export_failed, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        exporting = false
                    }
                }
            }
        },
        onExportGlobalDiagnostics = {
            if (!exporting) {
                exporting = true
                scope.launch {
                    try {
                        val file = InfinityLogger.prepareDiagnosticExport(context)
                        shareDiagnosticJsonl(context, file, diagChooserTitle)
                    } finally {
                        exporting = false
                    }
                }
            }
        },
    )
}

private fun shareDiagnosticJsonl(context: Context, file: File, chooserTitle: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/x-ndjson"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

/**
 * 开发者页：版本号连点解锁后出现在设置首页。
 * 提供视觉实验室、会话执行轨迹 Trace JSONL 导出、全局运行诊断包导出、Release ADB 调试桥控制与开发者总开关。
 */
@Composable
fun DeveloperScreen(
    onNavigateBack: () -> Unit,
    onOpenVisualLab: () -> Unit,
    onDisable: () -> Unit,
    packageName: String = "com.promenar.infinity",
    adbBridgeEnabled: Boolean = true,
    onAdbBridgeToggle: (Boolean) -> Unit = {},
    traceSessions: List<TraceSessionSummary> = emptyList(),
    exporting: Boolean = false,
    onRefreshTraceSessions: () -> Unit = {},
    onExportSessionTrace: (String) -> Unit = {},
    onExportGlobalDiagnostics: () -> Unit = {},
    initialTraceSheetOpen: Boolean = false,
) {
    var traceSheetOpen by remember { mutableStateOf(initialTraceSheetOpen) }
    var selectedSessionId by remember(traceSessions) { mutableStateOf(traceSessions.firstOrNull()?.sessionId) }
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getDateFormat(context) }
    val latest = traceSessions.firstOrNull()
    val traceSummarySegments = if (latest == null) {
        listOf("Trace JSONL", stringResource(R.string.devmode_trace_empty))
    } else {
        listOfNotNull(
            "Trace JSONL",
            latest.title,
            latest.runMode,
            pluralStringResource(R.plurals.devmode_trace_calls_count, latest.toolCallCount, latest.toolCallCount),
        )
    }

    InfinitySettingsPageLayout(
        title = stringResource(R.string.devmode_title),
        onBack = onNavigateBack,
        modifier = Modifier.testTag("developer_root"),
    ) { contentPadding ->
        Box(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
            ) {
                InfinitySettingsSection(title = stringResource(R.string.devmode_section_tools)) {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Science,
                        title = stringResource(R.string.lab_title),
                        subtitle = stringResource(R.string.lab_entry_desc),
                        subtitleMaxLines = 2,
                        onClick = onOpenVisualLab,
                        showChevron = true,
                        modifier = Modifier.testTag("developer_visual_lab"),
                    )
                }

                InfinitySettingsSection(title = stringResource(R.string.devmode_section_diagnostics)) {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Timeline,
                        title = stringResource(R.string.devmode_trace_title),
                        subtitleSegments = traceSummarySegments,
                        subtitleMaxLines = 2,
                        enabled = !exporting && traceSessions.isNotEmpty(),
                        onClick = {
                            onRefreshTraceSessions()
                            selectedSessionId = selectedSessionId?.takeIf { id -> traceSessions.any { it.sessionId == id } }
                                ?: traceSessions.firstOrNull()?.sessionId
                            traceSheetOpen = true
                        },
                        showChevron = true,
                        modifier = Modifier.testTag("developer_export_trace"),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    InfinitySettingsItem(
                        icon = Icons.Rounded.BugReport,
                        title = stringResource(R.string.devmode_diag_pack_title),
                        subtitleSegments = listOf(
                            stringResource(R.string.devmode_diag_pack_seg_snapshot),
                            stringResource(R.string.devmode_diag_pack_seg_size),
                            stringResource(R.string.devmode_diag_pack_seg_scope),
                        ),
                        subtitleMaxLines = 2,
                        enabled = !exporting,
                        onClick = onExportGlobalDiagnostics,
                        trailingContent = {
                            Icon(
                                imageVector = Icons.Rounded.FileDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier.testTag("developer_export_global"),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    SettingsToggle(
                        title = stringResource(R.string.devmode_adb_bridge_title),
                        description = stringResource(R.string.devmode_adb_bridge_desc),
                        checked = adbBridgeEnabled,
                        onCheckedChange = onAdbBridgeToggle,
                        icon = Icons.Rounded.Terminal,
                        descriptionMaxLines = 3,
                        modifier = Modifier.testTag("developer_adb_bridge_toggle"),
                    )
                }

                InfinitySectionFooterCard(
                    text = stringResource(R.string.devmode_adb_footer, packageName),
                    icon = Icons.Rounded.Code,
                    selectable = true,
                )

                InfinitySettingsSection(title = stringResource(R.string.devmode_section_mode)) {
                    SettingsToggle(
                        title = stringResource(R.string.devmode_toggle_title),
                        description = stringResource(R.string.devmode_toggle_desc),
                        checked = true,
                        onCheckedChange = { if (!it) onDisable() },
                        icon = Icons.Rounded.DeveloperMode,
                        descriptionMaxLines = 3,
                        modifier = Modifier.testTag("developer_mode_toggle"),
                    )
                }
            }
        }
    }

    InfinityBottomSheet(
        show = traceSheetOpen,
        onDismiss = { traceSheetOpen = false },
        title = stringResource(R.string.devmode_trace_sheet_title),
        height = InfinitySheetHeight.Content,
    ) {
        TraceSessionPickerContent(
            sessions = traceSessions,
            selectedSessionId = selectedSessionId,
            formatDate = { updatedAt -> timeFormat.format(Date(updatedAt)) },
            onSelect = { selectedSessionId = it },
            onDismiss = { traceSheetOpen = false },
            onConfirm = { id ->
                traceSheetOpen = false
                onExportSessionTrace(id)
            },
        )
    }
}

@Composable
internal fun TraceSessionPickerContent(
    sessions: List<TraceSessionSummary>,
    selectedSessionId: String?,
    formatDate: (Long) -> String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Large),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        if (sessions.isEmpty()) {
            Text(
                text = stringResource(R.string.devmode_trace_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Medium),
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = InfinitySpacing.Medium)
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                sessions.forEachIndexed { index, summary ->
                    val selected = summary.sessionId == selectedSessionId
                    val segments = listOfNotNull(
                        summary.runMode,
                        formatDate(summary.updatedAt),
                        pluralStringResource(R.plurals.devmode_trace_calls_count, summary.toolCallCount, summary.toolCallCount),
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .semantics { this.selected = selected }
                            .clickable(role = Role.RadioButton) { onSelect(summary.sessionId) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else Color.Transparent,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = summary.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            InfinityMetaLine(
                                segments = segments,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (index < sessions.lastIndex && !selected) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            }
        }
        InfinityPillRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.ScreenHorizontal),
            spacing = 10.dp,
        ) {
            InfinityPillButton(
                text = stringResource(R.string.devmode_trace_cancel),
                onClick = onDismiss,
            )
            InfinityPillButton(
                text = stringResource(R.string.devmode_trace_share),
                onClick = { selectedSessionId?.let(onConfirm) },
                tone = InfinityPillTone.PRIMARY,
                enabled = selectedSessionId != null,
            )
        }
    }
}
