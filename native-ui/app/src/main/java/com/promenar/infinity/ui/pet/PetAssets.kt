package com.promenar.infinity.ui.pet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import com.promenar.infinity.data.pet.InstalledPet
import com.promenar.infinity.domain.pet.PetFace
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 一个宠物包的片段位图缓存。位图在 IO 线程按需解码，解码完成前 [texture] 返回 null。
 *
 * 表情纹理是“底图 + 脸部椭圆内替换”的合成结果（规格第 3.2 节），以 `表情名` 为键单独缓存。
 */
internal class PetAssets(private val pet: InstalledPet, private val scope: CoroutineScope) {
    class Texture(val bitmap: Bitmap) {
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    private val textures = ConcurrentHashMap<String, Texture>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var released = false

    /** 片段 [clip] 的纹理；未解码时发起解码并返回 null。 */
    fun texture(clip: String): Texture? {
        textures[clip]?.let { return it }
        val file = pet.manifest.clips[clip]?.file ?: return null
        load(clip) { decode(file) }
        return null
    }

    /** 表情 [expression] 合成到表情底图后的纹理；素材缺失或未就绪时返回 null。 */
    fun expressionTexture(expression: String): Texture? {
        val key = EXPRESSION_PREFIX + expression
        textures[key]?.let { return it }
        val expressions = pet.manifest.expressions
        val baseClip = expressions.baseClip ?: return null
        val face = expressions.face ?: return null
        val file = expressions.set[expression] ?: return null
        val baseFile = pet.manifest.clips[baseClip]?.file ?: return null
        load(key) {
            val base = decode(baseFile) ?: return@load null
            val overlay = decode(file) ?: return@load null
            composeExpression(base, overlay, face)
        }
        return null
    }

    fun release() {
        released = true
        textures.clear()
    }

    private fun load(key: String, produce: () -> Bitmap?) {
        if (released || !pending.add(key)) return
        scope.launch(Dispatchers.IO) {
            val bitmap = runCatching(produce).getOrNull()
            if (bitmap != null && !released) textures[key] = Texture(bitmap)
            // 解码失败的键留在 pending 里，不反复重试。
            if (bitmap != null) pending.remove(key)
        }
    }

    private fun decode(path: String): Bitmap? {
        val file = File(pet.directory, path)
        if (!file.isFile) return null
        return BitmapFactory.decodeFile(file.path)
    }

    private fun composeExpression(base: Bitmap, overlay: Bitmap, face: PetFace): Bitmap? {
        if (face.center.size != 2 || face.radius.size != 2) return null
        val result = base.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val canvas = Canvas(result)
        val width = result.width.toFloat()
        val height = result.height.toFloat()
        val layer = canvas.saveLayer(0f, 0f, width, height, null)
        val scale = Matrix().apply { setScale(width / overlay.width, height / overlay.height) }
        canvas.drawBitmap(overlay, scale, Paint(Paint.FILTER_BITMAP_FLAG))
        // 只保留脸部椭圆内的像素，边缘羽化。
        val gradient = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
        gradient.setLocalMatrix(
            Matrix().apply {
                setScale(face.radius[0].toFloat() * width, face.radius[1].toFloat() * height)
                postTranslate(face.center[0].toFloat() * width, face.center[1].toFloat() * height)
            },
        )
        val mask = Paint(Paint.ANTI_ALIAS_FLAG)
        mask.shader = gradient
        mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawRect(0f, 0f, width, height, mask)
        canvas.restoreToCount(layer)
        return result
    }

    private companion object {
        const val EXPRESSION_PREFIX = "\u0000expression:"
    }
}
