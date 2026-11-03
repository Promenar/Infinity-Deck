package com.promenar.infinity.domain.linux

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** apk 软件包管理。所有操作在环境就绪后才可调用，否则抛出 [LinuxUnavailableException]。同一时间只运行一个操作。 */
interface LinuxPackageManager {
    /** 当前操作；无操作时为 null。 */
    val operation: StateFlow<LinuxPackageOperation?>

    /** 已安装的顶层与依赖包；[LinuxPackage.availableVersion] 依赖最近一次 [refreshIndex]。 */
    suspend fun installed(): List<LinuxPackage>

    suspend fun search(query: String, limit: Int = 50): List<LinuxPackage>

    /** `apk update`。 */
    suspend fun refreshIndex(): LinuxPackageResult

    suspend fun install(names: List<String>, origin: LinuxPackageOrigin): LinuxPackageResult

    suspend fun remove(names: List<String>): LinuxPackageResult

    /** [names] 为空时升级全部。 */
    suspend fun upgrade(names: List<String> = emptyList()): LinuxPackageResult
}

/**
 * 软件包来源记录。Agent 通过 `shell_exec` 运行 `apk add` 时，执行层在命令前后各取一次快照，
 * 把新增的顶层包归到对应会话。
 */
interface LinuxPackageProvenance {
    /** 读取 `/etc/apk/world`；环境未就绪时返回 null。 */
    suspend fun worldSnapshot(): Set<String>?

    suspend fun attributeNewPackages(before: Set<String>, origin: LinuxPackageOrigin)
}

data class LinuxPackage(
    val name: String,
    val version: String,
    val description: String?,
    val installedSizeBytes: Long?,
    val installed: Boolean,
    /** 有更新时的新版本。 */
    val availableVersion: String? = null,
    /** 在 `/etc/apk/world` 中（用户或 Agent 显式安装）；false 表示作为依赖引入。 */
    val explicit: Boolean = false,
    val origin: LinuxPackageOrigin? = null,
)

sealed interface LinuxPackageOrigin {
    /** 安装引导中勾选的常用工具。 */
    data object Preset : LinuxPackageOrigin
    data object User : LinuxPackageOrigin
    data class Agent(val sessionId: String, val sessionTitle: String?) : LinuxPackageOrigin
}

enum class LinuxPackageAction { REFRESH, INSTALL, REMOVE, UPGRADE }

data class LinuxPackageOperation(
    val action: LinuxPackageAction,
    val names: List<String>,
    /** apk 的 `(i/n)` 进度；无法解析时为 null。 */
    val step: Int?,
    val totalSteps: Int?,
    val downloadedBytes: Long?,
    val totalBytes: Long?,
    val outputTail: String,
)

data class LinuxPackageResult(
    val success: Boolean,
    val exitCode: Int,
    val output: String,
    val failure: LinuxFailure? = null,
)

object LinuxPackagePresets {
    val BASE: List<String> = listOf("bash", "ca-certificates", "curl")
    val COMMON: List<String> = BASE + optional(LinuxOptionalPackage.entries.toSet())

    fun optional(selection: Set<LinuxOptionalPackage>): List<String> =
        LinuxOptionalPackage.entries.filter { it in selection }.flatMap { it.packages }
}

enum class LinuxOptionalPackage(val packages: List<String>) {
    GIT(listOf("git")),
    RIPGREP(listOf("ripgrep")),
    PYTHON3(listOf("python3", "py3-pip")),
    NODEJS(listOf("nodejs", "npm")),
    FFMPEG(listOf("ffmpeg")),
}

/** 已安装环境补装勾选的附加包，沿用现有软件包操作、保活和来源记录。 */
suspend fun LinuxPackageManager.installOptionalPackages(selection: Set<LinuxOptionalPackage>): LinuxPackageResult {
    val names = LinuxPackagePresets.optional(selection)
    return if (names.isEmpty()) LinuxPackageResult(true, 0, "") else install(names, LinuxPackageOrigin.Preset)
}

/** apk 仓库镜像。签名始终用 Alpine 官方密钥校验。 */
interface LinuxMirrorSettings {
    val selected: StateFlow<LinuxMirrorChoice>

    /** 最近一次测速结果（毫秒）；未测或失败为 null。 */
    val latencies: StateFlow<Map<LinuxMirrorId, Long?>>

    /** 写入 `/etc/apk/repositories`；自定义地址支持 HTTP/HTTPS。 */
    suspend fun select(choice: LinuxMirrorChoice)

    /** 依次测速并逐个发出结果，同时更新 [latencies]。 */
    fun speedTest(): Flow<Pair<LinuxMirrorId, Long?>>
}

enum class LinuxMirrorId(val host: String) {
    TUNA("mirrors.tuna.tsinghua.edu.cn"),
    USTC("mirrors.ustc.edu.cn"),
    ALIYUN("mirrors.aliyun.com"),
    OFFICIAL("dl-cdn.alpinelinux.org"),
    CUSTOM(""),
}

data class LinuxMirrorChoice(val id: LinuxMirrorId, val customUrl: String? = null)
