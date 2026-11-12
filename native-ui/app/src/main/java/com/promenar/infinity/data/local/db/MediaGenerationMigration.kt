package com.promenar.infinity.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 本段只增加媒体任务和费用账本。 */
val MIGRATION_38_39_MEDIA_GENERATION = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `media_generation_tasks` (
            `id` TEXT NOT NULL, `source_ref` TEXT NOT NULL, `state` TEXT NOT NULL,
            `updated_at` INTEGER NOT NULL, `record_json` TEXT NOT NULL, PRIMARY KEY(`id`)
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_generation_tasks_source_ref` ON `media_generation_tasks` (`source_ref`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_generation_tasks_state` ON `media_generation_tasks` (`state`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `media_generation_usage` (
            `task_id` TEXT NOT NULL, `provider_id` TEXT NOT NULL, `model_id` TEXT NOT NULL, `source_ref` TEXT NOT NULL,
            `amount` TEXT, `currency` TEXT, `completed_at` INTEGER NOT NULL, PRIMARY KEY(`task_id`)
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_generation_usage_provider_id_model_id` ON `media_generation_usage` (`provider_id`, `model_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_generation_usage_completed_at` ON `media_generation_usage` (`completed_at`)")
    }
}
