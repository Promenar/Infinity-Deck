package com.promenar.infinity.ui.files

import androidx.compose.runtime.Composable
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.ui.git.branchLabel

/** 位置的 Git 显示名：分支名，游离 HEAD 时为“分离 HEAD · 短提交号”；没有仓库时为 null。 */
@Composable
internal fun FileLocation.gitLabel(): String? = when {
    gitDetached -> branchLabel(branch = null, detached = true, headShortSha = gitHeadShortSha, unborn = false)
    else -> gitBranch
}
