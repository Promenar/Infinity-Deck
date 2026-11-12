package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.InputKind
import com.promenar.infinity.domain.mediagen.PromptFormat
import java.util.Locale

/** 保留分镜的可见事实；图片编号和实际入参顺序共用同一份打包结果。 */
object ShotPromptCompiler {
    fun compile(production: Production, shot: Shot, inputs: List<PlannedInput>, format: PromptFormat): String {
        val images = inputs.filter { it.kind == InputKind.IMAGE }
        val legends = images.map { "图${it.number}：${it.title}${it.detail.takeIf(String::isNotBlank)?.let { d -> "（$d）" }.orEmpty()}" }
        val audio = inputs.filter { it.kind == InputKind.AUDIO }.mapIndexed { i, it -> "音频${i + 1}：${it.title}" }
        val picture = listOfNotNull(shot.size, shot.camera, shot.cameraMovement, shot.visual?.takeIf { it.isNotBlank() } ?: shot.title, shot.performance).filter(String::isNotBlank).joinToString("，")
        val nativeSpeakers = shot.dialogue.filter { it.audioSource == DialogueAudioSource.NATIVE }.map { it.characterAssetId }.distinct()
        val lines = shot.dialogue.filter { it.audioSource == DialogueAudioSource.NATIVE }.joinToString(" ") { line ->
            val name = production.assets.firstOrNull { it.id == line.characterAssetId }?.name ?: line.characterAssetId
            val delivery = line.tone?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            if (format == PromptFormat.H3) {
                val speaker = nativeSpeakers.indexOf(line.characterAssetId) + 1
                val language = if (line.text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) "Chinese" else "Original"
                "$name (S$speaker)$delivery 说：<d>[$language] ${line.text}</d>"
            } else "$name${delivery}说：“${line.text}”"
        }
        val sound = shot.sound?.takeIf { it.isNotBlank() } ?: "遵循画面动作与环境的自然声音"
        val specs = listOfNotNull(production.aspectRatio, "${shot.durationSeconds} 秒").joinToString("，")
        val continuity = listOfNotNull(shot.continuity?.let { "衔接：$it" }, shot.transition?.let { "转场：$it" }).joinToString("。")
        val body = listOf(picture, lines, specs, continuity).filter(String::isNotBlank).joinToString("。")
        return when (format) {
            PromptFormat.SEEDANCE -> (legends + audio).joinToString("；").let { if (it.isEmpty()) "" else "$it。\n" } + "$body\n声音：$sound。"
            PromptFormat.GENERIC -> listOf((legends + audio).joinToString("；"), body, "声音：$sound。").filter(String::isNotBlank).joinToString("\n")
            PromptFormat.H3 -> {
                // 字段顺序依据官方 H3 base-en.txt / ref-en.txt；中文内容沿用分镜原文。
                if (shot.mode == ShotMode.REFERENCE) {
                    val definitions = images.map { "<Subject ${it.number}> 来自 <Picture ${it.number}>（图${it.number}）：${it.title}，${it.detail}。" } +
                        audio.mapIndexed { i, label -> "<Audio ${i + 1}>：$label，用作对应角色的音色参考。" }
                    "subject_definitions:\n${definitions.joinToString("\n")}\n\nsummary:\n$body\n\nretention_analysis:\n${legends.joinToString("；")}。按各输入职责保留人物造型、环境、道具或关键帧构图。\n\ndetailed_description:\n[Shot 1] $body\n\noverall_soundscape:\n$sound${if (lines.isNotEmpty()) "，对白清晰" else ""}\n\nnon_diegetic_music:\n无"
                } else {
                    val alignment = when (shot.mode) {
                        ShotMode.FIRST_FRAME -> "图1（<Picture 1>）在 0.00 秒完整作为 [Shot 1] 首帧。\n\n"
                        ShotMode.FIRST_LAST -> "图1（<Picture 1>）对应 0.00 秒首帧，图2（<Picture 2>）对应 ${String.format(Locale.ROOT, "%.2f", shot.durationSeconds)} 秒尾帧，镜头连续发展至尾帧。\n\n"
                        else -> ""
                    }
                    "${alignment}integrated_multimodal_description:\n[Shot 1] ${legends.joinToString("；")} $body\n\noverall_soundscape:\n$sound${if (lines.isNotEmpty()) "，对白清晰" else ""}\n\nnon_diegetic_music:\n无"
                }
            }
        }
    }
}
