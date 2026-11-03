package com.promenar.infinity.data.backup

internal object SkillBackupPaths {
    const val PREFIX = "skills/"
    const val MAX_BYTES = BackupPackageLimits.MAX_TOTAL_BYTES
    const val MAX_GIT_ENTRIES = BackupPackageLimits.MAX_ENTRIES

    fun parts(path: String): List<String> {
        val parts = path.split('/')
        if (path.isBlank() || path.startsWith('/') || '\\' in path || ':' in path || '\u0000' in path ||
            parts.any { it.isEmpty() || it == "." || it == ".." || com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.hasInternalPrefix(it) }
        ) throw BackupValidationException("技能分区包含不安全路径", reason = BackupFailureReason.SKILL_CONTENT_UNSUPPORTED)
        return parts
    }

    fun isCredential(name: String): Boolean = name == "credentials" || name == ".git-credentials"

    fun isTemporaryDirectory(name: String): Boolean = name.startsWith(".staging-") || name.startsWith(".trash-")

    fun validate(metadata: SkillBackupMetadata, filePaths: Collection<String>, sizeBytes: Long) {
        val directories = metadata.directories.toSet()
        if (directories.size != metadata.directories.size ||
            directories.size + filePaths.size > BackupPackageLimits.MAX_ENTRIES || sizeBytes > MAX_BYTES ||
            metadata.summary.skillCount < 0 || metadata.summary.skippedSymbolicLinks < 0
        ) throw BackupValidationException("技能分区元数据或大小无效")
        val files = filePaths.toSet()
        if (files.size != filePaths.size || files.any { it in directories }) {
            throw BackupValidationException("技能分区包含重复路径")
        }
        (directories + files).forEach { path ->
            val segments = parts(path)
            if (isTemporaryDirectory(segments.first()) && segments.first() in directories ||
                path in files && isCredential(segments.last())
            ) throw BackupValidationException("技能分区包含凭据或临时目录")
            for (depth in 1 until segments.size) {
                if (segments.take(depth).joinToString("/") !in directories) {
                    throw BackupValidationException("技能分区父目录缺失或与文件冲突")
                }
            }
        }
        val skillCount = files.count { path ->
            val segments = path.split('/')
            segments.size == 2 && segments.last() == "SKILL.md" && segments.first() != ".git"
        }
        if (skillCount != metadata.summary.skillCount) throw BackupValidationException("技能数量与分区不一致")
        val repositories = metadata.summary.skippedGitRepositories
        if (repositories.map { it.relativePath }.toSet().size != repositories.size) {
            throw BackupValidationException("技能分区仓库信息重复")
        }
        repositories.forEach { repository ->
            if (repository.relativePath != ".") parts(repository.relativePath)
            repository.remotes.forEach { remote ->
                if (remote.name.isBlank() || remote.url != SkillGitMetadata.sanitizeUrls(remote.url)) {
                    throw BackupValidationException("技能分区远端地址未经脱敏")
                }
            }
            if (repository.commit != null && !Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}").matches(repository.commit)) {
                throw BackupValidationException("技能分区提交号无效")
            }
            val gitPath = if (repository.relativePath == ".") ".git" else "${repository.relativePath}/.git"
            if ((directories + files).any { it == gitPath || it.startsWith("$gitPath/") }) {
                throw BackupValidationException("已跳过的 Git 仓库仍包含元数据")
            }
        }
    }
}
