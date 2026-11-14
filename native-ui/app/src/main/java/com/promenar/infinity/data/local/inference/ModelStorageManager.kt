package com.promenar.infinity.data.local.inference

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

data class StoredModel(
    val id: String,
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val format: String,
    val addedAt: Long,
    val architecture: String = "",
    val quantization: String = "",
    val contextLength: Int = 0,
    val embeddingLength: Int = 0,
    val parameterCount: String = "",
    val modelName: String = "",
    val poolingType: Int? = null,
)

class ModelStorageManager(private val context: Context) {
    private companion object {
        val importCommit = Any()
    }

    val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    suspend fun importModel(uri: Uri): Result<StoredModel> = withContext(Dispatchers.IO) {
        var staged: File? = null
        try {
            val filename = resolveFileName(uri)?.substringAfterLast('/')?.substringAfterLast(92.toChar()) ?: "model.gguf"
            require(filename.endsWith(".gguf", ignoreCase = true) && filename.length <= 180)
            val temp = File.createTempFile("import-", ".part", modelsDir)
            staged = temp
            val source = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开模型" }
            source.use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output, 65536)
                    output.fd.sync()
                }
            }
            val metadata = GgufParser.parse(temp.absolutePath)
            currentCoroutineContext().ensureActive()
            val file = synchronized(importCommit) {
                val destination = resolveUniqueDestination(filename)
                Files.move(temp.toPath(), destination.toPath()).toFile()
            }
            val model = StoredModel(
                id = "model_${file.nameWithoutExtension}", fileName = file.name,
                filePath = file.absolutePath, sizeBytes = file.length(), format = "GGUF", addedAt = file.lastModified(),
                architecture = metadata.architecture, quantization = metadata.quantization,
                contextLength = metadata.contextLength, embeddingLength = metadata.embeddingLength,
                parameterCount = metadata.parameterCount,
                modelName = metadata.modelName, poolingType = metadata.poolingType,
            )
            Result.success(model)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        } finally {
            staged?.delete()
        }
    }

    suspend fun listModels(): List<StoredModel> = withContext(Dispatchers.IO) {
        modelsDir.listFiles()
            ?.filter { it.extension.equals("gguf", ignoreCase = true) }
            ?.map { file ->
                try {
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
                        format = "Unknown",
                        addedAt = file.lastModified()
                    )
                }
            }
            ?.sortedByDescending { it.addedAt }
            ?: emptyList()
    }

    suspend fun deleteModel(filePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (file.exists() && file.delete()) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Failed to delete: $filePath"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getModelSize(filePath: String): Long = File(filePath).length()

    fun isImporting(fileName: String): Boolean =
        fileName.endsWith(".tmp") || fileName.endsWith(".part")

    fun getAvailableStorageBytes(): Long = modelsDir.usableSpace

    private fun resolveUniqueDestination(fileName: String): File {
        var candidate = File(modelsDir, fileName)
        if (!candidate.exists()) return candidate

        val baseName = fileName.substringBeforeLast(".")
        val ext = fileName.substringAfterLast(".")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(modelsDir, "${baseName}_$counter.$ext")
            counter++
        }
        return candidate
    }

    private fun resolveFileName(uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment
    }
}
