package com.promenar.infinity.data.document.external

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import java.io.File
import java.io.IOException

/** 从打开或分享 Intent 里取出交给查看器的 Markdown 文件；不是单个 Markdown 文件时返回 null。 */
object ExternalDocumentIntents {
    fun markdownUri(intent: Intent?): Uri? {
        intent ?: return null
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT && uri.scheme != ContentResolver.SCHEME_FILE) return null
        return uri.takeIf { ExternalMarkdown.accepts(intent.type, it.path ?: it.lastPathSegment) || ExternalMarkdown.accepts(intent.type, it.lastPathSegment) }
    }
}

/**
 * file:// 路径不能指向应用自己的私有目录：其它应用可以借此诱导查看器显示并转发应用私有文件。
 * 判断基于规范化路径，符号链接解析后再比较。
 */
fun isAllowedExternalFile(file: File, privateRoots: List<File>): Boolean {
    val canonical = try { file.canonicalFile } catch (_: IOException) { return false }
    return privateRoots.none { root ->
        val base = try { root.canonicalFile } catch (_: IOException) { return false }
        canonical == base || canonical.path.startsWith(base.path + File.separator)
    }
}

/** 内容链接是否由本应用自己的 ContentProvider 提供。 */
fun Context.ownsContentUri(uri: Uri): Boolean =
    uri.scheme == ContentResolver.SCHEME_CONTENT &&
        uri.authority?.let { packageManager.resolveContentProvider(it, 0)?.packageName } == packageName

/** 查询显示名与大小，并给出读取入口；在 IO 线程调用。 */
fun androidExternalDocumentInput(context: Context, uri: Uri): ExternalDocumentInput {
    val resolver = context.contentResolver
    var name: String? = null
    var size: Long? = null
    when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> {
            val file = File(requireNotNull(uri.path) { "file URI 缺少路径" })
            val privateRoots = listOfNotNull(
                File(context.applicationInfo.dataDir),
                context.filesDir.parentFile,
                context.noBackupFilesDir,
                context.cacheDir,
                context.codeCacheDir,
                context.getExternalFilesDir(null)?.parentFile,
                context.externalCacheDir,
            )
            if (!isAllowedExternalFile(file, privateRoots)) throw SecurityException("不能打开应用私有目录中的文件")
            name = file.name
            size = file.length().takeIf { file.isFile }
        }
        else -> {
            // 指向本应用自己 ContentProvider 的链接会以本应用的权限读取，同样可能把私有文件带出去。
            if (context.ownsContentUri(uri)) throw SecurityException("不能打开本应用提供的内容链接")
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { name = cursor.getString(it)?.trim()?.takeIf(String::isNotEmpty) }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { size = cursor.getLong(it).takeIf { value -> value >= 0 } }
                }
            }
        }
    }
    val displayName = name ?: uri.lastPathSegment?.substringAfterLast('/')
    return ExternalDocumentInput(displayName, size) {
        resolver.openInputStream(uri) ?: throw IOException("无法打开文档")
    }
}
