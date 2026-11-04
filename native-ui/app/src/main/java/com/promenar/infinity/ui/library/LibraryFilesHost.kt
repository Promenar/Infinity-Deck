package com.promenar.infinity.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.ui.files.FileManagerHostSpec
import com.promenar.infinity.ui.files.FileManagerLeading
import com.promenar.infinity.ui.files.FileManagerPane
import com.promenar.infinity.ui.files.RecycleBinPage
import com.promenar.infinity.ui.files.state.FileManagerScope
import com.promenar.infinity.ui.files.state.FileManagerViewModel
import com.promenar.infinity.ui.files.state.RecycleBinViewModel

/*
 * 资料页里的资源管理器与回收站宿主：组件自带页头但不处理系统栏，资料页负责状态栏留白、最左侧按钮与能否固定到侧栏。
 */

private const val LIBRARY_FILES_VIEW_MODEL_KEY = "library-files"

internal fun LibraryLeading.toFileManagerLeading(): FileManagerLeading = when (this) {
    is LibraryLeading.Expand -> if (expanded) FileManagerLeading.Restore(onToggle) else FileManagerLeading.Expand(onToggle)
    is LibraryLeading.Back -> FileManagerLeading.Leave(onBack)
}

@Composable
internal fun LibraryBrowsePage(ref: FileRef, host: LibraryPageHost, leading: LibraryLeading) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    // 资料页只有一个资源管理器实例：左列切换位置时复用，保留排序、视图等偏好。
    val controller: FileManagerViewModel = viewModel(
        key = LIBRARY_FILES_VIEW_MODEL_KEY,
        factory = FileManagerViewModel.factory(app.fileManagerServices, FileManagerScope.Library, ref),
    )
    LaunchedEffect(ref) {
        val current = controller.state.value
        if (current.location?.id != ref.locationId || current.path != ref.path) controller.openLocation(ref.locationId, ref.path)
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().testTag(LibraryTags.page("browse"))) {
        FileManagerPane(
            controller = controller,
            host = FileManagerHostSpec(
                leading = leading.toFileManagerLeading(),
                showPin = true,
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun LibraryTrashPage(host: LibraryPageHost, leading: LibraryLeading) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    val controller: RecycleBinViewModel = viewModel(factory = RecycleBinViewModel.factory(app.fileManagerServices))
    val locations by host.viewModel.locations.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().statusBarsPadding().testTag(LibraryTags.page("trash"))) {
        RecycleBinPage(
            controller = controller,
            leading = leading.toFileManagerLeading(),
            locationTitle = { id -> locations.locations.firstOrNull { it.id == id }?.title },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
