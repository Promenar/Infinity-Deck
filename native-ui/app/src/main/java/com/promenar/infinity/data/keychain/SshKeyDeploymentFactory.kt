package com.promenar.infinity.data.keychain

import com.promenar.infinity.data.security.SshCredential
import com.promenar.infinity.data.ssh.RoomSshHostStore
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.ssh.SshHostStore
import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.data.ssh.SshRuntime
import com.promenar.infinity.data.ssh.SshUserPromptBroker
import com.promenar.infinity.domain.keychain.SshKeyDeployment

/** 界面复用应用现有运行时和提示代理，所有网络调用均在部署服务的 IO 上下文执行。 */
fun sshKeyDeployment(hosts: SshHostStore, runtime: SshRuntime, prompts: SshUserPromptBroker): SshKeyDeployment =
    DefaultSshKeyDeployer(hosts as? RoomSshHostStore ?: throw SshFailure("KEYCHAIN_UNAVAILABLE"), object : SshKeyDeploymentRuntime {
        override suspend fun probe(host: SshHost) = runtime.probeOnce(host)
        override suspend fun install(host: SshHost, credential: SshCredential, publicKey: String, connected: suspend () -> Unit) =
            runtime.installPublicKeyOnce(host, credential, publicKey, connected)
        override suspend fun verify(host: SshHost, credential: SshCredential) = runtime.verifyKeyOnce(host, credential)
    }, prompts::confirmHostKey)
