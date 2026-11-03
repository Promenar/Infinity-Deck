package com.promenar.infinity.data.linux

import android.content.Context
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.exec.*
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.domain.network.AgentNetworkPolicy
import java.io.File
import java.util.UUID

/** 将宿主服务适配为三个 domain 接口，避免软件包层依赖其它工作包的实现。 */
internal class LinuxPackageBackend(context: Context, runtime: LinuxRuntime, host: LinuxHostServices) {
    val provenance = LinuxPackageMetadata(runtime, File(context.filesDir, "linux/meta/packages.json"))
    private val runner = BuiltinCommandRunner(
        groupLauncher = listOf("/system/bin/setsid"), shell = "/system/bin/sh",
        signaller = AndroidProcessGroupSignaller,
        scratchDirectory = { File(context.cacheDir, "linux-package-run") },
        outputLimit = 2 * 1024 * 1024,
        clockNanos = android.os.SystemClock::elapsedRealtimeNanos,
    )
    val packages: LinuxPackageManager = LinuxPackageService(runtime, provenance, LinuxPackageCommandExecutor { launch, output ->
        val control = BuiltinCommandControl()
        val request = BuiltinCommandRequest(launch.command, launch.hostWorkingDirectory, launch.environment, timeoutSeconds = 600)
        host.keepAlive.hold(
            ExecRunInfo(UUID.randomUUID().toString(), ExecRunKind.LINUX, ExecRunInfo.summarize(launch.command.joinToString(" ")), 600, System.currentTimeMillis()),
            control::requestStop,
        ) { runner.run(request, control) { _, text -> output(text) } }.value
    })
    val mirrors: LinuxMirrorSettings by lazy {
        val application = context.applicationContext as InfinityApplication
        val tailnet = application.tailnetManager
        val policy = AgentNetworkPolicy(
            isTailnetHost = tailnet::isTailnetHost,
            tailnetRunning = { tailnet.state.value.phase == TailnetPhase.RUNNING },
        )
        LinuxMirrorService(runtime, LinuxMirrorSharedPreferences(context.getSharedPreferences(LinuxMirrorSharedPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)),
            LinuxMirrorHttpClient(application.gitRemoteHttpClient, policy, tailnet))
    }
}
