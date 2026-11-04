package com.promenar.infinity.ui.files.state

import com.promenar.infinity.domain.files.FileItem
import java.util.Locale

enum class FileKind { PDF, MD, DOC, SHEET, SLIDE, WEB, TXT, CODE, IMG, AUDIO, VIDEO, ZIP, FOLDER, OTHER }

object FileKinds {
    fun kind(item: FileItem): FileKind {
        if (item.isDirectory) return FileKind.FOLDER
        val extension = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val fromExtension = when (extension) {
            "pdf" -> FileKind.PDF
            "md", "markdown", "mdown" -> FileKind.MD
            "doc", "docx", "odt", "rtf" -> FileKind.DOC
            "xls", "xlsx", "ods", "csv", "tsv" -> FileKind.SHEET
            "ppt", "pptx", "odp" -> FileKind.SLIDE
            "htm", "html", "xhtml" -> FileKind.WEB
            "txt", "log" -> FileKind.TXT
            "kt", "kts", "java", "js", "jsx", "ts", "tsx", "py", "sh", "bash", "c", "h", "cpp", "hpp", "rs", "go", "css", "scss", "json", "xml", "yaml", "yml", "toml", "sql" -> FileKind.CODE
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "avif" -> FileKind.IMG
            "mp3", "wav", "m4a", "ogg", "flac", "aac", "opus" -> FileKind.AUDIO
            "mp4", "mkv", "webm", "mov", "avi", "m4v" -> FileKind.VIDEO
            "zip", "tar", "gz", "tgz", "7z", "rar" -> FileKind.ZIP
            else -> null
        }
        if (fromExtension != null) return fromExtension
        val mime = item.mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            mime == "application/pdf" -> FileKind.PDF
            mime == "text/markdown" -> FileKind.MD
            mime == "text/html" || mime == "application/xhtml+xml" -> FileKind.WEB
            mime in setOf("application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.oasis.opendocument.text", "application/rtf") -> FileKind.DOC
            mime in setOf("application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.oasis.opendocument.spreadsheet", "text/csv", "text/tab-separated-values") -> FileKind.SHEET
            mime in setOf("application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.oasis.opendocument.presentation") -> FileKind.SLIDE
            mime.startsWith("image/") -> FileKind.IMG
            mime.startsWith("audio/") -> FileKind.AUDIO
            mime.startsWith("video/") -> FileKind.VIDEO
            mime in setOf("application/zip", "application/x-tar", "application/gzip", "application/x-7z-compressed", "application/vnd.rar") -> FileKind.ZIP
            mime in setOf("application/json", "application/xml", "application/javascript", "application/x-sh", "application/sql") -> FileKind.CODE
            mime.startsWith("text/") -> FileKind.TXT
            else -> FileKind.OTHER
        }
    }

    fun isTextPreviewable(item: FileItem): Boolean {
        if (item.isDirectory) return false
        return when (kind(item)) {
            FileKind.MD, FileKind.WEB, FileKind.TXT, FileKind.CODE -> true
            FileKind.SHEET -> item.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("csv", "tsv") ||
                (item.mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: "") in setOf("text/csv", "text/tab-separated-values")
            else -> false
        }
    }
}
