package com.promenar.infinity.ui.chat

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.promenar.infinity.R
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.mediagen.cloud.CloudHttpException
import com.promenar.infinity.data.mediagen.cloud.CloudMediaModelSource
import com.promenar.infinity.data.mediagen.cloud.GeminiMediaGenerationBackend
import com.promenar.infinity.data.model.CloudMediaApi
import com.promenar.infinity.data.model.effectiveMediaSettings
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.ui.common.InfinityNoticeAction
import com.promenar.infinity.ui.common.InfinityNoticeController
import com.promenar.infinity.ui.common.InfinityNoticeSeverity
import com.promenar.infinity.ui.media.MediaPlaybackCoordinator
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 气泡朗读按钮状态。 */
enum class ReadAloudPhase { IDLE, LOADING, PLAYING }

/** 朗读控制器对外暴露的精简状态：只记录当前活跃气泡 ID 与阶段。 */
@Immutable
data class ChatReadAloudUiState(
    val activeMessageId: String? = null,
    val phase: ReadAloudPhase = ReadAloudPhase.IDLE,
) {
    fun phaseFor(messageId: String): ReadAloudPhase =
        if (activeMessageId == messageId) phase else ReadAloudPhase.IDLE
}

/**
 * 将助手正文 Markdown 温和清洗为适合自然朗读的纯文本：
 * - 不包含思考过程或工具日志（调用方只传入最终正文）；
 * - 去除围栏代码块、Markdown 装饰符号、表格分隔行、裸 URL 与图片语法，保留链接可见文字与自然段落停顿；
 * - 若清洗后无有效朗读内容则返回空串，由调用方触发“无正文不朗读”门禁。
 */
internal fun speechReadableText(markdown: String): String {
    if (markdown.isBlank()) return ""
    var text = markdown.replace("\r\n", "\n")
    // 1. 去掉围栏代码块（代码符号极不利于语音朗读）。
    text = text.replace(Regex("""```[\s\S]*?```"""), " ")
    text = text.replace(Regex("""~~~[\s\S]*?~~~"""), " ")
    // 2. 去掉图片语法 ![alt](url)，避免读出路径或链接。
    text = text.replace(Regex("""!\[[^\]]*]\([^)]*\)"""), " ")
    // 3. 保留普通链接 [可见文案](url) 的可见文案。
    text = text.replace(Regex("""\[([^]]+)]\([^)]+\)"""), "$1")
    // 4. 去掉行内反引号但保留字面词。
    text = text.replace(Regex("""`([^`]+)`"""), "$1")
    // 5. 去掉裸 HTTP/HTTPS URL。
    text = text.replace(Regex("""https?://\S+"""), " ")
    // 6. 去掉简单 HTML 标签。
    text = text.replace(Regex("""</?[a-zA-Z][^>]*>"""), " ")
    // 7. 逐行清理标题井号、引用符、无序列表前缀和 Markdown 表格分隔行。
    val lines = text.lines().mapNotNull { rawLine ->
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        if (Regex("""^\|?[\s:\-|]+\|?$""").matches(trimmed) && '-' in trimmed) return@mapNotNull null
        if (Regex("""^[-*_]{3,}$""").matches(trimmed)) return@mapNotNull null
        val cleaned = trimmed
            .replace(Regex("""^#{1,6}\s+"""), "")
            .replace(Regex("""^>+\s*"""), "")
            .replace(Regex("""^[-*+]\s+"""), "")
            .replace(Regex("""^\d+[.)]\s+"""), "")
            .replace("|", "，")
            .replace(Regex("""\*\*\*([^*]+)\*\*\*"""), "$1")
            .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
            .replace(Regex("""__([^_]+)__"""), "$1")
            .replace(Regex("""~~([^~]+)~~"""), "$1")
            .replace(Regex("""(?<!\w)\*([^*\n]+)\*(?!\w)"""), "$1")
            .replace(Regex("""\s+"""), " ")
            .trim()
        cleaned.takeIf { it.any { ch -> ch.isLetterOrDigit() } }
    }
    return lines.joinToString("\n").trim()
}

internal fun readAloudFailureMessage(error: Throwable): String {
    if (error is CloudHttpException) {
        return when (error.status) {
            401, 403 -> "提供商密钥无效或无权访问此语音模型"
            404 -> "提供商未找到该语音模型或接口不支持"
            429 -> "请求过于频繁或配额不足"
            in 500..599 -> "语音服务暂时不可用（HTTP ${error.status}）"
            else -> "语音接口返回 HTTP ${error.status}"
        }
    }
    return error.message?.takeIf { it.isNotBlank() } ?: "未知错误"
}

private val READ_ALOUD_APIS = setOf(
    CloudMediaApi.GEMINI_TTS,
    CloudMediaApi.MINIMAX_SPEECH,
    CloudMediaApi.OPENAI_SPEECH,
)

/**
 * 会话级朗读控制器：
 * - 强制前置检查：无正文不朗读、未设置默认语音模型不朗读，均通过全站顶部 [InfinityNoticeController] 提醒；
 * - 同一正文+模型+音色命中临时缓存时秒播不重复计费；
 * - 通过 [MediaPlaybackCoordinator.Shared] 保证与全局媒体播放器互斥，切出会话或退后台自动停止释放。
 */
@Stable
internal class ChatReadAloudController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val providerManager: ProviderManager,
    private val cloudModels: CloudMediaModelSource,
    private val backend: () -> GeminiMediaGenerationBackend,
    private val noticeController: () -> InfinityNoticeController?,
    private val onOpenDefaultModels: () -> Unit,
    private val coordinator: MediaPlaybackCoordinator = MediaPlaybackCoordinator.Shared,
) {
    private val playbackId = "chat-read-aloud"
    private val cacheDir = File(context.cacheDir, "chat_read_aloud")
    private val cachedFiles = LinkedHashMap<String, File>(16, 0.75f, true)

    var uiState by mutableStateOf(ChatReadAloudUiState())
        private set

    private var activeJob: Job? = null
    private var player: ExoPlayer? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                coordinator.onStarted(playbackId)
            } else if (uiState.phase == ReadAloudPhase.PLAYING) {
                val p = player
                if (p == null || p.playbackState == Player.STATE_ENDED || p.playbackState == Player.STATE_IDLE || !p.playWhenReady) {
                    coordinator.onStopped(playbackId)
                    uiState = ChatReadAloudUiState()
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                coordinator.onStopped(playbackId)
                uiState = ChatReadAloudUiState()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            coordinator.onStopped(playbackId)
            uiState = ChatReadAloudUiState()
            val detail = error.localizedMessage?.takeIf { it.isNotBlank() } ?: error.errorCodeName
            noticeController()?.show(
                severity = InfinityNoticeSeverity.ERROR,
                message = context.getString(R.string.chat_read_aloud_playback_failed, detail),
            )
        }
    }

    fun toggle(messageId: String, rawBodyMarkdown: String) {
        val current = uiState
        if (current.activeMessageId == messageId && current.phase != ReadAloudPhase.IDLE) {
            stop()
            return
        }
        stop()

        val cleanText = speechReadableText(rawBodyMarkdown)
        if (cleanText.isEmpty()) {
            noticeController()?.show(
                severity = InfinityNoticeSeverity.INFO,
                message = context.getString(R.string.chat_read_aloud_empty_body),
            )
            return
        }

        val defaultSpeechId = providerManager.speechModelId.value.trim()
        if (defaultSpeechId.isEmpty()) {
            noticeController()?.show(
                severity = InfinityNoticeSeverity.INFO,
                message = context.getString(R.string.chat_read_aloud_no_model),
                action = InfinityNoticeAction(
                    label = context.getString(R.string.chat_read_aloud_go_settings),
                    onClick = onOpenDefaultModels,
                ),
            )
            return
        }

        val modelInfo = providerManager.providerModels.value.firstOrNull { it.id == defaultSpeechId && it.enabled }
        val settings = modelInfo?.effectiveMediaSettings()
        val cloudModel = modelInfo?.let { cloudModels.find("cloud:${it.id}") }
        if (modelInfo == null || settings == null || cloudModel == null ||
            settings.group != MediaGroup.SPEECH || settings.api !in READ_ALOUD_APIS
        ) {
            noticeController()?.show(
                severity = InfinityNoticeSeverity.ERROR,
                message = context.getString(
                    R.string.chat_read_aloud_failed,
                    "默认语音模型未启用或协议不受支持，请在默认模型中重新选择",
                ),
                action = InfinityNoticeAction(
                    label = context.getString(R.string.chat_read_aloud_go_settings),
                    onClick = onOpenDefaultModels,
                ),
            )
            return
        }

        val voice = settings.options.voiceId.trim()
        val cacheKey = cacheKey(cleanText, cloudModel.providerId, cloudModel.modelId, voice)
        val hit = cachedFiles[cacheKey]?.takeIf { it.isFile && it.length() > 0L }
        if (hit != null) {
            playFile(messageId, hit)
            return
        }

        uiState = ChatReadAloudUiState(activeMessageId = messageId, phase = ReadAloudPhase.LOADING)
        activeJob = scope.launch {
            try {
                val result = backend().synthesizeSpeechDirect(
                    model = cloudModel,
                    text = cleanText,
                )
                val file = withContext(Dispatchers.IO) {
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    trimCache()
                    val target = File(cacheDir, "$cacheKey.${result.extension}")
                    target.writeBytes(result.bytes)
                    target
                }
                cachedFiles[cacheKey] = file
                if (uiState.activeMessageId == messageId && uiState.phase == ReadAloudPhase.LOADING) {
                    playFile(messageId, file)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (uiState.activeMessageId == messageId) {
                    uiState = ChatReadAloudUiState()
                }
                noticeController()?.show(
                    severity = InfinityNoticeSeverity.ERROR,
                    message = context.getString(R.string.chat_read_aloud_failed, readAloudFailureMessage(e)),
                )
            }
        }
    }

    fun stop() {
        activeJob?.cancel()
        activeJob = null
        player?.let { current ->
            runCatching { current.stop() }
            runCatching { current.clearMediaItems() }
        }
        coordinator.onStopped(playbackId)
        if (uiState.phase != ReadAloudPhase.IDLE) {
            uiState = ChatReadAloudUiState()
        }
    }

    fun release() {
        stop()
        coordinator.unregister(playbackId)
        player?.let { current ->
            current.removeListener(playerListener)
            current.release()
        }
        player = null
    }

    private fun playFile(messageId: String, file: File) {
        val current = ensurePlayer()
        uiState = ChatReadAloudUiState(activeMessageId = messageId, phase = ReadAloudPhase.PLAYING)
        current.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        current.prepare()
        current.playWhenReady = true
        coordinator.onStarted(playbackId)
    }

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(context.applicationContext)
        .build()
        .also { created ->
            created.setAudioAttributes(AudioAttributes.DEFAULT, true)
            created.setHandleAudioBecomingNoisy(true)
            created.addListener(playerListener)
            coordinator.register(playbackId) {
                scope.launch(Dispatchers.Main.immediate) { stop() }
            }
            player = created
        }

    private fun cacheKey(text: String, providerId: String, modelId: String, voice: String): String {
        val raw = "$providerId|$modelId|$voice|$text"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .take(24)
    }

    private fun trimCache() {
        val files = cacheDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        files.drop(12).forEach { runCatching { it.delete() } }
    }
}

@Composable
internal fun rememberChatReadAloudController(
    sessionId: String,
    providerManager: ProviderManager,
    cloudModels: CloudMediaModelSource,
    backend: () -> GeminiMediaGenerationBackend,
    noticeController: () -> InfinityNoticeController?,
    onOpenDefaultModels: () -> Unit,
): ChatReadAloudController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(sessionId) {
        ChatReadAloudController(
            context = context,
            scope = scope,
            providerManager = providerManager,
            cloudModels = cloudModels,
            backend = backend,
            noticeController = noticeController,
            onOpenDefaultModels = onOpenDefaultModels,
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return controller
}
