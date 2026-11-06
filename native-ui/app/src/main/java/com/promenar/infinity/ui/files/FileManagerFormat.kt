package com.promenar.infinity.ui.files

import com.promenar.infinity.domain.files.FileRef
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.ui.graphics.vector.ImageVector
import com.promenar.infinity.R
import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.domain.files.FileOpCode
import com.promenar.infinity.domain.files.FileSortKey
import com.promenar.infinity.domain.files.FileTypeFilter
import com.promenar.infinity.domain.files.FileKnowledge
import com.promenar.infinity.domain.files.KnowledgeIndexState
import com.promenar.infinity.domain.library.PendingStage
import com.promenar.infinity.ui.common.motion.IndexMotionPhase
import com.promenar.infinity.ui.common.motion.IndexMotionState
import com.promenar.infinity.ui.files.state.FileCrumb
import com.promenar.infinity.ui.files.state.FileManagerNotice
import com.promenar.infinity.ui.files.state.FileManagerUiState
import com.promenar.infinity.ui.media.formatMediaSize
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * 资源管理器的纯展示换算：文件类别、徽标文字、大小与时间、资料状态与操作结果文案。
 * 不含 Compose 状态，便于预览与单元测试直接调用。
 */

/** 文件在界面上的类别，决定徽标配色与图标。 */
internal enum class FileKind { FOLDER, PDF, DOCUMENT, TEXT, CODE, IMAGE, AUDIO, VIDEO, ARCHIVE, OTHER }

private val DocumentExtensions = setOf("doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "epub")
private val TextExtensions = setOf("md", "markdown", "txt", "log", "org", "rst", "tex")
private val CodeExtensions = setOf(
    "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "json", "yaml", "yml", "toml", "xml", "html", "htm", "css",
    "sh", "bash", "zsh", "c", "h", "cpp", "hpp", "rs", "go", "rb", "php", "swift", "sql", "gradle", "ini", "conf",
)
private val ImageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "svg", "avif")
private val AudioExtensions = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr")
private val VideoExtensions = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v")
private val ArchiveExtensions = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "apk")

internal fun fileExtension(name: String): String =
    name.substringAfterLast('.', missingDelimiterValue = "").takeIf { name.indexOf('.') > 0 }?.lowercase(Locale.ROOT) ?: ""

internal fun fileKindOf(item: FileItem): FileKind {
    if (item.isDirectory) return FileKind.FOLDER
    val ext = fileExtension(item.name)
    val mime = item.mimeType.orEmpty()
    return when {
        ext == "pdf" || mime == "application/pdf" -> FileKind.PDF
        ext in ImageExtensions || mime.startsWith("image/") -> FileKind.IMAGE
        ext in AudioExtensions || mime.startsWith("audio/") -> FileKind.AUDIO
        ext in VideoExtensions || mime.startsWith("video/") -> FileKind.VIDEO
        ext in ArchiveExtensions || mime == "application/zip" -> FileKind.ARCHIVE
        ext in DocumentExtensions -> FileKind.DOCUMENT
        ext in TextExtensions || mime == "text/plain" || mime == "text/markdown" -> FileKind.TEXT
        ext in CodeExtensions -> FileKind.CODE
        else -> FileKind.OTHER
    }
}

/** 只认 ZIP：解压菜单项只对它显示。 */
internal fun FileItem.isZip(): Boolean =
    !isDirectory && (fileExtension(name) == "zip" || mimeType == "application/zip")

/** 徽标文字：扩展名大写，最多 4 个字符；没有扩展名时为空，由图标代替。 */
internal fun fileBadgeLabel(item: FileItem): String {
    if (item.isDirectory) return ""
    val ext = fileExtension(item.name)
    return if (ext.isEmpty() || ext.length > 4) "" else ext.uppercase(Locale.ROOT)
}

internal fun fileKindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.FOLDER -> Icons.Rounded.Folder
    FileKind.PDF -> Icons.Rounded.PictureAsPdf
    FileKind.DOCUMENT, FileKind.TEXT -> Icons.Rounded.Description
    FileKind.CODE -> Icons.Rounded.Code
    FileKind.IMAGE -> Icons.Rounded.Image
    FileKind.AUDIO -> Icons.Rounded.AudioFile
    FileKind.VIDEO -> Icons.Rounded.Movie
    FileKind.ARCHIVE -> Icons.Rounded.FolderZip
    FileKind.OTHER -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

internal fun locationKindIcon(kind: FileLocationKind): ImageVector = when (kind) {
    FileLocationKind.KNOWLEDGE_BASE -> Icons.AutoMirrored.Rounded.LibraryBooks
    FileLocationKind.SESSION_WORKSPACE -> Icons.Rounded.Workspaces
    FileLocationKind.PROJECT -> Icons.Rounded.FolderSpecial
    FileLocationKind.LINUX_HOME -> Icons.Rounded.Terminal
    FileLocationKind.EXTERNAL_FOLDER -> Icons.Rounded.FolderShared
    FileLocationKind.BRANCH_WORKTREE -> Icons.Rounded.Computer
}

internal fun formatFileSize(bytes: Long): String = formatMediaSize(bytes)

/** 绝对时间（年-月-日 时:分），按系统时区；不用相对时间，截图与列表都稳定。 */
internal fun formatFileTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    FileTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

private val FileTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

/** 当前文件夹名：最后一级面包屑，根目录为位置名。 */
internal fun FileManagerUiState.currentFolderTitle(): String {
    if (path == "/" || path.isEmpty()) return location?.title.orEmpty()
    return crumbs.lastOrNull()?.title?.takeIf { it.isNotBlank() } ?: path.trimEnd('/').substringAfterLast('/')
}

/** 父文件夹名，供“..”行的副文字。 */
internal fun FileManagerUiState.parentFolderTitle(): String {
    val full = fullCrumbs()
    return if (full.size >= 2) full[full.size - 2].title else location?.title.orEmpty()
}

/** 带根的完整面包屑：控制器给出的列表未含根目录时补上位置名。 */
internal fun FileManagerUiState.fullCrumbs(): List<FileCrumb> =
    withRootCrumb(crumbs, location?.title.orEmpty(), path)

internal fun withRootCrumb(crumbs: List<FileCrumb>, rootTitle: String, path: String): List<FileCrumb> {
    val base = if (crumbs.isNotEmpty()) crumbs else pathCrumbs(path)
    return if (base.firstOrNull()?.path == "/") {
        base
    } else {
        listOf(FileCrumb(rootTitle, "/")) + base
    }
}

/** 由路径推出的面包屑（不含根）；控制器未给出时兜底。 */
internal fun pathCrumbs(path: String): List<FileCrumb> {
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    return parts.mapIndexed { index, part -> FileCrumb(part, "/" + parts.take(index + 1).joinToString("/")) }
}

/** 文件名去掉扩展名后的长度，重命名时默认只选中这一段。 */
internal fun renameSelectionEnd(name: String, isDirectory: Boolean): Int {
    if (isDirectory) return name.length
    val dot = name.lastIndexOf('.')
    return if (dot > 0) dot else name.length
}

/** 名称是否可用：非空、不含路径分隔符、不是 “.” 与 “..”。 */
internal fun isValidFileName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() && '/' !in trimmed && '\\' !in trimmed && trimmed != "." && trimmed != ".."
}

@StringRes
internal fun sortKeyLabel(key: FileSortKey): Int = when (key) {
    FileSortKey.NAME -> R.string.fm_sort_name
    FileSortKey.MODIFIED -> R.string.fm_sort_modified
    FileSortKey.SIZE -> R.string.fm_sort_size
    FileSortKey.TYPE -> R.string.fm_sort_type
}

@StringRes
internal fun typeFilterLabel(filter: FileTypeFilter): Int = when (filter) {
    FileTypeFilter.DOCUMENT -> R.string.fm_type_document
    FileTypeFilter.IMAGE -> R.string.fm_type_image
    FileTypeFilter.AUDIO -> R.string.fm_type_audio
    FileTypeFilter.VIDEO -> R.string.fm_type_video
    FileTypeFilter.ARCHIVE -> R.string.fm_type_archive
    FileTypeFilter.CODE -> R.string.fm_type_code
    FileTypeFilter.OTHER -> R.string.fm_type_other
}

/** 列表行只给需要用户留意的资料状态加徽标；已索引、未加入与不支持不显示。 */
@StringRes
internal fun knowledgeBadgeLabel(state: KnowledgeIndexState): Int? = when (state) {
    KnowledgeIndexState.FAILED -> R.string.fm_knowledge_failed
    KnowledgeIndexState.QUEUED -> R.string.fm_knowledge_queued
    KnowledgeIndexState.STALE -> R.string.fm_knowledge_stale
    KnowledgeIndexState.INDEXING -> R.string.fm_knowledge_indexing
    KnowledgeIndexState.NONE, KnowledgeIndexState.INDEXED, KnowledgeIndexState.UNSUPPORTED -> null
}

/**
 * 列表行的索引指示：排队与索引中显示与资料首页同一套索引动效，其它状态只用徽标文字。
 * 进度取当前阶段的已处理 / 总数，没有总数时按 0 处理，指示只表达阶段。
 */
internal fun FileKnowledge.indexMotionState(): IndexMotionState? {
    val fraction = total?.takeIf { it > 0 }?.let { (processed ?: 0).toFloat() / it }?.coerceIn(0f, 1f) ?: 0f
    return when (state) {
        KnowledgeIndexState.QUEUED -> IndexMotionState(IndexMotionPhase.QUEUED)
        KnowledgeIndexState.INDEXING -> when (stage) {
            PendingStage.VECTORIZING -> IndexMotionState(IndexMotionPhase.VECTORIZING, vectorProgress = fraction)
            PendingStage.EXTRACTING_GRAPH ->
                IndexMotionState(IndexMotionPhase.EXTRACTING_GRAPH, vectorProgress = 1f, graphProgress = fraction, withGraph = true)
            PendingStage.SAVING -> IndexMotionState(IndexMotionPhase.SAVING, vectorProgress = 1f)
            PendingStage.PREPARING, null -> IndexMotionState(IndexMotionPhase.PREPARING)
        }
        else -> null
    }
}

/** 信息卡里的完整资料状态。 */
@StringRes
internal fun knowledgeStateLabel(state: KnowledgeIndexState): Int = when (state) {
    KnowledgeIndexState.NONE -> R.string.fm_knowledge_none
    KnowledgeIndexState.QUEUED -> R.string.fm_knowledge_queued
    KnowledgeIndexState.INDEXING -> R.string.fm_knowledge_indexing
    KnowledgeIndexState.INDEXED -> R.string.fm_knowledge_indexed
    KnowledgeIndexState.FAILED -> R.string.fm_knowledge_failed
    KnowledgeIndexState.STALE -> R.string.fm_knowledge_stale
    KnowledgeIndexState.UNSUPPORTED -> R.string.fm_knowledge_unsupported
}

@StringRes
internal fun opCodeReason(code: FileOpCode): Int = when (code) {
    FileOpCode.NAME_EXISTS -> R.string.fm_error_name_exists
    FileOpCode.INVALID_NAME -> R.string.fm_error_invalid_name
    FileOpCode.NOT_FOUND -> R.string.fm_error_not_found
    FileOpCode.READ_ONLY -> R.string.fm_error_read_only
    FileOpCode.PERMISSION_LOST -> R.string.fm_error_permission_lost
    FileOpCode.TOO_LARGE -> R.string.fm_error_too_large
    FileOpCode.NO_SPACE -> R.string.fm_error_no_space
    FileOpCode.UNSUPPORTED -> R.string.fm_error_unsupported
    FileOpCode.CONFLICT -> R.string.fm_error_conflict
    FileOpCode.IO -> R.string.fm_error_io
}

@StringRes
private fun noticeKindLabel(kind: FileManagerNotice.Kind): Int = when (kind) {
    FileManagerNotice.Kind.CREATE -> R.string.fm_kind_create
    FileManagerNotice.Kind.RENAME -> R.string.fm_kind_rename
    FileManagerNotice.Kind.COPY -> R.string.fm_kind_copy
    FileManagerNotice.Kind.MOVE -> R.string.fm_kind_move
    FileManagerNotice.Kind.DELETE -> R.string.fm_kind_delete
    FileManagerNotice.Kind.COMPRESS -> R.string.fm_kind_compress
    FileManagerNotice.Kind.EXTRACT -> R.string.fm_kind_extract
    FileManagerNotice.Kind.IMPORT -> R.string.fm_kind_import
    FileManagerNotice.Kind.PIN -> R.string.fm_kind_pin
}

/** 操作结果的 Snackbar 文案。 */
internal fun fileNoticeText(resources: Resources, notice: FileManagerNotice): String = when (notice) {
    is FileManagerNotice.Done -> doneText(resources, notice)
    is FileManagerNotice.Failed -> {
        val action = resources.getString(noticeKindLabel(notice.kind))
        val reason = resources.getString(opCodeReason(notice.code))
        if (notice.total > 1 && notice.failedCount < notice.total) {
            resources.getString(R.string.fm_notice_failed_some, action, notice.failedCount, notice.total, reason)
        } else {
            resources.getString(R.string.fm_notice_failed, action, reason)
        }
    }
}

private fun doneText(resources: Resources, notice: FileManagerNotice.Done): String {
    val n = notice.count
    val target = notice.target
    return when (notice.kind) {
        FileManagerNotice.Kind.CREATE -> resources.getString(R.string.fm_notice_created)
        FileManagerNotice.Kind.RENAME -> resources.getString(R.string.fm_notice_renamed)
        FileManagerNotice.Kind.COPY -> if (target != null) {
            resources.getQuantityString(R.plurals.fm_notice_copied_to, n, n, target)
        } else {
            resources.getQuantityString(R.plurals.fm_notice_copied, n, n)
        }
        FileManagerNotice.Kind.MOVE -> if (target != null) {
            resources.getQuantityString(R.plurals.fm_notice_moved_to, n, n, target)
        } else {
            resources.getQuantityString(R.plurals.fm_notice_moved, n, n)
        }
        FileManagerNotice.Kind.DELETE -> resources.getQuantityString(R.plurals.permanent_delete_success, n, n)
        FileManagerNotice.Kind.COMPRESS -> resources.getString(R.string.fm_notice_compressed)
        FileManagerNotice.Kind.EXTRACT -> resources.getString(R.string.fm_notice_extracted)
        FileManagerNotice.Kind.IMPORT -> resources.getQuantityString(R.plurals.fm_notice_imported, n, n)
        FileManagerNotice.Kind.PIN -> resources.getString(R.string.fm_notice_pinned)
    }
}

/**
 * 列表行的稳定键：同一列表可能混排不同位置的同名路径（例如“会话工作区”汇总根下每个会话都是 "/"），
 * 因此必须带上位置 id，否则 LazyColumn 会因重复键崩溃。
 */
internal fun FileRef.listKey(): String = "$locationId|$path"
