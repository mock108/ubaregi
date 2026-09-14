@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.mock108.ubaregi

import androidx.lifecycle.SavedStateHandle
import io.github.mock108.ubaregi.data.CashEntry
import io.github.mock108.ubaregi.data.CashEntryKind
import io.github.mock108.ubaregi.data.ConcurrentDataModification
import io.github.mock108.ubaregi.data.DatasetMeta
import io.github.mock108.ubaregi.data.EntryIdConflict
import io.github.mock108.ubaregi.data.EntryNotFound
import io.github.mock108.ubaregi.data.InvalidRegisterData
import io.github.mock108.ubaregi.data.OpenRegisterAlreadyExists
import io.github.mock108.ubaregi.data.RegisterIsAlreadyClosed
import io.github.mock108.ubaregi.data.RegisterNotFound
import io.github.mock108.ubaregi.data.RegisterRepository
import io.github.mock108.ubaregi.data.RegisterSession
import io.github.mock108.ubaregi.data.RegisterStatus
import io.github.mock108.ubaregi.data.SavedEntry
import io.github.mock108.ubaregi.domain.RegisterSummary
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ViewModelsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun registerViewModelLoadsInitialStateAndRejectsSecondOpenRegister() = runTest(dispatcher) {
        val repository = FakeRegisterRepository()
        val viewModel = RegisterViewModel(repository, SavedStateHandle())
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.openRegister)
        viewModel.onOpeningFloatChanged("１０００")
        viewModel.startRegister()
        advanceUntilIdle()

        assertEquals(RegisterStatus.OPEN, viewModel.uiState.value.openRegister?.status)
        viewModel.onOpeningFloatChanged("500")
        viewModel.startRegister()
        advanceUntilIdle()
        assertEquals("稼働中のレジは最大1件です", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun calculatorKeepsPendingIdAndInputOnFailureThenRetriesIdempotently() = runTest(dispatcher) {
        val repository = FakeRegisterRepository()
        val session = repository.startRegister(1_000)
        repository.failNextPayment = true
        val viewModel = CalculatorViewModel(repository, SavedStateHandle())
        advanceUntilIdle()
        viewModel.selectRegister(session.id)
        viewModel.onProductChanged("１５００")
        viewModel.onReceivedChanged("２０００")
        viewModel.savePayment()
        advanceUntilIdle()

        val failed = viewModel.uiState.value
        assertEquals("１５００", failed.productText)
        assertEquals("２０００", failed.receivedText)
        assertNotNull(failed.pendingEntryId)

        viewModel.savePayment()
        advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.productText)
        assertNull(viewModel.uiState.value.pendingEntryId)
        assertEquals(1, repository.listEntries(session.id).size)
    }

    @Test
    fun calculatorStartsOnCalculationTabAndKeepsInputWhenTabChanges() = runTest(dispatcher) {
        val repository = FakeRegisterRepository()
        val viewModel = CalculatorViewModel(repository, SavedStateHandle())
        advanceUntilIdle()

        assertEquals(CalculatorTab.CALCULATE, viewModel.uiState.value.selectedTab)
        viewModel.onProductChanged("120")
        viewModel.onReceivedChanged("200")
        viewModel.selectTab(CalculatorTab.RECORDS)
        assertEquals("120", viewModel.uiState.value.productText)
        assertEquals("200", viewModel.uiState.value.receivedText)
        viewModel.selectTab(CalculatorTab.CALCULATE)
        assertEquals(CalculatorTab.CALCULATE, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun calculatorKeypadMovesFocusDeletesAndClearsOnlyFocusedInput() = runTest(dispatcher) {
        val repository = FakeRegisterRepository()
        val viewModel = CalculatorViewModel(repository, SavedStateHandle())
        advanceUntilIdle()

        viewModel.appendDigit(1)
        viewModel.appendDigit(2)
        assertEquals("12", viewModel.uiState.value.productText)
        viewModel.nextInput()
        assertEquals(CalculatorInputField.RECEIVED, viewModel.uiState.value.focusedField)
        viewModel.appendDigit(5)
        viewModel.appendDigit(0)
        viewModel.deleteLastDigit()
        assertEquals("5", viewModel.uiState.value.receivedText)
        viewModel.clearFocusedInput()
        assertEquals("", viewModel.uiState.value.receivedText)
        assertEquals("12", viewModel.uiState.value.productText)
        viewModel.moveFocus()
        assertEquals(CalculatorInputField.PRODUCT, viewModel.uiState.value.focusedField)
    }

    @Test
    fun calculatorShowsOnlyPaymentHistoryAndRejectsVoidedEdit() = runTest(dispatcher) {
        val repository = FakeRegisterRepository()
        val session = repository.startRegister(1_000)
        repository.addPayment(session.id, 100, 500)
        val viewModel = CalculatorViewModel(repository, SavedStateHandle())
        advanceUntilIdle()
        viewModel.selectRegister(session.id)
        advanceUntilIdle()
        viewModel.selectTab(CalculatorTab.RECORDS)
        assertEquals(1, viewModel.uiState.value.paymentEntries.size)

        val voided = viewModel.uiState.value.paymentEntries.single().copy(isVoided = true)
        viewModel.showPaymentEditDialog(voided)
        assertNull(viewModel.uiState.value.editEntryId)
    }

    @Test
    fun closeValidationAndClearHistoryReturnToNotStarted() = runTest(dispatcher) {
        val repository = FakeRegisterRepository()
        val viewModel = RegisterViewModel(repository, SavedStateHandle())
        advanceUntilIdle()
        viewModel.onOpeningFloatChanged("1000")
        viewModel.startRegister()
        advanceUntilIdle()

        viewModel.showCloseDialog()
        viewModel.onCloseActualChanged("1000")
        viewModel.onCloseNextFloatChanged("1500")
        viewModel.requestCloseConfirmation()
        assertEquals("次回に残す釣銭は実際に数えた手元現金を超えられません", viewModel.uiState.value.errorMessage)

        viewModel.onCloseNextFloatChanged("500")
        viewModel.requestCloseConfirmation()
        assertTrue(viewModel.uiState.value.isCloseConfirmationVisible)
        viewModel.closeRegister()
        advanceUntilIdle()
        assertEquals(RegisterStatus.CLOSED, viewModel.uiState.value.selectedRegister?.status)

        viewModel.showClearHistoryDialog()
        advanceUntilIdle()
        viewModel.clearAllHistory()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.openRegister)
        assertTrue(viewModel.uiState.value.registers.isEmpty())
    }
}

private class FakeRegisterRepository : RegisterRepository {
    private val metaFlow = MutableStateFlow(DatasetMeta(datasetId = UUID.randomUUID().toString()))
    private val sessionsFlow = MutableStateFlow<List<RegisterSession>>(emptyList())
    private val entriesBySession = mutableMapOf<String, MutableStateFlow<List<CashEntry>>>()
    var failNextPayment = false

    override suspend fun initializeDatasetMeta(): DatasetMeta = metaFlow.value
    override suspend fun getDatasetMeta(): DatasetMeta = metaFlow.value
    override fun observeDatasetMeta(): Flow<DatasetMeta?> = metaFlow.asStateFlow()
    override suspend fun getOpenRegister(): RegisterSession? = sessionsFlow.value.firstOrNull { it.status == RegisterStatus.OPEN }
    override fun observeOpenRegister(): Flow<RegisterSession?> = MutableStateFlow(sessionsFlow.value.firstOrNull { it.status == RegisterStatus.OPEN })
    override suspend fun getLatestClosedRegister(): RegisterSession? = sessionsFlow.value.firstOrNull { it.status == RegisterStatus.CLOSED }
    override suspend fun listRegisters(): List<RegisterSession> = sessionsFlow.value
    override fun observeRegisters(): Flow<List<RegisterSession>> = sessionsFlow.asStateFlow()

    override suspend fun startRegister(openingFloatYen: Long, sessionId: String, openedAt: Long?): RegisterSession {
        if (getOpenRegister() != null) throw OpenRegisterAlreadyExists()
        val now = openedAt ?: 1_000L
        val session = RegisterSession(
            id = sessionId,
            sequence = metaFlow.value.nextSessionSequence,
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
        sessionsFlow.value = listOf(session) + sessionsFlow.value
        metaFlow.value = metaFlow.value.copy(nextSessionSequence = session.sequence + 1, snapshotRevision = metaFlow.value.snapshotRevision + 1)
        entriesBySession.getOrPut(session.id) { MutableStateFlow(emptyList()) }
        return session
    }

    override suspend fun addPayment(sessionId: String, productAmountYen: Long, receivedAmountYen: Long, entryId: String, occurredAt: Long?): SavedEntry {
        val existing = entriesBySession.getOrPut(sessionId) { MutableStateFlow(emptyList()) }.value.firstOrNull { it.id == entryId }
        if (existing != null) {
            if (existing.productAmountYen == productAmountYen && existing.receivedAmountYen == receivedAmountYen) return SavedEntry(existing, true)
            throw EntryIdConflict()
        }
        if (failNextPayment) {
            failNextPayment = false
            error("simulated failure")
        }
        val session = sessionsFlow.value.firstOrNull { it.id == sessionId } ?: throw RegisterNotFound()
        if (session.status != RegisterStatus.OPEN) error("closed")
        val entry = CashEntry(
            id = entryId,
            sessionId = sessionId,
            sequence = entriesBySession.getOrPut(sessionId) { MutableStateFlow(emptyList()) }.value.size + 1L,
            kind = CashEntryKind.PAYMENT,
            occurredAt = occurredAt ?: 2_000L,
            productAmountYen = productAmountYen,
            receivedAmountYen = receivedAmountYen,
            amountYen = null,
            createdAt = occurredAt ?: 2_000L,
            updatedAt = occurredAt ?: 2_000L,
        )
        entriesBySession.getOrPut(sessionId) { MutableStateFlow(emptyList()) }.value += entry
        touch(sessionId)
        return SavedEntry(entry, false)
    }

    override suspend fun addCashIn(sessionId: String, amountYen: Long, entryId: String, occurredAt: Long?): SavedEntry = addAdjustment(sessionId, amountYen, entryId, CashEntryKind.CASH_IN)
    override suspend fun addCashOut(sessionId: String, amountYen: Long, entryId: String, occurredAt: Long?): SavedEntry = addAdjustment(sessionId, amountYen, entryId, CashEntryKind.CASH_OUT)

    private suspend fun addAdjustment(sessionId: String, amountYen: Long, entryId: String, kind: CashEntryKind): SavedEntry {
        val entry = CashEntry(entryId, sessionId, 1, kind, 2_000, null, null, amountYen, false, 2_000, 2_000, 1)
        entriesBySession.getOrPut(sessionId) { MutableStateFlow(emptyList()) }.value += entry
        touch(sessionId)
        return SavedEntry(entry, false)
    }

    override suspend fun listEntries(sessionId: String): List<CashEntry> = entriesBySession[sessionId]?.value ?: emptyList()
    override fun observeEntries(sessionId: String): Flow<List<CashEntry>> = entriesBySession.getOrPut(sessionId) { MutableStateFlow(emptyList()) }.asStateFlow()
    override suspend fun summarizeRegister(sessionId: String): RegisterSummary = error("summary not needed")
    override suspend fun editPayment(entryId: String, expectedEntryRevision: Long, expectedSessionRevision: Long, productAmountYen: Long, receivedAmountYen: Long, updatedAt: Long?): CashEntry = error("not needed")
    override suspend fun editCashAdjustment(entryId: String, expectedEntryRevision: Long, expectedSessionRevision: Long, amountYen: Long, updatedAt: Long?): CashEntry = error("not needed")
    override suspend fun voidEntry(entryId: String, expectedEntryRevision: Long, expectedSessionRevision: Long, updatedAt: Long?): CashEntry = error("not needed")

    override suspend fun closeRegister(sessionId: String, expectedSessionRevision: Long, actualCashYen: Long, nextFloatYen: Long, closedAt: Long?): RegisterSession {
        val current = sessionsFlow.value.first { it.id == sessionId }
        if (nextFloatYen > actualCashYen) throw InvalidRegisterData("次回に残す釣銭は実際に数えた手元現金を超えられません")
        val closed = current.copy(
            status = RegisterStatus.CLOSED,
            openSlot = null,
            closedAt = closedAt ?: 3_000L,
            actualCashYen = actualCashYen,
            nextFloatYen = nextFloatYen,
            revision = current.revision + 1,
            closeRevision = current.revision + 1,
        )
        sessionsFlow.value = sessionsFlow.value.map { if (it.id == sessionId) closed else it }
        return closed
    }

    override suspend fun editRegister(sessionId: String, expectedRevision: Long, openingFloatYen: Long, actualCashYen: Long?, nextFloatYen: Long?, updatedAt: Long?): RegisterSession = error("not needed")
    override suspend fun clearAllHistory(expectedSnapshotRevision: Long?): DatasetMeta {
        sessionsFlow.value = emptyList()
        entriesBySession.clear()
        metaFlow.value = metaFlow.value.copy(snapshotRevision = metaFlow.value.snapshotRevision + 1)
        return metaFlow.value
    }

    private fun touch(sessionId: String) {
        sessionsFlow.value = sessionsFlow.value.map { if (it.id == sessionId) it.copy(revision = it.revision + 1) else it }
        metaFlow.value = metaFlow.value.copy(snapshotRevision = metaFlow.value.snapshotRevision + 1)
    }
}
