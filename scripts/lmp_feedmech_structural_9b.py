"""
Re-run E<->NE structural connectivity with a corrected p_u-contrast estimator, plus new NE betweenness-
centrality instrumentation, on the paper's FINAL grid (vcr in {0.1, 0.15, 0.2} -- 0.3 dropped).
Supersedes lmp_feedmech_stage1_gexf.py's report_cell(), whose p_u contrast was a ratio of
vcr-averaged densities (mean_v[density(0.15,v)] / mean_v[density(0.05,v)]) -- methodologically
weaker than pairing within each vcr cell first (a ratio-of-means can hide a shape difference, not
just a level difference, between the two p_u rows -- a Simpson's-paradox-shaped risk).

Reuses lmp_feedmech_stage1.py's exact 15-seed block (8900000-8900014) and Gaussian/M=20 hub-tier
construction (derive_groups, already materialized under results/_shared/class_selection_hk/) so the
GEXF snapshots line up with every other number in this project's grid. Writes to a fresh results
subdir (GEXF export was not requested/kept for the original stage1 4-level run, and that data was
already cleaned up per the 2026-08-07 write-up -- see handoff section 9's "Complication discovered").

Usage: nohup python3 scripts/lmp_feedmech_structural_9b.py > logs/lmp_feedmech_structural_9b_stdout.log 2>&1 &
       (then disown)
"""
import concurrent.futures
import glob
import json
import xml.etree.ElementTree as ET

import networkx as nx
import numpy as np

from lmp_feedmech_stage1 import (
    SEEDS, FULL_STEPS, result_dir, is_done, launch, derive_groups, build_pin_token,
)

SUBDIR = "2026-08-14_lmp_feedmech_structural_9b"
CELLS = [(pu, vcr) for pu in (0.05, 0.15) for vcr in (0.1, 0.15, 0.2)]  # vcr=0.3 dropped, section 8
ARMS = ("control", "N_silent")
GEXF_NS = "{http://gexf.net/1.3}"


def cell_name(pu, vcr):
    return f"pu{pu}_vcr{vcr}"


def run_wave_gexf(jobs, max_workers=20):
    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as ex:
        futs = []
        for seed, steps, subdir, tokens in jobs:
            if is_done(seed, subdir, steps):
                print(f"  seed={seed} subdir={subdir}: reusing existing", flush=True)
                continue
            futs.append(ex.submit(lambda s=seed, st=steps, sd=subdir, tk=tokens:
                                   launch(s, st, sd, tk, log_gexf=True).wait()))
        for f in futs:
            f.result()


def parse_gexf(path):
    """Returns (opinionclass_by_id: dict[str,int], is_target_by_id: dict[str,bool],
    edges: list[(source_id, target_id)]) for one GEXF snapshot."""
    tree = ET.parse(path)
    root = tree.getroot()
    graph = root.find(f"{GEXF_NS}graph")
    opinionclass, is_target = {}, {}
    for node in graph.find(f"{GEXF_NS}nodes"):
        nid = node.get("id")
        cls, tgt = None, False
        attvalues = node.find(f"{GEXF_NS}attvalues")
        if attvalues is not None:
            for av in attvalues:
                if av.get("for") == "opinionclass":
                    cls = int(av.get("value"))
                elif av.get("for") == "target":
                    tgt = av.get("value") == "true"
        opinionclass[nid] = cls
        is_target[nid] = tgt
    edges = [(e.get("source"), e.get("target")) for e in graph.find(f"{GEXF_NS}edges")]
    return opinionclass, is_target, edges


def final_gexf_path(seed, subdir, checkpoint=FULL_STEPS):
    d = result_dir(seed, subdir)
    cands = glob.glob(str(d / "GEXF" / "*" / f"step_{checkpoint}.gexf"))
    if not cands:
        raise FileNotFoundError(f"no step_{checkpoint}.gexf under {d}/GEXF")
    return cands[0]


def cross_class_density(cls, tgt, edges, class_a, class_b):
    """Directed density of edges FROM class_a members TO class_b members (source follows target),
    non-hub (target attr False) only."""
    A = {n for n, c in cls.items() if c == class_a and not tgt[n]}
    B = {n for n, c in cls.items() if c == class_b and not tgt[n]}
    if not A or not B:
        return float("nan"), len(A), len(B), 0
    count = sum(1 for s, t in edges if s in A and t in B)
    return count / (len(A) * len(B)), len(A), len(B), count


def ne_density(cls, tgt, edges):
    """E<->NE directed density, both sides (E- ->NE- and E+ ->NE+) averaged -- matches the
    cross_class_density() definition already used across this project (classes: 0=E-, 1=NE-,
    3=NE+, 4=E+)."""
    d_minus, *_ = cross_class_density(cls, tgt, edges, 0, 1)
    d_plus, *_ = cross_class_density(cls, tgt, edges, 4, 3)
    return np.nanmean([d_minus, d_plus])


def betweenness_variants(cls, tgt, edges):
    """Returns (directed_bc, undirected_bc, reciprocated_bc) dicts id->betweenness, computed on the
    FULL graph (all nodes/edges, hubs included -- hubs are structurally relevant intermediaries), each
    normalized (networkx default)."""
    nodes = list(cls.keys())
    G_dir = nx.DiGraph()
    G_dir.add_nodes_from(nodes)
    G_dir.add_edges_from(edges)
    bc_dir = nx.betweenness_centrality(G_dir, normalized=True)

    G_undir = nx.Graph()
    G_undir.add_nodes_from(nodes)
    G_undir.add_edges_from(edges)  # symmetrized: either direction creates one undirected edge
    bc_undir = nx.betweenness_centrality(G_undir, normalized=True)

    edge_set = set(edges)
    recip_edges = [(s, t) for s, t in edges if (t, s) in edge_set]
    G_recip = nx.Graph()
    G_recip.add_nodes_from(nodes)
    G_recip.add_edges_from(recip_edges)
    bc_recip = nx.betweenness_centrality(G_recip, normalized=True)

    return bc_dir, bc_undir, bc_recip


def ne_mean_betweenness(cls, tgt, bc):
    """Mean betweenness over NE agents (classes 1 and 3), non-hub only."""
    vals = [bc[n] for n, c in cls.items() if c in (1, 3) and not tgt[n]]
    return float(np.mean(vals)) if vals else float("nan")


def per_seed_metrics(seed, subdir):
    """Betweenness centrality (3 variants) is expensive (~10s/seed) -- cache to a JSON file next to
    the run's own results.csv so repeat analysis passes (e.g. lmp_feedmech_structural_9c.py) don't
    recompute it from the GEXF snapshot."""
    d = result_dir(seed, subdir)
    cache_path = d / "structural_metrics_9b.json"
    if cache_path.exists():
        with open(cache_path) as f:
            return json.load(f)

    path = final_gexf_path(seed, subdir)
    cls, tgt, edges = parse_gexf(path)
    density = ne_density(cls, tgt, edges)
    bc_dir, bc_undir, bc_recip = betweenness_variants(cls, tgt, edges)
    metrics = {
        "density": density,
        "bc_dir": ne_mean_betweenness(cls, tgt, bc_dir),
        "bc_undir": ne_mean_betweenness(cls, tgt, bc_undir),
        "bc_recip": ne_mean_betweenness(cls, tgt, bc_recip),
    }
    with open(cache_path, "w") as f:
        json.dump(metrics, f)
    return metrics


def report():
    print("\n=== 9b: E<->NE density (corrected paired p_u estimator) + NE betweenness (3 variants) ===")
    print("Per-cell values are means over the 15-seed block; delta_density/delta_bc are paired within")
    print("each vcr (p_u=0.15 minus p_u=0.05), computed separately per arm, per handoff section 9b.\n")

    cell_metrics = {}  # (pu, vcr, arm) -> dict of seed-lists
    for pu, vcr in CELLS:
        for arm in ARMS:
            subdir = f"{SUBDIR}/{cell_name(pu, vcr)}/{arm}"
            rows = [per_seed_metrics(s, subdir) for s in SEEDS]
            cell_metrics[(pu, vcr, arm)] = {
                k: np.array([r[k] for r in rows]) for k in rows[0]
            }

    for arm in ARMS:
        print(f"--- arm={arm} ---")
        for metric in ("density", "bc_dir", "bc_undir", "bc_recip"):
            print(f"  metric={metric}")
            deltas = []
            for vcr in (0.1, 0.15, 0.2):
                lo = cell_metrics[(0.05, vcr, arm)][metric].mean()
                hi = cell_metrics[(0.15, vcr, arm)][metric].mean()
                delta = hi - lo
                rel = delta / lo if lo else float("nan")
                deltas.append(delta)
                print(f"    vcr={vcr:<5} pu0.05={lo:.6f}  pu0.15={hi:.6f}  "
                      f"delta={delta:+.6f}  rel={rel:+.2%}")
            deltas = np.array(deltas)
            print(f"    mean_delta={deltas.mean():+.6f}  spread(min,max)=({deltas.min():+.6f},"
                  f" {deltas.max():+.6f})  std={deltas.std(ddof=1):.6f}\n")

    return cell_metrics


def main():
    print("=== Deriving hub-tier groups (reused shared HK class-selection) ===", flush=True)
    groups_by_seed = {s: derive_groups(s) for s in SEEDS}

    print(f"\n=== Launching control + N_silent (log_gexf=true) at {len(CELLS)} cells x "
          f"{len(SEEDS)} seeds ===", flush=True)
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

    report()
    print("=== 9B RUN COMPLETE ===", flush=True)


if __name__ == "__main__":
    main()
