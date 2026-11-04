package com.promenar.infinity.domain.media

import kotlinx.serialization.Serializable

/** 生图入参画板的三种画法：在原图上标注、涂抹重绘选区、空白画布手绘草图。 */
@Serializable
enum class ImageAnnotationKind { MARKUP, MASK, SKETCH }

/**
 * 一张画板成果在会话工作区里的落点，随输入框里的预览图流转（写在预览 PNG 的私有块中），发送后存入用户消息。
 *
 * - MARKUP：[sourcePath] 是原图，[layerPath] 是与原图同像素尺寸的透明底标注 PNG，对应生图 `markup` 参数。
 * - MASK：[sourcePath] 是原图，[layerPath] 是同尺寸蒙版 PNG（涂抹处透明、其余不透明），对应 `mask` 参数。
 * - SKETCH：没有原图，[layerPath] 是白底草图 PNG，作为 `images` 参考图，[width]×[height] 即建议的 `size`。
 */
@Serializable
data class ImageAnnotation(
    val kind: ImageAnnotationKind,
    val width: Int,
    val height: Int,
    val layerPath: String,
    val sourcePath: String? = null,
    /** 画板笔画的序列化数据，只供再次编辑时恢复，数据层原样保存、不解读。 */
    val editorState: String? = null,
)

/** 画板导出结果；由数据层写入工作区并生成输入框预览图。 */
class ImageAnnotationExport(
    val kind: ImageAnnotationKind,
    val width: Int,
    val height: Int,
    /** 按 [width]×[height] 原始像素重绘的图层 PNG。 */
    val layerPng: ByteArray,
    /** 给对话模型看的合成预览 PNG（原图＋标注或选区；草图即草图本身），长边不超过 1568 像素。 */
    val previewPng: ByteArray,
    val source: ImageAnnotationSource,
    val editorState: String?,
)

sealed interface ImageAnnotationSource {
    /** 原图已在当前会话工作区，图层尺寸与之相同。 */
    data class Workspace(val path: String) : ImageAnnotationSource

    /** 输入框里尚未入工作区的图片，画板已按导出尺寸解码重编码为 PNG。 */
    class Bytes(val png: ByteArray) : ImageAnnotationSource

    /** 手绘草图没有原图。 */
    data object None : ImageAnnotationSource
}
