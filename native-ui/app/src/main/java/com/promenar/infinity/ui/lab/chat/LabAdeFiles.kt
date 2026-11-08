package com.promenar.infinity.ui.lab.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationCapabilities
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.ui.files.FileManagerHostSpec
import com.promenar.infinity.ui.files.FileManagerPane
import com.promenar.infinity.ui.files.preview.FakeFileManagerController

private const val STARLOG_LOCATION = "lab-starlog"
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val KB = 1024L

/**
 * starlog 仓库根目录（检视器“文件”页签）。位置固定为仓库、不可切换，页头因此是 Git 分支卡片：
 * 分支 main，改动数 [changeCount] 与会话顶栏的状态行一致；点卡片交给 [onOpenGit] 切到 Git 页签。
 */
@Composable
internal fun AdeFilesPanel(changeCount: Int, onOpenGit: () -> Unit) {
    val controller = remember(changeCount) { starlogFiles(changeCount, System.currentTimeMillis()) }
    FileManagerPane(
        controller = controller,
        host = FileManagerHostSpec(onOpenGit = onOpenGit),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    )
}

/** starlog 的目录树：源码包、测试与项目文件，修改时间与脚本里刚写完的文件对应。 */
internal fun starlogFiles(changeCount: Int, nowMillis: Long): FakeFileManagerController {
    val location = FileLocation(
        id = STARLOG_LOCATION,
        kind = FileLocationKind.PROJECT,
        title = "starlog",
        capabilities = FileLocationCapabilities(writable = true, deletable = true, archive = true, git = true, terminal = true),
        gitBranch = "main",
        gitChangeCount = changeCount,
    )
    fun dir(path: String, count: Int, age: Long) =
        FileItem(FileRef(STARLOG_LOCATION, path), path.substringAfterLast('/'), isDirectory = true, sizeBytes = 0, lastModified = nowMillis - age, childCount = count)
    fun file(path: String, size: Long, age: Long, mime: String? = null) =
        FileItem(FileRef(STARLOG_LOCATION, path), path.substringAfterLast('/'), isDirectory = false, sizeBytes = size, lastModified = nowMillis - age, mimeType = mime)
    val root = listOf(
        dir("/starlog", 4, 2 * MINUTE),
        dir("/tests", 3, 3 * MINUTE),
        dir("/logs", 0, 26 * HOUR),
        file("/README.md", 3 * KB + 200, 4 * MINUTE, "text/markdown"),
        file("/pyproject.toml", 1 * KB, 4 * MINUTE),
        file("/CHANGELOG.md", 2 * KB, 2 * 24 * HOUR, "text/markdown"),
        file("/.gitignore", 120, 5 * 24 * HOUR),
    )
    return FakeFileManagerController(
        locations = listOf(location),
        tree = mapOf(
            FileRef(STARLOG_LOCATION, "/") to root,
            FileRef(STARLOG_LOCATION, "/starlog") to listOf(
                file("/starlog/__main__.py", 4 * KB, 2 * MINUTE, "text/x-python"),
                file("/starlog/report.py", 6 * KB, 2 * MINUTE, "text/x-python"),
                file("/starlog/parser.py", 5 * KB, 3 * 24 * HOUR, "text/x-python"),
                file("/starlog/__init__.py", 60, 5 * 24 * HOUR, "text/x-python"),
            ),
            FileRef(STARLOG_LOCATION, "/tests") to listOf(
                file("/tests/test_main.py", 7 * KB, 3 * MINUTE, "text/x-python"),
                file("/tests/test_parser.py", 4 * KB, 4 * 24 * HOUR, "text/x-python"),
                file("/tests/conftest.py", 1 * KB, 4 * 24 * HOUR, "text/x-python"),
            ),
            FileRef(STARLOG_LOCATION, "/logs") to emptyList(),
        ),
        initialLocationId = STARLOG_LOCATION,
        clock = { nowMillis },
    )
}
