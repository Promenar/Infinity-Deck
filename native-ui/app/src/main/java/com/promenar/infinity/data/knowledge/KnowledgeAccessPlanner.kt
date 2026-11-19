package com.promenar.infinity.data.knowledge

import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.agent.KnowledgeFileAccess
import com.promenar.infinity.data.model.DocumentScope
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.rag.resolveEffectiveRagOptions
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.ExternalFolderManager
import com.promenar.infinity.data.storage.isKnowledgeBaseSource
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class KnowledgeAccessTarget(
    val rootUuid: String,
    val title: String,
    val documentCount: Int,
    val alias: String,
    val path: String,
    val readOnly: Boolean = true,
    val alreadyMounted: Boolean = false,
    val mountedId: String? = null,
    val mountedSourceType: String? = null,
    val mountedReadOnly: Boolean? = null,
)

@Serializable
data class KnowledgeAccessPreview(
    val sessionId: String,
    val knowledgeBase: String? = null,
    val allowed: Boolean,
    val reason: String? = null,
    val knowledgeBases: List<KnowledgeAccessTarget> = emptyList(),
    val accessMode: String = KNOWLEDGE_READ_ONLY,
)

internal const val KNOWLEDGE_READ_ONLY = "read_only"
internal const val KNOWLEDGE_READ_WRITE = "read_write"

internal fun requireKnowledgeAccessMode(mode: String): String {
    if (mode != KNOWLEDGE_READ_ONLY && mode != KNOWLEDGE_READ_WRITE) {
        throw ExternalFolderException("INVALID_ARGUMENT", "mode 必须为 read_only 或 read_write")
    }
    return mode
}

internal fun parseKnowledgeAccessArguments(args: kotlinx.serialization.json.JsonObject?): Pair<String?, String>? {
    if (args == null) return null
    val base = (args["knowledge_base"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    val mode = if ("mode" in args) {
        val raw = (args["mode"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (raw != KNOWLEDGE_READ_ONLY && raw != KNOWLEDGE_READ_WRITE) return null
        raw
    } else KNOWLEDGE_READ_ONLY
    return base to mode
}

/** 预览无写入；批准的完整预览在执行前重新解析并核验。 */
class KnowledgeAccessPlanner(
    private val loadSession: suspend (String) -> Session?,
    private val loadAgentConfig: suspend (String) -> AgentRetrievalConfig?,
    private val externalAccess: ExternalWorkspaceAccess,
    private val catalog: KnowledgeCatalogSource,
    private val effectiveOptions: (Session, AgentRetrievalConfig?) -> RagOptions = { session, config ->
        resolveEffectiveRagOptions(session.ragOptions, memoryPolicy = config?.memoryPolicy ?: AgentMemoryPolicy())
    },
) {
    suspend fun preview(
        sessionId: String, knowledgeBase: String? = null, accessMode: String = KNOWLEDGE_READ_ONLY,
    ): KnowledgeAccessPreview {
        requireKnowledgeAccessMode(accessMode)
        val readOnly = accessMode != KNOWLEDGE_READ_WRITE
        val selectedName = knowledgeBase?.trim()?.takeIf(String::isNotEmpty)
        fun denied(reason: String) = KnowledgeAccessPreview(sessionId, selectedName, false, reason, accessMode = accessMode)
        val session = loadSession(sessionId) ?: return denied("会话不存在")
        val config = loadAgentConfig(session.agentId) ?: AgentRetrievalConfig()
        if (!session.options.toolsEnabled) return denied("本会话已关闭工具，知识库文件访问未启用")
        if (config.knowledgeFileAccess == KnowledgeFileAccess.OFF) return denied("知识库文件访问已关闭")
        val options = effectiveOptions(session, config)
        if (!options.enableDocs) return denied("本会话已关闭资料检索")
        if (options.effectiveDocumentScope == DocumentScope.WORKSPACE) return denied("文档范围仅限本会话工作区，不能挂载知识库")
        val roots = catalog.knowledgeBases(session, options).distinctBy { it.rootUuid }
            .sortedWith(compareBy<KnowledgeBaseSummary>({ !it.isDefault }, { it.title }, { it.rootUuid }))
        val selected = if (selectedName == null) roots else roots.filter { it.title == selectedName }
        if (selected.isEmpty()) return denied(if (selectedName == null) "可见范围内没有知识库" else "可见范围内没有该知识库")
        if (selectedName != null && selected.size > 1) return denied("有多个同名知识库，请指定唯一名称后申请")
        val mounts = externalAccess.visible(sessionId)
        val occupied = mounts.mapTo(linkedSetOf()) { it.alias }
        val targets = selected.map { root ->
            val matching = mounts.filter { it.isKnowledgeBaseSource && it.sourceId == root.rootUuid }
            if (matching.size > 1) return denied("同一知识库存在重复挂载，请整理后申请")
            val existing = matching.singleOrNull()
            if (existing?.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE && !existing.readOnly) {
                return denied("已有知识库挂载只读状态无效，无法授予访问")
            }
            val alias = existing?.alias ?: uniqueAlias(if (root.isDefault) "knowledge" else root.title, occupied)
            occupied += alias
            KnowledgeAccessTarget(
                root.rootUuid, root.title, root.documentCount, alias, "/mnt/$alias", readOnly = readOnly,
                alreadyMounted = existing != null, mountedId = existing?.id, mountedSourceType = existing?.sourceType,
                mountedReadOnly = existing?.readOnly,
            )
        }
        return KnowledgeAccessPreview(sessionId, selectedName, true, knowledgeBases = targets, accessMode = accessMode)
    }

    suspend fun mount(approved: KnowledgeAccessPreview): List<ExternalMount> {
        val snapshot = approved.copy(knowledgeBases = approved.knowledgeBases.toList())
        return mountMutex.withLock { mountApproved(snapshot) }
    }

    private suspend fun mountApproved(approved: KnowledgeAccessPreview): List<ExternalMount> {
        val readOnly = requireKnowledgeAccessMode(approved.accessMode) == KNOWLEDGE_READ_ONLY
        if (!approved.allowed || approved.knowledgeBases.isEmpty() || approved.knowledgeBases.any { it.readOnly != readOnly }) {
            throw ExternalFolderException("ACCESS_DENIED", approved.reason ?: "知识库访问未获有效授权")
        }
        if (preview(approved.sessionId, approved.knowledgeBase, approved.accessMode) != approved) {
            throw ExternalFolderException("APPROVAL_TARGET_CHANGED", "知识库访问目标或范围已变化，请重新申请授权")
        }
        val source = if (readOnly) ExternalMountPaths.KNOWLEDGE_BASE_SOURCE else ExternalMountPaths.KNOWLEDGE_BASE_RW_SOURCE
        if (externalAccess is ExternalFolderManager) {
            return externalAccess.grantKnowledgeBaseAccess(approved.sessionId, approved.knowledgeBases)
        }
        val mounted = externalAccess.visible(approved.sessionId)
        val pending = approved.knowledgeBases.map { target ->
            val current = mounted.singleOrNull {
                it.isKnowledgeBaseSource && it.sourceId == target.rootUuid && it.alias == target.alias &&
                    it.id == target.mountedId && it.sourceType == target.mountedSourceType && it.readOnly == target.mountedReadOnly
            }
            if (target.alreadyMounted && current == null) {
                throw ExternalFolderException("APPROVAL_TARGET_CHANGED", "知识库挂载已变化，请重新申请授权")
            }
            if (!readOnly || current != null && (current.sourceType != source || !current.readOnly)) {
                throw ExternalFolderException("UNSUPPORTED", "挂载管理器不支持更改知识库授权级别")
            }
            current to target
        }
        return pending.map { (current, target) ->
            val mount = current ?: externalAccess.addKnowledgeBase(target.alias, approved.sessionId, target.rootUuid)
            if (mount.sourceType != source || mount.sourceId != target.rootUuid ||
                mount.alias != target.alias || mount.sessionId != approved.sessionId || mount.readOnly != readOnly) {
                throw ExternalFolderException("ACCESS_DENIED", "知识库挂载结果与已批准目标不一致")
            }
            mount
        }
    }

    /** 仅由新会话创建事件调用；没有监听或补齐已移除挂载的行为。 */
    suspend fun mountIfAlways(session: Session): List<ExternalMount> = mountMutex.withLock {
        if (!session.options.toolsEnabled) return emptyList()
        val current = loadSession(session.id) ?: return emptyList()
        val config = loadAgentConfig(current.agentId) ?: AgentRetrievalConfig()
        if (config.knowledgeFileAccess != KnowledgeFileAccess.ALWAYS) return emptyList()
        val target = preview(session.id)
        if (loadAgentConfig(current.agentId)?.knowledgeFileAccess != KnowledgeFileAccess.ALWAYS) return emptyList()
        return if (target.allowed) mountApproved(target) else emptyList()
    }

    private fun uniqueAlias(title: String, occupied: Set<String>): String {
        val candidate = title.trim().replace(Regex("[/\\\\\\p{Cc}\\p{Cf}]+"), "-")
            .trim(' ', '.', '-').take(80).ifBlank { "knowledge" }
        val base = runCatching { ExternalMountPaths.validateAlias(candidate) }.getOrDefault("knowledge")
        var alias = base
        var index = 2
        while (alias in occupied) alias = "$base-${index++}"
        return ExternalMountPaths.validateAlias(alias)
    }

    private companion object {
        val mountMutex = Mutex()
    }
}
