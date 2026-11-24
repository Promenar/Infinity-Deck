package com.promenar.infinity.data.linux

import com.promenar.infinity.data.exec.PackagedHostTools
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

internal object LinuxToolBridgeConfig {
    const val GUEST_BIN_DIR = "/run/infinity/bin"
    const val GUEST_SHIM = "$GUEST_BIN_DIR/nx-shim"
    const val GUEST_TERMINAL_PROFILE = "$GUEST_BIN_DIR/terminal-profile"
    const val GUEST_BRIDGE_DIR = "/run/infinity/bridge"
    const val GUEST_SOCKET = "$GUEST_BRIDGE_DIR/tools.sock"
    const val ENV_SOCKET = "INFINITY_TOOL_BRIDGE_SOCKET"
    const val ENV_NONCE = "INFINITY_TOOL_BRIDGE_NONCE"

    fun bridgeDirectory(cache: File) = File(cache, "tool-bridge")
    fun socket(cache: File) = File(bridgeDirectory(cache), "tools.sock")

    fun requireSocketPath(path: File) {
        require(path.absolutePath.toByteArray(Charsets.UTF_8).size < 108) { "宿主工具 socket 路径过长。" }
        require(!Files.isSymbolicLink(path.toPath())) { "宿主工具 socket 不能是符号链接。" }
    }

    fun prepareBridgeDirectory(cache: File): File = prepareDirectory(cache, bridgeDirectory(cache))

    @Synchronized
    fun prepareBin(cache: File, tools: PackagedHostTools, builtin: Boolean = false): File {
        val directory = prepareDirectory(cache, File(cache, if (builtin) "builtin-tool-shim-bin" else "linux-tool-shim-bin"))
        val links = if (builtin) mapOf("git" to tools.bridgeShim.absoluteFile.toPath()) else
            tools.availableTools().keys.associateWith { Paths.get("nx-shim") }
        val profile = directory.toPath().resolve("terminal-profile")
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) {
                if (!builtin && entry == profile && Files.isRegularFile(entry, NOFOLLOW_LINKS) &&
                    Files.size(entry) == terminalProfileBytes.size.toLong()
                ) {
                    val bytes = Files.readAllBytes(entry)
                    if (String(bytes, Charsets.UTF_8) == terminalProfile) continue
                    // 保留 readString 遇到非法 UTF-8 时抛出异常的行为。
                    Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
                }
                val target = links[entry.fileName.toString()]
                if (target != null && Files.isSymbolicLink(entry) && Files.readSymbolicLink(entry) == target) continue
                // 只删除受管目录中的单个残留条目，不跟随链接或递归进入目录。
                check(!Files.isDirectory(entry, NOFOLLOW_LINKS)) { "宿主工具 shim 目录存在未知子目录。" }
                Files.delete(entry)
            }
        }
        if (!builtin && !Files.exists(profile, NOFOLLOW_LINKS)) {
            Files.write(profile, terminalProfileBytes, StandardOpenOption.CREATE_NEW)
        }
        for ((name, target) in links) {
            val entry = directory.toPath().resolve(name)
            if (!Files.isSymbolicLink(entry)) Files.createSymbolicLink(entry, target)
        }
        return directory
    }

    // Alpine 登录配置可能重置 PATH。留在终端初始化序列内，在用户配置之后恢复受管入口。
    private val terminalProfile = """
        [ ! -r /etc/profile ] || . /etc/profile
        if [ -n "${'$'}{BASH_VERSION:-}" ] && [ -r "${'$'}HOME/.bash_profile" ]; then
            . "${'$'}HOME/.bash_profile"
        elif [ -n "${'$'}{BASH_VERSION:-}" ] && [ -r "${'$'}HOME/.bash_login" ]; then
            . "${'$'}HOME/.bash_login"
        elif [ -r "${'$'}HOME/.profile" ]; then
            . "${'$'}HOME/.profile"
        fi
        case "${'$'}PATH" in
            "$GUEST_BIN_DIR":*) ;;
            *) export PATH="$GUEST_BIN_DIR:${'$'}PATH" ;;
        esac
    """.trimIndent() + "\n"
    private val terminalProfileBytes = terminalProfile.toByteArray(Charsets.UTF_8)

    private fun prepareDirectory(cache: File, directory: File): File {
        require(Files.isDirectory(cache.toPath(), NOFOLLOW_LINKS)) { "宿主工具缓存目录无效。" }
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) Files.createDirectory(directory.toPath())
        require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) { "宿主工具受管目录不能是符号链接。" }
        return directory
    }
}
