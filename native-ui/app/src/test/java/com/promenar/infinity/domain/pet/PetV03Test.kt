package com.promenar.infinity.domain.pet

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PetV03Test {
    private val base = PetManifest("0.3", "fish", "鱼", "1", license = PetLicense("许可", true, false),
        display = PetDisplay(120, "right"), clips = mapOf("a" to PetClip("pose", "a.png", listOf(0.5, 1.0))),
        map = mapOf("idle" to PetReaction(clip = "a")))
    private val files = listOf(PetFile("manifest.json", 1), PetFile("thumbnail.webp", 1, 1, 1), PetFile("a.png", 1, 1, 1), PetFile("a.txt", 1))
    private fun issues(m: PetManifest) = PetPackageValidator().validate(m, null, files)
    private fun resolved(keys: List<String>, signal: String, q: PetQualifiers = PetQualifiers.NONE) =
        PetSignalResolver.resolve(base.copy(map = keys.associateWith { PetReaction() }), signal, q)?.signal

    @Test fun `版本与新增字段解析`() {
        val json = """{"contract":"0.3","id":"fish","name":"鱼","version":"1","license":{"name":"许可","redistribute":true,"commercial":false},"display":{"height_dp":120,"facing":"right"},"clips":{},"map":{"idle":{"haptic":"tick"}},"zones":{"head":{"center":[0.5,0.3],"radius":[0.2,0.1]}},"props":{"perch":{"cloud":{"file":"a.png","seat":[0.5,0.4],"width_dp":120,"appear":"a","vanish":"a"}}}}"""
        val parsed = PetParser.parseManifest(json).getOrThrow()
        assertThat(parsed.zones.getValue("head").center).containsExactly(0.5, 0.3).inOrder()
        assertThat(parsed.props.perch.getValue("cloud").widthDp).isEqualTo(120)
        assertThat(parsed.map.getValue("idle").haptic).isEqualTo("tick")
        val old = PetParser.parseManifest(json.replace("\"contract\":\"0.3\"", "\"contract\":\"0.2\"").replace("\"haptic\":\"tick\"", "\"clip\":\"a\"").substringBefore(",\"zones\"") + "}").getOrThrow()
        assertThat(old.zones).isEmpty(); assertThat(old.props.perch).isEmpty(); assertThat(old.map.getValue("idle").haptic).isNull()
    }
    @Test fun `校验新字段与版本`() {
        val valid = base.copy(zones = mapOf("head" to PetZone(listOf(0.0, 1.0), listOf(0.2, 0.2))),
            props = PetProps(mapOf("cloud" to PetPerchProp("a.png", listOf(0.5, 0.5), 120, "a", "a"))),
            map = base.map + ("idle@perch" to PetReaction(haptic = "tick")))
        listOf("0.2", "0.3", "0.99").forEach { assertThat(issues(valid.copy(contract = it))).isEmpty() }
        val suffixes = listOf("perch", "message", "stage", "long", "verylong", "mood.high", "mood.low")
        suffixes.forEach { suffix -> assertThat(issues(valid.copy(map = valid.map + ("task.done@$suffix" to PetReaction(haptic = "buzz"))))).isEmpty() }
        listOf(60, 200).forEach { width -> assertThat(issues(valid.copy(props = PetProps(mapOf("bubble" to PetPerchProp(widthDp = width)))))).isEmpty() }
        val invalid = listOf(
            valid.copy(zones = mapOf("ear" to PetZone(listOf(0.5, 0.5), listOf(0.1, 0.1)))),
            valid.copy(zones = mapOf("head" to PetZone(listOf(0.5), listOf(0.1, 0.1)))),
            valid.copy(zones = mapOf("head" to PetZone(listOf(0.5, 0.5), listOf(1.1, 0.1)))),
            valid.copy(props = PetProps(mapOf("chair" to PetPerchProp()))),
            valid.copy(props = PetProps(mapOf("cloud" to PetPerchProp(file = "missing.png")))),
            valid.copy(props = PetProps(mapOf("cloud" to PetPerchProp(file = "a.txt")))),
            valid.copy(props = PetProps(mapOf("cloud" to PetPerchProp(seat = listOf(-0.1, 0.2))))),
            valid.copy(props = PetProps(mapOf("cloud" to PetPerchProp(widthDp = 59)))),
            valid.copy(props = PetProps(mapOf("cloud" to PetPerchProp(widthDp = 201)))),
            valid.copy(props = PetProps(mapOf("cloud" to PetPerchProp(seat = listOf(0.5))))),
            valid.copy(props = PetProps(mapOf("cloud" to PetPerchProp(appear = "missing")))),
            valid.copy(props = PetProps(mapOf("cloud" to PetPerchProp(vanish = "missing")))),
            valid.copy(map = base.map + ("idle@bad" to PetReaction())),
            valid.copy(map = base.map + ("idle@perch@long" to PetReaction())),
            valid.copy(map = base.map + ("task.done" to PetReaction(haptic = "bad"))),
        )
        invalid.forEach { assertThat(issues(it)).isNotEmpty() }
        assertThat(issues(base.copy(map = mapOf("idle@perch" to PetReaction()))).map { it.code }).contains(PetIssueCode.MISSING_IDLE)
    }
    @Test fun `限定与工具回退顺序`() {
        val q = PetQualifiers("perch", "verylong", "mood.high")
        val keys = listOf("task.tool.read_file@perch", "task.tool.read_file@verylong", "task.tool.read_file@long", "task.tool.read_file@mood.high", "task.tool.read_file", "task.tool.read", "task.tool", "task.working", "idle")
        keys.indices.forEach { assertThat(resolved(keys.drop(it), "task.tool.read_file", q)).isEqualTo(keys[it]) }
        listOf("task.tool.read", "task.tool", "task.working", "idle").forEach { key ->
            assertThat(resolved(listOf("$key@perch"), "task.tool.read_file", q)).isEqualTo("$key@perch")
        }
        assertThat(PetSignalResolver.resolve(base, "task.tool.unknown")?.signal).isEqualTo("idle")
    }
    @Test fun `事件回退不进入空闲`() {
        val m = base.copy(map = mapOf("touch.tap@perch" to PetReaction(), "touch.poke" to PetReaction(), "idle" to PetReaction()))
        for (part in listOf("head", "belly", "tail")) assertThat(PetSignalResolver.resolveEvent(m, "touch.poke.$part")?.signal).isEqualTo("touch.poke")
        for (signal in listOf("touch.pet", "touch.annoyed")) assertThat(PetSignalResolver.resolveEvent(m, signal, PetQualifiers(place = "perch"))?.signal).isEqualTo("touch.tap@perch")
        val tapOnly = base.copy(map = mapOf("touch.tap" to PetReaction()))
        for (part in listOf("head", "belly", "tail")) assertThat(PetSignalResolver.resolveEvent(tapOnly, "touch.poke.$part")?.signal).isEqualTo("touch.tap")
        assertThat(PetSignalResolver.resolveEvent(m, "role.summon")).isNull()
        assertThat(PetSignalResolver.resolveEvent(base.copy(map = mapOf("touch.tap" to PetReaction())), "touch.poke")).isNull()
    }
    @Test fun `作息四段与限定顺序`() {
        mapOf(5 to "night", 6 to "morning", 10 to "morning", 11 to "day", 17 to "day", 18 to "evening", 22 to "evening", 23 to "night", 0 to "night")
            .forEach { (hour, part) -> assertThat(PetDaypart.of(hour)).isEqualTo(part) }
        val q = PetQualifiers(mood = "mood.low", daypart = "night")
        assertThat(resolved(listOf("idle@mood.low", "idle@night", "idle"), "idle", q)).isEqualTo("idle@mood.low")
        assertThat(resolved(listOf("idle@night", "idle@morning", "idle"), "idle", q)).isEqualTo("idle@night")
        assertThat(resolved(listOf("idle@morning", "idle"), "idle", q)).isEqualTo("idle")
        assertThat(resolved(listOf("task.working", "idle@night"), "task.working", q)).isEqualTo("task.working")
        PetDaypart.all.forEach { assertThat(issues(base.copy(map = base.map + ("idle@$it" to PetReaction())))).isEmpty() }
        assertThat(issues(base.copy(map = base.map + ("idle@noon" to PetReaction())))).isNotEmpty()
    }
    @Test fun `时长和工具类别`() {
        assertThat(PetQualifiers.durationOf(59.9f)).isNull(); assertThat(PetQualifiers.durationOf(60f)).isEqualTo("long")
        assertThat(PetQualifiers.durationOf(299f)).isEqualTo("long"); assertThat(PetQualifiers.durationOf(300f)).isEqualTo("verylong")
        mapOf("read_file" to "read", "write_file" to "write", "web_search" to "search", "web_fetch" to "web", "shell_exec" to "shell", "generate_image" to "image", "schedule_create" to "schedule", "agent_task" to "subagent", "unknown" to "other").forEach { (name, category) -> assertThat(PetToolCategories.categoryOf(name)).isEqualTo(category) }
    }
    @Test fun `心情事件上下限回归和阈值`() {
        for (event in listOf(PetMoodEvent.TASK_DONE, PetMoodEvent.PETTED)) { val m = PetMood(); m.apply(event); assertThat(m.value).isGreaterThan(0f) }
        for (event in listOf(PetMoodEvent.TASK_ERROR, PetMoodEvent.HARD_LANDING, PetMoodEvent.ANNOYED, PetMoodEvent.DISMISSED)) { val m = PetMood(); m.apply(event); assertThat(m.value).isLessThan(0f) }
        assertThat(PetMood(200f).value).isEqualTo(100f); assertThat(PetMood(-200f).value).isEqualTo(-100f)
        val high = PetMood(99f); high.apply(PetMoodEvent.TASK_DONE); assertThat(high.value).isEqualTo(100f)
        val low = PetMood(-99f); low.apply(PetMoodEvent.TASK_ERROR); assertThat(low.value).isEqualTo(-100f)
        val m = PetMood(50f); m.decay(3600f); assertThat(m.value).isWithin(0.001f).of(30f); m.decay(100000f); assertThat(m.value).isEqualTo(0f)
        val negative = PetMood(-50f); negative.decay(3600f); assertThat(negative.value).isWithin(0.001f).of(-30f)
        assertThat(PetMood(40f).qualifier).isNull(); assertThat(PetMood(41f).dataValue).isEqualTo("high")
        assertThat(PetMood(-40f).dataValue).isEqualTo("normal"); assertThat(PetMood(-41f).qualifier).isEqualTo("mood.low")
    }
}
