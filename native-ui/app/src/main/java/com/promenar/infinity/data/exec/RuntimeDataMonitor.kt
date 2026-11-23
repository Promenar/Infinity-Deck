package com.promenar.infinity.data.exec

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.DirectoryIteratorException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 只记录元数据，不读取应用内容；不跟随符号链接。 */
class RuntimeDataMonitor(private val dataDirectory: File, private val maxEntries: Int = 4096) {
    val dataDirectoryPath: String get() = runCatching { dataDirectory.canonicalPath }.getOrDefault(dataDirectory.path)
    internal data class Entry(val identity: String, val size: Long, val modified: Long)
    class Snapshot internal constructor(internal val groups: Map<String, Map<String, Entry>?>)
    private val groups = listOf("databases", "shared_prefs", "no_backup", "files/agent_skills", "diagnostics")
    private val ignoredBackup = setOf("python-pip", "exec-ca", "tailnet")

    init { require(maxEntries > 0) }

    suspend fun capture(): Snapshot = withContext(Dispatchers.IO) { snapshot() }

    suspend fun changesSince(before: Snapshot): JsonObject = withContext(Dispatchers.IO) {
        compare(before, snapshot())
    }

    private fun snapshot() = Snapshot(groups.associateWith { group ->
        try { scan(group) } catch (_: IOException) { null } catch (_: DirectoryIteratorException) { null } catch (_: SecurityException) { null }
    })

    private fun scan(group: String): Map<String, Entry> {
        val result = linkedMapOf<String, Entry>()
        var count = 0
        fun record(path: Path, detailed: Boolean, counted: Boolean = false): BasicFileAttributes? {
            if (!counted && ++count > maxEntries) throw IOException("扫描条目超限")
            val attributes = try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
                catch (_: NoSuchFileException) { return null }
            val key = attributes.fileKey()?.toString() ?: throw IOException("文件身份不可用")
            val label = dataDirectory.toPath().relativize(path).toString().replace(File.separatorChar, '/')
            result[label] = Entry(key, if (detailed && !attributes.isDirectory) attributes.size() else 0,
                if (detailed && !attributes.isDirectory) attributes.lastModifiedTime().toMillis() else 0)
            return attributes
        }
        if (group == "diagnostics") {
            listOf("files/infinity_diagnostics.jsonl", "files/infinity_logs.txt", "files/diag-keep").forEach {
                record(File(dataDirectory, it).toPath(), false)
            }
            return result
        }
        val root = File(dataDirectory, group).toPath()
        if (record(root, false)?.isDirectory != true) return result
        val directories = ArrayDeque<Path>()
        directories.add(root)
        while (directories.isNotEmpty()) {
            val directory = directories.removeLast()
            Files.newDirectoryStream(directory).use { stream ->
                for (path in stream) {
                    if (++count > maxEntries) throw IOException("扫描条目超限")
                    val name = path.fileName.toString()
                    if (group == "databases" && (name.endsWith("-wal") || name.endsWith("-shm") || name.endsWith("-journal"))) continue
                    if (group == "shared_prefs" && name.endsWith(".bak")) continue
                    if (group == "no_backup" && name in ignoredBackup) continue
                    val attributes = record(path, group == "files/agent_skills", counted = true)
                    if (group == "files/agent_skills" && attributes?.isDirectory == true) directories.add(path)
                }
            }
        }
        return result
    }

    private fun compare(before: Snapshot, after: Snapshot): JsonObject {
        val changes = mutableListOf<String>()
        val notes = mutableListOf<String>()
        for (group in groups) {
            val old = before.groups[group]
            val current = after.groups[group]
            if (old == null || current == null) {
                notes += "$group：已跳过检测（条目超过 $maxEntries、文件身份不可用或扫描失败）"
                continue
            }
            for ((path, entry) in old) {
                val now = current[path]
                when {
                    now == null -> changes += "$path (deleted)"
                    // SharedPreferences 每次提交都先改名为 .bak 再新建文件，身份变化属于应用自身写入。
                    group == "shared_prefs" -> Unit
                    now.identity != entry.identity -> changes += "$path (replaced)"
                    group == "files/agent_skills" && now != entry -> changes += path
                }
            }
            // 应用会按需新建设置与日志文件，这两组只关注删除。
            if (group !in setOf("no_backup", "shared_prefs", "diagnostics")) {
                (current.keys - old.keys).forEach { changes += "$it (added)" }
            }
        }
        return JsonObject(buildMap {
            if (changes.isNotEmpty()) put("runtime_changes", JsonArray(changes.sorted().map(::JsonPrimitive)))
            if (notes.isNotEmpty()) put("runtime_scan_notes", JsonArray(notes.map(::JsonPrimitive)))
        })
    }

    companion object {
        private const val BACKGROUND_RESULT = "runtime-changes.json"

        suspend fun writeBackgroundResult(directory: File, fields: JsonObject) = withContext(Dispatchers.IO) {
            val temporary = File.createTempFile("runtime-changes-", ".tmp", directory)
            try {
                temporary.writeText(fields.toString(), Charsets.UTF_8)
                Files.move(temporary.toPath(), File(directory, BACKGROUND_RESULT).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
        }

        suspend fun backgroundResult(directory: File, expectResult: Boolean = false): JsonObject = withContext(Dispatchers.IO) {
            val file = File(directory, BACKGROUND_RESULT)
            if (!file.exists() && !expectResult) return@withContext JsonObject(emptyMap())
            runCatching {
                check(file.isFile) { "检测结果未生成或不可用" }
                check(file.length() <= 2 * 1024 * 1024) { "检测结果超过读取上限" }
                val fields = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
                JsonObject(fields.filterKeys { it == "runtime_changes" || it == "runtime_scan_notes" })
            }.getOrElse {
                JsonObject(mapOf("runtime_scan_notes" to JsonArray(listOf(JsonPrimitive("后台关键数据检测结果无法读取")))))
            }
        }
    }
}
