package io.github.mock108.ubaregi.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRegisterRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: UbaregiDatabase
    private lateinit var repository: RoomRegisterRepository

    @Before
    fun setUp() {
        database = UbaregiDatabaseFactory.createInMemory(context)
        repository = RoomRegisterRepository(database, clock = { 1_000L })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun firstOpenCreatesExactlyOneDatasetMeta() = runBlocking {
        val meta = repository.initializeDatasetMeta()

        assertEquals(meta, database.datasetMetaDao().get())
        assertEquals(1, database.queryCount("dataset_meta"))
        assertEquals(0, meta.snapshotRevision)
        assertEquals(1, meta.nextSessionSequence)
        assertNotNull(UUID.fromString(meta.datasetId))
    }

    @Test
    fun startingAndReopeningKeepsDatasetMeta() = runBlocking {
        val file = File(context.noBackupFilesDir, "test-${UUID.randomUUID()}.db")
        var reopened: UbaregiDatabase? = null
        try {
            val first = UbaregiDatabaseFactory.createAt(context, file)
            val firstRepository = RoomRegisterRepository(first, clock = { 1_000L })
            val firstMeta = firstRepository.initializeDatasetMeta()
            firstRepository.startRegister(openingFloatYen = 0)
            first.close()

            val secondDatabase = UbaregiDatabaseFactory.createAt(context, file)
            reopened = secondDatabase
            val secondMeta = RoomRegisterRepository(secondDatabase).getDatasetMeta()
            assertEquals(firstMeta.datasetId, secondMeta.datasetId)
            assertEquals(1, secondDatabase.queryCount("register_session"))
            assertEquals(2, secondMeta.nextSessionSequence)
        } finally {
            reopened?.close()
            file.delete()
            File(file.path + "-shm").delete()
            File(file.path + "-wal").delete()
        }
    }

    @Test
    fun startIncrementsSequenceAndOnlyOneOpenRegisterIsAllowed() = runBlocking {
        val first = repository.startRegister(openingFloatYen = 0)

        assertEquals(1, first.sequence)
        assertEquals(2, repository.getDatasetMeta().nextSessionSequence)
        assertThrows(OpenRegisterAlreadyExists::class.java) {
            runBlocking { repository.startRegister(openingFloatYen = 100) }
        }
    }

    @Test
    fun paymentAndCashAdjustmentsAreAggregated() = runBlocking {
        val session = repository.startRegister(openingFloatYen = 10_000)
        repository.addPayment(session.id, 1_500, 2_000)
        repository.addCashIn(session.id, 2_000)
        repository.addCashOut(session.id, 1_000)

        val summary = repository.summarizeRegister(session.id)
        assertEquals(1_500, summary.paymentCollectedYen)
        assertEquals(4_000, summary.cashInYen)
        assertEquals(1_500, summary.cashOutYen)
        assertEquals(2_500, summary.netCashYen)
        assertEquals(12_500, summary.expectedCashYen)
    }

    @Test
    fun voidedEntryRemainsInHistoryButIsExcludedFromSummary() = runBlocking {
        val session = repository.startRegister(openingFloatYen = 10_000)
        val saved = repository.addPayment(session.id, 1_500, 2_000).entry

        repository.voidEntry(saved.id, saved.revision, session.revision + 1)

        assertEquals(1, repository.listEntries(session.id).size)
        assertEquals(true, repository.listEntries(session.id).single().isVoided)
        assertEquals(0, repository.summarizeRegister(session.id).paymentCollectedYen)
    }

    @Test
    fun sameEntryIdAndContentIsIdempotentButDifferentContentConflicts() = runBlocking {
        val session = repository.startRegister(openingFloatYen = 0)
        val id = UUID.randomUUID().toString()
        val first = repository.addPayment(session.id, 1_500, 2_000, entryId = id)
        val retry = repository.addPayment(session.id, 1_500, 2_000, entryId = id)

        assertEquals(false, first.alreadySaved)
        assertEquals(true, retry.alreadySaved)
        assertEquals(1, repository.listEntries(session.id).size)
        assertThrows(EntryIdConflict::class.java) {
            runBlocking { repository.addPayment(session.id, 1_000, 2_000, entryId = id) }
        }
    }

    @Test
    fun retryWithSameContentAfterCloseSucceedsWithoutChangingClose() = runBlocking {
        val session = repository.startRegister(openingFloatYen = 0)
        val id = UUID.randomUUID().toString()
        val saved = repository.addPayment(session.id, 1_500, 2_000, entryId = id).entry
        val current = repository.listRegisters().single()
        val closed = repository.closeRegister(session.id, current.revision, 1_500, 0, closedAt = 2_000)
        val retry = repository.addPayment(session.id, 1_500, 2_000, entryId = id)

        assertEquals(true, retry.alreadySaved)
        assertEquals(closed, repository.listRegisters().single())
        assertEquals(saved.id, retry.entry.id)
        assertThrows(EntryIdConflict::class.java) {
            runBlocking { repository.addPayment(session.id, 1_000, 2_000, entryId = id) }
        }
    }

    @Test
    fun editingPaymentUpdatesSummaryAndRejectsStaleRevision() = runBlocking {
        val session = repository.startRegister(openingFloatYen = 10_000)
        val saved = repository.addPayment(session.id, 1_500, 2_000).entry
        val current = repository.listRegisters().single()

        val edited = repository.editPayment(
            entryId = saved.id,
            expectedEntryRevision = saved.revision,
            expectedSessionRevision = current.revision,
            productAmountYen = 1_000,
            receivedAmountYen = 2_000,
        )

        assertEquals(2, edited.revision)
        assertEquals(11_000, repository.summarizeRegister(session.id).expectedCashYen)
        assertThrows(ConcurrentDataModification::class.java) {
            runBlocking {
                repository.editPayment(
                    entryId = edited.id,
                    expectedEntryRevision = saved.revision,
                    expectedSessionRevision = current.revision,
                    productAmountYen = 1_200,
                    receivedAmountYen = 2_000,
                )
            }
        }
        assertEquals(1_000, repository.listEntries(session.id).single().productAmountYen)
    }

    @Test
    fun editingCashAdjustmentAndRegisterPreservesClosedTimestamp() = runBlocking {
        val session = repository.startRegister(openingFloatYen = 10_000)
        val adjustment = repository.addCashIn(session.id, 2_000).entry
        val afterAdjustment = repository.listRegisters().single()
        val editedAdjustment = repository.editCashAdjustment(
            entryId = adjustment.id,
            expectedEntryRevision = adjustment.revision,
            expectedSessionRevision = afterAdjustment.revision,
            amountYen = 3_000,
        )
        assertEquals(3_000L, editedAdjustment.amountYen)

        val beforeClose = repository.listRegisters().single()
        val closed = repository.closeRegister(session.id, beforeClose.revision, 13_000, 10_000, closedAt = 2_000)
        val editedRegister = repository.editRegister(
            sessionId = closed.id,
            expectedRevision = closed.revision,
            openingFloatYen = 9_000,
            actualCashYen = 13_000,
            nextFloatYen = 10_000,
        )
        assertEquals(2_000, editedRegister.closedAt)
        assertEquals(9_000, editedRegister.openingFloatYen)
    }

    @Test
    fun failedTransactionRollsBackEntryAndParentUpdates() = runBlocking {
        val session = repository.startRegister(openingFloatYen = 0)
        val before = repository.listRegisters().single()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE dataset_meta SET snapshot_revision = 9223372036854775807",
        )

        assertThrows(ArithmeticException::class.java) {
            runBlocking { repository.addCashIn(session.id, 1_000) }
        }

        assertEquals(before, repository.listRegisters().single())
        assertEquals(0, repository.listEntries(session.id).size)
    }

    @Test
    fun clearWithStaleSnapshotDoesNotDeleteAnything() = runBlocking {
        val session = repository.startRegister(openingFloatYen = 0)
        repository.addCashIn(session.id, 1)
        val meta = repository.getDatasetMeta()

        assertThrows(ConcurrentDataModification::class.java) {
            runBlocking { repository.clearAllHistory(meta.snapshotRevision - 1) }
        }

        assertEquals(1, repository.listRegisters().size)
        assertEquals(1, repository.listEntries(session.id).size)
    }

    @Test
    fun closingCalculatesDifferenceAndWithdrawalWithoutAddingCashOut() = runBlocking {
        val session = repository.startRegister(openingFloatYen = 10_000)
        repository.addPayment(session.id, 1_500, 2_000)
        val current = repository.listRegisters().single()

        val closed = repository.closeRegister(
            sessionId = session.id,
            expectedSessionRevision = current.revision,
            actualCashYen = 11_400,
            nextFloatYen = 10_000,
        )

        val summary = repository.summarizeRegister(closed.id)
        assertEquals(RegisterStatus.CLOSED, closed.status)
        assertEquals(11_500, summary.expectedCashYen)
        assertEquals(-100, summary.differenceYen)
        assertEquals(1_400, summary.actualChangeYen)
        assertEquals(1_400, summary.withdrawalYen)
        assertEquals(1, repository.listEntries(session.id).size)
    }

    @Test
    fun nextFloatCannotExceedActualCashAndClosedRegisterRejectsNewEntries() = runBlocking {
        val session = repository.startRegister(openingFloatYen = 0)
        assertThrows(InvalidRegisterData::class.java) {
            runBlocking {
                repository.closeRegister(session.id, session.revision, actualCashYen = 1_000, nextFloatYen = 1_001)
            }
        }

        val closed = repository.closeRegister(session.id, session.revision, actualCashYen = 1_000, nextFloatYen = 1_000)
        assertThrows(RegisterIsNotOpen::class.java) {
            runBlocking { repository.addCashIn(closed.id, 1) }
        }
    }

    @Test
    fun clearRemovesBusinessRecordsButKeepsDatasetAndSequence() = runBlocking {
        val session = repository.startRegister(openingFloatYen = 0)
        repository.addCashIn(session.id, 1_000)
        val before = repository.getDatasetMeta()

        val after = repository.clearAllHistory(expectedSnapshotRevision = before.snapshotRevision)

        assertEquals(before.datasetId, after.datasetId)
        assertEquals(before.nextSessionSequence, after.nextSessionSequence)
        assertEquals(before.snapshotRevision + 1, after.snapshotRevision)
        assertEquals(0, repository.listRegisters().size)
        assertEquals(0, database.queryCount("cash_entry"))
        assertNull(repository.getOpenRegister())
    }

    @Test
    fun migrationKeepsExistingBusinessRecords() = runBlocking {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            UbaregiDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )
        val databaseName = "migration-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(databaseName, 0).apply {
                execSQL("CREATE TABLE dataset_meta (singleton_id INTEGER NOT NULL, dataset_id TEXT NOT NULL, snapshot_revision INTEGER NOT NULL, next_session_sequence INTEGER NOT NULL, PRIMARY KEY(singleton_id))")
                execSQL("CREATE TABLE register_session (id TEXT NOT NULL, sequence INTEGER NOT NULL, status TEXT NOT NULL, open_slot INTEGER, opened_at INTEGER NOT NULL, closed_at INTEGER, opening_float_yen INTEGER NOT NULL, actual_cash_yen INTEGER, next_float_yen INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, revision INTEGER NOT NULL, close_revision INTEGER, PRIMARY KEY(id))")
                execSQL("CREATE TABLE cash_entry (id TEXT NOT NULL, session_id TEXT NOT NULL, sequence INTEGER NOT NULL, kind TEXT NOT NULL, occurred_at INTEGER NOT NULL, product_amount_yen INTEGER, received_amount_yen INTEGER, amount_yen INTEGER, is_voided INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(session_id) REFERENCES register_session(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                execSQL("CREATE UNIQUE INDEX index_register_session_sequence ON register_session(sequence)")
                execSQL("CREATE UNIQUE INDEX index_register_session_open_slot ON register_session(open_slot)")
                execSQL("CREATE INDEX index_cash_entry_session_id ON cash_entry(session_id)")
                execSQL("CREATE UNIQUE INDEX index_cash_entry_session_id_sequence ON cash_entry(session_id, sequence)")
                execSQL("INSERT INTO dataset_meta VALUES(1, '11111111-1111-4111-8111-111111111111', 7, 3)")
                execSQL("INSERT INTO register_session VALUES('22222222-2222-4222-8222-222222222222', 2, 'OPEN', 1, 1000, NULL, 10000, NULL, NULL, 1000, 1000, 1, NULL)")
                execSQL("INSERT INTO cash_entry VALUES('33333333-3333-4333-8333-333333333333', '22222222-2222-4222-8222-222222222222', 1, 'PAYMENT', 1000, 1500, 2000, NULL, 0, 1000, 1000, 1)")
                close()
            }

            val migrated = Room.databaseBuilder(
                context,
                UbaregiDatabase::class.java,
                databaseName,
            ).addMigrations(*UbaregiDatabase.migrations).build()
            migrated.openHelper.writableDatabase
            assertEquals(7, migrated.datasetMetaDao().get()!!.snapshotRevision)
            assertEquals(2, migrated.registerSessionDao().findAll().single().sequence)
            assertEquals(1, migrated.cashEntryDao().findForSession("22222222-2222-4222-8222-222222222222").size)
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun UbaregiDatabase.queryCount(table: String): Int = openHelper.writableDatabase.query(
        "SELECT COUNT(*) FROM $table",
    ).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }
}
