import com.android.compose.screenshot.tasks.PreviewScreenshotUpdateTask
import com.android.compose.screenshot.tasks.PreviewScreenshotValidationTask
import com.promenar.infinity.buildlogic.BuildTailnetBinary
import com.promenar.infinity.buildlogic.BuildLinuxRuntime
import com.promenar.infinity.buildlogic.FetchVerifiedFile
import com.promenar.infinity.buildlogic.GenerateLauncherIcons
import com.promenar.infinity.buildlogic.PackagedPythonRelease
import com.promenar.infinity.buildlogic.PackagedPythonWheels
import com.promenar.infinity.buildlogic.PreparePackagedPython
import com.promenar.infinity.buildlogic.PreparePackagedSite
import com.promenar.infinity.buildlogic.PrepareLinuxRuntimeAssets
import com.promenar.infinity.buildlogic.LinuxRuntimeInputs
import groovy.json.JsonSlurper
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class GenerateCurrentRoomSchemaContracts : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val schemas = schemaFiles.files.map { file ->
            (JsonSlurper().parseText(file.readText()) as Map<*, *>).objectMap("database")
        }.sortedBy { (it.requiredValue("version") as Number).toInt() }
        require(schemas.map { (it.requiredValue("version") as Number).toInt() } == (18..33).toList()) {
            "完整数据库校验必须包含 v18 至 v33 的导出合同"
        }
        val source = buildString {
            appendLine("package com.promenar.infinity.data.local.db")
            appendLine()
            appendLine("// 由导出的 Room schema 构建生成，禁止手工维护字段白名单。")
            appendLine("internal object GeneratedCurrentRoomSchemas {")
            appendLine("    val schemas: Map<Int, ExportedRoomSchema> by lazy {")
            appendLine("        mapOf(${schemas.joinToString { "${it["version"]} to schema${it["version"]}()" }})")
            appendLine("    }")
            schemas.forEach { schema ->
                val version = (schema.requiredValue("version") as Number).toInt()
                val tables = schema.objectList("entities")
                appendLine("    private fun schema$version() = ExportedRoomSchema(")
                appendLine("        ${quote(schema.requiredValue("identityHash") as String)},")
                appendLine("        ${list(tables.indices.map { "schema${version}Table$it()" })},")
                appendLine("    )")
                tables.forEachIndexed { tableIndex, table ->
                    val name = table.requiredValue("tableName") as String
                    val primaryKey = table.objectMap("primaryKey").stringList("columnNames")
                    appendLine("    private fun schema${version}Table$tableIndex() = ExportedRoomTable(")
                    appendLine("        ${quote(name)},")
                    appendLine("        ${list(table.objectList("fields").map { field ->
                        val column = field.requiredValue("columnName") as String
                        val default = (field["defaultValue"] as? String)?.let(::quote) ?: "null"
                        "ExportedRoomColumn(${quote(column)}, ${quote(field.requiredValue("affinity") as String)}, " +
                            "${field["notNull"] == true}, ${primaryKey.indexOf(column) + 1}, $default)"
                    })},")
                    appendLine("        ${list(table.objectList("indices").map { index ->
                        val columns = index.stringList("columnNames")
                        val orders = index.stringList("orders").ifEmpty { List(columns.size) { "ASC" } }
                        require(orders.size == columns.size) { "索引排序与列数量不匹配" }
                        "ExportedRoomIndex(${quote(index.requiredValue("name") as String)}, ${index["unique"] == true}, " +
                            "${list(columns.map(::quote))}, ${list(orders.map(::quote))}, " +
                            "${quote((index.requiredValue("createSql") as String).replace("\${TABLE_NAME}", name))})"
                    })},")
                    appendLine("        ${list(table.objectList("foreignKeys").map { key ->
                        "ExportedRoomForeignKey(${quote(key.requiredValue("table") as String)}, " +
                            "${quote(key.requiredValue("onDelete") as String)}, ${quote(key.requiredValue("onUpdate") as String)}, " +
                            "${list(key.stringList("columns").map(::quote))}, " +
                            "${list(key.stringList("referencedColumns").map(::quote))})"
                    })},")
                    val ftsSql = if (table.containsKey("ftsVersion")) {
                        quote((table.requiredValue("createSql") as String).replace("\${TABLE_NAME}", name))
                    } else "null"
                    appendLine("        $ftsSql,")
                    appendLine("    )")
                }
            }
            appendLine("}")
        }
        val output = outputDirectory.file(
            "com/promenar/infinity/data/local/db/GeneratedCurrentRoomSchemas.kt",
        ).get().asFile
        output.parentFile.mkdirs()
        output.writeText(source)
    }

    private fun Map<*, *>.requiredValue(key: String): Any = requireNotNull(this[key]) { "导出 schema 缺少必填字段：$key" }
    private fun Map<*, *>.objectMap(key: String) = requiredValue(key) as Map<*, *>
    private fun Map<*, *>.objectList(key: String) = (get(key) as? List<*>)?.map { it as Map<*, *> }.orEmpty()
    private fun Map<*, *>.stringList(key: String) = (get(key) as? List<*>)?.map { it as String }.orEmpty()
    private fun list(values: List<String>) = values.joinToString(", ", "listOf(", ")")
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> { append('\\'); append('$') }
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
}

val realLlmEnvironmentNames = listOf(
    "INFINITY_TEST_LLM_BASE_URL",
    "INFINITY_TEST_LLM_API_KEY",
    "INFINITY_TEST_LLM_MULTIMODAL_MODEL",
    "INFINITY_TEST_LLM_FAST_TEXT_MODEL",
    "INFINITY_TEST_LLM_REASONING_MODEL",
    "INFINITY_TEST_LLM_BALANCED_MULTIMODAL_MODEL",
)
val releaseSigningEnvironment = mapOf(
    "INFINITY_KEYSTORE_PATH" to providers.environmentVariable("INFINITY_KEYSTORE_PATH").orNull?.trim(),
    "INFINITY_STORE_PASSWORD" to providers.environmentVariable("INFINITY_STORE_PASSWORD").orNull,
    "INFINITY_KEY_ALIAS" to providers.environmentVariable("INFINITY_KEY_ALIAS").orNull?.trim(),
    "INFINITY_KEY_PASSWORD" to providers.environmentVariable("INFINITY_KEY_PASSWORD").orNull,
)
val missingReleaseSigningEnvironment = releaseSigningEnvironment
    .filterValues { it.isNullOrBlank() }
    .keys
    .sorted()
val deviceE2eEnabled = providers.gradleProperty("infinity.deviceE2e")
    .map(String::toBoolean)
    .getOrElse(false)
val deviceE2eAbi = providers.gradleProperty("infinity.deviceE2eAbi")
    .getOrElse("x86_64")
    .trim()
val allowedE2eBuildTypes = setOf("debug", "deviceTest")
val selectedDeviceE2eBuildType = providers.gradleProperty("infinity.deviceE2eTestBuildType")
    .map(String::trim)
    .orElse(if (deviceE2eEnabled) "deviceTest" else "debug")
    .get()
require(deviceE2eAbi in setOf("x86_64", "arm64-v8a")) {
    "infinity.deviceE2eAbi 仅支持 x86_64 或 arm64-v8a，当前值：$deviceE2eAbi"
}
require(selectedDeviceE2eBuildType in allowedE2eBuildTypes) {
    "infinity.deviceE2eTestBuildType 仅支持 debug 或 deviceTest，当前值：$selectedDeviceE2eBuildType"
}
require(!deviceE2eEnabled || selectedDeviceE2eBuildType == "deviceTest") {
    "启用 infinity.deviceE2e 时，infinity.deviceE2eTestBuildType 必须为 deviceTest，当前值：$selectedDeviceE2eBuildType"
}
val finalReleaseArtifactTasks = setOf(
    "assemblerelease",
    "bundlerelease",
    "packagerelease",
    "packagereleasebundle",
    "packagereleaseuniversalapk",
    "signreleasebundle",
    "installrelease",
)

fun releaseArtifactTask(taskName: String): Boolean {
    val normalized = taskName.lowercase().replace("sideload", "").replace("play", "")
    return normalized in finalReleaseArtifactTasks || normalized.startsWith("publishrelease")
}

plugins {
    id("com.android.application")
    id("com.android.compose.screenshot")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// 编译器读取 QuickJS 的 2.4 元数据，应用源码保持既有 2.2 语言规则。
kotlin.compilerOptions {
    languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
}

val launcherIconTask = tasks.register("generateInfinityLauncherIcons") {
    group = "verification"
    description = "验证并生成所有应用变体的折叠 N 图标。"
}
val currentRoomSchemaContracts = tasks.register<GenerateCurrentRoomSchemaContracts>("generateCurrentRoomSchemaContracts") {
    group = "build setup"
    description = "依据 v18 至 v33 导出版本生成完整数据库结构校验合同。"
    schemaFiles.from((18..33).map { version ->
        layout.projectDirectory.file("schemas/com.promenar.infinity.data.local.db.InfinityDatabase/$version.json")
    })
    outputDirectory.set(layout.buildDirectory.dir("generated/sources/currentRoomSchemaContracts"))
}
// 官方 CPython Android 包：下载后按 SHA-256 校验，整理为 jniLibs 打包库；产物只在 build 与 Gradle 缓存目录，不入库。
val packagedPython = PackagedPythonRelease.load(file("python/python-android.properties").toPath())
val packagedPythonVersion: String = packagedPython.version
val packagedPythonDistDir = providers.gradleProperty("infinity.pythonDistDir")
    .map { File(it) }
    .orElse(provider { File(gradle.gradleUserHomeDir, "caches/infinity-python-android") })
// 标准库与 site zip 预编译哈希 pyc，需 magic 与打包 CPython 一致的宿主解释器（默认 PATH 中的 python<主次版本>）；
// -Pinfinity.hostPython 指定解释器，-Pinfinity.pythonBytecode=false 跳过（发行校验会拒绝缺少字节码的 APK）。
val packagedPythonBytecode = providers.gradleProperty("infinity.pythonBytecode").map { it.toBoolean() }.orElse(true)
val packagedPythonHost = providers.gradleProperty("infinity.hostPython").orElse("python${packagedPython.majorMinor}")
val packagedPythonBytecodeCompiler = layout.projectDirectory.file("python/compile-bytecode.py")
/** 官方只提供这两个 ABI；其它 ABI 的设备运行时报告 Python 不可用。 */
val packagedPythonAbis = mapOf("arm64-v8a" to "aarch64", "x86_64" to "x86_64")
val preparePackagedPython = packagedPythonAbis.mapValues { (abi, arch) ->
    val taskSuffix = abi.split('-', '_').joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
    val archiveName = packagedPython.archiveName(arch)
    val fetch = tasks.register<FetchVerifiedFile>("fetchPackagedPython$taskSuffix") {
        description = "下载并校验 $archiveName。"
        url.set(packagedPython.archiveUrl(arch))
        sha256.set(packagedPython.sha256(arch))
        outputFile.set(layout.file(packagedPythonDistDir.map { File(it, archiveName) }))
    }
    tasks.register<PreparePackagedPython>("preparePackagedPython$taskSuffix") {
        archive.set(fetch.flatMap { it.outputFile })
        this.abi.set(abi)
        clangTarget.set("$arch-linux-android${android.defaultConfig.minSdk}")
        pythonMajorMinor.set(packagedPython.majorMinor)
        excludedStdlibDirectories.set(listOf("test", "idlelib", "tkinter", "turtledemo", "pydoc_data"))
        launcherSource.set(layout.projectDirectory.file("python/pylauncher.c"))
        siteCustomize.set(layout.projectDirectory.file("python/sitecustomize.py"))
        compileBytecode.set(packagedPythonBytecode)
        bytecodeMagic.set(packagedPython.pycMagic)
        hostPython.set(packagedPythonHost)
        bytecodeCompiler.set(packagedPythonBytecodeCompiler)
        ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
        outputDirectory.set(layout.buildDirectory.dir("generated/packagedPython/$abi"))
    }
}

// 内置 git 的纯 Python 依赖：wheel 按 SHA-256 校验后与 python/site 中的 Infinity Workbench 模块合成 libpysite.so。
val packagedPythonWheels = PackagedPythonWheels.load(file("python/python-site.properties").toPath())
val fetchPackagedPythonWheels = packagedPythonWheels.names.map { name ->
    tasks.register<FetchVerifiedFile>("fetchPackagedPythonWheel${name.replaceFirstChar { it.uppercaseChar() }}") {
        description = "下载并校验 ${packagedPythonWheels.fileName(name)}。"
        url.set(packagedPythonWheels.url(name))
        sha256.set(packagedPythonWheels.sha256(name))
        outputFile.set(layout.file(packagedPythonDistDir.map { File(it, "wheels/${packagedPythonWheels.fileName(name)}") }))
    }
}
val preparePackagedSite = packagedPythonAbis.keys.associateWith { abi ->
    val taskSuffix = abi.split('-', '_').joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
    tasks.register<PreparePackagedSite>("preparePackagedSite$taskSuffix") {
        wheels.from(fetchPackagedPythonWheels.map { fetch -> fetch.flatMap { it.outputFile } })
        moduleDirectory.set(layout.projectDirectory.dir("python/site"))
        excludedPrefixes.set(listOf("dulwich/tests/"))
        compileBytecode.set(packagedPythonBytecode)
        bytecodeMagic.set(packagedPython.pycMagic)
        hostPython.set(packagedPythonHost)
        bytecodeCompiler.set(packagedPythonBytecodeCompiler)
        this.abi.set(abi)
        outputDirectory.set(layout.buildDirectory.dir("generated/packagedPythonSite/$abi"))
    }
}

// 内置 Tailscale 节点（tsnet）：app/tailnet 的 Go 程序以固定 Go 版本与 go.sum 编译为 libnxtailnet.so，随两个渠道打包。
// 宿主需要 Go 1.21 及以上（GOTOOLCHAIN 精确切换到登记版本）；-Pinfinity.goExecutable 指定 go，
// -Pinfinity.tailnet=false 跳过（运行时报告不可用，发行校验会拒绝缺少该库的 APK）。
val tailnetGoVersion = "1.27.1"
val tailnetEnabled = providers.gradleProperty("infinity.tailnet").map(String::toBoolean).getOrElse(true)
val tailnetGoExecutable = providers.gradleProperty("infinity.goExecutable").orElse("go")
// ABI → GOOS/GOARCH。android/amd64 只支持 cgo 外部链接，x86_64（模拟器设备测试）改用静态 linux/amd64，
// androidbin 为在 Android 内核上运行的静态 linux 程序补上网卡、DNS 与系统证书。
val tailnetGoTargets = mapOf("arm64-v8a" to ("android" to "arm64"), "x86_64" to ("linux" to "amd64"))
val buildTailnetBinary = tailnetGoTargets.mapValues { (abi, target) ->
    val taskSuffix = abi.split('-', '_').joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
    tasks.register<BuildTailnetBinary>("buildTailnet$taskSuffix") {
        moduleDirectory.set(layout.projectDirectory.dir("tailnet"))
        sources.from(fileTree("tailnet") { include("**/*.go", "go.mod", "go.sum"); exclude("**/*_test.go") })
        goVersion.set(tailnetGoVersion)
        this.abi.set(abi)
        goOs.set(target.first)
        goArch.set(target.second)
        goExecutable.set(tailnetGoExecutable)
        outputDirectory.set(layout.buildDirectory.dir("generated/tailnet/$abi"))
    }
}

// 设置页底部展示的提交短 SHA；源码不在 Git 仓库或没有 git 时写 unknown，不让构建失败。
val gitCommitShortSha: String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short=7", "HEAD")
        workingDir = projectDir
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrDefault("").takeIf { Regex("[0-9a-f]{7,40}").matches(it) } ?: "unknown"

// Linux 运行时固定构建输入；仅 arm64 侧载变体打包程序与基础系统。
val linuxRuntimeProperties = LinuxRuntimeInputs.load(file("linux/linux-runtime.properties").toPath())
android.defaultConfig {
    mapOf("LINUX_ALPINE_VERSION" to "alpine.version", "LINUX_PROOT_VERSION" to "proot.version",
        "LINUX_ROOTFS_SHA256" to "alpine.sha256", "LINUX_ROOTFS_ASSET" to "alpine.asset").forEach { (field, property) ->
        buildConfigField("String", field, "\"${linuxRuntimeProperties.getProperty(property)}\"")
    }
    buildConfigField("long", "LINUX_ROOTFS_UNPACKED_BYTES", "${linuxRuntimeProperties.getProperty("alpine.unpackedBytes")}L")
}
val linuxRuntimeFetches = listOf("proot", "talloc", "alpine").associateWith { name ->
    tasks.register<FetchVerifiedFile>("fetchLinuxRuntime${name.replaceFirstChar { it.uppercaseChar() }}") {
        url.set(linuxRuntimeProperties.getProperty("$name.url"))
        sha256.set(linuxRuntimeProperties.getProperty("$name.sha256"))
        outputFile.set(layout.buildDirectory.file("linux-downloads/$name.tar.gz"))
    }
}
val buildLinuxRuntime = tasks.register<BuildLinuxRuntime>("buildLinuxRuntime") {
    prootArchive.set(linuxRuntimeFetches.getValue("proot").flatMap { it.outputFile })
    tallocArchive.set(linuxRuntimeFetches.getValue("talloc").flatMap { it.outputFile })
    prootVersion.set(linuxRuntimeProperties.getProperty("proot.version"))
    buildSources.set(layout.projectDirectory.dir("linux"))
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    outputDirectory.set(layout.buildDirectory.dir("generated/linuxRuntime/jniLibs"))
}
val prepareLinuxRuntimeAssets = tasks.register<PrepareLinuxRuntimeAssets>("prepareLinuxRuntimeAssets") {
    archive.set(linuxRuntimeFetches.getValue("alpine").flatMap { it.outputFile })
    runtimeProperties.set(layout.projectDirectory.file("linux/linux-runtime.properties"))
    outputDirectory.set(layout.buildDirectory.dir("generated/linuxRuntime/assets"))
}

androidComponents.onVariants { variant ->
    val capitalizedName = variant.name.replaceFirstChar { it.uppercaseChar() }
    // 本地 PTY 仅供侧载版使用，Play 版继续只保留 Java 终端仿真。
    if (variant.productFlavors.any { it.first == "distribution" && it.second == "play" }) {
        variant.packaging.jniLibs.excludes.add("**/libtermux.so")
    }
    // 只为变体实际打包的 ABI 准备 Python。未设 abiFilters 的通用包（Release）只带 arm64-v8a：
    // x86_64 Python 约 11.5 MB 只服务 x86_64 设备，通用 APK 需为发行校验的 50 MB 上限留出余量。
    val variantAbis = android.defaultConfig.ndk.abiFilters + android.buildTypes.getByName(variant.buildType!!).ndk.abiFilters
    val pythonAbis = variantAbis.ifEmpty { setOf("arm64-v8a") }
    if (variant.productFlavors.any { it.first == "distribution" && it.second == "sideload" } && "arm64-v8a" in pythonAbis) {
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildLinuxRuntime, BuildLinuxRuntime::getOutputDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(prepareLinuxRuntimeAssets, PrepareLinuxRuntimeAssets::getOutputDirectory)
    }
    preparePackagedPython.filterKeys { it in pythonAbis }.values.forEach { prepare ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepare, PreparePackagedPython::getOutputDirectory)
    }
    preparePackagedSite.filterKeys { it in pythonAbis }.values.forEach { prepare ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepare, PreparePackagedSite::getOutputDirectory)
    }
    // 内置 Tailscale 与打包 Python 使用相同的 ABI 规则。
    if (tailnetEnabled) {
        buildTailnetBinary.filterKeys { it in pythonAbis }.values.forEach { build ->
            variant.sources.jniLibs?.addGeneratedSourceDirectory(build, BuildTailnetBinary::getOutputDirectory)
        }
    }
    val generateIcons = tasks.register<GenerateLauncherIcons>("generate${capitalizedName}InfinityLauncherIcons") {
        masterSvg.set(rootProject.layout.projectDirectory.file("../assets/branding/infinity-dots-master.svg"))
        foregroundRasters.set(rootProject.layout.projectDirectory.dir("../assets/branding/launcher-foreground"))
        monochromeSvg.set(rootProject.layout.projectDirectory.file("../assets/branding/infinity-dots-monochrome.svg"))
        notificationSvg.set(rootProject.layout.projectDirectory.file("../assets/branding/infinity-notification.svg"))
        sourceRoots.from(layout.projectDirectory.dir("src"))
        sourceRoots.from(provider {
            android.sourceSets.flatMap { source -> source.res.directories.map { project.file(it) } }
        })
    }
    variant.sources.res?.addGeneratedSourceDirectory(generateIcons, GenerateLauncherIcons::getOutputDirectory)
    variant.sources.java?.addGeneratedSourceDirectory(
        currentRoomSchemaContracts, GenerateCurrentRoomSchemaContracts::outputDirectory,
    )
    launcherIconTask.configure { dependsOn(generateIcons) }
}

android {
    namespace = "com.promenar.infinity"
    // navigation-compose 2.10 与其带入的 lifecycle 2.11 要求按 API 37 编译；运行行为由 targetSdk 决定，仍为 36。
    compileSdk = 37
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    defaultConfig {
        applicationId = "com.promenar.infinity"
        minSdk = 31
        targetSdk = 36
        versionCode = 76
        versionName = "0.29.0-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PACKAGED_PYTHON_VERSION", "\"$packagedPythonVersion\"")
        buildConfigField("String", "GIT_COMMIT_SHORT", "\"$gitCommitShortSha\"")
        val githubOAuthClientId = providers.gradleProperty("infinity.githubOAuthClientId").orNull ?: ""
        require(githubOAuthClientId.isEmpty() || githubOAuthClientId.matches(Regex("[A-Za-z0-9._-]+"))) {
            "Gradle 属性 infinity.githubOAuthClientId 格式不正确：只允许字母、数字、点、下划线和连字符"
        }
        buildConfigField("String", "GITHUB_OAUTH_CLIENT_ID", "\"$githubOAuthClientId\"")

        androidResources {
            localeFilters += listOf("en", "zh-rCN")
        }

    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"play\"")
            buildConfigField("boolean", "ALL_FILES_ACCESS_AVAILABLE", "false")
            buildConfigField("boolean", "EXACT_ALARM_AVAILABLE", "false")
        }
        create("sideload") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"sideload\"")
            buildConfigField("boolean", "ALL_FILES_ACCESS_AVAILABLE", "true")
            buildConfigField("boolean", "EXACT_ALARM_AVAILABLE", "true")
        }
    }

    signingConfigs {
        create("release") {
            if (missingReleaseSigningEnvironment.isEmpty()) {
                storeFile = file(requireNotNull(releaseSigningEnvironment["INFINITY_KEYSTORE_PATH"]))
                storePassword = requireNotNull(releaseSigningEnvironment["INFINITY_STORE_PASSWORD"])
                keyAlias = requireNotNull(releaseSigningEnvironment["INFINITY_KEY_ALIAS"])
                keyPassword = requireNotNull(releaseSigningEnvironment["INFINITY_KEY_PASSWORD"])
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Infinity Workbench (Dev)")
            buildConfigField("boolean", "LOCAL_INFERENCE_AVAILABLE", "true")
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
            externalNativeBuild {
                cmake {
                    arguments += "-DINFINITY_ENABLE_LOCAL_INFERENCE=ON"
                }
            }
        }
        create("deviceTest") {
            applicationIdSuffix = ".deviceTest"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "Infinity Workbench (Device Test)")
            buildConfigField("boolean", "LOCAL_INFERENCE_AVAILABLE", "false")
            ndk {
                abiFilters += listOf(deviceE2eAbi)
            }
            externalNativeBuild {
                cmake {
                    arguments += "-DINFINITY_ENABLE_LOCAL_INFERENCE=OFF"
                }
            }
        }
        create("minifiedTest") {
            applicationIdSuffix = ".minifiedTest"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "Infinity Workbench (Minified Test)")
            buildConfigField("boolean", "LOCAL_INFERENCE_AVAILABLE", "false")
            ndk {
                abiFilters += listOf(deviceE2eAbi)
            }
            externalNativeBuild {
                cmake {
                    arguments += "-DINFINITY_ENABLE_LOCAL_INFERENCE=OFF"
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            resValue("string", "app_name", "Infinity Workbench")
            buildConfigField("boolean", "LOCAL_INFERENCE_AVAILABLE", "false")
            externalNativeBuild {
                cmake {
                    arguments += "-DINFINITY_ENABLE_LOCAL_INFERENCE=OFF"
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testBuildType = selectedDeviceE2eBuildType

    sourceSets {
        getByName("deviceTest") {
            manifest.srcFile("src/debug/AndroidManifest.xml")
            java.directories.add("src/debug/java")
            kotlin.directories.add("src/debug/java")
            res.directories.add("src/debug/res")
        }
        getByName("androidTest") {
            assets.directories.add("$projectDir/schemas")
            assets.directories.add("$projectDir/src/test/resources/legacy")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // jniLibs 默认目录即 src/main/jniLibs，无需显式配置

    packaging {
        // 打包的 Python 启动器与扩展需要真实文件路径才能 execve/dlopen，安装时必须解压到 nativeLibraryDir。
        jniLibs.useLegacyPackaging = true
        // libpystdlib.so 与 libpysite.so 是 Python 模块 zip，不是 ELF，跳过符号剥离。
        // libnxtailnet.so 是已用 -s -w 剥离的 Go 可执行文件，保持构建产物原样（可复现）。
        jniLibs.keepDebugSymbols += listOf("**/libpystdlib.so", "**/libpysite.so", "**/libnxtailnet.so")
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        screenshotTests {
            imageDifferenceThreshold = 0.0001f
        }
    }

    lint {
        // 测试源码不跑 lint：两个测试组件的分析约占门禁 6 分钟 CPU，且与主变体 lint 抢核心。
        ignoreTestSources = true
    }

}

// 插件在变体注册期间设置目录；待所有项目完成配置后绑定共用基线，避免被覆盖。
gradle.projectsEvaluated {
    tasks.withType<PreviewScreenshotValidationTask>().configureEach {
        systemProperty("user.timezone", "Asia/Shanghai")
        val referenceOverride = providers.environmentVariable("INFINITY_SCREENSHOT_REFERENCE_DIR")
        testEngineInput.referenceImageDir.set(
            layout.dir(referenceOverride.map { file(it) }).orElse(layout.projectDirectory.dir("src/screenshotTestDebug/reference")),
        )
    }
    tasks.withType<PreviewScreenshotUpdateTask>().configureEach {
        systemProperty("user.timezone", "Asia/Shanghai")
        testEngineInput.referenceImageDir.set(layout.projectDirectory.dir("src/screenshotTestDebug/reference"))
    }
}

gradle.taskGraph.whenReady {
    val needsReleaseSigning = allTasks.any { task ->
        task.project.path == project.path && releaseArtifactTask(task.name)
    }
    if (needsReleaseSigning && missingReleaseSigningEnvironment.isNotEmpty()) {
        throw GradleException(
            "Release signing environment is incomplete. Missing: " +
                missingReleaseSigningEnvironment.joinToString(", ")
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("io.github.dokar3:quickjs-kt-android:1.0.15")
    // ─── Jetpack Compose BOM ───
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    screenshotTestImplementation("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha15")
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")

    // ─── AndroidX ───
    implementation("androidx.activity:activity-compose:1.13.0")
    // InfinityBackScope 直接替换返回调度器；版本与 activity-compose 1.13.0 解析结果一致。
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.18.0")

    // ─── OkHttp (SSE Client 临时依赖，后期迁移到 Ktor) ───
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // ─── 网络 (Multiplatform-Ready) ───
    implementation("io.ktor:ktor-client-android:2.3.7")
    implementation("io.ktor:ktor-client-okhttp:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // ─── 序列化 (Multiplatform-Ready) ───
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // ─── 协程 ───
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // ─── 图片 (Multiplatform-Ready) ───
    implementation("io.coil-kt.coil3:coil:3.0.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.0")
    implementation("io.coil-kt.coil3:coil-video:3.0.0")
    implementation("io.coil-kt.coil3:coil-svg:3.0.0")

    // ─── 音视频播放（会话内工作区媒体） ───
    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui-compose-material3:$media3Version")

    // ─── 数据存储 (Multiplatform-Ready) ───
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // ─── Room (SQLite ORM) ───
    val roomVersion = "2.7.0-alpha11"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    testImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")

    // ─── Markdown 渲染 ───
    // 会话自包含 HTML 导出直接使用已在渲染器运行时类路径中的 Markdown 解析器。
    implementation("org.jetbrains:markdown:0.7.3")
    val markdownRendererVersion = "0.41.0"
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:$markdownRendererVersion")
    implementation("com.mikepenz:multiplatform-markdown-renderer-coil3:$markdownRendererVersion")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:$markdownRendererVersion")

    // ─── HTML 解析 ───
    implementation("org.jsoup:jsoup:1.17.2")

    // ─── 文档解析 ───
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // ─── 测试 ───
    // SSH 仅使用库内密码学实现，不替换 Android 系统的 BC Provider。
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.86")
    constraints {
        // PDFBox 已使用该家族；SSH 与 PDF 密码学统一版本，避免重复类和跨版本 API 混用。
        implementation("org.bouncycastle:bcpkix-jdk15to18:1.86")
        implementation("org.bouncycastle:bcutil-jdk15to18:1.86")
    }
    implementation("com.termux.termux-app:terminal-emulator:0.118.0")
    implementation("com.termux.termux-app:terminal-view:0.118.0")
    testImplementation("org.apache.sshd:sshd-core:2.16.0")
    testImplementation("org.apache.sshd:sshd-sftp:2.16.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.junit.vintage:junit-vintage-engine:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.ktor:ktor-client-mock:2.3.7")
    testImplementation("com.google.truth:truth:1.2.0")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("com.google.truth:truth:1.2.0")
    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    "deviceTestImplementation"(composeBom)
    "deviceTestImplementation"("androidx.compose.ui:ui-test-manifest")
}

// JVM 测试加载桌面 JNI，设备和产品变体保持 Android JNI。
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:1.0.15"))
    }
}

// 宿主夹具使用独立 Java 类路径，MINA 不进入应用或 instrumentation APK。
val sshTestServerRuntime by configurations.creating
dependencies {
    add(sshTestServerRuntime.name, "org.apache.sshd:sshd-core:2.16.0")
    add(sshTestServerRuntime.name, "org.apache.sshd:sshd-sftp:2.16.0")
    add(sshTestServerRuntime.name, "org.slf4j:slf4j-nop:2.0.17")
}
val compileSshTestServer by tasks.registering(JavaCompile::class) {
    source(fileTree("src/sshTestServer/java") { include("**/*.java") })
    classpath = sshTestServerRuntime
    destinationDirectory.set(layout.buildDirectory.dir("classes/sshTestServer"))
    options.release.set(17)
    options.encoding = "UTF-8"
}
tasks.register<JavaExec>("runSshTestServer") {
    group = "verification"
    description = "前台运行仅监听宿主回环的 SSH 设备验收夹具；Ctrl-C 退出。"
    dependsOn(compileSshTestServer)
    classpath = files(compileSshTestServer.flatMap { it.destinationDirectory }) + sshTestServerRuntime
    mainClass.set("com.promenar.infinity.testserver.SshTestServer")
    workingDir = project.projectDir
    standardInput = System.`in`
}

tasks.withType<Test> {
    useJUnitPlatform()
    workingDir = rootProject.projectDir
    // 完整 JVM 单测的测试进程需容纳持久化测试夹具与 Android/Robolectric 类加载。
    if (name == "testSideloadDebugUnitTest" || name == "testPlayDebugUnitTest") {
        maxHeapSize = "1g"
        // 完整单测按测试类分到多个进程并行；每个进程 1g 堆，默认取半数核心且不超过 4，内存紧张时可用 -Pinfinity.unitTestForks 下调。
        maxParallelForks = providers.gradleProperty("infinity.unitTestForks").orNull?.toIntOrNull()
            ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    }
    // MINA 的 NIO SPI 由 JDK 加载；保持同一加载器，避免污染 Robolectric 原生 SQLite 初始化。
    systemProperty("org.robolectric.packagesToNotAcquire", "org.apache.sshd.")
    // Robolectric 默认把 Conscrypt 插到 JVM 全局安全提供者首位且不还原（仅 Apple Silicon 例外），
    // 会让同进程后续普通 JUnit 测试的 TLS 实现随类分配变化；统一关闭，各主机一致使用 JDK 提供者。
    systemProperty("robolectric.conscryptMode", "OFF")
}


afterEvaluate {
    if (deviceE2eEnabled) return@afterEvaluate
    val debugUnitTest = tasks.named<Test>("testSideloadDebugUnitTest")
    tasks.register<Test>("localGatewayIntegrationTest") {
        group = "verification"
        description = "显式测试本机无密钥聚合网关的五个模型；只发送有界合成文本与工具夹具。"
        testClassesDirs = debugUnitTest.get().testClassesDirs
        classpath = debugUnitTest.get().classpath
        dependsOn("compileSideloadDebugUnitTestSources", "processSideloadDebugUnitTestJavaRes")
        filter { includeTestsMatching("*LocalGatewayIntegrationTest") }
        systemProperty("infinity.localGatewayIntegration", "true")
        providers.gradleProperty("infinity.localGatewayModels").orNull?.let {
            systemProperty("infinity.localGatewayModels", it)
        }
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        maxParallelForks = 1
        testLogging { showStandardStreams = false }
    }
    tasks.register<Test>("realLlmIntegrationTest") {
        group = "verification"
        description = "显式运行真实 LLM 集成测试；凭证仅从允许的进程环境变量读取。"
        val configured = realLlmEnvironmentNames.all {
            providers.environmentVariable(it).orNull?.isNotBlank() == true
        }
        testClassesDirs = debugUnitTest.get().testClassesDirs
        classpath = debugUnitTest.get().classpath
        filter {
            includeTestsMatching("*RealLlmProviderIntegrationTest")
            isFailOnNoMatchingTests = true
        }
        failOnNoDiscoveredTests.set(true)
        systemProperty("infinity.realLlmIntegration", "true")
        failFast = true
        // 显式真实网络门禁每次调用都必须重跑，禁止复用旧模型、旧凭据或旧服务状态的结果。
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        reports.html.required.set(false)
        reports.junitXml.required.set(false)
        testLogging {
            showExceptions = false
            showCauses = false
            showStackTraces = false
            showStandardStreams = false
        }
        if (configured) {
            dependsOn("compileSideloadDebugUnitTestSources", "processSideloadDebugUnitTestJavaRes")
        }
        onlyIf {
            if (!configured) logger.lifecycle("integration credentials unavailable")
            configured
        }
    }
}
