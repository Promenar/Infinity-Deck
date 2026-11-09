# PKCS8 私钥测试夹具

仅供 `SshPkcs8ImportTest` 使用的测试密钥，不对应任何真实主机或账户。加密夹具的口令统一为 `12345678`，期望指纹由 `ssh-keygen -l -E sha256` 计算并写在测试中。

| 文件 | 生成方式 | 结构 |
| --- | --- | --- |
| `ssh-keygen-p{256,384,521}.pem` | `ssh-keygen -t ecdsa -b <位数> -m PKCS8 -N ''`（OpenSSH 10.5 / LibreSSL 3.3） | 显式曲线参数，未加密 |
| `ssh-keygen-p{256,384,521}-encrypted.pem` | 同一密钥经 `ssh-keygen -p -m PKCS8 -N 12345678` | 显式曲线参数，PBES2 + PBKDF2-HMAC-SHA1 + AES-128-CBC |
| `openssl-p{256,384,521}.pem` | `openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-<位数>`（OpenSSL 4.1） | 命名曲线，未加密 |
| `openssl-p{256,384,521}-pbkdf2.pem` | 同一密钥经 `openssl pkcs8 -topk8 -v2 aes-256-cbc -v2prf hmacWithSHA256` | PBES2 + PBKDF2-HMAC-SHA256 + AES-256-CBC |
| `openssl-p{256,384,521}-scrypt.pem` | 同一密钥经 `openssl pkcs8 -topk8 -scrypt` | PBES2 + scrypt（N=16384, r=8, p=1）+ AES-256-CBC |
| `ssh-keygen-rsa2048-encrypted.pem` | `ssh-keygen -t rsa -b 2048 -m PKCS8` 后以 `-p -N 12345678` 加密 | RSA，PBES2 + PBKDF2-HMAC-SHA1 + AES-128-CBC |
