package com.promenar.infinity.data.remote.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.ui.chat.manager.WebSearchContextProvider
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.WebSearchSkill
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebSearchKeenableTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = app.getSharedPreferences("infinity_search", Context.MODE_PRIVATE)
    private val secrets = MemorySecrets()
    private val executionContext = object : SkillExecutionContext {
        override val sessionId = "search-test"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }

    @Before
    fun setUp() {
        prefs.edit().clear().putString("search_engine", "keenable").commit()
    }

    @Test
    fun `会话搜索将 Keenable 选择路由到免密钥端点并保留引用正文`() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertThat(request.method).isEqualTo(HttpMethod.Post)
            assertThat(request.url.toString()).isEqualTo("https://api.keenable.ai/v1/search/public")
            assertThat(request.headers["X-Keenable-Title"]).isEqualTo("Infinity Deck")
            assertThat(request.headers["X-API-Key"]).isNull()
            assertThat(request.headers[HttpHeaders.Authorization]).isNull()
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertThat(request.body.contentType).isEqualTo(ContentType.Application.Json)
            assertThat(body.keys).containsExactly("query", "max_results")
            assertThat(body["query"]?.jsonPrimitive?.content).isEqualTo("Compose 文档")
            assertThat(body["max_results"]?.jsonPrimitive?.content).isEqualTo("5")
            respond(results, headers = jsonHeaders)
        })
        try {
            val (text, citations) = WebSearchContextProvider(app, client, secrets).search("Compose 文档")

            assertThat(citations).hasSize(2)
            assertThat(citations[0].source).isEqualTo("Keenable")
            assertThat(citations[0].title).isEqualTo("Compose")
            assertThat(citations[0].url).isEqualTo("https://developer.android.com/compose")
            assertThat(citations[0].snippet).isEqualTo("实际网页正文")
            assertThat(citations[1].snippet).isEqualTo("说明摘要")
            assertThat(text).contains("[1] Compose\nhttps://developer.android.com/compose\n实际网页正文")
            assertThat(text).contains("[2] Kotlin")
        } finally {
            client.close()
        }
    }

    @Test
    fun `工具搜索读取安全密钥并使用认证端点且清零读取缓冲`() = runTest {
        secrets.value = "fake-keenable-key".encodeToByteArray()
        val client = HttpClient(MockEngine { request ->
            assertThat(request.url.toString()).isEqualTo("https://api.keenable.ai/v1/search")
            assertThat(request.headers["X-API-Key"]).isEqualTo("fake-keenable-key")
            assertThat((request.body as TextContent).text).doesNotContain("fake-keenable-key")
            respond(results, headers = jsonHeaders)
        })
        try {
            val result = WebSearchSkill(app, client, secrets).execute(queryArgs, executionContext)

            assertThat(result.status).isEqualTo("success")
            assertThat(result.content).contains("实际网页正文")
            assertThat(result.data).contains("Keenable")
            assertThat(secrets.lastRead).isEqualTo(ByteArray("fake-keenable-key".length))
        } finally {
            client.close()
        }
    }

    @Test
    fun `结果数有界且域名过滤按主机匹配而非路径子串`() = runTest {
        prefs.edit()
            .putInt("result_count", 1)
            .putString("include_domains", "[\"android.com\"]")
            .putString("exclude_domains", "[\"blocked.android.com\"]")
            .commit()
        val response = """{"results":[
            {"title":"伪匹配","url":"https://android.com.attacker.test/android.com","snippet":"错误"},
            {"title":"排除子域","url":"https://sub.blocked.android.com/page","snippet":"错误"},
            {"title":"Compose","url":"https://developer.android.com/compose","snippet":"正文"},
            {"title":"Android","url":"https://android.com/","snippet":"正文"}
        ]}"""
        val client = HttpClient(MockEngine {
            respond(response, headers = jsonHeaders)
        })
        try {
            val (text, citations) = WebSearchContextProvider(app, client, secrets).search("Compose")

            assertThat(citations.map { it.title }).containsExactly("Compose")
            assertThat(text).startsWith("[1] Compose")
        } finally {
            client.close()
        }
    }

    @Test
    fun `损坏结果被跳过且空结果属于成功的无匹配搜索`() = runTest {
        val client = HttpClient(MockEngine {
            respond("""{"results":[null,{}, {"title":"","url":"https://example.com"},
                {"title":"无效链接","url":"javascript:alert(1)"}]}""", headers = jsonHeaders)
        })
        try {
            val result = WebSearchSkill(app, client, secrets).execute(queryArgs, executionContext)

            assertThat(result.status).isEqualTo("success")
            assertThat(result.content).isNotEmpty()
            assertThat(result.data).isNull()
        } finally {
            client.close()
        }
    }

    @Test
    fun `HTTP 错误回传失败状态且不泄露正文或尝试匿名降级`() = runTest {
        secrets.value = "fake-keenable-key".encodeToByteArray()
        for (status in listOf(400, 401, 402, 403, 429, 500)) {
            var requests = 0
            val client = HttpClient(MockEngine {
                requests++
                respond("fake-keenable-key upstream-private-detail", HttpStatusCode.fromValue(status))
            })
            try {
                val result = WebSearchSkill(app, client, secrets).execute(queryArgs, executionContext)

                assertThat(result.status).isEqualTo("error")
                assertThat(result.content).contains(status.toString())
                assertThat(result.content).doesNotContain("fake-keenable-key")
                assertThat(result.content).doesNotContain("upstream-private-detail")
                assertThat(requests).isEqualTo(1)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `非 JSON 和缺失结果数组不伪装成成功的空结果`() = runTest {
        for (body in listOf("<html>故障</html>", "{}", "{\"results\":{}}")) {
            val client = HttpClient(MockEngine { respond(body, headers = jsonHeaders) })
            try {
                val result = WebSearchSkill(app, client, secrets).execute(queryArgs, executionContext)
                assertThat(result.status).isEqualTo("error")
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `Keenable 搜索取消继续传播`() = runTest {
        val client = HttpClient(MockEngine { throw CancellationException("keenable-cancelled") })
        try {
            val thrown = runCatching {
                WebSearchSkill(app, client, secrets).execute(queryArgs, executionContext)
            }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(CancellationException::class.java)
            assertThat(thrown?.message).isEqualTo("keenable-cancelled")
        } finally {
            client.close()
        }
    }

    private class MemorySecrets : SecretStore {
        var value: ByteArray? = null
        var lastRead: ByteArray? = null
        override fun put(id: SecretId, value: ByteArray) = Unit
        override fun get(id: SecretId): ByteArray? =
            if (id == SecretId("keenable_api_key")) value?.copyOf()?.also { lastRead = it } else null
        override fun contains(id: SecretId) = id == SecretId("keenable_api_key") && value != null
        override fun remove(id: SecretId) = Unit
    }

    companion object {
        private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        private val queryArgs = buildJsonObject { put("query", "Compose 文档") }
        private val results = """{"query":"Compose 文档","mode":"pro","results":[
            {"title":"Compose","url":"https://developer.android.com/compose",
             "snippet":"实际网页正文","description":"不应覆盖正文","published_at":null,"acquired_at":"2026-10-01T00:00:00Z"},
            {"title":"Kotlin","url":"https://kotlinlang.org/","snippet":"","description":"说明摘要"}
        ]}"""
    }
}
