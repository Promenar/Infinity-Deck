package com.promenar.infinity.ui.keychain

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.keychain.sshKeyDeployment
import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.ssh.SshHostStore
import com.promenar.infinity.domain.keychain.DeployCredentialSource
import com.promenar.infinity.domain.keychain.DeployOptions
import com.promenar.infinity.domain.keychain.DeployStage
import com.promenar.infinity.domain.keychain.DeployTarget
import com.promenar.infinity.domain.keychain.ImportSshKeyResult
import com.promenar.infinity.domain.keychain.KeychainItem
import com.promenar.infinity.domain.keychain.KeychainMetadata
import com.promenar.infinity.domain.keychain.KeychainOrigin
import com.promenar.infinity.domain.keychain.KeychainRepository
import com.promenar.infinity.domain.keychain.SshKeyDeployment
import com.promenar.infinity.domain.keychain.SshKeyType
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 操作结果：成功带值，失败带稳定错误码，界面按 SSH 错误码表翻译。 */
sealed interface KeychainSshResult<out T> {
    data class Ok<T>(val value: T) : KeychainSshResult<T>
    data class Failed(val code: String) : KeychainSshResult<Nothing>
}

/** 部署面板里一台主机的进度；[stage] 为 null 表示尚未开始。 */
data class SshDeployHostState(val hostId: String, val alias: String, val stage: DeployStage? = null, val errorCode: String? = null) {
    val finished: Boolean get() = stage == DeployStage.COMPLETED || stage == DeployStage.FAILED
}

data class SshDeployRun(val entryRef: String, val hosts: List<SshDeployHostState>, val options: DeployOptions) {
    val finished: Boolean get() = hosts.all { it.finished }
    val succeeded: List<SshDeployHostState> get() = hosts.filter { it.stage == DeployStage.COMPLETED }
}

/** 部署前在面板里为一台主机选定的登录方式；一次性密码由调用方转交后清零。 */
sealed interface SshDeployLogin {
    data object StoredPassword : SshDeployLogin
    data object CurrentKey : SshDeployLogin
    class OneTimePassword(val chars: CharArray) : SshDeployLogin {
        override fun toString() = "OneTimePassword([已隐藏])"
    }
}

/**
 * SSH 密钥页、部署面板与主机选钥共用的状态与操作。所有口令与私钥字节只在调用期间存在，函数返回前清零；
 * 导出的私钥交给 [export] 的写入回调后立即清零。部署在 ViewModel 作用域内运行，关闭面板不会中断。
 */
class KeychainSshViewModel(
    private val repository: KeychainRepository,
    hostStore: SshHostStore,
    private val deployment: () -> SshKeyDeployment,
) : ViewModel() {
    val hosts: StateFlow<List<SshHost>> = hostStore.observeHosts()
        .catch {
            InfinityLogger.log("[Keychain] 读取 SSH 主机失败 type=${it.javaClass.simpleName}")
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _deploy = MutableStateFlow<SshDeployRun?>(null)
    val deploy: StateFlow<SshDeployRun?> = _deploy.asStateFlow()
    private var deployJob: Job? = null

    suspend fun generate(name: String, type: SshKeyType, passphrase: CharArray?): KeychainSshResult<KeychainItem> {
        val phrase = passphrase?.takeIf { it.isNotEmpty() }?.let(::encodeUtf8)
        passphrase?.fill('\u0000')
        return try {
            run { repository.generateSshKey(name.trim(), type, phrase) }
        } finally { phrase?.fill(0) }
    }

    /** 导入文件或剪贴板里的私钥；[privateKey] 在函数内清零。 */
    suspend fun import(name: String, privateKey: ByteArray, passphrase: CharArray?, origin: KeychainOrigin): KeychainSshResult<ImportSshKeyResult> {
        val phrase = passphrase?.takeIf { it.isNotEmpty() }?.let(::encodeUtf8)
        passphrase?.fill('\u0000')
        return try {
            run { repository.importSshKey(name.trim(), privateKey, phrase, origin) }
        } finally {
            privateKey.fill(0)
            phrase?.fill(0)
        }
    }

    /** 导出 OpenSSH 私钥；[newPassphrase] 为 null 时沿用私钥口令。[write] 返回后私钥字节清零。 */
    suspend fun export(ref: String, newPassphrase: CharArray?, write: suspend (ByteArray) -> Unit): KeychainSshResult<Unit> {
        val phrase = newPassphrase?.let(::encodeUtf8)
        newPassphrase?.fill('\u0000')
        return try {
            run {
                val bytes = repository.exportOpenSsh(ref, phrase)
                try { write(bytes) } finally { bytes.fill(0) }
            }
        } finally { phrase?.fill(0) }
    }

    /** 修改私钥口令；[newPassphrase] 为空表示去掉口令。 */
    suspend fun changePassphrase(ref: String, newPassphrase: CharArray?): KeychainSshResult<KeychainItem> {
        val phrase = newPassphrase?.takeIf { it.isNotEmpty() }?.let(::encodeUtf8)
        newPassphrase?.fill('\u0000')
        return try {
            run { repository.changePassphrase(ref, phrase) }
        } finally { phrase?.fill(0) }
    }

    suspend fun rename(item: KeychainItem, name: String): KeychainSshResult<KeychainItem> = run {
        repository.update(item.ref, KeychainMetadata(name.trim(), item.url, item.username, item.note, item.agentEnabled))
    }

    /**
     * 把 [entryRef] 的公钥部署到所选主机。一次性密码转成字节交给部署服务，原字符数组立即清零。
     * 已有进行中的部署时忽略新请求。
     */
    fun startDeploy(entryRef: String, logins: Map<SshHost, SshDeployLogin>, options: DeployOptions) {
        if (deployJob?.isActive == true || logins.isEmpty()) {
            logins.values.forEach { (it as? SshDeployLogin.OneTimePassword)?.chars?.fill('\u0000') }
            return
        }
        val targets = logins.map { (host, login) ->
            DeployTarget(
                host.id,
                when (login) {
                    SshDeployLogin.StoredPassword -> DeployCredentialSource.StoredPassword
                    SshDeployLogin.CurrentKey -> DeployCredentialSource.CurrentKey
                    is SshDeployLogin.OneTimePassword -> DeployCredentialSource.OneTimePassword(encodeUtf8(login.chars).also { login.chars.fill('\u0000') })
                },
            )
        }
        _deploy.value = SshDeployRun(entryRef, logins.keys.map { SshDeployHostState(it.id, it.alias) }, options)
        deployJob = viewModelScope.launch {
            try {
                deployment().deployPublicKey(entryRef, targets, options).collect { progress ->
                    _deploy.update { run ->
                        run?.copy(hosts = run.hosts.map {
                            if (it.hostId == progress.hostId) it.copy(stage = progress.stage, errorCode = progress.errorCode) else it
                        })
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val code = failure.sshCode()
                _deploy.update { run -> run?.copy(hosts = run.hosts.map { if (it.finished) it else it.copy(stage = DeployStage.FAILED, errorCode = code) }) }
            } finally {
                // 服务开始收集时已接管并清零一次性密码；未能开始时在此兜底清零。
                targets.forEach { (it.credentialSource as? DeployCredentialSource.OneTimePassword)?.bytes?.fill(0) }
            }
        }
    }

    /** 关闭已结束的部署结果。 */
    fun dismissDeploy() {
        if (deployJob?.isActive == true) return
        _deploy.value = null
    }

    private suspend fun <T> run(block: suspend () -> T): KeychainSshResult<T> = try {
        KeychainSshResult.Ok(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        InfinityLogger.log("[Keychain] SSH 密钥操作失败 code=${failure.sshCode()}")
        KeychainSshResult.Failed(failure.sshCode())
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as InfinityApplication
                return KeychainSshViewModel(app.keychainRepository, app.sshHostStore) {
                    sshKeyDeployment(app.sshHostStore, app.sshRuntime, app.sshUserPrompts)
                } as T
            }
        }
    }
}

/** 稳定错误码：SSH 失败沿用其代码，其余归为写入失败。 */
internal fun Throwable.sshCode(): String = (this as? SshFailure)?.code
    ?: (this as? com.promenar.infinity.ui.ssh.SshInputException)?.message
    ?: "KEYCHAIN_WRITE_FAILED"
