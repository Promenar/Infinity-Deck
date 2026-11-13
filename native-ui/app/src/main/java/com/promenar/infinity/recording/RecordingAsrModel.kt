package com.promenar.infinity.recording

import android.content.Context
import androidx.annotation.StringRes
import com.promenar.infinity.R
import com.promenar.infinity.data.local.inference.ModelDownloadIntegrityException
import com.promenar.infinity.data.local.inference.ModelDownloader
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
import java.io.FilterInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest

internal sealed interface RecordingAsrModelState {
    data object Missing : RecordingAsrModelState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : RecordingAsrModelState
    data object Installing : RecordingAsrModelState
    data object Installed : RecordingAsrModelState
    data class Failed(@StringRes val message: Int) : RecordingAsrModelState
}

internal data class RecordingAsrFile(val name: String, val bytes: Long, val sha256: String)
internal data class RecordingAsrPackage(
    val version: String,
    val archiveRoot: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val files: List<RecordingAsrFile>,
    val vocab: RecordingAsrFile,
    val vocabAsset: String = "asr/bpe.vocab",
) {
    val installedBytes get() = files.sumOf { it.bytes } + vocab.bytes
    val archiveName get() = "$version.tar.bz2"

    companion object {
        val CURRENT = RecordingAsrPackage(
            version = "x-asr-480ms-zh-en-punct-int8-2026-06-05",
            archiveRoot = "sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2",
            bytes = 133_895_136,
            sha256 = "fa5f63d618e5a01526e275a358bb7772e403f84808a4769fba52cffd8160bf74",
            files = listOf(
                RecordingAsrFile("encoder.int8.onnx", 155_278_641, "908596dcc137a73b95be908ca55e88caa1b3dbbe8027c171615f4b0609c5eb1e"),
                RecordingAsrFile("decoder.onnx", 11_309_084, "a1cbc9eac2d5e3fb6617a218c67ad6daaa7f4e0fd225f08b2c22ab0413c8c257"),
                RecordingAsrFile("joiner.int8.onnx", 2_581_422, "aedb7fa697b2ab43f20499826fff7c997eea7d67db77be97769aeeeb726e63b3"),
                RecordingAsrFile("tokens.txt", 58_806, "b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c"),
            ),
            vocab = RecordingAsrFile("bpe.vocab", 69_594, "28fc94d67aae53d8c58010fcfb16fc8c2f8dd263e03f3490c98210e354e8f914"),
        )
    }
}

internal interface RecordingAsrDownload {
    suspend fun download(spec: RecordingAsrPackage, directory: File, progress: (Long, Long) -> Unit): File
    fun cancel()
}

private class RecordingAsrHttpDownload(context: Context) : RecordingAsrDownload {
    private val downloader = ModelDownloader(context)
    override suspend fun download(spec: RecordingAsrPackage, directory: File, progress: (Long, Long) -> Unit): File =
        downloader.downloadFile(spec.url, spec.archiveName, directory, spec.sha256, spec.bytes, onProgress = progress).getOrThrow()
    override fun cancel() = downloader.cancel()
}

internal class RecordingAsrModelManager(
    private val root: File,
    private val scope: CoroutineScope,
    private val download: RecordingAsrDownload,
    private val asset: (String) -> InputStream,
    val spec: RecordingAsrPackage = RecordingAsrPackage.CURRENT,
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        File(context.filesDir, "asr-models"), scope, RecordingAsrHttpDownload(context), { context.assets.open(it) },
    )

    private val _state = MutableStateFlow<RecordingAsrModelState>(RecordingAsrModelState.Missing)
    val state = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var vocabulary: Set<Char>? = null
    private val directory get() = File(root, spec.version)
    private val downloads get() = File(root, ".download")

    init {
        scope.launch(Dispatchers.IO) {
            synchronized(this@RecordingAsrModelManager) {
                if (job == null && isInstalled()) {
                    loadVocabulary()
                    _state.value = RecordingAsrModelState.Installed
                }
            }
        }
    }

    fun isInstalled(): Boolean = directory.isDirectory &&
        runCatching { File(directory, "version").readText() == spec.version }.getOrDefault(false) &&
        (spec.files + spec.vocab).all { File(directory, it.name).let { file -> file.isFile && file.length() == it.bytes } }

    fun installedDirectory(): File? = directory.takeIf { isInstalled() }
    fun unknownCharacters(text: String): List<Char>? = vocabulary?.let { unknownRecordingCharacters(text, it) }
    fun tokenCharacters(): Set<Char>? = vocabulary

    @Synchronized fun start() {
        if (job?.isCompleted == false || _state.value == RecordingAsrModelState.Installed) return
        job = scope.launch(Dispatchers.IO) { install() }
    }

    @Synchronized fun cancel() {
        if (job?.isCompleted == false) {
            job?.cancel()
            download.cancel()
        }
    }

    private fun loadVocabulary() {
        vocabulary = File(directory, "tokens.txt").useLines { recordingTokenCharacters(it) }
    }

    private suspend fun install() = withContext(Dispatchers.IO) {
        val temporary = File(root, ".${spec.version}.install")
        try {
            if (isInstalled()) {
                loadVocabulary()
                _state.value = RecordingAsrModelState.Installed
                return@withContext
            }
            check(root.isDirectory || root.mkdirs())
            temporary.deleteRecursively()
            val cached = File(downloads, spec.archiveName)
            val partial = File(downloads, "${spec.archiveName}.part")
            val remaining = if (cached.isFile) 0 else (spec.bytes - partial.length()).coerceAtLeast(0)
            if (availableBytes(root) < remaining + spec.installedBytes + STORAGE_MARGIN_BYTES) throw AsrStorageException()
            _state.value = RecordingAsrModelState.Downloading(partial.length().coerceAtMost(spec.bytes), spec.bytes)
            val archive = download.download(spec, downloads) { done, total ->
                _state.value = RecordingAsrModelState.Downloading(done.coerceIn(0, spec.bytes), total)
            }
            currentCoroutineContext().ensureActive()
            _state.value = RecordingAsrModelState.Installing
            check(temporary.mkdirs())
            val remainingFiles = spec.files.associateBy { "${spec.archiveRoot}/${it.name}" }.toMutableMap()
            val coroutine = currentCoroutineContext()
            val cancellable = object : FilterInputStream(archive.inputStream().buffered()) {
                override fun read(): Int { coroutine.ensureActive(); return super.read() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    coroutine.ensureActive()
                    return `in`.read(buffer, offset, length)
                }
                override fun skip(count: Long): Long { coroutine.ensureActive(); return super.skip(count) }
            }
            cancellable.use { input ->
                BZip2CompressorInputStream(input).use { compressed ->
                    TarArchiveInputStream(compressed).use { tar ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = tar.nextEntry ?: break
                            val expected = spec.files.firstOrNull { entry.name == "${spec.archiveRoot}/${it.name}" } ?: continue
                            if (remainingFiles.remove(entry.name) == null || !entry.isFile || entry.isSymbolicLink || entry.isLink || entry.size != expected.bytes) {
                                throw ModelDownloadIntegrityException("听写模型归档条目无效")
                            }
                            copyVerified(tar, File(temporary, expected.name), expected)
                        }
                    }
                }
            }
            if (remainingFiles.isNotEmpty()) throw ModelDownloadIntegrityException("听写模型文件缺失")
            asset(spec.vocabAsset).use { copyVerified(it, File(temporary, spec.vocab.name), spec.vocab) }
            val tokens = File(temporary, "tokens.txt").useLines { recordingTokenCharacters(it) }
            File(temporary, "version").outputStream().use { it.write(spec.version.toByteArray()); it.fd.sync() }
            currentCoroutineContext().ensureActive()
            // 同一文件系统内提交完整目录；失效目录只在校验完成后清理。
            if (directory.exists()) check(directory.deleteRecursively())
            Files.move(temporary.toPath(), directory.toPath(), ATOMIC_MOVE)
            vocabulary = tokens
            _state.value = RecordingAsrModelState.Installed
            archive.delete()
        } catch (e: CancellationException) {
            _state.value = if (isInstalled()) RecordingAsrModelState.Installed else RecordingAsrModelState.Missing
            throw e
        } catch (e: Exception) {
            if (e is ModelDownloadIntegrityException) {
                File(downloads, spec.archiveName).delete()
                File(downloads, "${spec.archiveName}.part").delete()
            }
            _state.value = RecordingAsrModelState.Failed(when {
                e is AsrStorageException || storageFailure(e) -> R.string.recording_asr_storage
                e is ModelDownloadIntegrityException -> R.string.recording_asr_integrity
                e is IOException && _state.value is RecordingAsrModelState.Downloading -> R.string.recording_asr_network
                else -> R.string.recording_asr_other
            })
        } finally { temporary.deleteRecursively() }
    }

    private suspend fun copyVerified(input: InputStream, target: File, expected: RecordingAsrFile) {
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > expected.bytes - done) throw ModelDownloadIntegrityException("听写模型文件过大")
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                done += count
            }
            output.fd.sync()
        }
        if (done != expected.bytes || digest.digest().joinToString("") { "%02x".format(it) } != expected.sha256) {
            throw ModelDownloadIntegrityException("听写模型文件校验不符")
        }
    }

    private fun storageFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        it.message?.contains("ENOSPC", ignoreCase = true) == true ||
            it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC
    }
    private class AsrStorageException : IOException()
    companion object { const val STORAGE_MARGIN_BYTES = 64L * 1024 * 1024 }
}
