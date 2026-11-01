package com.promenar.infinity.data.linux

/**
 * 共享存储上的文件在 guest 内属主为媒体存储提供者，而 guest 进程以 root 运行，git 会以 dubious ownership 拒绝。
 * 这里只信任本次启动绑定的工作区与挂载根，经 `GIT_CONFIG_*` 以命令行作用域注入：
 * 不写 rootfs，重置 Linux 后仍生效，也不改动用户的 `~/.gitconfig`；并发会话各自只带自己的挂载。
 */
internal object LinuxGitSafeDirectories {
    const val KEY = "safe.directory"

    /**
     * 每个根同时信任自身与其下任意仓库；以斜杠加星号结尾的前缀匹配需要 git 2.46 及以上（内置 Alpine 已满足）。
     * 环境变量值由 git 原样读取，路径中的空格、引号、`#`、`;` 等无需转义；
     * 末段为 `*` 的根会被 git 当作前缀通配，扩大信任范围，因此丢弃。
     */
    fun values(roots: List<String>): List<String> = roots.asSequence()
        .map { it.trimEnd('/') }
        .filter { it.startsWith('/') && !it.endsWith("/*") && it.none(Char::isISOControl) && it.split('/').none { part -> part == ".." } }
        .distinct()
        .flatMap { sequenceOf(it, "$it/*") }
        .toList()

    /** 追加在已有 `GIT_CONFIG_COUNT` 条目（如凭据代理配置）之后；计数无法解析时保持原样，由 git 报告原配置错误。 */
    fun appendTo(environment: MutableMap<String, String>, roots: List<String>) {
        val values = values(roots)
        if (values.isEmpty()) return
        val start = environment[COUNT]?.let { it.toIntOrNull()?.takeIf { count -> count >= 0 } ?: return } ?: 0
        values.forEachIndexed { offset, value ->
            environment["GIT_CONFIG_KEY_${start + offset}"] = KEY
            environment["GIT_CONFIG_VALUE_${start + offset}"] = value
        }
        environment[COUNT] = (start + values.size).toString()
    }

    private const val COUNT = "GIT_CONFIG_COUNT"
}
