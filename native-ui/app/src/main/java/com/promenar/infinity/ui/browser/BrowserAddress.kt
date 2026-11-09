package com.promenar.infinity.ui.browser

import com.promenar.infinity.data.browser.BrowserUrlInput
import com.promenar.infinity.data.tailnet.InstalledTailnetRouting
import java.net.URLEncoder

private const val SEARCH_URL = "https://www.google.com/search?q="

private val WEB_URL = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

// 不带端口与路径的单个词：除 localhost 与 tailnet 节点短名外按搜索处理，避免“kotlin”被当成主机名打开。
private val SINGLE_WORD = Regex("^[\\p{L}\\p{N}-]+$")

private fun isSearchWord(text: String): Boolean =
    SINGLE_WORD.matches(text) && !text.equals("localhost", ignoreCase = true) &&
        !InstalledTailnetRouting.routing.isTailnetHost(text)

/**
 * 地址栏输入转为要打开的地址：网页地址原样打开，缺协议的主机按类型补全协议，其余（含 javascript:、file: 等非网页协议）按 Google 搜索。
 * 空白返回 null。
 */
internal fun browserAddressTarget(input: String): String? {
    val text = input.trim()
    if (text.isEmpty()) return null
    return when {
        WEB_URL.matches(text) -> text
        BrowserUrlInput.isBareHost(text) && !isSearchWord(text) -> BrowserUrlInput.withDefaultScheme(text)
        else -> SEARCH_URL + URLEncoder.encode(text, "UTF-8")
    }
}
