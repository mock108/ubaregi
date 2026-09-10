# ウバレジ CI/CD設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 / 版 | 35 / 0.1 |
| 対象 | GitHub Actions、固定署名APK、Firebase App Distribution |
| 関連文書 | [企画書](00_企画書.md) / [基本設計書](10_基本設計書.md) / [開発環境構築手順](30_開発環境構築手順.md) / [公開・運用手順](40_公開・運用手順.md) |

本書は、コードのpushからCI、APK署名、Firebase App Distributionへの配布までの構成を定める。Android Studio、ローカルAndroid SDK、ローカルエミュレータはWorkflowの前提にしない。

## 1. 基本構成

```text
GitHub Repository
  ↓ pull request / push
GitHub Actions CI
  ├─ compile
  ├─ unit test
  └─ lint

mainへのpush / 手動実行
  ↓
Distribution Workflow
  ├─ test / lint
  ├─ APK build
  ├─ 固定署名
  └─ Firebase App Distribution upload
       ↓
    Android実機
```

Gradleプロジェクトのルートは`pgm/ubaregi`である。すべてのGradleコマンドはこのディレクトリを作業ディレクトリとする。

## 2. Workflowファイル

```text
.github/
└─ workflows/
   ├─ ci.yml
   └─ distribute.yml
```

### 2.1 `ci.yml`

トリガーは次のとおりとする。

- `pull_request`（`main`向け）
- 通常のbranchへの`push`
- 必要に応じた`workflow_dispatch`

最低限の処理は次の順番とする。

```text
checkout
↓
JDK 17 / Android SDK API 37を準備
↓
./gradlew testDebugUnitTest lintDebug assembleDebug
↓
必要に応じてCompose Screenshot Test
```

Workflowの実装時には、GitHub公式Action、Gradle公式Action、Android SDK準備方法の現行仕様を確認してActionのバージョンを固定する。初期の実行権限は`contents: read`だけとする。

実装時のコマンド契約は次のとおりとする。

```yaml
defaults:
  run:
    working-directory: pgm/ubaregi

steps:
  - checkout
  - JDK 17を準備
  - Android SDK API 37を準備
  - Gradleキャッシュを設定
  - run: ./gradlew testDebugUnitTest lintDebug assembleDebug
```

Actionの具体的なバージョンは、作成時点の公式ドキュメントで確認した値をWorkflowへ反映する。動的なActionタグや未検証のPreview機能を常用しない。

### 2.2 `distribute.yml`

トリガーは次のとおりとする。

- `push`（`main`のみ）
- `workflow_dispatch`

処理は次の順番とする。

```text
checkout
↓
JDK 17 / Android SDK API 37を準備
↓
Unit Test / Lint
↓
versionCode・versionNameを設定
↓
debug APKをビルド
↓
固定の個人配布用keystoreで署名
↓
Firebase App Distributionへupload
↓
Actionsのログと配布結果を確認
```

Firebase CLIを使う場合のコマンド契約は次のとおりとする。実装時にはFirebase CLIの現行オプションを公式ドキュメントで確認する。

```bash
firebase appdistribution:distribute \
  app/build/outputs/apk/debug/app-debug.apk \
  --app "$FIREBASE_APP_ID" \
  --testers "$FIREBASE_TESTER_EMAIL" \
  --release-notes "main / Actions run ${GITHUB_RUN_NUMBER} / ${GITHUB_SHA}"
```

APKの実際のファイル名はGradle設定に合わせる。アップロード先は個人用Firebase Android App（`io.github.mock108.ubaregi.debug`）とする。

Distribution Workflowは自動でGoogle Playへ公開しない。Firebase App Distributionへの配布成功をもって個人用配布の完了とする。

## 3. Firebase構成

原則として次の対応にする。

```text
mock108/ubaregi
  = 1 Firebase Project
  = 1 Firebase Android App
  = 1個人用App Distribution配布先
```

| 設定 | 実値の記入先 |
| --- | --- |
| Firebase Project ID | GitHub Actionsの`FIREBASE_PROJECT_ID` |
| Firebase Android App ID | GitHub Actionsの`FIREBASE_APP_ID` |
| テスター | GitHub Actionsの`FIREBASE_TESTER_EMAIL` |
| 対応applicationId | `io.github.mock108.ubaregi.debug` |

Firestore、AuthenticationなどのFirebase機能は、アプリ要件で本当に必要になった場合だけ追加する。初期版はApp DistributionのためにFirebaseを使うが、業務データの保存・同期には使わない。

## 4. Secrets・IAM・認証

### 4.1 初期導入方式

構築容易性を優先し、初回はFirebase App Distribution用Service Accountを作成し、JSON全体をGitHub Actions Secretへ保存してよい。

| Secret名 | 内容 |
| --- | --- |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase App Distribution用Service Account JSON全体 |
| `FIREBASE_PROJECT_ID` | Firebase Project ID |
| `FIREBASE_APP_ID` | 個人用Android App ID |
| `FIREBASE_TESTER_EMAIL` | 自分のテスター用Googleアカウント |
| `UBAREGI_KEYSTORE_BASE64` | 個人配布用keystoreのBase64値 |
| `UBAREGI_STORE_PASSWORD` | keystoreパスワード |
| `UBAREGI_KEY_ALIAS` | 署名鍵Alias |
| `UBAREGI_KEY_PASSWORD` | 署名鍵パスワード |

Service AccountにはFirebase App Distributionの配布に必要な最小権限だけを与える。JSONをファイル化する場合はRunnerの一時ディレクトリに置き、処理後に削除する。Secretの内容を`echo`、Gradleエラー、release note、Artifactへ出力しない。

### 4.2 長期運用方式

長期間有効なService Account JSON keyを残さないため、将来は次の方式へ移行する。

```text
GitHub Actions OIDC token
  ↓
Google Cloud Workload Identity Federation
  ↓
短時間だけ発行されたGoogle認証
  ↓
Firebase App Distribution
```

移行時は、GitHub Actionsに`id-token: write`を付与し、Google Cloud側でRepository `mock108/ubaregi`、必要なbranch、Workflowなどの条件を制限する。移行後にJSON keyを無効化・削除し、不要なSecretを残さない。具体的なProvider条件とIAMロールは実装時点のGoogle公式ドキュメントで確認する。

## 5. APK署名

Firebaseから端末へ継続的に更新するため、個人用配布APKには毎回同じ署名鍵を使う。

```text
personal-distribution.keystore
  ↓ GitHub Secret（Base64）
GitHub Actions Runnerの一時ファイル
  ↓
debug APKへ署名
  ↓
Firebase App Distribution
```

次を禁止する。

- keystoreやパスワードのRepositoryへのcommit
- Runnerごとに生成される一時debug鍵での配布
- 固定署名APKを別の鍵で上書きすること
- 署名鍵をActions Artifactとして保存すること

Codespacesで`assembleDebug`が成功することと、Firebaseへ配布するAPKが固定鍵で署名されていることは別の確認とする。端末へ配布・更新するAPKは、必ずDistribution Workflowで作成したものを使う。

## 6. バージョニング

個人用の配布ビルドはGitHub ActionsのWorkflow run numberで識別する。

| ビルド | versionCode | versionName |
| --- | --- | --- |
| 初期・Codespaces確認 | `1` | `0.1.0`または`0.1.0-dev.local` |
| Distribution Workflow | `GITHUB_RUN_NUMBER` | `0.1.0-dev.<GITHUB_RUN_NUMBER>` |

Distribution Workflowでは、前回配布より小さい`versionCode`を生成しない。実装ではGradle propertyまたは環境変数から値を受け取り、未指定時だけ初期値を使う。例：

```text
0.1.0-dev.12
0.1.0-dev.13
0.1.0-dev.14
```

将来Google Playへ移行する際は、Play公開用のversionCode系列、release versionName、AAB、Play App Signingを別途確定する。

## 7. 配布結果の確認

Distribution Workflow成功後、次を確認する。

1. GitHub Actionsがテスト・Lint・ビルド・署名・uploadまで成功している。
2. Firebase App Distributionに新しいリリースが表示されている。
3. リリース番号とAPKのversionNameが一致している。
4. 登録した自分のGoogleアカウントでリリースを取得できる。
5. 既存の個人用アプリをアンインストールせずに上書き更新できる。
6. 更新後も端末内の業務DBが残っている。

インストールできない場合は、最初にapplicationId、署名鍵、versionCode、Firebase App IDを確認する。データを消すためのアンインストールやアプリデータ初期化を最初の対処にしない。

## 8. 失敗時の扱い

| 事象 | 初期確認 |
| --- | --- |
| CI失敗 | Gradleのテスト・Lintログを確認し、Codespacesで同じコマンドを再実行 |
| APKビルド失敗 | JDK、SDK API 37、Gradle Wrapper、依存バージョンを確認 |
| 署名失敗 | keystore Secret、Alias、パスワード、Runner一時ファイルを確認。Secretをログへ出さない |
| Firebase upload失敗 | Project ID、App ID、Service Account権限、テスター設定を確認 |
| 実機更新失敗 | applicationId、固定署名鍵、versionCodeを確認。旧アプリを保持する |
| 動作不具合 | アプリ版、操作手順、Actions runを控え、実データを公開せずIssueへ報告 |

Crashlyticsは個人運用を開始してから必要性を判断する。導入する場合は、データ取扱い、Manifest、Privacy Policy、Google Play申告内容を同時に更新する。
