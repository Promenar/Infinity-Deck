package com.promenar.infinity.data.exec

import com.promenar.infinity.data.proxy.BuiltInProxyConfig

/** 启动器已统一编号凭据与挂载信任配置，后续租约和保密槽位不得覆盖或补入 `GIT_CONFIG_*`。 */
internal fun linuxCommandEnvironment(
    launch: Map<String, String>,
    lease: Map<String, String>,
    secrets: Map<String, String>,
): Map<String, String> = launch + lease.filterKeys(::canOverride) + secrets.filterKeys(::canOverride)

private fun canOverride(key: String) = !key.startsWith("GIT_CONFIG_") && key !in BuiltInProxyConfig.environmentKeys
