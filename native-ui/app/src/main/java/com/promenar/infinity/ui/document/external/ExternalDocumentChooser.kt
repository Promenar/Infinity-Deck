package com.promenar.infinity.ui.document.external

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.promenar.infinity.MainActivity

/**
 * “用其它应用打开”的选择器不列出本应用：本应用的外部文档查看器不接收自己 FileProvider 的内容链接，
 * 应用内已有对应的查看入口。
 */
fun Intent.excludingOwnViewer(context: Context): Intent =
    putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
