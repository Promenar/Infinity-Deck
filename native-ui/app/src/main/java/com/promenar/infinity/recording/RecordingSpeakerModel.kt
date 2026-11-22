package com.promenar.infinity.recording

import android.content.Context
import com.promenar.infinity.R
import com.promenar.infinity.data.local.inference.ModelDownloadIntegrityException
import com.promenar.infinity.data.local.inference.ModelDownloader
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

private const val SPEAKER_SEGMENTATION_BYTES = 1_540_506L
private const val SPEAKER_EMBEDDING_BYTES = 28_281_139L

internal data class RecordingSpeakerModels(val segmentation: File, val embedding: File)

internal data class RecordingSpeakerPackage(
    val segmentation: RecordingAsrFile,
    val segmentationUrl: String,
    val segmentationArchiveFallbackUrl: String,
    val segmentationArchiveEntry: String,
    val segmentationArchiveBytes: Long,
    val segmentationArchiveSha256: String,
    val embedding: RecordingAsrFile,
    val embeddingUrl: String,
    val embeddingFallbackUrl: String,
    val legacyEmbeddingBytes: Long? = null,
) {
    val downloadBytes get() = segmentation.bytes + embedding.bytes
    val installedBytes get() = segmentation.bytes + embedding.bytes

    companion object {
        // 主源固定官方 Hugging Face 仓库的 40 位不可变 commit，防止上游覆盖同名资产导致校验漂移。
        val CURRENT = RecordingSpeakerPackage(
            segmentation = RecordingAsrFile(
                name = "segmentation.onnx",
                bytes = SPEAKER_SEGMENTATION_BYTES,
                sha256 = "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d",
            ),
            segmentationUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/9403a6902bb58e3d5ae8c7e77c3422de279db2e0/model.int8.onnx",
            segmentationArchiveFallbackUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
            segmentationArchiveEntry = "sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx",
            segmentationArchiveBytes = 6_958_444L,
            segmentationArchiveSha256 = "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488",
            embedding = RecordingAsrFile(
                name = "campplus.onnx",
                bytes = SPEAKER_EMBEDDING_BYTES,
                sha256 = "e6af4d4c444f3dab01d991bb9b7d5af98eb979e329f6c8880a19d5553561ed25",
            ),
            embeddingUrl = "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/a8055d6daef723a6a4ecf4de233708575bf4994f/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
            embeddingFallbackUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
            legacyEmbeddingBytes = 28_281_138L,
        )
    }
}

internal interface RecordingSpeakerDownload {
    suspend fun downloadFile(
        url: String,
        fileName: String,
        directory: File,
        expectedSha256: String,
        expectedBytes: Long,
        progress: (Long, Long) -> Unit,
    ): File

    fun cancel()
}

private class RecordingSpeakerHttpDownload(context: Context) : RecordingSpeakerDownload {
    private val downloader = ModelDownloader(context)

    override suspend fun downloadFile(
        url: String,
        fileName: String,
        directory: File,
        expectedSha256: String,
        expectedBytes: Long,
        progress: (Long, Long) -> Unit,
    ): File = downloader.downloadFile(
        url = url,
        fileName = fileName,
        directory = directory,
        expectedSha256 = expectedSha256,
        expectedBytes = expectedBytes,
        onProgress = progress,
    ).getOrThrow()

    override fun cancel() = downloader.cancel()
}

/** 管理说话人分离与声纹提取模型的不可变主源下载、GitHub 回退与原子安装。 */
internal class RecordingSpeakerModelManager(
    private val root: File,
    private val scope: CoroutineScope,
    private val download: RecordingSpeakerDownload,
    val spec: RecordingSpeakerPackage = RecordingSpeakerPackage.CURRENT,
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        File(context.filesDir, "speaker-models/v1"),
        scope,
        RecordingSpeakerHttpDownload(context),
    )

    private val files = RecordingSpeakerModels(
        segmentation = File(root, spec.segmentation.name),
        embedding = File(root, spec.embedding.name),
    )
    private val downloads get() = File(root, ".download")
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
        val segOk = it.segmentation.isFile && it.segmentation.length() == spec.segmentation.bytes
        val embLen = it.embedding.length()
        val embOk = it.embedding.isFile &&
            (embLen == spec.embedding.bytes || (spec.legacyEmbeddingBytes != null && embLen == spec.legacyEmbeddingBytes))
        segOk && embOk
    }

    @Synchronized fun start() {
        if (job?.isCompleted == false || stateValue.value == RecordingAsrModelState.Installed) return
        job = scope.launch(Dispatchers.IO) { install() }
    }

    @Synchronized fun cancel() {
        if (job?.isCompleted == false) {
            job?.cancel()
            download.cancel()
        }
    }

    private suspend fun install() = withContext(Dispatchers.IO) {
        val stagedSegmentation = File(root, "segmentation.install")
        val stagedEmbedding = File(root, "campplus.install")
        try {
            if (installed() != null) {
                stateValue.value = RecordingAsrModelState.Installed
                return@withContext
            }
            check(root.isDirectory || root.mkdirs())
            stagedSegmentation.delete()
            stagedEmbedding.delete()
            val pendingBytes = pendingDownloadBytes()
            if (availableBytes(root) < pendingBytes + spec.installedBytes + RecordingAsrModelManager.STORAGE_MARGIN_BYTES) {
                throw SpeakerStorageException()
            }
            stateValue.value = RecordingAsrModelState.Downloading(
                (spec.downloadBytes - pendingBytes).coerceIn(0L, spec.downloadBytes),
                spec.downloadBytes,
            )
            prepareSegmentation(stagedSegmentation)
            prepareEmbedding(stagedEmbedding)
            stateValue.value = RecordingAsrModelState.Installing
            currentCoroutineContext().ensureActive()
            Files.move(stagedSegmentation.toPath(), files.segmentation.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            Files.move(stagedEmbedding.toPath(), files.embedding.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            downloads.deleteRecursively()
            cleanupLegacyFiles()
            stateValue.value = RecordingAsrModelState.Installed
        } catch (error: CancellationException) {
            stateValue.value = if (installed() != null) RecordingAsrModelState.Installed else RecordingAsrModelState.Missing
            throw error
        } catch (error: Exception) {
            InfinityLogger.logError("recording_speaker_model_install", error)
            if (error is ModelDownloadIntegrityException) downloads.deleteRecursively()
            stateValue.value = RecordingAsrModelState.Failed(
                when {
                    error is SpeakerStorageException || isAsrStorageFailure(error) -> R.string.recording_speaker_storage
                    error is ModelDownloadIntegrityException -> R.string.recording_speaker_integrity
                    error is IOException && stateValue.value is RecordingAsrModelState.Downloading -> R.string.recording_speaker_network
                    else -> R.string.recording_speaker_download_failed
                },
            )
        } finally {
            stagedSegmentation.delete()
            stagedEmbedding.delete()
        }
    }

    private fun pendingDownloadBytes(): Long = listOf(spec.segmentation, spec.embedding).sumOf { expected ->
        val cached = File(downloads, expected.name)
        val partial = File(downloads, "${expected.name}.part")
        when {
            cached.isFile && cached.length() == expected.bytes -> 0L
            partial.isFile -> (expected.bytes - partial.length()).coerceAtLeast(0L)
            else -> expected.bytes
        }
    }

    private suspend fun prepareSegmentation(stagedSegmentation: File) {
        try {
            val file = download.downloadFile(
                url = spec.segmentationUrl,
                fileName = spec.segmentation.name,
                directory = downloads,
                expectedSha256 = spec.segmentation.sha256,
                expectedBytes = spec.segmentation.bytes,
            ) { done, _ ->
                stateValue.value = RecordingAsrModelState.Downloading(
                    done.coerceIn(0L, spec.segmentation.bytes),
                    spec.downloadBytes,
                )
            }
            currentCoroutineContext().ensureActive()
            file.inputStream().buffered().use { input ->
                copyVerifiedFile(input, stagedSegmentation, spec.segmentation, "声纹分割模型文件")
            }
        } catch (primaryError: Exception) {
            if (primaryError is CancellationException) throw primaryError
            stagedSegmentation.delete()
            InfinityLogger.logError("recording_speaker_model_primary_segmentation", primaryError)
            val archive = download.downloadFile(
                url = spec.segmentationArchiveFallbackUrl,
                fileName = "segmentation.tar.bz2",
                directory = downloads,
                expectedSha256 = spec.segmentationArchiveSha256,
                expectedBytes = spec.segmentationArchiveBytes,
            ) { done, _ ->
                val mapped = if (spec.segmentationArchiveBytes > 0L) {
                    (done.coerceIn(0L, spec.segmentationArchiveBytes) * spec.segmentation.bytes) / spec.segmentationArchiveBytes
                } else 0L
                stateValue.value = RecordingAsrModelState.Downloading(
                    mapped.coerceIn(0L, spec.segmentation.bytes),
                    spec.downloadBytes,
                )
            }
            extractSegmentationEntry(archive, stagedSegmentation)
        }
    }

    private suspend fun extractSegmentationEntry(archive: File, stagedSegmentation: File) {
        currentCoroutineContext().ensureActive()
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            var entry = tar.nextEntry
            while (entry != null && entry.name != spec.segmentationArchiveEntry) {
                currentCoroutineContext().ensureActive()
                entry = tar.nextEntry
            }
            if (entry == null || !entry.isFile || entry.isSymbolicLink || entry.isLink || entry.size != spec.segmentation.bytes) {
                throw ModelDownloadIntegrityException("声纹分割模型归档条目无效")
            }
            copyVerifiedFile(tar, stagedSegmentation, spec.segmentation, "声纹分割模型文件")
        }
    }

    private suspend fun prepareEmbedding(stagedEmbedding: File) {
        stateValue.value = RecordingAsrModelState.Downloading(spec.segmentation.bytes, spec.downloadBytes)
        try {
            val file = downloadDirectEmbedding(spec.embeddingUrl)
            currentCoroutineContext().ensureActive()
            file.inputStream().buffered().use { input ->
                copyVerifiedFile(input, stagedEmbedding, spec.embedding, "声纹提取模型文件")
            }
        } catch (primaryError: Exception) {
            if (primaryError is CancellationException) throw primaryError
            stagedEmbedding.delete()
            InfinityLogger.logError("recording_speaker_model_primary_embedding", primaryError)
            val file = downloadDirectEmbedding(spec.embeddingFallbackUrl)
            currentCoroutineContext().ensureActive()
            file.inputStream().buffered().use { input ->
                copyVerifiedFile(input, stagedEmbedding, spec.embedding, "声纹提取模型文件")
            }
        }
    }

    private suspend fun downloadDirectEmbedding(url: String): File = download.downloadFile(
        url = url,
        fileName = spec.embedding.name,
        directory = downloads,
        expectedSha256 = spec.embedding.sha256,
        expectedBytes = spec.embedding.bytes,
    ) { done, _ ->
        val overall = (spec.segmentation.bytes + done.coerceIn(0L, spec.embedding.bytes)).coerceIn(0L, spec.downloadBytes)
        stateValue.value = RecordingAsrModelState.Downloading(overall, spec.downloadBytes)
    }

    private fun cleanupLegacyFiles() {
        listOf(
            "segmentation.tar.bz2",
            "segmentation.tar.bz2.part",
            "${spec.segmentation.name}.part",
            "${spec.embedding.name}.part",
        ).forEach { File(root, it).delete() }
    }

    private class SpeakerStorageException : IOException()

    companion object {
        const val DOWNLOAD_BYTES = SPEAKER_SEGMENTATION_BYTES + SPEAKER_EMBEDDING_BYTES
        const val INSTALLED_BYTES = SPEAKER_SEGMENTATION_BYTES + SPEAKER_EMBEDDING_BYTES
    }
}
