package com.promenar.infinity.data.git.remote

import java.io.Closeable
import okhttp3.Call

/** 上游阻塞时关闭本地流不足以唤醒处理线程。连接关闭同时撤销当前请求。 */
internal class GitHttpConnection(private val socket: Closeable) : Closeable {
    private var closed = false
    private var active: Call? = null

    @Synchronized
    fun attach(call: Call): Boolean {
        if (closed) return false
        active = call
        return true
    }

    @Synchronized
    fun detach(call: Call) {
        if (active === call) active = null
    }

    override fun close() {
        val call = synchronized(this) {
            if (closed) return
            closed = true
            active.also { active = null }
        }
        try { call?.cancel() } finally { socket.close() }
    }
}
