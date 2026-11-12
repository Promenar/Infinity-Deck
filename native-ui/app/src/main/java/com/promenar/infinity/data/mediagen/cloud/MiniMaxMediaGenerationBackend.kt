package com.promenar.infinity.data.mediagen.cloud

import com.promenar.infinity.data.model.CloudMediaApi
import com.promenar.infinity.domain.mediagen.*
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class MiniMaxMediaGenerationBackend(
    private val models: CloudMediaModelSource,
    private val http: CloudMediaHttp,
    private val inputs: CloudMediaInputSource,
    private val outputs: CloudMediaOutputStore,
) : MediaGenerationBackend {
    private val apis = setOf(CloudMediaApi.MINIMAX_VIDEO_V1, CloudMediaApi.MINIMAX_VIDEO_V2, CloudMediaApi.MINIMAX_SPEECH)
    override fun handles(channel: MediaChannel): Boolean = models.find(channel.id)?.settings?.api in apis
    private fun model(channel: MediaChannel) = requireNotNull(models.find(channel.id)?.takeIf { it.settings.api in apis }) { "通道不是 MiniMax 媒体模型" }

    override suspend fun estimate(channel: MediaChannel, request: MediaGenerationRequest): Money? = withContext(Dispatchers.IO) {
        val model = model(channel)
        val effective = model.requestWithDefaults(channel, request)
        model.settings.tariff?.cost(estimatedUsage(effective, model.settings.options, inputs))
    }
    override suspend fun submit(channel: MediaChannel, request: MediaGenerationRequest): String = withContext(Dispatchers.IO) {
        val model = model(channel)
        val settings = model.settings
        val api = requireNotNull(settings.api)
        val effective = model.requestWithDefaults(channel, request)
        val speech = api == CloudMediaApi.MINIMAX_SPEECH
        val v2 = api == CloudMediaApi.MINIMAX_VIDEO_V2
        // H3 V2 原生音频随视频生成，官方接口没有单独关闭参数。
        require(settings.options.nativeAudio != false || !v2) { "MiniMax H3 接口不支持关闭原生音频" }
        val resolved = inputs.resolveInputs(effective)
        val body = if (speech) miniMaxSpeechBody(model.modelId, resolved, settings.options)
            else miniMaxVideoBody(model.modelId, resolved, api)
        require(body.toString().toByteArray().size <= 64 * 1024 * 1024) { "MiniMax 请求超过内联大小上限" }
        val create = settings.options.createPath ?: when (api) {
            CloudMediaApi.MINIMAX_SPEECH -> "/v1/t2a_async_v2"
            CloudMediaApi.MINIMAX_VIDEO_V2 -> "/v2/video_generation"
            else -> "/v1/video_generation"
        }
        val query = settings.options.queryPath ?: when (api) {
            CloudMediaApi.MINIMAX_SPEECH -> "/v1/query/t2a_async_query_v2"
            CloudMediaApi.MINIMAX_VIDEO_V2 -> "/v2/query/video_generation/{task_id}"
            else -> "/v1/query/video_generation"
        }
        val result = http.request(model.providerId, cloudApiUrl(model.baseUrl, create), body)
        val id = result.string("task_id") ?: throw IOException("MiniMax 创建响应缺少任务 ID")
        // V1 查询不返回时长，使用已明确提交的规格；语音使用创建响应的实际计费字符数。
        val known = buildMap {
            put("tasks", 1L)
            put("input_image_count", effective.inputs.count { it.kind == InputKind.IMAGE }.toLong())
            if (effective.inputs.none { it.kind == InputKind.VIDEO }) put("input_seconds", 0L)
            if (effective.inputs.none { it.kind == InputKind.AUDIO }) put("input_audio_seconds", 0L)
            if (api == CloudMediaApi.MINIMAX_VIDEO_V1) effective.specs.durationSeconds?.let { put("output_seconds", it.toLong()) }
            if (speech) (result["usage_characters"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.let { put("usage_characters", it) }
        }
        CloudTaskTicket(safeTaskId(id), api, model.providerId, channel.id, model.baseUrl, query, settings.options.retrievePath, known, settings.options.audioFormat).encode()
    }
    override suspend fun poll(channel: MediaChannel, remoteId: String): RemoteStatus = withContext(Dispatchers.IO) {
        val model = model(channel)
        val ticket = CloudTaskTicket.decode(remoteId)
        ticket.requireBinding(model, channel)
        require(ticket.api in apis && ticket.providerId == model.providerId && ticket.channelId == channel.id) { "远端任务不属于此通道" }
        val result = try { http.request(model.providerId, cloudApiUrl(ticket.baseUrl, queryPath(ticket.query, ticket.id)), null) }
        catch (e: CloudHttpException) { if (e.status in setOf(404, 410)) return@withContext RemoteStatus.Failed("MiniMax 任务已过期或不存在"); throw e }
        catch (e: CloudApiException) { return@withContext RemoteStatus.Failed("MiniMax 接口错误：${e.code}", e.code in setOf(1000, 1001, 1002)) }
        when (val status = parseMiniMaxStatus(result, ticket.api == CloudMediaApi.MINIMAX_VIDEO_V2, ticket.api == CloudMediaApi.MINIMAX_SPEECH)) {
            is RemoteStatus.Succeeded -> status.copy(usage = ticket.usage + status.usage)
            else -> status
        }
    }
    override suspend fun download(channel: MediaChannel, request: MediaGenerationRequest, remoteId: String, outputs: List<RemoteOutput>): List<String> = withContext(Dispatchers.IO) {
        val model = model(channel)
        val ticket = CloudTaskTicket.decode(remoteId)
        ticket.requireBinding(model, channel)
        require(ticket.api in apis && ticket.providerId == model.providerId && ticket.channelId == channel.id && request.channelId == channel.id && outputs.isNotEmpty())
        val speech = ticket.api == CloudMediaApi.MINIMAX_SPEECH
        require(outputs.all { it.kind == if (speech) InputKind.AUDIO else InputKind.VIDEO })
        outputs.mapIndexed { index, original ->
            suspend fun resolveFile(): RemoteOutput {
                if (!original.location.startsWith("minimax-file:")) return original
                val fileId = safeTaskId(original.location.removePrefix("minimax-file:"))
                val separator = if ('?' in ticket.retrieve) "&" else "?"
                val response = http.request(model.providerId, cloudApiUrl(ticket.baseUrl, "${ticket.retrieve}${separator}file_id=$fileId"), null)
                val url = (response["file"] as? JsonObject)?.string("download_url") ?: throw IOException("MiniMax 文件响应缺少下载地址")
                return original.copy(location = url)
            }
            try {
                this@MiniMaxMediaGenerationBackend.outputs.saveWithFormat(request, remoteId, index, resolveFile(), if (speech) ticket.audioFormat else "mp4")
            } catch (expired: com.promenar.infinity.domain.media.WorkspaceMediaException) {
                // 文件接口可以刷新限时链接；其它网络策略与存储错误继续交给队列。
                if (expired.code != "HTTP_ERROR") throw expired
                val replacement = if (original.location.startsWith("minimax-file:")) resolveFile()
                    else (poll(channel, remoteId) as? RemoteStatus.Succeeded)?.outputs?.getOrNull(index) ?: throw expired
                this@MiniMaxMediaGenerationBackend.outputs.saveWithFormat(request, remoteId, index, replacement, if (speech) ticket.audioFormat else "mp4")
            }
        }
    }
    override fun actualCost(channel: MediaChannel, usage: Map<String, Long>): Money? = model(channel).settings.tariff?.cost(usage)
}
