package com.promenar.infinity.data.artifact

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.canvas.FileCanvasStore
import com.promenar.infinity.domain.media.*
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSessionArtifactRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val files = mutableMapOf<String, ByteArray>()
    private val resolver = object : WorkspaceMediaResolver {
        override suspend fun resolve(sessionId: String, path: String): MediaResolution {
            val canonical = "/" + path.trimStart('/')
            val bytes = files[canonical] ?: return MediaResolution.Unavailable(canonical, MediaUnavailableReason.NOT_FOUND)
            return MediaResolution.Available(MediaRef(canonical, "text/plain", MediaKind.FILE, bytes.size.toLong()), MediaSource.LocalFile(File(folder.root, "source")))
        }
        override suspend fun open(resolution: MediaResolution.Available) = files.getValue(resolution.ref.path).inputStream()
    }
    @Test fun `声明保存私有版本去重并筛选候选`() = runTest {
        val canvas = FileCanvasStore(File(folder.root, "canvas"))
        val store: SessionArtifactRepository = FileSessionArtifacts(File(folder.root, "artifacts"), canvas, resolver)
        files["/report.txt"] = "one".toByteArray()
        val first = store.publishFile("s", "/report.txt", origin = ArtifactOrigin.DECLARED, messageId = "m")
        assertThat(store.readFile("s", first.id)!!.file.readText()).isEqualTo("one")
        assertThat(store.publishFile("s", "/report.txt", origin = ArtifactOrigin.DECLARED).currentVersion).isEqualTo(1)
        files["/report.txt"] = "two".toByteArray()
        assertThat(store.publishFile("s", "/report.txt", origin = ArtifactOrigin.DECLARED).currentVersion).isEqualTo(2)
        assertThat(store.readFile("s", first.id, 1)!!.file.readText()).isEqualTo("one")
        files["/note.txt"] = "note".toByteArray()
        val changes = listOf(WorkspaceChangedFile("/report.txt", "m", 1), WorkspaceChangedFile("/note.txt", "m", 2))
        assertThat(store.candidates("s", changes).map { it.path }).containsExactly("/note.txt")
        store.ignoreCandidate("s", "/note.txt")
        assertThat(store.candidates("s", changes)).isEmpty()
        store.publishFile("s", "/note.txt", origin = ArtifactOrigin.CLAIMED)
        val ignoreFile = folder.root.walkTopDown().single { it.name == "ignored.json" }
        assertThat(ignoreFile.readText()).doesNotContain("/note.txt")
        assertThat(store.observe("s").first()).hasSize(2)
    }
    @Test fun `版本保留配额限制分支复制与损坏恢复`() = runTest {
        val root = File(folder.root, "artifacts")
        val canvases = FileCanvasStore(File(folder.root, "canvases"))
        val store = FileSessionArtifacts(root, canvases, resolver, limit = 10, quota = 30)
        files["/a.txt"] = "one".toByteArray()
        val item = store.publishFile("s", "/a.txt", origin = ArtifactOrigin.DECLARED, messageId = "m")
        repeat(5) { n ->
            files["/a.txt"] = "v$n".toByteArray()
            store.publishFile("s", "/a.txt", origin = ArtifactOrigin.DECLARED, messageId = "later")
        }
        assertThat(store.readFile("s", item.id, 1)).isNull()
        assertThat(store.readFile("s", item.id, 2)!!.file.readText()).isEqualTo("v0")
        files["/claim.txt"] = "claim".toByteArray()
        val claimed = store.publishFile("s", "/claim.txt", origin = ArtifactOrigin.CLAIMED)
        files["/late.txt"] = "late".toByteArray()
        store.publishFile("s", "/late.txt", origin = ArtifactOrigin.DECLARED, messageId = "outside")
        store.copySession("s", "branch", mapOf("m" to "new-m"))
        val copied = store.observe("branch").first()
        assertThat(copied.map { it.id }).containsExactly(item.id, claimed.id)
        assertThat(copied.single { it.id == item.id }.createdByMessageId).isEqualTo("new-m")
        store.deleteSession("branch")
        assertThat(store.observe("branch").first()).isEmpty()
        val meta = root.walkTopDown().single { it.name == "metadata.json" && it.parentFile.name == item.id }
        meta.writeText("{损坏")
        val restored = FileSessionArtifacts(root, canvases, resolver, limit = 10, quota = 30)
        assertThat(restored.observe("s").first().map { it.id }).doesNotContain(item.id)
    }

    @Test fun `大小配额和候选排除规则`() = runTest {
        val store = FileSessionArtifacts(File(folder.root, "artifacts"), FileCanvasStore(File(folder.root, "canvas")), resolver, limit = 8, quota = 10)
        files["/huge.txt"] = ByteArray(9)
        assertThat((runCatching { store.publishFile("s", "/huge.txt", origin = ArtifactOrigin.CLAIMED) }.exceptionOrNull() as ArtifactException).code).isEqualTo("FILE_TOO_LARGE")
        files["/one.txt"] = ByteArray(6)
        store.publishFile("s", "/one.txt", origin = ArtifactOrigin.CLAIMED)
        files["/two.txt"] = ByteArray(6)
        assertThat((runCatching { store.publishFile("s", "/two.txt", origin = ArtifactOrigin.CLAIMED) }.exceptionOrNull() as ArtifactException).code).isEqualTo("SESSION_QUOTA_EXCEEDED")
        val excluded = listOf("/.hidden/a.txt", "/node_modules/a.txt", "/__pycache__/a.txt", "/canvas/a.html", "/a.log", "/a.tmp", "/a.pyc", "/a.o", "/a.class", "/a.lock")
        excluded.forEach { files[it] = byteArrayOf(1) }
        repeat(55) { files["/file$it.txt"] = byteArrayOf(1) }
        val changes = excluded.map { WorkspaceChangedFile(it, "x", 0) } + (0 until 55).map { WorkspaceChangedFile("/file$it.txt", "old", it.toLong()) } + WorkspaceChangedFile("/file0.txt", "last", 99)
        val candidates = store.candidates("s", changes)
        assertThat(candidates).hasSize(50)
        assertThat(candidates.first().changedByMessageId).isEqualTo("last")
        assertThat(candidates.none { it.path in excluded }).isTrue()
    }

    @Test fun `技能目录写入不算工作区候选产物`() = runTest {
        val store = FileSessionArtifacts(File(folder.root, "artifacts"), FileCanvasStore(File(folder.root, "canvas")), resolver)
        val skillFiles = listOf("/skills/hello-demo/SKILL.md", "/mnt/skills/hello-demo/SKILL.md")
        skillFiles.forEach { files[it] = byteArrayOf(1) }
        files["/mnt/docs/report.md"] = byteArrayOf(1)
        val changes = (skillFiles + "/mnt/docs/report.md").map { WorkspaceChangedFile(it, "m", 0) }
        assertThat(store.candidates("s", changes).map { it.path }).containsExactly("/mnt/docs/report.md")
    }

    @Test fun `画布流失败前仍发出已保存的文件并向下游报告异常`() = runTest {
        val base = FileCanvasStore(File(folder.root, "canvas"))
        val failing = object : com.promenar.infinity.data.canvas.CanvasStore by base {
            override fun observe(sessionId: String): kotlinx.coroutines.flow.Flow<List<com.promenar.infinity.data.canvas.CanvasMetadata>> = kotlinx.coroutines.flow.flow { throw IllegalStateException("canvas failed") }
        }
        val store = FileSessionArtifacts(File(folder.root, "artifacts"), failing, resolver)
        files["/doc.txt"] = "x".toByteArray()
        store.publishFile("s", "/doc.txt", origin = ArtifactOrigin.CLAIMED)
        val emitted = mutableListOf<List<ArtifactSummary>>()
        val error = runCatching { store.observe("s").collect { emitted.add(it) } }.exceptionOrNull()
        assertThat(emitted.single().single().sourcePath).isEqualTo("/doc.txt")
        assertThat(error).isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun `遗留快照计入配额且目录与链接不能发布`() = runTest {
        val root = File(folder.root, "artifacts")
        val canvas = FileCanvasStore(File(folder.root, "canvas"))
        val store = FileSessionArtifacts(root, canvas, resolver, limit = 8, quota = 10)
        files["/one.txt"] = byteArrayOf(1)
        val item = store.publishFile("s", "/one.txt", origin = ArtifactOrigin.CLAIMED)
        val directory = root.walkTopDown().single { it.name == item.id }
        File(directory, "99.bin").writeBytes(ByteArray(8))
        files["/two.txt"] = byteArrayOf(1, 2)
        val error = runCatching { store.publishFile("s", "/two.txt", origin = ArtifactOrigin.CLAIMED) }.exceptionOrNull() as ArtifactException
        assertThat(error.code).isEqualTo("SESSION_QUOTA_EXCEEDED")
        val dirResolver = object : WorkspaceMediaResolver {
            override suspend fun resolve(sessionId: String, path: String): MediaResolution = MediaResolution.Available(MediaRef(path, "text/plain", MediaKind.FILE, 0), MediaSource.LocalFile(folder.root))
            override suspend fun open(resolution: MediaResolution.Available) = ByteArray(0).inputStream()
        }
        val dirStore = FileSessionArtifacts(File(folder.root, "other"), canvas, dirResolver)
        assertThat((runCatching { dirStore.publishFile("s", "/dir", origin = ArtifactOrigin.CLAIMED) }.exceptionOrNull() as ArtifactException).code).isEqualTo("IS_DIRECTORY")
        val target = File(folder.root, "target").apply { writeText("x") }
        val link = File(folder.root, "link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        val linkResolver = object : WorkspaceMediaResolver {
            override suspend fun resolve(sessionId: String, path: String): MediaResolution = MediaResolution.Available(MediaRef(path, "text/plain", MediaKind.FILE, 1), MediaSource.LocalFile(link))
            override suspend fun open(resolution: MediaResolution.Available) = "x".byteInputStream()
        }
        val linkStore = FileSessionArtifacts(File(folder.root, "link-artifacts"), canvas, linkResolver)
        assertThat((runCatching { linkStore.publishFile("s", "/link", origin = ArtifactOrigin.CLAIMED) }.exceptionOrNull() as ArtifactException).code).isEqualTo("INVALID_PATH")
    }

}
