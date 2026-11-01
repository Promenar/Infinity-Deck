package com.promenar.infinity.ui.chat.manager

import com.promenar.infinity.data.model.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/** 元数据固定放在块头，资料正文中的同形标签不能改变恢复版本与生成身份。 */
@Serializable
internal data class TurnContextMetadata(val stateRevision: Long, val assistantMessageId: String)

internal fun turnContextMetadata(block: String?): TurnContextMetadata? {
    if (block == null || !block.startsWith("<turn_context>\n<snapshot_metadata>")) return null
    val line = block.lineSequence().drop(1).firstOrNull() ?: return null
    if (!line.endsWith("</snapshot_metadata>")) return null
    return runCatching {
        json.decodeFromString<TurnContextMetadata>(line.removePrefix("<snapshot_metadata>").removeSuffix("</snapshot_metadata>"))
    }.getOrNull()?.takeIf { it.stateRevision >= 0 }
}
