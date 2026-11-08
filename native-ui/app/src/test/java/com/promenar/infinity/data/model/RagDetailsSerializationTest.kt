package com.promenar.infinity.data.model

import com.promenar.infinity.data.local.db.entity.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RagDetailsSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `test RagReference serialization with rerankScore and rankChange`() {
        val originalRef = RagReference(
            id = "chunk_123",
            content = "This is a test chunk.",
            source = "test_doc.md",
            score = 0.85f,
            rerankScore = 0.95f,
            rankChange = 2,
            chunkIndex = 1
        )

        val jsonString = json.encodeToString(originalRef)
        assertTrue(jsonString.contains("rerankScore"))
        assertTrue(jsonString.contains("0.95"))
        assertTrue(jsonString.contains("rankChange"))
        assertTrue(jsonString.contains("2"))

        val deserializedRef = json.decodeFromString<RagReference>(jsonString)
        assertEquals(originalRef.id, deserializedRef.id)
        assertEquals(originalRef.content, deserializedRef.content)
        assertEquals(originalRef.score, deserializedRef.score)
        assertEquals(originalRef.rerankScore, deserializedRef.rerankScore)
        assertEquals(originalRef.rankChange, deserializedRef.rankChange)
    }

    @Test
    fun `test KgPath serialization`() {
        val node1 = KgNode(id = "n1", label = "Infinity Deck", type = "Software")
        val node2 = KgNode(id = "n2", label = "Android", type = "Platform")
        val edge = KgEdge(sourceId = "n1", targetId = "n2", relation = "runs_on")

        val path = KgPath(
            queryKeywords = listOf("Infinity Deck", "Platform"),
            nodes = listOf(node1, node2),
            edges = listOf(edge),
            reasoning = "Found software running platform."
        )

        val jsonString = json.encodeToString(path)
        assertTrue(jsonString.contains("queryKeywords"))
        assertTrue(jsonString.contains("Infinity Deck"))
        assertTrue(jsonString.contains("runs_on"))
        assertTrue(jsonString.contains("reasoning"))

        val deserializedPath = json.decodeFromString<KgPath>(jsonString)
        assertEquals(path.queryKeywords, deserializedPath.queryKeywords)
        assertEquals(path.nodes.size, deserializedPath.nodes.size)
        assertEquals(path.edges.size, deserializedPath.edges.size)
        assertEquals(path.edges[0].relation, deserializedPath.edges[0].relation)
        assertEquals(path.reasoning, deserializedPath.reasoning)
    }

    @Test
    fun `test Message serialization with kgPaths`() {
        val path = KgPath(
            queryKeywords = listOf("A"),
            nodes = listOf(KgNode("1", "A", "T")),
            edges = listOf()
        )

        val message = Message(
            id = "msg_1",
            role = MessageRole.ASSISTANT,
            content = "Hello",
            kgPaths = listOf(path)
        )

        val jsonString = json.encodeToString(message)
        assertTrue(jsonString.contains("kgPaths"))

        val deserializedMessage = json.decodeFromString<Message>(jsonString)
        assertEquals(1, deserializedMessage.kgPaths?.size)
        assertEquals("A", deserializedMessage.kgPaths?.get(0)?.queryKeywords?.get(0))
    }

    @Test
    fun `Message entity mapping preserves kgPaths JSON across database reload`() {
        val path = KgPath(
            queryKeywords = listOf("Infinity Deck"),
            nodes = listOf(KgNode("n1", "Infinity Deck", "project")),
            edges = listOf(KgEdge("n1", "n2", "uses")),
            reasoning = "graph path",
        )
        val message = Message(
            id = "msg-room",
            role = MessageRole.ASSISTANT,
            content = "answer",
            kgPaths = listOf(path),
        )

        val entity = message.toEntity("session-1")
        assertTrue(entity.kgPaths!!.contains("Infinity Deck"))
        assertEquals(listOf(path), entity.toDomain().kgPaths)
    }

    @Test
    fun `malformed kgPaths JSON degrades only that field`() {
        val entity = MessageEntity(
            id = "malformed-kg",
            sessionId = "session-1",
            role = "assistant",
            content = "answer remains readable",
            reasoning = "reasoning remains readable",
            kgPaths = "{not-valid-json",
            createdAt = 1L,
        )

        val message = entity.toDomain()

        assertEquals("answer remains readable", message.content)
        assertEquals("reasoning remains readable", message.reasoning)
        assertEquals(null, message.kgPaths)
    }

    @Test
    fun `kgPaths decoder never swallows virtual machine errors`() {
        assertThrows(OutOfMemoryError::class.java) {
            decodeKgPathsOrNull("ignored") { throw OutOfMemoryError("simulated") }
        }
    }
}
