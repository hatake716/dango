# CLAUDE.md — dango 作業ガイド

Claude Code でこのリポジトリを扱うときの共通ルール。セッションをまたいでも設計がぶれないよう、必ず最初に読むこと。

## プロジェクト概要

**dango** は macOS Finder の見た目・操作感・アニメーションを再現した Android ファイラー。
仕様の正は [docs/SPEC.md](docs/SPEC.md)（v0.2 確定版）。進捗は [docs/PROGRESS.md](docs/PROGRESS.md) に記録する。

- パッケージ: `io.github.hatake716.dango`
- 構成: Kotlin + Jetpack Compose + Material 3、MVVM + Repository、単一 Activity
- minSdk 30 / targetSdk 36
- 配布: GitHub Releases の APK（Play ストア審査は前提にしない）

## 作業ルール

1. **仕様が正**。docs/SPEC.md に書かれていない機能は追加しない。新たな論点・判断が必要になったら SPEC.md §15「決定事項」に論点を追記し、ユーザーに確認してから実装する。
2. **UI層から直接 `java.io.File` を触らない**。ファイルアクセスは必ず `FileSystemProvider`（`data/fs/`）経由。UI はスキーム（file / saf / smb / ...）を意識しない。
3. レイヤ構成は SPEC.md §8.1 に従う（`ui/` → `domain/` → `data/`。逆方向依存の禁止）。
4. Finder らしさを損なう UI 変更をしない。配色は SPEC.md §9 のトークン、アニメーションは §5 のパラメータに従う。Material You 動的カラーは既定オフ。
5. マイルストーン（SPEC.md §14）単位で進める。完了条件を満たしたら docs/PROGRESS.md に日付・内容・確認結果を追記する。
6. 文字列はハードコードせずリソース化（日本語が主、英語は追って）。
7. コミットは Conventional Commits（`feat:` `fix:` `docs:` `refactor:` など）＋日本語の要約。

## ビルドと検証

```bash
./gradlew assembleDebug        # デバッグAPK: app/build/outputs/apk/debug/app-debug.apk
./gradlew lint test            # 静的検査と単体テスト
```

- この開発機では `local.properties` の `sdk.dir` にローカル SDK を指定する（`local.properties` はコミットしない）
- 実機（Pixel）へは `adb install -r` で配置。**実機への入力注入・設定変更はしない**（ユーザーが日常使用中の端末のため）。動作確認はユーザーに依頼し、ログは `adb logcat` で読む
- 診断ログは `android.util.Log.d("dango", ...)` に統一し、`adb logcat -d -s dango` で確認する

## 現在の状態

docs/PROGRESS.md を参照。
