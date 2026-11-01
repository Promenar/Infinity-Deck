package com.promenar.infinity.data.repository

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/** 宿主 JDK（macOS）与 Robolectric 不提供 SecureDirectoryStream；测试用按路径遍历的等价分类，生产扫描器由设备测试覆盖。 */
object PathWorkspaceTreeScanner : WorkspaceTreeScanner {
    override fun scan(root: Path): WorkspaceTreeSnapshot {
        val nodes = LinkedHashMap<String, WorkspaceScanNode>()
        val reserved = LinkedHashMap<String, WorkspaceScanNode>()
        walk(root, "", false, nodes, reserved)
        return WorkspaceTreeSnapshot(nodes, reserved)
    }

    override fun scanPaths(root: Path, paths: List<String>): Map<String, WorkspaceScanNode> = paths.associateWith { path ->
        val attributes = Files.readAttributes(root.resolve(path.trimStart('/')), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val kind = when {
            attributes.isSymbolicLink || attributes.isOther -> WorkspaceScanKind.UNSUPPORTED
            attributes.isDirectory -> WorkspaceScanKind.DIRECTORY
            else -> WorkspaceScanKind.FILE
        }
        WorkspaceScanNode(kind, if (kind == WorkspaceScanKind.FILE) attributes.size() else 0L,
            attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.fileKey()?.toString())
    }

    private fun walk(
        directory: Path,
        prefix: String,
        inReserved: Boolean,
        nodes: MutableMap<String, WorkspaceScanNode>,
        reserved: MutableMap<String, WorkspaceScanNode>,
    ) {
        val children = Files.list(directory).use { stream -> stream.toList().sortedBy { it.fileName.toString() } }
        for (child in children) {
            val name = child.fileName.toString()
            val attributes = Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val isReserved = inReserved || isReservedWorkspaceName(name)
            val kind = when {
                !isIndexableWorkspaceName(name) || attributes.isSymbolicLink || attributes.isOther -> WorkspaceScanKind.UNSUPPORTED
                attributes.isDirectory -> WorkspaceScanKind.DIRECTORY
                attributes.isRegularFile -> WorkspaceScanKind.FILE
                else -> WorkspaceScanKind.UNSUPPORTED
            }
            val path = "$prefix/$name"
            (if (isReserved) reserved else nodes)[path] = WorkspaceScanNode(
                kind,
                if (kind == WorkspaceScanKind.FILE) attributes.size() else 0L,
                attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                attributes.fileKey()?.toString(),
            )
            if (kind == WorkspaceScanKind.DIRECTORY) walk(child, path, isReserved, nodes, reserved)
        }
    }
}
