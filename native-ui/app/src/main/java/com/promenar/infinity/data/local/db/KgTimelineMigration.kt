package com.promenar.infinity.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.promenar.infinity.data.rag.KgNaturalOrder
import java.math.BigInteger

/** 旧关系保留证据并以缺少出现记录表示位置未知。 */
val MIGRATION_35_36_TIMELINE = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `kg_edge_mentions` (`id` TEXT NOT NULL, `edge_id` TEXT NOT NULL, `evidence_id` TEXT NOT NULL, `doc_id` TEXT NOT NULL, `window_index` INTEGER NOT NULL, `char_start` INTEGER NOT NULL, `char_end` INTEGER NOT NULL, `kind` TEXT NOT NULL, `family` TEXT, `quote` TEXT, `task_id` TEXT NOT NULL, `content_hash` TEXT NOT NULL, `position_coarse` INTEGER NOT NULL, `flashback` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`edge_id`) REFERENCES `kg_edges`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`evidence_id`) REFERENCES `kg_evidence`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        listOf("edge_id", "evidence_id", "doc_id").forEach { db.execSQL("CREATE INDEX IF NOT EXISTS `index_kg_edge_mentions_$it` ON `kg_edge_mentions` (`$it`)") }
        db.execSQL("CREATE TABLE IF NOT EXISTS `kg_edge_validity` (`id` TEXT NOT NULL, `edge_id` TEXT NOT NULL, `collection_id` TEXT NOT NULL, `from_doc_id` TEXT NOT NULL, `from_window` INTEGER NOT NULL, `from_char` INTEGER NOT NULL, `to_doc_id` TEXT, `to_window` INTEGER, `to_char` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`edge_id`) REFERENCES `kg_edges`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        listOf("edge_id", "collection_id").forEach { db.execSQL("CREATE INDEX IF NOT EXISTS `index_kg_edge_validity_$it` ON `kg_edge_validity` (`$it`)") }
        db.execSQL("CREATE TABLE IF NOT EXISTS `kg_entity_links` (`id` TEXT NOT NULL, `collection_id` TEXT NOT NULL, `from_node` TEXT NOT NULL, `to_node` TEXT NOT NULL, `verdict` TEXT NOT NULL, `decided_by` TEXT NOT NULL, `model_id` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`from_node`) REFERENCES `kg_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`to_node`) REFERENCES `kg_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_kg_entity_links_from_node` ON `kg_entity_links` (`from_node`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_kg_entity_links_to_node` ON `kg_entity_links` (`to_node`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_kg_entity_links_collection_id_from_node_to_node` ON `kg_entity_links` (`collection_id`, `from_node`, `to_node`)")
        val entries = db.query("SELECT m.collection_id,m.doc_id,COALESCE(w.name,m.doc_id) FROM kg_collection_members m LEFT JOIN workspace_files w ON w.uuid=m.doc_id WHERE m.order_source IS NULL OR m.order_source!='manual'").use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) }
        }
        entries.groupBy { it.first }.forEach { (_, rows) ->
            rows.sortedWith(compareBy<Triple<String, String, String>, String>(KgNaturalOrder) { it.third }.thenBy { it.second })
                .forEachIndexed { index, row ->
                    val rank = BigInteger("1000000000000").multiply(BigInteger.valueOf(index + 1L)).toString().padStart(30, '0')
                    db.execSQL("UPDATE kg_collection_members SET order_key=?,order_source='natural' WHERE doc_id=?", arrayOf(rank, row.second))
                }
        }
    }
}
