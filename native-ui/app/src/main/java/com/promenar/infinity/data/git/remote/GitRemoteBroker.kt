package com.promenar.infinity.data.git.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一条命令的远端认证作用域：[environment] 合并进子进程环境，[close] 吊销 nonce。 */
interface GitRemoteLease : AutoCloseable {
    val environment: Map<String, String>
}

/** 每条 shell_exec / python_exec 命令开始前签发一次作用域，命令结束时关闭。 */
fun interface GitRemoteSessions {
    suspend fun open(): GitRemoteLease

    companion object {
        /** 未接入凭据代理时（测试、未配置）：不添加环境变量。 */
        val None = GitRemoteSessions { EmptyLease }
    }
}

private object EmptyLease : GitRemoteLease {
    override val environment: Map<String, String> = emptyMap()
    override fun close() = Unit
}

/**
 * 命令远端认证代理：懒启动 git HTTPS、GitHub API 代理与 SSH 桥，每条命令签发一次性 nonce。
 *
 * 交给子进程的环境变量（都不含令牌或私钥）：
 * - [ENV_NONCE]：本条命令的 nonce，命令结束即失效；
 * - [ENV_HTTP_PROXY]：HTTPS 凭据注入代理地址 `127.0.0.1:<端口>`；
 * - [ENV_CREDENTIAL_HOSTS]：已配置 Git 凭据的主机键，逗号分隔，只有这些主机的 HTTPS 请求改经代理；
 * - [ENV_SSH_BRIDGE]：SSH 桥地址 `127.0.0.1:<端口>`；
 * - [ENV_GH_SOCKET]：GitHub API 代理就绪后的 Linux socket 路径。
 *
 * 代理启动失败时不设置对应变量，内置 git 保持直连行为。
 */
class GitRemoteBroker(
    private val credentials: GitCredentialStore,
    private val nonces: GitNonceRegistry,
    private val proxy: GitCredentialProxy?,
    private val sshBridge: GitSshBridge?,
    private val diagnostics: (String) -> Unit = {},
    private val githubApi: GitHubApiEndpoint? = null,
) : GitRemoteSessions, AutoCloseable {
    override suspend fun open(): GitRemoteLease {
        var issued: GitNonceRegistry.Scope? = null
        try {
            return withContext(Dispatchers.IO) {
                val scope = nonces.issue().also { issued = it }
                val environment = linkedMapOf(ENV_NONCE to scope.nonce)
                val hosts = runCatching { credentials.hosts() }.getOrDefault(emptySet())
                proxy?.let { start("proxy", it::start) }?.let { port ->
                    environment[ENV_HTTP_PROXY] = "127.0.0.1:$port"
                    if (hosts.isNotEmpty()) environment[ENV_CREDENTIAL_HOSTS] = hosts.joinToString(",")
                }
                if (GitCredentialStore.GITHUB_HOST in hosts && githubApi != null) {
                    runCatching { githubApi.start() }.onSuccess {
                        environment[ENV_GH_SOCKET] = com.promenar.infinity.data.linux.LinuxGhConfig.GUEST_SOCKET
                        environment[ENV_CREDENTIAL_HOSTS] = hosts.joinToString(",")
                    }.onFailure { diagnostics("git-broker: gh START_FAILED") }
                }
                sshBridge?.let { start("ssh", it::start) }?.let { port -> environment[ENV_SSH_BRIDGE] = "127.0.0.1:$port" }
                object : GitRemoteLease {
                    override val environment: Map<String, String> = environment
                    override fun close() = scope.close()
                }
            }
        } catch (failure: Throwable) {
            // 切回调用方协程时也可能取消，尚未交付的租约仍由 broker 负责。
            issued?.close()
            throw failure
        }
    }

    private fun start(name: String, block: () -> Int): Int? = runCatching(block)
        .onFailure { failure -> diagnostics("git-broker: $name ${failure.javaClass.simpleName}") }
        .getOrNull()

    override fun close() {
        proxy?.close()
        sshBridge?.close()
        githubApi?.close()
    }

    companion object {
        const val ENV_NONCE = "INFINITY_GIT_NONCE"
        const val ENV_HTTP_PROXY = "INFINITY_GIT_PROXY"
        const val ENV_CREDENTIAL_HOSTS = "INFINITY_GIT_CREDENTIAL_HOSTS"
        const val ENV_SSH_BRIDGE = "INFINITY_GIT_SSH_BRIDGE"
        const val ENV_GH_SOCKET = "INFINITY_GH_SOCKET"
        /** SshRuntime 连接池中内置 git 使用的所有者。 */
        const val SSH_OWNER = "git"
    }
}
