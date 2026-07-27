# AndroidCode

**AIコーディングエージェントをAndroidのネイティブGUIでローカル実行 — ターミナル不要です。**

AndroidCodeはAIコーディングエージェントをスマートフォンで使えるようにするネイティブAndroid GUIアプリです。[OpenCode](https://github.com/sst/opencode)や[Claude Code](https://github.com/anthropics/claude-code)とタッチ操作中心のインターフェースで対話できます — 端末エミュレータもSSHもPCも、オンデバイス実行には一切不要です。PRootによるオンデバイスランタイムか、PC/Mac/Linux上の既存OpenCodeサーバーへのリモート接続で動作します。

> [!IMPORTANT]
> AndroidCodeは独立したオープンソースプロジェクトです。OpenCodeおよびAnthropicとは一切関係ありません。

[English README](README.md)

---

## 対応エージェント

| エージェント | オンデバイス | PCリモート | 状態 |
|-------------|:---------:|:---------:|------|
| [OpenCode](https://github.com/sst/opencode) | ✓ | ✓ | 安定版 |
| [Claude Code](https://github.com/anthropics/claude-code) | ✓ | — | ベータ |

オンデバイスエージェントはPRoot経由でAlpine Linux環境内で実行されます。リモートOpenCodeはLANまたはTailscale経由でPC/Mac/Linux上の`opencode serve`インスタンスに接続します。

## 主な機能

- **ネイティブAndroid GUI** — コーディングエージェントのためのタッチ操作中心インターフェース。CLIや端末は不要
- **オンデバイスランタイム** — Alpine Linux、Git、bash、curl、ripgrep、コーディングエージェントをPRootで自動セットアップ
- **リポジトリ・ワークスペース** — デバイス上でGitリポジトリを開いて作業
- **Gitサポート** — ステージング、差分表示、コミット、ブランチ管理をGUIで実行
- **差分ビューア** — 適用前にコード変更をインラインで確認
- **ツール承認** — 危険なツール操作の許可・拒否
- **セッション管理** — 新規作成、再開、名前変更、削除
- **動的モデル** — 接続中のエージェントインスタンスからモデル・プロバイダー・エージェントを動的取得
- **リアルタイムストリーミング** — SSEによる回答・実行状況・承認要求のリアルタイム受信
- **構造化タイムライン** — reasoning・ツール実行・コマンド出力を折りたたみ表示
- **音声＋ウェイクワード** — Android音声認識によるプッシュ・トゥ・トーク＋ウェイクワード検出
- **テキスト読み上げ** — 回答の音声読み上げ
- **デジタルアシスタント** — Androidの既定アシスタントとして登録（ホームジェスチャー／コーナースワイプ）
- **安全な保存** — 接続情報をAndroid Keystoreで暗号化
- **バイリンガル** — 日本語・英語UI

## リモートOpenCode

オンデバイスエージェントに加えて、AndroidCodeは追加機能としてPC/Mac/Linux上のOpenCodeに接続できます：

- **リモート接続** — LANまたはTailscale経由で接続
- **実行先切り替え** — 会話中でもAndroidローカル／PCリモート間をシームレスに切り替え（ハンドオフ）
- **自動検出** — QRコードまたはmDNS（ゼロ設定LAN検出）でPCを検索

## クイックスタート

### オンデバイス実行（PC不要）

1. [Releases](https://github.com/yuga-hashimoto/android-code/releases/latest)からAPKをインストール
2. アプリを開く → **作業先** → **このAndroid端末** → **この端末へセットアップ**
3. ランタイムのダウンロード・インストールを待つ（約2分）
4. コーディングエージェントを選択してチャット開始

### PCリモート実行

1. PCでOpenCodeサーバーを起動:

```bash
OPENCODE_SERVER_PASSWORD='your-strong-password' \
  opencode serve --hostname 0.0.0.0 --port 4096 --mdns
```

2. Android端末にAPKをインストール
3. アプリ → **作業先** → **接続先を追加**
4. PCのIPを入力（または**LANで検索**／**QRで追加**で自動発見）

### セキュリティ

- ポート4096をインターネットへ直接公開しないでください
- LANまたはTailscaleでの利用を推奨
- 公開ネットワークではHTTPSリバースプロキシを使用
- 危険操作は自動承認されません
- LAN上の平文HTTPは接続先ごとに明示的許可が必要

## ビルド

必要環境: JDK 17、Android SDK、Python 3、ネットワーク接続（初回のみ）

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

## コントリビューション

[CONTRIBUTING.md](CONTRIBUTING.md)を参照してください。

## 設計資料

- [AndroidCode v2設計書](docs/superpowers/specs/2026-07-18-opencode-android-v2-design.md)
- [第一完成版の実装計画](docs/superpowers/plans/2026-07-18-initial-mvp.md)
- [Androidローカル実行設計](docs/LOCAL_RUNTIME.md)

## 第三者ソフトウェア

ランタイム生成処理の一部は、MITライセンスのHermes Agent Android実装に含まれる汎用Termuxパッケージ解決・展開処理をコーディングエージェント向けに再設計しています。詳細は[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)を参照。

## ライセンス

[MIT](LICENSE)
