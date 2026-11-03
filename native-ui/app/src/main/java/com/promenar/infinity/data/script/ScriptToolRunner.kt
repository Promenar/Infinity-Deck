package com.promenar.infinity.data.script

import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.script.*
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.ScriptWorkspaceGateway
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.promenar.infinity.domain.tool.ToolOutputStream
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** 用户在界面停止脚本时取消执行所用的原因，与父协程取消区分。 */
private class UserStopped : CancellationException("用户停止了脚本")

fun interface ScriptToolRunner {
    suspend fun execute(request: ScriptRequest, context: SkillExecutionContext): ScriptResult
}

class AndroidScriptToolRunner(
    private val runtime: ScriptRuntime,
    private val workspace: IWorkspaceRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val files: IFileOperationRepository,
    private val indexEvents: FileIndexEventSink,
    private val approvals: ScriptFileApprovalBroker,
    private val sessionWorkspace: (suspend (String) -> SessionWorkspace)? = null,
    private val networkPolicy: com.promenar.infinity.domain.network.AgentNetworkPolicy? = null,
    private val tailnetRouting: com.promenar.infinity.data.tailnet.TailnetRouting = com.promenar.infinity.data.tailnet.InstalledTailnetRouting.routing,
) : ScriptToolRunner {
    override suspend fun execute(request: ScriptRequest, context: SkillExecutionContext): ScriptResult = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(ScriptLimits.hostTimeoutMillis(request)) {
                val (gateway, loaded) = ScriptCapabilityDispatcher.Shared.call {
                    val access = ScriptWorkspaceGateway.open(context, workspace, mounts, files, indexEvents, approvals, request.entryPath, sessionWorkspace)
                    val source = if (request.entryPath == null) request else {
                        val text = access.invoke("read", buildJsonObject { put("path", request.entryPath) }).jsonObject["content"]!!.jsonPrimitive.content
                        request.copy(code = text)
                    }
                    access to source
                }
                val requests = AtomicInteger()
                val networkBudget = ScriptNetworkBudget()
                val http = ScriptHttpClient(tailnetRouting = tailnetRouting, networkPolicy = networkPolicy)
                val live = context.liveOutput
                val capabilities = object : ScriptCapabilities {
                    override suspend fun invoke(operation: String, arguments: JsonObject): JsonElement {
                        if (requests.incrementAndGet() > ScriptLimits.CAPABILITY_CALLS) throw ScriptFailure("CAPABILITY_LIMIT", "能力调用次数超过限制")
                        return if (operation == "fetch") {
                            if (!loaded.allowNetwork) throw ScriptFailure("NETWORK_DENIED", "此脚本未启用 allow_network")
                            http.fetch(arguments, networkBudget)
                        } else gateway.invoke(operation, arguments)
                    }

                    override fun output(fd: Int, text: String) =
                        live.emit(if (fd == 2) ToolOutputStream.STDERR else ToolOutputStream.STDOUT, text)
                }
                // 用户停止即取消本次执行；隔离运行时在取消时终止隔离进程。
                coroutineScope {
                    val run = async { runtime.execute(context.sessionId, loaded, capabilities) }
                    live.onStop { run.cancel(UserStopped()) }
                    try {
                        run.await()
                    } catch (stopped: UserStopped) {
                        ScriptResult(errorCode = "STOPPED", error = "用户在聊天界面停止了脚本，隔离进程已终止；不要自动重试，先询问用户。")
                    }
                }
            } ?: ScriptResult(errorCode = "TIMEOUT", error = "脚本准备、执行或审批等待超时；执行中的隔离进程已终止")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ScriptFailure) { ScriptResult(errorCode = failure.code, error = failure.message) }
        catch (failure: ExternalFolderException) { ScriptResult(errorCode = failure.code, error = failure.message) }
        catch (_: Exception) { ScriptResult(errorCode = "CAPABILITY_DENIED", error = "工作区或挂载授权不可用") }
    }
}
