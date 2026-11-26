package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.*
import kotlinx.serialization.json.JsonNull

/** 应用可执行的逐段首帧续接与单次视频派生。 */
data class ShotOperations(val extension: ShotExtensionOperation? = null, val derivations: List<ShotDeriveOperation> = emptyList())
data class ShotExtensionOperation(val channelId: String, val maxSegments: Int = 8, val minDurationSeconds: Int? = null, val maxDurationSeconds: Int? = null)

sealed interface ShotDeriveOperation {
    val id: String
    val label: String

    data class Template(val template: ComfyTemplate) : ShotDeriveOperation {
        override val id get() = template.id
        override val label get() = requireNotNull(template.operation).label
    }

    data class Workflow(val workflow: ComfyWorkflow) : ShotDeriveOperation {
        override val id get() = workflow.workflowId
        override val label get() = workflow.name
        val params get() = workflow.fields.filter { it.mutability == FieldMutability.CONTENT && !it.isVideoInput() }
        val videoInput get() = workflow.videoInputField()
        val unavailableReason: String?
            get() {
                val input = videoInput ?: return "工作流需要声明可填入来源候选的 CONTENT 视频输入字段。"
                val requiredExtra = workflow.fields.filter {
                    it != input && it.isVideoInput() && it.mutability == FieldMutability.CONTENT &&
                        it.required && (it.defaultValue == null || it.defaultValue == JsonNull)
                }
                return if (requiredExtra.isEmpty()) null else
                    "工作流需要额外的视频输入 ${requiredExtra.joinToString { it.key }}，请使用 comfy_run_workflow。"
            }
    }
}

internal fun WorkflowFieldSpec.isVideoInput(): Boolean =
    semanticRole == FieldSemanticRole.INPUT_VIDEO || valueType == FieldValueType.VIDEO_FILE

internal fun ComfyWorkflow.videoInputField(): WorkflowFieldSpec? {
    val inputs = fields.filter { it.mutability == FieldMutability.CONTENT && it.isVideoInput() }
    return inputs.firstOrNull { it.semanticRole == FieldSemanticRole.INPUT_VIDEO } ?: inputs.firstOrNull()
}

internal fun ComfyWorkflow.acceptsVideoDerivation(): Boolean =
    mediaCategory == ComfyMediaCategory.POST_PROCESS || fields.any { it.isVideoInput() }

internal fun shotTakesDirectory(production: Production, episodeId: String) = "${production.rootPath}/$episodeId/takes"

internal fun readyShotDerivations(
    templates: List<ProductionTemplate>,
    workflows: List<ComfyWorkflowState>,
): List<ShotDeriveOperation> {
    val legacy = templates.filter { it.ready && it.template.locked && it.template.role == TemplateRole.DERIVE }
        .map { ShotDeriveOperation.Template(it.template) }
    val generic = workflows.filter { state ->
        state.preflight.status == PreflightStatus.PASS && state.workflow.acceptsVideoDerivation()
    }.map { ShotDeriveOperation.Workflow(it.workflow) }
    return (legacy + generic).distinctBy { it.id }
}

fun resolveShotOperations(
    shotChannel: MediaChannel?,
    channels: List<MediaChannel>,
    derivations: List<ShotDeriveOperation>,
): ShotOperations {
    val channel = shotChannel?.let { selected -> channels.firstOrNull { it.id == selected.id } }
    return ShotOperations(
        extension = channel?.takeIf { it.group == MediaGroup.VIDEO && GenerationMode.FIRST_FRAME in it.capabilities.modes }
            ?.let { ShotExtensionOperation(it.id, minDurationSeconds = it.capabilities.minDurationSeconds, maxDurationSeconds = it.capabilities.maxDurationSeconds) },
        derivations = derivations,
    )
}
