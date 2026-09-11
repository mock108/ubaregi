package io.github.mock108.ubaregi.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The pre-Room baseline is version 0. It is intentionally additive and never drops or
 * recreates business tables. Future schema changes should add a new numbered migration.
 */
object UbaregiDatabaseMigrations {
    val MIGRATION_0_1: Migration = object : Migration(0, 1) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS dataset_meta (
                    singleton_id INTEGER NOT NULL,
                    dataset_id TEXT NOT NULL,
                    snapshot_revision INTEGER NOT NULL,
                    next_session_sequence INTEGER NOT NULL,
                    PRIMARY KEY(singleton_id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS register_session (
                    id TEXT NOT NULL,
                    sequence INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    open_slot INTEGER,
                    opened_at INTEGER NOT NULL,
                    closed_at INTEGER,
                    opening_float_yen INTEGER NOT NULL,
                    actual_cash_yen INTEGER,
                    next_float_yen INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    revision INTEGER NOT NULL,
                    close_revision INTEGER,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cash_entry (
                    id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    sequence INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    occurred_at INTEGER NOT NULL,
                    product_amount_yen INTEGER,
                    received_amount_yen INTEGER,
                    amount_yen INTEGER,
                    is_voided INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    revision INTEGER NOT NULL,
                    PRIMARY KEY(id),
                    FOREIGN KEY(session_id) REFERENCES register_session(id)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_register_session_sequence " +
                    "ON register_session(sequence)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_register_session_open_slot " +
                    "ON register_session(open_slot)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_cash_entry_session_id ON cash_entry(session_id)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_cash_entry_session_id_sequence " +
                    "ON cash_entry(session_id, sequence)",
            )
            installBusinessTriggers(db)
        }
    }
}
