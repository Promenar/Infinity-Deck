package com.promenar.infinity.ui.chat.components

import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalMount
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal enum class ExternalMountSource {
    Saf,
    RealPath,
}

internal fun availableExternalMountSources(
    buildAllFilesAccess: Boolean,
    distributionAllFilesAccess: Boolean,
    linuxSession: Boolean = false,
): List<ExternalMountSource> = buildList {
    if (!linuxSession) add(ExternalMountSource.Saf)
    if (buildAllFilesAccess && distributionAllFilesAccess) add(ExternalMountSource.RealPath)
}

/** 表单里已选定的挂载目录：[location] 为真实路径或系统授权给出的可读位置，取不到时为 null。 */
internal data class PickedMountFolder(val name: String, val location: String?)

/** 真实路径的选定目录直接取自路径本身；空白路径表示尚未选定。 */
internal fun pickedRealPathFolder(path: String): PickedMountFolder? {
    val trimmed = path.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    return PickedMountFolder(trimmed.substringAfterLast('/').ifEmpty { trimmed }, path.trim())
}

private const val GENERATED_ALIAS_PREFIX = "external"
private val GENERATED_ALIAS = Regex("$GENERATED_ALIAS_PREFIX([2-9]|[1-9][0-9]+)?")
private const val MAX_FOLDER_ALIAS_LENGTH = 40

/** 表单打开时的默认别名：external、external2……取第一个未被占用的。 */
internal fun nextGeneratedMountAlias(taken: Collection<String>): String {
    var candidate = GENERATED_ALIAS_PREFIX
    var suffix = 2
    while (candidate in taken) candidate = GENERATED_ALIAS_PREFIX + suffix++
    return candidate
}

/** 只有自动生成的默认别名会被选定目录名替换，用户改过的别名保持不变。 */
internal fun isGeneratedMountAlias(alias: String): Boolean = GENERATED_ALIAS.matches(alias.trim())

/**
 * 由目录名生成别名：空白折成连字符、ASCII 字母转小写、去掉控制字符与路径分隔符，过长时截断；
 * 结果不能作别名（空、`.`/`..`、保留名）时返回 null，与 [taken] 重名时追加 -2、-3。
 */
internal fun mountAliasFromFolderName(name: String, taken: Collection<String>): String? {
    val base = name.trim()
        .replace(Regex("\\s+"), "-")
        .filterNot { it.isISOControl() || it == '/' || it == '\\' }
        .map { if (it in 'A'..'Z') it.lowercaseChar() else it }
        .joinToString("")
        .take(MAX_FOLDER_ALIAS_LENGTH)
        .trim('-')
    val valid = try {
        ExternalMountPaths.validateAlias(base)
        true
    } catch (_: ExternalFolderException) {
        false
    }
    if (!valid) return null
    var candidate = base
    var suffix = 2
    while (candidate in taken) candidate = "$base-${suffix++}"
    return candidate
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val PRIMARY_VOLUME_ID = "primary"

/**
 * 系统外部存储提供方的树文档 ID（`primary:Documents/a`、`1A2B-3C4D:Music`）转成“内部存储/Documents/a”这类可读位置；
 * 其它提供方（如 Termux）的文档 ID 不是存储路径，返回 null。
 */
internal fun safTreeLocationLabel(authority: String?, treeDocumentId: String?, primaryLabel: String): String? {
    if (authority != EXTERNAL_STORAGE_AUTHORITY || treeDocumentId == null || ':' !in treeDocumentId) return null
    val volume = treeDocumentId.substringBefore(':')
    if (volume.isEmpty()) return null
    val segments = treeDocumentId.substringAfter(':').split('/').filter(String::isNotEmpty)
    return (listOf(if (volume == PRIMARY_VOLUME_ID) primaryLabel else volume) + segments).joinToString("/")
}

/** 查不到显示名时用树文档 ID 的末段作目录名。 */
internal fun safTreeFallbackName(treeDocumentId: String?): String? =
    treeDocumentId?.substringAfter(':')?.trimEnd('/')?.substringAfterLast('/')?.takeIf(String::isNotEmpty)

/** 空会话不展示挂载，防止页面切换时展示其它会话的路径。 */
internal fun visibleExternalMounts(mounts: List<ExternalMount>, sessionId: String?): List<ExternalMount> =
    mounts.filter { !sessionId.isNullOrBlank() && it.sessionId == sessionId }

/** 浏览器回退只能留在当前挂载内，不能把挂载别名的相似前缀当作子目录。 */
internal fun parentExternalFolderPath(rootPath: String, path: String): String? {
    if (!path.startsWith("$rootPath/") || '\\' in path) return null
    val segments = path.removePrefix("$rootPath/").split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
    return path.substringBeforeLast('/').takeIf { it == rootPath || it.startsWith("$rootPath/") }
}

internal sealed interface ExternalTextPreview {
    data object Loading : ExternalTextPreview
    data object Failed : ExternalTextPreview
    data object Binary : ExternalTextPreview
    data object TooLarge : ExternalTextPreview
    data class Text(val content: String) : ExternalTextPreview
}

/** 不截断字节流或替换坏字符，避免把损坏内容显示成可信的完整文本。 */
internal fun decodeExternalTextPreview(bytes: ByteArray, maxBytes: Int): ExternalTextPreview {
    if (bytes.size > maxBytes) return ExternalTextPreview.TooLarge
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        return ExternalTextPreview.Binary
    }
    if (text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
        return ExternalTextPreview.Binary
    }
    return ExternalTextPreview.Text(text)
}

internal fun externalPreviewFileName(name: String): String = name
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .map { if (it.isISOControl()) '_' else it }
    .joinToString("")
    .take(180)
    .takeUnless { it.isBlank() || it == "." || it == ".." }
    ?: "preview"
