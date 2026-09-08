# Neutral hubs beyond direct transmission

Agent-based model and reproduction code for *"Neutral hubs beyond direct transmission"*
(Complex Networks 2026): does a neutral, opinion-inactive but highly reachable hub shape
opinion-driven vocalization in the population it reaches, beyond simply relaying content?

## Build & run

The only dependency is the Java standard library plus the **gephi toolkit**, used for optional
GEXF graph export. Fetch it once from Maven Central (verified by checksum, not vendored in git):

```bash
./lib/fetch-deps.sh
```

```bash
find src -name '*.java' > .javafiles.txt
LIBCP="$(find lib -name '*.jar' | tr '\n' ':')"
javac -cp "$LIBCP" -d bin @.javafiles.txt && rm -f .javafiles.txt
java -cp "${LIBCP}bin" dynamics.OpinionDynamics seed=0
```

This runs one simulation with default parameters and writes its output under
`results/run_0_/` (created automatically). `log_gexf=false` skips GEXF export entirely, useful
for large sweeps.

### Configuration

Every run parameter is a space-separated `key=value` token; unknown keys are silently ignored.
Each non-default value is appended to the result-folder name. All runtime parameters live on the
immutable `experiment.SimParams`, built once per run from `experiment.ExperimentConfig`. The
defaults in `ExperimentConfig` are the configuration of the reported experiments unless a run
states otherwise; see that file for every field and its meaning, and the paper's Model and
Experimental Design sections for the corresponding notation.

```bash
# a parameter-sweep point
java -cp "${LIBCP}bin" dynamics.OpinionDynamics seed=3 p_u=0.11 vocal_comfort_radius=0.17

# pin a hub pool by agent id and silence a subset of it (the paper's Neutral Hub Intervention)
java -cp "${LIBCP}bin" dynamics.OpinionDynamics seed=0 \
    pin_opinion_ids=1:1.0,2:-1.0,3:0.0 silent_ids=3

# smaller/faster instance, no gephi
java -cp "${LIBCP}bin" dynamics.OpinionDynamics seed=0 n=500 steps=5000 log_gexf=false
```

Frequently used keys: `seed`, `net_seed`, `n`, `steps`, `network`
(`hk|dcsbm|ba|ws|dms|cnn|lfr|random|read`), `p_u`, `vocal_comfort_radius`, `bc_dec`,
`bc_recovery`, `bc_floor`/`bc_ceiling`/`bc_init`, `repost_prob`, `out_of_bc_repost_prob`,
`stub_dist`/`stub_min`/`stub_max`, `evict_beta`, `pin_opinion_ids`, `silent_ids`, `access_prob`,
`log_gexf`, `log_repost_cascade`, `assert_int`. See `ExperimentConfig.fromArgs` for the full list.

### Tests

```bash
java -cp "${LIBCP}bin" test.ModelTests   # behavioral/logic assertions; exits non-zero on failure
java -cp "${LIBCP}bin" test.ParamCheck   # ExperimentConfig defaults vs. the paper's stated values
```

## Reproducing the paper's results

`scripts/` holds the pipeline that reproduces the Experimental Results section
(`lmp_feedmech_stage1.py` -> `lmp_feedmech_structural_9b.py` -> `lmp_feedmech_chamber_gate.py`
-> `lmp_feedmech_grid4x4.py` -> `lmp_feedmech_paper_figures.py`); see `OPERATIONS.md` for what
each stage does and the exact factor levels it runs.

## Analysis / dashboard

`dashboard/` is a live web dashboard (`python dashboard/dashboard.py --serve`) for inspecting a
running or completed batch of simulations: per-metric time series, a 2-D outcome plane, opinion
distribution over time, and network-snapshot/structure views. See `OPERATIONS.md` for usage and
`dashboard/README.md` for its design.

## Folder structure

- `src/` — the Java model (agents, network generators, dynamics, analysis, CSV output)
- `lib/` — third-party dependencies (gephi toolkit), fetched by `lib/fetch-deps.sh`, not vendored
- `bin/` — compiled classes (gitignored, produced by the build command above)
- `scripts/` — the paper-reproduction pipeline (Python)
- `dashboard/` — the live analysis dashboard
- `data/empirical/` — the survey data the model's empirical calibration targets are drawn from

## Python environment

```bash
pip install -r requirements.txt
```

Needed for `scripts/` and `dashboard/`, not for the Java model itself.

## License

MIT — see [LICENSE](LICENSE).
