package com.promenar.infinity.ui.chat.manager

import com.promenar.infinity.data.model.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import com.promenar.infinity.data.model.InferenceParams

/** 元数据固定放在块头，资料正文中的同形标签不能改变恢复版本与生成身份。 */
@Serializable
internal data class TurnContextMetadata(
    val stateRevision: Long,
    val assistantMessageId: String,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val configuration: TurnConfigurationSnapshot? = null,
    val productionRevision: Long = 0,
    val productionId: String? = null,
)

/** 非空对象可冻结原值为 null 的配置；旧轮次没有该对象时沿用当前配置。 */
@Serializable
data class TurnConfigurationSnapshot(
    val agentSystemPrompt: String?,
    val sessionCustomPrompt: String?,
    val modelId: String? = null,
    val inferenceParams: InferenceParams? = null,
    val activeSkillIds: List<String>? = null,
    val activeMcpServerIds: List<String>? = null,
)

internal fun turnContextMetadata(block: String?): TurnContextMetadata? {
    if (block == null || !block.startsWith("<turn_context>\n<snapshot_metadata>")) return null
    val line = block.lineSequence().drop(1).firstOrNull() ?: return null
    if (!line.endsWith("</snapshot_metadata>")) return null
    return runCatching {
        json.decodeFromString<TurnContextMetadata>(line.removePrefix("<snapshot_metadata>").removeSuffix("</snapshot_metadata>"))
    }.getOrNull()?.takeIf { it.stateRevision >= 0 }
}

/** 内部配置只供恢复本轮使用，模型历史只携带资料与原有版本元数据。 */
internal fun turnContextForModel(block: String?): String? {
    val metadata = turnContextMetadata(block) ?: return block
    if (metadata.configuration == null || block == null) return block
    val headerEnd = block.indexOf('\n', block.indexOf('\n') + 1)
    if (headerEnd < 0) return block
    return "<turn_context>\n<snapshot_metadata>${json.encodeToString(metadata.copy(configuration = null))}</snapshot_metadata>" + block.substring(headerEnd)
}
