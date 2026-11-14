package com.promenar.infinity.data.local.inference

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 推荐 GGUF 的下载由应用持有，引导、设置和折叠切换共享同一下载任务。 */
internal class RecordingModelInstaller(context: Context, private val scope: CoroutineScope) {
    private val file = File(File(context.filesDir, "models"), RecordingModelPackage.filename)
    private val downloader = ModelDownloader(context)
    private val stateValue = MutableStateFlow<ModelDownloader.DownloadState?>(null)
    val state = stateValue.asStateFlow()
    private var job: Job? = null

    fun installed(): Boolean = file.isFile && file.length() == RecordingModelPackage.bytes

    @Synchronized fun start() {
        if (job?.isCompleted == false || installed()) return
        val progress = File(file.parentFile, "${file.name}.part").length().coerceAtMost(RecordingModelPackage.bytes)
        stateValue.value = ModelDownloader.DownloadState(RecordingModelPackage.url, file.name,
            totalBytes = RecordingModelPackage.bytes, downloadedBytes = progress, isDownloading = true)
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val download = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            try {
                val result = downloader.download(RecordingModelPackage.url, file.name, RecordingModelPackage.sha256,
                    RecordingModelPackage.bytes) { fraction ->
                    stateValue.value = stateValue.value?.copy(downloadedBytes = (fraction * RecordingModelPackage.bytes).toLong())
                }
                stateValue.value = stateValue.value?.copy(isComplete = result.isSuccess,
                    error = result.exceptionOrNull()?.takeUnless { it is CancellationException }?.message)
            } finally {
                withContext(NonCancellable) { stateValue.value = stateValue.value?.copy(isDownloading = false) }
            }
        }
        job = download
    }

    @Synchronized fun pause() {
        if (job?.isCompleted == false) {
            job?.cancel()
            downloader.cancel()
        }
    }
}
