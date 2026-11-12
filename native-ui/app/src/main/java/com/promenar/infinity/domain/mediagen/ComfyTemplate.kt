package com.promenar.infinity.domain.mediagen

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.security.MessageDigest

enum class SlotKind { PROMPT, REFERENCE_IMAGES, FIRST_FRAME, LAST_FRAME, REFERENCE_AUDIO, INPUT_VIDEO, PARAM, DURATION, WIDTH, HEIGHT, FRAMES, SEED, OUTPUT }

enum class TemplateRole { GENERATE, DERIVE }
data class TemplateOperation(val label: String) {
    init { require(label.isNotBlank() && label.length <= 20) { "operation.label: 需要 1～20 字符" } }
}
@Serializable
enum class TemplateParamType { ENUM, NUMBER, BOOLEAN }
@Serializable
data class TemplateParam(
    val key: String,
    val label: String,
    val type: TemplateParamType,
    val default: JsonPrimitive,
    val options: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val integer: Boolean = false,
) {
    init {
        require(key.matches(Regex("[a-z][a-z0-9_]{0,31}"))) { "params.key: 需要小写字母开头的 1～32 位字母、数字或下划线" }
        require(label.isNotBlank() && label.length <= 40) { "params.$key.label: 需要 1～40 字符" }
        require(if (type == TemplateParamType.ENUM) options.size in 2..20 && options.distinct().size == options.size else options.isEmpty()) { "params.$key.options: ENUM 需要 2～20 个不重复选项，其它类型必须为空" }
        require(type == TemplateParamType.NUMBER || (min == null && max == null && !integer)) { "params.$key.min/max/integer: 只用于 NUMBER" }
        require((min == null || min.isFinite()) && (max == null || max.isFinite()) && (min == null || max == null || min <= max)) { "params.$key.min/max: 需要有限且有序的范围" }
        validateValue(default, "params.$key.default")
    }

    internal fun validateValue(value: JsonPrimitive, field: String = "params.$key") {
        when (type) {
            TemplateParamType.ENUM -> {
                require(value.isString) { "$field: 需要字符串" }
                require(value.content in options) { "$field: 不在 options 内" }
            }
            TemplateParamType.BOOLEAN -> require(!value.isString && value.booleanOrNull != null) { "$field: 需要布尔值" }
            TemplateParamType.NUMBER -> {
                val number = value.takeUnless { it.isString }?.content?.toBigDecimalOrNull()
                require(number != null && value.doubleOrNull?.isFinite() == true) { "$field: 需要有限数值" }
                require(min == null || number >= BigDecimal.valueOf(min)) { "$field: 小于 min" }
                require(max == null || number <= BigDecimal.valueOf(max)) { "$field: 大于 max" }
                require(!integer || number.stripTrailingZeros().scale() <= 0) { "$field: 需要整数" }
            }
        }
    }

    internal fun workflowValue(value: JsonPrimitive): JsonPrimitive {
        validateValue(value)
        return if (type == TemplateParamType.NUMBER && integer)
            Json.parseToJsonElement(value.content.toBigDecimal().toBigIntegerExact().toString()).jsonPrimitive else value
    }
}

/** 连线槽位对应的上传文件输入；显式指定，避免根据节点名猜测。 */
data class ComfyUploadTarget(val nodeId: String, val input: String) {
    init { require(nodeId.isNotBlank() && input.isNotBlank()) { "上传目标需要节点和输入名" } }
}
data class ComfyModelSlot(val nodeId: String, val input: String) {
    init { require(nodeId.isNotBlank() && input.isNotBlank()) { "模型槽位需要节点和输入名" } }
}
data class ComfySlot(
    val nodeId: String,
    val input: String,
    val kind: SlotKind,
    val capacity: Int = 1,
    val uploadTargets: List<ComfyUploadTarget> = emptyList(),
    val outputKind: InputKind? = null,
    val param: String? = null,
) {
    init {
        require(nodeId.isNotBlank() && input.isNotBlank() && capacity in 1..100) { "slots.nodeId/input/capacity: 节点、输入名或容量无效" }
        require(capacity == 1 || kind in setOf(SlotKind.REFERENCE_IMAGES, SlotKind.REFERENCE_AUDIO)) { "slots.capacity: 只有参考槽位支持可变长容量" }
        if (capacity > 1) {
            val start = Regex("^(.*?)([0-9]+)$").matchEntire(input)?.groupValues?.get(2)?.toIntOrNull()
            require(start != null && start <= Int.MAX_VALUE - capacity) { "可变长槽位需要可递增的数字后缀" }
        }
        require(uploadTargets.isEmpty() || (kind in COMFY_MEDIA_SLOTS && uploadTargets.size == capacity)) { "slots.uploadTargets: 数量必须等于媒体槽位容量" }
        require(outputKind == null || kind == SlotKind.OUTPUT) { "slots.outputKind: 只用于 OUTPUT 槽位" }
        require(if (kind == SlotKind.PARAM) !param.isNullOrBlank() else param == null) { "slots.param: PARAM 必须指定参数 key，其它槽位必须为空" }
    }
    fun names(): List<String> {
        if (capacity == 1) return listOf(input)
        val match = requireNotNull(Regex("^(.*?)([0-9]+)$").matchEntire(input))
        val start = match.groupValues[2].toInt()
        return (0 until capacity).map { match.groupValues[1] + (start + it) }
    }
}
internal val COMFY_MEDIA_SLOTS = setOf(SlotKind.REFERENCE_IMAGES, SlotKind.FIRST_FRAME, SlotKind.LAST_FRAME, SlotKind.REFERENCE_AUDIO, SlotKind.INPUT_VIDEO)

data class ComfyTemplate(
    val id: String,
    val workflow: JsonObject,
    val slots: List<ComfySlot>,
    val frameBase: Int = 1,
    val frameStep: Int = 1,
    val locked: Boolean = false,
    val capabilities: MediaCapabilities? = null,
    val models: List<ComfyModelSlot> = emptyList(),
    val hostId: String = "",
    val group: MediaGroup = MediaGroup.IMAGE,
    val name: String = id,
    val maxFrames: Int? = null,
    val role: TemplateRole = TemplateRole.GENERATE,
    val operation: TemplateOperation? = null,
    val params: List<TemplateParam> = emptyList(),
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "模板 id 只支持字母、数字、下划线和连字符" }
        require(frameBase > 0 && frameStep > 0 && (maxFrames == null || maxFrames >= frameBase)) { "frameBase/frameStep/maxFrames: 帧数约束无效" }
        require(name.isNotBlank() && name.length <= 120) { "name: 模板名称需要 1～120 字符" }
        require(role != TemplateRole.DERIVE || operation != null) { "operation: DERIVE 必须声明操作" }
        require(role != TemplateRole.GENERATE || operation == null) { "operation: GENERATE 不能声明操作" }
        require(role != TemplateRole.DERIVE || slots.count { it.kind == SlotKind.INPUT_VIDEO } == 1) { "slots.INPUT_VIDEO: DERIVE 必须有且只有一个来源视频槽位" }
        require(params.size <= 6) { "params: 最多 6 项" }
        require(params.distinctBy { it.key }.size == params.size) { "params.key: 不能重复" }
        slots.filter { it.kind == SlotKind.PARAM }.forEach { slot ->
            require(params.any { it.key == slot.param }) { "slots.param: 未声明参数 ${slot.param}" }
        }
        params.forEach { param ->
            require(slots.count { it.kind == SlotKind.PARAM && it.param == param.key } == 1) { "params.${param.key}: 必须有且只有一个 PARAM 槽位" }
        }
        require(slots.flatMap { s -> s.names().map { s.nodeId to it } }.distinct().size == slots.sumOf { it.capacity }) { "slots: 槽位不能重叠" }
        val uploadInputs = slots.filter { it.kind in COMFY_MEDIA_SLOTS }.flatMap { slot ->
            if (slot.uploadTargets.isEmpty()) slot.names().map { ComfyUploadTarget(slot.nodeId, it) } else slot.uploadTargets
        }
        require(uploadInputs.distinct().size == uploadInputs.size) { "媒体槽位不能共享上传文件输入" }
    }
    val channelId: String get() = "comfy:$id"
    fun channel(): MediaChannel {
        val declared = requireNotNull(capabilities) { "capabilities: 模板需要声明通道能力" }
        require(declared.modes.isNotEmpty()) { "capabilities.modes: 至少需要一种生成模式" }
        return MediaChannel(channelId, group, declared.copy(
            maxImages = slots.filter { it.kind == SlotKind.REFERENCE_IMAGES }.sumOf { it.capacity },
            maxVideos = if (role == TemplateRole.DERIVE) 1 else 0,
            maxAudio = slots.filter { it.kind == SlotKind.REFERENCE_AUDIO }.sumOf { it.capacity },
            characterReferenceMode = CharacterReferenceMode.SEPARATE,
        ))
    }
    /** 会话审批通过后的替换保持锁定，模板身份由稳定 id 约束。 */
    fun updateByAgent(replacement: ComfyTemplate): ComfyTemplate {
        require(replacement.id == id) { "模板 id 不可修改" }
        return replacement.copy(locked = true)
    }
}

enum class PreflightStatus { PASS, MISSING, REVIEW }
data class PreflightResult(val status: PreflightStatus, val missing: List<String> = emptyList(), val uncertain: List<String> = emptyList())

fun ComfyTemplate.resolveParams(overrides: Map<String, JsonPrimitive>): Map<String, JsonPrimitive> {
    overrides.keys.forEach { key -> require(params.any { it.key == key }) { "params.$key: 未知参数 key" } }
    return params.associate { param ->
        val value = overrides[param.key] ?: param.default
        param.validateValue(value)
        param.key to value
    }
}

/** 对象键递归排序；槽位数组保留媒体输入分配顺序。 */
fun ComfyTemplate.fingerprint(): String {
    val value = buildJsonObject {
        put("workflow", workflow)
        put("slots", JsonArray(slots.map { slot -> buildJsonObject {
            put("nodeId", slot.nodeId); put("input", slot.input); put("kind", slot.kind.name); put("capacity", slot.capacity)
            put("outputKind", slot.outputKind?.let { JsonPrimitive(it.name) } ?: JsonNull)
            put("param", slot.param?.let(::JsonPrimitive) ?: JsonNull)
            put("uploadTargets", JsonArray(slot.uploadTargets.map { buildJsonObject { put("nodeId", it.nodeId); put("input", it.input) } }))
        } }))
        put("frameBase", frameBase); put("frameStep", frameStep); put("maxFrames", maxFrames?.let(::JsonPrimitive) ?: JsonNull)
        put("models", JsonArray(models.sortedWith(compareBy(ComfyModelSlot::nodeId, ComfyModelSlot::input)).map { buildJsonObject { put("nodeId", it.nodeId); put("input", it.input) } }))
        put("capabilities", capabilities?.let { Json.encodeToJsonElement(MediaCapabilities.serializer(), it.copy(modes = it.modes.sortedBy { mode -> mode.name }.toSet())) } ?: JsonNull)
        put("group", group.name); put("role", role.name); put("operation", operation?.let { buildJsonObject { put("label", it.label) } } ?: JsonNull)
        put("params", JsonArray(params.map { Json.encodeToJsonElement(TemplateParam.serializer(), it) }))
    }
    fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray -> JsonArray(element.map(::sorted))
        else -> element
    }
    return MessageDigest.getInstance("SHA-256").digest(sorted(value).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

fun fillComfyWorkflow(template: ComfyTemplate, values: Map<SlotKind, List<String>>, params: Map<String, JsonPrimitive> = emptyMap()): JsonObject {
    val resolvedParams = template.resolveParams(params)
    val nodes = template.workflow.toMutableMap()
    values.filterValues { it.isNotEmpty() }.keys.forEach { kind ->
        require(template.slots.any { it.kind == kind && kind != SlotKind.OUTPUT }) { "模板未声明 $kind 槽位" }
    }
    COMFY_MEDIA_SLOTS.forEach { kind ->
        require(values[kind].orEmpty().size <= template.slots.filter { it.kind == kind }.sumOf { it.capacity }) { "$kind 输入超出模板容量" }
    }
    val offsets = mutableMapOf<SlotKind, Int>()
    fun inputs(id: String): MutableMap<String, JsonElement> = ((nodes[id] as? JsonObject)?.get("inputs") as? JsonObject)?.toMutableMap()
        ?: throw IllegalArgumentException("缺少节点输入 $id")
    fun write(id: String, data: Map<String, JsonElement>) {
        val node = (nodes[id] as? JsonObject) ?: throw IllegalArgumentException("缺少节点 $id")
        nodes[id] = JsonObject(node.toMutableMap().also { it["inputs"] = JsonObject(data) })
    }
    template.slots.filter { it.kind != SlotKind.OUTPUT }.forEach { slot ->
        val media = slot.kind in COMFY_MEDIA_SLOTS
        val supplied = if (media) {
            val offset = offsets.getOrDefault(slot.kind, 0)
            values[slot.kind].orEmpty().drop(offset).take(slot.capacity).also { offsets[slot.kind] = offset + it.size }
        } else if (slot.kind == SlotKind.PARAM) listOf(resolvedParams.getValue(requireNotNull(slot.param)).content)
        else values[slot.kind] ?: if (slot.kind in setOf(SlotKind.WIDTH, SlotKind.HEIGHT, SlotKind.FRAMES, SlotKind.SEED, SlotKind.DURATION)) {
            val existing = (inputs(slot.nodeId)[slot.input] as? JsonPrimitive)?.contentOrNull
                ?: throw IllegalArgumentException("${slot.kind} 需要显式指定数值")
            listOf(existing)
        } else return@forEach
        require(media || supplied.size == 1) { "${slot.kind} 需要一个值" }
        val data = inputs(slot.nodeId)
        slot.names().forEachIndexed { index, key ->
            require(key in data) { "缺少槽位 ${slot.nodeId}.$key" }
            val raw = supplied.getOrNull(index)
            if (raw == null) {
                // 未使用的可选参考必须移除，不能把空字符串送入图片连线。
                data.remove(key)
                return@forEachIndexed
            }
            if (slot.uploadTargets.isNotEmpty()) {
                val target = slot.uploadTargets[index]
                val link = data[key] as? JsonArray
                require(link?.size == 2 && (link[0] as? JsonPrimitive)?.contentOrNull == target.nodeId &&
                    (link[1] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.let { it >= 0 } == true) { "槽位 ${slot.nodeId}.$key 与上传目标连线不一致" }
                val destination = inputs(target.nodeId)
                require(target.input in destination) { "缺少上传输入 ${target.nodeId}.${target.input}" }
                destination[target.input] = JsonPrimitive(raw)
                write(target.nodeId, destination)
            } else {
                require(data[key] !is JsonArray && data[key] !is JsonObject) { "连线媒体槽位必须声明 uploadTargets" }
                data[key] = when (slot.kind) {
                    SlotKind.WIDTH, SlotKind.HEIGHT -> {
                        val size = raw.toIntOrNull()
                        require(size != null && size > 0 && size <= Int.MAX_VALUE - 31) { "宽高必须是可对齐的正整数" }
                        JsonPrimitive(((size + 31) / 32) * 32)
                    }
                    SlotKind.FRAMES -> {
                        val frames = raw.toIntOrNull()
                        require(frames != null && frames >= template.frameBase && (frames - template.frameBase) % template.frameStep == 0 &&
                            (template.maxFrames == null || frames <= template.maxFrames)) { "帧数必须满足 ${template.frameBase}+${template.frameStep}n 与上限 ${template.maxFrames ?: "不限"}" }
                        JsonPrimitive(frames)
                    }
                    SlotKind.SEED -> JsonPrimitive(requireNotNull(raw.toLongOrNull()) { "种子必须是 64 位整数" })
                    SlotKind.DURATION -> {
                        val seconds = raw.toDoubleOrNull()
                        require(seconds != null && seconds.isFinite() && seconds > 0) { "DURATION: 需要正数秒数" }
                        Json.parseToJsonElement(raw).jsonPrimitive.also { require(!it.isString) { "DURATION: 需要数值" } }
                    }
                    SlotKind.PARAM -> template.params.single { it.key == slot.param }.workflowValue(resolvedParams.getValue(requireNotNull(slot.param)))
                    else -> JsonPrimitive(raw)
                }
            }
        }
        write(slot.nodeId, data)
    }
    return JsonObject(nodes)
}

fun preflightComfy(template: ComfyTemplate, objectInfo: JsonObject): PreflightResult {
    val missing = mutableListOf<String>()
    val uncertain = mutableListOf<String>()
    if (template.role == TemplateRole.DERIVE) {
        if (template.operation?.label.isNullOrBlank()) missing += "operation.label: DERIVE 缺少操作名称"
        if (template.slots.count { it.kind == SlotKind.INPUT_VIDEO } != 1) missing += "slots.INPUT_VIDEO: DERIVE 需要一个来源视频槽位"
    }
    fun definition(id: String): JsonObject? = ((template.workflow[id] as? JsonObject)?.get("class_type") as? JsonPrimitive)
        ?.contentOrNull?.let { objectInfo[it] as? JsonObject }
    fun descriptor(id: String, input: String): JsonArray? {
        val fields = definition(id)?.get("input") as? JsonObject ?: return null
        val values = (template.workflow[id] as? JsonObject)?.get("inputs") as? JsonObject
        fun find(schema: JsonObject, parent: String = ""): JsonArray? {
            for (section in listOf("required", "optional")) {
                for ((field, raw) in (schema[section] as? JsonObject).orEmpty()) {
                    val key = if (parent.isEmpty()) field else "$parent.$field"
                    val spec = raw as? JsonArray ?: continue
                    if (key == input) return spec
                    if (input.startsWith("$key.") && (spec.firstOrNull() as? JsonPrimitive)?.contentOrNull == "COMFY_DYNAMICCOMBO_V3") {
                        val selected = (values?.get(key) as? JsonPrimitive)?.contentOrNull
                        val options = (spec.getOrNull(1) as? JsonObject)?.get("options") as? JsonArray
                        val child = options?.mapNotNull { it as? JsonObject }?.firstOrNull { (it["key"] as? JsonPrimitive)?.contentOrNull == selected }?.get("inputs") as? JsonObject
                        child?.let { find(it, key) }?.let { return it }
                    }
                }
            }
            return null
        }
        return find(fields)
    }
    fun checkInput(id: String, input: String) {
        if (definition(id) == null) missing += "上传或参数目标节点 $id"
        else if (descriptor(id, input) == null) missing += "主机输入 $id.$input"
    }
    template.workflow.forEach { (id, element) ->
        val node = element as? JsonObject
        val type = (node?.get("class_type") as? JsonPrimitive)?.contentOrNull
        if (type == null) { uncertain += "节点 $id 类型未知"; return@forEach }
        val definition = objectInfo[type] as? JsonObject
        if (definition == null) { missing += "节点 $type"; return@forEach }
        if (node?.get("inputs") !is JsonObject) uncertain += "节点 $id 缺少 API Format inputs"
        if (definition["input"] !is JsonObject) uncertain += "节点 $id 输入 schema 无法判定"
    }
    template.models.forEach { model ->
        val node = template.workflow[model.nodeId] as? JsonObject
        val type = (node?.get("class_type") as? JsonPrimitive)?.contentOrNull
        if (node == null) { missing += "模型槽位节点 ${model.nodeId}"; return@forEach }
        val selected = ((node?.get("inputs") as? JsonObject)?.get(model.input) as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (selected == null) { uncertain += "模型 ${model.nodeId}.${model.input} 不是文件名"; return@forEach }
        val definition = type?.let { objectInfo[it] as? JsonObject }
        val declared = definition?.get("input") as? JsonObject
        val descriptor = listOf("required", "optional").firstNotNullOfOrNull { (declared?.get(it) as? JsonObject)?.get(model.input) } as? JsonArray
        val options = descriptor?.firstOrNull() as? JsonArray
        if (options == null) uncertain += "模型 ${model.nodeId}.${model.input} 的可选值无法判定"
        else if (options.none { (it as? JsonPrimitive)?.contentOrNull == selected }) missing += "模型 $type.${model.input}: $selected"
    }
    template.slots.forEach { slot ->
        val node = template.workflow[slot.nodeId] as? JsonObject
        if (node == null) missing += "槽位节点 ${slot.nodeId}"
        else if (slot.kind == SlotKind.OUTPUT) {
            val type = (node["class_type"] as? JsonPrimitive)?.contentOrNull
            val outputNode = (type?.let { objectInfo[it] as? JsonObject }?.get("output_node") as? JsonPrimitive)?.booleanOrNull
            if (outputNode == false) missing += "节点 ${slot.nodeId} 不是产出节点"
            else if (outputNode == null) uncertain += "节点 ${slot.nodeId} 产出能力无法判定"
            val outputKind = slot.outputKind ?: when (slot.input) {
                "gifs", "videos", "video" -> InputKind.VIDEO
                "audio" -> InputKind.AUDIO
                else -> null
            }
            if (outputKind in setOf(InputKind.VIDEO, InputKind.AUDIO)) {
                val outputs = definition(slot.nodeId)?.get("output") as? JsonArray
                val types = outputs?.mapNotNull { (it as? JsonPrimitive)?.takeIf { it.isString }?.content }
                val expected = if (outputKind == InputKind.AUDIO) setOf("AUDIO") else setOf("VIDEO", "IMAGE")
                if (types.isNullOrEmpty() || types.size != outputs?.size || "*" in types) uncertain += "节点 ${slot.nodeId} 的 $outputKind 输出类型无法判定"
                else if (types.none { it in expected }) missing += "节点 ${slot.nodeId} 输出不含 ${expected.joinToString("/")}"
            }
        } else {
            val inputs = node["inputs"] as? JsonObject
            slot.names().filter { inputs?.containsKey(it) != true }.forEach { missing += "槽位 ${slot.nodeId}.$it" }
        }
        slot.uploadTargets.forEachIndexed { index, target ->
            val link = ((node?.get("inputs") as? JsonObject)?.get(slot.names()[index])) as? JsonArray
            if (link?.size != 2 || (link[0] as? JsonPrimitive)?.contentOrNull != target.nodeId || (link[1] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.let { it >= 0 } != true) missing += "上传目标与槽位 ${slot.nodeId}.${slot.names()[index]} 连线不一致"
            val inputs = (template.workflow[target.nodeId] as? JsonObject)?.get("inputs") as? JsonObject
            if (inputs?.containsKey(target.input) != true) missing += "上传输入 ${target.nodeId}.${target.input}"
        }
        if (slot.kind == SlotKind.INPUT_VIDEO) {
            val targets = slot.uploadTargets.ifEmpty { listOf(ComfyUploadTarget(slot.nodeId, slot.input)) }
            targets.forEach { checkInput(it.nodeId, it.input) }
        }
        if (slot.kind == SlotKind.PARAM) {
            checkInput(slot.nodeId, slot.input)
            val param = template.params.firstOrNull { it.key == slot.param }
            if (param == null) missing += "slots.param: 未声明参数 ${slot.param}"
            else {
                val spec = descriptor(slot.nodeId, slot.input)
                val options = spec?.firstOrNull() as? JsonArray ?: if ((spec?.firstOrNull() as? JsonPrimitive)?.contentOrNull == "COMFY_DYNAMICCOMBO_V3") {
                    ((spec?.getOrNull(1) as? JsonObject)?.get("options") as? JsonArray)?.let { choices ->
                        JsonArray(choices.mapNotNull { (it as? JsonObject)?.get("key") })
                    }
                } else null
                if (param.type == TemplateParamType.ENUM && options != null) {
                    val invalid = param.options.filter { option -> options.none { (it as? JsonPrimitive)?.takeIf { it.isString }?.content == option } }
                    if (invalid.isNotEmpty()) missing += "params.${param.key}.options: 主机不支持 ${invalid.joinToString("、")}"
                }
                if (param.type == TemplateParamType.NUMBER) {
                    val settings = spec?.getOrNull(1) as? JsonObject
                    val min = (settings?.get("min") as? JsonPrimitive)?.doubleOrNull
                    val max = (settings?.get("max") as? JsonPrimitive)?.doubleOrNull
                    if ((min != null && (param.min == null || param.min < min)) || (max != null && (param.max == null || param.max > max)))
                        uncertain += "params.${param.key}.min/max: 范围超出主机输入 ${slot.nodeId}.${slot.input}"
                }
            }
        }
    }
    validateComfyDynamicInputs(template.workflow, objectInfo, missing, uncertain)
    return PreflightResult(if (missing.isNotEmpty()) PreflightStatus.MISSING else if (uncertain.isNotEmpty()) PreflightStatus.REVIEW else PreflightStatus.PASS,
        missing.distinct(), uncertain.distinct())
}

/** 只处理已核验的 V3 扁平动态 schema；未知类型交由用户复核。 */
fun validateComfyDynamicInputs(graph: JsonObject, objectInfo: JsonObject, missing: MutableList<String>, uncertain: MutableList<String>) {
    graph.forEach { (id, value) ->
        val node = value as? JsonObject ?: return@forEach
        val inputs = node["inputs"] as? JsonObject ?: return@forEach
        val type = (node["class_type"] as? JsonPrimitive)?.contentOrNull
        val definition = type?.let { objectInfo[it] as? JsonObject }?.get("input") as? JsonObject ?: return@forEach
        fun inspect(fields: JsonObject, parent: String = "") {
            listOf("required", "optional").forEach { section ->
                (fields[section] as? JsonObject).orEmpty().forEach field@ { (field, raw) ->
                    val key = if (parent.isEmpty()) field else "$parent.$field"
                    val spec = raw as? JsonArray ?: return@field
                    val kind = (spec.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return@field
                    val settings = spec.getOrNull(1) as? JsonObject
                    when {
                        kind == "COMFY_DYNAMICCOMBO_V3" -> {
                            val selected = (inputs[key] as? JsonPrimitive)?.contentOrNull
                            val option = (settings?.get("options") as? JsonArray)?.mapNotNull { it as? JsonObject }
                                ?.firstOrNull { (it["key"] as? JsonPrimitive)?.contentOrNull == selected }
                            if (selected == null && section == "required") missing += "节点 $id 缺少动态选项 $key"
                            if (selected != null || inputs.keys.any { it.startsWith("$key.") }) {
                                val child = option?.get("inputs") as? JsonObject
                                if (child == null) uncertain += "节点 $id 动态选项 $key 无法判定" else inspect(child, key)
                            }
                        }
                        kind == "COMFY_AUTOGROW_V3" -> {
                            val config = settings?.get("template") as? JsonObject
                            val names = (config?.get("names") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                                ?: run {
                                    val prefix = (config?.get("prefix") as? JsonPrimitive)?.contentOrNull
                                    val max = (config?.get("max") as? JsonPrimitive)?.intOrNull
                                    if (prefix == null || max == null || max !in 1..100) null else (0 until max).map { "$prefix$it" }
                                }
                            val min = (config?.get("min") as? JsonPrimitive)?.intOrNull
                            val children = config?.get("input") as? JsonObject
                            if (names == null || names.isEmpty() || names.size > 100 || names.distinct().size != names.size || names.any { it.isBlank() } || min == null || min !in 0..names.size || children == null) {
                                uncertain += "节点 $id 动态输入 $key schema 无法判定"; return@field
                            }
                            if (key in inputs) missing += "节点 $id 动态输入 $key 必须使用扁平键"
                            val used = inputs.filterKeys { it.startsWith("$key.") }
                            used.forEach { (member, linkValue) ->
                                val memberName = member.removePrefix("$key.")
                                val link = linkValue as? JsonArray
                                val source = (link?.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content
                                val index = (link?.getOrNull(1) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                                if (memberName !in names || link?.size != 2 || source == null || source !in graph || index == null || index < 0) missing += "节点 $id 动态输入 $member 需要有效节点连线"
                                else {
                                    val sourceType = ((graph[source] as? JsonObject)?.get("class_type") as? JsonPrimitive)?.contentOrNull
                                    val outputs = sourceType?.let { objectInfo[it] as? JsonObject }?.get("output") as? JsonArray
                                    if (outputs == null) uncertain += "节点 $source 输出 schema 无法判定"
                                    else if (index >= outputs.size) missing += "节点 $id 动态输入 $member 输出索引越界"
                                    else {
                                        val childSpec = listOf("required", "optional").firstNotNullOfOrNull { (children[it] as? JsonObject)?.values?.firstOrNull() } as? JsonArray
                                        val expected = (childSpec?.firstOrNull() as? JsonPrimitive)?.contentOrNull
                                        val actual = (outputs[index] as? JsonPrimitive)?.contentOrNull
                                        if (expected == null || actual == null) uncertain += "节点 $id 动态输入 $member 类型无法判定"
                                        else if (expected != "*" && actual != expected) missing += "节点 $id 动态输入 $member 类型不匹配：需要 $expected，实际 $actual"
                                    }
                                }
                            }
                            if ((children["required"] as? JsonObject)?.isNotEmpty() == true) names.take(min).filter { "$key.$it" !in used }.forEach { missing += "节点 $id 缺少动态输入 $key.$it" }
                        }
                        kind.startsWith("COMFY_DYNAMIC") || kind.startsWith("COMFY_AUTOGROW") -> uncertain += "节点 $id 动态类型 $kind 需复核"
                    }
                }
            }
        }
        inspect(definition)
    }
}
