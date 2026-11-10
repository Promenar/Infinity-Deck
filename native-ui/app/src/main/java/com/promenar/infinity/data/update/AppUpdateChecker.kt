package com.promenar.infinity.data.update

import com.promenar.infinity.domain.update.AppUpdate
import com.promenar.infinity.domain.update.AppUpdateResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 向 GitHub 查询标为 Latest 的发行版并与当前版本比较；只查询，不下载安装包。 */
class AppUpdateChecker(
    private val fetch: suspend () -> String? = ::fetchLatestRelease,
) {
    suspend fun check(currentVersionName: String): AppUpdateResult =
        AppUpdate.evaluate(currentVersionName, runCatching { fetch() }.getOrNull())
}

private val client by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
}

private suspend fun fetchLatestRelease(): String? = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(AppUpdate.LATEST_RELEASE_API)
        .header("Accept", "application/vnd.github+json")
        .build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) null else response.body?.string()
    }
}
