package com.promenar.infinity.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_37_38_PRODUCTION = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `productions` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `root_path` TEXT NOT NULL, `revision` INTEGER NOT NULL, `snapshot` TEXT NOT NULL, `manifest_sha256` TEXT, `manifest_error` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `session_productions` (`session_id` TEXT NOT NULL, `production_id` TEXT NOT NULL, `active` INTEGER NOT NULL, `bound_at` INTEGER NOT NULL, PRIMARY KEY(`session_id`), FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`production_id`) REFERENCES `productions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_productions_production_id` ON `session_productions` (`production_id`)")
    }
}
