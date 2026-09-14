package io.github.mock108.ubaregi

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mock108.ubaregi.data.CashEntry
import io.github.mock108.ubaregi.data.CashEntryKind
import io.github.mock108.ubaregi.data.RegisterRepository
import io.github.mock108.ubaregi.data.RegisterSession
import io.github.mock108.ubaregi.data.RegisterStatus
import io.github.mock108.ubaregi.domain.calculateAmounts
import kotlin.math.abs

private const val CONTACT_URL = "https://github.com/mock108/ubaregi/issues"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UbaregiTheme {
                val repository = (application as UbaregiApplication).registerRepository
                UbaregiApp(repository)
            }
        }
    }
}

private enum class AppDestination(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    HOME("ホーム", Icons.Filled.Home),
    REGISTER("レジ", Icons.Filled.PointOfSale),
    CALCULATOR("計算", Icons.Filled.Calculate),
    ABOUT("情報", Icons.Filled.Info),
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun UbaregiApp(repository: RegisterRepository) {
    var destinationName by rememberSaveable { mutableStateOf(AppDestination.HOME.name) }
    var calculatorTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val destination = AppDestination.valueOf(destinationName)
    val factory = remember(repository) { UbaregiViewModelFactory(repository) }
    val homeViewModel: HomeViewModel = viewModel(factory = factory)
    val registerViewModel: RegisterViewModel = viewModel(factory = factory)
    val calculatorViewModel: CalculatorViewModel = viewModel(factory = factory)
    val context = LocalContext.current

    Scaffold(
        topBar = { TopAppBar(title = { Text(destination.label) }) },
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destinationName = item.name },
                        icon = { Icon(item.icon, contentDescription = "${item.label}画面") },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (destination) {
            AppDestination.HOME -> HomeScreen(
                paddingValues = innerPadding,
                viewModel = homeViewModel,
                onNavigate = { destinationName = it.name },
                onExit = { (context as? Activity)?.finish() },
            )

            AppDestination.REGISTER -> RegisterScreen(
                paddingValues = innerPadding,
                viewModel = registerViewModel,
                onNavigateToCalculator = { sessionId ->
                    calculatorTargetId = sessionId
                    destinationName = AppDestination.CALCULATOR.name
                },
                onNavigateHome = { destinationName = AppDestination.HOME.name },
            )

            AppDestination.CALCULATOR -> CalculatorScreen(
                paddingValues = innerPadding,
                viewModel = calculatorViewModel,
                initialTargetId = calculatorTargetId,
                onTargetConsumed = { calculatorTargetId = null },
                onNavigateToRegister = { destinationName = AppDestination.REGISTER.name },
                onNavigateHome = { destinationName = AppDestination.HOME.name },
            )

            AppDestination.ABOUT -> AboutScreen(
                paddingValues = innerPadding,
                onNavigateHome = { destinationName = AppDestination.HOME.name },
                onContact = {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(CONTACT_URL))
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("問い合わせURL", CONTACT_URL))
                        Toast.makeText(context, "ブラウザーがないためURLをコピーしました。", Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    paddingValues: PaddingValues,
    viewModel: HomeViewModel,
    onNavigate: (AppDestination) -> Unit,
    onExit: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler { onExit() }
    AppContent(paddingValues) {
        Text("ウバレジ", style = MaterialTheme.typography.headlineMedium)
        Text("現金とおつりを端末内で記録する補助アプリ")
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (state.openRegister == null) {
                    Text("レジはまだ始まっていません", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("レジを始めると、今回の現金の動きを記録できます。")
                } else {
                    val register = state.openRegister
                    Text("レジ稼働中", style = MaterialTheme.typography.titleLarge)
                    Text("レジ #${register?.sequence} / ${formatDateTime(register?.openedAt)}")
                    Spacer(Modifier.height(8.dp))
                    SummaryLine("受け取った現金", formatYen(state.summary?.paymentCollectedYen))
                    SummaryLine(EXPECTED_CASH_LABEL, formatYen(state.summary?.expectedCashYen))
                    SummaryLine("現在の状態", RegisterStatus.OPEN.displayLabel())
                }
            }
        }

        ErrorText(state.errorMessage)
        Spacer(Modifier.height(16.dp))
        HomeActionButton("おつりを計算", "商品金額と受取金額から計算") { onNavigate(AppDestination.CALCULATOR) }
        HomeActionButton("レジを始める・終える", "開始時の釣銭、現金の補充・取出し、終了時の確認") { onNavigate(AppDestination.REGISTER) }
        HomeActionButton("アプリ情報", "データの扱いと問い合わせ先") { onNavigate(AppDestination.ABOUT) }
    }
}

@Composable
private fun RegisterScreen(
    paddingValues: PaddingValues,
    viewModel: RegisterViewModel,
    onNavigateToCalculator: (String) -> Unit,
    onNavigateHome: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selected = state.selectedRegister
    val summary = state.selectedSummary

    BackHandler {
        when {
            state.clearHistoryDialog != null -> viewModel.dismissClearHistoryDialog()
            state.voidEntryId != null -> viewModel.dismissVoidEntry()
            state.entryEditDialog != null -> viewModel.dismissEntryEditDialog()
            state.registerEditDialog != null -> viewModel.dismissRegisterEditDialog()
            state.isCloseConfirmationVisible -> viewModel.dismissCloseConfirmation()
            state.closeDialog != null -> viewModel.dismissCloseDialog()
            state.adjustmentDialog != null -> viewModel.dismissAdjustmentDialog()
            else -> onNavigateHome()
        }
    }

    AppContent(paddingValues) {
        Text("レジの開始・終了", style = MaterialTheme.typography.headlineSmall)
        Text("前回レジ終了時に残した釣銭を候補として表示します。開始ボタンを押すまで保存されません。")
        ErrorText(state.errorMessage)
        SuccessText(state.message)

        if (state.openRegister == null) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("開始時の釣銭を入力してレジを始める", style = MaterialTheme.typography.titleMedium)
                    state.latestClosed?.nextFloatYen?.let { candidate ->
                        Text("前回終了時に残した釣銭: ${formatYen(candidate)}")
                        TextButton(onClick = { viewModel.onOpeningFloatChanged(candidate.toString()) }) { Text("候補を入力") }
                    } ?: Text("初回のため候補はありません。0円でも開始できます。")
                    MoneyField(STARTING_CHANGE_LABEL, state.openingFloatText, viewModel::onOpeningFloatChanged)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = viewModel::startRegister, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.isSaving) "保存中…" else "この金額でレジを始める")
                    }
                }
            }
        } else {
            val open = state.openRegister
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("稼働中のレジ #${open?.sequence}", style = MaterialTheme.typography.titleLarge)
                    Text("開始日時 ${formatDateTime(open?.openedAt)}")
                    SummaryLine(STARTING_CHANGE_LABEL, formatYen(open?.openingFloatYen))
                    SummaryLine("受け取った現金", formatYen(state.openSummary?.paymentCollectedYen))
                    SummaryLine("補充した現金", formatYen(state.openSummary?.cashInYen))
                    SummaryLine("取り出した現金", formatYen(state.openSummary?.cashOutYen))
                    SummaryLine(EXPECTED_CASH_LABEL, formatYen(state.openSummary?.expectedCashYen))
                    SummaryLine("記録件数", state.openEntries.size.toString())
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.showAdjustmentDialog(CashEntryKind.CASH_IN) }, enabled = !state.isSaving) { Text("釣銭を補充") }
                        OutlinedButton(onClick = { viewModel.showAdjustmentDialog(CashEntryKind.CASH_OUT) }, enabled = !state.isSaving) { Text("現金を取り出す") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::showRegisterEditDialog, enabled = !state.isSaving) { Text("開始時の釣銭を編集") }
                        Button(onClick = viewModel::showCloseDialog, enabled = !state.isSaving) { Text("レジを終える") }
                    }
                }
            }
        }

        if (selected != null) {
            Spacer(Modifier.height(16.dp))
            Text("選択中のレジ #${selected.sequence}", style = MaterialTheme.typography.titleMedium)
            if (selected.status == RegisterStatus.CLOSED && selected.revision > (selected.closeRevision ?: selected.revision)) {
                Text("終了後に修正済み", color = MaterialTheme.colorScheme.primary)
            }
            SummaryLine("状態", selected.status.displayLabel())
            SummaryLine(STARTING_CHANGE_LABEL, formatYen(selected.openingFloatYen))
            SummaryLine(EXPECTED_CASH_LABEL, formatYen(summary?.expectedCashYen))
            if (selected.status == RegisterStatus.CLOSED) {
                SummaryLine(COUNTED_CASH_LABEL, formatYen(selected.actualCashYen))
                SummaryLine(DIFFERENCE_LABEL, formatSignedYen(summary?.differenceYen))
                SummaryLine(NEXT_CHANGE_LABEL, formatYen(selected.nextFloatYen))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::showRegisterEditDialog, enabled = !state.isSaving) { Text("このレジを編集") }
                OutlinedButton(onClick = { onNavigateToCalculator(selected.id) }) { Text("このレジの取引を見る") }
            }
            if (state.selectedEntries.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("明細", style = MaterialTheme.typography.titleMedium)
                state.selectedEntries.filter { it.kind != CashEntryKind.PAYMENT }.forEach { entry ->
                    AdjustmentEntryCard(
                        entry = entry,
                        onEdit = { viewModel.showEntryEditDialog(entry) },
                        onVoid = { viewModel.requestVoidEntry(entry) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("レジ履歴（新しい順）", style = MaterialTheme.typography.titleMedium)
        state.registers.forEach { register ->
            RegisterHistoryCard(register, state.summaries[register.id], selected?.id == register.id) { viewModel.selectRegister(register.id) }
        }
        OutlinedButton(onClick = viewModel::showClearHistoryDialog, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) {
            Text("全レジ履歴をクリア")
        }
    }

    state.adjustmentDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::dismissAdjustmentDialog,
            title = { Text(if (dialog.kind == CashEntryKind.CASH_IN) "釣銭を補充" else "現金を取り出す") },
            text = {
                Column {
                    MoneyField("移動する金額", dialog.amountText, viewModel::onAdjustmentAmountChanged)
                    Spacer(Modifier.height(8.dp))
                    Text("実際に現金を移動してから保存してください。")
                }
            },
            confirmButton = { Button(onClick = viewModel::saveAdjustment, enabled = !state.isSaving) { Text("保存") } },
            dismissButton = { TextButton(onClick = viewModel::dismissAdjustmentDialog) { Text("キャンセル") } },
        )
    }

    state.closeDialog?.let { dialog ->
        val actual = parseMoneyInput(dialog.actualCashText, COUNTED_CASH_LABEL, true)
        val next = parseMoneyInput(dialog.nextFloatText, NEXT_CHANGE_LABEL, true)
        val openSummary = state.openSummary
        val openRegister = state.openRegister
        AlertDialog(
            onDismissRequest = viewModel::dismissCloseDialog,
            title = { Text("レジを終える") },
            text = {
                Column {
                    Text("レジ内の現金を数え、取出し前の金額を入力してください。")
                    Spacer(Modifier.height(8.dp))
                    MoneyField("$COUNTED_CASH_LABEL（取出し前）", dialog.actualCashText, viewModel::onCloseActualChanged)
                    MoneyField(NEXT_CHANGE_LABEL, dialog.nextFloatText, viewModel::onCloseNextFloatChanged)
                    Spacer(Modifier.height(8.dp))
                    SummaryLine(EXPECTED_CASH_LABEL, formatYen(openSummary?.expectedCashYen))
                    if (actual != null && openSummary != null) SummaryLine(DIFFERENCE_LABEL, formatSignedYen(actual - openSummary.expectedCashYen))
                    if (actual != null && openRegister != null) SummaryLine("開始時からの現金増減", formatSignedYen(actual - openRegister.openingFloatYen))
                    if (actual != null && next != null) SummaryLine("今回取り出す現金", formatYen(actual - next))
                }
            },
            confirmButton = { Button(onClick = viewModel::requestCloseConfirmation, enabled = !state.isSaving) { Text("終了内容を確認") } },
            dismissButton = { TextButton(onClick = viewModel::dismissCloseDialog) { Text("キャンセル") } },
        )
    }

    if (state.isCloseConfirmationVisible) {
        val dialog = state.closeDialog
        val actual = dialog?.let { parseMoneyInput(it.actualCashText, COUNTED_CASH_LABEL, true) }
        val next = dialog?.let { parseMoneyInput(it.nextFloatText, NEXT_CHANGE_LABEL, true) }
        val openSummary = state.openSummary
        val difference = if (actual != null && openSummary != null) actual - openSummary.expectedCashYen else null
        AlertDialog(
            onDismissRequest = viewModel::dismissCloseConfirmation,
            title = { Text("この内容でレジを終了しますか？") },
            text = {
                Column {
                    Text("$DIFFERENCE_LABEL: ${formatSignedYen(difference)}")
                    if (difference != null && difference != 0L) Text(if (difference < 0) "${formatYen(abs(difference))}不足のまま終了します。" else "${formatYen(difference)}余ったまま終了します。")
                    if (actual != null && next != null) Text("今回取り出す現金: ${formatYen(actual - next)}")
                    Text("確認中に別の更新があった場合は保存せず、最新状態を読み直します。")
                }
            },
            confirmButton = { Button(onClick = viewModel::closeRegister, enabled = !state.isSaving) { Text("レジを終了する") } },
            dismissButton = { TextButton(onClick = viewModel::dismissCloseConfirmation) { Text("戻る") } },
        )
    }

    state.registerEditDialog?.let { dialog ->
        val isClosed = selected?.status == RegisterStatus.CLOSED
        val opening = parseMoneyInput(dialog.openingFloatText, STARTING_CHANGE_LABEL, true)
        val editedExpected = if (opening != null && selected != null && summary != null) {
            summary.expectedCashYen + opening - selected.openingFloatYen
        } else {
            null
        }
        val editedActual = if (isClosed) parseMoneyInput(dialog.actualCashText, COUNTED_CASH_LABEL, true) else null
        AlertDialog(
            onDismissRequest = viewModel::dismissRegisterEditDialog,
            title = { Text("レジの記録を編集") },
            text = {
                Column {
                    MoneyField(STARTING_CHANGE_LABEL, dialog.openingFloatText, viewModel::onRegisterEditOpeningChanged)
                    if (isClosed) {
                        MoneyField(COUNTED_CASH_LABEL, dialog.actualCashText, viewModel::onRegisterEditActualChanged)
                        MoneyField(NEXT_CHANGE_LABEL, dialog.nextFloatText, viewModel::onRegisterEditNextChanged)
                    }
                    if (editedExpected != null) {
                        SummaryLine("編集後の$EXPECTED_CASH_LABEL", formatYen(editedExpected))
                        if (editedActual != null) SummaryLine("編集後の$DIFFERENCE_LABEL", formatSignedYen(editedActual - editedExpected))
                    }
                    Text("編集前後の集計は保存後に再計算されます。別のレジの開始時の釣銭は変更しません。")
                }
            },
            confirmButton = { Button(onClick = viewModel::saveRegisterEdit, enabled = !state.isSaving) { Text("保存") } },
            dismissButton = { TextButton(onClick = viewModel::dismissRegisterEditDialog) { Text("キャンセル") } },
        )
    }

    state.entryEditDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::dismissEntryEditDialog,
            title = { Text("補充・取出し明細を編集") },
            text = { MoneyField("金額", dialog.amountText, viewModel::onEntryEditAmountChanged) },
            confirmButton = { Button(onClick = viewModel::saveEntryEdit, enabled = !state.isSaving) { Text("保存") } },
            dismissButton = { TextButton(onClick = viewModel::dismissEntryEditDialog) { Text("キャンセル") } },
        )
    }

    state.voidEntryId?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissVoidEntry,
            title = { Text("明細を取消しますか？") },
            text = { Text("取消済み明細は集計から除外され、復活できません。") },
            confirmButton = { Button(onClick = viewModel::voidEntry, enabled = !state.isSaving) { Text("取消する") } },
            dismissButton = { TextButton(onClick = viewModel::dismissVoidEntry) { Text("キャンセル") } },
        )
    }

    state.clearHistoryDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::dismissClearHistoryDialog,
            title = { Text("全履歴を消去しますか？") },
            text = {
                Column {
                    Text("レジ件数: ${dialog.registerCount}件")
                    Text("明細件数: ${dialog.entryCount}件")
                    Text("稼働中のレジ: ${if (dialog.hasOpenRegister) "あり" else "なし"}")
                    Spacer(Modifier.height(8.dp))
                    Text("この操作は復元できません。稼働中レジも削除されます。")
                }
            },
            confirmButton = { Button(onClick = viewModel::clearAllHistory, enabled = !state.isSaving) { Text("すべて消去") } },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::dismissClearHistoryDialog) { Text("キャンセル") }
                    TextButton(onClick = viewModel::dismissClearHistoryDialog) { Text("ホームで先に出力") }
                }
            },
        )
    }
}

@Composable
private fun CalculatorScreen(
    paddingValues: PaddingValues,
    viewModel: CalculatorViewModel,
    initialTargetId: String?,
    onTargetConsumed: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateHome: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var registerMenuExpanded by remember { mutableStateOf(false) }
    var helpVisible by rememberSaveable { mutableStateOf(false) }
    var discardEditConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    val selected = state.selectedRegister
    val result = remember(state.productText, state.receivedText) { ChangeCalculator.calculate(state.productText, state.receivedText) }
    val editEntry = state.editEntryId?.let { id -> state.paymentEntries.firstOrNull { it.id == id } }
    val editResult = remember(state.editProductText, state.editReceivedText) {
        ChangeCalculator.calculate(state.editProductText, state.editReceivedText)
    }
    val editHasChanges = editEntry != null &&
        (state.editProductText != editEntry.productAmountYen?.toString() ||
            state.editReceivedText != editEntry.receivedAmountYen?.toString())

    fun requestDismissEdit() {
        if (editHasChanges) {
            discardEditConfirmationVisible = true
        } else {
            viewModel.dismissPaymentEditDialog()
        }
    }

    BackHandler {
        when {
            discardEditConfirmationVisible -> discardEditConfirmationVisible = false
            helpVisible -> helpVisible = false
            state.voidEntryId != null -> viewModel.dismissVoidPayment()
            state.editEntryId != null -> requestDismissEdit()
            else -> onNavigateHome()
        }
    }

    LaunchedEffect(Unit) { viewModel.selectTab(CalculatorTab.CALCULATE) }

    LaunchedEffect(initialTargetId) {
        initialTargetId?.let {
            viewModel.setInitialTarget(it)
            onTargetConsumed()
        }
    }

    AppContent(paddingValues) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("おつり計算", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { helpVisible = true }) {
                Icon(Icons.Filled.Info, contentDescription = "おつり計算の説明")
            }
        }
        ErrorText(state.errorMessage)
        SuccessText(state.message)
        Spacer(Modifier.height(12.dp))

        Text("対象レジ", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { registerMenuExpanded = true }, enabled = state.registers.isNotEmpty()) {
                Text(selected?.let { "#${it.sequence} ${it.status.displayLabel()}" } ?: "レジはまだありません")
            }
            DropdownMenu(expanded = registerMenuExpanded, onDismissRequest = { registerMenuExpanded = false }) {
                state.registers.forEach { register ->
                    DropdownMenuItem(
                        text = { Text("#${register.sequence} ${register.status.displayLabel()}") },
                        onClick = { viewModel.selectRegister(register.id); registerMenuExpanded = false },
                    )
                }
            }
        }
        if (selected?.status == RegisterStatus.CLOSED) {
            Text("終了済みのため、取引は記録できません", color = MaterialTheme.colorScheme.error)
        }
        TabRow(selectedTabIndex = state.selectedTab.ordinal) {
            Tab(
                selected = state.selectedTab == CalculatorTab.CALCULATE,
                onClick = { viewModel.selectTab(CalculatorTab.CALCULATE) },
                text = { Text("計算") },
            )
            Tab(
                selected = state.selectedTab == CalculatorTab.RECORDS,
                onClick = { viewModel.selectTab(CalculatorTab.RECORDS) },
                text = { Text("記録") },
            )
        }
        Spacer(Modifier.height(16.dp))

        when (state.selectedTab) {
            CalculatorTab.CALCULATE -> {
                CalculatorMoneyField(
                    label = "商品金額",
                    value = state.productText,
                    focused = state.focusedField == CalculatorInputField.PRODUCT,
                    onFocus = { viewModel.focusField(CalculatorInputField.PRODUCT) },
                )
                Spacer(Modifier.height(10.dp))
                CalculatorMoneyField(
                    label = "お客様支払金額",
                    value = state.receivedText,
                    focused = state.focusedField == CalculatorInputField.RECEIVED,
                    onFocus = { viewModel.focusField(CalculatorInputField.RECEIVED) },
                )
                Spacer(Modifier.height(14.dp))

                when (result) {
                    ChangeResult.Empty -> Text("2つの金額を入力してください")
                    is ChangeResult.Invalid -> ErrorText(result.message)
                    is ChangeResult.Shortage -> ErrorText("あと${formatYen(result.amount)}不足")
                    is ChangeResult.Success -> Text("おつり ${formatYen(result.change)}", style = MaterialTheme.typography.headlineSmall)
                }

                Spacer(Modifier.height(12.dp))
                CalculatorKeypad(
                    enabled = !state.isSaving,
                    onDigit = viewModel::appendDigit,
                    onDelete = viewModel::deleteLastDigit,
                    onClear = viewModel::clearFocusedInput,
                    onMoveFocus = viewModel::moveFocus,
                    onNext = viewModel::nextInput,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = viewModel::savePayment,
                        modifier = Modifier.weight(1f),
                        enabled = (selected?.status == RegisterStatus.OPEN || state.pendingEntryId != null) &&
                            result is ChangeResult.Success && !state.isSaving,
                    ) {
                        Text(
                            when {
                                state.isSaving -> "保存中…"
                                selected?.status == RegisterStatus.CLOSED -> "保存を再試行"
                                else -> "受渡しを記録"
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::clearInput,
                        modifier = Modifier.weight(1f),
                        enabled = !state.isSaving,
                    ) { Text("入力をクリア") }
                }
                if (selected == null) {
                    TextButton(onClick = onNavigateToRegister) { Text("レジを開始する") }
                }
            }

            CalculatorTab.RECORDS -> {
                Text("受渡し履歴", style = MaterialTheme.typography.titleMedium)
                if (state.paymentEntries.isEmpty()) Text("このレジの受け渡し記録はありません。")
                state.paymentEntries.forEach { entry ->
                    PaymentEntryCard(
                        entry = entry,
                        onEdit = { viewModel.showPaymentEditDialog(entry) },
                        onVoid = { viewModel.requestVoidPayment(entry) },
                    )
                }
            }
        }
    }

    state.editEntryId?.let {
        AlertDialog(
            onDismissRequest = ::requestDismissEdit,
            title = { Text("受渡し明細を編集") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CalculatorMoneyField(
                        label = "商品金額",
                        value = state.editProductText,
                        focused = state.editFocusedField == CalculatorInputField.PRODUCT,
                        onFocus = { viewModel.focusEditField(CalculatorInputField.PRODUCT) },
                    )
                    Spacer(Modifier.height(8.dp))
                    CalculatorMoneyField(
                        label = "お客様支払金額",
                        value = state.editReceivedText,
                        focused = state.editFocusedField == CalculatorInputField.RECEIVED,
                        onFocus = { viewModel.focusEditField(CalculatorInputField.RECEIVED) },
                    )
                    Spacer(Modifier.height(8.dp))
                    when (editResult) {
                        ChangeResult.Empty -> Text("2つの金額を入力してください")
                        is ChangeResult.Invalid -> ErrorText(editResult.message)
                        is ChangeResult.Shortage -> ErrorText("あと${formatYen(editResult.amount)}不足")
                        is ChangeResult.Success -> Text("おつり ${formatYen(editResult.change)}")
                    }
                    Spacer(Modifier.height(8.dp))
                    CalculatorKeypad(
                        enabled = !state.isSaving,
                        onDigit = viewModel::appendEditDigit,
                        onDelete = viewModel::deleteEditLastDigit,
                        onClear = viewModel::clearEditFocusedInput,
                        onMoveFocus = viewModel::moveEditFocus,
                        onNext = viewModel::nextEditInput,
                    )
                }
            },
            confirmButton = { Button(onClick = viewModel::savePaymentEdit, enabled = !state.isSaving) { Text("保存") } },
            dismissButton = { TextButton(onClick = ::requestDismissEdit) { Text("キャンセル") } },
        )
    }
    state.voidEntryId?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissVoidPayment,
            title = { Text("明細を取消しますか？") },
            text = { Text("取消済み明細は集計から除外され、復活・編集できません。") },
            confirmButton = { Button(onClick = viewModel::voidPayment, enabled = !state.isSaving) { Text("取消する") } },
            dismissButton = { TextButton(onClick = viewModel::dismissVoidPayment) { Text("キャンセル") } },
        )
    }

    if (discardEditConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { discardEditConfirmationVisible = false },
            title = { Text("編集を破棄しますか？") },
            text = { Text("変更した金額は保存されません。") },
            confirmButton = {
                Button(onClick = {
                    discardEditConfirmationVisible = false
                    viewModel.dismissPaymentEditDialog()
                }) { Text("破棄する") }
            },
            dismissButton = { TextButton(onClick = { discardEditConfirmationVisible = false }) { Text("編集を続ける") } },
        )
    }

    if (helpVisible) {
        AlertDialog(
            onDismissRequest = { helpVisible = false },
            title = { Text("おつり計算の使い方") },
            text = {
                Text(
                    "商品金額と受取金額を入力すると、おつりまたは不足額を表示します。\n\n" +
                        "不足している場合は保存できません。実際に現金を受け渡した後に「受渡しを記録」を押してください。\n\n" +
                        "レジ未開始でも計算だけ利用できます。記録は端末内に保存され、ログインやクラウド同期はありません。",
                )
            },
            confirmButton = { TextButton(onClick = { helpVisible = false }) { Text("閉じる") } },
        )
    }
}

@Composable
private fun AboutScreen(
    paddingValues: PaddingValues,
    onNavigateHome: () -> Unit,
    onContact: () -> Unit,
) {
    BackHandler { onNavigateHome() }
    AppContent(paddingValues) {
        Text("アプリ情報", style = MaterialTheme.typography.headlineSmall)
        Text("アプリ名: ${stringResource(R.string.app_name)}")
        Text("個人用アプリ / 開発者: Mockup")
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text("Android 17向け")
        Spacer(Modifier.height(12.dp))
        Text("Uber Eats公式アプリではない、個人用の非公式補助アプリです。")
        Text("記録は端末内にローカル保存し、オフラインで利用できます。ログイン、クラウド同期、業務データの外部送信はありません。")
        Text("自動バックアップや端末移行には対応しません。アンインストールや端末のアプリデータ消去で履歴は失われます。")
        Spacer(Modifier.height(12.dp))
        Text("問い合わせ操作をしたときだけ、固定URLを外部ブラウザーで開きます。顧客情報・金額・端末情報はURLに付加しません。")
        Spacer(Modifier.height(8.dp))
        Button(onClick = onContact) { Text("GitHubで問い合わせ") }
    }
}

@Composable
private fun CalculatorMoneyField(
    label: String,
    value: String,
    focused: Boolean,
    onFocus: () -> Unit,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onFocus),
        label = { Text(label) },
        singleLine = true,
        readOnly = true,
        enabled = enabled,
        isError = false,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun CalculatorKeypad(
    enabled: Boolean,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onMoveFocus: () -> Unit,
    onNext: () -> Unit,
) {
    val numberRows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        numberRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { digit ->
                    Button(
                        onClick = { onDigit(digit) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text(digit.toString(), style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onDigit(0) },
                enabled = enabled,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("0", style = MaterialTheme.typography.titleLarge) }
            KeypadIconButton(Icons.Filled.Backspace, "1文字削除", onDelete, enabled)
            KeypadIconButton(Icons.Filled.DeleteSweep, "入力欄を全消去", onClear, enabled)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            KeypadIconButton(Icons.Filled.SwapVert, "商品金額と受取金額を切り替え", onMoveFocus, enabled)
            KeypadIconButton(Icons.Filled.ArrowForward, "次の入力へ移動", onNext, enabled)
        }
    }
}

@Composable
private fun RowScope.KeypadIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).height(52.dp),
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}

@Composable
private fun AdjustmentEntryCard(entry: CashEntry, onEdit: () -> Unit, onVoid: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("#${entry.sequence} ${entry.kind.displayLabel()}")
            SummaryLine("日時", formatDateTime(entry.occurredAt))
            SummaryLine("金額", formatYen(entry.amountYen))
            if (entry.isVoided) {
                Text("取消済み", color = MaterialTheme.colorScheme.error)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onEdit) { Text("編集") }
                    TextButton(onClick = onVoid) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun PaymentEntryCard(entry: CashEntry, onEdit: () -> Unit, onVoid: () -> Unit) {
    val change = runCatching { entry.calculateAmounts().changeYen }.getOrNull()
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("明細 #${entry.sequence}")
            SummaryLine("記録日時", formatDateTime(entry.occurredAt))
            SummaryLine("商品金額", formatYen(entry.productAmountYen))
            SummaryLine("受取金額", formatYen(entry.receivedAmountYen))
            SummaryLine("おつり", formatYen(change))
            if (entry.isVoided) {
                Text("取消済み", color = MaterialTheme.colorScheme.error)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onEdit) { Text("編集") }
                    TextButton(onClick = onVoid) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun RegisterHistoryCard(register: RegisterSession, summary: io.github.mock108.ubaregi.domain.RegisterSummary?, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
            Text("#${register.sequence} ${register.status.displayLabel()}${if (selected) "（選択中）" else ""}")
            Text("開始日時 ${formatDateTime(register.openedAt)} / $STARTING_CHANGE_LABEL ${formatYen(register.openingFloatYen)}")
            Text("受け取った現金 ${formatYen(summary?.paymentCollectedYen)} / $EXPECTED_CASH_LABEL ${formatYen(summary?.expectedCashYen)}")
            Text("$DIFFERENCE_LABEL ${formatSignedYen(summary?.differenceYen)}${if (register.status == RegisterStatus.CLOSED && register.revision > (register.closeRevision ?: register.revision)) " / 終了後に修正済み" else ""}")
            Text("$COUNTED_CASH_LABEL ${formatYen(register.actualCashYen)} / $NEXT_CHANGE_LABEL ${formatYen(register.nextFloatYen)}")
        }
    }
}

@Composable
private fun MoneyField(label: String, value: String, onValueChange: (String) -> Unit, enabled: Boolean = true) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value)
    }
}

private fun formatSignedYen(value: Long?): String = value?.let {
    when {
        it > 0 -> "+${formatYen(it)}"
        it < 0 -> "-${formatYen(abs(it))}"
        else -> formatYen(0)
    }
} ?: "—"

@Composable
private fun ErrorText(message: String?) {
    if (!message.isNullOrBlank()) Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SuccessText(message: String?) {
    if (!message.isNullOrBlank()) Text(message, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun HomeActionButton(title: String, description: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AppContent(paddingValues: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
        content = content,
    )
}

@Composable
private fun UbaregiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = Color(0xFF1769AA),
            secondary = Color(0xFF4D6475),
        ),
        content = content,
    )
}
