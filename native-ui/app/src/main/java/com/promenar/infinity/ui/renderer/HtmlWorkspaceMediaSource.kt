package com.promenar.infinity.ui.renderer

import com.promenar.infinity.data.storage.AndroidRealPathFileSystem
import com.promenar.infinity.data.storage.BoundRealPathDirectory
import com.promenar.infinity.data.storage.RealPathFileSystem
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** 仅绑定一张 HTML 卡片的会话主工作区；不接受挂载别名、宿主路径或会话 id 参数。 */
internal class HtmlWorkspaceMediaSource(
    private val rootForSession: suspend () -> File?,
    private val fileSystem: RealPathFileSystem = AndroidRealPathFileSystem,
) {
    private var rootIdentity: Pair<String, String>? = null

    /** WebView 的资源回调位于工作线程；此方法及返回流都只执行只读 IO。 */
    suspend fun respond(url: String, method: String, range: String?): HtmlMediaResponse {
        if (method !in setOf("GET", "HEAD")) return empty(405, "Method Not Allowed", mapOf("Allow" to "GET, HEAD"))
        val relative = relativePath(url) ?: return empty(403, "Blocked")
        val directories = mutableListOf<BoundRealPathDirectory>()
        try {
            val suppliedRoot = rootForSession() ?: return empty(403, "Blocked")
            if (Files.isSymbolicLink(suppliedRoot.toPath())) return empty(403, "Blocked")
            // 只解析工作区外的系统路径别名；工作区根与根内全部由目录 fd 逐段打开。
            val root = File(suppliedRoot.parentFile!!.canonicalFile, suppliedRoot.name)
            var directory = fileSystem.openFileSystemRoot().also(directories::add)
            root.toPath().forEach { segment -> directory = directory.openDirectory(segment.toString()).also(directories::add) }
            val identity = root.path to directory.state().objectId
            synchronized(this) {
                if (rootIdentity == null) rootIdentity = identity
                check(rootIdentity == identity) { "HTML 卡片的工作区已经变化" }
            }
            val segments = relative.split('/')
            segments.dropLast(1).forEach { segment -> directory = directory.openDirectory(segment).also(directories::add) }
            val name = segments.last()
            val stat = directory.stat(name) ?: return empty(403, "Blocked")
            if (!stat.isRegularFile || stat.isSymbolicLink) return empty(403, "Blocked")
            val opened = directory.openRead(name)
            var transferred = false
            try {
                val state = opened.state()
                if (!state.isRegularFile || state.isSymbolicLink || state.objectId != stat.objectId) return empty(403, "Blocked")
                val size = state.size
                val selected = if (range == null || method == "HEAD") null else {
                    byteRange(range, size)
                        ?: return empty(416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$size", "Accept-Ranges" to "bytes"))
                }
                val start = selected?.first ?: 0L
                val length = selected?.let { it.last - it.first + 1 } ?: size
                val headers = mapOf("Accept-Ranges" to "bytes", "Content-Length" to length.toString()) +
                    if (selected == null) emptyMap() else mapOf("Content-Range" to "bytes ${selected.first}-${selected.last}/$size")
                if (method == "HEAD") return HtmlMediaResponse(mime(relative), 200, "OK", headers + securityHeaders, ByteArrayInputStream(ByteArray(0)))
                var remainingSkip = start
                while (remainingSkip > 0) {
                    val skipped = opened.input.skip(remainingSkip)
                    if (skipped > 0) remainingSkip -= skipped
                    else { check(opened.input.read() >= 0) { "媒体长度已变化" }; remainingSkip-- }
                }
                val body = object : FilterInputStream(opened.input) {
                    private var remaining = length
                    override fun read(): Int {
                        if (remaining == 0L) return -1
                        return `in`.read().also { if (it >= 0) remaining-- }
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (length == 0) return 0
                        if (remaining == 0L) return -1
                        return `in`.read(buffer, offset, minOf(length.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
                    }
                    override fun skip(count: Long): Long = `in`.skip(minOf(count.coerceAtLeast(0), remaining)).also { remaining -= it }
                    override fun available(): Int = minOf(`in`.available().toLong(), remaining).toInt()
                    override fun close() = opened.close()
                }
                transferred = true
                return HtmlMediaResponse(mime(relative), if (selected == null) 200 else 206,
                    if (selected == null) "OK" else "Partial Content", headers + securityHeaders, body)
            } finally {
                // 已转交响应体的句柄由 body.close 关闭；HEAD、拒绝与异常由这里关闭。
                if (!transferred) opened.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return empty(403, "Blocked")
        } finally {
            directories.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun empty(status: Int, reason: String, headers: Map<String, String> = emptyMap()) =
        HtmlMediaResponse("text/plain", status, reason, securityHeaders + headers, ByteArrayInputStream(ByteArray(0)))

    companion object {
        const val HOST = "workspace-media.infinity.invalid"
        const val BASE_URL = "https://$HOST/workspace/"
        private val types = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
            "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
            "avif" to "image/avif", "svg" to "image/svg+xml",
            "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm",
            "mkv" to "video/x-matroska", "3gp" to "video/3gpp", "ts" to "video/mp2t",
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav",
            "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg", "flac" to "audio/flac", "amr" to "audio/amr",
        )
        private val securityHeaders = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
            "Content-Security-Policy" to "default-src 'none'; sandbox", "Referrer-Policy" to "no-referrer")

        fun isOrigin(url: String): Boolean = runCatching { URI(url).host.equals(HOST, ignoreCase = true) }.getOrDefault(false)

        fun relativePath(url: String): String? = runCatching {
            if (url.any { it <= ' ' || it == '\\' }) return null
            val uri = URI(url)
            if (uri.scheme != "https" || uri.host != HOST || uri.port !in listOf(-1, 443) ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || !uri.rawPath.startsWith("/workspace/")) return null
            val segments = uri.rawPath.removePrefix("/workspace/").split('/').map { encoded ->
                val decoded = URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
                if (decoded.isEmpty() || decoded in setOf(".", "..") || decoded.any { it == '/' || it == '\\' || it == '%' || it == '\uFFFD' || Character.isISOControl(it) } ||
                    decoded.startsWith(".infinity", true) || decoded.startsWith(".nexara", true)) return null
                decoded
            }
            segments.joinToString("/").takeIf { it.substringAfterLast('.').lowercase(Locale.ROOT) in types }
        }.getOrNull()

        private fun mime(path: String): String = types.getValue(path.substringAfterLast('.').lowercase(Locale.ROOT))

        private fun byteRange(value: String, size: Long): LongRange? {
            val match = Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(value) ?: return null
            if (size <= 0) return null
            val (first, last) = match.destructured
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
                return (size - minOf(size, suffix))..(size - 1)
            }
            val start = first.toLongOrNull()?.takeIf { it in 0 until size } ?: return null
            val end = if (last.isEmpty()) size - 1 else last.toLongOrNull()?.takeIf { it >= start } ?: return null
            return start..minOf(end, size - 1)
        }
    }
}

internal data class HtmlMediaResponse(val mime: String, val status: Int, val reason: String,
    val headers: Map<String, String>, val body: InputStream) : Closeable {
    override fun close() = body.close()
}
