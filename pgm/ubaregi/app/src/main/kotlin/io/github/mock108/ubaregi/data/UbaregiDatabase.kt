package io.github.mock108.ubaregi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.util.UUID

@Database(
    entities = [DatasetMeta::class, RegisterSession::class, CashEntry::class],
    version = UbaregiDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class UbaregiDatabase : RoomDatabase() {
    abstract fun datasetMetaDao(): DatasetMetaDao

    abstract fun registerSessionDao(): RegisterSessionDao

    abstract fun cashEntryDao(): CashEntryDao

    companion object {
        const val SCHEMA_VERSION = 1
        const val DATABASE_FILE_NAME = "ubaregi_business.db"

        val migrations = arrayOf(UbaregiDatabaseMigrations.MIGRATION_0_1)
    }
}

object UbaregiDatabaseFactory {
    fun create(context: Context): UbaregiDatabase {
        val databaseFile = File(context.noBackupFilesDir, UbaregiDatabase.DATABASE_FILE_NAME)
        return createAt(context, databaseFile)
    }

    fun createAt(context: Context, databaseFile: File): UbaregiDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            UbaregiDatabase::class.java,
            databaseFile.absolutePath,
        )
            .addMigrations(*UbaregiDatabase.migrations)
            .addCallback(UbaregiDatabaseCallback)
            .build()
    }

    fun createInMemory(context: Context): UbaregiDatabase = Room.inMemoryDatabaseBuilder(
        context,
        UbaregiDatabase::class.java,
    )
        .addMigrations(*UbaregiDatabase.migrations)
        .addCallback(UbaregiDatabaseCallback)
        .build()
}

private object UbaregiDatabaseCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        installBusinessTriggers(db)
        db.execSQL(
            """
            INSERT INTO dataset_meta(singleton_id, dataset_id, snapshot_revision, next_session_sequence)
            VALUES(1, '${UUID.randomUUID()}', 0, 1)
            """.trimIndent(),
        )
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        // Re-installing IF NOT EXISTS is deliberately non-destructive. It also repairs
        // trigger-only protection after a process or OS upgrade without touching records.
        installBusinessTriggers(db)
    }
}

internal fun installBusinessTriggers(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS validate_dataset_meta_insert
        BEFORE INSERT ON dataset_meta
        WHEN NEW.singleton_id <> 1
          OR length(NEW.dataset_id) <> 36
          OR NEW.snapshot_revision < 0
          OR NEW.next_session_sequence < 1
        BEGIN SELECT RAISE(ABORT, 'invalid dataset_meta'); END
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS validate_dataset_meta_update
        BEFORE UPDATE ON dataset_meta
        WHEN NEW.singleton_id <> 1
          OR length(NEW.dataset_id) <> 36
          OR NEW.snapshot_revision < 0
          OR NEW.next_session_sequence < 1
        BEGIN SELECT RAISE(ABORT, 'invalid dataset_meta'); END
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS validate_register_session_insert
        BEFORE INSERT ON register_session
        WHEN length(NEW.id) <> 36
          OR NEW.sequence < 1
          OR NEW.revision < 1
          OR NEW.opening_float_yen < 0 OR NEW.opening_float_yen > 9999999
          OR (
            NEW.status = 'OPEN'
            AND (NEW.open_slot IS NOT 1 OR NEW.closed_at IS NOT NULL
              OR NEW.actual_cash_yen IS NOT NULL OR NEW.next_float_yen IS NOT NULL
              OR NEW.close_revision IS NOT NULL)
          )
          OR (
            NEW.status = 'CLOSED'
            AND (NEW.open_slot IS NOT NULL OR NEW.closed_at IS NULL
              OR NEW.actual_cash_yen IS NULL OR NEW.actual_cash_yen < 0
              OR NEW.actual_cash_yen > 9999999 OR NEW.next_float_yen IS NULL
              OR NEW.next_float_yen < 0 OR NEW.next_float_yen > NEW.actual_cash_yen
              OR NEW.close_revision IS NULL OR NEW.close_revision < 1)
          )
          OR NEW.status NOT IN ('OPEN', 'CLOSED')
        BEGIN SELECT RAISE(ABORT, 'invalid register_session'); END
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS validate_register_session_update
        BEFORE UPDATE ON register_session
        WHEN length(NEW.id) <> 36
          OR NEW.sequence < 1
          OR NEW.revision < 1
          OR NEW.opening_float_yen < 0 OR NEW.opening_float_yen > 9999999
          OR (
            NEW.status = 'OPEN'
            AND (NEW.open_slot IS NOT 1 OR NEW.closed_at IS NOT NULL
              OR NEW.actual_cash_yen IS NOT NULL OR NEW.next_float_yen IS NOT NULL
              OR NEW.close_revision IS NOT NULL)
          )
          OR (
            NEW.status = 'CLOSED'
            AND (NEW.open_slot IS NOT NULL OR NEW.closed_at IS NULL
              OR NEW.actual_cash_yen IS NULL OR NEW.actual_cash_yen < 0
              OR NEW.actual_cash_yen > 9999999 OR NEW.next_float_yen IS NULL
              OR NEW.next_float_yen < 0 OR NEW.next_float_yen > NEW.actual_cash_yen
              OR NEW.close_revision IS NULL OR NEW.close_revision < 1)
          )
          OR NEW.status NOT IN ('OPEN', 'CLOSED')
        BEGIN SELECT RAISE(ABORT, 'invalid register_session'); END
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS validate_cash_entry_insert
        BEFORE INSERT ON cash_entry
        WHEN length(NEW.id) <> 36
          OR length(NEW.session_id) <> 36
          OR NEW.sequence < 1
          OR NEW.revision < 1
          OR NEW.kind NOT IN ('PAYMENT', 'CASH_IN', 'CASH_OUT')
          OR (
            NEW.kind = 'PAYMENT'
            AND (NEW.product_amount_yen IS NULL OR NEW.product_amount_yen < 1
              OR NEW.product_amount_yen > 9999999 OR NEW.received_amount_yen IS NULL
              OR NEW.received_amount_yen < NEW.product_amount_yen
              OR NEW.received_amount_yen > 9999999 OR NEW.amount_yen IS NOT NULL)
          )
          OR (
            NEW.kind IN ('CASH_IN', 'CASH_OUT')
            AND (NEW.product_amount_yen IS NOT NULL OR NEW.received_amount_yen IS NOT NULL
              OR NEW.amount_yen IS NULL OR NEW.amount_yen < 1 OR NEW.amount_yen > 9999999)
          )
        BEGIN SELECT RAISE(ABORT, 'invalid cash_entry'); END
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS validate_cash_entry_update
        BEFORE UPDATE ON cash_entry
        WHEN length(NEW.id) <> 36
          OR length(NEW.session_id) <> 36
          OR NEW.sequence < 1
          OR NEW.revision < 1
          OR NEW.kind NOT IN ('PAYMENT', 'CASH_IN', 'CASH_OUT')
          OR (
            NEW.kind = 'PAYMENT'
            AND (NEW.product_amount_yen IS NULL OR NEW.product_amount_yen < 1
              OR NEW.product_amount_yen > 9999999 OR NEW.received_amount_yen IS NULL
              OR NEW.received_amount_yen < NEW.product_amount_yen
              OR NEW.received_amount_yen > 9999999 OR NEW.amount_yen IS NOT NULL)
          )
          OR (
            NEW.kind IN ('CASH_IN', 'CASH_OUT')
            AND (NEW.product_amount_yen IS NOT NULL OR NEW.received_amount_yen IS NOT NULL
              OR NEW.amount_yen IS NULL OR NEW.amount_yen < 1 OR NEW.amount_yen > 9999999)
          )
        BEGIN SELECT RAISE(ABORT, 'invalid cash_entry'); END
        """.trimIndent(),
    )
}
