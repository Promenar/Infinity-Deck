package com.promenar.infinity.recording

import android.content.Context
import com.promenar.infinity.R
import com.promenar.infinity.data.local.inference.ModelDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest

internal data class RecordingSpeakerModels(val segmentation: File, val embedding: File)

internal class RecordingSpeakerModelManager(context: Context, private val scope: CoroutineScope) {
    private val root = File(context.filesDir, "speaker-models/v1")
    private val downloader = ModelDownloader(context)
    private val files = RecordingSpeakerModels(File(root, "segmentation.onnx"), File(root, "campplus.onnx"))
    private val stateValue = MutableStateFlow<RecordingAsrModelState>(RecordingAsrModelState.Missing)
    val state = stateValue.asStateFlow()
    private var job: Job? = null

    init {
        scope.launch(Dispatchers.IO) {
            synchronized(this@RecordingSpeakerModelManager) {
                if (job == null && installed() != null) stateValue.value = RecordingAsrModelState.Installed
            }
        }
    }

    fun installed(): RecordingSpeakerModels? = files.takeIf {
        it.segmentation.length() == SEGMENTATION_BYTES && it.embedding.length() == EMBEDDING_BYTES
    }

    @Synchronized fun start() {
        if (job?.isCompleted == false || stateValue.value == RecordingAsrModelState.Installed) return
        job = scope.launch(Dispatchers.IO) {
            val staged = File(root, "segmentation.install")
            try {
                stateValue.value = RecordingAsrModelState.Downloading(0, DOWNLOAD_BYTES)
                if (installed() != null) {
                    stateValue.value = RecordingAsrModelState.Installed
                    return@launch
                }
                val archive = downloader.downloadFile(SEGMENTATION_URL, "segmentation.tar.bz2", root, SEGMENTATION_ARCHIVE_HASH,
                    SEGMENTATION_ARCHIVE_BYTES, onProgress = { done, _ ->
                        stateValue.value = RecordingAsrModelState.Downloading(done, DOWNLOAD_BYTES)
                    }).getOrThrow()
                downloader.downloadFile(EMBEDDING_URL, files.embedding.name, root, EMBEDDING_HASH, EMBEDDING_BYTES,
                    onProgress = { done, _ ->
                        stateValue.value = RecordingAsrModelState.Downloading(SEGMENTATION_ARCHIVE_BYTES + done, DOWNLOAD_BYTES)
                    }).getOrThrow()
                stateValue.value = RecordingAsrModelState.Installing
                val digest = MessageDigest.getInstance("SHA-256")
                TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null && entry.name != SEGMENTATION_ENTRY) {
                        ensureActive()
                        entry = tar.nextEntry
                    }
                    require(entry?.isFile == true && entry.size == SEGMENTATION_BYTES)
                    staged.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var size = 0L
                        while (true) {
                            ensureActive()
                            val count = tar.read(buffer)
                            if (count < 0) break
                            size += count
                            check(size <= SEGMENTATION_BYTES)
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        check(size == SEGMENTATION_BYTES && digest.digest().joinToString("") { "%02x".format(it) } == SEGMENTATION_HASH)
                        output.fd.sync()
                    }
                }
                ensureActive()
                Files.move(staged.toPath(), files.segmentation.toPath(), ATOMIC_MOVE)
                archive.delete()
                stateValue.value = RecordingAsrModelState.Installed
            } catch (error: CancellationException) {
                stateValue.value = RecordingAsrModelState.Missing
                throw error
            } catch (_: Exception) {
                stateValue.value = RecordingAsrModelState.Failed(R.string.recording_speaker_download_failed)
            } finally {
                staged.delete()
            }
        }
    }

    @Synchronized fun cancel() {
        if (job?.isCompleted == false) {
            job?.cancel()
            downloader.cancel()
        }
    }

    companion object {
        private const val SEGMENTATION_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
        private const val EMBEDDING_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
        private const val SEGMENTATION_ENTRY = "sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx"
        private const val SEGMENTATION_ARCHIVE_HASH = "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488"
        private const val SEGMENTATION_HASH = "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d"
        private const val EMBEDDING_HASH = "f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11"
        private const val SEGMENTATION_ARCHIVE_BYTES = 6_958_444L
        private const val SEGMENTATION_BYTES = 1_540_506L
        private const val EMBEDDING_BYTES = 28_281_138L
        const val DOWNLOAD_BYTES = SEGMENTATION_ARCHIVE_BYTES + EMBEDDING_BYTES
        const val INSTALLED_BYTES = SEGMENTATION_BYTES + EMBEDDING_BYTES
    }
}
