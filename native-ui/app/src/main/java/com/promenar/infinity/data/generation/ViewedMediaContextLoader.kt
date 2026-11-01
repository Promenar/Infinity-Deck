package com.promenar.infinity.data.generation

import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.remote.protocol.AudioInput
import com.promenar.infinity.data.remote.protocol.ImageInput
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.VideoInput
import com.promenar.infinity.domain.media.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.util.Base64

/** 字节仅存在于请求构建期；同一轮的工具循环每次都重新校验并加载。 */
class ViewedMediaContextLoader(
    private val viewer: WorkspaceMediaViewer,
    private val target: suspend (Session, String?) -> MediaViewTarget,
) {
    suspend fun attach(session: Session, messages: List<ProtocolMessage>, modelId: String?): List<ProtocolMessage> = withContext(Dispatchers.IO) {
        val cleanMessages = messages.map { message ->
            if (message.role == "tool" && message.name == "view_media") message.copy(imageUrls = null, audioData = null, videoData = null, mediaPaths = null) else message
        }
        val currentTurn = cleanMessages.indexOfLast { it.role == "user" && !it.isRuntimeInstruction }
        val records = session.messages.filter { it.role == MessageRole.TOOL && it.name == "view_media" }
            .associateBy { it.toolCallId }
        val resolvedTarget = if (cleanMessages.drop(currentTurn + 1).any { it.role == "tool" && !records[it.toolCallId]?.viewedMedia.isNullOrEmpty() }) target(session, modelId) else return@withContext cleanMessages
        var audioSent = false
        cleanMessages.mapIndexed { index, message ->
            coroutineContext.ensureActive()
            val viewed = records[message.toolCallId]?.viewedMedia.orEmpty()
            if (index <= currentTurn || message.role != "tool" || viewed.isEmpty()) return@mapIndexed message
            val (reloaded, loadNotes) = viewer.reload(session.id, viewed, resolvedTarget)
            val notes = loadNotes.toMutableList()
            val loaded = reloaded.mapNotNull { item ->
                if (resolvedTarget.protocol == ProtocolType.Google_VertexAI && item.parts.any { it.kind == MediaKind.AUDIO }) {
                    if (audioSent) {
                        notes += "${item.viewed.ref.path}：Vertex 单请求保守限制为 1 个音频文件，请分轮查看；未发送该音频。"
                        null
                    } else { audioSent = true; item }
                } else item
            }
            val parts = loaded.flatMap { it.parts }
            message.copy(
                content = (listOf(message.content) + notes).joinToString("\n"),
                imageUrls = parts.filter { it.kind == MediaKind.IMAGE }.map { ImageInput(base64 = Base64.getEncoder().encodeToString(it.bytes), mimeType = it.mimeType) }.takeIf { it.isNotEmpty() },
                audioData = parts.filter { it.kind == MediaKind.AUDIO }.map { AudioInput(Base64.getEncoder().encodeToString(it.bytes), it.mimeType) }.takeIf { it.isNotEmpty() },
                videoData = parts.filter { it.kind == MediaKind.VIDEO }.map { VideoInput(Base64.getEncoder().encodeToString(it.bytes), it.mimeType) }.takeIf { it.isNotEmpty() },
                mediaPaths = loaded.map { it.viewed.ref.path }.takeIf { it.isNotEmpty() },
            )
        }
    }
}
