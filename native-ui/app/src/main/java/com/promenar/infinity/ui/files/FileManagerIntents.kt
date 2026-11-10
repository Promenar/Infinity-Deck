package com.promenar.infinity.ui.files

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.promenar.infinity.ui.document.external.excludingOwnViewer

/*
 * “打开方式”“分享”与剪贴板：URI 由控制器经 FileProvider 授权给出，这里只组装系统 Intent。
 */

/** 用其它应用打开；没有可用应用时返回 false。 */
internal fun launchOpenWith(context: Context, uri: String, mimeType: String?, chooserTitle: String): Boolean {
    val parsed = Uri.parse(uri)
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(parsed, mimeType?.takeIf { it.contains('/') } ?: "*/*")
        clipData = ClipData.newRawUri(null, parsed)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return startChooser(context, view, chooserTitle, excludeOwnViewer = true)
}

/** 系统分享：单个文件用 ACTION_SEND，多个用 ACTION_SEND_MULTIPLE。 */
internal fun launchShare(context: Context, uris: List<Pair<String, String?>>, chooserTitle: String): Boolean {
    if (uris.isEmpty()) return false
    val parsed = uris.map { Uri.parse(it.first) }
    val types = uris.map { it.second?.takeIf { type -> type.contains('/') } ?: "*/*" }.distinct()
    val clip = ClipData.newRawUri(null, parsed.first()).apply {
        parsed.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    val send = if (parsed.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = types.first()
            putExtra(Intent.EXTRA_STREAM, parsed.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = types.singleOrNull() ?: "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(parsed))
        }
    }.apply {
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return startChooser(context, send, chooserTitle)
}

private fun startChooser(context: Context, intent: Intent, title: String, excludeOwnViewer: Boolean = false): Boolean {
    val chooser = Intent.createChooser(intent, title).apply {
        if (excludeOwnViewer) excludingOwnViewer(context)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** 剪贴板第一项的文字；没有或读取被系统拒绝时为 null。 */
internal fun readClipboardText(context: Context): String? = try {
    val manager = context.getSystemService(ClipboardManager::class.java)
    val clip = manager?.primaryClip
    if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(context)?.toString()
} catch (_: SecurityException) {
    null
}
