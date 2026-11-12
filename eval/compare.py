"""Merge rule for self-modification proposals (spec 8.6).

The last 2 benchmark runs of the proposal must each:
- beat main's baseline median (median of the last 3 baselines, or fewer if
  fewer exist) in at least one language, strictly; and
- fall below that median in no language. A language missing from a run
  counts as 0.
With no baselines, or fewer than 2 runs, the proposal holds.

CLI:
    python eval/compare.py --csv scores/history.csv --config p-<run-id>
Prints "merge" (exit 0) or "hold" (exit 1).
"""

import argparse
import sys
from pathlib import Path
from statistics import median

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from eval.history import baselines, proposal_runs  # noqa: E402

RUNS_REQUIRED = 2


def should_merge(runs: list[dict[str, float]], base: list[dict[str, float]]) -> bool:
    if len(runs) < RUNS_REQUIRED or not base:
        return False
    languages = sorted({lang for b in base for lang in b})
    medians = {lang: median(b[lang] for b in base if lang in b) for lang in languages}
    for run in runs[-RUNS_REQUIRED:]:
        improved = any(run.get(lang, 0.0) > medians[lang] for lang in languages)
        regressed = any(run.get(lang, 0.0) < medians[lang] for lang in languages)
        if not improved or regressed:
            return False
    return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Decide whether a proposal may merge.")
    parser.add_argument("--csv", required=True, type=Path)
    parser.add_argument("--config", required=True)
    args = parser.parse_args(argv)
    ok = should_merge(proposal_runs(args.csv, args.config), baselines(args.csv))
    print("merge" if ok else "hold")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
