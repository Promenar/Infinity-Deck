package com.promenar.infinity.domain.tool

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.ui.chat.ApprovalPreview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class MountToolTargetTest {
    private fun target(name: String, args: String) = ToolExecutionTargets.extract(
        ProtocolTool(function = ProtocolToolFunction(name, "", "{}")), args,
    )

    @Test fun `挂载写入审批保留完整虚拟路径`() {
        assertThat(target("write_file", """{"path":"/mnt/termux/project/a.md"}"""))
            .isEqualTo("""["path","/mnt/termux/project/a.md"]""")
        assertThat(target("move_file", """{"path":"/mnt/a/one.txt","newParentPath":"/mnt/b"}"""))
            .isEqualTo("""["move","/mnt/a/one.txt","/mnt/b/one.txt"]""")
    }

    @Test fun `虚拟根不能被工作区创建或移动覆盖`() {
        assertThat(target("create_directory", """{"name":"mnt"}""")).isNull()
        assertThat(target("move_file", """{"path":"/docs","newName":"mnt"}""")).isNull()
        assertThat(target("create_directory", """{"parentPath":"/docs","name":"mnt"}"""))
            .isEqualTo("""["path","/docs/mnt"]""")
    }

    @Test fun `删除必须显示永久删除提醒并禁止记忆授权目标`() {
        val raw = """{"path":"/mnt/termux/large.bin"}"""
        val target = target("delete_file", raw)!!
        assertThat(ToolExecutionTargets.display(target)).isEqualTo("/mnt/termux/large.bin")
        assertThat(ToolExecutionTargets.canRemember(target)).isFalse()
        assertThat(ApprovalPreview.format("delete_file", Json.parseToJsonElement(raw).jsonObject)?.detail)
            .contains("永久删除，无法恢复")
        val ordinary = target("delete_file", """{"path":"/mnt/termux/other.bin"}""")!!
        assertThat(ToolExecutionTargets.canRemember(ordinary)).isFalse()
    }

    @Test fun `挂载保留子目录不能进入授权目标`() {
        assertThat(target("write_file", """{"path":"/mnt/termux/.recycle_bin/a"}""")).isNull()
        assertThat(target("write_file", """{"path":"/mnt/termux/../other/a"}""")).isNull()
    }
}
