package com.promenar.infinity.data.mediagen.queue

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.promenar.infinity.R
import com.promenar.infinity.background.mediagen.MediaGenerationForegroundService
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 明确注入真实注册表与后端；不安装模块时不执行媒体任务。 */
class MediaGenerationModule(
    context: Context,
    database: InfinityDatabase,
    val registry: MediaChannelRegistry,
    bindings: List<MediaBackendBinding>,
    concurrency: MediaQueueConcurrency = MediaQueueConcurrency(),
    requestAvailable: suspend (MediaGenerationRequest) -> Boolean = { true },
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _background = MutableStateFlow(MediaQueueBackgroundState.IDLE)
    val background: StateFlow<MediaQueueBackgroundState> = _background.asStateFlow()
    val queue = PersistentMediaGenerationQueue(RoomMediaTaskStore(database), registry, bindings,
        concurrency = concurrency,
        messages = MediaQueueMessages { failure -> this.context.getString(mediaQueueErrorResource(failure)) },
        wake = ::resume,
        requestAvailable = { request -> database.sessionDao().getById(request.sessionId) != null && requestAvailable(request) },
    )

    internal fun start() {
        scope.launch(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { resume() }
            })
        }
        resume()
    }

    /** FGS 启动受限或超时后保留持久任务；应用下次回前台时重新唤醒。 */
    fun resume() {
        scope.launch {
            try {
                if (!queue.hasRunnableTasks()) return@launch
                ContextCompat.startForegroundService(context, Intent(context, MediaGenerationForegroundService::class.java))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _background.value = MediaQueueBackgroundState.WAITING_FOR_FOREGROUND
            }
        }
    }
    internal fun serviceStarted() { _background.value = MediaQueueBackgroundState.RUNNING }
    internal fun serviceStopped(interrupted: Boolean) {
        _background.value = if (interrupted) MediaQueueBackgroundState.WAITING_FOR_FOREGROUND else MediaQueueBackgroundState.IDLE
    }
}

enum class MediaQueueBackgroundState { IDLE, RUNNING, WAITING_FOR_FOREGROUND }

fun mediaQueueErrorResource(failure: MediaTaskFailure): Int = when (failure) {
    MediaTaskFailure.REMOTE -> R.string.media_queue_remote_failed
    MediaTaskFailure.POLL -> R.string.media_queue_poll_failed
    MediaTaskFailure.DOWNLOAD -> R.string.media_queue_download_failed
    MediaTaskFailure.SUBMISSION_UNKNOWN -> R.string.media_queue_result_unknown
    MediaTaskFailure.CHANNEL_UNAVAILABLE -> R.string.media_queue_channel_unavailable
    MediaTaskFailure.INVALID_OUTPUT -> R.string.media_queue_invalid_output
}
