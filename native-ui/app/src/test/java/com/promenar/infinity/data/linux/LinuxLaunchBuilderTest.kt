package com.promenar.infinity.data.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.exec.openJvmToolSocket
import com.promenar.infinity.domain.linux.LinuxLaunchSpec
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LinuxLaunchBuilderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `Node 百分比上限不覆盖预算且保留引用参数`() {
        val environment = mutableMapOf(
            "NODE_OPTIONS" to "--require \"/workspace/a b.js\" --max_old_space_size_percentage \"50\" --trace-warnings",
            "MAKEFLAGS" to "-s",
        )
        LinuxResourceBudget(768 * MIB, listOf(0, 1), 2).applyTo(environment)
        assertThat(environment["NODE_OPTIONS"]).isEqualTo(
            "--require \"/workspace/a b.js\" --trace-warnings --max-old-space-size=422",
        )
        assertThat(environment["MAKEFLAGS"]).isEqualTo("-s -j2")
    }

    @Test fun `资源变量在普通环境合并后定档且禁用加速由启动策略控制`() {
        val root = temporary.newFolder()
        val builder = LinuxLaunchBuilder(
            PackagedProot(root), root, temporary.newFolder(),
            tailnetEnvironment = { emptyMap() }, builtInProxyEndpoint = { null },
            resourceBudget = { LinuxResourceBudget(768 * MIB, listOf(0, 1, 2), 2) },
        )
        val spec = LinuxLaunchSpec(listOf("/bin/true"), environment = mapOf(
            "OMP_NUM_THREADS" to "100", "NX_PROOT_CPU_LIST" to "9",
            "MAKEFLAGS" to "-s --jobserver-auth=3,4",
            "NODE_OPTIONS" to "--max-old-space-size=9999 --trace-warnings",
            "PROOT_NO_SECCOMP" to "1",
        ))
        val launch = builder.build(spec, maintainHomeConfiguration = false, prootNoSeccomp = false)
        assertThat(launch.memoryBudgetBytes).isEqualTo(768 * MIB)
        assertThat(launch.environment).containsAtLeast(
            "NX_PROOT_NICE", "5", "NX_PROOT_CPU_LIST", "0,1,2",
            "NX_PROOT_NOFILE", "4096", "NX_PROOT_DISABLE_CORE", "1",
            "OMP_NUM_THREADS", "2", "RAYON_NUM_THREADS", "2", "GOMAXPROCS", "2",
            "GOMEMLIMIT", "499MiB", "UV_THREADPOOL_SIZE", "2",
            "MAKEFLAGS", "-s --jobserver-auth=3,4",
            "NODE_OPTIONS", "--max-old-space-size=9999 --trace-warnings --max-old-space-size=422",
        )
        assertThat(launch.environment).doesNotContainKey("PROOT_NO_SECCOMP")
        assertThat(builder.build(spec, false, prootNoSeccomp = true).environment["PROOT_NO_SECCOMP"]).isEqualTo("1")
        assertThat(File(root, "root/.gitignore").exists()).isFalse()
    }
    @Test fun `已租用的工具桥绑定 shim 和 socket 并前置 PATH`() {
        val root = temporary.newFolder()
        val cache = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("/tmp"), "nx-").toFile()
        val libraries = temporary.newFolder()
        File(libraries, "libnxtoolshim.so").createNewFile()
        File(libraries, "libnxrg.so").createNewFile()
        LinuxToolBridgeConfig.prepareBridgeDirectory(cache)
        val socket = LinuxToolBridgeConfig.socket(cache)
        val builder = LinuxLaunchBuilder(PackagedProot(libraries), root, cache,
            tailnetEnvironment = { emptyMap() }, builtInProxyEndpoint = { null })
        try {
            openJvmToolSocket(socket).use {
                val spec = LinuxLaunchSpec(listOf("/bin/true"), environment = mapOf(LinuxToolBridgeConfig.ENV_NONCE to "nonce"))
                val launch = builder.build(spec, false, false)
                assertThat(launch.arguments).containsAtLeast(
                    "${cache.path}/linux-tool-shim-bin:/run/infinity/bin",
                    "${libraries.path}/libnxtoolshim.so:/run/infinity/bin/nx-shim",
                    "${socket.parent}:/run/infinity/bridge",
                )
                assertThat(launch.environment.getValue("PATH")).startsWith("/run/infinity/bin:")
                assertThat(launch.environment[LinuxToolBridgeConfig.ENV_SOCKET]).isEqualTo(LinuxToolBridgeConfig.GUEST_SOCKET)
                val shim = File(cache, "linux-tool-shim-bin/rg").toPath()
                assertThat(java.nio.file.Files.readSymbolicLink(shim).toString()).isEqualTo("nx-shim")
                assertThat(builder.build(spec.copy(environment = emptyMap()), false, false).environment["PATH"])
                    .doesNotContain("/run/infinity/bin")
            }
        } finally { cache.deleteRecursively() }
    }
}
