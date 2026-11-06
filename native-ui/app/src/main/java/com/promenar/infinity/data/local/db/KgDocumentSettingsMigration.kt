package com.promenar.infinity.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_36_37_DOCUMENT_SETTINGS = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `kg_document_settings` (`doc_id` TEXT NOT NULL, `mode` TEXT NOT NULL, `target_collection_id` TEXT, PRIMARY KEY(`doc_id`))")
    }
}
