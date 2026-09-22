# プレイリスト自動同期（ディレクトリ保存） 実装計画

設計: `docs/superpowers/specs/2026-09-22-playlist-auto-sync-design.md`

## 未解決の質問（既定の扱いで進める。ブロッカーではない）

1. 複数サーバー運用時に `server_id` で絞るか → 既定: 絞らない（承認済みスキーマどおり）。
2. 保存先フォルダー変更・ダウンロード削除時に登録を消すか → 既定: 消さない。

## Goal

ディレクトリ保存（SAF ツリー）で一度ダウンロードしたプレイリストを登録し、アプリ起動時・
ホームタブ表示時・プレイリスト画面表示時に、サーバー側の曲の増減を反映する（不足曲を保存し、
`<name>.m3u8` を現在の曲リストで書き直す）。

## Non-goals

- Media3 の通常ダウンロード経路の同期。
- WorkManager・定期バックグラウンドジョブ。
- 曲ファイルや古い m3u8 の削除（一切削除しない）。
- `ExternalAudioWriter` の改修（既存の `downloadPlaylistToUserDirectory` をそのまま使う）。
- 実機での確認（debug ビルドは端末に入らない）。

## Design notes

- 登録簿は新テーブル `synced_playlist`（`download` / `playlist_song` は流用不可。理由は設計文書）。
  DB 21 → 22 は既存慣例どおり `@AutoMigration`。
- 判定ロジック（ハッシュ・欠落・改名・スロットル）は `util/PlaylistSyncPolicy.java` の純粋関数に
  切り出して JUnit4 でテストする（既存の `HttpResumePolicyTest` と同じ形式）。
- サーバー取得は `PlaylistRepository.getPlaylistSongs` を使わず、Subsonic の
  `getPlaylist(id).execute()` を同期で呼ぶ（失敗時にキャッシュへフォールバックされるため）。
- m3u8 は `ExternalAudioWriter` の単一スレッド `EXECUTOR` 上で全曲タスクの**後**に書かれる
  (`util/ExternalAudioWriter.java:125-138`) ので、新規追加曲も保存成功すれば m3u8 に載る。
- 並行実行は `AtomicBoolean` で捨てる。スロットル時刻はメモリ上（プロセス起動直後は必ず走る）。
- 却下案: WorkManager、`download` テーブル流用、欠落曲だけ writer に渡す（m3u8 から既存曲が落ちる）。

## 共通検証コマンド

```bash
./gradlew testDegoogledDebugUnitTest lintDegoogledDebug
```

lint は `app/lint-baseline.xml` でフィルタされる。ベースライン外の新規指摘が 0 件であること。

---

### Task 1: synced_playlist テーブルと DAO を追加し DB を 22 に上げる

- Files to touch:
  - `app/src/main/java/com/eddyizm/tempus/model/SyncedPlaylist.kt`（新規）
  - `app/src/main/java/com/eddyizm/tempus/database/dao/SyncedPlaylistDao.java`（新規）
  - `app/src/main/java/com/eddyizm/tempus/database/AppDatabase.java`
  - `app/schemas/com.eddyizm.tempus.database.AppDatabase/22.json`（ビルドで自動生成されるものをそのまま残す）
- Files NOT to touch: `app/schemas/**/21.json` 以前、`app/build.gradle`、他の model/DAO、`app/lint-baseline.xml`
- New dependencies: none
- Steps:
  1. `model/SyncedPlaylist.kt` を作る。既存の `model/Favorite.kt` の書き方に合わせる（`@Keep`、`@Entity`、`@ColumnInfo`）。Parcelize は不要。
     ```kotlin
     @Keep
     @Entity(tableName = "synced_playlist")
     data class SyncedPlaylist(
         @PrimaryKey
         @ColumnInfo(name = "playlist_id")
         val playlistId: String,
         @ColumnInfo(name = "name")
         var name: String? = null,
         @ColumnInfo(name = "song_ids_hash")
         var songIdsHash: String? = null,
         @ColumnInfo(name = "last_synced_at")
         var lastSyncedAt: Long = 0,
     )
     ```
  2. `database/dao/SyncedPlaylistDao.java` を作る（Java、`DownloadDao.java` の書式に合わせる）。メソッド:
     - `@Query("SELECT * FROM synced_playlist") List<SyncedPlaylist> getAllSync();`
     - `@Query("SELECT * FROM synced_playlist WHERE playlist_id = :playlistId") SyncedPlaylist getOne(String playlistId);`
     - `@Query("SELECT EXISTS(SELECT 1 FROM synced_playlist WHERE playlist_id = :playlistId)") LiveData<Boolean> isRegistered(String playlistId);`
     - `@Insert(onConflict = OnConflictStrategy.REPLACE) void upsert(SyncedPlaylist syncedPlaylist);`
     - `@Query("UPDATE synced_playlist SET name = :name, song_ids_hash = :songIdsHash, last_synced_at = :lastSyncedAt WHERE playlist_id = :playlistId") void updateSyncState(String playlistId, String name, String songIdsHash, long lastSyncedAt);`
     - `@Query("DELETE FROM synced_playlist WHERE playlist_id = :playlistId") void delete(String playlistId);`
  3. `AppDatabase.java`: `version = 21` → `22`。`entities` の末尾に `SyncedPlaylist.class` を追加。`autoMigrations` の末尾に `@AutoMigration(from = 21, to = 22),` を追加。`public abstract SyncedPlaylistDao syncedPlaylistDao();` を追加。import も追加。
  4. ビルド（検証コマンド）で `app/schemas/com.eddyizm.tempus.database.AppDatabase/22.json` が生成されるので削除しない。
- Acceptance:
  - コンパイルが通り、`22.json` に `synced_playlist` テーブル（4 列、PK `playlist_id`）が含まれる。
  - 既存テストがすべて成功し、lint の新規指摘が無い。
- Verify: `./gradlew testDegoogledDebugUnitTest lintDegoogledDebug && grep -q '"tableName": "synced_playlist"' app/schemas/com.eddyizm.tempus.database.AppDatabase/22.json`

### Task 2: 同期判定の純粋関数 PlaylistSyncPolicy と単体テストを追加する

- Files to touch:
  - `app/src/main/java/com/eddyizm/tempus/util/PlaylistSyncPolicy.java`（新規）
  - `app/src/test/java/com/eddyizm/tempus/util/PlaylistSyncPolicyTest.java`（新規）
- Files NOT to touch: 上記以外すべて
- New dependencies: none
- Steps:
  1. `util/PlaylistSyncPolicy.java` を `public final class`（private コンストラクタ）で作る。Android API は使わない（`java.security.MessageDigest`、`java.nio.charset.StandardCharsets`、`java.util.Objects` のみ）。
     - `public static final long THROTTLE_MS = 10L * 60L * 1000L;`
     - `public static String hashSongIds(List<String> songIds)`: null は空リスト扱い。各 ID（null は `""`）を `'\n'` で連結した UTF-8 バイト列の SHA-256 を小文字 16 進 64 文字で返す。順序が変われば結果も変わる。`NoSuchAlgorithmException` は `IllegalStateException` で包んで投げる。
     - `public static boolean isThrottled(long lastRunElapsedMs, long nowElapsedMs, boolean force)`: `force` なら false。`lastRunElapsedMs <= 0` なら false。`nowElapsedMs < lastRunElapsedMs` なら false。それ以外は `nowElapsedMs - lastRunElapsedMs < THROTTLE_MS`。
     - `public static boolean needsSync(String storedHash, String currentHash, int missingCount, String storedName, String currentName)`: 次のどれかで true — `storedHash == null`、`!storedHash.equals(currentHash)`、`missingCount > 0`、`currentName != null && !currentName.equals(storedName)`。それ以外 false。
  2. `PlaylistSyncPolicyTest.java` を JUnit4（`@RunWith(JUnit4.class)`、`HttpResumePolicyTest.java` と同じ形式）で作る。最低限のケース:
     - 同じリストは同じハッシュ、要素追加・削除・並び替えでハッシュが変わる、null リストと空リストが同じハッシュ、長さ 64。
     - `isThrottled`: force で false、未実行 (0) で false、5 分後 true、10 分ちょうどで false、時計逆行で false。
     - `needsSync`: 全一致で false、storedHash null で true、ハッシュ違いで true、missing 1 で true、改名で true、currentName null なら改名扱いしない。
- Acceptance: `PlaylistSyncPolicyTest` の全テストが成功し、既存テストも成功する。
- Verify: `./gradlew testDegoogledDebugUnitTest --tests 'com.eddyizm.tempus.util.PlaylistSyncPolicyTest' && ./gradlew testDegoogledDebugUnitTest lintDegoogledDebug`

### Task 3: PlaylistSyncManager を追加する（登録・解除・同期本体）

- Files to touch:
  - `app/src/main/java/com/eddyizm/tempus/util/PlaylistSyncManager.java`（新規）
- Files NOT to touch: `util/ExternalAudioWriter.java`、`util/ExternalAudioReader.java`、`repository/**`、`ui/**`、`database/**`
- New dependencies: none
- Steps:
  1. `util/PlaylistSyncManager.java` を作る。`@OptIn(markerClass = UnstableApi.class)` が必要な箇所（`AppDatabase` は `@UnstableApi`）は周辺コードに倣う。構成:
     - `private static final String TAG = "PlaylistSyncManager";`
     - シングルトン: `public static synchronized PlaylistSyncManager getInstance()`。
     - `private final ExecutorService executor = Executors.newSingleThreadExecutor();`
     - `private final AtomicBoolean running = new AtomicBoolean(false);`
     - `private volatile long lastRunElapsedMs = 0;`（`SystemClock.elapsedRealtime()` 基準、`syncAll` 専用）
     - DAO は `AppDatabase.getInstance().syncedPlaylistDao()`。
  2. 公開 API:
     - `public void register(String playlistId, String name, List<Child> songs)`: `playlistId` が null なら return。executor 上で `dao.upsert(new SyncedPlaylist(playlistId, name, PlaylistSyncPolicy.hashSongIds(ids(songs)), System.currentTimeMillis()))`。`running` フラグは見ない。
     - `public void unregister(String playlistId)`: executor 上で `dao.delete(playlistId)`。ファイルは触らない。
     - `public LiveData<Boolean> isRegistered(String playlistId)`: `dao.isRegistered(playlistId)` をそのまま返す。
     - `public void syncAll(Context context, boolean force)`: 前提条件（下記）を満たさなければ return。`PlaylistSyncPolicy.isThrottled(lastRunElapsedMs, SystemClock.elapsedRealtime(), force)` なら return。`running.compareAndSet(false, true)` に失敗したら `Log.d` して return（キューしない）。`lastRunElapsedMs` を現在値に更新し、executor 上で `dao.getAllSync()` の各行に `syncOne` を実行、`finally` で `running.set(false)`。
     - `public void syncPlaylist(Context context, String playlistId)`: 前提条件を満たさなければ return。スロットルは見ず、`lastRunElapsedMs` も更新しない。`running` の扱いは `syncAll` と同じ。executor 上で `dao.getOne(playlistId)` が null なら何もしない、あれば `syncOne`。
  3. 前提条件 `private static boolean canSync()`: `Preferences.getDownloadDirectoryUri() != null` かつ（`Preferences.getPassword() != null` または `Preferences.getToken() != null && Preferences.getSalt() != null`）。`Context` は `context.getApplicationContext()` を保持して使う。context が null なら return。
  4. `private void syncOne(Context appContext, SyncedPlaylist row)`（executor スレッドで実行。全体を try/catch(Exception) で囲み、例外は `Log.w` のみで次へ進む）:
     1. `Response<ApiResponse> response = App.getSubsonicClientInstance(false).getPlaylistClient().getPlaylist(row.getPlaylistId()).execute();`
     2. `!response.isSuccessful()`、`body == null`、`getSubsonicResponse().getPlaylist() == null`（エラーコード 70 を含む）は `Log.i` してスキップ。登録は残す。
     3. `songs = playlist.getEntries()`、null なら空扱い。空ならスキップ（ハッシュも更新しない）。null 要素は除外した `List<Child>` を作る。
     4. `currentHash = PlaylistSyncPolicy.hashSongIds(ids)`、`missing = songs のうち ExternalAudioReader.getUri(song) == null の数`、`currentName = playlist.getName() != null ? playlist.getName() : row.getName()`。
     5. `needsSync(row.getSongIdsHash(), currentHash, missing, row.getName(), currentName)` が false なら `dao.updateSyncState(id, row.getName(), row.getSongIdsHash(), now)` して終わり。
     6. true なら `ExternalAudioWriter.downloadPlaylistToUserDirectory(appContext, songs, id, currentName)` を呼び、`dao.updateSyncState(id, currentName, currentHash, now)`。`Log.i` で「曲数・欠落数・改名有無」を出す。
  5. クラス Javadoc に「m3u8 は writer の単一スレッド Executor 上で曲タスクの後に書かれるので、新規追加曲も保存成功後に載る。曲ファイルは削除しない」旨を 2〜3 行で書く。
- Acceptance:
  - コンパイルが通る。このタスク時点ではどこからも呼ばれない（未使用警告が lint の新規指摘にならないこと。なる場合は Task 4 と同時に確認する旨を報告）。
  - 曲ファイル削除や `ExternalAudioWriter` 以外への書き込みを行うコードが無い。
- Verify: `./gradlew testDegoogledDebugUnitTest lintDegoogledDebug`

### Task 4: プレイリスト画面で登録・解除メニュー・画面表示時の同期をつなぐ

- Files to touch:
  - `app/src/main/java/com/eddyizm/tempus/ui/fragment/PlaylistPageFragment.java`
  - `app/src/main/res/menu/playlist_page_menu.xml`
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/res/values-ja/strings.xml`
- Files NOT to touch: `util/PlaylistSyncManager.java`（API は Task 3 のまま使う）、`util/ExternalAudioWriter.java`、他言語の `values-*/strings.xml`、`app/lint-baseline.xml`
- New dependencies: none
- Steps:
  1. `playlist_page_menu.xml` の末尾（`action_unpin_playlist` の後）に追加:
     ```xml
     <item
         android:id="@+id/action_unregister_playlist_sync"
         android:title="@string/menu_playlist_sync_unregister_button"
         android:visible="false"
         app:showAsAction="never" />
     ```
  2. `values/strings.xml` の `menu_unpin_button` 付近に、既存のフォーク追加文字列と同じく `tools:ignore="MissingTranslation"` 付きで追加:
     - `menu_playlist_sync_unregister_button` = `Stop auto-sync`
     - `playlist_sync_unregistered_toast` = `Auto-sync stopped for this playlist`
     `values-ja/strings.xml` に同名で `自動同期を解除` / `このプレイリストの自動同期を解除しました` を追加。ファイル先頭に `xmlns:tools` が無ければ追加する。
  3. `PlaylistPageFragment.onOptionsItemSelected` の `action_download_playlist` 分岐、`else` 側（ディレクトリ保存経路）で `ExternalAudioWriter.downloadPlaylistToUserDirectory(...)` の直後に
     `PlaylistSyncManager.getInstance().register(_playListID, _playListName, songs);` を追加する。Media3 経路（`if` 側）では登録しない。
  4. 同メソッドに分岐を追加: `item.getItemId() == R.id.action_unregister_playlist_sync` なら `PlaylistSyncManager.getInstance().unregister(playlistPageViewModel.getPlaylist().getId())` を呼び、`Toast.makeText(requireContext(), R.string.playlist_sync_unregistered_toast, Toast.LENGTH_SHORT).show()`、`return true`。
  5. `initMenuOption(Menu menu)` に追加: `PlaylistSyncManager.getInstance().isRegistered(playlistPageViewModel.getPlaylist().getId()).observe(getViewLifecycleOwner(), registered -> { MenuItem item = menu.findItem(R.id.action_unregister_playlist_sync); if (item != null) item.setVisible(Boolean.TRUE.equals(registered)); });`
  6. `onCreateView` の `initSongsView();` の後（`init(playlistArg)` が済んだ位置）で
     `PlaylistSyncManager.getInstance().syncPlaylist(requireContext(), playlistArg.getId());` を呼ぶ（未登録・ディレクトリ保存未設定なら manager 側で何もしない）。
- Acceptance:
  - ディレクトリ保存経路でダウンロードすると `register` が呼ばれるコードになっている。Media3 経路では呼ばれない。
  - メニュー項目は登録中のときだけ可視。押すと解除して Toast。
  - 画面表示時に `syncPlaylist` が 1 回呼ばれる。
  - テスト成功、lint 新規指摘なし（`MissingTranslation` / `UnusedResources` を含む）。
- Verify: `./gradlew testDegoogledDebugUnitTest lintDegoogledDebug`

### Task 5: 起動時・ホームタブ表示時のトリガーを追加し、CLAUDE.md を更新する

- Files to touch:
  - `app/src/main/java/com/eddyizm/tempus/ui/activity/MainActivity.java`
  - `app/src/main/java/com/eddyizm/tempus/ui/fragment/HomeTabMusicFragment.java`
  - `CLAUDE.md`
- Files NOT to touch: `util/PlaylistSyncManager.java`、`util/PlaylistSyncPolicy.java`、`PlaylistPageFragment.java`、`AGENTS.md`、`docs/sp2000t-install.md`
- New dependencies: none
- Steps:
  1. `MainActivity.onCreate` の `checkConnectionType();` の直後に `PlaylistSyncManager.getInstance().syncAll(this, false);` を追加（前提条件・スロットル・並行実行は manager 側で処理される）。
  2. `HomeTabMusicFragment.onResume` の末尾に `PlaylistSyncManager.getInstance().syncAll(requireContext(), false);` を追加。
  3. `CLAUDE.md` の「upstream からの意図的な差分」節の末尾に 1 項目追加（既存項目と同じ書式）:
     ```
     - プレイリスト自動同期 — ディレクトリ保存でダウンロードしたプレイリストを `synced_playlist`（DB v22）に登録し、
       起動時・ホーム表示時（10 分スロットル）とプレイリスト画面表示時に曲の増減を反映。不足曲を保存し m3u8 を
       書き直す。消えた曲は m3u8 から外すだけでファイルは消さない。メニュー「自動同期を解除」で登録解除
       (`util/PlaylistSyncManager.java`, `util/PlaylistSyncPolicy.java`, `model/SyncedPlaylist.kt`,
       `database/dao/SyncedPlaylistDao.java`, `database/AppDatabase.java`, `ui/fragment/PlaylistPageFragment.java`,
       `ui/fragment/HomeTabMusicFragment.java`, `ui/activity/MainActivity.java`)
     ```
  4. tempus フレーバーでもビルド・テスト・lint が通ることを確認する。
- Acceptance:
  - 起動時とホームタブ表示時に `syncAll(..., false)` が呼ばれる。
  - `CLAUDE.md` に差分の 1 項目がある。
  - degoogled / tempus 両フレーバーでテスト成功、lint 新規指摘なし。
- Verify: `./gradlew testDegoogledDebugUnitTest lintDegoogledDebug && ./gradlew testTempusDebugUnitTest lintTempusDebug`
