package com.promenar.infinity.ui.hub

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.agent.AgentRetrievalMode
import com.promenar.infinity.data.agent.AgentSubagentModelPolicy
import com.promenar.infinity.data.agent.KnowledgeFileAccess
import com.promenar.infinity.data.agent.PresetAgents
import com.promenar.infinity.data.local.db.entity.McpServerEntity
import com.promenar.infinity.data.model.params.SamplingValues
import com.promenar.infinity.data.repository.AgentRepository
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.model.AgentField
import com.promenar.infinity.domain.model.AgentFieldUpdate
import com.promenar.infinity.domain.model.AgentPromptRevision
import com.promenar.infinity.domain.model.ExecutionMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AgentEditErrorCode { NOT_FOUND, LOAD_FAILED, SAVE_FAILED, DELETE_FAILED, AVATAR_IMPORT_FAILED }

/** 同一字段本地与智能体都改了且取值不同；[remote] 为数据库现值，界面据此显示对方的内容。 */
data class AgentEditConflict(val fields: Set<AgentField>, val remote: Agent)

/** 打开提示词编辑器期间智能体改了提示词；[remotePrompt] 为数据库现值。 */
data class AgentPromptConflict(val remotePrompt: String)

/** 冲突处理与历史恢复后的一次性提示。 */
enum class AgentEditNotice { ADOPTED_REMOTE_PROMPT, KEPT_MY_PROMPT, ADOPTED_REMOTE, KEPT_MINE, RESTORED }

/** 编辑器保存时发现提示词已被智能体修改；界面改为显示冲突提示条，不显示通用保存失败。 */
class AgentPromptSaveConflictException : com.promenar.infinity.ui.common.PromptSaveConflictException("提示词已被智能体修改")

class AgentEditViewModel(
    private val agentRepository: AgentRepository,
    private val avatarImporter: suspend (Uri, String) -> String? = { _, _ -> null },
    private val avatarCleaner: (Uri) -> Unit = {},
    /** 已登记的自选工作区，用于显示默认工作区的名称与路径。 */
    workspaces: Flow<List<WorkspaceEntity>> = flowOf(emptyList()),
    /** 按模型保存的参数方案；为 null 时不提供方案。 */
    private val parameterSchemeRepository: com.promenar.infinity.data.repository.ParameterSchemeRepository? = null,
    private val deleteAgentUseCase: com.promenar.infinity.domain.usecase.DeleteAgentUseCase? = null,
    /** 全局 MCP 服务器，供“新会话默认”选择。 */
    mcpServers: Flow<List<McpServerEntity>> = flowOf(emptyList()),
    /** 该智能体当前的会话数，删除确认据此写明将关闭几个会话。 */
    private val countAgentSessions: suspend (String) -> Int = { 0 },
) : ViewModel() {

    private val _initialAgent = MutableStateFlow<Agent?>(null)
    private val _initialDisplayName = MutableStateFlow("")
    private val _initialDisplayDescription = MutableStateFlow("")
    private val _name = MutableStateFlow("")
    private val _description = MutableStateFlow("")
    private val _systemPrompt = MutableStateFlow("")
    private val _selectedModel = MutableStateFlow("")
    private val _selectedColor = MutableStateFlow(Agent(id = "", name = "").color)
    private val _selectedIcon = MutableStateFlow("✨")
    private val _avatarPath = MutableStateFlow<String?>(null)
    private val _sampling = MutableStateFlow(SamplingValues())
    private val _reasoningEffort = MutableStateFlow<String?>(null)
    /** 本次编辑中最近选用的方案，只用于标出“已修改”，不落库。 */
    private val _baseSchemeId = MutableStateFlow<String?>(null)
    private val _isPinned = MutableStateFlow(false)
    /** 智能体检索配置：界面只编辑检索方式与知识库文件访问，其余旧字段原样保留、运行时不读取。 */
    private val _retrievalConfig = MutableStateFlow(AgentRetrievalConfig())
    private val _memoryPolicy = MutableStateFlow(AgentMemoryPolicy())
    private val _subagentModels = MutableStateFlow(AgentSubagentModelPolicy())
    private val _saveError = MutableStateFlow<AgentEditErrorCode?>(null)
    private val _defaultWorkspaceId = MutableStateFlow<String?>(null)
    private val _executionMode = MutableStateFlow(ExecutionMode.WORKSPACE)
    private val _toolsEnabledByDefault = MutableStateFlow(true)
    private val _mcpServerIds = MutableStateFlow<List<String>>(emptyList())
    private val _sessionCount = MutableStateFlow(0)
    private val _conflict = MutableStateFlow<AgentEditConflict?>(null)
    private val _promptConflict = MutableStateFlow<AgentPromptConflict?>(null)
    private val _refreshedFields = MutableStateFlow<Set<AgentField>>(emptySet())
    private val _notice = MutableStateFlow<AgentEditNotice?>(null)
    private val _agentId = MutableStateFlow<String?>(null)
    /** 预设智能体未自定义名称、描述时显示的本地化文本；远端刷新时沿用同一规则。 */
    private var localizedPresetName: String? = null
    private var localizedPresetDescription: String? = null
    /** 提示词编辑器打开时的内容；编辑期间远端提示词与它不同即为冲突。为空表示编辑器未打开。 */
    private var promptEditBase: String? = null
    /** 编辑页的写入串行执行，避免两次合并基于同一份基线。 */
    private val writeMutex = Mutex()
    private var remoteJob: Job? = null
    private var refreshFadeJob: Job? = null

    val name: StateFlow<String> = _name.asStateFlow()
    val description: StateFlow<String> = _description.asStateFlow()
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()
    val selectedColor: StateFlow<String> = _selectedColor.asStateFlow()
    val selectedIcon: StateFlow<String> = _selectedIcon.asStateFlow()
    val avatarPath: StateFlow<String?> = _avatarPath.asStateFlow()
    /** 智能体会话默认的采样参数；为空的项按模型默认。 */
    val sampling: StateFlow<SamplingValues> = _sampling.asStateFlow()
    val reasoningEffort: StateFlow<String?> = _reasoningEffort.asStateFlow()
    val baseSchemeId: StateFlow<String?> = _baseSchemeId.asStateFlow()

    /** 智能体当前模型的已存方案。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val parameterSchemes: StateFlow<List<com.promenar.infinity.data.model.params.ParameterScheme>> = _selectedModel
        .flatMapLatest { modelKey ->
            val repository = parameterSchemeRepository
            if (repository == null || modelKey.isBlank()) flowOf(emptyList()) else repository.observeForModel(modelKey)
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val isPinned: StateFlow<Boolean> = _isPinned.asStateFlow()
    val retrievalConfig: StateFlow<AgentRetrievalConfig> = _retrievalConfig.asStateFlow()
    val memoryPolicy: StateFlow<AgentMemoryPolicy> = _memoryPolicy.asStateFlow()
    /** 子代理默认模型与“允许智能体按任务换模型”；存放在检索配置 JSON 中，与记忆权限同一写法。 */
    val subagentModels: StateFlow<AgentSubagentModelPolicy> = _subagentModels.asStateFlow()
    val saveError: StateFlow<AgentEditErrorCode?> = _saveError.asStateFlow()
    val defaultWorkspaceId: StateFlow<String?> = _defaultWorkspaceId.asStateFlow()
    /** 新会话默认的执行模式与 MCP 服务器；只影响之后新建的会话。 */
    val executionMode: StateFlow<ExecutionMode> = _executionMode.asStateFlow()
    val toolsEnabledByDefault: StateFlow<Boolean> = _toolsEnabledByDefault.asStateFlow()
    val mcpServerIds: StateFlow<List<String>> = _mcpServerIds.asStateFlow()
    val sessionCount: StateFlow<Int> = _sessionCount.asStateFlow()
    /** 非提示词字段的冲突；为空时不显示提示条。 */
    val conflict: StateFlow<AgentEditConflict?> = _conflict.asStateFlow()
    /** 提示词编辑器内的冲突。 */
    val promptConflict: StateFlow<AgentPromptConflict?> = _promptConflict.asStateFlow()
    /** 刚被智能体静默刷新的字段，界面短暂高亮。 */
    val refreshedFields: StateFlow<Set<AgentField>> = _refreshedFields.asStateFlow()
    val notice: StateFlow<AgentEditNotice?> = _notice.asStateFlow()

    /** 提示词历史版本，最新在前。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val promptRevisions: StateFlow<List<AgentPromptRevision>> = _agentId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else agentRepository.observePromptRevisions(id) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val mcpServers: StateFlow<List<McpServerEntity>> = mcpServers
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** 默认工作区的登记记录；id 已设置但登记被删除时为 null，界面显示“已删除的工作区”。 */
    val defaultWorkspace: StateFlow<WorkspaceEntity?> = combine(_defaultWorkspaceId, workspaces) { id, all ->
        id?.let { wanted -> all.firstOrNull { it.id == wanted } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val hasChanges: StateFlow<Boolean> = combine(
        combine(
            _initialAgent,
            _name,
            _description,
            _initialDisplayName,
            _initialDisplayDescription,
        ) { initial, n, d, baselineName, baselineDescription ->
            TextEditState(initial, n, d, baselineName, baselineDescription)
        },
        combine(_systemPrompt, _selectedModel, _selectedColor) { sp, sm, sc -> Triple(sp, sm, sc) },
        combine(_selectedIcon, _avatarPath, _isPinned) { si, ap, pin -> Triple(si, ap, pin) },
        combine(_sampling, _reasoningEffort, _subagentModels) { values, effort, subagents -> Triple(values, effort, subagents) },
        combine(_retrievalConfig, _defaultWorkspaceId, _memoryPolicy, combine(_executionMode, _toolsEnabledByDefault, ::Pair), _mcpServerIds) { ret, ws, policy, (mode, tools), mcp ->
            DefaultsEditState(ret, ws, policy, mode, tools, mcp)
        }
    ) { text, (prompt, model, color), (icon, path, pin), (values, effort, subagents), defaults ->
        val initial = text.initial
        initial == null || text.baselineName != text.name || text.baselineDescription != text.description ||
        initial.defaultWorkspaceId != defaults.defaultWorkspaceId ||
        initial.systemPrompt != prompt || initial.modelId != model ||
        initial.color != color || initial.icon != icon || initial.avatarPath != path || initial.isPinned != pin ||
        initial.memoryPolicy != defaults.memoryPolicy || initial.subagentModelPolicy != subagents ||
        initial.executionMode != defaults.executionMode || initial.mcpServerIds != defaults.mcpServerIds ||
        initial.toolsEnabledByDefault != defaults.toolsEnabledByDefault ||
        (initial.retrievalConfig?.retrievalMode ?: AgentRetrievalMode.AUTO) != defaults.retrievalConfig.retrievalMode ||
        (initial.retrievalConfig?.knowledgeFileAccess ?: KnowledgeFileAccess.ON_REQUEST) != defaults.retrievalConfig.knowledgeFileAccess ||
        initial.samplingValues() != values || initial.reasoningEffort != effort
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var saveJob: Job? = null
    private var avatarImportJob: Job? = null
    private var retryAction: (() -> Unit)? = null

    fun loadAgent(
        agentId: String,
        localizedPresetName: String? = null,
        localizedPresetDescription: String? = null,
    ) {
        this.localizedPresetName = localizedPresetName
        this.localizedPresetDescription = localizedPresetDescription
        viewModelScope.launch {
            try {
                val agent = agentRepository.observeById(agentId).first()
                if (agent != null) {
                val displayName = displayName(agent)
                val displayDescription = displayDescription(agent)
                _initialAgent.value = agent
                _initialDisplayName.value = displayName
                _initialDisplayDescription.value = displayDescription
                _name.value = displayName
                _description.value = displayDescription
                _systemPrompt.value = agent.systemPrompt
                _selectedModel.value = agent.modelId
                _selectedColor.value = agent.color
                _selectedIcon.value = agent.icon
                _avatarPath.value = agent.avatarPath
                _isPinned.value = agent.isPinned
                _sampling.value = agent.samplingValues()
                _reasoningEffort.value = agent.reasoningEffort
                _defaultWorkspaceId.value = agent.defaultWorkspaceId
                _memoryPolicy.value = agent.memoryPolicy
                _subagentModels.value = agent.subagentModelPolicy
                _retrievalConfig.value = agent.retrievalConfig ?: AgentRetrievalConfig()
                _executionMode.value = agent.executionMode
                _toolsEnabledByDefault.value = agent.toolsEnabledByDefault
                _mcpServerIds.value = agent.mcpServerIds
                _agentId.value = agent.id
                refreshSessionCount()
                observeRemote(agent.id)
                _saveError.value = null
                retryAction = null
                } else {
                    _saveError.value = AgentEditErrorCode.NOT_FOUND
                    retryAction = { loadAgent(agentId, localizedPresetName, localizedPresetDescription) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _saveError.value = AgentEditErrorCode.LOAD_FAILED
                retryAction = { loadAgent(agentId, localizedPresetName, localizedPresetDescription) }
            }
        }
    }

    fun setName(value: String) {
        _name.value = value
        scheduleSave()
    }

    fun setDescription(value: String) {
        _description.value = value
        scheduleSave()
    }

    fun setSystemPrompt(value: String) {
        _systemPrompt.value = value
        scheduleSave()
    }

    fun setModel(value: String) {
        _selectedModel.value = value
        scheduleSave()
    }

    fun setColor(value: String) {
        _selectedColor.value = value
        scheduleSave()
    }

    fun setIcon(value: String) {
        _selectedIcon.value = value
        _avatarPath.value = null
        val agentId = _initialAgent.value?.id ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            // 头像选择是一次明确提交；离开页面不能像普通文本防抖一样取消它。
            withContext(NonCancellable) {
                persistCurrentAgent(agentId)
            }
        }
    }

    fun setAvatarPath(value: String?) {
        _avatarPath.value = value
        scheduleSave()
    }

    fun importAvatar(uri: Uri) {
        val agentId = _initialAgent.value?.id ?: return
        avatarImportJob?.cancel()
        avatarImportJob = viewModelScope.launch {
            val saved = try {
                avatarImporter(uri, "agent-$agentId")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (saved == null) {
                _saveError.value = AgentEditErrorCode.AVATAR_IMPORT_FAILED
                retryAction = { importAvatar(uri) }
                return@launch
            }
            val candidate = buildAgent(agentId).copy(avatarPath = saved)
            val persisted = persistCandidate(candidate) {
                _avatarPath.value = saved
            }
            if (persisted.isSuccess) {
                runCatching { avatarCleaner(uri) }
            } else {
                retryAction = { importAvatar(uri) }
            }
        }
    }

    fun reportAvatarImportFailure() {
        _saveError.value = AgentEditErrorCode.AVATAR_IMPORT_FAILED
        retryAction = null
    }

    fun setSampling(values: SamplingValues) {
        _sampling.value = values
        scheduleSave()
    }

    fun applyModelDefaultSampling() {
        _baseSchemeId.value = com.promenar.infinity.data.model.params.MODEL_DEFAULT_SCHEME_ID
        setSampling(SamplingValues())
    }

    fun applyParameterScheme(scheme: com.promenar.infinity.data.model.params.ParameterScheme) {
        _baseSchemeId.value = scheme.id
        setSampling(scheme.values)
    }

    fun setSamplingParam(param: com.promenar.infinity.data.model.params.SamplingParam, value: Double?) =
        setSampling(_sampling.value.with(param, value))

    fun saveParameterScheme(name: String, values: SamplingValues) {
        val repository = parameterSchemeRepository ?: return
        val modelKey = _selectedModel.value.takeIf(String::isNotBlank) ?: return
        viewModelScope.launch {
            val scheme = repository.create(modelKey, name, values)
            _baseSchemeId.value = scheme.id
            setSampling(values)
        }
    }

    fun overwriteParameterScheme(scheme: com.promenar.infinity.data.model.params.ParameterScheme, values: SamplingValues) {
        val repository = parameterSchemeRepository ?: return
        viewModelScope.launch {
            if (repository.overwrite(scheme.id, values)) {
                _baseSchemeId.value = scheme.id
                setSampling(values)
            }
        }
    }

    fun renameParameterScheme(scheme: com.promenar.infinity.data.model.params.ParameterScheme, name: String) {
        val repository = parameterSchemeRepository ?: return
        viewModelScope.launch { repository.rename(scheme.id, name) }
    }

    fun deleteParameterScheme(scheme: com.promenar.infinity.data.model.params.ParameterScheme) {
        val repository = parameterSchemeRepository ?: return
        viewModelScope.launch { repository.delete(scheme.id) }
    }

    fun setReasoningEffort(value: String?) {
        _reasoningEffort.value = value
        scheduleSave()
    }

    fun setPinned(value: Boolean) {
        _isPinned.value = value
        scheduleSave()
    }

    /** 检索方式：明确提交，立即持久化。与知识库文件访问互不联动。 */
    fun setRetrievalMode(mode: AgentRetrievalMode) {
        _retrievalConfig.update { it.copy(retrievalMode = mode) }
        commitNow()
    }

    fun setKnowledgeFileAccess(access: KnowledgeFileAccess) {
        _retrievalConfig.update { it.copy(knowledgeFileAccess = access) }
        commitNow()
    }

    /** 新会话默认的执行模式：明确提交，立即持久化，只影响之后新建的会话。 */
    fun setExecutionMode(mode: ExecutionMode) {
        _executionMode.value = mode
        commitNow()
    }

    /** 新会话默认是否开启工具：明确提交，立即持久化，只影响之后新建的会话。 */
    fun setToolsEnabledByDefault(enabled: Boolean) {
        _toolsEnabledByDefault.value = enabled
        commitNow()
    }

    /** 新会话默认连接的 MCP 服务器；保留原有顺序，只增删给出的一项。 */
    fun setMcpServerEnabled(serverId: String, enabled: Boolean) {
        _mcpServerIds.update { ids -> if (enabled) (ids + serverId).distinct() else ids - serverId }
        commitNow()
    }

    /** 删除确认打开前刷新会话数，避免显示进入编辑页之后新建或删除的会话。 */
    fun refreshSessionCount() {
        val agentId = _initialAgent.value?.id ?: return
        viewModelScope.launch {
            _sessionCount.value = try {
                countAgentSessions(agentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _sessionCount.value
            }
        }
    }

    /** 记忆权限明确提交并立即持久化；上限调低时默认档位随之收紧。只影响之后的检索与新建会话的默认值。 */
    fun updateMemoryPolicy(
        transform: (AgentMemoryPolicy) -> AgentMemoryPolicy,
    ) {
        _memoryPolicy.update { transform(it).normalized() }
        commitNow()
    }

    /** 子代理模型设置明确提交并立即持久化；只影响之后启动的子代理，恢复运行的子代理沿用原模型。 */
    fun updateSubagentModels(transform: (AgentSubagentModelPolicy) -> AgentSubagentModelPolicy) {
        _subagentModels.update { transform(it).let { next -> next.copy(selectableModelIds = next.selectableModelIds.distinct()) } }
        commitNow()
    }

    /** 设置或清除（null）默认工作区：明确提交，立即持久化，离开页面也不取消。只影响之后新建的会话。 */
    fun setDefaultWorkspace(workspaceId: String?) {
        _defaultWorkspaceId.value = workspaceId
        commitNow()
    }

    fun deleteAgent(agentId: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                if (deleteAgentUseCase != null) deleteAgentUseCase.invoke(agentId) else agentRepository.delete(agentId)
                _saveError.value = null
                retryAction = null
                onDeleted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _saveError.value = AgentEditErrorCode.DELETE_FAILED
                retryAction = { deleteAgent(agentId, onDeleted) }
            }
        }
    }

    fun retryLastFailure() {
        retryAction?.invoke()
    }

    fun saveAgent(agentId: String) {
        viewModelScope.launch { persistCurrentAgent(agentId) }
    }

    /**
     * 提示词编辑器保存：等待 Room 持久化成功，不能把延迟自动保存视为成功。
     * 编辑期间智能体改了提示词时不覆盖，返回 [AgentPromptSaveConflictException] 并显示冲突提示条。
     */
    suspend fun saveSystemPrompt(value: String): Result<Unit> {
        saveJob?.cancelAndJoin()
        val initial = _initialAgent.value
            ?: return Result.failure(IllegalStateException("Agent 尚未加载"))
        val editBase = promptEditBase ?: initial.systemPrompt
        return writePrompt(initial.id, value, expectedPrompt = editBase)
    }

    /** 打开提示词编辑器：记下编辑起点，编辑期间远端改动据此判定冲突。返回编辑器的初始内容。 */
    fun beginPromptEdit(): String {
        val text = _systemPrompt.value
        promptEditBase = text
        _promptConflict.value = null
        return text
    }

    fun endPromptEdit() {
        promptEditBase = null
        _promptConflict.value = null
    }

    /**
     * 冲突时采用智能体的提示词：编辑器换成新内容，未保存的草稿存为历史版本（来源“我”）。返回编辑器应显示的内容。
     */
    suspend fun adoptRemotePrompt(draft: String): String? {
        val agentId = _initialAgent.value?.id ?: return null
        val remote = _promptConflict.value?.remotePrompt ?: return null
        try {
            if (draft != promptEditBase && draft != remote) {
                agentRepository.recordPromptRevision(agentId, draft, AgentPromptRevision.Source.USER)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _saveError.value = AgentEditErrorCode.SAVE_FAILED
            retryAction = null
            return null
        }
        promptEditBase = remote
        _promptConflict.value = null
        _notice.value = AgentEditNotice.ADOPTED_REMOTE_PROMPT
        return remote
    }

    /** 冲突时保留自己的提示词并保存；智能体的版本留在历史中（工具写入时已记录，这里补记以防缺失）。 */
    suspend fun keepMyPrompt(draft: String): Result<Unit> {
        val agentId = _initialAgent.value?.id ?: return Result.failure(IllegalStateException("Agent 尚未加载"))
        val remote = _promptConflict.value?.remotePrompt
        try {
            if (remote != null) agentRepository.recordPromptRevision(agentId, remote, AgentPromptRevision.Source.AGENT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 补记失败不阻止保存：工具写入时已记录过智能体的版本。
        }
        val result = writePrompt(agentId, draft, expectedPrompt = null)
        if (result.isSuccess) _notice.value = AgentEditNotice.KEPT_MY_PROMPT
        return result
    }

    /** 把提示词恢复为某个历史版本；恢复本身也记一版，从各会话下一轮生效。 */
    fun restorePromptRevision(revisionId: String) {
        val agentId = _initialAgent.value?.id ?: return
        viewModelScope.launch {
            writeMutex.withLock {
                try {
                    when (val result = agentRepository.restorePromptRevision(agentId, revisionId)) {
                        is AgentFieldUpdate.Updated -> {
                            settlePromptWrite(result.agent)
                            _notice.value = AgentEditNotice.RESTORED
                            _saveError.value = null
                            retryAction = null
                        }
                        AgentFieldUpdate.NotFound, is AgentFieldUpdate.Conflict -> {
                            _saveError.value = AgentEditErrorCode.SAVE_FAILED
                            retryAction = { restorePromptRevision(revisionId) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _saveError.value = AgentEditErrorCode.SAVE_FAILED
                    retryAction = { restorePromptRevision(revisionId) }
                }
            }
        }
    }

    /** 冲突提示条：采用智能体写入的值。 */
    fun adoptRemoteConflict() {
        val conflict = _conflict.value ?: return
        val base = _initialAgent.value ?: return
        _initialAgent.value = conflict.fields.fold(base) { agent, field -> field.copy(agent, conflict.remote) }
        applyToLocal(conflict.fields, conflict.remote)
        _conflict.value = null
        _notice.value = AgentEditNotice.ADOPTED_REMOTE
    }

    /** 冲突提示条：保留自己的值并立即写入。 */
    fun keepMineConflict() {
        val conflict = _conflict.value ?: return
        val agentId = _initialAgent.value?.id ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            withContext(NonCancellable) {
                val local = buildAgent(agentId)
                val written = writeMutex.withLock {
                    try {
                        agentRepository.updateFields(agentId, conflict.fields, local)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
                if (written is AgentFieldUpdate.Updated) {
                    val base = _initialAgent.value ?: written.agent
                    _initialAgent.value = conflict.fields.fold(base) { agent, field -> field.copy(agent, written.agent) }
                    if (AgentField.NAME in conflict.fields) _initialDisplayName.value = _name.value
                    if (AgentField.DESCRIPTION in conflict.fields) _initialDisplayDescription.value = _description.value
                    _conflict.value = null
                    _notice.value = AgentEditNotice.KEPT_MINE
                    _saveError.value = null
                    retryAction = null
                } else {
                    _saveError.value = AgentEditErrorCode.SAVE_FAILED
                    retryAction = { keepMineConflict() }
                }
            }
        }
    }

    fun consumeNotice() {
        _notice.value = null
    }

    private suspend fun writePrompt(agentId: String, value: String, expectedPrompt: String?): Result<Unit> = writeMutex.withLock {
        try {
            val current = agentRepository.getById(agentId) ?: run {
                _saveError.value = AgentEditErrorCode.NOT_FOUND
                return@withLock Result.failure(IllegalStateException("Agent 不存在"))
            }
            if (expectedPrompt != null && current.systemPrompt != expectedPrompt && current.systemPrompt != value) {
                _promptConflict.value = AgentPromptConflict(current.systemPrompt)
                return@withLock Result.failure(AgentPromptSaveConflictException())
            }
            val values = current.copy(systemPrompt = value)
            when (val result = agentRepository.updateFields(agentId, setOf(AgentField.SYSTEM_PROMPT), values, expected = current)) {
                is AgentFieldUpdate.Updated -> {
                    settlePromptWrite(result.agent)
                    promptEditBase = value
                    _promptConflict.value = null
                    _saveError.value = null
                    retryAction = null
                    Result.success(Unit)
                }
                is AgentFieldUpdate.Conflict -> {
                    _promptConflict.value = AgentPromptConflict(result.current.systemPrompt)
                    Result.failure(AgentPromptSaveConflictException())
                }
                AgentFieldUpdate.NotFound -> {
                    _saveError.value = AgentEditErrorCode.NOT_FOUND
                    Result.failure(IllegalStateException("Agent 不存在"))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _saveError.value = AgentEditErrorCode.SAVE_FAILED
            retryAction = null
            Result.failure(error)
        }
    }

    private fun settlePromptWrite(written: Agent) {
        _systemPrompt.value = written.systemPrompt
        _initialAgent.value = _initialAgent.value?.let { AgentField.SYSTEM_PROMPT.copy(it, written) } ?: written
    }

    private suspend fun persistCurrentAgent(agentId: String): Result<Unit> =
        persistCandidate(buildAgent(agentId))

    private fun displayName(agent: Agent): String =
        if (PresetAgents.isPreset(agent.id) && !agent.nameCustomized) localizedPresetName ?: agent.name else agent.name

    private fun displayDescription(agent: Agent): String =
        if (PresetAgents.isPreset(agent.id) && !agent.descriptionCustomized) localizedPresetDescription ?: agent.description
        else agent.description

    /** 订阅数据库：智能体改了本地没在改的字段时静默刷新，两边都改的字段提示冲突。 */
    private fun observeRemote(agentId: String) {
        remoteJob?.cancel()
        remoteJob = viewModelScope.launch {
            try {
                agentRepository.observeById(agentId).collect { remote -> if (remote != null) onRemote(remote) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 订阅失败只影响实时刷新；保存时仍会按数据库现值合并。
            }
        }
    }

    private fun onRemote(remote: Agent) {
        val base = _initialAgent.value ?: return
        if (base.id != remote.id || base == remote) return
        val local = buildAgent(remote.id)
        val merge = mergeAgentEdit(base, local, remote)
        // 两边已一致的字段直接推进基线。
        val converged = AgentField.changed(base, remote) - merge.remoteOnly - merge.conflicts
        _initialAgent.value = (merge.remoteOnly + converged).fold(base) { agent, field -> field.copy(agent, remote) }
        applyToLocal(merge.remoteOnly, remote)
        flashRefreshed(merge.remoteOnly)
        if (merge.conflicts.isNotEmpty()) _conflict.value = AgentEditConflict(merge.conflicts, remote)
        promptEditBase?.let { editing ->
            if (remote.systemPrompt != editing) _promptConflict.value = AgentPromptConflict(remote.systemPrompt)
        }
    }

    private fun flashRefreshed(fields: Set<AgentField>) {
        if (fields.isEmpty()) return
        _refreshedFields.value = fields
        refreshFadeJob?.cancel()
        refreshFadeJob = viewModelScope.launch {
            delay(REFRESH_HIGHLIGHT_MILLIS)
            _refreshedFields.value = emptySet()
        }
    }

    /** 把 [remote] 中 [fields] 的值写回界面状态；采样参数逐项写入，不动其它参数的本地改动。 */
    private fun applyToLocal(fields: Set<AgentField>, remote: Agent) {
        fields.forEach { field ->
            when (field) {
                AgentField.NAME -> displayName(remote).let { _name.value = it; _initialDisplayName.value = it }
                AgentField.DESCRIPTION -> displayDescription(remote).let { _description.value = it; _initialDisplayDescription.value = it }
                AgentField.SYSTEM_PROMPT -> _systemPrompt.value = remote.systemPrompt
                AgentField.MODEL_ID -> _selectedModel.value = remote.modelId
                AgentField.TEMPERATURE -> _sampling.update { it.copy(temperature = remote.temperature) }
                AgentField.TOP_P -> _sampling.update { it.copy(topP = remote.topP) }
                AgentField.MAX_TOKENS -> _sampling.update { it.copy(maxTokens = remote.maxTokens) }
                AgentField.TOP_K -> _sampling.update { it.copy(topK = remote.topK) }
                AgentField.FREQUENCY_PENALTY -> _sampling.update { it.copy(frequencyPenalty = remote.frequencyPenalty) }
                AgentField.PRESENCE_PENALTY -> _sampling.update { it.copy(presencePenalty = remote.presencePenalty) }
                AgentField.REPETITION_PENALTY -> _sampling.update { it.copy(repetitionPenalty = remote.repetitionPenalty) }
                AgentField.REASONING_EFFORT -> _reasoningEffort.value = remote.reasoningEffort
                AgentField.MCP_SERVER_IDS -> _mcpServerIds.value = remote.mcpServerIds
                AgentField.DEFAULT_WORKSPACE_ID -> _defaultWorkspaceId.value = remote.defaultWorkspaceId
                AgentField.TOOLS_ENABLED_BY_DEFAULT -> _toolsEnabledByDefault.value = remote.toolsEnabledByDefault
                AgentField.ICON -> _selectedIcon.value = remote.icon
                AgentField.COLOR -> _selectedColor.value = remote.color
                AgentField.AVATAR_PATH -> _avatarPath.value = remote.avatarPath
                AgentField.IS_PINNED -> _isPinned.value = remote.isPinned
                AgentField.RETRIEVAL_CONFIG -> {
                    _retrievalConfig.value = remote.retrievalConfig ?: AgentRetrievalConfig()
                    _memoryPolicy.value = remote.memoryPolicy
                    _subagentModels.value = remote.subagentModelPolicy
                }
                AgentField.EXECUTION_MODE -> _executionMode.value = remote.executionMode
            }
        }
    }

    private fun buildAgent(agentId: String): Agent {
        val initial = _initialAgent.value
        val nameChanged = _name.value != _initialDisplayName.value
        val descriptionChanged = _description.value != _initialDisplayDescription.value
        return Agent(
            id = agentId,
            name = if (nameChanged) _name.value else initial?.name.orEmpty(),
            description = if (descriptionChanged) _description.value else initial?.description.orEmpty(),
            nameCustomized = initial?.nameCustomized == true || nameChanged,
            descriptionCustomized = initial?.descriptionCustomized == true || descriptionChanged,
            systemPrompt = _systemPrompt.value,
            modelId = _selectedModel.value,
            icon = _selectedIcon.value,
            color = _selectedColor.value,
            avatarPath = _avatarPath.value,
            isPinned = _isPinned.value,
            temperature = _sampling.value.temperature,
            topP = _sampling.value.topP,
            maxTokens = _sampling.value.maxTokens,
            topK = _sampling.value.topK,
            frequencyPenalty = _sampling.value.frequencyPenalty,
            presencePenalty = _sampling.value.presencePenalty,
            repetitionPenalty = _sampling.value.repetitionPenalty,
            reasoningEffort = _reasoningEffort.value,
            // 智能体不再覆盖检索参数：旧的自定义参数原样保留以便旧数据往返，运行时不读取。
            ragConfig = initial?.ragConfig,
            retrievalConfig = _retrievalConfig.value.copy(memoryPolicy = _memoryPolicy.value, subagentModels = _subagentModels.value)
                .takeUnless { initial?.retrievalConfig == null && it == AgentRetrievalConfig() },
            useInheritedConfig = initial?.useInheritedConfig ?: true,
            executionMode = _executionMode.value,
            toolsEnabledByDefault = _toolsEnabledByDefault.value,
            // 技能名单运行时不读取（用户技能按全局启用名单生效），不在编辑页写入。
            skills = initial?.skills ?: emptyList(),
            mcpServerIds = _mcpServerIds.value,
            defaultWorkspaceId = _defaultWorkspaceId.value,
            userRules = initial?.userRules.orEmpty(),
            createdAt = initial?.createdAt ?: System.currentTimeMillis(),
        )
    }

    /**
     * 字段级保存：与数据库现值三方合并，只写本地改过的字段；智能体改过的其它字段静默刷新到界面，
     * 两边都改的字段不覆盖、改为提示冲突。写入以读到的现值作为期望值，期间又被改动时重新合并。
     */
    private suspend fun persistCandidate(
        agent: Agent,
        commitLocalState: () -> Unit = {},
    ): Result<Unit> = writeMutex.withLock {
        try {
            for (attempt in 0 until MAX_MERGE_ATTEMPTS) {
                val base = _initialAgent.value
                    ?: return@withLock Result.failure(IllegalStateException("Agent 尚未加载"))
                val remote = agentRepository.getById(agent.id) ?: run {
                    _saveError.value = AgentEditErrorCode.NOT_FOUND
                    retryAction = null
                    return@withLock Result.failure(IllegalStateException("Agent 不存在"))
                }
                val merge = mergeAgentEdit(base, agent, remote)
                val written = if (merge.toWrite.isEmpty()) remote else {
                    when (val result = agentRepository.updateFields(agent.id, merge.toWrite, agent, expected = remote)) {
                        is AgentFieldUpdate.Updated -> result.agent
                        is AgentFieldUpdate.Conflict -> continue
                        AgentFieldUpdate.NotFound -> {
                            _saveError.value = AgentEditErrorCode.NOT_FOUND
                            retryAction = null
                            return@withLock Result.failure(IllegalStateException("Agent 不存在"))
                        }
                    }
                }
                settle(merge, written)
                commitLocalState()
                _saveError.value = null
                retryAction = null
                return@withLock Result.success(Unit)
            }
            _saveError.value = AgentEditErrorCode.SAVE_FAILED
            retryAction = { saveAgent(agent.id) }
            Result.failure(IllegalStateException("Agent 连续被修改"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _saveError.value = AgentEditErrorCode.SAVE_FAILED
            retryAction = { saveAgent(agent.id) }
            Result.failure(error)
        }
    }

    /** 写入后推进基线；冲突字段保留旧基线，用户选择前不会被本地值静默覆盖。 */
    private fun settle(merge: AgentEditMerge, written: Agent) {
        val oldBase = _initialAgent.value ?: written
        _initialAgent.value = merge.conflicts.fold(written) { agent, field -> field.copy(agent, oldBase) }
        if (AgentField.NAME in merge.toWrite) _initialDisplayName.value = _name.value
        if (AgentField.DESCRIPTION in merge.toWrite) _initialDisplayDescription.value = _description.value
        applyToLocal(merge.remoteOnly, written)
        flashRefreshed(merge.remoteOnly)
        if (merge.conflicts.isNotEmpty()) _conflict.value = AgentEditConflict(merge.conflicts, written)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1000)
            _initialAgent.value?.let { agent ->
                persistCurrentAgent(agent.id)
            }
        }
    }

    /** 明确提交的设置立即持久化；离开页面也不取消。 */
    private fun commitNow() {
        val agentId = _initialAgent.value?.id ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            withContext(NonCancellable) {
                persistCurrentAgent(agentId)
            }
        }
    }

    private data class DefaultsEditState(
        val retrievalConfig: AgentRetrievalConfig,
        val defaultWorkspaceId: String?,
        val memoryPolicy: AgentMemoryPolicy,
        val executionMode: ExecutionMode,
        val toolsEnabledByDefault: Boolean,
        val mcpServerIds: List<String>,
    )

    private data class TextEditState(
        val initial: Agent?,
        val name: String,
        val description: String,
        val baselineName: String,
        val baselineDescription: String,
    )

    companion object {
        private const val MAX_MERGE_ATTEMPTS = 3
        private const val REFRESH_HIGHLIGHT_MILLIS = 2_600L

        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as InfinityApplication
                    return AgentEditViewModel(
                        deleteAgentUseCase = app.deleteAgentUseCase,
                        parameterSchemeRepository = app.parameterSchemeRepository,
                        agentRepository = app.agentRepository,
                        avatarImporter = { uri, slot ->
                            withContext(Dispatchers.IO) {
                                com.promenar.infinity.ui.settings.AvatarStore(
                                    directory = java.io.File(app.filesDir, "avatars"),
                                    mimeTypeOf = { source ->
                                        app.contentResolver.getType(source)
                                            ?: java.net.URLConnection.guessContentTypeFromName(source.path)
                                    },
                                    openInput = app.contentResolver::openInputStream,
                                ).save(uri, slot)
                            }
                        },
                        avatarCleaner = { uri ->
                            com.promenar.infinity.ui.avatar.AvatarCropFiles.deleteIfOwned(
                                app.cacheDir,
                                uri,
                            )
                        },
                        workspaces = app.database.workspaceDao().observeAll(),
                        mcpServers = app.skillRepository.getAllMcpServers(),
                        countAgentSessions = { agentId ->
                            withContext(Dispatchers.IO) {
                                app.sessionRepository.getAll().count { it.agentId == agentId }
                            }
                        },
                    ) as T
                }
            }
    }
}
