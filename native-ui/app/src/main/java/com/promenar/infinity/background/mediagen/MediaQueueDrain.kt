package com.promenar.infinity.background.mediagen

/** 服务主线程串行维护版本；退出前接住运行期间送达的新启动请求。 */
internal suspend fun drainMediaQueue(run: suspend () -> Unit, hasWork: suspend () -> Boolean, version: () -> Long) {
    do {
        val observed = version()
        run()
        val pending = hasWork()
    } while (pending || observed != version())
}
