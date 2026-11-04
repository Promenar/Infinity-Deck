package com.promenar.infinity.domain.workspace

/**
 * 用户在资源管理器中对会话私有工作区发起的改名、移动与删除。
 *
 * 条目以 UUID 指定，操作保留 UUID 与持久文件身份；每项都经工作区 journal 事务提交后再更新索引，
 * 删除永久生效。批量结果以 UUID 为键，失败原因复用 [DirectoryFileOpCode]。
 */
interface PrivateWorkspaceFiles {
    /** 同目录改名；目标已存在时以 [DirectoryFileOpCode.NAME_EXISTS] 失败，改为当前名称视为成功。 */
    suspend fun rename(sessionId: String, uuid: String, newName: String)

    /** 逐项移动到 [targetParentUuid] 目录；同名时该项失败，不覆盖。已随所选上级目录一起移动的条目计为成功。 */
    suspend fun move(sessionId: String, uuids: List<String>, targetParentUuid: String): DirectoryBatchResult

    /** 逐项永久删除；已随所选上级目录一起删除的条目计为成功。 */
    suspend fun delete(sessionId: String, uuids: List<String>): DirectoryBatchResult
}
