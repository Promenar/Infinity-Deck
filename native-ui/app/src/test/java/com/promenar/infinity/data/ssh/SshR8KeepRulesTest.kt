package com.promenar.infinity.data.ssh

import com.google.common.truth.Truth.assertWithMessage
import com.jcraft.jsch.JSch
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

/**
 * JSch 按配置字符串反射加载算法与认证类，Release 的 R8 规则必须覆盖应用固定算法表用到的每个类；
 * 顶层包的类多为包内私有，还必须保留在原包内，否则反射调用方被搬包后抛 IllegalAccessException。
 */
class SshR8KeepRulesTest {
    private val moduleRoot: Path = Path.of(System.getProperty("user.dir") ?: ".").let { root ->
        if (Files.isDirectory(root.resolve("src/main"))) root else root.resolve("app")
    }
    private val rules = String(Files.readAllBytes(moduleRoot.resolve("proguard-rules.pro")), Charsets.UTF_8)

    @Test
    fun `应用固定的 SSH 算法与认证类全部被 R8 规则保留在原包`() {
        SshKeyGenerator.configuredClient()
        val algorithms = listOf("kex", "server_host_key", "cipher.c2s", "cipher.s2c", "mac.c2s", "mac.s2c")
            .flatMap { JSch.getConfig(it).split(',') }
        val helpers = listOf(
            "ecdh-sha2-nistp", "xdh", "dh", "sha-256", "random", "bcrypt",
            "keypairgen.eddsa", "keypairgen_fromprivate.eddsa", "keypairgen.rsa", "keypairgen.ecdsa",
            // 加密私钥解密：KeyPair.genKey 无论哪种格式都先建 md5；PEM 与 PKCS8 另需 CBC 与 PBKDF2/scrypt。
            "md5", "aes128-cbc", "aes192-cbc", "aes256-cbc", "3des-cbc", "pbkdf2", "scrypt",
            "userauth.none", "userauth.password", "userauth.publickey",
        )
        val classes = (algorithms + helpers).map { name ->
            JSch.getConfig(name).also { assertWithMessage("JSch 未登记算法 $name").that(it).isNotNull() }
        }.toSet()

        assertWithMessage("顶层包保留规则").that(rules).contains("-keep,allowshrinking class com.jcraft.jsch.* { *; }")
        classes.forEach { className ->
            assertWithMessage("R8 规则缺少反射类 $className").that(rules).contains("-keep class $className { *; }")
        }
    }
}
