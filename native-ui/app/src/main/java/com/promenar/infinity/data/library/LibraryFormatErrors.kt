package com.promenar.infinity.data.library

import androidx.annotation.StringRes
import com.promenar.infinity.R
import com.promenar.infinity.data.rag.DocumentFormatError

/**
 * 索引失败的稳定错误码（`library.format.*`，由文档抽取写入队列 error）到说明文字资源的映射。
 * 资料页待处理列表、文档详情与资源管理器详情共用，未知内容返回 null，由调用方保留原文或通用文案。
 */
object LibraryFormatErrors {
    const val PDF_NO_TEXT_LAYER = "library.format.pdf_no_text_layer"
    const val TEXT_ENCODING_UNRECOGNIZED = "library.format.text_encoding_unrecognized"
    const val UNSUPPORTED_FORMAT = "library.format.unsupported_format"
    const val DOC_INVALID = "library.format.doc_invalid"

    /** 队列 error 字段写入的错误码。 */
    fun codeFor(error: DocumentFormatError): String = "library.format.${error.name.lowercase()}"

    @StringRes
    fun resourceFor(error: String?): Int? = when (error?.trim()) {
        PDF_NO_TEXT_LAYER -> R.string.library_format_pdf_no_text_layer
        TEXT_ENCODING_UNRECOGNIZED -> R.string.library_format_text_encoding_unrecognized
        UNSUPPORTED_FORMAT -> R.string.library_format_unsupported_format
        DOC_INVALID -> R.string.library_format_doc_invalid
        else -> null
    }
}
