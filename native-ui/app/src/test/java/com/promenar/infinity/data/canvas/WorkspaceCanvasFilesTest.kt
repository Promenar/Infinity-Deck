package com.promenar.infinity.data.canvas

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.FileOperationRepository
import com.promenar.infinity.data.repository.SessionRepository
import com.promenar.infinity.data.repository.TestWorkspaceFileOps
import com.promenar.infinity.data.repository.WorkspaceDeletionTransaction
import com.promenar.infinity.data.repository.WorkspaceRepository
import com.promenar.infinity.data.storage.ExternalFolderManager
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.data.storage.RealPathExternalFolderBackend
import com.promenar.infinity.data.storage.isUserSkills
import com.promenar.infinity.data.storage.isWorkspaceRoot
import com.promenar.infinity.data.workspace.SessionWorkspaceResolver
import com.promenar.infinity.data.workspace.SharedWorkspaceAccess
import com.promenar.infinity.data.workspace.WorkspaceFileTargetResolver
import com.promenar.infinity.domain.repository.PatchOperation
import com.promenar.infinity.domain.repository.PatchResult
import com.promenar.infinity.data.session.BranchSessionUseCase
import com.promenar.infinity.data.session.BranchSessionResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class WorkspaceCanvasFilesTest {
    private lateinit var db: InfinityDatabase
    private lateinit var directory: File
    private lateinit var privateRoot: File
    private lateinit var workspace: WorkspaceRepository
    private lateinit var operations: FileOperationRepository
    private lateinit var roots: SessionWorkspaceResolver
    private lateinit var mounts: ExternalFolderManager
    private lateinit var store: FileCanvasStore
    private lateinit var canvasFiles: WorkspaceCanvasFiles
    private val events = mutableListOf<FileIndexEvent>()
    private val index = FileIndexEventSink { events.add(it) }
    private val fs = JvmBoundRealPathFileSystem()

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory("canvas-workspace-test").toFile().canonicalFile
        privateRoot = File(directory, "private")
        val skills = File(directory, "skills").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        db.sessionDao().insert(SessionEntity(id = "s", agentId = "a", workspacePath = privateRoot.path, createdAt = 1, updatedAt = 1))
        workspace = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), defaultWorkspaceParent = File(directory, "private-roots"), fileOps = TestWorkspaceFileOps(), deleteCommitter = WorkspaceDeletionTransaction(db)::delete)
        operations = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), TestWorkspaceFileOps(), indexEventSink = index)
        roots = SessionWorkspaceResolver(db.sessionDao(), db.workspaceDao(), File(directory, "projects"), object : SharedWorkspaceAccess {
            override fun root(location: String) = File(location)
            override fun backend(root: File) = real(root)
        }, ::real)
        mounts = ExternalFolderManager(context, db.externalMountDao(), workspace, index, roots, skills) { mount ->
            when {
                mount.isUserSkills -> real(skills)
                mount.isWorkspaceRoot -> real(File(directory, "projects/project"))
                mount.sourceType == "real_path" -> real(File(mount.sourceId))
                else -> null
            }
        }
        canvasFiles = WorkspaceCanvasFiles(
            WorkspaceFileTargetResolver(SessionRepository(db.sessionDao(), db.messageDao()), workspace, mounts, roots::resolve, skills,
                roots::resolveWorkspace, roots::backend, modeFor = { com.promenar.infinity.domain.model.SessionRunMode.fromStored(db.sessionDao().getById(it)?.runMode) }),
            workspace, operations, mounts, index, fs,
        )
        store = FileCanvasStore(File(directory, "history"), workspaceFiles = canvasFiles)
    }

    private fun real(root: File) = RealPathExternalFolderBackend(root, emptySet(), { true }, fs)

    private suspend fun directoryRoot(): File {
        val root = File(directory, "projects/project").apply { mkdirs() }
        db.workspaceDao().insert(WorkspaceEntity("project-id", WorkspaceEntity.KIND_PROJECT, "项目", "project", 1))
        db.sessionDao().update(db.sessionDao().getById("s")!!.copy(workspaceId = "project-id"))
        return root
    }

    private fun removeDestinationBinding(id: String) {
        val file = File(directory, "history").walkTopDown().single { it.name == "metadata.json" && it.parentFile.name == id }
        val metadata = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject
        file.writeText(kotlinx.serialization.json.JsonObject(metadata - "workspaceDestination").toString())
        store = FileCanvasStore(File(directory, "history"), workspaceFiles = canvasFiles)
    }

    @After fun cleanup() {
        com.promenar.infinity.startup.StartupWriteBarrier.resetForTesting()
        db.close()
        directory.deleteRecursively()
    }


    @Test fun `画布双向切换模式后保持原镜像并可同步和更新`() = runBlocking {
        val first = store.create("s", "内置画布", "<html>内置</html>", "m")
        db.sessionDao().updateRunMode("s", "LINUX", 2)
        val linux = store.create("s", "Linux画布", "<html>Linux</html>", "m")
        val updated = store.update("s", first.metadata.id, html = "<html>内置更新</html>")
        assertThat(updated.metadata.workspacePath).startsWith("/home/session/canvas/")
        assertThat(store.read("s", first.metadata.id)!!.metadata.workspacePath).startsWith("/home/session/canvas/")
        assertThat(store.list("s").single { it.id == first.metadata.id }.workspacePath).startsWith("/home/session/canvas/")
        assertThat(File(privateRoot, "canvas/内置画布.html").readText()).isEqualTo("<html>内置更新</html>")
        db.sessionDao().updateRunMode("s", "BUILTIN", 3)
        val root = workspace.ensureSessionRoot("s")
        val entry = workspace.getByMaterializedPath(root.uuid, "/canvas/Linux画布.html")!!
        operations.writeFileAtomicExact(root.uuid, entry.uuid, "<html>外部更新</html>", "s", entry.hash)
        assertThat(store.syncFromWorkspace("s", linux.metadata.id)!!.html).isEqualTo("<html>外部更新</html>")
        val builtin = store.update("s", linux.metadata.id, html = "<html>镜像更新</html>")
        assertThat(builtin.metadata.workspacePath).doesNotContain("/home/session")
        assertThat(builtin.metadata.workspacePath).doesNotContain("/workspace")
        assertThat(File(privateRoot, "canvas/Linux画布.html").readText()).isEqualTo("<html>镜像更新</html>")
    }

    @Test fun `Linux画布私有与主工作区路径可写入并从持久元数据重载`() = runBlocking {
        db.sessionDao().updateRunMode("s", "LINUX", 2)
        val privateCanvas = store.create("s", "私有画布", "<html>私有</html>", "m")
        assertThat(privateCanvas.metadata.workspacePath).startsWith("/home/session/canvas/")
        val project = directoryRoot()
        val main = store.create("s", "项目画布", "<html>项目</html>", "m")
        assertThat(main.metadata.workspacePath).startsWith("/workspace/canvas/")
        assertThat(File(project, main.metadata.workspacePath!!.removePrefix("/workspace/")).readText()).isEqualTo("<html>项目</html>")
        val restored = FileCanvasStore(File(directory, "history"), workspaceFiles = canvasFiles)
        assertThat(restored.read("s", privateCanvas.metadata.id)!!.html).isEqualTo("<html>私有</html>")
        assertThat(restored.read("s", main.metadata.id)!!.html).isEqualTo("<html>项目</html>")
        db.sessionDao().update(db.sessionDao().getById("s")!!.copy(workspaceId = null))
        assertThat(restored.read("s", main.metadata.id)!!.html).isEqualTo("<html>项目</html>")
    }

    @Test fun `隔离根画布可读取但更新在发布内部版本前被拒绝`() = runBlocking {
        val first = store.create("s", "图表", "原文", "m")
        val root = workspace.ensureSessionRoot("s")
        val manager = com.promenar.infinity.startup.StartupIssueManager()
        manager.bindRoot(root.uuid, privateRoot.canonicalPath, "s")
        com.promenar.infinity.startup.StartupWriteBarrier.install(manager)
        manager.recover(com.promenar.infinity.startup.StartupStep.WORKSPACE_JOURNAL,
            com.promenar.infinity.startup.StartupIssueScope(com.promenar.infinity.startup.StartupScopeKind.WORKSPACE_ROOT,
                root.uuid, "工作区")) { error("冲突") }
        val files = WorkspaceCanvasFiles(
            WorkspaceFileTargetResolver(SessionRepository(db.sessionDao(), db.messageDao()), workspace, mounts, roots::resolve,
                File(directory, "skills")), workspace, operations, mounts, index, fs,
        )

        assertThat(files.read("s", first.metadata.workspacePath!!, 1024)?.toString(Charsets.UTF_8)).isEqualTo("原文")
        assertThat(runCatching { store.update("s", first.metadata.id, html = "新内容") }.exceptionOrNull())
            .isInstanceOf(com.promenar.infinity.startup.StartupIsolationException::class.java)
        assertThat(store.read("s", first.metadata.id)?.version).isEqualTo(1)
        assertThat(File(privateRoot, first.metadata.workspacePath!!).readText()).isEqualTo("原文")
    }

    @Test fun `健康会话选中隔离物理目录时画布读取不申请写权限`() = runBlocking {
        val root = directoryRoot()
        val first = store.create("s", "图表", "原文", "m")
        val manager = com.promenar.infinity.startup.StartupIssueManager()
        manager.bindRoot("isolated-other-root", root.canonicalPath)
        com.promenar.infinity.startup.StartupWriteBarrier.install(manager)
        manager.recover(com.promenar.infinity.startup.StartupStep.WORKSPACE_JOURNAL,
            com.promenar.infinity.startup.StartupIssueScope(com.promenar.infinity.startup.StartupScopeKind.WORKSPACE_ROOT,
                "isolated-other-root", "工作区")) { error("冲突") }
        val files = WorkspaceCanvasFiles(
            WorkspaceFileTargetResolver(SessionRepository(db.sessionDao(), db.messageDao()), workspace, mounts, roots::resolve,
                File(directory, "skills")), workspace, operations, mounts, index, fs,
        )

        assertThat(manager.isSessionIsolated("s")).isFalse()
        assertThat(files.exists("s", first.metadata.workspacePath!!)).isTrue()
        assertThat(files.read("s", first.metadata.workspacePath!!, 1024)?.toString(Charsets.UTF_8)).isEqualTo("原文")
    }

    @Test fun `私有根按需建立文件树更新索引并保持BOM及CRLF字节`() = runBlocking {
        val first = store.create("s", "图表", "\uFEFF<p>一</p>\r\n", "m")
        assertThat(first.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        val path = "/${first.metadata.workspacePath}"
        val root = workspace.ensureSessionRoot("s")
        val entry = workspace.getByMaterializedPath(root.uuid, path)!!
        assertThat(entry.mimeType).isEqualTo("text/html")
        assertThat(workspace.getByMaterializedPath(root.uuid, "/canvas")?.isDirectory).isTrue()
        assertThat(File(privateRoot, path.trimStart('/')).readBytes()).isEqualTo(first.html.toByteArray())
        val updated = store.update("s", first.metadata.id, html = "\uFEFF<p>二</p>\r\n")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(File(privateRoot, path.trimStart('/')).readBytes()).isEqualTo(updated.html.toByteArray())
        val indexed = workspace.getByMaterializedPath(root.uuid, path)!!
        assertThat(indexed.uuid).isEqualTo(entry.uuid)
        assertThat(indexed.hash).isEqualTo(canvasSha256(updated.html.toByteArray()))
        assertThat(events.filterIsInstance<FileIndexEvent.Changed>().map { it.fileUuid }).contains(entry.uuid)
        store.deleteSession("s")
        assertThat(File(privateRoot, path.trimStart('/')).readText()).isEqualTo(updated.html)
        assertThat(workspace.getByMaterializedPath(root.uuid, path)?.uuid).isEqualTo(entry.uuid)
    }

    @Test fun `空HTML也能在私有根新建和更新`() = runBlocking {
        val first = store.create("s", "空画布", "", "m")
        assertThat(first.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        val file = File(privateRoot, first.metadata.workspacePath!!)
        assertThat(file.isFile).isTrue()
        assertThat(file.length()).isEqualTo(0)
        store.update("s", first.metadata.id, html = "内容")
        val empty = store.update("s", first.metadata.id, html = "")
        assertThat(empty.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(file.length()).isEqualTo(0)
    }

    @Test fun `私有根一MiB内多行HTML更新不受文件工具行数预算限制`() = runBlocking {
        val first = store.create("s", "多行", "<pre>${"\n".repeat(20_001)}</pre>", "m")
        assertThat(first.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        val updated = store.update("s", first.metadata.id, html = "<pre>${"\n".repeat(20_002)}更新</pre>")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(File(privateRoot, updated.metadata.workspacePath!!).readBytes()).isEqualTo(updated.html.toByteArray())
        val compact = store.update("s", first.metadata.id, html = "<p>简短</p>")
        assertThat(compact.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
    }

    @Test fun `文件工具补丁导入为新版本再以最新内容应用画布补丁`() = runBlocking {
        val first = store.create("s", "图表", "<p>原文</p>", "m")
        val root = workspace.ensureSessionRoot("s")
        val path = "/${first.metadata.workspacePath}"
        val entry = workspace.getByMaterializedPath(root.uuid, path)!!
        val patch = operations.patchFile(root.uuid, entry.uuid, listOf(PatchOperation("replace_lines", 1, 1, newContent = "<p>外部</p>")), entry.hash)
        assertThat(patch).isInstanceOf(PatchResult.Success::class.java)
        val updated = store.update("s", first.metadata.id, patches = listOf(CanvasPatch("外部", "画布")))
        assertThat(updated.version).isEqualTo(3)
        assertThat(store.read("s", first.metadata.id, 2)?.html).isEqualTo("<p>外部</p>")
        assertThat(updated.html).isEqualTo("<p>画布</p>")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
    }

    @Test fun `私有根文件永久删除后只标记缺失更新重新创建索引`() = runBlocking {
        val first = store.create("s", "图表", "原文", "m")
        val root = workspace.ensureSessionRoot("s")
        val path = "/${first.metadata.workspacePath}"
        val entry = workspace.getByMaterializedPath(root.uuid, path)!!
        workspace.permanentDelete(root.uuid, entry.uuid)
        assertThat(store.syncFromWorkspace("s", first.metadata.id)!!.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.MISSING)
        assertThat(workspace.getByMaterializedPath(root.uuid, path)).isNull()
        val updated = store.update("s", first.metadata.id, html = "更新")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(workspace.getByMaterializedPath(root.uuid, path)?.uuid).isNotEqualTo(entry.uuid)
    }

    @Test fun `目录根处理文件名冲突外部修改和删除且不落入私有文件树`() = runBlocking {
        val root = directoryRoot()
        File(root, "canvas").mkdirs()
        File(root, "canvas/图表.html").writeText("保留")
        val first = store.create("s", "图表", "原文", "m")
        assertThat(first.metadata.workspacePath).isEqualTo("canvas/图表-2.html")
        assertThat(first.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        val file = File(root, first.metadata.workspacePath!!)
        file.writeText("外部\r\n")
        assertThat(store.syncFromWorkspace("s", first.metadata.id)?.html).isEqualTo("外部\r\n")
        assertThat(store.read("s", first.metadata.id)?.version).isEqualTo(2)
        file.delete()
        assertThat(store.syncFromWorkspace("s", first.metadata.id)!!.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.MISSING)
        assertThat(file.exists()).isFalse()
        val updated = store.update("s", first.metadata.id, html = "更新")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(file.readText()).isEqualTo("更新")
        assertThat(db.sessionDao().getById("s")!!.workspaceRootUuid).isNull()
        store.deleteSession("s")
        assertThat(file.readText()).isEqualTo("更新")
        assertThat(File(root, "canvas/图表.html").readText()).isEqualTo("保留")
    }

    @Test fun `工作区不可用不回退私有根且恢复后重试写出`() = runBlocking {
        val root = directoryRoot()
        root.delete()
        val first = store.create("s", "标题", "完整私有源码", "m")
        assertThat(first.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.FAILED)
        assertThat(store.read("s", first.metadata.id, 1)?.html).isEqualTo("完整私有源码")
        assertThat(db.sessionDao().getById("s")!!.workspaceRootUuid).isNull()
        root.mkdirs()
        val synced = store.syncFromWorkspace("s", first.metadata.id)!!
        assertThat(synced.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(File(root, synced.metadata.workspacePath!!).readText()).isEqualTo("完整私有源码")
    }

    @Test fun `登记工作区有用户挂载时画布默认写入会话专用空间`() = runBlocking {
        val project = directoryRoot()
        val assets = File(directory, "assets").apply { mkdirs() }
        db.externalMountDao().insert(ExternalMountEntity("assets", "素材", "real_path", assets.path, "s", false, 1))
        val created = store.create("s", "图表", "源码", "m")
        assertThat(created.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(created.metadata.workspacePath).isEqualTo("/session/canvas/图表.html")
        assertThat(File(privateRoot, "canvas/图表.html").readText()).isEqualTo("源码")
        assertThat(File(project, "canvas").exists()).isFalse()
    }

    @Test fun `登记工作区中通用文件工具可按session路径创建读取修改列出删除私有文件`() = runBlocking {
        val project = directoryRoot()
        val context = object : com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext {
            override val sessionId = "s"
            override val agentId = "a"
            override val workspaceRootUuid = "unused-private-root"
            override val workspacePath: String? = null
        }
        suspend fun invoke(skill: com.promenar.infinity.ui.chat.manager.registry.SkillDefinition, args: String) =
            com.promenar.infinity.ui.chat.manager.skills.MountedWorkspaceSkill(skill, mounts, workspace, operations, index,
                privateReader = object : com.promenar.infinity.ui.chat.diff.PrivateWorkspaceReader {
                    private suspend fun backend(repository: com.promenar.infinity.domain.repository.IWorkspaceRepository, rootUuid: String) =
                        real(File(repository.getByUuid(rootUuid, rootUuid)!!.physicalRootPath))
                    override suspend fun read(workspace: com.promenar.infinity.domain.repository.IWorkspaceRepository, rootUuid: String, path: String, maxBytes: Long) =
                        backend(workspace, rootUuid).read(path, maxBytes)
                    // 与生产默认读取器一致提供真正的流，跨命名空间移动按流式摘要读取私有来源。
                    override suspend fun <T> readStream(workspace: com.promenar.infinity.domain.repository.IWorkspaceRepository, rootUuid: String, path: String, maxBytes: Long, consume: suspend (java.io.InputStream) -> T): T =
                        backend(workspace, rootUuid).readStream(path, maxBytes, consume)
                })
                .execute(kotlinx.serialization.json.Json.parseToJsonElement(args).let { it as kotlinx.serialization.json.JsonObject }, context)
        val created = invoke(com.promenar.infinity.ui.chat.manager.skills.CreateFileSkill(workspace, operations),
            """{"parentPath":"/session","name":"note.txt","content":"私有内容"}""")
        assertThat(created.status).isEqualTo("success")
        assertThat(File(privateRoot, "note.txt").readText()).isEqualTo("私有内容")
        assertThat(File(project, "session").exists()).isFalse()
        val read = invoke(com.promenar.infinity.ui.chat.manager.skills.FileReadSkill(operations, workspace), """{"path":"/session/note.txt"}""")
        assertThat(read.status).isEqualTo("success")
        assertThat(read.content).contains("私有内容")
        assertThat(read.content).contains("uuid=session:")
        val root = workspace.ensureSessionRoot("s")
        val hash = workspace.getByMaterializedPath(root.uuid, "/note.txt")!!.hash
        val updated = invoke(com.promenar.infinity.ui.chat.manager.skills.FileWriteSkill(operations, workspace),
            """{"path":"/session/note.txt","content":"修改","expectedHash":"$hash"}""")
        assertThat(updated.status).isEqualTo("success")
        assertThat(updated.fileChanges).isNotEmpty()
        assertThat(File(privateRoot, "note.txt").readText()).isEqualTo("修改")
        val listing = invoke(com.promenar.infinity.ui.chat.manager.skills.FileListSkill(workspace), """{"path":"/session"}""")
        assertThat(listing.content).contains("/session/note.txt")
        val move = com.promenar.infinity.ui.chat.manager.skills.MoveFileSkill(workspace, index)
        assertThat(invoke(move, """{"path":"/session/note.txt","newName":"renamed.txt"}""").status).isEqualTo("success")
        assertThat(File(privateRoot, "renamed.txt").readText()).isEqualTo("修改")
        val promoted = invoke(move, """{"path":"/session/renamed.txt","newParentPath":"/","newName":"promoted.txt"}""")
        com.google.common.truth.Truth.assertWithMessage(promoted.content).that(promoted.status).isEqualTo("success")
        assertThat(File(project, "promoted.txt").readText()).isEqualTo("修改")
        val movedBack = invoke(move, """{"path":"/promoted.txt","newParentPath":"/session","newName":"note.txt"}""")
        com.google.common.truth.Truth.assertWithMessage(movedBack.content).that(movedBack.status).isEqualTo("success")
        assertThat(invoke(com.promenar.infinity.ui.chat.manager.skills.DeleteFileSkill(workspace), """{"path":"/session/note.txt"}""").status).isEqualTo("success")
        assertThat(File(privateRoot, "note.txt").exists()).isFalse()
    }

    @Test fun `宿主缺少安全目录能力时默认传输读取明确失败关闭`() = runBlocking {
        val supportsSecureDirectory = Files.newDirectoryStream(directory.toPath()).use { it is java.nio.file.SecureDirectoryStream<*> }
        org.junit.Assume.assumeFalse("具备安全目录能力的宿主无需该环境探针", supportsSecureDirectory)
        val root = workspace.ensureSessionRoot("s")
        workspace.createFileInWorkspace(root.uuid, "probe", "probe.txt", "探针", root.uuid, "/probe.txt")
        val failure = runCatching {
            com.promenar.infinity.ui.chat.manager.skills.PrivateWorkspaceTransferBackend(workspace, root.uuid).read("/probe.txt", 1024)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(failure?.message).contains("不支持 SecureDirectoryStream")
    }

    @Test fun `切换主工作区后已有画布镜像保留原目标且重建Store后仍可更新`() = runBlocking {
        val firstRoot = directoryRoot()
        val first = store.create("s", "图表", "初始", "m")
        val nextRoot = File(directory, "projects/next").apply { mkdirs() }
        db.workspaceDao().insert(WorkspaceEntity("next-id", WorkspaceEntity.KIND_PROJECT, "新项目", "next", 1))
        db.sessionDao().update(db.sessionDao().getById("s")!!.copy(workspaceId = "next-id"))
        store = FileCanvasStore(File(directory, "history"), workspaceFiles = canvasFiles)
        val updated = store.update("s", first.metadata.id, html = "修改")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(File(firstRoot, "canvas/图表.html").readText()).isEqualTo("修改")
        assertThat(File(nextRoot, "canvas").exists()).isFalse()
    }

    @Test fun `私有画布切换到登记工作区后仍更新私有原镜像`() = runBlocking {
        val first = store.create("s", "私有图表", "初始", "m")
        val project = directoryRoot()
        store = FileCanvasStore(File(directory, "history"), workspaceFiles = canvasFiles)
        val updated = store.update("s", first.metadata.id, html = "私有修改")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(File(privateRoot, "canvas/私有图表.html").readText()).isEqualTo("私有修改")
        assertThat(File(project, "canvas").exists()).isFalse()
    }

    @Test fun `旧画布唯一匹配私有镜像时恢复绑定不搬到当前主根`() = runBlocking {
        val first = store.create("s", "旧画布", "初始", "m")
        val project = directoryRoot()
        removeDestinationBinding(first.metadata.id)
        val updated = store.update("s", first.metadata.id, html = "修改")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(File(privateRoot, "canvas/旧画布.html").readText()).isEqualTo("修改")
        assertThat(File(project, "canvas").exists()).isFalse()
    }

    @Test fun `旧画布无匹配原根时保留权威源码与原镜像且不创建新镜像`() = runBlocking {
        val original = directoryRoot()
        val first = store.create("s", "旧画布", "原镜像", "m")
        val next = File(directory, "projects/next").apply { mkdirs() }
        db.workspaceDao().insert(WorkspaceEntity("next-id", WorkspaceEntity.KIND_PROJECT, "新项目", "next", 1))
        db.sessionDao().update(db.sessionDao().getById("s")!!.copy(workspaceId = "next-id"))
        removeDestinationBinding(first.metadata.id)
        val updated = store.update("s", first.metadata.id, html = "权威源码")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.FAILED)
        assertThat(store.read("s", first.metadata.id)?.html).isEqualTo("权威源码")
        assertThat(File(original, "canvas/旧画布.html").readText()).isEqualTo("原镜像")
        assertThat(File(next, "canvas").exists()).isFalse()
        assertThat(File(privateRoot, "canvas").exists()).isFalse()
    }

    @Test fun `旧画布多个根内容匹配时拒绝猜测且不改任何镜像`() = runBlocking {
        val first = store.create("s", "旧画布", "相同镜像", "m")
        val project = directoryRoot()
        File(project, "canvas").mkdirs()
        File(project, "canvas/旧画布.html").writeText("相同镜像")
        removeDestinationBinding(first.metadata.id)
        val updated = store.update("s", first.metadata.id, html = "权威源码")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.FAILED)
        assertThat(store.read("s", first.metadata.id)?.html).isEqualTo("权威源码")
        assertThat(File(project, "canvas/旧画布.html").readText()).isEqualTo("相同镜像")
        assertThat(File(privateRoot, "canvas/旧画布.html").readText()).isEqualTo("相同镜像")
    }

    @Test fun `目录根分支保留工作区绑定且画布写到新路径不覆盖源文件`() = runBlocking {
        val root = directoryRoot()
        db.messageDao().insertAll(listOf(MessageEntity(id = "m", sessionId = "s", role = "assistant", content = "图表", status = "success", createdAt = 1)))
        val first = store.create("s", "图表", "源内容", "m")
        var serial = 0
        val result = BranchSessionUseCase(db, newId = { "branch-${serial++}" }, canvasStore = store).branch("s", "m") as BranchSessionResult.Success
        val session = db.sessionDao().getById(result.sessionId)!!
        assertThat(session.workspaceId).isEqualTo(db.sessionDao().getById("s")!!.workspaceId)
        val copied = store.read(result.sessionId, first.metadata.id)!!
        assertThat(copied.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.NOT_WRITTEN)
        assertThat(copied.metadata.workspacePath).isNull()
        val synced = store.syncFromWorkspace(result.sessionId, first.metadata.id)!!
        assertThat(synced.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(synced.metadata.workspacePath).isEqualTo("canvas/图表-2.html")
        val branchFile = File(root, synced.metadata.workspacePath!!)
        assertThat(branchFile.readText()).isEqualTo("源内容")
        store.update(result.sessionId, first.metadata.id, html = "分支内容")
        assertThat(File(root, first.metadata.workspacePath!!).readText()).isEqualTo("源内容")
        assertThat(branchFile.readText()).isEqualTo("分支内容")
    }
}
