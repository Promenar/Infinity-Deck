package com.promenar.infinity.ui.chat

import com.promenar.infinity.data.artifact.ArtifactKind
import com.promenar.infinity.data.artifact.WorkspaceChangedFile
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.FileChangeType
import com.promenar.infinity.ui.chat.diff.parseWorkspaceChanges
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** `artifact_publish` 成功结果在会话中的引用。 */
internal data class ArtifactInvocation(val id: String, val title: String, val kind: ArtifactKind, val version: Int)

private val ARTIFACT_KIND_NAMES = mapOf(
    "page" to ArtifactKind.PAGE,
    "document" to ArtifactKind.DOCUMENT,
    "image" to ArtifactKind.IMAGE,
    "other" to ArtifactKind.OTHER,
)

internal fun artifactInvocation(invocation: ToolInvocationPresentation): ArtifactInvocation? {
    if (invocation.name != "artifact_publish" || invocation.failed) return null
    return invocation.results.filter { it.type == "tool_result" }.firstNotNullOfOrNull { result ->
        val value = runCatching { Json.parseToJsonElement(result.content.orEmpty()) as? JsonObject }.getOrNull() ?: return@firstNotNullOfOrNull null
        fun text(key: String) = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val id = text("artifactId")?.takeIf(String::isNotBlank) ?: return@firstNotNullOfOrNull null
        val kind = ARTIFACT_KIND_NAMES[text("kind")] ?: return@firstNotNullOfOrNull null
        val version = (value["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 } ?: return@firstNotNullOfOrNull null
        ArtifactInvocation(id, text("title").orEmpty(), kind, version)
    }
}

/** 产出新文件的媒体工具；查看类工具只引用已有媒体，不算改动。 */
private val MEDIA_PRODUCING_TOOLS = setOf(
    "image_generation", "generate_image",
    "speech_generation", "generate_speech",
    "music_generation", "generate_music",
    "media_fetch",
)

/**
 * 一条助手消息的工具调用新增或修改的工作区文件，按出现顺序：命令对账的新增、修改与移动目标，
 * 文件工具的新建、修改与移动目标，以及生成或下载的媒体。删除不算；失败的调用不算。
 */
internal fun workspaceChangedFiles(messageId: String, steps: List<ExecutionStep>): List<WorkspaceChangedFile> =
    toolInvocations(steps).filterNot { it.failed }.flatMap { invocation ->
        invocation.results.filter { it.type == "tool_result" }.flatMap { result ->
            buildList {
                parseWorkspaceChanges(result.content)?.entries?.forEach { entry ->
                    if (entry.change != FileChangeType.DELETED) add(entry.path)
                }
                result.fileChanges.orEmpty().forEach { change ->
                    if (change.change != FileChangeType.DELETED && !change.directory) add(change.path)
                }
                if (invocation.name in MEDIA_PRODUCING_TOOLS) result.media.orEmpty().forEach { add(it.path) }
            }.map { path -> WorkspaceChangedFile(path, messageId, result.timestamp) }
        }
    }
