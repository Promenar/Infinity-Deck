package com.promenar.infinity.data.git.remote

import com.promenar.infinity.data.linux.LinuxGhConfig
import com.promenar.infinity.data.linux.LinuxResourceBudget
import com.promenar.infinity.data.linux.MIB

/** 为 Linux 原生 git 与 gh 生成仅在当前命令生效的凭据代理配置。 */
object LinuxGitEnvironment {
    private val loopback = Regex("127\\.0\\.0\\.1:([0-9]{1,5})")

    fun from(lease: Map<String, String>): Map<String, String> {
        val environment = linkedMapOf("GIT_TERMINAL_PROMPT" to "0")
        val nonce = lease[GitRemoteBroker.ENV_NONCE] ?: return environment
        if (nonce.isBlank() || nonce.any(Char::isISOControl)) return environment
        val hosts = lease[GitRemoteBroker.ENV_CREDENTIAL_HOSTS]?.split(',')
            ?.mapNotNull(GitCredentialHost::normalize)?.distinct().orEmpty()
        if (GitCredentialStore.GITHUB_HOST in hosts && lease[GitRemoteBroker.ENV_GH_SOCKET] == LinuxGhConfig.GUEST_SOCKET) {
            environment["GH_TOKEN"] = nonce
            environment["GH_CONFIG_DIR"] = LinuxGhConfig.GUEST_CONFIG
            environment["GH_PROMPT_DISABLED"] = "1"
            environment["GH_NO_UPDATE_NOTIFIER"] = "1"
        }
        val proxy = lease[GitRemoteBroker.ENV_HTTP_PROXY] ?: return environment
        val port = loopback.matchEntire(proxy)?.groupValues?.get(1)?.toIntOrNull()
        if (port == null || port !in 1..65535) return environment
        if (hosts.isEmpty()) return environment

        val prefix = "http://$proxy/"
        val config = hosts.map { host -> "url.$prefix$host/.insteadOf" to "https://$host/" } + listOf(
            "http.$prefix.extraHeader" to "${GitCredentialProxy.NONCE_HEADER}: $nonce",
            // 空值覆盖 HTTP(S)_PROXY；只对应用回环代理禁用上游代理。
            "http.$prefix.proxy" to "",
        )
        appendConfiguration(environment, config)
        return environment
    }

    internal fun appendResourceConfiguration(environment: MutableMap<String, String>, budget: LinuxResourceBudget) {
        val bytes = budget.jobMemoryBudgetBytes
        val packThreads = if (bytes < 1024 * MIB) 1 else minOf(2, budget.effectiveThreads)
        appendConfiguration(environment, listOf(
            "pack.threads" to packThreads.toString(),
            "pack.windowMemory" to "${(bytes / (packThreads * 4) / MIB).coerceIn(32, 256)}m",
            "core.packedGitLimit" to "${(bytes / 4 / MIB).coerceIn(64, 512)}m",
            "gc.auto" to "0",
            "fetch.writeCommitGraph" to "false",
        ))
    }

    /** 追加 Git 的命令级配置，保留凭据重写和工作区信任的原有顺序。 */
    internal fun appendConfiguration(environment: MutableMap<String, String>, config: List<Pair<String, String>>) {
        if (config.isEmpty()) return
        val start = environment["GIT_CONFIG_COUNT"]?.let {
            it.toIntOrNull()?.takeIf { count -> count in 0..(Int.MAX_VALUE - config.size) } ?: return
        } ?: 0
        config.forEachIndexed { offset, (key, value) ->
            environment["GIT_CONFIG_KEY_${start + offset}"] = key
            environment["GIT_CONFIG_VALUE_${start + offset}"] = value
        }
        environment["GIT_CONFIG_COUNT"] = (start + config.size).toString()
    }
}
