package com.promenar.infinity.data.ssh

private val PUBLIC_KEY = Regex("(?:ssh-ed25519|ecdsa-sha2-nistp256|ecdsa-sha2-nistp384|ecdsa-sha2-nistp521|ssh-rsa) [A-Za-z0-9+/]+={0,2}(?: [A-Za-z0-9 ]+)?")

/** 幂等安装经过字符校验的公钥，并限制远端目录与文件权限。 */
internal fun authorizedKeyInstallCommand(publicKey: String): String {
    require(PUBLIC_KEY.matches(publicKey)) { "公钥格式无效" }
    val quoted = "'$publicKey'"
    return "umask 077 && mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys && " +
        "(grep -qxF $quoted ~/.ssh/authorized_keys || { " +
        "if [ -s ~/.ssh/authorized_keys ] && [ -n \"\$(tail -c 1 ~/.ssh/authorized_keys)\" ]; then printf '\\n' >> ~/.ssh/authorized_keys; fi; " +
        "printf '%s\\n' $quoted >> ~/.ssh/authorized_keys; })"
}
