package com.promenar.infinity.domain.mediagen

import kotlinx.serialization.json.*

enum class SlotKind { PROMPT, REFERENCE_IMAGES, FIRST_FRAME, LAST_FRAME, REFERENCE_AUDIO, WIDTH, HEIGHT, FRAMES, SEED, OUTPUT }

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
) {
    init {
        require(nodeId.isNotBlank() && input.isNotBlank() && capacity in 1..100) { "槽位节点、输入名或容量无效" }
        require(capacity == 1 || kind in setOf(SlotKind.REFERENCE_IMAGES, SlotKind.REFERENCE_AUDIO)) { "只有参考槽位支持可变长容量" }
        if (capacity > 1) {
            val start = Regex("^(.*?)([0-9]+)$").matchEntire(input)?.groupValues?.get(2)?.toIntOrNull()
            require(start != null && start <= Int.MAX_VALUE - capacity) { "可变长槽位需要可递增的数字后缀" }
        }
        require(uploadTargets.isEmpty() || (kind in COMFY_MEDIA_SLOTS && uploadTargets.size == capacity)) { "上传目标数量必须等于媒体槽位容量" }
        require(outputKind == null || kind == SlotKind.OUTPUT) { "产出类型只用于产出槽位" }
    }
    fun names(): List<String> {
        if (capacity == 1) return listOf(input)
        val match = requireNotNull(Regex("^(.*?)([0-9]+)$").matchEntire(input))
        val start = match.groupValues[2].toInt()
        return (0 until capacity).map { match.groupValues[1] + (start + it) }
    }
}
internal val COMFY_MEDIA_SLOTS = setOf(SlotKind.REFERENCE_IMAGES, SlotKind.FIRST_FRAME, SlotKind.LAST_FRAME, SlotKind.REFERENCE_AUDIO)

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
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "模板 id 只支持字母、数字、下划线和连字符" }
        require(frameBase > 0 && frameStep > 0 && (maxFrames == null || maxFrames >= frameBase)) { "帧数约束无效" }
        require(name.isNotBlank() && name.length <= 120) { "模板名称需要 1～120 字符" }
        require(slots.flatMap { s -> s.names().map { s.nodeId to it } }.distinct().size == slots.sumOf { it.capacity }) { "槽位不能重叠" }
        val uploadInputs = slots.filter { it.kind in COMFY_MEDIA_SLOTS }.flatMap { slot ->
            if (slot.uploadTargets.isEmpty()) slot.names().map { ComfyUploadTarget(slot.nodeId, it) } else slot.uploadTargets
        }
        require(uploadInputs.distinct().size == uploadInputs.size) { "媒体槽位不能共享上传文件输入" }
    }
    val channelId: String get() = "comfy:$id"
    fun channel(): MediaChannel {
        val declared = requireNotNull(capabilities) { "模板需要声明通道能力" }
        require(declared.modes.isNotEmpty()) { "模板至少需要一种生成模式" }
        return MediaChannel(channelId, group, declared.copy(
            maxImages = slots.filter { it.kind == SlotKind.REFERENCE_IMAGES }.sumOf { it.capacity },
            maxVideos = 0,
            maxAudio = slots.filter { it.kind == SlotKind.REFERENCE_AUDIO }.sumOf { it.capacity },
            characterReferenceMode = CharacterReferenceMode.SEPARATE,
        ))
    }
    fun updateByAgent(replacement: ComfyTemplate): ComfyTemplate {
        check(!locked) { "模板已锁定，请用户在界面解锁后修改" }
        require(replacement.id == id) { "模板 id 不可修改" }
        return replacement.copy(locked = true)
    }
    fun unlock(userApproved: Boolean): ComfyTemplate {
        check(userApproved) { "解锁模板需要用户批准" }
        return copy(locked = false)
    }
}

enum class PreflightStatus { PASS, MISSING, REVIEW }
data class PreflightResult(val status: PreflightStatus, val missing: List<String> = emptyList(), val uncertain: List<String> = emptyList())

fun fillComfyWorkflow(template: ComfyTemplate, values: Map<SlotKind, List<String>>): JsonObject {
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
        } else values[slot.kind] ?: if (slot.kind in setOf(SlotKind.WIDTH, SlotKind.HEIGHT, SlotKind.FRAMES, SlotKind.SEED)) {
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
