package com.promenar.infinity.ui.files.state

import com.promenar.infinity.domain.files.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DefaultFileManagerController(
    private val scope: CoroutineScope,
    private val services: FileManagerServices,
    locations: Flow<List<FileLocation>>,
    private val initial: FileRef? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val preferences: FileManagerPreferences? = null,
) : FileManagerController {
    private val mutableState = MutableStateFlow(FileManagerUiState())
    override val state: StateFlow<FileManagerUiState> = mutableState.asStateFlow()
    private val operations = services.operations
    private var allItems: List<FileItem> = emptyList()
    private val blockedLocations = MutableStateFlow<Set<String>>(emptySet())
    private var pins: List<FileRef> = emptyList()
    private var initialHandled = false
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var previewJob: Job? = null
    private var pickerJob: Job? = null
    private var pickerCreateJob: Job? = null
    private var pinJob: Job? = null
    private var sessionLocationJob: Job? = null
    private var watchedSessionLocationId: String? = null
    @Volatile private var sessionLocation: FileLocation? = null
    private var sessionLocationVersion = 0L
    /** 目标选择器从“会话工作区”汇总根进入的会话位置；不在位置列表里，只在选择器内按需解析一次。 */
    @Volatile private var pickerSessionLocation: FileLocation? = null
    private var pickerResolveJob: Job? = null
    private var loadVersion = 0L
    private var searchVersion = 0L
    private var previewVersion = 0L
    private var pickerVersion = 0L
    private var dirtySort = false
    private var dirtyView = false
    private var dirtyHidden = false
    private var preferencesReady = false
    private val preferenceWrites = Channel<FileManagerPreferenceValues>(Channel.CONFLATED)

    init {
        observePreferences()
        scope.launch {
            services.pins.observePins().flowOn(io).collect { values ->
                synchronized(this@DefaultFileManagerController) {
                    pins = values
                    mutableState.update { it.copy(pinned = currentRef(it) in values) }
                }
            }
        }
        scope.launch {
            locations.flowOn(io).collect { values -> applyLocations(values) }
        }
    }

    private fun observePreferences() {
        val store = preferences ?: return
        scope.launch {
            val saved = try {
                withContext(io) { store.read() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            synchronized(this@DefaultFileManagerController) {
                val oldShowHidden = state.value.showHidden
                if (saved != null) {
                    mutableState.update { current ->
                        val updated = current.copy(
                            sort = if (dirtySort) current.sort else saved.sort,
                            viewMode = if (dirtyView) current.viewMode else saved.viewMode,
                            showHidden = if (dirtyHidden) current.showHidden else saved.showHidden,
                        )
                        withListing(updated)
                    }
                }
                val picker = state.value.targetPicker
                if (oldShowHidden != state.value.showHidden && picker != null && pickerCreateJob?.isActive != true) {
                    loadPicker(picker.locationId, picker.path)
                }
                preferencesReady = true
                if (dirtySort || dirtyView || dirtyHidden) queuePreferences()
            }
            for (values in preferenceWrites) {
                try {
                    withContext(io) { store.write(values) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // 偏好保存失败不影响本次会话内的设置。
                }
            }
        }
    }

    private fun queuePreferences() {
        if (preferences != null && preferencesReady) {
            val current = state.value
            preferenceWrites.trySend(FileManagerPreferenceValues(current.sort, current.viewMode, current.showHidden))
        }
    }

    @Synchronized
    private fun applyLocations(values: List<FileLocation>) {
        val current = state.value
        mutableState.update { it.copy(locations = values) }
        if (values.isEmpty()) {
            clearNavigation()
            return
        }
        val existing = current.location?.id?.let(::findLocation)
        if (existing != null) {
            if (existing.accessLost != current.location?.accessLost) {
                navigate(existing, current.path)
                return
            }
            val picker = current.targetPicker
            val pickerLocation = picker?.locationId?.let(::findLocation)?.takeUnless { it.accessLost }
            val pickerInvalid = picker != null && (pickerLocation == null || picker.sources.any { source ->
                findLocation(source.locationId)?.accessLost != false
            })
            val previewInvalid = current.previewItem?.ref?.let { preview ->
                findLocation(preview.locationId)?.accessLost != false
            } == true
            if (pickerInvalid) cancelPickerJobs()
            if (previewInvalid) cancelPreview()
            mutableState.update { old ->
                old.copy(
                    location = existing,
                    crumbs = navigationCrumbs(existing, old.path),
                    targetPicker = if (!pickerInvalid && picker != null && pickerLocation != null) {
                        picker.copy(crumbs = FileListing.crumbs(pickerLocation, picker.path))
                    } else null,
                    previewItem = if (previewInvalid) null else old.previewItem,
                    preview = if (previewInvalid) null else old.preview,
                    previewLoading = if (previewInvalid) false else old.previewLoading,
                    notice = if (pickerInvalid && picker != null) {
                        FileManagerNotice.Failed(picker.mode.noticeKind(), FileOpCode.PERMISSION_LOST, picker.sources.size, picker.sources.size)
                    } else old.notice,
                )
            }
            return
        }
        val requested = if (!initialHandled) initial else null
        initialHandled = true
        if (requested != null && requested.locationId.startsWith("session:") &&
            values.none { it.id == requested.locationId } && values.any { it.id == "sessions" }) {
            navigate(values.first { it.id == "sessions" }, "/")
            openSessionLocation(requested.locationId, requested.path)
            return
        }
        val selected = values.firstOrNull { it.id == requested?.locationId } ?: values.first()
        val path = if (selected.id == requested?.locationId) requested?.path ?: "/" else "/"
        navigate(selected, path)
    }

    private fun clearNavigation() {
        cancelSessionLocation()
        cancelLoad()
        cancelSearch()
        cancelPreview()
        cancelPickerJobs()
        allItems = emptyList()
        mutableState.update {
            it.copy(
                location = null, path = "/", crumbs = emptyList(), items = emptyList(),
                loading = false, loadError = null, selection = emptySet(), search = null,
                previewItem = null, preview = null, previewLoading = false, targetPicker = null, pinned = false,
            )
        }
    }

    private fun navigate(location: FileLocation, path: String) {
        if (watchedSessionLocationId != null && watchedSessionLocationId != location.id) cancelSessionLocation()
        val normalized = normalizeFilePath(path)
        cancelLoad()
        cancelSearch()
        cancelPreview()
        cancelPickerJobs()
        allItems = emptyList()
        val ref = FileRef(location.id, normalized)
        if (location.accessLost) blockedLocations.update { it + location.id }
        mutableState.update {
            it.copy(
                location = location, path = normalized, crumbs = navigationCrumbs(location, normalized),
                items = emptyList(), loading = !location.accessLost,
                loadError = if (location.accessLost) FileOpCode.PERMISSION_LOST else null, selection = emptySet(), search = null,
                previewItem = null, preview = null, previewLoading = false, targetPicker = null, pinned = ref in pins,
            )
        }
        if (!location.accessLost) load(ref)
    }

    private fun findLocation(id: String): FileLocation? =
        state.value.locations.firstOrNull { it.id == id }
            ?: listOfNotNull(sessionLocation, pickerSessionLocation)
                .firstOrNull { it.id == id && state.value.locations.any { parent -> parent.id == SESSIONS_ID } }

    private fun navigationCrumbs(location: FileLocation, path: String): List<FileCrumb> =
        sessionCrumbs(location, path, hidden = watchedSessionLocationId == location.id)

    /** 隐藏会话的面包屑以“会话工作区”开头，会话名一级用 [SESSION_ROOT_CRUMB] 标识会话根。 */
    private fun sessionCrumbs(location: FileLocation, path: String, hidden: Boolean): List<FileCrumb> {
        val crumbs = FileListing.crumbs(location, path)
        val parent = state.value.locations.firstOrNull { it.id == SESSIONS_ID }
        return if (hidden && parent != null) {
            listOf(FileCrumb(parent.title, "/"), crumbs.first().copy(path = SESSION_ROOT_CRUMB)) + crumbs.drop(1)
        } else crumbs
    }

    private fun isHiddenSession(id: String) = id.startsWith("session:") && state.value.locations.none { it.id == id }

    private fun pickerCrumbs(location: FileLocation, path: String) =
        sessionCrumbs(location, path, hidden = isHiddenSession(location.id))

    private fun cancelSessionLocation() {
        sessionLocationVersion++
        sessionLocationJob?.cancel()
        sessionLocationJob = null
        watchedSessionLocationId = null
        sessionLocation = null
    }

    private fun sessionLocationUnavailable(id: String) {
        if (state.value.location?.id == id) {
            val parent = state.value.locations.firstOrNull { it.id == "sessions" }
            if (parent != null) navigate(parent, "/") else clearNavigation()
        } else cancelSessionLocation()
    }

    private fun openSessionLocation(locationId: String, path: String, preview: FileItem? = null) {
        val sessionId = locationId.removePrefix("session:").takeIf { locationId.startsWith("session:") && it.isNotBlank() }
            ?: return
        val parent = state.value.locations.firstOrNull { it.id == "sessions" } ?: return
        if (watchedSessionLocationId != null && watchedSessionLocationId != locationId) navigate(parent, "/")
        cancelSessionLocation()
        watchedSessionLocationId = locationId
        val version = sessionLocationVersion
        var pendingNavigation = true
        sessionLocationJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                services.locations.observeSessionLocations(sessionId).flowOn(io).collect { values ->
                    synchronized(this@DefaultFileManagerController) {
                        if (version != sessionLocationVersion) return@synchronized
                        val resolved = values.firstOrNull { it.id == locationId && !it.accessLost }
                        if (resolved == null) {
                            sessionLocationUnavailable(locationId)
                        } else {
                            sessionLocation = resolved
                            if (pendingNavigation) {
                                pendingNavigation = false
                                navigate(resolved, path)
                                preview?.let(::showPreview)
                            } else if (state.value.location?.id == locationId) {
                                mutableState.update {
                                    val picker = it.targetPicker
                                    it.copy(location = resolved, crumbs = navigationCrumbs(resolved, it.path),
                                        targetPicker = if (picker?.locationId == locationId) {
                                            picker.copy(location = resolved, crumbs = pickerCrumbs(resolved, picker.path))
                                        } else picker)
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(this@DefaultFileManagerController) {
                    if (version == sessionLocationVersion) sessionLocationUnavailable(locationId)
                }
            }
        }
        sessionLocationJob?.start()
    }

    private fun cancelLoad() { loadVersion++; loadJob?.cancel(); loadJob = null }
    private fun cancelSearch() { searchVersion++; searchJob?.cancel(); searchJob = null }
    private fun cancelPreview() { previewVersion++; previewJob?.cancel(); previewJob = null }
    private fun cancelPickerJobs() {
        pickerVersion++
        pickerJob?.cancel()
        pickerCreateJob?.cancel()
        pickerResolveJob?.cancel()
        pickerJob = null
        pickerCreateJob = null
        pickerResolveJob = null
    }

    private fun withListing(current: FileManagerUiState): FileManagerUiState {
        val visible = FileListing.items(allItems, current.sort, current.showHidden)
        val paths = visible.mapTo(mutableSetOf()) { it.ref.path }
        return current.copy(items = visible, selection = current.selection.intersect(paths))
    }

    private fun load(ref: FileRef) {
        cancelLoad()
        val version = loadVersion
        mutableState.update { it.copy(loading = true, loadError = null) }
        loadJob = scope.launch {
            try {
                val items = withContext(io) {
                    requireLocationAvailable(ref)
                    operations.list(ref.locationId, ref.path)
                }
                synchronized(this@DefaultFileManagerController) {
                    if (version == loadVersion && currentRef(state.value) == ref) {
                        blockedLocations.update { it - ref.locationId }
                        allItems = items
                        mutableState.update { withListing(it.copy(loading = false, loadError = null)) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                synchronized(this@DefaultFileManagerController) {
                    if (version == loadVersion && currentRef(state.value) == ref) {
                        if (error.fileCode() == FileOpCode.PERMISSION_LOST) {
                            blockedLocations.update { it + ref.locationId }
                            cancelSearch()
                            cancelPreview()
                            cancelPickerJobs()
                            allItems = emptyList()
                            mutableState.update {
                                it.copy(loading = false, loadError = FileOpCode.PERMISSION_LOST, items = emptyList(),
                                    selection = emptySet(), search = null, previewItem = null, preview = null,
                                    previewLoading = false, targetPicker = null)
                            }
                        } else {
                            mutableState.update { it.copy(loading = false, loadError = error.fileCode()) }
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    override fun openLocation(locationId: String, path: String) {
        val location = findLocation(locationId)
        if (location != null) navigate(location, path) else openSessionLocation(locationId, path)
    }

    @Synchronized
    override fun open(item: FileItem) {
        if (state.value.location?.id == "sessions" && item.isDirectory &&
            state.value.items.any { it.ref == item.ref } && item.ref.locationId.startsWith("session:")) {
            openSessionLocation(item.ref.locationId, item.ref.path)
            return
        }
        if (!isAllowed(item.ref)) return
        if (item.isDirectory) openLocation(item.ref.locationId, item.ref.path) else showPreview(item)
    }

    @Synchronized
    override fun goUp() {
        val current = state.value
        val location = current.location ?: return
        if (!current.canGoUp) return
        if (current.path == "/" && watchedSessionLocationId == location.id) openLocation("sessions")
        else navigate(location, parentFilePath(current.path))
    }

    @Synchronized
    override fun goTo(path: String) {
        val location = state.value.location ?: return
        if (watchedSessionLocationId == location.id && path == "/") openLocation("sessions")
        else navigate(location, if (watchedSessionLocationId == location.id && path == SESSION_ROOT_CRUMB) "/" else path)
    }

    @Synchronized
    override fun refresh() {
        services.locations.refreshGitStatus()
        val current = state.value
        val ref = currentRef(current) ?: return
        if (!isLocationAvailable(ref)) return
        load(ref)
        current.search?.let { search(it) }
    }

    @Synchronized
    override fun setSortKey(key: FileSortKey) {
        dirtySort = true
        mutableState.update { withListing(it.copy(sort = it.sort.copy(key = key, ascending = key == FileSortKey.NAME || key == FileSortKey.TYPE))) }
        queuePreferences()
    }

    @Synchronized
    override fun toggleSortDirection() {
        dirtySort = true
        mutableState.update { withListing(it.copy(sort = it.sort.copy(ascending = !it.sort.ascending))) }
        queuePreferences()
    }

    @Synchronized
    override fun toggleFoldersFirst() {
        dirtySort = true
        mutableState.update { withListing(it.copy(sort = it.sort.copy(foldersFirst = !it.sort.foldersFirst))) }
        queuePreferences()
    }

    @Synchronized
    override fun setViewMode(mode: FileViewMode) {
        dirtyView = true
        mutableState.update { it.copy(viewMode = mode) }
        queuePreferences()
    }

    @Synchronized
    override fun toggleShowHidden() {
        dirtyHidden = true
        mutableState.update { withListing(it.copy(showHidden = !it.showHidden)) }
        val picker = state.value.targetPicker
        if (picker != null && pickerCreateJob?.isActive != true) loadPicker(picker.locationId, picker.path)
        queuePreferences()
    }

    @Synchronized
    override fun toggleSelection(item: FileItem) {
        mutableState.update { current ->
            // 汇总根的条目都是各会话的根（路径同为 "/"），只能进入，不能选择。
            if (current.search != null || current.location?.id == SESSIONS_ID || current.items.none { it.ref == item.ref }) current
            else current.copy(selection = if (item.ref.path in current.selection) current.selection - item.ref.path else current.selection + item.ref.path)
        }
    }

    @Synchronized
    override fun selectAll() {
        mutableState.update { current ->
            if (current.search != null || current.location?.id == SESSIONS_ID) current
            else current.copy(selection = current.items.mapTo(mutableSetOf()) { it.ref.path })
        }
    }

    @Synchronized
    override fun clearSelection() { mutableState.update { it.copy(selection = emptySet()) } }

    @Synchronized
    override fun startSearch() {
        val ref = currentRef(state.value) ?: return
        if (!isAllowed(ref)) return
        cancelSearch()
        mutableState.update { it.copy(search = FileSearchState(), selection = emptySet()) }
    }

    @Synchronized
    override fun updateSearch(transform: (FileSearchState) -> FileSearchState) {
        val previous = state.value.search ?: return
        val updated = transform(previous).copy(running = false, hits = emptyList())
        mutableState.update { it.copy(search = updated, selection = emptySet()) }
        search(updated)
    }

    private fun search(query: FileSearchState) {
        cancelSearch()
        val ref = currentRef(state.value) ?: return
        if (query.text.isBlank()) {
            mutableState.update { old -> old.copy(search = old.search?.copy(running = false, hits = emptyList())) }
            return
        }
        val version = searchVersion
        mutableState.update { old -> old.copy(search = old.search?.copy(running = true, hits = emptyList())) }
        val request = FileSearchQuery(query.text, if (query.withinCurrentFolder) ref.path else null, query.types, query.modifiedAfter)
        searchJob = scope.launch {
            try {
                delay(250)
                val hits = withContext(io) {
                    requireAllowed(ref)
                    operations.search(ref.locationId, request)
                }
                synchronized(this@DefaultFileManagerController) {
                    if (version == searchVersion && currentRef(state.value) == ref) {
                        mutableState.update { old -> old.copy(search = old.search?.copy(running = false, hits = hits)) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(this@DefaultFileManagerController) {
                    if (version == searchVersion && currentRef(state.value) == ref) {
                        mutableState.update { old -> old.copy(search = old.search?.copy(running = false, hits = emptyList())) }
                    }
                }
            }
        }
    }

    @Synchronized
    override fun closeSearch() {
        cancelSearch()
        mutableState.update { it.copy(search = null) }
    }

    @Synchronized
    override fun reveal(hit: FileSearchHit) {
        val location = findLocation(hit.item.ref.locationId)?.takeUnless { it.accessLost }
        if (location == null) {
            if (state.value.location?.id == "sessions" && state.value.search?.hits?.contains(hit) == true) {
                openSessionLocation(hit.item.ref.locationId, hit.parentPath, hit.item.takeUnless { it.isDirectory })
            }
            return
        }
        navigate(location, hit.parentPath)
        if (!hit.item.isDirectory) showPreview(hit.item)
    }

    @Synchronized
    override fun showPreview(item: FileItem) {
        if (item.isDirectory || !isAllowed(item.ref)) return
        cancelPreview()
        val version = previewVersion
        mutableState.update { it.copy(previewItem = item, preview = null, previewLoading = true) }
        previewJob = scope.launch {
            try {
                val preview = withContext(io) {
                    requireAllowed(item.ref)
                    operations.preview(item.ref)
                }
                synchronized(this@DefaultFileManagerController) {
                    if (version == previewVersion && state.value.previewItem?.ref == item.ref) {
                        mutableState.update { it.copy(preview = preview, previewLoading = false) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(this@DefaultFileManagerController) {
                    if (version == previewVersion && state.value.previewItem?.ref == item.ref) {
                        mutableState.update { it.copy(preview = FilePreview.Unsupported, previewLoading = false) }
                    }
                }
            }
        }
    }

    @Synchronized
    override fun closePreview() {
        cancelPreview()
        mutableState.update { it.copy(previewItem = null, preview = null, previewLoading = false) }
    }

    private fun isLocationAvailable(ref: FileRef): Boolean =
        findLocation(ref.locationId)?.accessLost == false

    private fun isAllowed(ref: FileRef): Boolean =
        isLocationAvailable(ref) && ref.locationId !in blockedLocations.value

    private fun requireLocationAvailable(ref: FileRef) {
        if (!isLocationAvailable(ref)) throw FileOpException(FileOpCode.PERMISSION_LOST)
    }

    private fun requireAllowed(ref: FileRef) {
        if (!isAllowed(ref)) throw FileOpException(FileOpCode.PERMISSION_LOST)
    }

    private fun ensureAllowed(refs: List<FileRef>, kind: FileManagerNotice.Kind, total: Int): Boolean {
        if (refs.all(::isAllowed)) return true
        mutableState.update { it.copy(notice = FileManagerNotice.Failed(kind, FileOpCode.PERMISSION_LOST, total, total)) }
        return false
    }

    private fun selected(items: List<FileItem>): List<FileRef> {
        if (items.isNotEmpty()) return items.map { it.ref }.distinct()
        val current = state.value
        return current.items.filter { it.ref.path in current.selection }.map { it.ref }
    }

    private fun validateName(name: String, kind: FileManagerNotice.Kind): Boolean {
        if (FileNames.isValid(name)) return true
        mutableState.update { it.copy(notice = FileManagerNotice.Failed(kind, FileOpCode.INVALID_NAME, 1, 1)) }
        return false
    }

    private fun write(
        kind: FileManagerNotice.Kind,
        total: Int,
        origin: FileRef,
        target: String? = null,
        protectedRefs: List<FileRef> = listOf(origin),
        after: (FileOpOutcome) -> Unit = {},
        action: suspend () -> FileOpOutcome,
    ) {
        if (total == 0 || !ensureAllowed(protectedRefs, kind, total)) return
        scope.launch {
            val notice = try {
                val outcome = withContext(io) {
                    protectedRefs.forEach(::requireAllowed)
                    action()
                }
                synchronized(this@DefaultFileManagerController) {
                    after(outcome)
                    outcome.toNotice(kind, total, target)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FileManagerNotice.Failed(kind, error.fileCode(), total, total)
            }
            synchronized(this@DefaultFileManagerController) {
                mutableState.update { current ->
                    current.copy(selection = if (currentRef(current) == origin) emptySet() else current.selection, notice = notice)
                }
                val current = currentRef(state.value)
                if (current != null && isAllowed(current)) refresh()
            }
        }
    }

    @Synchronized
    override fun createFolder(name: String) {
        val parent = currentRef(state.value) ?: return
        if (!validateName(name, FileManagerNotice.Kind.CREATE)) return
        write(FileManagerNotice.Kind.CREATE, 1, parent) {
            FileOpOutcome(listOf(operations.createFolder(parent, name).ref), emptyList())
        }
    }

    @Synchronized
    override fun createTextFile(name: String) {
        val parent = currentRef(state.value) ?: return
        if (!validateName(name, FileManagerNotice.Kind.CREATE)) return
        write(FileManagerNotice.Kind.CREATE, 1, parent) {
            FileOpOutcome(listOf(operations.createTextFile(parent, name).ref), emptyList())
        }
    }

    @Synchronized
    override fun rename(item: FileItem, newName: String) {
        val parent = currentRef(state.value) ?: return
        if (!validateName(newName, FileManagerNotice.Kind.RENAME)) return
        write(FileManagerNotice.Kind.RENAME, 1, parent, protectedRefs = listOf(parent, item.ref), action = {
            FileOpOutcome(listOf(operations.rename(item.ref, newName).ref), emptyList())
        }, after = {
            if (state.value.previewItem?.ref == item.ref) closePreview()
        })
    }

    @Synchronized
    override fun delete(items: List<FileItem>) {
        val parent = currentRef(state.value) ?: return
        val refs = selected(items)
        write(FileManagerNotice.Kind.DELETE, refs.size, parent, protectedRefs = listOf(parent) + refs, action = { operations.delete(refs) }, after = { outcome ->
            val preview = state.value.previewItem?.ref
            if (preview != null && outcome.succeeded.any { it.containsPath(preview) }) closePreview()
        })
    }

    @Synchronized
    override fun compress(items: List<FileItem>, archiveName: String) {
        val parent = currentRef(state.value) ?: return
        val refs = selected(items)
        if (refs.isEmpty() || !validateName(archiveName, FileManagerNotice.Kind.COMPRESS)) return
        write(FileManagerNotice.Kind.COMPRESS, refs.size, parent, protectedRefs = listOf(parent) + refs) {
            operations.compress(refs, parent, archiveName)
            FileOpOutcome(refs, emptyList())
        }
    }

    @Synchronized
    override fun extract(item: FileItem) {
        val parent = currentRef(state.value) ?: return
        write(FileManagerNotice.Kind.EXTRACT, 1, parent, protectedRefs = listOf(parent, item.ref)) {
            FileOpOutcome(listOf(operations.extract(item.ref, parent).ref), emptyList())
        }
    }

    @Synchronized
    override fun importUris(uris: List<String>) {
        val parent = currentRef(state.value) ?: return
        write(FileManagerNotice.Kind.IMPORT, uris.size, parent) { operations.importUris(uris, parent) }
    }

    @Synchronized
    override fun importText(text: String, name: String) {
        val parent = currentRef(state.value) ?: return
        if (!validateName(name, FileManagerNotice.Kind.IMPORT)) return
        write(FileManagerNotice.Kind.IMPORT, 1, parent) {
            FileOpOutcome(listOf(operations.importText(text, parent, name).ref), emptyList())
        }
    }

    @Synchronized
    override fun openTargetPicker(mode: FileTransferMode, items: List<FileItem>) {
        val current = state.value
        val location = current.location ?: return
        val sources = selected(items)
        if (sources.isEmpty()) return
        // 会话工作区汇总根里的条目是整个会话目录，后端不支持复制或移动位置根。
        if (location.id == SESSIONS_ID) {
            mutableState.update { it.copy(notice = FileManagerNotice.Failed(mode.noticeKind(), FileOpCode.UNSUPPORTED, sources.size, sources.size)) }
            return
        }
        if (!ensureAllowed(listOf(FileRef(location.id, current.path)) + sources, mode.noticeKind(), sources.size)) return
        cancelPickerJobs()
        pickerSessionLocation = null
        mutableState.update {
            it.copy(targetPicker = FileTargetPickerState(mode, sources, location.id, current.path, pickerCrumbs(location, current.path), emptyList(), true, location))
        }
        loadPicker(location.id, current.path)
    }

    @Synchronized
    override fun pickerOpenLocation(locationId: String) {
        if (state.value.targetPicker == null || pickerCreateJob?.isActive == true || !isAllowed(FileRef(locationId, "/"))) return
        loadPicker(locationId, "/")
    }

    @Synchronized
    override fun pickerGoTo(path: String) {
        val picker = state.value.targetPicker ?: return
        if (pickerCreateJob?.isActive == true) return
        if (isHiddenSession(picker.locationId)) {
            when (path) {
                "/" -> return loadPicker(SESSIONS_ID, "/")
                SESSION_ROOT_CRUMB -> return loadPicker(picker.locationId, "/")
            }
        }
        loadPicker(picker.locationId, path)
    }

    @Synchronized
    override fun pickerOpenFolder(folder: FileItem) {
        val picker = state.value.targetPicker ?: return
        if (pickerCreateJob?.isActive == true) return
        val target = folder.ref.locationId
        if (target == picker.locationId) return loadPicker(target, folder.ref.path)
        if (picker.locationId != SESSIONS_ID || !target.startsWith("session:")) return
        if (findLocation(target)?.accessLost == false) return loadPicker(target, folder.ref.path)
        val sessionId = target.removePrefix("session:")
        pickerJob?.cancel()
        pickerResolveJob?.cancel()
        val version = ++pickerVersion
        mutableState.update { it.copy(targetPicker = picker.copy(loading = true)) }
        pickerResolveJob = scope.launch {
            val resolved = try {
                services.locations.observeSessionLocations(sessionId).flowOn(io).first()
                    .firstOrNull { it.id == target && !it.accessLost }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            synchronized(this@DefaultFileManagerController) {
                val current = state.value.targetPicker
                if (version != pickerVersion || current == null) return@synchronized
                pickerResolveJob = null
                if (resolved == null) {
                    mutableState.update {
                        it.copy(targetPicker = current.copy(loading = false),
                            notice = FileManagerNotice.Failed(current.mode.noticeKind(), FileOpCode.NOT_FOUND, current.sources.size, current.sources.size))
                    }
                } else {
                    pickerSessionLocation = resolved
                    loadPicker(target, "/")
                }
            }
        }
    }

    private fun loadPicker(locationId: String, path: String) {
        val location = findLocation(locationId)?.takeUnless { it.accessLost } ?: return
        if (!isAllowed(FileRef(locationId, path))) return
        val normalized = normalizeFilePath(path)
        val existing = state.value.targetPicker ?: return
        pickerJob?.cancel()
        pickerCreateJob?.cancel()
        pickerResolveJob?.cancel()
        val version = ++pickerVersion
        mutableState.update { it.copy(targetPicker = existing.copy(locationId = locationId, path = normalized, crumbs = pickerCrumbs(location, normalized), folders = emptyList(), loading = true, location = location)) }
        pickerJob = scope.launch {
            try {
                val items = withContext(io) {
                    requireAllowed(FileRef(locationId, normalized))
                    operations.list(locationId, normalized)
                }
                synchronized(this@DefaultFileManagerController) {
                    if (version == pickerVersion) {
                        mutableState.update { current ->
                            val picker = current.targetPicker
                            if (picker == null || picker.locationId != locationId || picker.path != normalized) current
                            else current.copy(targetPicker = picker.copy(folders = FileListing.items(items.filter { it.isDirectory }, current.sort, current.showHidden), loading = false))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                synchronized(this@DefaultFileManagerController) {
                    if (version == pickerVersion) {
                        mutableState.update { current ->
                            val picker = current.targetPicker
                            if (picker == null) current else current.copy(
                                targetPicker = picker.copy(loading = false),
                                notice = FileManagerNotice.Failed(picker.mode.noticeKind(), error.fileCode(), picker.sources.size, picker.sources.size),
                            )
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    override fun pickerCreateFolder(name: String) {
        val picker = state.value.targetPicker ?: return
        if (pickerCreateJob?.isActive == true || !validateName(name, FileManagerNotice.Kind.CREATE)) return
        val parent = FileRef(picker.locationId, picker.path)
        if (!ensureAllowed(listOf(parent), FileManagerNotice.Kind.CREATE, 1)) return
        pickerJob?.cancel()
        val version = ++pickerVersion
        mutableState.update { it.copy(targetPicker = it.targetPicker?.copy(loading = true)) }
        pickerCreateJob = scope.launch {
            try {
                val created = withContext(io) {
                    requireAllowed(parent)
                    operations.createFolder(parent, name)
                }
                synchronized(this@DefaultFileManagerController) {
                    val current = state.value.targetPicker
                    if (version == pickerVersion && current != null && current.locationId == parent.locationId && current.path == parent.path) {
                        pickerCreateJob = null
                        loadPicker(created.ref.locationId, created.ref.path)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                synchronized(this@DefaultFileManagerController) {
                    if (version == pickerVersion && state.value.targetPicker != null) {
                        pickerCreateJob = null
                        mutableState.update {
                            it.copy(targetPicker = it.targetPicker?.copy(loading = false),
                                notice = FileManagerNotice.Failed(FileManagerNotice.Kind.CREATE, error.fileCode(), 1, 1))
                        }
                        if (isAllowed(parent)) loadPicker(parent.locationId, parent.path)
                    }
                }
            }
        }
    }

    @Synchronized
    override fun confirmTargetPicker() {
        val picker = state.value.targetPicker ?: return
        if (picker.loading || pickerCreateJob?.isActive == true) return
        val origin = currentRef(state.value) ?: return
        val target = FileRef(picker.locationId, picker.path)
        val kind = picker.mode.noticeKind()
        val protectedRefs = listOf(origin, target) + picker.sources
        if (!ensureAllowed(protectedRefs, kind, picker.sources.size)) return
        if (picker.mode == FileTransferMode.MOVE && picker.sources.any { it.containsPath(target) }) {
            mutableState.update { it.copy(notice = FileManagerNotice.Failed(kind, FileOpCode.CONFLICT, picker.sources.size, picker.sources.size)) }
            return
        }
        dismissTargetPicker()
        write(kind, picker.sources.size, origin, target.path, protectedRefs, after = { outcome ->
            if (picker.mode == FileTransferMode.MOVE && outcome.succeeded.isNotEmpty()) {
                val preview = state.value.previewItem?.ref
                if (preview != null && picker.sources.any { it.containsPath(preview) }) closePreview()
            }
        }) {
            if (picker.mode == FileTransferMode.COPY) operations.copy(picker.sources, target)
            else operations.move(picker.sources, target)
        }
    }

    @Synchronized
    override fun dismissTargetPicker() {
        cancelPickerJobs()
        mutableState.update { it.copy(targetPicker = null) }
    }

    @Synchronized
    override fun togglePinCurrentFolder() {
        val ref = currentRef(state.value) ?: return
        if (pinJob?.isActive == true) return
        if (!ensureAllowed(listOf(ref), FileManagerNotice.Kind.PIN, 1)) return
        val pinned = ref in pins
        pinJob = scope.launch {
            val notice = try {
                val success = withContext(io) {
                    requireAllowed(ref)
                    if (pinned) {
                        services.pins.unpin(ref)
                        true
                    } else services.pins.pin(ref)
                }
                if (success) FileManagerNotice.Done(FileManagerNotice.Kind.PIN, 1)
                else FileManagerNotice.Failed(FileManagerNotice.Kind.PIN, FileOpCode.UNSUPPORTED, 1, 1)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FileManagerNotice.Failed(FileManagerNotice.Kind.PIN, error.fileCode(), 1, 1)
            }
            synchronized(this@DefaultFileManagerController) { mutableState.update { it.copy(notice = notice) } }
        }
    }

    override suspend fun shareUri(item: FileItem): String = withContext(io) {
        requireAllowed(item.ref)
        operations.shareUri(item.ref)
    }

    @Synchronized
    override fun consumeNotice() { mutableState.update { it.copy(notice = null) } }

    private companion object {
        // 面包屑共用路径回调，双斜线仅标识会话根，不传入文件后端。
        const val SESSION_ROOT_CRUMB = "//"
        const val SESSIONS_ID = "sessions"
    }
}

internal fun currentRef(state: FileManagerUiState): FileRef? = state.location?.let { FileRef(it.id, state.path) }

internal fun Exception.fileCode(): FileOpCode = (this as? FileOpException)?.code ?: FileOpCode.IO

internal fun FileTransferMode.noticeKind(): FileManagerNotice.Kind =
    if (this == FileTransferMode.COPY) FileManagerNotice.Kind.COPY else FileManagerNotice.Kind.MOVE

internal fun FileRef.containsPath(other: FileRef): Boolean {
    if (locationId != other.locationId) return false
    val parent = normalizeFilePath(path)
    val child = normalizeFilePath(other.path)
    return parent == child || child.startsWith(if (parent == "/") "/" else "$parent/")
}

internal fun FileOpOutcome.toNotice(kind: FileManagerNotice.Kind, total: Int, target: String? = null): FileManagerNotice {
    if (failed.isEmpty()) return FileManagerNotice.Done(kind, succeeded.size, target)
    val code = failed.groupingBy { it.code }.eachCount().maxByOrNull { it.value }!!.key
    return FileManagerNotice.Failed(kind, code, failed.size, total)
}
