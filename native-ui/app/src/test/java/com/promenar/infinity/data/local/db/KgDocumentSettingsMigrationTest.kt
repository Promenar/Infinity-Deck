package com.promenar.infinity.data.local.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KgDocumentSettingsMigrationTest {
    @Test fun `迁移创建文档设置表并保留原数据`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(36) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE example (id TEXT PRIMARY KEY)")
                    db.execSQL("INSERT INTO example VALUES ('fixture')")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            MIGRATION_36_37_DOCUMENT_SETTINGS.migrate(db)
            db.execSQL("INSERT INTO kg_document_settings VALUES ('doc','COLLECTION','work:fixture')")
            assertThat(db.query("SELECT target_collection_id FROM kg_document_settings WHERE doc_id='doc'").use { it.moveToFirst(); it.getString(0) }).isEqualTo("work:fixture")
            assertThat(db.query("SELECT id FROM example").use { it.moveToFirst(); it.getString(0) }).isEqualTo("fixture")
        }
    }
}
