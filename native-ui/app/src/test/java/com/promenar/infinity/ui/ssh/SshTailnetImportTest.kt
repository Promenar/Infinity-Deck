package com.promenar.infinity.ui.ssh

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.ui.settings.SshHostEditorFields
import org.junit.Test

class SshTailnetImportTest {
    private val fnos = TailnetPeer("fnos", "fnos.example-tailnet.ts.net.", listOf("fd7a:115c:a1e0::1", "100.101.7.42"), "linux", online = true)
    private val laptop = TailnetPeer("Laptop", "laptop.example-tailnet.ts.net.", listOf("100.64.0.9"), "macOS", online = true)
    private val build = TailnetPeer("build-server", null, listOf("100.64.0.8"), "linux", online = false)
    private val noAddress = TailnetPeer("ghost", null, emptyList(), "linux", online = true)

    @Test fun `入口只在内置 Tailscale 已启用时出现，未连接时灰显`() {
        assertThat(sshTailnetAvailability(available = false, TailnetPhase.RUNNING)).isEqualTo(SshTailnetAvailability.HIDDEN)
        assertThat(sshTailnetAvailability(true, TailnetPhase.DISABLED)).isEqualTo(SshTailnetAvailability.HIDDEN)
        assertThat(sshTailnetAvailability(true, TailnetPhase.UNAVAILABLE)).isEqualTo(SshTailnetAvailability.HIDDEN)
        listOf(TailnetPhase.STOPPED, TailnetPhase.STARTING, TailnetPhase.NEEDS_LOGIN, TailnetPhase.FAILED).forEach {
            assertThat(sshTailnetAvailability(true, it)).isEqualTo(SshTailnetAvailability.DISCONNECTED)
        }
        assertThat(sshTailnetAvailability(true, TailnetPhase.RUNNING)).isEqualTo(SshTailnetAvailability.CONNECTED)
    }

    @Test fun `列表填 IPv4，跳过没有地址的设备，按在线状态排序`() {
        val candidates = sshTailnetCandidates(listOf(laptop, build, noAddress, fnos), emptyList())
        assertThat(candidates.map { it.peer.name }).containsExactly("fnos", "Laptop", "build-server").inOrder()
        assertThat(candidates.first().address).isEqualTo("100.101.7.42")
    }

    @Test fun `已有主机按 IP 或 MagicDNS 名对上设备`() {
        val hosts = listOf(
            SshHost(alias = "nas", hostname = "100.101.7.42", username = "root"),
            SshHost(alias = "mac", hostname = "Laptop.example-tailnet.ts.net", username = "me"),
        )
        val byName = sshTailnetCandidates(listOf(fnos, laptop, build), hosts).associate { it.peer.name to it.existingAlias }
        assertThat(byName).containsExactly("fnos", "nas", "Laptop", "mac", "build-server", null)
    }

    @Test fun `选中设备填入 IP，主机 ID 留空时按设备名生成`() {
        val fields = SshHostEditorFields(null)
        fields.applyTailnetCandidate(sshTailnetCandidates(listOf(laptop), emptyList()).single())
        fields.username = "me"
        fields.authType = com.promenar.infinity.data.ssh.SshAuthType.PASSWORD
        assertThat(fields.hostname).isEqualTo("100.64.0.9")
        assertThat(fields.tailnetPeer).isEqualTo(laptop)

        val host = fields.draft().toHost(existing = null, hasPrivateKey = false, hasPassword = true, generate = false, takenAliases = setOf("laptop"))
        assertThat(host.alias).isEqualTo("laptop-2")
        assertThat(host.hostname).isEqualTo("100.64.0.9")
    }

    @Test fun `手动改成别的地址后不再按设备名生成主机 ID`() {
        val fields = SshHostEditorFields(null, sshTailnetCandidates(listOf(fnos), emptyList()).single())
        assertThat(fields.hostname).isEqualTo("100.101.7.42")
        fields.editHostname(" 100.101.7.42 ")
        assertThat(fields.tailnetPeer).isEqualTo(fnos)
        fields.editHostname("10.0.0.2")
        assertThat(fields.tailnetPeer).isNull()
        assertThat(fields.draft().aliasSeed).isNull()
    }

    @Test fun `编辑已有主机保留原来的保留终端值`() {
        val existing = SshHost(alias = "nas", hostname = "10.0.0.2", username = "root", keepTerminal = true)
        assertThat(SshHostEditorFields(existing).draft().keepTerminal).isTrue()
        assertThat(SshHostEditorFields(null).draft().keepTerminal).isFalse()
    }
}
