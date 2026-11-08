package com.promenar.infinity.data.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.PublicKeyProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.encryption.PublicKeyRecipient
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** BC 家族升级必须保留既有 PDFBox 的真实密码及 CMS/证书路径，不只检查类是否存在。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], manifest = Config.NONE)
class SshCryptoDependencyCompatibilityTest {
    @Test fun `统一BC版本后PDF密码加密解密保留页面与中文元数据`() {
        val bytes = encryptedDocument(StandardProtectionPolicy("synthetic-owner", "synthetic-user", AccessPermission()))
        try {
            assertThrows(InvalidPasswordException::class.java) { PDDocument.load(bytes, "synthetic-wrong") }
            PDDocument.load(bytes, "synthetic-user").use(::assertDocument)
        } finally { bytes.fill(0) }
    }

    @Test fun `统一BC版本后PDF公钥CMS与PKCS12私钥解密实际往返`() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500Principal("CN=Infinity Deck Synthetic Test")
        val holder = JcaX509v3CertificateBuilder(
            subject, BigInteger.ONE, Date(0), Date(4_102_444_800_000L), subject, key.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(key.private))
        val certificate = JcaX509CertificateConverter().getCertificate(holder)
        val policy = PublicKeyProtectionPolicy().apply {
            addRecipient(PublicKeyRecipient().apply { setX509(certificate); setPermission(AccessPermission()) })
        }
        val password = "synthetic-keystore"
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("synthetic", key.private, password.toCharArray(), arrayOf(certificate))
        }
        val keys = ByteArrayOutputStream().also { store.store(it, password.toCharArray()) }.toByteArray()
        val bytes = encryptedDocument(policy)
        try {
            ByteArrayInputStream(keys).use { input ->
                PDDocument.load(bytes, password, input, "synthetic").use(::assertDocument)
            }
        } finally { bytes.fill(0); keys.fill(0); store.deleteEntry("synthetic") }
    }

    private fun encryptedDocument(policy: com.tom_roush.pdfbox.pdmodel.encryption.ProtectionPolicy): ByteArray =
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.documentInformation.title = "合成中文 PDF 哨兵"
            policy.encryptionKeyLength = 256
            policy.isPreferAES = true
            document.protect(policy)
            ByteArrayOutputStream().also(document::save).toByteArray()
        }

    private fun assertDocument(document: PDDocument) {
        assertThat(document.isEncrypted).isTrue()
        assertThat(document.numberOfPages).isEqualTo(1)
        assertThat(document.documentInformation.title).isEqualTo("合成中文 PDF 哨兵")
    }
}
