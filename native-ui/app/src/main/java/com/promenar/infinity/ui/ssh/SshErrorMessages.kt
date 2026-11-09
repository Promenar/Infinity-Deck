package com.promenar.infinity.ui.ssh

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R

/** SSH 设置页把稳定错误码翻译成用户可读文案；未收录的代码显示通用文案并附代码，便于反馈。 */
internal object SshErrorMessages {
    /** 指向表单顶部字段（主机 ID、主机名、端口、用户名）的错误码。 */
    val CONNECTION_FIELD_CODES = setOf(
        "SSH_ALIAS_INVALID", "SSH_ALIAS_TAKEN", "SSH_ALIAS_IMMUTABLE",
        "SSH_HOST_INVALID", "SSH_PORT_INVALID", "SSH_USERNAME_INVALID",
    )
    val ALIAS_CODES = setOf("SSH_ALIAS_INVALID", "SSH_ALIAS_TAKEN", "SSH_ALIAS_IMMUTABLE")

    @StringRes
    fun resource(code: String): Int? = when (code) {
        "SSH_ALIAS_INVALID" -> R.string.ssh_error_alias_invalid
        "SSH_ALIAS_TAKEN" -> R.string.ssh_error_alias_taken
        "SSH_ALIAS_IMMUTABLE" -> R.string.ssh_error_alias_immutable
        "SSH_HOST_INVALID" -> R.string.ssh_error_host_invalid
        "SSH_PORT_INVALID" -> R.string.ssh_error_port_invalid
        "SSH_USERNAME_INVALID" -> R.string.ssh_error_username_invalid
        "SSH_FORWARD_INVALID" -> R.string.ssh_error_forward_invalid
        "SSH_KEY_REQUIRED" -> R.string.ssh_error_key_required
        "SSH_PASSWORD_REQUIRED" -> R.string.ssh_error_password_required
        "SSH_KEY_READ_FAILED" -> R.string.ssh_error_key_read_failed
        "SSH_KEY_TOO_LARGE" -> R.string.ssh_error_key_too_large
        "SSH_KEY_EMPTY" -> R.string.ssh_error_key_empty
        "SSH_KEY_CLIPBOARD_EMPTY" -> R.string.ssh_error_key_clipboard_empty
        "SSH_CREDENTIAL_MISSING", "CREDENTIAL_MISSING" -> R.string.ssh_error_credential_missing
        "SSH_HOST_CHANGED_RETRY", "HOST_CONFIGURATION_CHANGED" -> R.string.ssh_error_host_changed_retry
        "SSH_HOST_NOT_FOUND", "HOST_NOT_FOUND" -> R.string.ssh_error_host_not_found
        "UNSUPPORTED_KEY_FORMAT" -> R.string.ssh_error_unsupported_key_format
        "INVALID_PRIVATE_KEY" -> R.string.ssh_error_invalid_private_key
        "INVALID_KEY_PASSPHRASE" -> R.string.ssh_error_invalid_key_passphrase
        "UNSUPPORTED_USER_KEY", "UNSUPPORTED_KEY_CIPHER", "KEY_KDF_LIMIT" -> R.string.ssh_error_unsupported_user_key
        "KEY_GENERATION_FAILED" -> R.string.ssh_error_key_generation_failed
        "AUTH_REJECTED" -> R.string.ssh_error_auth_rejected
        "AUTH_OR_CONNECTION_FAILED", "CONNECTION_FAILED", "SSH_IO_FAILED" -> R.string.ssh_error_connection_failed
        "CONNECTION_REFUSED" -> R.string.ssh_error_connection_refused
        "HOST_UNREACHABLE" -> R.string.ssh_error_host_unreachable
        "DNS_FAILED" -> R.string.ssh_error_dns_failed
        "TIMEOUT" -> R.string.ssh_error_timeout
        "HOST_KEY_CHANGED" -> R.string.ssh_error_host_key_changed
        "HOST_KEY_UNTRUSTED" -> R.string.ssh_error_host_key_untrusted
        "UNSUPPORTED_HOST_KEY", "INVALID_HOST_KEY" -> R.string.ssh_error_unsupported_host_key
        "CONNECTION_LIMIT", "SESSION_LIMIT" -> R.string.ssh_error_connection_limit
        "FOREGROUND_SERVICE_UNAVAILABLE" -> R.string.ssh_error_foreground_service
        "FORWARD_NOT_CONFIGURED" -> R.string.ssh_error_forward_not_configured
        "HOST_UNAVAILABLE", "RUNTIME_CLOSED" -> R.string.ssh_error_host_unavailable
        "KEY_INSTALL_FAILED" -> R.string.ssh_error_key_install_failed
        "KEY_LOGIN_FAILED" -> R.string.ssh_error_key_login_failed
        "KEY_PASSPHRASE_REQUIRED" -> R.string.ssh_error_key_passphrase_required
        "EXPORT_PASSPHRASE_REQUIRED" -> R.string.ssh_error_export_passphrase_required
        "KEYCHAIN_WRITE_FAILED", "KEYCHAIN_ROLLBACK_FAILED" -> R.string.ssh_error_keychain_write_failed
        else -> null
    }

    fun message(context: Context, code: String): String =
        resource(code)?.let(context::getString) ?: context.getString(R.string.ssh_error_unknown, code)
}

@Composable
internal fun sshErrorText(code: String): String =
    SshErrorMessages.resource(code)?.let { stringResource(it) } ?: stringResource(R.string.ssh_error_unknown, code)
