package com.promenar.infinity.ui.browser

import com.promenar.infinity.data.browser.BrowserUrlInput
import java.net.URLEncoder

private const val SEARCH_URL = "https://www.google.com/search?q="

private val WEB_URL = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

/**
 * 地址栏输入转为要打开的地址：网页地址原样打开，缺协议的主机按类型补全协议，其余（含 javascript:、file: 等非网页协议）按 Google 搜索。
 * 空白返回 null。
 */
internal fun browserAddressTarget(input: String): String? {
    val text = input.trim()
    if (text.isEmpty()) return null
    return when {
        WEB_URL.matches(text) -> text
        BrowserUrlInput.isBareHost(text) -> BrowserUrlInput.withDefaultScheme(text)
        else -> SEARCH_URL + URLEncoder.encode(text, "UTF-8")
    }
}
