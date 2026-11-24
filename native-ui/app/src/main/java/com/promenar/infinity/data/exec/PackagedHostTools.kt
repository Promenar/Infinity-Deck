package com.promenar.infinity.data.exec

import java.io.File

/** 以安装后的 APK 原生库为准。工具查找只接受固定名称。 */
class PackagedHostTools(private val nativeLibraryDir: File) {
    val gitRemoteHttps: File get() = File(nativeLibraryDir, "libnxgit-remote-https.so")
    val gitSsh: File get() = File(nativeLibraryDir, "libnxgitssh.so")
    val bridgeShim: File get() = File(nativeLibraryDir, "libnxtoolshim.so")

    val isGitAvailable: Boolean get() = library("git").isFile && gitRemoteHttps.isFile && gitSsh.isFile
    val isBridgeShimAvailable: Boolean get() = bridgeShim.isFile

    fun availableTools(): Map<String, File> = buildMap {
        for (name in TOOLS) {
            val file = library(name)
            if (if (name == "git") isGitAvailable else file.isFile) put(name, file)
        }
    }

    private fun library(name: String): File = File(nativeLibraryDir, "libnx$name.so")

    internal companion object {
        val TOOLS = setOf("git", "ffmpeg", "ffprobe", "rg", "fd", "jq", "sqlite3", "zstd", "bsdtar", "rsync")
    }
}
