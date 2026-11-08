package com.promenar.infinity.data.exec

import android.content.Context
import java.io.File

/**
 * 随 APK 打包、安装后位于 nativeLibraryDir 的 CPython：启动器 libnxpython.so、
 * 标准库 zip libpystdlib.so、纯 Python 依赖与 Infinity Deck 模块 zip libpysite.so（内置 git），
 * 以及改名为 libpyext_<模块>.so 的原生扩展。
 * 全部代码都从打包库目录加载，不解压、不从应用数据目录 execve 或 dlopen。
 */
class PackagedPython(private val nativeLibraryDir: File) {
    val launcher: File get() = File(nativeLibraryDir, LAUNCHER)
    val stdlib: File get() = File(nativeLibraryDir, STDLIB)
    val site: File get() = File(nativeLibraryDir, SITE)

    /** 官方包只有 arm64-v8a 与 x86_64；其它 ABI 的设备上没有打包 Python。 */
    val isAvailable: Boolean get() = launcher.isFile && stdlib.isFile

    /** 解释器自身需要的环境变量；HOME、TMPDIR、证书等由执行后端另行设置。 */
    fun environment(): Map<String, String> = linkedMapOf(
        "PYTHONHOME" to nativeLibraryDir.path,
        "PYTHONPATH" to listOf(stdlib, site).filter { it == stdlib || it.isFile }.joinToString(":") { it.path },
        "INFINITY_PYEXT_DIR" to nativeLibraryDir.path,
        "LD_LIBRARY_PATH" to nativeLibraryDir.path,
        "PYTHONNOUSERSITE" to "1",
        "PYTHONDONTWRITEBYTECODE" to "1",
    )

    companion object {
        const val LAUNCHER = "libnxpython.so"
        const val STDLIB = "libpystdlib.so"
        const val SITE = "libpysite.so"

        fun from(context: Context) = PackagedPython(File(context.applicationInfo.nativeLibraryDir))
    }
}
