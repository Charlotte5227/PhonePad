# PhonePad

スマホ（Android）を Windows PC のキーボード・マウス・Xbox 360 コントローラーとして使うアプリです。

**ダウンロード・使い方・変更履歴: https://charlotte5227.github.io/PhonePad/**

## 中身

| フォルダ | 内容 |
|---|---|
| `android/` | スマホアプリ（Kotlin） |
| `pc/` | PC側ソフト（Go、Windows 用） |
| `pc/bundle/` | 配布 zip に同梱するファイル（ViGEm ドライバー、説明書） |
| `docs/` | 配布サイト（GitHub Pages） |
| `.github/workflows/release.yml` | タグを付けると自動でビルドして公開する仕組み |

## 新しいバージョンを公開する手順

1. `CHANGELOG.md` の一番上に新しいバージョンの変更内容を書く（例: `## v1.5.0 (2026-11-01)`）
2. 変更をコミットして `main` に push
3. バージョンのタグを付けて push する

   ```
   git tag v1.5.0
   git push origin v1.5.0
   ```

4. GitHub の「Actions」で Release が緑になれば完了。APK・exe・zip がリリースに並び、
   配布サイトとアプリ／PCソフトの更新通知に反映されます。

バージョン番号は `メジャー.マイナー.パッチ` の3つの数字にしてください（アプリの versionCode はここから自動で計算されます）。

## 必要な Secrets（Settings → Secrets and variables → Actions）

| 名前 | 内容 |
|---|---|
| `PHONEPAD_KEYSTORE_BASE64` | アプリ署名の鍵（PhonePad-release.jks）を base64 にしたもの |
| `PHONEPAD_KEYSTORE_PASSWORD` | その鍵のパスワード |

署名の鍵をなくすと、以降の更新をスマホに上書きインストールできなくなります。手元に必ずバックアップを残し、リポジトリには絶対に入れないでください。

## 手元でビルドする場合

- PC側: `cd pc && GOOS=windows GOARCH=amd64 go build -ldflags "-H windowsgui" -o PhonePad.exe .`
- アプリ: `android/build_apk.bat`（Windows）。`android/` に `PhonePad-release.jks` と `PhonePad-release.password` があればリリース用の鍵で署名されます。
