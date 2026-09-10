# ウバレジ

Uber Eatsの配達で扱う現金とおつりを記録する、Android向けの非公式補助アプリです。開発者は **Mockup** です。

## 現在の状態

設計・開発準備を終え、`pgm/ubaregi`にCodespacesでビルドできるAndroidプロジェクトの最小骨格を追加しています。現在は4画面の導線と、おつり計算・入力検証を実装済みです。レジ履歴のRoom保存、CI/CD、Firebase App Distribution配布は次の実装段階です。

**Firebase準備 → GitHub Codespaces構築 → アプリ開発 → GitHub ActionsでCI/CD → Firebase App Distributionで個人運用 → 問題がなければGoogle Play公開**の順で進めます。公開日を先に決めず、当面は固定署名した個人用APKをFirebase App Distributionから取得して確認します。Google Playの開発者登録、公開用署名鍵の作成、購入商品の登録は後日行います。

## 主な機能

- 初期釣銭の記録とレジ開始。
- 商品金額・受取金額からのおつり計算と受渡し履歴。
- 釣銭補充・現金取出しの記録、レジ締めと過不足確認。
- 帳簿加工用の独自CSV / JSON出力。

計算・記録・履歴・締め・ローカルへの出力はオフラインで行います。ログイン、広告、解析、クラウド同期は設けません。個人用では課金機能を無効にし、問い合わせ時だけ外部ブラウザーでGitHubを開きます。

将来の公開版も全機能無料とし、任意の開発支援購入を用意する予定です。現金回収額は配達報酬・利益を意味しません。Uberとの連携や会計ソフトへの直接記帳は行いません。

## 決定した設定

| 項目 | 値 |
| --- | --- |
| 開発者 | Mockup |
| 対象 | Android 17 / API 37、日本語、日本円 |
| 公開版のアプリID | `io.github.mock108.ubaregi` |
| 個人用のアプリID | `io.github.mock108.ubaregi.debug` |
| 初期アプリ版 | `0.1.0` / `versionCode = 1` |
| 構成 | Kotlin、Compose、Room、単一アプリモジュール、4画面 |
| 開発・配布 | GitHub Codespaces、GitHub Actions、Firebase App Distribution |
| ライセンス | MIT |

アプリID等は実装に使う確定値であり、Google Playへの登録済みを意味しません。個人用と公開版は別アプリとして併存させ、データの自動移行は行いません。初期版のCSV / JSONにはアプリへの再取込機能がないため、個人運用記録は出力ファイルと個人用アプリで保持します。

## 文書

| 文書 | 内容 |
| --- | --- |
| [00_企画書](doc/00_企画書.md) | 目的、範囲、決定事項と後日作業 |
| [10_基本設計書](doc/10_基本設計書.md) | 構成、業務モデル、データ・通信方針 |
| [20_詳細設計書](doc/20_詳細設計書.md) | 4画面、計算、DB、出力、受入確認 |
| [30_開発環境構築手順](doc/30_開発環境構築手順.md) | Firebase準備、Codespaces、ビルド・実機確認の手順 |
| [35_CI/CD設計書](doc/35_CI-CD設計書.md) | GitHub Actions、署名、Secrets、Firebase App Distribution |
| [40_公開・運用手順](doc/40_公開・運用手順.md) | 個人運用、試験記録、将来の公開設定 |
| [50_プライバシーポリシー](doc/50_プライバシーポリシー.md) | 個人運用版のデータ取扱い文面 |

## 開発・問い合わせ

変更は公開ブランチで管理します。小さな文書修正は`main`、アプリ実装は`feature/<内容>`等の作業ブランチからPull Requestで取り込みます。CIはPRまたはpushで実行し、`main`へのpush時だけ固定署名APKをFirebase App Distributionへ配布します。Google Playへの自動公開は設けません。

[GitHub Issues](https://github.com/mock108/ubaregi/issues)で不具合や改善案を受け付けます。公開Issueには顧客情報や実際の配達記録を載せず、架空の金額で再現方法を書いてください。

公開用の設定見本は[release.properties.example](release.properties.example)です。実際の鍵・パスワード・Firebaseの認証情報・個人の記録はGitに登録しません。`.gitignore`は取り違えを減らす補助であり、コミット前には差分も確認します。

## Codespacesでの基本確認

ローカルPCにAndroid StudioやAndroid SDKを入れず、Codespacesで次を実行します。

```bash
cd pgm/ubaregi
./gradlew testDebugUnitTest lintDebug assembleDebug
```

CI/CDとFirebaseの初回設定は[CI/CD設計書](doc/35_CI-CD設計書.md)に従います。

## ライセンス

自作のソースコードと設計書は[MIT License](LICENSE)です。第三者のライブラリ・素材には各々のライセンスが適用されます。本アプリはUberまたはUber Eatsの公式アプリではありません。
