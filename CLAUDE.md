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
