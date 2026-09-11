package io.github.mock108.ubaregi.data

import androidx.room.withTransaction
import io.github.mock108.ubaregi.domain.RegisterSummary
import io.github.mock108.ubaregi.domain.calculateRegisterSummary
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed class RegisterDataException(message: String) : IllegalStateException(message)

class InvalidRegisterData(message: String) : RegisterDataException(message)

class OpenRegisterAlreadyExists : RegisterDataException("OPENレジは最大1件です")

class RegisterNotFound : RegisterDataException("対象レジが見つかりません")

class EntryNotFound : RegisterDataException("対象明細が見つかりません")

class RegisterIsNotOpen : RegisterDataException("レジがOPENではありません")

class RegisterIsAlreadyClosed : RegisterDataException("締め済みレジは再度締められません")

class EntryIsAlreadyVoided : RegisterDataException("取消済み明細は編集できません")

class EntryIdConflict : RegisterDataException("同じ明細IDに異なる内容が指定されています")

class ConcurrentDataModification : RegisterDataException("データが更新されています。最新状態を読み直してください")

data class SavedEntry(
    val entry: CashEntry,
    val alreadySaved: Boolean,
)

interface RegisterRepository {
    suspend fun initializeDatasetMeta(): DatasetMeta

    suspend fun getDatasetMeta(): DatasetMeta

    fun observeDatasetMeta(): Flow<DatasetMeta?>

    suspend fun getOpenRegister(): RegisterSession?

    fun observeOpenRegister(): Flow<RegisterSession?>

    suspend fun getLatestClosedRegister(): RegisterSession?

    suspend fun listRegisters(): List<RegisterSession>

    fun observeRegisters(): Flow<List<RegisterSession>>

    suspend fun startRegister(
        openingFloatYen: Long,
        sessionId: String = UUID.randomUUID().toString(),
        openedAt: Long? = null,
    ): RegisterSession

    suspend fun addPayment(
        sessionId: String,
        productAmountYen: Long,
        receivedAmountYen: Long,
        entryId: String = UUID.randomUUID().toString(),
        occurredAt: Long? = null,
    ): SavedEntry

    suspend fun addCashIn(
        sessionId: String,
        amountYen: Long,
        entryId: String = UUID.randomUUID().toString(),
        occurredAt: Long? = null,
    ): SavedEntry

    suspend fun addCashOut(
        sessionId: String,
        amountYen: Long,
        entryId: String = UUID.randomUUID().toString(),
        occurredAt: Long? = null,
    ): SavedEntry

    suspend fun listEntries(sessionId: String): List<CashEntry>

    fun observeEntries(sessionId: String): Flow<List<CashEntry>>

    suspend fun summarizeRegister(sessionId: String): RegisterSummary

    suspend fun editPayment(
        entryId: String,
        expectedEntryRevision: Long,
        expectedSessionRevision: Long,
        productAmountYen: Long,
        receivedAmountYen: Long,
        updatedAt: Long? = null,
    ): CashEntry

    suspend fun editCashAdjustment(
        entryId: String,
        expectedEntryRevision: Long,
        expectedSessionRevision: Long,
        amountYen: Long,
        updatedAt: Long? = null,
    ): CashEntry

    suspend fun voidEntry(
        entryId: String,
        expectedEntryRevision: Long,
        expectedSessionRevision: Long,
        updatedAt: Long? = null,
    ): CashEntry

    suspend fun closeRegister(
        sessionId: String,
        expectedSessionRevision: Long,
        actualCashYen: Long,
        nextFloatYen: Long,
        closedAt: Long? = null,
    ): RegisterSession

    suspend fun editRegister(
        sessionId: String,
        expectedRevision: Long,
        openingFloatYen: Long,
        actualCashYen: Long? = null,
        nextFloatYen: Long? = null,
        updatedAt: Long? = null,
    ): RegisterSession

    suspend fun clearAllHistory(expectedSnapshotRevision: Long? = null): DatasetMeta
}

class RoomRegisterRepository(
    private val database: UbaregiDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RegisterRepository {
    private val metaDao get() = database.datasetMetaDao()
    private val sessionDao get() = database.registerSessionDao()
    private val entryDao get() = database.cashEntryDao()

    override suspend fun initializeDatasetMeta(): DatasetMeta = database.withTransaction {
        getOrCreateDatasetMeta()
    }

    override suspend fun getDatasetMeta(): DatasetMeta = initializeDatasetMeta()

    override fun observeDatasetMeta(): Flow<DatasetMeta?> = metaDao.observe()

    override suspend fun getOpenRegister(): RegisterSession? = sessionDao.findOpen()

    override fun observeOpenRegister(): Flow<RegisterSession?> =
        sessionDao.observeAll().map { sessions -> sessions.firstOrNull { it.status == RegisterStatus.OPEN } }

    override suspend fun getLatestClosedRegister(): RegisterSession? = sessionDao.findLatestClosed()

    override suspend fun listRegisters(): List<RegisterSession> = sessionDao.findAll()

    override fun observeRegisters(): Flow<List<RegisterSession>> = sessionDao.observeAll()

    override suspend fun startRegister(
        openingFloatYen: Long,
        sessionId: String,
        openedAt: Long?,
    ): RegisterSession {
        validateAmount(openingFloatYen, allowZero = true, fieldName = "初期釣銭")
        validateUuid(sessionId, "レジID")
        val now = openedAt ?: clock()
        validateTimestamp(now)

        return database.withTransaction {
            val meta = getOrCreateDatasetMeta()
            if (sessionDao.findOpen() != null) throw OpenRegisterAlreadyExists()

            val sequence = meta.nextSessionSequence
            val session = RegisterSession(
                id = sessionId,
                sequence = sequence,
                status = RegisterStatus.OPEN,
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
            sessionDao.insert(session)
            advanceMeta(meta, Math.addExact(sequence, 1))
            session
        }
    }

    override suspend fun addPayment(
        sessionId: String,
        productAmountYen: Long,
        receivedAmountYen: Long,
        entryId: String,
        occurredAt: Long?,
    ): SavedEntry = saveEntry(
        sessionId = sessionId,
        entryId = entryId,
        occurredAt = occurredAt,
        kind = CashEntryKind.PAYMENT,
        productAmountYen = productAmountYen,
        receivedAmountYen = receivedAmountYen,
        amountYen = null,
    )

    override suspend fun addCashIn(
        sessionId: String,
        amountYen: Long,
        entryId: String,
        occurredAt: Long?,
    ): SavedEntry = saveEntry(
        sessionId = sessionId,
        entryId = entryId,
        occurredAt = occurredAt,
        kind = CashEntryKind.CASH_IN,
        productAmountYen = null,
        receivedAmountYen = null,
        amountYen = amountYen,
    )

    override suspend fun addCashOut(
        sessionId: String,
        amountYen: Long,
        entryId: String,
        occurredAt: Long?,
    ): SavedEntry = saveEntry(
        sessionId = sessionId,
        entryId = entryId,
        occurredAt = occurredAt,
        kind = CashEntryKind.CASH_OUT,
        productAmountYen = null,
        receivedAmountYen = null,
        amountYen = amountYen,
    )

    override suspend fun listEntries(sessionId: String): List<CashEntry> {
        validateUuid(sessionId, "レジID")
        return entryDao.findForSession(sessionId)
    }

    override fun observeEntries(sessionId: String): Flow<List<CashEntry>> = entryDao.observeForSession(sessionId)

    override suspend fun summarizeRegister(sessionId: String): RegisterSummary = database.withTransaction {
        validateUuid(sessionId, "レジID")
        val session = sessionDao.findById(sessionId) ?: throw RegisterNotFound()
        calculateRegisterSummary(session, entryDao.findForSession(sessionId))
    }

    override suspend fun editPayment(
        entryId: String,
        expectedEntryRevision: Long,
        expectedSessionRevision: Long,
        productAmountYen: Long,
        receivedAmountYen: Long,
        updatedAt: Long?,
    ): CashEntry = database.withTransaction {
        validateUuid(entryId, "明細ID")
        validatePayment(productAmountYen, receivedAmountYen)
        val entry = entryDao.findById(entryId) ?: throw EntryNotFound()
        val session = sessionDao.findById(entry.sessionId) ?: throw RegisterNotFound()
        checkRevisions(entry, session, expectedEntryRevision, expectedSessionRevision)
        checkEditable(entry)
        if (entry.kind != CashEntryKind.PAYMENT) throw InvalidRegisterData("PAYMENT明細ではありません")

        val now = updatedAt ?: clock()
        validateTimestamp(now)
        val newEntryRevision = Math.addExact(entry.revision, 1)
        checkUpdated(
            entryDao.updatePayment(
                id = entry.id,
                expectedRevision = entry.revision,
                newRevision = newEntryRevision,
                productAmountYen = productAmountYen,
                receivedAmountYen = receivedAmountYen,
                updatedAt = now,
            ),
        ) { ConcurrentDataModification() }
        touchSession(session, now)
        advanceMeta(getOrCreateDatasetMeta())
        entry.copy(
            productAmountYen = productAmountYen,
            receivedAmountYen = receivedAmountYen,
            updatedAt = now,
            revision = newEntryRevision,
        )
    }

    override suspend fun editCashAdjustment(
        entryId: String,
        expectedEntryRevision: Long,
        expectedSessionRevision: Long,
        amountYen: Long,
        updatedAt: Long?,
    ): CashEntry = database.withTransaction {
        validateUuid(entryId, "明細ID")
        validateAmount(amountYen, allowZero = false, fieldName = "補充・取出し額")
        val entry = entryDao.findById(entryId) ?: throw EntryNotFound()
        val session = sessionDao.findById(entry.sessionId) ?: throw RegisterNotFound()
        checkRevisions(entry, session, expectedEntryRevision, expectedSessionRevision)
        checkEditable(entry)
        if (entry.kind == CashEntryKind.PAYMENT) throw InvalidRegisterData("補充・取出し明細ではありません")

        val now = updatedAt ?: clock()
        validateTimestamp(now)
        val newEntryRevision = Math.addExact(entry.revision, 1)
        checkUpdated(
            entryDao.updateAdjustment(
                id = entry.id,
                expectedRevision = entry.revision,
                newRevision = newEntryRevision,
                amountYen = amountYen,
                updatedAt = now,
            ),
        ) { ConcurrentDataModification() }
        touchSession(session, now)
        advanceMeta(getOrCreateDatasetMeta())
        entry.copy(amountYen = amountYen, updatedAt = now, revision = newEntryRevision)
    }

    override suspend fun voidEntry(
        entryId: String,
        expectedEntryRevision: Long,
        expectedSessionRevision: Long,
        updatedAt: Long?,
    ): CashEntry = database.withTransaction {
        validateUuid(entryId, "明細ID")
        val entry = entryDao.findById(entryId) ?: throw EntryNotFound()
        val session = sessionDao.findById(entry.sessionId) ?: throw RegisterNotFound()
        checkRevisions(entry, session, expectedEntryRevision, expectedSessionRevision)
        if (entry.isVoided) return@withTransaction entry

        val now = updatedAt ?: clock()
        validateTimestamp(now)
        val newEntryRevision = Math.addExact(entry.revision, 1)
        checkUpdated(
            entryDao.voidEntry(
                id = entry.id,
                expectedRevision = entry.revision,
                newRevision = newEntryRevision,
                updatedAt = now,
            ),
        ) { ConcurrentDataModification() }
        touchSession(session, now)
        advanceMeta(getOrCreateDatasetMeta())
        entry.copy(isVoided = true, updatedAt = now, revision = newEntryRevision)
    }

    override suspend fun closeRegister(
        sessionId: String,
        expectedSessionRevision: Long,
        actualCashYen: Long,
        nextFloatYen: Long,
        closedAt: Long?,
    ): RegisterSession = database.withTransaction {
        validateUuid(sessionId, "レジID")
        validateAmount(actualCashYen, allowZero = true, fieldName = "実残高")
        validateAmount(nextFloatYen, allowZero = true, fieldName = "次回釣銭")
        if (nextFloatYen > actualCashYen) throw InvalidRegisterData("次回釣銭は実残高を超えられません")

        val session = sessionDao.findById(sessionId) ?: throw RegisterNotFound()
        if (session.status != RegisterStatus.OPEN) throw RegisterIsAlreadyClosed()
        if (session.revision != expectedSessionRevision) throw ConcurrentDataModification()
        // Recalculate inside the same transaction immediately before closing.
        calculateRegisterSummary(session, entryDao.findForSession(sessionId))

        val now = closedAt ?: clock()
        validateTimestamp(now)
        val newRevision = Math.addExact(session.revision, 1)
        checkUpdated(
            sessionDao.close(
                id = session.id,
                expectedRevision = session.revision,
                newRevision = newRevision,
                actualCashYen = actualCashYen,
                nextFloatYen = nextFloatYen,
                closedAt = now,
                updatedAt = now,
            ),
        ) { ConcurrentDataModification() }
        advanceMeta(getOrCreateDatasetMeta())
        session.copy(
            status = RegisterStatus.CLOSED,
            openSlot = null,
            closedAt = now,
            actualCashYen = actualCashYen,
            nextFloatYen = nextFloatYen,
            updatedAt = now,
            revision = newRevision,
            closeRevision = newRevision,
        )
    }

    override suspend fun editRegister(
        sessionId: String,
        expectedRevision: Long,
        openingFloatYen: Long,
        actualCashYen: Long?,
        nextFloatYen: Long?,
        updatedAt: Long?,
    ): RegisterSession = database.withTransaction {
        validateUuid(sessionId, "レジID")
        validateAmount(openingFloatYen, allowZero = true, fieldName = "初期釣銭")
        val session = sessionDao.findById(sessionId) ?: throw RegisterNotFound()
        if (session.revision != expectedRevision) throw ConcurrentDataModification()
        if (session.status == RegisterStatus.OPEN && (actualCashYen != null || nextFloatYen != null)) {
            throw InvalidRegisterData("OPENレジの締め値は未設定である必要があります")
        }
        if (session.status == RegisterStatus.CLOSED) {
            val actual = actualCashYen ?: throw InvalidRegisterData("実残高を指定してください")
            val next = nextFloatYen ?: throw InvalidRegisterData("次回釣銭を指定してください")
            validateAmount(actual, allowZero = true, fieldName = "実残高")
            validateAmount(next, allowZero = true, fieldName = "次回釣銭")
            if (next > actual) throw InvalidRegisterData("次回釣銭は実残高を超えられません")
        }

        val now = updatedAt ?: clock()
        validateTimestamp(now)
        val newRevision = Math.addExact(session.revision, 1)
        checkUpdated(
            sessionDao.updateDetails(
                id = session.id,
                expectedRevision = session.revision,
                newRevision = newRevision,
                openingFloatYen = openingFloatYen,
                actualCashYen = actualCashYen,
                nextFloatYen = nextFloatYen,
                updatedAt = now,
            ),
        ) { ConcurrentDataModification() }
        advanceMeta(getOrCreateDatasetMeta())
        session.copy(
            openingFloatYen = openingFloatYen,
            actualCashYen = actualCashYen,
            nextFloatYen = nextFloatYen,
            updatedAt = now,
            revision = newRevision,
        )
    }

    override suspend fun clearAllHistory(expectedSnapshotRevision: Long?): DatasetMeta = database.withTransaction {
        val meta = getOrCreateDatasetMeta()
        if (expectedSnapshotRevision != null && meta.snapshotRevision != expectedSnapshotRevision) {
            throw ConcurrentDataModification()
        }
        entryDao.deleteAll()
        sessionDao.deleteAll()
        advanceMeta(meta)
    }

    private suspend fun saveEntry(
        sessionId: String,
        entryId: String,
        occurredAt: Long?,
        kind: CashEntryKind,
        productAmountYen: Long?,
        receivedAmountYen: Long?,
        amountYen: Long?,
    ): SavedEntry = database.withTransaction {
        validateUuid(sessionId, "レジID")
        validateUuid(entryId, "明細ID")

        // Idempotency is resolved before checking the target register state or validating
        // a new write. A retry after closing must not mutate anything or be rejected as a
        // new write merely because the register is no longer OPEN.
        val existing = entryDao.findById(entryId)
        if (existing != null) {
            if (existing.matchesBusinessContent(sessionId, kind, productAmountYen, receivedAmountYen, amountYen)) {
                return@withTransaction SavedEntry(existing, alreadySaved = true)
            }
            throw EntryIdConflict()
        }

        when (kind) {
            CashEntryKind.PAYMENT -> validatePayment(
                requireNotNull(productAmountYen),
                requireNotNull(receivedAmountYen),
            )

            CashEntryKind.CASH_IN, CashEntryKind.CASH_OUT ->
                validateAmount(requireNotNull(amountYen), allowZero = false, fieldName = "補充・取出し額")
        }

        val session = sessionDao.findById(sessionId) ?: throw RegisterNotFound()
        if (session.status != RegisterStatus.OPEN) throw RegisterIsNotOpen()
        val now = occurredAt ?: clock()
        validateTimestamp(now)
        val entry = CashEntry(
            id = entryId,
            sessionId = sessionId,
            sequence = entryDao.nextSequence(sessionId),
            kind = kind,
            occurredAt = now,
            productAmountYen = productAmountYen,
            receivedAmountYen = receivedAmountYen,
            amountYen = amountYen,
            isVoided = false,
            createdAt = now,
            updatedAt = now,
            revision = 1,
        )
        entryDao.insert(entry)
        touchSession(session, now)
        advanceMeta(getOrCreateDatasetMeta())
        SavedEntry(entry, alreadySaved = false)
    }

    private suspend fun getOrCreateDatasetMeta(): DatasetMeta {
        return metaDao.get() ?: DatasetMeta(datasetId = UUID.randomUUID().toString()).also { metaDao.insert(it) }
    }

    private suspend fun advanceMeta(meta: DatasetMeta, nextSessionSequence: Long = meta.nextSessionSequence): DatasetMeta {
        val newSnapshot = Math.addExact(meta.snapshotRevision, 1)
        checkUpdated(
            metaDao.updateCounters(
                expectedSnapshotRevision = meta.snapshotRevision,
                newSnapshotRevision = newSnapshot,
                newNextSessionSequence = nextSessionSequence,
            ),
        ) { ConcurrentDataModification() }
        return meta.copy(snapshotRevision = newSnapshot, nextSessionSequence = nextSessionSequence)
    }

    private suspend fun touchSession(session: RegisterSession, now: Long): RegisterSession {
        val newRevision = Math.addExact(session.revision, 1)
        checkUpdated(
            sessionDao.touch(
                id = session.id,
                expectedRevision = session.revision,
                newRevision = newRevision,
                updatedAt = now,
            ),
        ) { ConcurrentDataModification() }
        return session.copy(revision = newRevision, updatedAt = now)
    }

    private fun checkRevisions(
        entry: CashEntry,
        session: RegisterSession,
        expectedEntryRevision: Long,
        expectedSessionRevision: Long,
    ) {
        if (entry.revision != expectedEntryRevision || session.revision != expectedSessionRevision) {
            throw ConcurrentDataModification()
        }
    }

    private fun checkEditable(entry: CashEntry) {
        if (entry.isVoided) throw EntryIsAlreadyVoided()
    }

    private fun validatePayment(productAmountYen: Long, receivedAmountYen: Long) {
        validateAmount(productAmountYen, allowZero = false, fieldName = "商品金額")
        validateAmount(receivedAmountYen, allowZero = true, fieldName = "受取金額")
        if (receivedAmountYen < productAmountYen) throw InvalidRegisterData("受取金額が商品金額より少なくなっています")
    }

    private fun validateAmount(value: Long, allowZero: Boolean, fieldName: String) {
        val lowerBound = if (allowZero) 0 else 1
        if (value !in lowerBound..MAX_AMOUNT_YEN) {
            throw InvalidRegisterData("$fieldName は${lowerBound}〜${MAX_AMOUNT_YEN}円で入力してください")
        }
    }

    private fun validateTimestamp(value: Long) {
        if (value < 0) throw InvalidRegisterData("日時はUTCのepoch millisecondsで指定してください")
    }

    private fun validateUuid(value: String, fieldName: String) {
        val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        if (!uuidPattern.matches(value)) throw InvalidRegisterData("$fieldName はUUIDで指定してください")
    }

    private fun checkUpdated(rows: Int, error: () -> RegisterDataException) {
        if (rows != 1) throw error()
    }

    private fun CashEntry.matchesBusinessContent(
        sessionId: String,
        kind: CashEntryKind,
        productAmountYen: Long?,
        receivedAmountYen: Long?,
        amountYen: Long?,
    ): Boolean = this.sessionId == sessionId && this.kind == kind &&
        when (kind) {
            CashEntryKind.PAYMENT ->
                this.productAmountYen == productAmountYen && this.receivedAmountYen == receivedAmountYen

            CashEntryKind.CASH_IN, CashEntryKind.CASH_OUT -> this.amountYen == amountYen
        }
}
