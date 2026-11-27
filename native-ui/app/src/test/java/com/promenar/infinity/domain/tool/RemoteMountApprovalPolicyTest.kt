package com.promenar.infinity.domain.tool

import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.RemoteMountTestFixture
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.chat.manager.skills.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMountApprovalPolicyTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun `远程挂载写入在两档模式逐次审批且不可授予限时权限`() {
        val writes = mapOf(
            "remote_mount_archive" to ToolRisk.EXTERNAL_WRITE,
            "remote_mount_mkdir" to ToolRisk.EXTERNAL_WRITE,
            "remote_mount_rename" to ToolRisk.EXTERNAL_WRITE,
            "remote_mount_delete" to ToolRisk.DELETE,
        )
        for (mode in listOf(ExecutionMode.WORKSPACE, ExecutionMode.FULL_ACCESS)) {
            for ((name, risk) in writes) {
                assertTrue("$mode $name", ToolExecutionPolicy.requiresApproval(mode, name, risk))
                assertTrue(ToolArgumentApprovalRules.requiresExplicitApproval(name, "{}"))
                assertNull(ToolExecutionPolicy.grantCategory(mode, name, risk))
            }
        }
    }

    @Test fun `下载在风险审批档逐次确认 免审批档可写普通文件`() {
        val name = "remote_mount_download"
        assertTrue(ToolExecutionPolicy.requiresApproval(ExecutionMode.WORKSPACE, name, ToolRisk.FILE_WRITE))
        assertNull(ToolExecutionPolicy.grantCategory(ExecutionMode.WORKSPACE, name, ToolRisk.FILE_WRITE))
        assertFalse(ToolExecutionPolicy.requiresApproval(ExecutionMode.FULL_ACCESS, name, ToolRisk.FILE_WRITE))
    }

    @Test fun `通用工具按路径 UUID 和移动目标拦截远程挂载`() = runBlocking<Unit> {
        val fixture = RemoteMountTestFixture(directory.root)
        val ws = fixture.workspace
        val fileOps = fixture.files
        val events = FileIndexEventSink.None
        val uuid = ExternalMountPaths.uuid(fixture.mount, "/one.txt")
        val calls = listOf(
            FileWriteSkill(fileOps, ws) to """{"path":"/mnt/drive/one.txt","content":"新内容"}""",
            FilePatchSkill(fileOps, ws) to """{"uuid":"$uuid"}""",
            CreateFileSkill(ws, fileOps) to """{"parentPath":"/mnt/drive","name":"a.txt"}""",
            CreateDirectorySkill(ws) to """{"parentPath":"/mnt/drive/nested","name":"more"}""",
            CreateDirectorySkill(ws) to """{"parentPath":"/mnt","name":"drive"}""",
            MoveFileSkill(ws, events) to """{"path":"/mnt/drive/one.txt","newName":"new.txt"}""",
            MoveFileSkill(ws, events) to """{"path":"one.txt","newParentPath":"/mnt/drive"}""",
            DeleteFileSkill(ws) to """{"path":"/mnt/drive/one.txt"}""",
        )
        for ((tool, raw) in calls) {
            val args = Json.parseToJsonElement(raw).jsonObject
            val plain = MountedWorkspaceSkill(tool, fixture.mounts, ws, fileOps, events)
            val blocked = plain.execute(args, fixture.context())
            assertEquals(tool.id, SFTP_USE_REMOTE_MOUNT_TOOL, Json.parseToJsonElement(requireNotNull(blocked.data)).jsonObject["errorCode"]?.jsonPrimitive?.content)
            for (mode in SessionRunMode.entries) {
                val assembled = LinuxSessionFileSkill(tool, { mode }, fixture.linux, ws, fileOps, events, builtinFiles = fixture.builtin)
                val result = assembled.execute(args, fixture.context(mode = mode))
                assertEquals("${tool.id} $mode", SFTP_USE_REMOTE_MOUNT_TOOL, Json.parseToJsonElement(requireNotNull(result.data)).jsonObject["errorCode"]?.jsonPrimitive?.content)
                assertTrue(ToolExecutionPolicy.isHardBlockedTarget(fixture.approve(tool, raw, mode)))
            }
        }
        assertTrue(fixture.transport.readOffsets.isEmpty())
        assertTrue(fixture.transport.appendOffsets.isEmpty())
    }

    @Test fun `解析器绑定会话挂载 主机指纹和本地文件大小`() = runBlocking<Unit> {
        val fixture = RemoteMountTestFixture(directory.root)
        fixture.manager().use { controller ->
            val tool = fixture.tools(controller).getValue("archive")
            val local = java.io.File(fixture.workspaceDir, "source.txt").apply { writeText("归档内容") }
            val raw = """{"mount_path":"/mnt/drive/archive.txt","workspace_path":"source.txt"}"""
            val target = requireNotNull(fixture.approve(tool, raw))
            val fields = Json.parseToJsonElement(target).jsonArray.last().jsonObject
            val identity = fixture.hosts.host.executionIdentity()
            assertEquals("mount-id", fields["mountId"]?.jsonPrimitive?.content)
            assertEquals(identity.hostId, fields["sshHostId"]?.jsonPrimitive?.content)
            assertEquals(identity.digest, fields["sshIdentity"]?.jsonPrimitive?.content)
            assertEquals(local.length(), fields["bytes_total"]?.jsonPrimitive?.long)
            assertEquals(JsonPrimitive(false), fields["target_exists"])
            assertEquals(JsonPrimitive(false), fields["will_overwrite"])
            assertEquals("nas · /mnt/drive/archive.txt", ToolExecutionTargets.display(target))
            assertFalse(ToolExecutionTargets.canRemember(target))
        }
    }
}
