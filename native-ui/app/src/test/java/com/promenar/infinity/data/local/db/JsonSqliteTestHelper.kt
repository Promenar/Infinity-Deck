package com.promenar.infinity.data.local.db

import android.database.Cursor
import android.database.MatrixCursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.serialization.json.*

/**
 * Robolectric 4.11 的 SQLite 没有 JSON1。Room 仍负责建表、插入和 DAO 行映射；
 * 含 JSON 的原始 SQL 与绑定值交给主机 Python 标准库的内存 SQLite 实际执行，不模拟聚合结果。
 */
internal fun jsonSqliteTestHelper(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
    val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
    return object : SupportSQLiteOpenHelper by helper {
        private val wrapped by lazy {
            val database = helper.writableDatabase
            object : SupportSQLiteDatabase by database {
                override fun query(query: SupportSQLiteQuery): Cursor {
                    if (!query.sql.contains("json_extract")) return database.query(query)
                    val rows = database.query("SELECT role, tokens, model_id, created_at FROM messages").use { cursor ->
                        buildJsonArray {
                            while (cursor.moveToNext()) add(buildJsonArray {
                                for (i in 0..3) add(if (cursor.isNull(i)) JsonNull else if (i == 3) JsonPrimitive(cursor.getLong(i)) else JsonPrimitive(cursor.getString(i)))
                            })
                        }
                    }
                    val arguments = mutableListOf<JsonElement>()
                    fun bind(index: Int, value: JsonElement) {
                        while (arguments.size < index) arguments += JsonNull
                        arguments[index - 1] = value
                    }
                    query.bindTo(object : SupportSQLiteProgram {
                        override fun bindNull(index: Int) { bind(index, JsonNull) }
                        override fun bindLong(index: Int, value: Long) { bind(index, JsonPrimitive(value)) }
                        override fun bindDouble(index: Int, value: Double) { bind(index, JsonPrimitive(value)) }
                        override fun bindString(index: Int, value: String) { bind(index, JsonPrimitive(value)) }
                        override fun bindBlob(index: Int, value: ByteArray) = error("用量 SQL 不接受 BLOB")
                        override fun clearBindings() { arguments.fill(JsonNull) }
                        override fun close() = Unit
                    })
                    val process = ProcessBuilder("python3", "-c", PYTHON_SQLITE).start()
                    process.outputStream.bufferedWriter().use { it.write(buildJsonObject {
                        put("sql", query.sql); put("args", JsonArray(arguments)); put("rows", rows)
                    }.toString()) }
                    val output = process.inputStream.bufferedReader().readText()
                    val error = process.errorStream.bufferedReader().readText()
                    check(process.waitFor() == 0) { "主机 SQLite 查询失败：$error" }
                    val result = Json.parseToJsonElement(output).jsonObject
                    return MatrixCursor(result.getValue("columns").jsonArray.map { it.jsonPrimitive.content }.toTypedArray()).apply {
                        result.getValue("rows").jsonArray.forEach { row -> addRow(row.jsonArray.map<JsonElement, Any?> { value ->
                            if (value == JsonNull) null else value.jsonPrimitive.let { it.longOrNull ?: it.content }
                        }.toTypedArray()) }
                    }
                }
            }
        }
        override val writableDatabase: SupportSQLiteDatabase get() = wrapped
        override val readableDatabase: SupportSQLiteDatabase get() = wrapped
    }
}

private const val PYTHON_SQLITE = """
import json, sqlite3, sys
data = json.load(sys.stdin)
database = sqlite3.connect(':memory:')
database.execute('CREATE TABLE messages(role TEXT, tokens TEXT, model_id TEXT, created_at INTEGER)')
database.executemany('INSERT INTO messages VALUES(?,?,?,?)', data['rows'])
cursor = database.execute(data['sql'], data['args'])
print(json.dumps({'columns': [column[0] for column in cursor.description], 'rows': cursor.fetchall()}))
"""
