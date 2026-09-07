package io.github.mock108.ubaregi

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerRepositoryTest {
    private lateinit var database: UbaregiDatabase
    private lateinit var repository: LedgerRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            UbaregiDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = LedgerRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun t35RetryAfterCloseIsIdempotentAndConflictingInputIsRejected() = runBlocking {
        val started = (repository.startRegister(10_000) as LedgerOperation.Success).value
        repository.savePayment("entry-1", started.id, 1_500, 2_000)
        val beforeClose = repository.loadSnapshot()
        val open = beforeClose.openSession!!
        repository.closeRegister(open.id, open.revision, 11_400, 10_000)
        val closedBeforeRetry = repository.loadSnapshot()
        val closed = closedBeforeRetry.sessions.single()
        val closedAt = closed.closedAt
        val version = closedBeforeRetry.meta.snapshotRevision

        val sameRetry = repository.savePayment("entry-1", closed.id, 1_500, 2_000)
        assertTrue(sameRetry is LedgerOperation.Success && sameRetry.alreadySaved)
        val afterSameRetry = repository.loadSnapshot()
        assertEquals(version, afterSameRetry.meta.snapshotRevision)
        assertEquals(closedAt, afterSameRetry.sessions.single().closedAt)
        assertEquals(1, afterSameRetry.entries.size)

        val conflictingRetry = repository.savePayment("entry-1", closed.id, 1_600, 2_000)
        assertTrue(conflictingRetry is LedgerOperation.Failure && conflictingRetry.conflict)
        val afterConflict = repository.loadSnapshot()
        assertEquals(version, afterConflict.meta.snapshotRevision)
        assertEquals(1_500, afterConflict.entries.single().productAmountYen)

        val unsavedIdAfterClose = repository.savePayment("entry-2", closed.id, 1_500, 2_000)
        assertTrue(unsavedIdAfterClose is LedgerOperation.Failure)
        assertFalse(afterConflict.entries.any { it.id == "entry-2" })
        assertEquals(closedAt, afterConflict.sessions.single().closedAt)
    }

    @Test
    fun stage3EditCancelConflictAndClearKeepLaterOpeningFloatStable() = runBlocking {
        val first = (repository.startRegister(10_000) as LedgerOperation.Success).value
        repository.savePayment("entry-1", first.id, 1_500, 2_000)
        val beforeClose = repository.loadSnapshot()
        val open = beforeClose.openSession!!
        repository.closeRegister(open.id, open.revision, 11_500, 10_000)
        val closedSnapshot = repository.loadSnapshot()
        val closed = closedSnapshot.sessions.single()
        val second = (repository.startRegister(10_000) as LedgerOperation.Success).value

        val edited = repository.editEntry(
            entryId = "entry-1",
            expectedSessionRevision = closed.revision,
            expectedEntryRevision = 1,
            productAmountYen = 1_000,
            receivedAmountYen = 2_000,
            amountYen = null,
        )
        assertTrue(edited is LedgerOperation.Success)
        val afterEdit = repository.loadSnapshot()
        val editedClosed = afterEdit.sessions.first { it.id == first.id }
        assertEquals(11_000, LedgerCalculator.summary(editedClosed, afterEdit.entriesFor(first.id)).expectedCashYen)
        assertEquals(10_000, afterEdit.sessions.first { it.id == second.id }.openingFloatYen)
        assertTrue(editedClosed.revision > (editedClosed.closeRevision ?: 0L))

        val editedEntry = afterEdit.entries.single { it.id == "entry-1" }
        val cancelled = repository.cancelEntry("entry-1", editedClosed.revision, editedEntry.revision)
        assertTrue(cancelled is LedgerOperation.Success)
        val afterCancel = repository.loadSnapshot()
        val cancelledEntry = afterCancel.entries.single { it.id == "entry-1" }
        assertTrue(cancelledEntry.isVoided)
        assertEquals(10_000, LedgerCalculator.summary(afterCancel.sessions.first { it.id == first.id }, afterCancel.entriesFor(first.id)).expectedCashYen)
        val editVoided = repository.editEntry("entry-1", afterCancel.sessions.first { it.id == first.id }.revision, cancelledEntry.revision, 900, 1_000, null)
        assertTrue(editVoided is LedgerOperation.Failure)

        val stale = afterCancel.sessions.first { it.id == first.id }
        repository.editRegister(stale.id, stale.revision, 9_500, stale.actualCashYen, stale.nextFloatYen)
        val staleEdit = repository.editRegister(stale.id, stale.revision, 9_000, stale.actualCashYen, stale.nextFloatYen)
        assertTrue(staleEdit is LedgerOperation.Failure && staleEdit.conflict)
        assertEquals(10_000, repository.loadSnapshot().sessions.first { it.id == second.id }.openingFloatYen)

        val beforeClear = repository.loadSnapshot()
        val datasetId = beforeClear.meta.datasetId
        val nextSequence = beforeClear.meta.nextSessionSequence
        val cleared = repository.clearAll(beforeClear.meta.snapshotRevision)
        assertTrue(cleared is LedgerOperation.Success)
        val afterClear = repository.loadSnapshot()
        assertTrue(afterClear.sessions.isEmpty())
        assertTrue(afterClear.entries.isEmpty())
        assertEquals(datasetId, afterClear.meta.datasetId)
        assertEquals(nextSequence, afterClear.meta.nextSessionSequence)
        assertEquals(beforeClear.meta.snapshotRevision + 1, afterClear.meta.snapshotRevision)

        val fresh = (repository.startRegister(12_000) as LedgerOperation.Success).value
        assertNotNull(fresh)
        val staleClear = repository.clearAll(beforeClear.meta.snapshotRevision)
        assertTrue(staleClear is LedgerOperation.Failure && staleClear.conflict)
        assertEquals(1, repository.loadSnapshot().sessions.size)
    }
}
