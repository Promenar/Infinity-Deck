package com.promenar.infinity.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LegacyDatabasePromoterCurrentSchemaTest {
    @Test
    fun `健康v38当前库不因无效历史库被阻断且来源文件保持不变`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("infinity.db")
        context.deleteDatabase("infinity_v2.db")
        try {
            val legacy = context.getDatabasePath("infinity.db").apply { parentFile!!.mkdirs() }
            legacy.writeText("这不是有效的 SQLite 数据库\n")
            val current = context.getDatabasePath("infinity_v2.db")
            val room = Room.databaseBuilder(context, InfinityDatabase::class.java, current.name)
                .allowMainThreadQueries().build()
            try {
                val sqlite = room.openHelper.writableDatabase
                assertThat(sqlite.version).isEqualTo(38)
                sqlite.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                    check(it.moveToFirst())
                    assertThat(it.getString(0)).isEqualTo(com.promenar.infinity.data.local.db.recovery.CURRENT_V38_IDENTITY)
                }
            } finally { room.close() }
            val legacyBytes = legacy.readBytes()
            val currentBytes = current.readBytes()

            assertThat(LegacyDatabasePromoter.promote(context))
                .isEqualTo(LegacyDatabasePromotionResult.CurrentDatabaseExists)

            assertThat(legacy.readBytes()).isEqualTo(legacyBytes)
            assertThat(current.readBytes()).isEqualTo(currentBytes)
        } finally {
            context.deleteDatabase("infinity.db")
            context.deleteDatabase("infinity_v2.db")
        }
    }
}
