package com.promenar.infinity.ui.files.state

import com.promenar.infinity.domain.files.FileSort

data class FileManagerPreferenceValues(
    val sort: FileSort = FileSort(),
    val viewMode: FileViewMode = FileViewMode.LIST,
    val showHidden: Boolean = false,
)

/** 偏好存储由宿主注入；读写均在 IO 调度器执行。 */
interface FileManagerPreferences {
    suspend fun read(): FileManagerPreferenceValues
    suspend fun write(values: FileManagerPreferenceValues)
}
