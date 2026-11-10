package com.promenar.infinity.domain.pet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetValidatorTest {
    private val pose = PetClip("pose", "clips/a.png", listOf(0.5, 1.0))
    private val manifest = PetManifest(
        contract = "0.2", id = "id", name = "宠物", version = "1",
        license = PetLicense("许可", true, false), display = PetDisplay(125, "right"),
        clips = mapOf("a" to pose), map = mapOf("idle" to PetReaction(clip = "a")),
    )
    private val files = listOf(
        PetFile("manifest.json", 1),
        PetFile("thumbnail.webp", 1, 100, 100),
        PetFile("clips/a.png", 1, 100, 100),
    )

    private fun codes(
        m: PetManifest = manifest,
        p: PetPersona? = null,
        f: List<PetFile> = files,
        l: PetLimits = PetLimits(),
    ): Set<PetIssueCode> = PetPackageValidator(l).validate(m, p, f).map { it.code }.toSet()

    @Test
    fun `合法包没有问题`() {
        assertTrue(codes().isEmpty())
    }

    @Test
    fun `不安全路径被拒绝`() {
        assertTrue(PetIssueCode.UNSAFE_PATH in codes(f = files + PetFile("../a.png", 1, 1, 1)))
    }

    @Test
    fun `重复路径被拒绝`() {
        assertTrue(PetIssueCode.DUPLICATE_PATH in codes(f = files + files[0]))
    }

    @Test
    fun `包大小超限`() {
        assertTrue(PetIssueCode.PACKAGE_SIZE in codes(l = PetLimits(maxPackageBytes = 2)))
    }

    @Test
    fun `文件数量超限`() {
        assertTrue(PetIssueCode.FILE_COUNT in codes(l = PetLimits(maxFiles = 2)))
    }

    @Test
    fun `扩展名不受支持`() {
        assertTrue(PetIssueCode.UNSUPPORTED_EXTENSION in codes(f = files + PetFile("a.exe", 1)))
    }

    @Test
    fun `文件大小为负`() {
        assertTrue(PetIssueCode.INVALID_FILE_SIZE in codes(f = files + PetFile("a.txt", -1)))
    }

    @Test
    fun `缺少文件`() {
        assertTrue(PetIssueCode.MISSING_FILE in codes(f = files.drop(1)))
    }

    @Test
    fun `缺少媒体元数据`() {
        assertTrue(PetIssueCode.MISSING_MEDIA_METADATA in codes(f = files + PetFile("b.png", 1)))
    }

    @Test
    fun `图片尺寸超限`() {
        assertTrue(PetIssueCode.IMAGE_SIZE in codes(f = files + PetFile("b.png", 1, 4097, 1)))
    }

    @Test
    fun `视频尺寸超限`() {
        assertTrue(PetIssueCode.VIDEO_SIZE in codes(f = files + PetFile("b.mp4", 1, 1025, 1, 1, "h264")))
    }

    @Test
    fun `视频时长超限`() {
        assertTrue(PetIssueCode.VIDEO_DURATION in codes(f = files + PetFile("b.mp4", 1, 1, 1, 15001, "h264")))
    }

    @Test
    fun `视频编码不受支持`() {
        assertTrue(PetIssueCode.VIDEO_CODEC in codes(f = files + PetFile("b.mp4", 1, 1, 1, 1, "vp9")))
    }

    @Test
    fun `版本格式错误`() {
        assertTrue(PetIssueCode.INVALID_CONTRACT in codes(m = manifest.copy(contract = "0.x")))
    }

    @Test
    fun `主版本过高`() {
        assertTrue(PetIssueCode.UNSUPPORTED_CONTRACT in codes(m = manifest.copy(contract = "1.0")))
    }

    @Test
    fun `字段取值错误`() {
        assertTrue(PetIssueCode.INVALID_FIELD in codes(m = manifest.copy(display = PetDisplay(79, "right"))))
    }

    @Test
    fun `缺少空闲映射`() {
        assertTrue(PetIssueCode.MISSING_IDLE in codes(m = manifest.copy(map = emptyMap())))
    }

    @Test
    fun `引用不存在的片段`() {
        assertTrue(PetIssueCode.MISSING_CLIP in codes(m = manifest.copy(map = mapOf("idle" to PetReaction(clip = "x")))))
    }

    @Test
    fun `视频替补为视频`() {
        assertTrue(PetIssueCode.INVALID_VIDEO_FALLBACK in codes(m = manifest.copy(
            clips = mapOf("a" to pose.copy(type = "video", alpha = "side_by_side", loop = true, fallback = "a")),
        )))
    }

    @Test
    fun `角色文本超长`() {
        assertTrue(PetIssueCode.PERSONA_LENGTH in codes(p = PetPersona("鱼", personality = "好".repeat(201))))
    }

    @Test
    fun `角色条数超限`() {
        assertTrue(PetIssueCode.PERSONA_COUNT in codes(p = PetPersona("鱼", catchphrases = List(11) { "好" })))
    }

    @Test
    fun `高次版本被接受`() {
        assertTrue(PetIssueCode.UNSUPPORTED_CONTRACT !in codes(m = manifest.copy(contract = "0.99")))
    }

    @Test
    fun `引用不存在的表情`() {
        assertTrue(PetIssueCode.INVALID_FIELD in codes(m = manifest.copy(
            map = mapOf("idle" to PetReaction(expression = "happy")),
        )))
    }

    @Test
    fun `表情集缺少基础片段`() {
        assertTrue(PetIssueCode.INVALID_FIELD in codes(m = manifest.copy(
            expressions = PetExpressions(face = PetFace(listOf(0.5, 0.5), listOf(0.2, 0.2)),
                set = mapOf("happy" to "clips/a.png")),
        )))
    }

    @Test
    fun `表情集缺少面部区域`() {
        assertTrue(PetIssueCode.INVALID_FIELD in codes(m = manifest.copy(
            expressions = PetExpressions(baseClip = "a", set = mapOf("happy" to "clips/a.png")),
        )))
    }

    @Test
    fun `头发起点超出范围`() {
        assertTrue(PetIssueCode.INVALID_FIELD in codes(m = manifest.copy(
            clips = mapOf("a" to pose.copy(rig = PetRig(hairTop = 1.1))),
        )))
    }

    @Test
    fun `各种路径穿越写法均被拒绝`() {
        listOf("../a.png", "/a.png", "a\\b.png", "C:/a.png", "a//b.png").forEach { path ->
            assertTrue(path, PetIssueCode.UNSAFE_PATH in codes(f = files + PetFile(path, 1, 1, 1)))
        }
    }

    @Test
    fun `一次汇总多个问题`() {
        val result = codes(m = manifest.copy(contract = "1.0", map = emptyMap()))
        assertTrue(PetIssueCode.UNSUPPORTED_CONTRACT in result)
        assertTrue(PetIssueCode.MISSING_IDLE in result)
    }
}
