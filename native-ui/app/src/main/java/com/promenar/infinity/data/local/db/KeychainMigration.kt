package com.promenar.infinity.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_36_37_KEYCHAIN = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ssh_hosts ADD COLUMN key_entry_id TEXT")
        db.execSQL("""CREATE TABLE IF NOT EXISTS keychain_entries (
            id TEXT NOT NULL PRIMARY KEY, category TEXT NOT NULL, name TEXT NOT NULL, url TEXT,
            username TEXT, note TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            origin TEXT NOT NULL, added_by_session_id TEXT, key_type TEXT, fingerprint_sha256 TEXT,
            public_key TEXT, has_passphrase INTEGER NOT NULL DEFAULT 0, agent_enabled INTEGER NOT NULL DEFAULT 1
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_keychain_entries_fingerprint_sha256 ON keychain_entries(fingerprint_sha256)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS keychain_usage (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, entry_ref TEXT NOT NULL,
            action TEXT NOT NULL, context TEXT, at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_keychain_usage_entry_ref_at ON keychain_usage(entry_ref, at)")
    }
}
