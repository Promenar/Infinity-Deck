package com.promenar.infinity.data.exec

/** 启动器已统一编号凭据与挂载信任配置，后续租约和保密槽位不得覆盖或补入 `GIT_CONFIG_*`。 */
internal fun linuxCommandEnvironment(
    launch: Map<String, String>,
    lease: Map<String, String>,
    secrets: Map<String, String>,
): Map<String, String> = launch + lease.filterKeys { !it.startsWith("GIT_CONFIG_") } +
    secrets.filterKeys { !it.startsWith("GIT_CONFIG_") }
