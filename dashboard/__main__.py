import argparse
from pathlib import Path
from .core import ROOT
from .api import run_server
from .gui import run_gui


def main():
    ap = argparse.ArgumentParser(
        description="Live dashboard for a run.sh batch: tails logs/ + results/*/metrics/ while seeds are running.",
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--interval", type=float, default=5.0,
                    help="GUI-mode refresh period, seconds (web UI has its own control)")
    ap.add_argument("--seeds", type=int, nargs="*", default=None,
                    help="restrict to these seeds (default: all seeds found in logs/)")
    ap.add_argument("--logdir", default="logs")
    ap.add_argument("--serve", action="store_true",
                    help="serve the interactive web dashboard instead of a GUI window")
    ap.add_argument("--host", default="127.0.0.1", help="--serve bind address")
    ap.add_argument("--port", type=int, default=8765, help="--serve port")
    args = ap.parse_args()

    logdir = ROOT / args.logdir
    only = set(args.seeds) if args.seeds else None

    if args.serve:
        run_server(logdir, only, args.host, args.port)
    else:
        run_gui(logdir, only, args.interval)


if __name__ == "__main__":
    main()
