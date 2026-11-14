package com.promenar.infinity.data.local.inference

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ModelDownloadIntegrityException(message: String) : IOException(message)

class ModelDownloader internal constructor(
    private val modelsDir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
) {
    constructor(context: Context) : this(File(context.filesDir, "models"))

    data class DownloadState(
        val url: String,
        val fileName: String,
        val totalBytes: Long = -1,
        val downloadedBytes: Long = 0,
        val isDownloading: Boolean = false,
        val isComplete: Boolean = false,
        val error: String? = null,
    )

    @Volatile private var cancelled = false
    @Volatile private var call: Call? = null

    fun cancel() {
        cancelled = true
        call?.cancel()
    }

    suspend fun download(
        url: String,
        fileName: String,
        expectedSha256: String? = null,
        expectedBytes: Long? = null,
        onProgress: (Float) -> Unit = {},
    ): Result<StoredModel> = withContext(Dispatchers.IO) {
        if (!fileName.endsWith(".gguf", ignoreCase = true)) return@withContext Result.failure(IllegalArgumentException("模型须为 GGUF 文件"))
        downloadFile(url, fileName, modelsDir, expectedSha256, expectedBytes,
            validate = { GgufParser.parse(it.absolutePath) },
            onProgress = { done, total -> if (total > 0) onProgress(done.toFloat() / total) },
        ).map(::buildStoredModel)
    }

    /** 普通文件下载与 GGUF 共用断点续传；取消和网络中断保留 part。 */
    suspend fun downloadFile(
        url: String,
        fileName: String,
        directory: File,
        expectedSha256: String? = null,
        expectedBytes: Long? = null,
        validate: (File) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        cancelled = false
        var tempFile: File? = null
        try {
            require(fileName.isNotBlank() && fileName == File(fileName).name && fileName !in setOf(".", ".."))
            check(directory.isDirectory || directory.mkdirs())
            val destination = File(directory, fileName)
            val partial = File(directory, "$fileName.part")
            tempFile = partial
            suspend fun checkActive() {
                currentCoroutineContext().ensureActive()
                if (cancelled) throw CancellationException("下载已取消")
            }
            suspend fun verify(file: File) {
                checkActive()
                if (expectedBytes != null && file.length() != expectedBytes) throw ModelDownloadIntegrityException("模型文件长度不符")
                if (expectedSha256 != null) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    if (digest.digest().joinToString("") { "%02x".format(it) } != expectedSha256) {
                        throw ModelDownloadIntegrityException("模型文件摘要不符")
                    }
                }
                try { validate(file) } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    throw ModelDownloadIntegrityException("模型文件格式无效")
                }
            }
            if (destination.exists()) {
                try { verify(destination) } catch (e: ModelDownloadIntegrityException) { destination.delete(); throw e }
                return@withContext Result.success(destination)
            }
            if (expectedBytes != null && partial.length() > expectedBytes) partial.delete()
            var start = partial.length()
            if (expectedBytes == null || start != expectedBytes) {
                checkActive()
                val request = Request.Builder().url(url).apply {
                    if (start > 0) header("Range", "bytes=$start-")
                }.build()
                val requestCall = client.newCall(request)
                call = requestCall
                checkActive()
                requestCall.execute().use { response ->
                    if (response.code != 200 && response.code != 206) throw IOException("下载响应异常：${response.code}")
                    val body = response.body ?: throw IOException("下载响应为空")
                    if (response.code == 206) {
                        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                            ?: throw IOException("下载范围响应无效")
                        if (range.groupValues[1].toLong() != start ||
                            expectedBytes != null && range.groupValues[3].toLong() != expectedBytes) throw IOException("下载范围不匹配")
                    } else start = 0
                    val total = expectedBytes ?: (body.contentLength().takeIf { it >= 0 }?.plus(start) ?: -1L)
                    onProgress(start, total)
                    body.byteStream().use { input ->
                        FileOutputStream(partial, start > 0).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = start
                            try {
                                while (true) {
                                    checkActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (expectedBytes != null && count > expectedBytes - done) throw ModelDownloadIntegrityException("模型文件超出预期大小")
                                    output.write(buffer, 0, count)
                                    done += count
                                    onProgress(done, total)
                                }
                            } finally { output.fd.sync() }
                        }
                    }
                }
            }
            verify(partial)
            checkActive()
            java.nio.file.Files.move(partial.toPath(), destination.toPath())
            Result.success(destination)
        } catch (e: Exception) {
            if (e is ModelDownloadIntegrityException) tempFile?.delete()
            Result.failure(if (cancelled) CancellationException("下载已取消") else e)
        } finally { call = null }
    }

    fun cleanupPartial(fileName: String) {
        require(fileName == File(fileName).name)
        File(modelsDir, "$fileName.part").delete()
    }

    private fun buildStoredModel(file: File): StoredModel {
        return try {
            val meta = GgufParser.parse(file.absolutePath)
            StoredModel(
                id = "model_${file.nameWithoutExtension}",
                fileName = file.name,
                filePath = file.absolutePath,
                sizeBytes = file.length(),
                format = "GGUF",
                addedAt = file.lastModified(),
                architecture = meta.architecture,
                quantization = meta.quantization,
                contextLength = meta.contextLength,
                embeddingLength = meta.embeddingLength,
                parameterCount = meta.parameterCount,
                modelName = meta.modelName,
                poolingType = meta.poolingType,
            )
        } catch (_: Exception) {
            StoredModel(
                id = "model_${file.nameWithoutExtension}",
                fileName = file.name,
                filePath = file.absolutePath,
                sizeBytes = file.length(),
                format = "GGUF",
                addedAt = file.lastModified()
            )
        }
    }
}
