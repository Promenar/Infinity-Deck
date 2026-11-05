package com.promenar.infinity.background.indexing

/** 同一可见期或活动批次内只尝试一次，避免系统拒绝后随进度变化反复启动。 */
internal class IndexForegroundStartGate {
    private var hasDocuments = false
    private var visible = false
    private var attempted = false
    private var serviceRequested = false

    fun observe(hasDocuments: Boolean, visible: Boolean): Boolean {
        if (!hasDocuments || (visible && !this.visible)) attempted = false
        this.hasDocuments = hasDocuments
        this.visible = visible
        return tryStart()
    }

    fun startRejected() {
        serviceRequested = false
    }

    fun suppressRestart() {
        attempted = true
    }

    fun serviceStopped(queueWasEmpty: Boolean, hasDocuments: Boolean, visible: Boolean): Boolean {
        serviceRequested = false
        if (queueWasEmpty) attempted = false
        return observe(hasDocuments, visible)
    }

    private fun tryStart(): Boolean {
        if (!hasDocuments || !visible || attempted || serviceRequested) return false
        attempted = true
        serviceRequested = true
        return true
    }
}
