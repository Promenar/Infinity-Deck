package com.promenar.infinity.ui.document.external

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DriveFolderUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.R
import com.promenar.infinity.data.document.external.ExternalDocumentFailure
import com.promenar.infinity.data.document.external.ExternalMarkdown
import com.promenar.infinity.data.document.external.ExternalSendMode
import com.promenar.infinity.share.core.ShareKnowledgeFolder
import com.promenar.infinity.share.core.ShareSessionChoice
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinityPropertyRow
import com.promenar.infinity.ui.common.InfinityPropertySheet
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.document.DocumentBody
import com.promenar.infinity.ui.document.DocumentBodyState
import com.promenar.infinity.ui.document.DocumentViewerAction
import com.promenar.infinity.ui.document.DocumentViewerPanel
import com.promenar.infinity.ui.document.DocumentViewerScaffold
import com.promenar.infinity.ui.document.DocumentViewerTags
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 外部打开的 Markdown 查看器；状态不可见时不占位。放在根布局的最上层。 */
@Composable
fun ExternalDocumentViewer(viewModel: ExternalDocumentViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.visible) return
    val context = LocalContext.current
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExternalMarkdown.MARKDOWN_MIME)) { uri ->
        if (uri != null) viewModel.saveAs { context.contentResolver.openOutputStream(uri, "wt") }
    }
    BackHandler { if (state.panel != null) viewModel.panelBack() else viewModel.close() }
    ExternalDocumentViewerContent(
        state = state,
        callbacks = ExternalDocumentViewerCallbacks(
            onClose = viewModel::close,
            onSaveAs = { saveAs.launch(viewModel.suggestedName() ?: "document.md") },
            onSaveToStorage = viewModel::openFolderPanel,
            onOpenInLibrary = viewModel::openInLibrary,
            onSend = viewModel::openSendPanel,
            onPanelBack = viewModel::panelBack,
            onDismissPanel = viewModel::dismissPanel,
            onEnterFolder = viewModel::enterFolder,
            onFolderUp = viewModel::folderUp,
            onConfirmFolder = viewModel::confirmFolder,
            onSendMode = viewModel::setSendMode,
            onPickSession = viewModel::sendTo,
            onNewSession = viewModel::startNewSession,
            onPickAgent = viewModel::createSessionAndSend,
        ),
    )
}

class ExternalDocumentViewerCallbacks(
    val onClose: () -> Unit = {},
    val onSaveAs: () -> Unit = {},
    val onSaveToStorage: () -> Unit = {},
    val onOpenInLibrary: () -> Unit = {},
    val onSend: () -> Unit = {},
    val onPanelBack: () -> Unit = {},
    val onDismissPanel: () -> Unit = {},
    val onEnterFolder: (ShareKnowledgeFolder) -> Unit = {},
    val onFolderUp: () -> Unit = {},
    val onConfirmFolder: () -> Unit = {},
    val onSendMode: (ExternalSendMode) -> Unit = {},
    val onPickSession: (String) -> Unit = {},
    val onNewSession: () -> Unit = {},
    val onPickAgent: (String) -> Unit = {},
)

/** 无状态界面，便于预览与截图测试。 */
@Composable
fun ExternalDocumentViewerContent(state: ExternalDocumentUiState, callbacks: ExternalDocumentViewerCallbacks) {
    val context = LocalContext.current
    val document = state.document
    val sizeLabel = document?.let { Formatter.formatShortFileSize(context, it.sizeBytes) }
    val storageLabel = stringResource(R.string.doc_viewer_storage)
    val location = state.saved?.let { saved -> storageLocationLabel(storageLabel, saved.folderPath) }
        ?: stringResource(R.string.doc_viewer_external_file)
    val title = document?.name ?: stringResource(R.string.doc_viewer_external_file)
    val subtitle = sizeLabel?.let { stringResource(R.string.doc_viewer_meta, location, it) }
    val ready = document != null && !state.busy

    val actions = if (document == null) emptyList() else listOf(
        if (state.saved == null) {
            DocumentViewerAction(
                label = stringResource(R.string.doc_viewer_save_to_storage),
                icon = Icons.Rounded.DriveFolderUpload,
                onClick = callbacks.onSaveToStorage,
                enabled = ready,
                testTag = ExternalDocumentViewerTags.SAVE_TO_STORAGE,
            )
        } else {
            DocumentViewerAction(
                label = stringResource(R.string.doc_viewer_open_in_library),
                icon = Icons.Rounded.FolderOpen,
                onClick = callbacks.onOpenInLibrary,
                enabled = ready,
                testTag = ExternalDocumentViewerTags.OPEN_IN_LIBRARY,
            )
        },
        DocumentViewerAction(
            label = stringResource(R.string.doc_viewer_send_to_session),
            icon = Icons.AutoMirrored.Rounded.Send,
            onClick = callbacks.onSend,
            tone = InfinityPillTone.PRIMARY,
            enabled = ready,
            testTag = ExternalDocumentViewerTags.SEND,
        ),
    )
    val secondary = if (document == null) emptyList() else listOf(
        DocumentViewerAction(
            label = stringResource(R.string.doc_viewer_save_as),
            icon = Icons.Rounded.SaveAs,
            onClick = callbacks.onSaveAs,
            enabled = ready,
            testTag = ExternalDocumentViewerTags.SAVE_AS,
        ),
    )
    val body = when {
        state.loading -> DocumentBodyState.Loading
        state.failure != null -> DocumentBodyState.Message(stringResource(state.failure.messageRes()))
        document == null -> DocumentBodyState.Loading
        document.chunks == null -> DocumentBodyState.Message(stringResource(R.string.doc_viewer_body_too_large))
        else -> DocumentBodyState.Markdown(document.chunks)
    }

    val details: @Composable ColumnScope.() -> Unit = {
        InfinityPropertySheet {
            InfinityPropertyRow(stringResource(R.string.doc_viewer_detail_name), title)
            InfinityPropertyRow(stringResource(R.string.doc_viewer_detail_size), sizeLabel.orEmpty())
            InfinityPropertyRow(stringResource(R.string.doc_viewer_detail_location), location)
        }
    }

    DocumentViewerScaffold(
        title = title,
        subtitle = subtitle,
        onClose = callbacks.onClose,
        modifier = Modifier.testTag(DocumentViewerTags.ROOT),
        actions = actions,
        secondaryActions = secondary,
        details = details.takeIf { document != null },
        panel = state.panel?.let { panel -> panelFor(panel, state.busy, callbacks) },
        body = { modifier -> DocumentBody(body, modifier) },
    )
}

@Composable
private fun panelFor(panel: ExternalDocumentPanel, busy: Boolean, callbacks: ExternalDocumentViewerCallbacks): DocumentViewerPanel =
    when (panel) {
        is ExternalDocumentPanel.Folder -> DocumentViewerPanel(
            title = stringResource(R.string.doc_viewer_save_to_storage),
            onBack = callbacks.onPanelBack,
            onDismiss = callbacks.onDismissPanel,
            testTag = ExternalDocumentViewerTags.FOLDER_PANEL,
            footer = {
                InfinityPillRow(Modifier.fillMaxWidth()) {
                    InfinityPillButton(
                        text = stringResource(R.string.doc_viewer_cancel),
                        onClick = callbacks.onDismissPanel,
                    )
                    InfinityPillButton(
                        text = stringResource(R.string.doc_viewer_save_here),
                        onClick = callbacks.onConfirmFolder,
                        tone = InfinityPillTone.PRIMARY,
                        enabled = !busy && !panel.loading && !panel.failed,
                        modifier = Modifier.testTag(ExternalDocumentViewerTags.SAVE_HERE),
                    )
                }
            },
        ) { FolderPanelContent(panel, busy, callbacks) }
        is ExternalDocumentPanel.Send -> DocumentViewerPanel(
            title = if (panel.pickingAgent) stringResource(R.string.doc_viewer_choose_agent) else stringResource(R.string.doc_viewer_send_to_session),
            onBack = callbacks.onPanelBack,
            onDismiss = callbacks.onDismissPanel,
            testTag = ExternalDocumentViewerTags.SEND_PANEL,
        ) { SendPanelContent(panel, busy, callbacks) }
    }

@Composable
private fun FolderPanelContent(panel: ExternalDocumentPanel.Folder, busy: Boolean, callbacks: ExternalDocumentViewerCallbacks) {
    val storage = stringResource(R.string.doc_viewer_storage)
    Text(
        storageLocationLabel(storage, panel.trail.lastOrNull()?.path ?: "/"),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = InfinitySpacing.Small),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (panel.trail.isNotEmpty()) {
        PanelRow(
            text = stringResource(R.string.doc_viewer_folder_up),
            icon = Icons.Rounded.ArrowUpward,
            enabled = !busy && !panel.loading,
            testTag = ExternalDocumentViewerTags.FOLDER_UP,
            onClick = callbacks.onFolderUp,
        )
    }
    when {
        panel.loading -> Unit
        panel.failed -> PanelMessage(stringResource(R.string.doc_viewer_load_failed))
        panel.folders.isEmpty() -> PanelMessage(stringResource(R.string.doc_viewer_folder_empty))
        else -> panel.folders.forEach { folder ->
            PanelRow(
                text = folder.name,
                icon = Icons.Rounded.Folder,
                enabled = !busy,
                testTag = ExternalDocumentViewerTags.folder(folder.uuid),
                onClick = { callbacks.onEnterFolder(folder) },
            )
        }
    }
}

@Composable
private fun SendPanelContent(panel: ExternalDocumentPanel.Send, busy: Boolean, callbacks: ExternalDocumentViewerCallbacks) {
    if (panel.pickingAgent) {
        panel.agents.forEach { agent ->
            PanelRow(
                text = agent.name,
                icon = Icons.Rounded.SmartToy,
                enabled = !busy,
                testTag = ExternalDocumentViewerTags.agent(agent.id),
                onClick = { callbacks.onPickAgent(agent.id) },
            )
        }
        return
    }
    InfinitySegmentedControl(
        segments = listOf(
            InfinitySegment(
                value = ExternalSendMode.Attachment,
                label = stringResource(R.string.doc_viewer_mode_attachment),
                tag = ExternalDocumentViewerTags.MODE_ATTACHMENT,
                clickable = panel.attachmentAllowed,
            ),
            InfinitySegment(
                value = ExternalSendMode.Reference,
                label = stringResource(R.string.doc_viewer_mode_reference),
                tag = ExternalDocumentViewerTags.MODE_REFERENCE,
            ),
        ),
        selected = panel.mode,
        onSelect = callbacks.onSendMode,
        fillWidth = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    if (!panel.attachmentAllowed) {
        Text(
            stringResource(R.string.doc_viewer_reference_only),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = InfinitySpacing.XSmall).testTag(ExternalDocumentViewerTags.REFERENCE_ONLY),
        )
    }
    Column(Modifier.padding(top = InfinitySpacing.Small)) {
        if (panel.agents.isNotEmpty()) {
            PanelRow(
                text = stringResource(R.string.doc_viewer_new_session),
                icon = Icons.Rounded.Add,
                enabled = !busy && !panel.loading,
                testTag = ExternalDocumentViewerTags.NEW_SESSION,
                onClick = callbacks.onNewSession,
            )
        }
        when {
            panel.loading -> Unit
            panel.failed -> PanelMessage(stringResource(R.string.doc_viewer_load_failed))
            panel.sessions.isEmpty() -> PanelMessage(stringResource(R.string.doc_viewer_sessions_empty))
            else -> panel.sessions.forEach { session -> SessionRow(session, enabled = !busy, onClick = { callbacks.onPickSession(session.sessionId) }) }
        }
    }
}

@Composable
private fun SessionRow(session: ShareSessionChoice, enabled: Boolean, onClick: () -> Unit) {
    PanelRow(
        text = session.title,
        supporting = DateUtils.getRelativeTimeSpanString(session.updatedAt).toString(),
        icon = Icons.Rounded.ChatBubbleOutline,
        enabled = enabled,
        testTag = ExternalDocumentViewerTags.session(session.sessionId),
        onClick = onClick,
    )
}

@Composable
private fun PanelRow(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
    supporting: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (supporting == null) InfinitySpacing.RowSingleLineHeight else InfinitySpacing.RowTwoLineHeight)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = InfinitySpacing.Small)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.RowIconGap),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(InfinitySpacing.RowIconSize),
        )
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PanelMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = InfinitySpacing.Large),
    )
}

/** “存储空间 / 工作 / 周报”；根目录只显示“存储空间”。 */
internal fun storageLocationLabel(storage: String, folderPath: String): String {
    val segments = folderPath.split('/').filter(String::isNotBlank)
    return (listOf(storage) + segments).joinToString(" / ")
}

private fun ExternalDocumentFailure.messageRes(): Int = when (this) {
    ExternalDocumentFailure.TooLarge -> R.string.doc_viewer_failure_too_large
    ExternalDocumentFailure.Empty -> R.string.doc_viewer_failure_empty
    ExternalDocumentFailure.Unreadable -> R.string.doc_viewer_failure_unreadable
    ExternalDocumentFailure.NotText -> R.string.doc_viewer_failure_not_text
}

/** 外部文档通知对应的提示文字。 */
fun ExternalDocumentNotice.message(context: Context): String = when (this) {
    is ExternalDocumentNotice.Saved -> context.getString(
        R.string.doc_viewer_saved,
        folderPath.split('/').lastOrNull(String::isNotBlank) ?: context.getString(R.string.doc_viewer_storage),
    )
    ExternalDocumentNotice.SaveFailed -> context.getString(R.string.doc_viewer_save_failed)
    ExternalDocumentNotice.SavedAs -> context.getString(R.string.doc_viewer_saved_as)
    ExternalDocumentNotice.SaveAsFailed -> context.getString(R.string.doc_viewer_save_failed)
    ExternalDocumentNotice.SendFailed -> context.getString(R.string.doc_viewer_send_failed)
    ExternalDocumentNotice.CreateSessionFailed -> context.getString(R.string.doc_viewer_create_session_failed)
}

object ExternalDocumentViewerTags {
    const val SAVE_TO_STORAGE = "external_doc_save_to_storage"
    const val OPEN_IN_LIBRARY = "external_doc_open_in_library"
    const val SEND = "external_doc_send"
    const val SAVE_AS = "external_doc_save_as"
    const val FOLDER_PANEL = "external_doc_folder_panel"
    const val SEND_PANEL = "external_doc_send_panel"
    const val SAVE_HERE = "external_doc_save_here"
    const val FOLDER_UP = "external_doc_folder_up"
    const val NEW_SESSION = "external_doc_new_session"
    const val MODE_ATTACHMENT = "external_doc_mode_attachment"
    const val MODE_REFERENCE = "external_doc_mode_reference"
    const val REFERENCE_ONLY = "external_doc_reference_only"
    fun folder(uuid: String) = "external_doc_folder_$uuid"
    fun session(id: String) = "external_doc_session_$id"
    fun agent(id: String) = "external_doc_agent_$id"
}
