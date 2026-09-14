@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.mock108.ubaregi

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.mock108.ubaregi.data.CashEntry
import io.github.mock108.ubaregi.data.CashEntryKind
import io.github.mock108.ubaregi.data.ConcurrentDataModification
import io.github.mock108.ubaregi.data.DatasetMeta
import io.github.mock108.ubaregi.data.EntryIdConflict
import io.github.mock108.ubaregi.data.EntryIsAlreadyVoided
import io.github.mock108.ubaregi.data.InvalidRegisterData
import io.github.mock108.ubaregi.data.OpenRegisterAlreadyExists
import io.github.mock108.ubaregi.data.RegisterDataException
import io.github.mock108.ubaregi.data.RegisterRepository
import io.github.mock108.ubaregi.data.RegisterSession
import io.github.mock108.ubaregi.data.RegisterStatus
import io.github.mock108.ubaregi.domain.RegisterSummary
import java.util.UUID
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val HISTORY_PAGE_SIZE = 100

data class HomeUiState(
    val isLoading: Boolean = true,
    val openRegister: RegisterSession? = null,
    val summary: RegisterSummary? = null,
    val exportRequest: ExportRequest? = null,
    val isExporting: Boolean = false,
    val isUnavailable: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

class HomeViewModel(
    private val repository: RegisterRepository,
    private val exportService: AndroidExportService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                repository.initializeDatasetMeta()
                repository.observeOpenRegister().collectLatest { openRegister ->
                    if (openRegister == null) {
                        _uiState.update { it.copy(isLoading = false, openRegister = null, summary = null, isUnavailable = false) }
                    } else {
                        runCatching { repository.summarizeRegister(openRegister.id) }
                            .onSuccess { summary ->
                                _uiState.update {
                                    it.copy(isLoading = false, openRegister = openRegister, summary = summary, isUnavailable = false, errorMessage = null)
                                }
                            }
                            .onFailure { error ->
                                _uiState.update {
                                    it.copy(isLoading = false, openRegister = openRegister, errorMessage = error.loadUserMessage())
                                }
                            }
                    }
                }
            } catch (error: Throwable) {
                _uiState.update { it.copy(isLoading = false, isUnavailable = true, errorMessage = error.loadUserMessage()) }
            }
        }
    }

    fun requestExport(format: ExportFormat) {
        if (_uiState.value.isUnavailable || _uiState.value.isExporting || _uiState.value.exportRequest != null) return
        viewModelScope.launch {
            runCatching {
                val meta = repository.getDatasetMeta()
                val now = System.currentTimeMillis()
                ExportRequest(format, ExportSerializer.fileName(format, now, meta.snapshotRevision))
            }.onSuccess { request ->
                _uiState.update { it.copy(exportRequest = request, errorMessage = null, message = null) }
            }.onFailure { error -> _uiState.update { it.copy(errorMessage = error.loadUserMessage()) } }
        }
    }

    fun completeExport(destination: Uri?) {
        val request = _uiState.value.exportRequest ?: return
        if (destination == null) {
            _uiState.update { it.copy(exportRequest = null) }
            return
        }
        if (_uiState.value.isExporting) return
        _uiState.update { it.copy(isExporting = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            runCatching { exportService.export(request.format, destination) }
                .onSuccess {
                    _uiState.update {
                        it.copy(exportRequest = null, isExporting = false, message = "${request.format.extension.uppercase()}で保存しました。", errorMessage = null)
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            exportRequest = null,
                            isExporting = false,
                            errorMessage = "保存に失敗しました。保存先に不完全なファイルが残った可能性があります。別のファイル名で再試行してください。元データは変更していません。",
                        )
                    }
                }
        }
    }
}

data class AdjustmentDialogState(
    val kind: CashEntryKind,
    val amountText: String = "",
)

data class CloseDialogState(
    val actualCashText: String,
    val nextFloatText: String,
)

data class RegisterEditDialogState(
    val openingFloatText: String,
    val actualCashText: String,
    val nextFloatText: String,
)

data class EntryEditDialogState(
    val entryId: String,
    val amountText: String,
)

data class ClearHistoryDialogState(
    val registerCount: Int,
    val entryCount: Int,
    val hasOpenRegister: Boolean,
    val expectedSnapshotRevision: Long,
)

data class RegisterUiState(
    val isLoading: Boolean = true,
    val registers: List<RegisterSession> = emptyList(),
    val summaries: Map<String, RegisterSummary> = emptyMap(),
    val selectedRegisterId: String? = null,
    val selectedSummary: RegisterSummary? = null,
    val openSummary: RegisterSummary? = null,
    val selectedEntries: List<CashEntry> = emptyList(),
    val openEntries: List<CashEntry> = emptyList(),
    val selectedEntryCount: Int = 0,
    val openEntryCount: Int = 0,
    val selectedEntryLimit: Int = HISTORY_PAGE_SIZE,
    val openEntryLimit: Int = HISTORY_PAGE_SIZE,
    val openingFloatText: String = "",
    val latestClosed: RegisterSession? = null,
    val adjustmentDialog: AdjustmentDialogState? = null,
    val closeDialog: CloseDialogState? = null,
    val isCloseConfirmationVisible: Boolean = false,
    val registerEditDialog: RegisterEditDialogState? = null,
    val entryEditDialog: EntryEditDialogState? = null,
    val voidEntryId: String? = null,
    val clearHistoryDialog: ClearHistoryDialogState? = null,
    val isSaving: Boolean = false,
    val message: String? = null,
    val isUnavailable: Boolean = false,
    val errorMessage: String? = null,
) {
    val openRegister: RegisterSession?
        get() = registers.firstOrNull { it.status == RegisterStatus.OPEN }

    val selectedRegister: RegisterSession?
        get() = registers.firstOrNull { it.id == selectedRegisterId }
}

class RegisterViewModel(
    private val repository: RegisterRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        RegisterUiState(
            openingFloatText = savedStateHandle[OPENING_FLOAT_KEY] ?: "",
            selectedRegisterId = savedStateHandle[SELECTED_REGISTER_KEY],
            adjustmentDialog = savedStateHandle.get<String>(ADJUSTMENT_KIND_KEY)?.let { kindName ->
                runCatching { CashEntryKind.valueOf(kindName) }.getOrNull()?.let { kind ->
                    AdjustmentDialogState(kind, savedStateHandle[ADJUSTMENT_AMOUNT_KEY] ?: "")
                }
            },
            closeDialog = if (savedStateHandle.get<Boolean>(CLOSE_DIALOG_KEY) == true) {
                CloseDialogState(
                    savedStateHandle[CLOSE_ACTUAL_KEY] ?: "",
                    savedStateHandle[CLOSE_NEXT_KEY] ?: "",
                )
            } else {
                null
            },
            isCloseConfirmationVisible = savedStateHandle[CLOSE_CONFIRMATION_KEY] ?: false,
            registerEditDialog = if (savedStateHandle.get<Boolean>(REGISTER_EDIT_DIALOG_KEY) == true) {
                RegisterEditDialogState(
                    savedStateHandle[REGISTER_EDIT_OPENING_KEY] ?: "",
                    savedStateHandle[REGISTER_EDIT_ACTUAL_KEY] ?: "",
                    savedStateHandle[REGISTER_EDIT_NEXT_KEY] ?: "",
                )
            } else {
                null
            },
            entryEditDialog = savedStateHandle.get<String>(ENTRY_EDIT_ID_KEY)?.let { id ->
                EntryEditDialogState(id, savedStateHandle[ENTRY_EDIT_AMOUNT_KEY] ?: "")
            },
            voidEntryId = savedStateHandle[VOID_ENTRY_ID_KEY],
        ),
    )
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                repository.initializeDatasetMeta()
                repository.observeRegisters().collectLatest { sessions ->
                    val currentSelected = _uiState.value.selectedRegisterId
                    val selectedId = when {
                        currentSelected != null && sessions.any { it.id == currentSelected } -> currentSelected
                        sessions.any { it.status == RegisterStatus.OPEN } -> sessions.first { it.status == RegisterStatus.OPEN }.id
                        else -> sessions.firstOrNull()?.id
                    }
                    val latestClosed = sessions.firstOrNull { session -> session.status == RegisterStatus.CLOSED }
                    val hasOpen = sessions.any { session -> session.status == RegisterStatus.OPEN }
                    val openingText = if (!hasOpen && _uiState.value.openingFloatText.isBlank()) {
                        latestClosed?.nextFloatYen?.toString() ?: ""
                    } else {
                        _uiState.value.openingFloatText
                    }
                    savedStateHandle[SELECTED_REGISTER_KEY] = selectedId
                    savedStateHandle[OPENING_FLOAT_KEY] = openingText
                    val loadedSummaries = buildMap {
                        sessions.forEach { session ->
                            runCatching { repository.summarizeRegister(session.id) }
                                .onSuccess { summary -> put(session.id, summary) }
                        }
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            registers = sessions,
                            summaries = loadedSummaries,
                            selectedRegisterId = selectedId,
                            latestClosed = latestClosed,
                            openingFloatText = openingText,
                            openSummary = if (hasOpen) it.openSummary else null,
                            openEntries = if (hasOpen) it.openEntries else emptyList(),
                            openEntryCount = if (hasOpen) it.openEntryCount else 0,
                            isUnavailable = false,
                            errorMessage = null,
                        )
                    }
                    if (selectedId != null) loadSummary(selectedId)
                    val openId = sessions.firstOrNull { it.status == RegisterStatus.OPEN }?.id
                    if (openId != null && openId != selectedId) loadSummary(openId)
                }
            } catch (error: Throwable) {
                _uiState.update { it.copy(isLoading = false, isUnavailable = true, errorMessage = error.loadUserMessage()) }
            }
        }

        viewModelScope.launch {
            _uiState
                .map { it.selectedRegisterId to it.selectedEntryLimit }
                .distinctUntilChanged()
                .flatMapLatest { (id, limit) ->
                    if (id == null) flowOf(emptyList()) else repository.observeEntriesPage(id, limit, 0)
                }
                .collectLatest { entries -> _uiState.update { it.copy(selectedEntries = entries) } }
        }
        viewModelScope.launch {
            _uiState
                .map { it.openRegister?.id to it.openEntryLimit }
                .distinctUntilChanged()
                .flatMapLatest { (id, limit) ->
                    if (id == null) flowOf(emptyList()) else repository.observeEntriesPage(id, limit, 0)
                }
                .collectLatest { entries -> _uiState.update { it.copy(openEntries = entries) } }
        }
        viewModelScope.launch {
            _uiState
                .map { it.selectedRegisterId }
                .distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(0) else repository.observeEntryCount(id) }
                .collectLatest { count -> _uiState.update { it.copy(selectedEntryCount = count) } }
        }
        viewModelScope.launch {
            _uiState
                .map { it.openRegister?.id }
                .distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(0) else repository.observeEntryCount(id) }
                .collectLatest { count -> _uiState.update { it.copy(openEntryCount = count) } }
        }
    }

    fun onOpeningFloatChanged(value: String) {
        savedStateHandle[OPENING_FLOAT_KEY] = value
        _uiState.update { it.copy(openingFloatText = value, errorMessage = null, message = null) }
    }

    fun selectRegister(sessionId: String) {
        if (_uiState.value.registers.none { it.id == sessionId }) return
        if (_uiState.value.entryEditDialog != null) return
        savedStateHandle[SELECTED_REGISTER_KEY] = sessionId
        _uiState.update {
            it.copy(
                selectedRegisterId = sessionId,
                selectedEntryLimit = HISTORY_PAGE_SIZE,
                errorMessage = null,
                message = null,
            )
        }
        viewModelScope.launch { loadSummary(sessionId) }
    }

    fun loadMoreSelectedEntries() {
        _uiState.update { it.copy(selectedEntryLimit = it.selectedEntryLimit + HISTORY_PAGE_SIZE) }
    }

    fun loadMoreOpenEntries() {
        _uiState.update { it.copy(openEntryLimit = it.openEntryLimit + HISTORY_PAGE_SIZE) }
    }

    fun startRegister() {
        if (_uiState.value.isSaving || _uiState.value.isUnavailable) return
        val amount = parseMoneyInput(_uiState.value.openingFloatText, STARTING_CHANGE_LABEL, allowZero = true)
        if (amount == null) {
            showError("${STARTING_CHANGE_LABEL}は0〜9,999,999円で入力してください")
            return
        }
        saveOperation {
            val session = repository.startRegister(amount)
            savedStateHandle[OPENING_FLOAT_KEY] = ""
            _uiState.update {
                it.copy(
                    openingFloatText = "",
                    selectedRegisterId = session.id,
                    message = "レジを開始しました。",
                    errorMessage = null,
                )
            }
        }
    }

    fun showAdjustmentDialog(kind: CashEntryKind) {
        if (_uiState.value.openRegister == null) {
            showError("稼働中のレジを始めてください")
            return
        }
        savedStateHandle[ADJUSTMENT_KIND_KEY] = kind.name
        savedStateHandle[ADJUSTMENT_AMOUNT_KEY] = ""
        _uiState.update { it.copy(adjustmentDialog = AdjustmentDialogState(kind), message = null, errorMessage = null) }
    }

    fun onAdjustmentAmountChanged(value: String) {
        savedStateHandle[ADJUSTMENT_AMOUNT_KEY] = value
        _uiState.update { state -> state.adjustmentDialog?.let { state.copy(adjustmentDialog = it.copy(amountText = value), errorMessage = null) } ?: state }
    }

    fun dismissAdjustmentDialog() {
        clearAdjustmentDialogState()
        _uiState.update { it.copy(adjustmentDialog = null) }
    }

    fun saveAdjustment() {
        val dialog = _uiState.value.adjustmentDialog ?: return
        val amount = parseMoneyInput(dialog.amountText, "金額", allowZero = false)
        if (amount == null) {
            showError("金額は1〜9,999,999円で入力してください")
            return
        }
        val session = _uiState.value.openRegister ?: run {
            showError("稼働中のレジを始めてください")
            return
        }
        saveOperation {
            if (dialog.kind == CashEntryKind.CASH_IN) {
                repository.addCashIn(session.id, amount)
            } else {
                repository.addCashOut(session.id, amount)
            }
            _uiState.update {
                it.copy(
                    adjustmentDialog = null,
                    message = "現金の移動を記録しました。",
                    errorMessage = null,
                )
            }
            clearAdjustmentDialogState()
        }
    }

    fun showCloseDialog() {
        val session = _uiState.value.openRegister ?: run {
            showError("稼働中のレジはありません")
            return
        }
        _uiState.update {
            it.copy(
                closeDialog = CloseDialogState("", session.openingFloatYen.toString()),
                isCloseConfirmationVisible = false,
                message = null,
                errorMessage = null,
            )
        }
        savedStateHandle[CLOSE_DIALOG_KEY] = true
        savedStateHandle[CLOSE_ACTUAL_KEY] = ""
        savedStateHandle[CLOSE_NEXT_KEY] = session.openingFloatYen.toString()
    }

    fun onCloseActualChanged(value: String) {
        savedStateHandle[CLOSE_ACTUAL_KEY] = value
        _uiState.update { it.copy(closeDialog = it.closeDialog?.copy(actualCashText = value), errorMessage = null) }
    }

    fun onCloseNextFloatChanged(value: String) {
        savedStateHandle[CLOSE_NEXT_KEY] = value
        _uiState.update { it.copy(closeDialog = it.closeDialog?.copy(nextFloatText = value), errorMessage = null) }
    }

    fun dismissCloseDialog() {
        clearCloseDialogState()
        _uiState.update { it.copy(closeDialog = null, isCloseConfirmationVisible = false) }
    }

    fun requestCloseConfirmation() {
        val dialog = _uiState.value.closeDialog ?: return
        val actual = parseMoneyInput(dialog.actualCashText, COUNTED_CASH_LABEL, allowZero = true)
        val next = parseMoneyInput(dialog.nextFloatText, NEXT_CHANGE_LABEL, allowZero = true)
        when {
            actual == null -> showError("${COUNTED_CASH_LABEL}は0〜9,999,999円で入力してください")
            next == null -> showError("${NEXT_CHANGE_LABEL}は0〜9,999,999円で入力してください")
            next > actual -> showError("${NEXT_CHANGE_LABEL}は${COUNTED_CASH_LABEL}を超えられません")
            else -> {
                savedStateHandle[CLOSE_CONFIRMATION_KEY] = true
                _uiState.update { it.copy(isCloseConfirmationVisible = true, errorMessage = null) }
            }
        }
    }

    fun dismissCloseConfirmation() {
        savedStateHandle[CLOSE_CONFIRMATION_KEY] = false
        _uiState.update { it.copy(isCloseConfirmationVisible = false) }
    }

    fun closeRegister() {
        if (_uiState.value.isSaving) return
        val session = _uiState.value.openRegister ?: return
        val dialog = _uiState.value.closeDialog ?: return
        val actual = parseMoneyInput(dialog.actualCashText, COUNTED_CASH_LABEL, allowZero = true)
        val next = parseMoneyInput(dialog.nextFloatText, NEXT_CHANGE_LABEL, allowZero = true)
        if (actual == null || next == null || next > actual) {
            _uiState.update { it.copy(isCloseConfirmationVisible = false) }
            requestCloseConfirmation()
            return
        }
        saveOperation {
            repository.closeRegister(session.id, session.revision, actual, next)
            clearCloseDialogState()
            _uiState.update {
                it.copy(
                    closeDialog = null,
                    isCloseConfirmationVisible = false,
                    message = "レジを終了しました。次のレジは自動で始まりません。",
                    errorMessage = null,
                )
            }
        }
    }

    fun showRegisterEditDialog() {
        val session = _uiState.value.selectedRegister ?: return
        savedStateHandle[REGISTER_EDIT_DIALOG_KEY] = true
        savedStateHandle[REGISTER_EDIT_OPENING_KEY] = session.openingFloatYen.toString()
        savedStateHandle[REGISTER_EDIT_ACTUAL_KEY] = session.actualCashYen?.toString() ?: ""
        savedStateHandle[REGISTER_EDIT_NEXT_KEY] = session.nextFloatYen?.toString() ?: ""
        _uiState.update {
            it.copy(
                registerEditDialog = RegisterEditDialogState(
                    openingFloatText = session.openingFloatYen.toString(),
                    actualCashText = session.actualCashYen?.toString() ?: "",
                    nextFloatText = session.nextFloatYen?.toString() ?: "",
                ),
                message = null,
                errorMessage = null,
            )
        }
    }

    fun onRegisterEditOpeningChanged(value: String) {
        savedStateHandle[REGISTER_EDIT_OPENING_KEY] = value
        _uiState.update { state -> state.registerEditDialog?.let { state.copy(registerEditDialog = it.copy(openingFloatText = value), errorMessage = null) } ?: state }
    }

    fun onRegisterEditActualChanged(value: String) {
        savedStateHandle[REGISTER_EDIT_ACTUAL_KEY] = value
        _uiState.update { state -> state.registerEditDialog?.let { state.copy(registerEditDialog = it.copy(actualCashText = value), errorMessage = null) } ?: state }
    }

    fun onRegisterEditNextChanged(value: String) {
        savedStateHandle[REGISTER_EDIT_NEXT_KEY] = value
        _uiState.update { state -> state.registerEditDialog?.let { state.copy(registerEditDialog = it.copy(nextFloatText = value), errorMessage = null) } ?: state }
    }

    fun dismissRegisterEditDialog() {
        clearRegisterEditState()
        _uiState.update { it.copy(registerEditDialog = null) }
    }

    fun saveRegisterEdit() {
        val session = _uiState.value.selectedRegister ?: return
        val dialog = _uiState.value.registerEditDialog ?: return
        val opening = parseMoneyInput(dialog.openingFloatText, STARTING_CHANGE_LABEL, allowZero = true)
        if (opening == null) {
            showError("${STARTING_CHANGE_LABEL}は0〜9,999,999円で入力してください")
            return
        }
        val actual = if (session.status == RegisterStatus.CLOSED) parseMoneyInput(dialog.actualCashText, COUNTED_CASH_LABEL, true) else null
        val next = if (session.status == RegisterStatus.CLOSED) parseMoneyInput(dialog.nextFloatText, NEXT_CHANGE_LABEL, true) else null
        if (session.status == RegisterStatus.CLOSED && (actual == null || next == null)) {
            showError("${COUNTED_CASH_LABEL}と${NEXT_CHANGE_LABEL}を入力してください")
            return
        }
        if (actual != null && next != null && next > actual) {
            showError("${NEXT_CHANGE_LABEL}は${COUNTED_CASH_LABEL}を超えられません")
            return
        }
        saveOperation {
            repository.editRegister(session.id, session.revision, opening, actual, next)
            clearRegisterEditState()
            _uiState.update { it.copy(registerEditDialog = null, message = "レジ情報を更新しました。", errorMessage = null) }
        }
    }

    fun showEntryEditDialog(entry: CashEntry) {
        if (entry.isVoided || entry.kind == CashEntryKind.PAYMENT) return
        savedStateHandle[ENTRY_EDIT_ID_KEY] = entry.id
        savedStateHandle[ENTRY_EDIT_AMOUNT_KEY] = entry.amountYen?.toString() ?: ""
        _uiState.update { it.copy(entryEditDialog = EntryEditDialogState(entry.id, entry.amountYen?.toString() ?: ""), errorMessage = null) }
    }

    fun onEntryEditAmountChanged(value: String) {
        savedStateHandle[ENTRY_EDIT_AMOUNT_KEY] = value
        _uiState.update { state -> state.entryEditDialog?.let { state.copy(entryEditDialog = it.copy(amountText = value), errorMessage = null) } ?: state }
    }

    fun dismissEntryEditDialog() {
        clearEntryEditState()
        _uiState.update { it.copy(entryEditDialog = null) }
    }

    fun saveEntryEdit() {
        val dialog = _uiState.value.entryEditDialog ?: return
        val session = _uiState.value.selectedRegister ?: return
        val amount = parseMoneyInput(dialog.amountText, "補充・取出し額", allowZero = false)
        if (amount == null) {
            showError("金額は1〜9,999,999円で入力してください")
            return
        }
        val entry = _uiState.value.selectedEntries.firstOrNull { it.id == dialog.entryId } ?: return
        saveOperation {
            repository.editCashAdjustment(entry.id, entry.revision, session.revision, amount)
            clearEntryEditState()
            _uiState.update { it.copy(entryEditDialog = null, message = "明細を更新しました。", errorMessage = null) }
        }
    }

    fun requestVoidEntry(entry: CashEntry) {
        if (!entry.isVoided) {
            savedStateHandle[VOID_ENTRY_ID_KEY] = entry.id
            _uiState.update { it.copy(voidEntryId = entry.id, errorMessage = null) }
        }
    }

    fun dismissVoidEntry() {
        savedStateHandle[VOID_ENTRY_ID_KEY] = null
        _uiState.update { it.copy(voidEntryId = null) }
    }

    fun voidEntry() {
        val entry = _uiState.value.selectedEntries.firstOrNull { it.id == _uiState.value.voidEntryId } ?: return
        val session = _uiState.value.selectedRegister ?: return
        saveOperation {
            repository.voidEntry(entry.id, entry.revision, session.revision)
            savedStateHandle[VOID_ENTRY_ID_KEY] = null
            _uiState.update { it.copy(voidEntryId = null, message = "明細を取消しました。", errorMessage = null) }
        }
    }

    fun showClearHistoryDialog() {
        viewModelScope.launch {
            runCatching {
                val meta = repository.getDatasetMeta()
                val sessions = repository.listRegisters()
                val entryCount = sessions.sumOf { repository.getEntryCount(it.id) }
                ClearHistoryDialogState(
                    registerCount = sessions.size,
                    entryCount = entryCount,
                    hasOpenRegister = sessions.any { it.status == RegisterStatus.OPEN },
                    expectedSnapshotRevision = meta.snapshotRevision,
                )
            }.onSuccess { dialog -> _uiState.update { it.copy(clearHistoryDialog = dialog, errorMessage = null) } }
                .onFailure { error -> showError(error.userMessage()) }
        }
    }

    fun dismissClearHistoryDialog() = _uiState.update { it.copy(clearHistoryDialog = null) }

    fun clearAllHistory() {
        val dialog = _uiState.value.clearHistoryDialog ?: return
        saveOperation {
            repository.clearAllHistory(dialog.expectedSnapshotRevision)
            savedStateHandle[OPENING_FLOAT_KEY] = ""
            savedStateHandle[SELECTED_REGISTER_KEY] = null
            clearAllDialogState()
            _uiState.update {
                it.copy(
                    registers = emptyList(),
                    summaries = emptyMap(),
                    selectedRegisterId = null,
                    selectedSummary = null,
                    openSummary = null,
                    selectedEntries = emptyList(),
                    openEntries = emptyList(),
                    openingFloatText = "",
                    latestClosed = null,
                    clearHistoryDialog = null,
                    adjustmentDialog = null,
                    closeDialog = null,
                    registerEditDialog = null,
                    entryEditDialog = null,
                    voidEntryId = null,
                    message = "すべての履歴を消去しました。",
                    errorMessage = null,
                )
            }
        }
    }

    private suspend fun loadSummary(sessionId: String) {
        runCatching { repository.summarizeRegister(sessionId) }
            .onSuccess { summary ->
                _uiState.update {
                    it.copy(
                        summaries = it.summaries + (sessionId to summary),
                        selectedSummary = if (it.selectedRegisterId == sessionId) summary else it.selectedSummary,
                        openSummary = if (it.openRegister?.id == sessionId) summary else it.openSummary,
                    )
                }
            }
            .onFailure { error -> _uiState.update { it.copy(selectedSummary = null, errorMessage = error.userMessage()) } }
    }

    private fun saveOperation(operation: suspend () -> Unit) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            try {
                operation()
            } catch (error: Throwable) {
                if (error is ConcurrentDataModification) {
                    _uiState.update { it.copy(errorMessage = "データが更新されたため、最新状態を読み込みました。もう一度確認してください。") }
                } else {
                    _uiState.update { it.copy(errorMessage = error.userMessage()) }
                }
            }
            _uiState.update { it.copy(isSaving = false) }
        }
    }

    private fun showError(message: String) = _uiState.update { it.copy(errorMessage = message, message = null) }

    private fun clearAdjustmentDialogState() {
        savedStateHandle[ADJUSTMENT_KIND_KEY] = null
        savedStateHandle[ADJUSTMENT_AMOUNT_KEY] = null
    }

    private fun clearCloseDialogState() {
        savedStateHandle[CLOSE_DIALOG_KEY] = false
        savedStateHandle[CLOSE_CONFIRMATION_KEY] = false
        savedStateHandle[CLOSE_ACTUAL_KEY] = null
        savedStateHandle[CLOSE_NEXT_KEY] = null
    }

    private fun clearRegisterEditState() {
        savedStateHandle[REGISTER_EDIT_DIALOG_KEY] = false
        savedStateHandle[REGISTER_EDIT_OPENING_KEY] = null
        savedStateHandle[REGISTER_EDIT_ACTUAL_KEY] = null
        savedStateHandle[REGISTER_EDIT_NEXT_KEY] = null
    }

    private fun clearEntryEditState() {
        savedStateHandle[ENTRY_EDIT_ID_KEY] = null
        savedStateHandle[ENTRY_EDIT_AMOUNT_KEY] = null
    }

    private fun clearAllDialogState() {
        clearAdjustmentDialogState()
        clearCloseDialogState()
        clearRegisterEditState()
        clearEntryEditState()
        savedStateHandle[VOID_ENTRY_ID_KEY] = null
    }

    companion object {
        private const val OPENING_FLOAT_KEY = "register.openingFloat"
        private const val SELECTED_REGISTER_KEY = "register.selectedId"
        private const val ADJUSTMENT_KIND_KEY = "register.adjustment.kind"
        private const val ADJUSTMENT_AMOUNT_KEY = "register.adjustment.amount"
        private const val CLOSE_DIALOG_KEY = "register.close.dialog"
        private const val CLOSE_ACTUAL_KEY = "register.close.actual"
        private const val CLOSE_NEXT_KEY = "register.close.next"
        private const val CLOSE_CONFIRMATION_KEY = "register.close.confirmation"
        private const val REGISTER_EDIT_DIALOG_KEY = "register.edit.dialog"
        private const val REGISTER_EDIT_OPENING_KEY = "register.edit.opening"
        private const val REGISTER_EDIT_ACTUAL_KEY = "register.edit.actual"
        private const val REGISTER_EDIT_NEXT_KEY = "register.edit.next"
        private const val ENTRY_EDIT_ID_KEY = "register.entry.edit.id"
        private const val ENTRY_EDIT_AMOUNT_KEY = "register.entry.edit.amount"
        private const val VOID_ENTRY_ID_KEY = "register.entry.void.id"
    }
}

data class CalculatorUiState(
    val isLoading: Boolean = true,
    val registers: List<RegisterSession> = emptyList(),
    val selectedRegisterId: String? = null,
    val selectedTab: CalculatorTab = CalculatorTab.CALCULATE,
    val focusedField: CalculatorInputField = CalculatorInputField.PRODUCT,
    val productText: String = "",
    val receivedText: String = "",
    val pendingEntryId: String? = null,
    val pendingEntrySessionId: String? = null,
    val entries: List<CashEntry> = emptyList(),
    val entryCount: Int = 0,
    val entryLimit: Int = HISTORY_PAGE_SIZE,
    val editEntryId: String? = null,
    val editFocusedField: CalculatorInputField = CalculatorInputField.PRODUCT,
    val editProductText: String = "",
    val editReceivedText: String = "",
    val voidEntryId: String? = null,
    val isSaving: Boolean = false,
    val isUnavailable: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
) {
    val selectedRegister: RegisterSession?
        get() = registers.firstOrNull { it.id == selectedRegisterId }

    val paymentEntries: List<CashEntry>
        get() = entries.filter { it.kind == CashEntryKind.PAYMENT }
}

enum class CalculatorTab {
    CALCULATE,
    RECORDS,
}

enum class CalculatorInputField {
    PRODUCT,
    RECEIVED,
}

class CalculatorViewModel(
    private val repository: RegisterRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        CalculatorUiState(
            selectedRegisterId = savedStateHandle[SELECTED_REGISTER_KEY],
            selectedTab = savedStateHandle.get<String>(SELECTED_TAB_KEY)
                ?.let { runCatching { CalculatorTab.valueOf(it) }.getOrNull() }
                ?: CalculatorTab.CALCULATE,
            focusedField = savedStateHandle.get<String>(FOCUSED_FIELD_KEY)
                ?.let { runCatching { CalculatorInputField.valueOf(it) }.getOrNull() }
                ?: CalculatorInputField.PRODUCT,
            productText = savedStateHandle[PRODUCT_KEY] ?: "",
            receivedText = savedStateHandle[RECEIVED_KEY] ?: "",
            pendingEntryId = savedStateHandle[PENDING_ENTRY_ID_KEY],
            pendingEntrySessionId = savedStateHandle[PENDING_ENTRY_SESSION_KEY],
            editEntryId = savedStateHandle[EDIT_ENTRY_ID_KEY],
            editFocusedField = savedStateHandle.get<String>(EDIT_FOCUSED_FIELD_KEY)
                ?.let { runCatching { CalculatorInputField.valueOf(it) }.getOrNull() }
                ?: CalculatorInputField.PRODUCT,
            editProductText = savedStateHandle[EDIT_PRODUCT_KEY] ?: "",
            editReceivedText = savedStateHandle[EDIT_RECEIVED_KEY] ?: "",
            voidEntryId = savedStateHandle[VOID_ENTRY_ID_KEY],
        ),
    )
    val uiState: StateFlow<CalculatorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                repository.initializeDatasetMeta()
                repository.observeRegisters().collectLatest { sessions ->
                    val selected = _uiState.value.selectedRegisterId
                    val selectedId = when {
                        selected != null && sessions.any { it.id == selected } -> selected
                        sessions.any { it.status == RegisterStatus.OPEN } -> sessions.first { it.status == RegisterStatus.OPEN }.id
                        else -> sessions.firstOrNull()?.id
                    }
                    val pendingSessionId = _uiState.value.pendingEntrySessionId
                        ?: if (_uiState.value.pendingEntryId != null) selectedId else null
                    savedStateHandle[SELECTED_REGISTER_KEY] = selectedId
                    savedStateHandle[PENDING_ENTRY_SESSION_KEY] = pendingSessionId
                    if (sessions.isEmpty()) clearCalculatorInputState()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            registers = sessions,
                            selectedRegisterId = if (sessions.isEmpty()) null else selectedId,
                            productText = if (sessions.isEmpty()) "" else it.productText,
                            receivedText = if (sessions.isEmpty()) "" else it.receivedText,
                            pendingEntryId = if (sessions.isEmpty()) null else it.pendingEntryId,
                            pendingEntrySessionId = if (sessions.isEmpty()) null else pendingSessionId,
                            isUnavailable = false,
                            errorMessage = null,
                        )
                    }
                }
            } catch (error: Throwable) {
                _uiState.update { it.copy(isLoading = false, isUnavailable = true, errorMessage = error.loadUserMessage()) }
            }
        }
        viewModelScope.launch {
            try {
                _uiState.map { it.selectedRegisterId to it.entryLimit }.distinctUntilChanged().flatMapLatest { (id, limit) ->
                    if (id == null) flowOf(emptyList()) else repository.observeEntriesPage(id, limit, 0)
                }.collectLatest { entries -> _uiState.update { it.copy(entries = entries) } }
            } catch (error: Throwable) {
                _uiState.update { it.copy(isUnavailable = true, errorMessage = error.loadUserMessage()) }
            }
        }
        viewModelScope.launch {
            try {
                _uiState.map { it.selectedRegisterId }.distinctUntilChanged().flatMapLatest { id ->
                    if (id == null) flowOf(0) else repository.observeEntryCount(id)
                }.collectLatest { count -> _uiState.update { it.copy(entryCount = count) } }
            } catch (error: Throwable) {
                _uiState.update { it.copy(isUnavailable = true, errorMessage = error.loadUserMessage()) }
            }
        }
    }

    fun selectRegister(sessionId: String?) {
        val state = _uiState.value
        if (state.pendingEntryId != null && sessionId != (state.pendingEntrySessionId ?: state.selectedRegisterId)) {
            showError("保存待ちの受渡しがあるため、対象レジを変更できません。先に保存または入力をクリアしてください")
            return
        }
        savedStateHandle[SELECTED_REGISTER_KEY] = sessionId
        _uiState.update { it.copy(selectedRegisterId = sessionId, entryLimit = HISTORY_PAGE_SIZE, errorMessage = null, message = null) }
    }

    fun selectTab(tab: CalculatorTab) {
        savedStateHandle[SELECTED_TAB_KEY] = tab.name
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun focusField(field: CalculatorInputField) {
        savedStateHandle[FOCUSED_FIELD_KEY] = field.name
        _uiState.update { it.copy(focusedField = field) }
    }

    fun moveFocus() {
        val next = when (_uiState.value.focusedField) {
            CalculatorInputField.PRODUCT -> CalculatorInputField.RECEIVED
            CalculatorInputField.RECEIVED -> CalculatorInputField.PRODUCT
        }
        savedStateHandle[FOCUSED_FIELD_KEY] = next.name
        _uiState.update {
            it.copy(
                focusedField = next,
            )
        }
    }

    fun nextInput() = moveFocus()

    fun appendDigit(digit: Int) {
        if (digit !in 0..9) return
        val state = _uiState.value
        val current = when (state.focusedField) {
            CalculatorInputField.PRODUCT -> state.productText
            CalculatorInputField.RECEIVED -> state.receivedText
        }
        val normalized = normalizeDigits(current)
        if (normalized.length >= MAX_MONEY_DIGITS) return
        val updated = normalized + digit
        when (state.focusedField) {
            CalculatorInputField.PRODUCT -> onProductChanged(updated)
            CalculatorInputField.RECEIVED -> onReceivedChanged(updated)
        }
    }

    fun deleteLastDigit() {
        val state = _uiState.value
        val current = when (state.focusedField) {
            CalculatorInputField.PRODUCT -> state.productText
            CalculatorInputField.RECEIVED -> state.receivedText
        }
        val updated = current.dropLast(1)
        when (state.focusedField) {
            CalculatorInputField.PRODUCT -> onProductChanged(updated)
            CalculatorInputField.RECEIVED -> onReceivedChanged(updated)
        }
    }

    fun clearFocusedInput() {
        when (_uiState.value.focusedField) {
            CalculatorInputField.PRODUCT -> onProductChanged("")
            CalculatorInputField.RECEIVED -> onReceivedChanged("")
        }
    }

    fun setInitialTarget(sessionId: String?) {
        if (sessionId != null && _uiState.value.registers.any { it.id == sessionId }) selectRegister(sessionId)
    }

    fun onProductChanged(value: String) {
        savedStateHandle[PRODUCT_KEY] = value
        _uiState.update { it.copy(productText = value, errorMessage = null, message = null) }
    }

    fun onReceivedChanged(value: String) {
        savedStateHandle[RECEIVED_KEY] = value
        _uiState.update { it.copy(receivedText = value, errorMessage = null, message = null) }
    }

    fun clearInput() {
        clearCalculatorInputState()
        _uiState.update { it.copy(productText = "", receivedText = "", pendingEntryId = null, pendingEntrySessionId = null, errorMessage = null, message = null) }
    }

    fun savePayment() {
        if (_uiState.value.isSaving || _uiState.value.isUnavailable) return
        val state = _uiState.value
        val session = state.selectedRegister
        if (session == null || (session.status != RegisterStatus.OPEN && state.pendingEntryId == null)) {
            showError("レジを始めると受け渡しを記録できます")
            return
        }
        if (state.pendingEntryId != null && state.pendingEntrySessionId != null && state.pendingEntrySessionId != session.id) {
            showError("保存待ちの受渡しの対象レジが変わっています。対象レジを戻してください")
            return
        }
        val result = ChangeCalculator.calculate(state.productText, state.receivedText)
        if (result !is ChangeResult.Success) {
            showError(result.messageForSave())
            return
        }
        val product = parseMoneyInput(state.productText, "商品金額", false) ?: return
        val received = parseMoneyInput(state.receivedText, "受取金額", true) ?: return
        val entryId = state.pendingEntryId ?: UUID.randomUUID().toString().also {
            savedStateHandle[PENDING_ENTRY_ID_KEY] = it
            savedStateHandle[PENDING_ENTRY_SESSION_KEY] = session.id
            _uiState.update { current -> current.copy(pendingEntryId = it, pendingEntrySessionId = session.id) }
        }
        _uiState.update { it.copy(isSaving = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            runCatching { repository.addPayment(session.id, product, received, entryId) }
                .onSuccess {
                    savedStateHandle[PRODUCT_KEY] = ""
                    savedStateHandle[RECEIVED_KEY] = ""
                    savedStateHandle[PENDING_ENTRY_ID_KEY] = null
                    savedStateHandle[PENDING_ENTRY_SESSION_KEY] = null
                    _uiState.update {
                        it.copy(
                            productText = "",
                            receivedText = "",
                            pendingEntryId = null,
                            pendingEntrySessionId = null,
                            message = "受渡しを記録しました。おつり ${formatYen(result.change)}",
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { error -> _uiState.update { it.copy(errorMessage = error.userMessage()) } }
            _uiState.update { it.copy(isSaving = false) }
        }
    }

    fun showPaymentEditDialog(entry: CashEntry) {
        if (entry.isVoided || entry.kind != CashEntryKind.PAYMENT) return
        savedStateHandle[EDIT_ENTRY_ID_KEY] = entry.id
        savedStateHandle[EDIT_FOCUSED_FIELD_KEY] = CalculatorInputField.PRODUCT.name
        savedStateHandle[EDIT_PRODUCT_KEY] = entry.productAmountYen?.toString() ?: ""
        savedStateHandle[EDIT_RECEIVED_KEY] = entry.receivedAmountYen?.toString() ?: ""
        _uiState.update {
            it.copy(
                editEntryId = entry.id,
                editFocusedField = CalculatorInputField.PRODUCT,
                editProductText = entry.productAmountYen?.toString() ?: "",
                editReceivedText = entry.receivedAmountYen?.toString() ?: "",
                errorMessage = null,
            )
        }
    }

    fun onEditProductChanged(value: String) {
        savedStateHandle[EDIT_PRODUCT_KEY] = value
        _uiState.update { it.copy(editProductText = value, errorMessage = null) }
    }

    fun onEditReceivedChanged(value: String) {
        savedStateHandle[EDIT_RECEIVED_KEY] = value
        _uiState.update { it.copy(editReceivedText = value, errorMessage = null) }
    }

    fun focusEditField(field: CalculatorInputField) {
        savedStateHandle[EDIT_FOCUSED_FIELD_KEY] = field.name
        _uiState.update { it.copy(editFocusedField = field) }
    }

    fun moveEditFocus() {
        val next = when (_uiState.value.editFocusedField) {
            CalculatorInputField.PRODUCT -> CalculatorInputField.RECEIVED
            CalculatorInputField.RECEIVED -> CalculatorInputField.PRODUCT
        }
        savedStateHandle[EDIT_FOCUSED_FIELD_KEY] = next.name
        _uiState.update {
            it.copy(
                editFocusedField = next,
            )
        }
    }

    fun nextEditInput() = moveEditFocus()

    fun appendEditDigit(digit: Int) {
        if (digit !in 0..9) return
        val state = _uiState.value
        val current = when (state.editFocusedField) {
            CalculatorInputField.PRODUCT -> state.editProductText
            CalculatorInputField.RECEIVED -> state.editReceivedText
        }
        val normalized = normalizeDigits(current)
        if (normalized.length >= MAX_MONEY_DIGITS) return
        val updated = normalized + digit
        when (state.editFocusedField) {
            CalculatorInputField.PRODUCT -> onEditProductChanged(updated)
            CalculatorInputField.RECEIVED -> onEditReceivedChanged(updated)
        }
    }

    fun deleteEditLastDigit() {
        val state = _uiState.value
        val current = when (state.editFocusedField) {
            CalculatorInputField.PRODUCT -> state.editProductText
            CalculatorInputField.RECEIVED -> state.editReceivedText
        }
        val updated = current.dropLast(1)
        when (state.editFocusedField) {
            CalculatorInputField.PRODUCT -> onEditProductChanged(updated)
            CalculatorInputField.RECEIVED -> onEditReceivedChanged(updated)
        }
    }

    fun clearEditFocusedInput() {
        when (_uiState.value.editFocusedField) {
            CalculatorInputField.PRODUCT -> onEditProductChanged("")
            CalculatorInputField.RECEIVED -> onEditReceivedChanged("")
        }
    }

    fun dismissPaymentEditDialog() {
        savedStateHandle[EDIT_ENTRY_ID_KEY] = null
        savedStateHandle[EDIT_PRODUCT_KEY] = null
        savedStateHandle[EDIT_RECEIVED_KEY] = null
        _uiState.update { it.copy(editEntryId = null) }
    }

    fun savePaymentEdit() {
        val state = _uiState.value
        val entry = state.entries.firstOrNull { it.id == state.editEntryId } ?: return
        val session = state.selectedRegister ?: return
        val result = ChangeCalculator.calculate(state.editProductText, state.editReceivedText)
        if (result !is ChangeResult.Success) {
            showError(result.messageForSave())
            return
        }
        val product = parseMoneyInput(state.editProductText, "商品金額", false) ?: return
        val received = parseMoneyInput(state.editReceivedText, "受取金額", true) ?: return
        saveOperation {
            repository.editPayment(entry.id, entry.revision, session.revision, product, received)
            dismissPaymentEditDialog()
            _uiState.update { it.copy(message = "明細を更新しました。", errorMessage = null) }
        }
    }

    fun requestVoidPayment(entry: CashEntry) {
        if (!entry.isVoided) {
            savedStateHandle[VOID_ENTRY_ID_KEY] = entry.id
            _uiState.update { it.copy(voidEntryId = entry.id, errorMessage = null) }
        }
    }

    fun dismissVoidPayment() {
        savedStateHandle[VOID_ENTRY_ID_KEY] = null
        _uiState.update { it.copy(voidEntryId = null) }
    }

    fun voidPayment() {
        val state = _uiState.value
        val entry = state.entries.firstOrNull { it.id == state.voidEntryId } ?: return
        val session = state.selectedRegister ?: return
        saveOperation {
            repository.voidEntry(entry.id, entry.revision, session.revision)
            dismissVoidPayment()
            _uiState.update { it.copy(message = "明細を取消しました。", errorMessage = null) }
        }
    }

    fun loadMoreEntries() {
        _uiState.update { it.copy(entryLimit = it.entryLimit + HISTORY_PAGE_SIZE) }
    }

    private fun saveOperation(operation: suspend () -> Unit) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            try {
                operation()
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = if (error is ConcurrentDataModification) "データが更新されたため、最新状態を読み込みました。もう一度確認してください。" else error.userMessage())
                }
            }
            _uiState.update { it.copy(isSaving = false) }
        }
    }

    private fun showError(message: String) = _uiState.update { it.copy(errorMessage = message, message = null) }

    private fun clearCalculatorInputState() {
        savedStateHandle[PRODUCT_KEY] = ""
        savedStateHandle[RECEIVED_KEY] = ""
        savedStateHandle[PENDING_ENTRY_ID_KEY] = null
        savedStateHandle[PENDING_ENTRY_SESSION_KEY] = null
    }

    companion object {
        private const val MAX_MONEY_DIGITS = 7
        private const val SELECTED_REGISTER_KEY = "calculator.selectedRegisterId"
        private const val SELECTED_TAB_KEY = "calculator.selectedTab"
        private const val FOCUSED_FIELD_KEY = "calculator.focusedField"
        private const val PRODUCT_KEY = "calculator.product"
        private const val RECEIVED_KEY = "calculator.received"
        private const val PENDING_ENTRY_ID_KEY = "calculator.pendingEntryId"
        private const val PENDING_ENTRY_SESSION_KEY = "calculator.pendingEntrySessionId"
        private const val EDIT_ENTRY_ID_KEY = "calculator.editEntryId"
        private const val EDIT_FOCUSED_FIELD_KEY = "calculator.editFocusedField"
        private const val EDIT_PRODUCT_KEY = "calculator.editProduct"
        private const val EDIT_RECEIVED_KEY = "calculator.editReceived"
        private const val VOID_ENTRY_ID_KEY = "calculator.voidEntryId"
    }
}

class UbaregiViewModelFactory(
    private val repository: RegisterRepository,
    private val exportService: AndroidExportService,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val savedStateHandle = extras.createSavedStateHandle()
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(repository, exportService)
            modelClass.isAssignableFrom(RegisterViewModel::class.java) -> RegisterViewModel(repository, savedStateHandle)
            modelClass.isAssignableFrom(CalculatorViewModel::class.java) -> CalculatorViewModel(repository, savedStateHandle)
            else -> error("Unknown ViewModel ${modelClass.name}")
        } as T
    }
}

private fun Throwable.userMessage(): String = when (this) {
    is RegisterDataException -> message ?: "保存できませんでした"
    else -> "保存できませんでした。入力を保持したまま、もう一度お試しください。"
}

private fun Throwable.loadUserMessage(): String = when (this) {
    is RegisterDataException -> "データを読み込めませんでした。${message ?: ""} アプリを再起動してください。保存済みデータは消去していません。"
    else -> "データを読み込めませんでした。アプリを再起動してください。保存済みデータは消去していません。"
}

private fun ChangeResult.messageForSave(): String = when (this) {
    ChangeResult.Empty -> "商品金額と受取金額を入力してください"
    is ChangeResult.Invalid -> message
    is ChangeResult.Shortage -> "受取金額が商品金額より${amount}円不足しています"
    is ChangeResult.Success -> ""
}
