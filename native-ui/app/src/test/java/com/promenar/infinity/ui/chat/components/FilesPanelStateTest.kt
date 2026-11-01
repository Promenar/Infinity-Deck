package com.promenar.infinity.ui.chat.components

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FilesPanelStateTest {

    @Test
    fun `首次加载时不显示空库`() {
        val state = resolveFilesPanelUiState(
            workspaceReady = false,
            isLoading = true,
            hasLoadError = false,
            itemCount = 0,
            filteredItemCount = 0,
            searchQuery = "",
        )

        assertThat(state).isEqualTo(FilesPanelUiState.Loading)
    }

    @Test
    fun `加载完成且没有文件时显示真实空库`() {
        val state = resolveFilesPanelUiState(
            workspaceReady = true,
            isLoading = false,
            hasLoadError = false,
            itemCount = 0,
            filteredItemCount = 0,
            searchQuery = "",
        )

        assertThat(state).isEqualTo(FilesPanelUiState.Empty)
    }

    @Test
    fun `有文件但搜索不匹配时显示搜索空态`() {
        val state = resolveFilesPanelUiState(
            workspaceReady = true,
            isLoading = false,
            hasLoadError = false,
            itemCount = 2,
            filteredItemCount = 0,
            searchQuery = "missing",
        )

        assertThat(state).isEqualTo(FilesPanelUiState.SearchEmpty)
    }

    @Test
    fun `加载失败优先显示错误而非空库`() {
        val state = resolveFilesPanelUiState(
            workspaceReady = false,
            isLoading = false,
            hasLoadError = true,
            itemCount = 0,
            filteredItemCount = 0,
            searchQuery = "",
        )

        assertThat(state).isEqualTo(FilesPanelUiState.Error)
    }

    @Test
    fun `过滤后仍有文件时显示内容`() {
        val state = resolveFilesPanelUiState(
            workspaceReady = true,
            isLoading = false,
            hasLoadError = false,
            itemCount = 2,
            filteredItemCount = 1,
            searchQuery = "doc",
        )

        assertThat(state).isEqualTo(FilesPanelUiState.Content)
    }

    @Test
    fun `部分删除后只保留失败项的选择`() {
        val selected = mutableListOf("doc-1", "doc-2", "doc-3")

        reduceSelectionAfterFileOperation(
            selectedIds = selected,
            result = FileBatchOperationResult(
                attemptedIds = listOf("doc-1", "doc-2"),
                failedIds = listOf("doc-2"),
            ),
        )

        assertThat(selected).containsExactly("doc-2", "doc-3").inOrder()
    }

    @Test
    fun `嵌套搜索结果包含所有祖先但不包含无关分支`() = runTest {
        val rootFolder = entry("folder", "root", "/folder", isDirectory = true)
        val nestedFolder = entry("nested", "folder", "/folder/nested", isDirectory = true)
        val match = entry("match", "nested", "/folder/nested/needle.txt", isDirectory = false)
        val unrelated = entry("other", "root", "/other", isDirectory = true)
        val entries = listOf(rootFolder, nestedFolder, match, unrelated).associateBy { it.uuid }

        val visibleIds = resolveSearchVisibleIds(
            matches = listOf(match),
            workspaceRootUuid = "root",
            lookup = { entries[it] },
        )

        assertThat(visibleIds).containsExactly("match", "nested", "folder")
        assertThat(visibleIds).doesNotContain("other")
    }

    @Test
    fun `可见节点投影按UUID保留同名节点并稳定展开深层目录`() {
        val alpha = entry("alpha", "root", "/alpha", isDirectory = true)
        val beta = entry("beta", "root", "/beta", isDirectory = true)
        val alphaNested = entry("alpha-nested", "alpha", "/alpha/same", isDirectory = true)
        val betaNested = entry("beta-nested", "beta", "/beta/same", isDirectory = true)
        val deepFolder = entry("deep-folder", "alpha-nested", "/alpha/same/deep", isDirectory = true)
        val deepFile = entry("deep-file", "deep-folder", "/alpha/same/deep/target.md", isDirectory = false)
        val children = mapOf(
            "alpha" to listOf(alphaNested),
            "beta" to listOf(betaNested),
            "alpha-nested" to listOf(deepFolder),
            "deep-folder" to listOf(deepFile),
        )

        val original = projectVisibleFileNodes(
            roots = listOf(alpha, beta),
            childrenByParent = children,
            expansionOverrides = mapOf("deep-folder" to true),
        )
        val reordered = projectVisibleFileNodes(
            roots = listOf(beta, alpha),
            childrenByParent = children,
            expansionOverrides = mapOf("deep-folder" to true),
        )

        assertThat(original.map { it.file.uuid }).containsExactly(
            "alpha",
            "alpha-nested",
            "deep-folder",
            "deep-file",
            "beta",
            "beta-nested",
        ).inOrder()
        assertThat(reordered.map { it.file.uuid }).containsExactly(
            "beta",
            "beta-nested",
            "alpha",
            "alpha-nested",
            "deep-folder",
            "deep-file",
        ).inOrder()
        assertThat(original.filter { it.file.name == "same" }.map { it.file.uuid })
            .containsExactly("alpha-nested", "beta-nested")
    }

    @Test
    fun `折叠覆盖和搜索强制展开都以UUID而非位置为准`() {
        val folder = entry("folder", "root", "/folder", isDirectory = true)
        val child = entry("child", "folder", "/folder/child.md", isDirectory = false)

        val collapsed = projectVisibleFileNodes(
            roots = listOf(folder),
            childrenByParent = mapOf("folder" to listOf(child)),
            expansionOverrides = mapOf("folder" to false),
        )
        val searchExpanded = projectVisibleFileNodes(
            roots = listOf(folder),
            childrenByParent = mapOf("folder" to listOf(child)),
            expansionOverrides = mapOf("folder" to false),
            forceExpandedIds = setOf("folder"),
        )

        assertThat(collapsed.map { it.file.uuid }).containsExactly("folder")
        assertThat(searchExpanded.map { it.file.uuid }).containsExactly("folder", "child").inOrder()
    }

    @Test
    fun `禁止树展开时仅投影根列表且深度全为0`() {
        val folder = entry("folder", "root", "/folder", isDirectory = true)
        val child = entry("child", "folder", "/folder/child.md", isDirectory = false)

        val flatNodes = projectVisibleFileNodes(
            roots = listOf(folder),
            childrenByParent = mapOf("folder" to listOf(child)),
            allowTreeExpansion = false,
        )

        assertThat(flatNodes).containsExactly(
            VisibleFileNode(file = folder, depth = 0),
        )
    }

    @Test
    fun `文件树缩进层级在深层目录封顶`() {
        assertThat(boundedFileTreeIndentLevel(-1)).isEqualTo(0)
        assertThat(boundedFileTreeIndentLevel(2)).isEqualTo(2)
        assertThat(boundedFileTreeIndentLevel(12)).isEqualTo(4)
    }

    @Test
    fun `可见后代要求所有展开祖先目录持续订阅`() {
        val outer = entry("outer", "root", "/outer", isDirectory = true)
        val branch = entry("branch", "outer", "/outer/branch", isDirectory = true)
        val visibleLeaf = entry(
            "visible-leaf",
            "branch",
            "/outer/branch/visible.md",
            isDirectory = false,
        )
        val unrelated = entry("unrelated", "root", "/unrelated", isDirectory = true)
        val visibleNodes = listOf(
            VisibleFileNode(outer, depth = 0),
            VisibleFileNode(branch, depth = 1),
            VisibleFileNode(visibleLeaf, depth = 2),
            VisibleFileNode(unrelated, depth = 0),
        )
        val requiredIds = resolveRequiredDirectorySubscriptionIds(
            visibleNodes = visibleNodes,
            visibleNodeIds = setOf(visibleLeaf.uuid),
            expandedDirectoryIds = setOf(outer.uuid, branch.uuid, unrelated.uuid),
        )

        assertThat(requiredIds).containsExactly(outer.uuid, branch.uuid)
    }

    @Test
    fun `最后一个可见后代离开后不再要求祖先目录订阅`() {
        val outer = entry("outer", "root", "/outer", isDirectory = true)
        val branch = entry("branch", "outer", "/outer/branch", isDirectory = true)
        val leaf = entry("leaf", "branch", "/outer/branch/leaf.md", isDirectory = false)

        val requiredIds = resolveRequiredDirectorySubscriptionIds(
            visibleNodes = listOf(
                VisibleFileNode(outer, depth = 0),
                VisibleFileNode(branch, depth = 1),
                VisibleFileNode(leaf, depth = 2),
            ),
            visibleNodeIds = emptySet(),
            expandedDirectoryIds = setOf(outer.uuid, branch.uuid),
        )

        assertThat(requiredIds).isEmpty()
    }

    @Test
    fun `生产文件树使用UUID键单层Material列表和有界移动目录`() {
        val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = projectRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/components/FilesPanel.kt",
        ).readText()

        assertThat(source).contains("items = visibleNodes")
        // 行间分隔线需要下标，改用 itemsIndexed，键仍是文件 UUID。
        assertThat(source).contains("key = { _, node -> node.file.uuid }")
        assertThat(source).contains(
            "val rootsFlow = remember(rootFiles, workspaceRootUuid, workspaceRepo)",
        )
        assertThat(source).contains("ListItem(")
        assertThat(source).contains(".heightIn(max = 420.dp)")
        assertThat(source).doesNotContain("InfinityGlassCard")
        assertThat(source).doesNotContain("filteredChildren.forEach")
        assertThat(source).doesNotContain("directoryList.forEach")
    }

    @Test
    fun `文件树目录订阅由投影视口持有而不是组合行持有`() {
        val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        val source = projectRoot.resolve(
            "src/main/java/com/promenar/infinity/ui/chat/components/FilesPanel.kt",
        ).readText()
        val beforeContent = source.substringBefore("val content = @Composable")
        val contentBody = source.substringAfter("val content = @Composable")
            .substringBefore("Box(modifier =")

        assertThat(source).contains("internal fun FilesPanel(")
        assertThat(source).contains("resolveRequiredDirectorySubscriptionIds(")
        assertThat(beforeContent).contains("requiredDirectorySubscriptionIds.forEach")
        assertThat(beforeContent).contains("observeChildren(rootUuid, parentUuid)")
        assertThat(contentBody).doesNotContain("observeChildren(")
    }

    private fun entry(
        uuid: String,
        parentUuid: String,
        path: String,
        isDirectory: Boolean,
    ) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = "root",
        parentUuid = parentUuid,
        name = path.substringAfterLast('/'),
        hash = "",
        isDirectory = isDirectory,
        physicalRootPath = "/tmp$path",
        materializedPath = path,
        createdAt = 1,
        updatedAt = 1,
    )
}
