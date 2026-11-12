package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.data.security.*
import com.promenar.infinity.domain.media.*
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.InputStream

internal class MemoryComfyStorage : ComfyConfigurationStorage {
    val state = MutableStateFlow(ComfyConfiguration())
    override fun observe() = state
    override suspend fun read() = state.value
    override suspend fun write(configuration: ComfyConfiguration) { state.value = configuration }
}
internal class MemoryComfySecrets : SecretStore {
    private val values = mutableMapOf<SecretId, ByteArray>()
    override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
    override fun get(id: SecretId) = values[id]?.copyOf()
    override fun contains(id: SecretId) = id in values
    override fun remove(id: SecretId) { values.remove(id) }
}
internal class FakeComfyHttp : ComfyHttp {
    var info = JsonObject(emptyMap())
    val calls = mutableListOf<Pair<String, JsonObject?>>()
    val uploads = mutableListOf<Pair<String, String>>()
    var history = JsonObject(emptyMap())
    var queue = JsonObject(emptyMap())
    var onPrompt: suspend () -> Unit = {}
    var failInfo = false
    override suspend fun get(host: ComfyHost, path: String): JsonObject {
        calls += path to null
        return when {
            path == "/object_info" -> { if (failInfo) throw ComfyFailure("无法连接 ComfyUI 主机"); info }
            path.startsWith("/object_info/") -> {
                if (failInfo) throw ComfyFailure("无法连接 ComfyUI 主机")
                // 与 ComfyUI 一致：未知节点类型返回空对象。
                val type = java.net.URLDecoder.decode(path.removePrefix("/object_info/"), Charsets.UTF_8.name())
                info[type]?.let { buildJsonObject { put(type, it) } } ?: JsonObject(emptyMap())
            }
            path.startsWith("/history/") -> history
            path == "/queue" -> queue
            path == "/system_stats" -> buildJsonObject { putJsonObject("system") { put("comfyui_version", "test") } }
            else -> error("未配置假响应")
        }
    }
    override suspend fun post(host: ComfyHost, path: String, body: JsonObject): JsonObject {
        calls += path to body
        onPrompt()
        return buildJsonObject { put("prompt_id", "prompt-${calls.count { it.first == "/prompt" }}") }
    }
    override suspend fun upload(host: ComfyHost, filename: String, mimeType: String, source: InputStream, audio: Boolean): String {
        uploads += filename to source.readBytes().decodeToString()
        return "infinity/$filename"
    }
    override suspend fun download(host: ComfyHost, location: String, save: suspend (InputStream, String?) -> String): String =
        save(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "image/png")
}
internal class FakeComfyMedia : WorkspaceMediaResolver, WorkspaceMediaStore {
    val saved = mutableListOf<String>()
    override suspend fun productionPath(sessionId: String, path: String) = "/$path"
    override suspend fun resolve(sessionId: String, path: String) = MediaResolution.Available(
        MediaRef(path, if (path.endsWith("wav")) "audio/wav" else "image/png", if (path.endsWith("wav")) MediaKind.AUDIO else MediaKind.IMAGE, 3),
        MediaSource.ContentUri("content://fake"))
    override suspend fun open(resolution: MediaResolution.Available) = ByteArrayInputStream("abc".toByteArray())
    override suspend fun save(sessionId: String, payload: MediaPayload, origin: MediaOrigin, mimeTypeHint: String?, suggestedName: String?, targetPath: String?): MediaRef {
        val path = requireNotNull(targetPath)
        when (payload) { is MediaPayload.Stream -> payload.open().use { it.readBytes() }; is MediaPayload.Bytes -> Unit }
        saved += path
        return MediaRef(path, "image/png", MediaKind.IMAGE, 3)
    }
}
internal class ComfyFixture {
    val storage = MemoryComfyStorage()
    val secrets = MemoryComfySecrets()
    val host = ComfyHost("host", "测试主机", "http://comfy.test:8188")
    val graph = Json.parseToJsonElement("""{"1":{"class_type":"Test","inputs":{"prompt":"","image":"","width":32,"height":32,"frames":22,"seed":1}},"2":{"class_type":"Save","inputs":{}}}""").jsonObject
    val info = Json.parseToJsonElement("""{"Test":{"input":{"required":{"prompt":["STRING"],"image":["STRING"]}}},"Save":{"input":{"required":{}},"output_node":true}}""").jsonObject
    val template = ComfyTemplate("test", graph, listOf(
        ComfySlot("1", "prompt", SlotKind.PROMPT), ComfySlot("1", "image", SlotKind.REFERENCE_IMAGES),
        ComfySlot("1", "width", SlotKind.WIDTH), ComfySlot("1", "height", SlotKind.HEIGHT),
        ComfySlot("1", "frames", SlotKind.FRAMES), ComfySlot("1", "seed", SlotKind.SEED),
        ComfySlot("2", "images", SlotKind.OUTPUT)), frameBase = 5, frameStep = 17,
        capabilities = MediaCapabilities(setOf(GenerationMode.TEXT, GenerationMode.REFERENCE), maxImages = 99), hostId = host.id)
    val http = FakeComfyHttp().also { it.info = info }
    val repository = ComfyRepository(storage, ComfyCredentials(secrets), http)
    val media = FakeComfyMedia()
    val backend = ComfyBackend(repository, http, media, media)
    val request = MediaGenerationRequest("comfy:test", GenerationMode.REFERENCE, "提示", listOf(MediaInput("refs/a.png", InputKind.IMAGE, InputRole.REFERENCE)), "source",
        GenerationSpecs(width = 101, height = 129, frames = 22, seed = 42), "session", "productions/test/takes")
    suspend fun prepare() { repository.saveHost(host); repository.defineTemplateByAgent(template) }
}
