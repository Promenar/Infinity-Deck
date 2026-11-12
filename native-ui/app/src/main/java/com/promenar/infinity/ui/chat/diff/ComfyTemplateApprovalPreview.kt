package com.promenar.infinity.ui.chat.diff

import com.promenar.infinity.data.mediagen.comfy.ComfyConfigurationCodec
import com.promenar.infinity.data.mediagen.comfy.ComfyRepository
import com.promenar.infinity.domain.mediagen.ComfyModelSlot
import com.promenar.infinity.domain.mediagen.ComfyTemplate
import com.promenar.infinity.domain.mediagen.SlotKind
import com.promenar.infinity.domain.mediagen.TemplateParamType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 预览只读取模板和主机显示名，不触发预检或远端请求。 */
interface ComfyTemplatePreviewSources {
    suspend fun template(id: String): ComfyTemplate?
    suspend fun hostName(id: String): String?
}

/** 从已核对审批摘要的工具参数读取同 id 现值，说明来自审批卡的会话调用说明。 */
class ComfyTemplatePreviewLoader(private val sources: ComfyTemplatePreviewSources) {
    suspend fun load(args: JsonObject, reason: String): ApprovalDiffPreview.ComfyTemplate? = try {
        val raw = args["template"] as? JsonObject
        if (raw == null || args.keys != setOf("template") || raw.toString().toByteArray().size > 1024 * 1024) null
        else {
            val requested = ComfyConfigurationCodec.decodeTemplate(raw)
            val hostName = sources.hostName(requested.hostId)
            if (hostName == null) null
            else {
                val previous = sources.template(requested.id)
                withContext(Dispatchers.Default) {
                    ComfyTemplatePreviewBuilder.build(previous, requested, hostName, reason)
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/** 只按输入构建差异；容量使用与保存时一致的槽位推导值。 */
object ComfyTemplatePreviewBuilder {
    fun build(
        previous: ComfyTemplate?,
        requested: ComfyTemplate,
        hostName: String,
        reason: String,
    ): ApprovalDiffPreview.ComfyTemplate {
        require(previous == null || previous.id == requested.id) { "模板预览必须比较同一 id" }
        val oldTypes = previous?.nodeTypes().orEmpty()
        val newTypes = requested.nodeTypes()
        val before = previous?.capabilityFields().orEmpty()
        val after = requested.capabilityFields()
        return ApprovalDiffPreview.ComfyTemplate(
            templateName = requested.name,
            hostName = hostName,
            isNew = previous == null,
            reason = reason,
            addedNodeTypes = (newTypes - oldTypes).sorted(),
            removedNodeTypes = (oldTypes - newTypes).sorted(),
            modelChanges = modelChanges(previous, requested),
            capabilityChanges = (before.keys + after.keys).mapNotNull { key ->
                val value = after[key]
                val old = before[key]
                if ((previous == null && value != null) || (previous != null && old != value)) FieldChange(key, old, value) else null
            },
        )
    }

    private fun ComfyTemplate.nodeTypes(): Set<String> = workflow.values.mapNotNull { node ->
        ((node as? JsonObject)?.get("class_type") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }.toSet()

    private fun modelChanges(before: ComfyTemplate?, after: ComfyTemplate): List<FieldChange> {
        val oldSlots = before?.models.orEmpty().toSet()
        val newSlots = after.models.toSet()
        return (oldSlots + newSlots).sortedWith(compareBy(ComfyModelSlot::nodeId, ComfyModelSlot::input)).mapNotNull { slot ->
            val old = if (slot in oldSlots) before?.modelValue(slot) else null
            val new = if (slot in newSlots) after.modelValue(slot) else null
            if (old == new) null else FieldChange("${slot.nodeId}.${slot.input}", old, new)
        }
    }

    private fun ComfyTemplate.modelValue(slot: ComfyModelSlot): String =
        (((workflow[slot.nodeId] as? JsonObject)?.get("inputs") as? JsonObject)?.get(slot.input))
            ?.displayValue() ?: "未设置"

    private fun JsonElement.displayValue(): String = if (this is JsonPrimitive && isString) content else toString()

    private fun ComfyTemplate.capabilityFields(): Map<String, String?> {
        val c = channel().capabilities
        return linkedMapOf(
            "name" to name,
            "hostId" to hostId,
            "group" to group.name,
            "role" to role.name,
            "operation" to operation?.label,
            "modes" to c.modes.map { it.name }.sorted().joinToString("、"),
            "maxImages" to c.maxImages.toString(),
            "maxVideos" to c.maxVideos.toString(),
            "maxAudio" to c.maxAudio.toString(),
            "nativeAudio" to c.nativeAudio.toString(),
            "resolutions" to c.resolutions.joinToString("、").ifEmpty { null },
            "minDurationSeconds" to c.minDurationSeconds?.toString(),
            "maxDurationSeconds" to c.maxDurationSeconds?.toString(),
            "promptFormat" to c.promptFormat.name,
            "referenceOrder" to c.referenceOrder.joinToString("、").ifEmpty { null },
            "frameBase" to frameBase.toString(),
            "frameStep" to frameStep.toString(),
            "maxFrames" to maxFrames?.toString(),
            "slots" to slots.joinToString("\n") { slot ->
                buildString {
                    append("${slot.kind.name}: ${slot.nodeId}.${slot.input} × ${slot.capacity}")
                    slot.outputKind?.let { append(" → ${it.name}") }
                    slot.param?.let { append(" · param.$it") }
                    if (slot.uploadTargets.isNotEmpty()) append(" [${slot.uploadTargets.joinToString("、") { "${it.nodeId}.${it.input}" }}]")
                }
            }.ifEmpty { null },
        ).apply {
            listOf(SlotKind.INPUT_VIDEO, SlotKind.PARAM, SlotKind.DURATION).forEach { kind ->
                put("slots.${kind.name}", slots.filter { it.kind == kind }.sumOf { it.capacity }.toString())
            }
            params.forEach { param ->
                put("param.${param.key}", buildString {
                    append("${param.label} · ${param.type.name} · ${param.default.displayValue()}")
                    when (param.type) {
                        TemplateParamType.ENUM -> append(" · ${param.options.joinToString("、")}")
                        TemplateParamType.NUMBER -> {
                            append(" · ${param.min ?: "不限"}～${param.max ?: "不限"}")
                            if (param.integer) append(" · 整数")
                        }
                        TemplateParamType.BOOLEAN -> Unit
                    }
                })
            }
        }
    }
}

internal class AppComfyTemplatePreviewSources(private val repository: ComfyRepository) : ComfyTemplatePreviewSources {
    override suspend fun template(id: String): ComfyTemplate? = repository.templates().firstOrNull { it.template.id == id }?.template
    override suspend fun hostName(id: String): String? = repository.hosts().firstOrNull { it.id == id }?.name
}
