package com.promenar.infinity.data.exec

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.junit.After
import org.junit.Test

class BuiltinExecEnvironmentTest {
    @Test fun `shell与Python环境同时导出相同的新旧工作区和技能变量`() {
        val workspace = File(work, "workspace with spaces")
        for (env in listOf(environment(null).forWorkspace(workspace, "project"), environment(null).forPython(workspace, "shared"))) {
            for (suffix in listOf("WORKSPACE", "WORKSPACE_KIND", "SKILLS")) {
                assertThat(env["INFINITY_$suffix"]).isNotNull()
                assertThat(env["NEXARA_$suffix"]).isEqualTo(env["INFINITY_$suffix"])
            }
        }
    }

    private val work: File = Files.createTempDirectory("builtin-exec-env").toFile()
    private val pythonDir = File(work, "lib").apply {
        mkdirs()
        resolve(PackagedPython.LAUNCHER).writeText("")
        resolve(PackagedPython.STDLIB).writeText("")
        resolve(PackagedPython.SITE).writeText("")
    }

    @After fun tearDown() { work.deleteRecursively() }

    private fun environment(
        userPackages: PythonUserPackages?,
        tailnet: () -> Map<String, String> = { emptyMap() },
    ) = BuiltinExecEnvironment(
        python = PackagedPython(pythonDir),
        homeDirectory = File(work, "home"),
        tempDirectory = File(work, "tmp"),
        caBundle = { null },
        userPackages = userPackages,
        inherited = emptyMap(),
        tailnetEnvironment = tailnet,
    )

    @Test fun `站点 zip 排在标准库之后进入 PYTHONPATH`() {
        val env = environment(null).forWorkspace(File(work, "ws"))
        assertThat(env["PYTHONPATH"]).isEqualTo("${pythonDir.resolve(PackagedPython.STDLIB).path}:${pythonDir.resolve(PackagedPython.SITE).path}")
        pythonDir.resolve(PackagedPython.SITE).delete()
        assertThat(environment(null).forWorkspace(File(work, "ws"))["PYTHONPATH"]).isEqualTo(pythonDir.resolve(PackagedPython.STDLIB).path)
    }

    @Test fun `进程技能目录自动创建且HOME链接指向同一目录`() {
        val env = environment(null).forWorkspace(File(work, "ws"))
        val skills = File(work, "agent_skills")
        assertThat(env["INFINITY_SKILLS"]).isEqualTo(skills.path)
        assertThat(skills.isDirectory).isTrue()
        val link = File(work, "home/.agents/skills").toPath()
        assertThat(Files.isSymbolicLink(link)).isTrue()
        assertThat(link.toRealPath()).isEqualTo(skills.toPath().toRealPath())
        Files.write(link.resolve("SKILL.md"), "same directory".toByteArray())
        assertThat(File(skills, "SKILL.md").readText()).isEqualTo("same directory")
        assertThat(environment(null).forPython(File(work, "other"))["INFINITY_SKILLS"]).isEqualTo(skills.path)
    }

    @Test fun `正确的技能符号链接保持原始形式与身份`() {
        File(work, "agent_skills").mkdirs()
        val link = File(work, "home/.agents/skills").toPath()
        Files.createDirectories(link.parent)
        val target = java.nio.file.Path.of("../../agent_skills")
        Files.createSymbolicLink(link, target)
        val before = Files.getAttribute(link, "basic:lastModifiedTime", NOFOLLOW_LINKS)
        environment(null).forWorkspace(File(work, "ws"))
        assertThat(Files.readSymbolicLink(link)).isEqualTo(target)
        assertThat(Files.getAttribute(link, "basic:lastModifiedTime", NOFOLLOW_LINKS)).isEqualTo(before)
    }

    @Test fun `错误链接以及既有文件和目录均不覆盖`() {
        val link = File(work, "home/.agents/skills").toPath()
        Files.createDirectories(link.parent)
        for (kind in listOf("file", "directory", "symlink")) {
            when (kind) {
                "file" -> Files.write(link, "keep".toByteArray())
                "directory" -> Files.createDirectory(link)
                else -> Files.createSymbolicLink(link, java.nio.file.Path.of("/missing-skills"))
            }
            assertThat(environment(null).forWorkspace(File(work, "ws"))["INFINITY_SKILLS"]).isEqualTo(File(work, "agent_skills").path)
            when (kind) {
                "file" -> assertThat(String(Files.readAllBytes(link))).isEqualTo("keep")
                "directory" -> assertThat(Files.isDirectory(link, NOFOLLOW_LINKS)).isTrue()
                else -> assertThat(Files.readSymbolicLink(link).toString()).isEqualTo("/missing-skills")
            }
            Files.delete(link)
        }
    }

    @Test fun `未开放 pip 时保持禁用用户站点且不设置 pip 变量`() {
        val env = environment(null).forWorkspace(File(work, "ws"))
        assertThat(env["PYTHONNOUSERSITE"]).isEqualTo("1")
        assertThat(env.keys.filter { it.startsWith("PIP_") || it.startsWith("INFINITY_PIP") || it == "PYTHONUSERBASE" }).isEmpty()
    }

    @Test fun `开放 pip 时启用用户站点并指向应用目录`() {
        val packages = PythonUserPackages(File(work, "python-user"), File(work, "python-pip"), File(work, "pip-cache"))
        val env = environment(packages).forPython(File(work, "ws"))
        assertThat(env).doesNotContainKey("PYTHONNOUSERSITE")
        assertThat(env["PYTHONUSERBASE"]).isEqualTo(packages.userBase.path)
        assertThat(env["INFINITY_PIP"]).isEqualTo("1")
        assertThat(env["INFINITY_PIP_DIR"]).isEqualTo(packages.pipDirectory.path)
        assertThat(env["PIP_CACHE_DIR"]).isEqualTo(packages.cacheDirectory.path)
        assertThat(env["PIP_DISABLE_PIP_VERSION_CHECK"]).isEqualTo("1")
        assertThat(env["PIP_NO_INPUT"]).isEqualTo("1")
    }

    @Test fun `shell 前导以函数提供 python3、git 与 pip`() {
        val prelude = environment(null).shellPrelude()
        listOf("python3()", "python()", "git()", "pip()", "pip3()").forEach { assertThat(prelude).contains(it) }
        assertThat(prelude).contains("-m infinity_git")
        assertThat(prelude).contains("-m infinity_pip")
        assertThat(prelude).doesNotContain("ffmpeg()")
    }

    @Test fun `存在内置 FFmpeg 库时暴露环境变量、PATH 软链与 shell 函数`() {
        val ffmpegLib = File(pythonDir, PackagedPython.FFMPEG).apply { writeText("elf") }
        val ffprobeLib = File(pythonDir, PackagedPython.FFPROBE).apply { writeText("elf") }
        val envObj = environment(null)
        val wsEnv = envObj.forWorkspace(File(work, "ws"))
        val pyEnv = envObj.forPython(File(work, "ws"))
        val expectedBin = File(work, "exec-bin")
        for (env in listOf(wsEnv, pyEnv)) {
            assertThat(env["INFINITY_FFMPEG"]).isEqualTo(ffmpegLib.path)
            assertThat(env["INFINITY_FFPROBE"]).isEqualTo(ffprobeLib.path)
            assertThat(env["PATH"]?.split(File.pathSeparator)?.firstOrNull()).isEqualTo(expectedBin.path)
        }
        assertThat(Files.isSymbolicLink(File(expectedBin, "ffmpeg").toPath())).isTrue()
        assertThat(Files.readSymbolicLink(File(expectedBin, "ffmpeg").toPath())).isEqualTo(ffmpegLib.absoluteFile.toPath())
        assertThat(Files.isSymbolicLink(File(expectedBin, "ffprobe").toPath())).isTrue()
        assertThat(Files.readSymbolicLink(File(expectedBin, "ffprobe").toPath())).isEqualTo(ffprobeLib.absoluteFile.toPath())
        val prelude = envObj.shellPrelude()
        assertThat(prelude).contains("ffmpeg()")
        assertThat(prelude).contains("ffprobe()")
    }

    @Test fun `应用升级更换原生库目录后自动刷新过期 FFmpeg 软链`() {
        val ffmpegLib = File(pythonDir, PackagedPython.FFMPEG).apply { writeText("elf") }
        val ffprobeLib = File(pythonDir, PackagedPython.FFPROBE).apply { writeText("elf") }
        val binDir = File(work, "exec-bin").apply { mkdirs() }
        Files.createSymbolicLink(File(binDir, "ffmpeg").toPath(), File(work, "old-apk-lib/libnxffmpeg.so").toPath())
        Files.createSymbolicLink(File(binDir, "ffprobe").toPath(), File(work, "old-apk-lib/libnxffprobe.so").toPath())

        environment(null).forWorkspace(File(work, "ws"))

        assertThat(Files.readSymbolicLink(File(binDir, "ffmpeg").toPath())).isEqualTo(ffmpegLib.absoluteFile.toPath())
        assertThat(Files.readSymbolicLink(File(binDir, "ffprobe").toPath())).isEqualTo(ffprobeLib.absoluteFile.toPath())
    }

    @Test fun `内置PATH发现随包工具且保持Git经现有入口执行`() {
        val tools = listOf("rg", "fd", "jq", "sqlite3", "zstd", "bsdtar", "rsync")
        tools.forEach { File(pythonDir, "libnx$it.so").writeText("elf") }
        listOf("libnxgit.so", "libnxgit-remote-https.so", "libnxgitssh.so").forEach { File(pythonDir, it).writeText("elf") }
        val env = environment(null).forWorkspace(File(work, "ws"))
        val binDir = File(work, "exec-bin")
        assertThat(env.getValue("PATH").substringBefore(File.pathSeparator)).isEqualTo(binDir.path)
        assertThat(binDir.list()!!.toList()).containsExactlyElementsIn(tools)
        tools.forEach {
            assertThat(Files.readSymbolicLink(File(binDir, it).toPath())).isEqualTo(File(pythonDir, "libnx$it.so").toPath())
        }
        assertThat(environment(null).shellPrelude()).contains("-m infinity_git")
    }

    @Test fun `拒绝把符号链接目录前置到内置PATH`() {
        File(pythonDir, PackagedPython.FFMPEG).writeText("elf")
        File(pythonDir, PackagedPython.FFPROBE).writeText("elf")
        val target = File(work, "outside").apply { mkdirs() }
        val binDir = File(work, "exec-bin")
        Files.createSymbolicLink(binDir.toPath(), target.toPath())
        val env = environment(null).forWorkspace(File(work, "ws"))
        assertThat(env.getValue("PATH").split(File.pathSeparator)).doesNotContain(binDir.path)
        assertThat(target.list()!!.toList()).isEmpty()
    }

    @Test fun `内置 Tailscale 未运行时不设置任何代理变量`() {
        val env = environment(null).forWorkspace(File(work, "ws"))
        assertThat(env.keys.filter { it.lowercase().endsWith("_proxy") }).isEmpty()
        val default = BuiltinExecEnvironment(PackagedPython(pythonDir), File(work, "home"), File(work, "tmp"), { null }, inherited = emptyMap())
        assertThat(default.forWorkspace(File(work, "ws")).keys.filter { it.lowercase().endsWith("_proxy") }).isEmpty()
    }

    @Test fun `内置 Tailscale 运行时合入代理变量且 NO_PROXY 覆盖本机地址`() {
        val endpoint = com.promenar.infinity.data.tailnet.TailnetProxyEndpoint(41000, "u".repeat(24), "p".repeat(24))
        val tailnet = com.promenar.infinity.data.tailnet.TailnetProxyConfig.environment(endpoint)
        val env = environment(null) { tailnet }.forPython(File(work, "ws"))
        assertThat(env["NO_PROXY"]).isEqualTo("localhost,127.0.0.1,::1")
        assertThat(env["no_proxy"]).isEqualTo("localhost,127.0.0.1,::1")
        assertThat(env["https_proxy"]).isEqualTo("http://${"u".repeat(24)}:${"p".repeat(24)}@127.0.0.1:41000")
        assertThat(env["ALL_PROXY"]).startsWith("socks5h://")
        assertThat(env["INFINITY_BACKEND"]).isEqualTo("builtin")
    }
}
