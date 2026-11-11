package com.promenar.infinity.data.production

/** 所有路径为会话工作区虚拟路径；缺失文件返回 null，其余读取错误应抛出。 */
interface ProductionWorkspaceFiles {
    suspend fun read(sessionId: String, path: String): ByteArray?
    /** 实现须使用条件写入，避免覆盖读取之后被外部修改的内容。expectedSha256 为 null 时仅新建。 */
    suspend fun write(sessionId: String, path: String, bytes: ByteArray, expectedSha256: String?)
    suspend fun isImageOrVideo(sessionId: String, path: String): Boolean
}
