package com.promenar.infinity.data.remote.mcp

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteReadPacket
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class McpClientContractTest {
    @Test
    fun `tools list使用2026现代headers meta精确id并消费全部分页`() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            val body = request.body.toByteReadPacket().readText()
            bodies += body
            assertThat(request.headers["MCP-Protocol-Version"]).isEqualTo("2026-07-28")
            assertThat(request.headers["Mcp-Method"]).isEqualTo("tools/list")
            assertThat(request.headers[HttpHeaders.Accept])
                .isEqualTo("application/json, text/event-stream")
            val meta = Json.parseToJsonElement(body).jsonObject
                .getValue("params").jsonObject.getValue("_meta").jsonObject
            assertModernMetadata(meta)
            val id = Json.parseToJsonElement(body).jsonObject.getValue("id").toString()
            if (bodies.size == 1) {
                respond(json(id, """{"tools":[{"name":"one","inputSchema":{"type":"object"}}],"nextCursor":"next"}"""))
            } else {
                respond(json(id, """{"tools":[{"name":"two","inputSchema":{"type":"object"}}]}"""))
            }
        }

        val tools = McpClient(HttpClient(engine), "https://mcp.example.test").listTools()

        assertThat(tools.map { it.name }).containsExactly("one", "two").inOrder()
        assertThat(bodies).hasSize(2)
        assertThat(bodies[1]).contains("\"cursor\":\"next\"")
    }

    @Test
    fun `cursor循环 重复名 id不匹配与非成功HTTP均失败关闭`() = runTest {
        suspend fun failure(engine: MockEngine): Throwable? = runCatching {
            McpClient(HttpClient(engine), "https://mcp.example.test").listTools()
        }.exceptionOrNull()

        var calls = 0
        assertThat(failure(MockEngine { request ->
            calls++
            val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
            respond(json(id.toString(), """{"tools":[],"nextCursor":"loop"}"""))
        })).isNotNull()
        assertThat(calls).isAtMost(2)

        assertThat(failure(MockEngine { request ->
            val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
            respond(json(id.toString(), """{"tools":[{"name":"dup","inputSchema":{"type":"object"}},{"name":"dup","inputSchema":{"type":"object"}}]}"""))
        })).isNotNull()

        assertThat(failure(MockEngine { respond(json("999", """{"tools":[]}""")) })).isNotNull()
        assertThat(failure(MockEngine { respond("no", HttpStatusCode.BadGateway) })).isNotNull()
    }

    @Test
    fun `Streamable HTTP SSE可忽略通知并解析精确响应id`() = runTest {
        val engine = MockEngine { request ->
            val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
            respond(
                """: keepalive

event: message
data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}

event: message
data: {"jsonrpc":"2.0",
data: "id":$id,"result":{"tools":[{"name":"sse_tool","inputSchema":{"type":"object"}}]}}

""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/event-stream; charset=utf-8"),
            )
        }

        val tools = McpClient(HttpClient(engine), "https://mcp.example.test").listTools()

        assertThat(tools.map { it.name }).containsExactly("sse_tool")
    }

    @Test
    fun `Streamable HTTP SSE首个完整精确id响应胜出且不等待连接关闭`() = runBlocking {
        val responseBody = ByteChannel(autoFlush = true)
        responseBody.writeStringUtf8(
            "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}\n\n",
        )
        val engine = MockEngine { request ->
            val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
            assertThat(id).isEqualTo(JsonPrimitive(1))
            respond(
                responseBody,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }

        val tools = withTimeout(5_000) {
            McpClient(HttpClient(engine), "https://mcp.example.test").listTools()
        }

        assertThat(tools).isEmpty()
        assertThat(responseBody.isClosedForRead).isTrue()
    }

    @Test
    fun `Streamable HTTP SSE错id截断与事件超限均失败关闭`() = runTest {
        suspend fun failure(body: (String) -> String): Throwable? = runCatching {
            McpClient(HttpClient(MockEngine { request ->
                val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
                respond(
                    body(id.toString()),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            }), "https://mcp.example.test").listTools()
        }.exceptionOrNull()

        assertThat(failure {
            "data: {\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{\"tools\":[]}}\n\n"
        }).isInstanceOf(McpProtocolException::class.java)
        assertThat(failure { id ->
            "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[]}}"
        }).isInstanceOf(McpProtocolException::class.java)
        assertThat(failure {
            "data: ${"x".repeat(200_000)}\ndata: ${"y".repeat(100_000)}\n\n"
        }).isInstanceOf(McpProtocolException::class.java)
    }

    @Test
    fun `Streamable HTTP SSE畸形事件或错id响应后即使出现精确响应也失败关闭`() = runTest {
        suspend fun failure(body: (String) -> String): Throwable? = runCatching {
            McpClient(HttpClient(MockEngine { request ->
                val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
                respond(
                    body(id.toString()),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            }), "https://mcp.example.test").listTools()
        }.exceptionOrNull()

        fun exact(id: String) =
            "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[]}}\n\n"

        assertThat(failure { id -> "data: {}\n\n${exact(id)}" })
            .isInstanceOf(McpProtocolException::class.java)
        assertThat(failure { id ->
            "data: {\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{\"tools\":[]}}\n\n${exact(id)}"
        }).isInstanceOf(McpProtocolException::class.java)
        assertThat(failure { id ->
            "data: {\"jsonrpc\":\"1.0\",\"method\":\"notifications/progress\"}\n\n${exact(id)}"
        }).isInstanceOf(McpProtocolException::class.java)
    }

    @Test
    fun `Streamable HTTP SSE行长事件数与总字节边界均失败关闭`() = runTest {
        suspend fun failure(body: String): Throwable? = runCatching {
            McpClient(HttpClient(MockEngine {
                respond(
                    body,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            }), "https://mcp.example.test").listTools()
        }.exceptionOrNull()

        assertThat(failure(":${"x".repeat(300_000)}\n\n"))
            .isInstanceOf(McpProtocolException::class.java)

        val tooManyEvents = buildString {
            repeat(1_001) {
                append("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n")
            }
        }
        assertThat(failure(tooManyEvents)).isInstanceOf(McpProtocolException::class.java)

        val tooManyTotalBytes = buildString {
            repeat(2_100) {
                append(':')
                append("x".repeat(1_000))
                append('\n')
            }
        }
        assertThat(failure(tooManyTotalBytes)).isInstanceOf(McpProtocolException::class.java)
    }

    @Test
    fun `无效URL非法工具名与不支持schema均不可发现`() = runTest {
        assertThat(runCatching {
            McpClient(HttpClient(MockEngine { error("不应联网") }), "ftp://invalid.example.test").listTools()
        }.exceptionOrNull()).isNotNull()

        listOf(
            """{"name":"bad name","inputSchema":{"type":"object"}}""",
            """{"name":"bad-schema","inputSchema":{"type":"object","oneOf":[]}}""",
            """{"name":"bad-root","inputSchema":{"type":"array"}}""",
        ).forEach { tool ->
            val error = runCatching {
                McpClient(HttpClient(MockEngine { request ->
                    val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
                    respond(json(id.toString(), """{"tools":[$tool]}"""))
                }), "https://mcp.example.test").listTools()
            }.exceptionOrNull()
            assertThat(error).isNotNull()
        }
    }

    @Test
    fun `x mcp header只接受primitive并映射到调用header`() = runTest {
        var callHeaders: Map<String, String> = emptyMap()
        val engine = MockEngine { request ->
            val body = request.body.toByteReadPacket().readText()
            val root = Json.parseToJsonElement(body).jsonObject
            val id = root.getValue("id").toString()
            when (root.getValue("method").toString().trim('"')) {
                "tools/list" -> respond(json(id, """{"tools":[{"name":"search","inputSchema":{"type":"object","properties":{"tenant":{"type":"string","x-mcp-header":true},"count":{"type":"integer"}}}}]}"""))
                else -> {
                    callHeaders = request.headers.entries().associate { it.key to it.value.single() }
                    assertThat(request.headers["MCP-Protocol-Version"]).isEqualTo("2026-07-28")
                    assertThat(request.headers["Mcp-Method"]).isEqualTo("tools/call")
                    assertThat(request.headers["Mcp-Name"]).isEqualTo("search")
                    assertThat(request.headers[HttpHeaders.Accept])
                        .isEqualTo("application/json, text/event-stream")
                    val meta = root.getValue("params").jsonObject.getValue("_meta").jsonObject
                    assertModernMetadata(meta)
                    assertThat(root.getValue("params").jsonObject.getValue("name").toString())
                        .isEqualTo("\"search\"")
                    respond(json(id, """{"content":[{"type":"text","text":"ok"}]}"""))
                }
            }
        }
        val client = McpClient(HttpClient(engine), "https://mcp.example.test")
        client.listTools()
        client.callTool("search", Json.parseToJsonElement("""{"tenant":"acme","count":2}"""))

        assertThat(callHeaders["Mcp-Param-tenant"]).isEqualTo("acme")

        val invalid = runCatching {
            McpClient(HttpClient(MockEngine { request ->
                val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
                respond(json(id.toString(), """{"tools":[{"name":"bad","inputSchema":{"type":"object","properties":{"nested":{"type":"object","x-mcp-header":true}}}}]}"""))
            }), "https://mcp.example.test").listTools()
        }.exceptionOrNull()
        assertThat(invalid).isNotNull()

        val nestedInvalid = runCatching {
            McpClient(HttpClient(MockEngine { request ->
                val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
                respond(json(id.toString(), """{"tools":[{"name":"bad","inputSchema":{"type":"object","properties":{"outer":{"type":"object","properties":{"tenant":{"type":"string","x-mcp-header":true}}}}}}]}"""))
            }), "https://mcp.example.test").listTools()
        }.exceptionOrNull()
        assertThat(nestedInvalid).isNotNull()
    }

    @Test
    fun `isError与input required都作为稳定失败而非成功`() = runTest {
        suspend fun call(result: String): Throwable? {
            val engine = MockEngine { request ->
                val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
                respond(json(id.toString(), result))
            }
            return runCatching {
                McpClient(HttpClient(engine), "https://mcp.example.test")
                    .callTool("tool", Json.parseToJsonElement("{}"))
            }.exceptionOrNull()
        }

        assertThat(call("""{"content":[{"type":"text","text":"remote failed"}],"isError":true}""")).isInstanceOf(McpRemoteToolException::class.java)
        assertThat(call("""{"content":[{"type":"text","text":"need input"}],"input_required":true}""")).isInstanceOf(McpInputRequiredUnsupportedException::class.java)
    }

    private fun json(id: String, result: String) = """{"jsonrpc":"2.0","id":$id,"result":$result}"""

    private fun assertModernMetadata(meta: kotlinx.serialization.json.JsonObject) {
        assertThat(meta.keys).containsExactly(
            "io.modelcontextprotocol/protocolVersion",
            "io.modelcontextprotocol/clientInfo",
            "io.modelcontextprotocol/clientCapabilities",
        )
        assertThat(meta.containsKey("protocolVersion")).isFalse()
        assertThat(meta.containsKey("clientInfo")).isFalse()
        assertThat(meta.containsKey("clientCapabilities")).isFalse()
        assertThat(meta.getValue("io.modelcontextprotocol/protocolVersion").toString())
            .isEqualTo("\"2026-07-28\"")
        assertThat(
            meta.getValue("io.modelcontextprotocol/clientInfo").jsonObject
                .getValue("name").toString(),
        ).isEqualTo("\"Infinity Workbench\"")
        assertThat(
            meta.getValue("io.modelcontextprotocol/clientInfo").jsonObject
                .getValue("version").toString(),
        ).isEqualTo(JsonPrimitive(BuildConfig.VERSION_NAME).toString())
        assertThat(meta.getValue("io.modelcontextprotocol/clientCapabilities").jsonObject).isEmpty()
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respond(body: String) = respond(
        body,
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
