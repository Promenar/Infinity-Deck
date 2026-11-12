package com.promenar.infinity.data.mediagen.comfy

import android.content.SharedPreferences
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.URI

const val COMFY_CONFIGURATION_KEY = "comfy_configuration_v1"

@Serializable
enum class ComfyAuthKind { NONE, BEARER, BASIC }

@Serializable
data class ComfyHost(
    val id: String,
    val name: String,
    val baseUrl: String,
    val authKind: ComfyAuthKind = ComfyAuthKind.NONE,
    /** 某些主机通过扩展提供音频上传；字段与路径按主机实际接口配置。 */
    val audioUploadPath: String = "/upload/image",
    val audioUploadField: String = "image",
    val timeoutSeconds: Int = 120,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "主机 id 无效" }
        require(name.isNotBlank() && name.length <= 120) { "主机名称需要 1～120 字符" }
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && !baseUrl.contains('\\') &&
            uri.path.orEmpty().split('/').none { it in setOf(".", "..") }) { "主机地址需要不含凭据、查询和片段的 HTTP(S) 地址" }
        require(audioUploadPath.matches(Regex("/[A-Za-z0-9_/-]+")) && audioUploadPath.split('/').none { it == ".." }) { "音频上传路径无效" }
        require(audioUploadField.matches(Regex("[A-Za-z0-9_]{1,64}"))) { "音频上传字段无效" }
        require(timeoutSeconds in 5..1800) { "超时需要 5～1800 秒" }
    }
    val endpoint: String get() = baseUrl.trimEnd('/')
}

data class ComfyTemplateState(
    val template: ComfyTemplate,
    val preflight: PreflightResult = PreflightResult(PreflightStatus.REVIEW, uncertain = listOf("尚未预检")),
    /** 保存通过预检时的主机 schema 摘要，用于区分初次缺失与主机变化。 */
    val passedFingerprint: String? = null,
    /** 最近一次向主机读取节点信息完成预检的时间（毫秒）；界面与智能体触发的预检都会记录。 */
    val checkedAt: Long? = null,
)
data class ComfyConfiguration(val hosts: List<ComfyHost> = emptyList(), val templates: List<ComfyTemplateState> = emptyList())

/** 注册表包仅依赖此接口；返回全部模板通道，包含待预检的条目。 */
interface ComfyTemplateChannelSource {
    fun observeTemplateChannels(): Flow<List<MediaChannel>>
}

interface ComfyConfigurationStorage {
    fun observe(): Flow<ComfyConfiguration>
    suspend fun read(): ComfyConfiguration
    suspend fun write(configuration: ComfyConfiguration)
}

class PreferenceComfyConfigurationStorage(private val preferences: SharedPreferences) : ComfyConfigurationStorage {
    override fun observe(): Flow<ComfyConfiguration> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == COMFY_CONFIGURATION_KEY || key == null) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { read() }.distinctUntilChanged()

    override suspend fun read(): ComfyConfiguration = withContext(Dispatchers.IO) {
        preferences.getString(COMFY_CONFIGURATION_KEY, null)?.let(ComfyConfigurationCodec::decode) ?: ComfyConfiguration()
    }
    override suspend fun write(configuration: ComfyConfiguration) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString(COMFY_CONFIGURATION_KEY, ComfyConfigurationCodec.encode(configuration)).commit()) { "ComfyUI 配置保存失败" }
    }
}

/** 使用独立持久化记录，保持共享生成契约签名不变。 */
object ComfyConfigurationCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    @Serializable private data class ConfigurationRecord(val version: Int = 2, val hosts: List<ComfyHost>, val templates: List<TemplateStateRecord>)
    @Serializable private data class TemplateStateRecord(val template: TemplateRecord, val status: String, val missing: List<String>, val uncertain: List<String>, val passedFingerprint: String?, val checkedAt: Long? = null)
    @Serializable private data class UploadRecord(val nodeId: String, val input: String)
    @Serializable private data class SlotRecord(val nodeId: String, val input: String, val kind: String, val capacity: Int = 1,
        val uploadTargets: List<UploadRecord> = emptyList(), val outputKind: String? = null, val param: String? = null)
    @Serializable private data class OperationRecord(val label: String)
    @Serializable private data class ParamRecord(val key: String, val label: String, val type: String, val default: JsonPrimitive,
        val options: List<String> = emptyList(), val min: Double? = null, val max: Double? = null, val integer: Boolean = false)
    @Serializable private data class ModelRecord(val nodeId: String, val input: String)
    @Serializable private data class CapabilitiesRecord(val modes: List<String>, val nativeAudio: Boolean = false, val resolutions: List<String> = emptyList(),
        val minDurationSeconds: Int? = null, val maxDurationSeconds: Int? = null, val promptFormat: String = "GENERIC", val referenceOrder: List<String> = emptyList())
    @Serializable private data class TemplateRecord(val id: String, val workflow: JsonObject, val slots: List<SlotRecord>,
        val frameBase: Int = 1, val frameStep: Int = 1, val locked: Boolean = false, val capabilities: CapabilitiesRecord,
        val models: List<ModelRecord> = emptyList(), val hostId: String, val group: String, val name: String = id, val maxFrames: Int? = null,
        val role: String = "GENERATE", val operation: OperationRecord? = null, val params: List<ParamRecord> = emptyList())

    fun encode(configuration: ComfyConfiguration): String {
        val raw = json.encodeToString(ConfigurationRecord(hosts = configuration.hosts,
            templates = configuration.templates.map { state -> TemplateStateRecord(record(state.template), state.preflight.status.name,
                state.preflight.missing, state.preflight.uncertain, state.passedFingerprint, state.checkedAt) }))
        require(raw.toByteArray().size <= 8 * 1024 * 1024) { "ComfyUI 配置超过 8 MiB" }
        return raw
    }
    fun decode(raw: String): ComfyConfiguration {
        require(raw.length <= 8 * 1024 * 1024 && raw.toByteArray().size <= 8 * 1024 * 1024) { "ComfyUI 配置超过 8 MiB" }
        val value = json.decodeFromString<ConfigurationRecord>(raw)
        require(value.version in 1..2) { "ComfyUI 配置版本不受支持" }
        val result = ComfyConfiguration(value.hosts, value.templates.map { ComfyTemplateState(template(it.template),
            PreflightResult(PreflightStatus.valueOf(it.status), it.missing, it.uncertain), it.passedFingerprint, it.checkedAt?.takeIf { at -> at > 0 }) })
        require(result.hosts.size <= 100 && result.templates.size <= 500 && result.hosts.distinctBy { it.id }.size == result.hosts.size &&
            result.templates.distinctBy { it.template.id }.size == result.templates.size) { "主机或模板数量无效，或 id 重复" }
        require(result.templates.all { state -> result.hosts.any { it.id == state.template.hostId } }) { "模板引用的主机不存在" }
        return result
    }
    fun encodeTemplate(template: ComfyTemplate): JsonObject = json.encodeToJsonElement(record(template)).jsonObject
    fun decodeTemplate(raw: JsonObject): ComfyTemplate = try {
        template(json.decodeFromJsonElement<TemplateRecord>(raw))
    } catch (e: SerializationException) {
        // 只提取字段路径，禁止将包含完整工作流的解码日志返回会话。
        val path = Regex("path: \\$(\\.[A-Za-z0-9_\\[\\].]+)").find(e.message.orEmpty())?.groupValues?.get(1)?.removePrefix(".")
        throw IllegalArgumentException("${path ?: "template 字段"}: 字段缺失、包含未知字段或类型无效")
    }
    private fun record(t: ComfyTemplate): TemplateRecord {
        val c = t.channel().capabilities
        return TemplateRecord(t.id, t.workflow, t.slots.map { SlotRecord(it.nodeId, it.input, it.kind.name, it.capacity,
            it.uploadTargets.map { target -> UploadRecord(target.nodeId, target.input) }, it.outputKind?.name, it.param) }, t.frameBase, t.frameStep, t.locked,
            CapabilitiesRecord(c.modes.map { it.name }, c.nativeAudio, c.resolutions, c.minDurationSeconds, c.maxDurationSeconds, c.promptFormat.name, c.referenceOrder),
            t.models.map { ModelRecord(it.nodeId, it.input) }, t.hostId, t.group.name, t.name, t.maxFrames, t.role.name,
            t.operation?.let { OperationRecord(it.label) }, t.params.map { ParamRecord(it.key, it.label, it.type.name, it.default, it.options, it.min, it.max, it.integer) })
    }
    private fun template(r: TemplateRecord): ComfyTemplate = ComfyTemplate(r.id, r.workflow,
        r.slots.map { ComfySlot(it.nodeId, it.input, SlotKind.valueOf(it.kind), it.capacity,
            it.uploadTargets.map { target -> ComfyUploadTarget(target.nodeId, target.input) }, it.outputKind?.let(InputKind::valueOf), it.param) },
        r.frameBase, r.frameStep, r.locked, MediaCapabilities(r.capabilities.modes.map(GenerationMode::valueOf).toSet(),
            nativeAudio = r.capabilities.nativeAudio, resolutions = r.capabilities.resolutions,
            minDurationSeconds = r.capabilities.minDurationSeconds, maxDurationSeconds = r.capabilities.maxDurationSeconds,
            promptFormat = PromptFormat.valueOf(r.capabilities.promptFormat), referenceOrder = r.capabilities.referenceOrder),
        r.models.map { ComfyModelSlot(it.nodeId, it.input) }, r.hostId, MediaGroup.valueOf(r.group), r.name, r.maxFrames,
        enumValue<TemplateRole>(r.role, "role"), r.operation?.let { TemplateOperation(it.label) }, r.params.mapIndexed { index, p ->
            try { TemplateParam(p.key, p.label, enumValue<TemplateParamType>(p.type, "params[$index].type"), p.default, p.options, p.min, p.max, p.integer) }
            catch (e: IllegalArgumentException) {
                val message = e.message.orEmpty()
                val indexed = if (message.startsWith("params.key:")) "params[$index].key" + message.removePrefix("params.key")
                    else message.replaceFirst("params.${p.key}.", "params[$index].")
                throw IllegalArgumentException(indexed)
            }
        })
        .let { it.copy(capabilities = it.channel().capabilities) }

    private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
        enumValues<T>().firstOrNull { it.name == value } ?: throw IllegalArgumentException("$field: 枚举值无效")
}
