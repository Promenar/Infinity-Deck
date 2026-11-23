package com.promenar.infinity.domain.tool

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShellCommandSafetyClassifierTest {
    private val appData = "/data/user/0/com.promenar.infinity"

    @Test
    fun `毁灭性根目录破坏命令在所有写法与变形下均硬拦截`() {
        val catastrophicCommands = listOf(
            "rm -rf /",
            "rm -rf /*",
            "rm -rf /.",
            "rm -rf /..",
            "rm -rf ///",
            "rm -rf /tmp/..",
            "rm -rf /a/b/../..",
            "rm -r -f --no-preserve-root /",
            "rm -rf '/'",
            "rm -rf \"/\"",
            """rm -rf \/""",
            """r""m -rf /""",
            """\rm -rf /""",
            "/system/bin/rm -rf /",
            "busybox rm -rf /",
            "command rm -rf /",
            "env FOO=1 rm -rf /",
            "FOO=bar rm -rf /",
            "sh -c 'rm -rf /'",
            """/system/bin/sh -c "rm -rf /*"""",
            "eval 'rm -rf /'",
            "echo / | xargs rm -rf",
            "find / -delete",
            "find / -exec rm -rf {} +",
            "find /sdcard -delete",
            "rm -rf /sdcard",
            "rm -rf /sdcard/*",
            "rm -rf /storage",
            "rm -rf /storage/emulated/0",
            "rm -rf /storage/emulated/0/*",
            "rm -rf /data",
            "rm -rf /data/user/0/com.promenar.infinity",
            "rm -rf /data/data/com.promenar.infinity/*",
            "rm -rf /data/user/0/com.promenar.infinity/databases",
            "rm -rf /data/user/0/com.promenar.infinity/shared_prefs/*",
            "rm -rf /data/user/0/com.promenar.infinity/files/session_workspaces",
            "rm -rf /data/user/0/com.promenar.infinity/files/linux",
            "rm -rf /data/user/0/com.promenar.infinity/files/knowledge_base",
            "rm -rf /data/user/0/com.promenar.infinity/files/agent_skills",
            "rm -rf /workspace",
            "rm -rf /workspace/*",
            "rm -rf /home/session",
            "rm -rf /mnt",
            "rm -rf /mnt/skills",
            "rm -rf /etc",
            "rm -rf /usr",
            "rm -rf ~",
            "rm -rf ~/*",
            "rm -rf \$UNSET_VAR/",
            "rm -rf \${EMPTY_DIR}/*",
            """rm -rf "${'$'}INFINITY_WORKSPACE"""",
            """rm -rf "${'$'}INFINITY_WORKSPACE/*"""",
            "cd / && rm -rf *",
            "cd /sdcard && rm -rf ./*",
            "chmod -R 000 /",
            "chown -R 0:0 /sdcard",
            "mv /sdcard /tmp/gone",
            "dd if=/dev/zero of=/dev/sda",
            "mkfs.ext4 /dev/block/sda1",
            "ls && rm -rf /",
            "echo \$(rm -rf /)",
            """python3 -c "import shutil; shutil.rmtree('/')"""",
            """python3 -c "import os; os.system('rm -rf /sdcard')"""",
        )
        for (cmd in catastrophicCommands) {
            val decision = ShellCommandSafetyClassifier.classifyShell(cmd, appDataDir = appData)
            com.google.common.truth.Truth.assertWithMessage("应硬拦截命令: $cmd")
                .that(decision.verdict)
                .isEqualTo(CommandSafetyVerdict.BLOCKED_CATASTROPHIC)
            assertThat(decision.reason).isNotEmpty()
        }
    }

    @Test
    fun `Python 毁灭性根目录删除硬拦截但普通子目录操作进入审批`() {
        listOf(
            "import shutil; shutil.rmtree('/')",
            "import shutil; shutil.rmtree('/sdcard')",
            "import shutil; shutil.rmtree('/data/user/0/com.promenar.infinity/databases')",
            "import os; os.system('rm -rf /')",
            "import subprocess; subprocess.run(['rm', '-rf', '/'])",
        ).forEach { code ->
            assertThat(ShellCommandSafetyClassifier.classifyPython(code, appDataDir = appData).verdict)
                .isEqualTo(CommandSafetyVerdict.BLOCKED_CATASTROPHIC)
        }
        assertThat(ShellCommandSafetyClassifier.classifyPython("import shutil; shutil.rmtree('./build')", appDataDir = appData).verdict)
            .isEqualTo(CommandSafetyVerdict.REQUIRES_APPROVAL)
    }

    @Test
    fun `选项 A 严格只读白名单命令免审批`() {
        val safeCommands = listOf(
            "pwd",
            "ls -la",
            "ls -la /data/user/0/com.promenar.infinity/files/infinity_diagnostics.jsonl",
            "cat notes/readme.md",
            "head -n 20 media/log.txt",
            "tail -n 50 /data/user/0/com.promenar.infinity/files/infinity_logs.txt",
            "wc -l src/app.py",
            "stat media/demo.mp4",
            "file media/demo.mp4",
            "rg -n TODO src/",
            "grep -rn hello .",
            "find . -maxdepth 3 -name '*.py'",
            "git status",
            "git status -s",
            "git diff HEAD~1",
            "git log -n 10 --oneline",
            "git show HEAD --stat",
            "git branch -a",
            "git branch --show-current",
            "git rev-parse --abbrev-ref HEAD",
            "git -C subdir status",
            "ffprobe -v error -show_format -show_streams -of json media/clip.mp4",
            "ffmpeg -version",
            "ffmpeg -hide_banner -encoders",
            "python3 --version",
            "python3 -m py_compile app.py helper.py",
            "pip list",
            "which ffmpeg",
            "command -v ffprobe",
            "ls -la 2>/dev/null | head -n 20",
            "ffprobe media/clip.mp4 2>&1 | head -n 30",
            "pwd && ls -la",
            "true",
        )
        for (cmd in safeCommands) {
            val decision = ShellCommandSafetyClassifier.classifyShell(cmd, appDataDir = appData)
            assertThat(decision.verdict).isEqualTo(CommandSafetyVerdict.SAFE_READ_ONLY)
        }
    }

    @Test
    fun `修改性命令、重定向、子 shell、危险参数与合法子目录 rm 均落入需审批`() {
        val approvalCommands = listOf(
            "rm -rf ./build",
            "rm -rf dist/",
            "rm -rf /workspace/tmp/out",
            "rm -rf /data/user/0/com.promenar.infinity/files/session_workspaces/abc/temp_dir",
            """rm -rf "${'$'}INFINITY_WORKSPACE/temp_dir"""",
            "touch new.txt",
            "mkdir -p output/frames",
            "cp a.txt b.txt",
            "mv a.txt b.txt",
            "ls -la > out.txt",
            "cat a.txt >> b.txt",
            "echo hi | tee out.txt",
            "ls \$(whoami)",
            "ls `id`",
            "find . -name '*.tmp' -delete",
            "find . -exec echo {} \\;",
            "git commit -m 'msg'",
            "git push origin main",
            "git reset --hard HEAD",
            "git branch -D feature",
            "git branch new-branch",
            "git -c core.pager=evil log",
            "git diff --output=patch.diff",
            "ffmpeg -i in.mp4 out.mp4",
            "ffprobe -report media/clip.mp4",
            "python3 -c 'print(1)'",
            "python3 script.py",
            "pip install requests",
            "sort -o sorted.txt input.txt",
            "rg --pre=evil pattern .",
            "sed -i 's/a/b/g' file.txt",
            "awk '{print}' file.txt",
            "curl https://example.com",
            "cat /data/user/0/com.promenar.infinity/shared_prefs/infinity_encrypted_secrets.xml",
            "sh -c 'ls'",
            "ls -la; pwd",
            "ls -la || true",
            "ls -la &",
            "(ls -la)",
            "ls 'unclosed",
        )
        for (cmd in approvalCommands) {
            val decision = ShellCommandSafetyClassifier.classifyShell(cmd, appDataDir = appData)
            assertThat(decision.verdict).isEqualTo(CommandSafetyVerdict.REQUIRES_APPROVAL)
        }
    }
}
