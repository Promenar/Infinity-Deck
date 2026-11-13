package com.promenar.infinity.recording

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RecordingHotwordsTest {
    private fun word(text: String, spoken: String = "") = RecordingHotword(text, text, spoken)
    private val vocabulary = "张永模型中文夹英文".toSet()

    @Test fun recognizerSeparatesCjkAndPreservesLatinFragments() {
        assertEquals(listOf("张 永", "中 文 Infinity Deck 2 模 型", "Zipformer"), recordingRecognizerHotwords(listOf(
            word("张永"), word("中文Infinity Deck 2模型"), word("Zipformer", "Zipformer"),
        ), vocabulary))
    }

    @Test fun recognizerCleansSeparatorsUsesSpokenAndExcludesUnknownHan() {
        assertEquals(listOf("张 永 Infinity Deck", "Lama CPP"), recordingRecognizerHotwords(listOf(
            word("张永/Infinity\nDeck"), word("llama.cpp", "Lama CPP"), word("蔺永"),
        ), vocabulary))
        assertEquals(listOf('蔺'), unknownRecordingCharacters("蔺永蔺", vocabulary))
    }

    @Test fun tokenCharactersAcceptPlainAndSentencePiecePrefix() {
        val tokens = recordingTokenCharacters(sequenceOf("张 1", "▁永 2", "▁ABC 3", "<blk> 0", ""))
        assertEquals(setOf('张', '永'), tokens)
    }

    @Test fun replacementHandlesCaseWhitespaceAndLatinBoundaries() {
        val replace = RecordingHotwordReplacer(listOf(word("llama.cpp", "Lama CPP"), word("GGUF", "G G U F")))
        assertEquals("llama.cpp llama.cpp llama.cpp，GGUF", replace.replace("Lama CPP lama  cpp LAMACPP，g g u f"))
        assertEquals("xLamaCPP LamaCPP2 myGGUF", replace.replace("xLamaCPP LamaCPP2 myGGUF"))
        assertEquals("用llama.cpp和GGUF", replace.replace("用LamaCPP和G G U F"))
    }

    @Test fun replacementPrefersLongestAndDoesNotCascade() {
        val replace = RecordingHotwordReplacer(listOf(word("Deck", "Infinity Deck"), word("X", "Deck"), word("Y", "Infinity")))
        assertEquals("Deck X Y", replace.replace("Infinity Deck Deck Infinity"))
        assertEquals("$1\\path", RecordingHotwordReplacer(listOf(word("$1\\path", "Lama"))).replace("lama"))
    }

    @Test fun importCountsBothEqualsBlankDuplicateInvalidAndLimit() {
        val (entries, result) = RecordingHotwordPolicy.import("\n张永\nllama.cpp=Lama CPP\nGGUF＝G G U F\nLLAMA.CPP\n=读法\na=b=c\n${"长".repeat(51)}", emptyList())
        assertEquals(RecordingHotwordImport(3, 1, 3), result)
        assertEquals(result, RecordingHotwordPolicy.previewImport(
            "张永\nllama.cpp=Lama CPP\nGGUF＝G G U F\nLLAMA.CPP\n=读法\na=b=c\n${"长".repeat(51)}", emptyList(),
        ))
        assertEquals("G G U F", entries.last().spoken)
        val full = List(1000) { word("word$it") }
        assertEquals(RecordingHotwordImport(0, 1, 1), RecordingHotwordPolicy.import("word1\nnew", full).second)
    }

    @Test fun validationTrimsRejectsDuplicatesAndOverlongFields() {
        assertEquals("张永", RecordingHotwordPolicy.normalize(word(" 张永 ")).text)
        for (entries in listOf(listOf(word("One"), word("one")), listOf(word(" ")), listOf(word("x", "a".repeat(51))))) {
            assertThrows(IllegalArgumentException::class.java) { RecordingHotwordPolicy.validate(entries) }
        }
    }

    @Test fun effectiveWordsRespectEnabledLibraryAndSessionPreference() {
        val session = RecordingSession("id", 1, "标题", hotwords = listOf("张永"))
        val library = listOf(word("张永", "张勇"), word("GGUF"), word("disabled").copy(enabled = false))
        assertEquals(listOf("张永", "GGUF"), effectiveRecordingHotwords(session, library).map { it.text })
        assertEquals("张勇", effectiveRecordingHotwords(session, library).first().spoken)
        assertEquals(listOf("张永"), effectiveRecordingHotwords(session.copy(useHotwordLibrary = false), library).map { it.text })
        assertEquals(400, recordingHotwordGlossary(List(20) { word("词".repeat(50)) }).length)
    }

    @Test fun transcriptHighlightsLongestWrittenTermsWithoutPartialLatinWords() {
        val pattern = requireNotNull(recordingHotwordPattern(listOf(word("Deck"), word("Infinity Deck"), word("张永"), word("llama.cpp", "Lama CPP"))))
        val text = "用INFINITY Deck和张永开会，提到llama.cpp、Lama CPP、Deck2和myDeck。"
        assertEquals(listOf("INFINITY Deck", "张永", "llama.cpp"), pattern.findAll(text).map { it.value }.toList())
        assertNull(recordingHotwordPattern(emptyList()))
    }

    @Test fun oldDefaultGlossaryStaysEmptyAndCustomGlossaryMigrates() {
        fun decode(glossary: String) = decodeRecordingSession("""{"id":"id","createdAt":1,"title":"标题","glossary":${Json.encodeToString(glossary)}}""")
        assertTrue(decode("Infinity Deck、Zipformer、GGUF、llama.cpp").hotwords.isEmpty())
        val migrated = decode(" 张永、Zipformer,GGUF，llama.cpp\nzipformer ")
        assertEquals(listOf("张永", "Zipformer", "GGUF", "llama.cpp"), migrated.hotwords)
        assertTrue(migrated.useHotwordLibrary)
        assertFalse(Json.encodeToString(migrated).contains("glossary"))
        val explicit = decodeRecordingSession("""{"id":"id","createdAt":1,"title":"标题","glossary":"旧词","hotwords":["新词"],"useHotwordLibrary":false}""")
        assertEquals(listOf("新词"), explicit.hotwords)
        assertFalse(explicit.useHotwordLibrary)
    }

    @Test fun previewAndCommittedSegmentBothUseWrittenForm() {
        val fake = object : RecordingDecoder {
            override fun accept(pcm: ShortArray) = RecordingRecognition("lama CPP", false)
            override fun finish() = "LamaCPP"
            override fun reset() = Unit
        }
        val decoder = RecordingReplacingDecoder(fake, listOf(word("llama.cpp", "Lama CPP")))
        val segmentation = RecordingSegmentation(0)
        assertEquals("llama.cpp", segmentation.accept(ShortArray(640), decoder.accept(ShortArray(640)).text, false).frame.partial)
        assertEquals("llama.cpp", segmentation.finish(decoder.finish()).segment?.text)
    }
}
