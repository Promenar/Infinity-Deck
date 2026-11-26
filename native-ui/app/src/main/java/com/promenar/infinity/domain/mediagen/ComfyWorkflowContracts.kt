package com.promenar.infinity.domain.mediagen

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal

@Serializable(with = ComfyMediaCategorySerializer::class)
enum class ComfyMediaCategory {
    IMAGE,
    VIDEO,
    SPEECH,
    MUSIC,
    POST_PROCESS,
    MODEL_3D,
    FILE;

    fun toMediaGroup(modes: Set<GenerationMode> = emptySet()): MediaGroup = when (this) {
        IMAGE, MODEL_3D, FILE -> MediaGroup.IMAGE
        VIDEO, POST_PROCESS -> MediaGroup.VIDEO
        SPEECH -> MediaGroup.SPEECH
        MUSIC -> if (GenerationMode.SPEECH in modes && GenerationMode.MUSIC !in modes) MediaGroup.SPEECH else MediaGroup.MUSIC
    }

    companion object {
        fun fromMediaGroup(group: MediaGroup, isPostProcess: Boolean = false): ComfyMediaCategory = when {
            isPostProcess -> POST_PROCESS
            group == MediaGroup.IMAGE -> IMAGE
            group == MediaGroup.SPEECH -> SPEECH
            group == MediaGroup.MUSIC -> MUSIC
            else -> VIDEO
        }
    }
}

/** 兼容历史已持久化的 "AUDIO" 类别；读回时默认归入 MUSIC，并由工作流 modes 在投影时细分。 */
object ComfyMediaCategorySerializer : kotlinx.serialization.KSerializer<ComfyMediaCategory> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "ComfyMediaCategory",
        kotlinx.serialization.descriptors.PrimitiveKind.STRING,
    )

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: ComfyMediaCategory) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ComfyMediaCategory {
        val raw = decoder.decodeString()
        return when (raw.uppercase()) {
            "AUDIO" -> ComfyMediaCategory.MUSIC
            else -> ComfyMediaCategory.valueOf(raw)
        }
    }
}

@Serializable
enum class WorkflowExecutionMode {
    SINGLE,
    STATEFUL_CHAIN,
}

@Serializable
enum class FieldMutability {
    CONTENT,
    FIXED,
}

@Serializable
enum class FieldValueType {
    STRING,
    TEXT,
    INT,
    FLOAT,
    BOOL,
    ENUM,
    JSON,
    IMAGE_FILE,
    VIDEO_FILE,
    AUDIO_FILE,
    ANY_FILE,
    DIRECTORY,
    FILE_LIST,
    TIMED_FILES;

    val isMediaFileType: Boolean
        get() = this in setOf(IMAGE_FILE, VIDEO_FILE, AUDIO_FILE, FILE_LIST, TIMED_FILES)

    val isWorkspacePathType: Boolean
        get() = isMediaFileType || this in setOf(ANY_FILE, DIRECTORY)
}

@Serializable
enum class FieldSemanticRole {
    NONE,
    PROMPT,
    NEGATIVE_PROMPT,
    REFERENCE_IMAGES,
    FIRST_FRAME,
    LAST_FRAME,
    GUIDES,
    FRAMES,
    DURATION_SECONDS,
    WIDTH,
    HEIGHT,
    SEED,
    AUDIO_REF,
    INPUT_VIDEO,
}

@Serializable
enum class CliArgMode {
    VALUE,
    REPEAT,
    BOOL_FLAG,
    TEXT_FILE,
    JSON,
    TEMPLATE,
}

@Serializable
enum class SeedPolicy {
    CALLER,
    RANDOM_EACH_RUN,
    FIXED,
}

@Serializable
data class WorkflowFieldSpec(
    val key: String,
    val label: String,
    val description: String = "",
    val mutability: FieldMutability = FieldMutability.CONTENT,
    val valueType: FieldValueType = FieldValueType.STRING,
    val required: Boolean = false,
    val defaultValue: JsonElement? = null,
    val options: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val semanticRole: FieldSemanticRole = FieldSemanticRole.NONE,
    val cliFlag: String? = null,
    val cliFalseFlag: String? = null,
    val cliMode: CliArgMode = CliArgMode.VALUE,
    val cliValueTemplate: String? = null,
    val stageParentDirectory: Boolean = false,
    val seedPolicy: SeedPolicy = SeedPolicy.CALLER,
    val nodeId: String? = null,
    val inputKey: String? = null,
    val uploadNodeId: String? = null,
    val uploadInputKey: String? = null,
    val uploadTargets: List<ComfyUploadTarget> = emptyList(),
    val dynamicInputPrefix: String? = null,
    val dynamicStartIndex: Int = 0,
    val uploadNodeClassType: String? = null,
) {
    init {
        require(key.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,63}"))) {
            "field.key($key): 须为字母开头、1～64 位字母数字下划线或点/连字符"
        }
        require(label.isNotBlank() && label.length <= 60) {
            "field.$key.label: 需要 1～60 字符"
        }
        if (valueType == FieldValueType.ENUM) {
            require(options.isNotEmpty() && options.distinct().size == options.size) {
                "field.$key.options: ENUM 需要至少 1 个不重复选项"
            }
        }
        require(min == null || min.isFinite()) { "field.$key.min: 需要有限数值" }
        require(max == null || max.isFinite()) { "field.$key.max: 需要有限数值" }
        require(min == null || max == null || min <= max) { "field.$key.min/max: 范围顺序无效" }
        require(dynamicStartIndex >= 0) { "field.$key.dynamicStartIndex: 不能为负数" }
        dynamicInputPrefix?.let { prefix ->
            require(prefix.isNotBlank() && prefix.none { it <= ' ' || it == '\u007f' }) {
                "field.$key.dynamicInputPrefix: 动态前缀不能为空或包含空白字符"
            }
        }
        uploadNodeClassType?.let { ct ->
            require(ct.isNotBlank() && ct.none { it < ' ' || it == '\u007f' }) {
                "field.$key.uploadNodeClassType: 上传节点类名无效"
            }
        }
        cliFlag?.let { flag ->
            require(flag.isNotBlank() && flag.none { it <= ' ' || it == '\u007f' }) {
                "field.$key.cliFlag: 命令行标志不能包含空白或控制字符"
            }
        }
        cliFalseFlag?.let { flag ->
            require(flag.isNotBlank() && flag.none { it <= ' ' || it == '\u007f' }) {
                "field.$key.cliFalseFlag: 布尔假值标志不能包含空白或控制字符"
            }
        }
        cliValueTemplate?.let { tpl ->
            require(tpl.isNotBlank() && '\u0000' !in tpl) {
                "field.$key.cliValueTemplate: 参数模板不能为空或包含空字符"
            }
        }
        if (cliMode == CliArgMode.TEMPLATE) {
            require(!cliValueTemplate.isNullOrBlank()) {
                "field.$key.cliValueTemplate: cliMode=TEMPLATE 必须提供 cliValueTemplate"
            }
        }
        if (defaultValue != null && defaultValue != JsonNull) {
            validateValue(defaultValue, isDefaultCheck = true)
        }
    }

    /** 统一解析静态上传目标清单：优先使用 [uploadTargets]，兼容单目标 [uploadNodeId]/[uploadInputKey]。 */
    fun effectiveUploadTargets(): List<ComfyUploadTarget> = when {
        uploadTargets.isNotEmpty() -> uploadTargets
        !uploadNodeId.isNullOrBlank() && !uploadInputKey.isNullOrBlank() ->
            listOf(ComfyUploadTarget(uploadNodeId, uploadInputKey))
        else -> emptyList()
    }

    fun validateValue(value: JsonElement?, isDefaultCheck: Boolean = false) {
        if (value == null || value == JsonNull) {
            if (required && !isDefaultCheck) {
                throw IllegalArgumentException("字段 $key 为必填项")
            }
            return
        }
        when (valueType) {
            FieldValueType.STRING, FieldValueType.TEXT -> {
                val content = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    ?: throw IllegalArgumentException("字段 $key 需要字符串")
                if (required && !isDefaultCheck) {
                    require(content.isNotBlank()) { "字段 $key 不能为空白" }
                }
            }
            FieldValueType.INT -> {
                val decimal = numericDecimal(value)
                    ?: throw IllegalArgumentException("字段 $key 需要整数")
                require(decimal.stripTrailingZeros().scale() <= 0) { "字段 $key 需要整数" }
                checkNumericRange(decimal.toDouble())
            }
            FieldValueType.FLOAT -> {
                val decimal = numericDecimal(value)
                    ?: throw IllegalArgumentException("字段 $key 需要数值")
                checkNumericRange(decimal.toDouble())
            }
            FieldValueType.BOOL -> {
                parseBool(value) ?: throw IllegalArgumentException("字段 $key 需要布尔值")
            }
            FieldValueType.ENUM -> {
                val choice = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    ?: throw IllegalArgumentException("字段 $key 需要枚举字符串")
                require(choice in options) { "字段 $key 的值 '$choice' 不在允许选项内" }
            }
            FieldValueType.JSON -> Unit
            FieldValueType.IMAGE_FILE, FieldValueType.VIDEO_FILE, FieldValueType.AUDIO_FILE,
            FieldValueType.ANY_FILE, FieldValueType.DIRECTORY, -> {
                val path = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    ?: throw IllegalArgumentException("字段 $key 需要工作区路径字符串")
                if (!isDefaultCheck || path.isNotEmpty()) {
                    require(path.isNotBlank()) { "字段 $key 的路径不能为空" }
                }
            }
            FieldValueType.FILE_LIST -> {
                val array = value as? JsonArray
                    ?: throw IllegalArgumentException("字段 $key 需要文件路径数组")
                array.forEachIndexed { idx, item ->
                    when (item) {
                        is JsonPrimitive -> {
                            val path = item.takeIf { it.isString }?.contentOrNull
                            require(!path.isNullOrBlank()) { "字段 $key[$idx] 需要非空路径字符串" }
                        }
                        is JsonObject -> {
                            require(!cliValueTemplate.isNullOrBlank()) {
                                "字段 $key[$idx] 为对象时须声明 cliValueTemplate"
                            }
                            val path = extractPathFromObject(item)
                            require(!path.isNullOrBlank()) { "字段 $key[$idx] 缺少文件路径（file/path/image_path/video/audio）" }
                        }
                        else -> throw IllegalArgumentException("字段 $key[$idx] 需要非空路径字符串")
                    }
                }
            }
            FieldValueType.TIMED_FILES -> {
                val array = value as? JsonArray
                    ?: throw IllegalArgumentException("字段 $key 需要带帧号/时间锚点的数组")
                array.forEachIndexed { idx, item ->
                    val obj = item as? JsonObject
                        ?: throw IllegalArgumentException("字段 $key[$idx] 需要对象 {frame_idx|time_seconds, path}")
                    val path = (obj["path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?: (obj["image_path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?: (obj["file"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    require(!path.isNullOrBlank()) { "字段 $key[$idx] 缺少文件路径" }
                    val hasFrame = obj["frame_idx"]?.let(::numericDecimal) != null
                    val hasTime = obj["time_seconds"]?.let(::numericDecimal) != null
                    require(hasFrame || hasTime) { "字段 $key[$idx] 需要 frame_idx 或 time_seconds" }
                }
            }
        }
    }

    fun isEffectivelyDifferentFromBaseline(overrideValue: JsonElement): Boolean {
        val baseline = defaultValue ?: return overrideValue != JsonNull
        return normalizeForComparison(baseline) != normalizeForComparison(overrideValue)
    }

    fun displayValue(element: JsonElement?): String = when {
        element == null || element == JsonNull -> "未设置"
        element is JsonPrimitive && element.isString -> element.content
        else -> element.toString()
    }

    private fun checkNumericRange(number: Double) {
        require(number.isFinite()) { "字段 $key 需要有限数值" }
        min?.let { require(number >= it) { "字段 $key 不能小于 $it" } }
        max?.let { require(number <= it) { "字段 $key 不能大于 $it" } }
    }

    private fun numericDecimal(element: JsonElement): BigDecimal? {
        val prim = element as? JsonPrimitive ?: return null
        if (prim.isString) return null
        val doubleVal = prim.doubleOrNull ?: return null
        if (!doubleVal.isFinite()) return null
        return prim.content.toBigDecimalOrNull()
    }

    private fun parseBool(element: JsonElement): Boolean? {
        val prim = element as? JsonPrimitive ?: return null
        if (!prim.isString) return prim.booleanOrNull
        return when (prim.content.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun normalizeForComparison(element: JsonElement): String = when (valueType) {
        FieldValueType.INT, FieldValueType.FLOAT -> {
            numericDecimal(element)?.stripTrailingZeros()?.toPlainString() ?: element.toString()
        }
        FieldValueType.BOOL -> {
            parseBool(element)?.toString() ?: element.toString()
        }
        else -> canonicalJsonString(element)
    }

    private fun canonicalJsonString(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${canonicalJsonString(it.value)}" }
        is JsonArray -> element.joinToString(",", "[", "]") { canonicalJsonString(it) }
        else -> element.toString()
    }

    internal fun extractPathFromObject(obj: JsonObject): String? =
        listOf("file", "path", "image_path", "video", "audio").firstNotNullOfOrNull { k ->
            (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        }
}

@Serializable
enum class SubmitterInvocationProtocol {
    PAYLOAD_JSON,
    CLI_ARGS,
}

@Serializable
enum class SubmitterSourceKind {
    DESKTOP_IMPORT,
    SKILL_SYNC,
    BUILTIN_GENERIC,
    AGENT_AUTHORED,
}

@Serializable
enum class SubmitterDryRunStatus {
    UNTESTED,
    PASSED,
    FAILED,
}

@Serializable
data class ComfySubmitterScript(
    val scriptId: String,
    val hostId: String,
    val fileName: String,
    val displayName: String = fileName,
    val description: String = "",
    val sourceKind: SubmitterSourceKind = SubmitterSourceKind.AGENT_AUTHORED,
    val protocol: SubmitterInvocationProtocol = SubmitterInvocationProtocol.PAYLOAD_JSON,
    val sha256: String,
    val supportFiles: List<String> = emptyList(),
    val lastDryRunStatus: SubmitterDryRunStatus = SubmitterDryRunStatus.UNTESTED,
    val lastDryRunMessage: String = "",
    val lastDryRunLatencyMs: Long? = null,
    val lastDryRunAt: Long? = null,
    val entrySchemaVersion: Int = 1,
    val updatedAt: Long = 0L,
) {
    init {
        require(scriptId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "提交器 scriptId 无效" }
        require(hostId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "提交器 hostId 无效" }
        require(isSafeRelativeSubPath(fileName) && fileName.endsWith(".py")) {
            "提交器 fileName 须为 .py 相对文件名"
        }
        require(displayName.isNotBlank() && displayName.length <= 120) { "提交器名称需要 1～120 字符" }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "提交器 sha256 格式无效" }
        supportFiles.forEach { rel ->
            require(isSafeRelativeSubPath(rel)) { "配套文件路径 $rel 不合法" }
        }
    }
}

@Serializable
enum class StatefulBranchPolicy {
    ISOLATED_DIR,
    IN_PLACE_OVERWRITE,
}

@Serializable
data class StatefulChainSpec(
    val stages: List<String> = listOf("first", "next"),
    val lockedInheritedKeys: List<String> = listOf(
        "latent_dir",
        "width",
        "height",
        "family",
        "context_length",
        "audio_context_length",
    ),
    val defaultBranchPolicy: StatefulBranchPolicy = StatefulBranchPolicy.ISOLATED_DIR,
    val supportsIsolatedBranchDir: Boolean = true,
    val hostFlag: String = "--host",
    val outputDirFlag: String = "--output-dir",
    val stageFlag: String = "--stage",
    val previousWorkflowFlag: String = "--previous-workflow",
    val latentDirFlag: String = "--latent-dir",
    val branchIdFlag: String = "",
    val segmentIndexFlag: String = "",
    val readLatentDirFlag: String = "",
    val writeLatentDirFlag: String = "",
    val dryRunFlag: String = "--dry-run",
    val sidecarSuffixes: List<String> = emptyList(),
) {
    init {
        require(stages.isNotEmpty() && stages.all { it.isNotBlank() }) { "chainSpec.stages 不能为空" }
        sidecarSuffixes.forEach { suffix ->
            require(
                suffix.startsWith('.') &&
                    suffix.length in 2..64 &&
                    '/' !in suffix &&
                    '\\' !in suffix &&
                    ".." !in suffix &&
                    suffix.none { it <= ' ' || it == '\u007f' },
            ) { "chainSpec.sidecarSuffixes 项无效：$suffix" }
        }
    }

    /** 仅当脚本协议或成对读写目录标志真实支持“读父写子”物理分支隔离时返回 true。 */
    fun supportsPhysicalBranchIsolation(protocol: SubmitterInvocationProtocol?): Boolean =
        supportsIsolatedBranchDir && (
            protocol == SubmitterInvocationProtocol.PAYLOAD_JSON ||
                (readLatentDirFlag.isNotBlank() && writeLatentDirFlag.isNotBlank())
            )
}

@Serializable
data class ComfyWorkflow(
    val workflowId: String,
    val hostId: String,
    val name: String,
    val description: String = "",
    val mediaCategory: ComfyMediaCategory = ComfyMediaCategory.IMAGE,
    val submitterScriptId: String? = null,
    val rawWorkflowJson: JsonObject? = null,
    val outputNodeIds: List<String> = emptyList(),
    val executionMode: WorkflowExecutionMode = WorkflowExecutionMode.SINGLE,
    val chainSpec: StatefulChainSpec? = null,
    val fields: List<WorkflowFieldSpec> = emptyList(),
    val capabilities: MediaCapabilities = MediaCapabilities(modes = setOf(GenerationMode.TEXT)),
    val cliBaseArgs: List<String> = emptyList(),
    val hostFlag: String = "--host",
    val outputDirFlag: String = "--output-dir",
    val dryRunFlag: String = "--dry-run",
    val receiptFlag: String? = "--receipt",
    val emitWorkflowFlag: String? = null,
    val emitWorkflowTakesPath: Boolean = true,
    val preflightOnlyFlag: String? = null,
    val promptIdPattern: String? = null,
    val timeoutSeconds: Int? = null,
    val allowEmptyOutputs: Boolean = false,
    val backfillOutputsFromHistory: Boolean = true,
    val historyOutputFields: List<String> = emptyList(),
    val requiredTools: List<String> = emptyList(),
    val requiredPlatforms: List<String> = emptyList(),
    val fps: Double? = null,
    val frameBase: Int = 1,
    val frameStep: Int = 1,
    val maxFrames: Int? = null,
    val updatedAt: Long = 0L,
) {
    init {
        require(workflowId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "工作流 id 无效" }
        require(hostId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "主机 id 无效" }
        require(name.isNotBlank() && name.length <= 120) { "工作流名称需要 1～120 字符" }
        require(fields.distinctBy { it.key }.size == fields.size) { "工作流字段 key 不能重复" }
        require(executionMode != WorkflowExecutionMode.STATEFUL_CHAIN || chainSpec != null) {
            "有状态链式工作流必须声明 chainSpec"
        }
        require(capabilities.modes.isNotEmpty()) { "capabilities.modes 至少包含一种生成模式" }
        require(submitterScriptId != null || rawWorkflowJson != null) {
            "工作流须绑定提交器脚本或提供 API Format 工作流 JSON"
        }
        emitWorkflowFlag?.let { flag ->
            require(flag.isNotBlank() && flag.none { it <= ' ' || it == '\u007f' }) {
                "emitWorkflowFlag 不能包含空白或控制字符"
            }
        }
        preflightOnlyFlag?.let { flag ->
            require(flag.isNotBlank() && flag.none { it <= ' ' || it == '\u007f' }) {
                "preflightOnlyFlag 不能包含空白或控制字符"
            }
        }
        promptIdPattern?.let { pattern ->
            require(pattern.isNotBlank() && pattern.length <= 256) { "promptIdPattern 长度须在 1～256" }
            runCatching { Regex(pattern) }.getOrElse {
                throw IllegalArgumentException("promptIdPattern 正则无效：${it.message}")
            }
        }
        require(timeoutSeconds == null || timeoutSeconds in 10..7200) { "工作流超时须在 10～7200 秒" }
        requiredTools.forEach { tool ->
            require(tool.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "requiredTools 项无效：$tool" }
        }
        requiredPlatforms.forEach { platform ->
            require(platform.matches(Regex("[a-z0-9_-]{1,32}"))) { "requiredPlatforms 项须为小写标识：$platform" }
        }
        historyOutputFields.forEach { field ->
            require(field.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "historyOutputFields 项无效：$field" }
        }
        require(fps == null || (fps.isFinite() && fps > 0.0 && fps <= 240.0)) { "fps 须在 0～240 之间" }
        require(frameBase > 0 && frameStep > 0 && (maxFrames == null || maxFrames >= frameBase)) {
            "frameBase/frameStep/maxFrames 约束无效"
        }
    }

    val channelId: String get() = "comfy:$workflowId"

    fun contentFields(): List<WorkflowFieldSpec> = fields.filter { it.mutability == FieldMutability.CONTENT }

    fun fixedFields(): List<WorkflowFieldSpec> = fields.filter { it.mutability == FieldMutability.FIXED }

    fun field(key: String): WorkflowFieldSpec? = fields.firstOrNull { it.key == key }

    fun toMediaChannel(): MediaChannel = MediaChannel(
        id = channelId,
        group = mediaCategory.toMediaGroup(capabilities.modes),
        capabilities = capabilities,
    )

    fun detectFixedOverrides(fixedOverrides: Map<String, JsonElement>): List<WorkflowFixedOverrideDiff> {
        if (fixedOverrides.isEmpty()) return emptyList()
        val diffs = mutableListOf<WorkflowFixedOverrideDiff>()
        for ((key, newVal) in fixedOverrides) {
            val spec = field(key)
            if (spec == null && key == CHARACTER_REFERENCE_MODE_OVERRIDE_KEY) {
                require(GenerationMode.REFERENCE in capabilities.modes) {
                    "当前工作流未声明参考模式，不支持覆写 $key"
                }
                val parsed = parseCharacterReferenceModeOverride(newVal)
                if (parsed != capabilities.characterReferenceMode) {
                    diffs += WorkflowFixedOverrideDiff(
                        key = key,
                        label = "资产参考入参模式",
                        oldDisplayValue = displayCharacterReferenceMode(capabilities.characterReferenceMode),
                        newDisplayValue = displayCharacterReferenceMode(parsed),
                        newValue = JsonPrimitive(parsed.name.lowercase()),
                    )
                }
                continue
            }
            if (spec == null) throw IllegalArgumentException("未知工作流字段：$key")
            require(spec.mutability == FieldMutability.FIXED) {
                "字段 $key 属于内容字段，请传入 content_inputs 而非 fixed_overrides"
            }
            spec.validateValue(newVal)
            if (spec.isEffectivelyDifferentFromBaseline(newVal)) {
                diffs += WorkflowFixedOverrideDiff(
                    key = spec.key,
                    label = spec.label,
                    oldDisplayValue = spec.displayValue(spec.defaultValue),
                    newDisplayValue = spec.displayValue(newVal),
                    newValue = newVal,
                )
            }
        }
        return diffs
    }

    companion object {
        /** 工作流级参考入参模式保留覆写键：未显式建字段时也允许走一次性 fixed_overrides Diff 审批。 */
        const val CHARACTER_REFERENCE_MODE_OVERRIDE_KEY = "character_reference_mode"

        fun parseCharacterReferenceModeOverride(value: JsonElement): CharacterReferenceMode {
            val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()
                ?: throw IllegalArgumentException("$CHARACTER_REFERENCE_MODE_OVERRIDE_KEY 须为字符串 separate 或 composite")
            return when (raw) {
                "separate" -> CharacterReferenceMode.SEPARATE
                "composite", "sheet" -> CharacterReferenceMode.COMPOSITE
                else -> throw IllegalArgumentException("$CHARACTER_REFERENCE_MODE_OVERRIDE_KEY 仅支持 separate（分张单视图）或 composite（多视图整卡）")
            }
        }

        private fun displayCharacterReferenceMode(mode: CharacterReferenceMode): String = when (mode) {
            CharacterReferenceMode.SEPARATE -> "分张单视图 (separate)"
            CharacterReferenceMode.COMPOSITE -> "多视图整卡 (composite)"
        }

        /** 将历史 ComfyTemplate 无损投影为通用工作流视角，便于统一列表展示。 */
        fun fromLegacyTemplate(template: ComfyTemplate): ComfyWorkflow {
            val fields = buildList {
                template.slots.filter { it.kind != SlotKind.OUTPUT }.forEach { slot ->
                    val existingInput = (((template.workflow[slot.nodeId] as? JsonObject)
                        ?.get("inputs") as? JsonObject)?.get(slot.input))
                    when (slot.kind) {
                        SlotKind.PROMPT -> add(
                            WorkflowFieldSpec(
                                key = "prompt",
                                label = "提示词",
                                mutability = FieldMutability.CONTENT,
                                valueType = FieldValueType.TEXT,
                                required = template.role != TemplateRole.DERIVE,
                                defaultValue = existingInput,
                                semanticRole = FieldSemanticRole.PROMPT,
                                nodeId = slot.nodeId,
                                inputKey = slot.input,
                            ),
                        )
                        SlotKind.REFERENCE_IMAGES -> add(
                            WorkflowFieldSpec(
                                key = "reference_images_${slot.nodeId}",
                                label = "参考图",
                                mutability = FieldMutability.CONTENT,
                                valueType = if (slot.capacity > 1) FieldValueType.FILE_LIST else FieldValueType.IMAGE_FILE,
                                semanticRole = FieldSemanticRole.REFERENCE_IMAGES,
                                nodeId = slot.nodeId,
                                inputKey = slot.input,
                                uploadNodeId = slot.uploadTargets.firstOrNull()?.nodeId,
                                uploadInputKey = slot.uploadTargets.firstOrNull()?.input,
                                uploadTargets = slot.uploadTargets,
                            ),
                        )
                        SlotKind.FIRST_FRAME -> add(
                            WorkflowFieldSpec(
                                key = "first_frame",
                                label = "首帧图",
                                mutability = FieldMutability.CONTENT,
                                valueType = FieldValueType.IMAGE_FILE,
                                semanticRole = FieldSemanticRole.FIRST_FRAME,
                                nodeId = slot.nodeId,
                                inputKey = slot.input,
                                uploadNodeId = slot.uploadTargets.firstOrNull()?.nodeId,
                                uploadInputKey = slot.uploadTargets.firstOrNull()?.input,
                                uploadTargets = slot.uploadTargets,
                            ),
                        )
                        SlotKind.LAST_FRAME -> add(
                            WorkflowFieldSpec(
                                key = "last_frame",
                                label = "尾帧图",
                                mutability = FieldMutability.CONTENT,
                                valueType = FieldValueType.IMAGE_FILE,
                                semanticRole = FieldSemanticRole.LAST_FRAME,
                                nodeId = slot.nodeId,
                                inputKey = slot.input,
                                uploadNodeId = slot.uploadTargets.firstOrNull()?.nodeId,
                                uploadInputKey = slot.uploadTargets.firstOrNull()?.input,
                                uploadTargets = slot.uploadTargets,
                            ),
                        )
                        SlotKind.REFERENCE_AUDIO -> add(
                            WorkflowFieldSpec(
                                key = "reference_audio_${slot.nodeId}",
                                label = "参考音频",
                                mutability = FieldMutability.CONTENT,
                                valueType = if (slot.capacity > 1) FieldValueType.FILE_LIST else FieldValueType.AUDIO_FILE,
                                semanticRole = FieldSemanticRole.AUDIO_REF,
                                nodeId = slot.nodeId,
                                inputKey = slot.input,
                                uploadNodeId = slot.uploadTargets.firstOrNull()?.nodeId,
                                uploadInputKey = slot.uploadTargets.firstOrNull()?.input,
                                uploadTargets = slot.uploadTargets,
                            ),
                        )
                        SlotKind.INPUT_VIDEO -> add(
                            WorkflowFieldSpec(
                                key = "input_video",
                                label = "输入视频",
                                mutability = FieldMutability.CONTENT,
                                valueType = FieldValueType.VIDEO_FILE,
                                required = template.role == TemplateRole.DERIVE,
                                semanticRole = FieldSemanticRole.INPUT_VIDEO,
                                nodeId = slot.nodeId,
                                inputKey = slot.input,
                                uploadNodeId = slot.uploadTargets.firstOrNull()?.nodeId,
                                uploadInputKey = slot.uploadTargets.firstOrNull()?.input,
                                uploadTargets = slot.uploadTargets,
                            ),
                        )
                        SlotKind.DURATION -> add(
                            WorkflowFieldSpec(
                                key = "duration_seconds",
                                label = "时长（秒）",
                                mutability = FieldMutability.CONTENT,
                                valueType = FieldValueType.FLOAT,
                                defaultValue = existingInput,
                                semanticRole = FieldSemanticRole.DURATION_SECONDS,
                                nodeId = slot.nodeId,
                                inputKey = slot.input,
                            ),
                        )
                        SlotKind.WIDTH -> add(
                            WorkflowFieldSpec(
                                key = "width",
                                label = "宽度",
                                mutability = FieldMutability.CONTENT,
                                valueType = FieldValueType.INT,
                                defaultValue = existingInput,
                                semanticRole = FieldSemanticRole.WIDTH,
                                nodeId = slot.nodeId,
                                inputKey = slot.input,
                            ),
                        )
                        SlotKind.HEIGHT -> add(
                            WorkflowFieldSpec(
                                key = "height",
                                label = "高度",
                                mutability = FieldMutability.CONTENT,
                                valueType = FieldValueType.INT,
                                defaultValue = existingInput,
                                semanticRole = FieldSemanticRole.HEIGHT,
                                nodeId = slot.nodeId,
                                inputKey = slot.input,
                            ),
                        )
                        SlotKind.FRAMES -> add(
                            WorkflowFieldSpec(
                                key = "frames",
                                label = "总帧数",
                                mutability = FieldMutability.CONTENT,
                                valueType = FieldValueType.INT,
                                defaultValue = existingInput,
                                semanticRole = FieldSemanticRole.FRAMES,
                                nodeId = slot.nodeId,
                                inputKey = slot.input,
                            ),
                        )
                        SlotKind.SEED -> add(
                            WorkflowFieldSpec(
                                key = "seed",
                                label = "随机种子",
                                mutability = FieldMutability.CONTENT,
                                valueType = FieldValueType.INT,
                                defaultValue = existingInput,
                                semanticRole = FieldSemanticRole.SEED,
                                nodeId = slot.nodeId,
                                inputKey = slot.input,
                            ),
                        )
                        SlotKind.PARAM -> {
                            val p = template.params.firstOrNull { it.key == slot.param }
                            if (p != null) {
                                add(
                                    WorkflowFieldSpec(
                                        key = p.key,
                                        label = p.label,
                                        mutability = FieldMutability.FIXED,
                                        valueType = when (p.type) {
                                            TemplateParamType.ENUM -> FieldValueType.ENUM
                                            TemplateParamType.NUMBER -> if (p.integer) FieldValueType.INT else FieldValueType.FLOAT
                                            TemplateParamType.BOOLEAN -> FieldValueType.BOOL
                                        },
                                        defaultValue = p.default,
                                        options = p.options,
                                        min = p.min,
                                        max = p.max,
                                        nodeId = slot.nodeId,
                                        inputKey = slot.input,
                                    ),
                                )
                            }
                        }
                        SlotKind.OUTPUT -> Unit
                    }
                }
            }.distinctBy { it.key }
            return ComfyWorkflow(
                workflowId = template.id,
                hostId = template.hostId,
                name = template.name,
                mediaCategory = ComfyMediaCategory.fromMediaGroup(
                    template.group,
                    isPostProcess = template.role == TemplateRole.DERIVE,
                ),
                submitterScriptId = null,
                rawWorkflowJson = template.workflow,
                outputNodeIds = template.slots.filter { it.kind == SlotKind.OUTPUT }.map { it.nodeId }.distinct(),
                executionMode = WorkflowExecutionMode.SINGLE,
                fields = fields,
                capabilities = template.channel().capabilities,
                fps = template.fps,
                frameBase = template.frameBase,
                frameStep = template.frameStep,
                maxFrames = template.maxFrames,
            )
        }
    }
}

data class ComfyWorkflowState(
    val workflow: ComfyWorkflow,
    val preflight: PreflightResult = PreflightResult(PreflightStatus.REVIEW, uncertain = listOf("尚未预检")),
    val checkedAt: Long? = null,
)

data class WorkflowFixedOverrideDiff(
    val key: String,
    val label: String,
    val oldDisplayValue: String,
    val newDisplayValue: String,
    val newValue: JsonElement,
)

@Serializable
enum class ChainNodeValidity {
    ACTIVE,
    OVERWRITTEN_BY_SIBLING,
    EXPIRED,
}

@Serializable
data class StatefulChainNode(
    val nodeId: String,
    val parentNodeId: String? = null,
    val stage: String = "first",
    val segmentIndex: Int = 1,
    val attemptLabel: String = "v1",
    val branchId: String = "main",
    val branchPolicy: StatefulBranchPolicy = StatefulBranchPolicy.ISOLATED_DIR,
    val outputMediaPath: String,
    val startSeconds: Double? = null,
    val endSeconds: Double? = null,
    val frames: Int? = null,
    val promptSummary: String = "",
    val contentInputsJson: JsonObject = JsonObject(emptyMap()),
    val fixedOverridesJson: JsonObject = JsonObject(emptyMap()),
    val continuationStateJson: JsonObject = JsonObject(emptyMap()),
    val assignedReadLatentDir: String? = null,
    val assignedWriteLatentDir: String? = null,
    val isolationWarning: String? = null,
    val serverLatentSlotKey: String? = null,
    val stateValidity: ChainNodeValidity = ChainNodeValidity.ACTIVE,
    val createdAt: Long = 0L,
) {
    init {
        require(nodeId.isNotBlank()) { "chain nodeId 不能为空" }
        require(segmentIndex >= 1) { "segmentIndex 须 >= 1" }
        require(outputMediaPath.isNotBlank()) { "outputMediaPath 不能为空" }
    }
}

@Serializable
data class StatefulChainRecord(
    val chainId: String,
    val sessionId: String,
    val workflowId: String,
    val hostId: String,
    val shotRef: String? = null,
    val rootNodeId: String,
    val activeTipNodeId: String,
    val nodes: List<StatefulChainNode>,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    init {
        require(chainId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "chainId 无效" }
        require(sessionId.isNotBlank()) { "sessionId 不能为空" }
        require(nodes.isNotEmpty()) { "链记录至少包含一个节点" }
    }

    fun node(nodeId: String): StatefulChainNode? = nodes.firstOrNull { it.nodeId == nodeId }

    fun activeNodes(): List<StatefulChainNode> = nodes.filter { it.stateValidity == ChainNodeValidity.ACTIVE }

    fun inheritedContextForParent(parentNodeId: String): JsonObject {
        val parent = requireNotNull(node(parentNodeId)) { "父节点不存在：$parentNodeId" }
        require(parent.stateValidity == ChainNodeValidity.ACTIVE) {
            "节点 ${parent.nodeId} 的服务端潜空间已失效（${parent.stateValidity}），不能从此段继续；视频仍可回放，请从更早有效段重试"
        }
        return JsonObject(
            parent.continuationStateJson.toMutableMap().apply {
                put("parent_node_id", JsonPrimitive(parent.nodeId))
                put("parent_segment_index", JsonPrimitive(parent.segmentIndex))
                put("parent_attempt_label", JsonPrimitive(parent.attemptLabel))
                put("parent_output_media_path", JsonPrimitive(parent.outputMediaPath))
            },
        )
    }
}

/** 有状态链 DAG 状态转换结果；[invalidatedNodeIds] 为被原位覆写标记失效的兄弟及下游节点 ID。 */
data class StatefulChainAppendResult(
    val record: StatefulChainRecord,
    val appendedNode: StatefulChainNode,
    val invalidatedNodeIds: List<String>,
)

/**
 * 不可变节点树状态机：支持任意 `ACTIVE` 中段分叉与同层重试；
 * 在 `IN_PLACE_OVERWRITE` 策略下自动失效同槽位兄弟节点及全部下游后继节点（保留视频产物路径）。
 */
object StatefulChainDag {
    fun nextAttemptLabel(existingNodes: List<StatefulChainNode>, parentNodeId: String?, segmentIndex: Int): String {
        val count = existingNodes.count { it.segmentIndex == segmentIndex && (parentNodeId == null || it.parentNodeId == parentNodeId || segmentIndex > 1) }
        return "v${count + 1}"
    }

    fun appendNode(
        existing: StatefulChainRecord?,
        sessionId: String,
        workflowId: String,
        hostId: String,
        chainId: String,
        shotRef: String?,
        newNode: StatefulChainNode,
    ): StatefulChainAppendResult {
        if (existing == null) {
            require(newNode.parentNodeId == null && newNode.segmentIndex == 1 && newNode.stage == "first") {
                "首段节点必须无父节点且 segmentIndex=1、stage=first"
            }
            val created = StatefulChainRecord(
                chainId = chainId,
                sessionId = sessionId,
                workflowId = workflowId,
                hostId = hostId,
                shotRef = shotRef,
                rootNodeId = newNode.nodeId,
                activeTipNodeId = newNode.nodeId,
                nodes = listOf(newNode),
                createdAt = newNode.createdAt,
                updatedAt = newNode.createdAt,
            )
            return StatefulChainAppendResult(created, newNode, emptyList())
        }
        require(existing.chainId == chainId && existing.workflowId == workflowId) {
            "链 ID 或工作流不匹配"
        }
        val parentId = requireNotNull(newNode.parentNodeId) { "续接或重试节点必须指定 parentNodeId" }
        val parent = requireNotNull(existing.node(parentId)) { "父节点不存在：$parentId" }
        require(parent.stateValidity == ChainNodeValidity.ACTIVE) {
            "父节点 $parentId 潜空间状态为 ${parent.stateValidity}，无法继续生成；请改选仍为 ACTIVE 的前驱段"
        }
        require(newNode.segmentIndex == parent.segmentIndex + 1) {
            "新节点 segmentIndex(${newNode.segmentIndex}) 必须等于父节点段号+1(${parent.segmentIndex + 1})"
        }
        require(existing.node(newNode.nodeId) == null) { "节点 ${newNode.nodeId} 已存在" }

        val ancestorIds = buildSet {
            var cur: String? = parentId
            while (cur != null && add(cur)) {
                cur = existing.node(cur)?.parentNodeId
            }
        }
        val invalidatedIds = mutableSetOf<String>()
        val slotKey = newNode.serverLatentSlotKey?.takeIf { it.isNotBlank() }
        if (slotKey != null) {
            val directOverwritten = existing.nodes.filter {
                it.nodeId !in ancestorIds &&
                    it.serverLatentSlotKey == slotKey &&
                    it.stateValidity == ChainNodeValidity.ACTIVE
            }.mapTo(mutableSetOf()) { it.nodeId }
            invalidatedIds += directOverwritten
            // 同时失效所有依赖于被覆写兄弟节点潜空间的后继分支，防止用旧链节点继续接出废片；绝不误杀父/祖先链。
            var added: Boolean
            do {
                added = false
                existing.nodes.forEach { candidate ->
                    if (candidate.nodeId !in ancestorIds &&
                        candidate.parentNodeId in invalidatedIds &&
                        candidate.stateValidity == ChainNodeValidity.ACTIVE &&
                        invalidatedIds.add(candidate.nodeId)
                    ) {
                        added = true
                    }
                }
            } while (added)
        }
        val effectiveNode = if (invalidatedIds.isNotEmpty() && newNode.branchPolicy == StatefulBranchPolicy.ISOLATED_DIR) {
            val note = "检测到与既有活跃兄弟节点槽位冲突（$slotKey），已诚实降级为 IN_PLACE_OVERWRITE 并失效：${invalidatedIds.sorted().joinToString("、")}"
            val mergedWarning = listOfNotNull(newNode.isolationWarning?.takeIf { it.isNotBlank() }, note).distinct().joinToString("；")
            newNode.copy(branchPolicy = StatefulBranchPolicy.IN_PLACE_OVERWRITE, isolationWarning = mergedWarning)
        } else {
            newNode
        }

        val updatedNodes = existing.nodes.map { old ->
            if (old.nodeId in invalidatedIds) old.copy(stateValidity = ChainNodeValidity.OVERWRITTEN_BY_SIBLING) else old
        } + effectiveNode

        val nextRecord = existing.copy(
            activeTipNodeId = effectiveNode.nodeId,
            nodes = updatedNodes,
            updatedAt = effectiveNode.createdAt,
        )
        return StatefulChainAppendResult(nextRecord, effectiveNode, invalidatedIds.toList())
    }

    fun setActiveTip(record: StatefulChainRecord, targetNodeId: String, now: Long): StatefulChainRecord {
        val target = requireNotNull(record.node(targetNodeId)) { "节点不存在：$targetNodeId" }
        require(target.stateValidity == ChainNodeValidity.ACTIVE) {
            "仅可将潜空间仍为 ACTIVE 的节点设为当前主链末端"
        }
        return record.copy(activeTipNodeId = targetNodeId, updatedAt = now)
    }
}

internal fun isSafeRelativeSubPath(path: String): Boolean {
    if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.contains(':')) return false
    if (path.any { it < ' ' || it == '\u007f' }) return false
    val segments = path.split('/')
    return segments.all { it.isNotEmpty() && it !in setOf(".", "..") }
}
