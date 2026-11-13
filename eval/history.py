"""Benchmark score history: scores/history.csv in the auto-engineer-bench repo.

Only CI writes this file (spec 8.6). One row per language per benchmark run:
    timestamp,config,run_id,language,pass_rate
config is "main" for daily baselines, "p-<run-id>" for proposal runs.
"""

import csv
from pathlib import Path

HEADER = ["timestamp", "config", "run_id", "language", "pass_rate"]


def _rows(csv_path: Path) -> list[dict[str, str]]:
    path = Path(csv_path)
    if not path.exists():
        return []
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def append_rows(csv_path: Path, timestamp: str, config: str, run_id: str,
                rates: dict[str, float]) -> bool:
    """Record one benchmark run. Returns False (writes nothing) if this
    config + run_id is already recorded, so a re-push or re-run of the same
    benchmark branch never counts twice."""
    csv_path = Path(csv_path)
    if any(r["config"] == config and r["run_id"] == run_id for r in _rows(csv_path)):
        return False
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    new = not csv_path.exists() or csv_path.stat().st_size == 0
    with csv_path.open("a", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        if new:
            w.writerow(HEADER)
        for lang in sorted(rates):
            w.writerow([timestamp, config, run_id, lang, f"{rates[lang]:.4f}"])
    return True


def _runs(csv_path: Path, config: str) -> list[dict[str, float]]:
    """All distinct runs (by run_id) of one config, oldest first, as language -> pass rate."""
    runs: dict[str, dict[str, float]] = {}
    first_seen: dict[str, str] = {}
    for row in _rows(csv_path):
        if row["config"] == config:
            first_seen.setdefault(row["run_id"], row["timestamp"])
            runs.setdefault(row["run_id"], {})[row["language"]] = float(row["pass_rate"])
    return [runs[rid] for rid in sorted(runs, key=lambda rid: (first_seen[rid], rid))]


def baselines(csv_path: Path, n: int = 3) -> list[dict[str, float]]:
    """The last n runs of main, oldest first."""
    return _runs(csv_path, "main")[-n:]


def proposal_runs(csv_path: Path, config: str) -> list[dict[str, float]]:
    return _runs(csv_path, config)
