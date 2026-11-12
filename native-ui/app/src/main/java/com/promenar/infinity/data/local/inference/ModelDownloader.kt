package com.promenar.infinity.data.local.inference

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.promenar.infinity.infra.util.Sha256Utils
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class ModelDownloader(private val context: Context) {

    data class DownloadState(
        val url: String,
        val fileName: String,
        val totalBytes: Long = -1,
        val downloadedBytes: Long = 0,
        val isDownloading: Boolean = false,
        val isComplete: Boolean = false,
        val error: String? = null
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    suspend fun download(
        url: String,
        fileName: String,
        expectedSha256: String? = null,
        expectedBytes: Long? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<StoredModel> = withContext(Dispatchers.IO) {
        cancelled = false
        try {
            require(fileName == File(fileName).name && fileName.endsWith(".gguf", ignoreCase = true))
            val destFile = File(modelsDir, fileName)
            fun verify(file: File) {
                require(expectedBytes == null || file.length() == expectedBytes) { "模型文件长度不符" }
                require(expectedSha256 == null || Sha256Utils.hashFile(file) == expectedSha256) { "模型文件摘要不符" }
            }
            if (destFile.exists()) {
                verify(destFile)
                return@withContext Result.success(buildStoredModel(destFile))
            }

            val tempFile = File(modelsDir, "$fileName.part")
            var startByte = if (tempFile.exists()) tempFile.length() else 0L

            val request = Request.Builder().url(url).apply {
                if (startByte > 0) {
                    header("Range", "bytes=$startByte-")
                }
            }.build()

            client.newCall(request).execute().use { response ->
                val httpCode = response.code

                if (httpCode !in 200..206) {
                    tempFile.delete()
                    response.close()
                    return@withContext Result.failure(
                        IOException("HTTP $httpCode for $url")
                    )
                }

                val body = response.body ?: run {
                    response.close()
                    return@withContext Result.failure(IOException("Empty response body"))
                }

                val serverHonoredRange = httpCode == 206
                if (!serverHonoredRange) {
                    startByte = 0L
                }

                val totalSize = if (serverHonoredRange) {
                    response.header("Content-Range")
                        ?.substringAfter("/")?.toLongOrNull()
                        ?: (body.contentLength() + startByte)
                } else {
                    body.contentLength()
                }

                body.byteStream().use { input ->
                    FileOutputStream(tempFile, startByte > 0).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = startByte

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            if (cancelled) {
                                output.fd.sync()
                                return@withContext Result.failure(
                                    CancellationException("Download cancelled")
                                )
                            }
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (expectedBytes != null && totalRead > expectedBytes) {
                                throw IOException("模型文件超出预期大小")
                            }
                            if (totalSize > 0) {
                                onProgress(totalRead.toFloat() / totalSize)
                            }
                        }
                        output.fd.sync()
                    }
                }
            }

            if (cancelled) {
                return@withContext Result.failure(
                    CancellationException("Download cancelled")
                )
            }

            try {
                verify(tempFile)
                GgufParser.parse(tempFile.absolutePath)
            } catch (failure: Exception) {
                tempFile.delete()
                throw failure
            }
            currentCoroutineContext().ensureActive()
            if (cancelled) return@withContext Result.failure(CancellationException("Download cancelled"))
            java.nio.file.Files.move(tempFile.toPath(), destFile.toPath())
            Result.success(buildStoredModel(destFile))
        } catch (e: CancellationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun cleanupPartial(fileName: String) {
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
                parameterCount = meta.parameterCount
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
