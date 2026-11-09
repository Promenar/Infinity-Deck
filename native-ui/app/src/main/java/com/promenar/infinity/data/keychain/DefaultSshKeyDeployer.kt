package com.promenar.infinity.data.keychain

import com.promenar.infinity.data.security.SshCredential
import com.promenar.infinity.data.ssh.*
import com.promenar.infinity.domain.keychain.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** 只暴露固定公钥安装与登录验证，测试可替换运行时。凭据由调用方关闭。 */
interface SshKeyDeploymentRuntime {
    suspend fun probe(host: SshHost): SshHostKey
    suspend fun install(host: SshHost, credential: SshCredential, publicKey: String, connected: suspend () -> Unit)
    suspend fun verify(host: SshHost, credential: SshCredential)
}

class DefaultSshKeyDeployer(
    private val hosts: RoomSshHostStore,
    private val runtime: SshKeyDeploymentRuntime,
    private val confirmHostKey: suspend (SshHost, SshHostKey) -> Boolean,
) : SshKeyDeployment {
    override fun deployPublicKey(entryId: String, targets: List<DeployTarget>, options: DeployOptions): Flow<DeployProgress> {
        val requested = targets.toList()
        val collected = AtomicBoolean(false)
        return flow {
            check(collected.compareAndSet(false, true)) { "部署流只能收集一次" }
            // 先复制所有目标，再擦除原数组，允许调用方为多台主机提供同一个一次性密码数组。
            var owned = emptyList<DeployTarget>()
            try {
                owned = requested.map { target ->
                    val source = target.credentialSource
                    if (source is DeployCredentialSource.OneTimePassword) target.copy(credentialSource = DeployCredentialSource.OneTimePassword(source.bytes.copyOf())) else target
                }
                requested.forEach(::wipePassword)
                emitAll(flow {
                    for (target in owned) {
                        try {
                            emitAll(deployTarget(entryId, target, options).catch { failure ->
                                if (failure is CancellationException) throw failure
                                emit(DeployProgress(target.hostId, DeployStage.FAILED, (failure as? SshFailure)?.code ?: "DEPLOY_FAILED"))
                            })
                        } finally { wipePassword(target) }
                    }
                }.flowOn(Dispatchers.IO))
            } finally { requested.forEach(::wipePassword); owned.forEach(::wipePassword) }
        }
    }

    // 运行时在自己的 IO 与超时协程里回调 connected，进度改经通道发送，避免 flow 跨协程 emit。
    // 失败在生产者内部转成 FAILED 进度：生产者异常会取消转发协程，下游 catch 可能把它误判为下游异常而重新抛出。
    private fun deployTarget(entryId: String, target: DeployTarget, options: DeployOptions) = channelFlow {
        send(DeployProgress(target.hostId, DeployStage.CONNECTING))
        val failure = try {
            installAndCommit(entryId, target, options) { send(it) }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure
        }
        send(
            if (failure == null) DeployProgress(target.hostId, DeployStage.COMPLETED)
            else DeployProgress(target.hostId, DeployStage.FAILED, (failure as? SshFailure)?.code ?: "DEPLOY_FAILED"),
        )
    }

    private suspend fun installAndCommit(entryId: String, target: DeployTarget, options: DeployOptions, send: suspend (DeployProgress) -> Unit) {
        hosts.deploymentSnapshot(target.hostId).use { snapshot ->
            val original = snapshot.host
            val presented = runtime.probe(original)
            if (original.trustedFingerprint != null && original.trustedKeyType != null) {
                if (original.trustedFingerprint != presented.fingerprint || original.trustedKeyType != presented.type) throw SshFailure("HOST_KEY_CHANGED")
            } else if (!confirmHostKey(original, presented)) throw SshFailure("HOST_KEY_UNTRUSTED")
            val pinned = original.copy(trustedKeyType = presented.type, trustedFingerprint = presented.fingerprint)
            hosts.deploymentKey(entryId).use { key ->
                loginCredential(original, target.credentialSource).use { login ->
                    val loginHost = pinned.copy(authType = if (target.credentialSource == DeployCredentialSource.CurrentKey) SshAuthType.KEY else SshAuthType.PASSWORD)
                    runtime.install(loginHost, login, key.publicKey) {
                        send(DeployProgress(target.hostId, DeployStage.WRITING_AUTHORIZED_KEYS))
                    }
                }
                send(DeployProgress(target.hostId, DeployStage.VERIFYING_KEY))
                SshCredential(privateKey = key.credential.privateKey.copyOf(), passphrase = key.credential.passphrase.copyOf()).use { verification ->
                    runtime.verify(pinned.copy(authType = SshAuthType.KEY, keyEntryId = entryId), verification)
                }
                hosts.commitKeyDeployment(snapshot, entryId, key.fingerprint, presented, options)
            }
        }
    }

    private suspend fun loginCredential(host: SshHost, source: DeployCredentialSource): SshCredential = when (source) {
        is DeployCredentialSource.OneTimePassword -> {
            if (source.bytes.isEmpty()) throw SshFailure("CREDENTIAL_MISSING")
            SshCredential(password = source.bytes.copyOf())
        }
        DeployCredentialSource.StoredPassword -> hosts.storedPasswordForDeployment(host)
        DeployCredentialSource.CurrentKey -> {
            val stored = hosts.credential(host) ?: throw SshFailure("CREDENTIAL_MISSING")
            stored.use {
                if (host.authType != SshAuthType.KEY || stored.privateKey.isEmpty()) throw SshFailure("CREDENTIAL_MISSING")
                SshCredential(privateKey = stored.privateKey.copyOf(), passphrase = stored.passphrase.copyOf())
            }
        }
    }

    private fun wipePassword(target: DeployTarget) { (target.credentialSource as? DeployCredentialSource.OneTimePassword)?.bytes?.fill(0) }
}
