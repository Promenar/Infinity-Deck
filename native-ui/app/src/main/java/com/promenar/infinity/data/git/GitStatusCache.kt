package com.promenar.infinity.data.git

import kotlinx.coroutines.flow.Flow

/*
 * Git 状态的应用内缓存契约：按仓库规范路径去重与缓存，不写仓库（不刷新 `.git/index`）。
 * 会话顶栏、资源管理器、资料位置与 Git 页统一经过它，避免同一仓库被多个调用方各自启动解释器。
 */

/** 某个会话所在仓库的状态快照。 */
data class GitStatusSnapshot(
    /** 最近一次读取结果；从未读取过为 null。 */
    val result: GitInspectResult<GitStatus>?,
    /** [result] 读取完成时的墙钟毫秒，用于显示“N 秒前”；null 同上。 */
    val readAtMillis: Long?,
    /** 是否有读取正在进行（首次读取或后台刷新）。 */
    val refreshing: Boolean,
)

interface GitStatusCache {
    /**
     * 读取状态：缓存未过期（读取时间距今不超过 [maxAgeMillis]）且未被失效时直接返回；
     * 否则启动读取，同一仓库同时只有一次读取，其它调用方等待同一结果。
     * 某个等待者取消不影响其它等待者与读取本身。
     */
    suspend fun status(sessionId: String, maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS): GitInspectResult<GitStatus>

    /**
     * 先给缓存、后台刷新：订阅时立即发出当前快照；缓存缺失、过期或已失效时启动一次读取，
     * 读取开始与完成各发出一次快照。没有订阅者时不保持读取。
     */
    fun observe(sessionId: String, maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS): Flow<GitStatusSnapshot>

    /** 标记该会话所在仓库的缓存已失效；有订阅者时立即后台刷新，结果到达前仍提供旧值。 */
    fun invalidate(sessionId: String)

    /** 全部仓库失效（例如每轮生成结束后不知道写到了哪个仓库时）。 */
    fun invalidateAll()

    companion object {
        /** 默认最长复用 30 秒；手动刷新与写入类工具结束通过 [invalidate] 立即失效。 */
        const val DEFAULT_MAX_AGE_MILLIS: Long = 30_000
    }
}
