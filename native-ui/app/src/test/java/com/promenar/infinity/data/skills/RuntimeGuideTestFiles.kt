package com.promenar.infinity.data.skills

import java.io.File

/** Gradle 与 IDE 的测试工作目录不同，从已有主源码目录定位测试输入。 */
internal fun runtimeGuideMainDirectory(): File = listOf("src/main", "app/src/main", "native-ui/app/src/main")
    .map(::File).first { it.resolve("java/com/promenar/infinity").isDirectory }
