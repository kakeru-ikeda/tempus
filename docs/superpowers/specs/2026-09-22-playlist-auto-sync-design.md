# プレイリスト自動同期（ディレクトリ保存） 設計

- 日付: 2026-09-22
- 対象: SP2000T フォーク (`sp2000t-tuning` ブランチ)
- 実装計画: `docs/plans/2026-09-22-playlist-auto-sync.md`

## 背景

ディレクトリ保存（SAF ツリーへの保存）でプレイリストをダウンロードすると、曲ファイルと
`<プレイリスト名>.m3u8` が選択フォルダーに書き出される。しかしサーバー側でプレイリストに曲が
追加・削除されても、端末側は手動で再ダウンロードしない限り古いままになる。

SP2000T は GMS が無く doze も効くため、WorkManager などの定期ジョブには頼れない。
UI からはバックキーしか押せない端末なので、ユーザーが意識しなくても「アプリを開いたとき」に
揃っていることを目指す。

## 要件（ユーザー承認済み）

- 対象はフォーク独自の「ディレクトリ保存」経路だけ。Media3 の通常ダウンロードは対象外。
- トリガー
  - アプリ起動時 (`MainActivity`)
  - `HomeTabMusicFragment` を開いたとき
  - `PlaylistPageFragment` を開いたとき（そのプレイリストだけ、スロットル無視）
  - WorkManager / 定期バックグラウンドジョブは使わない。
- サーバーで削除された曲は m3u8 から外す。音声ファイルは**決して削除しない**。
- ディレクトリ保存で一度でもダウンロードしたプレイリストは自動で登録される。
  プレイリスト画面のオーバーフローメニュー「自動同期を解除」で登録を外せる
  （登録中のときだけ表示）。

## 全体構成

```text
PlaylistPageFragment ──(ディレクトリ保存でDL)──> PlaylistSyncManager.register()
        │                                             │
        └─(画面を開く)──> syncPlaylist(id) ──┐          ▼
MainActivity.onCreate ──> syncAll(false) ──┤   synced_playlist テーブル (Room)
HomeTabMusicFragment.onResume ──> syncAll ─┘          │
                                                      ▼
                        getPlaylist(id) を Subsonic API から同期取得
                                                      │
                        PlaylistSyncPolicy.needsSync(...)  ← 純粋関数・単体テスト
                                                      │ true のとき
                                                      ▼
                ExternalAudioWriter.downloadPlaylistToUserDirectory(ctx, songs, id, name)
                  （既存ファイルはスキップ、欠けている曲だけ保存、最後に m3u8 を "wt" で書き直し）
```

### 1. 登録テーブル `synced_playlist`

| 列 | 型 | 説明 |
|---|---|---|
| `playlist_id` | TEXT PK | Subsonic のプレイリスト ID |
| `name` | TEXT NULL | 最後に m3u8 を書いたときのプレイリスト名 |
| `song_ids_hash` | TEXT NULL | 曲 ID リスト（順序込み）の SHA-256 16 進 |
| `last_synced_at` | INTEGER NOT NULL | 最後に同期判定した時刻 (`System.currentTimeMillis()`) |

- エンティティは `model/SyncedPlaylist.kt`（他のモデルと同じく Kotlin）、DAO は
  `database/dao/SyncedPlaylistDao.java`。
- DB は version 21 → 22。既存の慣例どおり `@AutoMigration(from = 21, to = 22)` を追加する
  （テーブル追加のみなので spec 不要）。スキーマ JSON
  `app/schemas/com.eddyizm.tempus.database.AppDatabase/22.json` はビルド時に生成されるので
  コミットに含める。
- 既存の `download` テーブル（PK が曲 ID、REPLACE 挿入）は同じ曲が複数プレイリストに入ると
  上書きされるため、プレイリスト登録簿には使えない。`playlist` / `playlist_song` はサーバーの
  キャッシュで、サーバー側で消えると行ごと消されるため、これも登録簿には使えない。

### 2. 判定ロジック `util/PlaylistSyncPolicy.java`（純粋関数）

Android 依存なし・単体テスト対象。

- `hashSongIds(List<String>)` — 順序込みの SHA-256。並び替えも m3u8 の変更なので差分として扱う。
- `isThrottled(lastRunElapsedMs, nowElapsedMs, force)` — 直近 10 分以内に `syncAll` が走っていれば
  スキップ。`force`、未実行 (`<= 0`)、時計の逆行 (`now < last`) のときはスキップしない。
- `needsSync(storedHash, currentHash, missingCount, storedName, currentName)` — 次のどれかで true:
  - 保存済みハッシュが無い、または現在のハッシュと違う
  - 欠けているファイルが 1 曲以上ある（`ExternalAudioReader.getUri(song) == null`）
  - プレイリスト名が変わった（`currentName` が null でなく `storedName` と違う）

### 3. `util/PlaylistSyncManager.java`（シングルトン）

- `register(playlistId, name, songs)` — upsert。ハッシュはダウンロードした曲リストから計算し、
  `last_synced_at` は現在時刻。バックグラウンドスレッドで DB を触る。
- `unregister(playlistId)` — 行を削除するだけ。m3u8 と曲ファイルはそのまま残す。
- `isRegistered(playlistId)` — `LiveData<Boolean>`（メニュー表示切り替え用）。
- `syncAll(context, force)` — 登録済み全件を同期。スロットル対象。
- `syncPlaylist(context, playlistId)` — 1 件だけ同期。スロットル無視、スロットルの時刻も更新しない。
  登録されていなければ何もしない。

共通の前提条件（呼び出しスレッドで確認し、満たさなければ即 return）:

- `Preferences.getDownloadDirectoryUri() == null`（ディレクトリ保存が未設定）なら何もしない。
- 未ログイン（`Preferences.getPassword() == null` かつ token/salt の組が無い）なら何もしない。

並行実行: `AtomicBoolean` で実行中フラグを持ち、実行中に来た呼び出しは**捨てる**（キューしない）。
処理は専用の単一スレッド Executor 上で行う（`ExternalAudioReader.getUri` は main スレッド以外で
呼ぶ必要があり、Subsonic 呼び出しも同期 `execute()` を使うため）。

1 プレイリストの処理:

1. `App.getSubsonicClientInstance(false).getPlaylistClient().getPlaylist(id).execute()` で
   サーバーから取得する。`PlaylistRepository.getPlaylistSongs` は失敗時に `playlist_song`
   キャッシュへフォールバックし、そのキャッシュは曲数が同じだと更新されない
   (`PlaylistRepository.cachePlaylistSongs` の TODO) ため使わない。
2. 例外・HTTP 失敗・`playlist == null`（エラーコード 70 = サーバーで削除済みを含む）は
   ログだけ出してスキップ。登録は残し、次のトリガーで再試行する。
3. 曲リストが空ならスキップ（`downloadPlaylistToUserDirectory` は空リストで何もせず、
   `writePlaylistFile` も保存曲 0 件では m3u8 を書かないため、既存の m3u8 が残る）。
   ハッシュも更新しない。
4. 欠けている曲数を `ExternalAudioReader.getUri(song) == null` で数える。
5. `needsSync` が false なら `last_synced_at` だけ更新して終わり。
6. true なら `ExternalAudioWriter.downloadPlaylistToUserDirectory(ctx, 現在の全曲, id, 現在の名前)`
   を呼び、`name` / `song_ids_hash` / `last_synced_at` を更新する。

### 4. m3u8 がいつ書かれるか（確認済み）

`ExternalAudioWriter.downloadPlaylistToUserDirectory`（`util/ExternalAudioWriter.java:125`）は
曲ごとのタスクを単一スレッドの `EXECUTOR` に積んだあと、最後に `writePlaylistFile` を同じ
Executor に積む。したがって m3u8 は**キューされた曲のダウンロードがすべて終わった後**に書かれ、
新しく追加された曲も保存に成功していれば m3u8 に載る。保存に失敗した曲は載らないが、次回の
同期で「欠けているファイルあり」として再度処理される。

`writePlaylistFile` は既存の `<name>.m3u8` を `"wt"` で上書きし、渡した曲リストの順で
保存済みの曲だけを書く。サーバーで削除された曲は渡すリストに含まれないので m3u8 から消える。
曲ファイルを消すコードパスは通らない。

### 5. 名前変更

サーバー側でプレイリスト名が変わった場合、新しい名前で m3u8 を書き、`name` 列を更新する。
古い名前の m3u8 は残す（ファイル削除をしない方針を優先）。

### 6. UI

- `PlaylistPageFragment` の「すべてダウンロード」でディレクトリ保存経路を通ったとき、
  `ExternalAudioWriter.downloadPlaylistToUserDirectory` の直後に `register` する。
- 画面を開いたとき (`onCreateView` で `init` の後) に `syncPlaylist(id)` を呼ぶ。
- オーバーフローメニューに `action_unregister_playlist_sync`（「自動同期を解除」）を追加し、
  `isRegistered` の LiveData で表示を切り替える。押したら `unregister` して Toast を出す。
- 文字列は英語の既定値を `tools:ignore="MissingTranslation"` 付きで `values/strings.xml` に、
  日本語を `values-ja/strings.xml` に置く（既存のフォーク追加文字列と同じ慣例）。

## 既知のトレードオフ

- `performDownload` は既存ファイルの確認より前に HTTP 接続を開く（`openInitial`）。そのため
  同期が必要と判定されたときは、既に保存済みの曲についても 1 曲ずつ接続を張ってはすぐ閉じる。
  判定が true になるのは曲の増減・並び替え・欠落・改名のときだけなので許容する。
  必要なら将来「既存ファイルなら HTTP を開かない」最適化を別途入れる。
- 同期が走ると進捗通知（`DownloadProgressState`）にスキップ分も数えられる。
- `ExternalAudioReader` のキャッシュが再構築中だと `getUri` が null を返し、全曲欠落と判定される
  ことがある。その場合も writer 側で既存ファイルはスキップされるので結果は正しい（コストだけ増える）。
- キューはメモリ上のみ（既存仕様）。同期中にプロセスが死んでも、次の起動で欠落として再処理される。

## 採用しなかった案

- **WorkManager の定期ジョブ**: GMS 無し・doze の端末で信頼できず、要件で除外。
- **`download` テーブルの `playlist_id` を登録簿に流用**: PK が曲 ID で REPLACE 挿入のため、
  複数プレイリストに属する曲で情報が失われる。
- **`playlist_song` キャッシュとの比較**: 曲数が同じだと更新されないため差分を見落とす。
- **欠けている曲だけを writer に渡す**: m3u8 の相対パスは曲タスクの結果から組み立てられるため、
  全曲を渡さないと m3u8 から既存曲が落ちる。writer の改修が必要になるので見送り。
- **サーバーで消えたプレイリストの登録を自動削除**: 一時的なエラーと区別しにくいので、
  登録は残して黙ってスキップする。

## 未解決の質問（既定の扱いで実装を進め、必要なら後で変更）

1. 複数サーバーを切り替えて使う場合、他サーバーのプレイリスト ID も同期対象に残る
   （取得に失敗して黙ってスキップされるだけ）。`server_id` 列を足して現在のサーバーだけに
   絞るべきか。既定: 足さない（承認済みスキーマどおり）。
2. 保存先フォルダーを変更したときや「ダウンロード済みを削除」したときに登録を消すべきか。
   既定: 消さない（次回同期で新しいフォルダーに全曲を保存し直す挙動になる）。
