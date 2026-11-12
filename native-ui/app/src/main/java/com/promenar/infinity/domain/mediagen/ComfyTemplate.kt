package com.promenar.infinity.domain.mediagen

import kotlinx.serialization.json.*

enum class SlotKind { PROMPT, REFERENCE_IMAGES, FIRST_FRAME, LAST_FRAME, REFERENCE_AUDIO, WIDTH, HEIGHT, FRAMES, SEED, OUTPUT }
data class ComfySlot(val nodeId: String, val input: String, val kind: SlotKind, val capacity: Int = 1) {
    init { require(nodeId.isNotBlank() && input.isNotBlank() && capacity > 0) }
}
data class ComfyTemplate(
    val id: String,
    val workflow: JsonObject,
    val slots: List<ComfySlot>,
    val frameBase: Int = 1,
    val frameStep: Int = 1,
    val locked: Boolean = false,
    val capabilities: MediaCapabilities? = null,
) {
    init { require(id.isNotBlank() && frameBase > 0 && frameStep > 0) }
    fun updateByAgent(replacement: ComfyTemplate): ComfyTemplate {
        check(!locked) { "模板已锁定" }
        require(replacement.id == id && !replacement.locked)
        return replacement
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
    template.slots.filter { it.kind != SlotKind.OUTPUT }.forEach { slot ->
        val supplied = values[slot.kind] ?: return@forEach
        require(supplied.size <= slot.capacity && (slot.kind == SlotKind.REFERENCE_IMAGES || supplied.size <= 1))
        val node = nodes[slot.nodeId]?.jsonObject ?: error("缺少节点 ${slot.nodeId}")
        val inputs = node["inputs"]?.jsonObject?.toMutableMap() ?: error("缺少节点输入 ${slot.nodeId}")
        if (slot.kind == SlotKind.REFERENCE_IMAGES && slot.capacity > 1) {
            val prefix = slot.input.removeSuffix("0")
            require(prefix != slot.input) { "可变长槽位须以 0 结尾" }
            repeat(slot.capacity) { index ->
                val name = "$prefix$index"
                require(inputs.containsKey(name)) { "缺少槽位 $name" }
                inputs[name] = JsonPrimitive("")
            }
        }
        supplied.forEachIndexed { index, raw ->
            val name = if (slot.kind == SlotKind.REFERENCE_IMAGES && slot.capacity > 1) {
                val prefix = slot.input.removeSuffix("0")
                require(prefix != slot.input) { "可变长槽位须以 0 结尾" }
                "$prefix$index"
            } else slot.input
            require(inputs.containsKey(name)) { "缺少槽位 $name" }
            val value = when (slot.kind) {
                SlotKind.WIDTH, SlotKind.HEIGHT -> {
                    val size = raw.toInt(); require(size > 0 && size <= Int.MAX_VALUE - 31)
                    JsonPrimitive(((size + 31) / 32) * 32)
                }
                SlotKind.FRAMES -> {
                    val frames = raw.toInt(); require(frames >= template.frameBase && (frames - template.frameBase) % template.frameStep == 0)
                    JsonPrimitive(frames)
                }
                SlotKind.SEED -> JsonPrimitive(raw.toLong())
                else -> JsonPrimitive(raw)
            }
            inputs[name] = value
        }
        nodes[slot.nodeId] = JsonObject(node.toMutableMap().also { it["inputs"] = JsonObject(inputs) })
    }
    return JsonObject(nodes)
}

fun preflightComfy(template: ComfyTemplate, objectInfo: JsonObject): PreflightResult {
    val missing = mutableListOf<String>()
    val uncertain = mutableListOf<String>()
    template.workflow.forEach { (id, element) ->
        val node = element as? JsonObject
        val type = (node?.get("class_type") as? JsonPrimitive)?.contentOrNull
        if (type == null) { uncertain.add("节点 $id 类型未知"); return@forEach }
        val definition = objectInfo[type] as? JsonObject
        if (definition == null) { missing.add("节点 $type"); return@forEach }
        val inputs = node["inputs"] as? JsonObject ?: return@forEach
        val declared = (definition["input"] as? JsonObject)
        val choices = listOf("required", "optional").mapNotNull { declared?.get(it) as? JsonObject }
        inputs.filterKeys { it.contains("model", true) || it.contains("ckpt", true) || it.contains("lora", true) || it.contains("vae", true) }.forEach modelInput@ { (name, value) ->
            val selected = (value as? JsonPrimitive)?.contentOrNull ?: return@modelInput
            val descriptor = choices.firstNotNullOfOrNull { it[name] } as? JsonArray
            val options = descriptor?.firstOrNull() as? JsonArray
            if (options == null) uncertain.add("$type.$name")
            else if (options.none { (it as? JsonPrimitive)?.contentOrNull == selected }) missing.add("$type.$name: $selected")
        }
    }
    template.slots.forEach { slot ->
        val node = template.workflow[slot.nodeId] as? JsonObject
        if (node == null) {
            missing.add("槽位节点 ${slot.nodeId}")
        } else if (slot.kind != SlotKind.OUTPUT) {
            val inputs = node["inputs"] as? JsonObject
            val names = if (slot.kind == SlotKind.REFERENCE_IMAGES && slot.capacity > 1 && slot.input.endsWith("0")) {
                (0 until slot.capacity).map { slot.input.removeSuffix("0") + it }
            } else listOf(slot.input)
            names.filter { inputs?.containsKey(it) != true }.forEach { missing.add("槽位 ${slot.nodeId}.$it") }
        }
    }
    return PreflightResult(if (missing.isNotEmpty()) PreflightStatus.MISSING else if (uncertain.isNotEmpty()) PreflightStatus.REVIEW else PreflightStatus.PASS, missing.distinct(), uncertain.distinct())
}
