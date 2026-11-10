package com.promenar.infinity.data.repository

import java.io.OutputStream
import java.nio.file.Path

/** Room 与 journal 继续使用原有逻辑路径，物理元数据由此端口统一分流。 */
class PrivateWorkspaceFileOps(
    private val storage: WorkspaceInternalStorage,
    private val delegate: WorkspaceFileOps = SecureWorkspaceFileOps(identityStorage = storage),
) : WorkspaceFileOps {
    private data class Location(val root: Path, val path: List<String>)

    override fun ensureRoot(root: Path, initializeIdentity: Boolean, expectedIdentity: String?, allowUnboundParent: Boolean): String =
        delegate.ensureRoot(root, initializeIdentity, expectedIdentity, allowUnboundParent)

    override fun captureRootIdentityRestorer(root: Path): () -> Boolean = delegate.captureRootIdentityRestorer(root)
    override fun hasExternalRootIdentity(root: Path): Boolean = delegate.hasExternalRootIdentity(root)
    override fun adoptLegacyRootIdentity(root: Path, legacyIdentity: String): String? =
        delegate.adoptLegacyRootIdentity(root, legacyIdentity)
    override fun adoptLegacyRootPrivateData(legacyIdentity: String, identity: String) = storage.renameArea(legacyIdentity, identity)

    override fun migrateInternalStorage(root: Path) {
        val identity = identity(root)
        val names = try { delegate.listChildren(root, emptyList()) } catch (failure: Exception) {
            storage.recordFailure(identity, "legacy-discovery", failure)
            return
        }
        names.filter { com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.currentName(it) in setOf(".recycle_bin", ".infinity_versions", CREATE_OWNERSHIP_DIRECTORY, ".infinity_tombstones", COMMAND_PENDING_MARKER) ||
            com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.currentName(it).startsWith("$COMMAND_PENDING_MARKER-") }.forEach { name ->
            try { mapped(root, listOf(name)) } catch (failure: Exception) { storage.recordFailure(identity, name, failure) }
        }
    }

    /** 仅在会话目录删除成功且业务数据库已提交后回收该根的私有内容。失败保留诊断供后续维护。 */
    fun removePrivateData(identity: String) {
        try {
            val area = storage.existingArea(identity)
            if (area != null) {
                val areaIdentity = delegate.ensureRoot(area, initializeIdentity = false, expectedIdentity = storage.privateIdentity(area))
                storage.bindPrivateArea(area, areaIdentity)
                delegate.listChildren(area, emptyList()).forEach { delegate.delete(area, listOf(it)) }
                storage.removeEmptyArea(identity)
                storage.removeIdentity(areaIdentity)
            }
            storage.removeIdentity(identity)
        } catch (failure: Exception) { storage.recordFailure(identity, "session-metadata-cleanup", failure) }
    }

    private fun identity(root: Path): String {
        val expected = WorkspaceMutationCoordinator.expectedIdentity(root)
            ?: throw SecurityException("工作区根缺少数据库身份绑定")
        return delegate.ensureRoot(root, initializeIdentity = false, expectedIdentity = expected)
    }

    private fun area(root: Path): Path {
        val ownerIdentity = identity(root)
        val area = storage.area(ownerIdentity)
        val expected = storage.privateIdentity(area)
        val actual = delegate.ensureRoot(area, initializeIdentity = expected == null, expectedIdentity = expected, allowUnboundParent = true)
        storage.bindPrivateArea(area, actual)
        return area
    }

    private fun mapped(root: Path, path: List<String>, migrate: Boolean = true, allowFallback: Boolean = true): Location {
        require(path.isNotEmpty() && path.none { it.isBlank() || it == "." || it == ".." || '/' in it || '\\' in it || '\u0000' in it })
        identity(root)
        val originalFirst = path.first()
        val first = com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.currentName(originalFirst)
        val privateName = when {
            first == ".recycle_bin" -> "recycle"
            first == ".infinity_versions" -> "versions"
            first == COMMAND_PENDING_MARKER -> "command-pending"
            first.startsWith("$COMMAND_PENDING_MARKER-") -> "command-pending-" + first.removePrefix("$COMMAND_PENDING_MARKER-")
            else -> null
        }
        val result = when {
            privateName != null -> Location(area(root), listOf(privateName) + path.drop(1))
            first == CREATE_OWNERSHIP_DIRECTORY -> Location(root, listOf(".infinity", "create-operations") + path.drop(1))
            first == ".infinity_tombstones" -> Location(root, listOf(".infinity", "tombstones") + path.drop(1))
            else -> return Location(root, path)
        }
        val targetBase = if (result.root == root) result.path.take(2) else result.path.take(1)
        val candidates = buildList {
            add(listOf(first))
            add(listOf(com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.legacyName(first)))
            if (first == CREATE_OWNERSHIP_DIRECTORY) add(listOf(".nexara", "create-operations"))
        }.distinct().filter { delegate.exists(root, it) }
        if (candidates.size > 1 || candidates.isNotEmpty() && delegate.exists(result.root, targetBase)) {
            throw SecurityException("工作区新旧元数据源同时存在，保留所有节点")
        }
        val sourcePath = candidates.singleOrNull()
        if (migrate && sourcePath != null) {
            try {
                val base = result.path.take(1).let {
                    if (result.root == root) result.path.take(2) else it
                }
                ensureParents(Location(result.root, base))
                migrateNode(root, sourcePath, result.root, base)
            } catch (failure: Exception) {
                storage.recordFailure(identity(root), first, failure)
                if (first == CREATE_OWNERSHIP_DIRECTORY || !allowFallback) {
                    throw SecurityException("工作区元数据迁移未完成，保留原始节点与 journal", failure)
                }
                val originalPath = sourcePath + path.drop(1)
                if (delegate.exists(root, originalPath)) return Location(root, originalPath)
                throw SecurityException("工作区元数据迁移未完成，原路径不可读", failure)
            }
        }
        return result
    }

    private fun ensureParents(location: Location) {
        if (storage.privateIdentity(location.root) == null && location.path.firstOrNull() != ".infinity") return
        if (location.path.firstOrNull() == ".infinity" && location.path.size > 1) ensureWorkspaceInternalDirectory(delegate, location.root)
        location.path.dropLast(1).indices.forEach { index -> delegate.ensureDirectory(location.root, location.path.take(index + 1)) }
    }

    private fun migrateNode(sourceRoot: Path, source: List<String>, targetRoot: Path, target: List<String>) {
        if (!delegate.exists(sourceRoot, source)) return
        delegate.inspect(sourceRoot, source)
        if (!delegate.exists(targetRoot, target)) {
            delegate.moveAcrossRoots(sourceRoot, source, targetRoot, target).commit()
            return
        }
        throw SecurityException("元数据迁移目标已存在，保留两个节点")
    }

    private inline fun <T> at(root: Path, path: List<String>, allowFallback: Boolean = true, action: (Location) -> T): T {
        val location = mapped(root, path, allowFallback = allowFallback)
        return action(location).also { identity(root) }
    }

    override fun read(root: Path, relative: List<String>): ByteArray = at(root, relative) { delegate.read(it.root, it.path) }
    override fun readLimited(root: Path, relative: List<String>, maxBytes: Long): ByteArray =
        at(root, relative) { delegate.readLimited(it.root, it.path, maxBytes) }
    override fun <T> readStreamLimited(root: Path, relative: List<String>, maxBytes: Long, consume: (java.io.InputStream) -> T): T =
        at(root, relative) { delegate.readStreamLimited(it.root, it.path, maxBytes, consume) }
    override fun exists(root: Path, relative: List<String>): Boolean = at(root, relative) { delegate.exists(it.root, it.path) }
    override fun inspect(root: Path, relative: List<String>): WorkspaceNodeIdentity = at(root, relative) { delegate.inspect(it.root, it.path) }
    override fun listChildren(root: Path, relative: List<String>): List<String> = at(root, relative) { delegate.listChildren(it.root, it.path) }
    override fun deleteNonRecursive(root: Path, relative: List<String>) = at(root, relative) { delegate.deleteNonRecursive(it.root, it.path) }
    override fun createFile(root: Path, relative: List<String>, bytes: ByteArray) = at(root, relative, allowFallback = false) {
        ensureParents(it)
        delegate.createFile(it.root, it.path, bytes)
    }
    override fun createFileStreaming(root: Path, relative: List<String>, maxBytes: Long, writer: (OutputStream) -> Unit): WorkspaceStreamWriteResult =
        at(root, relative, allowFallback = false) { ensureParents(it); delegate.createFileStreaming(it.root, it.path, maxBytes, writer) }
    override fun createDirectory(root: Path, relative: List<String>) = at(root, relative, allowFallback = false) { ensureParents(it); delegate.createDirectory(it.root, it.path) }
    override fun ensureDirectory(root: Path, relative: List<String>) = at(root, relative, allowFallback = false) { ensureParents(it); delegate.ensureDirectory(it.root, it.path) }
    override fun replaceFile(root: Path, relative: List<String>, bytes: ByteArray): WorkspaceFileRollback =
        at(root, relative, allowFallback = false) { delegate.replaceFile(it.root, it.path, bytes) }
    override fun reconcileFile(root: Path, relative: List<String>, expectedHash: String) =
        at(root, relative, allowFallback = false) { delegate.reconcileFile(it.root, it.path, expectedHash) }

    override fun move(root: Path, source: List<String>, target: List<String>): WorkspaceFileRollback {
        val from = mapped(root, source)
        val to = mapped(root, target, allowFallback = false)
        ensureParents(to)
        return delegate.moveAcrossRoots(from.root, from.path, to.root, to.path).also { identity(root) }
    }

    override fun moveAcrossRoots(sourceRoot: Path, source: List<String>, targetRoot: Path, target: List<String>): WorkspaceFileRollback {
        val from = mapped(sourceRoot, source)
        val to = mapped(targetRoot, target, allowFallback = false)
        ensureParents(to)
        return delegate.moveAcrossRoots(from.root, from.path, to.root, to.path)
    }

    override fun retainCreationProof(root: Path, node: List<String>, owner: List<String>, token: String) = at(root, node, allowFallback = false) {
        delegate.retainCreationProof(it.root, it.path, mapped(root, owner).path, token)
    }
    override fun verifyCreationProof(root: Path, node: List<String>, owner: List<String>, token: String, expected: WorkspaceNodeIdentity) = at(root, node) {
        delegate.verifyCreationProof(it.root, it.path, mapped(root, owner).path, token, expected)
    }
    override fun deleteCreatedNode(root: Path, node: List<String>, owner: List<String>, token: String, expected: WorkspaceNodeIdentity) {
        val from = mapped(root, node)
        val proofOwner = mapped(root, owner)
        if (from.root == proofOwner.root) {
            delegate.deleteCreatedNode(from.root, from.path, proofOwner.path, token, expected)
        } else {
            delegate.verifyCreationProof(from.root, from.path, proofOwner.path, token, expected)
            val quarantine = proofOwner.path + CREATE_ROLLBACK_NODE
            delegate.moveAcrossRoots(from.root, from.path, proofOwner.root, quarantine).commit()
            delegate.deleteCreatedNode(proofOwner.root, quarantine, proofOwner.path, token, expected)
        }
    }
    override fun releaseCreationProof(root: Path, node: List<String>, token: String) =
        at(root, node) { delegate.releaseCreationProof(it.root, it.path, token) }

    override fun stageDelete(root: Path, source: List<String>, deletionToken: String): WorkspaceFileRollback =
        at(root, source) { delegate.stageDelete(it.root, it.path, deletionToken) }

    override fun recoverTombstones(root: Path, restorePath: (deletionToken: String) -> List<String>?): TombstoneRecoveryReport {
        val attention = mutableListOf<String>()
        for (physicalRoot in listOf(root, area(root))) {
            for (directory in com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.tombstoneDirectories) {
                if (!delegate.exists(physicalRoot, directory)) continue
                delegate.listChildren(physicalRoot, directory).forEach { token ->
                    try {
                        require(token.matches(Regex("[0-9a-f]{24}")))
                        val source = directory + token
                        val original = restorePath(token)
                        if (original == null) delegate.delete(physicalRoot, source)
                        else {
                            val target = mapped(root, original, allowFallback = false)
                            ensureParents(target)
                            delegate.moveAcrossRoots(physicalRoot, source, target.root, target.path).commit()
                        }
                    } catch (_: Exception) { attention += token }
                }
                if (directory in listOf(listOf(".infinity_tombstones"), listOf(".nexara_tombstones")) && delegate.listChildren(physicalRoot, directory).isEmpty()) {
                    delegate.deleteNonRecursive(physicalRoot, directory)
                }
            }
        }
        return TombstoneRecoveryReport(attention.distinct())
    }
    override fun delete(root: Path, source: List<String>) = at(root, source) { delegate.delete(it.root, it.path) }
    override fun cleanupTombstones(root: Path) {
        delegate.cleanupTombstones(root)
        delegate.cleanupTombstones(area(root))
    }
}
