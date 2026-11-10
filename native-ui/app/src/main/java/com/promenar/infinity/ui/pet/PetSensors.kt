package com.promenar.infinity.ui.pet

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** 摇晃的力度：轻摇让宠物晕，用力摇晃把被收起的宠物摇回来。 */
internal enum class PetShake { LIGHT, STRONG }

/**
 * 从加速度计读数里识别摇晃和左右倾斜，见 v0.3 增补第 3.2、4 节。纯逻辑，不依赖 Android。
 *
 * 摇晃：去掉重力后的加速度多次超过阈值，且相邻两次方向相反。轻摇要求 1 秒内 3 次往复，
 * 用力摇晃要求更高的阈值和 5 次往复，避免走路或放进口袋时误触发。
 */
internal class PetMotionDetector {
    /** 屏幕横向的倾斜程度，-1 到 1；右侧朝下为正。 */
    var tilt = 0f
        private set

    private val gravity = FloatArray(3)
    private var primed = false
    private val lightPeaks = ArrayDeque<Long>()
    private val strongPeaks = ArrayDeque<Long>()
    private var lightSign = 0
    private var strongSign = 0
    private var quietUntil = 0L

    /**
     * 输入一次读数（米每二次方秒）。[rotation] 为屏幕旋转（`Surface.ROTATION_*`）。
     * 识别到摇晃时返回力度，否则返回 null。
     */
    fun feed(x: Float, y: Float, z: Float, timeMillis: Long, rotation: Int = Surface.ROTATION_0): PetShake? {
        if (!primed) {
            primed = true
            gravity[0] = x
            gravity[1] = y
            gravity[2] = z
        }
        gravity[0] += (x - gravity[0]) * GRAVITY_FILTER
        gravity[1] += (y - gravity[1]) * GRAVITY_FILTER
        gravity[2] += (z - gravity[2]) * GRAVITY_FILTER
        // 设备右侧朝下时，重力在设备横轴上的读数为负。
        val screenX = when (rotation) {
            Surface.ROTATION_90 -> -gravity[1]
            Surface.ROTATION_180 -> -gravity[0]
            Surface.ROTATION_270 -> gravity[1]
            else -> gravity[0]
        }
        tilt = petClamp(-screenX / STANDARD_GRAVITY * TILT_GAIN, -1f, 1f)

        val lx = x - gravity[0]
        val ly = y - gravity[1]
        val lz = z - gravity[2]
        val magnitude = sqrt(lx * lx + ly * ly + lz * lz)
        // 取幅度最大的一轴的方向来判断往复。
        val dominant = if (abs(lx) >= abs(ly) && abs(lx) >= abs(lz)) lx else if (abs(ly) >= abs(lz)) ly else lz
        val direction = if (dominant >= 0f) 1 else -1
        if (timeMillis < quietUntil) return null

        if (magnitude > STRONG_THRESHOLD && direction != strongSign) {
            strongSign = direction
            strongPeaks.addLast(timeMillis)
        }
        if (magnitude > LIGHT_THRESHOLD && direction != lightSign) {
            lightSign = direction
            lightPeaks.addLast(timeMillis)
        }
        while (strongPeaks.isNotEmpty() && timeMillis - strongPeaks.first() > WINDOW_MILLIS) strongPeaks.removeFirst()
        while (lightPeaks.isNotEmpty() && timeMillis - lightPeaks.first() > WINDOW_MILLIS) lightPeaks.removeFirst()
        val result = when {
            strongPeaks.size >= STRONG_PEAKS -> PetShake.STRONG
            lightPeaks.size >= LIGHT_PEAKS -> PetShake.LIGHT
            else -> null
        }
        // 用力摇晃的前几下也满足轻摇的条件：先等一小段时间，看能不能凑够用力摇晃。
        if (result == PetShake.LIGHT && strongPeaks.size >= 2 && timeMillis - lightPeaks.first() < WINDOW_MILLIS / 2) return null
        if (result != null) {
            strongPeaks.clear()
            lightPeaks.clear()
            quietUntil = timeMillis + COOLDOWN_MILLIS
        }
        return result
    }

    private companion object {
        const val STANDARD_GRAVITY = 9.81f
        const val GRAVITY_FILTER = 0.1f
        const val TILT_GAIN = 1.6f
        const val LIGHT_THRESHOLD = 9f
        const val STRONG_THRESHOLD = 16f
        const val LIGHT_PEAKS = 3
        const val STRONG_PEAKS = 5
        const val WINDOW_MILLIS = 1000L
        const val COOLDOWN_MILLIS = 1500L
    }
}

/**
 * 从麦克风音量里识别“对着手机吹气”。纯逻辑：吹气会让麦克风持续接近满幅，
 * 音量连续超过阈值达到 [HOLD_SECONDS] 才算一次，之后有一段冷却时间。
 */
internal class PetBlowLogic {
    private var held = 0f
    private var cooldown = 0f

    /** [level] 为一帧音频的均方根音量（0 到 1），[seconds] 为这一帧的时长。 */
    fun feed(level: Float, seconds: Float): Boolean {
        if (cooldown > 0f) {
            cooldown -= seconds
            held = 0f
            return false
        }
        held = if (level >= THRESHOLD) held + seconds else max(0f, held - seconds * 2f)
        if (held < HOLD_SECONDS) return false
        held = 0f
        cooldown = COOLDOWN_SECONDS
        return true
    }

    private companion object {
        const val THRESHOLD = 0.22f
        const val HOLD_SECONDS = 0.3f
        const val COOLDOWN_SECONDS = 2f
    }
}

internal fun petMicrophoneGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * 监听麦克风音量，识别到吹气时在主线程调用 [onBlow]，直到协程被取消。
 * 只读取音量，不保存也不发送音频。没有权限或麦克风不可用时直接返回。
 */
@SuppressLint("MissingPermission")
internal suspend fun petListenForBlow(context: Context, onBlow: () -> Unit) {
    if (!petMicrophoneGranted(context)) return
    withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return@withContext
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minimum, FRAME_SAMPLES * 4),
            )
        }.getOrNull() ?: return@withContext
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return@withContext
            // 麦克风被其它应用占用等情况下启动会失败：放弃这次监听。
            if (runCatching { recorder.startRecording() }.isFailure) return@withContext
            val buffer = ShortArray(FRAME_SAMPLES)
            val logic = PetBlowLogic()
            while (isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) break
                var sum = 0.0
                for (index in 0 until read) {
                    val sample = buffer[index] / 32768.0
                    sum += sample * sample
                }
                val level = sqrt(sum / read).toFloat()
                if (logic.feed(level, read / SAMPLE_RATE.toFloat())) withContext(Dispatchers.Main) { onBlow() }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}

private const val SAMPLE_RATE = 8000
private const val FRAME_SAMPLES = 400
