package com.promenar.infinity.data.mediagen.cloud

import com.promenar.infinity.data.model.CloudMediaApi
import com.promenar.infinity.data.proxy.applyOptionalBuiltInProxy
import com.promenar.infinity.domain.mediagen.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Google AI Studio（Gemini TTS / Lyria 3 / 3.5）与 OpenAI 兼容语音（/v1/audio/speech）云端媒体后端：
 * - 既实现 [MediaGenerationBackend] 接入持久队列（供视频制作面板与后台任务使用）；
 * - 也暴露同步直调方法 [synthesizeSpeech] / [synthesizeMusic] 供会话气泡“朗读”与通用对话工具低延迟使用。
 */
class GeminiMediaGenerationBackend(
    private val models: CloudMediaModelSource,
    private val http: CloudMediaHttp,
    private val inputs: CloudMediaInputSource,
    private val outputs: CloudMediaOutputStore,
    private val stagingDir: File,
) : MediaGenerationBackend {

    private val apis = setOf(
        CloudMediaApi.GEMINI_TTS,
        CloudMediaApi.GEMINI_LYRIA,
        CloudMediaApi.OPENAI_SPEECH,
    )

    override fun handles(channel: MediaChannel): Boolean = models.find(channel.id)?.settings?.api in apis

    private fun model(channel: MediaChannel): CloudMediaModel =
        requireNotNull(models.find(channel.id)?.takeIf { it.settings.api in apis }) { "通道不是受支持的云端语音或音乐模型" }

    override suspend fun estimate(channel: MediaChannel, request: MediaGenerationRequest): Money? = withContext(Dispatchers.IO) {
        val model = model(channel)
        val effective = model.requestWithDefaults(channel, request)
        model.settings.tariff?.cost(estimatedUsage(effective, model.settings.options, inputs))
    }

    override suspend fun submit(channel: MediaChannel, request: MediaGenerationRequest): String = withContext(Dispatchers.IO) {
        val model = model(channel)
        val api = requireNotNull(model.settings.api)
        val effective = model.requestWithDefaults(channel, request)
        val resolved = inputs.resolveInputs(effective)
        val result = when (api) {
            CloudMediaApi.GEMINI_TTS -> executeGeminiTts(model, resolved)
            CloudMediaApi.GEMINI_LYRIA -> executeGeminiLyria(model, resolved)
            CloudMediaApi.OPENAI_SPEECH -> executeOpenAiSpeech(model, resolved)
            else -> error("不支持的云端音频协议：$api")
        }
        val token = stageAudio(result)
        val usage = buildMap {
            putAll(result.usage)
            put("tasks", 1L)
            if (effective.mode == GenerationMode.SPEECH) {
                put("usage_characters", speechBillableCharacters(effective.prompt))
            }
        }
        CloudTaskTicket(
            id = token,
            api = api,
            providerId = model.providerId,
            channelId = channel.id,
            baseUrl = model.baseUrl,
            query = "/staged",
            retrieve = result.mimeType,
            usage = usage,
            audioFormat = result.extension,
        ).encode()
    }

    override suspend fun poll(channel: MediaChannel, remoteId: String): RemoteStatus = withContext(Dispatchers.IO) {
        val model = model(channel)
        val ticket = CloudTaskTicket.decode(remoteId)
        ticket.requireBinding(model, channel)
        val staged = stagedFile(ticket.id, ticket.audioFormat)
        if (!staged.isFile || staged.length() <= 0L) {
            return@withContext RemoteStatus.Failed("暂存的音频产物已失效，请重新生成", retryable = false)
        }
        RemoteStatus.Succeeded(
            outputs = listOf(RemoteOutput("staged:${ticket.id}", InputKind.AUDIO)),
            usage = ticket.usage,
        )
    }

    override suspend fun download(
        channel: MediaChannel,
        request: MediaGenerationRequest,
        remoteId: String,
        outputs: List<RemoteOutput>,
    ): List<String> = withContext(Dispatchers.IO) {
        val model = model(channel)
        val ticket = CloudTaskTicket.decode(remoteId)
        ticket.requireBinding(model, channel)
        require(outputs.isNotEmpty() && outputs.all { it.kind == InputKind.AUDIO })
        val staged = stagedFile(ticket.id, ticket.audioFormat)
        if (!staged.isFile) throw IOException("暂存音频文件缺失")
        val bytes = staged.readBytes()
        val mime = ticket.retrieve.takeIf { it.startsWith("audio/") } ?: mimeForExtension(ticket.audioFormat)
        val saved = outputs.mapIndexed { index, _ ->
            this@GeminiMediaGenerationBackend.outputs.saveBytes(
                request = request,
                remoteId = remoteId,
                index = index,
                kind = InputKind.AUDIO,
                bytes = bytes,
                mimeType = mime,
                extension = ticket.audioFormat,
            )
        }
        runCatching { staged.delete() }
        saved
    }

    override fun actualCost(channel: MediaChannel, usage: Map<String, Long>): Money? = model(channel).settings.tariff?.cost(usage)

    /** 直接同步调用 Gemini TTS / OpenAI Speech / MiniMax Speech（低延迟直出，供气泡朗读与 generate_speech 复用）。 */
    suspend fun synthesizeSpeechDirect(
        model: CloudMediaModel,
        text: String,
        voiceOverride: String? = null,
        speakers: List<SpeechSpeakerVoice> = emptyList(),
        styleInstruction: String? = null,
        formatOverride: String? = null,
    ): SynthesizedAudioResult = withContext(Dispatchers.IO) {
        when (val api = model.settings.api) {
            CloudMediaApi.GEMINI_TTS -> {
                val voice = voiceOverride?.trim()?.takeIf { it.isNotEmpty() }
                    ?: model.settings.options.voiceId.trim().ifEmpty { "Kore" }
                val body = geminiTtsCreateBody(
                    prompt = text,
                    voiceName = voice,
                    speakers = speakers,
                    styleInstruction = styleInstruction,
                )
                val cleanModel = model.modelId.removePrefix("models/")
                val path = model.settings.options.createPath ?: "/v1beta/models/$cleanModel:generateContent"
                val response = http.requestLarge(model.providerId, cloudApiUrl(model.baseUrl, path), body)
                parseGeminiTtsResponse(response)
            }
            CloudMediaApi.OPENAI_SPEECH -> {
                val voice = voiceOverride?.trim()?.takeIf { it.isNotEmpty() }
                    ?: model.settings.options.voiceId.trim().ifEmpty { "alloy" }
                val format = formatOverride?.trim()?.lowercase()?.takeIf { it in setOf("mp3", "wav", "flac") }
                    ?: model.settings.options.audioFormat
                val body = openAiSpeechBody(model.modelId, text, voice = voice, format = format, instructions = styleInstruction)
                val path = model.settings.options.createPath ?: "/v1/audio/speech"
                val binary = http.requestBinary(model.providerId, cloudApiUrl(model.baseUrl, path), body)
                normalizeAudioPayload(binary.bytes, binary.contentType ?: mimeForExtension(format), usage = mapOf("tasks" to 1L))
            }
            CloudMediaApi.MINIMAX_SPEECH -> {
                val voice = voiceOverride?.trim()?.takeIf { it.isNotEmpty() } ?: model.settings.options.voiceId.trim()
                require(voice.isNotEmpty()) { "MiniMax 语音模型尚未配置默认音色 ID，请先在媒体设置中填写音色 ID" }
                val format = formatOverride?.trim()?.lowercase()?.takeIf { it in setOf("mp3", "wav", "flac") }
                    ?: model.settings.options.audioFormat
                val opts = model.settings.options.copy(voiceId = voice, audioFormat = format)
                val req = MediaGenerationRequest(
                    channelId = "direct",
                    mode = GenerationMode.SPEECH,
                    prompt = text,
                    inputs = emptyList(),
                    sourceRef = "direct-speech",
                )
                val body = miniMaxSpeechBody(model.modelId, req, opts)
                val createPath = opts.createPath ?: "/v1/t2a_async_v2"
                val queryTemplate = opts.queryPath ?: "/v1/query/t2a_async_query_v2"
                val created = http.request(model.providerId, cloudApiUrl(model.baseUrl, createPath), body)
                val taskId = created.string("task_id") ?: throw IOException("MiniMax 语音创建响应缺少任务 ID")
                var fileId: String? = null
                repeat(90) {
                    delay(1_200L)
                    val polled = http.request(model.providerId, cloudApiUrl(model.baseUrl, queryPath(queryTemplate, taskId)), null)
                    when (val status = parseMiniMaxStatus(polled, v2 = false, speech = true)) {
                        is RemoteStatus.Succeeded -> {
                            fileId = status.outputs.firstOrNull()?.location
                            return@repeat
                        }
                        is RemoteStatus.Failed -> throw IOException(status.message)
                        is RemoteStatus.Running -> Unit
                    }
                }
                val resolvedLoc = fileId ?: throw IOException("MiniMax 语音合成等待超时")
                val downloadUrl = if (resolvedLoc.startsWith("minimax-file:")) {
                    val fid = safeTaskId(resolvedLoc.removePrefix("minimax-file:"))
                    val sep = if ('?' in opts.retrievePath) "&" else "?"
                    val fileResp = http.request(model.providerId, cloudApiUrl(model.baseUrl, "${opts.retrievePath}${sep}file_id=$fid"), null)
                    (fileResp["file"] as? kotlinx.serialization.json.JsonObject)?.string("download_url")
                        ?: throw IOException("MiniMax 文件响应缺少下载地址")
                } else {
                    resolvedLoc
                }
                val bytes = downloadMiniMaxAudio(downloadUrl)
                normalizeAudioPayload(bytes, mimeForExtension(format), usage = mapOf("tasks" to 1L))
            }
            else -> throw IllegalArgumentException("所选模型未绑定受支持的云端语音协议（$api）")
        }
    }

    /** 直接同步调用 Lyria 3 / 3.5（供通用对话工具 generate_music 使用）。 */
    suspend fun synthesizeMusicDirect(
        model: CloudMediaModel,
        prompt: String,
        inlineImageDataUrls: List<String> = emptyList(),
        durationSeconds: Int? = null,
    ): SynthesizedAudioResult = withContext(Dispatchers.IO) {
        require(model.settings.api == CloudMediaApi.GEMINI_LYRIA) { "所选模型未绑定受支持的云端音乐协议" }
        executeGeminiLyriaRaw(model, prompt, inlineImageDataUrls, durationSeconds)
    }

    private suspend fun executeGeminiTts(model: CloudMediaModel, request: MediaGenerationRequest): SynthesizedAudioResult {
        val body = geminiTtsBody(request, model.settings.options)
        val cleanModel = model.modelId.removePrefix("models/")
        val path = model.settings.options.createPath ?: "/v1beta/models/$cleanModel:generateContent"
        val response = http.requestLarge(model.providerId, cloudApiUrl(model.baseUrl, path), body)
        return parseGeminiTtsResponse(response)
    }

    private suspend fun executeGeminiLyria(model: CloudMediaModel, request: MediaGenerationRequest): SynthesizedAudioResult {
        val images = extractLyriaInlineImages(request)
        val duration = request.specs.durationSeconds ?: request.specs.durationSecondsExact?.toInt()
        return executeGeminiLyriaRaw(model, request.prompt, images, duration)
    }

    private suspend fun executeGeminiLyriaRaw(
        model: CloudMediaModel,
        prompt: String,
        inlineImageDataUrls: List<String>,
        durationSeconds: Int?,
    ): SynthesizedAudioResult {
        val cleanModel = model.modelId.removePrefix("models/")
        val primaryPath = model.settings.options.createPath ?: "/v1beta/interactions"
        val interactionsBody = geminiLyriaInteractionsBody(cleanModel, prompt, inlineImageDataUrls, durationSeconds)
        return try {
            val response = http.requestLarge(model.providerId, cloudApiUrl(model.baseUrl, primaryPath), interactionsBody)
            parseGeminiLyriaResponse(response)
        } catch (httpError: CloudHttpException) {
            if (httpError.status !in setOf(400, 404, 405, 501) || primaryPath.endsWith(":generateContent")) {
                throw httpError
            }
            // 部分兼容网关或变体仅暴露 :generateContent，自动平滑回退一次。
            val fallbackPath = "/v1beta/models/$cleanModel:generateContent"
            val fallbackBody = geminiLyriaGenerateContentBody(prompt, inlineImageDataUrls, durationSeconds)
            val fallbackResp = http.requestLarge(model.providerId, cloudApiUrl(model.baseUrl, fallbackPath), fallbackBody)
            parseGeminiLyriaResponse(fallbackResp)
        }
    }

    private suspend fun executeOpenAiSpeech(model: CloudMediaModel, request: MediaGenerationRequest): SynthesizedAudioResult {
        val voice = request.params["voice"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.trim()?.takeIf { it.isNotEmpty() } ?: model.settings.options.voiceId.trim().ifEmpty { "alloy" }
        val style = request.params["style"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.trim()?.takeIf { it.isNotEmpty() }
        val format = model.settings.options.audioFormat
        val body = openAiSpeechBody(model.modelId, request.prompt, voice = voice, format = format, instructions = style)
        val path = model.settings.options.createPath ?: "/v1/audio/speech"
        val binary = http.requestBinary(model.providerId, cloudApiUrl(model.baseUrl, path), body)
        return normalizeAudioPayload(binary.bytes, binary.contentType ?: mimeForExtension(format), usage = mapOf("tasks" to 1L))
    }

    private val audioDownloadClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .applyOptionalBuiltInProxy(publicTargetOnly = true)
            .build()
    }

    private suspend fun downloadMiniMaxAudio(url: String): ByteArray = withContext(Dispatchers.IO) {
        val uri = java.net.URI(url)
        require(uri.scheme in setOf("https", "http") && uri.host != null) { "音频下载地址无效" }
        val request = okhttp3.Request.Builder().url(url).get().build()
        audioDownloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw CloudHttpException(response.code)
            val body = response.body ?: throw IOException("音频下载响应为空")
            val maxBytes = 32 * 1024 * 1024
            val out = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > maxBytes) throw IOException("音频下载文件超过大小上限")
                    out.write(buffer, 0, count)
                }
            }
            out.toByteArray()
        }
    }

    private fun stageAudio(result: SynthesizedAudioResult): String {
        if (!stagingDir.exists()) stagingDir.mkdirs()
        sweepExpiredStagingFiles()
        val token = "ga_${UUID.randomUUID().toString().replace("-", "")}"
        val file = stagedFile(token, result.extension)
        file.writeBytes(result.bytes)
        return token
    }

    private fun stagedFile(token: String, extension: String): File {
        val safeExt = extension.lowercase().takeIf { it in setOf("wav", "mp3", "flac", "ogg", "m4a") } ?: "wav"
        return File(stagingDir, "${safeTaskId(token)}.$safeExt")
    }

    private fun sweepExpiredStagingFiles() {
        val cutoff = System.currentTimeMillis() - 6L * 3600_000L
        stagingDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }

    private fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        else -> "audio/mpeg"
    }
}
