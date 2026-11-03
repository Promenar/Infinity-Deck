package com.promenar.infinity.data.exec

import java.io.File

/** 前台与让出任务共用同一进程、控制柄、日志和工作区锁。 */
class LinuxExecAttachment(
    val control: BuiltinCommandControl,
    val processGroupFile: File,
    val logFile: File,
    val onDirectory: suspend (File) -> Unit = {},
)
