package io.github.mock108.ubaregi

import android.os.Bundle
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.lightColorScheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UbaregiTheme {
                UbaregiApp()
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
fun UbaregiApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.HOME) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(currentDestination.label) })
        },
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination == destination,
                        onClick = { currentDestination = destination },
                        icon = { Text(destination.shortLabel) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (currentDestination) {
            AppDestination.HOME -> HomeScreen(
                paddingValues = innerPadding,
                onNavigate = { currentDestination = it },
            )

            AppDestination.REGISTER -> RegisterScreen(innerPadding)
            AppDestination.CALCULATOR -> CalculatorScreen(innerPadding)
            AppDestination.ABOUT -> AboutScreen(innerPadding)
        }
    }
}

@Composable
private fun HomeScreen(
    paddingValues: PaddingValues,
    onNavigate: (AppDestination) -> Unit,
) {
    AppContent(paddingValues) {
        Text("ウバレジ", style = MaterialTheme.typography.headlineMedium)
        Text("現金とおつりを端末内で記録する補助アプリ")
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("レジ未開始", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("まず初期釣銭を登録してレジを開始してください。")
            }
        }

        Spacer(Modifier.height(16.dp))
        HomeActionButton("おつりを計算", "商品金額と受取金額から計算") {
            onNavigate(AppDestination.CALCULATOR)
        }
        HomeActionButton("レジを開始・締める", "初期釣銭、補充、取出し、締め結果") {
            onNavigate(AppDestination.REGISTER)
        }
        HomeActionButton("アプリ情報", "データの扱いと問い合わせ先") {
            onNavigate(AppDestination.ABOUT)
        }
    }
}

@Composable
private fun HomeActionButton(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RegisterScreen(paddingValues: PaddingValues) {
    AppContent(paddingValues) {
        Text("レジ締め / 初期釣銭", style = MaterialTheme.typography.headlineSmall)
        Text("この画面は次工程でRoom保存と接続します。")
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("初期釣銭")
                Spacer(Modifier.height(8.dp))
                Text("未開始")
                Spacer(Modifier.height(8.dp))
                Text("開始・補充・取出し・締めの業務処理を実装予定です。")
            }
        }
    }
}

@Composable
private fun CalculatorScreen(paddingValues: PaddingValues) {
    var productAmount by rememberSaveable { mutableStateOf("") }
    var receivedAmount by rememberSaveable { mutableStateOf("") }
    val result = remember(productAmount, receivedAmount) {
        ChangeCalculator.calculate(productAmount, receivedAmount)
    }

    AppContent(paddingValues) {
        Text("おつり計算", style = MaterialTheme.typography.headlineSmall)
        Text("計算だけならレジ未開始でも利用できます。")
        Spacer(Modifier.height(16.dp))

        MoneyField(
            label = "商品金額",
            value = productAmount,
            onValueChange = { productAmount = it },
        )
        Spacer(Modifier.height(12.dp))
        MoneyField(
            label = "お客様支払金額",
            value = receivedAmount,
            onValueChange = { receivedAmount = it },
        )
        Spacer(Modifier.height(20.dp))

        when (result) {
            ChangeResult.Empty -> Text("2つの金額を入力してください")
            is ChangeResult.Invalid -> Text(result.message, color = MaterialTheme.colorScheme.error)
            is ChangeResult.Shortage -> Text(
                "あと${result.amount}円不足",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleLarge,
            )

            is ChangeResult.Success -> Text(
                "おつり ${result.change}円",
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = {
                productAmount = ""
                receivedAmount = ""
            }) {
                Text("入力をクリア")
            }
        }
        Text(
            "受渡し履歴への保存は、Room導入後に有効化します。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MoneyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun AboutScreen(paddingValues: PaddingValues) {
    AppContent(paddingValues) {
        Text("アプリ情報", style = MaterialTheme.typography.headlineSmall)
        Text("ウバレジ（個人用）")
        Text("version 0.1.0-debug")
        Spacer(Modifier.height(16.dp))
        Text("Uber Eatsの配達で扱う現金とおつりを記録する非公式の補助アプリです。")
        Spacer(Modifier.height(12.dp))
        Text("計算・履歴・レジ締めはオフラインで利用し、記録は端末内に保存します。広告配信、利用状況の解析、記録の自動送信は行いません。")
        Spacer(Modifier.height(12.dp))
        Text("お問い合わせは、利用者が操作したときだけ外部ブラウザーでGitHub Issuesを開きます。")
    }
}

@Composable
private fun AppContent(
    paddingValues: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
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
        colorScheme = lightColorScheme(
            primary = Color(0xFF1769AA),
            secondary = Color(0xFF4D6475),
        ),
        content = content,
    )
}
