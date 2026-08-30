# dango 🍡

macOS Finder の見た目・操作感・アニメーションを再現した Android ファイラー。

- サイドバー／アイコン・リスト・カラム・ギャラリーの4表示モード
- Quick Look 風プレビュー（画像・動画・PDF・テキスト・音声）
- 圧縮・解凍（zip / tar系 / 7z / rar読み取り）
- ネットワークドライブ（SMB2/3・SFTP・WebDAV・FTP）
- ライト／ダークテーマ（Finder 配色トークン）

詳細は [docs/SPEC.md](docs/SPEC.md)（仕様書 v0.2）を参照。進捗は [docs/PROGRESS.md](docs/PROGRESS.md)。

## 動作要件

- Android 11（API 30）以上。主要検証端末は Pixel 10a（Android 16）
- 配布は当面 GitHub Releases の APK

## ビルド

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## 開発

Claude Code で作業する場合は [CLAUDE.md](CLAUDE.md) を先に読むこと。
