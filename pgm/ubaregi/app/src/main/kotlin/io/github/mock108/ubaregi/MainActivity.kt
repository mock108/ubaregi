package io.github.mock108.ubaregi

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    val shortLabel: String,
) {
    HOME("ホーム", "家"),
    REGISTER("レジ", "レ"),
    CALCULATOR("計算", "計"),
    ABOUT("情報", "情"),
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
                        icon = { Text(item.shortLabel) },
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
            )

            AppDestination.REGISTER -> RegisterScreen(
                paddingValues = innerPadding,
                viewModel = registerViewModel,
                onNavigateToCalculator = { sessionId ->
                    calculatorTargetId = sessionId
                    destinationName = AppDestination.CALCULATOR.name
                },
            )

            AppDestination.CALCULATOR -> CalculatorScreen(
                paddingValues = innerPadding,
                viewModel = calculatorViewModel,
                initialTargetId = calculatorTargetId,
                onNavigateToRegister = { destinationName = AppDestination.REGISTER.name },
            )

            AppDestination.ABOUT -> AboutScreen(
                paddingValues = innerPadding,
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
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AppContent(paddingValues) {
        Text("ウバレジ", style = MaterialTheme.typography.headlineMedium)
        Text("現金とおつりを端末内で記録する補助アプリ")
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (state.openRegister == null) {
                    Text("レジ未開始", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("前回の締め結果を現在残高としては表示していません。")
                } else {
                    val register = state.openRegister
                    Text("レジ稼働中", style = MaterialTheme.typography.titleLarge)
                    Text("レジ #${register?.sequence} / ${formatDateTime(register?.openedAt)}")
                    Spacer(Modifier.height(8.dp))
                    SummaryLine("現金回収額", formatYen(state.summary?.paymentCollectedYen))
                    SummaryLine("予定残高", formatYen(state.summary?.expectedCashYen))
                    SummaryLine("現在の状態", "OPEN")
                }
            }
        }

        ErrorText(state.errorMessage)
        Spacer(Modifier.height(16.dp))
        HomeActionButton("おつりを計算", "商品金額と受取金額から計算") { onNavigate(AppDestination.CALCULATOR) }
        HomeActionButton("レジを開始・締める", "初期釣銭、補充、取出し、締め結果") { onNavigate(AppDestination.REGISTER) }
        HomeActionButton("アプリ情報", "データの扱いと問い合わせ先") { onNavigate(AppDestination.ABOUT) }
    }
}

@Composable
private fun RegisterScreen(
    paddingValues: PaddingValues,
    viewModel: RegisterViewModel,
    onNavigateToCalculator: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selected = state.selectedRegister
    val summary = state.selectedSummary

    AppContent(paddingValues) {
        Text("レジ締め / 初期釣銭", style = MaterialTheme.typography.headlineSmall)
        Text("候補は表示するだけでは保存されません。開始ボタンで確定します。")
        ErrorText(state.errorMessage)
        SuccessText(state.message)

        if (state.openRegister == null) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("初期釣銭を入力してレジを開始", style = MaterialTheme.typography.titleMedium)
                    state.latestClosed?.nextFloatYen?.let { candidate ->
                        Text("最新の締め済みレジの候補: ${formatYen(candidate)}")
                        TextButton(onClick = { viewModel.onOpeningFloatChanged(candidate.toString()) }) { Text("候補を入力") }
                    } ?: Text("初回のため候補はありません。0円でも開始できます。")
                    MoneyField("初期釣銭", state.openingFloatText, viewModel::onOpeningFloatChanged)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = viewModel::startRegister, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.isSaving) "保存中…" else "この金額でレジ開始")
                    }
                }
            }
        } else {
            val open = state.openRegister
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("稼働中レジ #${open?.sequence}", style = MaterialTheme.typography.titleLarge)
                    Text("開始 ${formatDateTime(open?.openedAt)}")
                    SummaryLine("初期釣銭", formatYen(open?.openingFloatYen))
                    SummaryLine("現金回収額", formatYen(state.openSummary?.paymentCollectedYen))
                    SummaryLine("入金合計", formatYen(state.openSummary?.cashInYen))
                    SummaryLine("出金合計", formatYen(state.openSummary?.cashOutYen))
                    SummaryLine("予定残高", formatYen(state.openSummary?.expectedCashYen))
                    SummaryLine("明細件数", state.openEntries.size.toString())
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.showAdjustmentDialog(CashEntryKind.CASH_IN) }, enabled = !state.isSaving) { Text("釣銭補充") }
                        OutlinedButton(onClick = { viewModel.showAdjustmentDialog(CashEntryKind.CASH_OUT) }, enabled = !state.isSaving) { Text("現金取出し") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::showRegisterEditDialog, enabled = !state.isSaving) { Text("初期釣銭を編集") }
                        Button(onClick = viewModel::showCloseDialog, enabled = !state.isSaving) { Text("レジ締め") }
                    }
                }
            }
        }

        if (selected != null) {
            Spacer(Modifier.height(16.dp))
            Text("選択中のレジ #${selected.sequence}", style = MaterialTheme.typography.titleMedium)
            if (selected.status == RegisterStatus.CLOSED && selected.revision > (selected.closeRevision ?: selected.revision)) {
                Text("締め後に修正済み", color = MaterialTheme.colorScheme.primary)
            }
            SummaryLine("状態", selected.status.name)
            SummaryLine("初期釣銭", formatYen(selected.openingFloatYen))
            SummaryLine("予定残高", formatYen(summary?.expectedCashYen))
            if (selected.status == RegisterStatus.CLOSED) {
                SummaryLine("実残高", formatYen(selected.actualCashYen))
                SummaryLine("過不足", formatSignedYen(summary?.differenceYen))
                SummaryLine("次回釣銭", formatYen(selected.nextFloatYen))
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
            title = { Text(if (dialog.kind == CashEntryKind.CASH_IN) "釣銭補充" else "現金取出し") },
            text = {
                Column {
                    MoneyField("金額", dialog.amountText, viewModel::onAdjustmentAmountChanged)
                    Spacer(Modifier.height(8.dp))
                    Text("実際に現金を移動した後に保存してください。")
                }
            },
            confirmButton = { Button(onClick = viewModel::saveAdjustment, enabled = !state.isSaving) { Text("保存") } },
            dismissButton = { TextButton(onClick = viewModel::dismissAdjustmentDialog) { Text("キャンセル") } },
        )
    }

    state.closeDialog?.let { dialog ->
        val actual = parseMoneyInput(dialog.actualCashText, "実残高", true)
        val next = parseMoneyInput(dialog.nextFloatText, "次回釣銭", true)
        val openSummary = state.openSummary
        val openRegister = state.openRegister
        AlertDialog(
            onDismissRequest = viewModel::dismissCloseDialog,
            title = { Text("レジ締め") },
            text = {
                Column {
                    MoneyField("実残高R", dialog.actualCashText, viewModel::onCloseActualChanged)
                    MoneyField("次回釣銭N", dialog.nextFloatText, viewModel::onCloseNextFloatChanged)
                    Spacer(Modifier.height(8.dp))
                    SummaryLine("予定残高E", formatYen(openSummary?.expectedCashYen))
                    if (actual != null && openSummary != null) SummaryLine("過不足D", formatSignedYen(actual - openSummary.expectedCashYen))
                    if (actual != null && openRegister != null) SummaryLine("手元現金の増減G", formatSignedYen(actual - openRegister.openingFloatYen))
                    if (actual != null && next != null) SummaryLine("締め時の取出額W", formatYen(actual - next))
                }
            },
            confirmButton = { Button(onClick = viewModel::requestCloseConfirmation, enabled = !state.isSaving) { Text("締め内容を確認") } },
            dismissButton = { TextButton(onClick = viewModel::dismissCloseDialog) { Text("キャンセル") } },
        )
    }

    if (state.isCloseConfirmationVisible) {
        val dialog = state.closeDialog
        val actual = dialog?.let { parseMoneyInput(it.actualCashText, "実残高", true) }
        val next = dialog?.let { parseMoneyInput(it.nextFloatText, "次回釣銭", true) }
        val openSummary = state.openSummary
        val difference = if (actual != null && openSummary != null) actual - openSummary.expectedCashYen else null
        AlertDialog(
            onDismissRequest = viewModel::dismissCloseConfirmation,
            title = { Text("この内容でレジを締めますか？") },
            text = {
                Column {
                    Text("過不足: ${formatSignedYen(difference)}")
                    if (difference != null && difference != 0L) Text(if (difference < 0) "不足${formatYen(abs(difference))}のまま締めます。" else "超過${formatYen(difference)}のまま締めます。")
                    if (actual != null && next != null) Text("取出額: ${formatYen(actual - next)}")
                    Text("確認中に別の更新があった場合は保存せず、最新状態を読み直します。")
                }
            },
            confirmButton = { Button(onClick = viewModel::closeRegister, enabled = !state.isSaving) { Text("締める") } },
            dismissButton = { TextButton(onClick = viewModel::dismissCloseConfirmation) { Text("戻る") } },
        )
    }

    state.registerEditDialog?.let { dialog ->
        val isClosed = selected?.status == RegisterStatus.CLOSED
        val opening = parseMoneyInput(dialog.openingFloatText, "初期釣銭", true)
        val editedExpected = if (opening != null && selected != null && summary != null) {
            summary.expectedCashYen + opening - selected.openingFloatYen
        } else {
            null
        }
        val editedActual = if (isClosed) parseMoneyInput(dialog.actualCashText, "実残高", true) else null
        AlertDialog(
            onDismissRequest = viewModel::dismissRegisterEditDialog,
            title = { Text("レジ情報を編集") },
            text = {
                Column {
                    MoneyField("初期釣銭", dialog.openingFloatText, viewModel::onRegisterEditOpeningChanged)
                    if (isClosed) {
                        MoneyField("実残高", dialog.actualCashText, viewModel::onRegisterEditActualChanged)
                        MoneyField("次回釣銭", dialog.nextFloatText, viewModel::onRegisterEditNextChanged)
                    }
                    if (editedExpected != null) {
                        SummaryLine("編集後予定残高", formatYen(editedExpected))
                        if (editedActual != null) SummaryLine("編集後過不足", formatSignedYen(editedActual - editedExpected))
                    }
                    Text("編集前後の集計は保存後に再計算されます。別レジの初期釣銭は変更しません。")
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
                    Text("稼働中レジ: ${if (dialog.hasOpenRegister) "あり" else "なし"}")
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
    onNavigateToRegister: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var registerMenuExpanded by remember { mutableStateOf(false) }
    val selected = state.selectedRegister
    val result = remember(state.productText, state.receivedText) { ChangeCalculator.calculate(state.productText, state.receivedText) }
    LaunchedEffect(initialTargetId, state.registers) { viewModel.setInitialTarget(initialTargetId) }

    AppContent(paddingValues) {
        Text("おつり計算", style = MaterialTheme.typography.headlineSmall)
        Text("計算だけならレジ未開始でも利用できます。")
        ErrorText(state.errorMessage)
        SuccessText(state.message)
        Spacer(Modifier.height(12.dp))

        Text("対象レジ")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { registerMenuExpanded = true }, enabled = state.registers.isNotEmpty()) {
                Text(selected?.let { "#${it.sequence} ${it.status.name}" } ?: "レジ未開始")
            }
            DropdownMenu(expanded = registerMenuExpanded, onDismissRequest = { registerMenuExpanded = false }) {
                state.registers.forEach { register ->
                    DropdownMenuItem(
                        text = { Text("#${register.sequence} ${register.status.name}") },
                        onClick = { viewModel.selectRegister(register.id); registerMenuExpanded = false },
                    )
                }
            }
            if (selected?.status == RegisterStatus.CLOSED) Text("締め済みレジのため新規保存不可", color = MaterialTheme.colorScheme.error)
        }
        if (selected == null || selected.status == RegisterStatus.OPEN) {
            MoneyField("商品金額", state.productText, viewModel::onProductChanged)
            Spacer(Modifier.height(12.dp))
            MoneyField("お客様支払金額", state.receivedText, viewModel::onReceivedChanged)
        } else {
            MoneyField("商品金額", state.productText, viewModel::onProductChanged, enabled = false)
            MoneyField("お客様支払金額", state.receivedText, viewModel::onReceivedChanged, enabled = false)
            Text("新規入力・保存は無効です。既存履歴の編集・取消だけ行えます。")
        }
        Spacer(Modifier.height(16.dp))

        when (result) {
            ChangeResult.Empty -> Text("2つの金額を入力してください")
            is ChangeResult.Invalid -> ErrorText(result.message)
            is ChangeResult.Shortage -> ErrorText("あと${formatYen(result.amount)}不足")
            is ChangeResult.Success -> Text("おつり ${formatYen(result.change)}", style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::savePayment,
                enabled = (selected?.status == RegisterStatus.OPEN || state.pendingEntryId != null) && result is ChangeResult.Success && !state.isSaving,
            ) { Text(if (state.isSaving) "保存中…" else if (selected?.status == RegisterStatus.CLOSED) "保存を再試行" else "受渡しを記録") }
            TextButton(onClick = viewModel::clearInput, enabled = selected?.status != RegisterStatus.CLOSED) { Text("入力をクリア") }
        }
        if (selected == null) {
            TextButton(onClick = onNavigateToRegister) { Text("レジを開始する") }
        }

        Spacer(Modifier.height(20.dp))
        Text("受渡し履歴", style = MaterialTheme.typography.titleMedium)
        if (state.paymentEntries.isEmpty()) Text("このレジのPAYMENT明細はありません。")
        state.paymentEntries.forEach { entry ->
            PaymentEntryCard(
                entry = entry,
                onEdit = { viewModel.showPaymentEditDialog(entry) },
                onVoid = { viewModel.requestVoidPayment(entry) },
            )
        }
    }

    state.editEntryId?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissPaymentEditDialog,
            title = { Text("受渡し明細を編集") },
            text = {
                Column {
                    MoneyField("商品金額", state.editProductText, viewModel::onEditProductChanged)
                    MoneyField("受取金額", state.editReceivedText, viewModel::onEditReceivedChanged)
                }
            },
            confirmButton = { Button(onClick = viewModel::savePaymentEdit, enabled = !state.isSaving) { Text("保存") } },
            dismissButton = { TextButton(onClick = viewModel::dismissPaymentEditDialog) { Text("キャンセル") } },
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
}

@Composable
private fun AboutScreen(
    paddingValues: PaddingValues,
    onContact: () -> Unit,
) {
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
private fun AdjustmentEntryCard(entry: CashEntry, onEdit: () -> Unit, onVoid: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("#${entry.sequence} ${if (entry.kind == CashEntryKind.CASH_IN) "CASH_IN 補充" else "CASH_OUT 取出し"}")
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
            Text("#${register.sequence} ${register.status.name}${if (selected) "（選択中）" else ""}")
            Text("開始 ${formatDateTime(register.openedAt)} / 初期釣銭 ${formatYen(register.openingFloatYen)}")
            Text("現金回収 ${formatYen(summary?.paymentCollectedYen)} / 予定残高 ${formatYen(summary?.expectedCashYen)}")
            Text("過不足 ${formatSignedYen(summary?.differenceYen)}${if (register.status == RegisterStatus.CLOSED && register.revision > (register.closeRevision ?: register.revision)) " / 締め後に修正済み" else ""}")
            Text("実残高 ${formatYen(register.actualCashYen)} / 次回 ${formatYen(register.nextFloatYen)}")
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
