package com.promenar.infinity.data.production

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface HorizontalImageCodec { fun compose(parts: List<ByteArray>): ByteArray }

/** 按原始像素横向拼接，顶部对齐；空余区域透明，编码参数固定。 */
class AndroidHorizontalImageCodec : HorizontalImageCodec {
    override fun compose(parts: List<ByteArray>): ByteArray {
        require(parts.isNotEmpty() && parts.size <= 32)
        val bounds = parts.map { bytes -> BitmapFactory.Options().also { it.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) } }
        require(bounds.all { it.outWidth > 0 && it.outHeight > 0 }) { "INVALID_REFERENCE_IMAGE" }
        val width = bounds.sumOf { it.outWidth.toLong() }
        val height = bounds.maxOf { it.outHeight }
        require(width <= 16384 && width * height <= 16_000_000) { "REFERENCE_SHEET_TOO_LARGE" }
        val result = Bitmap.createBitmap(width.toInt(), height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(result)
            var left = 0
            parts.forEach { bytes ->
                val image = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                try { canvas.drawBitmap(image, left.toFloat(), 0f, null); left += image.width } finally { image.recycle() }
            }
            return ByteArrayOutputStream().use { output -> check(result.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray().also { require(it.size <= 16 * 1024 * 1024) } }
        } finally { result.recycle() }
    }
}

/** 缓存键包含算法版本、有序路径和素材内容；原图被覆盖也会产生新派生文件。 */
class CachedCharacterCompositor(private val files: ProductionWorkspaceFiles, private val codec: HorizontalImageCodec = AndroidHorizontalImageCodec(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val mutex = Mutex()
    private val verified = mutableMapOf<Pair<String, String>, String>()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    suspend fun materialize(sessionId: String, rootPath: String, paths: List<String>): String = withContext(ioDispatcher) {
        mutex.withLock {
            require(paths.isNotEmpty())
            ProductionManifestCodec.validatePath(rootPath)
            val parts = paths.map { path -> ProductionManifestCodec.validatePath(path); requireNotNull(files.read(sessionId, path)) { "MISSING_REFERENCE_IMAGE" } }
            require(parts.sumOf { it.size.toLong() } <= 64L * 1024 * 1024)
            val hash = MessageDigest.getInstance("SHA-256")
            hash.update("horizontal-original-pixels-v1".toByteArray())
            paths.zip(parts).forEach { (path, bytes) ->
                hash.update(path.toByteArray()); hash.update(0.toByte()); hash.update(bytes.size.toString().toByteArray()); hash.update(0.toByte()); hash.update(bytes)
            }
            val key = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val target = "$rootPath/derived/character-sheets/$key.png"
            val cached = files.read(sessionId, target)
            if (cached == null) {
                val bytes = codec.compose(parts)
                try { files.write(sessionId, target, bytes, null) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { if (files.read(sessionId, target)?.contentEquals(bytes) != true) throw failure }
                verified[sessionId to target] = digest(bytes)
            } else {
                val currentDigest = digest(cached)
                if (verified[sessionId to target] != currentDigest) {
                    require(cached.contentEquals(codec.compose(parts))) { "INVALID_REFERENCE_SHEET" }
                    verified[sessionId to target] = currentDigest
                }
            }
            target
        }
    }
}
