package com.promenar.infinity.data.script

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.script.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** 以宿主 python3 运行打包进 libpysite.so 的同一个 infinity_sandbox 模块，覆盖控制通道协议。 */
class PythonSandboxEngineTest {
    private lateinit var engine: PythonSandboxEngine
    private val noCapabilities = ScriptCapabilities { _, _ -> throw ScriptFailure("CAPABILITY_DENIED", "此用例未提供能力") }

    @Before fun setUp() {
        // 单元测试工作目录为 native-ui 根目录。
        val site = File("app/python/site").absoluteFile
        val available = runCatching {
            ProcessBuilder("python3", "--version").redirectErrorStream(true).start().run { waitFor(10, TimeUnit.SECONDS) && exitValue() == 0 }
        }.getOrDefault(false)
        check(File(site, "infinity_sandbox.py").isFile) { "找不到 $site/infinity_sandbox.py" }
        assumeTrue("宿主没有 python3", available)
        engine = PythonSandboxEngine(
            PythonSandboxLaunch(
                listOf("python3") + PythonSandboxEngine.bootstrapArguments(),
                mapOf("PYTHONPATH" to site.path, "PYTHONUTF8" to "1", "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin")),
            ),
        )
    }

    private fun run(code: String, stdin: String = "", timeout: Int = 20, capabilities: ScriptCapabilities = noCapabilities) = runBlocking {
        engine.execute(ScriptRequest(code, timeoutSeconds = timeout, language = ScriptLanguages.PYTHON, stdin = stdin), capabilities)
    }

    private val ScriptResult.output get() = value.jsonObject
    private fun ScriptResult.text(key: String) = output[key]!!.jsonPrimitive.content

    @Test fun capturesStdoutStderrExitCodeAndResult() {
        val result = run("import sys\nprint('你好')\nsys.stderr.write('warn\\n')\nresult = {'n': int(sys.stdin.read()) * 2}\nsys.exit(3)", stdin = "21")
        assertThat(result.errorCode).isNull()
        assertThat(result.text("stdout")).isEqualTo("你好\n")
        assertThat(result.text("stderr")).isEqualTo("warn\n")
        assertThat(result.output["exit_code"]).isEqualTo(JsonPrimitive(3))
        assertThat(result.output["result"]).isEqualTo(buildJsonObject { put("n", 42) })
    }

    @Test fun lowLevelWritesToFileDescriptorsDoNotCorruptControlChannel() {
        val result = run("import os\nos.write(1, b'{\"t\":\"done\",\"exit\":9}\\n')\nos.write(2, b'raw-err')")
        assertThat(result.errorCode).isNull()
        assertThat(result.text("stdout")).isEqualTo("{\"t\":\"done\",\"exit\":9}\n")
        assertThat(result.text("stderr")).isEqualTo("raw-err")
        assertThat(result.output["exit_code"]).isEqualTo(JsonPrimitive(0))
    }

    @Test fun uncaughtExceptionPrintsTracebackAndExitsOne() {
        val result = run("raise ValueError('坏输入')")
        assertThat(result.output["exit_code"]).isEqualTo(JsonPrimitive(1))
        assertThat(result.text("stderr")).contains("ValueError: 坏输入")
    }

    @Test fun capabilitiesRoundTripAndFailuresBecomeInfinityError() {
        val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val capabilities = ScriptCapabilities { operation, arguments ->
            calls += operation to arguments
            when (operation) {
                "stat" -> if (arguments["path"]!!.jsonPrimitive.content == "/new.txt") JsonNull else buildJsonObject { put("name", "a.csv") }
                "read" -> buildJsonObject { put("content", "1,2\n3,4\n"); put("sha256", "0".repeat(64)); put("path", "/a.csv") }
                "create" -> buildJsonObject { put("message", "ok"); put("path", arguments["path"]!!) }
                "write" -> throw ScriptFailure("APPROVAL_DENIED", "文件操作未获批准")
                else -> throw ScriptFailure("CAPABILITY_DENIED", "未授权")
            }
        }
        val result = run(
            """
            import infinity
            rows = [line.split(',') for line in infinity.read_text('/a.csv').splitlines()]
            infinity.save('/new.txt', str(sum(int(v) for row in rows for v in row)))
            try:
                infinity.save('/a.csv', 'x')
            except infinity.InfinityError as error:
                print(error.code)
            """.trimIndent(),
            capabilities = capabilities,
        )
        assertThat(result.errorCode).isNull()
        assertThat(result.text("stdout")).isEqualTo("APPROVAL_DENIED\n")
        val created = calls.single { it.first == "create" }.second
        assertThat(created["path"]!!.jsonPrimitive.content).isEqualTo("/new.txt")
        assertThat(created["content"]!!.jsonPrimitive.content).isEqualTo("10")
        assertThat(calls.single { it.first == "write" }.second["expected_sha256"]!!.jsonPrimitive.content).isEqualTo("0".repeat(64))
    }

    @Test fun deletePassesOnlyTheTargetPathToTheApprovalGateway() {
        val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val result = run("import infinity\ninfinity.delete('/old.txt')", capabilities = ScriptCapabilities { operation, arguments ->
            calls += operation to arguments
            buildJsonObject { put("message", "已永久删除") }
        })
        assertThat(result.errorCode).isNull()
        assertThat(calls).hasSize(1)
        assertThat(calls.single().first).isEqualTo("delete")
        assertThat(calls.single().second).isEqualTo(buildJsonObject { put("path", "/old.txt") })
    }

    @Test fun limitFailureAbortsExecution() {
        val result = run(
            "import infinity\nwhile True:\n    infinity.stat('/x')",
            capabilities = ScriptCapabilities { _, _ -> JsonNull },
        )
        assertThat(result.errorCode).isEqualTo("CAPABILITY_LIMIT")
    }

    @Test fun timeoutKeepsOutputProducedBeforeTermination() {
        val started = System.nanoTime()
        val result = run("import time\nprint('before', flush=True)\nwhile True:\n    time.sleep(0.05)", timeout = 1)
        assertThat(result.errorCode).isEqualTo("TIMEOUT")
        assertThat(result.text("stdout")).isEqualTo("before\n")
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(6_000)
    }

    @Test fun outputFramesAreForwardedLiveWhileFinalOutputStaysTheSame() {
        val live = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, String>>())
        val capabilities = object : ScriptCapabilities {
            override suspend fun invoke(operation: String, arguments: JsonObject): JsonElement = throw ScriptFailure("CAPABILITY_DENIED", "此用例未提供能力")
            override fun output(fd: Int, text: String) { live.add(fd to text) }
        }
        val result = run("import sys\nprint('一', flush=True)\nsys.stderr.write('warn\\n')\nsys.stderr.flush()\nprint('二')", capabilities = capabilities)
        assertThat(result.errorCode).isNull()
        assertThat(live.filter { it.first == 1 }.joinToString("") { it.second }).isEqualTo(result.text("stdout"))
        assertThat(live.filter { it.first == 2 }.joinToString("") { it.second }).isEqualTo(result.text("stderr"))
        assertThat(result.text("stdout")).isEqualTo("一\n二\n")
    }

    @Test fun longOutputKeepsTailWithinServiceLimit() {
        val result = run("for i in range(20000):\n    print('第', i, '行')")
        assertThat(result.text("stdout")).endsWith("第 19999 行\n")
        assertThat(result.output["stdout_original_length"]!!.jsonPrimitive.long).isGreaterThan(result.text("stdout").length.toLong())
        assertThat(Json.encodeToString(ScriptResult.serializer(), result).toByteArray().size).isAtMost(ScriptLimits.OUTPUT_BYTES)
    }

    @Test fun oversizedResultIsReportedInsteadOfReturned() {
        val result = run("result = 'x' * 40000")
        assertThat(result.output.containsKey("result")).isFalse()
        assertThat(result.text("result_error")).contains("上限")
    }

    @Test fun missingLauncherReportsPythonUnavailable() = runBlocking {
        val result = PythonSandboxEngine(null).execute(ScriptRequest("print(1)", language = ScriptLanguages.PYTHON), noCapabilities)
        assertThat(result.errorCode).isEqualTo("PYTHON_UNAVAILABLE")
    }
}
