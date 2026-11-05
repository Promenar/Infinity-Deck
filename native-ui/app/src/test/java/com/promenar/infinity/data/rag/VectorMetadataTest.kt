package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VectorMetadataTest {
    @Test
    fun `向量空间摘要保留类型首字段和原有文档信息`() {
        val original = documentVectorMetadata("file-id", 1, "资料.txt", "content-hash")
        val tagged = withVectorSpaceMetadata(original, "fixture-space")
        assertThat(tagged).startsWith("{\"type\":\"document\"")
        assertThat(vectorSpaceFromMetadata(tagged)).isEqualTo("fixture-space")
        assertThat(documentContentHashFromVectorMetadata(tagged)).isEqualTo("content-hash")
        assertThat(documentTitleFromVectorMetadata(tagged)).isEqualTo("资料.txt")
        assertThat(withVectorSpaceMetadata("type=image", "fixture-space")).isEqualTo("type=image")
        val image = withVectorSpaceMetadata("type=image&mime=image/png", "fixture-space")
        assertThat(image).startsWith("type=image&mime=image/png")
        assertThat(vectorSpaceFromMetadata(image)).isEqualTo("fixture-space")
    }

    @Test
    fun `新metadata安全往返真实文件名`() {
        val metadata = documentVectorMetadata(
            fileUuid = "file-id",
            chunkIndex = 2,
            documentTitle = "用户的 \"发布\" 资料.txt",
        )

        assertThat(metadata).startsWith("{\"type\":\"document\"")
        assertThat(vectorMetadataType(metadata)).isEqualTo("document")
        assertThat(documentTitleFromVectorMetadata(metadata)).isEqualTo("用户的 \"发布\" 资料.txt")
    }

    @Test
    fun `旧metadata保持类型可读并回退无标题`() {
        val legacy = "{\"type\":\"document\",\"fileUuid\":\"legacy-id\",\"chunkIndex\":0}"

        assertThat(vectorMetadataType(legacy)).isEqualTo("document")
        assertThat(documentTitleFromVectorMetadata(legacy)).isNull()
        assertThat(documentReferenceSource(legacy, "fallback-document"))
            .isEqualTo("文档: legacy-i")
    }
}
