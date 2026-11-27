package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.generation.RemoteMountTargetResolver
import com.promenar.infinity.data.generation.ResolvedRemoteMount
import com.promenar.infinity.data.generation.ResolvedRemoteMountTarget
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.data.storage.SftpExternalFolderBackend
import com.promenar.infinity.domain.storage.*
import com.promenar.infinity.domain.tool.*
import com.promenar.infinity.ui.chat.manager.registry.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal const val SFTP_USE_REMOTE_MOUNT_TOOL = "SFTP_USE_REMOTE_MOUNT_TOOL"

internal fun sftpGenericMutationDenied() = ExternalFolderException(
    SFTP_USE_REMOTE_MOUNT_TOOL,
    "远程 SFTP 挂载请使用 remote_mount_archive、remote_mount_mkdir、remote_mount_rename 或 remote_mount_delete。",
)

internal fun remoteMountTools(
    resolver: RemoteMountTargetResolver,
    transfers: () -> RemoteTransferController,
    backend: (String, ResolvedRemoteMount) -> SftpExternalFolderBackend,
): List<SkillDefinition> = RemoteMountOperation.entries.map { RemoteMountSkill(it, resolver, transfers, backend) }

private class RemoteMountSkill(
    private val operation: RemoteMountOperation,
    private val resolver: RemoteMountTargetResolver,
    private val transfers: () -> RemoteTransferController,
    private val backend: (String, ResolvedRemoteMount) -> SftpExternalFolderBackend,
) : SkillDefinition {
    override val id = operation.toolName
    override val name = id
    override val risk = operation.risk
    override val mcpServerId: String? = null
    override val description = when (operation) {
        RemoteMountOperation.ARCHIVE -> "将工作区普通文件归档到可写 SFTP 挂载，逐次审批，返回传输任务或远端 SHA-256 回执。"
        RemoteMountOperation.DOWNLOAD -> "将 SFTP 挂载中的普通文件下载到当前工作区，返回传输任务或校验回执。"
        RemoteMountOperation.STATUS -> "查询当前会话的远程挂载传输进度、错误或校验回执。"
        RemoteMountOperation.MKDIR -> "在可写 SFTP 挂载中创建目录，父目录须存在，逐次审批。"
        RemoteMountOperation.RENAME -> "核验远端文件 SHA-256 后在同一个 SFTP 挂载内重命名，目标必须不存在，逐次审批。"
        RemoteMountOperation.DELETE -> "核验远端文件 SHA-256 后永久删除普通文件，逐次审批。"
    }
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") { operation.requiredKeys.forEach { add(it) } }
        putJsonObject("properties") {
            for (key in operation.requiredKeys + operation.optionalKeys) {
                putJsonObject(key) {
                    when (key) {
                        "wait_ms" -> {
                            put("type", "integer")
                            put("minimum", 0)
                            put("maximum", 15_000)
                            put("default", operation.defaultWaitMs)
                        }
                        "overwrite" -> {
                            put("type", "boolean")
                            put("default", false)
                            put("description", if (operation == RemoteMountOperation.ARCHIVE)
                                "服务端须支持原子条件替换，当前传输引擎将拒绝开启。" else "允许覆盖批准时已存在的本地普通文件。")
                        }
                        else -> {
                            put("type", "string")
                            if ("sha256" in key) put("pattern", "^[A-Fa-f0-9]{64}$")
                            if (key.endsWith("mount_path")) put("description", "/mnt/<alias>/ 下的完整路径")
                            if (key == "workspace_path") put("description", "工作区相对路径或当前会话文件工具使用的工作区绝对路径")
                        }
                    }
                }
            }
        }
    }.toString()

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        try {
            val request = RemoteMountArguments.parse(operation, args)
            if (operation == RemoteMountOperation.STATUS) {
                return@withContext transferResult(awaitOwned(context.sessionId, requireNotNull(request.transferId), request.waitMs))
            }
            val path = requireNotNull(request.path)
            val approved = context.approvedExecutionTarget
            val approvedMetadata = approved?.let { runCatching { Json.parseToJsonElement(it).jsonArray.last() as? JsonObject }.getOrNull() }
            val binding = try {
                resolver.binding(context.sessionId, path, operation.writesRemote)
            } catch (failure: ExternalFolderException) {
                if (approvedMetadata != null && failure.code in setOf("SSH_HOST_MISSING", "HOST_AUTHORIZATION_CHANGED")) {
                    fail("HOST_AUTHORIZATION_CHANGED", "SSH 主机已变更，请重新审批")
                }
                if (approvedMetadata != null && failure.code in setOf("NOT_FOUND", "LIBRARY_SOURCE_CHANGED", "LIBRARY_SOURCE_MISSING")) {
                    fail("CONFLICT", "批准的远程挂载已变化，请重新审批")
                }
                throw failure
            }
            if (operation.writesRemote || context.isApprovalBoundExecution || approved != null) {
                if (!context.isApprovalBoundExecution || approvedMetadata == null) fail("APPROVAL_REQUIRED", "请批准本次远程挂载操作")
                fun field(key: String) = (approvedMetadata[key] as? JsonPrimitive)?.contentOrNull
                if (field("mountId") != binding.mount.id || field("mountSource") != binding.sourceIdentity ||
                    field("operation") != id || field("mountPath") != path.virtual
                ) fail("CONFLICT", "批准的路径或远程挂载来源已变化，请重新审批")
                if (field("sshHostId") != binding.identity.hostId || field("sshIdentity") != binding.identity.digest) {
                    fail("HOST_AUTHORIZATION_CHANGED", "批准的 SSH 主机身份已变化，请重新审批")
                }
            }
            val resolved = resolver.resolve(context.sessionId, context.runMode, toProtocolTool(), operation, args, request, binding)
            if (approved != null && resolved.target != approved) fail("CONFLICT", "批准的传输参数或文件状态已变化，请重新审批")
            if (operation.writesRemote || operation == RemoteMountOperation.DOWNLOAD) {
                com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = context.sessionId)
            }
            if (operation.transfers) return@withContext enqueue(context.sessionId, request, resolved)
            val remote = backend(context.sessionId, resolved.binding)
            suspend fun verifyBinding() {
                val current = resolver.binding(context.sessionId, path, writing = true)
                if (current.identity != resolved.binding.identity) fail("HOST_AUTHORIZATION_CHANGED", "批准的 SSH 主机身份已变化")
                if (current.mount.id != resolved.binding.mount.id || current.spec != resolved.binding.spec) {
                    fail("CONFLICT", "远程挂载来源已变更")
                }
            }
            when (operation) {
                RemoteMountOperation.MKDIR -> {
                    verifyBinding()
                    remote.createDirectory(path.relative)
                }
                RemoteMountOperation.RENAME -> remote.renameFileWithSha256(path.relative,
                    requireNotNull(request.targetPath).relative, requireNotNull(request.expectedSha256), ::verifyBinding)
                RemoteMountOperation.DELETE -> remote.deleteFileWithSha256(path.relative, requireNotNull(request.expectedSha256), ::verifyBinding)
                else -> error("当前操作不修改远程目录项")
            }
            result(buildJsonObject {
                put("state", "SUCCEEDED")
                put("mount_path", path.virtual)
                request.targetPath?.let { put("target_mount_path", it.virtual) }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SshFailure) {
            val code = if (failure.code == "HOST_CONFIGURATION_CHANGED") "HOST_AUTHORIZATION_CHANGED" else failure.code
            failure(code, "远程挂载操作失败（$code）")
        } catch (failure: ExternalFolderException) {
            val code = if (failure.code == "HOST_CONFIGURATION_CHANGED") "HOST_AUTHORIZATION_CHANGED" else failure.code
            failure(code, failure.message.orEmpty())
        } catch (_: com.promenar.infinity.startup.StartupIsolationException) {
            failure("READ_ONLY", "当前工作区处于只读隔离状态")
        } catch (_: Exception) {
            failure("IO_FAILED", "远程挂载操作未完成，请检查文件路径与连接")
        }
    }

    private suspend fun enqueue(sessionId: String, args: RemoteMountArguments, resolved: ResolvedRemoteMountTarget): ToolResult {
        val upload = operation == RemoteMountOperation.ARCHIVE
        if (upload && args.overwrite) fail("ATOMIC_CONDITIONAL_REPLACE_UNSUPPORTED", "当前传输引擎不支持远程原子条件替换，请使用新路径或省略 overwrite")
        val binding = resolved.binding
        val path = requireNotNull(args.path)
        val local = requireNotNull(resolved.local)
        val metadata = Json.parseToJsonElement(resolved.target).jsonArray.last().jsonObject
        val request = RemoteTransferRequest(
            mountId = binding.mount.id,
            hostAlias = binding.spec.hostAlias,
            mountVirtualPath = path.virtual,
            remoteFullPath = path.under(binding.spec.rootPath),
            localPath = local.file.path,
            sourceLabel = if (upload) local.workspacePath else path.virtual,
            targetLabel = if (upload) path.virtual else local.workspacePath,
            overwritePolicy = if (args.overwrite) RemoteOverwritePolicy.ALLOW_OVERWRITE else RemoteOverwritePolicy.REFUSE_DIFFERING,
            sessionId = sessionId,
            authorization = RemoteTransferAuthorization(
                binding.identity.hostId, binding.identity.digest, binding.spec.rootPath, binding.sourceIdentity,
                RemoteTransferWorkspaceBinding(requireNotNull(args.workspacePath), local.identity, local.revision, local.privateRootUuid),
                metadata["bytes_total"]?.jsonPrimitive?.longOrNull,
                remoteSourceRevision = metadata["source_revision"]?.jsonPrimitive?.contentOrNull.takeUnless { upload },
            ),
        )
        val transfer = if (upload) transfers().enqueueUpload(request) else transfers().enqueueDownload(request)
        return transferResult(awaitOwned(sessionId, transfer.id, args.waitMs))
    }

    private suspend fun awaitOwned(sessionId: String, transferId: String, waitMs: Long): RemoteTransferTask {
        val controller = transfers()
        if (controller.tasks.value.none { it.id == transferId && it.sessionId == sessionId }) {
            fail("NOT_FOUND", "当前会话没有这项远程传输")
        }
        return controller.awaitTask(transferId, waitMs)?.takeIf { it.sessionId == sessionId }
            ?: fail("NOT_FOUND", "当前会话没有这项远程传输")
    }

    private fun transferResult(task: RemoteTransferTask): ToolResult {
        val data = buildJsonObject {
            put("state", task.state.name)
            put("transfer_id", task.id)
            put("bytes_done", task.bytesTransferred)
            put("bytes_total", task.bytesTotal)
            put("bytes_per_second", task.bytesPerSecond)
            task.etaSeconds?.let { put("eta_seconds", it) }
            task.errorCode?.let { put("errorCode", it) }
            task.errorMessage?.let { put("error_message", it) }
            task.receipt?.let { receipt ->
                putJsonObject("receipt") {
                    put("mount_path", receipt.mountPath)
                    put("remote_path", receipt.remotePath)
                    put("size_bytes", receipt.sizeBytes)
                    put("sha256", receipt.sha256)
                    put("verification_method", receipt.verificationMethod)
                    put("transfer_id", receipt.transferId)
                    put("deduplicated", receipt.deduplicated)
                    if (task.direction == RemoteTransferDirection.DOWNLOAD) put("local_path", task.targetLabel)
                }
            }
            if (task.state !in setOf(RemoteTransferState.SUCCEEDED, RemoteTransferState.FAILED, RemoteTransferState.CANCELLED)) {
                put("guidance", "已进入传输管理队列，可用 remote_mount_transfer_status 查询进度与校验回执。")
            }
        }
        return result(data, error = task.state in setOf(RemoteTransferState.FAILED, RemoteTransferState.CANCELLED))
    }

    private fun result(data: JsonObject, error: Boolean = false): ToolResult =
        ToolResult(id, data.toString(), status = if (error) "error" else "success", data = data.toString())

    private fun failure(code: String, message: String) = result(buildJsonObject {
        put("errorCode", code)
        put("message", message)
    }, error = true)

    private fun fail(code: String, message: String): Nothing = throw ExternalFolderException(code, message)
}
