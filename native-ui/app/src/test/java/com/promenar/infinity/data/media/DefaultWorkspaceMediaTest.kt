package com.promenar.infinity.data.media

import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.*
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.*
import com.promenar.infinity.data.storage.*
import com.promenar.infinity.data.workspace.*
import com.promenar.infinity.data.production.DefaultProductionWorkspaceFiles
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.media.*
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.*
import kotlinx.coroutines.*
import io.mockk.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class DefaultWorkspaceMediaTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var directory: File
    private lateinit var privateRoot: File
    private lateinit var skillsRoot: File
    private lateinit var db: InfinityDatabase
    private lateinit var repo: WorkspaceRepository
    private lateinit var roots: SessionWorkspaceResolver
    private lateinit var mounts: ExternalWorkspaceAccess
    private lateinit var service: DefaultWorkspaceMedia
    private lateinit var root: FileEntry
    private lateinit var provider: ExternalFolderTestDocumentsProvider
    private val events = mutableListOf<FileIndexEvent>()
    private val clock = Clock.fixed(Instant.parse("2026-10-19T16:32:00Z"), ZoneId.of("Asia/Shanghai"))
    private val fs = JvmBoundRealPathFileSystem()
    private val png by lazy {
        ByteArrayOutputStream().use { out ->
            val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    @Before fun setup() = runBlocking {
        directory = Files.createTempDirectory("workspace-media").toFile().canonicalFile
        privateRoot = File(directory, "private")
        skillsRoot = File(context.filesDir, "agent_skills").canonicalFile.apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        db.sessionDao().insert(SessionEntity(id = "session", agentId = "agent", workspacePath = privateRoot.path, createdAt = 1, updatedAt = 1))
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(), deleteCommitter = WorkspaceDeletionTransaction(db)::delete)
        root = repo.ensureSessionRoot("session")
        roots = SessionWorkspaceResolver(db.sessionDao(), db.workspaceDao(), File(directory, "projects"), object : SharedWorkspaceAccess {
            override fun root(location: String) = File(location)
            override fun backend(root: File) = real(root)
        }, ::real)
        mounts = ExternalFolderManager(context, db.externalMountDao(), repo, FileIndexEventSink.None, roots, skillsRoot) { mount ->
            when {
                mount.isUserSkills -> real(skillsRoot)
                mount.sourceType == "real_path" -> real(File(mount.sourceId))
                mount.isWorkspaceRoot -> real(File(directory, "projects/project"))
                mount.sourceType == "saf_tree" -> SafExternalFolderBackend(context.contentResolver, Uri.parse(mount.sourceId))
                else -> null
            }
        }
        val info = ProviderInfo().apply {
            authority = AUTHORITY; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        provider = Robolectric.buildContentProvider(ExternalFolderTestDocumentsProvider::class.java).create(info).get()
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        service = newService()
    }

    private fun real(root: File) = RealPathExternalFolderBackend(root, emptySet(), { true }, fs)
    private fun newService(workspace: IWorkspaceRepository = repo, index: FileIndexEventSink = FileIndexEventSink { events.add(it) }, skillsDirectory: File = skillsRoot,
        presenter: WorkspaceFilePathPresenter = BuiltinWorkspaceFilePathPresenter, linuxFiles: com.promenar.infinity.domain.linux.LinuxFileSystem? = null) =
        DefaultWorkspaceMedia(context, SessionRepository(db.sessionDao(), db.messageDao()), workspace, mounts, roots::resolve, index, clock, fs, skillsDirectory, presenter, modeFor = if (presenter == BuiltinWorkspaceFilePathPresenter) { id -> com.promenar.infinity.domain.model.SessionRunMode.fromStored(db.sessionDao().getById(id)?.runMode) } else null, directoryBackend = roots::backend, linuxFiles = linuxFiles)

    @After fun cleanup() {
        db.close()
        Files.walk(directory.toPath()).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        if (::provider.isInitialized) provider.root.deleteRecursively()
        File(skillsRoot, "media-test.png").delete()
    }

    private suspend fun privateFile(path: String, bytes: ByteArray = png): FileEntry {
        val entry = repo.createFileInWorkspaceStreaming(root.uuid, "f-${System.nanoTime()}", path.substringAfterLast('/'), "application/octet-stream", root.uuid, path, 1024 * 1024) { it.write(bytes) }
        repo.confirmCreatedEntry(root.uuid, entry.uuid)
        return entry
    }
    private suspend fun addMount(alias: String, source: String, type: String = "real_path", readOnly: Boolean = false) {
        db.externalMountDao().insert(ExternalMountEntity("id-$alias", alias, type, source, "session", readOnly, 1))
    }
    private suspend fun bindDirectory(): File {
        val project = File(directory, "projects/project").apply { mkdirs() }
        db.workspaceDao().insert(WorkspaceEntity("project-id", WorkspaceEntity.KIND_PROJECT, "项目", "project", 1))
        db.sessionDao().update(db.sessionDao().getById("session")!!.copy(workspaceId = "project-id"))
        return project
    }
    private suspend fun available(path: String) = service.resolve("session", path).let {
        assertThat(it).isInstanceOf(MediaResolution.Available::class.java)
        it as MediaResolution.Available
    }
    private suspend fun reason(path: String, expected: MediaUnavailableReason) {
        assertThat((service.resolve("session", path) as MediaResolution.Unavailable).reason).isEqualTo(expected)
    }
    private suspend fun code(expected: String, block: suspend () -> Unit) {
        val failure = try { block(); null } catch (failure: WorkspaceMediaException) { failure }
        assertThat(failure?.code).isEqualTo(expected)
        assertThat(failure?.message).isNotEmpty()
    }

    @Test fun `制作媒体与清单位于同一会话私有根和挂载`() = runBlocking {
        val files = DefaultProductionWorkspaceFiles(repo, mockk<IFileOperationRepository>(), mounts, service)
        val path = "productions/p/takes/t01.mp4"
        files.write("session", path, byteArrayOf(1, 2, 3), null)
        db.sessionDao().updateRunMode("session", "LINUX", 2)
        assertThat(service.productionPath("session", path)).isEqualTo("/home/session/$path")
        assertThat(files.isImageOrVideo("session", path)).isTrue()
        db.sessionDao().updateRunMode("session", "BUILTIN", 3)
        assertThat(service.productionPath("session", path)).isEqualTo("/$path")
        assertThat(files.isImageOrVideo("session", path)).isTrue()
        val project = bindDirectory()
        val privatePath = "session/$path"
        files.write("session", privatePath, byteArrayOf(1, 2, 3), null)
        assertThat(service.productionPath("session", privatePath)).isEqualTo("/session/$privatePath")
        assertThat(files.isImageOrVideo("session", privatePath)).isTrue()
        File(project, path).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        assertThat(files.isImageOrVideo("session", path)).isTrue()
        db.sessionDao().updateRunMode("session", "LINUX", 4)
        assertThat(service.productionPath("session", path)).isEqualTo("/workspace/$path")
        assertThat(files.isImageOrVideo("session", path)).isTrue()
        assertThat(service.productionPath("session", privatePath)).isEqualTo("/home/session/$privatePath")
        assertThat(files.isImageOrVideo("session", privatePath)).isTrue()
        val mounted = File(directory, "mounted").apply { mkdirs() }
        File(mounted, "a.png").writeBytes(png)
        addMount("素材", mounted.path)
        assertThat(service.productionPath("session", "mnt/素材/a.png")).isEqualTo("/mnt/素材/a.png")
        assertThat(files.isImageOrVideo("session", "mnt/素材/a.png")).isTrue()
        db.sessionDao().updateRunMode("session", "BUILTIN", 5)
        assertThat(files.isImageOrVideo("session", "mnt/素材/a.png")).isTrue()
    }




    @Test fun `媒体写入可用性覆盖缺失会话且标注使用独立命名前缀`() = runBlocking {
        assertThat(service.canSave("session")).isTrue()
        assertThat(service.canSave("missing")).isFalse()
        val saved = service.save("session", MediaPayload.Bytes(png), MediaOrigin.ANNOTATION, "image/png")
        assertThat(saved.path.substringAfterLast('/')).startsWith("annot-")
        assertThat(service.resolve("session", saved.path)).isInstanceOf(MediaResolution.Available::class.java)
    }

    @Test fun `Linux私有主根双入口同源且SAF和workspace内同名虚拟根不串根`() = runBlocking {
        db.sessionDao().updateRunMode("session", "LINUX", 2)
        privateFile("/a.png")
        val home = available("/home/session/a.png")
        assertThat(available("/workspace/a.png").source).isEqualTo(home.source)
        val saved = service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED)
        assertThat(saved.path).startsWith("/home/session/media/")
        val project = bindDirectory()
        File(project, "session/a.png").apply { parentFile!!.mkdirs(); writeBytes(png) }
        assertThat((available("/workspace/session/a.png").source as MediaSource.LocalFile).file).isEqualTo(File(project, "session/a.png"))
        addMount("文档", DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root").toString(), "saf_tree")
        File(provider.root, "a.png").writeBytes(png)
        assertThat(service.resolve("session", "/mnt/文档/a.png")).isInstanceOf(MediaResolution.Unavailable::class.java)
        code("PROTECTED_PATH") { service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED, targetPath = "/workspace/.agents/skills/a.png") }
    }


    @Test fun `Linux自选目录媒体使用实际路径且技能路径保持保护`() = runBlocking {
        val project = bindDirectory()
        val identity = PersistentFileIdentity.encode("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "ino=1")
        val selected = SessionWorkspace.Directory(WorkspaceEntity("linux", WorkspaceEntity.KIND_LINUX_HOME, "app", "/opt/app#$identity", 0), project)
        service = DefaultWorkspaceMedia(context, SessionRepository(db.sessionDao(), db.messageDao()), repo, mounts,
            { selected }, FileIndexEventSink.None, clock, fs, skillsRoot, LinuxWorkspaceFilePathPresenter,
            directoryBackend = { real(it.root) })
        val saved = service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED, targetPath = "/opt/app/picture.png")
        assertThat(saved.path).isEqualTo("/opt/app/picture.png")
        assertThat(File(project, "picture.png").readBytes()).isEqualTo(png)
        assertThat((available("/opt/app/picture.png").source as MediaSource.LocalFile).file).isEqualTo(File(project, "picture.png"))
        val relative = service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED, targetPath = "relative.png")
        assertThat(relative.path).isEqualTo("/opt/app/relative.png")
        code("PROTECTED_PATH") { service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED, targetPath = "/opt/app/.agents/skills/a.png") }
    }

    @Test fun `Linux媒体暂存期间主工作区改变时拒绝写入旧根`() = runBlocking {
        db.sessionDao().updateRunMode("session", "LINUX", 2)
        val project = bindDirectory()
        val next = File(directory, "projects/next").apply { mkdirs() }
        db.workspaceDao().insert(WorkspaceEntity("next-id", WorkspaceEntity.KIND_PROJECT, "新项目", "next", 1))
        code("WORKSPACE_UNAVAILABLE") {
            service.save("session", MediaPayload.Stream {
                runBlocking { db.sessionDao().update(db.sessionDao().getById("session")!!.copy(workspaceId = "next-id")) }
                png.inputStream()
            }, MediaOrigin.GENERATED)
        }
        assertThat(File(project, "media").exists()).isFalse()
        assertThat(File(next, "media").exists()).isFalse()
    }


    @Test fun `Linux同名嵌套目录显式保存只剥一次命名空间前缀`() = runBlocking {
        db.sessionDao().updateRunMode("session", "LINUX", 2)
        val private = service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED, targetPath = "/home/session/home/session/a.png")
        assertThat(File(privateRoot, "home/session/a.png").readBytes()).isEqualTo(png)
        assertThat(File(privateRoot, "a.png").exists()).isFalse()
        assertThat(service.open(available(private.path)).use { it.readBytes() }).isEqualTo(png)
        val project = bindDirectory()
        val main = service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED, targetPath = "/workspace/workspace/a.png")
        assertThat(File(project, "workspace/a.png").readBytes()).isEqualTo(png)
        assertThat(File(project, "a.png").exists()).isFalse()
        assertThat(service.open(available(main.path)).use { it.readBytes() }).isEqualTo(png)
    }

    @Test fun `Linux主根与真实挂载同路径重建不能继承已解析媒体`() = runBlocking {
        db.sessionDao().updateRunMode("session", "LINUX", 2)
        val project = bindDirectory()
        val album = File(directory, "album").apply { mkdirs() }
        addMount("相册", album.path)
        for ((path, rootDirectory) in mapOf("/workspace/a.png" to project, "/mnt/相册/a.png" to album)) {
            File(rootDirectory, "a.png").writeBytes(png)
            val resolved = available(path)
            Files.move(rootDirectory.toPath(), File(rootDirectory.parentFile, rootDirectory.name + "-old").toPath())
            rootDirectory.mkdir()
            File(rootDirectory, "a.png").writeBytes(png)
            assertThat(runCatching { service.open(resolved) }.isFailure).isTrue()
            assertThat(service.open(available(path)).use { it.readBytes() }).isEqualTo(png)
        }
    }

    @Test fun `Linux主根媒体保存使用workspace且同一解析器逐会话读取持久模式`() = runBlocking {
        db.sessionDao().updateRunMode("session", "LINUX", 2)
        val project = bindDirectory()
        val ref = service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED)
        assertThat(ref.path).startsWith("/workspace/media/")
        assertThat(File(project, ref.path.removePrefix("/workspace/")).readBytes()).isEqualTo(png)
        val resolved = available(ref.path)
        db.sessionDao().updateRunMode("session", "BUILTIN", 2)
        assertThat(runCatching { service.open(resolved) }.isFailure).isTrue()
        val builtin = service.save("session", MediaPayload.Bytes(png), MediaOrigin.DOWNLOAD)
        assertThat(builtin.path).startsWith("/media/")
    }

    @Test fun `Linux rootfs媒体安全只读且根重建后引用失效`() = runBlocking {
        db.sessionDao().updateRunMode("session", "LINUX", 2)
        val rootfs = File(directory, "rootfs").apply { mkdirs() }
        File(rootfs, "root/a.png").apply { parentFile!!.mkdirs(); writeBytes(png) }
        val files = com.promenar.infinity.data.linux.LinuxFileSystemImpl(rootfs, { true }, com.promenar.infinity.data.linux.TestLinuxFileMetadata)
        service = newService(linuxFiles = files)
        val resolved = available("/root/a.png")
        assertThat(service.open(resolved).use { it.readBytes() }).isEqualTo(png)
        code("READ_ONLY") { service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED, targetPath = "/root/b.png") }
        Files.createSymbolicLink(File(rootfs, "root/link.png").toPath(), File(rootfs, "root/a.png").toPath())
        assertThat(service.resolve("session", "/root/link.png")).isInstanceOf(MediaResolution.Unavailable::class.java)
        Files.move(rootfs.toPath(), File(directory, "old-rootfs").toPath())
        File(rootfs, "root").mkdirs()
        File(rootfs, "root/a.png").writeBytes(png)
        assertThat(runCatching { service.open(resolved) }.isFailure).isTrue()
    }

    @Test fun `Linux生图和下载返回home路径且可重新打开`() = runBlocking {
        db.sessionDao().updateRunMode("session", com.promenar.infinity.domain.model.SessionRunMode.LINUX.name, 2)
        bindDirectory()
        addMount("相册", File(directory, "album").apply { mkdirs() }.path)
        for (origin in listOf(MediaOrigin.GENERATED, MediaOrigin.DOWNLOAD)) {
            val ref = service.save("session", MediaPayload.Bytes(png), origin)
            assertThat(ref.path).startsWith("/home/session/media/")
            assertThat(ref.path.startsWith("/session/")).isFalse()
            assertThat(service.open(available(ref.path)).use { it.readBytes() }).isEqualTo(png)
        }
    }

    @Test fun `Linux媒体解析home与workspace和真实挂载且不猜测其它根`() = runBlocking {
        db.sessionDao().updateRunMode("session", com.promenar.infinity.domain.model.SessionRunMode.LINUX.name, 2)
        privateFile("/a.png")
        val project = bindDirectory()
        File(project, "a.png").writeBytes(png)
        val album = File(directory, "album").apply { mkdirs() }
        File(album, "a.png").writeBytes(png)
        addMount("相册", album.path)
        for ((path, expected) in mapOf("/home/session/a.png" to File(privateRoot, "a.png"),
            "/workspace/a.png" to File(project, "a.png"), "/mnt/相册/a.png" to File(album, "a.png"))) {
            val resolved = available(path)
            assertThat((resolved.source as MediaSource.LocalFile).file).isEqualTo(expected)
            assertThat(service.open(resolved).use { it.readBytes() }).isEqualTo(png)
        }
        assertThat(service.resolve("session", "/home/session/missing.png")).isInstanceOf(MediaResolution.Unavailable::class.java)
        assertThat(service.resolve("session", "/session/a.png")).isInstanceOf(MediaResolution.Unavailable::class.java)
        assertThat(service.resolve("session", "/mnt/missing/a.png")).isInstanceOf(MediaResolution.Unavailable::class.java)
    }

    @Test fun `私有路径解析真实来源和索引哈希且读取图片尺寸`() = runBlocking {
        val indexed = privateFile("/a.png")
        val result = available("a.png")
        assertThat(result.ref.path).isEqualTo("/a.png")
        assertThat(result.ref.mimeType).isEqualTo("image/png")
        assertThat(result.ref.kind).isEqualTo(MediaKind.IMAGE)
        assertThat(result.ref.sizeBytes).isEqualTo(png.size.toLong())
        assertThat(result.ref.sha256).isEqualTo(indexed.hash)
        assertThat(result.ref.width).isEqualTo(3)
        assertThat(result.ref.height).isEqualTo(2)
        assertThat((result.source as MediaSource.LocalFile).file).isEqualTo(File(privateRoot, "a.png"))
        assertThat(service.open(result).use { it.readBytes() }).isEqualTo(png)
    }

    @Test fun `根模式和真实挂载与用户技能均按同一命名空间解析`() = runBlocking {
        val project = bindDirectory()
        File(project, "a.png").writeBytes(png)
        val album = File(directory, "album").apply { mkdirs() }
        File(album, "a.png").writeBytes(png)
        addMount("相册", album.path)
        File(skillsRoot, "media-test.png").writeBytes(png)
        for ((path, file) in mapOf("a.png" to File(project, "a.png"), "/mnt/相册/a.png" to File(album, "a.png"), "/skills/media-test.png" to File(skillsRoot, "media-test.png"))) {
            assertThat((available(path).source as MediaSource.LocalFile).file).isEqualTo(file)
        }
        privateFile("/private-only.png")
        reason("/private-only.png", MediaUnavailableReason.NOT_FOUND)
    }

    @Test fun `SAF按文档标识解析ContentUri并打开无上限流`() = runBlocking {
        val file = File(provider.root, "大文件.bin")
        RandomAccessFile(file, "rw").use { it.setLength(9L * 1024 * 1024) }
        addMount("文档", DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root").toString(), "saf_tree")
        val result = available("/mnt/文档/大文件.bin")
        assertThat(result.source).isInstanceOf(MediaSource.ContentUri::class.java)
        assertThat(result.ref.sha256).isNull()
        assertThat(service.open(result).use { input -> input.copyTo(java.io.OutputStream.nullOutputStream()) }).isEqualTo(9L * 1024 * 1024)
        File(provider.root, "picture.png").writeBytes(png)
        val image = available("/mnt/文档/picture.png")
        assertThat(image.ref.width).isEqualTo(3)
        assertThat(image.ref.height).isEqualTo(2)
        provider.revoked = true
        reason("/mnt/文档/picture.png", MediaUnavailableReason.UNREADABLE)
    }

    @Test fun `非法路径保留目录和URI不能作为虚拟路径`() = runBlocking {
        for (path in listOf("file:/a.png", "content://provider/a", "https://host/a", "custom:a", " /../a", "../a", "/.infinity-root/a", "/.RECYCLE_BIN/a", "a\\b", "a\u0000b", "/mnt/skills/a")) {
            reason(path, MediaUnavailableReason.INVALID_PATH)
        }
    }

    @Test fun `不存在目录失效挂载及工作区不可用原因各自正确`() = runBlocking {
        reason("missing.png", MediaUnavailableReason.NOT_FOUND)
        reason("/", MediaUnavailableReason.IS_DIRECTORY)
        reason("/mnt", MediaUnavailableReason.IS_DIRECTORY)
        reason("/mnt/missing/a.png", MediaUnavailableReason.UNREADABLE)
        val project = bindDirectory()
        project.deleteRecursively()
        reason("a.png", MediaUnavailableReason.UNREADABLE)
        reason("/skills/a.png", MediaUnavailableReason.UNREADABLE)
        code("WORKSPACE_UNAVAILABLE") { service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED) }
    }

    @Test fun `解析和重新打开均拒绝文件及父目录符号链接`() = runBlocking {
        privateFile("/a.png")
        val resolution = available("/a.png")
        File(privateRoot, "a.png").delete()
        val outside = File(directory, "outside.png").apply { writeBytes(png) }
        Files.createSymbolicLink(File(privateRoot, "a.png").toPath(), outside.toPath())
        reason("/a.png", MediaUnavailableReason.INVALID_PATH)
        assertThat(runCatching { service.open(resolution) }.isFailure).isTrue()
        val folder = repo.createDirectoryInWorkspace(root.uuid, "dir", "dir", root.uuid, "/dir")
        repo.createFileInWorkspaceStreaming(root.uuid, "nested", "a.png", "image/png", folder.uuid, "/dir/a.png", 1024) { it.write(png) }
        File(privateRoot, "dir").deleteRecursively()
        Files.createSymbolicLink(File(privateRoot, "dir").toPath(), directory.toPath())
        reason("/dir/a.png", MediaUnavailableReason.INVALID_PATH)
    }

    @Test fun `大文件本地读取不受八MiB限制且元数据失败不影响可用性`() = runBlocking {
        privateFile("/large.mp4", "bad".toByteArray())
        RandomAccessFile(File(privateRoot, "large.mp4"), "rw").use { it.setLength(9L * 1024 * 1024) }
        val result = available("large.mp4")
        assertThat(result.ref.kind).isEqualTo(MediaKind.VIDEO)
        assertThat(result.ref.durationMs).isNull()
        assertThat(service.open(result).use { it.copyTo(java.io.OutputStream.nullOutputStream()) }).isEqualTo(9L * 1024 * 1024)
    }

    @Test fun `两个会话的等值媒体引用保持独立授权绑定`() = runBlocking {
        val album = File(directory, "album").apply { mkdirs() }
        File(album, "a.png").writeBytes(png)
        addMount("共享", album.path)
        db.sessionDao().insert(SessionEntity(id = "second", agentId = "agent", createdAt = 1, updatedAt = 1))
        db.externalMountDao().insert(ExternalMountEntity("id-second", "共享", "real_path", album.path, "second", false, 1))
        val first = available("/mnt/共享/a.png")
        val second = service.resolve("second", "/mnt/共享/a.png") as MediaResolution.Available
        assertThat(first).isEqualTo(second)
        db.externalMountDao().delete("id-共享")
        assertThat(runCatching { service.open(first) }.isFailure).isTrue()
        assertThat(service.open(second).use { it.readBytes() }).isEqualTo(png)
    }

    @Test fun `可信应用目录的父路径别名不导致用户技能不可读`() = runBlocking {
        File(skillsRoot, "media-test.png").writeBytes(png)
        val alias = File(directory, "files-alias")
        Files.createSymbolicLink(alias.toPath(), skillsRoot.parentFile!!.toPath())
        service = newService(skillsDirectory = File(alias, "agent_skills"))
        assertThat(available("/skills/media-test.png").ref.kind).isEqualTo(MediaKind.IMAGE)
    }

    @Test fun `默认目录按本地日期创建且同名后缀从二开始`() = runBlocking {
        val one = service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED)
        val two = service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED)
        assertThat(one.path).matches("/media/2026-10-20/gen-0032-[0-9a-f]{6}\\.png")
        assertThat(two.path).isEqualTo(one.path.removeSuffix(".png") + "-2.png")
        assertThat(one.mimeType).isEqualTo("image/png")
        assertThat(one.kind).isEqualTo(MediaKind.IMAGE)
        assertThat(one.width).isEqualTo(3)
        assertThat(one.height).isEqualTo(2)
        assertThat(one.sha256).hasLength(64)
        assertThat(one.sizeBytes).isEqualTo(png.size.toLong())
        val entry = repo.getByMaterializedPath(root.uuid, one.path)!!
        assertThat(entry.hash).isEqualTo(one.sha256)
        // 图片不在向量化支持范围内，不发布索引事件。
        assertThat(events.filterIsInstance<FileIndexEvent.Changed>().map { it.fileUuid }).doesNotContain(entry.uuid)
        assertThat(File(privateRoot, one.path.trimStart('/')).readBytes()).isEqualTo(png)
    }

    @Test fun `登记工作区只有技能挂载时默认媒体保存在主工作区`() = runBlocking {
        val project = bindDirectory()
        val saved = service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED)
        assertThat(File(project, saved.path.trimStart('/')).readBytes()).isEqualTo(png)
        assertThat(repo.getByMaterializedPath(root.uuid, saved.path)).isNull()
    }

    @Test fun `登记工作区有用户挂载时默认媒体进入可重新解析的会话专用空间`() = runBlocking {
        val project = bindDirectory()
        addMount("素材", File(directory, "assets").apply { mkdirs() }.path)
        val saved = service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED)
        assertThat(saved.path).startsWith("/session/media/")
        assertThat(File(privateRoot, saved.path.removePrefix("/session/")).readBytes()).isEqualTo(png)
        assertThat(File(project, "media").exists()).isFalse()
        assertThat(service.open(available(saved.path)).use { it.readBytes() }).isEqualTo(png)
        val explicit = service.save("session", MediaPayload.Bytes(png), MediaOrigin.DOWNLOAD, targetPath = "/media/explicit.png")
        assertThat(explicit.path).isEqualTo("/media/explicit.png")
        assertThat(File(project, "media/explicit.png").readBytes()).isEqualTo(png)
    }

    @Test fun `运行模式可替换私有目标路径呈现与反向解析`() = runBlocking {
        val project = bindDirectory()
        addMount("素材", File(directory, "assets").apply { mkdirs() }.path)
        service = newService(presenter = object : WorkspaceFilePathPresenter {
            override fun present(destination: WorkspaceDefaultFileDestination, relativePath: String, privatePrimary: Boolean) =
                if (destination == WorkspaceDefaultFileDestination.SESSION_PRIVATE) "/home/session/$relativePath".replace("//", "/") else relativePath
            override fun privateRelativePath(path: String) = when {
                path == "/home/session" -> "/"
                path.startsWith("/home/session/") -> path.removePrefix("/home/session")
                else -> SessionPrivateWorkspacePaths.relative(path)
            }
        })
        val saved = service.save("session", MediaPayload.Bytes(png), MediaOrigin.MCP)
        assertThat(saved.path).startsWith("/home/session/media/")
        assertThat(service.open(available(saved.path)).use { it.readBytes() }).isEqualTo(png)
        assertThat(File(project, "home").exists()).isFalse()
    }

    @Test fun `登记主工作区会话首次默认媒体保存按需创建私有根`() = runBlocking {
        bindDirectory()
        val targetRoot = File(directory, "new-private")
        db.sessionDao().insert(SessionEntity(id = "new", agentId = "agent", workspaceId = "project-id", workspacePath = targetRoot.path, createdAt = 1, updatedAt = 1))
        val assets = File(directory, "assets").apply { mkdirs() }
        db.externalMountDao().insert(ExternalMountEntity("new-assets", "素材", "real_path", assets.path, "new", false, 1))
        val saved = service.save("new", MediaPayload.Bytes(png), MediaOrigin.DOWNLOAD)
        assertThat(saved.path).startsWith("/session/media/")
        assertThat(db.sessionDao().getById("new")?.workspaceRootUuid).isNotNull()
        assertThat(File(targetRoot, saved.path.removePrefix("/session/")).readBytes()).isEqualTo(png)
    }

    @Test fun `默认主根在落点判定与首次解析之间切换时拒绝异根保存`() = runBlocking {
        val original = bindDirectory()
        val next = File(directory, "projects/next").apply { mkdirs() }
        db.workspaceDao().insert(WorkspaceEntity("next-id", WorkspaceEntity.KIND_PROJECT, "新项目", "next", 1))
        var resolutions = 0
        service = DefaultWorkspaceMedia(context, SessionRepository(db.sessionDao(), db.messageDao()), repo, mounts, { session ->
            if (++resolutions == 2) db.sessionDao().update(db.sessionDao().getById(session)!!.copy(workspaceId = "next-id"))
            roots.resolve(session)
        }, FileIndexEventSink.None, clock, fs, skillsRoot)
        code("WORKSPACE_UNAVAILABLE") { service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED) }
        assertThat(File(original, "media").exists()).isFalse()
        assertThat(File(next, "media").exists()).isFalse()
        assertThat(File(privateRoot, "media").exists()).isFalse()
    }

    @Test fun `显式路径规范化并创建父目录且已有目标不覆盖`() = runBlocking {
        val one = service.save("session", MediaPayload.Bytes(png), MediaOrigin.MCP, targetPath = "folder//picture.jpg")
        assertThat(one.path).isEqualTo("/folder/picture.jpg")
        assertThat(one.mimeType).isEqualTo("image/png")
        code("EXISTS") { service.save("session", MediaPayload.Bytes(byteArrayOf(1)), MediaOrigin.MCP, targetPath = one.path) }
        assertThat(File(privateRoot, "folder/picture.jpg").readBytes()).isEqualTo(png)
    }

    @Test fun `头部优先于提示且未知类型使用提示或建议名`() = runBlocking {
        val one = service.save("session", MediaPayload.Bytes(png), MediaOrigin.FRAME, "audio/mpeg", "wrong.mp3")
        assertThat(one.path).endsWith(".png")
        assertThat(one.kind).isEqualTo(MediaKind.IMAGE)
        val hinted = service.save("session", MediaPayload.Bytes(byteArrayOf(1)), MediaOrigin.DOWNLOAD, "audio/mpeg", "wrong.png")
        assertThat(hinted.path).endsWith(".mp3")
        val named = service.save("session", MediaPayload.Bytes(byteArrayOf(2)), MediaOrigin.MCP, suggestedName = "voice.M4A")
        assertThat(named.mimeType).isEqualTo("audio/mp4")
        assertThat(named.path).endsWith(".m4a")
        val unknown = service.save("session", MediaPayload.Bytes(byteArrayOf(3)), MediaOrigin.DOWNLOAD, suggestedName = "file.foo")
        assertThat(unknown.path).endsWith(".foo")
        assertThat(unknown.kind).isEqualTo(MediaKind.FILE)
    }

    @Test fun `只读挂载和两级技能目录拒绝且不打开载荷`() = runBlocking {
        val album = File(directory, "readonly").apply { mkdirs() }
        addMount("只读", album.path, readOnly = true)
        var opened = false
        val payload = MediaPayload.Stream { opened = true; png.inputStream() }
        for (path in listOf("/skills/a.png", "/skills", "/.agents/skills/a.png", "/.agents/skills", "/mnt/只读/.agents/skills/a.png")) {
            code("PROTECTED_PATH") { service.save("session", payload, MediaOrigin.MCP, targetPath = path) }
        }
        code("INVALID_PATH") { service.save("session", payload, MediaOrigin.MCP, targetPath = "https://host/a") }
        assertThat(opened).isFalse()
        assertThat(album.list()).isEmpty()
        code("READ_ONLY") { service.save("session", payload, MediaOrigin.MCP, targetPath = "/mnt/只读/a.png") }
        assertThat(opened).isFalse()
        assertThat(File(album, "a.png").exists()).isFalse()
    }

    @Test fun `流载荷在成功和读取失败时关闭且清理缓存`() = runBlocking {
        val before = context.cacheDir.list()!!.toSet()
        var closed = false
        service.save("session", MediaPayload.Stream { object : java.io.ByteArrayInputStream(png) { override fun close() { closed = true; super.close() } } }, MediaOrigin.MCP)
        assertThat(closed).isTrue()
        assertThat(context.cacheDir.list()!!.toSet()).isEqualTo(before)
        closed = false
        code("IO") { service.save("session", MediaPayload.Stream { object : InputStream() {
            override fun read(): Int = throw java.io.IOException("测试读取失败")
            override fun close() { closed = true }
        } }, MediaOrigin.MCP) }
        assertThat(closed).isTrue()
        assertThat(context.cacheDir.list()!!.toSet()).isEqualTo(before)
    }

    @Test fun `超限流立即停止不创建目标并清理缓存`() = runBlocking {
        val before = context.cacheDir.list()!!.toSet()
        var read = 0L
        var closed = false
        code("TOO_LARGE") { service.save("session", MediaPayload.Stream { object : InputStream() {
            override fun read() = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { read += length; java.util.Arrays.fill(bytes, offset, offset + length, 0); return length }
            override fun close() { closed = true }
        } }, MediaOrigin.MCP, targetPath = "/not-created/a.bin") }
        assertThat(read).isAtMost(256L * 1024 * 1024 + 64 * 1024)
        assertThat(closed).isTrue()
        assertThat(repo.getByMaterializedPath(root.uuid, "/not-created")).isNull()
        assertThat(File(privateRoot, "not-created").exists()).isFalse()
        assertThat(context.cacheDir.list()!!.toSet()).isEqualTo(before)
    }

    @Test fun `同会话并发落地无覆盖且序号唯一`() = runBlocking {
        val refs = (1..5).map { async(Dispatchers.Default) { service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED) } }.awaitAll()
        assertThat(refs.map { it.path }.toSet()).hasSize(5)
        assertThat(refs.map { File(privateRoot, it.path.trimStart('/')).readBytes().toList() }.distinct()).hasSize(1)
    }

    @Test fun `根模式与SAF目标经现有外部写入可读取`() = runBlocking {
        val project = bindDirectory()
        val rootRef = service.save("session", MediaPayload.Bytes(png), MediaOrigin.DOWNLOAD, targetPath = "nested/root.png")
        assertThat(File(project, "nested/root.png").readBytes()).isEqualTo(png)
        assertThat(repo.getByMaterializedPath(root.uuid, rootRef.path)).isNull()
        addMount("文档", DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root").toString(), "saf_tree")
        val saf = service.save("session", MediaPayload.Stream { png.inputStream() }, MediaOrigin.MCP, targetPath = "/mnt/文档/nested/a.png")
        assertThat(File(provider.root, "nested/a.png").readBytes()).isEqualTo(png)
        assertThat(saf.width).isEqualTo(3)
        assertThat(available(saf.path).source).isInstanceOf(MediaSource.ContentUri::class.java)
    }

    @Test fun `SAF音视频元数据使用文档描述符读取时长及旋转尺寸`() = runBlocking {
        mockkConstructor(MediaMetadataRetriever::class)
        try {
            every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<java.io.FileDescriptor>()) } just Runs
            every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<java.io.FileDescriptor>(), any<Long>(), any<Long>()) } just Runs
            every { anyConstructed<MediaMetadataRetriever>().extractMetadata(any()) } returns null
            every { anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) } returns "4200"
            every { anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) } returns "640"
            every { anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) } returns "360"
            every { anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) } returns "90"
            every { anyConstructed<MediaMetadataRetriever>().release() } just Runs
            every { anyConstructed<MediaMetadataRetriever>().close() } just Runs
            addMount("文档", DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root").toString(), "saf_tree")
            File(provider.root, "movie.mp4").writeText("invalid")
            File(provider.root, "voice.m4a").writeText("invalid")
            val movie = available("/mnt/文档/movie.mp4").ref
            assertThat(movie.durationMs).isEqualTo(4200)
            assertThat(movie.width).isEqualTo(360)
            assertThat(movie.height).isEqualTo(640)
            val voice = available("/mnt/文档/voice.m4a").ref
            assertThat(voice.durationMs).isEqualTo(4200)
            assertThat(voice.width).isNull()
        } finally { unmockkConstructor(MediaMetadataRetriever::class) }
    }

    @Test fun `可索引资源发布索引事件且发布失败回滚私有文件`() = runBlocking {
        val text = "MCP 文本资源".toByteArray()
        val saved = service.save("session", MediaPayload.Bytes(text), MediaOrigin.MCP, "text/plain", "note.txt", "/note.txt")
        val entry = repo.getByMaterializedPath(root.uuid, saved.path)!!
        assertThat(events.filterIsInstance<FileIndexEvent.Changed>().map { it.fileUuid }).contains(entry.uuid)

        service = newService(index = FileIndexEventSink { throw java.io.IOException("测试索引失败") })
        code("IO") { service.save("session", MediaPayload.Bytes(text), MediaOrigin.MCP, "text/plain", "a.txt", "/a.txt") }
        assertThat(repo.getByMaterializedPath(root.uuid, "/a.txt")).isNull()
        assertThat(File(privateRoot, "a.txt").exists()).isFalse()
    }

    @Test fun `媒体不进入索引队列且索引接收端拒绝不影响保存`() = runBlocking {
        // 设备上的向量化队列对图片、音视频 MIME 直接拒绝；媒体写入不能因此失败。
        service = newService(index = FileIndexEventSink { throw IllegalArgumentException("不支持索引 MIME") })
        val saved = service.save("session", MediaPayload.Bytes(png), MediaOrigin.DOWNLOAD, targetPath = "/media/test/a.png")
        assertThat(saved.path).isEqualTo("/media/test/a.png")
        assertThat(File(privateRoot, "media/test/a.png").readBytes()).isEqualTo(png)
    }

    @Test fun `取消必须传播且流和缓存都会清理`() = runBlocking {
        val before = context.cacheDir.list()!!.toSet()
        var closed = false
        val failure = runCatching { service.save("session", MediaPayload.Stream { object : InputStream() {
            override fun read(): Int = throw CancellationException("测试取消")
            override fun close() { closed = true }
        } }, MediaOrigin.MCP) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(closed).isTrue()
        assertThat(context.cacheDir.list()!!.toSet()).isEqualTo(before)
    }

    companion object { private const val AUTHORITY = "com.promenar.infinity.test.media" }
}
