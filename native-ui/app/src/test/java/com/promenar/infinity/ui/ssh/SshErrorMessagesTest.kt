package com.promenar.infinity.ui.ssh

import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

/** SSH 设置页可能出现的错误码都要有可读文案，不能把代码原样显示给用户。 */
class SshErrorMessagesTest {
    private val moduleRoot: Path = Path.of(System.getProperty("user.dir") ?: ".").let { root ->
        if (Files.isDirectory(root.resolve("src/main"))) root else root.resolve("app")
    }

    private fun source(relativePath: String): String =
        String(Files.readAllBytes(moduleRoot.resolve("src/main/java/com/promenar/infinity/$relativePath")), Charsets.UTF_8)

    @Test
    fun `表单与私钥载入抛出的错误码全部有文案`() {
        val texts = listOf("ui/ssh/SshHostDraft.kt", "ui/ssh/TerminalInput.kt", "ui/settings/SshSettingsScreen.kt", "ui/keychain/KeychainSshKeyScreen.kt")
            .map(::source)
        val codes = texts.flatMap { text ->
            Regex("""(?:(?:invalid|SshInputException|showError)\(|error = )"(SSH_[A-Z_]+)"""").findAll(text).map { it.groupValues[1] }.toList()
        }.toSet()
        assertWithMessage("应至少扫描到表单错误码").that(codes).containsAtLeast("SSH_ALIAS_INVALID", "SSH_ALIAS_TAKEN", "SSH_KEY_CLIPBOARD_EMPTY")
        assertWithMessage("缺少文案的错误码").that(codes.filter { SshErrorMessages.resource(it) == null }).isEmpty()
    }

    @Test
    fun `私钥校验、密钥生成与连接分类的错误码全部有文案`() {
        val runtimeCodes = listOf(
            "UNSUPPORTED_KEY_FORMAT", "INVALID_PRIVATE_KEY", "INVALID_KEY_PASSPHRASE", "UNSUPPORTED_USER_KEY",
            "UNSUPPORTED_KEY_CIPHER", "KEY_KDF_LIMIT", "KEY_GENERATION_FAILED", "CREDENTIAL_MISSING",
        )
        val classified = Regex(""""([A-Z_]{4,})"""").findAll(source("data/ssh/SshFailureClassifier.kt")).map { it.groupValues[1] }.toSet()
        val missing = (runtimeCodes + classified).filter { SshErrorMessages.resource(it) == null }
        assertWithMessage("缺少文案的错误码").that(missing).isEmpty()
    }
}
