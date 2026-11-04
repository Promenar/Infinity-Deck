package com.promenar.infinity.data.repository

import java.util.concurrent.CancellationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal data class LegacyRecycleEntry(
    val rootUuid: String,
    val uuid: String,
    val path: String,
    val recycled: Boolean,
    val isDirectory: Boolean,
)

internal data class LegacyRecycleCleanupResult(
    val completed: Boolean,
    val alreadyComplete: Boolean = false,
    val deletedRoots: Int = 0,
    val failed: Int = 0,
)

/** 对存量回收条目做一次可重试的清理，失败时不写完成标记。 */
internal class LegacyRecycleCleanup(
    private val isComplete: () -> Boolean,
    private val markComplete: () -> Boolean,
    private val loadEntries: suspend () -> List<LegacyRecycleEntry>,
    private val deleteEntry: suspend (rootUuid: String, uuid: String) -> Unit,
    private val deleteDirectory: (name: String) -> Boolean,
) {
    suspend fun run(): LegacyRecycleCleanupResult {
        if (isComplete()) return LegacyRecycleCleanupResult(completed = true, alreadyComplete = true)
        var failed = 0
        var deleted = 0
        val allEntries = try {
            loadEntries()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed++
            emptyList()
        }
        val entries = allEntries.filter { it.recycled }
        val valid = entries.filter { entry ->
            val parts = entry.path.split('/').drop(1)
            val accepted = entry.path.startsWith('/') && parts.firstOrNull() == ".recycle_bin" &&
                parts.none { it.isEmpty() || it == "." || it == ".." || '\\' in it || '\u0000' in it }
            if (!accepted) failed++
            accepted
        }
        val safe = valid.filter { entry ->
            val containsActive = entry.isDirectory && allEntries.any { other ->
                other.rootUuid == entry.rootUuid && !other.recycled && other.path.startsWith(entry.path + "/")
            }
            if (containsActive) failed++
            !containsActive
        }
        val selected = mutableListOf<LegacyRecycleEntry>()
        for (entry in safe.sortedBy { it.path.length }) {
            if (selected.none { parent -> parent.isDirectory && parent.rootUuid == entry.rootUuid &&
                    (entry.path == parent.path || entry.path.startsWith(parent.path + "/")) }) {
                selected += entry
            }
        }
        for (entry in selected) {
            try {
                deleteEntry(entry.rootUuid, entry.uuid)
                deleted++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed++
            }
        }
        try {
            failed += loadEntries().count { it.recycled }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed++
        }
        for (name in listOf("external-recycle", "file-manager-recycle")) {
            try {
                if (!deleteDirectory(name)) failed++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed++
            }
        }
        val completed = if (failed == 0) try {
            markComplete().also { if (!it) failed++ }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed++
            false
        } else false
        return LegacyRecycleCleanupResult(completed = completed, deletedRoots = deleted, failed = failed)
    }
}

/** 只处理应用持有的清理目录，遍历不跟随符号链接。 */
internal fun deleteLegacyRecycleDirectory(base: Path, name: String): Boolean {
    require(name in setOf("external-recycle", "file-manager-recycle"))
    val directory = base.resolve(name)
    if (!Files.exists(directory, NOFOLLOW_LINKS)) return true
    if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, NOFOLLOW_LINKS)) return false
    fun remove(path: Path) {
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
    remove(directory)
    return true
}
