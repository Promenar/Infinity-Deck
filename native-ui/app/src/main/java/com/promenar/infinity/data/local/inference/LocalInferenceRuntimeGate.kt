package com.promenar.infinity.data.local.inference

import com.promenar.infinity.BuildConfig

/**
 * 将构建变体能力与已恢复的用户偏好分离，防止不含原生库的 Release 进入本地加载链。
 */
class LocalInferenceRuntimeGate(
    val isAvailable: Boolean,
) {
    /**
     * 仅在当前构建包含本地推理能力时创建依赖。
     *
     * [factory] 保持惰性，避免无本地原生库的发行变体在构造远程能力时触发本地引擎门禁。
     */
    fun <T> createIfAvailable(factory: () -> T): T? =
        if (isAvailable) factory() else null

    fun startupModelPath(
        localModelsEnabled: Boolean,
        autoLoadEnabled: Boolean,
        persistedModelPath: String?,
    ): String? = persistedModelPath?.takeIf {
        isAvailable && localModelsEnabled && autoLoadEnabled && it.isNotBlank()
    }

    fun requireAvailable() {
        check(isAvailable) { "当前发行版本不包含本地推理能力" }
    }

    companion object {
        fun fromBuildConfig(): LocalInferenceRuntimeGate =
            LocalInferenceRuntimeGate(BuildConfig.LOCAL_INFERENCE_AVAILABLE && android.os.Process.is64Bit())
    }
}
