package com.promenar.infinity.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.skills.AgentSkillConflictException
import com.promenar.infinity.data.skills.AgentSkillFormatException
import com.promenar.infinity.data.skills.AgentSkillMetadata
import com.promenar.infinity.data.skills.AgentSkillStore
import com.promenar.infinity.data.skills.InstalledAgentSkill
import com.promenar.infinity.data.skills.ParsedSkillDocument
import com.promenar.infinity.data.skills.SkillCatalog
import com.promenar.infinity.data.skills.SkillWorkspace
import com.promenar.infinity.data.skills.SkillScope
import com.promenar.infinity.data.skills.SkillSourceGroup
import com.promenar.infinity.data.skills.SkillBatchResult
import com.promenar.infinity.data.skills.sourceGroups
import com.promenar.infinity.data.skills.setGroupEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import kotlinx.coroutines.Job

/** 技能包设置页的一次性结果；由界面消费后清除。 */
sealed interface AgentSkillEvent {
    data class Installed(val name: String) : AgentSkillEvent
    data class Conflict(val name: String, val retry: () -> Unit) : AgentSkillEvent
    data class Failed(val message: String) : AgentSkillEvent
}

class AgentSkillsViewModel(
    private val store: AgentSkillStore,
    private val openInput: (Uri) -> InputStream?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val catalog: SkillCatalog? = null,
) : ViewModel() {
    val skills: StateFlow<List<InstalledAgentSkill>> = store.skills

    private val _event = MutableStateFlow<AgentSkillEvent?>(null)
    val event: StateFlow<AgentSkillEvent?> = _event.asStateFlow()

    private var workspace: SkillWorkspace? = null
    private val _groups = MutableStateFlow<List<SkillSourceGroup>>(emptyList())
    val groups: StateFlow<List<SkillSourceGroup>> = _groups.asStateFlow()
    private val _batchResult = MutableStateFlow<SkillBatchResult?>(null)
    val batchResult: StateFlow<SkillBatchResult?> = _batchResult.asStateFlow()

    init {
        if (catalog != null) viewModelScope.launch {
            catalog.revision.collect {
                try {
                    refreshGroupsInternal()
                    withContext(ioDispatcher) { store.refresh() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    _event.value = AgentSkillEvent.Failed(failure.message?.take(160) ?: "技能列表刷新失败")
                }
            }
        }
    }

    /** 设置页传 null；会话或项目入口可传入已解析的当前工作区。 */
    fun bindWorkspace(workspace: SkillWorkspace?): Job {
        this.workspace = workspace
        return refreshGroups()
    }

    fun refreshGroups(): Job = launchMutation { refreshGroupsInternal() }

    fun setGroupEnabled(scope: SkillScope, enabled: Boolean): Job = setManyEnabled(scope, enabled)

    fun setAllEnabled(enabled: Boolean): Job = setManyEnabled(null, enabled)

    fun consumeBatchResult() { _batchResult.value = null }

    private fun setManyEnabled(scope: SkillScope?, enabled: Boolean): Job = launchMutation {
        val currentCatalog = checkNotNull(catalog) { "未配置统一技能目录" }
        _batchResult.value = currentCatalog.setGroupEnabled(currentCatalog.scan(workspace), scope, enabled)
        refreshGroupsInternal()
    }

    private suspend fun refreshGroupsInternal() {
        val currentCatalog = catalog ?: return
        _groups.value = currentCatalog.scan(workspace).sourceGroups()
    }

    fun consumeEvent() {
        _event.value = null
    }

    /** 按内容识别 ZIP（`PK\u0003\u0004` 魔数）或单个 SKILL.md 后安装。 */
    fun import(uri: Uri, overwrite: Boolean = false): Job = launchMutation(
        onConflict = if (overwrite) null else fun() { import(uri, overwrite = true) },
    ) {
        val name = withContext(ioDispatcher) {
            val stream = openInput(uri) ?: throw AgentSkillFormatException("无法读取所选文件")
            BufferedInputStream(stream).use { input ->
                input.mark(4)
                val magic = ByteArray(4)
                val read = input.read(magic)
                input.reset()
                if (read == 4 && magic.contentEquals(ZIP_MAGIC)) {
                    store.installZip(input, overwrite).name
                } else {
                    val bytes = readAtMost(input, AgentSkillStore.MAX_FILE_BYTES)
                        ?: throw AgentSkillFormatException("SKILL.md 超过 ${AgentSkillStore.MAX_FILE_BYTES / 1024} KiB")
                    val text = AgentSkillStore.decodeText(bytes)
                        ?: throw AgentSkillFormatException("所选文件既不是 ZIP 也不是 UTF-8 文本")
                    store.installMarkdown(text, overwrite).name
                }
            }
        }
        _event.value = AgentSkillEvent.Installed(name)
    }

    fun save(previousName: String?, name: String, description: String, body: String): Job = launchMutation {
        val existing = previousName?.let(store::find)?.metadata
        val metadata = AgentSkillMetadata(
            name = name.trim(),
            description = description.trim(),
            license = existing?.license,
            allowedTools = existing?.allowedTools.orEmpty(),
        )
        withContext(ioDispatcher) { store.save(previousName, metadata, body) }
        _event.value = AgentSkillEvent.Installed(metadata.name)
    }

    fun setEnabled(name: String, enabled: Boolean): Job = launchMutation {
        if (catalog == null) withContext(ioDispatcher) { store.setEnabled(name, enabled) }
        else catalog.setEnabled(catalog.scan(null).user.single { it.name == name }, enabled)
    }

    fun delete(name: String): Job = launchMutation {
        if (catalog == null) withContext(ioDispatcher) { store.delete(name) }
        else catalog.delete(catalog.scan(null).user.single { it.name == name })
    }

    suspend fun loadDocument(name: String): ParsedSkillDocument? = withContext(ioDispatcher) { store.readDocument(name) }

    private fun launchMutation(
        onConflict: (() -> Unit)? = null,
        block: suspend () -> Unit,
    ): Job = viewModelScope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (conflict: AgentSkillConflictException) {
            _event.value = if (onConflict != null) {
                AgentSkillEvent.Conflict(conflict.name, onConflict)
            } else {
                AgentSkillEvent.Failed(conflict.message.orEmpty())
            }
        } catch (format: AgentSkillFormatException) {
            _event.value = AgentSkillEvent.Failed(format.message.orEmpty())
        } catch (failure: Exception) {
            _event.value = AgentSkillEvent.Failed(failure.message?.take(160) ?: failure::class.simpleName.orEmpty())
        } finally {
            try {
                refreshGroupsInternal()
                withContext(ioDispatcher) { store.refresh() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _event.value = AgentSkillEvent.Failed(failure.message?.take(160) ?: "技能列表刷新失败")
            }
        }
    }

    companion object {
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        private fun readAtMost(input: InputStream, limit: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) return out.toByteArray()
                if (out.size() + read > limit) return null
                out.write(chunk, 0, read)
            }
        }

        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as InfinityApplication
                return AgentSkillsViewModel(
                    store = app.agentSkillStore,
                    openInput = { uri -> app.contentResolver.openInputStream(uri) },
                    catalog = app.skillCatalog,
                ) as T
            }
        }
    }
}
