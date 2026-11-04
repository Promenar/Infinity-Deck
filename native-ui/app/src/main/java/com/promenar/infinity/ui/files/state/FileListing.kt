package com.promenar.infinity.ui.files.state

import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileSort
import com.promenar.infinity.domain.files.FileSortKey
import java.text.Collator
import java.util.Locale

/** 对同一目录中的条目应用隐藏文件和排序偏好。 */
object FileListing {
    fun items(items: List<FileItem>, sort: FileSort, showHidden: Boolean): List<FileItem> {
        val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
        val comparator = Comparator<FileItem> { left, right ->
            if (sort.foldersFirst && left.isDirectory != right.isDirectory) {
                if (left.isDirectory) -1 else 1
            } else {
                val primary = when (sort.key) {
                    FileSortKey.NAME -> collator.compare(left.name, right.name)
                    FileSortKey.MODIFIED -> left.lastModified.compareTo(right.lastModified)
                    FileSortKey.SIZE -> left.sizeBytes.compareTo(right.sizeBytes)
                    FileSortKey.TYPE -> collator.compare(FileKinds.kind(left).name, FileKinds.kind(right).name)
                }
                val value = if (primary != 0) primary else collator.compare(left.name, right.name)
                if (sort.ascending) value else -value
            }
        }
        return items.asSequence().filter { showHidden || !it.name.startsWith('.') }.sortedWith(comparator).toList()
    }

    fun crumbs(location: FileLocation, path: String): List<FileCrumb> {
        var current = ""
        return listOf(FileCrumb(location.title, "/")) + normalizeFilePath(path).split('/')
            .filter { it.isNotEmpty() }
            .map { part ->
                current += "/$part"
                FileCrumb(part, current)
            }
    }
}

internal fun normalizeFilePath(path: String): String {
    val parts = mutableListOf<String>()
    for (part in path.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(part)
        }
    }
    return if (parts.isEmpty()) "/" else parts.joinToString(separator = "/", prefix = "/")
}

internal fun parentFilePath(path: String): String {
    val normalized = normalizeFilePath(path)
    return normalized.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { "/" }
}

object FileNames {
    fun isValid(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            '/' !in name && '\\' !in name && '\u0000' !in name && name.toByteArray(Charsets.UTF_8).size <= 255

    /** 返回重命名输入框从 0 开始选中的结束位置。 */
    fun renameSelectionEnd(name: String): Int {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.lastIndex) dot else name.length
    }
}
