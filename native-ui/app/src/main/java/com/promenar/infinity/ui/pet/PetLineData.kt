package com.promenar.infinity.ui.pet

/**
 * 交给宠物台词的任务数据，见规格第 6 节。只含元信息，不含消息正文、文件内容和工具输出；
 * 没有值的键不出现，引用它的兜底台词模板会被跳过。
 */
internal fun petLineData(
    sessionTitle: String?,
    modelName: String?,
    outputChars: Int,
    startedAt: Long?,
    question: String?,
    now: Long = System.currentTimeMillis(),
): Map<String, String> = buildMap {
    sessionTitle?.takeIf { it.isNotBlank() }?.let { put("session.title", it.take(40)) }
    modelName?.takeIf { it.isNotBlank() }?.let { put("model.name", it) }
    if (outputChars > 0) put("output.chars", outputChars.toString())
    if (startedAt != null && now >= startedAt) put("elapsed.s", ((now - startedAt) / 1000).toString())
    question?.takeIf { it.isNotBlank() }?.let { put("ask.text", it.take(60)) }
}
