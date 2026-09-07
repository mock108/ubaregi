# ウバレジ OSS表示

## 自作部分

Copyright (c) 2026 Mockup

自作ソースコードと設計書は、リポジトリ同梱のMIT Licenseに従います。

## 使用しているライブラリ

本アプリのruntime依存と、実際に解決された主要な推移依存を記載します。AndroidX、Jetpack Compose、Kotlin、Kotlinx、SQLiteのライブラリはApache License 2.0に従います。

- org.jetbrains.kotlin:kotlin-stdlib:2.3.21 — Apache License 2.0
- androidx.activity:activity-compose:1.13.0 — Apache License 2.0
- androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0 — Apache License 2.0
- androidx.compose:compose-bom:2026.08.00 — Apache License 2.0（バージョン管理用）
- androidx.compose.material3:material3:1.4.0 — Apache License 2.0
- androidx.compose.ui:ui-tooling:1.12.0 — Apache License 2.0（debug）
- androidx.compose.ui:ui-tooling-preview:1.12.0 — Apache License 2.0
- androidx.compose.runtime:runtime:1.12.0 — Apache License 2.0
- androidx.core:core-ktx:1.18.0 — Apache License 2.0
- androidx.annotation:annotation:1.9.1 — Apache License 2.0
- androidx.room:room-runtime:2.8.4 — Apache License 2.0
- androidx.room:room-ktx:2.8.4 — Apache License 2.0
- androidx.room:room-common:2.8.4 — Apache License 2.0
- androidx.sqlite:sqlite:2.6.2 — Apache License 2.0
- androidx.lifecycle関連（2.10.0） — Apache License 2.0
- kotlinx-coroutines（Android / Core）:1.9.0 — Apache License 2.0
- kotlinx-serialization-core:1.7.3 — Apache License 2.0
- org.jetbrains:annotations:23.0.0 — Apache License 2.0
- org.jspecify:jspecify:1.0.0 — Apache License 2.0
- com.google.guava:listenablefuture:1.0 — Apache License 2.0

テスト専用：JUnit:junit:4.13.2 — Common Public License 1.0（APKには含めません）。本表示は依存関係を固定した個人用debug版の構成に対応しています。

---

## MIT License（自作部分）

Copyright (c) 2026 Mockup

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

---

## Apache License 2.0（依存ライブラリ）

Copyright 2010 The Apache Software Foundation

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
