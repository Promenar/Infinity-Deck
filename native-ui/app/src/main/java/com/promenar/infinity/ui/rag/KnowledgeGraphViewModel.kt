package com.promenar.infinity.ui.rag

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.data.rag.GraphData
import com.promenar.infinity.data.rag.GraphStore
import com.promenar.infinity.data.rag.GraphSubgraphRequest
import com.promenar.infinity.data.rag.GraphEntitySearchRequest
import com.promenar.infinity.data.rag.DEFAULT_GRAPH_EDGE_LIMIT
import com.promenar.infinity.data.rag.GRAPH_EXPANSION_BATCH_SIZE
import com.promenar.infinity.data.rag.MAX_GRAPH_NODE_LIMIT
import com.promenar.infinity.data.rag.MAX_GRAPH_EDGE_LIMIT
import com.promenar.infinity.data.rag.KgDocumentOption
import com.promenar.infinity.data.rag.KgEntitySources
import com.promenar.infinity.data.rag.KgNode
import com.promenar.infinity.data.rag.KgSessionOption
import com.promenar.infinity.data.rag.KgSessionTurn
import com.promenar.infinity.data.repository.KnowledgeGraphRepository
import com.promenar.infinity.domain.repository.IKnowledgeGraphRepository
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.ui.rag.canvas.KgEntityKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

import com.promenar.infinity.utils.InfinityLogger

enum class KgViewMode { GLOBAL, DOCUMENT, SESSION }

enum class KgLoadErrorCode { LoadFailed }

enum class KgDocumentOptionsErrorCode { LoadFailed }

data class KgLoadError(
    val code: KgLoadErrorCode,
    val canRetry: Boolean,
    val technical: String?,
)

data class KgDocumentOptionsError(
    val code: KgDocumentOptionsErrorCode,
    val canRetry: Boolean,
    val technical: String?,
)

/** 选中实体的详情：来源文档与会话轮次异步加载。 */
data class KgEntityDetail(
    val nodeId: String,
    val sources: KgEntitySources? = null,
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    /** 合集范围下按阅读顺序的状态变化；其它范围为空。 */
    val history: List<KgEntityHistoryLine> = emptyList(),
)

/** 请求画布把某实体移到视野中央；[key] 每次请求递增。 */
data class KgCenterRequest(val nodeId: String? = null, val key: Int = 0)

data class KgGraphState(
    val scopeKey: String? = null,
    val isLoading: Boolean = false,
    val isStale: Boolean = false,
)

data class GraphNode(
    val id: String,
    val label: String,
    val type: String,
    val x: Float,
    val y: Float,
    val icon: ImageVector = Icons.Rounded.Hub
)

data class GraphEdge(
    val sourceId: String,
    val targetId: String,
    val relation: String = ""
)

class KnowledgeGraphViewModel(
    private val repo: IKnowledgeGraphRepository,
    private val graphStore: GraphStore,
    private val application: Application,
    /** 时间轴读取；为空且读本机图谱列表时读本机数据库，测试注入列表时不读时间轴。 */
    timelineSource: KgGraphTimelineSource? = null,
    /** 图谱列表读取；为空时读本机数据库（测试注入固定列表）。 */
    private val collectionLoader: (suspend () -> List<com.promenar.infinity.data.rag.KgCollectionSummary>)? = null,
) : ViewModel() {

    private val timeline: KgGraphTimelineSource? = timelineSource
        ?: if (collectionLoader == null) DatabaseKgGraphTimelineSource((application as InfinityApplication).database) else null

    /** 当前合集的篇目刻度；少于两篇时不显示时间轴。 */
    private val _timelineTicks = MutableStateFlow<List<com.promenar.infinity.data.rag.KgCollectionArticle>>(emptyList())
    val timelineTicks: StateFlow<List<com.promenar.infinity.data.rag.KgCollectionArticle>> = _timelineTicks.asStateFlow()

    /** 时间轴所选篇目序号；为空表示最新。 */
    private val _asOfOrdinal = MutableStateFlow<Int?>(null)
    val asOfOrdinal: StateFlow<Int?> = _asOfOrdinal.asStateFlow()
    private var ticksJob: Job? = null

    private val _nodes = MutableStateFlow<List<GraphNode>>(emptyList())
    val nodes: StateFlow<List<GraphNode>> = _nodes.asStateFlow()

    private val _edges = MutableStateFlow<List<GraphEdge>>(emptyList())
    val edges: StateFlow<List<GraphEdge>> = _edges.asStateFlow()

    private val _totalNodeCount = MutableStateFlow(0)
    val totalNodeCount: StateFlow<Int> = _totalNodeCount.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _searchResults = MutableStateFlow<List<KgNode>>(emptyList())
    val searchResults: StateFlow<List<KgNode>> = _searchResults.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    private val _searchFailed = MutableStateFlow(false)
    val searchFailed: StateFlow<Boolean> = _searchFailed.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _graphState = MutableStateFlow(KgGraphState())
    val graphState: StateFlow<KgGraphState> = _graphState.asStateFlow()

    private val _viewMode = MutableStateFlow(KgViewMode.GLOBAL)
    val viewMode: StateFlow<KgViewMode> = _viewMode.asStateFlow()

    /** “图谱”视角可选的作品图谱与通用知识图谱。 */
    private val _collectionOptions = MutableStateFlow<List<com.promenar.infinity.data.rag.KgCollectionSummary>>(emptyList())
    val collectionOptions: StateFlow<List<com.promenar.infinity.data.rag.KgCollectionSummary>> = _collectionOptions.asStateFlow()

    private val _selectedCollectionId = MutableStateFlow<String?>(null)
    val selectedCollectionId: StateFlow<String?> = _selectedCollectionId.asStateFlow()

    private val _selectedDocumentId = MutableStateFlow<String?>(null)
    val selectedDocumentId: StateFlow<String?> = _selectedDocumentId.asStateFlow()

    private val _documentSelectionRequired = MutableStateFlow(false)
    val documentSelectionRequired: StateFlow<Boolean> = _documentSelectionRequired.asStateFlow()

    private val _loadError = MutableStateFlow<KgLoadError?>(null)
    val loadError: StateFlow<KgLoadError?> = _loadError.asStateFlow()

    private val _documentOptions = MutableStateFlow<List<KgDocumentOption>>(emptyList())
    val documentOptions: StateFlow<List<KgDocumentOption>> = _documentOptions.asStateFlow()

    private val _documentOptionsError = MutableStateFlow<KgDocumentOptionsError?>(null)
    val documentOptionsError: StateFlow<KgDocumentOptionsError?> =
        _documentOptionsError.asStateFlow()

    private val _sessionOptions = MutableStateFlow<List<KgSessionOption>>(emptyList())
    val sessionOptions: StateFlow<List<KgSessionOption>> = _sessionOptions.asStateFlow()

    private val _sessionOptionsError = MutableStateFlow<KgDocumentOptionsError?>(null)
    val sessionOptionsError: StateFlow<KgDocumentOptionsError?> = _sessionOptionsError.asStateFlow()

    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()

    private val _sessionSelectionRequired = MutableStateFlow(false)
    val sessionSelectionRequired: StateFlow<Boolean> = _sessionSelectionRequired.asStateFlow()

    /** 当前会话有存活证据的轮次，按轮次升序。 */
    private val _sessionTurns = MutableStateFlow<List<KgSessionTurn>>(emptyList())
    val sessionTurns: StateFlow<List<KgSessionTurn>> = _sessionTurns.asStateFlow()

    /** 聚焦的轮次（助手消息 id）；为 null 时显示整个会话。 */
    private val _selectedTurnId = MutableStateFlow<String?>(null)
    val selectedTurnId: StateFlow<String?> = _selectedTurnId.asStateFlow()

    private val _typeFilter = MutableStateFlow<Set<KgEntityKind>>(emptySet())
    val typeFilter: StateFlow<Set<KgEntityKind>> = _typeFilter.asStateFlow()

    private val _selectedEntity = MutableStateFlow<KgEntityDetail?>(null)
    val selectedEntity: StateFlow<KgEntityDetail?> = _selectedEntity.asStateFlow()

    private val _centerRequest = MutableStateFlow(KgCenterRequest())
    val centerRequest: StateFlow<KgCenterRequest> = _centerRequest.asStateFlow()

    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var lastSuccessfulScopeKey: String? = null
    private var documentOptionsJob: Job? = null
    private var documentOptionsGeneration = 0L
    private var sessionOptionsJob: Job? = null
    private var sessionOptionsGeneration = 0L
    private var sessionTurnsJob: Job? = null
    private var sessionTurnsGeneration = 0L
    private var entitySourcesJob: Job? = null
    private var entitySourcesGeneration = 0L
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    /** 搜索选中但尚未在画布中的实体：展开加载完成后再选中并居中。 */
    private var pendingFocusId: String? = null

    init {
        loadDocumentOptions()
        loadSessionOptions()
        loadCollectionOptions(loadDefault = true)
    }

    /** 读取图谱列表；[loadDefault] 时选中实体最多的一张并加载（无图谱时选通用知识）。 */
    fun loadCollectionOptions(loadDefault: Boolean = false) {
        viewModelScope.launch {
            val options = try {
                collectionLoader?.invoke() ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.promenar.infinity.data.rag.KgCollectionRepository((application as com.promenar.infinity.InfinityApplication).database)
                        .listBindableCollections("").filter { it.kind != "session" }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                InfinityLogger.logError("[KG] loadCollectionOptions failed", failure)
                emptyList()
            }
            _collectionOptions.value = options
            if (loadDefault && _selectedCollectionId.value == null && _viewMode.value == KgViewMode.GLOBAL) {
                loadGraphByCollection(options.maxByOrNull { it.nodeCount }?.takeIf { it.nodeCount > 0 }?.id ?: "shared")
            }
        }
    }

    fun loadGraphByCollection(collectionId: String) {
        require(collectionId.isNotBlank()) { "图谱标识不能为空" }
        clearSearch()
        clearScopeFocus()
        _viewMode.value = KgViewMode.GLOBAL
        if (_selectedCollectionId.value != collectionId) {
            _asOfOrdinal.value = null
            _timelineTicks.value = emptyList()
        }
        _selectedCollectionId.value = collectionId
        _documentSelectionRequired.value = false
        _sessionSelectionRequired.value = false
        _loadError.value = null
        loadGraphInternal(scope = KgScope.Collection(collectionId, _asOfOrdinal.value), preserveSameScope = false)
        loadTimelineTicks(collectionId)
    }

    /** 拖动时间轴：[ordinal] 为空或到最后一篇时回到最新。 */
    fun setAsOf(ordinal: Int?) {
        val collectionId = _selectedCollectionId.value ?: return
        val last = _timelineTicks.value.lastOrNull()?.ordinal
        val next = ordinal?.takeIf { last != null && it in 1 until last }
        if (next == _asOfOrdinal.value) return
        _asOfOrdinal.value = next
        if (_viewMode.value != KgViewMode.GLOBAL) return
        // 选中的实体在新时间点仍存在时重新选中，状态变化按新时间点读取。
        _selectedEntity.value?.nodeId?.let { selected ->
            selectEntity(null)
            pendingFocusId = selected
        }
        loadGraphInternal(scope = KgScope.Collection(collectionId, next), preserveSameScope = false)
    }

    private fun loadTimelineTicks(collectionId: String) {
        val source = timeline ?: return
        ticksJob?.cancel()
        ticksJob = viewModelScope.launch {
            val ticks = try {
                source.ticks(collectionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                InfinityLogger.logError("[KG] timeline ticks failed", failure)
                emptyList()
            }
            if (_selectedCollectionId.value != collectionId) return@launch
            _timelineTicks.value = ticks
            // 篇目被删除后原时间点越界时回到最新。
            val point = _asOfOrdinal.value
            if (point != null && (ticks.size < 2 || point >= ticks.last().ordinal)) setAsOf(null)
        }
    }

    fun loadSessionOptions() {
        val generation = ++sessionOptionsGeneration
        sessionOptionsJob?.cancel()
        sessionOptionsJob = viewModelScope.launch {
            try {
                val options = graphStore.getSessionOptions()
                if (generation != sessionOptionsGeneration) return@launch
                _sessionOptions.value = options
                _sessionOptionsError.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation != sessionOptionsGeneration) return@launch
                InfinityLogger.logError("[KG] loadSessionOptions failed", failure)
                _sessionOptionsError.value = KgDocumentOptionsError(
                    code = KgDocumentOptionsErrorCode.LoadFailed,
                    canRetry = true,
                    technical = failure::class.simpleName?.take(80),
                )
            }
        }
    }

    fun loadGraphBySession(sessionId: String) {
        require(sessionId.isNotBlank()) { "会话标识不能为空" }
        clearSearch()
        clearScopeFocus()
        _viewMode.value = KgViewMode.SESSION
        _selectedSessionId.value = sessionId
        _documentSelectionRequired.value = false
        _sessionSelectionRequired.value = false
        _loadError.value = null
        loadGraphInternal(scope = KgScope.Session(sessionId), preserveSameScope = false)
        loadSessionTurns(sessionId)
    }

    /** 再次点选同一轮取消聚焦。 */
    fun selectTurn(messageId: String?) {
        _selectedTurnId.value = if (messageId == _selectedTurnId.value) null else messageId
        _selectedEntity.value = null
    }

    fun toggleTypeFilter(kind: KgEntityKind) {
        _typeFilter.value = _typeFilter.value.let { if (kind in it) it - kind else it + kind }
    }

    fun clearTypeFilter() {
        _typeFilter.value = emptySet()
    }

    /** 选中实体并加载来源；传 null 或再次点同一实体取消选中。 */
    fun selectEntity(nodeId: String?) {
        val generation = ++entitySourcesGeneration
        entitySourcesJob?.cancel()
        if (nodeId == null || nodeId == _selectedEntity.value?.nodeId) {
            _selectedEntity.value = null
            return
        }
        _selectedEntity.value = KgEntityDetail(nodeId)
        val collectionId = _selectedCollectionId.value.takeIf { _viewMode.value == KgViewMode.GLOBAL }
        val ticks = _timelineTicks.value
        val asOf = _asOfOrdinal.value
        entitySourcesJob = viewModelScope.launch {
            try {
                val sources = graphStore.getEntitySources(nodeId)
                if (generation != entitySourcesGeneration) return@launch
                val history = if (collectionId == null || ticks.size < 2) emptyList() else try {
                    timeline?.history(collectionId, nodeId, ticks, asOf).orEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    InfinityLogger.logError("[KG] entity history failed", failure)
                    emptyList()
                }
                if (generation != entitySourcesGeneration) return@launch
                _selectedEntity.value = KgEntityDetail(nodeId, sources, isLoading = false, history = history)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation != entitySourcesGeneration) return@launch
                InfinityLogger.logError("[KG] getEntitySources failed", failure)
                _selectedEntity.value = KgEntityDetail(nodeId, isLoading = false, failed = true)
            }
        }
    }

    /** 画布上点实体：选中并移到可见区域；再点同一实体取消选中。 */
    fun tapEntity(nodeId: String) {
        if (_selectedEntity.value?.nodeId == nodeId) {
            selectEntity(null)
            return
        }
        selectEntity(nodeId)
        requestCenter(nodeId)
    }

    /** 搜索结果选中：已在画布中直接选中并居中，否则先展开它的邻居。 */
    fun focusEntity(nodeId: String) {
        clearSearch()
        if (_nodes.value.any { it.id == nodeId }) {
            pendingFocusId = null
            if (_selectedEntity.value?.nodeId != nodeId) selectEntity(nodeId)
            requestCenter(nodeId)
        } else {
            pendingFocusId = nodeId
            expandNode(nodeId)
        }
    }

    private fun requestCenter(nodeId: String) {
        _centerRequest.value = KgCenterRequest(nodeId, _centerRequest.value.key + 1)
    }

    private fun loadSessionTurns(sessionId: String) {
        val generation = ++sessionTurnsGeneration
        sessionTurnsJob?.cancel()
        sessionTurnsJob = viewModelScope.launch {
            try {
                val turns = graphStore.getSessionTurns(sessionId)
                if (generation != sessionTurnsGeneration) return@launch
                _sessionTurns.value = turns
                if (turns.none { it.messageId == _selectedTurnId.value }) _selectedTurnId.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation != sessionTurnsGeneration) return@launch
                // 轮次条只是辅助导航，读取失败时隐藏，图谱本身照常显示。
                InfinityLogger.logError("[KG] getSessionTurns failed", failure)
                _sessionTurns.value = emptyList()
                _selectedTurnId.value = null
            }
        }
    }

    /** 切换范围时清掉只对旧范围有意义的选中、轮次与类型筛选。 */
    private fun clearScopeFocus() {
        selectEntity(null)
        pendingFocusId = null
        _typeFilter.value = emptySet()
        _selectedTurnId.value = null
        sessionTurnsGeneration++
        sessionTurnsJob?.cancel()
        _sessionTurns.value = emptyList()
    }

    fun loadDocumentOptions() {
        val generation = ++documentOptionsGeneration
        documentOptionsJob?.cancel()
        documentOptionsJob = viewModelScope.launch {
            try {
                val options = graphStore.getDocumentOptions()
                if (generation != documentOptionsGeneration) return@launch
                _documentOptions.value = options
                _documentOptionsError.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation != documentOptionsGeneration) return@launch
                InfinityLogger.logError("[KG] loadDocumentOptions failed", failure)
                _documentOptionsError.value = KgDocumentOptionsError(
                    code = KgDocumentOptionsErrorCode.LoadFailed,
                    canRetry = true,
                    technical = failure::class.simpleName?.take(80),
                )
            }
        }
    }

    fun retryDocumentOptions() = loadDocumentOptions()

    fun retrySessionOptions() = loadSessionOptions()

    fun setViewMode(mode: KgViewMode) {
        clearSearch()
        clearScopeFocus()
        _viewMode.value = mode
        _loadError.value = null
        _documentSelectionRequired.value = false
        _sessionSelectionRequired.value = false
        val scope = currentScope()
        if (scope == null) {
            // 文档与会话视角在选定前不复用全局图，明确要求选择。
            if (mode == KgViewMode.DOCUMENT) _documentSelectionRequired.value = true
            if (mode == KgViewMode.SESSION) _sessionSelectionRequired.value = true
            cancelPendingLoad()
            _nodes.value = emptyList()
            _edges.value = emptyList()
            _totalNodeCount.value = 0
            lastSuccessfulScopeKey = null
            _graphState.value = KgGraphState()
            return
        }
        loadGraphInternal(scope = scope, preserveSameScope = false)
        if (scope is KgScope.Session) loadSessionTurns(scope.sessionId)
    }

    fun loadGraph() {
        clearSearch()
        clearScopeFocus()
        _viewMode.value = KgViewMode.GLOBAL
        _documentSelectionRequired.value = false
        _sessionSelectionRequired.value = false
        _selectedCollectionId.value?.let { loadGraphInternal(scope = KgScope.Collection(it, _asOfOrdinal.value), preserveSameScope = false) }
            ?: loadCollectionOptions(loadDefault = true)
    }

    fun loadGraphByDoc(docId: String) {
        require(docId.isNotBlank()) { "文档标识不能为空" }
        clearSearch()
        clearScopeFocus()
        _viewMode.value = KgViewMode.DOCUMENT
        _selectedDocumentId.value = docId
        _documentSelectionRequired.value = false
        _sessionSelectionRequired.value = false
        _loadError.value = null
        loadGraphInternal(scope = KgScope.Document(docId), preserveSameScope = false)
    }

    fun retryLoad() {
        reloadCurrentScope(preserveSameScope = _graphState.value.isStale)
    }

    fun refreshCurrentScope() {
        reloadCurrentScope(preserveSameScope = true)
        if (_viewMode.value == KgViewMode.SESSION) loadSessionOptions()
        if (_viewMode.value == KgViewMode.GLOBAL) {
            loadCollectionOptions()
            _selectedCollectionId.value?.let(::loadTimelineTicks)
        }
    }

    fun searchEntities(query: String) {
        val generation = ++searchGeneration
        searchJob?.cancel()
        _searchQuery.value = query.take(512)
        _searchFailed.value = false
        _searchResults.value = emptyList()
        val scope = currentScope()
        if (query.isBlank() || scope == null) {
            _isSearching.value = false
            return
        }
        val expectedScope = _graphState.value.scopeKey
        val request = GraphEntitySearchRequest(
            query = _searchQuery.value,
            docId = (scope as? KgScope.Document)?.docId,
            sessionId = (scope as? KgScope.Session)?.sessionId,
            collectionId = (scope as? KgScope.Collection)?.collectionId,
        )
        _isSearching.value = true
        searchJob = viewModelScope.launch {
            try {
                delay(200)
                val found = graphStore.searchGraphEntities(request)
                if (generation == searchGeneration && expectedScope == _graphState.value.scopeKey) _searchResults.value = found
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == searchGeneration) _searchFailed.value = true
                InfinityLogger.logError("[KG] 实体搜索失败", failure)
            } finally {
                if (generation == searchGeneration) _isSearching.value = false
            }
        }
    }

    fun expandNode(nodeId: String) {
        val scope = currentScope() ?: return
        if (_isLoading.value || _nodes.value.size >= MAX_GRAPH_NODE_LIMIT) return
        loadGraphInternal(
            scope = scope,
            preserveSameScope = true,
            request = GraphSubgraphRequest(
                docId = (scope as? KgScope.Document)?.docId,
                sessionId = (scope as? KgScope.Session)?.sessionId,
                collectionId = (scope as? KgScope.Collection)?.collectionId,
                asOf = (scope as? KgScope.Collection)?.asOfRequest(),
                nodeLimit = (_nodes.value.size + GRAPH_EXPANSION_BATCH_SIZE + 1).coerceAtMost(MAX_GRAPH_NODE_LIMIT),
                edgeLimit = maxOf(DEFAULT_GRAPH_EDGE_LIMIT, (_nodes.value.size + GRAPH_EXPANSION_BATCH_SIZE) * 4).coerceAtMost(MAX_GRAPH_EDGE_LIMIT),
                retainedNodeIds = _nodes.value.map { it.id },
                expandNodeId = nodeId,
            ),
        )
    }

    private fun clearSearch() {
        searchGeneration++
        searchJob?.cancel()
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _isSearching.value = false
        _searchFailed.value = false
    }

    /** 当前视角对应的查询范围；文档或会话未选定时为 null。 */
    private fun currentScope(): KgScope? = when (_viewMode.value) {
        KgViewMode.GLOBAL -> _selectedCollectionId.value?.let { KgScope.Collection(it, _asOfOrdinal.value) }
        KgViewMode.DOCUMENT -> _selectedDocumentId.value?.let(KgScope::Document)
        KgViewMode.SESSION -> _selectedSessionId.value?.let(KgScope::Session)
    }

    private fun reloadCurrentScope(preserveSameScope: Boolean) {
        val scope = currentScope()
        if (scope == null) {
            _documentSelectionRequired.value = _viewMode.value == KgViewMode.DOCUMENT
            _sessionSelectionRequired.value = _viewMode.value == KgViewMode.SESSION
            return
        }
        loadGraphInternal(scope = scope, preserveSameScope = preserveSameScope)
        if (scope is KgScope.Session) loadSessionTurns(scope.sessionId)
    }

    private fun loadGraphInternal(
        scope: KgScope,
        preserveSameScope: Boolean,
        request: GraphSubgraphRequest? = null,
    ) {
        val scopeKey = scope.key
        val preserveLastGood = preserveSameScope &&
            _graphState.value.scopeKey == scopeKey &&
            lastSuccessfulScopeKey == scopeKey
        val generation = ++loadGeneration
        loadJob?.cancel()
        if (!preserveLastGood) {
            _nodes.value = emptyList()
            _edges.value = emptyList()
            _totalNodeCount.value = 0
            lastSuccessfulScopeKey = null
        }
        _loadError.value = null
        _isLoading.value = true
        _graphState.value = KgGraphState(
            scopeKey = scopeKey,
            isLoading = true,
            isStale = false,
        )
        loadJob = viewModelScope.launch {
            try {
                val result = graphStore.getGraphSubgraph(request ?: scope.request())
                val data = result.data
                if (generation != loadGeneration) return@launch
                InfinityLogger.log("[KG] graphStore returned: ${data.nodes.size} nodes, ${data.edges.size} edges (scope=$scopeKey)")
                if (data.nodes.isEmpty() && data.edges.isEmpty()) {
                    InfinityLogger.log("[KG] WARNING: No graph data available — KG extraction may not have been completed, or all extractions failed")
                }
                _loadError.value = null
                render(data)
                _totalNodeCount.value = result.totalNodeCount
                lastSuccessfulScopeKey = scopeKey
                _graphState.value = KgGraphState(scopeKey = scopeKey)
                applyPendingFocus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                InfinityLogger.logError("[KG] loadGraphInternal failed", e)
                pendingFocusId = null
                _loadError.value = KgLoadError(
                    code = KgLoadErrorCode.LoadFailed,
                    canRetry = true,
                    technical = e::class.simpleName?.take(80),
                )
                _graphState.value = KgGraphState(
                    scopeKey = scopeKey,
                    isLoading = false,
                    isStale = preserveLastGood,
                )
            } finally {
                if (generation == loadGeneration) {
                    _isLoading.value = false
                    if (_graphState.value.isLoading) {
                        _graphState.value = _graphState.value.copy(isLoading = false)
                    }
                }
            }
        }
    }

    private fun applyPendingFocus() {
        val nodeId = pendingFocusId ?: return
        pendingFocusId = null
        if (_nodes.value.none { it.id == nodeId }) return
        if (_selectedEntity.value?.nodeId != nodeId) selectEntity(nodeId)
        requestCenter(nodeId)
    }

    private fun cancelPendingLoad() {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        _isLoading.value = false
        _graphState.value = _graphState.value.copy(isLoading = false)
    }

    private fun render(data: GraphData) {
        // GraphStore 已按存活证据筛选；同名不同类型必须保留独立实体 ID。
        val mappedNodes = data.nodes.distinctBy { it.id }.map { node ->
            GraphNode(
                id = node.id,
                label = node.name,
                type = node.type,
                x = 0f,
                y = 0f,
                icon = KgEntityKind.of(node.type).icon,
            )
        }
        val ids = mappedNodes.map { it.id }.toSet()
        val mappedEdges = data.edges.filter { it.sourceId in ids && it.targetId in ids }.map { edge ->
            GraphEdge(
                sourceId = edge.sourceId,
                targetId = edge.targetId,
                relation = edge.relation
            )
        }

        _nodes.value = mappedNodes
        _edges.value = mappedEdges
        // 展开或刷新后选中实体可能已不在子图中。
        _selectedEntity.value?.let { selected -> if (selected.nodeId !in ids) selectEntity(null) }
        InfinityLogger.log("[KG] render success: mapped ${mappedNodes.size} nodes, ${mappedEdges.size} edges")
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as InfinityApplication
                    val repo = KnowledgeGraphRepository(
                        kgNodeDao = app.database.kgNodeDao(),
                        kgEdgeDao = app.database.kgEdgeDao()
                    )
                    return KnowledgeGraphViewModel(repo, app.graphStore, application) as T
                }
            }
    }
}

/** 图谱查询范围；scope key 用来判断加载结果与缓存是否属于当前范围。 */
private sealed interface KgScope {
    val key: String
    fun request(): GraphSubgraphRequest

    /** 一张图谱（作品或通用知识）；图谱之间实体互不合并，不再提供跨图谱的全局视图。 */
    data class Collection(val collectionId: String, val asOfOrdinal: Int? = null) : KgScope {
        override val key get() = "collection:$collectionId" + (asOfOrdinal?.let { "@$it" } ?: "")
        override fun request() = GraphSubgraphRequest(collectionId = collectionId, asOf = asOfRequest())
        fun asOfRequest() = asOfOrdinal?.let { com.promenar.infinity.data.rag.KgAsOf.Ordinal(it) }
    }

    data class Document(val docId: String) : KgScope {
        override val key get() = "document:$docId"
        override fun request() = GraphSubgraphRequest(docId = docId)
    }

    data class Session(val sessionId: String) : KgScope {
        override val key get() = "session:$sessionId"
        override fun request() = GraphSubgraphRequest(sessionId = sessionId)
    }
}
