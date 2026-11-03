package com.promenar.infinity.data.remote.mcp

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.McpSkill
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteReadPacket
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.core.readText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class McpLoopbackClientTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = McpConnectionSettings(
        context.getSharedPreferences("mcp_client_test_${System.nanoTime()}", Context.MODE_PRIVATE),
        MemoryMcpSecrets(),
    )
    private val executionContext = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }

    @Test
    fun `discovery与执行都携带Bearer且环回工具为PROCESS风险`() = runTest {
        settings.saveBearerToken("local", "test-only-bearer")
        val methods = mutableListOf<String?>()
        val credentials = mutableListOf<String?>()
        val http = HttpClient(MockEngine { request ->
            methods += request.headers["Mcp-Method"]
            credentials += request.headers[HttpHeaders.Authorization]
            val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
            val result = if (methods.size == 1) {
                """{"tools":[{"name":"exec","inputSchema":{"type":"object"}}]}"""
            } else {
                """{"content":[]}"""
            }
            respond(
                """{"jsonrpc":"2.0","id":$id,"result":$result}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val mcp = McpClient(http, "http://127.0.0.1:8765/mcp", settings, "local")
        val tool = mcp.listTools().single()
        settings.saveBearerToken("local", "test-only-rotated")
        val skill = McpSkill(
            name = "local_exec", remoteToolName = tool.name,
            description = "本机工具", parametersSchema = tool.advertisedInputSchema,
            mcpClient = mcp, mcpServerId = "local",
        )
        assertThat(skill.risk).isEqualTo(ToolRisk.PROCESS)
        assertThat(skill.execute(JsonObject(emptyMap()), executionContext).status).isEqualTo("success")
        assertThat(methods).containsExactly("tools/list", "tools/call").inOrder()
        assertThat(credentials).containsExactly("Bearer test-only-bearer", "Bearer test-only-rotated").inOrder()
        http.close()
    }

    @Test fun `HTTP无需令牌且删除令牌后仍可调用`() = runTest {
        val credentials = mutableListOf<String?>()
        val http = HttpClient(MockEngine { request ->
            credentials += request.headers[HttpHeaders.Authorization]
            val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
            respond("""{"jsonrpc":"2.0","id":$id,"result":{"tools":[]}}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        for (host in listOf("127.0.0.1", "192.168.1.2", "remote.example.test")) {
            assertThat(McpClient(http, "http://$host/mcp", settings, "local").listTools()).isEmpty()
        }
        settings.saveBearerToken("local", "test-only-bearer")
        val client = McpClient(http, "http://127.0.0.1:8765/mcp", settings, "local")
        assertThat(client.listTools()).isEmpty()
        settings.removeBearerToken("local")
        assertThat(client.listTools()).isEmpty()
        assertThat(credentials).containsExactly(null, null, null, "Bearer test-only-bearer", null).inOrder()
        http.close()
    }

    @Test
    fun `HTTP重定向不离开指定端口且不关闭共享引擎`() = runTest {
        settings.saveBearerToken("local", "test-only-bearer")
        val urls = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            urls += request.url.toString()
            if (request.url.host == "127.0.0.1") {
                respond(
                    "", HttpStatusCode.TemporaryRedirect,
                    headersOf(HttpHeaders.Location, "http://remote.example.test/mcp"),
                )
            } else {
                val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
                respond(
                    """{"jsonrpc":"2.0","id":$id,"result":{"tools":[]}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }) { followRedirects = true }
        val failure = runCatching {
            McpClient(http, "http://127.0.0.1:8765/mcp", settings, "local").listTools()
        }.exceptionOrNull()
        assertThat(failure?.message).isEqualTo("MCP_HTTP_307")
        assertThat(urls).containsExactly("http://127.0.0.1:8765/mcp")
        assertThat(McpClient(http, "https://remote.example.test/mcp").listTools()).isEmpty()
        assertThat(urls).containsExactly("http://127.0.0.1:8765/mcp", "https://remote.example.test/mcp").inOrder()
        http.close()
    }

    @Test
    fun `远端HTTPS维持UNKNOWN且支持独立可选凭据`() = runTest {
        settings.saveBearerToken("remote", "test-only-local-credential")
        var authorization: String? = null
        val http = HttpClient(MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            respond(
                """{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val mcp = McpClient(http, "https://remote.example.test/mcp", settings, "remote")
        assertThat(mcp.defaultToolRisk).isEqualTo(ToolRisk.UNKNOWN)
        assertThat(mcp.listTools()).isEmpty()
        assertThat(authorization).isEqualTo("Bearer test-only-local-credential")
        http.close()
    }

    @Test
    fun `传输层异常不得把Bearer值带回工具结果`() = runTest {
        settings.saveBearerToken("local", "test-only-bearer")
        val http = HttpClient(MockEngine { error("test-only-bearer") })
        val skill = McpSkill(
            name = "exec", description = "本机工具", parametersSchema = """{"type":"object"}""",
            mcpClient = McpClient(http, "http://127.0.0.1:8765/mcp", settings, "local"),
        )
        val result = skill.execute(JsonObject(emptyMap()), executionContext)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).doesNotContain("test-only-bearer")
        assertThat(result.content).contains("MCP_REQUEST_FAILED")
        http.close()
    }
}
