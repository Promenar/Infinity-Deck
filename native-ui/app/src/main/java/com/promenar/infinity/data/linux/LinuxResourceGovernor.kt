package com.promenar.infinity.data.linux

import android.app.ActivityManager
import android.content.Context
import android.system.Os
import com.promenar.infinity.data.exec.BuiltinCommandRequest
import com.promenar.infinity.domain.linux.LinuxProcessLaunch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class LinuxResourceBudget(
    val jobMemoryBudgetBytes: Long,
    val linuxCpuIds: List<Int>,
    val effectiveThreads: Int,
) {
    internal fun applyTo(environment: MutableMap<String, String>) {
        val threads = effectiveThreads.toString()
        environment.putAll(mapOf(
            "OMP_NUM_THREADS" to threads,
            "RAYON_NUM_THREADS" to threads,
            "GOMAXPROCS" to threads,
            "GOMEMLIMIT" to "${(jobMemoryBudgetBytes * 65 / 100 / MIB).coerceAtLeast(128)}MiB",
            "UV_THREADPOOL_SIZE" to effectiveThreads.coerceIn(1, 4).toString(),
            "NX_PROOT_NICE" to "5",
            "NX_PROOT_CPU_LIST" to linuxCpuIds.joinToString(","),
            "NX_PROOT_NOFILE" to "4096",
            "NX_PROOT_DISABLE_CORE" to "1",
        ))
        val makeFlags = environment["MAKEFLAGS"].orEmpty()
        if (!makeParallelism.containsMatchIn(makeFlags)) {
            environment["MAKEFLAGS"] = listOf(makeFlags, "-j$threads").filter(String::isNotBlank).joinToString(" ")
        }
        // 数值上限采用最后一项，百分比上限优先级更高，先移除这一项再附加预算。
        val nodeLimit = (jobMemoryBudgetBytes * 55 / 100 / MIB).coerceIn(128, 2048)
        val nodeOptions = withoutNodeHeapPercentage(environment["NODE_OPTIONS"].orEmpty())
        environment["NODE_OPTIONS"] = listOf(nodeOptions, "--max-old-space-size=$nodeLimit")
            .filter(String::isNotBlank).joinToString(" ")
    }

    private fun withoutNodeHeapPercentage(options: String): String {
        val retained = mutableListOf<String>()
        var skipPercentageValue = false
        for (match in nodeOptionToken.findAll(options)) {
            val token = match.value
            val argument = token.trim('"')
            if (skipPercentageValue && argument.toDoubleOrNull() != null) {
                skipPercentageValue = false
                continue
            }
            val isHeapPercentage = argument.substringBefore('=').replace('_', '-') == "--max-old-space-size-percentage"
            if (!isHeapPercentage) retained += token
            skipPercentageValue = isHeapPercentage && '=' !in argument
        }
        return retained.joinToString(" ")
    }

    internal companion object {
        val managedEnvironmentKeys = setOf(
            "OMP_NUM_THREADS", "RAYON_NUM_THREADS", "GOMAXPROCS", "GOMEMLIMIT", "UV_THREADPOOL_SIZE",
            "MAKEFLAGS", "NODE_OPTIONS", "NX_PROOT_NICE", "NX_PROOT_CPU_LIST", "NX_PROOT_NOFILE",
            "NX_PROOT_DISABLE_CORE", "PROOT_NO_SECCOMP",
        )
        private val nodeOptionToken = Regex("""(?:[^\s"]|"(?:\\.|[^"\\])*")+""")
        private val makeParallelism = Regex("(?:^|\\s)(?:-[A-Za-z]*j[0-9]*|--jobs(?:=\\S+)?|--jobserver(?:-(?:auth|fds|style))?(?:=\\S+)?)(?=\\s|$)")
    }
}

/** 硬件画像在进程内固定，预算只扣本应用及其子进程。 */
internal class LinuxResourceGovernor(
    readProfile: () -> DeviceHardwareProfile,
    private val selfMemory: () -> Long,
    private val childMemory: () -> Long,
) {
    private val profile by lazy(readProfile)

    private fun warmUp() {
        profile
    }

    fun budget(): LinuxResourceBudget {
        val selfResidentBytes = selfMemory() + childMemory()
        val bytes = maxOf(256 * MIB, profile.appMemoryEnvelopeBytes - selfResidentBytes)
        val threads = when {
            bytes < 512 * MIB -> 1
            bytes < 1024 * MIB -> minOf(2, profile.linuxParallelLimit)
            else -> profile.linuxParallelLimit
        }
        return LinuxResourceBudget(bytes, profile.linuxCpuIds, threads)
    }

    companion object {
        private val criticalMemoryEpoch = AtomicLong()
        @Volatile private var activityManager: ActivityManager? = null
        private val processMemory = LinuxProcessMemory(::readOwnProcesses) { pid ->
            readResourceFile("/proc/$pid/status")
        }
        val system = LinuxResourceGovernor(
            { DeviceHardwareProfile.read() },
            { readResourceFile("/proc/self/status")?.let(LinuxProcessMemory::residentBytes) ?: 0 },
            { readResourceFile("/proc/self/stat")?.substringBefore(' ')?.toIntOrNull()?.let(processMemory::forChildren) ?: 0 },
        )

        fun initialize(context: Context) {
            activityManager = context.getSystemService(ActivityManager::class.java)
            system.warmUp()
        }

        fun signalCriticalMemory() {
            criticalMemoryEpoch.incrementAndGet()
        }

        fun watch(budgetBytes: Long): LinuxMemoryWatch {
            val initialEpoch = criticalMemoryEpoch.get()
            return LinuxMemoryWatch(
                budgetBytes, processMemory::forGroup, { criticalMemoryEpoch.get() != initialEpoch },
                lowMemory = {
                    val manager = activityManager
                    manager != null && runCatching {
                        ActivityManager.MemoryInfo().also(manager::getMemoryInfo).lowMemory
                    }.getOrDefault(false)
                },
            )
        }

        private fun readOwnProcesses(): List<LinuxProcessIdentity> {
            val uid = runCatching { Os.getuid() }.getOrNull() ?: return emptyList()
            return File("/proc").listFiles().orEmpty().mapNotNull { directory ->
                val pid = directory.name.toIntOrNull() ?: return@mapNotNull null
                val owner = runCatching { Os.stat(directory.path).st_uid }.getOrNull()
                if (owner != uid) return@mapNotNull null
                val stat = readResourceFile("${directory.path}/stat") ?: return@mapNotNull null
                LinuxProcessIdentity.parse(pid, stat)
            }
        }
    }
}

/** 无法采样时将连续越限计数清零。操作系统发出的紧急回调独立于采样频率。 */
class LinuxMemoryWatch(
    private val budgetBytes: Long,
    private val groupMemory: (Int) -> Long?,
    private val critical: () -> Boolean,
    private val lowMemory: () -> Boolean = { false },
) {
    private var nextSampleMillis = Long.MIN_VALUE
    private var consecutiveOverBudget = 0
    var peakMemoryBytes: Long? = null
        private set

    internal fun check(pgid: Int?, nowMillis: Long): Boolean {
        if (critical()) return true
        if (pgid == null || nowMillis < nextSampleMillis) return false
        val bytes = groupMemory(pgid)
        if (bytes != null) peakMemoryBytes = maxOf(peakMemoryBytes ?: 0, bytes)
        consecutiveOverBudget = if (bytes != null && bytes > budgetBytes) consecutiveOverBudget + 1 else 0
        nextSampleMillis = nowMillis + if (bytes != null && bytes >= budgetBytes * 4 / 5) 500 else 1000
        return consecutiveOverBudget >= 2 || lowMemory()
    }
}

internal data class LinuxProcessIdentity(val pid: Int, val parent: Int, val group: Int) {
    companion object {
        fun parse(pid: Int, stat: String): LinuxProcessIdentity? {
            // stat 的 comm 可以包含空格和括号，进程状态之后才是 PPID、PGID。
            val fields = stat.substringAfterLast(") ", "").split(' ', limit = 4)
            val parent = fields.getOrNull(1)?.toIntOrNull() ?: return null
            val group = fields.getOrNull(2)?.toIntOrNull() ?: return null
            return LinuxProcessIdentity(pid, parent, group)
        }
    }
}

internal class LinuxProcessMemory(
    private val processes: () -> List<LinuxProcessIdentity>,
    private val status: (Int) -> String?,
) {
    fun forChildren(root: Int): Long {
        val snapshot = processes()
        val descendants = descendantsOf(setOf(root), snapshot) - root
        val groups = snapshot.filter { it.pid in descendants && it.pid == it.group }.mapTo(hashSetOf()) { it.group }
        val roots = snapshot.filter { it.group in groups }.mapTo(descendants.toMutableSet()) { it.pid }
        return total(descendantsOf(roots, snapshot) - root) ?: 0
    }

    fun forGroup(group: Int): Long? {
        val snapshot = processes()
        val members = snapshot.filter { it.group == group }.mapTo(linkedSetOf()) { it.pid }
        return total(descendantsOf(members, snapshot))
    }

    private fun descendantsOf(roots: Set<Int>, snapshot: List<LinuxProcessIdentity>): Set<Int> {
        val byParent = snapshot.groupBy(LinuxProcessIdentity::parent)
        val visited = roots.toMutableSet()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            byParent[queue.removeFirst()].orEmpty().forEach { if (visited.add(it.pid)) queue.addLast(it.pid) }
        }
        return visited
    }

    private fun total(pids: Set<Int>): Long? {
        val readable = pids.mapNotNull { status(it)?.let(::residentBytes) }
        return readable.takeIf { it.isNotEmpty() }?.sum()
    }

    companion object {
        fun residentBytes(status: String): Long? {
            val rss = readKib(status, "VmRSS") ?: readKib(status, "RssAnon") ?: return null
            return rss + (readKib(status, "VmSwap") ?: 0)
        }
    }
}

internal fun LinuxProcessLaunch.commandRequest(
    timeoutSeconds: Int,
    stdin: String? = null,
    terminal: Boolean = false,
): BuiltinCommandRequest = BuiltinCommandRequest(
    command, hostWorkingDirectory, environment, stdin, timeoutSeconds, terminal,
    memoryWatch = memoryBudgetBytes?.let { budget -> { LinuxResourceGovernor.watch(budget) } },
)
