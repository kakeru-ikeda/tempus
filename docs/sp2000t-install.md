# SP2000T へのインストール

Astell&Kern A&ultima SP2000T に Tempus を入れるための手引き。すべて実機で確認した
内容で、推測は「未確認」と明記してある。フォーク固有の文書であり upstream には無い。

## 対象機

| 項目 | 値 |
|---|---|
| 機種 | A&ultima SP2000T (AK ROM) |
| Android | 9 / API 28 |
| SoC | Rockchip RK3326 |
| ABI | arm64-v8a |
| Google Play 開発者サービス | **無し**（`com.google.android.webview` のみ） |

`minSdk 24` の Tempus は API 28 を無改造で満たす。ABI も universal APK に arm64-v8a が
含まれるため、SDK まわりで手を入れる必要は無い。

GMS が無いので **`degoogled` フレーバーを使う**。`tempus` フレーバーは
`tempusImplementation libs.media3.cast` で Cast SDK を引き込むため、この端末には向かない。

## ADB の有効化

`com.iriver.tester.factorytool` の exported な Activity から行う。

```bash
adb shell am start --user 0 -n com.iriver.tester.factorytool/.DebugSettingActivity   # ADB 有効化
adb shell am start --user 0 -n com.iriver.tester.factorytool/.UserDebugActivity      # 再起動後も維持
```

機種や ROM によって Activity 名が異なりうるので、コンポーネント名をハードコードせず
exported な Activity を列挙して選ぶのが安全。`.PreOtaActivity` は SP2000T には存在しない。

## 最大の関門 — package 名ホワイトリスト

**AK ROM の `PackageInstaller.validateInstallLocked` が package 名を 40 件のホワイトリストで
強制している。** 対象外の名前は梱包方法や署名を変えても必ず
`INSTALL_FAILED_INVALID_APK: Not apk supported` になる。**`adb install` も同じ検査を通る**ので
ADB 経由でも回避できない。この文字列は AOSP に無く `services.vdex` に埋め込まれている。

`com.eddyizm.degoogled.tempus` はホワイトリストに含まれない。**改名が必須。**

### ホワイトリスト全 40 件

```text
com.amazon.mp3                          com.now.moov
com.amazon.ziggy.android                com.onkyomusic.android
com.apkfab.installer                    com.pandora.android
com.apkpure.installer                   com.qobuz.music
com.appgeneration.itunerfree            com.sirius
com.apple.android.music                 com.skysoft.kkbox.android
com.apple.android.music.classical       com.soundcloud.android
com.aspiro.tidal                        com.spotify.music
com.audible.application                 com.synology.
com.bandcamp.android                    com.tencent.ibg.joox
com.bbc.globaliplayerradio.international deezer.android.app
com.foobar2000.foobar2000               fm.awa.liverpool
com.google.android.inputmethod.japanese fm.last.android
com.google.android.inputmethod.latin    fm.player
com.idagio.app                          fm.xiami.main
com.iloen.melon                         net.nugs.multiband
com.ktmusic.geniemusic                  net.siamdev.nattster.manman
com.neowiz.android.bugs                 ru.yandex.music
com.netease.cloudmusic                  skplanet.musicmate
                                        tunein.player
                                        uk.co.sevendigital.android
```

`com.synology.` の末尾のドットは原文ママ。前方一致で判定されている可能性があるが**未確認**。

### 使用中の枠

```bash
adb shell pm list packages -3
```

2026-08-25 時点: `com.now.moov` / `fm.last.android`（AKScrobble が借用）/
`com.apple.android.music` / `com.foobar2000.foobar2000`（**Tempus が使用中**）。
残り 36 枠が空き。

## 改名インストールの手順

Tempus の `degoogled` フレーバーは `applicationId`（`com.eddyizm.degoogled.tempus`）と
`namespace`（`com.eddyizm.tempus`）が既に別なので、**manifest 内のコンポーネント名がすべて
完全修飾**になっている。よって manifest の `package` 属性だけ差し替えれば通る。

```bash
java -jar apktool.jar d -f -o src app-degoogled-release.apk

# package 属性のみ。android:name の値には絶対に触らない（相対名になると class 解決が壊れる）
sed -i 's|package="com.eddyizm.degoogled.tempus"|package="com.foobar2000.foobar2000"|' src/AndroidManifest.xml

# apktool の再ビルドで lib の圧縮方式が変わるため、false のままだと
# インストール時に "Failed to extract native libraries, res=-2" で落ちる
sed -i 's|android:extractNativeLibs="false"|android:extractNativeLibs="true"|' src/AndroidManifest.xml

java -jar apktool.jar b -o unsigned.apk src
zipalign -p -f 4 unsigned.apk aligned.apk
apksigner sign --ks <keystore> --ks-pass pass:<pass> --ks-key-alias <alias> \
  --v1-signing-enabled true --v2-signing-enabled true --out tempus-renamed.apk aligned.apk
adb install -r tempus-renamed.apk
```

ContentProvider の authority や独自パーミッション名には旧 applicationId の文字列が残るが、
authority は package 属性と独立なので競合しない。

ソースからビルドする場合は `productFlavors` の `applicationId` を上書きすればよく、
apktool は不要。`compileSdk/targetSdk 37`・`buildToolsVersion 36.1.0`・JDK 21 が要る。
ffmpeg デコーダは `libs/lib-decoder-ffmpeg-release.aar` としてリポジトリ同梱なので
NDK ビルドは不要。

### 別経路 — Open APP Service

素の `.apk` を端末 UI から開くと必ず「解析中に問題が発生しました」になる。MTP で内部
ストレージの `OpenService` フォルダへ `.xapk`（`base.apk` + 空の config split 1 本 +
`manifest.json`、`style="plain"`）を置いて Open APP Service から入れる経路もあるが、
ADB が使えるなら `adb install` のほうが速い。投入前に古い `.xapk` と AK のインストーラが
作った展開済みフォルダを消しておくこと。

## インストール後に必要な設定

### 電池最適化からの除外

**アプリ内からは原理的に要求できない。** AK ROM には
`android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` を受ける Activity が無く、
`cmd package query-activities` は `No activities found` を返す。ただし設定アプリに
非対応と表示するだけのスタブ `com.iriver.settings/.warnning.WarnnigUnsupportedActivity`
が登録されているため `resolveActivity` は非 null を返す。つまり Tempus が起動時に出す
「バッテリー最適化」ダイアログの「無効にする」は、この端末では何もしない。

doze 自体は有効（`mDeepEnabled=true`）なので、ADB で入れる。

```bash
adb shell dumpsys deviceidle whitelist +com.foobar2000.foobar2000
adb shell dumpsys deviceidle whitelist | grep foobar    # 確認
```

## アンインストールで失われるもの

署名鍵を変えたビルドを入れ替えるにはアンインストールが必要だが、アプリのデータ以外も
消える。実機で確認済み。

- **doze ホワイトリストから外れる。** 再登録が必要
- 付与済みの実行時権限が取り消される
- アプリ内に保存した認証情報は失われる

## 音まわりの既知事項

- **Android 9 の `SoftFlacDecoder` は壊れている。** 条件によって
  `W/FLACDecoder: decodeOneFrame: no streaminfo metadata block` を出しながらゴミを
  デコードする。Tempus は `lib/arm64-v8a/libffmpegJNI.so`（Media3 の ffmpeg デコーダ拡張）を
  同梱しており、この経路を構造的に回避できるはず。**実証は未了**
- **`audioserver` が SIGSEGV で落ちることがある。** `AudioOut_D` スレッドで複数回観測。
  端末側の問題なのでアプリを替えても残る
- 出力は `AUDIO_DEVICE_OUT_WIRED_HEADSET`、HAL は `PCM_FORMAT_S16_LE`
- `MediaBrowser` は権限なしで接続できるが、`NotificationListenerService` と
  `MediaSessionManager.getActiveSessions()` は AK ROM の内部状態で拒否され root が要る

## 現状

`com.foobar2000.foobar2000` として Tempus v4.25.5（degoogled）をインストール済み、
doze 除外済み。APK は 9.3MB。
