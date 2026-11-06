package com.promenar.infinity.ui.files.preview

import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileKnowledge
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationCapabilities
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.domain.files.FilePreview
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.files.KnowledgeIndexState

/*
 * 资源管理器的示例数据，供预览与截图测试使用。名称与内容均为虚构。
 * 时间以固定的 [FileManagerSamples.NOW] 为基准，截图在任何日期运行都一致。
 */
internal object FileManagerSamples {
    /** 2026-11-03 16:00（东八区）。 */
    const val NOW: Long = 1_793_671_200_000L
    const val LIBRARY_ID = "sample-library"
    const val WORKSPACE_ID = "sample-workspace"
    const val PROJECT_ID = "sample-project"

    private const val HOUR = 60L * 60 * 1000
    private const val DAY = 24 * HOUR
    private const val KB = 1024L
    private const val MB = 1024L * 1024

    private class Names(val english: Boolean) {
        fun t(zh: String, en: String) = if (english) en else zh
    }

    fun locations(english: Boolean): List<FileLocation> {
        val n = Names(english)
        val full = FileLocationCapabilities(writable = true, deletable = true, archive = true)
        return listOf(
            FileLocation(
                id = LIBRARY_ID,
                kind = FileLocationKind.KNOWLEDGE_BASE,
                title = n.t("存储空间", "Storage"),
                capabilities = full,
                freeBytes = 18_400 * MB,
            ),
            FileLocation(
                id = WORKSPACE_ID,
                kind = FileLocationKind.SESSION_WORKSPACE,
                title = n.t("会话工作区", "Session workspace"),
                capabilities = full,
                sessionId = "sample-session",
            ),
            FileLocation(
                id = PROJECT_ID,
                kind = FileLocationKind.PROJECT,
                title = n.t("示例项目", "Sample project"),
                capabilities = full.copy(git = true, terminal = true),
                gitBranch = "main",
                gitChangeCount = 3,
                freeBytes = 18_400 * MB,
            ),
        )
    }

    fun folderPath(english: Boolean): String = "/" + Names(english).t("产品调研", "Product research")

    fun nestedFolderPath(english: Boolean): String = folderPath(english) + "/" + Names(english).t("旧版", "Archive")

    fun markdownPath(english: Boolean): String = "/" + Names(english).t("离线模式架构决策.md", "Offline mode decision.md")

    fun searchText(english: Boolean): String = Names(english).t("纪要", "notes")

    fun controller(
        english: Boolean = false,
        locationId: String = LIBRARY_ID,
        path: String = "/",
    ): FakeFileManagerController {
        val tree = tree(english)
        return FakeFileManagerController(
            locations = locations(english),
            tree = tree,
            previews = previews(english),
            initialLocationId = locationId,
            initialPath = path,
            clock = { NOW },
        )
    }

    private fun tree(english: Boolean): Map<FileRef, List<FileItem>> {
        val n = Names(english)
        val lib = LIBRARY_ID
        fun dir(location: String, path: String, name: String, count: Int, ageHours: Long) =
            FileItem(FileRef(location, path), name, isDirectory = true, sizeBytes = 0, lastModified = NOW - ageHours * HOUR, childCount = count)
        fun file(
            location: String,
            path: String,
            name: String,
            size: Long,
            ageHours: Long,
            knowledge: FileKnowledge? = null,
            mime: String? = null,
        ) = FileItem(FileRef(location, path), name, isDirectory = false, sizeBytes = size, lastModified = NOW - ageHours * HOUR, mimeType = mime, knowledge = knowledge)
        fun child(parent: String, name: String) = if (parent == "/") "/$name" else "$parent/$name"

        val research = folderPath(english)
        val older = nestedFolderPath(english)
        val meetings = "/" + n.t("会议纪要", "Meeting notes")
        val designs = "/" + n.t("设计稿", "Designs")

        val libraryRoot = listOf(
            dir(lib, research, n.t("产品调研", "Product research"), 6, 3),
            dir(lib, meetings, n.t("会议纪要", "Meeting notes"), 3, 26),
            dir(lib, designs, n.t("设计稿", "Designs"), 0, 120),
            file(lib, markdownPath(english), n.t("离线模式架构决策.md", "Offline mode decision.md"), 12 * KB + 410, 2,
                FileKnowledge(KnowledgeIndexState.INDEXED, documentId = "doc-1", chunkCount = 14), "text/markdown"),
            file(lib, child("/", n.t("发布检查清单.pdf", "Release checklist.pdf")), n.t("发布检查清单.pdf", "Release checklist.pdf"), 2 * MB + 120 * KB, 30,
                FileKnowledge(KnowledgeIndexState.FAILED, failureReason = n.t("嵌入表格未能提取正文", "Text inside embedded tables could not be extracted")), "application/pdf"),
            file(lib, child("/", n.t("定价策略草稿.md", "Pricing draft.md")), n.t("定价策略草稿.md", "Pricing draft.md"), 6 * KB, 120,
                FileKnowledge(KnowledgeIndexState.STALE, documentId = "doc-5", chunkCount = 6), "text/markdown"),
            file(lib, child("/", n.t("素材.zip", "Assets.zip")), n.t("素材.zip", "Assets.zip"), 48 * MB, 72, mime = "application/zip"),
            file(lib, child("/", n.t("演示录屏.mp4", "Demo recording.mp4")), n.t("演示录屏.mp4", "Demo recording.mp4"), 86 * MB, 200, mime = "video/mp4"),
            file(lib, "/.library-config.json", ".library-config.json", 2 * KB, 400, mime = "application/json"),
        )
        val researchItems = listOf(
            dir(lib, older, n.t("旧版", "Archive"), 2, 400),
            file(lib, child(research, n.t("竞品访谈纪要 10 月.docx", "Competitor interview notes Oct.docx")),
                n.t("竞品访谈纪要 10 月.docx", "Competitor interview notes Oct.docx"), 340 * KB, 5, FileKnowledge(KnowledgeIndexState.QUEUED)),
            file(lib, child(research, n.t("用户调研问卷汇总.pdf", "Survey summary.pdf")),
                n.t("用户调研问卷汇总.pdf", "Survey summary.pdf"), 1 * MB + 300 * KB, 3, FileKnowledge(KnowledgeIndexState.INDEXING), "application/pdf"),
            file(lib, child(research, n.t("访谈录音.m4a", "Interview recording.m4a")),
                n.t("访谈录音.m4a", "Interview recording.m4a"), 22 * MB, 8, mime = "audio/mp4"),
            file(lib, child(research, n.t("白板照片.jpg", "Whiteboard photo.jpg")),
                n.t("白板照片.jpg", "Whiteboard photo.jpg"), 3 * MB, 9, mime = "image/jpeg"),
            file(lib, child(research, n.t("调研提纲.txt", "Research outline.txt")),
                n.t("调研提纲.txt", "Research outline.txt"), 2 * KB, 50, mime = "text/plain"),
        )
        val olderItems = listOf(
            file(lib, child(older, n.t("访谈提纲 v1.txt", "Interview outline v1.txt")), n.t("访谈提纲 v1.txt", "Interview outline v1.txt"), 1 * KB, 900),
            file(lib, child(older, n.t("早期结论.md", "Early findings.md")), n.t("早期结论.md", "Early findings.md"), 3 * KB, 920),
        )
        val meetingItems = listOf(
            file(lib, child(meetings, n.t("周会纪要 11-03.md", "Weekly notes 11-03.md")), n.t("周会纪要 11-03.md", "Weekly notes 11-03.md"), 4 * KB, 6,
                FileKnowledge(KnowledgeIndexState.INDEXED, chunkCount = 3)),
            file(lib, child(meetings, n.t("周会纪要 10-27.md", "Weekly notes 10-27.md")), n.t("周会纪要 10-27.md", "Weekly notes 10-27.md"), 5 * KB, 7L * 24,
                FileKnowledge(KnowledgeIndexState.INDEXED, chunkCount = 4)),
            file(lib, child(meetings, n.t("季度复盘.md", "Quarterly review.md")), n.t("季度复盘.md", "Quarterly review.md"), 9 * KB, 20L * 24),
        )
        val ws = WORKSPACE_ID
        val workspaceRoot = listOf(
            dir(ws, "/src", "src", 4, 1),
            file(ws, "/notes.md", "notes.md", 2 * KB, 1),
            file(ws, "/build.log", "build.log", 180 * KB, 2),
        )
        val project = PROJECT_ID
        val projectRoot = listOf(
            dir(project, "/app", "app", 5, 4),
            dir(project, "/docs", "docs", 2, 30),
            file(project, "/README.md", "README.md", 6 * KB, 4),
            file(project, "/settings.gradle.kts", "settings.gradle.kts", 1 * KB, 48),
        )
        return mapOf(
            FileRef(lib, "/") to libraryRoot,
            FileRef(lib, research) to researchItems,
            FileRef(lib, older) to olderItems,
            FileRef(lib, meetings) to meetingItems,
            FileRef(lib, designs) to emptyList(),
            FileRef(ws, "/") to workspaceRoot,
            FileRef(ws, "/src") to emptyList(),
            FileRef(project, "/") to projectRoot,
            FileRef(project, "/app") to emptyList(),
            FileRef(project, "/docs") to emptyList(),
        )
    }

    private fun previews(english: Boolean): Map<String, FilePreview> {
        val n = Names(english)
        val markdown = if (english) {
            "# Offline mode decision\n\nStatus: accepted\n\n## Decision\n\n- Writes go to a local queue first.\n- The queue replays in order once the device is online.\n- On conflict the server copy wins and the local copy is kept for review."
        } else {
            "# 离线模式架构决策\n\n状态：已采纳\n\n## 决策\n\n- 写操作先进入本地离线队列。\n- 联网后按入队顺序重放。\n- 同步冲突以服务端版本为准，本地副本另存供比对。"
        }
        val research = folderPath(english)
        return mapOf(
            markdownPath(english) to FilePreview.Text(markdown, truncated = false, markdown = true),
            "/" + n.t("发布检查清单.pdf", "Release checklist.pdf") to FilePreview.Pdf("content://example.invalid/checklist", pageCount = 12),
            "/" + n.t("素材.zip", "Assets.zip") to FilePreview.Archive(
                entries = listOf("icons/", "icons/app.svg", "icons/folder.svg", "photos/board-01.jpg", "photos/board-02.jpg", "readme.txt"),
                totalEntries = 128,
            ),
            "/" + n.t("演示录屏.mp4", "Demo recording.mp4") to FilePreview.Video("content://example.invalid/demo", durationMs = 192_000),
            "$research/" + n.t("访谈录音.m4a", "Interview recording.m4a") to FilePreview.Audio("content://example.invalid/interview", durationMs = 1_458_000),
            "$research/" + n.t("白板照片.jpg", "Whiteboard photo.jpg") to FilePreview.Image("content://example.invalid/whiteboard"),
            "$research/" + n.t("调研提纲.txt", "Research outline.txt") to FilePreview.Text(
                n.t("1. 每天什么时候使用？\n2. 断网时遇到过什么问题？\n3. 最希望先改进哪一项？", "1. When do you use it each day?\n2. What happened when you lost signal?\n3. What should improve first?"),
                truncated = false,
                markdown = false,
            ),
        )
    }


}

/** 当前目录里路径为 [path] 的条目，供示例进入预览或选择。 */
internal fun FakeFileManagerController.itemAt(path: String): FileItem? = state.value.items.firstOrNull { it.ref.path == path }
