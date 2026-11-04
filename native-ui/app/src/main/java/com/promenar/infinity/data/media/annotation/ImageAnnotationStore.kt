package com.promenar.infinity.data.media.annotation

import com.promenar.infinity.domain.media.*
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** 工作区保存与预览生命周期；私有预览按会话散列隔离，工作区图层由媒体存储管理。 */
class ImageAnnotationStore(
    private val mediaStore: WorkspaceMediaStore,
    private val resolver: WorkspaceMediaResolver,
    private val previewRoot: File,
    private val previewGeneration: String = PROCESS_GENERATION,
) {
    private fun directory(sessionId: String): File = File(previewRoot,
        MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })

    suspend fun save(sessionId: String, export: ImageAnnotationExport, context: ImageAnnotationContext? = null): File = withContext(Dispatchers.IO) {
        check(mediaStore.canSave(sessionId)) { "当前会话工作区不可写入媒体，请检查工作区目录与授权。" }
        require(export.width > 0 && export.height > 0) { "画板尺寸无效。" }
        val layer = ImageAnnotationPreviewCodec.strip(export.layerPng)
        require(dimensions(layer) == (export.width to export.height)) { "画板图层尺寸与导出尺寸不一致。" }
        val preview = ImageAnnotationPreviewCodec.strip(export.previewPng)
        val previewSize = dimensions(preview)
        require(previewSize.first > 0 && previewSize.second > 0 && maxOf(previewSize.first, previewSize.second) <= 1568) { "画板预览尺寸无效，长边不能超过 1568 像素。" }
        require(export.kind == ImageAnnotationKind.SKETCH || export.source != ImageAnnotationSource.None) { "标注或选区需要原图。" }
        require(export.kind != ImageAnnotationKind.SKETCH || export.source == ImageAnnotationSource.None) { "手绘草图无需原图。" }
        // 在创建媒体前校验编辑数据，避免无法编码的成果留下无用文件。
        ImageAnnotationPreviewCodec.embed(preview, ImageAnnotation(export.kind, export.width, export.height, "", editorState = export.editorState))
        val sourcePath = when (val source = export.source) {
            is ImageAnnotationSource.Bytes -> {
                val sourcePng = ImageAnnotationPreviewCodec.strip(source.png)
                require(dimensions(sourcePng) == (export.width to export.height)) { "原图尺寸与画板图层不一致。" }
                mediaStore.save(sessionId, MediaPayload.Bytes(sourcePng), MediaOrigin.ANNOTATION, "image/png").path
            }
            is ImageAnnotationSource.Workspace -> {
                val resolved = resolver.resolve(sessionId, source.path)
                require(resolved is MediaResolution.Available && resolved.ref.kind == MediaKind.IMAGE) { "当前会话工作区原图不可读取。" }
                require(resolved.ref.width == export.width && resolved.ref.height == export.height) { "原图尺寸与画板图层不一致。" }
                resolved.ref.path
            }
            ImageAnnotationSource.None -> null
        }
        val layerPath = mediaStore.save(sessionId, MediaPayload.Bytes(layer), MediaOrigin.ANNOTATION, "image/png").path
        val encoded = ImageAnnotationPreviewCodec.embed(preview,
            ImageAnnotation(export.kind, export.width, export.height, layerPath, sourcePath, export.editorState), context)
        val dir = File(directory(sessionId), previewGeneration)
        check(dir.isDirectory || dir.mkdirs()) { "无法创建画板预览目录。" }
        val file = File(dir, "${UUID.randomUUID()}.png")
        try {
            FileOutputStream(file).use { output -> output.write(encoded); output.fd.sync() }
            file
        } catch (failure: Exception) { file.delete(); throw failure }
    }

    /** 只清理当前会话中超过七天且已无持有者的预览；清理失败留待下次，不影响已保存成果。 */
    suspend fun cleanup(sessionId: String, protectedPaths: Set<String>) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        directory(sessionId).listFiles()?.filter { it.isDirectory && it.name != previewGeneration }?.forEach { generation ->
            generation.listFiles()?.forEach { file ->
                if (file.isFile && file.extension == "png" && file.lastModified() < cutoff && file.absolutePath !in protectedPaths) file.delete()
            }
            if (generation.list()?.isEmpty() == true) generation.delete()
        }
    }

    suspend fun readSource(sessionId: String, path: String): ByteArray = withContext(Dispatchers.IO) {
        val resolved = resolver.resolve(sessionId, path)
        require(resolved is MediaResolution.Available && resolved.ref.kind == MediaKind.IMAGE) { "当前会话工作区图片不可读取，请检查路径与授权。" }
        val limit = 50 * 1024 * 1024
        require(resolved.ref.sizeBytes < limit) { "画板原图必须小于 50 MiB。" }
        resolver.open(resolved).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(output.size().toLong() + count < limit) { "画板原图必须小于 50 MiB。" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    companion object {
        // 同进程所有 ViewModel 共用代号，清理不会触碰尚未登记到输入框的新预览。
        private val PROCESS_GENERATION = UUID.randomUUID().toString()
    }

    private fun dimensions(png: ByteArray): Pair<Int, Int> = ByteBuffer.wrap(png, 16, 8).let { it.int to it.int }
}
