package com.promenar.infinity.ui.common

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.promenar.infinity.R
import com.promenar.infinity.data.remote.protocol.ProtocolType

/** 旧版本保存本地推理提供商时写入的名称。 */
private const val LEGACY_LOCAL_PROVIDER_NAME = "本地模型"

/** 模型缺少提供商信息时写入的历史占位名，只在不知道协议时视为未命名。 */
private val LEGACY_UNKNOWN_PROVIDER_NAMES = setOf("Cloud", "Provider")

/**
 * 提供商在界面上的名称。
 *
 * 未命名提供商的名称在存储里是空串或旧版本写入的默认名（协议的历史显示名、“本地模型”），
 * 这些默认名按当前语言显示为协议名称；用户自己起的名称原样返回。
 */
fun providerDisplayName(name: String, protocol: ProtocolType?, resources: Resources): String {
    val trimmed = name.trim()
    val defaultProtocol = when {
        trimmed.isEmpty() -> protocol
        trimmed == LEGACY_LOCAL_PROVIDER_NAME -> ProtocolType.Local
        protocol == null && trimmed in LEGACY_UNKNOWN_PROVIDER_NAMES -> null
        else -> ProtocolType.fromLegacyDisplayName(trimmed) ?: return name
    }
    return defaultProtocol?.label(resources) ?: resources.getString(R.string.provider_models_default_provider)
}

@Composable
fun providerDisplayName(name: String, protocol: ProtocolType?): String =
    providerDisplayName(name, protocol, LocalResources.current)

/** 名称是否为未命名提供商的存储形态；表单据此把名称输入框留空。 */
fun isDefaultProviderName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isEmpty() || trimmed == LEGACY_LOCAL_PROVIDER_NAME || ProtocolType.fromLegacyDisplayName(trimmed) != null
}
