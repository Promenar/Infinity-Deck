package com.promenar.infinity.ui.settings

import com.promenar.infinity.data.mediagen.cloud.BillingUnit
import com.promenar.infinity.data.mediagen.comfy.ComfyAuthKind
import com.promenar.infinity.data.mediagen.comfy.ComfyTemplateState
import kotlinx.serialization.json.JsonObject
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.MediaModelSettings
import com.promenar.infinity.domain.mediagen.*
import org.junit.Assert.*
import org.junit.Test

class MediaSettingsPresentationTest {
    private val capabilities = MediaCapabilities(setOf(GenerationMode.REFERENCE), maxImages = 3,
        characterReferenceMode = CharacterReferenceMode.SEPARATE, referenceOrder = listOf("front", "side", "back"))

    @Test fun everyGroupHasItsOwnDefaultAndReferenceDescription() {
        val channels = MediaGroup.entries.flatMap { g -> listOf(MediaChannel("${g}1", g, capabilities), MediaChannel("${g}2", g, capabilities)) }
        val groups = mediaChannelRows(channels, emptyList(), emptyList(), emptyList())
        MediaGroup.entries.forEach { g ->
            assertEquals(listOf(true, false), groups.getValue(g).map { it.isDefault })
            assertEquals(ReferenceInputDescription(CharacterReferenceMode.SEPARATE, 3), groups.getValue(g).first().references)
        }
        assertEquals(ReferenceInputDescription(CharacterReferenceMode.COMPOSITE, 3),
            referenceInputDescription(capabilities.copy(characterReferenceMode = CharacterReferenceMode.COMPOSITE)))
        assertNull(referenceInputDescription(capabilities.copy(maxImages = 0)))
    }

    @Test fun moveUpOnlyExchangesTheSelectedItemWithItsPredecessor() {
        assertEquals(listOf("a", "c", "b"), moveMediaItemUp(listOf("a", "b", "c"), "c"))
        assertEquals(listOf("a", "b"), moveMediaItemUp(listOf("a", "b"), "a"))
        assertEquals(listOf("a", "b"), moveMediaItemUp(listOf("a", "b"), "missing"))
    }

    @Test fun changingInputModePreservesTheReferenceOrder() {
        val initial = MediaModelSettings(MediaGroup.VIDEO, capabilities)
        val composite = changeMediaReferences(initial, CharacterReferenceMode.COMPOSITE)
        assertEquals(capabilities.referenceOrder, composite.capabilities.referenceOrder)
        val separate = changeMediaReferences(composite, CharacterReferenceMode.SEPARATE)
        assertEquals(listOf("side", "front", "back"), moveMediaItemUp(separate.capabilities.referenceOrder, "side"))
    }

    @Test fun billingAcceptsMultipleUnitsAndRejectsInvalidOrDuplicateRates() {
        val draft = MediaTariffDraft("CNY", listOf(MediaRateDraft(BillingUnit.TOKENS, "70", "1000000", "0"),
            MediaRateDraft(BillingUnit.INPUT_IMAGES, "0.03", "1", "3")))
        assertNull(draft.validationError())
        assertEquals(2, draft.toTariff()!!.rates.size)
        listOf("-1", "NaN", "Infinity", "1e999999999", "").forEach { value ->
            assertNotNull(draft.copy(rates = listOf(MediaRateDraft(amount = value))).validationError())
        }
        assertNotNull(draft.copy(rates = listOf(MediaRateDraft(perUnits = "0"))).validationError())
        assertNotNull(draft.copy(rates = listOf(MediaRateDraft(freeUnits = "-1"))).validationError())
        assertNotNull(draft.copy(rates = draft.rates + draft.rates.first()).validationError())
        assertNotNull(draft.copy(currency = "").validationError())
        assertNull(MediaTariffDraft().toTariff())
    }

    @Test fun hostValidationSupportsProxyPathsAndRequiresCredentialsWhenAuthChanges() {
        val form = ComfyHostDraft(name = "MAIN", address = "http://main:8188/comfy")
        assertNull(validateComfyHost(form, ComfyCredentialInput(), false, null))
        listOf("http://u:p@main:8188", "https://main?token=x", "file:///tmp", "https://main/a/../b", "https://main#fragment").forEach {
            assertEquals(MediaFormError.ADDRESS, validateComfyHost(form.copy(address = it), ComfyCredentialInput(), false, null))
        }
        assertEquals(MediaFormError.NAME, validateComfyHost(form.copy(name = " "), ComfyCredentialInput(), false, null))
        val bearer = form.copy(authKind = ComfyAuthKind.BEARER)
        assertEquals(MediaFormError.CREDENTIAL, validateComfyHost(bearer, ComfyCredentialInput(), false, null))
        assertNull(validateComfyHost(bearer, ComfyCredentialInput(), true, ComfyAuthKind.BEARER))
        assertNotNull(validateComfyHost(bearer, ComfyCredentialInput(), true, ComfyAuthKind.BASIC))
        assertNotNull(validateComfyHost(bearer, ComfyCredentialInput(token = "a\nb"), true, ComfyAuthKind.BEARER))
        assertNull(validateComfyHost(bearer, ComfyCredentialInput(token = "sample"), false, null))
        assertNotNull(validateComfyHost(form.copy(authKind = ComfyAuthKind.BASIC), ComfyCredentialInput(username = "a:b", password = "x"), false, null))
        assertNull(validateComfyHost(form.copy(authKind = ComfyAuthKind.BASIC), ComfyCredentialInput(username = "user"), false, null))
    }

    @Test fun templateLabelsKeepMissingAndReviewDistinct() {
        assertEquals(TemplateStatusLabel(PreflightStatus.PASS, 0), templateStatusLabel(PreflightResult(PreflightStatus.PASS)))
        assertEquals(TemplateStatusLabel(PreflightStatus.MISSING, 2), templateStatusLabel(PreflightResult(PreflightStatus.MISSING, listOf("node", "model"))))
        assertEquals(PreflightStatus.REVIEW, templateStatusLabel(PreflightResult(PreflightStatus.REVIEW, listOf("node"))).status)
    }

    @Test fun unlockRequiresARequestAndExplicitConfirmationAndCanBeCancelled() {
        val locked = TemplateUnlockState()
        assertNull(locked.confirm())
        assertEquals("template", locked.request("template", true).confirm())
        assertNull(locked.request("template", true).cancel().confirm())
        assertNull(locked.request("template", false).confirm())
    }

    @Test fun providerSummaryIncludesConfiguredHostsWithoutTreatingOfflineAsDisabled() {
        val summary = mediaProviderListSummary(listOf(ProviderListItem(enabled = true), ProviderListItem(enabled = false)), 1, 2)
        assertEquals(5, summary.total)
        assertEquals(3, summary.enabled)
        assertEquals(ProviderListSummary(2, 2), mediaProviderListSummary(emptyList(), 0, 2))
    }

    @Test fun preflightTimeIsBoundToTheValidatedDefinitionAndResult() {
        val state = ComfyTemplateState(ComfyTemplate("tpl", JsonObject(emptyMap()), emptyList(), capabilities = capabilities),
            PreflightResult(PreflightStatus.PASS))
        val stamp = mediaPreflightStamp(state, 123456789L)
        assertEquals(123456789L, mediaPreflightTime(state, stamp)!!)
        assertNull(mediaPreflightTime(state.copy(template = state.template.copy(name = "changed")), stamp))
        assertNull(mediaPreflightTime(state.copy(preflight = PreflightResult(PreflightStatus.REVIEW)), stamp))
        assertNull(mediaPreflightTime(state, null))
        assertNull(mediaPreflightTime(state, "corrupt"))
        assertNull(mediaPreflightTime(state, stamp.substringBefore('|') + "|-1"))
    }

    @Test fun diagnosticsPreserveTechnicalArgumentsAndUnknownDetails() {
        assertEquals(listOf("12", "refs.image_0"), mediaDiagnostic("节点 12 动态输入 refs.image_0 需要有效节点连线")!!.arguments)
        assertEquals(listOf("type.ckpt: model.safetensors"), mediaDiagnostic("模型 type.ckpt: model.safetensors")!!.arguments)
        assertNotNull(mediaDiagnostic("无法读取主机节点信息，请检查连接与鉴权后重新预检"))
        assertNull(mediaDiagnostic("Custom node diagnostic"))
        assertFalse(ComfyCredentialInput(token = "fixture").toString().contains("fixture"))
    }

    @Test fun providerCountsClassifyMediaBeforeChatAndIncludeDisabledModels() {
        fun model(id: String, type: String, enabled: Boolean = true) = ModelInfo(id, id, "", enabled, type, providerId = "p")
        val counts = providerMediaCounts(listOf(model("chat", "reasoning"), model("image", "image"),
            model("video", "video", false), model("audio", "audio"), model("embed", "embedding"))).getValue("p")
        assertEquals(ProviderMediaCount(1, 1, 1, 1), counts)
    }
}
