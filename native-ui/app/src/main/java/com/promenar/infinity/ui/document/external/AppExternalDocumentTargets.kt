package com.promenar.infinity.ui.document.external

import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.document.external.ExternalDocumentLibrarySaver
import com.promenar.infinity.data.document.external.ExternalDocumentSender
import com.promenar.infinity.data.document.external.ExternalDocumentStore
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.domain.usecase.IdGenerator
import com.promenar.infinity.domain.usecase.RagConfigPersistence
import com.promenar.infinity.domain.usecase.newSessionDefaults
import com.promenar.infinity.share.core.AndroidShareIndexScheduler
import com.promenar.infinity.share.core.ShareImportTargetProvider
import com.promenar.infinity.share.core.ShareKnowledgeFolder
import com.promenar.infinity.ui.chat.manager.SessionManager
import com.promenar.infinity.ui.hub.defaultNewSessionModelId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 应用内的存储空间、会话与智能体数据。 */
class AppExternalDocumentTargets(
    private val app: InfinityApplication,
    private val shareTargets: ShareImportTargetProvider,
) : ExternalDocumentTargets {
    override suspend fun knowledgeRootUuid(): String = app.globalKnowledgeWorkspaceProvisioner.ensureRoot().uuid

    override suspend fun folders(parentUuid: String): List<ShareKnowledgeFolder> = shareTargets.knowledgeFolders(parentUuid)

    override suspend fun folderExists(uuid: String): Boolean = withContext(Dispatchers.IO) {
        val root = knowledgeRootUuid()
        app.workspaceRepository.getByUuid(root, uuid)?.let { it.isDirectory && !it.inRecycleBin } == true
    }

    override suspend fun sessions() = shareTargets.sessions(excludeSessionId = null)

    override suspend fun agents(): List<ExternalAgentChoice> =
        app.agentRepository.observeAll().first().map { ExternalAgentChoice(it.id, it.name) }

    override suspend fun createSession(agentId: String): String? {
        val agent = runCatching { app.agentRepository.observeById(agentId).first() }.getOrNull() ?: return null
        val ragConfig = runCatching {
            RagConfigPersistence(app.getSharedPreferences("rag_settings", 0)).loadFullConfig()
        }.getOrElse { RagConfiguration() }
        val sessionId = IdGenerator.session()
        val session = newSessionDefaults(sessionId, agentId, agent, defaultNewSessionModelId(), ragConfig, System.currentTimeMillis())
        SessionManager(app.chatStore, app.sessionRepository).addSession(session)
        return sessionId
    }
}

/** 上次存入的文件夹层级，存成 JSON 数组。 */
class PreferencesExternalFolderMemory(private val preferences: SharedPreferences) : ExternalFolderMemory {
    override fun load(): List<ShareKnowledgeFolder> = try {
        val array = JSONArray(preferences.getString(KEY_TRAIL, null) ?: return emptyList())
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ShareKnowledgeFolder(item.getString("uuid"), item.getString("name"), item.getString("path"))
        }
    } catch (_: org.json.JSONException) {
        emptyList()
    }

    override fun save(trail: List<ShareKnowledgeFolder>) {
        val array = JSONArray()
        trail.forEach { folder -> array.put(JSONObject().put("uuid", folder.uuid).put("name", folder.name).put("path", folder.path)) }
        preferences.edit().putString(KEY_TRAIL, array.toString()).apply()
    }

    private companion object {
        const val KEY_TRAIL = "last_storage_folder_trail"
    }
}

/** 主界面使用的外部文档查看器 ViewModel 工厂。 */
fun externalDocumentViewModelFactory(app: InfinityApplication, shareTargets: ShareImportTargetProvider): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            val librarySaver = ExternalDocumentLibrarySaver(app.workspaceRepository, AndroidShareIndexScheduler(app))
            ExternalDocumentViewModel(
                store = ExternalDocumentStore(File(app.noBackupFilesDir, EXTERNAL_DOCUMENT_DIRECTORY)),
                librarySave = librarySaver::save,
                sender = ExternalDocumentSender(app.composerDocumentInbox, app.documentWorkspaceSaver),
                targets = AppExternalDocumentTargets(app, shareTargets),
                folderMemory = PreferencesExternalFolderMemory(app.getSharedPreferences(FOLDER_MEMORY_PREFS, 0)),
                savedState = createSavedStateHandle(),
            )
        }
    }

private const val EXTERNAL_DOCUMENT_DIRECTORY = "external-documents"
private const val FOLDER_MEMORY_PREFS = "external_document_viewer"
