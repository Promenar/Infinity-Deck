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
    /**
     * 在主导航打开任意页面。制作面板的媒体设置子页打开媒体设置以外的页面（例如新会话）时使用；
     * 为 null 时制作面板的“媒体模型”入口显示为不可用。
     */
    val openRoute: ((String) -> Unit)? = null,
)

val LocalSessionSettingsNavigation = staticCompositionLocalOf<SessionSettingsNavigation?> { null }
