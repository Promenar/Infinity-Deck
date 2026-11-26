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

    @Test fun `排除目录前缀匹配按目录边界生效并兼容各类工作区前缀`() {
        val root = "productions/e2e-qa-lantern"
        // 精确目录与多层子目录均命中
        assertThat(isInsideExcludedDirectory("productions/e2e-qa-lantern", root)).isTrue()
        assertThat(isInsideExcludedDirectory("/productions/e2e-qa-lantern/", "$root/")).isTrue()
        assertThat(isInsideExcludedDirectory("productions/e2e-qa-lantern/S1E01/script.md", root)).isTrue()
        assertThat(isInsideExcludedDirectory("productions/e2e-qa-lantern/assets/char/hero.png", root)).isTrue()
        assertThat(isInsideExcludedDirectory("productions/e2e-qa-lantern/audio/bgm.mp3", root)).isTrue()

        // 各类工作区与会话根前缀归一化后命中
        listOf(
            "/workspace/productions/e2e-qa-lantern/S1E01/script.md",
            "/home/session/productions/e2e-qa-lantern/assets/hero.png",
            "/session/productions/e2e-qa-lantern/audio/bgm.mp3",
            "/productions/e2e-qa-lantern/S1E01/script.md",
            "///workspace//./productions/e2e-qa-lantern//S1E01/script.md",
            "other/../productions/e2e-qa-lantern/S1E01/script.md",
        ).forEach { path ->
            assertThat(isInsideExcludedDirectory(path, root)).isTrue()
            assertThat(isInsideExcludedDirectory(path, "/workspace/$root/")).isTrue()
            assertThat(isInsideExcludedDirectory(path, ".infinity-production-private/$root")).isTrue()
        }

        // 外部挂载目录同样按完整目录边界匹配
        assertThat(isInsideExcludedDirectory("/mnt/film/demo/S1E01/shot.mp4", "mnt/film/demo")).isTrue()
        assertThat(isInsideExcludedDirectory("/mnt/film/demo-2/S1E01/shot.mp4", "mnt/film/demo")).isFalse()

        // 兄弟目录、同名文件、上级目录与越界回退不误命中
        listOf(
            "productions/e2e-qa-lantern-2/foo.md",
            "/workspace/productions/e2e-qa-lantern-2/foo.md",
            "/home/session/productions/e2e-qa-lantern_backup/a.md",
            "productions/e2e-qa-lantern.md",
            "productions/notes.md",
            "productions/e2e-qa-lantern/../outside.md",
            "../productions/e2e-qa-lantern/S1E01/script.md",
        ).forEach { path ->
            assertThat(isInsideExcludedDirectory(path, root)).isFalse()
        }

        // 空前缀、空白、纯工作区根与空集合绝不排除文件
        listOf("", "   ", "/", "/workspace", "/workspace/", "/home/session", "/home/session/", "/session", "/session/").forEach { blank ->
            assertThat(isInsideExcludedDirectory("/workspace/notes.md", blank)).isFalse()
        }
        assertThat(isPathExcluded("productions/e2e-qa-lantern/S1E01/script.md", emptySet())).isFalse()
    }

    @Test fun `候选发现排除已绑定制作项目根目录并保留兄弟目录与普通文件`() = runTest {
        val workspaceResolver = object : WorkspaceMediaResolver {
            override suspend fun resolve(sessionId: String, path: String): MediaResolution {
                val canonical = when {
                    path.startsWith("/workspace/") -> "/" + path.removePrefix("/workspace/")
                    path.startsWith("/home/session/") -> "/" + path.removePrefix("/home/session/")
                    path.startsWith("/session/") -> "/" + path.removePrefix("/session/")
                    path == "/alias-to-production.md" -> "/productions/e2e-qa-lantern/S1E01/script.md"
                    else -> "/" + path.trimStart('/')
                }
                val bytes = files[canonical] ?: return MediaResolution.Unavailable(canonical, MediaUnavailableReason.NOT_FOUND)
                return MediaResolution.Available(MediaRef(canonical, "text/plain", MediaKind.FILE, bytes.size.toLong()), MediaSource.LocalFile(File(folder.root, "source")))
            }
            override suspend fun open(resolution: MediaResolution.Available) = files.getValue(resolution.ref.path).inputStream()
        }
        val store = FileSessionArtifacts(File(folder.root, "artifacts"), FileCanvasStore(File(folder.root, "canvas")), workspaceResolver)
        files["/productions/e2e-qa-lantern/S1E01/script.md"] = "s".toByteArray()
        files["/productions/e2e-qa-lantern/assets/hero.png"] = "i".toByteArray()
        files["/productions/e2e-qa-lantern/audio/bgm.mp3"] = "a".toByteArray()
        files["/productions/e2e-qa-lantern-2/foo.md"] = "f".toByteArray()
        files["/notes/summary.md"] = "n".toByteArray()

        val changes = listOf(
            WorkspaceChangedFile("productions/e2e-qa-lantern/S1E01/script.md", "m1", 1),
            WorkspaceChangedFile("/workspace/productions/e2e-qa-lantern/assets/hero.png", "m1", 2),
            WorkspaceChangedFile("/home/session/productions/e2e-qa-lantern/audio/bgm.mp3", "m1", 3),
            WorkspaceChangedFile("/alias-to-production.md", "m1", 4),
            WorkspaceChangedFile("/workspace/productions/e2e-qa-lantern-2/foo.md", "m2", 5),
            WorkspaceChangedFile("/notes/summary.md", "m2", 6),
        )

        val filtered = store.candidates("s", changes, excludedPathPrefixes = setOf("productions/e2e-qa-lantern"))
        assertThat(filtered.map { it.path }).containsExactly(
            "/notes/summary.md",
            "/productions/e2e-qa-lantern-2/foo.md",
        ).inOrder()

        val unfiltered = store.candidates("s", changes, excludedPathPrefixes = emptySet())
        assertThat(unfiltered.map { it.path }).containsAtLeast(
            "/productions/e2e-qa-lantern/S1E01/script.md",
            "/productions/e2e-qa-lantern/assets/hero.png",
            "/productions/e2e-qa-lantern/audio/bgm.mp3",
            "/productions/e2e-qa-lantern-2/foo.md",
            "/notes/summary.md",
        )
    }

}
