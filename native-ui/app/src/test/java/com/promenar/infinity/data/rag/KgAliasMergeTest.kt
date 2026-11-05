package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class KgAliasMergeTest {
    @Test fun `声明别名合并节点并改写关系端点`() {
        val nodes = listOf(
            ExtractedNode("林会长", "person", """{"description":"人物","aliases":["林青"]}"""),
            ExtractedNode("林青", "person", """{"description":"组织负责人"}"""),
            ExtractedNode("山城", "location"),
        )
        val result = KgAliasMerge.merge(nodes, listOf(ExtractedEdge("林青", "山城", "位于")))
        assertThat(result.nodes.map { it.name }).containsExactly("林会长", "山城").inOrder()
        assertThat(result.nodes.first().metadata).contains("组织负责人")
        assertThat(result.edges.single().source).isEqualTo("林会长")
    }

    @Test fun `相似名称与损坏元数据不触发合并`() {
        val result = KgAliasMerge.merge(listOf(
            ExtractedNode("星河", "organization", "{"),
            ExtractedNode("星河社", "organization"),
        ), emptyList())
        assertThat(result.nodes).hasSize(2)
    }

    @Test fun `不同类型的同名或别名不会合并`() {
        val result = KgAliasMerge.merge(listOf(
            ExtractedNode("林会长", "person", """{"aliases":["林青"]}"""),
            ExtractedNode("林青", "concept"), ExtractedNode("林青", "concept"),
            ExtractedNode("山城", "location"),
        ), listOf(ExtractedEdge("林会长", "山城", "位于"),
            ExtractedEdge("林青", "山城", "位于", sourceType = "concept")))
        assertThat(result.nodes.map { it.name to it.type }).containsExactly(
            "林会长" to "person", "林青" to "concept", "山城" to "location")
        assertThat(result.edges).hasSize(2)
    }

    @Test fun `同名不同类型保留并按类型改写别名端点`() {
        val result = KgAliasMerge.merge(listOf(
            ExtractedNode("星河", "person", """{"aliases":["小星"]}"""),
            ExtractedNode("小星", "person"),
            ExtractedNode("星河", "organization"),
            ExtractedNode("山城", "location"),
        ), listOf(
            ExtractedEdge("小星", "山城", "居住", sourceType = "person"),
            ExtractedEdge("星河", "山城", "设址", sourceType = "organization"),
        ))
        assertThat(result.nodes.map { it.name to it.type }).containsExactly(
            "星河" to "person", "星河" to "organization", "山城" to "location")
        assertThat(result.edges.map { it.source to it.sourceType }).containsExactly(
            "星河" to "person", "星河" to "organization")
    }
}
