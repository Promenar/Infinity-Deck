package com.promenar.infinity.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.mediagen.comfy.ComfyHost
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.domain.usecase.IdGenerator
import com.promenar.infinity.domain.usecase.RagConfigPersistence
import com.promenar.infinity.domain.usecase.newSessionDefaults
import com.promenar.infinity.ui.chat.manager.SessionManager
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityHeaderTitle
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.hub.defaultNewSessionModelId
import com.promenar.infinity.ui.production.ProductionActionRow
import com.promenar.infinity.ui.production.ProductionButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** “交给智能体配置”弹层的打开请求：主机与预填的目标工作流。 */
internal data class ComfyConfigureRequest(val hostId: String, val target: String = "")

object ComfyConfigureTags {
    const val TARGET = "comfy_configure_target"
    const val COPY = "comfy_configure_copy"
    const val START = "comfy_configure_start"
}

/** 交给智能体的指令：现有工具合同不变，填写了目标工作流时附在末尾。 */
internal fun comfyAgentInstruction(base: String, targetLine: String?): String =
    if (targetLine == null) base else "$base\n\n$targetLine"

/** 媒体模型列表某分组末尾需要显示“交给智能体配置”的主机：该主机在这个分组下还没有模板。只对图像与视频分组给出。 */
internal fun comfyHostsMissingGroup(group: MediaGroup, hosts: List<ComfyHost>, templateGroupsByHost: Map<String, Set<MediaGroup>>): List<ComfyHost> =
    if (group != MediaGroup.IMAGE && group != MediaGroup.VIDEO) emptyList()
    else hosts.filter { group !in templateGroupsByHost[it.id].orEmpty() }

/**
 * 以默认智能体新建会话并预置首条用户消息，返回会话 id。默认智能体不存在时用第一个智能体。
 * 消息由会话页加载后照常发送；会话标题保持 [title]。
 */
internal suspend fun startAgentTaskSession(app: InfinityApplication, title: String, message: String): String = withContext(Dispatchers.IO) {
    val agents = app.agentRepository.observeAll().first()
    val agent = agents.firstOrNull { it.id == DEFAULT_AGENT_ID } ?: agents.first()
    val ragConfig = runCatching {
        RagConfigPersistence(app.getSharedPreferences("rag_settings", 0)).loadFullConfig()
    }.getOrElse { RagConfiguration() }
    val sessionId = IdGenerator.session()
    val session = newSessionDefaults(sessionId, agent.id, agent, defaultNewSessionModelId(), ragConfig, System.currentTimeMillis())
        .copy(title = title)
    SessionManager(app.chatStore, app.sessionRepository).addSession(session)
    app.sessionLaunchInbox.offer(sessionId, message)
    sessionId
}

private const val DEFAULT_AGENT_ID = "default"

/**
 * “交给智能体配置”弹层：可选的目标工作流、复制指令、以新会话配置。
 * 指令正文不在界面展示，发出后作为用户消息出现在会话里。
 */
@Composable
internal fun ComfyAgentConfigureSheet(
    request: ComfyConfigureRequest,
    state: MediaSettingsUiState,
    onDismiss: () -> Unit,
    onSession: (String) -> Unit,
) {
    val host = state.snapshot.hosts.firstOrNull { it.id == request.hostId } ?: return
    val context = LocalContext.current
    val app = context.applicationContext as InfinityApplication
    val scope = rememberCoroutineScope()
    var target by rememberSaveable(request) { mutableStateOf(request.target) }
    var starting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val base = stringResource(R.string.media_settings_instruction, host.name, host.id)
    val trimmed = target.trim()
    val targetLine = if (trimmed.isEmpty()) null else stringResource(R.string.media_settings_instruction_target, trimmed)
    val instruction = comfyAgentInstruction(base, targetLine)
    val sessionTitle = stringResource(R.string.media_settings_session_title, host.name)
    val templates = state.snapshot.templates.filter { it.template.hostId == host.id }
    val status = listOf(
        stringResource(R.string.media_settings_templates_count, templates.size),
        stringResource(R.string.media_settings_locked_count, templates.count { it.template.locked }),
        connectionLabel(state.connections[host.id] ?: ComfyConnection.UNKNOWN),
    )
    InfinityBottomSheet(show = true, onDismiss = onDismiss, dismissEnabled = !starting) {
        ComfyAgentConfigureContent(
            hostName = host.name,
            status = status,
            target = target,
            onTarget = { target = it },
            busy = starting,
            failed = failed,
            onCopy = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(host.name, instruction))
            },
            onStart = {
                starting = true
                failed = false
                scope.launch {
                    try {
                        val sessionId = startAgentTaskSession(app, sessionTitle, instruction)
                        onSession(sessionId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        starting = false
                    }
                }
            },
        )
    }
}

/** 弹层正文，无副作用，供预览与截图。 */
@Composable
internal fun ComfyAgentConfigureContent(
    hostName: String,
    status: List<String>,
    target: String,
    onTarget: (String) -> Unit,
    busy: Boolean,
    failed: Boolean,
    onCopy: () -> Unit,
    onStart: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InfinityHeaderTitle(
                text = stringResource(R.string.media_settings_configure_title, hostName),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            InfinityMetaLine(segments = status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = target,
            onValueChange = onTarget,
            label = { Text(stringResource(R.string.media_settings_target_workflow)) },
            placeholder = { Text(stringResource(R.string.media_settings_target_placeholder)) },
            singleLine = true,
            enabled = !busy,
            textStyle = MaterialTheme.typography.bodyLarge.forInput(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ComfyConfigureTags.TARGET),
        )
        if (failed) Text(
            stringResource(R.string.media_settings_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        ProductionActionRow(
            listOf(
                ProductionButton(stringResource(R.string.media_settings_copy_instruction), InfinityPillTone.NEUTRAL, !busy, ComfyConfigureTags.COPY, onCopy),
                ProductionButton(stringResource(R.string.media_settings_new_session), InfinityPillTone.PRIMARY, !busy, ComfyConfigureTags.START, onStart),
            ),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
