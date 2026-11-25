package com.promenar.infinity.ui.chat.manager.skills

/**
 * 未绑定制作项目时的轻量运行时软提醒（每会话最多触发一次）：
 * 当会话尚未关联制作项目且 `production_define` 工具可用时，若智能体直接发起视频/后期类 ComfyUI 工作流
 * 或在同会话内累计触发多次媒体生成，在工具返回结果中附带一次轻量提示，避免快推理模型漏装制作面板。
 */
class ProductionPanelNudgeTracker(
    private val hasProduction: suspend (sessionId: String) -> Boolean,
    private val isDefineEnabled: () -> Boolean,
) {
    private val lock = Any()
    private val counts = LinkedHashMap<String, Int>(16, 0.75f, true)
    private val nudged = LinkedHashMap<String, Boolean>(16, 0.75f, true)

    suspend fun hintIfNeeded(sessionId: String, isVideoOrPostProcess: Boolean): String? {
        if (sessionId.isBlank() || !isDefineEnabled()) return null
        val shouldCheck = synchronized(lock) {
            if (nudged[sessionId] == true) return null
            val next = (counts[sessionId] ?: 0) + 1
            counts[sessionId] = next
            trimLocked(counts)
            isVideoOrPostProcess || next >= MULTI_MEDIA_THRESHOLD
        }
        if (!shouldCheck) return null
        val bound = runCatching { hasProduction(sessionId) }.getOrDefault(false)
        if (bound) return null
        val emit = synchronized(lock) {
            if (nudged[sessionId] == true) false else {
                nudged[sessionId] = true
                trimLocked(nudged)
                true
            }
        }
        return if (emit) PRODUCTION_PANEL_NUDGE_HINT else null
    }

    private fun <V> trimLocked(map: LinkedHashMap<String, V>) {
        while (map.size > MAX_TRACKED_SESSIONS) {
            val eldest = map.keys.firstOrNull() ?: break
            map.remove(eldest)
        }
    }

    companion object {
        const val MULTI_MEDIA_THRESHOLD = 2
        private const val MAX_TRACKED_SESSIONS = 256
        const val PRODUCTION_PANEL_NUDGE_HINT =
            "当前会话尚未绑定视频制作面板；若本次属于多镜头、带视觉资产/分镜或需审片复盘的视频创作，" +
                "请先调用 production_define 建项（或用 production_attach 挂载工作区已有的 production.json），" +
                "并同步登记方案/剧本、视觉资产与分镜，方便用户在制作面板审核。"
    }
}
