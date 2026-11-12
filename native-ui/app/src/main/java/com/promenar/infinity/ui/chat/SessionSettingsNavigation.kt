package com.promenar.infinity.ui.chat

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 会话设置面板与可达范围面板跳往全局设置的去向，由会话页所在的导航提供。
 * 预览与测试中为 null，相应的行显示为不可跳转。
 */
class SessionSettingsNavigation(
    /** 全局“技能包”页（S3）。 */
    val openSkills: () -> Unit,
    /** 全局“网络访问”页（R3）。 */
    val openNetworkAccess: () -> Unit,
    /** 当前会话所属智能体的编辑页（S7）。 */
    val openAgentEdit: (agentId: String) -> Unit,
    /** “远程”面板底部的 Linux 环境、Termux、后台与定时设置页；为 null 时不显示该行。 */
    val openRemoteSettings: ((com.promenar.infinity.ui.remote.RemoteSettingsLink) -> Unit)? = null,
    /** 设置中的“媒体模型”页；为 null 时制作面板的入口显示为不可用。 */
    val openMediaModels: (() -> Unit)? = null,
)

val LocalSessionSettingsNavigation = staticCompositionLocalOf<SessionSettingsNavigation?> { null }
