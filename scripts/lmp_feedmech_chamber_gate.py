"""
2026-08-15 -- "is the near-extreme camp inside the extreme camp's echo chamber?" measurement.

MOTIVATION (user, 2026-08-15). The trajectory result that a HIGHER unfollow probability produces a
WIDER bounded confidence (self-purification: p_u removes out-of-tolerance sources faster, raising the
in-tolerance share of what remains, which raises bc's relaxation target) is, in the user's reading,
just echo-chamber formation seen from another angle. The paper already argues that vcr and p_u both
gate HOW FAR OUT still counts as homophilic -- vcr directly, in opinion space; p_u indirectly, by
shaping bc. The prediction that follows: when the near-extreme (NE) camp falls INSIDE the extreme (E)
camp's homophily gate, NE-authored content flows into E readers' feeds; when it falls outside, it does
not.

THE CIRCULARITY CONSTRAINT (the main design problem). If "same echo chamber" is operationalised as
"E agents follow NE agents a lot," then "NE->E influx rises" is true by definition, because in this
configuration (alpha=0, chronological-only) influx arrives ONLY through follow edges. Every chamber
measure below is therefore built to be independent of the E->NE edges whose consequence we are trying
to explain:
  - Layer 1 uses opinions and bc/vcr only -- it never looks at the graph.
  - Layer 2's overlap measure is also reported in a "third-party only" variant that excludes E- and
    NE-camp members as sources entirely, so shared-chamber membership is established purely by shared
    attention to OTHER authors.
  - Layer 2's Louvain co-membership does use the graph including E<->NE edges (there is no way around
    that for a community measure) and is therefore the weakest of the three on circularity grounds --
    it is included because it is the measure network-science reviewers will expect, not because it is
    the cleanest test. Read it alongside, not instead of, the other two.

WHAT IS MEASURED, per (p_u, vcr, arm) cell and per checkpoint, over same-sign camp pairs only
(E-=class 0 with NE-=class 1; E+=class 4 with NE+=class 3), non-hub agents:

  LAYER 1 -- gate width in opinion space (graph-independent)
    bc_inclusion   : mean over E agents of the fraction of same-sign NE agents j with |o_i-o_j| < bc_i
    vcr_inclusion  : same with the global vcr in place of bc_i
                     -> these operationalise "how far out still counts as homophilic," and put p_u
                        (acting through bc) and vcr on one common scale.

  LAYER 2 -- realised chamber structure (graph)
    src_overlap_all   : cosine similarity between the E camp's aggregate followee-count vector and the
                        NE camp's, over all authors
    src_overlap_third : same, restricted to authors who are in NEITHER camp (circularity-safe: two
                        camps can share an information environment without following each other)
    louvain_comembership : fraction of same-sign (E,NE) agent pairs assigned to the same community
    louvain_Q            : modularity of that partition

  LAYER 3 -- opinion-distribution shape (is there a valley between the camps at all?)
    ene_dist_mean : mean same-sign |o_i - o_j| over (E,NE) pairs
    ene_dist_p10  : 10th percentile of that distance -- the closest pairs are the ones bc can actually
                    reach, so this is the quantity bc must exceed for inclusion to occur
    valley_frac   : share of non-hub agents with |o| in [0.5,0.7], i.e. sitting in the band around the
                    E/NE class boundary -- low means the two camps are genuinely separated rather than
                    being two labels on one continuous cluster
    e_abs_mean / ne_abs_mean : mean |o| of each camp (do the camps move apart, or does the gate move?)

Reads the existing results/2026-08-14_lmp_feedmech_structural_9b/ grid; NO new simulation runs.
Per-run results cached to chamber_gate_metrics.json inside each run dir.

Usage: nohup python3 -u scripts/lmp_feedmech_chamber_gate.py > logs/chamber_gate.log 2>&1 & disown
"""
import json
import xml.etree.ElementTree as ET

import networkx as nx
import numpy as np

from lmp_feedmech_stage1 import SEEDS, result_dir
from lmp_feedmech_structural_9b import SUBDIR, CELLS, cell_name, GEXF_NS

ARMS = ("control", "N_silent")
CHECKPOINTS = (0, 5000, 10000, 20000, 40000)
# same-sign camp pairing: (extreme class, near-extreme class)
SIGN_PAIRS = ((0, 1), (4, 3))
VALLEY_BAND = (0.5, 0.7)  # around the |o|=0.6 boundary between the E and NE bins


def parse_nodes_edges(path):
    root = ET.parse(path).getroot()
    graph = root.find(f"{GEXF_NS}graph")
    cls, tgt, op, bc = {}, {}, {}, {}
    for node in graph.find(f"{GEXF_NS}nodes"):
        nid = node.get("id")
        c, t, o, b = None, False, np.nan, np.nan
        av_parent = node.find(f"{GEXF_NS}attvalues")
        if av_parent is not None:
            for av in av_parent:
                k, v = av.get("for"), av.get("value")
                if k == "opinionclass":
                    c = int(v)
                elif k == "target":
                    t = (v == "true")
                elif k == "opinion":
                    o = float(v)
                elif k == "boundedconfidence":
                    b = float(v)
        cls[nid], tgt[nid], op[nid], bc[nid] = c, t, o, b
    edges = [(e.get("source"), e.get("target")) for e in graph.find(f"{GEXF_NS}edges")]
    return cls, tgt, op, bc, edges


def gexf_path(seed, subdir, step):
    d = result_dir(seed, subdir)
    hits = list((d / "GEXF").glob(f"*/step_{step}.gexf"))
    return hits[0] if hits else None


def compute(path, vcr):
    cls, tgt, op, bc, edges = parse_nodes_edges(path)
    pop = [n for n in cls if not tgt[n]]  # non-hub agents only
    if not pop:
        return None

    by_class = {c: [n for n in pop if cls[n] == c] for c in range(5)}

    # ---------- LAYER 1: gate width in opinion space (graph never consulted) ----------
    bc_incl, vcr_incl, d_means, d_p10s, weights = [], [], [], [], []
    for e_cls, ne_cls in SIGN_PAIRS:
        E, NE = by_class[e_cls], by_class[ne_cls]
        if not E or not NE:
            continue
        oE = np.array([op[n] for n in E])
        bE = np.array([bc[n] for n in E])
        oNE = np.array([op[n] for n in NE])
        dist = np.abs(oE[:, None] - oNE[None, :])          # (|E|, |NE|) same-sign distances
        bc_incl.append((dist < bE[:, None]).mean(axis=1).mean())
        vcr_incl.append((dist < vcr).mean(axis=1).mean())
        d_means.append(dist.mean())
        d_p10s.append(np.percentile(dist, 10))
        weights.append(len(E) * len(NE))
    if not weights:
        return None
    w = np.array(weights, dtype=float)
    w /= w.sum()

    # ---------- LAYER 2: realised chamber structure ----------
    e_all = set(by_class[0]) | set(by_class[4])
    ne_all = set(by_class[1]) | set(by_class[3])

    authors = sorted({t for _, t in edges})
    a_index = {a: i for i, a in enumerate(authors)}
    vE = np.zeros(len(authors))
    vNE = np.zeros(len(authors))
    for s, t in edges:
        if s in e_all:
            vE[a_index[t]] += 1
        elif s in ne_all:
            vNE[a_index[t]] += 1

    def cosine(a, b):
        na, nb = np.linalg.norm(a), np.linalg.norm(b)
        return float(a @ b / (na * nb)) if na > 0 and nb > 0 else float("nan")

    third = np.array([(a not in e_all and a not in ne_all) for a in authors])
    src_overlap_all = cosine(vE, vNE)
    src_overlap_third = cosine(vE * third, vNE * third)

    G = nx.Graph()
    G.add_nodes_from(cls.keys())
    G.add_edges_from(edges)
    try:
        comms = nx.community.louvain_communities(G, seed=0)
        comm_of = {n: i for i, c in enumerate(comms) for n in c}
        louvain_Q = float(nx.community.modularity(G, comms))
        co, tot = 0, 0
        for e_cls, ne_cls in SIGN_PAIRS:
            for i in by_class[e_cls]:
                ci = comm_of.get(i)
                for j in by_class[ne_cls]:
                    tot += 1
                    if ci is not None and ci == comm_of.get(j):
                        co += 1
        louvain_comembership = co / tot if tot else float("nan")
    except Exception:
        louvain_Q, louvain_comembership = float("nan"), float("nan")

    # ---------- LAYER 3: opinion-distribution shape ----------
    abs_all = np.array([abs(op[n]) for n in pop])
    valley_frac = float(((abs_all >= VALLEY_BAND[0]) & (abs_all <= VALLEY_BAND[1])).mean())
    e_abs = np.array([abs(op[n]) for n in e_all]) if e_all else np.array([np.nan])
    ne_abs = np.array([abs(op[n]) for n in ne_all]) if ne_all else np.array([np.nan])

    return {
        "bc_inclusion": float(np.dot(bc_incl, w)),
        "vcr_inclusion": float(np.dot(vcr_incl, w)),
        "src_overlap_all": src_overlap_all,
        "src_overlap_third": src_overlap_third,
        "louvain_comembership": louvain_comembership,
        "louvain_Q": louvain_Q,
        "ene_dist_mean": float(np.dot(d_means, w)),
        "ene_dist_p10": float(np.dot(d_p10s, w)),
        "valley_frac": valley_frac,
        "e_abs_mean": float(np.nanmean(e_abs)),
        "ne_abs_mean": float(np.nanmean(ne_abs)),
    }


def cached(seed, subdir, vcr):
    d = result_dir(seed, subdir)
    cp = d / "chamber_gate_metrics.json"
    if cp.exists():
        return json.loads(cp.read_text())
    out = {}
    for step in CHECKPOINTS:
        p = gexf_path(seed, subdir, step)
        out[str(step)] = compute(p, vcr) if p is not None else None
    cp.write_text(json.dumps(out))
    return out


KEYS = ("bc_inclusion", "vcr_inclusion", "src_overlap_all", "src_overlap_third",
        "louvain_comembership", "louvain_Q", "ene_dist_mean", "ene_dist_p10",
        "valley_frac", "e_abs_mean", "ne_abs_mean")

LABELS = {
    "bc_inclusion": "L1 bc-gate: share of same-sign NE camp inside an E agent's bc window",
    "vcr_inclusion": "L1 vcr-gate: share of same-sign NE camp inside vcr of an E agent",
    "src_overlap_all": "L2 shared sources, all authors (cosine of E vs NE followee vectors)",
    "src_overlap_third": "L2 shared sources, THIRD PARTIES ONLY (circularity-safe)",
    "louvain_comembership": "L2 Louvain: share of same-sign (E,NE) pairs in one community",
    "louvain_Q": "L2 Louvain modularity Q",
    "ene_dist_mean": "L3 mean same-sign |o_E - o_NE|",
    "ene_dist_p10": "L3 10th pct of same-sign |o_E - o_NE| (what bc must exceed)",
    "valley_frac": "L3 share of agents with |o| in [0.5,0.7] (the E/NE boundary band)",
    "e_abs_mean": "L3 mean |o| of the extreme camp",
    "ne_abs_mean": "L3 mean |o| of the near-extreme camp",
}


def main():
    data = {}
    for pu, vcr in CELLS:
        for arm in ARMS:
            subdir = f"{SUBDIR}/{cell_name(pu, vcr)}/{arm}"
            print(f"  computing {subdir} ...", flush=True)
            per_seed = [cached(s, subdir, vcr) for s in SEEDS]
            for step in CHECKPOINTS:
                vals = [ps[str(step)] for ps in per_seed if ps.get(str(step))]
                if vals:
                    data[(pu, vcr, arm, step)] = {
                        k: float(np.nanmean([v[k] for v in vals])) for k in KEYS
                    }

    print("\nSame-sign camp pairs only (E-/NE- and E+/NE+), non-hub agents, mean over 15 seeds.\n")
    for key in KEYS:
        print("=" * 108)
        print(f"{key}  --  {LABELS[key]}")
        print("=" * 108)
        for vcr in (0.1, 0.15, 0.2):
            print(f"  vcr={vcr}")
            print(f"    {'step':>12s}" + "".join(f"{s:>12d}" for s in CHECKPOINTS))
            for pu in (0.05, 0.15):
                for arm in ARMS:
                    vals = [data.get((pu, vcr, arm, s), {}).get(key, np.nan) for s in CHECKPOINTS]
                    print(f"    p_u={pu} {arm:9s}" + "".join(f"{v:>12.4f}" for v in vals))
                diffs = [data.get((pu, vcr, 'N_silent', s), {}).get(key, np.nan) -
                         data.get((pu, vcr, 'control', s), {}).get(key, np.nan) for s in CHECKPOINTS]
                print(f"    p_u={pu} {'DIFF':9s}" + "".join(f"{v:>+12.4f}" for v in diffs))
            print()


if __name__ == "__main__":
    main()
