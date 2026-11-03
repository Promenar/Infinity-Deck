package com.promenar.infinity.ui.settings

import com.promenar.infinity.data.tailnet.TailnetEndpoints
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.data.tailnet.TailnetState

/** 提供商接口的访问路径：经公网，或经 App 内置的 Tailscale 访问 tailnet 内的网关。 */
enum class ProviderApiSource { PUBLIC, TAILNET }

/** API 源区域的提示；按钮一键改回与地址一致的来源。 */
enum class ProviderApiSourceHint {
    /** 手动选了公网，地址却在 tailnet 内（按钮：改用 Tailscale）。 */
    ADDRESS_IS_TAILNET,
    /** 选了 Tailscale，地址却是公网域名（按钮：改用公网）。 */
    ADDRESS_IS_PUBLIC,
    /** 已识别为 Tailscale 内网地址，经内置 Tailscale 发送。 */
    RECOGNIZED_TAILNET,
}

/** 选中 Tailscale 时状态行显示的内置 Tailscale 状态。 */
enum class BuiltInTailscaleStatus { CONNECTED, CONNECTING, NEEDS_LOGIN, FAILED, DISABLED, UNAVAILABLE }

data class ProviderApiSourceState(
    val selected: ProviderApiSource,
    val detected: ProviderApiSource,
    val hint: ProviderApiSourceHint?,
    val builtIn: BuiltInTailscaleStatus,
)

fun builtInTailscaleStatus(phase: TailnetPhase): BuiltInTailscaleStatus = when (phase) {
    TailnetPhase.RUNNING -> BuiltInTailscaleStatus.CONNECTED
    TailnetPhase.STARTING, TailnetPhase.STOPPED -> BuiltInTailscaleStatus.CONNECTING
    TailnetPhase.NEEDS_LOGIN -> BuiltInTailscaleStatus.NEEDS_LOGIN
    TailnetPhase.FAILED -> BuiltInTailscaleStatus.FAILED
    TailnetPhase.DISABLED -> BuiltInTailscaleStatus.DISABLED
    TailnetPhase.UNAVAILABLE -> BuiltInTailscaleStatus.UNAVAILABLE
}

/**
 * API 源不单独保存，由接口地址推出：没有手动切换过（[manual] 为 null）时跟随地址自动识别；
 * 手动选过后不再自动改动，只在与地址不一致时提示。
 */
fun providerApiSourceState(baseUrl: String, manual: ProviderApiSource?, tailnet: TailnetState): ProviderApiSourceState {
    val isTailnet = TailnetEndpoints.isTailnetEndpoint(baseUrl, tailnet)
    val detected = if (isTailnet) ProviderApiSource.TAILNET else ProviderApiSource.PUBLIC
    val selected = manual ?: detected
    val hasHost = TailnetEndpoints.hostOf(baseUrl) != null
    val hint = when {
        selected == ProviderApiSource.PUBLIC && isTailnet -> ProviderApiSourceHint.ADDRESS_IS_TAILNET
        selected == ProviderApiSource.TAILNET && !isTailnet && hasHost -> ProviderApiSourceHint.ADDRESS_IS_PUBLIC
        selected == ProviderApiSource.TAILNET && isTailnet -> ProviderApiSourceHint.RECOGNIZED_TAILNET
        else -> null
    }
    return ProviderApiSourceState(selected, detected, hint, builtInTailscaleStatus(tailnet.phase))
}
