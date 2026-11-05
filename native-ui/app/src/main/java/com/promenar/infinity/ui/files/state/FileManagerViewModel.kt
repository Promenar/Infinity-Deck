package com.promenar.infinity.ui.files.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileManagerServices
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.files.FileSearchHit
import com.promenar.infinity.domain.files.FileSortKey
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

sealed interface FileManagerScope {
    data object Library : FileManagerScope
    data class Session(val sessionId: String) : FileManagerScope
    data object LinuxHome : FileManagerScope
}

class FileManagerViewModel(
    services: FileManagerServices,
    scope: FileManagerScope,
    initial: FileRef? = null,
) : ViewModel(), FileManagerController {
    private val controller = DefaultFileManagerController(
        viewModelScope,
        services,
        when (scope) {
            FileManagerScope.Library -> services.locations.observeLibraryLocations()
            is FileManagerScope.Session -> services.locations.observeSessionLocations(scope.sessionId)
            FileManagerScope.LinuxHome -> services.locations.observeLinuxHome().map { listOfNotNull(it) }
        },
        initial,
    )
    override val state: StateFlow<FileManagerUiState> get() = controller.state
    override fun openLocation(locationId: String, path: String) = controller.openLocation(locationId, path)
    override fun open(item: FileItem) = controller.open(item)
    override fun goUp() = controller.goUp()
    override fun goTo(path: String) = controller.goTo(path)
    override fun refresh() = controller.refresh()
    override fun setSortKey(key: FileSortKey) = controller.setSortKey(key)
    override fun toggleSortDirection() = controller.toggleSortDirection()
    override fun toggleFoldersFirst() = controller.toggleFoldersFirst()
    override fun setViewMode(mode: FileViewMode) = controller.setViewMode(mode)
    override fun toggleShowHidden() = controller.toggleShowHidden()
    override fun toggleSelection(item: FileItem) = controller.toggleSelection(item)
    override fun selectAll() = controller.selectAll()
    override fun clearSelection() = controller.clearSelection()
    override fun startSearch() = controller.startSearch()
    override fun updateSearch(transform: (FileSearchState) -> FileSearchState) = controller.updateSearch(transform)
    override fun closeSearch() = controller.closeSearch()
    override fun reveal(hit: FileSearchHit) = controller.reveal(hit)
    override fun showPreview(item: FileItem) = controller.showPreview(item)
    override fun closePreview() = controller.closePreview()
    override fun createFolder(name: String) = controller.createFolder(name)
    override fun createTextFile(name: String) = controller.createTextFile(name)
    override fun rename(item: FileItem, newName: String) = controller.rename(item, newName)
    override fun delete(items: List<FileItem>) = controller.delete(items)
    override fun compress(items: List<FileItem>, archiveName: String) = controller.compress(items, archiveName)
    override fun extract(item: FileItem) = controller.extract(item)
    override fun importUris(uris: List<String>) = controller.importUris(uris)
    override fun importText(text: String, name: String) = controller.importText(text, name)
    override fun openTargetPicker(mode: FileTransferMode, items: List<FileItem>) = controller.openTargetPicker(mode, items)
    override fun pickerOpenLocation(locationId: String) = controller.pickerOpenLocation(locationId)
    override fun pickerGoTo(path: String) = controller.pickerGoTo(path)
    override fun pickerOpenFolder(folder: FileItem) = controller.pickerOpenFolder(folder)
    override fun pickerCreateFolder(name: String) = controller.pickerCreateFolder(name)
    override fun confirmTargetPicker() = controller.confirmTargetPicker()
    override fun dismissTargetPicker() = controller.dismissTargetPicker()
    override fun togglePinCurrentFolder() = controller.togglePinCurrentFolder()
    override suspend fun shareUri(item: FileItem): String = controller.shareUri(item)
    override fun consumeNotice() = controller.consumeNotice()

    companion object {
        fun factory(services: FileManagerServices, scope: FileManagerScope, initial: FileRef? = null): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(FileManagerViewModel::class.java))
                    return FileManagerViewModel(services, scope, initial) as T
                }
            }
    }
}
