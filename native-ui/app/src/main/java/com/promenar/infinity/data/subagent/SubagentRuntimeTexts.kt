package com.promenar.infinity.data.subagent

/** 应用为状态区注入当前语言；默认文字供独立后端执行与测试使用。 */
data class SubagentRuntimeTexts(
    val retry: (com.promenar.infinity.domain.generation.GenerationFailureCode, Long, Int) -> String = { code, seconds, attempt -> "${when (code) { com.promenar.infinity.domain.generation.GenerationFailureCode.RATE_LIMIT -> "限流"; com.promenar.infinity.domain.generation.GenerationFailureCode.NETWORK -> "网络错误"; com.promenar.infinity.domain.generation.GenerationFailureCode.TIMEOUT -> "超时"; com.promenar.infinity.domain.generation.GenerationFailureCode.SERVER -> "服务器错误"; else -> "请求中断" }}，${seconds} 秒后第 ${attempt} 次重试" },
    val interrupted: String = "应用进程中断；可经 session_send 恢复。",
    val resultWriteFailed: String = "完整结论文件写入失败；原文保留在子代理模型历史中。",
    val executionFailed: String = "子代理后台执行失败。",
    val persistenceFailed: String = "子代理记录持久化失败，执行已结束；重启后可能显示为中断。",
    val stopPersistenceFailed: String = "停止状态持久化失败，执行已取消。",
    val contextLimit: String = SubagentRunner.CONTEXT_LIMIT,
    val modelUnavailable: (String) -> String = { "子代理模型“$it”当前不可用；没有改用其它模型。" },
    val modelToolCallingUnsupported: String = "模型不支持工具调用。",
)
