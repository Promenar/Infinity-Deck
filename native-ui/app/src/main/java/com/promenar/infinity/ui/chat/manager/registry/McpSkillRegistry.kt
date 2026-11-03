package com.promenar.infinity.ui.chat.manager.registry

import com.promenar.infinity.data.local.db.entity.McpToolSnapshotEntity
import com.promenar.infinity.data.local.db.dao.McpDiscoveryRow
import com.promenar.infinity.data.remote.mcp.McpClient
import com.promenar.infinity.data.remote.mcp.McpConnectionSettings
import com.promenar.infinity.data.remote.mcp.McpEndpointPolicy
import com.promenar.infinity.data.remote.mcp.McpInputSchemaPolicy
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.repository.SkillRepository
import com.promenar.infinity.ui.chat.manager.skills.McpSkill
import io.ktor.client.HttpClient
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

sealed interface McpSyncResult {
    data class Success(val toolCount: Int) : McpSyncResult
    data class Failure(val code: String) : McpSyncResult
}

class McpSkillRegistry(
    private val repository: SkillRepository,
    private val httpClient: HttpClient,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val connectionSettings: McpConnectionSettings? = null,
    private val mediaStore: com.promenar.infinity.domain.media.WorkspaceMediaStore? = null,
) : SkillRegistry {
    private data class PublishedSkill(
        val skill: McpSkill,
        val serverUrl: String,
        val serverType: String,
    )

    private val published = AtomicReference<List<PublishedSkill>>(emptyList())
    private val syncGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val syncCommitLocks = ConcurrentHashMap<String, Mutex>()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.observeMcpDiscoveryRows()
                .combine(connectionSettings?.revision ?: flowOf(0L)) { rows, _ -> rows }
                .catch { emit(emptyList()) }
                .collectLatest { rows ->
                    published.set(buildPublishedSkills(rows))
                }
        }
    }

    override fun getSkill(name: String): SkillDefinition? = currentSkills()
        .singleOrNull { it.name == name }

    override fun getSkillByRuntimeToolId(runtimeToolId: String): SkillDefinition? = currentSkills()
        .singleOrNull { it.runtimeToolId == runtimeToolId }

    override fun getAllSkills(): List<SkillDefinition> = currentSkills()

    override fun getAllTools(allowedIds: List<String>?): List<ProtocolTool> {
        val skills = currentSkills()
        val filtered = if (allowedIds == null) skills else skills.filter { skill ->
            skill.mcpServerId in allowedIds || skill.runtimeToolId in allowedIds
        }
        return filtered.map(SkillDefinition::toProtocolTool)
    }

    suspend fun syncServer(serverId: String): McpSyncResult {
        val syncGeneration = syncGenerations.computeIfAbsent(serverId) { AtomicLong() }
            .incrementAndGet()
        val configRevision = repository.currentMcpConfigRevision(serverId)
        val server = repository.getMcpServer(serverId)
            ?: return McpSyncResult.Failure("MCP_SERVER_NOT_FOUND")
        if (!isSupported(server.id, server.type, server.url)) {
            return McpSyncResult.Failure(unsupportedCode())
        }
        if (!server.enabled) return McpSyncResult.Failure("MCP_SERVER_DISABLED")
        val connectionRevision = connectionSettings?.revision?.value
        return try {
            val tools = McpClient(httpClient, server.url, connectionSettings, server.id) {
                // 分页前也核对撤销、改址和新发现请求，避免旧任务继续携带凭据联网。
                repository.currentMcpConfigRevision(serverId) == configRevision &&
                    connectionSettings?.revision?.value == connectionRevision &&
                    syncGenerations[serverId]?.get() == syncGeneration
            }.listTools()
            val timestamp = now()
            val snapshots = tools.map { tool ->
                McpToolSnapshotEntity(
                    serverId = server.id,
                    remoteToolName = tool.name,
                    description = tool.description,
                    inputSchemaJson = tool.inputSchema,
                    syncedAt = timestamp,
                )
            }
            syncCommitLocks.computeIfAbsent(serverId) { Mutex() }.withLock {
                if (syncGenerations[serverId]?.get() != syncGeneration) {
                    return@withLock McpSyncResult.Failure("MCP_SYNC_SUPERSEDED")
                }
                if (connectionSettings?.revision?.value != connectionRevision ||
                    !isSupported(server.id, server.type, server.url)
                ) {
                    return@withLock McpSyncResult.Failure("MCP_CONNECTION_SETTINGS_CHANGED_DURING_SYNC")
                }
                if (!repository.replaceMcpToolSnapshotsAtRevision(
                        serverId = server.id,
                        expectedUrl = server.url,
                        expectedType = server.type,
                        snapshots = snapshots,
                        expectedConfigRevision = configRevision,
                    )
                ) {
                    McpSyncResult.Failure("MCP_SERVER_CHANGED_DURING_SYNC")
                } else {
                    replacePublishedServer(server.id, server.url, server.type, snapshots)
                    McpSyncResult.Success(snapshots.size)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            McpSyncResult.Failure(error.message?.takeIf { it.startsWith("MCP_") } ?: "MCP_SYNC_FAILED")
        }
    }

    private fun currentSkills(): List<SkillDefinition> = published.get()
        .filter { record ->
            val serverId = record.skill.mcpServerId ?: return@filter false
            isSupported(serverId, record.serverType, record.serverUrl) && repository.isMcpSnapshotCurrent(
                serverId,
                record.serverUrl,
                record.serverType,
            )
        }
        .map { it.skill }

    private suspend fun buildPublishedSkills(rows: List<McpDiscoveryRow>): List<PublishedSkill> =
        rows.groupBy { it.serverId }
            .toSortedMap()
            .flatMap { (_, serverRows) ->
                coroutineContext.ensureActive()
                runCatching { serverRows.map(::publishedSkill) }.getOrElse { emptyList() }
            }

    private fun publishedSkill(row: McpDiscoveryRow): PublishedSkill {
        if (!isSupported(row.serverId, row.serverType, row.serverUrl)) {
            throw IllegalArgumentException(unsupportedCode())
        }
        val schema = McpInputSchemaPolicy.parse(row.inputSchemaJson)
        return PublishedSkill(
            skill = McpSkill(
                id = runtimeToolId(row.serverId, row.remoteToolName),
                name = providerAlias(row.serverId, row.remoteToolName),
                remoteToolName = row.remoteToolName,
                description = row.description,
                parametersSchema = schema.schema.toString(),
                headerParameters = schema.headerParameters,
                mcpClient = McpClient(httpClient, row.serverUrl, connectionSettings, row.serverId) {
                    repository.isMcpSnapshotCurrent(row.serverId, row.serverUrl, row.serverType)
                },
                mcpServerId = row.serverId,
                mediaStore = mediaStore,
            ),
            serverUrl = row.serverUrl,
            serverType = row.serverType,
        )
    }

    private fun replacePublishedServer(
        serverId: String,
        serverUrl: String,
        serverType: String,
        snapshots: List<McpToolSnapshotEntity>,
    ) {
        val replacement = runCatching {
            snapshots.map { snapshot ->
                publishedSkill(
                    McpDiscoveryRow(
                        serverId = serverId,
                        serverUrl = serverUrl,
                        serverType = serverType,
                        remoteToolName = snapshot.remoteToolName,
                        description = snapshot.description,
                        inputSchemaJson = snapshot.inputSchemaJson,
                        syncedAt = snapshot.syncedAt,
                    ),
                )
            }
        }.getOrElse { emptyList() }
        published.updateAndGet { existing ->
            (existing.filterNot { it.skill.mcpServerId == serverId } + replacement)
                .sortedWith(compareBy({ it.skill.mcpServerId }, { it.skill.runtimeToolId }))
        }
    }

    private fun isSupported(serverId: String, type: String, url: String): Boolean =
        connectionSettings?.isAllowed(serverId, type, url) ?: McpEndpointPolicy.isAllowed(type, url)

    private fun unsupportedCode(): String = "MCP_LEGACY_TRANSPORT_UNSUPPORTED"

    companion object {
        fun runtimeToolId(serverId: String, remoteToolName: String): String =
            "mcp:$serverId:$remoteToolName"

        fun providerAlias(serverId: String, remoteToolName: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$serverId\u0000$remoteToolName".toByteArray())
                .take(6)
                .joinToString("") { "%02x".format(it) }
            val safeName = remoteToolName.map { char ->
                if (char.isLetterOrDigit() || char == '_' || char == '-') char else '_'
            }.joinToString("").take(80).ifBlank { "tool" }
            return "mcp_${digest}_$safeName"
        }
    }
}
