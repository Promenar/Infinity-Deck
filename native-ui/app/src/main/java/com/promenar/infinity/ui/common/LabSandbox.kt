package com.promenar.infinity.ui.common

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 视觉实验室的沙箱标记：实验室宿主提供 true。组件内部绕过参数与注入依赖、直接读取真实数据的位置
 * （例如按会话读工作区文件）在沙箱内一律返回空结果，保证实验室只渲染脚本数据。
 */
val LocalLabSandbox = staticCompositionLocalOf { false }
