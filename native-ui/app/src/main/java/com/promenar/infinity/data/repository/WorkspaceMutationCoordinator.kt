package com.promenar.infinity.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path

/** 同一进程内按规范化物理根串行化全部文件与元数据变更。 */
object WorkspaceMutationCoordinator {
    private data class RootLock(val mutex: Mutex = Mutex(), var references: Int = 0)

    private val monitor = Any()
    private val roots = mutableMapOf<String, RootLock>()
    private val identities = LinkedHashMap<String, String>(32, 0.75f, true)

    suspend fun <T> withRoot(root: Path, block: suspend () -> T): T {
        val key = root.toFile().canonicalPath
        val lock = acquire(key, identity = null)
        return use(key, lock, block)
    }

    internal suspend fun <T> withIndexedMutation(root: Path, identity: String, block: suspend () -> T): T {
        if (identity.isBlank()) throw SecurityException("工作区根缺少数据库身份绑定")
        while (true) {
            WorkspaceExternalMutationCoordinator.awaitIdle(root)
            val key = root.toFile().canonicalPath
            val lock = acquire(key, identity)
            try {
                return use(key, lock) { WorkspaceExternalMutationCoordinator.indexedMutation(root, block) }
            } catch (_: WorkspaceScanInvalidatedException) {
                // 命令可能在等待根锁期间启动，释放根锁后重新等候，不能阻塞它的退出清理。
            }
        }
    }

    /** 扫描和只读句柄使用根锁；扫描仅在应用计划时取得外部命令提交门。 */
    suspend fun <T> withBoundRoot(root: Path, identity: String, block: suspend () -> T): T {
        if (identity.isBlank()) throw SecurityException("工作区根缺少数据库身份绑定")
        val key = root.toFile().canonicalPath
        val lock = acquire(key, identity)
        return use(key, lock, block)
    }

    /** 安全读取句柄已打开时允许回调进入同根条件删除，返回前重新持锁复核读取版本。 */
    internal suspend fun <T> consumeUnlocked(root: Path, block: suspend () -> T): T {
        val key = root.toFile().canonicalPath
        val mutex = synchronized(monitor) { roots[key]?.mutex } ?: error("工作区读取未持有根锁")
        mutex.unlock()
        try { return block() }
        finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { mutex.lock() } }
    }

    private fun acquire(key: String, identity: String?): RootLock = synchronized(monitor) {
        if (identity != null) identities[key] = identity
        roots.getOrPut(key) { RootLock() }.also { it.references += 1 }
    }

    private suspend fun <T> use(key: String, lock: RootLock, block: suspend () -> T): T {
        try {
            return lock.mutex.withLock { block() }
        } finally {
            synchronized(monitor) {
                lock.references -= 1
                if (lock.references == 0 && roots[key] === lock) roots.remove(key)
                trimIdentities()
            }
        }
    }

    internal fun bindIdentityForTesting(root: Path, identity: String) {
        if (identity.isBlank()) throw SecurityException("工作区根缺少数据库身份绑定")
        synchronized(monitor) {
            identities[root.toFile().canonicalPath] = identity
            trimIdentities()
        }
    }

    internal fun bindIdentityWhileHeld(root: Path, identity: String) {
        if (identity.isBlank()) throw SecurityException("工作区根缺少数据库身份绑定")
        val key = root.toFile().canonicalPath
        synchronized(monitor) {
            if ((roots[key]?.references ?: 0) <= 0) throw IllegalStateException("root identity 必须在持锁期间绑定")
            identities[key] = identity
        }
    }

    fun expectedIdentity(root: Path): String? = synchronized(monitor) {
        identities[root.toFile().canonicalPath]
    }

    private fun trimIdentities() {
        while (identities.size > 4096) {
            val removable = identities.keys.firstOrNull { key -> (roots[key]?.references ?: 0) == 0 }
                ?: return
            identities.remove(removable)
        }
    }
}
