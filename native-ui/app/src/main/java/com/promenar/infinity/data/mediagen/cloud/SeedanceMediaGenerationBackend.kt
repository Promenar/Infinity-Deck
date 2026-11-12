package com.promenar.infinity.data.mediagen.cloud

import com.promenar.infinity.data.mediagen.*
import com.promenar.infinity.data.model.CloudMediaApi
import com.promenar.infinity.domain.mediagen.*
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SeedanceMediaGenerationBackend(
    private val models: CloudMediaModelSource,
    private val http: CloudMediaHttp,
    private val inputs: CloudMediaInputSource,
    private val outputs: CloudMediaOutputStore,
) : MediaGenerationBackend {
    override fun handles(channel: MediaChannel): Boolean = models.find(channel.id)?.settings?.api == CloudMediaApi.SEEDANCE
    private fun model(channel: MediaChannel) = requireNotNull(models.find(channel.id)?.takeIf { it.settings.api == CloudMediaApi.SEEDANCE }) { "通道不是方舟媒体模型" }
    override suspend fun estimate(channel: MediaChannel, request: MediaGenerationRequest): Money? = withContext(Dispatchers.IO) {
        val model = model(channel)
        val effective = model.requestWithDefaults(channel, request)
        model.settings.tariff?.cost(estimatedUsage(effective, model.settings.options, inputs))
    }
    override suspend fun submit(channel: MediaChannel, request: MediaGenerationRequest): String = withContext(Dispatchers.IO) {
        val model = model(channel)
        val effective = model.requestWithDefaults(channel, request)
        val resolved = inputs.resolveInputs(effective)
        require(resolved.inputs.none { it.kind == InputKind.VIDEO && !it.location.startsWith("http") && !it.location.startsWith("asset://") }) { "方舟视频参考需要 URL 或素材 ID" }
        require(resolved.inputs.none { it.location.startsWith("file://") }) { "方舟不接受 MiniMax 文件 ID" }
        val body = seedanceCreateBody(model.modelId, resolved, model.settings.options.nativeAudio)
        require(body.toString().toByteArray().size <= 64 * 1024 * 1024) { "方舟请求超过 64 MiB" }
        val result = http.request(model.providerId, cloudApiUrl(model.baseUrl, model.settings.options.createPath ?: TASK_PATH), body)
        val id = result.string("id") ?: throw IOException("方舟创建响应缺少任务 ID")
        CloudTaskTicket(safeTaskId(id), CloudMediaApi.SEEDANCE, model.providerId, channel.id, model.baseUrl, model.settings.options.queryPath ?: "$TASK_PATH/{task_id}", model.settings.options.retrievePath).encode()
    }
    override suspend fun poll(channel: MediaChannel, remoteId: String): RemoteStatus = withContext(Dispatchers.IO) {
        val model = model(channel)
        val ticket = CloudTaskTicket.decode(remoteId)
        ticket.requireBinding(model, channel)
        require(ticket.api == CloudMediaApi.SEEDANCE && ticket.providerId == model.providerId && ticket.channelId == channel.id) { "远端任务不属于此通道" }
        val result = try { http.request(model.providerId, cloudApiUrl(ticket.baseUrl, queryPath(ticket.query, ticket.id)), null) }
        catch (e: CloudHttpException) { if (e.status in setOf(404, 410)) return@withContext RemoteStatus.Failed("方舟任务已过期或不存在"); throw e }
        val parsed = runCatching { parseSeedanceStatus(result) }.getOrElse { return@withContext RemoteStatus.Failed("方舟任务响应格式无效") }
        when (parsed.state) {
            SeedanceRemoteState.QUEUED, SeedanceRemoteState.RUNNING -> RemoteStatus.Running()
            SeedanceRemoteState.FAILED -> RemoteStatus.Failed("方舟任务失败或已过期")
            SeedanceRemoteState.SUCCEEDED -> RemoteStatus.Succeeded(listOf(RemoteOutput(parsed.outputUrl!!, InputKind.VIDEO)),
                parsed.totalTokens?.let { mapOf("total_tokens" to it, "tasks" to 1L) } ?: mapOf("tasks" to 1L))
        }
    }
    override suspend fun download(channel: MediaChannel, request: MediaGenerationRequest, remoteId: String, outputs: List<RemoteOutput>): List<String> = withContext(Dispatchers.IO) {
        val model = model(channel)
        val ticket = CloudTaskTicket.decode(remoteId)
        ticket.requireBinding(model, channel)
        require(ticket.providerId == model.providerId && ticket.channelId == channel.id && ticket.api == CloudMediaApi.SEEDANCE)
        require(request.channelId == channel.id)
        require(outputs.isNotEmpty() && outputs.all { it.kind == InputKind.VIDEO })
        outputs.mapIndexed { index, output ->
            try {
                this@SeedanceMediaGenerationBackend.outputs.save(request, remoteId, index, output)
            } catch (expired: com.promenar.infinity.domain.media.WorkspaceMediaException) {
                if (expired.code != "HTTP_ERROR") throw expired
                val fresh = poll(channel, remoteId) as? RemoteStatus.Succeeded ?: throw expired
                val replacement = fresh.outputs.getOrNull(index) ?: throw expired
                this@SeedanceMediaGenerationBackend.outputs.save(request, remoteId, index, replacement)
            }
        }
    }
    override fun actualCost(channel: MediaChannel, usage: Map<String, Long>): Money? = model(channel).settings.tariff?.cost(usage)
    companion object { private const val TASK_PATH = "/api/v3/contents/generations/tasks" }
}
