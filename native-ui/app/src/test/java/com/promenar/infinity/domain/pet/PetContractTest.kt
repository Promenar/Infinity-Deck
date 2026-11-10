package com.promenar.infinity.domain.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetContractTest {
    private val manifest = """
        {
          "contract": "0.1", "id": "community.whale-maid", "name": "大肥鱼", "version": "1.0.0",
          "author": "作者名", "license": {"name": "CC BY-NC 4.0", "redistribute": true, "commercial": false},
          "display": {"height_dp": 125, "facing": "right"},
          "clips": {"stand": {"type": "pose", "file": "clips/stand.webp", "anchor": [0.5, 1.0]}},
          "expressions": {}, "map": {"idle": {"clip": "stand"}}
        }
    """.trimIndent()
    private val persona = """
        {
          "name": "大肥鱼", "self": "我", "user": "主人", "personality": "活泼、贪吃、做事认真",
          "style": "短句，口语，偶尔用“～”收尾", "catchphrases": ["嘿嘿"], "avoid": ["评价用户的代码质量"],
          "examples": ["边吃边写，快好了～"], "languages": ["zh-CN"],
          "fallback": {
            "task.generating": ["嚼嚼…在写了", "已经写了 {output.chars} 字"],
            "task.error": ["出错了（{error.kind}），等会儿再试"], "touch.held": ["哇啊！放我下来～"]
          }
        }
    """.trimIndent()

    @Test
    fun `规格清单示例可以解析`() {
        assertEquals("大肥鱼", PetParser.parseManifest(manifest).getOrThrow().name)
    }

    @Test
    fun `规格角色示例可以解析`() {
        assertEquals("主人", PetParser.parsePersona(persona).getOrThrow().user)
    }

    @Test
    fun `未知字段被忽略`() {
        assertTrue(PetParser.parseManifest(manifest.replace("\"contract\"", "\"future\": 1, \"contract\"")).isSuccess)
    }

    @Test
    fun `缺少标识无法解析`() {
        assertTrue(PetParser.parseManifest(manifest.replace("\"id\": \"community.whale-maid\",", "")).isFailure)
    }

    @Test
    fun `缺少片段表无法解析`() {
        val input = manifest.replace(Regex("\"clips\": \\{\"stand\".*?\\}\\},"), "")
        assertTrue(PetParser.parseManifest(input).isFailure)
    }

    @Test
    fun `字段类型错误无法解析`() {
        assertTrue(PetParser.parseManifest(manifest.replace("\"height_dp\": 125", "\"height_dp\": \"bad\"")).isFailure)
    }

    @Test
    fun `只有名字的角色可以解析`() {
        assertEquals(PetPersona("鱼"), PetParser.parsePersona("""{"name":"鱼"}""").getOrThrow())
    }

    @Test
    fun `省略表情和作者的清单可以解析`() {
        val input = manifest.replace("\"expressions\": {},", "").replace("\"author\": \"作者名\",", "")
        assertEquals(PetExpressions(), PetParser.parseManifest(input).getOrThrow().expressions)
    }

    @Test
    fun `头发起点是归一化小数`() {
        val input = manifest.replace("\"anchor\": [0.5, 1.0]", "\"anchor\": [0.5, 1.0], \"rig\": {\"hair_top\": 0.2}")
        assertEquals(0.2, PetParser.parseManifest(input).getOrThrow().clips.getValue("stand").rig!!.hairTop!!, 0.0)
    }
}
