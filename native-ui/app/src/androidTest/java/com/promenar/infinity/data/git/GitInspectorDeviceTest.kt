package com.promenar.infinity.data.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.data.exec.AndroidProcessGroupSignaller
import com.promenar.infinity.data.exec.BuiltinCommandRequest
import com.promenar.infinity.data.exec.BuiltinCommandRunner
import com.promenar.infinity.data.exec.BuiltinExecEnvironment
import com.promenar.infinity.data.exec.PackagedPython
import com.promenar.infinity.data.exec.SystemCaBundle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 在设备上用打包 Python 与真实进程组执行器跑只读 Git 检查：
 * 在应用私有临时目录建仓库，制造已暂存、未暂存与未跟踪改动，再经 [GitInspector] 读取状态、文件内容与提交历史，
 * 并用 [NioGitWorkTreeReader] 的描述符相对读取取得工作区一侧内容。设备没有打包 Python 时跳过。
 */
@RunWith(AndroidJUnit4::class)
class GitInspectorDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var base: File
    private lateinit var repo: File
    private lateinit var outputDirectory: File
    private lateinit var environment: BuiltinExecEnvironment
    private lateinit var runner: BuiltinCommandRunner
    private lateinit var inspector: GitInspector
    private val target get() = GitWorkspaceTarget(repo, KIND_PRIVATE)

    @Before
    fun setUp() {
        val python = PackagedPython.from(context)
        assumeTrue("当前设备架构没有打包 Python，跳过 Git 检查设备测试", python.isAvailable)
        base = File(context.cacheDir, "git-inspect-device-${UUID.randomUUID()}").apply { mkdirs() }
        repo = File(base, "repo").apply { mkdirs() }
        outputDirectory = File(base, "inspect-out")
        environment = BuiltinExecEnvironment(
            python = python,
            homeDirectory = File(base, "home"),
            tempDirectory = File(base, "tmp"),
            caBundle = SystemCaBundle(File(base, "ca"))::ensure,
        )
        runner = BuiltinCommandRunner(
            groupLauncher = listOf("/system/bin/setsid"),
            shell = "/system/bin/sh",
            signaller = AndroidProcessGroupSignaller,
            scratchDirectory = { File(base, "scratch") },
        )
        inspector = GitInspector(BuiltinGitPythonRuntime(environment), BuiltinGitInspectCommandRunner(runner), { outputDirectory })
    }

    @After
    fun tearDown() {
        if (::base.isInitialized) base.deleteRecursively()
    }

    /** 以模型侧同一入口执行写操作准备仓库；dulwich.cli 不支持 -q 与 --short，这里只用最基本的形式。 */
    private fun git(vararg args: String) = runBlocking {
        val output = runner.run(
            BuiltinCommandRequest(
                argv = listOf(environment.pythonLauncher.path, "-m", "infinity_git") + args,
                workingDirectory = repo,
                environment = environment.forPython(repo, KIND_PRIVATE) + IDENTITY,
                timeoutSeconds = 120,
            ),
        )
        assertWithMessage("git ${args.joinToString(" ")} 失败：${output.stderr}").that(output.exitCode).isEqualTo(0)
    }

    private fun write(path: String, text: String) = File(repo, path).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun <T> GitInspectResult<T>.value(): T {
        assertWithMessage("检查失败：$this").that(this).isInstanceOf(GitInspectResult.Success::class.java)
        return (this as GitInspectResult.Success).value
    }

    @Test
    fun 读取三组改动_文件内容与提交历史() = runBlocking {
        git("init", ".")
        write("a.txt", "one\n")
        write("src/b.txt", "keep\n")
        git("add", "a.txt", "src/b.txt")
        git("commit", "-m", "初始提交")

        write("a.txt", "two\n")
        git("add", "a.txt")
        write("a.txt", "three\n")
        write("src/b.txt", "changed\n")
        write("notes/新文件.txt", "fresh\n")

        val status = inspector.status(target).value()
        assertThat(status.unborn).isFalse()
        assertThat(status.detached).isFalse()
        assertThat(status.branch).isNotEmpty()
        assertThat(status.head).isNotNull()
        // 已跟踪改动带行数统计：每个文件都是一行替换为另一行。
        assertThat(status.staged.entries).containsExactly(GitFileChange("a.txt", GitChangeKind.MODIFIED, addedLines = 1, removedLines = 1))
        assertThat(status.unstaged.entries).containsExactly(
            GitFileChange("a.txt", GitChangeKind.MODIFIED, addedLines = 1, removedLines = 1),
            GitFileChange("src/b.txt", GitChangeKind.MODIFIED, addedLines = 1, removedLines = 1),
        )
        assertThat(status.untracked.entries.map { it.path to it.kind }).containsExactly("notes/新文件.txt" to GitChangeKind.ADDED)

        val contents = inspector.read(
            target,
            listOf(GitBlobRef(GitRevisions.HEAD, "a.txt"), GitBlobRef(GitRevisions.INDEX, "a.txt"), GitBlobRef(GitRevisions.HEAD, "notes/新文件.txt")),
        ).value()
        assertThat(contents.map { it.text }).containsExactly("one\n", "two\n", null).inOrder()
        assertThat(contents[2].exists).isFalse()

        // 工作区一侧在设备上走描述符相对读取，不降级为按路径读取。
        val workTree = NioGitWorkTreeReader()
        assertThat(workTree.read(repo, "a.txt").text).isEqualTo("three\n")
        assertThat(workTree.read(repo, "notes/新文件.txt").text).isEqualTo("fresh\n")

        val log = inspector.log(target).value()
        assertThat(log.commits).hasSize(1)
        assertThat(log.commits.single().title).isEqualTo("初始提交")
        assertThat(log.commits.single().sha).isEqualTo(status.head)
        assertThat(log.commits.single().parents).isEmpty()

        // 结果文件读后即删；status-cache 是 status 的持久缓存，按设计保留。
        assertThat(outputDirectory.listFiles().orEmpty().map { it.name }.filterNot { it == "status-cache" }).isEmpty()
    }

    @Test
    fun 普通目录判定为不是仓库() = runBlocking {
        write("plain.txt", "x\n")
        val result = inspector.status(target)
        assertThat(result).isEqualTo(GitInspectResult.Failure(GitInspectError.NotRepository))
    }

    private companion object {
        const val KIND_PRIVATE = "private"
        val IDENTITY = mapOf(
            "GIT_AUTHOR_NAME" to "Infinity Deck Device Test",
            "GIT_AUTHOR_EMAIL" to "device-test@example.invalid",
            "GIT_COMMITTER_NAME" to "Infinity Deck Device Test",
            "GIT_COMMITTER_EMAIL" to "device-test@example.invalid",
        )
    }
}
