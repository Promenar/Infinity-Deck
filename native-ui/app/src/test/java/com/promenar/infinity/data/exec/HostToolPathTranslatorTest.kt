package com.promenar.infinity.data.exec

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class HostToolPathTranslatorTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun translatesMountsAndRejectsEscapes() {
        val mounts = listOf("/workspace", "/home/session", "/mnt/project", "/mnt/skills",
            "/mnt/comfy_run", "/mnt/comfy_stage", "/root", "/tmp", "/mnt/project/nested")
            .associateWith { temp.newFolder().canonicalFile }
        val translator = HostToolPathTranslator(mounts)
        for ((guest, host) in mounts) {
            assertEquals(host, translator.workingDirectory(guest))
            assertEquals(host.resolve("file").path, translator.translate("rg", guest, listOf("pattern", "file"), 3).arguments.last())
        }
        val root = mounts.getValue("/workspace")
        Files.createSymbolicLink(root.resolve("outside").toPath(), temp.root.toPath())
        for (path in listOf("/etc/passwd", "/usr/bin", "/proc/self", "/workspace/outside/file", "/workspace/../etc/file")) {
            assertThrows(HostToolException::class.java) { translator.translate("rg", "/workspace", listOf("needle", path), 3) }
        }
        assertEquals(listOf("--safe", root.resolve("db").path), translator.translate("sqlite3", "/workspace", listOf("db"), 3).arguments)
    }

    @Test fun refusesToolSpecificEscapeOptionsAndDataSources() {
        val translator = HostToolPathTranslator(mapOf("/workspace" to temp.root))
        val blocked = mapOf("git" to listOf("fetch", "--upload-p=x"), "rg" to listOf("--pre=x"),
            "fd" to listOf("-HIx", "cmd"), "jq" to listOf("-L", "/etc", "."),
            "sqlite3" to listOf("-init", "/etc/x"), "zstd" to listOf("--filelist=/etc/x"),
            "bsdtar" to listOf("-cPf", "archive", "file"), "rsync" to listOf("-av", "remote:src", "."),
            "ffmpeg" to listOf("-i", "concat:/etc/a|/etc/b", "out"), "ffprobe" to listOf("-i", "https://host/a"))
        for ((tool, args) in blocked) assertThrows(tool, HostToolException::class.java) {
            translator.translate(tool, "/workspace", args, 2)
        }
    }
    @Test fun preservesPatternsRefsAndRemoteNamesWhileTranslatingToolPaths() {
        val work = temp.newFolder().canonicalFile
        val translator = HostToolPathTranslator(mapOf("/workspace" to work))
        fun args(tool: String, vararg values: String) = translator.translate(tool, "/workspace", values.toList(), 3).arguments
        assertEquals(listOf("-j", "3", "-f", work.resolve("patterns").path, work.path), args("rg", "-f", "patterns", "."))
        assertEquals(listOf("--threads", "2", "filename", work.path), args("fd", "--threads=2", "filename", "."))
        assertEquals(listOf(work.path, "--files", "-j", "3"), args("rg", ".", "--files", "-j0"))
        assertThrows(HostToolException::class.java) { args("rg", "/etc", "--files") }
        assertThrows(HostToolException::class.java) { args("ffmpeg", "-vf", "scale=640:480,negate", "out.mp4") }
        assertEquals(listOf("-L", "/dev/null", "--slurpfile", "data", work.resolve("input.json").path, ".a"), args("jq", "--slurpfile", "data", "input.json", ".a"))
        assertEquals(listOf("-T2", "-o", work.resolve("out.zst").path, work.resolve("file").path), args("zstd", "-T2", "-o", "out.zst", "file"))
        assertEquals(listOf("clone", "-b", "feature/main", "git@host:project/a", work.resolve("dest").path),
            args("git", "clone", "-b", "feature/main", "git@host:project/a", "/workspace/dest"))
        assertEquals(listOf("push", "origin", "refs/heads/main"), args("git", "push", "origin", "refs/heads/main"))
        assertTrue(args("ffmpeg", "-i", "in.mp4", "out.mp4").containsAll(listOf("-threads", "3", work.resolve("in.mp4").path, work.resolve("out.mp4").path)))
    }
    @Test fun archiveDirectoryChangesAndJqModulePolicyFollowToolSemantics() {
        val work = temp.newFolder().canonicalFile
        val files = work.resolve("files").apply { mkdir() }
        files.resolve("a").writeText("a")
        val translator = HostToolPathTranslator(mapOf("/workspace" to work))
        assertEquals(listOf("-c", "-f", work.resolve("archive").path, "-C", files.path, "a"),
            translator.translate("bsdtar", "/workspace", listOf("-cf", "archive", "-C", "files", "a"), 2).arguments)
        assertEquals(listOf("-x", "-f", work.resolve("archive").path, "a"),
            translator.translate("bsdtar", "/workspace", listOf("-xf", "archive", "a"), 2).arguments)
        assertThrows(HostToolException::class.java) {
            translator.translate("jq", "/workspace", listOf("include \"local\"; ."), 2)
        }
    }
}
