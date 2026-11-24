package com.promenar.infinity.data.linux

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.data.exec.AndroidProcessGroupSignaller
import com.promenar.infinity.data.exec.BuiltinCommandRequest
import com.promenar.infinity.data.exec.BuiltinCommandRunner
import com.promenar.infinity.domain.linux.LinuxLaunchSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class ProotSeccompProbeDeviceTest {
    @Test fun probeForkWithAndWithoutProotSeccomp() = runBlocking<Unit>(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val proot = PackagedProot(File(context.applicationInfo.nativeLibraryDir))
        assumeTrue("该发行渠道未打包 proot", proot.isAvailable)
        assumeTrue("探针需要 Android 17 及以上", Build.VERSION.SDK_INT >= 37)

        val status = File("/proc/self/status").readLines()
            .filter { it.startsWith("Seccomp:") || it.startsWith("Seccomp_filters:") }
        val domain = File("/proc/self/attr/current").readText().trim { it == '\u0000' || it.isWhitespace() }
        report("Android=${Build.VERSION.SDK_INT}，SELinux=$domain，${status.joinToString("，")}")
        assertThat(status.firstOrNull { it.startsWith("Seccomp:") }?.substringAfter(':')?.trim()).isEqualTo("2")
        assertWithMessage("需在普通应用 SELinux 域执行").that(Regex(":untrusted_app(?:_[0-9]+)?:").containsMatchIn(domain)).isTrue()

        val scratch = Files.createTempDirectory(context.cacheDir.toPath(), "proot-seccomp-probe-").toFile()
        try {
            val rootfs = File(scratch, "alpine")
            val extractor = SafeTarExtractor(symlink = { target, file -> Os.symlink(target, file.path) },
                chmod = { file, mode -> Os.chmod(file.path, mode) })
            context.assets.open("linux/alpine-minirootfs.tar.gz.bin").use { extractor.extractGzip(it, rootfs) }
            val builder = LinuxLaunchBuilder(proot, rootfs, scratch,
                tailnetEnvironment = { emptyMap() }, builtInProxyEndpoint = { null })
            val runner = BuiltinCommandRunner(
                listOf("/system/bin/setsid"), "/system/bin/sh", AndroidProcessGroupSignaller,
                { scratch }, clockNanos = SystemClock::elapsedRealtimeNanos,
            )
            val spec = LinuxLaunchSpec.shell("for i in 1 2 3 4 5; do (/bin/true) || exit 42; done; echo ok")
            for (disabled in listOf(false, true)) {
                val launch = builder.build(spec, maintainHomeConfiguration = false, prootNoSeccomp = disabled)
                val start = SystemClock.elapsedRealtime()
                // 两组均关闭应用看护器，单独观测 proot 自身的进程创建行为。
                val output = runner.run(BuiltinCommandRequest(launch.command, launch.hostWorkingDirectory,
                    launch.environment, timeoutSeconds = 15))
                val elapsed = SystemClock.elapsedRealtime() - start
                val stderr = output.stderr.replace(scratch.path, "<探针目录>")
                    .replace(proot.executable.parent.orEmpty(), "<原生库目录>")
                val enosys = "Function not implemented" in stderr || "Function not implemented" in output.stdout
                val label = if (disabled) "PROOT_NO_SECCOMP=1" else "默认"
                report("$label，exitCode=${output.exitCode}，elapsedMs=$elapsed，ENOSYS=$enosys，stderr=$stderr")
                if (disabled) {
                    assertWithMessage("兼容组 stderr=%s", stderr).that(output.exitCode).isEqualTo(0)
                    assertThat(output.stdout.lineSequence().map(String::trim).toList()).contains("ok")
                }
            }
        } finally {
            LinuxRootfsFiles.deleteTree(scratch)
        }
    }

    @Test fun probeHardwareProfileAndOwnProcessSampling() = runBlocking<Unit>(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LinuxResourceGovernor.initialize(context)
        val profile = DeviceHardwareProfile.read()
        val budget = LinuxResourceGovernor.system.budget()
        report(
            "硬件画像 envelopeMiB=${profile.appMemoryEnvelopeBytes / MIB}，" +
                "reservedUiCpu=${profile.reservedUiCpuId}，" +
                "linuxCpuIds=${profile.linuxCpuIds}，" +
                "parallelLimit=${profile.linuxParallelLimit}，" +
                "jobBudgetMiB=${budget.jobMemoryBudgetBytes / MIB}，" +
                "effectiveThreads=${budget.effectiveThreads}",
        )
        assertThat(profile.appMemoryEnvelopeBytes).isAtLeast(512 * MIB)
        assertThat(profile.linuxCpuIds).isNotEmpty()
        assertThat(budget.jobMemoryBudgetBytes).isAtLeast(256 * MIB)

        val watch = LinuxResourceGovernor.watch(budget.jobMemoryBudgetBytes)
        val runner = BuiltinCommandRunner(
            listOf("/system/bin/setsid"), "/system/bin/sh", AndroidProcessGroupSignaller,
            { context.cacheDir }, clockNanos = SystemClock::elapsedRealtimeNanos,
        )
        val output = runner.run(
            BuiltinCommandRequest(
                argv = listOf("/system/bin/sh", "-c", "sleep 1; echo sampled"),
                workingDirectory = context.cacheDir,
                environment = emptyMap(),
                timeoutSeconds = 10,
                memoryWatch = { watch },
            ),
        )
        report("实机进程树采样 exitCode=${output.exitCode}，peakBytes=${output.peakMemoryBytes}")
        assertThat(output.exitCode).isEqualTo(0)
        assertWithMessage("Android 17 untrusted_app 应能采样自身子进程组 RSS").that(output.peakMemoryBytes).isNotNull()
        assertThat(output.peakMemoryBytes!!).isGreaterThan(0L)
    }

    private fun report(message: String) {
        Log.i("ProotSeccompProbe", message)
        println("ProotSeccompProbe $message")
    }
}
