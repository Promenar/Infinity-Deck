package com.promenar.infinity.ui.chat.manager.registry

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.McpServerEntity
import com.promenar.infinity.data.local.db.entity.McpToolSnapshotEntity
import com.promenar.infinity.data.repository.SkillRepository
import com.promenar.infinity.data.remote.mcp.McpConnectionSettings
import com.promenar.infinity.data.remote.mcp.MemoryMcpSecrets
import com.promenar.infinity.domain.tool.ToolRisk
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteReadPacket
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.core.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class McpSkillRegistryPersistenceTest {
    private lateinit var database: InfinityDatabase
    private lateinit var repository: SkillRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SkillRepository(database.skillDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `restart从Room恢复且同名跨server使用稳定不同身份`() = runTest {
        seedServer("a")
        seedServer("b")
        seedSnapshot("a", "search")
        seedSnapshot("b", "search")

        val firstRegistry = registry(MockEngine { error("不应联网") }, backgroundScope)
        val restartedRegistry = registry(
            MockEngine { error("不应联网") },
            backgroundScope,
            SkillRepository(database.skillDao()),
        )
        val first = awaitToolCount(firstRegistry, 2)
        val restarted = awaitToolCount(restartedRegistry, 2)

        assertThat(first.map { it.runtimeToolId })
            .containsExactly("mcp:a:search", "mcp:b:search")
        assertThat(first.map { it.function.name }.distinct()).hasSize(2)
        assertThat(restarted.map { it.runtimeToolId }).containsExactlyElementsIn(first.map { it.runtimeToolId })
        assertThat(McpSkillRegistry.providerAlias("a", "same/path"))
            .isNotEqualTo(McpSkillRegistry.providerAlias("a", "same.path"))
        repository.updateMcpServerEnabled("a", false)
        assertThat(firstRegistry.getAllTools(null).map { it.runtimeToolId })
            .containsExactly("mcp:b:search")
        assertThat(firstRegistry.getSkillByRuntimeToolId("mcp:a:search"))
            .isNull()
    }

    @Test
    fun `empty成功原子清除且失败保留旧批`() = runTest {
        seedServer("a")
        seedSnapshot("a", "old")
        val emptyRegistry = registry(rpcEngine("""{"tools":[]}"""), backgroundScope)

        assertThat(emptyRegistry.syncServer("a")).isEqualTo(McpSyncResult.Success(0))
        assertThat(repository.getMcpToolSnapshots("a")).isEmpty()

        seedSnapshot("a", "kept")
        val failed = registry(
            MockEngine { respond("bad", HttpStatusCode.BadGateway) },
            backgroundScope,
        ).syncServer("a")
        assertThat(failed).isInstanceOf(McpSyncResult.Failure::class.java)
        assertThat(repository.getMcpToolSnapshots("a").map { it.remoteToolName }).containsExactly("kept")
    }

    @Test
    fun `sync提交前server变化不会让late结果复活`() = runTest {
        seedServer("a")
        seedSnapshot("a", "old")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val registry = registry(MockEngine { request ->
            entered.complete(Unit)
            release.await()
            val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
            respond(
                """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"late","inputSchema":{"type":"object"}}]}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }, backgroundScope)

        val sync = async { registry.syncServer("a") }
        entered.await()
        repository.insertMcpServer(
            McpServerEntity("a", "A", "https://changed.example.test", "http", createdAt = 1),
        )
        release.complete(Unit)

        assertThat(sync.await()).isEqualTo(McpSyncResult.Failure("MCP_SERVER_CHANGED_DURING_SYNC"))
        assertThat(repository.getMcpToolSnapshots("a")).isEmpty()
    }

    @Test
    fun `disable enable ABA期间返回的旧sync不得提交`() = runTest {
        seedServer("a")
        seedSnapshot("a", "old")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val registry = registry(MockEngine { request ->
            entered.complete(Unit)
            release.await()
            val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
            respond(
                """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"late","inputSchema":{"type":"object"}}]}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }, backgroundScope)

        val sync = async { registry.syncServer("a") }
        entered.await()
        repository.updateMcpServerEnabled("a", false)
        repository.updateMcpServerEnabled("a", true)
        release.complete(Unit)

        assertThat(sync.await()).isEqualTo(McpSyncResult.Failure("MCP_SERVER_CHANGED_DURING_SYNC"))
        assertThat(repository.getMcpToolSnapshots("a")).isEmpty()
    }

    @Test
    fun `同server并发sync逆序返回时旧代际不得覆盖新批`() = runTest {
        seedServer("a")
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var calls = 0
        val registry = registry(MockEngine { request ->
            calls++
            val call = calls
            if (call == 1) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
            val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
            val name = if (call == 1) "older" else "newer"
            respond(
                """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"$name","inputSchema":{"type":"object"}}]}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }, backgroundScope)

        val first = async { registry.syncServer("a") }
        firstEntered.await()
        val second = async { registry.syncServer("a") }
        assertThat(second.await()).isEqualTo(McpSyncResult.Success(1))
        releaseFirst.complete(Unit)

        assertThat(first.await()).isEqualTo(McpSyncResult.Failure("MCP_SYNC_SUPERSEDED"))
        assertThat(repository.getMcpToolSnapshots("a").map { it.remoteToolName })
            .containsExactly("newer")
    }

    @Test
    fun `同步广告读取不得等待挂起DAO或阻塞调用线程`() {
        val hangingRepository = mockk<SkillRepository>()
        coEvery { hangingRepository.getAllEnabledMcpServers() } coAnswers { awaitCancellation() }
        every { hangingRepository.observeMcpDiscoveryRows() } returns flow { awaitCancellation() }
        val registryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val registry = McpSkillRegistry(
            hangingRepository,
            HttpClient(MockEngine { error("不应联网") }),
            registryScope,
        )
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<List<com.promenar.infinity.data.remote.protocol.ProtocolTool>> {
            registry.getAllTools(null)
        }
        try {
            val result = runCatching { future.get(200, TimeUnit.MILLISECONDS) }.getOrNull()
            assertThat(result).isNotNull()
            assertThat(result).isEmpty()
        } finally {
            future.cancel(true)
            executor.shutdownNow()
            registryScope.cancel()
        }
    }

    @Test
    fun `restart后的原始snapshot仍恢复x mcp header执行语义`() = runTest {
        seedServer("a")
        assertThat(repository.replaceMcpToolSnapshotsIfServerUnchanged(
            "a",
            "https://a.example.test",
            "http",
            listOf(
                McpToolSnapshotEntity(
                    "a",
                    "search",
                    "Search",
                    """{"type":"object","properties":{"tenant":{"type":"string","x-mcp-header":true}}}""",
                    1,
                ),
            ),
        )).isTrue()
        var header: String? = null
        val registry = registry(MockEngine { request ->
            header = request.headers["Mcp-Param-tenant"]
            val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
            respond(
                """{"jsonrpc":"2.0","id":$id,"result":{"content":[]}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }, backgroundScope, SkillRepository(database.skillDao()))
        awaitToolCount(registry, 1)
        val skill = registry.getSkillByRuntimeToolId("mcp:a:search")!!

        val result = skill.execute(
            Json.parseToJsonElement("""{"tenant":"acme"}""").jsonObject,
            object : SkillExecutionContext {
                override val sessionId = "session"
                override val agentId = "agent"
                override val workspacePath: String? = null
                override val workspaceRootUuid = "root"
            },
        )

        assertThat(result.status).isEqualTo("success")
        assertThat(header).isEqualTo("acme")
    }

    @Test fun `HTTP服务器可发现且stdio配置不接入`() = runTest {
        repository.insertMcpServer(McpServerEntity("http", "HTTP", "http://legacy.example.test", "http", createdAt = 1))
        repository.insertMcpServer(McpServerEntity("stdio", "STDIO", "/usr/bin/server", "stdio", createdAt = 1))
        val registry = registry(MockEngine { request ->
            val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
            respond("""{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"exec","inputSchema":{"type":"object"}}]}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }, backgroundScope)
        assertThat(registry.syncServer("http")).isEqualTo(McpSyncResult.Success(1))
        assertThat(awaitToolCount(registry, 1)).hasSize(1)
        assertThat(registry.syncServer("stdio")).isEqualTo(McpSyncResult.Failure("MCP_LEGACY_TRANSPORT_UNSUPPORTED"))
    }

    @Test
    fun `本机MCP发现与注册执行使用可选凭据且服务器停用后撤下工具`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = McpConnectionSettings(
            context.getSharedPreferences("mcp_registry_${System.nanoTime()}", Context.MODE_PRIVATE),
            MemoryMcpSecrets(),
        )
        settings.saveBearerToken("local", "test-only-bearer")
        repository.insertMcpServer(McpServerEntity("local", "本机", "http://127.0.0.1:8765/mcp", "http"))
        val credentials = mutableListOf<String?>()
        val methods = mutableListOf<String?>()
        val registry = registry(MockEngine { request ->
            credentials += request.headers[HttpHeaders.Authorization]
            val method = request.headers["Mcp-Method"]
            methods += method
            val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
            val result = if (method == "tools/list") {
                """{"tools":[{"name":"exec","inputSchema":{"type":"object"}}]}"""
            } else {
                """{"content":[]}"""
            }
            respond(
                """{"jsonrpc":"2.0","id":$id,"result":$result}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }, backgroundScope, connectionSettings = settings)

        assertThat(registry.syncServer("local")).isEqualTo(McpSyncResult.Success(1))
        assertThat(awaitToolCount(registry, 1).single().risk).isEqualTo(ToolRisk.PROCESS)
        val skill = registry.getSkillByRuntimeToolId("mcp:local:exec")!!
        val executionContext = object : SkillExecutionContext {
            override val sessionId = "session"
            override val agentId = "agent"
            override val workspacePath: String? = null
            override val workspaceRootUuid = "root"
        }
        assertThat(skill.execute(JsonObject(emptyMap()), executionContext).status).isEqualTo("success")
        assertThat(methods).containsExactly("tools/list", "tools/call").inOrder()
        assertThat(credentials).containsExactly("Bearer test-only-bearer", "Bearer test-only-bearer")

        repository.updateMcpServerEnabled("local", false)
        assertThat(registry.getAllTools(null)).isEmpty()
        assertThat(skill.execute(JsonObject(emptyMap()), executionContext).status).isEqualTo("error")
        assertThat(methods).hasSize(2)
    }

    @Test
    fun `MCP发现期间凭据变化不得提交返回的工具`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = McpConnectionSettings(
            context.getSharedPreferences("mcp_disabling_${System.nanoTime()}", Context.MODE_PRIVATE),
            MemoryMcpSecrets(),
        )
        settings.saveBearerToken("local", "test-only-bearer")
        repository.insertMcpServer(McpServerEntity("local", "本机", "http://127.0.0.1:8765/mcp", "http"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val registry = registry(MockEngine { request ->
            entered.complete(Unit)
            release.await()
            val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
            respond(
                """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"exec","inputSchema":{"type":"object"}}]}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }, backgroundScope, connectionSettings = settings)
        val sync = async { registry.syncServer("local") }
        entered.await()
        settings.removeBearerToken("local")
        release.complete(Unit)
        assertThat(sync.await()).isEqualTo(McpSyncResult.Failure("MCP_CONNECTION_SETTINGS_CHANGED_DURING_SYNC"))
        assertThat(repository.getMcpToolSnapshots("local")).isEmpty()
        assertThat(registry.getAllTools(null)).isEmpty()
    }

    @Test
    fun `本机MCP分页期间撤销服务器或安全配置后不再发送后续请求`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (change in listOf("disable", "delete", "url", "security_aba")) {
            val id = "paging_$change"
            val settings = McpConnectionSettings(
                context.getSharedPreferences("mcp_paging_${System.nanoTime()}", Context.MODE_PRIVATE), MemoryMcpSecrets(),
            )
            settings.saveBearerToken(id, "test-only-bearer")
            val server = McpServerEntity(id, "本机", "http://127.0.0.1:8765/mcp", "http")
            repository.insertMcpServer(server)
            var calls = 0
            val credentials = mutableListOf<String?>()
            val client = registry(MockEngine { request ->
                calls++
                credentials += request.headers[HttpHeaders.Authorization]
                if (calls == 1) when (change) {
                    "disable" -> repository.updateMcpServerEnabled(id, false)
                    "delete" -> repository.deleteMcpServer(server)
                    "url" -> repository.insertMcpServer(server.copy(url = "http://127.0.0.1:8766/mcp"))
                    else -> { settings.removeBearerToken(id); settings.saveBearerToken(id, "test-only-bearer") }
                }
                val rpcId = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
                val page = if (calls == 1) """{"tools":[],"nextCursor":"second"}""" else """{"tools":[]}"""
                respond("""{"jsonrpc":"2.0","id":$rpcId,"result":$page}""", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }, backgroundScope, connectionSettings = settings)
            assertThat(client.syncServer(id)).isInstanceOf(McpSyncResult.Failure::class.java)
            assertThat(calls).isEqualTo(1)
            assertThat(credentials).containsExactly("Bearer test-only-bearer")
            assertThat(repository.getMcpToolSnapshots(id)).isEmpty()
            assertThat(client.getAllTools(null)).isEmpty()
        }
    }

    private suspend fun seedServer(id: String) = repository.insertMcpServer(
        McpServerEntity(id, id.uppercase(), "https://$id.example.test", "http", createdAt = 1),
    )

    private suspend fun seedSnapshot(serverId: String, name: String) {
        assertThat(repository.replaceMcpToolSnapshotsIfServerUnchanged(
            serverId,
            "https://$serverId.example.test",
            "http",
            listOf(McpToolSnapshotEntity(serverId, name, name, """{"type":"object"}""", 1)),
        )).isTrue()
    }

    private fun registry(
        engine: MockEngine,
        scope: CoroutineScope,
        sourceRepository: SkillRepository = repository,
        connectionSettings: McpConnectionSettings? = null,
    ) = McpSkillRegistry(sourceRepository, HttpClient(engine), scope, now = { 2 }, connectionSettings = connectionSettings)

    private suspend fun awaitToolCount(registry: McpSkillRegistry, count: Int) = withTimeout(2_000) {
        while (registry.getAllTools(null).size != count) delay(1)
        registry.getAllTools(null)
    }

    private fun rpcEngine(result: String) = MockEngine { request ->
        val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
        respond(
            """{"jsonrpc":"2.0","id":$id,"result":$result}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
}
