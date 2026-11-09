package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.ssh.authorizedKeyInstallCommand

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.security.SshCredential
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.ssh.SshHostKey
import com.promenar.infinity.data.ssh.SshHostStore
import com.promenar.infinity.data.ssh.SshUserPrompt
import com.promenar.infinity.data.ssh.SshUserPromptBroker
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class SshHostManagementSkillsTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "s1"
        override val agentId = "a1"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root-1"
    }
    private val presented = SshHostKey("ssh-ed25519", "SHA256:synthetic-presented")
    private val store = MemoryHosts()
    private val runtime = FakeRuntime()
    private val prompts = SshUserPromptBroker(timeoutMillis = 5_000)
    private var enabled = true
    private val tools = sshHostManagementTools(store, runtime, prompts) { enabled }.associateBy(SkillDefinition::id)

    private suspend fun call(tool: String, args: JsonObject = buildJsonObject {}): Pair<String, JsonObject> {
        val result = tools.getValue(tool).execute(args, context)
        return result.status to Json.parseToJsonElement(result.content).jsonObject
    }

    /** 模拟用户在应用弹窗中的操作。 */
    private fun TestScope.answerPrompts(password: String? = "synthetic-password", trust: Boolean = true) {
        backgroundScope.launch {
            prompts.current.filterNotNull().collect { prompt ->
                when (prompt) {
                    is SshUserPrompt.Password -> prompt.submit(password?.toByteArray())
                    is SshUserPrompt.HostKey -> prompt.answer(trust)
                }
            }
        }
    }

    @Test fun `disabled switch blocks every tool`() = runTest {
        enabled = false
        val (status, body) = call("ssh_host_list")
        assertThat(status).isEqualTo("error")
        assertThat(body["error_code"]!!.jsonPrimitive.content).isEqualTo("SSH_AGENT_HOST_MANAGEMENT_DISABLED")
    }

    @Test fun `list exposes configuration but never credentials`() = runTest {
        store.put(host("web"), SshCredential(password = "synthetic-password".toByteArray()))
        val (status, body) = call("ssh_host_list")

        assertThat(status).isEqualTo("success")
        val entry = body["hosts"]!!.jsonArray.single().jsonObject
        assertThat(entry.keys).containsExactly("alias", "hostname", "port", "username", "auth_type", "has_credential", "host_key_trusted")
        assertThat(entry["has_credential"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(body.toString()).doesNotContain("synthetic-password")
    }

    @Test fun `saving a password host asks the user and keeps the password out of the result`() = runTest {
        answerPrompts()
        val (status, body) = call("ssh_host_save", buildJsonObject {
            put("alias", "web"); put("hostname", "203.0.113.5"); put("username", "deploy")
        })

        assertThat(status).isEqualTo("success")
        assertThat(body["credential"]!!.jsonPrimitive.content).isEqualTo("stored")
        assertThat(store.password("web")).isEqualTo("synthetic-password")
        assertThat(body.toString()).doesNotContain("synthetic-password")
    }

    @Test fun `cancelled password prompt saves the host without credential`() = runTest {
        answerPrompts(password = null)
        val (_, body) = call("ssh_host_save", buildJsonObject {
            put("alias", "web"); put("hostname", "203.0.113.5"); put("username", "deploy")
        })

        assertThat(body["credential"]!!.jsonPrimitive.content).isEqualTo("missing")
        assertThat(store.find("web")).isNotNull()
    }

    @Test fun `key host without key gets a generated key and returns only the public key`() = runTest {
        val (_, body) = call("ssh_host_save", buildJsonObject {
            put("alias", "git"); put("hostname", "203.0.113.6"); put("username", "git"); put("auth_type", "key")
        })

        assertThat(body["public_key"]!!.jsonPrimitive.content).isEqualTo(runtime.publicKey)
        assertThat(store.privateKey("git")).isEqualTo("synthetic-private-key")
        assertThat(body.toString()).doesNotContain("synthetic-private-key")
    }

    @Test fun `changing the endpoint clears the trusted fingerprint`() {
        val trusted = host("web").copy(trustedKeyType = presented.type, trustedFingerprint = presented.fingerprint)

        assertThat(mergeSshHost(trusted, "web", trusted.hostname, 22, "other", SshAuthType.PASSWORD).trustedFingerprint)
            .isEqualTo(presented.fingerprint)
        assertThat(mergeSshHost(trusted, "web", "198.51.100.9", 22, "deploy", SshAuthType.PASSWORD).trustedFingerprint).isNull()
        assertThat(mergeSshHost(trusted, "web", trusted.hostname, 2222, "deploy", SshAuthType.PASSWORD).trustedFingerprint).isNull()
    }

    @Test fun `delete stops forwarding and removes the host`() = runTest {
        store.put(host("web"), null)
        val (status, _) = call("ssh_host_delete", buildJsonObject { put("alias", "web") })

        assertThat(status).isEqualTo("success")
        assertThat(runtime.stoppedForwards).containsExactly("web")
        assertThat(store.find("web")).isNull()
        assertThat(call("ssh_host_delete", buildJsonObject { put("alias", "web") }).second["error_code"]!!.jsonPrimitive.content)
            .isEqualTo("SSH_HOST_NOT_FOUND")
    }

    @Test fun `install key trusts after confirmation, installs, verifies and switches to key auth`() = runTest {
        answerPrompts()
        store.put(host("web"), SshCredential(password = "synthetic-password".toByteArray()))
        val (status, body) = call("ssh_host_install_key", buildJsonObject { put("alias", "web") })

        assertThat(status).isEqualTo("success")
        val saved = store.find("web")!!
        assertThat(saved.authType).isEqualTo(SshAuthType.KEY)
        assertThat(saved.trustedFingerprint).isEqualTo(presented.fingerprint)
        assertThat(store.privateKey("web")).isEqualTo("synthetic-private-key")
        assertThat(store.password("web")).isNull()
        assertThat(runtime.commands.first()).contains("authorized_keys")
        assertThat(runtime.commands.last()).isEqualTo("true")
        assertThat(body.toString()).doesNotContain("synthetic-password")
    }

    @Test fun `failed key login restores password authentication`() = runTest {
        answerPrompts()
        runtime.verifyExit = 255
        store.put(host("web"), SshCredential(password = "synthetic-password".toByteArray()))
        val (status, body) = call("ssh_host_install_key", buildJsonObject { put("alias", "web") })

        assertThat(status).isEqualTo("error")
        assertThat(body["error_code"]!!.jsonPrimitive.content).isEqualTo("SSH_KEY_LOGIN_FAILED")
        assertThat(store.find("web")!!.authType).isEqualTo(SshAuthType.PASSWORD)
        assertThat(store.find("web")!!.trustedFingerprint).isEqualTo(presented.fingerprint)
        assertThat(store.password("web")).isEqualTo("synthetic-password")
    }

    @Test fun `declined fingerprint changes nothing`() = runTest {
        answerPrompts(trust = false)
        store.put(host("web"), SshCredential(password = "synthetic-password".toByteArray()))
        val (_, body) = call("ssh_host_install_key", buildJsonObject { put("alias", "web") })

        assertThat(body["error_code"]!!.jsonPrimitive.content).isEqualTo("SSH_HOST_KEY_UNTRUSTED")
        assertThat(runtime.commands).isEmpty()
        assertThat(store.find("web")!!.trustedFingerprint).isNull()
    }

    @Test fun `install command rejects shell characters`() {
        val command = authorizedKeyInstallCommand(runtime.publicKey)
        assertThat(command).contains("grep -qxF '${runtime.publicKey}' ~/.ssh/authorized_keys")
        assertThat(command).contains("chmod 600 ~/.ssh/authorized_keys")
        listOf("ssh-ed25519 AAAA'; rm -rf ~; '", "ssh-ed25519 AAAA\ninfinity").forEach { bad ->
            assertThat(runCatching { authorizedKeyInstallCommand(bad) }.isFailure).isTrue()
        }
    }

    private fun host(alias: String) = SshHost(alias = alias, hostname = "203.0.113.5", username = "deploy", authType = SshAuthType.PASSWORD)

    private inner class FakeRuntime : SshHostManagementRuntime {
        val publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAISyntheticPublicKey infinity"
        val commands = mutableListOf<String>()
        val stoppedForwards = mutableListOf<String>()
        var verifyExit = 0
        override suspend fun probe(alias: String) = presented
        override suspend fun run(owner: String, alias: String, command: String): Int? {
            if (store.find(alias)?.trustedFingerprint == null) throw SshFailure("HOST_KEY_UNTRUSTED")
            commands += command
            return if (command == "true") verifyExit else 0
        }
        override suspend fun stopForward(alias: String) { stoppedForwards += alias }
        override fun generateKey() = "synthetic-private-key".toByteArray() to publicKey
    }

    private class MemoryHosts : SshHostStore {
        private val hosts = MutableStateFlow<List<SshHost>>(emptyList())
        private val secrets = mutableMapOf<String, Pair<String?, String?>>()

        fun put(host: SshHost, credential: SshCredential?) {
            hosts.value = hosts.value.filterNot { it.alias == host.alias } + host
            credential?.use { secrets[host.alias] = it.password.decodeOrNull() to it.privateKey.decodeOrNull() }
        }
        fun password(alias: String) = secrets[alias]?.first
        fun privateKey(alias: String) = secrets[alias]?.second

        override fun observeHosts() = hosts
        override suspend fun find(alias: String) = hosts.value.firstOrNull { it.alias == alias }
        // 与 RoomSshHostStore 契约一致：save 不接受传入的信任字段，连接身份或认证方式变化即清除信任。
        override suspend fun save(host: SshHost, credential: SshCredential?) {
            val previous = find(host.alias)
            val sameIdentity = previous != null && previous.hostname == host.hostname && previous.port == host.port &&
                previous.username == host.username && previous.authType == host.authType
            put(host.copy(
                trustedKeyType = if (sameIdentity) previous?.trustedKeyType else null,
                trustedFingerprint = if (sameIdentity) previous?.trustedFingerprint else null,
            ), credential)
            if (credential == null && previous != null && previous.authType != host.authType) secrets.remove(host.alias)
        }
        override suspend fun delete(alias: String) { hosts.value = hosts.value.filterNot { it.alias == alias }; secrets.remove(alias) }
        override suspend fun trust(alias: String, expectedOld: String?, key: SshHostKey, expectedHost: SshHost): Boolean {
            val current = find(alias) ?: return false
            if (current != expectedHost || current.trustedFingerprint != expectedOld) return false
            hosts.value = hosts.value.map { if (it.alias == alias) it.copy(trustedKeyType = key.type, trustedFingerprint = key.fingerprint) else it }
            return true
        }
        override fun credential(host: SshHost): SshCredential? = secrets[host.alias]?.let { (password, key) ->
            SshCredential(privateKey = key?.toByteArray() ?: ByteArray(0), password = password?.toByteArray() ?: ByteArray(0))
        }

        private fun ByteArray.decodeOrNull() = takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)
    }
}
