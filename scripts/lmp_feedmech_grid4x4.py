"""
Resolution upgrade of the p_u x vcr grid from 2x3 to 4x4, following mechanism work establishing
that (a) bc_inclusion, not raw follow-density/betweenness, is the right structural-gate measure, and
(b) betweenness centrality is not worth computing at all (algorithmic feed is dead, alpha=0). This
script therefore skips the expensive betweenness pass entirely and only computes bc_inclusion/
vcr_inclusion (via lmp_feedmech_chamber_gate.compute, ~0.15s/snapshot) plus the headline posting-rate
outcome.

Grid:
  p_u in {0.05, 0.08, 0.11, 0.15}   (0.05, 0.15 already simulated under 9b's SUBDIR)
  vcr in {0.10, 0.13, 0.17, 0.20}   (0.10, 0.20 already simulated under 9b's SUBDIR)
Reuses lmp_feedmech_structural_9b.py's exact SUBDIR/cell_name convention so the 4 already-completed
(p_u, vcr) corners (0.05/0.15 x 0.10/0.20 = 4 cells x 2 arms x 15 seeds = 120 runs) are picked up by
is_done() and NOT rerun; only the remaining 360 runs (of 480 total = 4x4x2x15) launch fresh.

Usage: nohup python3 -u scripts/lmp_feedmech_grid4x4.py > logs/lmp_feedmech_grid4x4_stdout.log 2>&1 &
       (then disown)
"""
import numpy as np

from lmp_feedmech_stage1 import (
    SEEDS, FULL_STEPS, WINDOW, derive_groups, build_pin_token, extreme_post_total,
    pop_counts_nonhub_windowed,
)
from lmp_feedmech_structural_9b import SUBDIR, cell_name, run_wave_gexf
from lmp_feedmech_chamber_gate import compute as chamber_compute, gexf_path

PU_LEVELS = (0.05, 0.08, 0.11, 0.15)
VCR_LEVELS = (0.10, 0.13, 0.17, 0.20)
CELLS = [(pu, vcr) for pu in PU_LEVELS for vcr in VCR_LEVELS]
ARMS = ("control", "N_silent")


def outcome_for(seed, c_sub, m_sub):
    c_n = pop_counts_nonhub_windowed(seed, c_sub)
    m_n = pop_counts_nonhub_windowed(seed, m_sub)
    c_rate = extreme_post_total(seed, c_sub) / (c_n[0] + c_n[4]) / WINDOW
    m_rate = extreme_post_total(seed, m_sub) / (m_n[0] + m_n[4]) / WINDOW
    return m_rate - c_rate


def gate_metrics(seed, subdir, vcr):
    p = gexf_path(seed, subdir, FULL_STEPS)
    if p is None:
        return None
    return chamber_compute(p, vcr)


def main():
    print("=== Deriving hub-tier groups (reused shared HK class-selection) ===", flush=True)
    groups_by_seed = {s: derive_groups(s) for s in SEEDS}

    print(f"\n=== Launching {len(CELLS)} cells x {len(SEEDS)} seeds "
          f"(already-completed corners will be skipped by is_done) ===", flush=True)
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
    run_wave_gexf(jobs, max_workers=20)

    print("\n=== outcome heatmap (N_silent - control extreme-camp posting rate) ===")
    print(f"{'':10s}" + "".join(f"vcr={v:<9}" for v in VCR_LEVELS))
    outcome_grid = {}
    for pu in PU_LEVELS:
        vals = []
        for vcr in VCR_LEVELS:
            c_sub = f"{SUBDIR}/{cell_name(pu, vcr)}/control"
            m_sub = f"{SUBDIR}/{cell_name(pu, vcr)}/N_silent"
            v = np.mean([outcome_for(s, c_sub, m_sub) for s in SEEDS])
            outcome_grid[(pu, vcr)] = v
            vals.append(v)
        print(f"p_u={pu:<6}" + "".join(f"{v:<+13.5f}" for v in vals))

    print("\n=== bc_inclusion (structural gate) ===")
    print(f"{'':10s}" + "".join(f"vcr={v:<9}" for v in VCR_LEVELS))
    for arm in ARMS:
        print(f"  arm={arm}")
        for pu in PU_LEVELS:
            vals = []
            for vcr in VCR_LEVELS:
                sub = f"{SUBDIR}/{cell_name(pu, vcr)}/{arm}"
                ms = [gate_metrics(s, sub, vcr) for s in SEEDS]
                ms = [m for m in ms if m]
                vals.append(np.mean([m["bc_inclusion"] for m in ms]))
            print(f"  p_u={pu:<4}" + "".join(f"{v:<13.4f}" for v in vals))

    print("\n=== vcr_inclusion (comfort gate) ===")
    print(f"{'':10s}" + "".join(f"vcr={v:<9}" for v in VCR_LEVELS))
    for arm in ARMS:
        print(f"  arm={arm}")
        for pu in PU_LEVELS:
            vals = []
            for vcr in VCR_LEVELS:
                sub = f"{SUBDIR}/{cell_name(pu, vcr)}/{arm}"
                ms = [gate_metrics(s, sub, vcr) for s in SEEDS]
                ms = [m for m in ms if m]
                vals.append(np.mean([m["vcr_inclusion"] for m in ms]))
            print(f"  p_u={pu:<4}" + "".join(f"{v:<13.4f}" for v in vals))

    print("\n=== 4x4 GRID RUN COMPLETE ===", flush=True)


if __name__ == "__main__":
    main()
