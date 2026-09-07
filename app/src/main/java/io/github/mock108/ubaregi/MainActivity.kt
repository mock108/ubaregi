package io.github.mock108.ubaregi

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val ISSUES_URL = "https://github.com/mock108/ubaregi/issues"

internal object LedgerRepositoryHolder {
    var current: LedgerRepository? = null
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LedgerRepositoryHolder.current = LedgerRepository(UbaregiDatabase.get(applicationContext))
        enableEdgeToEdge()
        setContent { UbaregiTheme { UbaregiApp() } }
    }
}

internal enum class AppRoute(val title: String) {
    HOME("ウバレジ"),
    REGISTER("レジ締め / 初期釣銭"),
    CHANGE_CALCULATOR("おつり計算"),
    ABOUT("アプリ情報"),
}

internal data class LedgerUiState(
    val snapshot: LedgerSnapshot? = null,
    val busy: Boolean = false,
    val notice: String? = null,
)

internal data class PaymentPreview(
    val productYen: Long? = null,
    val receivedYen: Long? = null,
    val changeYen: Long? = null,
    val error: String? = null,
) {
    val canSave: Boolean get() = error == null && productYen != null && receivedYen != null && changeYen != null
}

internal data class ClosePreview(
    val actualYen: Long? = null,
    val nextYen: Long? = null,
    val summary: RegisterSummary? = null,
    val error: String? = null,
) {
    val canClose: Boolean get() = error == null && actualYen != null && nextYen != null && summary != null
}

internal class MainViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    private val repository = LedgerRepositoryHolder.current
    private val _route = MutableStateFlow(
        savedStateHandle.get<String>(ROUTE_KEY)
            ?.let { name -> AppRoute.entries.firstOrNull { it.name == name } }
            ?: AppRoute.HOME,
    )
    internal val route: StateFlow<AppRoute> = _route.asStateFlow()
    private val _ledger = MutableStateFlow(LedgerUiState())
    internal val ledger: StateFlow<LedgerUiState> = _ledger.asStateFlow()
    private val _productInput = MutableStateFlow(savedStateHandle.get<String>(PRODUCT_KEY).orEmpty())
    private val _receivedInput = MutableStateFlow(savedStateHandle.get<String>(RECEIVED_KEY).orEmpty())
    private val _draftResetToken = MutableStateFlow(savedStateHandle.get<Long>(DRAFT_RESET_KEY) ?: 0L)
    internal val productInput: StateFlow<String> = _productInput.asStateFlow()
    internal val receivedInput: StateFlow<String> = _receivedInput.asStateFlow()
    internal val draftResetToken: StateFlow<Long> = _draftResetToken.asStateFlow()

    init {
        if (repository != null) refresh()
    }

    internal fun navigate(route: AppRoute) {
        if (route == AppRoute.CHANGE_CALCULATOR) {
            savedStateHandle[SELECTED_SESSION_KEY] = _ledger.value.snapshot?.openSession?.id
        }
        savedStateHandle[ROUTE_KEY] = route.name
        _route.value = route
        refresh()
    }

    internal fun goHome() {
        savedStateHandle.remove<String>(SELECTED_SESSION_KEY)
        navigate(AppRoute.HOME)
    }

    internal fun openCalculatorFor(sessionId: String) {
        savedStateHandle[SELECTED_SESSION_KEY] = sessionId
        savedStateHandle[ROUTE_KEY] = AppRoute.CHANGE_CALCULATOR.name
        _route.value = AppRoute.CHANGE_CALCULATOR
        refresh()
    }

    internal fun calculatorSession(snapshot: LedgerSnapshot?): RegisterSessionEntity? {
        val selectedId = savedStateHandle.get<String>(SELECTED_SESSION_KEY)
        return selectedId?.let { id -> snapshot?.sessions?.firstOrNull { it.id == id } }
            ?: snapshot?.openSession
    }

    internal fun setProductInput(value: String) {
        savedStateHandle[PRODUCT_KEY] = value
        _productInput.value = value
    }

    internal fun setReceivedInput(value: String) {
        savedStateHandle[RECEIVED_KEY] = value
        _receivedInput.value = value
    }

    internal fun clearPaymentInput() {
        setProductInput("")
        setReceivedInput("")
    }

    internal fun paymentPreview(): PaymentPreview {
        val product = MoneyInput.parse(_productInput.value, "商品金額", allowZero = false)
        val received = MoneyInput.parse(_receivedInput.value, "受取金額", allowZero = true)
        if (product is MoneyParseResult.Invalid) return PaymentPreview(error = product.message)
        if (received is MoneyParseResult.Invalid) return PaymentPreview(error = received.message)
        product as MoneyParseResult.Valid
        received as MoneyParseResult.Valid
        if (received.value < product.value) {
            return PaymentPreview(
                productYen = product.value,
                receivedYen = received.value,
                error = "あと" + formatYen(product.value - received.value) + "不足しています",
            )
        }
        return PaymentPreview(product.value, received.value, received.value - product.value)
    }

    internal fun previewClose(actualRaw: String, nextRaw: String): ClosePreview {
        val snapshot = _ledger.value.snapshot ?: return ClosePreview(error = "データを読み込んでいます")
        val open = snapshot.openSession ?: return ClosePreview(error = "稼働中のレジがありません")
        val actual = MoneyInput.parse(actualRaw, "実残高", allowZero = true)
        val next = MoneyInput.parse(nextRaw, "次回釣銭", allowZero = true)
        if (actual is MoneyParseResult.Invalid) return ClosePreview(error = actual.message)
        if (next is MoneyParseResult.Invalid) return ClosePreview(error = next.message)
        actual as MoneyParseResult.Valid
        next as MoneyParseResult.Valid
        if (next.value > actual.value) return ClosePreview(error = "次回釣銭は実残高以下で入力してください")
        return ClosePreview(actual.value, next.value, LedgerCalculator.summary(open, snapshot.entriesFor(open.id)))
    }

    internal fun startRegister(raw: String) {
        val parsed = MoneyInput.parse(raw, "初期釣銭", allowZero = true)
        if (parsed is MoneyParseResult.Invalid) {
            setNotice(parsed.message)
            return
        }
        parsed as MoneyParseResult.Valid
        runBusy {
            when (val result = repository!!.startRegister(parsed.value)) {
                is LedgerOperation.Success -> "レジを開始しました。初期釣銭 " + formatYen(parsed.value)
                is LedgerOperation.Failure -> result.message
            }
        }
    }

    internal fun recordPayment() {
        val preview = paymentPreview()
        if (!preview.canSave) {
            setNotice(preview.error ?: "商品金額と受取金額を確認してください")
            return
        }
        val session = _ledger.value.snapshot?.openSession
        if (session == null) {
            setNotice("稼働中のレジがありません。先にレジを開始してください")
            return
        }
        val entryId = savedStateHandle.get<String>(PAYMENT_DRAFT_ID) ?: UUID.randomUUID().toString().also {
            savedStateHandle[PAYMENT_DRAFT_ID] = it
        }
        runBusy {
            when (val result = repository!!.savePayment(entryId, session.id, preview.productYen!!, preview.receivedYen!!)) {
                is LedgerOperation.Success -> {
                    savedStateHandle.remove<String>(PAYMENT_DRAFT_ID)
                    clearPaymentInput()
                    if (result.alreadySaved) "この受渡しは保存済みです（重複登録なし）" else "受渡しを記録しました"
                }
                is LedgerOperation.Failure -> result.message
            }
        }
    }

    internal fun saveMovement(kind: String, raw: String) {
        val label = if (kind == KIND_CASH_IN) "補充額" else "取出額"
        val parsed = MoneyInput.parse(raw, label, allowZero = false)
        if (parsed is MoneyParseResult.Invalid) {
            setNotice(parsed.message)
            return
        }
        val session = _ledger.value.snapshot?.openSession
        if (session == null) {
            setNotice("稼働中のレジがありません")
            return
        }
        parsed as MoneyParseResult.Valid
        val entryId = savedStateHandle.get<String>(MOVEMENT_DRAFT_ID) ?: UUID.randomUUID().toString().also {
            savedStateHandle[MOVEMENT_DRAFT_ID] = it
        }
        runBusy {
            when (val result = repository!!.saveCashMovement(entryId, session.id, kind, parsed.value)) {
                is LedgerOperation.Success -> {
                    savedStateHandle.remove<String>(MOVEMENT_DRAFT_ID)
                    if (kind == KIND_CASH_IN) "釣銭を" + formatYen(parsed.value) + "補充しました"
                    else "現金を" + formatYen(parsed.value) + "取り出しました"
                }
                is LedgerOperation.Failure -> result.message
            }
        }
    }

    internal fun beginMovementDraft() {
        savedStateHandle[MOVEMENT_DRAFT_ID] = UUID.randomUUID().toString()
    }

    internal fun closeRegister(actualRaw: String, nextRaw: String) {
        val preview = previewClose(actualRaw, nextRaw)
        if (!preview.canClose) {
            setNotice(preview.error ?: "締め入力を確認してください")
            return
        }
        val session = _ledger.value.snapshot?.openSession ?: return
        runBusy {
            when (val result = repository!!.closeRegister(session.id, session.revision, preview.actualYen!!, preview.nextYen!!)) {
                is LedgerOperation.Success -> "レジを締めました。次回釣銭 " + formatYen(preview.nextYen)
                is LedgerOperation.Failure -> result.message
            }
        }
    }

    internal fun editRegister(session: RegisterSessionEntity, openingRaw: String, actualRaw: String, nextRaw: String) {
        val opening = MoneyInput.parse(openingRaw, "初期釣銭", allowZero = true)
        if (opening is MoneyParseResult.Invalid) {
            setNotice(opening.message)
            return
        }
        val actual = if (session.status == STATUS_CLOSED) MoneyInput.parse(actualRaw, "実残高", allowZero = true) else null
        if (actual is MoneyParseResult.Invalid) {
            setNotice(actual.message)
            return
        }
        val next = if (session.status == STATUS_CLOSED) MoneyInput.parse(nextRaw, "次回釣銭", allowZero = true) else null
        if (next is MoneyParseResult.Invalid) {
            setNotice(next.message)
            return
        }
        val openingValue = (opening as MoneyParseResult.Valid).value
        val actualValue = (actual as? MoneyParseResult.Valid)?.value
        val nextValue = (next as? MoneyParseResult.Valid)?.value
        if (actualValue != null && nextValue != null && nextValue > actualValue) {
            setNotice("次回釣銭は実残高以下で入力してください")
            return
        }
        runBusy {
            when (val result = repository!!.editRegister(session.id, session.revision, openingValue, actualValue, nextValue)) {
                is LedgerOperation.Success -> if (session.status == STATUS_CLOSED) "レジ履歴を保存しました（締め後に修正済み）" else "レジ履歴を保存しました"
                is LedgerOperation.Failure -> result.message
            }
        }
    }

    internal fun editEntry(entry: CashEntryEntity, productRaw: String, receivedRaw: String, amountRaw: String) {
        val product = if (entry.kind == KIND_PAYMENT) MoneyInput.parse(productRaw, "商品金額", allowZero = false) else null
        if (product is MoneyParseResult.Invalid) {
            setNotice(product.message)
            return
        }
        val received = if (entry.kind == KIND_PAYMENT) MoneyInput.parse(receivedRaw, "受取金額", allowZero = true) else null
        if (received is MoneyParseResult.Invalid) {
            setNotice(received.message)
            return
        }
        val amount = if (entry.kind != KIND_PAYMENT) MoneyInput.parse(amountRaw, if (entry.kind == KIND_CASH_IN) "補充額" else "取出額", allowZero = false) else null
        if (amount is MoneyParseResult.Invalid) {
            setNotice(amount.message)
            return
        }
        val productValue = (product as? MoneyParseResult.Valid)?.value
        val receivedValue = (received as? MoneyParseResult.Valid)?.value
        val amountValue = (amount as? MoneyParseResult.Valid)?.value
        if (productValue != null && receivedValue != null && receivedValue < productValue) {
            setNotice("受取金額が不足しています")
            return
        }
        val session = _ledger.value.snapshot?.sessions?.firstOrNull { it.id == entry.sessionId }
        if (session == null) {
            setNotice("対象レジが見つかりません。画面を更新してください")
            return
        }
        runBusy {
            when (val result = repository!!.editEntry(entry.id, session.revision, entry.revision, productValue, receivedValue, amountValue)) {
                is LedgerOperation.Success -> if (session.status == STATUS_CLOSED) "明細を保存しました（締め後に修正済み）" else "明細を保存しました"
                is LedgerOperation.Failure -> result.message
            }
        }
    }

    internal fun cancelEntry(entry: CashEntryEntity) {
        val session = _ledger.value.snapshot?.sessions?.firstOrNull { it.id == entry.sessionId }
        if (session == null) {
            setNotice("対象レジが見つかりません。画面を更新してください")
            return
        }
        runBusy {
            when (val result = repository!!.cancelEntry(entry.id, session.revision, entry.revision)) {
                is LedgerOperation.Success -> "明細を取消しました。金額は履歴に残り、集計から除外されます"
                is LedgerOperation.Failure -> result.message
            }
        }
    }

    internal fun clearAll(expectedSnapshotRevision: Long) {
        val current = repository ?: return
        viewModelScope.launch {
            _ledger.value = _ledger.value.copy(busy = true, notice = null)
            val result = try {
                current.clearAll(expectedSnapshotRevision)
            } catch (_: Exception) {
                LedgerOperation.Failure("保存に失敗しました。履歴は変更していません")
            }
            val notice = when (result) {
                is LedgerOperation.Success -> {
                    savedStateHandle.remove<String>(PAYMENT_DRAFT_ID)
                    savedStateHandle.remove<String>(MOVEMENT_DRAFT_ID)
                    clearPaymentInput()
                    val nextResetToken = _draftResetToken.value + 1L
                    savedStateHandle[DRAFT_RESET_KEY] = nextResetToken
                    _draftResetToken.value = nextResetToken
                    savedStateHandle.remove<String>(SELECTED_SESSION_KEY)
                    savedStateHandle[ROUTE_KEY] = AppRoute.HOME.name
                    _route.value = AppRoute.HOME
                    "全履歴を消去しました（レジ ${result.value.sessionCount}件、明細 ${result.value.entryCount}件）"
                }
                is LedgerOperation.Failure -> result.message
            }
            try {
                _ledger.value = LedgerUiState(snapshot = current.loadSnapshot(), notice = notice)
            } catch (_: Exception) {
                _ledger.value = LedgerUiState(notice = "消去結果を読み込めませんでした。再起動して確認してください")
            }
        }
    }

    private fun refresh() {
        val current = repository ?: return
        viewModelScope.launch {
            try {
                _ledger.value = _ledger.value.copy(snapshot = current.loadSnapshot(), busy = false)
            } catch (_: Exception) {
                _ledger.value = _ledger.value.copy(notice = "保存データを読み込めませんでした。再起動して確認してください")
            }
        }
    }

    private fun runBusy(operation: suspend () -> String) {
        val current = repository ?: return
        viewModelScope.launch {
            _ledger.value = _ledger.value.copy(busy = true, notice = null)
            val notice = try {
                operation()
            } catch (_: Exception) {
                "保存に失敗しました。入力は保持しています"
            }
            try {
                _ledger.value = LedgerUiState(snapshot = current.loadSnapshot(), notice = notice)
            } catch (_: Exception) {
                _ledger.value = LedgerUiState(notice = "保存結果を読み込めませんでした。再起動して確認してください")
            }
        }
    }

    private fun setNotice(message: String) {
        _ledger.value = _ledger.value.copy(notice = message)
    }

    private companion object {
        const val ROUTE_KEY = "current_route"
        const val PRODUCT_KEY = "payment_product"
        const val RECEIVED_KEY = "payment_received"
        const val PAYMENT_DRAFT_ID = "payment_draft_id"
        const val MOVEMENT_DRAFT_ID = "movement_draft_id"
        const val SELECTED_SESSION_KEY = "selected_session_id"
        const val DRAFT_RESET_KEY = "draft_reset_token"
    }
}

@Composable
private fun UbaregiApp(viewModel: MainViewModel = viewModel()) {
    val route by viewModel.route.collectAsState()
    val ledger by viewModel.ledger.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    BackHandler(enabled = route != AppRoute.HOME) { viewModel.goHome() }
    Scaffold(
        topBar = {
            UbaregiTopBar(route.title, route != AppRoute.HOME, viewModel::goHome)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Surface(Modifier.fillMaxSize().padding(innerPadding), color = MaterialTheme.colorScheme.background) {
            when (route) {
                AppRoute.HOME -> HomeScreen(ledger, viewModel)
                AppRoute.REGISTER -> RegisterScreen(ledger, viewModel)
                AppRoute.CHANGE_CALCULATOR -> ChangeCalculatorScreen(ledger, viewModel)
                AppRoute.ABOUT -> AboutScreen(
                    onOpenIssues = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL)))
                        } catch (_: ActivityNotFoundException) {
                            scope.launch { snackbarHostState.showSnackbar("ブラウザーが見つかりません。URLをコピーして開いてください。") }
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UbaregiTopBar(title: String, showBack: Boolean, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { if (showBack) IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun HomeScreen(state: LedgerUiState, viewModel: MainViewModel) {
    val snapshot = state.snapshot
    val open = snapshot?.openSession
    val summary = open?.let { LedgerCalculator.summary(it, snapshot.entriesFor(it.id)) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusCard(open, summary)
        state.notice?.let { NoticeText(it) }
        Text("使う画面を選んでください", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        MenuButton("おつり計算", "商品金額と受取金額を確認し、受渡しを記録") { viewModel.navigate(AppRoute.CHANGE_CALCULATOR) }
        MenuButton("レジ締め / 初期釣銭", "レジの開始、補充・取出し、締めと次回釣銭") { viewModel.navigate(AppRoute.REGISTER) }
        MenuButton("アプリ情報", "バージョン、説明、プライバシー、OSS表示") { viewModel.navigate(AppRoute.ABOUT) }
        ClearAllAction(state, viewModel)
        ExportDisabledCard()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusCard(open: RegisterSessionEntity?, summary: RegisterSummary?) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AssistChip(onClick = {}, enabled = false, label = { Text(if (open == null) "レジ未開始" else "レジ稼働中") })
            if (open == null) {
                Text("現金の記録はありません", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("レジ締め / 初期釣銭から初期釣銭を登録して開始してください")
            } else {
                Text("開始 " + formatDate(open.openedAt), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("現金回収額（商品金額） " + formatYen(summary?.paymentCollectedYen ?: 0L))
                Text("予定残高 " + formatYen(summary?.expectedCashYen ?: 0L))
            }
        }
    }
}

@Composable
private fun RegisterScreen(state: LedgerUiState, viewModel: MainViewModel) {
    val snapshot = state.snapshot
    val open = snapshot?.openSession
    val draftResetToken by viewModel.draftResetToken.collectAsState()
    var initialRaw by rememberSaveable(draftResetToken) { mutableStateOf("") }
    var actualRaw by rememberSaveable(draftResetToken) { mutableStateOf("") }
    var nextRaw by rememberSaveable(draftResetToken) { mutableStateOf("") }
    var movementKind by rememberSaveable(draftResetToken) { mutableStateOf<String?>(null) }
    var movementRaw by rememberSaveable(draftResetToken) { mutableStateOf("") }
    var confirmClose by rememberSaveable(draftResetToken) { mutableStateOf(false) }
    var editingSession by remember { mutableStateOf<RegisterSessionEntity?>(null) }
    val candidate = snapshot?.latestClosed?.nextFloatYen
    val closePreview = if (open == null) null else viewModel.previewClose(actualRaw, nextRaw)
    val openSummary = snapshot?.openSession?.let { current -> LedgerCalculator.summary(current, snapshot.entriesFor(current.id)) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        state.notice?.let { NoticeText(it) }
        if (open == null) {
            Text("未開始のレジ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (candidate != null) {
                Text("前回の次回釣銭：" + formatYen(candidate))
                OutlinedButton(onClick = { initialRaw = candidate.toString() }, modifier = Modifier.fillMaxWidth()) { Text("前回の次回釣銭を候補に入れる") }
            } else {
                Text("初回は次回釣銭の候補がありません")
            }
            MoneyField("初期釣銭", initialRaw, { initialRaw = it })
            Button(onClick = { viewModel.startRegister(initialRaw) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("この金額でレジを開始") }
        } else {
            Text("稼働中のレジ #" + open.sequence, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            SummaryCard(open, openSummary!!)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { movementKind = KIND_CASH_IN; movementRaw = ""; viewModel.beginMovementDraft() }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("釣銭を補充") }
                OutlinedButton(onClick = { movementKind = KIND_CASH_OUT; movementRaw = ""; viewModel.beginMovementDraft() }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("現金を取り出す") }
            }
            Text("締め時の取出し前の手元現金を数えて入力してください")
            MoneyField("実残高", actualRaw, { actualRaw = it })
            MoneyField("次回釣銭", nextRaw, { nextRaw = it })
            closePreview?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            closePreview?.takeIf { it.canClose }?.let { preview ->
                val difference = preview.actualYen!! - preview.summary!!.expectedCashYen
                Text("予定残高 " + formatYen(preview.summary.expectedCashYen) + " / 過不足 " + formatDifference(difference))
                Text("締め時の取出額 " + formatYen(preview.actualYen - preview.nextYen!!))
            }
            Button(onClick = { confirmClose = true }, enabled = !state.busy && closePreview?.canClose == true, modifier = Modifier.fillMaxWidth()) { Text("レジを締める") }
            Text("このレジの記録", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            EntryHistory(snapshot.entriesFor(open.id), open, viewModel)
        }
        Text("レジ履歴（新しい順）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        snapshot?.sessions?.forEach { session ->
            RegisterHistoryCard(
                session = session,
                summary = LedgerCalculator.summary(session, snapshot.entriesFor(session.id)),
                onOpenEntries = { viewModel.openCalculatorFor(session.id) },
                onEdit = { editingSession = session },
            )
        }
        ClearAllAction(state, viewModel)
        Spacer(Modifier.height(8.dp))
    }
    movementKind?.let { kind ->
        AlertDialog(
            onDismissRequest = { movementKind = null },
            title = { Text(if (kind == KIND_CASH_IN) "釣銭を補充" else "現金を取り出す") },
            text = { MoneyField(if (kind == KIND_CASH_IN) "補充額" else "取出額", movementRaw, { movementRaw = it }) },
            confirmButton = { TextButton(onClick = { viewModel.saveMovement(kind, movementRaw); movementKind = null }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { movementKind = null }) { Text("キャンセル") } },
        )
    }
    if (confirmClose && closePreview?.canClose == true) {
        val difference = closePreview.actualYen!! - closePreview.summary!!.expectedCashYen
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("レジを締めますか？") },
            text = {
                Text("実残高 " + formatYen(closePreview.actualYen) + "、次回釣銭 " + formatYen(closePreview.nextYen!!) + "。" +
                    if (difference == 0L) "過不足なしで締めます。" else formatDifference(difference) + "のまま締めます。")
            },
            confirmButton = { TextButton(onClick = { confirmClose = false; viewModel.closeRegister(actualRaw, nextRaw) }) { Text("締める") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("戻る") } },
        )
    }
    editingSession?.let { session ->
        EditRegisterDialog(session, viewModel) { editingSession = null }
    }
}

@Composable
private fun ChangeCalculatorScreen(state: LedgerUiState, viewModel: MainViewModel) {
    val product by viewModel.productInput.collectAsState()
    val received by viewModel.receivedInput.collectAsState()
    val preview = viewModel.paymentPreview()
    val snapshot = state.snapshot
    val target = viewModel.calculatorSession(snapshot)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("おつりを確認", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            when {
                target == null -> "対象レジ：未開始（計算のみ）"
                target.status == STATUS_OPEN -> "対象レジ：稼働中 #" + target.sequence
                else -> "対象レジ：締め済み #" + target.sequence + "（新規明細は追加できません）"
            },
        )
        state.notice?.let { NoticeText(it) }
        MoneyField("商品金額", product, viewModel::setProductInput)
        MoneyField("お客様支払金額", received, viewModel::setReceivedInput)
        preview.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        preview.changeYen?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text("おつり " + formatYen(it), Modifier.padding(20.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = viewModel::clearPaymentInput, modifier = Modifier.weight(1f)) { Text("入力クリア") }
            Button(onClick = viewModel::recordPayment, enabled = !state.busy && target?.status == STATUS_OPEN && preview.canSave, modifier = Modifier.weight(1f)) { Text("受渡しを記録") }
        }
        if (target == null) {
            OutlinedButton(onClick = { viewModel.navigate(AppRoute.REGISTER) }, modifier = Modifier.fillMaxWidth()) { Text("レジを開始する") }
        } else {
            Text("受渡し履歴（新しい順）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            EntryHistory(snapshot?.entriesFor(target.id).orEmpty().filter { it.kind == KIND_PAYMENT }, target, viewModel)
            val movements = snapshot?.entriesFor(target.id).orEmpty().filter { it.kind != KIND_PAYMENT }
            if (movements.isNotEmpty()) {
                Text("補充・取出し履歴（新しい順）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                EntryHistory(movements, target, viewModel)
            }
        }
    }
}

@Composable
private fun MoneyField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, suffix = { Text("円") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SummaryCard(session: RegisterSessionEntity, summary: RegisterSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("初期釣銭 " + formatYen(session.openingFloatYen))
            Text("現金回収額（商品金額） " + formatYen(summary.paymentCollectedYen))
            Text("予定残高 " + formatYen(summary.expectedCashYen))
        }
    }
}

@Composable
private fun RegisterHistoryCard(
    session: RegisterSessionEntity,
    summary: RegisterSummary,
    onOpenEntries: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("#" + session.sequence + " " + if (session.status == STATUS_OPEN) "稼働中" else "締め済み", fontWeight = FontWeight.SemiBold)
            Text("開始 " + formatDate(session.openedAt) + " / 初期釣銭 " + formatYen(session.openingFloatYen))
            Text("予定残高 " + formatYen(summary.expectedCashYen) + " / 現金回収額 " + formatYen(summary.paymentCollectedYen))
            if (session.status == STATUS_CLOSED) {
                Text("実残高 " + formatYen(session.actualCashYen ?: 0L) + " / 次回釣銭 " + formatYen(session.nextFloatYen ?: 0L))
                Text("過不足 " + formatDifference(summary.differenceYen ?: 0L) + " / 締め時の取出額 " + formatYen(summary.withdrawalYen ?: 0L))
                if ((session.closeRevision ?: session.revision) < session.revision) {
                    Text("締め後に修正済み", color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onOpenEntries, modifier = Modifier.weight(1f)) { Text("このレジの取引を見る") }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("レジを編集") }
            }
        }
    }
}

@Composable
private fun EntryHistory(entries: List<CashEntryEntity>, session: RegisterSessionEntity, viewModel: MainViewModel) {
    val ledger by viewModel.ledger.collectAsState()
    var editingEntry by remember { mutableStateOf<CashEntryEntity?>(null) }
    var cancellingEntry by remember { mutableStateOf<CashEntryEntity?>(null) }
    if (entries.isEmpty()) {
        Text("記録はまだありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        entries.forEach { entry ->
            val label = when (entry.kind) {
                KIND_PAYMENT -> "受渡し：商品 " + formatYen(entry.productAmountYen ?: 0L) + " / 受取 " + formatYen(entry.receivedAmountYen ?: 0L) + " / おつり " + formatYen(LedgerCalculator.entryChange(entry) ?: 0L)
                KIND_CASH_IN -> "補充：" + formatYen(entry.amountYen ?: 0L)
                else -> "取出し：" + formatYen(entry.amountYen ?: 0L)
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(formatDate(entry.occurredAt) + "  " + label, style = MaterialTheme.typography.bodyMedium)
                    if (entry.isVoided) {
                        Text("取消済み（集計から除外）", color = MaterialTheme.colorScheme.error)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { editingEntry = entry }, enabled = !ledger.busy, modifier = Modifier.weight(1f)) { Text("編集") }
                            TextButton(onClick = { cancellingEntry = entry }, enabled = !ledger.busy, modifier = Modifier.weight(1f)) { Text("取消") }
                        }
                    }
                }
            }
        }
    }
    editingEntry?.let { entry ->
        EditEntryDialog(entry, viewModel) { editingEntry = null }
    }
    cancellingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { cancellingEntry = null },
            title = { Text("明細を取消しますか？") },
            text = { Text("元の金額は履歴に残りますが、集計から除外されます。取消後は元に戻せません。") },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelEntry(entry); cancellingEntry = null }) { Text("取消する") }
            },
            dismissButton = { TextButton(onClick = { cancellingEntry = null }) { Text("戻る") } },
        )
    }
}

@Composable
private fun EditRegisterDialog(session: RegisterSessionEntity, viewModel: MainViewModel, onDismiss: () -> Unit) {
    var openingRaw by rememberSaveable(session.id, session.revision) { mutableStateOf(session.openingFloatYen.toString()) }
    var actualRaw by rememberSaveable(session.id, session.revision, "actual") { mutableStateOf((session.actualCashYen ?: 0L).toString()) }
    var nextRaw by rememberSaveable(session.id, session.revision, "next") { mutableStateOf((session.nextFloatYen ?: 0L).toString()) }
    var confirmSave by remember { mutableStateOf(false) }
    val opening = MoneyInput.parse(openingRaw, "初期釣銭", allowZero = true)
    val actual = if (session.status == STATUS_CLOSED) MoneyInput.parse(actualRaw, "実残高", allowZero = true) else null
    val next = if (session.status == STATUS_CLOSED) MoneyInput.parse(nextRaw, "次回釣銭", allowZero = true) else null
    val openingValue = (opening as? MoneyParseResult.Valid)?.value
    val actualValue = (actual as? MoneyParseResult.Valid)?.value
    val nextValue = (next as? MoneyParseResult.Valid)?.value
    val validationError = when {
        opening is MoneyParseResult.Invalid -> opening.message
        actual is MoneyParseResult.Invalid -> actual.message
        next is MoneyParseResult.Invalid -> next.message
        actualValue != null && nextValue != null && nextValue > actualValue -> "次回釣銭は実残高以下で入力してください"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("レジ履歴を編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MoneyField("初期釣銭", openingRaw) { openingRaw = it }
                if (session.status == STATUS_CLOSED) {
                    MoneyField("実残高", actualRaw) { actualRaw = it }
                    MoneyField("次回釣銭", nextRaw) { nextRaw = it }
                    Text("保存すると締め後の修正として表示されます。後続レジの初期釣銭は変更しません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirmSave = true }, enabled = validationError == null) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
    if (confirmSave && validationError == null && openingValue != null) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("この内容で保存しますか？") },
            text = {
                Text(
                    "初期釣銭 " + formatYen(openingValue) +
                        if (session.status == STATUS_CLOSED) "、実残高 " + formatYen(actualValue ?: 0L) + "、次回釣銭 " + formatYen(nextValue ?: 0L) else "。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSave = false
                    onDismiss()
                    viewModel.editRegister(session, openingRaw, actualRaw, nextRaw)
                }) { Text("保存する") }
            },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("戻る") } },
        )
    }
}

@Composable
private fun EditEntryDialog(entry: CashEntryEntity, viewModel: MainViewModel, onDismiss: () -> Unit) {
    var productRaw by rememberSaveable(entry.id, entry.revision, "product") { mutableStateOf((entry.productAmountYen ?: 0L).toString()) }
    var receivedRaw by rememberSaveable(entry.id, entry.revision, "received") { mutableStateOf((entry.receivedAmountYen ?: 0L).toString()) }
    var amountRaw by rememberSaveable(entry.id, entry.revision, "amount") { mutableStateOf((entry.amountYen ?: 0L).toString()) }
    var confirmSave by remember { mutableStateOf(false) }
    val product = if (entry.kind == KIND_PAYMENT) MoneyInput.parse(productRaw, "商品金額", allowZero = false) else null
    val received = if (entry.kind == KIND_PAYMENT) MoneyInput.parse(receivedRaw, "受取金額", allowZero = true) else null
    val amount = if (entry.kind != KIND_PAYMENT) MoneyInput.parse(amountRaw, if (entry.kind == KIND_CASH_IN) "補充額" else "取出額", allowZero = false) else null
    val productValue = (product as? MoneyParseResult.Valid)?.value
    val receivedValue = (received as? MoneyParseResult.Valid)?.value
    val amountValue = (amount as? MoneyParseResult.Valid)?.value
    val validationError = when {
        product is MoneyParseResult.Invalid -> product.message
        received is MoneyParseResult.Invalid -> received.message
        amount is MoneyParseResult.Invalid -> amount.message
        productValue != null && receivedValue != null && receivedValue < productValue -> "受取金額が不足しています"
        else -> null
    }
    val title = when (entry.kind) {
        KIND_PAYMENT -> "受渡しを編集"
        KIND_CASH_IN -> "補充を編集"
        else -> "取出しを編集"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (entry.kind == KIND_PAYMENT) {
                    MoneyField("商品金額", productRaw) { productRaw = it }
                    MoneyField("お客様支払金額", receivedRaw) { receivedRaw = it }
                    if (productValue != null && receivedValue != null && receivedValue >= productValue) {
                        Text("おつり " + formatYen(receivedValue - productValue), style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    MoneyField(if (entry.kind == KIND_CASH_IN) "補充額" else "取出額", amountRaw) { amountRaw = it }
                }
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirmSave = true }, enabled = validationError == null) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
    if (confirmSave && validationError == null) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("この明細を保存しますか？") },
            text = {
                Text(
                    if (entry.kind == KIND_PAYMENT) {
                        "商品 " + formatYen(productValue ?: 0L) + "、受取 " + formatYen(receivedValue ?: 0L) + "、おつり " + formatYen((receivedValue ?: 0L) - (productValue ?: 0L))
                    } else {
                        (if (entry.kind == KIND_CASH_IN) "補充 " else "取出し ") + formatYen(amountValue ?: 0L)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSave = false
                    onDismiss()
                    viewModel.editEntry(entry, productRaw, receivedRaw, amountRaw)
                }) { Text("保存する") }
            },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("戻る") } },
        )
    }
}

@Composable
private fun ClearAllAction(state: LedgerUiState, viewModel: MainViewModel) {
    var showConfirm by remember { mutableStateOf(false) }
    val snapshot = state.snapshot
    OutlinedButton(
        onClick = { showConfirm = true },
        enabled = !state.busy && snapshot != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("全履歴をクリア") }
    if (showConfirm && snapshot != null) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("全履歴を消去しますか？") },
            text = {
                Text(
                    "レジ " + snapshot.sessions.size + "件、明細 " + snapshot.entries.size + "件を消去します。" +
                        if (snapshot.openSession != null) "稼働中のレジも対象です。" else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    viewModel.clearAll(snapshot.meta.snapshotRevision)
                }) { Text("すべて消去") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showConfirm = false; viewModel.goHome() }) { Text("ホームへ戻る（出力は段階4）") }
                    TextButton(onClick = { showConfirm = false }) { Text("キャンセル") }
                }
            },
        )
    }
}

@Composable
private fun NoticeText(message: String) {
    Text(message, color = if (message.contains("失敗") || message.contains("不足") || message.contains("入力") || message.contains("ありません")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
}

@Composable
private fun MenuButton(label: String, description: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().selectable(selected = false, onClick = onClick, role = androidx.compose.ui.semantics.Role.Button)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("開く", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ExportDisabledCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("データを書き出す", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("ファイル出力は段階4で対応します。段階2では保存処理を行いません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("JSONで保存") }
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("CSVで保存") }
            }
        }
    }
}

@Composable
private fun AboutScreen(onOpenIssues: () -> Unit) {
    val context = LocalContext.current
    val privacyPolicy = remember { readRawResource(context, R.raw.privacy_policy) }
    val licenses = remember { readRawResource(context, R.raw.licenses) }
    val ownLicense = remember { readRawResource(context, R.raw.license) }
    var privacyExpanded by rememberSaveable { mutableStateOf(false) }
    var ossExpanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ウバレジ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (BuildConfig.DEBUG) "個人用 debug 版" else "公開版")
                Text("バージョン " + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）")
                Text("開発者：Mockup")
            }
        }
        InfoSection("このアプリについて") {
            Text("Uber Eatsの配達で扱う現金とおつりを記録する、非公式の補助アプリです。")
            Text("検証対象：Android 17（API 37）")
            Text("本アプリはUberまたはUber Eatsの公式アプリではありません。表示される現金回収額は配達報酬や利益ではありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        InfoSection("データの扱い") {
            Text("計算・履歴・レジ締めはオフラインで利用でき、記録は端末内に保存します。")
            Text("広告配信、利用状況の解析、記録の自動送信は行いません。ログインもありません。")
            Text("CSV / JSONの書き出し、問い合わせのGitHub表示は、本人が操作したときだけ外部機能を開きます。")
            Text("アプリ削除やデータ消去、端末故障で記録が失われます。出力ファイルをアプリへ戻す機能はありません。")
        }
        InfoSection("個人用版の説明") {
            Text("すべての機能は無料です。この個人用版では開発支援の購入は利用できません。")
            Text("業務の記録は自動送信されません。必要な記録は本人が手動で書き出してください。")
        }
        ExpandableTextSection("プライバシーポリシー（同梱）", privacyExpanded, { privacyExpanded = !privacyExpanded }, privacyPolicy)
        ExpandableTextSection("OSS表示・ライセンス（同梱）", ossExpanded, { ossExpanded = !ossExpanded }, licenses + "\n\n---\n\n" + ownLicense)
        InfoSection("問い合わせ") {
            Text("GitHub Issuesを外部ブラウザーで開きます。投稿にはGitHub側のログインが必要な場合があります。")
            Text("顧客情報・実際の配達記録・端末情報は投稿しないでください。")
            OutlinedButton(onClick = onOpenIssues, modifier = Modifier.fillMaxWidth()) { Text("GitHub Issuesを開く") }
            Text(ISSUES_URL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        Text("開発支援購入：個人用版では利用できません。業務機能は無料です。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InfoSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun ExpandableTextSection(title: String, expanded: Boolean, onToggle: () -> Unit, text: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onToggle) { Text(if (expanded) "閉じる" else "展開") }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun readRawResource(context: Context, resourceId: Int): String =
    context.resources.openRawResource(resourceId).bufferedReader(Charsets.UTF_8).use { it.readText() }

internal fun formatYen(value: Long): String = java.lang.String.format(java.util.Locale.JAPAN, "%,d円", value)

private fun formatDifference(value: Long): String = when {
    value == 0L -> "過不足なし"
    value > 0L -> "超過 " + formatYen(value)
    else -> "不足 " + formatYen(-value)
}

private fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of("Asia/Tokyo")).format(DateTimeFormatter.ofPattern("M/d HH:mm"))

@Composable
private fun UbaregiTheme(content: @Composable () -> Unit) {
    val lightColors = androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF185ABC), onPrimary = Color.White, primaryContainer = Color(0xFFD8E5FF),
        onPrimaryContainer = Color(0xFF001A41), secondary = Color(0xFF4E5F79),
    )
    val darkColors = androidx.compose.material3.darkColorScheme(
        primary = Color(0xFFADC6FF), onPrimary = Color(0xFF002E6B), primaryContainer = Color(0xFF16457E),
        onPrimaryContainer = Color(0xFFD8E5FF), secondary = Color(0xFFBAC7E0),
    )
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (isDark) darkColors else lightColors, content = content)
}
