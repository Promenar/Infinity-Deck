package com.promenar.infinity.domain.keychain

import kotlinx.coroutines.flow.Flow

sealed interface ImportSshKeyResult {
    data class Imported(val item: KeychainItem) : ImportSshKeyResult
    data class AlreadyExists(val item: KeychainItem) : ImportSshKeyResult
    data object PassphraseRequired : ImportSshKeyResult
    data object WrongPassphrase : ImportSshKeyResult
    data class InvalidFormat(val code: String = "INVALID_PRIVATE_KEY") : ImportSshKeyResult
    data object UnsupportedKeyType : ImportSshKeyResult
}

sealed interface DeployCredentialSource {
    data object StoredPassword : DeployCredentialSource
    data object CurrentKey : DeployCredentialSource

    /** 冷流收集一次后即消耗输入；即使取消或主机不存在，输入字节也会被清零。 */
    class OneTimePassword(val bytes: ByteArray) : DeployCredentialSource {
        override fun toString() = "OneTimePassword([已隐藏])"
    }
}

data class DeployTarget(val hostId: String, val credentialSource: DeployCredentialSource)
data class DeployOptions(val switchToKey: Boolean = true, val deleteStoredPassword: Boolean = true)
enum class DeployStage { CONNECTING, WRITING_AUTHORIZED_KEYS, VERIFYING_KEY, COMPLETED, FAILED }
data class DeployProgress(val hostId: String, val stage: DeployStage, val errorCode: String? = null)

interface SshKeyDeployment {
    /** 每次调用返回的流只收集一次；开始收集时接管一次性密码，调用方应立即收集。 */
    fun deployPublicKey(entryId: String, targets: List<DeployTarget>, options: DeployOptions = DeployOptions()): Flow<DeployProgress>
}
