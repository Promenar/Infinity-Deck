package com.promenar.infinity.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ImageGenerationResultData
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 生图与查看媒体卡片摘要行的事实；文案由 [mediaStepChips] 组装。 */
internal data class MediaStepFacts(
    val count: Int,
    /** 统一的像素尺寸，如 1536×1024；多张尺寸不一时为 null。 */
    val dimensions: String?,
    /** 大写格式名，如 PNG。 */
    val format: String?,
    val totalBytes: Long?,
    /** 生图实际或请求的质量档位，auto 不展示。 */
    val quality: String?,
    val transparent: Boolean = false,
    val referenceCount: Int = 0,
)

/** 生图结果摘要：优先取接口回显的实际值，其次请求值，最后从保存的媒体推断。 */
internal fun imageGenerationFacts(data: ImageGenerationResultData?, media: List<MediaRef>): MediaStepFacts {
    val images = media.filter { it.kind == MediaKind.IMAGE }
    val actualSize = (data?.actual?.size ?: data?.requested?.size)?.takeUnless { it.equals("auto", ignoreCase = true) }
    return MediaStepFacts(
        count = images.size,
        dimensions = actualSize?.let(::normalizeDimensions) ?: sharedDimensions(images),
        format = (data?.actual?.outputFormat ?: data?.requested?.outputFormat)?.uppercase(java.util.Locale.ROOT)
            ?: sharedFormat(images),
        totalBytes = images.sumOf { it.sizeBytes }.takeIf { it > 0 },
        quality = (data?.actual?.quality ?: data?.requested?.quality)?.lowercase(java.util.Locale.ROOT)?.takeUnless { it == "auto" },
        transparent = (data?.actual?.background ?: data?.requested?.background).equals("transparent", ignoreCase = true),
        referenceCount = data?.inputImages?.size ?: 0,
    )
}

/** 查看媒体摘要：只看本次查看的媒体本身。 */
internal fun mediaViewFacts(media: List<MediaRef>): MediaStepFacts = MediaStepFacts(
    count = media.size,
    dimensions = sharedDimensions(media),
    format = sharedFormat(media),
    totalBytes = media.sumOf { it.sizeBytes }.takeIf { it > 0 },
    quality = null,
)

private fun normalizeDimensions(size: String): String? {
    val parts = size.lowercase(java.util.Locale.ROOT).split('x', '×')
    val width = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
    val height = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
    return "$width×$height"
}

private fun sharedDimensions(media: List<MediaRef>): String? {
    val sizes = media.mapNotNull { ref -> if (ref.width != null && ref.height != null) "${ref.width}×${ref.height}" else null }.distinct()
    return sizes.singleOrNull()?.takeIf { sizes.isNotEmpty() && media.all { it.width != null } }
}

private fun sharedFormat(media: List<MediaRef>): String? = media.map { ref ->
    when (val subtype = ref.mimeType.substringAfter('/', "").substringBefore(';').lowercase(java.util.Locale.ROOT)) {
        "jpeg", "jpg" -> "JPEG"
        "svg+xml" -> "SVG"
        "" -> null
        else -> subtype.uppercase(java.util.Locale.ROOT)
    }
}.distinct().singleOrNull()

/** 从参数 JSON 取一个字符串字段；不是对象或字段不是字符串时为 null。 */
internal fun toolArgumentText(arguments: String?, key: String): String? {
    if (arguments.isNullOrBlank() || !arguments.trimStart().startsWith("{")) return null
    val root = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: return null
    return (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
}

/** 参数里是否给出了非空数组或字符串字段，用于识别生图的参考图编辑。 */
internal fun toolArgumentPresent(arguments: String?, key: String): Boolean {
    if (arguments.isNullOrBlank() || !arguments.trimStart().startsWith("{")) return false
    val root = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull() ?: return false
    return when (val value = root[key]) {
        null -> false
        is kotlinx.serialization.json.JsonArray -> value.isNotEmpty()
        is JsonPrimitive -> value.isString && value.content.isNotBlank()
        else -> true
    }
}

@Composable
internal fun mediaStepChips(facts: MediaStepFacts, viewed: Boolean): List<String> {
    val context = LocalContext.current
    return buildList {
        if (facts.count > 0) add(
            if (viewed) pluralStringResource(R.plurals.tool_step_viewed_count, facts.count, facts.count)
            else pluralStringResource(R.plurals.tool_step_image_count, facts.count, facts.count),
        )
        if (facts.referenceCount > 0) add(pluralStringResource(R.plurals.tool_step_reference_count, facts.referenceCount, facts.referenceCount))
        facts.dimensions?.let(::add)
        facts.quality?.let { quality ->
            when (quality) {
                "low" -> R.string.tool_step_quality_low
                "medium" -> R.string.tool_step_quality_medium
                "high" -> R.string.tool_step_quality_high
                "xhigh" -> R.string.tool_step_quality_xhigh
                "max" -> R.string.tool_step_quality_max
                else -> null
            }?.let { add(stringResource(it)) }
        }
        if (facts.transparent) add(stringResource(R.string.tool_step_background_transparent))
        facts.format?.let(::add)
        facts.totalBytes?.let { add(android.text.format.Formatter.formatShortFileSize(context, it)) }
    }
}

/** 标题行图标，按工具类别选取。 */
internal fun toolStepIcon(name: String?): ImageVector = when (toolSummaryAction(name)) {
    ToolSummaryAction.COMMAND -> Icons.Rounded.Terminal
    ToolSummaryAction.WEB_SEARCH -> Icons.Rounded.Search
    ToolSummaryAction.WEB_READ, ToolSummaryAction.BROWSER -> Icons.Rounded.Public
    ToolSummaryAction.FILE_READ -> Icons.Rounded.Description
    ToolSummaryAction.FILE_EDIT -> Icons.Rounded.EditNote
    ToolSummaryAction.FILE_TRANSFER -> Icons.Rounded.SwapVert
    ToolSummaryAction.IMAGE -> Icons.Rounded.AutoAwesome
    ToolSummaryAction.MEDIA_VIEW -> Icons.Rounded.Visibility
    ToolSummaryAction.MEDIA_FETCH -> Icons.Rounded.CloudDownload
    ToolSummaryAction.PLAN -> Icons.AutoMirrored.Rounded.Article
    ToolSummaryAction.SKILL -> Icons.Rounded.Extension
    ToolSummaryAction.PET -> Icons.Rounded.Pets
    else -> if (name?.contains("image", ignoreCase = true) == true) Icons.Rounded.Image else Icons.Rounded.Build
}
