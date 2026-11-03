package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.linux.*
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface LinuxMirrorHttp { suspend fun head(url: String): Boolean }
internal interface LinuxMirrorPreferences {
    fun readChoice(): LinuxMirrorChoice
    fun readLatencies(): Map<LinuxMirrorId, Long?>
    fun saveChoice(choice: LinuxMirrorChoice)
    fun saveLatencies(latencies: Map<LinuxMirrorId, Long?>)
}
internal class LinuxMirrorService(
    private val runtime: LinuxRuntime,
    private val preferences: LinuxMirrorPreferences,
    private val http: LinuxMirrorHttp,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockNanos: () -> Long = System::nanoTime,
) : LinuxMirrorSettings {
    private val mutableSelected = MutableStateFlow(preferences.readChoice())
    override val selected = mutableSelected.asStateFlow()
    private val mutableLatencies = MutableStateFlow(preferences.readLatencies())
    override val latencies = mutableLatencies.asStateFlow()
    private val selectMutex = Mutex()
    private val speedMutex = Mutex()

    override suspend fun select(choice: LinuxMirrorChoice) = withContext(ioDispatcher) {
        val base = baseUrl(choice)
        selectMutex.withLock {
            val release = LinuxPackageStorage.checked(runtime.paths.rootfs, "etc/alpine-release")
            var wroteRepository = false
            var previousRepository: String? = null
            // 安装器的网络步骤尚未发布 Ready；rootfs 中已有版本文件即可写入。
            if (release.isFile) {
                val version = version(release.readText())
                val repository = LinuxPackageStorage.checked(runtime.paths.rootfs, "etc/apk/repositories")
                previousRepository = repository.takeIf { it.isFile }?.readText()
                LinuxPackageStorage.atomicWrite(runtime.paths.rootfs, "etc/apk/repositories",
                    "$base/v$version/main\n$base/v$version/community\n")
                wroteRepository = true
            } else if (runtime.state.value is LinuxRuntimeState.Ready) {
                throw IOException("Linux 版本文件缺失")
            }
            try {
                preferences.saveChoice(choice)
            } catch (failure: IOException) {
                if (wroteRepository) try {
                    if (previousRepository == null) Files.deleteIfExists(LinuxPackageStorage.checked(runtime.paths.rootfs, "etc/apk/repositories").toPath())
                    else LinuxPackageStorage.atomicWrite(runtime.paths.rootfs, "etc/apk/repositories", previousRepository)
                } catch (rollback: Exception) { failure.addSuppressed(rollback) }
                // commit 失败也可能已改变 SharedPreferences 的内存值。
                try { preferences.saveChoice(selected.value) } catch (rollback: Exception) { failure.addSuppressed(rollback) }
                throw failure
            }
            mutableSelected.value = choice
        }
    }

    override fun speedTest(): Flow<Pair<LinuxMirrorId, Long?>> = flow {
        speedMutex.withLock {
            val version = LinuxPackageStorage.read(runtime.paths.rootfs, "etc/alpine-release")
                .takeIf(String::isNotBlank)?.let(::version) ?: "3.24"
            val architecture = LinuxPackageStorage.read(runtime.paths.rootfs, "etc/apk/arch").trim()
                .takeIf { it.matches(Regex("[a-zA-Z0-9_]+")) }
                ?: (runtime.state.value as? LinuxRuntimeState.Ready)?.info?.architecture ?: "aarch64"
            require(architecture.matches(Regex("[a-zA-Z0-9_]+"))) { "Linux 架构无效" }
            val custom = selected.value.takeIf { it.id == LinuxMirrorId.CUSTOM }
            val choices = LinuxMirrorId.entries.filter { it != LinuxMirrorId.CUSTOM }.map(::LinuxMirrorChoice) + listOfNotNull(custom)
            // 每轮清除旧值，取消时只保留已经完成的镜像。
            mutableLatencies.value = emptyMap()
            for (choice in choices) {
                currentCoroutineContext().ensureActive()
                val latency = try {
                    withTimeoutOrNull(5_000) {
                        val start = clockNanos()
                        if (http.head("${baseUrl(choice)}/v$version/main/$architecture/APKINDEX.tar.gz"))
                            ((clockNanos() - start) / 1_000_000).coerceAtLeast(0) else null
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IOException) { null }
                catch (_: IllegalArgumentException) { null }
                mutableLatencies.value = mutableLatencies.value + (choice.id to latency)
                preferences.saveLatencies(mutableLatencies.value)
                emit(choice.id to latency)
            }
        }
    }.flowOn(ioDispatcher)

    companion object {
        internal fun baseUrl(choice: LinuxMirrorChoice): String {
            if (choice.id != LinuxMirrorId.CUSTOM) return "https://${choice.id.host}/alpine"
            val raw = choice.customUrl ?: throw IllegalArgumentException("自定义镜像地址不能为空")
            val uri = try { URI(raw) } catch (_: Exception) { throw IllegalArgumentException("镜像地址无效") }
            require(raw.length <= 2048 && raw.none { it.isWhitespace() || it.isISOControl() } &&
                !Regex("%(?:0[0-9a-f]|1[0-9a-f]|20|7f)", RegexOption.IGNORE_CASE).containsMatchIn(raw) &&
                (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535) &&
                uri.path.split('/').none { it == ".." || it == "." }) { "镜像必须是无凭据、查询参数或片段的有效 HTTP/HTTPS 地址" }
            val base = raw.trimEnd('/')
            return if (base.endsWith("/alpine")) base else "$base/alpine"
        }

        private fun version(release: String): String = Regex("^(\\d+\\.\\d+)(?:\\.\\d+)?(?:[-_][a-zA-Z0-9_.-]+)?$")
            .matchEntire(release.trim())?.groupValues?.get(1) ?: throw IllegalArgumentException("无法识别 Alpine 版本")
    }
}
