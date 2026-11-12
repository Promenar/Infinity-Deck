package com.promenar.infinity.ui.chat.diff

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.mediagen.comfy.ComfyConfigurationCodec
import com.promenar.infinity.domain.mediagen.ComfyModelSlot
import com.promenar.infinity.domain.mediagen.ComfySlot
import com.promenar.infinity.domain.mediagen.ComfyTemplate
import com.promenar.infinity.domain.mediagen.GenerationMode
import com.promenar.infinity.domain.mediagen.MediaCapabilities
import com.promenar.infinity.domain.mediagen.SlotKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class ComfyTemplatePreviewBuilderTest {
    private val template = ComfyTemplate(
        id = "image", name = "图像模板", hostId = "host",
        workflow = graph("""{"1":{"class_type":"Loader","inputs":{"model":"a.safetensors","prompt":""}},"2":{"class_type":"Save","inputs":{}}}"""),
        slots = listOf(ComfySlot("1", "prompt", SlotKind.PROMPT), ComfySlot("2", "images", SlotKind.OUTPUT)),
        models = listOf(ComfyModelSlot("1", "model")),
        capabilities = MediaCapabilities(setOf(GenerationMode.TEXT)),
    )

    @Test
    fun `新建模板显示节点类型模型和全部能力并保留审批说明`() {
        val preview = ComfyTemplatePreviewBuilder.build(null, template, "工作站", "按用户要求配置")
        assertThat(preview.templateName).isEqualTo("图像模板")
        assertThat(preview.hostName).isEqualTo("工作站")
        assertThat(preview.isNew).isTrue()
        assertThat(preview.reason).isEqualTo("按用户要求配置")
        assertThat(preview.addedNodeTypes).containsExactly("Loader", "Save").inOrder()
        assertThat(preview.removedNodeTypes).isEmpty()
        assertThat(preview.modelChanges).containsExactly(FieldChange("1.model", null, "a.safetensors"))
        assertThat(preview.capabilityChanges).contains(FieldChange("modes", null, "TEXT"))
        assertThat(preview.capabilityChanges.all { it.oldValue == null }).isTrue()
    }

    @Test
    fun `同 ID 更新分别列出新增移除节点和显式模型输入变化`() {
        val before = template.copy(models = template.models + ComfyModelSlot("1", "optional"))
        val requested = template.copy(
            workflow = graph("""{"1":{"class_type":"NewLoader","inputs":{"model":"b.safetensors","weights":"c.bin","prompt":""}},"2":{"class_type":"Save","inputs":{}},"3":{"class_type":"Save","inputs":{}}}"""),
            models = listOf(ComfyModelSlot("1", "weights"), ComfyModelSlot("1", "model")),
        )
        val preview = ComfyTemplatePreviewBuilder.build(before, requested, "工作站", "切换模型")
        assertThat(preview.isNew).isFalse()
        assertThat(preview.addedNodeTypes).containsExactly("NewLoader")
        assertThat(preview.removedNodeTypes).containsExactly("Loader")
        assertThat(preview.modelChanges).containsExactly(
            FieldChange("1.model", "a.safetensors", "b.safetensors"),
            FieldChange("1.optional", "未设置", null),
            FieldChange("1.weights", null, "c.bin"),
        ).inOrder()
        assertThat(preview.capabilityChanges).isEmpty()
    }

    @Test
    fun `能力差异使用槽位推导容量且保留帧数和参考顺序`() {
        val requested = template.copy(
            slots = template.slots + ComfySlot("1", "ref_0", SlotKind.REFERENCE_IMAGES, 2),
            frameBase = 5, frameStep = 4, maxFrames = 81,
            capabilities = MediaCapabilities(setOf(GenerationMode.TEXT, GenerationMode.REFERENCE), maxImages = 999,
                referenceOrder = listOf("角色", "场景")),
        )
        val rows = ComfyTemplatePreviewBuilder.build(template, requested, "工作站", "增加参考输入").capabilityChanges
        assertThat(rows).containsAtLeast(
            FieldChange("modes", "TEXT", "REFERENCE、TEXT"),
            FieldChange("maxImages", "0", "2"),
            FieldChange("referenceOrder", null, "角色、场景"),
            FieldChange("frameBase", "1", "5"),
            FieldChange("frameStep", "1", "4"),
            FieldChange("maxFrames", null, "81"),
        )
        assertThat(rows.any { it.key == "slots" }).isTrue()
    }

    @Test
    fun `未变化字段和声明顺序不会制造差异`() {
        val requested = template.copy(locked = true, capabilities = template.capabilities!!.copy(maxImages = 99),
            workflow = JsonObject(template.workflow.entries.reversed().associate { it.key to it.value }))
        val preview = ComfyTemplatePreviewBuilder.build(template, requested, "工作站", "核对")
        assertThat(preview.addedNodeTypes).isEmpty()
        assertThat(preview.removedNodeTypes).isEmpty()
        assertThat(preview.modelChanges).isEmpty()
        assertThat(preview.capabilityChanges).isEmpty()
    }

    @Test
    fun `模型只比较显式声明的输入并显示新增移除声明`() {
        val before = template.copy(models = emptyList())
        val added = ComfyTemplatePreviewBuilder.build(before, template, "工作站", "声明模型")
        assertThat(added.modelChanges).containsExactly(FieldChange("1.model", null, "a.safetensors"))
        val removed = ComfyTemplatePreviewBuilder.build(template, before, "工作站", "移除声明")
        assertThat(removed.modelChanges).containsExactly(FieldChange("1.model", "a.safetensors", null))
    }

    @Test
    fun `加载器按稳定 ID 读取原模板并沿用审批卡说明`() = runTest {
        val requested = template.copy(name = "新名称")
        val source = object : ComfyTemplatePreviewSources {
            override suspend fun template(id: String) = template.takeIf { it.id == id }
            override suspend fun hostName(id: String) = "工作站".takeIf { id == "host" }
        }
        val preview = ComfyTemplatePreviewLoader(source).load(arguments(requested), "会话工具调用说明")!!
        assertThat(preview.isNew).isFalse()
        assertThat(preview.reason).isEqualTo("会话工具调用说明")
        assertThat(preview.capabilityChanges).containsExactly(FieldChange("name", "图像模板", "新名称"))
    }

    @Test
    fun `加载器对无效参数和缺失主机返回空预览`() = runTest {
        val source = object : ComfyTemplatePreviewSources {
            override suspend fun template(id: String): ComfyTemplate? = null
            override suspend fun hostName(id: String): String? = null
        }
        val loader = ComfyTemplatePreviewLoader(source)
        assertThat(loader.load(buildJsonObject { put("template", "invalid") }, "说明")).isNull()
        assertThat(loader.load(buildJsonObject { put("template", JsonObject(emptyMap())) }, "说明")).isNull()
        assertThat(loader.load(arguments(template), "说明")).isNull()
    }

    private fun arguments(value: ComfyTemplate) = buildJsonObject { put("template", ComfyConfigurationCodec.encodeTemplate(value)) }
    private fun graph(value: String) = Json.parseToJsonElement(value).jsonObject
}
