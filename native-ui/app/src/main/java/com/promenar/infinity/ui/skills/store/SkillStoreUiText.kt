package com.promenar.infinity.ui.skills.store

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.skills.store.SkillChannel
import com.promenar.infinity.data.skills.store.StoreFailure

/** 商店失败原因的界面文案；数据层只给出枚举与可选的 HTTP 状态码。 */
fun storeFailureText(context: Context, failure: StoreFailure, httpStatus: Int?): String = when (failure) {
    StoreFailure.NETWORK -> context.getString(R.string.skill_store_failure_network)
    StoreFailure.HTTP -> httpStatus?.let { context.getString(R.string.skill_store_failure_http, it) }
        ?: context.getString(R.string.skill_store_failure_http_unknown)
    StoreFailure.TOO_LARGE -> context.getString(R.string.skill_store_failure_too_large)
    StoreFailure.HASH_MISMATCH -> context.getString(R.string.skill_store_failure_hash)
    StoreFailure.FILE_LIST_MISMATCH -> context.getString(R.string.skill_store_failure_file_list)
    StoreFailure.NAME_MISMATCH -> context.getString(R.string.skill_store_failure_name)
    StoreFailure.INVALID_PACKAGE -> context.getString(R.string.skill_store_failure_package)
    StoreFailure.INVALID_CATALOG -> context.getString(R.string.skill_store_failure_catalog)
    StoreFailure.UNSUPPORTED_SCHEMA -> context.getString(R.string.skill_store_failure_schema)
    StoreFailure.INVALID_URL -> context.getString(R.string.skill_store_failure_url)
    StoreFailure.DUPLICATE_SOURCE -> context.getString(R.string.skill_store_failure_duplicate)
    StoreFailure.SOURCE_LIMIT -> context.getString(R.string.skill_store_failure_limit)
    StoreFailure.STORAGE -> context.getString(R.string.skill_store_failure_storage)
}

@Composable
fun storeFailureText(failure: StoreFailure, httpStatus: Int?): String =
    storeFailureText(LocalContext.current, failure, httpStatus)

/** 字节数按系统习惯缩写（KB、MB），与系统文件管理器一致。 */
@Composable
fun storeSizeText(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** 适用版本：两个渠道都支持时写“侧载与 Play”，只支持侧载时写“仅侧载”。 */
@Composable
fun storeChannelsText(channels: Set<SkillChannel>): String = when {
    SkillChannel.SIDELOAD in channels && SkillChannel.PLAY in channels -> stringResource(R.string.skill_store_channels_both)
    SkillChannel.SIDELOAD in channels -> stringResource(R.string.skill_store_tag_sideload_only)
    else -> "Play"
}
