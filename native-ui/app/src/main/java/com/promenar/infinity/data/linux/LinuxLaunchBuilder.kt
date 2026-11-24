package com.promenar.infinity.data.linux

import com.promenar.infinity.data.proxy.BuiltInProxyConfig
import com.promenar.infinity.data.proxy.BuiltInProxyEndpoint
import com.promenar.infinity.data.proxy.InstalledBuiltInProxyRouting
import com.promenar.infinity.data.tailnet.InstalledTailnetRouting
import com.promenar.infinity.data.exec.withLegacyWorkspaceAliases
import com.promenar.infinity.data.exec.PackagedHostTools
import com.promenar.infinity.data.git.remote.LinuxGitEnvironment
import com.promenar.infinity.domain.linux.LinuxLaunchSpec
import com.promenar.infinity.domain.linux.LinuxProcessLaunch
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId

/** 调用方在 IO 线程准备目录；参数逐项传递，不经过宿主 shell 拼接。 */
class LinuxLaunchBuilder(
    private val proot: PackagedProot,
    private val rootfs: File,
    private val cacheDirectory: File,
    private val tailnetEnvironment: () -> Map<String, String> = { InstalledTailnetRouting.routing.subprocessEnvironment() },
    private val messages: LinuxRuntimeMessages = LinuxRuntimeMessages(),
    private val builtInProxyEndpoint: () -> BuiltInProxyEndpoint? = { InstalledBuiltInProxyRouting.routing.endpoint.value },
    private val resourceBudget: () -> LinuxResourceBudget = { LinuxResourceGovernor.system.budget() },
    private val hostTools: PackagedHostTools = PackagedHostTools(proot.executable.absoluteFile.parentFile!!),
) {
    fun build(
        spec: LinuxLaunchSpec,
        maintainHomeConfiguration: Boolean = true,
        // Android 17 优先用 ptrace 路径，避开 proot 自身的 seccomp 加速兼容问题。
        prootNoSeccomp: Boolean = android.os.Build.VERSION.SDK_INT >= 37,
    ): LinuxProcessLaunch {
        val tmp = File(cacheDirectory, "proot-tmp")
        val shm = File(cacheDirectory, "proot-shm")
        listOf(tmp, shm).forEach { check(it.isDirectory || it.mkdirs()) { messages(LinuxRuntimeMessage.TEMP_DIRECTORY) } }
        val arguments = mutableListOf("--kill-on-exit", "-0", "--link2symlink", "-L", "--sysvipc", "-r", rootfs.path)
        listOf("/dev", "/proc", "/sys", "/dev/urandom:/dev/random", "${shm.path}:/dev/shm").forEach {
            arguments += listOf("-b", it)
        }
        spec.workspace?.let {
            require(it.isDirectory && '\u0000' !in it.path) { messages(LinuxRuntimeMessage.WORKSPACE_DIRECTORY) }
            arguments += listOf("-b", "${LinuxWorkspaceBindSource.path(it, cacheDirectory, messages(LinuxRuntimeMessage.WORKSPACE_DIRECTORY))}:/workspace")
        }
        spec.sessionHome?.let {
            require(Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) && ':' !in it.path && '\u0000' !in it.path) { messages(LinuxRuntimeMessage.WORKSPACE_DIRECTORY) }
            Files.createDirectories(LinuxRootfsFiles.safePath(rootfs, "home/session").toPath())
            arguments += listOf("-b", "${it.absolutePath}:/home/session")
        }
        spec.wakeInbox?.let {
            require(Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) && ':' !in it.path && '\u0000' !in it.path) { messages(LinuxRuntimeMessage.HOST_MOUNT) }
            Files.createDirectories(LinuxRootfsFiles.safePath(rootfs, LinuxWakeLimits.GUEST_DIRECTORY.removePrefix("/")).toPath())
            arguments += listOf("-b", "${it.absolutePath}:${LinuxWakeLimits.GUEST_DIRECTORY}")
        }
        if (spec.environment["GH_CONFIG_DIR"] == LinuxGhConfig.GUEST_CONFIG) {
            val ghDirectory = LinuxGhConfig.directory(cacheDirectory)
            require(Files.isDirectory(ghDirectory.toPath(), NOFOLLOW_LINKS) && ':' !in ghDirectory.path && '\u0000' !in ghDirectory.path) {
                messages(LinuxRuntimeMessage.HOST_MOUNT)
            }
            Files.createDirectories(LinuxRootfsFiles.safePath(rootfs, LinuxGhConfig.GUEST_DIRECTORY.removePrefix("/")).toPath())
            arguments += listOf("-b", "${ghDirectory.absolutePath}:${LinuxGhConfig.GUEST_DIRECTORY}")
        }
        spec.mounts.forEach { bind ->
            val guest = bind.guestPath
            require(guest.startsWith("/mnt/") && guest.split('/').none { it == ".." } &&
                guest.removePrefix("/mnt/").split('/').any { it.isNotEmpty() && it != "." } &&
                ':' !in guest && '\\' !in guest && '\u0000' !in guest) { messages(LinuxRuntimeMessage.MOUNT_TARGET) }
            require(!bind.readOnly) { messages(LinuxRuntimeMessage.READ_ONLY_MOUNT) }
            require(bind.host.exists() && ':' !in bind.host.path && '\u0000' !in bind.host.path) { messages(LinuxRuntimeMessage.HOST_MOUNT) }
            arguments += listOf("-b", "${bind.host.absolutePath}:$guest")
        }
        val bridge = LinuxToolBridgeConfig.bridgeDirectory(cacheDirectory)
        val bridgeEnabled = hostTools.isBridgeShimAvailable && !spec.environment[LinuxToolBridgeConfig.ENV_NONCE].isNullOrBlank() &&
            Files.isDirectory(bridge.toPath(), NOFOLLOW_LINKS)
        if (bridgeEnabled) {
            val socket = LinuxToolBridgeConfig.socket(cacheDirectory)
            LinuxToolBridgeConfig.requireSocketPath(socket)
            check(Files.readAttributes(socket.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).isOther) {
                "宿主工具 socket 未就绪。"
            }
            val bin = LinuxToolBridgeConfig.prepareBin(cacheDirectory, hostTools)
            for (guest in listOf(LinuxToolBridgeConfig.GUEST_BIN_DIR, LinuxToolBridgeConfig.GUEST_BRIDGE_DIR)) {
                Files.createDirectories(LinuxRootfsFiles.safePath(rootfs, guest.removePrefix("/")).toPath())
            }
            arguments += listOf("-b", "${bin.absolutePath}:${LinuxToolBridgeConfig.GUEST_BIN_DIR}",
                "-b", "${hostTools.bridgeShim.absolutePath}:${LinuxToolBridgeConfig.GUEST_SHIM}",
                "-b", "${bridge.absolutePath}:${LinuxToolBridgeConfig.GUEST_BRIDGE_DIR}")
        }
        val directory = spec.workingDirectory ?: if (spec.workspace != null) "/workspace" else "/root"
        require(directory.startsWith('/') && '\u0000' !in directory && directory.split('/').none { it == ".." }) { messages(LinuxRuntimeMessage.WORKING_DIRECTORY) }
        arguments += listOf("-w", directory)
        val bash = File(rootfs, "bin/bash")
        val shell = if (LinuxRootfsFiles.isGuestFile(rootfs, bash)) "/bin/bash" else "/bin/sh"
        arguments += spec.argv.ifEmpty {
            when {
                !bridgeEnabled -> listOf(shell, "-l")
                shell == "/bin/bash" -> listOf(shell, "--rcfile", LinuxToolBridgeConfig.GUEST_TERMINAL_PROFILE, "-i")
                else -> listOf(shell, "-i")
            }
        }
        val environment = linkedMapOf(
            "PROOT_LOADER" to proot.loader.path, "PROOT_TMP_DIR" to tmp.path,
            "HOME" to "/root", "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C.UTF-8", "TERM" to "xterm-256color", "PIP_BREAK_SYSTEM_PACKAGES" to "1",
            // 防止 Linux 会话内运行技能 Python 脚本时向受监控的 /mnt/skills 写入 __pycache__/*.pyc，
            // 导致误触发“改动了运行时关键数据”或脏文件；不影响经授权对 /mnt/skills 的显式管理。
            "PYTHONDONTWRITEBYTECODE" to "1",
            "PYTHONPYCACHEPREFIX" to "/tmp/pycache",
            "TZ" to LinuxLaunchTimezone.posix(ZoneId.systemDefault().rules, Instant.now()),
            // 与默认登录 shell 一致；脚本和编辑器据此启动子 shell。
            "SHELL" to shell,
        )
        if (spec.workspace != null) environment["INFINITY_WORKSPACE"] = "/workspace"
        environment.putAll(tailnetEnvironment().filterKeys { it in PROXY_KEYS })
        // 保留用户的标准代理变量。专用变量仅反映本次启动时有效的内置代理。
        environment.putAll(spec.environment.filterKeys { it !in BuiltInProxyConfig.environmentKeys })
        if (bridgeEnabled) {
            environment["PATH"] = LinuxToolBridgeConfig.GUEST_BIN_DIR + ":" + environment.getValue("PATH")
            environment[LinuxToolBridgeConfig.ENV_SOCKET] = LinuxToolBridgeConfig.GUEST_SOCKET
            if (spec.argv.isEmpty() && shell == "/bin/sh") environment["ENV"] = LinuxToolBridgeConfig.GUEST_TERMINAL_PROFILE
        } else {
            environment.remove(LinuxToolBridgeConfig.ENV_NONCE)
            environment.remove(LinuxToolBridgeConfig.ENV_SOCKET)
        }
        builtInProxyEndpoint()?.let { environment.putAll(BuiltInProxyConfig.environment(it)) }
        LinuxGitSafeDirectories.appendTo(environment, listOfNotNull("/workspace".takeIf { spec.workspace != null }) + spec.mounts.map { it.guestPath })
        if (spec.wakeInbox != null) environment[LinuxWakeLimits.ENVIRONMENT_VARIABLE] = LinuxWakeLimits.GUEST_DIRECTORY
        val budget = resourceBudget()
        budget.applyTo(environment)
        LinuxGitEnvironment.appendResourceConfiguration(environment, budget)
        if (prootNoSeccomp) environment["PROOT_NO_SECCOMP"] = "1" else environment.remove("PROOT_NO_SECCOMP")
        val runtimeEnvironment = environment.withLegacyWorkspaceAliases()
        // 旧受管忽略区块清理失败不阻止命令启动；修复启动不改 rootfs 配置。
        if (maintainHomeConfiguration) runCatching { LinuxLaunchGitIgnore.cleanupOnce(rootfs, runtimeEnvironment) }
        if (maintainHomeConfiguration) LinuxWakeScript.installForLaunch(rootfs)
        return LinuxProcessLaunch(proot.executable.path, arguments, runtimeEnvironment, spec.workspace ?: rootfs, budget.jobMemoryBudgetBytes)
    }

    private companion object {
        val PROXY_KEYS = setOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "all_proxy", "no_proxy")
    }
}

/** 宿主操作不跟随 rootfs 的目录链接；guest 可执行文件只接受解析后仍在 rootfs 内的链接。 */
internal object LinuxRootfsFiles {
    fun isGuestFile(root: File, file: File): Boolean = runCatching {
        val base = root.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(base, NOFOLLOW_LINKS))
        val path = file.toPath().toAbsolutePath().normalize()
        require(path.startsWith(base))
        val pending = ArrayDeque(base.relativize(path).map { it.toString() })
        var current = base
        var links = 0
        while (pending.isNotEmpty()) {
            when (val part = pending.removeFirst()) {
                "", "." -> Unit
                ".." -> current = if (current == base) base else current.parent
                else -> {
                    val next = current.resolve(part)
                    if (Files.isSymbolicLink(next)) {
                        require(++links <= 40) { "guest 链接循环或过深" }
                        val target = Files.readSymbolicLink(next)
                        if (target.isAbsolute) current = base
                        target.map { it.toString() }.asReversed().forEach { pending.addFirst(it) }
                    } else {
                        if (pending.isNotEmpty()) require(Files.isDirectory(next, NOFOLLOW_LINKS)) { "guest 路径的中间组件不是目录" }
                        current = next
                    }
                }
            }
        }
        Files.isRegularFile(current, NOFOLLOW_LINKS)
    }.getOrDefault(false)

    fun safePath(root: File, relative: String): File {
        val base = root.toPath().toAbsolutePath().normalize()
        val target = SafeTarExtractor.entryPath(base, relative)
        SafeTarExtractor.requireNoSymlinks(base, target)
        return target.toFile()
    }

    fun deleteTree(file: File) {
        if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) Files.walk(file.toPath()).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    /** guest 进程可能正在增删文件；统计时消失或不可读的条目按 0 计，不让整次统计失败。 */
    fun size(file: File): Long {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return 0
        var total = 0L
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (attributes.isRegularFile) total += attributes.size()
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(path: Path, error: IOException) = FileVisitResult.CONTINUE
            override fun postVisitDirectory(directory: Path, error: IOException?) = FileVisitResult.CONTINUE
        })
        return total
    }
}
