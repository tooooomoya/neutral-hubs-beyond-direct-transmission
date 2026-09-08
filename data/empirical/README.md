# Empirical reference data

## buntain_snegovaya_2025_activity_by_handle.csv

Individual-level (per-handle) posting activity, joined with self-reported ideology.
Source: Buntain & Snegovaya 2025 (PNAS Nexus, "Post-January 6 deplatforming..."), OSF
10.17605/OSF.IO/KQFM6, folder `03-its.models/_anon_data/`:
- `tw_handle_to_libcon.csv` (handle -> self-reported ideological lean, [-3,+3] / 7-point scale)
- `twitter.2020to2021.matched.counts.parquet` (per-user daily post counts, 2020-01-01 to
  2022-01-06, 737 days)

Retrieved 2026-08-01 via the OSF API (`api.osf.io/v2/nodes/kqfm6/files/osfstorage/...`) rather
than the JS-rendered OSF web UI. This CSV is a **summary** of the joined source (per-handle
total post count over the full window, not the daily time series) -- small enough to commit
directly; the daily granularity isn't needed for the calibration use case below and isn't
included here.

Columns: `handle`, `party_id`, `ideological_lean` (numeric, 0-6), `party_id_label`,
`ideological_lean_label` (the 7 bins, "1 Very liberal" .. "7 Very conservative"), `total_posts`
(sum over the full window), `n_days_observed` (737, constant).

**Validation**: summing per-handle and grouping by `ideological_lean_label` reproduces
`Const.java`'s `EMPIRICAL_OPINION_BIN_COUNTS` (bin populations) and `EMPIRICAL_POST_BIN_WEIGHTS`
(bin means) exactly (to rounding) -- confirms this is the same source those constants were
computed from, just not previously exposed at the individual level.

**Use**: this CSV is the source of `Const.java`'s `EMPIRICAL_OPINION_BIN_COUNTS` and
`EMPIRICAL_POST_BIN_WEIGHTS` (the `init_dist=empirical` initial-opinion sampler and the 7-bin
posting-activity calibration target). The calibration script that computed the current
model-vs-empirical activity-heterogeneity comparison (CV ~0.12-0.24 in the model vs. ~1.4-3.2 by
bin in this data) was a one-off analysis from an earlier research phase and is no longer in the
repository; this file remains as the primary source those constants were derived from.
