package com.promenar.infinity.data.memory

/** 原因与纠正提示均为固定文字，允许界面及工具安全展示。 */
enum class LongTermMemoryFailure(val code: String, val message: String, val suggestion: String) {
    INVALID_KIND("INVALID_MEMORY_KIND", "记忆 kind 必须为 user、feedback、project 或 reference。", "使用四种有效 kind 之一。"),
    INVALID_TITLE("INVALID_MEMORY_TITLE", "记忆标题去除首尾空白后必须为 1–80 字符。", "缩短标题或补充非空标题。"),
    INVALID_CONTENT("INVALID_MEMORY_CONTENT", "记忆正文去除首尾空白后必须为 1–2000 字符。", "缩短正文或补充非空正文。"),
    CREDENTIALS("MEMORY_CREDENTIALS_REJECTED", "记忆疑似包含凭据，无法写入。", "移除 API key、令牌、私钥或密码后重试。"),
    NOT_FOUND("MEMORY_NOT_FOUND", "指定的记忆不存在。", "检查 id；需要新建时省略 id。"),
    LIMIT_REACHED("MEMORY_LIMIT_REACHED", "主动记忆已达 200 条上限，请先合并相关条目或删除过时记忆。", "先合并相关条目或删除过时记忆，再创建新条目。"),
    INVALID_RESTORE("INVALID_MEMORY_RESTORE", "恢复的记忆必须有 UUID id 且正文标题已去除首尾空白。", "使用写入或删除操作返回的原记忆恢复。"),
}

class LongTermMemoryException(val reason: LongTermMemoryFailure) : IllegalArgumentException(reason.message)
