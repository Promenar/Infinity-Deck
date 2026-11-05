package com.promenar.infinity.data.rag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

data class ImageMetadata(
    val title: String? = null,
    val docId: String? = null,
    val sessionId: String? = null,
    val mimeType: String = "image/jpeg"
)

class ImageService(
    private val context: Context,
    private val embeddingClient: EmbeddingClient,
    private val vectorStore: VectorStore,
    private val textSplitter: RecursiveCharacterTextSplitter
) {

    private val descriptionPromptZh = "请详细描述这张图片的内容。描述将被用于语义搜索与知识检索。请包含关键对象、文字、人物、场景和氛围。请使用中文回答。"
    private val descriptionPromptEn = "Please describe this image in detail. The description will be used for searching and retrieval. Include key objects, text, people, setting, and mood."

    suspend fun processImage(
        uri: Uri,
        metadata: ImageMetadata,
        language: String = "en"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bitmap = loadBitmap(uri)
                ?: return@withContext Result.failure(IllegalArgumentException("Failed to load image from URI: $uri"))

            val base64 = compressToBase64(bitmap, quality = 85)

            val description = describeImageInternal(base64, language)

            val chunks = textSplitter.splitText(description)
            val (embeddings, embeddingSpace) = embeddingClient.embedDocumentsForIndex(chunks)

            val vectors = chunks.zip(embeddings.embeddings).map { (chunk, embedding) ->
                VectorStore.NewVectorRecord(
                    docId = metadata.docId ?: UUID.randomUUID().toString(),
                    sessionId = metadata.sessionId,
                    content = chunk,
                    embedding = embedding,
                    metadata = withVectorSpaceMetadata("type=image&mime=${metadata.mimeType}${metadata.title?.let { "&title=$it" } ?: ""}", embeddingSpace)
                )
            }
            vectorStore.addVectorRecords(vectors)

            Result.success(description)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun processImageBase64(
        base64Image: String,
        metadata: ImageMetadata,
        language: String = "en"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val description = describeImageInternal(base64Image, language)

            val chunks = textSplitter.splitText(description)
            val (embeddings, embeddingSpace) = embeddingClient.embedDocumentsForIndex(chunks)

            val vectors = chunks.zip(embeddings.embeddings).map { (chunk, embedding) ->
                VectorStore.NewVectorRecord(
                    docId = metadata.docId ?: UUID.randomUUID().toString(),
                    sessionId = metadata.sessionId,
                    content = chunk,
                    embedding = embedding,
                    metadata = withVectorSpaceMetadata("type=image&mime=${metadata.mimeType}${metadata.title?.let { "&title=$it" } ?: ""}", embeddingSpace)
                )
            }
            vectorStore.addVectorRecords(vectors)

            Result.success(description)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun describeImageInternal(
        base64Image: String,
        language: String
    ): String {
        val prompt = if (language == "zh") descriptionPromptZh else descriptionPromptEn
        return prompt
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun compressToBase64(bitmap: Bitmap, quality: Int = 85): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
