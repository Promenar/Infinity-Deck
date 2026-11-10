package com.promenar.infinity.data.pet

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class PetV03PreferenceTest {
    @Test fun `默认值和偏好读写`() {
        val values = mutableMapOf<String, Any>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>(); editor }
        every { editor.putInt(any(), any()) } answers { values[firstArg()] = secondArg<Int>(); editor }
        every { editor.putLong(any(), any()) } answers { values[firstArg()] = secondArg<Long>(); editor }
        every { editor.putFloat(any(), any()) } answers { values[firstArg()] = secondArg<Float>(); editor }
        every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg<String>(); editor }
        every { editor.remove(any()) } answers { values.remove(firstArg<String>()); editor }
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
        every { prefs.getInt(any(), any()) } answers { values[firstArg()] as? Int ?: secondArg() }
        every { prefs.getLong(any(), any()) } answers { values[firstArg()] as? Long ?: secondArg() }
        every { prefs.getFloat(any(), any()) } answers { values[firstArg()] as? Float ?: secondArg() }
        every { prefs.getString(any(), any()) } answers { values[firstArg()] as? String ?: secondArg() }
        every { prefs.contains(any()) } answers { values.containsKey(firstArg<String>()) }
        every { prefs.edit() } returns editor
        val context = mockk<Context>(); every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val store = PetPreferenceStore(context)
        assertThat(store.dismissed.value).isFalse(); assertThat(store.dismissHintCount.value).isEqualTo(0)
        assertThat(store.flingGestureEnabled.value).isTrue(); assertThat(store.motionSensorEnabled.value).isTrue()
        assertThat(store.hapticsEnabled.value).isTrue(); assertThat(store.blowEnabled.value).isFalse()
        assertThat(store.perchKind.value).isEqualTo("cloud"); assertThat(store.perch.value).isNull()
        assertThat(store.mood.value).isEqualTo(PetMoodSnapshot(0f, 0L))
        store.setDismissed(true); store.setDismissHintCount(2); store.setFlingGestureEnabled(false)
        store.setMotionSensorEnabled(false); store.setHapticsEnabled(false); store.setBlowEnabled(true)
        store.setPerchKind("bubble"); store.setPerch(PetPerchPlacement(0.4f, 80f)); store.setMood(15f, 12L)
        val restored = PetPreferenceStore(context)
        assertThat(restored.dismissed.value).isTrue(); assertThat(restored.dismissHintCount.value).isEqualTo(2)
        assertThat(restored.flingGestureEnabled.value).isFalse(); assertThat(restored.motionSensorEnabled.value).isFalse()
        assertThat(restored.hapticsEnabled.value).isFalse(); assertThat(restored.blowEnabled.value).isTrue()
        assertThat(restored.perchKind.value).isEqualTo("bubble"); assertThat(restored.perch.value).isEqualTo(PetPerchPlacement(0.4f, 80f))
        assertThat(restored.mood.value).isEqualTo(PetMoodSnapshot(15f, 12L))
        restored.setPerchKind("bad"); assertThat(PetPreferenceStore(context).perchKind.value).isEqualTo("cloud")
        values["pet_perch_kind"] = "bad"; assertThat(PetPreferenceStore(context).perchKind.value).isEqualTo("cloud")
        restored.setPerch(null); assertThat(PetPreferenceStore(context).perch.value).isNull()
    }
}
