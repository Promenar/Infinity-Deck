package com.promenar.infinity.data.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** 调用方在 IO 线程执行；只清理已由应用写入默认全局忽略文件的受管区块。 */
internal object LinuxLaunchGitIgnore {
    private const val START = "# >>> Infinity Deck workspace internal files >>>"
    private const val END = "# <<< Infinity Deck workspace internal files <<<"

    @Synchronized
    fun cleanupOnce(rootfs: File, environment: Map<String, String>): Boolean {
        val home = environment["HOME"] ?: "/root"
        val config = environment["XDG_CONFIG_HOME"]?.takeIf { it.isNotEmpty() } ?: "$home/.config"
        if (!config.startsWith('/') || config.split('/').any { it == ".." } || '\u0000' in config || '\\' in config) return false
        val relative = (config.split('/').filter { it.isNotEmpty() && it != "." } + listOf("git", "ignore")).joinToString("/")
        val digest = MessageDigest.getInstance("SHA-256").digest(relative.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val markerRelative = "var/lib/infinity/migrations/git-ignore-cleanup-$digest"
        // 用户提供的目录链接不由宿主跟随，也不阻止其启动 shell。
        val target = try { LinuxRootfsFiles.safePath(rootfs, relative) } catch (_: IllegalArgumentException) { return false }
        val marker = try { LinuxRootfsFiles.safePath(rootfs, markerRelative) } catch (_: IllegalArgumentException) { return false }
        if (Files.exists(marker.toPath(), NOFOLLOW_LINKS)) return false
        val exists = Files.exists(target.toPath(), NOFOLLOW_LINKS)
        if (exists && (!Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS) || target.length() > 1024 * 1024)) return false
        // 单字节解码仅用于定位 ASCII 标记，用户原有非 UTF-8 字节也逐字节保留。
        val original = if (exists) target.readBytes().toString(Charsets.ISO_8859_1) else ""
        val content = merge(original) ?: return false
        val changed = content != original
        if (changed) {
            val temporary = Files.createTempFile(target.parentFile!!.toPath(), "git-ignore-", ".tmp")
            try {
                Files.write(temporary, content.toByteArray(Charsets.ISO_8859_1))
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(target.toPath(), NOFOLLOW_LINKS))
                LinuxRootfsFiles.safePath(rootfs, relative)
                Files.move(temporary, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
        // 无受管区块也记为完成；用户随后写入、修改或删除 ignore 均不再干预。
        Files.createDirectories(marker.parentFile!!.toPath())
        LinuxRootfsFiles.safePath(rootfs, markerRelative)
        Files.createFile(marker.toPath())
        return changed
    }

    internal fun merge(original: String): String? {
        fun markers(marker: String) = Regex("(?m)^${Regex.escape(marker)}(?:\\r?\\n|$)").findAll(original).toList()
        val starts = markers(START)
        val ends = markers(END)
        if (starts.isEmpty() && ends.isEmpty()) return original
        if (starts.size != 1 || ends.size != 1 || starts.single().range.first >= ends.single().range.first) return null
        return original.substring(0, starts.single().range.first) + original.substring(ends.single().range.last + 1)
    }
}
