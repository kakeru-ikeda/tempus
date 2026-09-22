# CLAUDE.md

Tempus の **SP2000T 向けフォーク**。upstream (`eddyizm/tempus`) からクローンし、
Astell&Kern A&ultima SP2000T (AK ROM / Android 9) に合わせて改変していく。

## まずこれを読む

| 知りたいこと | 参照先 |
|---|---|
| ビルド、アーキテクチャ、フレーバー、CI | `AGENTS.md` |
| **SP2000T へのインストール全般** | `docs/sp2000t-install.md` |

**SP2000T 関連の背景・手順は `docs/sp2000t-install.md` を参照すること。**
このファイルに書き写さない。あの文書は実機で確認した内容だけを載せ、推測は
「未確認」と明記する方針なので、憶測を足さないこと。

`docs/sp2000t-install.md` が扱う範囲:

- 対象機のスペックと `degoogled` フレーバーを使う理由（GMS 無し）
- ADB の有効化手順（`com.iriver.tester.factorytool`）
- **package 名ホワイトリスト** — AK ROM が 40 件の許可リストを強制しており、
  対象外の名前は署名や梱包を変えても必ずインストールに失敗する
- 署名（既存インストールへ `-r` で上書きするための鍵）
- ビルド環境（`local.properties` / SDK パッケージ名）
- doze ホワイトリスト、アンインストールで失われるもの
- 音まわりの既知事項（`SoftFlacDecoder`、`audioserver` の SIGSEGV）

## upstream からの意図的な差分

新しく差分を入れたら、ここに 1 行足す。

- `app/build.gradle` — `degoogled` フレーバーの `applicationId` を
  `com.foobar2000.foobar2000` に変更。ホワイトリストの空き枠を借用するため。
  `namespace` は `com.eddyizm.tempus` のままなので manifest は完全修飾で壊れない
- `docs/sp2000t-install.md` — フォーク固有の文書。upstream には無い
- ナビゲーション — ボトムナビ／ドロワーの遷移で画面が積み上がらないよう
  `NavigationUI.setupWithNavController()` を自前リスナーに置換し、
  トップレベルでのバックキーを `moveTaskToBack` にした
  (`navigation/NavigationHelper.java`, `ui/activity/MainActivity.java`)
- ディレクトリ保存 — 設定 `download_directory_preserve_path`（既定 off）で
  `Child.path` のフォルダー構成のままサブフォルダーに保存し、読み戻し・削除も対応
  (`util/ExternalDownloadPath.java`, `util/ExternalAudioWriter.java`, `util/ExternalAudioReader.java`)
- ディレクトリ保存の大量ダウンロード対策 — キューに仕事がある間だけフォアグラウンドサービス
  (`dataSync`) と WakeLock / WifiLock で保護し、SAF の一覧取得をバッチ中キャッシュ、
  読み戻しキャッシュは差分更新、通知と再描画イベントを間引く。1 曲の例外でキューを止めない。
  キューはメモリ上のみでプロセス再起動後の再開は無し
  (`service/ExternalDownloadService.java`, `service/DownloadProgressState.java`,
  `util/ExternalAudioWriter.java`, `util/ExternalAudioReader.java`, `util/ExternalDownloadMetadataStore.java`)
- プレイリストのダウンロードで二重登録しない（ワンショット observe）
  (`ui/fragment/PlaylistPageFragment.java`)
- ディレクトリ保存でプレイリストをダウンロードすると、選択フォルダー直下に
  `<プレイリスト名>.m3u8` を書き出す。外部ストレージのツリーなら `/storage/<ボリューム>/…` の
  絶対パス、それ以外はツリー内の相対パス。保存できなかった曲は載せない
  (`util/M3uPlaylist.java`, `util/ExternalAudioWriter.java`)
- ディレクトリ保存の HTTP 取得 — 曲ごとに `Connection: close` で新しい接続を張り、無通信 20 秒か
  直近 15 秒の平均が 64 KB/s 未満（開始 10 秒後から判定）なら watchdog が切断して `Range` で途中から再開。
  1 曲あたり最大 6 回、1/2/5/10/20 秒のバックオフ。206 の `Content-Range` が合わなければ先頭からやり直す。
  バッファは 64 KB
  (`util/ExternalAudioWriter.java`, `util/HttpResumePolicy.java`)
- プレイリスト自動同期 — ディレクトリ保存でダウンロードしたプレイリストを `synced_playlist`（DB v22）に登録し、
  起動時・ホーム表示時（10 分スロットル）とプレイリスト画面表示時に曲の増減を反映。不足曲を保存し m3u8 を
  書き直す。ダウンロード中は同期しない。消えた曲は m3u8 から外すだけでファイルは消さない。
  メニュー「自動同期を解除」で登録解除。
  既存のディレクトリ保存ダウンロード（download テーブルの content:// 行の playlist_id）から初回だけ自動登録する（フラグ playlist_sync_backfilled）。
  (`util/PlaylistSyncManager.java`, `util/PlaylistSyncPolicy.java`, `model/SyncedPlaylist.kt`,
  `util/Preferences.kt`, `database/dao/DownloadDao.java`, `database/dao/SyncedPlaylistDao.java`,
  `database/AppDatabase.java`, `ui/fragment/PlaylistPageFragment.java`,
  `ui/fragment/HomeTabMusicFragment.java`, `ui/activity/MainActivity.java`)

## この端末で作業するときの注意

- **debug ビルドは端末に入らない。** `applicationIdSuffix = ".debug"` で
  package 名がホワイトリストから外れる。常に `assembleDegoogledRelease` を使う
- **署名鍵を間違えるとアンインストールが必要になり、doze 除外と認証情報が消える。**
  `adb install -r` の前に `apksigner verify --print-certs` で既存と SHA-256 を照合する
- 実機は ADB で接続できる。UI 操作は `adb shell input tap` / `input keyevent`、
  確認は `adb exec-out screencap -p` と `dumpsys activity activities` が使える
- **UI からはバックキーしか押せない端末**である点を UX 判断の前提にする。
  ホームキーもタスクキーも無いので、アプリから抜ける手段はバックキーだけ

## 検証

```bash
./gradlew testTempusDebugUnitTest lintTempusDebug
./gradlew testDegoogledDebugUnitTest lintDegoogledDebug
```

lint は `app/lint-baseline.xml` でフィルタされる。ベースライン外の新規指摘だけを見る。
