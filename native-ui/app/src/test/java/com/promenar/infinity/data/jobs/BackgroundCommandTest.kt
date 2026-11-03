package com.promenar.infinity.data.jobs

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.exec.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Test

class BackgroundCommandTest {
    @Test fun `合并两路日志按顺序写入并返回退出码`() = runBlocking {
        val root = Files.createTempDirectory("job-command").toFile()
        try {
            val runner = runner(root)
            val log = File(root, "out.log")
            val out = runner.runBackground(request(root, "printf a; printf b >&2; printf c; exit 7"), BuiltinCommandControl(), log, File(root, "pgid"))
            assertThat(log.readText()).matches("""\[\+\d{2}:\d{2}\.\d] abc""")
            assertThat(readJobLogTail(log, 64, stripTimestamps = true).text).isEqualTo("abc")
            assertThat(out.exitCode).isEqualTo(7)
            assertThat(out.stdout).isEmpty()
        } finally { root.deleteRecursively() }
    }
    @Test fun `日志轮转只保留一份旧文件且尾部有界`() {
        val root = Files.createTempDirectory("job-log").toFile()
        try {
            val file = File(root, "out.log")
            RotatingJobLog(file, 8).use { log ->
                log.write("12345678".toByteArray()); log.write("abcdefgh".toByteArray()); log.write("tail".toByteArray())
            }
            assertThat(File(root, "out.log.1").readText()).isEqualTo("abcdefgh")
            assertThat(file.readText()).isEqualTo("tail")
            assertThat(readJobLogTail(file, 3).text).isEqualTo("ail")
            assertThat(readJobLogTail(file, 3).truncated).isTrue()
        } finally { root.deleteRecursively() }
    }
    @Test fun `停止与超时终止子进程`() = runBlocking {
        val root = Files.createTempDirectory("job-stop").toFile()
        try {
            val control = BuiltinCommandControl()
            val out = async { runner(root).runBackground(request(root, "sleep 60 & echo \$! > child; wait"), control, File(root,"out.log"), File(root,"pgid")) }
            withTimeout(5000) { while (!File(root,"child").exists()) delay(10) }
            control.requestStop()
            assertThat(out.await().stoppedByUser).isTrue()
            assertThat(ProcessBuilder("/bin/kill", "-0", File(root,"child").readText().trim()).start().waitFor()).isNotEqualTo(0)
            val timed = runner(root).runBackground(request(root, "sleep 60", 1), BuiltinCommandControl(), File(root,"out.log"), File(root,"pgid"))
            assertThat(timed.timedOut).isTrue()
        } finally { root.deleteRecursively() }
    }
    @Test fun `重启清理真实进程组且身份不匹配时不误杀`() = runBlocking<Unit> {
        val root=Files.createTempDirectory("job-interrupt").toFile()
        val pgid=File(root,"pgid")
        val child=File(root,"child")
        val process=ProcessBuilder("/usr/bin/perl","-e","setpgrp(0,0); exec @ARGV or exit 127","/bin/sh","-c","echo \$\$ > '${pgid.path}'; sleep 60 & echo \$! > '${child.path}'; wait").start()
        try {
            withTimeout(5000) {while(!child.isFile || child.readText().isBlank())delay(10)}
            File(pgid.path+".identity").writeText("test-boot\nstale")
            val killer=BuiltinCommandRunner(emptyList(),"/bin/sh",ProcessGroupSignaller{pid,sig->ProcessBuilder("/bin/kill","-$sig","-$pid").start().waitFor()},{root},killGraceMillis=100,processIdentity={"current"},bootIdentity={"test-boot"})
            killer.interruptBackground(pgid)
            assertThat(process.isAlive).isTrue()
            File(pgid.path+".identity").writeText("test-boot\ncurrent")
            killer.interruptBackground(pgid)
            withTimeout(5000){while(process.isAlive)delay(10)}
            assertThat(ProcessBuilder("/bin/kill","-0",child.readText().trim()).start().waitFor()).isNotEqualTo(0)
        } finally {process.destroyForcibly();root.deleteRecursively()}
    }
    private fun request(root:File, command:String, seconds:Int=10) = BuiltinCommandRequest(listOf("/bin/sh","-c",command),root,mapOf("PATH" to "/usr/bin:/bin"),timeoutSeconds=seconds)
    private fun runner(root:File) = BuiltinCommandRunner(listOf("/usr/bin/perl","-e","setpgrp(0, 0); exec @ARGV or exit 127"),"/bin/sh", ProcessGroupSignaller { pid, signal -> ProcessBuilder("/bin/kill","-$signal","-$pid").start().waitFor() },{root},killGraceMillis=100)
}
