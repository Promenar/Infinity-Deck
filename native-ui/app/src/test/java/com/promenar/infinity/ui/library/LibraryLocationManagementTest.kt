package com.promenar.infinity.ui.library

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.files.DefaultPinnedFolderStore
import com.promenar.infinity.data.files.PinPersistence
import com.promenar.infinity.data.files.compactSharedDisplayPath
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationCapabilities
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.domain.files.FileLocationStorageKind
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.files.fullDisplayPath
import com.promenar.infinity.ui.files.shouldShowPinAction
import com.promenar.infinity.ui.files.state.FileManagerUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class LibraryLocationManagementTest {
    private val writable = FileLocationCapabilities(writable = true, deletable = true, archive = true)

    @Test
    fun `资料页位置严格划分为内置位置应用内工作区外部路径三组且Linux按安装状态显隐`() {
        val kb = FileLocation("kb", FileLocationKind.KNOWLEDGE_BASE, "存储空间", writable, storageKind = FileLocationStorageKind.BUILTIN)
        val sessions = FileLocation("sessions", FileLocationKind.SESSION_WORKSPACE, "会话工作区", writable, storageKind = FileLocationStorageKind.BUILTIN)
        val linux = FileLocation("linux:home", FileLocationKind.LINUX_HOME, "Linux 主目录", writable, storageKind = FileLocationStorageKind.LINUX_HOME, displayPath = "/root")
        val retained = FileLocation("workspace:w1", FileLocationKind.PROJECT, "执行测试文件操作", writable, storageKind = FileLocationStorageKind.RETAINED_SESSION, workspaceId = "w1")
        val branch = FileLocation("workspace:branch:s1", FileLocationKind.BRANCH_WORKTREE, "分支", writable, storageKind = FileLocationStorageKind.BRANCH_WORKTREE)
        val shared = FileLocation("workspace:w2", FileLocationKind.PROJECT, "Personal-Archive", writable, storageKind = FileLocationStorageKind.SHARED_STORAGE, workspaceId = "w2")
        val ext = FileLocation("external:m1", FileLocationKind.EXTERNAL_FOLDER, "文档", writable, storageKind = FileLocationStorageKind.EXTERNAL_MOUNT, mountId = "m1")

        val withLinux = categorizeLibraryLocations(listOf(kb, sessions, retained, branch, shared, linux, ext))
        assertThat(withLinux.builtin.map { it.id }).containsExactly("kb", "sessions", "linux:home").inOrder()
        assertThat(withLinux.appWorkspaces.map { it.id }).containsExactly("workspace:w1", "workspace:branch:s1").inOrder()
        assertThat(withLinux.externalPaths.map { it.id }).containsExactly("workspace:w2", "external:m1").inOrder()

        val withoutLinux = categorizeLibraryLocations(listOf(kb, sessions, shared))
        assertThat(withoutLinux.builtin.map { it.id }).containsExactly("kb", "sessions").inOrder()
        assertThat(withoutLinux.appWorkspaces).isEmpty()
        assertThat(withoutLinux.externalPaths.map { it.id }).containsExactly("workspace:w2")
    }

    @Test
    fun `一级位置根目录不重复显示固定按钮，仅子目录具体会话目录与旧固定项显示`() {
        val wsRoot = FileLocation("workspace:w1", FileLocationKind.PROJECT, "执行测试文件操作", writable, storageKind = FileLocationStorageKind.RETAINED_SESSION, workspaceId = "w1")
        val sessionRoot = FileLocation("session:s1", FileLocationKind.SESSION_WORKSPACE, "对话1", writable, sessionId = "s1")

        // 工作区根目录且未固定：隐藏固定按钮
        assertThat(shouldShowPinAction(true, FileManagerUiState(location = wsRoot, path = "/", pinned = false))).isFalse()
        // 历史遗留已固定根目录：允许取消固定
        assertThat(shouldShowPinAction(true, FileManagerUiState(location = wsRoot, path = "/", pinned = true))).isTrue()
        // 工作区子目录：允许固定为快捷入口
        assertThat(shouldShowPinAction(true, FileManagerUiState(location = wsRoot, path = "/docs", pinned = false))).isTrue()
        // 会话工作区下的具体会话根目录：允许固定
        assertThat(shouldShowPinAction(true, FileManagerUiState(location = sessionRoot, path = "/", pinned = false))).isTrue()
        // 非资料页宿主（showPin=false）：永不显示
        assertThat(shouldShowPinAction(false, FileManagerUiState(location = wsRoot, path = "/docs", pinned = false))).isFalse()
    }

    @Test
    fun `完整路径拼接与紧凑共享存储显示路径正确`() {
        val loc = FileLocation(
            id = "workspace:w1",
            kind = FileLocationKind.PROJECT,
            title = "执行测试文件操作",
            capabilities = writable,
            displayPath = "/data/data/com.promenar.infinity/files/projects/session-20261115-0002",
        )
        assertThat(loc.fullDisplayPath("/")).isEqualTo("/data/data/com.promenar.infinity/files/projects/session-20261115-0002")
        assertThat(loc.fullDisplayPath("/sub/a.txt")).isEqualTo("/data/data/com.promenar.infinity/files/projects/session-20261115-0002/sub/a.txt")

        val saf = loc.copy(displayPath = "content://com.android.externalstorage.documents/tree/primary%3ADocs")
        assertThat(saf.fullDisplayPath("/")).isEqualTo("content://com.android.externalstorage.documents/tree/primary%3ADocs")
        assertThat(saf.fullDisplayPath("/notes/a.md")).isEqualTo("content://com.android.externalstorage.documents/tree/primary%3ADocs (notes/a.md)")

        assertThat(compactSharedDisplayPath("/storage/emulated/0/Personal-Archive")).isEqualTo("/Personal-Archive")
        assertThat(compactSharedDisplayPath("/sdcard/Download/Sub/Deep")).isEqualTo("…/Sub/Deep")
    }

    @Test
    fun `移除工作区时同步清空该位置下的快捷固定并保留其它固定`() = runBlocking {
        val backing = MutableStateFlow(
            listOf(
                FileRef("workspace:w1", "/docs"),
                FileRef("workspace:w1", "/"),
                FileRef("kb", "/帝国暗面"),
            ),
        )
        val persistence = object : PinPersistence {
            override fun observe(): Flow<List<FileRef>> = backing
            override fun read(): List<FileRef> = backing.value
            override fun write(pins: List<FileRef>) { backing.value = pins }
        }
        val store = DefaultPinnedFolderStore(persistence, flowOf(setOf("workspace:w1", "kb")))
        store.unpinLocation("workspace:w1")
        assertThat(store.observePins().first()).containsExactly(FileRef("kb", "/帝国暗面"))
    }

    @Test
    fun `资料页提示在独立协程作用域展示，避免 consumeNotice 触发首帧取消`() {
        val rel = "main/java/com/promenar/infinity/ui/library/LibraryScreen.kt"
        val file = listOf("src/$rel", "app/src/$rel", "native-ui/app/src/$rel")
            .map { java.io.File(it) }
            .first { it.exists() }
        val source = file.readText()
        val body = source.substringAfter("private fun LibraryNoticeEffect(")
        assertThat(body).contains("rememberCoroutineScope()")
        assertThat(body).contains("noticeScope.launch")
        assertThat(body).contains("snackbar.showSnackbar(")
    }
}
