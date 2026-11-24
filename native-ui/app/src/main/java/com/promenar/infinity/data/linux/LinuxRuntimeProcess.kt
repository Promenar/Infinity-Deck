package com.promenar.infinity.data.linux

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.R
import com.promenar.infinity.data.exec.*
import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

interface LinuxRuntimePreferences {
    fun readAgentEnabled(): Boolean
    fun writeAgentEnabled(enabled: Boolean)
}

/** 应用级安装任务与独立命令共享基础系统；文件与进程操作均在 IO 调度器执行。 */
class ProcessLinuxRuntime(
    private val scope: CoroutineScope,
    override val paths: LinuxPaths,
    private val installer: LinuxRootfsInstaller,
    private val proot: PackagedProot,
    private val launchBuilder: LinuxLaunchBuilder,
    private val dns: LinuxLaunchDns,
    private val preferences: LinuxRuntimePreferences,
    private val keepAlive: ExecKeepAlive,
    private val packages: () -> LinuxPackageManager,
    private val mirrors: () -> LinuxMirrorSettings,
    private val execute: suspend (LinuxProcessLaunch, Int) -> BuiltinCommandOutput,
    private val operationSummary: (maintenance: Boolean) -> String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val messages: LinuxRuntimeMessages = LinuxRuntimeMessages(),
) : LinuxRuntime {
    private val mutableState = MutableStateFlow<LinuxRuntimeState>(LinuxRuntimeState.NotInstalled(0))
    override val state = mutableState.asStateFlow()
    private val mutableAgentEnabled = MutableStateFlow(true)
    override val agentEnabled = mutableAgentEnabled.asStateFlow()
    private val lifecycle = Mutex()
    @Volatile private var installJob: Job? = null
    @Volatile private var lastDns: LinuxDnsResult? = null
    @Volatile private var bootstrapProgress: LinuxInstallProgress? = null
    @Volatile private var preserveHomeDuringRepair = false
    private var lastDiagnostics: LinuxDiagnostics? = null

    init {
        scope.launch(dispatcher) {
            lifecycle.withLock {
                mutableAgentEnabled.value = preferences.readAgentEnabled()
                // IO 后原子发布，不能覆盖读取期间已发起的操作或取消结果。
                val initial = LinuxRuntimeState.NotInstalled(0)
                if (mutableState.value == initial) {
                    val restored = deriveState()
                    mutableState.compareAndSet(initial, restored)
                }
            }
        }
    }

    override suspend fun setAgentEnabled(enabled: Boolean) = withContext(dispatcher) {
        lifecycle.withLock { preferences.writeAgentEnabled(enabled); mutableAgentEnabled.value = enabled }
    }

    @Synchronized
    override fun startInstall(options: LinuxInstallOptions) {
        if (installJob?.isCompleted == false || state.value is LinuxRuntimeState.Ready || state.value is LinuxRuntimeState.Maintaining) return
        mutableState.value = LinuxRuntimeState.Installing(emptyProgress())
        val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            lifecycle.withLock { install(options) }
        }
        installJob = job
        job.start()
    }

    @Synchronized
    override fun cancelInstall() {
        val installing = state.value as? LinuxRuntimeState.Installing ?: return
        mutableState.value = LinuxRuntimeState.InstallInterrupted(interruptedProgress(installing.progress), null)
        installJob?.cancel()
    }

    private suspend fun install(options: LinuxInstallOptions, maintenance: Boolean = false, repairing: Boolean = false) {
        var progress = emptyProgress()
        fun publish() {
            bootstrapProgress = progress
            mutableState.value = if (maintenance) LinuxRuntimeState.Maintaining(LinuxMaintenance.RESETTING, progress)
                else LinuxRuntimeState.Installing(progress)
        }
        fun update(step: LinuxInstallStep, status: LinuxStepStatus, detail: LinuxStepDetail? = null, duration: Long? = null, output: String = "") {
            val steps = progress.steps.map { if (it.step == step) LinuxInstallStepState(step, status, duration, detail) else it }
            val warning = if (lastDns?.fallback == true) messages(LinuxRuntimeMessage.DNS_FALLBACK) else ""
            progress = LinuxInstallProgress(steps, steps.count { it.status in TERMINAL }.toFloat() / steps.size,
                listOf(output.takeLast(3800), warning).filter { it.isNotBlank() }.joinToString("\n"))
            publish()
        }
        try {
            val owner = currentCoroutineContext().job
            keepAlive.hold(ExecRunInfo(UUID.randomUUID().toString(), ExecRunKind.LINUX,
                operationSummary(maintenance), 0, System.currentTimeMillis()), { owner.cancel() }) {
                if (!proot.isAvailable) throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.PROOT_MISSING))
                val existing = runCatching { installer.readRecord() }.getOrNull()
                val repairBaseOnly = repairing || (existing == null && java.nio.file.Files.exists(paths.rootfs.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                preserveHomeDuringRepair = repairBaseOnly
                progress = progress.copy(steps = progress.steps.map { step ->
                    step.copy(status = existing?.steps?.get(step.step)?.takeIf { it in TERMINAL } ?: LinuxStepStatus.PENDING)
                })
                publish()
                for (step in LinuxInstallStep.entries) {
                    currentCoroutineContext().ensureActive()
                    if (progress.steps.first { it.step == step }.status in TERMINAL) continue
                    val started = System.nanoTime()
                    update(step, LinuxStepStatus.RUNNING)
                    var detail: LinuxStepDetail? = null
                    var status = LinuxStepStatus.DONE
                    when (step) {
                        LinuxInstallStep.UNPACK -> {
                            if (!proot.isAvailable) throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.PROOT_MISSING))
                            val record = runInterruptible { installer.install() }
                            detail = LinuxStepDetail.Unpacked(record.baseBytes, true)
                        }
                        LinuxInstallStep.NETWORK -> {
                            val result = refreshDns()
                            try {
                                val mirror = mirrors()
                                if (!repairBaseOnly) {
                                    mirror.select(mirror.selected.value)
                                    installer.rememberGeneratedConfiguration("etc/apk/repositories")
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.NETWORK, error.message), error) }
                            val proxy = buildLaunch(LinuxLaunchSpec(emptyList())).environment.keys.any { it.equals("HTTPS_PROXY", true) || it.equals("ALL_PROXY", true) }
                            detail = LinuxStepDetail.Network(result.servers, result.viaVpn, proxy)
                        }
                        LinuxInstallStep.COMMON_TOOLS -> {
                            coroutineScope {
                                val manager = packages()
                                val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                                    manager.operation.collect { operation ->
                                        if (operation != null) update(step, LinuxStepStatus.RUNNING,
                                            LinuxStepDetail.Downloading(mirrors().selected.value.id, operation.downloadedBytes ?: 0, operation.totalBytes),
                                            output = operation.outputTail)
                                    }
                                }
                                try {
                                    val installed = if (repairBaseOnly) checkPackageStateForRepair() else emptySet()
                                    val names = if (repairBaseOnly) LinuxPackagePresets.BASE else options.packages
                                    val toInstall = if (repairBaseOnly) names.filterNot { it in installed } else names
                                    if (toInstall.isNotEmpty()) {
                                        val result = manager.install(toInstall, LinuxPackageOrigin.Preset)
                                        if (!result.success) throw LinuxRootfsException(result.failure ?: LinuxFailure(LinuxFailureCode.PACKAGE_INSTALL_FAILED, result.output.takeLast(4096)))
                                    }
                                    // 已登记的包直接 fix，保留来源；全部基础包均检查，避免只看入口文件而漏掉损坏依赖。
                                    if (repairBaseOnly) {
                                        val fixed = executeObserved(buildLaunch(LinuxLaunchSpec(listOf("/sbin/apk", "--no-interactive", "--no-progress", "fix", "--reinstall", "--depends", "--") + names)), 120)
                                        if (fixed.exitCode != 0 || fixed.timedOut || fixed.stoppedByUser) {
                                            val reason = when {
                                                fixed.timedOut -> "软件包修复超时"
                                                fixed.stoppedByUser -> "用户终止了软件包修复"
                                                else -> "exit=${fixed.exitCode}"
                                            }
                                            throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.PACKAGE_INSTALL_FAILED,
                                                listOf(fixed.stdout, fixed.stderr, reason).filter { it.isNotBlank() }.joinToString("\n").takeLast(4096)))
                                        }
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: LinuxRootfsException) { throw error }
                                catch (error: Exception) { throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.PACKAGE_INSTALL_FAILED, error.message), error) }
                                finally { observer.cancelAndJoin() }
                            }
                        }
                        LinuxInstallStep.WORKSPACE -> installer.createGuestDirectories()
                        LinuxInstallStep.DIAGNOSTICS -> {
                            val diagnostics = diagnose()
                            if (!diagnostics.passed) throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.DIAGNOSTICS_FAILED, diagnostics.checks.filterNot { it.passed }.joinToString { it.id }))
                            detail = LinuxStepDetail.Diagnostics(diagnostics.startupMs ?: 0)
                        }
                    }
                    installer.completeStep(step, status)
                    update(step, status, detail, (System.nanoTime() - started) / 1_000_000)
                }
                mutableState.value = LinuxRuntimeState.Ready(environmentInfo(checkNotNull(installer.readRecord())))
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = LinuxRuntimeState.InstallInterrupted(interruptedProgress(progress), null)
            throw cancelled
        } catch (error: Exception) {
            val failure = (error as? LinuxRootfsException)?.failure ?: LinuxFailure(LinuxFailureCode.IO, error.message)
            val failed = progress.steps.map { if (it.status == LinuxStepStatus.RUNNING) it.copy(status = LinuxStepStatus.FAILED) else it }
            mutableState.value = LinuxRuntimeState.InstallInterrupted(progress.copy(steps = failed), failure)
        } finally { bootstrapProgress = null; preserveHomeDuringRepair = false }
    }

    private fun checkPackageStateForRepair(): Set<String> {
        fun damaged(): Nothing = throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.ROOTFS_DAMAGED,
            "软件包状态缺失或格式损坏；已保留用户文件，请导出或手动处理"))
        try {
            for (relative in listOf("etc/apk/world", "lib/apk/db/installed")) {
                val file = LinuxPackageStorage.checked(paths.rootfs, relative)
                if (!java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) damaged()
            }
            val names = mutableSetOf<String>()
            var name: String? = null
            var version: String? = null
            var fields = 0
            fun finish() {
                if (fields == 0) return
                val packageName = name ?: damaged()
                if (!LinuxPackageParsing.validName(packageName) || version.isNullOrBlank() || !names.add(packageName)) damaged()
                name = null; version = null; fields = 0
            }
            LinuxPackageStorage.read(paths.rootfs, "lib/apk/db/installed").lineSequence().forEach { line ->
                if (line.isBlank()) finish()
                else {
                    if (line.length < 2 || !line[0].isLetter() || line[1] != ':') damaged()
                    fields++
                    when (line[0]) {
                        'P' -> { if (name != null) damaged(); name = line.substring(2) }
                        'V' -> { if (version != null) damaged(); version = line.substring(2) }
                    }
                }
            }
            finish()
            if (names.isEmpty()) damaged()
            return names
        } catch (error: LinuxRootfsException) { throw error }
        catch (_: Exception) { damaged() }
    }

    private fun buildLaunch(spec: LinuxLaunchSpec) = launchBuilder.build(spec, maintainHomeConfiguration = !preserveHomeDuringRepair)

    override suspend fun repair(): LinuxRepairResult = withContext(dispatcher) {
        lifecycle.withLock {
            if (state.value is LinuxRuntimeState.Ready) return@withLock LinuxRepairResult(true)
            if (state.value is LinuxRuntimeState.Unavailable || state.value is LinuxRuntimeState.Maintaining) {
                return@withLock LinuxRepairResult(false, LinuxFailure(LinuxFailureCode.ROOTFS_DAMAGED, "Linux 环境当前无法修复"))
            }
            mutableState.value = LinuxRuntimeState.Installing(emptyProgress())
            val owner = currentCoroutineContext().job
            installJob = owner
            try {
                if (!proot.isAvailable) throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.PROOT_MISSING))
                runInterruptible { installer.repairMissingBaseFiles() }
                install(LinuxInstallOptions(false), repairing = true)
                when (val result = state.value) {
                    is LinuxRuntimeState.Ready -> LinuxRepairResult(true)
                    is LinuxRuntimeState.InstallInterrupted -> LinuxRepairResult(false, result.failure)
                    is LinuxRuntimeState.Broken -> LinuxRepairResult(false, result.failure)
                    else -> LinuxRepairResult(false, LinuxFailure(LinuxFailureCode.IO, "Linux 修复未完成"))
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = LinuxRuntimeState.InstallInterrupted(emptyProgress(), null)
                throw cancelled
            } catch (error: Exception) {
                val failure = (error as? LinuxRootfsException)?.failure ?: LinuxFailure(LinuxFailureCode.IO, error.message)
                mutableState.value = LinuxRuntimeState.Broken(failure)
                LinuxRepairResult(false, failure)
            } finally { if (installJob === owner) installJob = null }
        }
    }

    override suspend fun prepareLaunch(spec: LinuxLaunchSpec): LinuxProcessLaunch = withContext(dispatcher) {
        val current = state.value
        // 包安装和镜像提供者在引导阶段也通过同一接口运行；解包完成前不能启动。
        val installing = current as? LinuxRuntimeState.Installing
        val bootstrapping = installing?.progress ?: bootstrapProgress?.takeIf {
            (current as? LinuxRuntimeState.Maintaining)?.operation == LinuxMaintenance.RESETTING
        }
        val canBootstrap = bootstrapping?.steps?.firstOrNull()?.status == LinuxStepStatus.DONE
        if (current !is LinuxRuntimeState.Ready && !canBootstrap) throw LinuxUnavailableException(current)
        checkHealthy()
        refreshDns()
        buildLaunch(spec)
    }

    override suspend fun runDiagnostics(): LinuxDiagnostics = withContext(dispatcher) {
        lifecycle.withLock {
            if (state.value !is LinuxRuntimeState.Ready) throw LinuxUnavailableException(state.value)
            val result = diagnose()
            val record = checkNotNull(installer.readRecord())
            mutableState.value = if (result.passed) LinuxRuntimeState.Ready(environmentInfo(record))
                else LinuxRuntimeState.Broken(LinuxFailure(LinuxFailureCode.DIAGNOSTICS_FAILED))
            result
        }
    }

    private suspend fun diagnose(): LinuxDiagnostics {
        checkHealthy()
        val network = refreshDns()
        val started = System.nanoTime()
        val shell = diagnosticCheck("shell", "/bin/sh -c true")
        val startupMs = (System.nanoTime() - started) / 1_000_000
        val release = diagnosticCheck("release", "cat /etc/alpine-release") { it.trim() == installer.metadata.version }
        val bash = diagnosticCheck("bash", "/bin/bash -c true")
        val curl = diagnosticCheck("curl", "/usr/bin/curl --version") { it.startsWith("curl ") }
        val dnsCheck = diagnosticCheck("dns", "if command -v nslookup >/dev/null 2>&1; then nslookup dl-cdn.alpinelinux.org; else getent hosts dl-cdn.alpinelinux.org; fi")
        val source = LinuxDiagnosticCheck("dns-source", !network.fallback, messages(
            when { network.fallback -> LinuxRuntimeMessage.DNS_PUBLIC; network.viaVpn -> LinuxRuntimeMessage.DNS_VPN; else -> LinuxRuntimeMessage.DNS_ACTIVE },
            network.servers.joinToString(),
        ))
        val certificates = LinuxDiagnosticCheck("ca-certificates", LinuxRootfsFiles.isGuestFile(paths.rootfs, File(paths.rootfs, "etc/ssl/certs/ca-certificates.crt")), messages(LinuxRuntimeMessage.ALPINE_CA))
        return LinuxDiagnostics(shell.passed && release.passed && bash.passed && curl.passed && certificates.passed, System.currentTimeMillis(), startupMs,
            listOf(shell, release, bash, curl, dnsCheck, source, certificates)).also { lastDiagnostics = it }
    }

    private suspend fun diagnosticCheck(id: String, command: String, validate: (String) -> Boolean = { true }): LinuxDiagnosticCheck = try {
        val output = executeObserved(buildLaunch(LinuxLaunchSpec.shell(command)), 15)
        LinuxDiagnosticCheck(id, output.exitCode == 0 && !output.timedOut && validate(output.stdout),
            (output.stdout + output.stderr).takeLast(4096).ifBlank { if (output.timedOut) messages(LinuxRuntimeMessage.TIMEOUT) else "exit=${output.exitCode}" })
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { LinuxDiagnosticCheck(id, false, error.message) }

    private suspend fun executeObserved(launch: LinuxProcessLaunch, timeoutSeconds: Int): BuiltinCommandOutput =
        observeLinuxExecution(currentCoroutineContext()[ToolCallControlContext]?.sessionId, {
            LinuxExecutionStatus(it.exitCode, it.timedOut)
        }) { execute(launch, timeoutSeconds) }

    override suspend fun storageUsage(): LinuxStorageUsage = withContext(dispatcher) { lifecycle.withLock { installer.storageUsage() } }
    override suspend fun clearDownloadCache(): Long = withContext(dispatcher) { lifecycle.withLock { installer.clearDownloadCache() } }

    override suspend fun reset(): Unit = withContext(dispatcher) {
        cancelInstall(); installJob?.join()
        lifecycle.withLock {
            mutableState.value = LinuxRuntimeState.Maintaining(LinuxMaintenance.RESETTING)
            try {
                runInterruptible { installer.install(force = true, preserveConfiguration = true) }
                // 重置补齐基础包，不自动恢复附加包；生成配置由安装器按内容摘要保留。
                install(LinuxInstallOptions(false), maintenance = true)
            } catch (cancelled: CancellationException) { mutableState.value = deriveState(); throw cancelled }
            catch (error: Exception) { mutableState.value = LinuxRuntimeState.Broken((error as? LinuxRootfsException)?.failure ?: LinuxFailure(LinuxFailureCode.IO, error.message)) }
        }
    }

    override suspend fun uninstall(): Unit = withContext(dispatcher) {
        cancelInstall(); installJob?.join()
        lifecycle.withLock {
            mutableState.value = LinuxRuntimeState.Maintaining(LinuxMaintenance.UNINSTALLING)
            try {
                runInterruptible { installer.uninstall() }
                lastDiagnostics = null
                mutableState.value = LinuxRuntimeState.NotInstalled(installer.freeBytes())
            } catch (cancelled: CancellationException) { mutableState.value = deriveState(); throw cancelled }
            catch (error: Exception) { mutableState.value = LinuxRuntimeState.Broken(LinuxFailure(LinuxFailureCode.IO, error.message)) }
        }
    }

    private fun refreshDns(): LinuxDnsResult = dns.refresh().also { lastDns = it }

    private fun checkHealthy() {
        val failure = when {
            !proot.isAvailable -> LinuxFailure(LinuxFailureCode.PROOT_MISSING)
            !LinuxRootfsFiles.isGuestFile(paths.rootfs, File(paths.rootfs, "bin/sh")) -> LinuxFailure(LinuxFailureCode.ROOTFS_DAMAGED)
            else -> null
        }
        if (failure != null) {
            mutableState.value = LinuxRuntimeState.Broken(failure)
            throw LinuxUnavailableException(state.value)
        }
    }

    private fun deriveState(): LinuxRuntimeState = try {
        val record = installer.readRecord()
        when {
            !proot.isAvailable -> LinuxRuntimeState.Broken(LinuxFailure(LinuxFailureCode.PROOT_MISSING))
            record == null -> LinuxRuntimeState.NotInstalled(installer.freeBytes())
            record.steps.size == LinuxInstallStep.entries.size && record.steps.values.all { it in TERMINAL } -> LinuxRuntimeState.Ready(environmentInfo(record))
            else -> LinuxRuntimeState.InstallInterrupted(emptyProgress().let { progress ->
                progress.copy(steps = progress.steps.map { it.copy(status = record.steps[it.step] ?: LinuxStepStatus.PENDING) })
            }, null)
        }
    } catch (error: Exception) { LinuxRuntimeState.Broken((error as? LinuxRootfsException)?.failure ?: LinuxFailure(LinuxFailureCode.IO, error.message)) }

    private fun environmentInfo(record: LinuxRootfsRecord) = LinuxEnvironmentInfo("Alpine Linux", installer.metadata.version,
        "aarch64", installer.metadata.prootVersion, record.installedAtMs, lastDiagnostics)

    companion object {
        // Linux 执行开关属于本机能力；独立偏好文件不在应用备份命名空间内。
        internal const val PREFERENCES_NAME = "infinity_linux_runtime"
        internal const val KEY_AGENT_ENABLED = "agent_enabled"
        private val TERMINAL = setOf(LinuxStepStatus.DONE, LinuxStepStatus.SKIPPED)
        private fun emptyProgress() = LinuxInstallProgress(LinuxInstallStep.entries.map { LinuxInstallStepState(it, LinuxStepStatus.PENDING) }, 0f)
        private fun interruptedProgress(progress: LinuxInstallProgress) = progress.copy(steps = progress.steps.map {
            if (it.status == LinuxStepStatus.RUNNING) it.copy(status = LinuxStepStatus.PENDING) else it
        })

        fun create(context: Context, scope: CoroutineScope, paths: LinuxPaths, keepAlive: ExecKeepAlive,
            packages: () -> LinuxPackageManager, mirrors: () -> LinuxMirrorSettings): ProcessLinuxRuntime {
            val messages = LinuxRuntimeMessages.android(context)
            val proot = PackagedProot(File(context.applicationInfo.nativeLibraryDir))
            val metadata = LinuxRootfsMetadata(BuildConfig.LINUX_ALPINE_VERSION, BuildConfig.LINUX_ROOTFS_SHA256,
                BuildConfig.LINUX_ROOTFS_ASSET, BuildConfig.LINUX_PROOT_VERSION, BuildConfig.LINUX_ROOTFS_UNPACKED_BYTES)
            val installer = LinuxRootfsInstaller(paths, metadata, { context.assets.open(metadata.asset) },
                { StatFs(context.filesDir.path).availableBytes }, SafeTarExtractor(
                    symlink = { target, file -> Os.symlink(target, file.path) }, chmod = { file, mode -> Os.chmod(file.path, mode) },
                ), messages = messages)
            val runner = BuiltinCommandRunner(listOf("/system/bin/setsid"), "/system/bin/sh", AndroidProcessGroupSignaller,
                { File(context.cacheDir, "linux-command") }, clockNanos = SystemClock::elapsedRealtimeNanos)
            val prefs by lazy { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }
            return ProcessLinuxRuntime(scope, paths, installer, proot, LinuxLaunchBuilder(proot, paths.rootfs, context.cacheDir, messages = messages),
                LinuxLaunchDns(paths.rootfs, File(context.filesDir, "linux/dns-tmp"), LinuxLaunchDns.androidSource(context)),
                object : LinuxRuntimePreferences {
                    override fun readAgentEnabled() = prefs.getBoolean(KEY_AGENT_ENABLED, true)
                    override fun writeAgentEnabled(enabled: Boolean) { check(prefs.edit().putBoolean(KEY_AGENT_ENABLED, enabled).commit()) { messages(LinuxRuntimeMessage.PREFERENCE_FAILED) } }
                }, keepAlive, packages, mirrors, { launch, timeout ->
                    runner.run(launch.commandRequest(timeout))
                }, operationSummary = { maintenance -> context.getString(
                    if (maintenance) R.string.linux_runtime_reset_summary else R.string.linux_runtime_install_summary,
                ) }, messages = messages)
        }
    }
}

internal data class LinuxExecutionStatus(val exitCode: Int?, val timedOut: Boolean?)

internal suspend fun <T> observeLinuxExecution(
    sessionId: String?,
    summarize: (T) -> LinuxExecutionStatus,
    action: suspend () -> T,
): T {
    val startedAt = System.nanoTime()
    InfinityLogger.diagnostic("linux.exec_start", mapOf("sessionId" to sessionId))
    var status: LinuxExecutionStatus? = null
    try {
        return action().also { output -> status = runCatching { summarize(output) }.getOrNull() }
    } finally {
        InfinityLogger.diagnostic("linux.exec_end", mapOf(
            "sessionId" to sessionId,
            "exitCode" to status?.exitCode,
            "timedOut" to status?.timedOut,
            "elapsedMs" to (System.nanoTime() - startedAt) / 1_000_000,
        ))
    }
}
