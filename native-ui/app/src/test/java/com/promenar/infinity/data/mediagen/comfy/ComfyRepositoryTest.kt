package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.data.backup.BackupPreferencePolicy
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ComfyRepositoryTest {
    @Test fun locksAgentUpdatesAndOnlyUserUnlockAllowsReplacement() = runTest {
        val f = ComfyFixture()
        f.repository.saveHost(f.host)
        val created = f.repository.defineTemplateByAgent(f.template)
        assertTrue(created.template.locked)
        assertEquals(PreflightStatus.PASS, created.preflight.status)
        try { f.repository.defineTemplateByAgent(f.template); fail("锁定后应拒绝") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("用户")) }
        f.repository.unlockTemplateByUser(f.template.id)
        assertTrue(f.repository.defineTemplateByAgent(f.template.copy(name = "新版")).template.locked)
    }

    @Test fun channelsDeriveReferenceLimitsFromSlotsAndPreserveId() = runTest {
        val f = ComfyFixture()
        f.repository.saveHost(f.host)
        f.repository.defineTemplateByAgent(f.template)
        val channel = f.repository.observeTemplateChannels().first().single()
        assertEquals("comfy:test", channel.id)
        assertEquals(1, channel.capabilities.maxImages)
        assertEquals(CharacterReferenceMode.SEPARATE, channel.capabilities.characterReferenceMode)
    }

    @Test fun changedHostAndMissingNodesRequireReviewAndCanBeRechecked() = runTest {
        val f = ComfyFixture()
        f.repository.saveHost(f.host)
        f.repository.defineTemplateByAgent(f.template)
        f.http.info = JsonObject(emptyMap())
        val changed = f.repository.preflight(f.template.id)
        assertEquals(PreflightStatus.REVIEW, changed.preflight.status)
        assertTrue(changed.preflight.missing.isNotEmpty())
        f.http.info = f.info
        assertEquals(PreflightStatus.PASS, f.repository.preflight(f.template.id).preflight.status)
        f.repository.saveHost(f.host.copy(baseUrl = "http://other:8188"))
        assertEquals(PreflightStatus.REVIEW, f.repository.templates().single().preflight.status)
    }

    @Test fun configurationRoundTripsWithoutCredentialsAndBackupIncludesOnlyConfiguration() = runTest {
        val f = ComfyFixture()
        f.repository.saveHost(f.host.copy(authKind = ComfyAuthKind.BEARER), ComfyCredential.Bearer("fixture-token"))
        f.repository.defineTemplateByAgent(f.template)
        val encoded = ComfyConfigurationCodec.encode(f.storage.read())
        assertFalse(encoded.contains("fixture-token"))
        assertEquals(f.storage.read(), ComfyConfigurationCodec.decode(encoded))
        assertTrue(BackupPreferencePolicy.isAllowed("settings", COMFY_CONFIGURATION_KEY))
        assertFalse(SecretCatalog.isBackupEligible(ComfyCredentials.secretId(f.host.id), emptyList()))
        f.repository.removeHost(f.host.id)
        assertTrue(f.repository.templates().isEmpty())
        assertFalse(f.secrets.contains(ComfyCredentials.secretId(f.host.id)))
    }
}
