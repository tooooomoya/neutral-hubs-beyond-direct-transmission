"""
Generates the three Results-section heatmap figures for the paper
(outcome-heatmap: per-camp posting-rate difference; mod-extreme-influx: moderate-camp->extreme content
as % of an extreme agent's actual (realized) feed size S_fd by condition -- NOT % of the fixed
capacity \maxfeed=15, since the extreme camp's feed is far from full (S_fd~2-3);
control-minus-msilent: N-silent-control difference of that share), from the already-completed 4x4
grid under results/2026-08-14_lmp_feedmech_structural_9b/ (scripts/lmp_feedmech_grid4x4.py). No new
simulation.

Camp terminology: moderate/near-extreme/extreme -> neutral/moderate/extreme; the former "NE"
(near-extreme) abbreviation is now "moderate" throughout (variable/file names use "mod" rather than
"m" to avoid colliding with this project's "N-silent"/N_silent condition-name, itself renamed from
"M-silent"/M_silent for the same reason -- the old "M" stood for the now-renamed neutral hub).

Usage: python3 scripts/lmp_feedmech_paper_figures.py
"""
import numpy as np
import pandas as pd
from scipy import stats
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import matplotlib.colors as mcolors

from lmp_feedmech_stage1 import SEEDS, WINDOW, FULL_STEPS, ROOT, result_dir
from lmp_feedmech_structural_9b import SUBDIR, cell_name
from lmp_feedmech_grid4x4 import PU_LEVELS, VCR_LEVELS, CELLS

OUT_DIR = str(ROOT / "figures")

CAMPS = {"neutral": (2,), "moderate": (1, 3), "extreme": (0, 4)}
MOD_COLS = ["feedAuthorClass1CountMean_0", "feedAuthorClass3CountMean_4"]
# feedAuthorClass{a}CountMean_{r}, summed over all author classes a=0..4, gives each extreme
# reader class's actual realized feed size S_fd(t) -- which is far below \maxfeed=15 for the
# extreme camp (~2-3), so the moderate-camp share must be normalized against this, not against \maxfeed.
S_FD_COLS_0 = [f"feedAuthorClass{a}CountMean_0" for a in range(5)]
S_FD_COLS_4 = [f"feedAuthorClass{a}CountMean_4" for a in range(5)]


def rates_and_influx(seed, subdir, checkpoint=FULL_STEPS, window=WINDOW):
    """Single pass per run dir: reads post_result.csv and results.csv exactly once each,
    returns per-camp posting rate and the moderate->extreme influx figure together."""
    d = result_dir(seed, subdir)
    lo = checkpoint - window

    posts = pd.read_csv(d / "posts" / "post_result.csv",
                         usecols=["step", "bin_0", "bin_1", "bin_2", "bin_3", "bin_4"])
    posts = posts[(posts["step"] > lo) & (posts["step"] <= checkpoint)]

    metrics_cols = (["step"] + [f"popCountByClass_{c}" for c in range(5)]
                     + MOD_COLS + S_FD_COLS_0 + S_FD_COLS_4)
    metrics = pd.read_csv(d / "metrics" / "results.csv", usecols=metrics_cols)
    metrics = metrics[(metrics["step"] > lo) & (metrics["step"] <= checkpoint)]

    out_rate = {}
    for camp, bins in CAMPS.items():
        total_posts = posts[[f"bin_{b}" for b in bins]].sum().sum()
        n = metrics[[f"popCountByClass_{c}" for c in bins]].sum(axis=1).mean()
        out_rate[camp] = total_posts / n / window if n > 0 else float("nan")

    # mean over the window of the per-step, population-averaged moderate-camp-authored post count
    # sitting in an extreme agent's feed -- a converged occupancy level, not a per-step arrival
    # flow -- expressed as % of the extreme camp's actual realized feed size S_fd (averaged over
    # classes 0 and 4), since that feed is far from the \maxfeed=15 capacity.
    mod_count = metrics[MOD_COLS].mean().mean()
    s_fd = (metrics[S_FD_COLS_0].sum(axis=1).mean() + metrics[S_FD_COLS_4].sum(axis=1).mean()) / 2
    mod_infl = mod_count / s_fd * 100 if s_fd > 0 else float("nan")
    return out_rate, mod_infl


def cohens_d(m_vals, c_vals):
    """Paired effect size: mean(N-silent - control) / std(N-silent - control), ddof=1 -- same
    convention as lmp_feedmech_stage1.py's d() and every d-value reported elsewhere in this project."""
    diff = np.array(m_vals) - np.array(c_vals)
    sd = diff.std(ddof=1)
    return diff.mean() / sd if sd > 0 else float("nan")


def one_sided_p(d_z, n):
    """One-sided paired t-test p-value in the direction of the observed sign of d_z: since
    d_z = mean(diff)/std(diff), the paired t-statistic is t = d_z*sqrt(n) (df=n-1)."""
    if np.isnan(d_z):
        return float("nan")
    t_stat = d_z * np.sqrt(n)
    return stats.t.sf(abs(t_stat), df=n - 1)


def build_grids():
    d_grids = {camp: np.zeros((len(PU_LEVELS), len(VCR_LEVELS))) for camp in CAMPS}
    p_grids = {camp: np.zeros((len(PU_LEVELS), len(VCR_LEVELS))) for camp in CAMPS}
    ctrl_infl = np.zeros((len(PU_LEVELS), len(VCR_LEVELS)))
    msil_infl = np.zeros((len(PU_LEVELS), len(VCR_LEVELS)))

    for i, pu in enumerate(PU_LEVELS):
        for j, vcr in enumerate(VCR_LEVELS):
            print(f"  cell p_u={pu} vcr={vcr} ...", flush=True)
            c_sub = f"{SUBDIR}/{cell_name(pu, vcr)}/control"
            m_sub = f"{SUBDIR}/{cell_name(pu, vcr)}/N_silent"

            c_rates = {camp: [] for camp in CAMPS}
            m_rates = {camp: [] for camp in CAMPS}
            c_infl, m_infl = [], []
            for s in SEEDS:
                cr, ci = rates_and_influx(s, c_sub)
                mr, mi = rates_and_influx(s, m_sub)
                for camp in CAMPS:
                    c_rates[camp].append(cr[camp])
                    m_rates[camp].append(mr[camp])
                c_infl.append(ci)
                m_infl.append(mi)

            for camp in CAMPS:
                d_grids[camp][i, j] = cohens_d(m_rates[camp], c_rates[camp])
                p_grids[camp][i, j] = one_sided_p(d_grids[camp][i, j], len(SEEDS))
            ctrl_infl[i, j] = np.mean(c_infl)
            msil_infl[i, j] = np.mean(m_infl)

    return d_grids, p_grids, ctrl_infl, msil_infl


TITLE_FS = 19
LABEL_FS = 17
TICK_FS = 15
ANNOT_FS = 12
CBAR_FS = 13


def truncated_cmap(name, lo=0.12, hi=0.88):
    """Clips the darkest/most-saturated ends of a colormap so max-magnitude cells stay light
    enough for black annotation text to remain legible."""
    base = matplotlib.colormaps[name]
    return mcolors.LinearSegmentedColormap.from_list(f"{name}_trunc", base(np.linspace(lo, hi, 256)))


def heatmap(ax, grid, title, cmap="RdBu_r", vmax=None, fmt="{:.3f}", sig_grid=None, cmap_clip=(0.12, 0.88)):
    vmax = vmax if vmax is not None else np.abs(grid).max()
    cmap = truncated_cmap(cmap, *cmap_clip) if cmap_clip is not None else cmap
    im = ax.imshow(grid, cmap=cmap, vmin=-vmax, vmax=vmax, aspect="auto", origin="upper")
    ax.set_xticks(range(len(VCR_LEVELS)))
    ax.set_xticklabels([f"{v:g}" for v in VCR_LEVELS], fontsize=TICK_FS)
    ax.set_yticks(range(len(PU_LEVELS)))
    ax.set_yticklabels([f"{p:g}" for p in PU_LEVELS], fontsize=TICK_FS)
    ax.set_xlabel(r"$\kappa$ (VCR)", fontsize=LABEL_FS)
    ax.set_ylabel(r"$P_u$", fontsize=LABEL_FS)
    ax.set_title(title, fontsize=TITLE_FS)
    for i in range(grid.shape[0]):
        for j in range(grid.shape[1]):
            label = fmt.format(grid[i, j])
            if sig_grid is not None:
                label += "*" * sig_grid[i, j]
            ax.text(j, i, label, ha="center", va="center", fontsize=ANNOT_FS)
    return im


def main():
    print("Building effect-size + influx grids (3 camps x 16 cells, single-pass per run)...", flush=True)
    d_grids, p_grids, ctrl, msil = build_grids()

    fig, axes = plt.subplots(1, 3, figsize=(15, 5), constrained_layout=True)
    for ax, camp in zip(axes, ("neutral", "moderate", "extreme")):
        sig = (p_grids[camp] < 0.05).astype(int) + (p_grids[camp] < 0.01).astype(int)
        im = heatmap(ax, d_grids[camp], camp, fmt="{:.2f}", sig_grid=sig)
        cb = fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
        cb.ax.tick_params(labelsize=CBAR_FS)
    fig.savefig(f"{OUT_DIR}/outcome-heatmap.pdf", dpi=200)
    print(f"  saved {OUT_DIR}/outcome-heatmap.pdf")

    diff = msil - ctrl
    vmax_abs = max(ctrl.max(), msil.max())

    fig2, axes2 = plt.subplots(1, 3, figsize=(15, 5), constrained_layout=True)
    for ax, grid, title, vmax in zip(axes2, (ctrl, msil, diff),
                                      ("control", "N-silent", "N-silent $-$ control"),
                                      (vmax_abs, vmax_abs, diff.max())):
        im = heatmap(ax, grid, title, cmap="viridis", vmax=vmax, fmt="{:.1f}%", cmap_clip=(0.25, 0.88))
        ax.images[0].set_clim(0, vmax)
        cb = fig2.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
        cb.ax.tick_params(labelsize=CBAR_FS)
        cb.ax.yaxis.set_major_formatter(mticker.FuncFormatter(lambda v, _: f"{v:g}%"))
    fig2.savefig(f"{OUT_DIR}/mod-extreme-influx.pdf", dpi=200)
    print(f"  saved {OUT_DIR}/mod-extreme-influx.pdf")

    # Fig3: same two effects, contrast reversed to control - N-silent (framing "the effect of an
    # active neutral hub" rather than "the effect of silencing it") -- this is the direction the
    # paper's Results text already argues in prose ("Reversing the contrast to control--N-silent
    # frames these differences via the presence of neutral hubs") but never plots. Left panel =
    # -1 x outcome-heatmap's "extreme" panel (same p-values: negating d_z
    # negates the paired t-stat but not |t|, so significance stars are unchanged). Right panel =
    # -1 x mod-extreme-influx's "diff" panel; that quantity is msil - ctrl >= 0 everywhere (N-silent
    # feeds always carry a larger moderate-camp share), so its flip ctrl - msil is <= 0 everywhere
    # -- a one-sided (not symmetric-about-zero) colormap, unlike the left panel.
    extreme_flip = -d_grids["extreme"]
    sig_extreme = p_grids["extreme"]
    sig_extreme = (sig_extreme < 0.05).astype(int) + (sig_extreme < 0.01).astype(int)
    influx_flip = ctrl - msil

    fig3, axes3 = plt.subplots(1, 2, figsize=(10, 5), constrained_layout=True)

    im0 = heatmap(axes3[0], extreme_flip, "extreme camp (posting-rate $d_z$)",
                   fmt="{:.2f}", sig_grid=sig_extreme)
    cb0 = fig3.colorbar(im0, ax=axes3[0], fraction=0.046, pad=0.04)
    cb0.ax.tick_params(labelsize=CBAR_FS)

    im1 = heatmap(axes3[1], influx_flip, r"moderate$\to$extreme influx share", cmap="viridis", fmt="{:.1f}%", cmap_clip=(0.25, 0.88))
    axes3[1].images[0].set_clim(influx_flip.min(), 0)
    cb1 = fig3.colorbar(im1, ax=axes3[1], fraction=0.046, pad=0.04)
    cb1.ax.tick_params(labelsize=CBAR_FS)
    cb1.ax.yaxis.set_major_formatter(mticker.FuncFormatter(lambda v, _: f"{v:g}%"))

    fig3.suptitle("control $-$ N-silent", fontsize=TITLE_FS)
    fig3.savefig(f"{OUT_DIR}/control-minus-msilent.pdf", dpi=200)
    print(f"  saved {OUT_DIR}/control-minus-msilent.pdf")

    print("\n=== DONE ===")


if __name__ == "__main__":
    main()
