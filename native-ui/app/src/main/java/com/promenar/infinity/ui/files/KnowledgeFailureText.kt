package com.promenar.infinity.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.data.library.LibraryFormatErrors

/** 索引失败原因的显示文字：稳定错误码换成说明文字，其它原因原样显示。 */
@Composable
internal fun knowledgeFailureText(reason: String): String =
    LibraryFormatErrors.resourceFor(reason)?.let { stringResource(it) } ?: reason
