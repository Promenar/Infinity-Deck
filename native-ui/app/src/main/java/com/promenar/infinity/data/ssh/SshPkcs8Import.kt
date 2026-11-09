package com.promenar.infinity.data.ssh

import java.math.BigInteger
import java.util.Base64
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo
import org.bouncycastle.asn1.pkcs.PBES2Parameters
import org.bouncycastle.asn1.pkcs.PBKDF2Params
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.sec.ECPrivateKey
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.asn1.x9.X962Parameters
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA224Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA384Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.FixedPointCombMultiplier

/**
 * PKCS8 导入预处理：口令解密由 BouncyCastle 完成，以区分口令错误与格式问题；
 * EC 私钥（含 ssh-keygen 写出的显式曲线参数）转为带命名曲线的 SEC1，供 JSch 解析。
 */
internal object SshPkcs8Import {
    private const val PBES2 = "1.2.840.113549.1.5.13"
    private const val PBKDF2 = "1.2.840.113549.1.5.12"
    private const val SCRYPT = "1.3.6.1.4.1.11591.4.11"
    private val curves = listOf(SECObjectIdentifiers.secp256r1, SECObjectIdentifiers.secp384r1, SECObjectIdentifiers.secp521r1)

    /** 返回交给 JSch 的 PEM（调用方负责清零）；原文可直接交给 JSch 时返回 null。 */
    fun prepare(label: String, der: ByteArray, phrase: ByteArray?): ByteArray? = when (label) {
        "PRIVATE KEY" -> ecSec1Pem(PrivateKeyInfo.getInstance(der))
        "ENCRYPTED PRIVATE KEY" -> {
            validateKdf(der)
            if (phrase == null || phrase.isEmpty()) throw SshFailure("KEY_PASSPHRASE_REQUIRED")
            val plain = decrypt(der, phrase)
            try {
                val info = try { PrivateKeyInfo.getInstance(plain) } catch (_: Exception) { throw SshFailure("INVALID_KEY_PASSPHRASE") }
                ecSec1Pem(info) ?: pem("PRIVATE KEY", plain)
            } finally { plain.fill(0) }
        }
        else -> null
    }

    /** 在派生密钥前限定 PKCS8 的口令派生成本，防止文件参数占用过量资源。 */
    private fun validateKdf(der: ByteArray) {
        val info = ASN1Sequence.getInstance(der)
        val algorithm = ASN1Sequence.getInstance(info.getObjectAt(0))
        if (ASN1ObjectIdentifier.getInstance(algorithm.getObjectAt(0)).id != PBES2) throw SshFailure("UNSUPPORTED_KEY_CIPHER")
        val params = ASN1Sequence.getInstance(algorithm.getObjectAt(1))
        val kdf = ASN1Sequence.getInstance(params.getObjectAt(0))
        val values = ASN1Sequence.getInstance(kdf.getObjectAt(1))
        val salt = ASN1OctetString.getInstance(values.getObjectAt(0)).octets
        if (salt.size !in 1..256) throw SshFailure("KEY_KDF_LIMIT")
        fun positive(index: Int, max: Long): Long {
            val value = ASN1Integer.getInstance(values.getObjectAt(index)).value
            if (value.signum() <= 0 || value.bitLength() > 63 || value.toLong() > max) throw SshFailure("KEY_KDF_LIMIT")
            return value.toLong()
        }
        when (ASN1ObjectIdentifier.getInstance(kdf.getObjectAt(0)).id) {
            PBKDF2 -> positive(1, 1_000_000)
            SCRYPT -> {
                val n = positive(1, 65_536); val r = positive(2, 8); val p = positive(3, 4)
                if (n < 2 || n and (n - 1) != 0L || 128L * n * r > 64L * 1024 * 1024 || n * r * p > 2_097_152) throw SshFailure("KEY_KDF_LIMIT")
            }
            else -> throw SshFailure("UNSUPPORTED_KEY_CIPHER")
        }
    }

    private fun decrypt(der: ByteArray, phrase: ByteArray): ByteArray {
        val info = EncryptedPrivateKeyInfo.getInstance(der)
        val pbes2 = PBES2Parameters.getInstance(info.encryptionAlgorithm.parameters)
        val scheme = pbes2.encryptionScheme
        val keyLength = when (scheme.algorithm) {
            NISTObjectIdentifiers.id_aes128_CBC -> 16
            NISTObjectIdentifiers.id_aes192_CBC -> 24
            NISTObjectIdentifiers.id_aes256_CBC -> 32
            else -> throw SshFailure("UNSUPPORTED_KEY_CIPHER")
        }
        val iv = ASN1OctetString.getInstance(scheme.parameters).octets
        if (iv.size != 16) throw SshFailure("INVALID_PRIVATE_KEY")
        val kdf = pbes2.keyDerivationFunc
        val key = when (kdf.algorithm.id) {
            PBKDF2 -> {
                val params = PBKDF2Params.getInstance(kdf.parameters)
                requireKeyLength(params.keyLength, keyLength)
                val generator = PKCS5S2ParametersGenerator(prf(params))
                generator.init(phrase, params.salt, params.iterationCount.toInt())
                (generator.generateDerivedParameters(keyLength * 8) as KeyParameter).key
            }
            SCRYPT -> {
                val values = ASN1Sequence.getInstance(kdf.parameters)
                fun int(index: Int) = ASN1Integer.getInstance(values.getObjectAt(index)).value.toInt()
                if (values.size() > 4) requireKeyLength(ASN1Integer.getInstance(values.getObjectAt(4)).value, keyLength)
                SCrypt.generate(phrase, ASN1OctetString.getInstance(values.getObjectAt(0)).octets, int(1), int(2), int(3), keyLength)
            }
            else -> throw SshFailure("UNSUPPORTED_KEY_CIPHER")
        }
        try {
            val cipher = PaddedBufferedBlockCipher(CBCBlockCipher.newInstance(AESEngine.newInstance()))
            cipher.init(false, ParametersWithIV(KeyParameter(key), iv))
            val data = info.encryptedData
            val out = ByteArray(cipher.getOutputSize(data.size))
            try {
                val written = cipher.processBytes(data, 0, data.size, out, 0)
                return out.copyOf(written + cipher.doFinal(out, written))
            } catch (_: InvalidCipherTextException) {
                throw SshFailure("INVALID_KEY_PASSPHRASE")
            } finally { out.fill(0); data.fill(0) }
        } finally { key.fill(0) }
    }

    private fun requireKeyLength(declared: BigInteger?, expected: Int) {
        if (declared != null && declared != BigInteger.valueOf(expected.toLong())) throw SshFailure("UNSUPPORTED_KEY_CIPHER")
    }

    private fun prf(params: PBKDF2Params): Digest = when (params.prf.algorithm) {
        PKCSObjectIdentifiers.id_hmacWithSHA1 -> SHA1Digest()
        PKCSObjectIdentifiers.id_hmacWithSHA224 -> SHA224Digest()
        PKCSObjectIdentifiers.id_hmacWithSHA256 -> SHA256Digest()
        PKCSObjectIdentifiers.id_hmacWithSHA384 -> SHA384Digest()
        PKCSObjectIdentifiers.id_hmacWithSHA512 -> SHA512Digest()
        else -> throw SshFailure("UNSUPPORTED_KEY_CIPHER")
    }

    /** EC 私钥转为带命名曲线与公钥的 SEC1 PEM；其它算法返回 null。 */
    private fun ecSec1Pem(info: PrivateKeyInfo): ByteArray? {
        if (info.privateKeyAlgorithm.algorithm != X9ObjectIdentifiers.id_ecPublicKey) return null
        val oid = namedCurve(X962Parameters.getInstance(info.privateKeyAlgorithm.parameters))
        val curve = SECNamedCurves.getByOID(oid)
        val private = ECPrivateKey.getInstance(info.parsePrivateKey())
        val d = private.key
        if (d.signum() <= 0 || d >= curve.n) throw SshFailure("INVALID_PRIVATE_KEY")
        val point = FixedPointCombMultiplier().multiply(curve.g, d).normalize()
        private.publicKey?.let { if (curve.curve.decodePoint(it.octets) != point) throw SshFailure("INVALID_PRIVATE_KEY") }
        val sec1 = ECPrivateKey(curve.n.bitLength(), d, DERBitString(point.getEncoded(false)), oid).encoded
        try { return pem("EC PRIVATE KEY", sec1) } finally { sec1.fill(0) }
    }

    /** 仅接受 NIST P-256/384/521；显式参数须与对应命名曲线完全一致。 */
    private fun namedCurve(params: X962Parameters): ASN1ObjectIdentifier {
        if (params.isNamedCurve) {
            return ASN1ObjectIdentifier.getInstance(params.parameters).takeIf { it in curves } ?: throw SshFailure("UNSUPPORTED_USER_KEY")
        }
        if (params.isImplicitlyCA) throw SshFailure("UNSUPPORTED_USER_KEY")
        val explicit = X9ECParameters.getInstance(params.parameters)
        return curves.firstOrNull { oid ->
            val named = SECNamedCurves.getByOID(oid)
            named.curve.field == explicit.curve.field &&
                named.curve.a.toBigInteger() == explicit.curve.a.toBigInteger() &&
                named.curve.b.toBigInteger() == explicit.curve.b.toBigInteger() &&
                named.n == explicit.n &&
                named.g.getEncoded(false).contentEquals(explicit.g.getEncoded(false))
        } ?: throw SshFailure("UNSUPPORTED_USER_KEY")
    }

    private fun pem(label: String, der: ByteArray): ByteArray {
        val head = "-----BEGIN $label-----\n".toByteArray(Charsets.US_ASCII)
        val tail = "\n-----END $label-----\n".toByteArray(Charsets.US_ASCII)
        val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encode(der)
        try {
            return ByteArray(head.size + body.size + tail.size).also {
                head.copyInto(it); body.copyInto(it, head.size); tail.copyInto(it, head.size + body.size)
            }
        } finally { body.fill(0) }
    }
}
