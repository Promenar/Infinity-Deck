package com.promenar.infinity.data.production

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 3D 白模预演的工作区文件读写契约，解耦真实存储后端与单元测试。 */
interface PrevisFileAccess {
    suspend fun normalizeRelativePath(sessionId: String, rawPath: String, projectRoot: String?): String
    suspend fun read(sessionId: String, path: String): ByteArray?
    suspend fun replaceBytes(sessionId: String, path: String, bytes: ByteArray)
    suspend fun replaceFile(sessionId: String, path: String, file: File)
}

/**
 * 负责离线 3D 白模预演 DSL（`*.previs.json`）及导出产物（Beauty/Depth PNG、H.264 MP4）的持久化与解析。
 */
class ProductionPrevisStore(
    private val files: PrevisFileAccess,
) {
    suspend fun normalizePath(sessionId: String, rawPath: String, projectRoot: String): String =
        withContext(Dispatchers.IO) {
            files.normalizeRelativePath(sessionId, rawPath, projectRoot).also(ProductionManifestCodec::validatePath)
        }

    suspend fun load(sessionId: String, path: String, episode: Episode? = null): PrevisScene? =
        withContext(Dispatchers.IO) {
            ProductionManifestCodec.validatePath(path)
            val bytes = files.read(sessionId, path) ?: return@withContext null
            require(bytes.size <= PrevisCodec.MAX_JSON_BYTES) { "3D 预演文件超过 512 KiB 上限。" }
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            PrevisCodec.decode(text, episode)
        }

    suspend fun saveScene(sessionId: String, path: String, scene: PrevisScene, episode: Episode? = null): PrevisScene =
        withContext(Dispatchers.IO) {
            ProductionManifestCodec.validatePath(path)
            val encoded = PrevisCodec.encode(scene, episode)
            files.replaceBytes(sessionId, path, encoded.toByteArray(Charsets.UTF_8))
            scene
        }

    suspend fun savePng(sessionId: String, path: String, pngBytes: ByteArray) =
        withContext(Dispatchers.IO) {
            ProductionManifestCodec.validatePath(path)
            require(pngBytes.isNotEmpty()) { "导出的 PNG 图像为空。" }
            files.replaceBytes(sessionId, path, pngBytes)
        }

    suspend fun saveVideo(sessionId: String, path: String, tempFile: File) =
        withContext(Dispatchers.IO) {
            ProductionManifestCodec.validatePath(path)
            require(tempFile.isFile && tempFile.length() > 0L) { "导出的视频临时文件不存在或为空。" }
            files.replaceFile(sessionId, path, tempFile)
        }
}
