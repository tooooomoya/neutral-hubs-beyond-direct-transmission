"""
Stage 1 diagnostic for the feed-composition/structure mechanism investigation: why does
avgComfortRate flip sign between control and N_silent as p_u/vocal_comfort_radius vary, for the
far-extreme camp?

Reuses the exact 8 diagnostic cells from scripts/lmp_hubtier_specificity_check.py (p_u in
{0.05, 0.15} x vcr in {0.1, 0.15, 0.2, 0.3} -- the "ordinary crossover" row and the "anomalous
trough-then-overshoot" row), control vs N_silent only (no P_silent placebo -- specificity was already
confirmed once, this run is about mechanism not re-verification).

DIAGNOSTIC pass: only the first 5 of the original 15-seed block (8900000-8900004), a genuine subset
so it extends cleanly to the full 15 (Stage 2) if this looks promising. Reads the new feed-composition
(feedAuthorClass*CountMean_i, feedModCountMean_i, feedHubCountMean_i in results.csv) and echo-chamber
(degrees/echo_chamber_by_class_{step}.csv) instrumentation added this session, for the far-extreme
reader camps (bins 0 and 4) only, and compares control vs N_silent against the already-known
avgComfortRate d-value crossover pattern from the specificity check (recalled in main(), not
recomputed -- that data no longer exists on disk, results/ was cleaned up after its conclusions were
written into the report).

Usage: nohup python3 scripts/lmp_feedmech_stage1.py > logs/lmp_feedmech_stage1_stdout.log 2>&1 &
       (then disown)
"""
import concurrent.futures
import csv
import random
import subprocess
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
_JARS = list(ROOT.glob("lib/**/*.jar"))
LIBCP = ":".join(str(p) for p in _JARS) + (":" if _JARS else "") + str(ROOT / "bin")

N = 1000
FULL_STEPS = 40000
WINDOW = 2000
SEEDS = [8900000 + i for i in range(15)]  # Stage 2 (2026-08-06): full 15-seed block -- the first 5
# reuse Stage 1 v3's already-completed runs via is_done() below, only the new 10 launch fresh.
SUBDIR = "2026-08-06_lmp_feedmech_stage1"
# Moved out of the (now-deleted) oat_battery run dir into a stable shared location.
# All 15 seeds' t0 snapshots are present.
HK_CLASS_SELECT_SUBDIR = "_shared/class_selection_hk"

BASE_TOKENS = [
    "network=hk", "hk_m=15", "hk_a=4", "hk_pt=0.05",
    "max_follow=43.8638", "follow_prob=0.01", "feed_capacity=15",
    "access_prob=0.1", "p_u=0.05",
    "bc_init=0.800000", "bc_dec=0.500000", "bc_floor=0.2", "bc_recovery=0.0001", "bc_ceiling=1.0",
    "vocal_comfort_radius=0.1", "repost_prob=0.1", "relay_enabled=false",
    "out_of_bc_repost_prob=0.01", "rel_attrib=original",
    "stub_dist=uniform", "stub_min=0.82", "stub_max=0.82",
    "evict_beta=-1", "init_prune=trim",
    "initial_pp=0.25537", "max_pp=1.0", "min_pp=0.01", "pp_relax_eta=0.100000",
    "neutral_band=0.2",
    "extreme=none", "n_neutral=0", "alpha=0",
]

CELLS = [(pu, vcr) for pu in (0.05, 0.15) for vcr in (0.1, 0.15, 0.2, 0.3)]


def cell_name(pu, vcr):
    return f"pu{pu}_vcr{vcr}"


def result_dir(seed, subdir):
    cands = sorted(ROOT.glob(f"results/{subdir}/run_{seed}_*"))
    return cands[-1] if cands else None


def is_done(seed, subdir, steps):
    d = result_dir(seed, subdir)
    if d is None:
        return False
    rp = d / "metrics" / "results.csv"
    if not rp.exists():
        return False
    with open(rp, newline="") as f:
        rows = list(csv.DictReader(f))
    return bool(rows) and int(rows[-1]["step"]) >= steps


def launch(seed, steps, subdir, extra_tokens, log_gexf=False):
    tokens = [f"seed={seed}", f"steps={steps}", f"n={N}", "force=true",
              f"log_gexf={str(log_gexf).lower()}", f"results_subdir={subdir}"] \
        + BASE_TOKENS + extra_tokens
    logfile = ROOT / "logs" / f"feedmech_seed{seed}_{subdir.replace('/', '_')}.log"
    logfile.parent.mkdir(exist_ok=True)
    with open(logfile, "w") as lf:
        p = subprocess.Popen(["java", "-Xmx2g", "-cp", LIBCP, "dynamics.OpinionDynamics", *tokens],
                              stdout=lf, stderr=subprocess.STDOUT, cwd=ROOT)
    return p


def run_wave(jobs, max_workers=20):
    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as ex:
        futs = []
        for seed, steps, subdir, tokens in jobs:
            if is_done(seed, subdir, steps):
                print(f"  seed={seed} subdir={subdir}: reusing existing", flush=True)
                continue
            futs.append(ex.submit(lambda s=seed, st=steps, sd=subdir, tk=tokens: launch(s, st, sd, tk).wait()))
        for f in futs:
            f.result()


def derive_groups(seed):
    d = result_dir(seed, HK_CLASS_SELECT_SUBDIR)
    ranked = []
    with open(d / "degrees" / "agent_snapshot_t0.csv", newline="") as f:
        for r in csv.DictReader(f):
            ranked.append((int(r["followerCount"]), int(r["agentId"])))
    ranked.sort(reverse=True)
    top_ids = [aid for _, aid in ranked[:60]]
    rng = random.Random(seed)
    rng.shuffle(top_ids)
    sizes = [("E_minus", 10, -1.0), ("P_minus", 10, -0.4), ("M", 20, 0.0),
             ("P_plus", 10, 0.4), ("E_plus", 10, 1.0)]
    groups = {}
    i = 0
    for name, size, target in sizes:
        groups[name] = (top_ids[i:i + size], target)
        i += size
    return groups


def build_pin_token(groups):
    pairs = []
    for _, (ids, target) in groups.items():
        for aid in ids:
            pairs.append(f"{aid}:{target}")
    return "pin_opinion_ids=" + ",".join(pairs)


# ---- analysis ----

def trailing_window_means(seed, subdir, cols, checkpoint=FULL_STEPS, window=WINDOW):
    d = result_dir(seed, subdir)
    with open(d / "metrics" / "results.csv", newline="") as f:
        rows = list(csv.DictReader(f))
    lo = checkpoint - window
    sel = [r for r in rows if lo < int(r["step"]) <= checkpoint]
    return {c: np.mean([float(r[c]) for r in sel]) for c in cols}


def echo_chamber_final(seed, subdir, checkpoint=FULL_STEPS):
    d = result_dir(seed, subdir)
    p = d / "degrees" / f"echo_chamber_by_class_{checkpoint}.csv"
    with open(p, newline="") as f:
        rows = {int(r["class"]): r for r in csv.DictReader(f)}
    return rows


FEED_COLS = (
    ["feedModCountMean_0", "feedHubCountMean_0", "feedModCountMean_4", "feedHubCountMean_4"]
    + [f"feedAuthorClass{a}CountMean_0" for a in range(5)]
    + [f"feedAuthorClass{a}CountMean_4" for a in range(5)]
    + [f"feedComfortClass{a}CountMean_0" for a in range(5)]
    + [f"feedComfortClass{a}CountMean_4" for a in range(5)]
)


def pop_counts_nonhub_final(seed, subdir):
    """Population by class at the SINGLE final-step snapshot -- kept for comparison against the
    trailing-window-mean version below, not used as the actual per-capita denominator anymore: this
    single end-snapshot doesn't time-match a trailing-window numerator if the population is still
    shifting during that window."""
    d = result_dir(seed, subdir)
    counts = {0: 0, 1: 0, 2: 0, 3: 0, 4: 0}
    with open(d / "degrees" / "agent_snapshot_final.csv", newline="") as f:
        for r in csv.DictReader(f):
            if r["isTarget"] == "0":
                shifted = float(r["opinion"]) + 1.0
                counts[min(int(shifted / 0.4), 4)] += 1
    return counts


def pop_counts_nonhub_windowed(seed, subdir, checkpoint=FULL_STEPS, window=WINDOW):
    """2026-08-06: trailing-window MEAN of the live, per-step popCountByClass_i column (population
    count per opinion class, recomputed every step in AdminOptim.AdminFeedback) -- time-matched to
    the same window extreme_post_total() sums over, instead of a single end-of-run snapshot."""
    d = result_dir(seed, subdir)
    with open(d / "metrics" / "results.csv", newline="") as f:
        rows = list(csv.DictReader(f))
    lo = checkpoint - window
    sel = [r for r in rows if lo < int(r["step"]) <= checkpoint]
    return {c: np.mean([float(r[f"popCountByClass_{c}"]) for r in sel]) for c in range(5)}


def extreme_post_total(seed, subdir, checkpoint=FULL_STEPS, window=WINDOW):
    d = result_dir(seed, subdir)
    with open(d / "posts" / "post_result.csv", newline="") as f:
        rows = list(csv.DictReader(f))
    lo = checkpoint - window
    return sum(int(r["bin_0"]) + int(r["bin_4"]) for r in rows if lo < int(r["step"]) <= checkpoint)


def report_cell(pu, vcr):
    ctrl_subdir = f"{SUBDIR}/{cell_name(pu, vcr)}/control"
    msil_subdir = f"{SUBDIR}/{cell_name(pu, vcr)}/N_silent"

    ctrl_feed = [trailing_window_means(s, ctrl_subdir, FEED_COLS) for s in SEEDS]
    msil_feed = [trailing_window_means(s, msil_subdir, FEED_COLS) for s in SEEDS]

    print(f"\n--- p_u={pu} vcr={vcr} ---")
    print("  feed composition (trailing 2000-step mean, far-extreme reader bins 0/4), N_silent - control:")
    for col in FEED_COLS:
        c = np.array([r[col] for r in ctrl_feed])
        m = np.array([r[col] for r in msil_feed])
        diff = m - c
        print(f"    {col:36s} control={c.mean():+.4f}  N_silent={m.mean():+.4f}  diff={diff.mean():+.4f}")

    print("  within-comfort fraction of same-side pre-extreme content (feedComfortClass/feedAuthorClass),")
    print("  class 1 for bin-0 readers, class 3 for bin-4 readers:")
    for reader_cls, author_cls in ((0, 1), (4, 3)):
        tot_col = f"feedAuthorClass{author_cls}CountMean_{reader_cls}"
        com_col = f"feedComfortClass{author_cls}CountMean_{reader_cls}"
        c_tot = np.array([r[tot_col] for r in ctrl_feed]); c_com = np.array([r[com_col] for r in ctrl_feed])
        m_tot = np.array([r[tot_col] for r in msil_feed]); m_com = np.array([r[com_col] for r in msil_feed])
        c_ratio = c_com.sum() / c_tot.sum() if c_tot.sum() > 0 else float("nan")
        m_ratio = m_com.sum() / m_tot.sum() if m_tot.sum() > 0 else float("nan")
        print(f"    reader={reader_cls} author={author_cls}  control_ratio={c_ratio:.4f}  N_silent_ratio={m_ratio:.4f}  diff={m_ratio - c_ratio:+.4f}")

    print("  echo-chamber structure (final checkpoint, classes 0/4), N_silent - control:")
    for cls in (0, 4):
        ctrl_ec = [echo_chamber_final(s, ctrl_subdir)[cls] for s in SEEDS]
        msil_ec = [echo_chamber_final(s, msil_subdir)[cls] for s in SEEDS]
        for metric in ("nodeCount", "density", "avgClusteringCoeff", "triangleCount"):
            c = np.array([float(r[metric]) for r in ctrl_ec])
            m = np.array([float(r[metric]) for r in msil_ec])
            diff = m - c
            print(f"    class={cls} {metric:20s} control={c.mean():+.4f}  N_silent={m.mean():+.4f}  diff={diff.mean():+.4f}")

    print("  posting-rate d: final-snapshot-n (OLD, stale) vs trailing-window-mean-n (NEW, time-matched)")
    print("  vs FIXED control-denominator (tests whether 'moderate activates extreme camp' at narrow")
    print("  vcr survives a fair per-capita comparison):")
    ctrl_n_final = np.array([pop_counts_nonhub_final(s, ctrl_subdir)[0] + pop_counts_nonhub_final(s, ctrl_subdir)[4] for s in SEEDS])
    msil_n_final = np.array([pop_counts_nonhub_final(s, msil_subdir)[0] + pop_counts_nonhub_final(s, msil_subdir)[4] for s in SEEDS])
    ctrl_n_win = np.array([pop_counts_nonhub_windowed(s, ctrl_subdir)[0] + pop_counts_nonhub_windowed(s, ctrl_subdir)[4] for s in SEEDS])
    msil_n_win = np.array([pop_counts_nonhub_windowed(s, msil_subdir)[0] + pop_counts_nonhub_windowed(s, msil_subdir)[4] for s in SEEDS])
    ctrl_posts = np.array([extreme_post_total(s, ctrl_subdir) for s in SEEDS])
    msil_posts = np.array([extreme_post_total(s, msil_subdir) for s in SEEDS])

    ctrl_rate_final = ctrl_posts / ctrl_n_final / WINDOW
    msil_rate_final = msil_posts / msil_n_final / WINDOW
    ctrl_rate_win = ctrl_posts / ctrl_n_win / WINDOW
    msil_rate_win = msil_posts / msil_n_win / WINDOW
    msil_rate_fixed = msil_posts / ctrl_n_win / WINDOW  # windowed control n as the shared denominator

    def d(a, b):
        diff = a - b
        sd = diff.std(ddof=1)
        return diff.mean() / sd if sd > 0 else float("nan")

    print(f"    n (final snapshot):    control={ctrl_n_final.mean():.1f}  N_silent={msil_n_final.mean():.1f}")
    print(f"    n (trailing-window mean): control={ctrl_n_win.mean():.1f}  N_silent={msil_n_win.mean():.1f}")
    print(f"    final-snapshot-n (OLD):        d={d(msil_rate_final, ctrl_rate_final):+.3f}  (control_rate={ctrl_rate_final.mean():.5f}  N_silent_rate={msil_rate_final.mean():.5f})")
    print(f"    trailing-window-mean-n (NEW):  d={d(msil_rate_win, ctrl_rate_win):+.3f}  (control_rate={ctrl_rate_win.mean():.5f}  N_silent_rate={msil_rate_win.mean():.5f})")
    print(f"    fixed control-n (windowed):    d={d(msil_rate_fixed, ctrl_rate_win):+.3f}  (control_rate={ctrl_rate_win.mean():.5f}  N_silent_rate={msil_rate_fixed.mean():.5f})")

    print("  network dynamism: population-only follow/unfollow action counts (StepStats.followCount/")
    print("  unfollowCount, 2026-08-06 addition -- excludes hub agents' own actions, unlike the pre-")
    print("  existing all-agents 'follow'/'unfollow' columns), summed over the trailing 2000-step window:")
    ctrl_follow = np.array([window_sum(s, ctrl_subdir, "followCount") for s in SEEDS])
    msil_follow = np.array([window_sum(s, msil_subdir, "followCount") for s in SEEDS])
    ctrl_unfollow = np.array([window_sum(s, ctrl_subdir, "unfollowCount") for s in SEEDS])
    msil_unfollow = np.array([window_sum(s, msil_subdir, "unfollowCount") for s in SEEDS])
    print(f"    follow:   control={ctrl_follow.mean():.1f}  N_silent={msil_follow.mean():.1f}  diff={(msil_follow-ctrl_follow).mean():+.1f}  d={d(msil_follow, ctrl_follow):+.3f}")
    print(f"    unfollow: control={ctrl_unfollow.mean():.1f}  N_silent={msil_unfollow.mean():.1f}  diff={(msil_unfollow-ctrl_unfollow).mean():+.1f}  d={d(msil_unfollow, ctrl_unfollow):+.3f}")


def window_sum(seed, subdir, col, checkpoint=FULL_STEPS, window=WINDOW):
    d = result_dir(seed, subdir)
    with open(d / "metrics" / "results.csv", newline="") as f:
        rows = list(csv.DictReader(f))
    lo = checkpoint - window
    return sum(int(r[col]) for r in rows if lo < int(r["step"]) <= checkpoint)


def main():
    print("=== Deriving hub-tier groups (reused shared HK class-selection) ===", flush=True)
    groups_by_seed = {s: derive_groups(s) for s in SEEDS}

    print(f"\n=== Launching control + N_silent at {len(CELLS)} cells x {len(SEEDS)} seeds ===", flush=True)
    jobs = []
    for pu, vcr in CELLS:
        for s in SEEDS:
            groups = groups_by_seed[s]
            pin_token = build_pin_token(groups)
            base = [pin_token, f"p_u={pu}", f"vocal_comfort_radius={vcr}"]
            jobs.append((s, FULL_STEPS, f"{SUBDIR}/{cell_name(pu, vcr)}/control", base))
            m_ids = groups["M"][0]
            idstr = ",".join(str(a) for a in m_ids)
            msil_tokens = base + [f"silent_ids={idstr}"]
            jobs.append((s, FULL_STEPS, f"{SUBDIR}/{cell_name(pu, vcr)}/N_silent", msil_tokens))
    run_wave(jobs, max_workers=20)

    print("\n=== Recall: already-known avgComfortRate d (N_silent vs control), far-extreme camp,")
    print("    from the specificity check -- NOT recomputed here ===")
    print("    pu=0.05: [-0.495, +0.608, +0.882, +0.791]  (vcr = 0.1, 0.15, 0.2, 0.3)")
    print("    pu=0.15: [-0.743, -0.517, +0.390, +1.778]  (vcr = 0.1, 0.15, 0.2, 0.3)")

    for pu, vcr in CELLS:
        report_cell(pu, vcr)


if __name__ == "__main__":
    main()
