package com.promenar.infinity.data.remote.mcp

import com.promenar.infinity.BuildConfig
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.tool.ToolSchemaValidation
import com.promenar.infinity.domain.tool.ToolSchemaValidator
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import io.ktor.utils.io.readUTF8LineTo
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement,
    val id: Long,
)

class McpProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
class McpRemoteToolException(message: String) : Exception(message)
class McpInputRequiredUnsupportedException : Exception("MCP_INPUT_REQUIRED_UNSUPPORTED")

/** 仅实现 MCP 2026-07-28 无状态 Streamable HTTP。 */
class McpClient(
    private val httpClient: HttpClient,
    private val serverUrl: String,
    private val connectionSettings: McpConnectionSettings? = null,
    private val serverId: String? = null,
    private val isCurrent: () -> Boolean = { true },
) {
    private val endpointKind = McpEndpointPolicy.kind(serverUrl)
    val defaultToolRisk: ToolRisk
        get() = if (endpointKind == McpEndpointPolicy.Kind.LOOPBACK_HTTP) ToolRisk.PROCESS else ToolRisk.UNKNOWN
    private val requestId = AtomicLong(1)
    private val discoveredHeaderParameters = mutableMapOf<String, Set<String>>()

    init {
        require(endpointKind != null) { "MCP_ENDPOINT_INVALID" }
        require(isAllowed()) { "MCP_ENDPOINT_INVALID" }
    }

    suspend fun listTools(): List<McpTool> {
        val tools = mutableListOf<McpTool>()
        val names = mutableSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val result = call("tools/list", params)
            val root = result as? JsonObject
                ?: throw McpProtocolException("MCP_TOOLS_LIST_RESULT_NOT_OBJECT")
            val page = root["tools"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?: throw McpProtocolException("MCP_TOOLS_LIST_TOOLS_NOT_ARRAY")
            page.forEach { element ->
                val obj = element as? JsonObject
                    ?: throw McpProtocolException("MCP_TOOL_DEFINITION_NOT_OBJECT")
                val name = obj["name"]?.let(::requiredString)
                    ?: throw McpProtocolException("MCP_TOOL_NAME_MISSING")
                if (!TOOL_NAME.matches(name)) throw McpProtocolException("MCP_TOOL_NAME_INVALID")
                if (!names.add(name)) throw McpProtocolException("MCP_TOOL_NAME_DUPLICATE")
                val rawSchema = obj["inputSchema"] as? JsonObject
                    ?: throw McpProtocolException("MCP_TOOL_SCHEMA_NOT_OBJECT")
                val normalized = normalizeInputSchema(rawSchema)
                val tool = McpTool(
                    name = name,
                    description = obj["description"]?.let(::requiredString).orEmpty(),
                    inputSchema = rawSchema.toString(),
                    advertisedInputSchema = normalized.schema.toString(),
                    headerParameters = normalized.headerParameters,
                )
                discoveredHeaderParameters[name] = tool.headerParameters
                tools += tool
            }
            cursor = root["nextCursor"]?.let { next ->
                if (next is JsonNull) null else requiredString(next)
                    .takeIf(String::isNotEmpty)
                    ?: throw McpProtocolException("MCP_NEXT_CURSOR_INVALID")
            }
            if (cursor != null && !cursors.add(cursor!!)) throw McpProtocolException("MCP_CURSOR_LOOP")
            if (cursors.size > MAX_PAGES) throw McpProtocolException("MCP_PAGE_LIMIT_EXCEEDED")
        } while (cursor != null)
        return tools
    }

    suspend fun callTool(
        name: String,
        arguments: JsonElement,
        headerParameters: Set<String> = discoveredHeaderParameters[name].orEmpty(),
    ): JsonElement {
        if (!TOOL_NAME.matches(name)) throw McpProtocolException("MCP_TOOL_NAME_INVALID")
        val argumentObject = arguments as? JsonObject
            ?: throw McpProtocolException("MCP_TOOL_ARGUMENTS_NOT_OBJECT")
        val headers = encodeHeaderParameters(argumentObject, headerParameters)
        val params = buildJsonObject {
            put("name", name)
            put("arguments", argumentObject)
        }
        val result = call("tools/call", params, name, headers)
        val resultObject = result as? JsonObject
            ?: throw McpProtocolException("MCP_TOOL_RESULT_NOT_OBJECT")
        val inputRequired = containsInputRequired(resultObject)
        if (inputRequired) throw McpInputRequiredUnsupportedException()
        if (resultObject["isError"]?.jsonPrimitive?.booleanOrNull == true) {
            throw McpRemoteToolException("MCP_REMOTE_TOOL_ERROR")
        }
        return resultObject
    }

    private suspend fun call(
        method: String,
        rawParams: JsonObject,
        toolName: String? = null,
        parameterHeaders: Map<String, String> = emptyMap(),
    ): JsonElement {
        val id = requestId.getAndIncrement()
        val params = JsonObject(rawParams + ("_meta" to requestMetadata()))
        val request = JsonRpcRequest(method = method, params = params, id = id)
        return try {
            ensureAllowed()
            val token = serverId?.let { connectionSettings?.readBearerToken(it) }
            ensureAllowed()
            // 明文请求不跟随重定向；包装客户端复用引擎但不持有其生命周期。
            val localClient = if (endpointKind != McpEndpointPolicy.Kind.HTTPS) {
                HttpClient(httpClient.engine) { followRedirects = false }
            } else {
                null
            }
            try {
                (localClient ?: httpClient).preparePost(serverUrl) {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Accept, ACCEPT)
                    header(HEADER_PROTOCOL_VERSION, PROTOCOL_VERSION)
                    header(HEADER_METHOD, method)
                    token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    toolName?.let { header(HEADER_NAME, it) }
                    parameterHeaders.forEach { (name, value) -> header(name, value) }
                    setBody(Json.encodeToString(JsonRpcRequest.serializer(), request))
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        throw McpProtocolException("MCP_HTTP_${response.status.value}")
                    }
                    val root = parseRpcResponse(response, id)
                    if (root["jsonrpc"]?.jsonPrimitive?.contentOrNull != "2.0") {
                        throw McpProtocolException("MCP_RPC_VERSION_MISMATCH")
                    }
                    if (root["id"] != JsonPrimitive(id)) throw McpProtocolException("MCP_RPC_ID_MISMATCH")
                    if (root["error"] != null && root["error"] !is JsonNull) {
                        throw McpProtocolException("MCP_RPC_ERROR")
                    }
                    root["result"] ?: throw McpProtocolException("MCP_RPC_RESULT_MISSING")
                }
            } finally {
                localClient?.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: McpProtocolException) {
            throw error
        } catch (error: Exception) {
            throw McpProtocolException("MCP_REQUEST_FAILED")
        }
    }

    private suspend fun parseRpcResponse(response: HttpResponse, expectedId: Long): JsonObject {
        val mediaType = response.headers[HttpHeaders.ContentType]
            ?.substringBefore(';')?.trim()?.lowercase()
            ?: throw McpProtocolException("MCP_RPC_CONTENT_TYPE_MISSING")
        return when (mediaType) {
            ContentType.Application.Json.toString() -> parseRpcObject(response.bodyAsText())
            ContentType.Text.EventStream.toString() -> parseSseRpcResponse(response, expectedId)
            else -> throw McpProtocolException("MCP_RPC_CONTENT_TYPE_UNSUPPORTED")
        }
    }

    private fun parseRpcObject(payload: String): JsonObject = try {
        Json.parseToJsonElement(payload) as? JsonObject
            ?: throw McpProtocolException("MCP_RPC_NOT_OBJECT")
    } catch (error: McpProtocolException) {
        throw error
    } catch (error: SerializationException) {
        throw McpProtocolException("MCP_RPC_MALFORMED", error)
    } catch (error: IllegalArgumentException) {
        throw McpProtocolException("MCP_RPC_MALFORMED", error)
    }

    private suspend fun parseSseRpcResponse(response: HttpResponse, expectedId: Long): JsonObject {
        val channel = response.bodyAsChannel()
        val dataLines = mutableListOf<String>()
        val lineBuffer = StringBuilder()
        var eventBytes = 0L
        var totalBytes = 0L
        var eventCount = 0
        try {
            while (true) {
                lineBuffer.clear()
                val hasLine = try {
                    channel.readUTF8LineTo(lineBuffer, MAX_SSE_LINE_CHARS)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    throw McpProtocolException("MCP_SSE_LINE_TOO_LARGE", error)
                }
                if (!hasLine) {
                    if (dataLines.isNotEmpty()) throw McpProtocolException("MCP_SSE_TRUNCATED")
                    throw McpProtocolException("MCP_RPC_ID_MISMATCH")
                }
                val line = lineBuffer.toString()
                totalBytes += line.toByteArray(Charsets.UTF_8).size + 1L
                if (totalBytes > MAX_SSE_TOTAL_BYTES) {
                    throw McpProtocolException("MCP_SSE_TOTAL_TOO_LARGE")
                }
                when {
                    line.isEmpty() -> {
                        if (dataLines.isEmpty()) continue
                        eventCount++
                        if (eventCount > MAX_SSE_EVENTS) {
                            throw McpProtocolException("MCP_SSE_EVENT_LIMIT_EXCEEDED")
                        }
                        val message = parseRpcObject(dataLines.joinToString("\n"))
                        dataLines.clear()
                        eventBytes = 0L
                        val version = (message["jsonrpc"] as? JsonPrimitive)
                            ?.takeIf { it.isString }
                            ?.contentOrNull
                        if (version != "2.0") {
                            throw McpProtocolException("MCP_RPC_VERSION_MISMATCH")
                        }
                        val hasResult = message.containsKey("result")
                        val hasError = message.containsKey("error")
                        if (hasResult || hasError) {
                            if (hasResult == hasError || message["error"] is JsonNull) {
                                throw McpProtocolException("MCP_RPC_MALFORMED")
                            }
                            if (message["id"] != JsonPrimitive(expectedId)) {
                                throw McpProtocolException("MCP_RPC_ID_MISMATCH")
                            }
                            return message
                        }
                        val method = (message["method"] as? JsonPrimitive)
                            ?.takeIf { it.isString }
                            ?.contentOrNull
                            ?.takeIf(String::isNotBlank)
                        if (method != null && message["id"] == null) continue
                        if (method != null && message["id"] != null) {
                            throw McpProtocolException("MCP_SERVER_REQUEST_UNSUPPORTED")
                        }
                        throw McpProtocolException("MCP_SSE_EVENT_MALFORMED")
                    }
                    line.startsWith(":") -> Unit
                    line.startsWith("data:") -> {
                        val data = line.removePrefix("data:").removePrefix(" ")
                        eventBytes += data.toByteArray(Charsets.UTF_8).size + 1L
                        if (eventBytes > MAX_SSE_EVENT_BYTES) {
                            throw McpProtocolException("MCP_SSE_EVENT_TOO_LARGE")
                        }
                        dataLines += data
                    }
                    else -> Unit
                }
            }
        } finally {
            channel.cancel(null)
        }
    }

    private fun normalizeInputSchema(schema: JsonObject): McpNormalizedSchema = McpInputSchemaPolicy.parse(schema)

    private fun encodeHeaderParameters(args: JsonObject, declared: Set<String>): Map<String, String> =
        declared.mapNotNull { name ->
            if (!HEADER_PARAMETER_NAME.matches(name)) throw McpProtocolException("MCP_HEADER_NAME_INVALID")
            val rawValue = args[name] ?: return@mapNotNull null
            val value = rawValue as? JsonPrimitive
                ?: throw McpProtocolException("MCP_HEADER_VALUE_NOT_PRIMITIVE")
            val encoded = when {
                value.isString -> value.content
                value.booleanOrNull != null -> value.booleanOrNull.toString()
                value.longOrNull != null -> value.longOrNull.toString()
                else -> throw McpProtocolException("MCP_HEADER_VALUE_NOT_SUPPORTED")
            }
            if (encoded.any { it.code < 0x20 || it.code == 0x7f }) {
                throw McpProtocolException("MCP_HEADER_VALUE_INVALID")
            }
            "$HEADER_PARAMETER_PREFIX$name" to encoded
        }.toMap()

    private fun containsInputRequired(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element["input_required"]?.jsonPrimitive?.booleanOrNull == true ||
            element["status"]?.jsonPrimitive?.contentOrNull == "input_required" ||
            element["type"]?.jsonPrimitive?.contentOrNull == "input_required" ||
            element.values.any(::containsInputRequired)
        is kotlinx.serialization.json.JsonArray -> element.any(::containsInputRequired)
        else -> false
    }

    private fun requestMetadata(): JsonObject = buildJsonObject {
        put(META_PROTOCOL_VERSION, PROTOCOL_VERSION)
        put(META_CLIENT_INFO, buildJsonObject {
            put("name", CLIENT_NAME)
            put("version", BuildConfig.VERSION_NAME)
        })
        put(META_CLIENT_CAPABILITIES, JsonObject(emptyMap()))
    }

    private fun requiredString(element: JsonElement): String {
        val primitive = element as? JsonPrimitive ?: throw McpProtocolException("MCP_STRING_EXPECTED")
        if (!primitive.isString) throw McpProtocolException("MCP_STRING_EXPECTED")
        return primitive.content
    }

    private fun isAllowed(): Boolean = connectionSettings?.isAllowed(serverId.orEmpty(), "http", serverUrl)
        ?: McpEndpointPolicy.isAllowed("http", serverUrl)

    private fun ensureAllowed() {
        if (!isCurrent()) throw McpProtocolException("MCP_SERVER_CHANGED")
        if (!isAllowed()) throw McpProtocolException("MCP_ENDPOINT_INVALID")
    }

    private companion object {
        const val PROTOCOL_VERSION = "2026-07-28"
        const val CLIENT_NAME = "Infinity Deck"
        const val ACCEPT = "application/json, text/event-stream"
        const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
        const val META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
        const val META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
        const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"
        const val HEADER_METHOD = "Mcp-Method"
        const val HEADER_NAME = "Mcp-Name"
        const val HEADER_PARAMETER_PREFIX = "Mcp-Param-"
        const val MAX_PAGES = 1_000
        const val MAX_SSE_EVENTS = 1_000
        const val MAX_SSE_EVENT_BYTES = 256L * 1024L
        const val MAX_SSE_TOTAL_BYTES = 2L * 1024L * 1024L
        const val MAX_SSE_LINE_CHARS = 256 * 1024
        val TOOL_NAME = Regex("[A-Za-z0-9_.:/-]{1,128}")
        val HEADER_PARAMETER_NAME = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
    }
}

internal data class McpNormalizedSchema(val schema: JsonObject, val headerParameters: Set<String>)

internal object McpInputSchemaPolicy {
    fun parse(schemaJson: String): McpNormalizedSchema {
        val schema = runCatching { Json.parseToJsonElement(schemaJson) as? JsonObject }.getOrNull()
            ?: throw McpProtocolException("MCP_TOOL_SCHEMA_NOT_OBJECT")
        return parse(schema)
    }

    fun parse(schema: JsonObject): McpNormalizedSchema {
        val headerParameters = mutableSetOf<String>()
        fun strip(
            current: JsonObject,
            propertyName: String? = null,
            headerAllowed: Boolean = false,
            allowHeaderProperties: Boolean = false,
        ): JsonObject {
            val header = current["x-mcp-header"]
            if (header != null) {
                if (!headerAllowed || propertyName == null || header !is JsonPrimitive || header.booleanOrNull != true ||
                    !HEADER_NAME.matches(propertyName)
                ) throw McpProtocolException("MCP_HEADER_SCHEMA_INVALID")
                val type = (current["type"] as? JsonPrimitive)?.contentOrNull
                if (type !in PRIMITIVE_TYPES) throw McpProtocolException("MCP_HEADER_SCHEMA_NOT_PRIMITIVE")
                headerParameters += propertyName
            }
            return JsonObject(current.filterKeys { it != "x-mcp-header" }.mapValues { (key, value) ->
                when {
                    key == "properties" && value is JsonObject -> JsonObject(
                        value.mapValues { (nestedName, nested) ->
                            strip(
                                nested as? JsonObject
                                    ?: throw McpProtocolException("MCP_TOOL_SCHEMA_PROPERTY_INVALID"),
                                nestedName,
                                headerAllowed = allowHeaderProperties,
                            )
                        },
                    )
                    key == "items" && value is JsonObject -> strip(value)
                    key == "additionalProperties" && value is JsonObject -> strip(value)
                    else -> value
                }
            })
        }
        val sanitized = strip(schema, allowHeaderProperties = true)
        val validation = ToolSchemaValidator().validateDefinition(sanitized.toString())
        if (validation !is ToolSchemaValidation.Valid) throw McpProtocolException("MCP_TOOL_SCHEMA_UNSUPPORTED")
        return McpNormalizedSchema(
            schema = Json.parseToJsonElement(validation.canonicalSchema).jsonObject,
            headerParameters = headerParameters,
        )
    }

    private val HEADER_NAME = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
    private val PRIMITIVE_TYPES = setOf("string", "integer", "boolean")
}

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: String,
    val advertisedInputSchema: String = inputSchema,
    val headerParameters: Set<String> = emptySet(),
)
