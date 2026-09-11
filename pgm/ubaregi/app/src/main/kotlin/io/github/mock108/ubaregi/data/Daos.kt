package io.github.mock108.ubaregi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DatasetMetaDao {
    @Query("SELECT * FROM dataset_meta WHERE singleton_id = :singletonId")
    suspend fun get(singletonId: Int = DatasetMeta.SINGLETON_ID): DatasetMeta?

    @Query("SELECT * FROM dataset_meta WHERE singleton_id = :singletonId")
    fun observe(singletonId: Int = DatasetMeta.SINGLETON_ID): Flow<DatasetMeta?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(meta: DatasetMeta)

    @Query(
        """
        UPDATE dataset_meta
        SET snapshot_revision = :newSnapshotRevision,
            next_session_sequence = :newNextSessionSequence
        WHERE singleton_id = :singletonId
          AND snapshot_revision = :expectedSnapshotRevision
        """,
    )
    suspend fun updateCounters(
        expectedSnapshotRevision: Long,
        newSnapshotRevision: Long,
        newNextSessionSequence: Long,
        singletonId: Int = DatasetMeta.SINGLETON_ID,
    ): Int
}

@Dao
interface RegisterSessionDao {
    @Query("SELECT * FROM register_session WHERE id = :id")
    suspend fun findById(id: String): RegisterSession?

    @Query("SELECT * FROM register_session WHERE open_slot = 1 LIMIT 1")
    suspend fun findOpen(): RegisterSession?

    @Query("SELECT * FROM register_session WHERE status = 'CLOSED' ORDER BY sequence DESC LIMIT 1")
    suspend fun findLatestClosed(): RegisterSession?

    @Query("SELECT * FROM register_session ORDER BY sequence DESC")
    suspend fun findAll(): List<RegisterSession>

    @Query("SELECT * FROM register_session ORDER BY sequence DESC")
    fun observeAll(): Flow<List<RegisterSession>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: RegisterSession)

    @Query(
        """
        UPDATE register_session
        SET opening_float_yen = :openingFloatYen,
            actual_cash_yen = :actualCashYen,
            next_float_yen = :nextFloatYen,
            updated_at = :updatedAt,
            revision = :newRevision
        WHERE id = :id
          AND revision = :expectedRevision
        """,
    )
    suspend fun updateDetails(
        id: String,
        expectedRevision: Long,
        newRevision: Long,
        openingFloatYen: Long,
        actualCashYen: Long?,
        nextFloatYen: Long?,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE register_session
        SET updated_at = :updatedAt,
            revision = :newRevision
        WHERE id = :id
          AND revision = :expectedRevision
        """,
    )
    suspend fun touch(
        id: String,
        expectedRevision: Long,
        newRevision: Long,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE register_session
        SET status = 'CLOSED',
            open_slot = NULL,
            closed_at = :closedAt,
            actual_cash_yen = :actualCashYen,
            next_float_yen = :nextFloatYen,
            updated_at = :updatedAt,
            revision = :newRevision,
            close_revision = :newRevision
        WHERE id = :id
          AND status = 'OPEN'
          AND revision = :expectedRevision
        """,
    )
    suspend fun close(
        id: String,
        expectedRevision: Long,
        newRevision: Long,
        actualCashYen: Long,
        nextFloatYen: Long,
        closedAt: Long,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM register_session")
    suspend fun deleteAll(): Int
}

@Dao
interface CashEntryDao {
    @Query("SELECT * FROM cash_entry WHERE id = :id")
    suspend fun findById(id: String): CashEntry?

    @Query("SELECT * FROM cash_entry WHERE session_id = :sessionId ORDER BY sequence ASC")
    suspend fun findForSession(sessionId: String): List<CashEntry>

    @Query("SELECT * FROM cash_entry WHERE session_id = :sessionId ORDER BY sequence DESC")
    fun observeForSession(sessionId: String): Flow<List<CashEntry>>

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM cash_entry WHERE session_id = :sessionId")
    suspend fun nextSequence(sessionId: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: CashEntry)

    @Query(
        """
        UPDATE cash_entry
        SET product_amount_yen = :productAmountYen,
            received_amount_yen = :receivedAmountYen,
            updated_at = :updatedAt,
            revision = :newRevision
        WHERE id = :id
          AND revision = :expectedRevision
        """,
    )
    suspend fun updatePayment(
        id: String,
        expectedRevision: Long,
        newRevision: Long,
        productAmountYen: Long,
        receivedAmountYen: Long,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE cash_entry
        SET amount_yen = :amountYen,
            updated_at = :updatedAt,
            revision = :newRevision
        WHERE id = :id
          AND revision = :expectedRevision
        """,
    )
    suspend fun updateAdjustment(
        id: String,
        expectedRevision: Long,
        newRevision: Long,
        amountYen: Long,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE cash_entry
        SET is_voided = 1,
            updated_at = :updatedAt,
            revision = :newRevision
        WHERE id = :id
          AND revision = :expectedRevision
        """,
    )
    suspend fun voidEntry(id: String, expectedRevision: Long, newRevision: Long, updatedAt: Long): Int

    @Query("DELETE FROM cash_entry")
    suspend fun deleteAll(): Int
}
