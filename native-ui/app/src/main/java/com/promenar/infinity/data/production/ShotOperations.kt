package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.*

/** 应用可执行的逐段首帧续接与单次模板派生。 */
data class ShotOperations(val extension: ShotExtensionOperation? = null, val derivations: List<ShotDeriveOperation> = emptyList())
data class ShotExtensionOperation(val channelId: String, val maxSegments: Int = 8, val minDurationSeconds: Int? = null, val maxDurationSeconds: Int? = null)
data class ShotDeriveOperation(val templateId: String, val label: String, val params: List<TemplateParam>)

fun resolveShotOperations(shotChannel: MediaChannel?, channels: List<MediaChannel>, templates: List<ComfyTemplate>, preflight: (ComfyTemplate) -> Boolean): ShotOperations {
    val channel = shotChannel?.let { selected -> channels.firstOrNull { it.id == selected.id } }
    return ShotOperations(
        extension = channel?.takeIf { it.group == MediaGroup.VIDEO && GenerationMode.FIRST_FRAME in it.capabilities.modes }
            ?.let { ShotExtensionOperation(it.id, minDurationSeconds = it.capabilities.minDurationSeconds, maxDurationSeconds = it.capabilities.maxDurationSeconds) },
        derivations = templates.filter { it.role == TemplateRole.DERIVE && preflight(it) }
            .map { ShotDeriveOperation(it.id, requireNotNull(it.operation).label, it.params) },
    )
}
