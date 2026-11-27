package com.promenar.infinity.data.ssh

import com.promenar.infinity.domain.storage.SftpRemoteMountSpec

data class RemoteSha256Receipt(val sha256: String, val verificationMethod: String)

private val checksumMethods = setOf("sha256sum", "python3_hashlib", "shasum_a_256")
internal val sftpSha256Pattern = Regex("^[0-9a-fA-F]{64}$")

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

internal fun remoteChecksumCommand(path: String?): String {
    val input = path?.let { "p=" + shellQuote(SftpRemoteMountSpec.canonicalPath(it)) + "\n" }.orEmpty()
    val source = if (path == null) "/dev/null" else "\"\$p\""
    return input + """
        LC_ALL=C; export LC_ALL
        if command -v sha256sum >/dev/null 2>&1; then
          h=${'$'}(sha256sum < $source) || exit 1
          tool=sha256sum
        elif command -v python3 >/dev/null 2>&1 && python3 -c 'import hashlib' >/dev/null 2>&1; then
          h=${'$'}(python3 -c 'import hashlib,sys
        h=hashlib.sha256()
        for block in iter(lambda: sys.stdin.buffer.read(1048576), b""):
            h.update(block)
        print(h.hexdigest())' < $source) || exit 1
          tool=python3_hashlib
        elif command -v shasum >/dev/null 2>&1; then
          h=${'$'}(shasum -a 256 < $source) || exit 1
          tool=shasum_a_256
        else
          exit 127
        fi
        printf '%s\n%s\n' "${'$'}tool" "${'$'}{h%% *}"
    """.trimIndent()
}

internal fun remoteChecksumReceipt(output: SshExecOutput): RemoteSha256Receipt {
    if (output.timedOut) throw SshFailure("TIMEOUT")
    if (output.exitCode == -1 || output.exitCode == 126 || output.exitCode != 0 &&
        listOf(output.stdout, output.stderr).any { it.trim() == "This service allows sftp connections only." }
    ) throw SshFailure("EXEC_UNAVAILABLE")
    if (output.exitCode == 127 && output.stdout.isEmpty()) throw SshFailure("CHECKSUM_TOOL_UNAVAILABLE")
    if (output.exitCode != 0 || output.truncated) throw SshFailure("CHECKSUM_FAILED")
    val lines = output.stdout.removeSuffix("\n").split('\n')
    if (lines.size != 2 || lines[0] !in checksumMethods || !sftpSha256Pattern.matches(lines[1])) {
        throw SshFailure("CHECKSUM_FAILED")
    }
    return RemoteSha256Receipt(lines[1].lowercase(), "ssh_exec_${lines[0]}")
}
