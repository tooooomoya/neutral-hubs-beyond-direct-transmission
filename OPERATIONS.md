# Operations manual — common "how do I do X again" commands

Quick reference for recurring tasks on this project. `README.md` covers first-time setup; this
file is the day-to-day runbook. Update it when a workflow changes (e.g. a new CLI flag, a new
gotcha found) rather than letting it drift.

## Build & test

```bash
./lib/fetch-deps.sh   # once, to fetch the gephi toolkit jar from Maven Central
LIBCP="$(find lib -name '*.jar' | tr '\n' ':')"
find src -name '*.java' > .javafiles.txt
javac -cp "$LIBCP" -d bin @.javafiles.txt && rm -f .javafiles.txt

java -cp "${LIBCP}bin" test.ModelTests   # assertion harness, exits non-zero on failure
```

## Running simulations

**Single run, direct:**
```bash
java -cp "${LIBCP}bin" dynamics.OpinionDynamics seed=0 [key=value ...]
```

**Batch via `run.sh`** (compiles first, then runs seeds in parallel):
```bash
./run.sh                                          # defaults: seeds 0..29, parallel 10
./run.sh --seeds "0 1 2" --parallel 4 --heap 4g
./run.sh --seeds "0 1" -- p_u=0.05 bc_dec=0.995    # tokens after `--` go to the simulator
```

Full CLI key list: `src/experiment/ExperimentConfig.java`'s `fromArgs` switch statement is the
source of truth (it silently ignores unknown keys, so a typo won't error — double-check spelling
against that file if a run doesn't behave as expected). `README.md` lists the frequently-used ones.

## Force-overwriting an existing result folder

**Default behavior (R26): a run whose target result folder already exists ABORTS rather than
overwriting** — folder names are derived from the full config (`ExperimentConfig.tag()`), so a
collision usually means "you're about to silently redo/clobber a previous run with the same
config," which the tool refuses to do by default.

**To force it anyway (R27):** pass `force=true` as a config token. This deletes the pre-existing
folder first, then proceeds normally.

```bash
java -cp "${LIBCP}bin" dynamics.OpinionDynamics seed=0 force=true
./run.sh --seeds "0" -- force=true
```

There is no partial/merge behavior — `force=true` is a full delete-then-redo. If you only want to
keep some of a folder's contents, move/copy what you need out before running with `force=true`.

## Live dashboard while `run.sh` is running

```bash
python dashboard/dashboard.py --serve          # interactive web dashboard (recommended; for SSH / VSCode Remote-SSH)
python dashboard/dashboard.py                  # minimal matplotlib GUI fallback (needs a local display)
python dashboard/dashboard.py --serve --port 8765 --seeds 0 1 2
```

`--serve` starts a local HTTP server (`dashboard/dashboard.html` + JSON APIs, no extra deps beyond
requirements.txt) with an interactive dashboard:

- **metric grid** — any `results.csv` column (or derived metric: `apparentPolarization`,
  `repostShare`) via the "metrics…" picker, plus `_0..4` opinion-class families
  (`cRateMean`, `hostility`, ...) rendered as one class-colored chart each; defaults =
  `cRateMean`/`disagreement`/`apparentPolarization`/`unfollow`/`originalPostCount`. Hover
  crosshair/tooltip, drag-to-zoom on steps (synced across charts, double-click to reset),
  smoothing, and a "show mean" toggle to overlay the average across visible seeds.
- **seed chips** — status (running/done/failed) + progress % from `logs/run_<seed>.log` markers
  (`"Elapsed time"` = done, `"step = N"` = in progress); click to show/hide a seed, ▤ to tail its log.
- **2-D outcome plane** — per-seed trajectories, both axes selectable, with a time-scrub slider.
- **opinion distribution** — stacked bin shares over time from `opinion/opinion_result.csv`.
- **network snapshot** — live force-directed layout of the GEXF snapshots (follow or repost
  graph, any 5000-step snapshot; node color = opinion, radius ∝ √followers, ringed = hub,
  cross-cutting-edge highlight). Style after soramame0518/Social-Media-Echo-Chamber.
- **network structure** — degree distributions (in/out/total) as log-log CCDFs with a
  power-law fit overlay, clustering coefficient, giant-component betweenness + λ₂
  (algebraic connectivity), Q_sign (modularity) and E-I index over time — all for the same
  seed/step as the network snapshot, matching the paper's undirected-giant-component convention.
- **agent behavior & attributes** — mean repost depth and repost share over time
  (`posts/repost_cascades.csv`), plus bounded-confidence and post-probability distribution
  histograms (GEXF node attributes, not available in any CSV).
- pause / refresh-interval / dark-light toggle; view settings persist in the browser.
- every card is resizable (drag its bottom-right corner) and reorderable within its row
  (drag the ✦ handle); both persist across reloads.

Full design rationale (derived-metric mechanism, family grouping, caching strategy, betweenness
cost, known limitations) is in `dashboard/README.md` (Japanese) — read that before extending either
file. The server tails the result CSVs incrementally (byte-offset + adaptive decimation), so it
stays cheap even against 15 MB `results.csv` files while a 30-seed batch is writing. Read-only —
never edits `results/` or `logs/`; stopping it does not touch the simulations.

**Over SSH:** VSCode Remote-SSH auto-forwards the port (click the toast, or check the PORTS tab);
view it in-editor via Cmd/Ctrl+Shift+P -> "Simple Browser: Show" -> the forwarded
`http://127.0.0.1:8765` URL, or in any normal browser tab.

## Analysis scripts

`scripts/` holds exactly the pipeline that reproduces the paper's Experimental Results section
(Figs. `outcome-heatmap`, `mod-extreme-influx`, `control-minus-msilent`), run in order:

1. `scripts/lmp_feedmech_stage1.py` — shared constants (15 seeds, 40,000 steps, 2,000-step
   trailing window) and the t=0 hub-class selection.
2. `scripts/lmp_feedmech_structural_9b.py` — per-seed structural metrics (degree, clustering,
   betweenness) on the resulting network.
3. `scripts/lmp_feedmech_chamber_gate.py` — echo-chamber measurement used by the grid launcher.
4. `scripts/lmp_feedmech_grid4x4.py` — launches the full $P_u\times\VCR=4\times4$ factorial
   (control vs. N-silent, 15 seeds, 480 runs total) that the paper's factor levels are drawn from.
5. `scripts/lmp_feedmech_paper_figures.py` — reads that grid's output and renders the three
   figures into `figures/`.

Each script's own docstring documents its exact CLI/usage and result-folder assumptions.
`scripts/` contains only this reproduction pipeline — earlier exploratory scripts (parameter
calibration, mechanism-investigation dead ends) are not included.

## Git workflow

Standard flow (see the repo's own commit history for message style — short imperative summary,
detail in the body, `Co-Authored-By` trailer when Claude made the change):

```bash
git status                     # review what's staged/unstaged before adding
git add <specific files>       # avoid `git add -A` — review for secrets/large binaries first
git commit -m "..."
git push origin main           # plain push; this repo has no protected-branch friction so far
```

**Force-push (`git push --force` / `--force-with-lease`): only when you specifically mean to
rewrite remote history (e.g. after an interactive rebase or amending a pushed commit).**
- Prefer `--force-with-lease` over bare `--force` — it refuses if someone else pushed to the
  branch since your last fetch, so you don't silently clobber someone else's work:
  ```bash
  git push --force-with-lease origin main
  ```
- Never do this on `main` without being certain no one else has pushed in the meantime — check
  `git fetch && git log origin/main` first.
- This is a genuinely destructive operation (can permanently lose commits on the remote); treat
  a request for it as needing fresh confirmation every time, not a standing default.

## Environment gotchas (found the hard way)

- **macOS ships bash 3.2** (`/bin/bash`, pre-GPLv3, no Apple updates) — not bash 4+. Two specific
  traps for sweep/batch shell scripts:
  - `"${array[@]}"` on an **empty** array throws `unbound variable` under `set -u` and silently
    aborts *that one command* (loop continues) — guard with `[ ${#array[@]} -gt 0 ]` before
    expanding, don't assume the expansion is a no-op.
  - `wait -n` doesn't exist before bash 4.3 — use a `sleep 1` polling loop against
    `jobs -rp | wc -l` for concurrency throttling instead.
- **A "clean exit code" is not evidence a sweep produced valid data.** Three real bugs this
  project hit (`sbm_pin`/`sbm_pout` missing from `tag()` causing folder collisions; the two bash
  issues above; a second hardcoded `feed_capacity` default in `Agent`'s constructor) all left the
  driving shell script's exit code at 0. Always check `logs/*.log` for `ABORT` lines and eyeball
  whether an aggregated result is *suspiciously exact* (e.g. bit-identical to another cell to 6
  decimal places) before trusting it.
- **Result folder collisions are silent by default (R26 abort), not loud.** If a sweep cell's
  metrics look identical to another cell's, check whether they actually landed in different
  folders (`tag()` doesn't encode every `ExperimentConfig` field — verify the specific parameter
  you're sweeping actually appears in the folder name, or two configs can collide unnoticed).

## Where things live

- `results/` — simulation output, gitignored.
