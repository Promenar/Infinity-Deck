package com.promenar.infinity.data.workspace

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.data.exec.AndroidProcessGroupSignaller
import com.promenar.infinity.data.exec.BuiltinCommandRunner
import com.promenar.infinity.data.exec.BuiltinExecEnvironment
import com.promenar.infinity.data.exec.BuiltinExecService
import com.promenar.infinity.data.exec.PackagedPython
import com.promenar.infinity.data.exec.SystemCaBundle
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.FileOperationRepository
import com.promenar.infinity.data.repository.WorkspaceDeletionTransaction
import com.promenar.infinity.data.repository.WorkspaceRepository
import com.promenar.infinity.data.storage.ExternalFolderManager
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.CreateDirectorySkill
import com.promenar.infinity.ui.chat.manager.skills.CreateFileSkill
import com.promenar.infinity.ui.chat.manager.skills.DeleteFileSkill
import com.promenar.infinity.ui.chat.manager.skills.FileListSkill
import com.promenar.infinity.ui.chat.manager.skills.FileReadSkill
import com.promenar.infinity.ui.chat.manager.skills.FileWriteSkill
import com.promenar.infinity.ui.chat.manager.skills.MountedWorkspaceSkill
import com.promenar.infinity.infra.util.Sha256Utils
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 项目文件夹工作区在设备上经真实 fd 目录后端读写，shell 与内置 git 在该目录运行；
 * 会话私有工作区与 Room 文件树保持不变。
 */
@RunWith(AndroidJUnit4::class)
class DirectoryWorkspaceDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: InfinityDatabase
    private lateinit var base: File
    private lateinit var project: File
    private lateinit var privateRoot: FileEntry
    private lateinit var repository: WorkspaceRepository
    private lateinit var resolver: SessionWorkspaceResolver
    private lateinit var manager: ExternalFolderManager

    private val execution = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid: String get() = privateRoot.uuid
    }

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        base = File(context.filesDir, "workspace-device-${UUID.randomUUID()}").apply { mkdirs() }
        val projects = File(base, "projects")
        project = File(projects, "demo").apply { mkdirs() }
        repository = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), deleteCommitter = WorkspaceDeletionTransaction(db)::delete)
        val now = System.currentTimeMillis()
        db.workspaceDao().register(WorkspaceEntity("ws", WorkspaceEntity.KIND_PROJECT, "演示", "demo", now))
        db.sessionDao().insert(SessionEntity(id = "s", agentId = "agent", workspacePath = File(base, "private").apply { mkdirs() }.path, workspaceId = "ws", createdAt = now, updatedAt = now))
        privateRoot = repository.ensureSessionRoot("s")
        resolver = SessionWorkspaceResolver(db.sessionDao(), db.workspaceDao(), projects, AndroidSharedWorkspaceAccess(context))
        manager = ExternalFolderManager(context, db.externalMountDao(), repository, FileIndexEventSink.None, resolver)
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    private fun skill(original: SkillDefinition) =
        MountedWorkspaceSkill(original, manager, repository, FileOperationRepository(db.fileEntryDao(), db.fileVersionDao()), FileIndexEventSink.None)
    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject
    private suspend fun privateEntries() = db.fileEntryDao().getAllStatesByWorkspaceRootForCleanup(privateRoot.uuid).map { it.materializedPath }

    @Test
    fun fileToolsReadAndWriteProjectFolderThroughDeviceBackend() = runBlocking<Unit> {
        val before = privateEntries()
        val files = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao())
        val directory = skill(CreateDirectorySkill(repository)).execute(args("""{"name":"src"}"""), execution)
        assertWithMessage(directory.content).that(directory.status).isEqualTo("success")
        val created = skill(CreateFileSkill(repository, files)).execute(args("""{"parentPath":"/src","name":"main.py","content":"print('一')\n"}"""), execution)
        assertWithMessage(created.content).that(created.status).isEqualTo("success")
        assertThat(File(project, "src/main.py").readText()).isEqualTo("print('一')\n")
        val read = skill(FileReadSkill(files, repository)).execute(args("""{"path":"/src/main.py"}"""), execution)
        assertThat(read.content).contains("1| print('一')")
        val hash = Sha256Utils.hash(File(project, "src/main.py").readBytes())
        val saved = skill(FileWriteSkill(files, repository)).execute(args("""{"path":"/src/main.py","content":"print(2)\n","expected_sha256":"$hash"}"""), execution)
        assertWithMessage(saved.content).that(saved.status).isEqualTo("success")
        assertThat(File(project, "src/main.py").readText()).isEqualTo("print(2)\n")
        assertThat(skill(FileListSkill(repository)).execute(args("""{"depth":2}"""), execution).content).contains("/src/main.py")
        assertThat(skill(DeleteFileSkill(repository)).execute(args("""{"path":"/src/main.py"}"""), execution).status).isEqualTo("success")
        assertThat(File(project, "src/main.py").exists()).isFalse()
        assertThat(privateEntries()).isEqualTo(before)
        assertThat(File(privateRoot.physicalRootPath, "src").exists()).isFalse()
    }

    @Test
    fun shellAndBuiltinGitRunInsideProjectFolder() = runBlocking<Unit> {
        val python = PackagedPython.from(context)
        val service = BuiltinExecService(
            workspace = repository,
            runner = BuiltinCommandRunner(
                groupLauncher = listOf("/system/bin/setsid"),
                shell = "/system/bin/sh",
                signaller = AndroidProcessGroupSignaller,
                scratchDirectory = { File(base, "scratch") },
            ),
            environment = BuiltinExecEnvironment(python, File(base, "home"), File(base, "tmp"), SystemCaBundle(File(base, "ca"))::ensure),
            sessionWorkspace = resolver::resolve,
        )
        val before = privateEntries()
        val shell = json(service.runShell(privateRoot.uuid, "s", "pwd; echo \"\$INFINITY_WORKSPACE_KIND\"; echo hi > note.txt", null, 60).toJson().toString())
        assertWithMessage(shell.toString()).that(shell.getValue("exit_code").jsonPrimitive.int).isEqualTo(0)
        assertThat(shell.getValue("stdout").jsonPrimitive.content.lines().take(2)).containsExactly(project.canonicalPath, "project").inOrder()
        assertThat(shell).doesNotContainKey("workspace_changes")
        assertThat(File(project, "note.txt").readText()).isEqualTo("hi\n")
        assumeTrue("当前 ABI 没有内置 Python，跳过内置 git", python.isAvailable)
        val git = json(service.runShell(
            privateRoot.uuid, "s",
            "git init . && git config user.name t && git config user.email t@example.com && git add note.txt && " +
                "git commit -m init >/dev/null && git log --oneline | wc -l",
            null, 120,
        ).toJson().toString())
        assertWithMessage(git.toString()).that(git.getValue("exit_code").jsonPrimitive.int).isEqualTo(0)
        assertThat(git.getValue("stdout").jsonPrimitive.content.trim()).isEqualTo("1")
        assertThat(File(project, ".git/HEAD").isFile).isTrue()
        assertThat(privateEntries()).isEqualTo(before)
    }

    @Test
    fun missingProjectFolderIsRejectedWithoutFallback() = runBlocking<Unit> {
        project.deleteRecursively()
        val result = skill(CreateFileSkill(repository, FileOperationRepository(db.fileEntryDao(), db.fileVersionDao())))
            .execute(args("""{"name":"leak.txt","content":"x"}"""), execution)
        assertThat(result.status).isEqualTo("error")
        assertThat(File(privateRoot.physicalRootPath, "leak.txt").exists()).isFalse()
        assertThat(resolver.resolve("s")).isInstanceOf(SessionWorkspace.Unavailable::class.java)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
