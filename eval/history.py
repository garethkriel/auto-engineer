"""Benchmark score history: scores/history.csv in the auto-engineer-bench repo.

Only CI writes this file (spec 8.6). One row per language per benchmark run:
    timestamp,config,run_id,language,pass_rate
config is "main" for daily baselines, "p-<run-id>" for proposal runs.
"""

import csv
from pathlib import Path

HEADER = ["timestamp", "config", "run_id", "language", "pass_rate"]


def append_rows(csv_path: Path, timestamp: str, config: str, run_id: str,
                rates: dict[str, float]) -> None:
    csv_path = Path(csv_path)
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    new = not csv_path.exists() or csv_path.stat().st_size == 0
    with csv_path.open("a", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        if new:
            w.writerow(HEADER)
        for lang in sorted(rates):
            w.writerow([timestamp, config, run_id, lang, f"{rates[lang]:.4f}"])


def _runs(csv_path: Path, config: str) -> list[dict[str, float]]:
    """All runs of one config, oldest first, as language -> pass rate."""
    path = Path(csv_path)
    if not path.exists():
        return []
    runs: dict[tuple[str, str], dict[str, float]] = {}
    with path.open(newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["config"] == config:
                runs.setdefault((row["timestamp"], row["run_id"]), {})[row["language"]] = float(row["pass_rate"])
    return [runs[k] for k in sorted(runs)]


def baselines(csv_path: Path, n: int = 3) -> list[dict[str, float]]:
    """The last n runs of main, oldest first."""
    return _runs(csv_path, "main")[-n:]


def proposal_runs(csv_path: Path, config: str) -> list[dict[str, float]]:
    return _runs(csv_path, config)
