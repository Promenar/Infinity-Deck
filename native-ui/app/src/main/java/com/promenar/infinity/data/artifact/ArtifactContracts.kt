package com.promenar.infinity.data.artifact

import com.promenar.infinity.data.canvas.CanvasKind
import com.promenar.infinity.data.canvas.CanvasMetadata
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 产物页的分类。 */
@Serializable
enum class ArtifactKind {
    @SerialName("prototype") PROTOTYPE,
    @SerialName("page") PAGE,
    @SerialName("document") DOCUMENT,
    @SerialName("image") IMAGE,
    @SerialName("other") OTHER,
}

/** 产物的来源：主动声明为主，工作区对账为辅。 */
@Serializable
enum class ArtifactOrigin {
    /** `canvas_create` 生成的页面或原型，存于画布存储。 */
    @SerialName("canvas") CANVAS,

    /** Agent 以 `artifact_publish` 声明的工作区文件，保存为私有快照。 */
    @SerialName("declared") DECLARED,

    /** 用户从“工作区新文件”收录的文件，保存为私有快照。 */
    @SerialName("claimed") CLAIMED,
}

/**
 * 产物页的一项。画布产物的 [id] 即画布 ID；文件产物的 [id] 由文件产物存储分配，二者不重叠。
 * [sourcePath] 是文件产物来源的工作区路径，按会话运行模式呈现（Linux 会话为 `/home/session/…` 等）。
 */
data class ArtifactSummary(
    val id: String,
    val kind: ArtifactKind,
    val origin: ArtifactOrigin,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val currentVersion: Int,
    /** 声明或创建它的助手消息；用户收录的为空。 */
    val createdByMessageId: String?,
    /** 原型的屏数，其它为 0。 */
    val screenCount: Int = 0,
    val fileName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val sourcePath: String? = null,
)

/** 文件产物某一版本的私有快照；[file] 只读，调用方不得修改或删除。 */
data class ArtifactFileSnapshot(val summary: ArtifactSummary, val version: Int, val file: File)

/** 某次工具调用新增或修改的工作区文件，由界面从工具结果中取出后交给 [SessionArtifactRepository.candidates]。 */
data class WorkspaceChangedFile(val path: String, val messageId: String, val changedAt: Long)

/** 工作区里新出现或被修改、尚未声明为产物、也未被忽略的文件。 */
data class ArtifactCandidate(
    val path: String,
    val fileName: String,
    val kind: ArtifactKind,
    val sizeBytes: Long?,
    val changedByMessageId: String,
    val changedAt: Long,
)

/**
 * 会话产物：画布（页面、原型）与文件快照（文档、图片、其它）合并成一个列表。
 *
 * 文件快照与工作区解耦：工作区里的原文件之后被改动或删除，快照不变；同一来源路径再次声明或收录时追加新版本，
 * 内容未变则不追加。
 */
interface SessionArtifactRepository {
    /** 全部产物，按 [ArtifactSummary.updatedAt] 倒序。 */
    fun observe(sessionId: String): Flow<List<ArtifactSummary>>

    /**
     * 把工作区文件保存为产物快照。[path] 为模型或界面可见的工作区路径；[kind] 为空时按文件名与类型推断。
     * 失败抛出 [ArtifactException]。
     */
    suspend fun publishFile(
        sessionId: String,
        path: String,
        title: String? = null,
        kind: ArtifactKind? = null,
        origin: ArtifactOrigin,
        messageId: String? = null,
    ): ArtifactSummary

    /** 读取文件产物的快照，默认最新版；不存在时为空。 */
    suspend fun readFile(sessionId: String, id: String, version: Int? = null): ArtifactFileSnapshot?

    /**
     * 从本会话的工作区改动中挑出未收录的候选：同一路径取最后一次改动；排除已声明的来源路径、已忽略的路径、
     * [excludedPathPrefixes] 指定的托管目录（如已绑定的制作项目根目录）、已不存在或是目录的路径、
     * 隐藏目录与内部目录（`.infinity`、`.git`、`node_modules` 等）、画布镜像目录与临时类文件。
     */
    suspend fun candidates(
        sessionId: String,
        changes: List<WorkspaceChangedFile>,
        excludedPathPrefixes: Set<String> = emptySet(),
    ): List<ArtifactCandidate>

    /** 忽略一个候选路径；之后同一路径不再作为候选，直到它被声明或收录。 */
    suspend fun ignoreCandidate(sessionId: String, path: String)
}

class ArtifactException(val code: String, override val message: String) : Exception(message)

val CanvasKind.artifactKind: ArtifactKind
    get() = when (this) {
        CanvasKind.PAGE -> ArtifactKind.PAGE
        CanvasKind.PROTOTYPE -> ArtifactKind.PROTOTYPE
    }

/** 画布元数据在产物页的呈现。 */
fun CanvasMetadata.toArtifactSummary(): ArtifactSummary = ArtifactSummary(
    id = id,
    kind = kind.artifactKind,
    origin = ArtifactOrigin.CANVAS,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    currentVersion = currentVersion,
    createdByMessageId = createdByMessageId.takeIf(String::isNotBlank),
    screenCount = screens.size,
)

/** 按扩展名分类，扩展名不认识时参考 MIME 类型；HTML 文件归入页面。 */
fun artifactKindFor(fileName: String, mimeType: String?): ArtifactKind {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    EXTENSION_KINDS[extension]?.let { return it }
    if (extension.isNotEmpty() && extension in OTHER_EXTENSIONS) return ArtifactKind.OTHER
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return when {
        mime.startsWith("image/") -> ArtifactKind.IMAGE
        mime == "text/html" -> ArtifactKind.PAGE
        mime in DOCUMENT_MIME_TYPES -> ArtifactKind.DOCUMENT
        else -> ArtifactKind.OTHER
    }
}

private val EXTENSION_KINDS: Map<String, ArtifactKind> = buildMap {
    listOf("md", "markdown", "txt", "pdf", "doc", "docx", "odt", "rtf", "epub", "ppt", "pptx", "odp")
        .forEach { put(it, ArtifactKind.DOCUMENT) }
    listOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "heic", "heif", "avif")
        .forEach { put(it, ArtifactKind.IMAGE) }
    listOf("html", "htm").forEach { put(it, ArtifactKind.PAGE) }
}

/** 表格、数据与压缩包等明确归入其它，不受文本类 MIME 影响。 */
private val OTHER_EXTENSIONS = setOf("csv", "tsv", "xls", "xlsx", "ods", "json", "xml", "yaml", "yml", "zip", "tar", "gz", "7z")

private val DOCUMENT_MIME_TYPES = setOf(
    "text/markdown", "text/plain", "application/pdf", "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
)

private val ARTIFACT_EXCLUSION_ROOT_PREFIXES = listOf(
    "/home/session/",
    "/workspace/",
    "/session/",
    "/.infinity-production-private/",
    ".infinity-production-private/",
    "/",
)

private val ARTIFACT_EXCLUSION_BARE_ROOTS = setOf(
    "/home/session",
    "/workspace",
    "/session",
    "/.infinity-production-private",
    ".infinity-production-private",
    "/",
)

private val MULTIPLE_SLASHES_REGEX = Regex("/+")

private fun normalizeArtifactExclusionPath(raw: String): String {
    val trimmed = raw.trim().replace('\\', '/')
    if (trimmed.isEmpty()) return ""
    val collapsed = MULTIPLE_SLASHES_REGEX.replace(trimmed, "/")
    val withoutTrailing = collapsed.trimEnd('/').ifEmpty { "/" }
    if (withoutTrailing in ARTIFACT_EXCLUSION_BARE_ROOTS) return ""
    val stripped = ARTIFACT_EXCLUSION_ROOT_PREFIXES
        .firstOrNull { withoutTrailing.startsWith(it) }
        ?.let { withoutTrailing.removePrefix(it) }
        ?: withoutTrailing
    val resolved = ArrayDeque<String>()
    for (segment in stripped.split('/')) {
        when (segment) {
            "", "." -> continue
            ".." -> if (resolved.isEmpty()) return "" else resolved.removeLast()
            else -> resolved.addLast(segment)
        }
    }
    return resolved.joinToString("/")
}

/**
 * 判断工作区路径 [path] 是否落在排除目录 [excludedPrefix] 下（含目录本身与任意嵌套子目录）。
 * 统一剥离首尾斜杠与已知工作区根前缀（`/home/session/`、`/workspace/`、`/session/`、`.infinity-production-private/`、`/`），
 * 并按完整目录边界匹配，避免将兄弟目录（如 `productions/demo-2/foo.md`）误判为 `productions/demo` 的子项。
 */
fun isInsideExcludedDirectory(path: String, excludedPrefix: String): Boolean {
    val normalizedPrefix = normalizeArtifactExclusionPath(excludedPrefix)
    if (normalizedPrefix.isEmpty()) return false
    val normalizedPath = normalizeArtifactExclusionPath(path)
    if (normalizedPath.isEmpty()) return false
    return normalizedPath == normalizedPrefix || normalizedPath.startsWith("$normalizedPrefix/")
}

/** 判断工作区路径 [path] 是否命中任意排除目录前缀。 */
fun isPathExcluded(path: String, excludedPrefixes: Collection<String>): Boolean =
    excludedPrefixes.any { prefix -> isInsideExcludedDirectory(path, prefix) }
