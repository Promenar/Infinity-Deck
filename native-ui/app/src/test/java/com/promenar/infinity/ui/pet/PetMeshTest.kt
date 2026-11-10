package com.promenar.infinity.ui.pet

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.pet.InstalledPet
import com.promenar.infinity.domain.pet.PetClip
import com.promenar.infinity.domain.pet.PetDisplay
import com.promenar.infinity.domain.pet.PetLicense
import com.promenar.infinity.domain.pet.PetManifest
import com.promenar.infinity.domain.pet.PetReaction
import java.io.File
import org.junit.Test

class PetMeshTest {
    private val n = PET_MESH_CELLS

    private fun deform(flip: Boolean = false, pop: Float = 1f, motion: PetMotion = PetMotion()): FloatArray {
        val out = FloatArray(PET_MESH_VERTICES * 2)
        deformPetMesh(
            out = out, width = 100f, height = 200f, anchorU = 0.5f, anchorV = 1f, rig = null, motion = motion,
            rigState = null, baseX = 300f, baseY = 600f, pop = pop, rotation = 0f, flip = flip, dpToPx = 2f,
        )
        return out
    }

    private fun vertex(out: FloatArray, i: Int, j: Int) = out[(j * (n + 1) + i) * 2] to out[(j * (n + 1) + i) * 2 + 1]

    @Test
    fun `没有运动时网格是以接触点为基准的矩形`() {
        val out = deform()

        assertThat(vertex(out, 0, 0)).isEqualTo(250f to 400f)
        assertThat(vertex(out, n, 0)).isEqualTo(350f to 400f)
        assertThat(vertex(out, n / 2, n)).isEqualTo(300f to 600f)
    }

    @Test
    fun `翻转时左右顶点互换`() {
        val out = deform(flip = true)

        assertThat(vertex(out, 0, 0)).isEqualTo(350f to 400f)
        assertThat(vertex(out, n, 0)).isEqualTo(250f to 400f)
    }

    @Test
    fun `落地回弹压低高度而接触点不动`() {
        val out = deform(pop = 0.8f)

        assertThat(vertex(out, n / 2, n)).isEqualTo(300f to 600f)
        assertThat(vertex(out, n / 2, 0).second).isWithin(0.01f).of(440f)
    }

    @Test
    fun `跳跃量按屏幕密度换算成像素`() {
        val motion = PetMotion()
        motion.hopY = -10f

        val out = deform(motion = motion)

        assertThat(vertex(out, n / 2, n)).isEqualTo(300f to 580f)
    }

    @Test
    fun `索引覆盖全部格子且不越界`() {
        val indices = petMeshIndices()

        assertThat(indices.size).isEqualTo(n * n * 6)
        assertThat(indices.max().toInt()).isEqualTo(PET_MESH_VERTICES - 1)
        assertThat(indices.min().toInt()).isEqualTo(0)
    }

    @Test
    fun `纹理坐标铺满图集中的一格`() {
        val out = FloatArray(PET_MESH_VERTICES * 2)

        petMeshTextureCoordinates(out, left = 100f, top = 50f, cellWidth = 40f, cellHeight = 80f)

        assertThat(vertex(out, 0, 0)).isEqualTo(100f to 50f)
        assertThat(vertex(out, n, n)).isEqualTo(140f to 130f)
    }

    @Test
    fun `运动预设 none 不产生位移`() {
        val motion = PetMotion()
        motion.hopY = 3f

        petMotion("none", 1.3f, motion)

        assertThat(motion.hopY).isEqualTo(0f)
        assertThat(motion.breathAmplitude).isEqualTo(0f)
    }

    @Test
    fun `弹簧向目标收敛`() {
        val spring = PetSpring(170f, 15f)

        repeat(240) { spring.step(1f, 1f / 60f) }

        assertThat(spring.x).isWithin(0.01f).of(1f)
    }

    private fun pet(id: String) = InstalledPet(
        id = id,
        manifest = PetManifest(
            contract = "0.2", id = id, name = id, version = "1", license = PetLicense("CC0", true, true),
            display = PetDisplay(130, "right"),
            clips = mapOf("stand" to PetClip(type = "pose", file = "a.webp", anchor = listOf(0.5, 1.0))),
            map = mapOf("idle" to PetReaction(clip = "stand")),
        ),
        persona = null,
        directory = File(id),
    )

    @Test
    fun `按模型关联选择宠物并在未关联时使用默认宠物`() {
        val pets = listOf(pet("a"), pet("b"), pet("c"))

        assertThat(resolveActivePet(pets, "b", mapOf("m1" to "c"), "m1")?.id).isEqualTo("c")
        assertThat(resolveActivePet(pets, "b", mapOf("m1" to "c"), "m2")?.id).isEqualTo("b")
        assertThat(resolveActivePet(pets, "b", mapOf("m1" to "missing"), "m1")?.id).isEqualTo("b")
        assertThat(resolveActivePet(pets, null, emptyMap(), null)?.id).isEqualTo("a")
        assertThat(resolveActivePet(emptyList(), "b", emptyMap(), "m1")).isNull()
    }

    @Test
    fun `台词数据只包含有值的元信息`() {
        val data = petLineData(
            sessionTitle = "重构文件索引", modelName = null, outputChars = 0, startedAt = 1_000, question = " ", now = 13_500,
        )

        assertThat(data).containsExactly("session.title", "重构文件索引", "elapsed.s", "12")
    }
}
