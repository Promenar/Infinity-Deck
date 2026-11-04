package com.promenar.infinity.ui.chat.manager.skills

/** 进程内文件基准；删除提交后使对应路径及其后代失效。 */
internal class LinuxVersionSnapshots(private val maxFileBytes: Long) {
    data class Key(
        val namespace: String,
        val path: String,
        val hash: String,
        val privateRoot: String? = null,
        val rootfs: Boolean = false,
    )

    private val values = linkedMapOf<Key, ByteArray>()
    private var generation = 0L

    @Synchronized fun generation(): Long = generation

    @Synchronized fun remember(key: Key, bytes: ByteArray, capturedGeneration: Long) {
        if (bytes.size > maxFileBytes || capturedGeneration != generation) return
        values[key] = bytes.copyOf()
        while (values.size > 64 || values.values.sumOf { it.size.toLong() } > 16L * maxFileBytes) {
            values.remove(values.keys.first())
        }
    }

    @Synchronized fun get(key: Key): ByteArray? = values[key]?.copyOf()

    @Synchronized fun forgetPrivate(root: String, path: String) {
        generation++
        values.keys.removeAll { it.privateRoot == root && within(it.path, path) }
    }

    @Synchronized fun forgetRootfs(path: String) {
        generation++
        values.keys.removeAll { it.rootfs && within(it.path, path) }
    }

    private fun within(candidate: String, path: String): Boolean =
        candidate == path || candidate.startsWith(path.trimEnd('/') + "/")
}
