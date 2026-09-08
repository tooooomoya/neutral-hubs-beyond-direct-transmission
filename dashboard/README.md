# ライブダッシュボード (`dashboard/dashboard.py` + `dashboard/dashboard.html`)

`./run.sh`がseedのバッチを実行している間に使うリアルタイム監視ダッシュボードのリファレンス文書。別マシンのClaude Codeセッションが予備知識なしでも把握できるように書いてある — どちらのファイルにも手を入れる前にまずこれを読むこと。

## 何のためのものか

`run.sh`はN個のseedを並列実行し、それぞれが実行中に`logs/run_<seed>.log`と
`results/run_<seed>_<tag>/{metrics,opinion,GEXF}/...`へ逐次書き込みを行う(実行の仕組みは
`OPERATIONS.md`参照)。このダッシュボードはそれらのファイルをtailして、バッチが終わってから
ノートブックを読み込むのではなく、モデルの出力指標がリアルタイムに更新される様子を見せる。
**読み取り専用**であり、`results/`や`logs/`には一切書き込まない。閉じても止めても
シミュレーション自体には何の影響もない。

ファイルは2つだけ、ビルド不要、`requirements.txt`に既にある依存以外は追加なし
(GUIフォールバック用の`pandas`/`matplotlib`、ネットワークパネル用に遅延importされる
`networkx`):

- **`dashboard/dashboard.py`** — 標準ライブラリのみで書かれたHTTPサーバー(`http.server`)+
  JSON API、加えてフォールバック用のローカルmatplotlib GUI。約700行。`networkx`/`powerlaw`
  (構造パネル用)、`pandas`/`matplotlib`(GUIフォールバック用)はすべて使う箇所で遅延import
  されており、`--serve`単体はstdlibのみで動く。
- **`dashboard/dashboard.html`** — インタラクティブなフロントエンド: 素のJS、自前実装 of SVG
  チャートと、Canvasベースの力学レイアウトによるネットワークビュー。フレームワークなし、
  CDNなし(完全オフラインで動作する — CSPフリーのローカルサーバーはそもそも外部アセットに
  アクセスできないのでこれは意味がある)。約1500行。

## 起動方法(デフォルト: 外部公開込み)

```bash
./dashboard/serve_public.sh                        # デフォルト — ローカルサーバー + 公開URLを同時に起動
./dashboard/serve_public.sh --port 9000
./dashboard/serve_public.sh --seeds 0 1 2          # dashboard.py側の引数はそのまま渡せる
```

**`dashboard/serve_public.sh`(デフォルトの起動方法、2026-07-13〜):**
`dashboard/dashboard.py --serve`(ローカル`127.0.0.1:<port>`)と
[Cloudflare Quick Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/)
(`cloudflared tunnel --url http://127.0.0.1:<port>`)を1つのプロセスグループとしてまとめて起動し、
発行された`https://<random>.trycloudflare.com`のURLを標準出力に表示する。ルーターのポート開放・
アカウント登録・ドメイン設定は一切不要 — 同じWi-Fi内はもちろん、Wi-Fi外・別ネットワークからも
このURLだけでダッシュボードにフルアクセスできる。Ctrl-Cで両プロセスとも終了する。要
`cloudflared`(`brew install cloudflared`)。

**既知の制約(認識した上で採用):**
- 認証レイヤーは無い。URLを知っていれば誰でも閲覧でき、`POST /api/color-presets`で
  ローカルの色プリセットファイルを書き換えることもできてしまう。実験メトリクスのみで
  機密情報は無いため許容と判断(2026-07-13の設計判断、下記「外部アクセスの設計判断」参照)。
- URLは`serve_public.sh`を起動し直すたびに**毎回変わる**(アカウント登録なしのQuick
  Tunnelは固定URLを持たない)。共有し続けたい相手には都度新しいURLを伝える必要がある。
- Cloudflareのアカウントレス Quick Tunnelは稼働率保証が無い(実験用途と割り切ること)。

**`dashboard.py`を直接叩く(ローカル限定・またはSSHポートフォワード用):**

```bash
python dashboard/dashboard.py --serve             # インタラクティブなWebダッシュボード
python dashboard/dashboard.py --serve --port 8765 --seeds 0 1 2
python dashboard/dashboard.py                      # 最小限のmatplotlib GUI(ローカルディスプレイが必要)
python dashboard/dashboard.py --interval 10        # GUIモードの更新間隔のみ変更
```

デフォルトで`127.0.0.1:8765`に`http.server.ThreadingHTTPServer`を立て、`/`で`dashboard.html`を、
加えて下記のJSON APIを配信する。SSH越しの作業を想定して作られている(このプロジェクトの
実際の開発環境はVSCode Remote-SSH): VSCodeがポートを自動転送するので、トースト通知を
クリックするかPORTSタブを確認するか、Cmd/Ctrl+Shift+P →「Simple Browser: Show」→転送された
`http://127.0.0.1:8765`を貼り付ければエディタ内で見られる。ローカルディスプレイがあれば
普通のブラウザタブでも動く。

**`--serve`なしのGUIモード:** `matplotlib.animation.FuncAnimation`のウィンドウで、6パネルの
指標グリッドのみ表示する(2-D outcome planeの軸選択、opinion分布、ネットワークビュー、
ログテイルはなし — インタラクティブ版に書き換える前のv1のまま)。ブラウザが使えない場合の
フォールバックとしてのみ残している。拡張はこちらではなくWeb版に対して行うこと。実ディスプレイが
必要なので、素のSSH越しでは動かない。

## データモデル: seedのデータをどう見つけ、どう読むか

`active_seeds(logdir, only)` (`dashboard/dashboard.py:88`) は`logs/run_*.log`をスキャンし、
ファイル名からseedを抽出し、ファイル内容を見てステータスを分類する:
- ログのどこかに`"Exception"`または`"TERMINATE"`があれば → `failed`
- `"Elapsed time"`が存在すれば(`OpinionDynamics.java`が終了直前に出力する) → `done`
- それ以外 → `running`

### 「run set」ピッカー — `run.sh`以外(BOスクリプト等)の実験を切り替える(2026-07-23)

`active_seeds()`は元々`logs/run_<seed>.log`という`run.sh`固有の命名にしか反応しなかった。
一方`scripts/*.py`のBOキャリブレーション・スイープ類は各々が独自のログ名
(`logs/app_pol_baseline_v5_seed<seed>.log`、`logs/stage1b_bo_seed<seed>.log`等)で書いており、
これらは`results/`に何があろうと一切ダッシュボードから見えなかった。`results/run_<seed>_<tag>/`
自体は`ExperimentConfig.tag()`という同じ規約で全スクリプトが共有しているため、直す必要が
あったのはログ側の発見ロジックだけだった。

`dashboard/core.py`の`GROUP_LOG_RE = r"^(.+?)(\d+)\.log$"`が、末尾の数字列の直前までを
「プレフィックス」として抽出する(`run_0.log`→`run_`、`app_pol_baseline_v5_seed3.log`→
`app_pol_baseline_v5_seed`)。`log_groups(logdir)`が`logs/*.log`をこのプレフィックスで
グルーピングし、`api_groups()`/`set_group(prefix)`がそれぞれ「利用可能なrun set一覧
(seed数・running/done/failed内訳・最終更新時刻)」の取得と「アクティブなrun setの切り替え」を
担う。切り替えは`SERVE_GROUP`モジュールグローバル(`SERVE_LOGDIR`/`SERVE_ONLY`と同じ扱い)を
更新し、`STORES`をクリアするだけ — **サーバー再起動不要**。フロントエンドはヘッダーの
`#rungroup`セレクトから選ぶだけで(`/api/groups`は毎ポーリングで再取得するので、新しく
起動したスクリプトのログも自動的に選択肢へ現れる)、`change`で`POST /api/group`し
`refresh()`し直す。

**新しいスクリプトを書く際に何もする必要はない** — `logs/<好きな名前><seed>.log`という
命名でログを書きさえすれば、次のポーリングでピッカーの選択肢に自動的に現れる
(`run.sh`のような事前登録は不要)。

`result_dir(seed)` (`dashboard/dashboard.py:109`) は`results/run_<seed>_*`をglobして、
**最も最近更新された**マッチするフォルダを選ぶ。これは正確な設定でのルックアップではなく、
意図的なヒューリスティックである: `ExperimentConfig.tag()`はデフォルトでない全パラメータを
フォルダ名に焼き込むため(`OPERATIONS.md`参照)、Python側で今日の正確なtagを再導出せずに
知る方法がない。mtimeが機能するのは、同じseed番号の以前のスイープ由来の古いフォルダは
現在実行中のrunによって触られることがないためで、複数マッチするフォルダのうち最も
最近書き込まれたものが、構造上、稼働中のものになる。**落とし穴:** 同じseedのrunが稼働中に
古いresultフォルダを手動で`touch`する(あるいはコピーしてくる)と、このヒューリスティックは
誤ったフォルダを選んでしまう。

### CSVの増分tailing — `CsvTail` (`dashboard/dashboard.py:187`)

`results.csv`は40000-stepのrunの終わりには15MB超になり得る。毎回のポーリングで
再パースしていたのではダッシュボードがワークフローの中で一番遅い部分になってしまう。
そこで`CsvTail`は:
1. ファイルへの**バイトオフセット**を保持し、毎回のポーリングでそこからシークして新規バイト
   のみ読み、最後の完全な`\n`まででしかオフセットを進めない(書き込み途中の行を切り詰めた
   状態でパースせず、次のポーリングに持ち越す)。
2. ファイルの置き換え(inodeまたはサイズ縮小 — `force=true`でrunを再実行しresultフォルダが
   削除・再作成された場合に起きる)を検知し、ゼロからリセットする。
3. **適応的デシメーション**でメモリを制限する: 保持行数が`max_rows`(メイン指標ファイルは
   デフォルト4000、`opinion_result.csv`は2000)を超えると、保持セットを半分にし
   (`rows[::2]`)、保持ストライドを倍にする。これによりrunが長くなるにつれてメモリが
   無制限に増えることはない。つまりrunが長くなるほど初期の行は段階的に間引かれていく —
   監視ビューとしては想定内かつ問題ないが、事後分析には向かない(そちらは実際のCSV/
   ノートブックを使うこと)。

各seedの`SeedStore` (`dashboard/dashboard.py:257`) は4つの`CsvTail`を持つ:
`metrics/results.csv` (約60列のper-step指標行)、`metrics/modularity.csv`
(`step,Q_sign`、5000stepごとにしか書かれない — `OpinionDynamics.java`の
`step % 5000 == 0`ブロック参照)、`opinion/opinion_result.csv` (step毎の5-bin
opinionヒストグラム)、そして`posts/repost_cascades.csv` (`step,rootPostId,
parentPostId,postId,author,relayer,depth,...` — 1行が1回のrepostイベントで、
stepの行ではなく**イベント**の行なので、同じ`max_rows`の下でも他の3つよりずっと
早く間引かれ始める。`log_repost_cascade=false`のrunではファイルごと存在しない
— `CsvTail.poll()`は`FileNotFoundError`を無視するのでエラーにはならず、単に空
データを返す)。モジュールレベルの`STORES`辞書(`seed -> SeedStore`)と`LOCK`が、
スレッド化されたHTTPサーバー全体で共有状態を保護している。`poll_all()`は全ストアの
ステータス更新とtailを行い、鮮度が必要な全APIハンドラの先頭で`LOCK`下で呼ばれる。

### ネットワークスナップショット(GEXF)

`api_network` (`dashboard/dashboard.py:615`) は`results/.../GEXF/lambda_*/step_<N>.gexf`
(followグラフ)または`GEXF/repostNW/repost_step_<N>.gexf` (repostグラフ)を読む。これらは
Java側の`GraphVisualize`/`RepostVisualize`が5000stepごとに書き出す。パースは
`parse_gexf_cached()`が担当し、生の`networkx.Graph`をファイルサイズキーで`GRAPH_CACHE`に
キャッシュする(スナップショットファイルはwrite-onceなので、コンテンツハッシュなしでも
サイズが有効なキャッシュキーになる)。この生グラフを2箇所が共有する:

- **`load_network()`** — 力学シミュレーション用の軽量ペイロード。ノードごとに
  `{op, indeg, outdeg, hub, bc, pp}`(`bc`/`pp`はGEXFのノード属性`boundedConfidence`/
  `postProb` — **CSVには存在せずGEXFにしかないため**、agent-behavior-and-attributes
  パネルの分布ヒストグラムはこのフィールドに依存する)とエッジリストを返す —
  **レイアウト座標はサーバー側では計算しない**。ブラウザ側でライブの力学シミュレーションを
  走らせる(後述)。
- **`compute_structural()`** — 構造指標(下記)。`api_network`が`structural=1`クエリ
  パラメータ付きで呼ばれたときだけ計算される(ネットワークスナップショットパネルの通常の
  ポーリングでは払わないコスト — n=1000/m~10000で`betweenness_centrality`だけで約4秒
  かかるため)。この関数自身もgexfパスのファイルサイズで`STRUCT_CACHE`にキャッシュされる
  ので、同じスナップショットへの2回目以降の呼び出しは一瞬で返る。両方の関数が同じ
  `GRAPH_CACHE`を経由するので、コールドキャッシュ時でもXMLパースは1回で済む。

**既知の競合状態、対処済み:** `exportController.exportFile()`(Gephi、Java側)は対象の
`.gexf`パスへ直接書き込んでおり、アトミックではない(テンポラリファイル経由のリネームなし)。
ダッシュボードがちょうど最新スナップショットの書き込み中にポーリングすると、パースが
例外を投げる(途中で切れたファイルのXMLパースエラー)。`api_network`はこれを捕捉し、
**段階的に古いスナップショットへフォールバック**する(次のスナップショットの書き込みが
始まるまでそのstepのファイルは二度と触られないため、古いものは既に完全にflush済み)。
`"stale": true`を返すことで、フロントエンドは単なる失敗ではなく「step 35k (older
snapshot — latest still being written)」のように表示できる。全ての候補スナップショットの
パースに失敗した場合のみ`"error"`フィールドを返す。元のバグ報告と再現手順は
2026-07-10の修正を参照 — インシデントの詳細が必要ならこのファイルのgit logで
`ParseError`を検索すること。

### 構造指標(`compute_structural`)— journal.texの慣習に合わせた設計

`compute_structural(gexf_path, degree_csv, clustering_csv)` (`dashboard/dashboard.py`)は
1スナップショット分の構造読み取りをまとめて返す:

- **betweenness centrality / λ<sub>2</sub>(algebraic connectivity)** — 無向化した
  **giant component上でのみ**計算する(「hub betweenness falls... measured on the undirected
  giant component」「directed full-graph betweenness is uninformative here because the hub is a
  near-pure in-degree sink」という論文の慣習に合わせている)。有向グラフ全体でbetweennessを
  取っても、hubがほぼin-degreeシンクである以上意味がないため。
- **degree分布 + heavy-tail fit** — GEXFを再解析せず、Java側が同じstepで既に書いている
  `degrees/degree_result_<step>.csv` (`inDegree,outDegree`列)を読む。総次数は
  `in+out`をその場で計算。fitは`powerlaw.Fit(arr, discrete=True)`
  (`scripts/heavy_tail_check_n1000.py`と同じ規約)+`distribution_compare("power_law",
  "exponential")`でR値による妥当性判定(`R>0`ならべき乗則優勢、非heavy-tailと即断は
  しない)。fitが得られない場合`fit_powerlaw`は理由付きのdict(`{"unavailable": "too-few"|
  "error", "n": ...}`)を返し、フロントは「n<20でそもそも走らせていない(灰色)」と
  「n≥20だが計算が例外で失敗した — powerlaw未インストール等、`detail`付き(警告色)」を
  区別表示する(2026-07-11。以前はどちらも`None`で"n<20 nonzero"に潰れ、計算失敗を
  データ不足と誤認させていた)。
- **clustering coefficient** — 同様に`clusterings/clustering_result_<step>.csv`を読む
  (GEXFからの再計算はしない)。**注意:** これらのCSVは常にfollowグラフのものしか
  存在しない(repostグラフ用のclustering/degreeファイルをJava側は書いていない)ので、
  `net=repost`で表示中でもdegree/clusteringの数字はfollowグラフのまま
  — betweenness/λ<sub>2</sub>だけが選択中のグラフ(follow/repost)に追従する。

## 派生指標(DERIVED)とファミリーグルーピング(FAMILY_RE)

`dashboard/dashboard.py`先頭の`DERIVED`辞書は、`results.csv`に列として存在しない指標を
既存列から都度計算する仕組み。`(依存列のタプル, 関数)`の形で登録し、`api_series`が
リクエストされた列名が`DERIVED`にあれば`derived_column()`で計算する(丸め前の生の値
同士で演算してから最後に丸める — 先に丸めてから引き算すると誤差が乗る)。現在の3つ:

| 名前 | 式 | 用途 |
|---|---|---|
| `apparentPolarization` | `exposureOpinionVar - opinionVar` | 実際にユーザが持つ意見の分散より、見ている投稿の分散の方が大きいか(見かけ上の分極) |
| `repostShare` | `repostCount / (repostCount + originalPostCount)` | そのstepのコンテンツのうちrepostが占める割合 |
| `EI_index` | `2 * crossCuttingFraction - 1` | Krackhardt & SternのE-I index。**新規計算ではない** — 既存の`crossCuttingFraction`(follow graphの陣営間辺の割合、符号でグルーピング — `Analysis.computeCrossCuttingFraction`)を`[0,1]`から`[-1,+1]`の符号付き慣習に線形変換しただけ |

`apparentPolarization`/`repostShare`は`api_summary`の`DERIVED_IN_PICKER`経由で
「metrics…」ピッカーにも出る(通常の列と同列に選択可能)。`EI_index`は意図的にピッカーから
除外している — network-structureパネルが`Q_sign`と並べて直接`/api/series?cols=EI_index`
を叩くための専用指標という位置づけ。

`Q_sign`(modularity)は元は一般ピッカーにも出ていたが、**2026-07-10の変更でnetwork-structure
パネル専用に移動した**(GEXFと同じ5000-step周期でしか書かれない構造指標であり、per-step
グリッドの他の指標とは性質が違うため)。`api_summary`が`columns`から明示的に除外し、
`api_series`の`aux`サイドチャネル(`modularity.csv`由来)経由でしか取れないようにしている
— この配線自体は変更していない。

`FAMILY_RE`(`^(.+)_([0-4])$`)は`cRateMean_0..4`、`hostility_0..4`のようなopinion-class
別サフィックス付き列を検出し、`api_summary`が`{base: [col_0,...,col_4]}`の形で
`families`フィールドに返す(完全な0..4セットが揃っている場合のみ — 揃っていなければ
普通の列として扱う)。フロントエンドはこれを1つのチェックボックス項目として提示し、
選択時は5列すべてを`/api/series`にリクエストして**1つのグラフにopinion-class別の色**で
まとめて描く(`drawFamilyChart`、後述)。**bimodalityCoeff は移動していない**:
Sarle's bimodality coefficient(`Analysis.computeBimodalityCoefficient`)はopinion値の
歪度/尖度だけから計算され、ネットワーク構造にもGEXFにも依存しない per-step 統計量
なので、Q_signとは異なりmetrics gridに残すのが妥当と判断した(元のユーザー指示は
「GEXFから計算しているもの」という括りだったが、実際のコードを確認した結果bimodality
はそれに当てはまらなかった、という経緯)。

## JSON APIリファレンス

すべて`--serve`のHTTPサーバー配下、すべて読み取り専用、すべてライブtailされた状態に対して
毎回再計算する(永続DBなし):

| エンドポイント | パラメータ | 返り値 |
|---|---|---|
| `GET /api/summary` | — | `{seeds: [{seed, status, step, target, tag}], columns: [...], families: {base: [col_0..col_4]}}` — `logs/`で見つかった各seedにつき1行(**現在アクティブなrun setのプレフィックスに一致するログのみ** — 前節「run setピッカー」参照)、これまでに見た`results.csv`の全カラムヘッダの和集合(`_0..4`サフィックスの列と`Q_sign`を除く)+選択可能な派生指標名、そして`FAMILY_RE`で検出したファミリーのグルーピング |
| `GET /api/groups` | — | `{groups: [{prefix, seedCount, counts:{running,done,failed}, latestMtime}], active}` — `logs/*.log`をプレフィックスでグルーピングした「run set」一覧とアクティブなプレフィックス |
| `POST /api/group` | body `{prefix}` | アクティブなrun setを切り替える(`api_groups()`と同じ形を返す)。未知のprefixは拒否 |
| `GET /api/series` | `cols` (カンマ区切りのカラム名。`DERIVED`の名前も指定可), `max` (デシメーション上限、デフォルト1200) | `{seeds: {"<seed>": {step: [...], cols: {name: [...]}, aux?: {Q_sign: {step,values}}}}}` — `Q_sign`は独自のより疎なstepグリッドを持つため、`aux`サイドチャネル経由で`modularity.csv`から来る |
| `GET /api/opinion` | `seed`, `max` (デフォルト800) | `{step: [...], bins: [[...]×5]}` — 1 seed分の時系列opinion-binカウント |
| `GET /api/repost` | `seed`, `bucket` (stepバケット幅、デフォルト1000) | `{step: [...], meanDepth: [...], depthHist: {"0":count,...}, n}` — `posts/repost_cascades.csv`をstepバケットで集計した平均repost深度と、全期間のdepthヒストグラム |
| `GET /api/network` | `seed`, `net` (`follow`\|`repost`), `step` (`latest`または整数), `structural` (`1`で構造指標も計算) | `{steps: [...], step, stale, n, m, nodes, edges}` (`nodes[i]`は`{op,indeg,outdeg,hub,bc,pp}`)、`structural=1`なら追加で`structural: {giantComponentFrac, lambda2, betweenness:{mean,top}, degree:{in,out,total}, degreeFit:{in,out,total}, clustering:{values,mean}}`、全滅した場合は`{..., error}` |
| `GET /api/log` | `seed`, `lines` (デフォルト200) | `logs/run_<seed>.log`のプレーンテキストtail |

`structural=1`は**キャッシュがコールドな時だけ**重い(`betweenness_centrality`が
n=1000/m~10000で約4秒)。ネットワークスナップショットパネルは新しいスナップショットが
出現した時だけ自動再取得する(`maybeRefetchNetwork()`)ので、この数秒のコストは
5000stepに1回、しかもその1回だけしか発生しない。

値は小数点以下6桁に丸められ(`rnd()`)、`NaN`はJSONの`null`になる。`decimate()`は
一様ストライドのサブサンプリングを行う(min/maxのエンベロープ保存ではない — 保持点の間の
鋭いスパイクはズームアウト時に見えなくなり得る。ドラッグズーム機能はこれを一部補うために
存在する。ズームでstep範囲を狭めれば、同じ`max`のポイント予算がより少ないstep数を
フル解像度でカバーすることになるため)。

## フロントエンド (`scripts/dashboard.html`)

単一ページで、全状態は`state`/`netState`オブジェクトにまとまっており、`setInterval`
(デフォルト5秒、ユーザーが変更可能)でポーリングされる。仮想DOMは使っていない — 各パネルは
毎回の更新で`innerHTML`/SVG文字列を組み立て直すことで再描画される。これは意図的な選択
(デシメーション後のデータ量は小さく、差分検出レイヤーはここでは純粋にオーバーヘッドに
なるだけ)。

**表示設定は`localStorage`に永続化される**(キーの接頭辞`dash.`): 選択中の指標、
非表示にしたseed、2-D plane軸、opinion/family/repostパネル共通の「detail seed」
(`opseed`)、ネットワークのseed/type/step、スムージング、mean表示、テーマ、そして
下記のカードサイズ・並び順。ページをリロードしても表示状態は保たれる。

### カードのリサイズ・並び替え(2026-07-11)

ほぼ全てのチャートは`.panel.card`という単位で描かれ、右下の隅をドラッグして
リサイズでき(ネイティブCSS `resize:both`。ピクセル単位の精密さは狙っていない
— 「ある程度」ちょうどよく調整できれば十分という前提)、カード右上の「✦」
ハンドルをドラッグすると同じ行の中で並び替えられる。サイズ・並び順はどちらも
`localStorage`に永続化され、ページをリロードしても復元される。

- **`applyCardSize`/`watchCardSize`** — 各カードに`ResizeObserver`を1つ付け、
  リサイズの度に(250msデバウンス後)`dash.size.<cardId>`へ`{w,h}`を保存する。
  ネットワークスナップショットのカード(`id="netsnapshot"`)だけは、リサイズ後に
  `startSim()`を再度呼んでcanvasの内部解像度をカードの新しいサイズに合わせ直す
  コールバックを渡している(`initStaticCard`の第4引数)。
- **`attachDrag`/`makeCard`/`initStaticCard`** — ネイティブHTML5 drag-and-drop
  (`dragstart`/`dragover`/`drop`)で、ドロップ先カードの左半分/右半分どちらに
  マウスがあるかで挿入位置を決める。カードには2つの流儀がある:
  - **動的カード**(メトリクスグリッド、degree3枚、structstats4枚、qsignei/
    repostpair/attrpairの各duo) — その親コンテナは毎回の描画で`innerHTML=""`
    ごと作り直されるので、並び順は"データ"として持たせる。メトリクスグリッドは
    既存の`state.metrics`配列そのものが並び順であり(`renderPicker`のチェック
    ボックス操作と全く同じ仕組みで並び替えも`save("metrics",...)`する)、それ
    以外の固定カード群(degree/structstats/qsignei/repostpair/attrpair)は
    `DEFAULT_ORDER`+`getOrder("<key>")`/`saveOrder("<key>",...)`
    (`localStorage`キー`dash.cardOrder.<key>`)という同じ形のペアを使う。新しい
    カードIDが将来追加されても`getOrder`が末尾に自動追記するので、保存済みの
    並び順が壊れて一部のカードが消えることはない。
  - **静的カード**(2-D outcome plane / opinion distribution のペア、ネットワーク
    スナップショット) — 独自のセレクタ/スクラブバー/凡例を持つ複雑なHTMLなので
    毎回作り直さない。ドラッグは実DOMノードを直接並び替えるだけ(`container.
    insertBefore`)——次の描画でも消えずにそのまま残る。ページ**リロード**を
    またぐ場合だけ`restoreStaticOrder()`が`dash.cardOrder.top1`を読んで
    `initStaticCards()`内で並び替えを再生する。
- **`interacting`フラグ** — ポーリングは5秒ごとに`renderGrid()`等を呼んで
  カードを丸ごと作り直すため、リサイズ/並び替えの操作中にポーリングが割り込むと
  ドラッグ中のDOMノードが消えてジェスチャーが壊れる。`ResizeObserver`発火中と
  ドラッグ中は`interacting=true`にし、`refresh()`冒頭でこれを見て丸ごとスキップ
  する(次のポーリングでやり直せばよいだけなので、単純にreturnするだけで十分)。

パネル:
- **メトリクスグリッド** (`renderGrid`/`drawLineChart`/`drawFamilyChart`) — 選択した指標
  ごとに1つのSVG折れ線グラフ(「metrics…」の下のピッカー、デフォルト=`dashboard.html`の
  `DEFAULT_METRICS`にある5つ — `cRateMean`(ファミリー)、`disagreement`、
  `apparentPolarization`、`unfollow`、`originalPostCount` — 旧デフォルトは2-D outcome-planeの
  6指標だった)。ホバーでクロスヘア+seed毎の
  ツールチップ、ドラッグ選択でstep範囲をズーム(`state.zoom`経由で全チャート同期、
  ダブルクリックでリセット)、任意で移動平均によるスムージング。
  - **ファミリー指標**(`cRateMean_0..4`のような`_0..4`サフィックス列。前節参照)を選ぶと
    `drawFamilyChart`が使われる: seedごとではなく、`state.opseed`(detail seed)**1つ分**を
    opinion-class 0〜4別の5色(opinion-bin用のdiverging青↔赤パレットを再利用)で描く。
    同じチャートに複数seedを重ねない設計 — 5クラス×複数seedだと判読不能になるため。
  - **「show mean」トグル**(`#showmean`)— 通常の(ファミリーでない)チャートで、現在
    表示中(非hidden)のseedすべての平均線を追加で描く。`seriesFor()`が、最も点数の多い
    seedのstepグリッドを基準に、他のseedの値を`nearestIdx`でスナップして平均する
    (ホバーツールチップの最近傍スナップと同じ考え方)。平均線は太め+破線+ink色で他と
    区別され、凡例上は`mean( N seeds )`として扱われる。
- **2-D outcome plane** (`renderPlane`) — 任意の2指標をx/y軸に選べる(セレクタ)、seed毎の
  軌跡を早→遅の不透明度グラデーションで表示し、スクラブ位置にドットを打つ。これは
  Type-1/Type-2の構造分離×感情/意見分極という本来読みたい対象のためのパネル。パネル上部に**時間スクラブ
  スライダー**(`#planeScrub`)があり、ドラッグすると軌跡の終端(ドット)をその時点のstepまで
  巻き戻せる — 軸のスケール(`x0..x1`/`y0..y1`)は巻き戻し前の全区間から計算されたまま固定
  なので、スクラブしてもプロットが再スケールでガクガク動くことはない。`state.scrub`は
  `null`(=live、5秒ごとのポーリングで新stepに自動追従)か、固定step番号(その値で凍結され、
  ポーリングでデータが伸びても動かない)のどちらか。**live**ボタン(`#planeLive`)で
  `null`に戻す。**play**ボタン(`#planePlay`、2026-07-11)は`state.scrub`をstep0から
  最新まで約6秒でアニメーション走査し、軌跡がstep0から描かれていく様子を再生する
  (`startPlanePlay`/`stopPlanePlay`、step範囲は`renderPlane`が`state.planeRange`に格納)。
  再生中にスクラブ/liveを触ると停止。`state.zoom`と同様`localStorage`には永続化されない
  (ページリロードでliveに戻る)。
- **opinion分布** (`renderOpinion`) — 時系列でのbinシェアの積み上げエリア、diverging
  青↔赤パレット。
- **ネットワークスナップショット** (`startSim`/`simTick`/`drawNet`) — **ブラウザ内での
  ライブな力学レイアウト**、
  [soramame0518/Social-Media-Echo-Chamber](https://github.com/soramame0518/Social-Media-Echo-Chamber)
  のD3デモに倣ったスタイル(多体反発+リンクのバネ+中心力)。**ノードの半径・色は
  パネル上の `size`/`color` セレクトで属性を切り替えられる**(2026-07-11)。既定は
  半径 = followers(in-degree)、色 = opinion。**色スケールはユーザが編集できる**
  (2026-07-11):数値属性(`op`/`indeg`/`outdeg`/総次数/`pp`/`bc`)は**両端＋中央の3点の
  カラーピッカー**で決める2-stopグラデーション(`gradColor`、opinionのみ域を[-1,1]固定)、
  カテゴリ属性(`opsign`=意見符号 −/0/+、`hub`=介入ハブか否か)は**カテゴリごとの
  パレット**で決める(`getCatPalette`/`renderColorControls`、`#netcolorctl`に描画)。
  半径は面積比例(`r ∝ √value`)で、選んだ属性の最大値を最大半径に正規化する。凡例
  (`updateNetLegend`)とピッカーは選択中のcolor属性に追従。設定(グラデーション3色、
  カテゴリパレット)は `localStorage`(`netgrad`/`netcat.<attr>`)に保存される。
  **時間シーケンスバー**(`#netScrub`＋`#netPlay`、2026-07-11)で利用可能なGEXFスナップ
  ショット(step 0, 5000, …)をスクラブ/再生できる(`startNetPlay`は1.8秒間隔で
  fetchNetworkを進める。初回はbetweenness等の計算で律速、以降はサーバー側キャッシュで即時)。
  **枠への張り付き対策**(2026-07-11):`simTick`の壁クランプを撤去し、`drawNet`が毎フレーム
  レイアウトのbboxをキャンバスの約9割に収まるようスケール(`fitView`、k は退化レイアウトの
  暴発を防ぐため上限6でクリップ)して中央寄せ描画する — 絶対座標の広がりに関係なく必ず枠内に
  収まり、ノードが壁に集まらない。ホバー判定も同じ変換を通す。
  D3を使わずCanvas上に依存ゼロで再実装:
  反発計算は真のBarnes-Hutではなく粗い12×12グリッド近似を使う(ノード自身+隣接セル内は
  厳密、それより外側は重心近似)。n≈1000であれば十分。位置は**スナップショットのstep間で
  引き継がれる**(同じseed+typeならシミュレーションオブジェクトを保持し`alpha`を
  再加熱するだけ)ため、stepを送るたびに毎回ランダムな初期配置からやり直すのではなく、
  ソーティング/分離が進行する様子が見える。「re-layout」ボタンで強制的に再加熱できる。
  ホバーツールチップにはopinion/followers/followingに加え`bc`(bounded confidence)/
  `pp`(post prob)も出す(2026-07-10、agent-behaviorパネル用にペイロードへ追加した
  フィールドをそのまま流用)。
  **repostグラフのedge種別カラーリング**(`#netedgetype`、2026-07-21):
  `RepostVisualize.java`が同日追加したhomophily/hostile edge分割(`dominantEdgeType`
  ="homophily"|"hostile"|"mixed"、repostProb-gatedのin-bc relay vs
  outOfBCRepostProb-gatedのout-of-bc relay)をGEXFエッジ属性から読み、チェックを
  入れるとタイプ別に固定パレット(`getEdgeTypePalette`、緑/赤/紫 — opinionの
  diverging blue、opsignのblue/cyan/greenと衝突しないよう選定)で描き分ける
  (`drawNet`)。ノードの色スケールと違い**ユーザ編集不可の固定パレット**。
  followグラフのエッジ、または2026-07-21より前に書かれたrepostスナップショットには
  この属性がない(`dominantEdgeType`が`null`)ため、チェックを入れていても通常の
  単色エッジ描画にフォールバックする(消えたりエラーにはならない) —
  `dashboard/analysis.py`の`load_network`がエッジタプルを`[u,v,weight]`から
  `[u,v,weight,dominantEdgeType]`に拡張した(第4要素は無い場合`None`)。
- **network structure** (`renderStructural`) — ネットワークスナップショットと同じ
  seed/stepに対する構造指標。「network snapshot」パネルが`&structural=1`付きで
  `fetchNetwork()`を呼ぶたびに更新される(=構造指標だけ別ポーリングはしない、キャッシュが
  効くので実質タダ)。
  - **degree分布** (`drawDegreeChart`) — in/out/total degreeそれぞれ、経験累積分布関数
    (CCDF, P(X≥x))をlog-logで描く(Clauset/Newman/Shalizi の慣習 — ヒストグラムの
    ビニングアーティファクトを避けられ、べき乗則ならCCDF自体が直線になるので目視での
    妥当性確認がしやすい)。`degreeFit.plausible`が真なら破線のfit参照線を重ね描き
    (`fit.xmin`以上の範囲のみ、経験曲線上の最近傍点にアンカーして`(x/anchor)^-(alpha-1)`
    で外挿)、色は妥当なら`var(--warn)`、そうでなければ`var(--muted)`。**out-degreeは
    `max_follow`でハードキャップされているため、通常は"not favored vs. exponential"に
    なる**(ほぼ全ノードが同じ値に張り付く、有機的なheavy-tailではなく人為的な上限による
    もの — これはバグではなく正しい観測)。
  - **clustering coefficient** — 平均値のstat tile + ヒストグラム(`drawHistChart`、
    線形ビン、`clusterings/clustering_result_<step>.csv`由来)。
  - **betweenness (giant comp.)** — 平均値のstat tile + 上位10ノードの表(`hub`フラグ付き)。
  - **λ<sub>2</sub> algebraic connectivity** / **giant component fraction** — stat tile。
  - **Q_sign** / **E-I index** — `drawMiniSeedChart`(ホバーなしの軽量折れ線、
    `drawSimpleSeries`を共有)。`netState.seed`のみを対象にする(表示中の全seedではない
    — このパネル自体が「今見ているネットワークスナップショットの seed を深掘りする」
    という位置づけのため)。
- **agent behavior & attributes** (`renderBehavior`) — `state.opseed`(detail seed、
  opinion分布/ファミリーチャートと共通)を主対象にする2パネル+`netState.seed`を対象にする
  2パネルが混在するので、パネル見出しの`attrSeedNote`で今どのseedを見ているか明示している:
  - **mean repost depth** / **repost share of content** — `/api/repost`
    (detail seed)、`repostShare`派生指標(detail seed)。
  - **bounded confidence distribution** / **post probability distribution** —
    `netState.data.nodes[].bc`/`.pp`のヒストグラム(`autoRange()`でmin/maxを自動決定)。
    GEXFのノード属性からしか取れないため、ネットワークスナップショットパネルが今表示中の
    seed/stepに従う(detail seedとは独立)。スパイラル・オブ・サイレンス機構により
    多くのrunで`pp`が下限付近に、`bc`が上限付近に偏った分布になるのは想定内(2026-07-10の
    実データで確認済み — バグではない)。
- **seedチップ** — ステータス+進捗%、クリックでそのseedを全パネルで表示/非表示切替、
  ▤で右側にログテイルのドロワーを開く(`/api/log`、自動更新、既に一番下にいれば
  自動スクロール)。
- ヘッダーのコントロール: 一時停止、更新間隔、スムージングのトグル、show meanトグル、
  ダーク/ライトテーマの切替(永続化される。`:root[data-theme]`が`prefers-color-scheme`
  メディアクエリを上書きする)、ズームリセット。

**カラーシステム:** 固定のカテゴリカルパレット(8色、ライト/ダーク両バリアント)をseed
インデックスに割り当てる — プロジェクトの`dataviz`スキルの規約に従い*サイクルさせない*
(`<script>`ブロック内の`SERIES_LIGHT`/`SERIES_DARK`は`dashboard/dashboard.py`の`COLORS`と対応しており、
これは他の箇所でも使われている同じ参照パレット。変更する際は両者を同期させること)。
9番目以降のseedは色相`i % 8`を再利用しつつ破線スタイルにする(新しい色相を作らない)。
opinionの値はカテゴリカルパレットとは別の、diverging青↔赤スケール(`BINS_LIGHT`/
`BINS_DARK`、`opColor()`)を使う。

## 拡張のしかた

- **新しい実験スクリプト(BOキャリブレーション、スイープ等)をダッシュボードに見せる:**
  何もしなくてよい。`logs/<好きな名前><seed>.log`という命名でログファイルを書くスクリプトなら、
  次のポーリングで自動的に`#rungroup`ピッカーの選択肢に現れる(前節「run setピッカー」参照)。
  事前登録・設定ファイル編集は不要。
- **新しいスカラー指標パネル:** `results.csv`にそのカラムが存在すれば既に使える —
  「metrics…」を開いてチェックを入れるだけ。*デフォルトで表示*させたい場合は
  `dashboard.html`の`DEFAULT_METRICS`に追加する(GUIモードのフォールバックや
  `Q_sign`のような`aux`サイドチャネル扱いも必要な場合のみ、`dashboard/dashboard.py`の
  `METRICS`/`RESULTS_COLS`にも反映する)。
- **既存列から計算する派生指標:** `dashboard/dashboard.py`の`DERIVED`辞書に
  `"name": (("dep1","dep2"), lambda a,b: ...)`を追加するだけ — `api_series`が自動で
  拾う。ピッカーに出したいなら`DERIVED_IN_PICKER`にも名前を足す(出したくない
  — 特定パネル専用にしたい — なら足さない。`EI_index`がその例)。
- **`_0..4`サフィックスの新しいファミリー指標:** Java側で`row.put("foo_"+i, ...)`の
  形式で5クラス分書けば、`FAMILY_RE`が自動検出し、ピッカーに1エントリとして出る
  (フロントエンド側の変更は不要)。
- **step毎の新しい非スカラーデータソース**(`results.csv`のカラムではないもの)を
  追加する場合: `SeedStore`に`CsvTail`を追加し、新しい`api_*`関数を作り、
  `Handler.do_GET`にルートを追加し、クライアント側に対応する`fetch`/render関数を書く —
  「新しいデータソースには専用エンドポイントが要る」というテンプレートとして
  `opinion`/`repost`/`network`パネルを参考にすること。
- **GEXFスナップショットに紐づく新しい構造指標:** `compute_structural()`に足すのが
  自然(`parse_gexf_cached()`経由で生グラフを取れる)。**必ずキャッシュに乗せること**
  — betweenness級の計算はポーリングのたびに払えるコストではない。既存の
  `STRUCT_CACHE`パターン(ファイルサイズキー)をそのまま流用できる。
- **変更の検証:** このツールにテストスイートはない。実際の(または`n=100 steps=200`程度の
  小さなスモークランで作った)`results/`ツリーに対して
  `python dashboard/dashboard.py --serve --port <空いているport>`を実行し、該当する
  `/api/...`エンドポイントを`curl`してJSON形状を確認し、見た目に関わる変更であれば
  headless Chromeでページをスクリーンショットする
  (`--headless --disable-gpu --screenshot=out.png --window-size=W,H --virtual-time-budget=ms URL`)、
  という方法で検証すること — この文書にある機能はすべて、実際にこうやって開発中に
  確認されたものである(未検証のまま出荷した想定は一つもない)。

## 既知の限界・やらないこと

- マルチユーザー非対応、認証なし、デフォルトで`127.0.0.1`にバインド(`--host`で変更可能だが
  認証レイヤーはないので、共有マシンで`0.0.0.0`にバインドしないこと)。外部からアクセスしたい
  場合も`--host 0.0.0.0`+ルーターのポート開放ではなく`dashboard/serve_public.sh`
  (Cloudflare Quick Tunnel)を使うこと — サーバー自体は`127.0.0.1`にバインドしたまま
  トンネル経由で公開するため、意図せずLAN全体やインターネットにポートを晒すことがない。
- 履歴・永続ストレージなし — 現時点の`results/`/`logs/`の中身に対するライブビューでしか
  ない。ブラウザタブを閉じても何も失われないが(状態はサーバー側で導出されるものと
  localStorageの表示設定のみ)、ダッシュボードサーバーの再起動をまたいだタイムライン
  スクラブは、CSV自体が持っている範囲を超えてはできない。
- デシメーションは一様ストライドであり、エンベロープ保存ではない(上のAPIの節参照) —
  厳密な分析には必ず実際の`results.csv`/ノートブックに戻ること。このダッシュボードの
  間引かれたビューを使わないこと。
- `result_dir()`のmtime-newestヒューリスティック(前述)は保証ではなく実質的な仮定であり、
  既知の失敗モードが1つある(手動でtouchされた古いフォルダ)。**run setピッカー(前述)は
  「どのseedを追跡するか」を切り替えるだけで、この仮定自体には手を入れていない** — 2つの
  実験が同じseed番号を同時に使っている場合、mtime-newestは依然としてどちらのresultフォルダが
  「今のもの」か取り違えうる。
- degree分布/clustering coefficientは常にfollowグラフのCSVから読む — `net=repost`表示中でも
  数字は変わらない(repost用の同等CSVをJava側が書いていないため)。betweenness/λ<sub>2</sub>
  だけが選択中のグラフに追従する。前節「構造指標」参照。
- ヒストグラム(`drawHistChart`)は線形ビン+線形y軸(左に軸線+目盛りラベル`0`〜最大count、
  degree分布`drawDegreeChart`は左に対数y軸の10のべき乗目盛り`1`/`1e-1`/…を描く。以前は
  x軸しか描いておらず縦軸目盛りが不可視だった、2026-07-11修正)。bounded confidence/post probのように
  分布が片側の上限・下限近くに極端に偏る場合(スパイラル・オブ・サイレンス機構の実データで
  実際に起きる)、支配的なビン以外がほぼ見えなくなる。対数y軸は未実装 — 必要なら
  `drawHistChart`にオプションを足すのが素直な拡張。
- heavy-tail fit(`fit_powerlaw`)はMLE1点推定+指数分布との比較(R値)のみ。
  対数正規分布など他の重い裾を持つ分布との比較はしていない — 「べき乗則が指数分布より
  良い」以上のことは主張していない(Clauset et al. 2009の最低限の作法)。

## 独立リポジトリとの同期ワークフロー (Git Subtree)

本リポジトリ内の `dashboard/` ディレクトリは、Git Subtree 機能によって独立した別リポジトリ（ダッシュボード単独公開用リポジトリ、`ISL-opinion-dynamics-dashboard`）と同期されています。これにより、メインリポジトリでコードを追跡しつつ、ダッシュボード機能だけを軽量な状態で外部公開・配布することが可能になっています。

**ブランチモデル(2026-07-18改訂):** 外部リポジトリの `main` は特定モデルに依存しない汎用版で、
共同研究者(yabe)を含む複数フォークの「いいとこどり」ベースとして維持する。このリポジトリ
(polarized-vocal-minority-model)の `dashboard/` は**このプロジェクト専用のフォーク**であり、
外部リポジトリの `toomoya` ブランチに対応する。したがって以降の push/pull は `main` ではなく
`toomoya` を対象にすること — `main` へ直接pushしない。

### リモートリポジトリの登録
初期設定として、ダッシュボード専用の外部リモートリポジトリを登録します。
```bash
git remote add dashboard-remote <外部リポジトリのGit-URL>
```

### 双方向同期コマンド

#### 1. メインリポジトリの変更を外部リポジトリへ送信する (Push)
メインリポジトリの `dashboard/` ディレクトリ内で加えた変更（コミット）を、外部リポジトリの `toomoya` ブランチ(このプロジェクト専用フォーク)に反映させます。
```bash
git subtree push --prefix=dashboard dashboard-remote toomoya
```

#### 2. 外部リポジトリの変更をメインリポジトリへ取り込む (Pull)
外部リポジトリの `toomoya` ブランチでの変更内容を、メインリポジトリの `dashboard/` ディレクトリに取り込みます。履歴を簡潔に保つため `--squash` の併用を推奨します。
```bash
git subtree pull --prefix=dashboard dashboard-remote toomoya --squash
```

> [!NOTE]
> 同期作業を行う際は、あらかじめメインリポジトリのワーキングツリーをクリーン（すべての変更をコミットまたは退避）にしてから実行してください。
