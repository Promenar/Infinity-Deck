package com.promenar.infinity.data.session

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * 应用其它位置新建会话时要自动发出的首条用户消息（例如设置里“交给智能体配置” ComfyUI 主机）。
 * 会话页加载该会话、运行模式检查结束后取走并按普通用户消息发送。只存内存。
 *
 * 经这里新建的会话带有预设标题，首轮结束后不按首条消息自动改名（[keepsTitle]）。
 */
class SessionLaunchInbox {
    private val state = MutableStateFlow<Map<String, String>>(emptyMap())
    val pending: StateFlow<Map<String, String>> = state.asStateFlow()
    private val presetTitles: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun offer(sessionId: String, message: String) {
        require(sessionId.isNotBlank()) { "会话 id 不能为空" }
        require(message.isNotBlank()) { "消息不能为空" }
        presetTitles += sessionId
        state.update { it + (sessionId to message) }
    }

    /** 取走该会话待发送的消息；没有时为 null。 */
    fun take(sessionId: String): String? = state.getAndUpdate { it - sessionId }[sessionId]

    /** 会话标题由发起方预设，首轮结束后保留。 */
    fun keepsTitle(sessionId: String): Boolean = sessionId in presetTitles
}
