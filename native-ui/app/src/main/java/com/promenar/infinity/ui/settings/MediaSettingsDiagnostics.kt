package com.promenar.infinity.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R

/** 将既有预检契约中的诊断映射到双语资源，未知技术详情保留原文。 */
internal data class MediaDiagnostic(val labelRes: Int, val arguments: List<String>)

private val mediaDiagnosticRules = listOf(
    Regex("尚未预检") to R.string.media_settings_diagnostic_0,
    Regex("主机配置已变化，请重新预检") to R.string.media_settings_diagnostic_1,
    Regex("模板已解锁，保存后需要预检") to R.string.media_settings_diagnostic_2,
    Regex("主机节点或模型可能已变化，请复核缺项") to R.string.media_settings_diagnostic_3,
    Regex("无法读取主机节点信息，请检查连接与鉴权后重新预检") to R.string.media_settings_diagnostic_4,
    Regex("节点 (.+) 类型未知") to R.string.media_settings_diagnostic_5,
    Regex("节点 (.+) 缺少 API Format inputs") to R.string.media_settings_diagnostic_6,
    Regex("节点 (.+) 输入 schema 无法判定") to R.string.media_settings_diagnostic_7,
    Regex("模型槽位节点 (.+)") to R.string.media_settings_diagnostic_8,
    Regex("模型 (.+) 不是文件名") to R.string.media_settings_diagnostic_9,
    Regex("模型 (.+) 的可选值无法判定") to R.string.media_settings_diagnostic_10,
    Regex("模型 (.+)") to R.string.media_settings_diagnostic_11,
    Regex("槽位节点 (.+)") to R.string.media_settings_diagnostic_12,
    Regex("节点 (.+) 不是产出节点") to R.string.media_settings_diagnostic_13,
    Regex("节点 (.+) 产出能力无法判定") to R.string.media_settings_diagnostic_14,
    Regex("槽位 (.+)") to R.string.media_settings_diagnostic_15,
    Regex("上传目标与槽位 (.+) 连线不一致") to R.string.media_settings_diagnostic_16,
    Regex("上传输入 (.+)") to R.string.media_settings_diagnostic_17,
    Regex("节点 (.+) 缺少动态选项 (.+)") to R.string.media_settings_diagnostic_18,
    Regex("节点 (.+) 动态选项 (.+) 无法判定") to R.string.media_settings_diagnostic_19,
    Regex("节点 (.+) 动态输入 (.+) schema 无法判定") to R.string.media_settings_diagnostic_20,
    Regex("节点 (.+) 动态输入 (.+) 必须使用扁平键") to R.string.media_settings_diagnostic_21,
    Regex("节点 (.+) 动态输入 (.+) 需要有效节点连线") to R.string.media_settings_diagnostic_22,
    Regex("节点 (.+) 输出 schema 无法判定") to R.string.media_settings_diagnostic_23,
    Regex("节点 (.+) 动态输入 (.+) 输出索引越界") to R.string.media_settings_diagnostic_24,
    Regex("节点 (.+) 动态输入 (.+) 类型无法判定") to R.string.media_settings_diagnostic_25,
    Regex("节点 (.+) 动态输入 (.+) 类型不匹配：需要 (.+)，实际 (.+)") to R.string.media_settings_diagnostic_26,
    Regex("节点 (.+) 缺少动态输入 (.+)") to R.string.media_settings_diagnostic_27,
    Regex("节点 (.+) 动态类型 (.+) 需复核") to R.string.media_settings_diagnostic_28,
    Regex("节点 (.+)") to R.string.media_settings_diagnostic_29,
)

internal fun mediaDiagnostic(raw: String): MediaDiagnostic? {
    mediaDiagnosticRules.forEach { (pattern, label) ->
        val match = pattern.matchEntire(raw) ?: return@forEach
        return MediaDiagnostic(label, match.groupValues.drop(1))
    }
    return null
}

@Composable
internal fun mediaDiagnosticLabel(raw: String): String {
    val diagnostic = mediaDiagnostic(raw) ?: return raw
    return stringResource(diagnostic.labelRes, *diagnostic.arguments.toTypedArray())
}
