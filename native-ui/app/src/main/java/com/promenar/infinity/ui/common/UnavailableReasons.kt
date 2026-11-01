package com.promenar.infinity.ui.common

import androidx.annotation.StringRes
import com.promenar.infinity.R
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.ui.theme.InfinityThemeMode

/**
 * “不生效即灰显”的判定集中在这里：每个函数返回不生效原因的字符串资源，返回 null 表示该项当前可用。
 * 界面按返回值把设置项置灰并把原因写进副标题，不再因为场景不符而移除该项（规格 §3.5）。
 * 本文件只做判定，不触及提供给模型的工具清单。
 */
internal object UnavailableReasons {
    /** 提供商表单底部“删除”行的显示方式。 */
    enum class ProviderDeleteEntry {
        /** 尚未保存的新提供商没有可删除的对象，整行不出现。 */
        HIDDEN,

        ENABLED,
    }

    fun providerDeleteEntry(providerId: String?): ProviderDeleteEntry = when (providerId) {
        null -> ProviderDeleteEntry.HIDDEN
        else -> ProviderDeleteEntry.ENABLED
    }

    /** 自定义文字大小关闭时，具体比例不起作用。 */
    @StringRes
    fun textScaleValue(textScaleEnabled: Boolean): Int? =
        if (textScaleEnabled) null else R.string.ux2_unavailable_text_scale_off

    /** 纯黑背景只作用于深色模式。 */
    @StringRes
    fun pureBlack(mode: InfinityThemeMode): Int? =
        if (mode == InfinityThemeMode.DARK) null else R.string.ux2_unavailable_pure_black_mode

    /** 复制公钥只对密钥认证的主机有意义。 */
    @StringRes
    fun sshCopyPublicKey(authType: SshAuthType): Int? =
        if (authType == SshAuthType.KEY) null else R.string.ux2_unavailable_ssh_public_key

    /** 没有配置转发端口的主机无法开启本地端口转发。 */
    @StringRes
    fun sshForward(localForwardPort: Int?): Int? =
        if (localForwardPort != null) null else R.string.ux2_unavailable_ssh_forward

    /** 只有已连接或等待登录的 Tailnet 才有可退出的登录状态。 */
    @StringRes
    fun tailscaleLogout(phase: TailnetPhase): Int? =
        if (phase == TailnetPhase.RUNNING || phase == TailnetPhase.NEEDS_LOGIN) null else R.string.ux2_unavailable_tailscale_logout

    /** WebDAV 开关关闭时，上传、刷新、恢复与配置入口都不起作用。 */
    @StringRes
    fun webDavActions(webDavEnabled: Boolean): Int? =
        if (webDavEnabled) null else R.string.ux2_unavailable_webdav_off

    /** 没有选中云端备份时无法从云端恢复。 */
    @StringRes
    fun cloudRestore(hasSelection: Boolean): Int? =
        if (hasSelection) null else R.string.ux2_unavailable_cloud_restore_none

    /** 会话工具总开关关闭后，执行模式不起作用。 */
    @StringRes
    fun executionMode(toolsEnabled: Boolean): Int? =
        if (toolsEnabled) null else R.string.ux2_unavailable_tools_off

    /** 当前模型不支持工具调用时，“关闭工具后保留联网搜索”没有意义。 */
    @StringRes
    fun keepWebSearch(toolCallingSupported: Boolean): Int? =
        if (toolCallingSupported) null else R.string.ux2_unavailable_tool_calling

    /** 智能体默认参数中的推理强度：未选模型或模型不提供推理调节时不可用。 */
    @StringRes
    fun reasoningEffort(modelSelected: Boolean, reasoningAdjustable: Boolean): Int? = when {
        !modelSelected -> R.string.ux2_unavailable_reasoning_no_model
        !reasoningAdjustable -> R.string.ux2_unavailable_reasoning_unsupported
        else -> null
    }

    /** Linux 运行模式只随侧载渠道发行。 */
    @StringRes
    fun linuxRunMode(linuxSupported: Boolean): Int? =
        if (linuxSupported) null else R.string.ux2_unavailable_linux_channel

    /** 没有可复用的 SSH 主机时，Termux 的密钥复制入口不起作用。 */
    @StringRes
    fun termuxKeyActions(hasKeyHosts: Boolean): Int? =
        if (hasKeyHosts) null else R.string.ux2_unavailable_termux_key
}
