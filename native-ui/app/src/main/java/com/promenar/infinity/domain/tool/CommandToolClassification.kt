package com.promenar.infinity.domain.tool

enum class CommandToolBackend { BUILTIN_SHELL, BUILTIN_PYTHON, LINUX, TERMUX, SSH }

object CommandToolClassification {
    fun backendFor(toolName: String, usesLinux: Boolean = false): CommandToolBackend? = when (toolName) {
        "shell_exec" -> if (usesLinux) CommandToolBackend.LINUX else CommandToolBackend.BUILTIN_SHELL
        GitExecArguments.TOOL_NAME -> CommandToolBackend.BUILTIN_SHELL
        "job_input" -> CommandToolBackend.LINUX
        "python_exec" -> if (usesLinux) CommandToolBackend.LINUX else CommandToolBackend.BUILTIN_PYTHON
        "termux_exec" -> CommandToolBackend.TERMUX
        "ssh_exec", "ssh_session_open", "ssh_session_send", "sftp_upload", "sftp_download" -> CommandToolBackend.SSH
        "ask_user", "request_secret", "show_secret" -> null
        else -> null
    }
}
