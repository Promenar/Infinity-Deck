package com.promenar.infinity.domain.repository

interface IFileOperationRepository {
    /** 保留已校验 UTF-8 源码的 BOM 与换行，供需要字节一致的画布镜像使用。 */
    suspend fun writeFileAtomicExact(
        workspaceRootUuid: String,
        uuid: String,
        newContent: String,
        sessionId: String,
        expectedHash: String,
    ): WriteResult = writeFileAtomic(workspaceRootUuid, uuid, newContent, sessionId, expectedHash)

    suspend fun writeFileAtomic(
        workspaceRootUuid: String,
        uuid: String,
        newContent: String,
        sessionId: String,
        expectedHash: String
    ): WriteResult

    /** 读取整文件或行范围，文件须在 1 MiB、2 万行的工具预算内；编辑器、检索等需要全文的调用方使用。 */
    suspend fun readFileRange(
        workspaceRootUuid: String,
        uuid: String,
        startLine: Int? = null,
        endLine: Int? = null
    ): ReadResult

    /**
     * read_file 的分段读取：流式扫描文件，只解码 [request] 指定窗口内的行。
     * 文件不超过 [ReadWindowRequest.MAX_FILE_BYTES] 即可读取；单次结果受行数与字节预算约束，超出时给出续读位置。
     */
    suspend fun readFileWindow(
        workspaceRootUuid: String,
        uuid: String,
        request: ReadWindowRequest,
    ): ReadWindowResult {
        val range = readFileRange(
            workspaceRootUuid,
            uuid,
            request.startLine,
            request.endLine ?: (request.startLine.toLong() + request.maxLines - 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
        return ReadWindowResult(
            uuid = range.uuid,
            name = range.name,
            totalLines = range.totalLines,
            startLine = range.startLine,
            endLine = range.endLine,
            startLineByteOffset = 0,
            content = range.content,
            hash = range.hash,
            lastModified = range.lastModified,
            nextLine = (range.endLine + 1).takeIf { range.endLine < range.totalLines },
            nextLineByteOffset = 0,
        )
    }

    suspend fun diffFile(
        workspaceRootUuid: String,
        uuid: String,
        basisHash: String? = null
    ): DiffResult

    suspend fun patchFile(
        workspaceRootUuid: String,
        uuid: String,
        operations: List<PatchOperation>,
        expectedHash: String
    ): PatchResult

}

sealed class WriteResult {
    data class Success(
        val newHash: String,
        val indexQueued: Boolean = true,
        val targetEpoch: Long? = null,
    ) : WriteResult()
    data class Conflict(val currentHash: String, val expectedHash: String, val message: String) :
        WriteResult()

    data object NotFound : WriteResult()
}

data class ReadResult(
    val uuid: String,
    val name: String,
    val totalLines: Int,
    val startLine: Int,
    val endLine: Int,
    val content: String,
    val hash: String,
    val lastModified: Long
)

/**
 * 行窗口读取请求。行号从 1 开始；[lineByteOffset] 是 [startLine] 行内的 UTF-8 字节偏移，只用于续读被截断的超长行。
 * [endLine] 为空时读到 [maxLines] 行为止。
 */
data class ReadWindowRequest(
    val startLine: Int = 1,
    val endLine: Int? = null,
    val lineByteOffset: Long = 0,
    val maxLines: Int = MAX_LINES,
    val maxContentBudget: Int = MAX_CONTENT_BUDGET,
) {
    init {
        require(startLine >= 1) { "startLine 必须从 1 开始" }
        require(endLine == null || endLine >= startLine) { "endLine 不能小于 startLine" }
        require(lineByteOffset >= 0) { "lineByteOffset 不能为负数" }
        require(maxLines >= 1) { "maxLines 必须大于 0" }
        require(maxContentBudget >= 16) { "maxContentBudget 过小" }
    }

    companion object {
        /** 单次返回的最大行数。 */
        const val MAX_LINES = 2000

        /**
         * 单次返回正文的字节预算，按 JSON 转义后的长度计（引号、反斜杠计 2 字节，其它控制字符最多计 6 字节）。
         * 加上行号前缀与表头后，序列化结果仍低于工具结果 256 KiB 上限。
         */
        const val MAX_CONTENT_BUDGET = 192 * 1024

        /**
         * 可分段读取的单文件上限，与存储空间单文件 100 MiB 上限一致。
         * 每次读取都要顺序扫描全文统计行数，这一上限把单次扫描控制在数百毫秒级，避免用超大文件反复占用 IO。
         */
        const val MAX_FILE_BYTES = 100L * 1024L * 1024L
    }
}

/**
 * 行窗口读取结果。[endLine] 小于 [startLine] 表示窗口为空（空文件或起始行超出末尾）。
 * [nextLine] 为空表示已读到文件末尾；否则从 [nextLine] 行、行内字节偏移 [nextLineByteOffset] 处续读。
 */
data class ReadWindowResult(
    val uuid: String,
    val name: String,
    val totalLines: Int,
    val startLine: Int,
    val endLine: Int,
    val startLineByteOffset: Long,
    val content: String,
    val hash: String,
    val lastModified: Long,
    val nextLine: Int?,
    val nextLineByteOffset: Long,
)

data class DiffResult(
    val uuid: String,
    val basisHash: String,
    val currentHash: String,
    val hunks: List<DiffHunk>
)

data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>
)

data class DiffLine(val type: String, val content: String)

data class PatchOperation(
    val action: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val afterLine: Int? = null,
    val newContent: String? = null,
    val expectedContent: String? = null,
)

sealed class PatchResult {
    data class Success(
        val newHash: String,
        val appliedOperations: Int,
        val indexQueued: Boolean = true,
        val targetEpoch: Long? = null,
    ) : PatchResult()
    data class Failure(val error: PatchError) : PatchResult()
}

data class PatchError(
    val code: String,
    val message: String,
    val operationIndex: Int,
    val fileUuid: String,
    val totalLines: Int? = null,
    val suggestion: String? = null
)
