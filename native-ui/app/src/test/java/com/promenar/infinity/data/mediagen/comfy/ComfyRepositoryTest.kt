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
    @Test fun approvedAgentReplacementStaysLockedAndRunsPreflight() = runTest {
        val f = ComfyFixture()
        f.repository.saveHost(f.host)
        val created = f.repository.defineTemplateByAgent(f.template)
        assertTrue(created.template.locked)
        assertEquals(PreflightStatus.PASS, created.preflight.status)
        f.http.calls.clear()
        f.http.info = JsonObject(emptyMap())
        val updated = f.repository.defineTemplateByAgent(f.template.copy(name = "新版", locked = false))
        assertTrue(updated.template.locked)
        assertEquals("新版", updated.template.name)
        assertEquals(PreflightStatus.REVIEW, updated.preflight.status)
        assertTrue(updated.preflight.missing.isNotEmpty())
        assertEquals(setOf("/object_info/Test", "/object_info/Save"), f.http.calls.map { it.first }.toSet())
        assertEquals(listOf(updated), f.repository.templates())
    }

    @Test fun connectionCheckOnlyRequestsLightweightSystemStats() = runTest {
        val f = ComfyFixture()
        f.repository.saveHost(f.host)
        f.repository.checkConnection(f.host.id)
        assertEquals(listOf("/system_stats"), f.http.calls.map { it.first })
    }

    @Test fun preflightOnlyRequestsNodeTypesUsedByTemplate() = runTest {
        val f = ComfyFixture()
        f.prepare()
        f.http.calls.clear()
        assertEquals(PreflightStatus.PASS, f.repository.preflight(f.template.id).preflight.status)
        assertEquals(setOf("/object_info/Test", "/object_info/Save"), f.http.calls.map { it.first }.toSet())
    }

    @Test fun agentDefinitionRecordsCheckTimeThatSurvivesStorage() = runTest {
        val f = ComfyFixture()
        val repository = ComfyRepository(f.storage, ComfyCredentials(f.secrets), f.http, clock = { 1_700_000_000_000L })
        repository.saveHost(f.host)
        assertEquals(1_700_000_000_000L, repository.defineTemplateByAgent(f.template).checkedAt)
        assertEquals(1_700_000_000_000L, ComfyConfigurationCodec.decode(ComfyConfigurationCodec.encode(f.storage.read())).templates.single().checkedAt)
        val legacy = ComfyConfigurationCodec.encode(f.storage.read()).replace(",\"checkedAt\":1700000000000", "")
        assertFalse(legacy.contains("checkedAt"))
        assertNull(ComfyConfigurationCodec.decode(legacy).templates.single().checkedAt)
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
