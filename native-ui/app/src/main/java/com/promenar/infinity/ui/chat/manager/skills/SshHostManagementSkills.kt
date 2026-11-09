package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.security.SshCredential
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.ssh.SshHostKey
import com.promenar.infinity.data.ssh.SshHostStore
import com.promenar.infinity.data.ssh.SshKeyGenerator
import com.promenar.infinity.data.ssh.SshPolicy
import com.promenar.infinity.data.ssh.SshUserPromptBroker
import com.promenar.infinity.data.ssh.authorizedKeyInstallCommand
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.tool.ToolSchemaValidation
import com.promenar.infinity.domain.tool.ToolSchemaValidator
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Agent 管理 SSH 主机时依赖的运行时能力；测试可替换为假实现。 */
interface SshHostManagementRuntime {
    suspend fun probe(alias: String): SshHostKey
    /** 在主机上执行固定命令并返回退出码；超时或连接失败抛出 [SshFailure]。 */
    suspend fun run(owner: String, alias: String, command: String): Int?
    suspend fun stopForward(alias: String)
    fun generateKey(): Pair<ByteArray, String>
}

fun sshHostManagementTools(
    store: SshHostStore,
    runtime: SshHostManagementRuntime,
    prompts: SshUserPromptBroker,
    isEnabled: () -> Boolean,
): List<SkillDefinition> = listOf(
    SshHostListSkill(store),
    SshHostSaveSkill(store, runtime, prompts),
    SshHostDeleteSkill(store, runtime),
    SshHostInstallKeySkill(store, runtime, prompts),
).onEach { it.isEnabled = isEnabled }

private const val SECRET_RULE = "密码由用户在本应用弹出的安全输入框中填写，不经过对话，也不要向用户索要密码、私钥或口令。"

private abstract class SshHostSkill : SkillDefinition {
    override val mcpServerId: String? = null
    private val validator = ToolSchemaValidator()
    /** 设备级授权开关；工具列表已按它过滤，执行时再核对一次，防止开关在审批后被关闭。 */
    var isEnabled: () -> Boolean = { false }

    final override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        if (!isEnabled()) return failure("AGENT_HOST_MANAGEMENT_DISABLED")
        if (validator.validate(parametersSchema, args) is ToolSchemaValidation.Invalid) return failure("INVALID_ARGUMENTS")
        return try {
            ToolResult(id = "ssh_host_${UUID.randomUUID()}", content = run(args, context).toString(), status = "success")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: SshFailure) { failure(failure.code)
        } catch (_: IllegalArgumentException) { failure("INVALID_ARGUMENTS")
        } catch (_: Exception) { failure("SSH_OPERATION_FAILED") }
    }

    abstract suspend fun run(args: JsonObject, context: SkillExecutionContext): JsonObject

    protected fun JsonObject.text(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: throw SshFailure("INVALID_ARGUMENTS")
    protected fun JsonObject.optionalText(key: String): String? = if (key in this) text(key) else null
    protected fun JsonObject.alias(): String = SshPolicy.alias(text("alias"))

    private fun failure(code: String) = ToolResult(
        id = "ssh_host_${UUID.randomUUID()}",
        content = buildJsonObject {
            put("error_code", if (code.startsWith("SSH_")) code else "SSH_$code")
            put("guidance", sshHostGuidance(code))
        }.toString(),
        status = "error",
    )
}

private suspend fun SshHostStore.hasCredential(host: SshHost): Boolean =
    withContext(Dispatchers.IO) { credential(host)?.use { true } ?: false }

private class SshHostListSkill(private val store: SshHostStore) : SshHostSkill() {
    override val id = "ssh_host_list"
    override val name = id
    override val risk = ToolRisk.SAFE_READ
    override val description = "列出本应用中已配置的 SSH 主机：别名、地址、端口、用户名、认证方式、是否已保存凭据、主机指纹是否已信任。不返回任何凭据。"
    override val parametersSchema = """{"type":"object","properties":{},"additionalProperties":false}"""

    override suspend fun run(args: JsonObject, context: SkillExecutionContext): JsonObject {
        val hosts = withContext(Dispatchers.IO) { store.observeHosts().first() }
        return buildJsonObject {
            put("hosts", JsonArray(hosts.sortedBy(SshHost::alias).map { host ->
                buildJsonObject {
                    put("alias", host.alias)
                    put("hostname", host.hostname)
                    put("port", host.port)
                    put("username", host.username)
                    put("auth_type", host.authType.wireName())
                    put("has_credential", store.hasCredential(host))
                    put("host_key_trusted", host.trustedFingerprint != null)
                }
            }))
        }
    }
}

private class SshHostSaveSkill(
    private val store: SshHostStore,
    private val runtime: SshHostManagementRuntime,
    private val prompts: SshUserPromptBroker,
) : SshHostSkill() {
    override val id = "ssh_host_save"
    override val name = id
    override val risk = ToolRisk.EXTERNAL_WRITE
    override val description = "新增或修改本应用的 SSH 主机配置（按别名定位）。别名为 1–48 位小写字母开头的字母、数字、- 或 _；" +
        "auth_type 为 password 或 key，新建默认 password。password 主机缺少凭据时由用户在弹窗中输入；key 主机缺少私钥时生成新密钥并返回公钥。" +
        "地址、端口、用户名或认证方式变化后需要重新确认主机指纹。$SECRET_RULE"
    override val parametersSchema = """{"type":"object","properties":{"alias":{"type":"string"},"hostname":{"type":"string"},"port":{"type":"integer","description":"1–65535，默认 22"},"username":{"type":"string"},"auth_type":{"type":"string","enum":["password","key"]}},"required":["alias","hostname","username"],"additionalProperties":false}"""

    override suspend fun run(args: JsonObject, context: SkillExecutionContext): JsonObject {
        val alias = args.alias()
        val port = if ("port" in args) (args["port"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw SshFailure("INVALID_ARGUMENTS") else null
        val existing = store.find(alias)
        val authType = args.optionalText("auth_type")?.let(::parseAuthType) ?: existing?.authType ?: SshAuthType.PASSWORD
        val host = mergeSshHost(existing, alias, args.text("hostname"), port, args.text("username"), authType)
        withContext(Dispatchers.IO) { store.save(host) }
        var publicKey: String? = null
        var credentialStored = store.hasCredential(host)
        if (!credentialStored) {
            when (authType) {
                SshAuthType.PASSWORD -> prompts.requestPassword(host)?.let { password ->
                    SshCredential(password = password).use { withContext(Dispatchers.IO + NonCancellable) { store.save(host, it) } }
                    credentialStored = true
                }
                SshAuthType.KEY -> {
                    val (privateKey, public) = runtime.generateKey()
                    SshCredential(privateKey = privateKey).use { withContext(Dispatchers.IO + NonCancellable) { store.save(host, it) } }
                    publicKey = public
                    credentialStored = true
                }
            }
        }
        return buildJsonObject {
            put("alias", alias)
            put("created", existing == null)
            put("auth_type", authType.wireName())
            put("credential", if (credentialStored) "stored" else "missing")
            put("host_key_trusted", host.trustedFingerprint != null)
            publicKey?.let {
                put("public_key", it)
                put("guidance", "新密钥的公钥需加入目标服务器的 ~/.ssh/authorized_keys；也可改用 password 认证后调用 ssh_host_install_key 自动安装。")
            }
            if (!credentialStored) put("guidance", "用户未在弹窗中提供密码；请让用户在 设置 → 工具设置 → SSH 主机 中编辑该主机填写凭据。")
        }
    }
}

private class SshHostDeleteSkill(
    private val store: SshHostStore,
    private val runtime: SshHostManagementRuntime,
) : SshHostSkill() {
    override val id = "ssh_host_delete"
    override val name = id
    override val risk = ToolRisk.DELETE
    override val description = "删除本应用中的一个 SSH 主机配置及其本机凭据，并停止该主机的端口转发。不会改动远端服务器。"
    override val parametersSchema = """{"type":"object","properties":{"alias":{"type":"string"}},"required":["alias"],"additionalProperties":false}"""

    override suspend fun run(args: JsonObject, context: SkillExecutionContext): JsonObject {
        val alias = args.alias()
        store.find(alias) ?: throw SshFailure("HOST_NOT_FOUND")
        withContext(Dispatchers.IO + NonCancellable) {
            runtime.stopForward(alias)
            store.delete(alias)
        }
        return buildJsonObject { put("alias", alias); put("deleted", true) }
    }
}

private class SshHostInstallKeySkill(
    private val store: SshHostStore,
    private val runtime: SshHostManagementRuntime,
    private val prompts: SshUserPromptBroker,
) : SshHostSkill() {
    override val id = "ssh_host_install_key"
    override val name = id
    override val risk = ToolRisk.PROCESS
    override val description = "为使用密码认证的 SSH 主机配置免密登录：本应用生成本机 ed25519 密钥，用现有密码登录后把公钥追加到远端 ~/.ssh/authorized_keys（已存在则跳过，并修正目录与文件权限），" +
        "再用新密钥验证登录；成功后该主机改为密钥认证并删除本机保存的密码，失败则恢复原配置。主机指纹未信任时由用户在弹窗中核对确认。$SECRET_RULE"
    override val parametersSchema = """{"type":"object","properties":{"alias":{"type":"string"}},"required":["alias"],"additionalProperties":false}"""

    override suspend fun run(args: JsonObject, context: SkillExecutionContext): JsonObject {
        val alias = args.alias()
        val owner = "agent:${context.sessionId}"
        var host = store.find(alias) ?: throw SshFailure("HOST_NOT_FOUND")
        if (host.authType == SshAuthType.KEY) {
            return buildJsonObject { put("alias", alias); put("auth_type", "key"); put("changed", false) }
        }
        host = ensureTrustedHostKey(host)
        val pinned = SshHostKey(requireNotNull(host.trustedKeyType), requireNotNull(host.trustedFingerprint))
        val stored = withContext(Dispatchers.IO) { store.credential(host) }
        val previous = stored ?: prompts.requestPassword(host)?.let { SshCredential(password = it) }
            ?: throw SshFailure("CREDENTIAL_MISSING")
        previous.use { password ->
            // 安装命令经运行时从存储取凭据登录；弹窗新输入的密码先写入存储。
            if (stored == null) withContext(Dispatchers.IO + NonCancellable) { store.save(host, password.copyPassword()) }
            val (privateKey, publicKey) = runtime.generateKey()
            try {
                val installed = runtime.run(owner, alias, authorizedKeyInstallCommand(publicKey))
                if (installed != 0) throw SshFailure("KEY_INSTALL_FAILED")
                val keyHost = host.copy(authType = SshAuthType.KEY)
                SshCredential(privateKey = privateKey.copyOf()).use { saveRepinned(keyHost, it, pinned) }
                val verified = try { runtime.run(owner, alias, "true") == 0 } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
                if (!verified) {
                    password.copyPassword().use { saveRepinned(host, it, pinned) }
                    throw SshFailure("KEY_LOGIN_FAILED")
                }
                return buildJsonObject {
                    put("alias", alias)
                    put("auth_type", "key")
                    put("changed", true)
                    put("public_key", publicKey)
                    put("verified", true)
                }
            } finally { privateKey.fill(0) }
        }
    }

    /**
     * 存储在认证方式变化时按安全约定清除已信任指纹；此处切换的是同一端点的认证方式，
     * 把本次调用中刚核对过的同一主机密钥重新固定，不接受任何新密钥。
     */
    private suspend fun saveRepinned(target: SshHost, credential: SshCredential, pinned: SshHostKey) =
        withContext(Dispatchers.IO + NonCancellable) {
            store.save(target, credential)
            val saved = store.find(target.alias) ?: throw SshFailure("HOST_NOT_FOUND")
            if (saved.trustedFingerprint == pinned.fingerprint && saved.trustedKeyType == pinned.type) return@withContext
            if (!store.trust(target.alias, saved.trustedFingerprint, pinned, saved)) throw SshFailure("HOST_CONFIGURATION_CHANGED")
        }

    private suspend fun ensureTrustedHostKey(host: SshHost): SshHost {
        val presented = runtime.probe(host.alias)
        if (host.trustedFingerprint == presented.fingerprint && host.trustedKeyType == presented.type) return host
        if (!prompts.confirmHostKey(host, presented)) throw SshFailure("HOST_KEY_UNTRUSTED")
        val accepted = withContext(Dispatchers.IO) { store.trust(host.alias, host.trustedFingerprint, presented, host) }
        if (!accepted) throw SshFailure("HOST_CONFIGURATION_CHANGED")
        return store.find(host.alias) ?: throw SshFailure("HOST_NOT_FOUND")
    }
}

private fun SshCredential.copyPassword() = SshCredential(password = password.copyOf())

private fun SshAuthType.wireName() = if (this == SshAuthType.KEY) "key" else "password"

private fun parseAuthType(value: String) = when (value) {
    "password" -> SshAuthType.PASSWORD
    "key" -> SshAuthType.KEY
    else -> throw SshFailure("INVALID_ARGUMENTS")
}

/** 合并 Agent 提交的非敏感字段；连接身份变化时清除已信任指纹，需重新核对。 */
internal fun mergeSshHost(
    existing: SshHost?,
    alias: String,
    hostname: String,
    port: Int?,
    username: String,
    authType: SshAuthType,
): SshHost {
    if (existing == null) return SshHost(alias = alias, hostname = hostname, port = port ?: 22, username = username, authType = authType)
    val updated = existing.copy(hostname = hostname, port = port ?: existing.port, username = username, authType = authType)
    val endpointChanged = updated.hostname != existing.hostname || updated.port != existing.port
    return if (endpointChanged) updated.copy(trustedKeyType = null, trustedFingerprint = null) else updated
}

internal fun sshHostGuidance(code: String): String = when (code.removePrefix("SSH_")) {
    "AGENT_HOST_MANAGEMENT_DISABLED" -> "用户尚未允许智能体管理 SSH 主机，请提示用户在 设置 → 工具设置 → SSH 主机 中开启。"
    "HOST_KEY_UNTRUSTED" -> "用户没有确认主机指纹，未做任何改动；需要用户核对指纹后再试。"
    "CREDENTIAL_MISSING" -> "没有可用于登录的密码，用户未在弹窗中提供；请让用户稍后重试或在设置中编辑该主机。"
    "KEY_INSTALL_FAILED" -> "公钥写入远端 authorized_keys 失败（可能是主目录不可写），原配置未改变。"
    "KEY_LOGIN_FAILED" -> "公钥已写入，但用新密钥登录失败（服务器可能禁用了公钥认证），已恢复为密码认证。"
    else -> sshGuidance(code)
}
