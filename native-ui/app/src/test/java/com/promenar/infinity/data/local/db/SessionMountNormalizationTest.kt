package com.promenar.infinity.data.local.db

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class SessionMountNormalizationTest {
    @Test fun `全局挂载复制到每个会话且别名冲突递增到唯一并保留来源`() = runBlocking {
        opened(seed = { db ->
            session(db, "a")
            session(db, "b")
            mount(db, "global", "资料", "", readOnly = true)
            mount(db, "global-2", "其它", "")
            mount(db, "local", "资料", "a", readOnly = true)
            mount(db, "local-2", "资料-2", "a")
            snapshot(db, "global")
            snapshot(db, "local")
        }) { room ->
            val dao = room.externalMountDao()
            val all = dao.all()
            assertThat(room.openHelper.writableDatabase.version).isEqualTo(33)
            assertThat(all).hasSize(6)
            assertThat(all.map { it.id }.distinct()).hasSize(6)
            assertThat(all.map { it.id }).containsNoneOf("global", "global-2")
            assertThat(all.map { it.scopeKey }).containsExactly("a", "a", "a", "a", "b", "b")
            assertThat(all.filter { it.readOnly }.map { it.sourceId }).containsExactly(
                "content://test/tree/local", "content://test/tree/global", "content://test/tree/global")
            assertThat(dao.visible("a").map { it.alias }).containsExactly("资料", "资料-2", "资料-3", "其它")
            assertThat(dao.observeVisible("b").first().map { it.alias }).containsExactly("资料", "其它")
            assertThat(dao.visible("")).isEmpty()
            val copies = all.filter { it.sourceId == "content://test/tree/global" }
            assertThat(copies).hasSize(2)
            copies.forEach {
                assertThat(it.sourceType).isEqualTo("saf_tree")
                assertThat(it.createdAt).isEqualTo(123L)
                assertThat(it.unavailableReason).isEqualTo("存储离线")
                assertThat(dao.snapshots(it.id, "/file.txt")).isEmpty()
            }
            assertThat(dao.snapshots("global", "/file.txt")).isEmpty()
            assertThat(dao.snapshots("local", "/file.txt")).hasSize(1)
            room.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
        }
    }

    @Test fun `无会话时删除所有全局挂载`() = runBlocking {
        opened(seed = { db ->
            mount(db, "global", "资料", "", readOnly = true)
            snapshot(db, "global")
        }) { room ->
            assertThat(room.externalMountDao().all()).isEmpty()
            room.openHelper.writableDatabase.query("SELECT * FROM external_snapshots").use { assertThat(it.count).isEqualTo(0) }
        }
    }

    @Test fun `多个全局别名之间的冲突也使用唯一后缀`() = runBlocking {
        opened(seed = { db ->
            session(db, "a")
            mount(db, "g1", "资料", "")
            mount(db, "g2", "资料-2", "")
            mount(db, "local", "资料", "a")
        }) { room ->
            assertThat(room.externalMountDao().visible("a").map { it.alias })
                .containsExactly("资料", "资料-2", "资料-2-2")
        }
    }

    @Test fun `重复打开不再改动已规整的挂载`() = runBlocking {
        opened(seed = { db ->
            session(db, "a")
            mount(db, "global", "资料", "")
        }, reopen = true) { room ->
            val all = room.externalMountDao().all()
            assertThat(all).hasSize(1)
            assertThat(all.single().scopeKey).isEqualTo("a")
            assertThat(all.single().alias).isEqualTo("资料")
        }
    }

    @Test fun `打开后写回的旧行在下次打开时规整`() = runBlocking {
        opened(seed = { db -> session(db, "a") }, afterFirstOpen = { db ->
            // 模拟从旧备份按行恢复：绕过迁移直接写入全局与只读挂载。
            db.execSQL("INSERT INTO external_mounts VALUES ('restored', '旧资料', 'saf_tree', 'content://test/tree/restored', '', 1, 123, NULL)")
        }) { room ->
            val all = room.externalMountDao().all()
            assertThat(all.map { it.scopeKey }).containsExactly("a")
            assertThat(all.single().readOnly).isTrue()
            assertThat(all.single().alias).isEqualTo("旧资料")
        }
    }

    @Test fun `会话知识库挂载的只读标志在重复打开时保持`() = runBlocking {
        opened(seed = { db ->
            session(db, "a")
            db.execSQL("INSERT INTO external_mounts VALUES ('kb', 'knowledge', 'knowledge_base', 'kb-root', 'a', 1, 123, NULL)")
        }, reopen = true) { room ->
            val row = room.externalMountDao().visible("a").single()
            assertThat(row.sourceType).isEqualTo("knowledge_base")
            assertThat(row.sourceId).isEqualTo("kb-root")
            assertThat(row.readOnly).isTrue()
        }
    }

    private suspend fun opened(
        seed: (SQLiteDatabase) -> Unit,
        reopen: Boolean = false,
        afterFirstOpen: ((androidx.sqlite.db.SupportSQLiteDatabase) -> Unit)? = null,
        check: suspend (InfinityDatabase) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "session-mount-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                val definition = Json.parseToJsonElement(File("app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/27.json").readText())
                    .jsonObject.getValue("database").jsonObject
                definition.getValue("entities").jsonArray.forEach { raw ->
                    val entity = raw.jsonObject
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    fun sql(value: String) = value.replace("\${TABLE_NAME}", table)
                    db.execSQL(sql(entity.getValue("createSql").jsonPrimitive.content))
                    entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(sql(it.jsonObject.getValue("createSql").jsonPrimitive.content)) }
                }
                definition.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                seed(db)
                db.version = 27
            }
            fun open() = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
                .addMigrations(MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33).addCallback(SessionMountNormalizationCallback { throw it }).allowMainThreadQueries().build()
            if (reopen || afterFirstOpen != null) {
                val first = open()
                try { afterFirstOpen?.invoke(first.openHelper.writableDatabase) ?: first.openHelper.writableDatabase } finally { first.close() }
            }
            val room = open()
            try { check(room) } finally { room.close() }
        } finally { context.deleteDatabase(name) }
    }

    private fun session(db: SQLiteDatabase, id: String) {
        val values = ContentValues()
        db.rawQuery("PRAGMA table_info(sessions)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val column = cursor.getString(1)
                if (column == "id") values.put(column, id)
                else if (cursor.getInt(3) != 0 && cursor.isNull(4)) {
                    if (cursor.getString(2) in setOf("INTEGER", "REAL")) values.put(column, 0) else values.put(column, "")
                }
            }
        }
        db.insertOrThrow("sessions", null, values)
    }

    private fun mount(db: SQLiteDatabase, id: String, alias: String, scope: String, readOnly: Boolean = false) {
        db.execSQL("INSERT INTO external_mounts VALUES (?, ?, 'saf_tree', ?, ?, ?, 123, '存储离线')",
            arrayOf(id, alias, "content://test/tree/$id", scope, if (readOnly) 1 else 0))
    }

    private fun snapshot(db: SQLiteDatabase, id: String) {
        db.execSQL("INSERT INTO external_snapshots VALUES (?, '/file.txt', 'root', 'file', 1, 'hash', 2)", arrayOf(id))
    }
}
