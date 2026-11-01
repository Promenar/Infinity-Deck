package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.ui.common.UnavailableReasons.ProviderDeleteEntry
import com.promenar.infinity.ui.theme.InfinityThemeMode
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class UnavailableReasonsTest {
    private fun resourceNames(path: String): Set<String> =
        Regex("""<string name="([^"]+)"""")
            .findAll(String(Files.readAllBytes(Path.of(path)), Charsets.UTF_8))
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `所有已保存提供商均可删除且新建时隐藏删除行`() {
        assertThat(UnavailableReasons.providerDeleteEntry(null)).isEqualTo(ProviderDeleteEntry.HIDDEN)
        assertThat(UnavailableReasons.providerDeleteEntry("default")).isEqualTo(ProviderDeleteEntry.ENABLED)
        assertThat(UnavailableReasons.providerDeleteEntry("custom-1")).isEqualTo(ProviderDeleteEntry.ENABLED)
    }

    @Test
    fun `外观页只在前置开关满足时可用`() {
        assertThat(UnavailableReasons.textScaleValue(true)).isNull()
        assertThat(UnavailableReasons.textScaleValue(false)).isEqualTo(R.string.ux2_unavailable_text_scale_off)
        assertThat(UnavailableReasons.pureBlack(InfinityThemeMode.DARK)).isNull()
        assertThat(UnavailableReasons.pureBlack(InfinityThemeMode.LIGHT)).isEqualTo(R.string.ux2_unavailable_pure_black_mode)
        assertThat(UnavailableReasons.pureBlack(InfinityThemeMode.SYSTEM)).isEqualTo(R.string.ux2_unavailable_pure_black_mode)
    }

    @Test
    fun `SSH 主机操作按认证方式与转发端口判定`() {
        assertThat(UnavailableReasons.sshCopyPublicKey(SshAuthType.KEY)).isNull()
        assertThat(UnavailableReasons.sshCopyPublicKey(SshAuthType.PASSWORD)).isEqualTo(R.string.ux2_unavailable_ssh_public_key)
        assertThat(UnavailableReasons.sshForward(2222)).isNull()
        assertThat(UnavailableReasons.sshForward(null)).isEqualTo(R.string.ux2_unavailable_ssh_forward)
    }

    @Test
    fun `Tailscale 只有运行中或等待登录时才能退出登录`() {
        val available = setOf(TailnetPhase.RUNNING, TailnetPhase.NEEDS_LOGIN)
        TailnetPhase.entries.forEach { phase ->
            val reason = UnavailableReasons.tailscaleLogout(phase)
            if (phase in available) assertThat(reason).isNull()
            else assertThat(reason).isEqualTo(R.string.ux2_unavailable_tailscale_logout)
        }
    }

    @Test
    fun `备份的云端操作先看 WebDAV 开关再看是否选中备份`() {
        assertThat(UnavailableReasons.webDavActions(true)).isNull()
        assertThat(UnavailableReasons.webDavActions(false)).isEqualTo(R.string.ux2_unavailable_webdav_off)
        assertThat(UnavailableReasons.cloudRestore(true)).isNull()
        assertThat(UnavailableReasons.cloudRestore(false)).isEqualTo(R.string.ux2_unavailable_cloud_restore_none)
    }

    @Test
    fun `会话工具设置按总开关与模型能力判定`() {
        assertThat(UnavailableReasons.executionMode(true)).isNull()
        assertThat(UnavailableReasons.executionMode(false)).isEqualTo(R.string.ux2_unavailable_tools_off)
        assertThat(UnavailableReasons.keepWebSearch(true)).isNull()
        assertThat(UnavailableReasons.keepWebSearch(false)).isEqualTo(R.string.ux2_unavailable_tool_calling)
    }

    @Test
    fun `推理强度区分未选模型与模型不支持`() {
        assertThat(UnavailableReasons.reasoningEffort(modelSelected = true, reasoningAdjustable = true)).isNull()
        assertThat(UnavailableReasons.reasoningEffort(modelSelected = true, reasoningAdjustable = false))
            .isEqualTo(R.string.ux2_unavailable_reasoning_unsupported)
        assertThat(UnavailableReasons.reasoningEffort(modelSelected = false, reasoningAdjustable = false))
            .isEqualTo(R.string.ux2_unavailable_reasoning_no_model)
    }

    @Test
    fun `渠道与前置条件判定`() {
        assertThat(UnavailableReasons.linuxRunMode(true)).isNull()
        assertThat(UnavailableReasons.linuxRunMode(false)).isEqualTo(R.string.ux2_unavailable_linux_channel)
        assertThat(UnavailableReasons.termuxKeyActions(true)).isNull()
        assertThat(UnavailableReasons.termuxKeyActions(false)).isEqualTo(R.string.ux2_unavailable_termux_key)
    }

    @Test
    fun `灰显原因文案中英文资源键一致且都有内容`() {
        val en = resourceNames("app/src/main/res/values/ux2_greying_strings.xml")
        val zh = resourceNames("app/src/main/res/values-zh-rCN/ux2_greying_strings.xml")
        assertThat(en).isNotEmpty()
        assertThat(zh).isEqualTo(en)
    }
}
