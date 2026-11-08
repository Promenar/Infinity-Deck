package com.promenar.infinity.data.exec

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.background.exec.AndroidExecKeepAlive
import com.promenar.infinity.background.exec.ExecForegroundService
import com.promenar.infinity.background.exec.ExecRunRegistryOwner
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.repository.SecureWorkspaceTreeScanner
import com.promenar.infinity.data.repository.WorkspaceDeletionTransaction
import com.promenar.infinity.data.repository.WorkspaceRepository
import com.promenar.infinity.data.repository.WorkspaceScanKind
import com.promenar.infinity.domain.tool.CommandToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** 在设备上以真实 toybox setsid、系统 sh、打包 Python 与安全目录遍历运行内置执行后端。 */
@RunWith(AndroidJUnit4::class)
class BuiltinExecDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: InfinityDatabase
    private lateinit var repo: WorkspaceRepository
    private lateinit var base: File
    private lateinit var workspace: File
    private lateinit var service: BuiltinExecService
    private lateinit var root: FileEntry

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), deleteCommitter = WorkspaceDeletionTransaction(db)::delete)
        base = File(context.filesDir, "exec-device-${UUID.randomUUID()}").apply { mkdirs() }
        workspace = File(base, "ws").apply { mkdirs() }
        val now = System.currentTimeMillis()
        db.sessionDao().insert(SessionEntity(id = "s", agentId = "agent", workspacePath = workspace.path, createdAt = now, updatedAt = now))
        root = repo.ensureSessionRoot("s")
        service = execService(userPackages = null)
    }

    private fun execService(userPackages: PythonUserPackages?) = BuiltinExecService(
        workspace = repo,
        runner = BuiltinCommandRunner(
            groupLauncher = listOf("/system/bin/setsid"),
            shell = "/system/bin/sh",
            signaller = AndroidProcessGroupSignaller,
            scratchDirectory = { File(base, "scratch") },
        ),
        environment = BuiltinExecEnvironment(
            python = PackagedPython.from(context),
            homeDirectory = File(base, "home"),
            tempDirectory = File(base, "tmp"),
            caBundle = SystemCaBundle(File(base, "ca"))::ensure,
            userPackages = userPackages,
        ),
    )

    private fun pythonUserPackages() = PythonUserPackages(File(base, "python-user"), File(base, "python-pip"), File(base, "pip-cache"))

    /** 测试进程能解析域名时才运行联网用例；执行后端的子进程与应用同 uid，网络权限一致。 */
    private fun assumeNetwork(host: String) {
        val reachable = runCatching { java.net.InetAddress.getByName(host); true }.getOrDefault(false)
        org.junit.Assume.assumeTrue("无法解析 $host，跳过联网用例", reachable)
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    private fun shellOutput(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText()
        }

    /** 只取当前持有的唤醒锁段；dumpsys power 另有包含已释放记录的历史日志。 */
    private fun heldWakeLocks(): List<String> = shellOutput("dumpsys power").lines()
        .dropWhile { !it.trimStart().startsWith("Wake Locks: size=") }.drop(1)
        .takeWhile { it.startsWith(" ") && it.isNotBlank() }

    @Suppress("DEPRECATION")
    private fun execServiceInForeground(): Boolean = context.getSystemService(android.app.ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == ExecForegroundService::class.java.name && it.foreground }

    private fun execNotification() = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.id == ExecForegroundService.NOTIFICATION_ID }?.notification

    private fun CommandToolResult.json(): JsonObject = Json.parseToJsonElement(toJson().toString()).jsonObject
    private fun JsonObject.paths(key: String) =
        getValue("workspace_changes").jsonObject.getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private suspend fun shell(command: String, timeout: Int = 60) =
        service.runShell(root.uuid, "s", command, null, timeout)

    @Test
    fun shellCommandChangesAreReconciledIntoIndex() = runBlocking<Unit> {
        repo.createFileInWorkspace(root.uuid, "keep", "keep.txt", "k", root.uuid, "/keep.txt").also { repo.confirmCreatedEntry(root.uuid, it.uuid) }
        repo.createFileInWorkspace(root.uuid, "gone", "gone.txt", "g", root.uuid, "/gone.txt").also { repo.confirmCreatedEntry(root.uuid, it.uuid) }

        val result = shell(
            "pwd; mkdir -p out && printf 'a,b\\n1,2\\n' > out/r.csv && rm gone.txt && mv keep.txt kept.txt && " +
                "ln -s /system out/link && python3 -c 'import sys; print(sys.version.split()[0])'",
        )

        val value = result.json()
        assertWithMessage(value.toString()).that(result.succeeded).isTrue()
        val stdout = value.getValue("stdout").jsonPrimitive.content.lines()
        assertThat(stdout[0]).isEqualTo("\$INFINITY_WORKSPACE")
        assertThat(stdout[1]).isEqualTo(BuildConfig.PACKAGED_PYTHON_VERSION)
        assertThat(value.paths("added")).containsExactly("/out", "/out/r.csv").inOrder()
        assertThat(value.paths("removed")).containsExactly("/gone.txt")
        assertThat(value.getValue("workspace_changes").jsonObject.getValue("moved").jsonArray.single().jsonObject
            .getValue("to").jsonPrimitive.content).isEqualTo("/kept.txt")
        val warning = value.getValue("workspace_warnings").jsonArray.single().jsonObject
        assertThat(warning.getValue("code").jsonPrimitive.content).isEqualTo("WORKSPACE_UNSUPPORTED_ENTRY")
        assertThat(warning.getValue("paths").jsonArray.map { it.jsonPrimitive.content }).containsExactly("/out/link")

        assertThat(repo.getByUuid(root.uuid, "keep")?.materializedPath).isEqualTo("/kept.txt")
        assertThat(repo.getByUuid(root.uuid, "gone")).isNull()
        val csv = checkNotNull(repo.getByMaterializedPath(root.uuid, "/out/r.csv"))
        assertThat(csv.mimeType).isEqualTo("text/csv")
        assertThat(csv.parentUuid).isEqualTo(repo.getByMaterializedPath(root.uuid, "/out")?.uuid)
    }

    @Test
    fun pythonRunsInWorkspaceWithSystemCertificates() = runBlocking<Unit> {
        val result = service.runPython(
            root.uuid, "s",
            """
            import json, os, ssl
            open('note.txt', 'w').write('中文')
            ctx = ssl.create_default_context()
            print(json.dumps({'cwd': os.getcwd() == os.environ['INFINITY_WORKSPACE'], 'ca': ctx.cert_store_stats()['x509_ca'],
                              'ld': 'LD_LIBRARY_PATH' in os.environ}))
            """.trimIndent(),
            null, 60,
        )
        val value = result.json()
        assertWithMessage(value.toString()).that(result.succeeded).isTrue()
        val report = Json.parseToJsonElement(value.getValue("stdout").jsonPrimitive.content.trim()).jsonObject
        assertThat(report.getValue("cwd").jsonPrimitive.content).isEqualTo("true")
        assertThat(report.getValue("ca").jsonPrimitive.int).isGreaterThan(50)
        assertThat(value.paths("added")).containsExactly("/note.txt")
        assertThat(File(workspace, "note.txt").readText()).isEqualTo("中文")
    }

    @Test
    fun builtinGitCommitsAndClonesLocallyAndCollapsesGitInternals() = runBlocking<Unit> {
        if (!PackagedPython.from(context).isAvailable) return@runBlocking
        val result = shell(
            "git --version && git init repo && cd repo && git config user.name Tester && git config user.email t@example.com && " +
                "echo hi > a.txt && mkdir d && echo x > d/b.txt && git add -A && git commit -m first && " +
                "echo more >> a.txt && git commit -am second && git log --oneline && cd .. && " +
                "git clone repo copy && git -C copy log --oneline && git -C copy status",
        ).json()
        assertWithMessage(result.toString()).that(result.getValue("exit_code").jsonPrimitive.int).isEqualTo(0)
        val stdout = result.getValue("stdout").jsonPrimitive.content
        assertThat(stdout).contains("dulwich")
        assertThat(Regex("second").findAll(stdout).count()).isEqualTo(2)
        assertThat(File(workspace, "copy/d/b.txt").readText().trim()).isEqualTo("x")
        val added = result.paths("added")
        assertThat(added).containsAtLeast("/repo/.git", "/repo/a.txt", "/copy/.git", "/copy/a.txt")
        assertThat(added.none { "/.git/" in it }).isTrue()
        assertThat(result.getValue("workspace_changes").jsonObject.getValue("git_internal_changes").jsonPrimitive.int).isGreaterThan(0)
        assertThat(repo.getByMaterializedPath(root.uuid, "/repo/.git/HEAD")).isNotNull()

        val sync = shell(
            "git init --bare hub.git && git -C repo remote add origin ../hub.git && git -C repo push origin master && " +
                "git clone hub.git peer && cd repo && echo three >> a.txt && git commit -am third && git push origin master && " +
                "cd ../peer && git pull origin && git log --oneline && git remote -v",
        ).json()
        assertWithMessage(sync.toString()).that(sync.getValue("exit_code").jsonPrimitive.int).isEqualTo(0)
        assertThat(sync.getValue("stdout").jsonPrimitive.content).contains("third")
        assertThat(sync.getValue("stdout").jsonPrimitive.content).contains("(push)")
        assertThat(File(workspace, "peer/a.txt").readText()).contains("three")

        val unsupported = shell("git -C repo bisect start").json()
        assertThat(unsupported.getValue("exit_code").jsonPrimitive.int).isEqualTo(2)
        assertThat(unsupported.getValue("stderr").jsonPrimitive.content).contains("GIT_UNSUPPORTED")
        val ssh = shell("git clone git@github.com:octocat/Hello-World.git ssh-copy").json()
        assertThat(ssh.getValue("stderr").jsonPrimitive.content).contains("SSH")
        val anonymous = shell("git init other && cd other && echo y > y.txt && git add y.txt && git commit -m nope").json()
        assertThat(anonymous.getValue("exit_code").jsonPrimitive.int).isEqualTo(128)
        assertThat(anonymous.getValue("stderr").jsonPrimitive.content).contains("GIT_IDENTITY_MISSING")
    }

    @Test
    fun builtinGitClonesOverHttps() = runBlocking<Unit> {
        if (!PackagedPython.from(context).isAvailable) return@runBlocking
        assumeNetwork("github.com")
        val result = shell("git clone --depth 1 https://github.com/octocat/Hello-World.git hw && git -C hw log --oneline -1", timeout = 180).json()
        assertWithMessage(result.toString()).that(result.getValue("exit_code").jsonPrimitive.int).isEqualTo(0)
        assertThat(File(workspace, "hw/README").isFile).isTrue()
        // git 的 -<N> 写法要有输出；回车刷新的进度只保留最终状态，不挤占输出尾部。
        assertThat(result.getValue("stdout").jsonPrimitive.content.trim()).isNotEmpty()
        val stderr = result.getValue("stderr").jsonPrimitive.content
        assertThat(stderr).doesNotContain("\r")
        assertThat(stderr.length).isLessThan(4_000)
        // 只取分支与标签：不取 GitHub 为每个 PR 通告的 refs/pull/*（否则约 20 MiB）。
        val gitBytes = File(workspace, "hw/.git").walkTopDown().filter(File::isFile).sumOf(File::length)
        assertWithMessage("hw/.git 大小").that(gitBytes).isLessThan(1_000_000L)
        assertThat(result.paths("added")).contains("/hw/README")
    }

    @Test
    fun pipInstallsPurePackageIntoUserSiteWhenEnabled() = runBlocking<Unit> {
        if (!PackagedPython.from(context).isAvailable) return@runBlocking
        val disabled = shell("pip --version").json()
        assertThat(disabled.getValue("exit_code").jsonPrimitive.int).isEqualTo(2)
        assertThat(disabled.getValue("stderr").jsonPrimitive.content).contains("PIP_UNSUPPORTED")

        val enabled = execService(pythonUserPackages())
        val version = enabled.runShell(root.uuid, "s", "pip --version", null, 60).json()
        assertWithMessage(version.toString()).that(version.getValue("exit_code").jsonPrimitive.int).isEqualTo(0)
        assertThat(version.getValue("stdout").jsonPrimitive.content).startsWith("pip ")
        assumeNetwork("pypi.org")
        val install = enabled.runShell(
            root.uuid, "s", "pip install six==1.17.0 && python3 -c 'import six, sys; print(six.__version__, six.__file__)'", null, 180,
        ).json()
        assertWithMessage(install.toString()).that(install.getValue("exit_code").jsonPrimitive.int).isEqualTo(0)
        val stdout = install.getValue("stdout").jsonPrimitive.content
        assertThat(stdout).contains("1.17.0")
        assertThat(stdout).contains(File(base, "python-user").path)
        // 安装位置在应用目录，不进入工作区索引。
        assertThat(install.paths("added")).isEmpty()
    }

    @Test
    fun shellEnvironmentDoesNotLeakLibraryPathToSystemCommands() = runBlocking<Unit> {
        val result = shell("echo \"[\${LD_LIBRARY_PATH:-}]\"; echo \"\$INFINITY_BACKEND\"; ls /system/bin/toybox")
        val stdout = result.json().getValue("stdout").jsonPrimitive.content.lines()
        assertThat(result.succeeded).isTrue()
        assertThat(stdout[0]).isEqualTo("[]")
        assertThat(stdout[1]).isEqualTo("builtin")
    }

    @Test
    fun timeoutKillsWholeProcessGroup() = runBlocking<Unit> {
        val result = shell("sleep 60 & echo \$! > bg.pid; sleep 60", timeout = 2)
        val value = result.json()
        assertThat(value.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_TIMEOUT")
        assertThat(result.exitCode).isNull()
        val pid = File(workspace, "bg.pid").readText().trim()
        withTimeout(5_000) { while (File("/proc/$pid").exists()) delay(50) }
        assertThat(value.paths("added")).containsExactly("/bg.pid")
    }

    @Test
    fun cancellationKillsCommandAndReleasesWorkspace() = runBlocking<Unit> {
        val job = async { shell("echo \$\$ > main.pid; sleep 60") }
        val pidFile = File(workspace, "main.pid")
        withTimeout(10_000) { while (!pidFile.isFile || pidFile.readText().isBlank()) delay(50) }
        val pid = pidFile.readText().trim()
        job.cancel()
        runCatching { job.await() }
        withTimeout(5_000) { while (File("/proc/$pid").exists()) delay(50) }
        // 写锁已释放，取消后的对账已把 main.pid 写入索引。
        val next = shell("true", timeout = 10)
        assertThat(next.succeeded).isTrue()
        assertThat(repo.getByMaterializedPath(root.uuid, "/main.pid")).isNotNull()
    }

    @Test
    fun rootIdentityRemovedByCommandIsRestored() = runBlocking<Unit> {
        val result = shell("rm -f .infinity_root_identity && mkdir .infinity_planted")
        val warning = result.json().getValue("workspace_warnings").jsonArray.single().jsonObject
        assertThat(warning.getValue("code").jsonPrimitive.content).isEqualTo("WORKSPACE_RESERVED_PATH")
        assertThat(warning.getValue("paths").jsonArray.map { it.jsonPrimitive.content })
            .containsAtLeast("/.infinity_root_identity", "/.infinity_planted")
        repo.createDirectoryInWorkspace(root.uuid, "after", "after", root.uuid, "/after")
        assertThat(repo.getByMaterializedPath(root.uuid, "/after")).isNotNull()
    }

    @Test
    fun secureScannerDoesNotFollowSymbolicLinks() {
        val outside = File(base, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("s")
        File(workspace, "docs").mkdirs()
        File(workspace, "docs/a.txt").writeText("abc")
        Files.createSymbolicLink(File(workspace, "link").toPath(), outside.toPath())
        val snapshot = SecureWorkspaceTreeScanner.scan(workspace.toPath())
        assertThat(snapshot.nodes.keys).containsExactly("/docs", "/docs/a.txt", "/link")
        assertThat(snapshot.nodes.getValue("/link").kind).isEqualTo(WorkspaceScanKind.UNSUPPORTED)
        assertThat(snapshot.nodes.getValue("/docs/a.txt").sizeBytes).isEqualTo(3)
        assertThat(snapshot.reserved.keys).contains("/.infinity_root_identity")
    }

    @Test
    fun notificationStopEndsCommandAndReleasesWakeLock() = runBlocking<Unit> {
        if (Build.VERSION.SDK_INT >= 33) {
            // ColorOS 未开“USB 调试（安全设置）”时 shell 无权授予；此时改从运行中服务列表核对前台状态。
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val notificationsVisible = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val registry = (context.applicationContext as ExecRunRegistryOwner).execRunRegistry
        val kept = BuiltinExecService(
            workspace = repo,
            runner = BuiltinCommandRunner(
                groupLauncher = listOf("/system/bin/setsid"),
                shell = "/system/bin/sh",
                signaller = AndroidProcessGroupSignaller,
                scratchDirectory = { File(base, "scratch") },
                clockNanos = android.os.SystemClock::elapsedRealtimeNanos,
            ),
            environment = BuiltinExecEnvironment(
                python = PackagedPython.from(context),
                homeDirectory = File(base, "home"),
                tempDirectory = File(base, "tmp"),
                caBundle = { null },
            ),
            keepAlive = AndroidExecKeepAlive(context, registry, foregroundServiceAvailable = true),
        )
        val job = async(kotlinx.coroutines.Dispatchers.IO) { kept.runShell(root.uuid, "s", "echo \$\$ > main.pid; sleep 60", null, 120) }
        val pidFile = File(workspace, "main.pid")
        withTimeout(10_000) { while (!pidFile.isFile || pidFile.readText().isBlank()) delay(50) }
        val pid = pidFile.readText().trim()

        withTimeout(10_000) { while (!execServiceInForeground()) delay(100) }
        assertThat(heldWakeLocks().any { "Infinity Deck:builtin-exec" in it }).isTrue()
        if (notificationsVisible) {
            val notification = withTimeout(10_000) { var found = execNotification(); while (found == null) { delay(100); found = execNotification() }; found }
            assertThat(notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
                .isEqualTo("Shell · echo \$\$ > main.pid; sleep 60")
            notification.actions.single().actionIntent.send()
        } else {
            context.startService(ExecForegroundService.stopIntent(context, registry.runs.value.map { it.info.id }))
        }
        val result = withTimeout(15_000) { job.await() }
        val value = result.json()
        assertWithMessage(value.toString()).that(value.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_STOPPED")
        withTimeout(5_000) { while (File("/proc/$pid").exists()) delay(50) }
        withTimeout(5_000) { while (execNotification() != null || execServiceInForeground()) delay(100) }
        assertThat(registry.runs.value).isEmpty()
        assertThat(heldWakeLocks().none { "Infinity Deck:builtin-exec" in it }).isTrue()
        assertThat(value.paths("added")).containsExactly("/main.pid")
    }
}
