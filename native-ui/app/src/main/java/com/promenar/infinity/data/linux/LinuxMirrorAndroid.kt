package com.promenar.infinity.data.linux

import android.content.SharedPreferences
import com.promenar.infinity.data.network.AgentHttpRouting
import com.promenar.infinity.data.network.awaitAgentResponse
import com.promenar.infinity.data.tailnet.TailnetRouting
import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.domain.network.AgentNetworkPolicy
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** 环境本地偏好使用独立命名空间，不进入应用跨设备备份。 */
internal class LinuxMirrorSharedPreferences(private val preferences: SharedPreferences) : LinuxMirrorPreferences {
    override fun readChoice(): LinuxMirrorChoice = runCatching {
        LinuxMirrorChoice(LinuxMirrorId.valueOf(preferences.getString(KEY_MIRROR, "TUNA")!!), preferences.getString(KEY_CUSTOM, null))
            .also { LinuxMirrorService.baseUrl(it) }
    }.getOrDefault(LinuxMirrorChoice(LinuxMirrorId.TUNA))

    override fun readLatencies(): Map<LinuxMirrorId, Long?> = runCatching {
        Json.parseToJsonElement(preferences.getString(KEY_LATENCIES, "{}")!!).jsonObject.mapNotNull { (id, value) ->
            LinuxMirrorId.entries.find { it.name == id }?.let { it to value.jsonPrimitive.longOrNull?.takeIf { ms -> ms >= 0 } }
        }.toMap()
    }.getOrDefault(emptyMap())

    override fun saveChoice(choice: LinuxMirrorChoice) {
        if (!preferences.edit().putString(KEY_MIRROR, choice.id.name).putString(KEY_CUSTOM, choice.customUrl).commit())
            throw IOException("镜像选择保存失败")
    }

    override fun saveLatencies(latencies: Map<LinuxMirrorId, Long?>) {
        val json = buildJsonObject { latencies.forEach { (id, ms) -> put(id.name, ms?.let(::JsonPrimitive) ?: JsonNull) } }
        if (!preferences.edit().putString(KEY_LATENCIES, json.toString()).commit()) throw IOException("镜像测速结果保存失败")
    }

    companion object {
        const val PREFERENCES_NAME = "infinity_linux_mirrors"
        const val KEY_MIRROR = "mirror"
        const val KEY_CUSTOM = "custom"
        const val KEY_LATENCIES = "latencies"
    }
}

/** 每次重定向重新执行网络访问策略；路由、代理及证书校验沿用应用 HTTP 层。 */
internal class LinuxMirrorHttpClient(
    private val client: OkHttpClient,
    private val policy: AgentNetworkPolicy,
    private val routing: TailnetRouting,
) : LinuxMirrorHttp {
    override suspend fun head(url: String): Boolean = withContext(Dispatchers.IO) {
        var current = url
        repeat(5) {
            val endpoint = policy.validate(current)
            val route = AgentHttpRouting.route(client, endpoint, routing)
            val request = route.request(Request.Builder().url(current).head().build())
            route.client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build().newCall(request).awaitAgentResponse().use { response ->
                if (!response.isRedirect) return@withContext response.isSuccessful
                current = response.header("Location")?.let { current.toHttpUrl().resolve(it)?.toString() }
                    ?: return@withContext false
            }
        }
        false
    }
}
