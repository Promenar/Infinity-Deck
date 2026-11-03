package com.promenar.infinity.data.exec

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ToolExecutionControlsTest {
    @Test fun `等待按会话与调用隔离且用户释放不取消任务`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val wait = async(ToolCallControlContext("s", "call", "step")) {
            ToolExecutionControls.controlled(ToolExecutionControls.Kind.WAITING) { release ->
                entered.complete(Unit)
                release.await()
                "running"
            }
        }
        entered.await()
        assertThat(ToolExecutionControls.snapshot("s", "call")?.canCheckNow).isTrue()
        assertThat(ToolExecutionControls.checkNow("other", "call")).isFalse()
        assertThat(ToolExecutionControls.checkNow("s", "call")).isTrue()
        assertThat(wait.await()).isEqualTo("running")
        assertThat(ToolExecutionControls.snapshot("s", "call")).isNull()
    }

    @Test fun `界面按步骤订阅控制状态且退出后清空`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val wait = async(ToolCallControlContext("s", "call", "step")) {
            ToolExecutionControls.controlled(ToolExecutionControls.Kind.WAITING) { release ->
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val state = ToolExecutionControls.observeStep("s", "step").first()
        assertThat(state?.toolCallId).isEqualTo("call")
        assertThat(state?.kind).isEqualTo(ToolExecutionControls.Kind.WAITING)
        assertThat(ToolExecutionControls.observeStep("other", "step").first()).isNull()
        assertThat(ToolExecutionControls.checkNow(state!!.sessionId, state.toolCallId)).isTrue()
        wait.await()
        assertThat(ToolExecutionControls.observeStep("s", "step").first()).isNull()
    }

    @Test fun `前台命令只在有后台接管能力时允许让出`() = runTest {
        val control = BuiltinCommandControl()
        val done = CompletableDeferred<Unit>()
        val job = launch(ToolCallControlContext("s", "call", "step")) {
            ToolExecutionControls.controlled(ToolExecutionControls.Kind.FOREGROUND, background = control::requestYield) {
                done.complete(Unit)
                control.yielded.await()
            }
        }
        done.await()
        assertThat(ToolExecutionControls.moveToBackground("s", "call")).isTrue()
        job.join()
        assertThat(control.timeoutSecondsOverride).isEqualTo(control.yieldedTimeoutSeconds)
        ToolExecutionControls.controlled(ToolExecutionControls.Kind.FOREGROUND) {
            assertThat(ToolExecutionControls.moveToBackground("s", "call")).isFalse()
        }
    }
}
