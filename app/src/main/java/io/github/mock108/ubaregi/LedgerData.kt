package io.github.mock108.ubaregi

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Update
import androidx.room.Dao
import androidx.room.Query
import androidx.room.withTransaction
import java.time.Instant
import java.util.UUID

internal const val STATUS_OPEN = "OPEN"
internal const val STATUS_CLOSED = "CLOSED"
internal const val KIND_PAYMENT = "PAYMENT"
internal const val KIND_CASH_IN = "CASH_IN"
internal const val KIND_CASH_OUT = "CASH_OUT"
internal const val MAX_YEN = 9_999_999L

@Entity(tableName = "dataset_meta")
internal data class DatasetMetaEntity(
    @PrimaryKey
    val singletonId: Int = 1,
    val datasetId: String,
    val snapshotRevision: Long = 0,
    val nextSessionSequence: Long = 1,
)

@Entity(
    tableName = "register_sessions",
    indices = [
        Index(value = ["sequence"], unique = true),
        Index(value = ["openSlot"], unique = true),
    ],
)
internal data class RegisterSessionEntity(
    @PrimaryKey val id: String,
    val sequence: Long,
    val status: String,
    val openSlot: Int?,
    val openedAt: Long,
    val closedAt: Long?,
    val openingFloatYen: Long,
    val actualCashYen: Long?,
    val nextFloatYen: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
    val closeRevision: Long?,
)

@Entity(
    tableName = "cash_entries",
    foreignKeys = [
        ForeignKey(
            entity = RegisterSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "sequence"], unique = true),
        Index(value = ["sessionId"]),
    ],
)
internal data class CashEntryEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val sequence: Long,
    val kind: String,
    val occurredAt: Long,
    val productAmountYen: Long?,
    val receivedAmountYen: Long?,
    val amountYen: Long?,
    val isVoided: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
)

@Dao
internal interface LedgerDao {
    @Query("SELECT * FROM dataset_meta WHERE singletonId = 1")
    suspend fun getMeta(): DatasetMetaEntity?

    @Insert
    suspend fun insertMeta(meta: DatasetMetaEntity)

    @Update
    suspend fun updateMeta(meta: DatasetMetaEntity)

    @Query("SELECT * FROM register_sessions WHERE status = 'OPEN' LIMIT 1")
    suspend fun getOpenSession(): RegisterSessionEntity?

    @Query("SELECT * FROM register_sessions WHERE status = 'CLOSED' ORDER BY sequence DESC LIMIT 1")
    suspend fun getLatestClosedSession(): RegisterSessionEntity?

    @Query("SELECT * FROM register_sessions ORDER BY sequence DESC")
    suspend fun getAllSessions(): List<RegisterSessionEntity>

    @Query("SELECT * FROM register_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: String): RegisterSessionEntity?

    @Insert
    suspend fun insertSession(session: RegisterSessionEntity)

    @Update
    suspend fun updateSession(session: RegisterSessionEntity)

    @Query("SELECT MAX(sequence) FROM cash_entries WHERE sessionId = :sessionId")
    suspend fun getMaxEntrySequence(sessionId: String): Long?

    @Query("SELECT * FROM cash_entries WHERE id = :entryId")
    suspend fun getEntry(entryId: String): CashEntryEntity?

    @Query("SELECT * FROM cash_entries WHERE sessionId = :sessionId ORDER BY sequence DESC")
    suspend fun getEntries(sessionId: String): List<CashEntryEntity>

    @Query("SELECT * FROM cash_entries ORDER BY sessionId, sequence DESC")
    suspend fun getAllEntries(): List<CashEntryEntity>

    @Insert
    suspend fun insertEntry(entry: CashEntryEntity)

    @Update
    suspend fun updateEntry(entry: CashEntryEntity)

    @Query("DELETE FROM cash_entries")
    suspend fun deleteAllEntries()

    @Query("DELETE FROM register_sessions")
    suspend fun deleteAllSessions()
}

@Database(
    entities = [DatasetMetaEntity::class, RegisterSessionEntity::class, CashEntryEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class UbaregiDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao

    companion object {
        @Volatile private var instance: UbaregiDatabase? = null

        fun get(context: Context): UbaregiDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UbaregiDatabase::class.java,
                    "ubaregi.db",
                ).addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TRIGGER register_sessions_validate_insert
                            BEFORE INSERT ON register_sessions
                            BEGIN
                                SELECT CASE WHEN
                                    NEW.status NOT IN ('OPEN', 'CLOSED') OR
                                    (NEW.status = 'OPEN' AND (NEW.openSlot != 1 OR NEW.closedAt IS NOT NULL OR NEW.actualCashYen IS NOT NULL OR NEW.nextFloatYen IS NOT NULL OR NEW.closeRevision IS NOT NULL)) OR
                                    (NEW.status = 'CLOSED' AND (NEW.openSlot IS NOT NULL OR NEW.closedAt IS NULL OR NEW.actualCashYen IS NULL OR NEW.nextFloatYen IS NULL OR NEW.closeRevision IS NULL)) OR
                                    NEW.openingFloatYen < 0 OR NEW.openingFloatYen > 9999999
                                THEN RAISE(ABORT, 'invalid register session') END;
                            END
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TRIGGER register_sessions_validate_update
                            BEFORE UPDATE ON register_sessions
                            BEGIN
                                SELECT CASE WHEN
                                    NEW.status NOT IN ('OPEN', 'CLOSED') OR
                                    (NEW.status = 'OPEN' AND (NEW.openSlot != 1 OR NEW.closedAt IS NOT NULL OR NEW.actualCashYen IS NOT NULL OR NEW.nextFloatYen IS NOT NULL OR NEW.closeRevision IS NOT NULL)) OR
                                    (NEW.status = 'CLOSED' AND (NEW.openSlot IS NOT NULL OR NEW.closedAt IS NULL OR NEW.actualCashYen IS NULL OR NEW.nextFloatYen IS NULL OR NEW.closeRevision IS NULL)) OR
                                    NEW.openingFloatYen < 0 OR NEW.openingFloatYen > 9999999 OR
                                    (NEW.actualCashYen IS NOT NULL AND (NEW.actualCashYen < 0 OR NEW.actualCashYen > 9999999)) OR
                                    (NEW.nextFloatYen IS NOT NULL AND (NEW.nextFloatYen < 0 OR NEW.nextFloatYen > NEW.actualCashYen))
                                THEN RAISE(ABORT, 'invalid register session') END;
                            END
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TRIGGER cash_entries_validate_insert
                            BEFORE INSERT ON cash_entries
                            BEGIN
                                SELECT CASE WHEN
                                    NEW.kind NOT IN ('PAYMENT', 'CASH_IN', 'CASH_OUT') OR
                                    (NEW.kind = 'PAYMENT' AND (NEW.productAmountYen IS NULL OR NEW.receivedAmountYen IS NULL OR NEW.amountYen IS NOT NULL OR NEW.productAmountYen < 1 OR NEW.productAmountYen > 9999999 OR NEW.receivedAmountYen < NEW.productAmountYen OR NEW.receivedAmountYen > 9999999)) OR
                                    (NEW.kind IN ('CASH_IN', 'CASH_OUT') AND (NEW.amountYen IS NULL OR NEW.productAmountYen IS NOT NULL OR NEW.receivedAmountYen IS NOT NULL OR NEW.amountYen < 1 OR NEW.amountYen > 9999999))
                                THEN RAISE(ABORT, 'invalid cash entry') END;
                            END
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TRIGGER cash_entries_validate_update
                            BEFORE UPDATE ON cash_entries
                            BEGIN
                                SELECT CASE WHEN
                                    NEW.kind != OLD.kind OR NEW.sessionId != OLD.sessionId OR NEW.occurredAt != OLD.occurredAt OR
                                    NEW.kind NOT IN ('PAYMENT', 'CASH_IN', 'CASH_OUT') OR
                                    (NEW.kind = 'PAYMENT' AND (NEW.productAmountYen IS NULL OR NEW.receivedAmountYen IS NULL OR NEW.amountYen IS NOT NULL OR NEW.productAmountYen < 1 OR NEW.productAmountYen > 9999999 OR NEW.receivedAmountYen < NEW.productAmountYen OR NEW.receivedAmountYen > 9999999)) OR
                                    (NEW.kind IN ('CASH_IN', 'CASH_OUT') AND (NEW.amountYen IS NULL OR NEW.productAmountYen IS NOT NULL OR NEW.receivedAmountYen IS NOT NULL OR NEW.amountYen < 1 OR NEW.amountYen > 9999999))
                                THEN RAISE(ABORT, 'invalid cash entry') END;
                            END
                            """.trimIndent(),
                        )
                    }
                }).build().also { instance = it }
            }
    }
}

internal data class LedgerSnapshot(
    val meta: DatasetMetaEntity,
    val sessions: List<RegisterSessionEntity>,
    val entries: List<CashEntryEntity>,
) {
    val openSession: RegisterSessionEntity?
        get() = sessions.firstOrNull { it.status == STATUS_OPEN }

    val latestClosed: RegisterSessionEntity?
        get() = sessions.firstOrNull { it.status == STATUS_CLOSED }

    fun entriesFor(sessionId: String?): List<CashEntryEntity> =
        entries.filter { it.sessionId == sessionId }.sortedByDescending { it.sequence }
}

internal sealed interface MoneyParseResult {
    data class Valid(val value: Long) : MoneyParseResult
    data class Invalid(val message: String) : MoneyParseResult
}

internal object MoneyInput {
    fun parse(raw: String, label: String, allowZero: Boolean): MoneyParseResult {
        val normalized = raw.trim().map { char ->
            if (char in '０'..'９') (char.code - '０'.code + '0'.code).toChar() else char
        }.joinToString("")
        if (normalized.isEmpty()) return MoneyParseResult.Invalid("${label}を入力してください")
        if (normalized.any { it !in '0'..'9' }) {
            return MoneyParseResult.Invalid("${label}は数字だけで入力してください")
        }
        val value = normalized.toLongOrNull()
            ?: return MoneyParseResult.Invalid("${label}が大きすぎます")
        if (value > MAX_YEN) return MoneyParseResult.Invalid("${label}は9,999,999円以下で入力してください")
        if (!allowZero && value == 0L) return MoneyParseResult.Invalid("${label}は1円以上で入力してください")
        return MoneyParseResult.Valid(value)
    }
}

internal data class RegisterSummary(
    val paymentCollectedYen: Long,
    val cashInYen: Long,
    val cashOutYen: Long,
    val netCashYen: Long,
    val expectedCashYen: Long,
    val differenceYen: Long?,
    val actualChangeYen: Long?,
    val withdrawalYen: Long?,
)

internal object LedgerCalculator {
    fun entryChange(entry: CashEntryEntity): Long? =
        if (entry.kind == KIND_PAYMENT) {
            (entry.receivedAmountYen ?: 0L) - (entry.productAmountYen ?: 0L)
        } else {
            null
        }

    fun summary(session: RegisterSessionEntity, entries: List<CashEntryEntity>): RegisterSummary {
        var paymentCollected = 0L
        var cashIn = 0L
        var cashOut = 0L
        entries.filterNot { it.isVoided }.forEach { entry ->
            when (entry.kind) {
                KIND_PAYMENT -> {
                    paymentCollected = exactAdd(paymentCollected, entry.productAmountYen ?: 0L)
                    cashIn = exactAdd(cashIn, entry.receivedAmountYen ?: 0L)
                    cashOut = exactAdd(cashOut, entryChange(entry) ?: 0L)
                }
                KIND_CASH_IN -> cashIn = exactAdd(cashIn, entry.amountYen ?: 0L)
                KIND_CASH_OUT -> cashOut = exactAdd(cashOut, entry.amountYen ?: 0L)
            }
        }
        val netCash = exactAdd(cashIn, -cashOut)
        val expected = exactAdd(session.openingFloatYen, netCash)
        val actual = session.actualCashYen
        val next = session.nextFloatYen
        return RegisterSummary(
            paymentCollectedYen = paymentCollected,
            cashInYen = cashIn,
            cashOutYen = cashOut,
            netCashYen = netCash,
            expectedCashYen = expected,
            differenceYen = if (session.status == STATUS_CLOSED && actual != null) exactAdd(actual, -expected) else null,
            actualChangeYen = if (session.status == STATUS_CLOSED && actual != null) exactAdd(actual, -session.openingFloatYen) else null,
            withdrawalYen = if (session.status == STATUS_CLOSED && actual != null && next != null) exactAdd(actual, -next) else null,
        )
    }

    private fun exactAdd(left: Long, right: Long): Long = Math.addExact(left, right)
}

internal sealed interface LedgerOperation<out T> {
    data class Success<T>(val value: T, val alreadySaved: Boolean = false) : LedgerOperation<T>
    data class Failure(val message: String, val conflict: Boolean = false) : LedgerOperation<Nothing>
}

internal data class ClearResult(
    val sessionCount: Int,
    val entryCount: Int,
)

internal class LedgerRepository(private val database: UbaregiDatabase) {
    suspend fun loadSnapshot(): LedgerSnapshot = database.withTransaction {
        val dao = database.ledgerDao()
        val meta = ensureMeta(dao)
        LedgerSnapshot(meta, dao.getAllSessions(), dao.getAllEntries())
    }

    suspend fun latestCandidateYen(): Long? = database.withTransaction {
        database.ledgerDao().getLatestClosedSession()?.nextFloatYen
    }

    suspend fun startRegister(openingFloatYen: Long): LedgerOperation<RegisterSessionEntity> =
        database.withTransaction {
            val dao = database.ledgerDao()
            val meta = ensureMeta(dao)
            if (openingFloatYen !in 0L..MAX_YEN) {
                return@withTransaction LedgerOperation.Failure("初期釣銭は0円以上、9,999,999円以下で入力してください")
            }
            if (dao.getOpenSession() != null) {
                return@withTransaction LedgerOperation.Failure("すでに稼働中のレジがあります")
            }
            val now = Instant.now().toEpochMilli()
            val session = RegisterSessionEntity(
                id = UUID.randomUUID().toString(),
                sequence = meta.nextSessionSequence,
                status = STATUS_OPEN,
                openSlot = 1,
                openedAt = now,
                closedAt = null,
                openingFloatYen = openingFloatYen,
                actualCashYen = null,
                nextFloatYen = null,
                createdAt = now,
                updatedAt = now,
                revision = 1,
                closeRevision = null,
            )
            dao.insertSession(session)
            dao.updateMeta(meta.copy(snapshotRevision = meta.snapshotRevision + 1, nextSessionSequence = meta.nextSessionSequence + 1))
            LedgerOperation.Success(session)
        }

    suspend fun savePayment(
        entryId: String,
        sessionId: String,
        productAmountYen: Long,
        receivedAmountYen: Long,
    ): LedgerOperation<CashEntryEntity> = database.withTransaction {
        val dao = database.ledgerDao()
        val meta = ensureMeta(dao)
        val existing = dao.getEntry(entryId)
        if (existing != null) {
            return@withTransaction if (
                existing.sessionId == sessionId &&
                existing.kind == KIND_PAYMENT &&
                existing.productAmountYen == productAmountYen &&
                existing.receivedAmountYen == receivedAmountYen
            ) {
                LedgerOperation.Success(existing, alreadySaved = true)
            } else {
                LedgerOperation.Failure("この保存IDには別の入力が紐づいています。入力を確認してください", conflict = true)
            }
        }
        if (productAmountYen !in 1L..MAX_YEN) {
            return@withTransaction LedgerOperation.Failure("商品金額は1円以上、9,999,999円以下で入力してください")
        }
        if (receivedAmountYen !in 0L..MAX_YEN || receivedAmountYen < productAmountYen) {
            return@withTransaction LedgerOperation.Failure("受取金額が不足しています")
        }
        val session = dao.getSession(sessionId)
            ?: return@withTransaction LedgerOperation.Failure("対象レジが見つかりません")
        if (session.status != STATUS_OPEN) {
            return@withTransaction LedgerOperation.Failure("対象レジは締め済みです")
        }
        val now = Instant.now().toEpochMilli()
        val entry = CashEntryEntity(
            id = entryId,
            sessionId = sessionId,
            sequence = (dao.getMaxEntrySequence(sessionId) ?: 0L) + 1,
            kind = KIND_PAYMENT,
            occurredAt = now,
            productAmountYen = productAmountYen,
            receivedAmountYen = receivedAmountYen,
            amountYen = null,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertEntry(entry)
        dao.updateSession(session.copy(updatedAt = now, revision = session.revision + 1))
        dao.updateMeta(meta.copy(snapshotRevision = meta.snapshotRevision + 1))
        LedgerOperation.Success(entry)
    }

    suspend fun saveCashMovement(
        entryId: String,
        sessionId: String,
        kind: String,
        amountYen: Long,
    ): LedgerOperation<CashEntryEntity> = database.withTransaction {
        val dao = database.ledgerDao()
        val meta = ensureMeta(dao)
        val existing = dao.getEntry(entryId)
        if (existing != null) {
            return@withTransaction if (
                existing.sessionId == sessionId && existing.kind == kind && existing.amountYen == amountYen
            ) LedgerOperation.Success(existing, alreadySaved = true)
            else LedgerOperation.Failure("この保存IDには別の入力が紐づいています", conflict = true)
        }
        if (kind != KIND_CASH_IN && kind != KIND_CASH_OUT) {
            return@withTransaction LedgerOperation.Failure("補充または取出しを選択してください")
        }
        if (amountYen !in 1L..MAX_YEN) {
            return@withTransaction LedgerOperation.Failure("金額は1円以上、9,999,999円以下で入力してください")
        }
        val session = dao.getSession(sessionId)
            ?: return@withTransaction LedgerOperation.Failure("対象レジが見つかりません")
        if (session.status != STATUS_OPEN) return@withTransaction LedgerOperation.Failure("対象レジは締め済みです")
        val now = Instant.now().toEpochMilli()
        val entry = CashEntryEntity(
            id = entryId,
            sessionId = sessionId,
            sequence = (dao.getMaxEntrySequence(sessionId) ?: 0L) + 1,
            kind = kind,
            occurredAt = now,
            productAmountYen = null,
            receivedAmountYen = null,
            amountYen = amountYen,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertEntry(entry)
        dao.updateSession(session.copy(updatedAt = now, revision = session.revision + 1))
        dao.updateMeta(meta.copy(snapshotRevision = meta.snapshotRevision + 1))
        LedgerOperation.Success(entry)
    }

    suspend fun closeRegister(
        sessionId: String,
        expectedRevision: Long,
        actualCashYen: Long,
        nextFloatYen: Long,
    ): LedgerOperation<RegisterSessionEntity> = database.withTransaction {
        val dao = database.ledgerDao()
        val meta = ensureMeta(dao)
        val session = dao.getSession(sessionId)
            ?: return@withTransaction LedgerOperation.Failure("対象レジが見つかりません")
        if (session.status != STATUS_OPEN) return@withTransaction LedgerOperation.Failure("このレジはすでに締め済みです")
        if (session.revision != expectedRevision) {
            return@withTransaction LedgerOperation.Failure("明細が更新されています。画面を更新して再確認してください", conflict = true)
        }
        if (actualCashYen !in 0L..MAX_YEN) {
            return@withTransaction LedgerOperation.Failure("実残高は0円以上、9,999,999円以下で入力してください")
        }
        if (nextFloatYen !in 0L..actualCashYen) {
            return@withTransaction LedgerOperation.Failure("次回釣銭は実残高以下で入力してください")
        }
        val summary = LedgerCalculator.summary(session, dao.getEntries(sessionId))
        val now = Instant.now().toEpochMilli()
        val newRevision = session.revision + 1
        val closed = session.copy(
            status = STATUS_CLOSED,
            openSlot = null,
            closedAt = now,
            actualCashYen = actualCashYen,
            nextFloatYen = nextFloatYen,
            updatedAt = now,
            revision = newRevision,
            closeRevision = newRevision,
        )
        dao.updateSession(closed)
        dao.updateMeta(meta.copy(snapshotRevision = meta.snapshotRevision + 1))
        LedgerOperation.Success(closed)
    }

    suspend fun editRegister(
        sessionId: String,
        expectedRevision: Long,
        openingFloatYen: Long,
        actualCashYen: Long?,
        nextFloatYen: Long?,
    ): LedgerOperation<RegisterSessionEntity> = database.withTransaction {
        val dao = database.ledgerDao()
        val meta = ensureMeta(dao)
        val session = dao.getSession(sessionId)
            ?: return@withTransaction LedgerOperation.Failure("対象レジが見つかりません")
        if (session.revision != expectedRevision) {
            return@withTransaction LedgerOperation.Failure("レジ履歴が更新されています。画面を更新して再確認してください", conflict = true)
        }
        if (openingFloatYen !in 0L..MAX_YEN) {
            return@withTransaction LedgerOperation.Failure("初期釣銭は0円以上、9,999,999円以下で入力してください")
        }
        if (session.status == STATUS_OPEN && (actualCashYen != null || nextFloatYen != null)) {
            return@withTransaction LedgerOperation.Failure("稼働中のレジでは実残高と次回釣銭を編集できません")
        }
        if (session.status == STATUS_CLOSED) {
            if (actualCashYen == null || actualCashYen !in 0L..MAX_YEN) {
                return@withTransaction LedgerOperation.Failure("実残高は0円以上、9,999,999円以下で入力してください")
            }
            if (nextFloatYen == null || nextFloatYen !in 0L..actualCashYen) {
                return@withTransaction LedgerOperation.Failure("次回釣銭は実残高以下で入力してください")
            }
        }
        val now = Instant.now().toEpochMilli()
        val updated = session.copy(
            openingFloatYen = openingFloatYen,
            actualCashYen = if (session.status == STATUS_CLOSED) actualCashYen else null,
            nextFloatYen = if (session.status == STATUS_CLOSED) nextFloatYen else null,
            updatedAt = now,
            revision = session.revision + 1,
        )
        dao.updateSession(updated)
        dao.updateMeta(meta.copy(snapshotRevision = meta.snapshotRevision + 1))
        LedgerOperation.Success(updated)
    }

    suspend fun editEntry(
        entryId: String,
        expectedSessionRevision: Long,
        expectedEntryRevision: Long,
        productAmountYen: Long?,
        receivedAmountYen: Long?,
        amountYen: Long?,
    ): LedgerOperation<CashEntryEntity> = database.withTransaction {
        val dao = database.ledgerDao()
        val meta = ensureMeta(dao)
        val entry = dao.getEntry(entryId)
            ?: return@withTransaction LedgerOperation.Failure("対象明細が見つかりません")
        val session = dao.getSession(entry.sessionId)
            ?: return@withTransaction LedgerOperation.Failure("対象レジが見つかりません")
        if (session.revision != expectedSessionRevision || entry.revision != expectedEntryRevision) {
            return@withTransaction LedgerOperation.Failure("履歴が更新されています。画面を更新して再確認してください", conflict = true)
        }
        if (entry.isVoided) {
            return@withTransaction LedgerOperation.Failure("取消済み明細は編集できません")
        }
        if (entry.kind == KIND_PAYMENT) {
            if (productAmountYen !in 1L..MAX_YEN) {
                return@withTransaction LedgerOperation.Failure("商品金額は1円以上、9,999,999円以下で入力してください")
            }
            if (receivedAmountYen !in 0L..MAX_YEN || receivedAmountYen!! < productAmountYen!!) {
                return@withTransaction LedgerOperation.Failure("受取金額が不足しています")
            }
        } else if (entry.kind == KIND_CASH_IN || entry.kind == KIND_CASH_OUT) {
            if (amountYen !in 1L..MAX_YEN) {
                return@withTransaction LedgerOperation.Failure("金額は1円以上、9,999,999円以下で入力してください")
            }
        } else {
            return@withTransaction LedgerOperation.Failure("不明な明細種別です")
        }
        val now = Instant.now().toEpochMilli()
        val updatedEntry = entry.copy(
            productAmountYen = if (entry.kind == KIND_PAYMENT) productAmountYen else null,
            receivedAmountYen = if (entry.kind == KIND_PAYMENT) receivedAmountYen else null,
            amountYen = if (entry.kind != KIND_PAYMENT) amountYen else null,
            updatedAt = now,
            revision = entry.revision + 1,
        )
        dao.updateEntry(updatedEntry)
        dao.updateSession(session.copy(updatedAt = now, revision = session.revision + 1))
        dao.updateMeta(meta.copy(snapshotRevision = meta.snapshotRevision + 1))
        LedgerOperation.Success(updatedEntry)
    }

    suspend fun cancelEntry(
        entryId: String,
        expectedSessionRevision: Long,
        expectedEntryRevision: Long,
    ): LedgerOperation<CashEntryEntity> = database.withTransaction {
        val dao = database.ledgerDao()
        val meta = ensureMeta(dao)
        val entry = dao.getEntry(entryId)
            ?: return@withTransaction LedgerOperation.Failure("対象明細が見つかりません")
        val session = dao.getSession(entry.sessionId)
            ?: return@withTransaction LedgerOperation.Failure("対象レジが見つかりません")
        if (session.revision != expectedSessionRevision || entry.revision != expectedEntryRevision) {
            return@withTransaction LedgerOperation.Failure("履歴が更新されています。画面を更新して再確認してください", conflict = true)
        }
        if (entry.isVoided) {
            return@withTransaction LedgerOperation.Failure("この明細はすでに取消済みです")
        }
        val now = Instant.now().toEpochMilli()
        val cancelled = entry.copy(
            isVoided = true,
            updatedAt = now,
            revision = entry.revision + 1,
        )
        dao.updateEntry(cancelled)
        dao.updateSession(session.copy(updatedAt = now, revision = session.revision + 1))
        dao.updateMeta(meta.copy(snapshotRevision = meta.snapshotRevision + 1))
        LedgerOperation.Success(cancelled)
    }

    suspend fun clearAll(expectedSnapshotRevision: Long): LedgerOperation<ClearResult> = database.withTransaction {
        val dao = database.ledgerDao()
        val meta = ensureMeta(dao)
        if (meta.snapshotRevision != expectedSnapshotRevision) {
            return@withTransaction LedgerOperation.Failure("履歴が更新されています。画面を更新して再確認してください", conflict = true)
        }
        val sessionCount = dao.getAllSessions().size
        val entryCount = dao.getAllEntries().size
        dao.deleteAllEntries()
        dao.deleteAllSessions()
        dao.updateMeta(meta.copy(snapshotRevision = meta.snapshotRevision + 1))
        LedgerOperation.Success(ClearResult(sessionCount, entryCount))
    }

    private suspend fun ensureMeta(dao: LedgerDao): DatasetMetaEntity {
        dao.getMeta()?.let { return it }
        val meta = DatasetMetaEntity(datasetId = UUID.randomUUID().toString())
        dao.insertMeta(meta)
        return meta
    }
}
