package com.promenar.infinity.data.files

import com.promenar.infinity.domain.files.FileOpCode
import com.promenar.infinity.domain.files.FileOpException
import com.promenar.infinity.domain.files.FileTypeFilter
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalFolderPaths
import java.util.Base64
import java.util.Locale

internal sealed interface LocationKey {
    data object Library : LocationKey
    data object Sessions : LocationKey
    data object LinuxHome : LocationKey
    data class Session(val id: String) : LocationKey
    data class Workspace(val id: String) : LocationKey
    data class External(val id: String) : LocationKey
}

internal fun parseLocationId(id: String): LocationKey? = when {
    id == "kb" -> LocationKey.Library
    id == "sessions" -> LocationKey.Sessions
    id == "linux:home" -> LocationKey.LinuxHome
    id.startsWith("session:") && id.length > 8 -> LocationKey.Session(id.substring(8))
    id.startsWith("workspace:") && id.length > 10 -> LocationKey.Workspace(id.substring(10))
    id.startsWith("external:") && id.length > 9 -> LocationKey.External(id.substring(9))
    else -> null
}

internal fun normalizeFilePath(path: String): String = try {
    ExternalFolderPaths.normalize(path)
} catch (error: ExternalFolderException) {
    throw FileOpException(FileOpCode.INVALID_NAME, cause = error)
}

internal fun validateFileName(name: String): String = try {
    ExternalFolderPaths.validateName(name)
} catch (error: ExternalFolderException) {
    throw FileOpException(FileOpCode.INVALID_NAME, cause = error)
}

internal fun joinFilePath(parent: String, name: String): String =
    normalizeFilePath(normalizeFilePath(parent).trimEnd('/') + "/" + validateFileName(name))

internal fun parentFilePath(path: String): String = normalizeFilePath(path).substringBeforeLast('/').ifEmpty { "/" }

internal fun collisionName(name: String, directory: Boolean, index: Int): String {
    validateFileName(name)
    require(index >= 2)
    val dot = name.lastIndexOf('.')
    val split = if (!directory && dot > 0) dot else name.length
    return name.substring(0, split) + " ($index)" + name.substring(split)
}

/** 根据扩展名和 MIME 判断文件的浏览分类。 */
fun classifyFile(name: String, mimeType: String?): FileTypeFilter {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return when {
        ext in setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz") || mime in setOf("application/zip", "application/x-7z-compressed", "application/x-tar", "application/gzip") -> FileTypeFilter.ARCHIVE
        ext in setOf("kt", "kts", "java", "c", "h", "cpp", "cc", "hpp", "py", "js", "jsx", "ts", "tsx", "rs", "go", "swift", "sh", "sql", "css", "xml", "yaml", "yml", "toml", "json") -> FileTypeFilter.CODE
        mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "avif") -> FileTypeFilter.IMAGE
        mime.startsWith("audio/") || ext in setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus") -> FileTypeFilter.AUDIO
        mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "webm", "avi", "m4v") -> FileTypeFilter.VIDEO
        mime.startsWith("text/") || mime == "application/pdf" || ext in setOf("txt", "md", "markdown", "pdf", "doc", "docx", "odt", "rtf", "csv", "xls", "xlsx", "ppt", "pptx", "html", "htm") -> FileTypeFilter.DOCUMENT
        else -> FileTypeFilter.OTHER
    }
}

internal fun safeZipPath(raw: String): List<String> {
    if (raw.isEmpty() || raw.startsWith('/') || '\\' in raw || Regex("^[A-Za-z]:").containsMatchIn(raw)) {
        throw FileOpException(FileOpCode.INVALID_NAME)
    }
    val clean = raw.removeSuffix("/")
    val parts = clean.split('/')
    if (parts.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." }) throw FileOpException(FileOpCode.INVALID_NAME)
    return parts.map(::validateFileName)
}

internal data class RecycleKey(val locationId: String, val itemId: String)

internal fun encodeRecycleId(key: RecycleKey): String = listOf(key.locationId, key.itemId).joinToString(".") {
    Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
}

internal fun decodeRecycleId(id: String): RecycleKey? = try {
    val parts = id.split('.')
    if (parts.size != 2) null else {
        val location = String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
        val item = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        if (parseLocationId(location) == null || item.isEmpty()) null else RecycleKey(location, item)
    }
} catch (_: IllegalArgumentException) { null }
