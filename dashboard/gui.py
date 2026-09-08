from pathlib import Path
from .core import active_seeds, result_dir, METRICS, RESULTS_COLS, COLORS


def load_seed_data(seed: int):
    import pandas as pd
    d = result_dir(seed)
    if d is None:
        return None
    try:
        df = pd.read_csv(d / "metrics" / "results.csv", usecols=RESULTS_COLS,
                          on_bad_lines="skip")
    except (FileNotFoundError, pd.errors.EmptyDataError, pd.errors.ParserError):
        return None
    try:
        mod = pd.read_csv(d / "metrics" / "modularity.csv", on_bad_lines="skip")
        df = df.merge(mod, on="step", how="left")
    except (FileNotFoundError, pd.errors.EmptyDataError, pd.errors.ParserError):
        df["Q_sign"] = float("nan")
    return df


def redraw(fig, axes, logdir: Path, only, interval: float):
    statuses = active_seeds(logdir, only)
    seeds = sorted(statuses)

    for ax, (_col, label) in zip(axes, METRICS):
        ax.clear()
        ax.set_title(label, fontsize=10)
        ax.set_xlabel("step")
        ax.grid(True, color="#e1e0d9", linewidth=0.6)
        for spine in ax.spines.values():
            spine.set_color("#c3c2b7")

    for i, seed in enumerate(seeds):
        df = load_seed_data(seed)
        if df is None or df.empty:
            continue
        color = COLORS[i % len(COLORS)]
        style = "-" if i < len(COLORS) else "--"
        for ax, (col, _label) in zip(axes, METRICS):
            if col in df.columns:
                sub = df[["step", col]].dropna()
                ax.plot(sub["step"], sub[col], style, color=color,
                        linewidth=1.5, label=f"seed {seed} ({statuses[seed]})")

    handles, labels = axes[0].get_legend_handles_labels()
    if handles:
        fig.legend(handles, labels, loc="upper center", bbox_to_anchor=(0.5, 0.94),
                   ncol=min(len(labels), 8), fontsize=8, frameon=False)
    n_run = sum(1 for s in statuses.values() if s == "running")
    n_done = sum(1 for s in statuses.values() if s == "done")
    n_fail = sum(1 for s in statuses.values() if s == "failed")
    fig.suptitle(f"run.sh live dashboard — running={n_run} done={n_done} "
                 f"failed={n_fail}  (refresh {interval:.0f}s)", fontsize=11, y=0.99)
    fig.tight_layout(rect=(0, 0, 1, 0.88))


def run_gui(logdir: Path, only, interval: float):
    import matplotlib.pyplot as plt
    from matplotlib.animation import FuncAnimation
    fig, axes = plt.subplots(2, 3, figsize=(15, 8))
    axes = axes.flatten()
    ani = FuncAnimation(fig, lambda _frame: redraw(fig, axes, logdir, only, interval),
                         interval=interval * 1000, cache_frame_data=False)
    plt.show()
