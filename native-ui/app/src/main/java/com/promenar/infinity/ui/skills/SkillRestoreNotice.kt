package com.promenar.infinity.ui.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.data.backup.SkillBackupSummary
import com.promenar.infinity.data.backup.SkippedSkillGitRepository
import com.promenar.infinity.ui.common.InfinityMetaLine

/** 仓库在文件工具中的虚拟路径；技能根本身记为 `.`。 */
internal fun SkippedSkillGitRepository.virtualPath(): String =
    if (relativePath == "." || relativePath.isEmpty()) "/skills" else "/skills/$relativePath"

/** 提交号只显示前 12 位，足以在远端定位。 */
internal fun SkippedSkillGitRepository.shortCommit(): String? = commit?.take(12)

@Composable
fun SkillRestoreNoticeDialog(summary: SkillBackupSummary, onDismiss: () -> Unit) {
    InfinityConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.skills_restore_notice_title),
        message = "",
        confirmText = stringResource(R.string.skills_restore_notice_dismiss),
        cancelText = null,
        onConfirm = onDismiss,
        onCancel = onDismiss,
        content = {
            SelectionContainer {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("skill_restore_notice"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SkillRestoreNoticeContent(summary)
                }
            }
        },
    )
}

/** 技能恢复中需要用户处理的事项：未带 Git 元数据的仓库与跳过的符号链接；备份恢复结果对话框复用。 */
@Composable
fun SkillRestoreNoticeContent(summary: SkillBackupSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (summary.skippedGitRepositories.isNotEmpty()) {
            Text(stringResource(R.string.skills_restore_notice_git))
            summary.skippedGitRepositories.forEach { repository -> SkippedRepository(repository) }
        }
        if (summary.skippedSymbolicLinks > 0) {
            Text(pluralStringResource(R.plurals.skills_restore_notice_links, summary.skippedSymbolicLinks, summary.skippedSymbolicLinks))
        }
    }
}

@Composable
private fun SkippedRepository(repository: SkippedSkillGitRepository) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(repository.virtualPath(), style = MaterialTheme.typography.titleSmall)
        val secondary = MaterialTheme.colorScheme.onSurfaceVariant
        val body = MaterialTheme.typography.bodySmall
        if (repository.remotes.isEmpty()) {
            Text(stringResource(R.string.skills_restore_notice_no_remote), style = body, color = secondary)
        } else {
            repository.remotes.map { it.url }.distinct().forEach { url ->
                Text(stringResource(R.string.skills_restore_notice_remote, url), style = body, color = secondary)
            }
        }
        val revision = listOfNotNull(
            repository.branch?.let { stringResource(R.string.skills_restore_notice_branch, it) },
            repository.shortCommit()?.let { stringResource(R.string.skills_restore_notice_commit, it) },
        )
        InfinityMetaLine(revision, style = body, color = secondary)
    }
}
