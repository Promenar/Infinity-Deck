package com.promenar.infinity.data.linux

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

data class LinuxDnsSnapshot(val servers: List<String>, val viaVpn: Boolean = false)
data class LinuxDnsResult(val servers: List<String>, val viaVpn: Boolean, val fallback: Boolean, val written: Boolean)

/** 标记包含正文摘要：用户保留首行但修改正文时，也不会覆盖。 */
class LinuxLaunchDns(
    private val rootfs: File,
    private val temporaryDirectory: File,
    private val source: () -> LinuxDnsSnapshot,
) {
    @Synchronized
    fun refresh(): LinuxDnsResult {
        val snapshot = source()
        val valid = snapshot.servers.filter { ADDRESS.matches(it) }
        val fallback = valid.isEmpty()
        val servers = valid.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }.distinct()
        val target = LinuxRootfsFiles.safePath(rootfs, "etc/resolv.conf")
        if (Files.exists(target.toPath(), NOFOLLOW_LINKS) && !isGenerated(target)) {
            return LinuxDnsResult(servers, snapshot.viaVpn, fallback, false)
        }
        Files.createDirectories(target.parentFile!!.toPath())
        Files.createDirectories(temporaryDirectory.toPath())
        // 临时文件位于 rootfs 之外，避免 guest 修改临时文件或植入链接。
        val temporary = Files.createTempFile(temporaryDirectory.toPath(), "resolv-", ".tmp")
        try {
            val body = servers.joinToString("\n", postfix = "\n") { "nameserver $it" }
            Files.write(temporary, (PREFIX + digest(body) + "\n" + body).toByteArray())
            // createTempFile 默认 0600；guest 中的非 root 用户也需要读取解析配置。
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r--r--"))
            LinuxRootfsFiles.safePath(rootfs, "etc/resolv.conf")
            Files.move(temporary, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        return LinuxDnsResult(servers, snapshot.viaVpn, fallback, true)
    }

    companion object {
        private const val PREFIX = "# Infinity Deck generated resolv.conf sha256="
        private val ADDRESS = Regex("[0-9a-fA-F:.]+(?:%[A-Za-z0-9_.-]+)?")
        internal fun isGenerated(file: File): Boolean {
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() > 64 * 1024) return false
            val text = file.readText()
            return text.substringBefore('\n') == PREFIX + digest(text.substringAfter('\n', ""))
        }
        private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        fun androidSource(context: Context): () -> LinuxDnsSnapshot = {
            runCatching {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                val vpn = manager.allNetworks.firstOrNull { network ->
                    manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                        !manager.getLinkProperties(network)?.dnsServers.isNullOrEmpty()
                }
                val network = vpn ?: manager.activeNetwork
                LinuxDnsSnapshot(network?.let(manager::getLinkProperties)?.dnsServers.orEmpty().mapNotNull { it.hostAddress }, vpn != null)
            }.getOrDefault(LinuxDnsSnapshot(emptyList()))
        }
    }
}
