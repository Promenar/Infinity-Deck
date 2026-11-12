package com.promenar.infinity.data.mediagen.registry

import com.promenar.infinity.data.model.*
import com.promenar.infinity.domain.mediagen.*
import org.junit.Assert.*
import org.junit.Test

class MediaModelSettingsTest {
    private fun model(remote: String) = ModelInfo(remote, "provider::$remote", "", true, providerId = "provider", remoteModelId = remote)
    @Test fun presetsAndStableChannelIdentity() {
        val m = model("doubao-seedance-2-0-260128")
        assertEquals(9, m.effectiveMediaSettings()!!.capabilities.maxImages)
        assertTrue("4k" in m.effectiveMediaSettings()!!.capabilities.resolutions)
        assertFalse("4k" in mediaModelPreset("doubao-seedance-2-0-fast-user-snapshot")!!.capabilities.resolutions)
        assertTrue("1080p" in mediaModelPreset("doubao-seedance-2-5-user-snapshot")!!.capabilities.resolutions)
        assertEquals(PromptFormat.SEEDANCE, m.toMediaChannel()!!.capabilities.promptFormat)
        assertEquals(m.toMediaChannel()!!.id, m.copy(name = "新名称").toMediaChannel()!!.id)
        assertNull(m.copy(enabled = false).toMediaChannel())
        assertNull(model("minimax-m3").toMediaChannel())
        assertEquals(MediaGroup.AUDIO, model("speech-2.8-hd").toMediaChannel()!!.group)
        assertEquals(1, model("S2V-01").effectiveMediaSettings()!!.capabilities.maxImages)
    }
    @Test fun settingsRoundTripAndReferenceEdits() {
        val m = model("MiniMax-H3")
        val settings = m.effectiveMediaSettings()!!.withCharacterReferences(CharacterReferenceMode.COMPOSITE, listOf("正面", "面部"))
        val decoded = MediaModelSettingsCodec.decode(MediaModelSettingsCodec.encode(settings))!!
        assertEquals(settings, decoded)
        assertEquals(CharacterReferenceMode.COMPOSITE, m.copy(mediaSettings = decoded).toMediaChannel()!!.capabilities.characterReferenceMode)
        assertEquals(listOf("正面", "面部"), decoded.withCharacterReferences(CharacterReferenceMode.SEPARATE, listOf("正面", "面部")).capabilities.referenceOrder)
        assertThrows(IllegalArgumentException::class.java) { settings.withCharacterReferences(CharacterReferenceMode.SEPARATE, listOf("正面", "正面")) }
        assertNull(MediaModelSettingsCodec.decode("broken"))
    }
}
