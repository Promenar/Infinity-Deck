package com.promenar.infinity.data.exec

import com.promenar.infinity.data.proxy.BuiltInProxyConfig
import com.promenar.infinity.data.linux.LinuxResourceBudget

/** 后续租约和保密槽位保留启动器生成的 Git、资源及内置代理配置。 */
internal fun linuxCommandEnvironment(
    launch: Map<String, String>,
    lease: Map<String, String>,
    secrets: Map<String, String>,
): Map<String, String> = launch + lease.filterKeys(::canOverride) + secrets.filterKeys(::canOverride)

private fun canOverride(key: String) =
    !key.startsWith("GIT_CONFIG_") && key !in BuiltInProxyConfig.environmentKeys &&
        key !in LinuxResourceBudget.managedEnvironmentKeys
