package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.media.download.MAX_MEDIA_FETCH_BYTES
import com.promenar.infinity.data.media.download.WorkspaceMediaDownloader
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.media.*
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.domain.storage.ExternalMountOperationBindings
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.namespaceBindings
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.ui.chat.manager.registry.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

class MediaFetchSkill(
    private val store: WorkspaceMediaStore,
    private val downloader: WorkspaceMediaDownloader,
    private val maxBytes: Long = MAX_MEDIA_FETCH_BYTES,
    private val mounts: ExternalWorkspaceAccess? = null,
    private val sessionWorkspace: (suspend (String) -> SessionWorkspace)? = null,
    private val jobs: com.promenar.infinity.data.jobs.BackgroundJobManager? = null,
) : SkillDefinition {
    override val id = "media_fetch"
    override val name = "media_fetch"
    override val description = "下载图片、视频或音频到会话工作区并返回虚拟路径。支持 HTTP/HTTPS，公网、局域网与本机直连，tailnet 经已连接的内置 Tailscale。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.FILE_WRITE
    override val parametersSchema = """
        {"type":"object","properties":{
          "url":{"type":"string","description":"Media URL to download."},
          "path":{"type":"string","description":"Optional destination workspace virtual path. Omit to save under media/<yyyy-MM-dd>/. Existing files are never overwritten."},
          "yield_after_seconds":{"type":"integer","description":"默认 15 秒让出；0 不让出，上限为最长运行秒数。"},
          "background":{"type":"boolean","description":"立即转后台并返回任务号，默认 false。"},
          "on_exit":{"type":"string","enum":["none","notify","wake"],"description":"后台结束处理，默认 notify。"},
          "max_runtime_minutes":{"type":"integer","description":"最长运行 1–60 分钟，默认 10。"}
        },"required":["url"]}
    """.trimIndent()

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        return runMediaJob(id, args, context, jobs, 15, "notify", "background", "下载媒体") { output ->
            executeFetch(args, context, output)
        }
    }

    private suspend fun executeFetch(args: JsonObject, context: SkillExecutionContext,
        output: com.promenar.infinity.domain.tool.ToolLiveOutputSink): ToolResult {
        val resultId = "media_${UUID.randomUUID()}"
        val url = args.stringArgument("url")?.takeIf(String::isNotBlank)
            ?: return ToolResult(resultId, "缺少必填参数 url", status = "error")
        output.emit(com.promenar.infinity.domain.tool.ToolOutputStream.STDOUT, "正在下载媒体\n")
        return try {
            val path = MediaFetchPaths.targetPath(args.stringArgument("path"))
            val ref = withContext(Dispatchers.IO) {
                val workspace = sessionWorkspace?.invoke(context.sessionId)
                if (workspace is SessionWorkspace.Unavailable) throw WorkspaceMediaException("WORKSPACE_UNAVAILABLE", "当前会话工作区不可用，请检查目录与授权")
                val visibleMounts = mounts?.visible(context.sessionId).orEmpty()
                val primaryMount = mounts?.workspaceMount(context.sessionId)
                val bindings = (visibleMounts + listOfNotNull(primaryMount)).associate { it.alias to it.id }
                val currentTarget = ToolExecutionTargets.extract(toProtocolTool(), args.toString())
                    ?: throw WorkspaceMediaException("INVALID_PATH", "无法确定媒体保存路径")
                val approved = context.approvedExecutionTarget?.let { Json.parseToJsonElement(it).jsonArray }
                val metadata = approved?.lastOrNull() as? JsonObject
                val approvedIds = (metadata?.get("mountIds") as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
                if (context.isApprovalBoundExecution || approved != null) {
                    val tuple = approved?.let { if (metadata != null) it.dropLast(1) else it.toList() }
                    val expected = Json.parseToJsonElement(currentTarget).jsonArray.take(2)
                    val defaultPrivate = com.promenar.infinity.data.workspace.defaultWorkspaceFileDestination(workspace,
                        visibleMounts) == com.promenar.infinity.data.workspace.WorkspaceDefaultFileDestination.SESSION_PRIVATE
                    if (path == null) {
                        val frozen = com.promenar.infinity.data.workspace.defaultWorkspaceFileApprovalTarget(
                            (workspace as? SessionWorkspace.Directory)?.workspace?.id ?: primaryMount?.sourceId, visibleMounts, bindings)
                        if (metadata?.get("defaultFileDestination") != frozen) {
                            throw WorkspaceMediaException("APPROVAL_CHANGED", "批准的默认媒体落点或挂载集合已变化，请重新审批")
                        }
                    }
                    val alias = ExternalMountPaths.aliasOf(path ?: if (defaultPrivate) "/session/media" else "/media", workspace is SessionWorkspace.Directory)
                    if (tuple != expected || approvedIds.any { (alias, id) -> bindings[alias] != id } ||
                        alias != null && (alias !in approvedIds || approvedIds[alias] != bindings[alias])) {
                        throw WorkspaceMediaException("APPROVAL_CHANGED", "批准的媒体路径、挂载或工作区身份已失效，请重新审批")
                    }
                }
                val guardedStore = object : WorkspaceMediaStore by store {
                    override suspend fun save(sessionId: String, payload: MediaPayload, origin: MediaOrigin, mimeTypeHint: String?, suggestedName: String?, targetPath: String?): MediaRef {
                        val currentBindings = mounts?.namespaceBindings(sessionId).orEmpty()
                        if (sessionWorkspace?.invoke(sessionId) != workspace ||
                            bindings != currentBindings) {
                            throw WorkspaceMediaException("WORKSPACE_CHANGED", "媒体下载期间工作区或挂载发生变化，请重新审批")
                        }
                        return store.save(sessionId, payload, origin, mimeTypeHint, suggestedName, targetPath)
                    }
                }
                // 继承审批身份到存储的首次路径解析，避免下载等待期间同别名换源。
                withContext(ExternalMountOperationBindings(bindings)) {
                    downloader.save(url, context.sessionId, guardedStore, MediaOrigin.DOWNLOAD, path, maxBytes)
                }
            }
            output.emit(com.promenar.infinity.domain.tool.ToolOutputStream.STDOUT, "媒体已保存\n")
            ToolResult(resultId, "${mediaSavedDescription(ref)}\n${mediaReferenceGuidance(ref)}", media = listOf(ref))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = if (error is WorkspaceMediaException) "${error.code}：${error.message}" else mediaFailureDescription(error)
            ToolResult(resultId, message, status = "error")
        }
    }
}
