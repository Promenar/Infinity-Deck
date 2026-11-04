package com.promenar.infinity.ui.files.state

import com.promenar.infinity.domain.files.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal fun file(
    path: String,
    locationId: String = "library",
    directory: Boolean = false,
    modified: Long = 0,
    size: Long = 0,
    mime: String? = null,
): FileItem = FileItem(FileRef(locationId, path), path.substringAfterLast('/'), directory, size, modified, mime)

internal fun location(id: String = "library"): FileLocation = FileLocation(
    id, FileLocationKind.KNOWLEDGE_BASE, id, FileLocationCapabilities(true, true, true),
)

internal class FakeFileServices : FileManagerServices {
    override val operations = FakeFileOperations()
    override val pins = FakePins()
    override val locations = FakeFileLocations()
}

internal class FakeFileLocations : FileLocationsSource {
    val library = MutableStateFlow<List<FileLocation>>(emptyList())
    val session = MutableStateFlow<List<FileLocation>>(emptyList())
    val linux = MutableStateFlow<FileLocation?>(null)
    var requestedSession: String? = null
    override fun observeLibraryLocations(): Flow<List<FileLocation>> = library
    override fun observeSessionLocations(sessionId: String): Flow<List<FileLocation>> {
        requestedSession = sessionId
        return session
    }
    override fun observeLinuxHome(): Flow<FileLocation?> = linux
}

internal class FakePins : PinnedFolderStore {
    val values = MutableStateFlow<List<FileRef>>(emptyList())
    var accept = true
    override fun observePins(): Flow<List<FileRef>> = values
    override suspend fun pin(ref: FileRef): Boolean {
        if (!accept || ref in values.value || values.value.size >= PinnedFolderStore.MAX_PINS) return false
        values.value = values.value + ref
        return true
    }
    override suspend fun unpin(ref: FileRef) { values.value = values.value - ref }
}

internal class FakeFileOperations : FileOperations {
    val listings = mutableMapOf<FileRef, List<FileItem>>()
    val listCalls = mutableListOf<FileRef>()
    val searchCalls = mutableListOf<FileSearchQuery>()
    var listHandler: suspend (FileRef) -> List<FileItem> = { listings[it].orEmpty() }
    var searchHandler: suspend (String, FileSearchQuery) -> List<FileSearchHit> = { _, _ -> emptyList() }
    var previewHandler: suspend (FileRef) -> FilePreview = { FilePreview.Text(it.path, false, false) }
    var deleteHandler: suspend (List<FileRef>) -> FileOpOutcome = { FileOpOutcome(it, emptyList()) }
    var moveHandler: suspend (List<FileRef>, FileRef) -> FileOpOutcome = { refs, target -> FileOpOutcome(refs.map { destination(it, target) }, emptyList()) }
    var copyHandler: suspend (List<FileRef>, FileRef) -> FileOpOutcome = { refs, target -> FileOpOutcome(refs.map { destination(it, target) }, emptyList()) }
    var createFolderHandler: suspend (FileRef, String) -> FileItem = { parent, name ->
        file(if (parent.path == "/") "/$name" else "${parent.path}/$name", parent.locationId, directory = true)
    }
    var moveCalls = 0
    var createCalls = 0
    var renameCalls = 0
    var extractCalls = 0
    var shareCalls = 0

    private fun destination(source: FileRef, target: FileRef): FileRef = FileRef(
        target.locationId, if (target.path == "/") "/${source.path.substringAfterLast('/')}" else "${target.path}/${source.path.substringAfterLast('/')}",
    )

    override suspend fun list(locationId: String, path: String): List<FileItem> {
        val ref = FileRef(locationId, path)
        listCalls += ref
        return listHandler(ref)
    }
    override suspend fun stat(ref: FileRef): FileItem? = listings.values.flatten().find { it.ref == ref }
    override suspend fun search(locationId: String, query: FileSearchQuery, limit: Int): List<FileSearchHit> {
        searchCalls += query
        return searchHandler(locationId, query)
    }
    override suspend fun createFolder(parent: FileRef, name: String): FileItem {
        createCalls++
        return createFolderHandler(parent, name)
    }
    override suspend fun createTextFile(parent: FileRef, name: String, content: String): FileItem =
        file(if (parent.path == "/") "/$name" else "${parent.path}/$name", parent.locationId)
    override suspend fun rename(ref: FileRef, newName: String): FileItem {
        renameCalls++
        return file("/$newName", ref.locationId)
    }
    override suspend fun copy(sources: List<FileRef>, targetDirectory: FileRef): FileOpOutcome = copyHandler(sources, targetDirectory)
    override suspend fun move(sources: List<FileRef>, targetDirectory: FileRef): FileOpOutcome {
        moveCalls++
        return moveHandler(sources, targetDirectory)
    }
    override suspend fun delete(sources: List<FileRef>): FileOpOutcome = deleteHandler(sources)
    override suspend fun compress(sources: List<FileRef>, targetDirectory: FileRef, archiveName: String): FileItem =
        file("/$archiveName", targetDirectory.locationId)
    override suspend fun extract(archive: FileRef, targetDirectory: FileRef): FileItem {
        extractCalls++
        return file("/extract", targetDirectory.locationId, true)
    }
    override suspend fun importUris(uris: List<String>, targetDirectory: FileRef): FileOpOutcome =
        FileOpOutcome(uris.map { FileRef(targetDirectory.locationId, "/import") }, emptyList())
    override suspend fun importText(text: String, targetDirectory: FileRef, name: String): FileItem = file("/$name", targetDirectory.locationId)
    override suspend fun preview(ref: FileRef, maxTextBytes: Int): FilePreview = previewHandler(ref)
    override suspend fun shareUri(ref: FileRef): String {
        shareCalls++
        return "content://example.invalid${ref.path}"
    }
}
