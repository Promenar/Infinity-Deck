package com.promenar.infinity.startup.diagnostics

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val THREAD_LIMIT = 80
private const val FRAME_LIMIT = 12
private const val OMITTED = "[omitted]"

private enum class ThreadGroup(val ioLike: Boolean = false) {
    MAIN, DISPATCHER, ARCH_DISK(true), OKHTTP(true), INFINITY, OTHER,
}

private data class DiagnosticThread(
    val name: String,
    val state: Thread.State,
    val daemon: Boolean,
    val group: ThreadGroup,
    val frames: Array<StackTraceElement>,
)

internal fun diagnosticThreadLines(snapshot: Map<Thread, Array<StackTraceElement>>): List<String> {
    val threads = snapshot.map { (thread, frames) ->
        val (name, group) = safeThreadName(thread.name.substringBefore(" @"))
        DiagnosticThread(name, thread.state, thread.isDaemon, group, frames)
    }
    val stateCounts = threads.groupingBy { it.state }.eachCount()
    return buildList {
        add(buildJsonObject {
            put("event", "diagnostics.threads_summary")
            put("totalThreads", threads.size)
            put("stateCounts", buildJsonObject {
                Thread.State.entries.forEach { put(it.name, stateCounts[it] ?: 0) }
            })
            put("defaultDispatcherCount", threads.count { it.group == ThreadGroup.DISPATCHER })
            put("ioLikeThreadCount", threads.count { thread ->
                thread.group.ioLike || thread.frames.asSequence().take(FRAME_LIMIT).any(::isIoFrame)
            })
        }.toString())
        threads.sortedWith(compareBy<DiagnosticThread> { it.group.ordinal }
            .thenBy { statePriority(it.state) }.thenBy { it.name }).take(THREAD_LIMIT).forEach { thread ->
            add(buildJsonObject {
                put("event", "diagnostics.thread")
                put("name", thread.name)
                put("state", thread.state.name)
                put("daemon", thread.daemon)
                put("frames", buildJsonArray {
                    thread.frames.asSequence().take(FRAME_LIMIT).mapNotNull(::safeThreadFrame).forEach {
                        add(kotlinx.serialization.json.JsonPrimitive(it))
                    }
                })
            }.toString())
        }
    }
}

private val dispatcherThread = Regex("DefaultDispatcher-worker-[0-9]{1,5}")
private val diskThread = Regex("arch_disk_io_[0-9]{1,5}")
private val infinityThread = Regex("infinity-(?:diagnostics|tailnet-stdout|loopback-(?:accept|connection-[0-9]{1,5})|github-api-(?:accept|connection-[0-9]{1,5})|generation-cleanup-[0-9]{1,5})")
private val standardRuntimeThread = Regex("(?:Reference Handler|Finalizer|FinalizerDaemon|FinalizerWatchdogDaemon|Signal Dispatcher|Common-Cleaner|HeapTaskDaemon|Binder:[0-9]{1,8}_[0-9]{1,5})")

private fun safeThreadName(raw: String): Pair<String, ThreadGroup> = when {
    dispatcherThread.matches(raw) -> raw to ThreadGroup.DISPATCHER
    raw == "main" -> raw to ThreadGroup.MAIN
    diskThread.matches(raw) -> raw to ThreadGroup.ARCH_DISK
    raw == "OkHttp Dispatcher" || raw == "OkHttp TaskRunner" || raw == "OkHttp ConnectionPool" -> raw to ThreadGroup.OKHTTP
    raw == "OkHttp" || raw.startsWith("OkHttp ") -> "OkHttp" to ThreadGroup.OKHTTP
    infinityThread.matches(raw) || raw == "diagnostic-dump" -> raw to ThreadGroup.INFINITY
    standardRuntimeThread.matches(raw) -> raw to ThreadGroup.OTHER
    else -> OMITTED to ThreadGroup.OTHER
}

private fun statePriority(state: Thread.State): Int = when (state) {
    Thread.State.BLOCKED -> 0
    Thread.State.RUNNABLE -> 1
    Thread.State.WAITING -> 2
    Thread.State.TIMED_WAITING -> 3
    Thread.State.NEW -> 4
    Thread.State.TERMINATED -> 5
}

private val safeClassName = Regex("[A-Za-z_][\$A-Za-z0-9_]*(?:[.][A-Za-z_][\$A-Za-z0-9_]*)+")
private val safeMethodName = Regex("(?:[A-Za-z_][\$A-Za-z0-9_-]*|<(?:cl)?init>)")
private val safePackages = listOf("com.promenar.infinity.", "android.", "androidx.", "dalvik.", "java.",
    "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.coroutines.", "io.ktor.", "okhttp3.", "okio.",
    "libcore.", "com.android.", "org.conscrypt.")

private fun safeThreadFrame(frame: StackTraceElement): String? {
    val owner = frame.className
    val method = frame.methodName
    if (owner.length > 180 || method.length > 80 || safePackages.none(owner::startsWith) ||
        !safeClassName.matches(owner) || !safeMethodName.matches(method)) return null
    val base = owner.substringAfterLast('.').substringBefore('$').removeSuffix("Kt")
    val source = frame.fileName?.takeIf { it == "$base.kt" || it == "$base.java" } ?: "Unknown"
    return "$owner.$method($source:${frame.lineNumber})"
}

private fun isIoFrame(frame: StackTraceElement): Boolean =
    frame.className.startsWith("java.io.") || frame.className.startsWith("java.net.") ||
        frame.className.startsWith("sun.nio.ch.") || frame.className.startsWith("android.system.") ||
        frame.className == "android.net.LocalSocketImpl"
