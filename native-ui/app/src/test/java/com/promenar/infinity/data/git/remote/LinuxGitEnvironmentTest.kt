package com.promenar.infinity.data.git.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.linux.LinuxResourceBudget
import com.promenar.infinity.data.linux.MIB
import org.junit.Test

class LinuxGitEnvironmentTest {
    @Test fun `资源配置连续追加且保留代理重写 凭据头与工作区信任`() {
        val environment = LinuxGitEnvironment.from(lease()).toMutableMap()
        LinuxGitEnvironment.appendConfiguration(environment, listOf("safe.directory" to "/workspace"))
        LinuxGitEnvironment.appendResourceConfiguration(environment, LinuxResourceBudget(768 * MIB, listOf(0, 1), 2))
        val keys = (0 until checkNotNull(environment["GIT_CONFIG_COUNT"]).toInt()).map { environment["GIT_CONFIG_KEY_$it"] }
        assertThat(keys).containsExactly(
            "url.http://127.0.0.1:43123/github.com/.insteadOf", "http.http://127.0.0.1:43123/.extraHeader",
            "http.http://127.0.0.1:43123/.proxy", "safe.directory", "pack.threads", "pack.windowMemory",
            "core.packedGitLimit", "gc.auto", "fetch.writeCommitGraph",
        ).inOrder()
        assertThat(environment["GIT_CONFIG_VALUE_1"]).isEqualTo("${GitCredentialProxy.NONCE_HEADER}: synthetic-command")
        assertThat(environment["GIT_CONFIG_VALUE_4"]).isEqualTo("1")
        assertThat(environment["GIT_CONFIG_VALUE_5"]).isEqualTo("192m")
        assertThat(environment["GIT_CONFIG_VALUE_6"]).isEqualTo("192m")
        assertThat(environment["GIT_CONFIG_VALUE_7"]).isEqualTo("0")
        assertThat(environment["GIT_CONFIG_VALUE_8"]).isEqualTo("false")
    }

    @Test fun `较大预算限制 Git 线程和绝对内存上限`() {
        val environment = mutableMapOf<String, String>()
        LinuxGitEnvironment.appendResourceConfiguration(environment, LinuxResourceBudget(3072 * MIB, listOf(0, 1, 2, 3), 4))
        assertThat(environment["GIT_CONFIG_VALUE_0"]).isEqualTo("2")
        assertThat(environment["GIT_CONFIG_VALUE_1"]).isEqualTo("256m")
        assertThat(environment["GIT_CONFIG_VALUE_2"]).isEqualTo("512m")
    }

    @Test fun `GitHub代理就绪时gh使用命令nonce和独立配置目录`() {
        val environment = LinuxGitEnvironment.from(lease() + ("INFINITY_GH_SOCKET" to "/run/infinity/gh/gh.sock"))
        assertThat(environment["GH_TOKEN"]).isEqualTo("synthetic-command")
        assertThat(environment["GH_CONFIG_DIR"]).isEqualTo("/run/infinity/gh/config")
        assertThat(environment["GH_PROMPT_DISABLED"]).isEqualTo("1")
        assertThat(environment["GH_NO_UPDATE_NOTIFIER"]).isEqualTo("1")
    }

    @Test fun `非GitHub凭据或代理未就绪时不改变gh环境`() {
        listOf(lease(), lease(hosts = "git.example.com") + ("INFINITY_GH_SOCKET" to "/run/infinity/gh/gh.sock")).forEach {
            assertThat(LinuxGitEnvironment.from(it).keys.none { key -> key.startsWith("GH_") }).isTrue()
        }
    }

    private fun lease(proxy: String = "127.0.0.1:43123", nonce: String = "synthetic-command", hosts: String = "github.com") = mapOf(
        GitRemoteBroker.ENV_HTTP_PROXY to proxy,
        GitRemoteBroker.ENV_NONCE to nonce,
        GitRemoteBroker.ENV_CREDENTIAL_HOSTS to hosts,
    )

    @Test fun `无凭据或缺少租约字段时只禁用交互提示`() {
        val prompt = mapOf("GIT_TERMINAL_PROMPT" to "0")
        assertThat(LinuxGitEnvironment.from(emptyMap())).isEqualTo(prompt)
        lease().keys.forEach { missing ->
            assertThat(LinuxGitEnvironment.from(lease() - missing)).isEqualTo(prompt)
        }
    }

    @Test fun `多个主机规范化去重且带端口主机保留端口`() {
        val environment = LinuxGitEnvironment.from(lease(hosts = "GitHub.com,github.com:443,git.example.com:8443,,bad/path,evil@host"))
        assertThat(environment["GIT_CONFIG_COUNT"]).isEqualTo("4")
        assertThat(environment).hasSize(10)
        assertThat(environment["GIT_CONFIG_KEY_0"]).isEqualTo("url.http://127.0.0.1:43123/github.com/.insteadOf")
        assertThat(environment["GIT_CONFIG_VALUE_0"]).isEqualTo("https://github.com/")
        assertThat(environment["GIT_CONFIG_KEY_1"]).isEqualTo("url.http://127.0.0.1:43123/git.example.com:8443/.insteadOf")
        assertThat(environment["GIT_CONFIG_VALUE_1"]).isEqualTo("https://git.example.com:8443/")
        assertThat(environment["GIT_CONFIG_KEY_2"]).isEqualTo("http.http://127.0.0.1:43123/.extraHeader")
        assertThat(environment["GIT_CONFIG_VALUE_2"]).isEqualTo("${GitCredentialProxy.NONCE_HEADER}: synthetic-command")
        assertThat(environment["GIT_CONFIG_KEY_3"]).isEqualTo("http.http://127.0.0.1:43123/.proxy")
        assertThat(environment["GIT_CONFIG_VALUE_3"]).isEmpty()
        assertThat(environment.values.count { "synthetic-command" in it }).isEqualTo(1)
        assertThat(environment.keys).doesNotContain(GitRemoteBroker.ENV_NONCE)
    }

    @Test fun `畸形代理地址无效端口控制字符与空nonce不生成配置`() {
        val prompt = mapOf("GIT_TERMINAL_PROMPT" to "0")
        listOf("localhost:1234", "http://127.0.0.1:1234", "127.0.0.1", "127.0.0.1:0", "127.0.0.1:65536",
            "127.0.0.1:-1", "127.0.0.1:1/path", "127.0.0.1:1\n", " 127.0.0.1:1", "127.0.0.2:1234").forEach {
            assertThat(LinuxGitEnvironment.from(lease(proxy = it))).isEqualTo(prompt)
        }
        listOf("", " ", "bad\nheader", "bad\rheader", "bad\u0000header", "bad\u007fheader").forEach {
            assertThat(LinuxGitEnvironment.from(lease(nonce = it))).isEqualTo(prompt)
        }
        listOf("", "bad/path,evil@host,host:65536", "bad\n.test").forEach {
            assertThat(LinuxGitEnvironment.from(lease(hosts = it))).isEqualTo(prompt)
        }
        listOf(1, 65535).forEach {
            assertThat(LinuxGitEnvironment.from(lease(proxy = "127.0.0.1:$it"))["GIT_CONFIG_COUNT"]).isEqualTo("3")
        }
    }
}
