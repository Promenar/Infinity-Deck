package com.promenar.infinity.domain.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** GitHub 上标为 Latest 的发行版。 */
data class LatestRelease(val versionName: String, val pageUrl: String)

/** 检查更新的结果。 */
sealed interface AppUpdateResult {
    data object UpToDate : AppUpdateResult
    data class Available(val release: LatestRelease) : AppUpdateResult
    data object Failed : AppUpdateResult
}

object AppUpdate {
    const val LATEST_RELEASE_API = "https://api.github.com/repos/Promenar/Infinity-Deck/releases/latest"
    private const val RELEASE_PAGE_PREFIX = "https://github.com/Promenar/Infinity-Deck/releases/"

    /** 解析 GitHub `releases/latest` 的响应；缺少标签或页面地址不在本项目发行页下时返回 null。 */
    fun parseLatestRelease(body: String): LatestRelease? = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        val tag = root["tag_name"]?.jsonPrimitive?.content?.trim().orEmpty()
        val url = root["html_url"]?.jsonPrimitive?.content?.trim().orEmpty()
        val name = tag.removePrefix("v")
        if (parse(name) == null || !url.startsWith(RELEASE_PAGE_PREFIX)) null else LatestRelease(name, url)
    }.getOrNull()

    /** [latest] 是否比 [current] 新：先比 主.次.补丁；相同时无后缀的正式版比带后缀的新。 */
    fun isNewer(current: String, latest: String): Boolean {
        val a = parse(current) ?: return false
        val b = parse(latest) ?: return false
        for (index in 0 until 3) {
            if (b.numbers[index] != a.numbers[index]) return b.numbers[index] > a.numbers[index]
        }
        return a.suffix.isNotEmpty() && b.suffix.isEmpty()
    }

    fun evaluate(current: String, body: String?): AppUpdateResult {
        val release = body?.let(::parseLatestRelease) ?: return AppUpdateResult.Failed
        return if (isNewer(current, release.versionName)) AppUpdateResult.Available(release) else AppUpdateResult.UpToDate
    }

    private class Version(val numbers: List<Int>, val suffix: String)

    private fun parse(name: String): Version? {
        val core = name.removePrefix("v").substringBefore('-')
        val numbers = core.split('.').map { it.toIntOrNull() ?: return null }
        if (numbers.size != 3 || numbers.any { it < 0 }) return null
        return Version(numbers, name.substringAfter('-', ""))
    }
}
