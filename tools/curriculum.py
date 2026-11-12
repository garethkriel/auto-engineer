"""Current curriculum level per language, computed from merged round results.

Rule (spec 8.2): start at level 1. Three consecutive passes move up one
level; three consecutive failures move down one. The streak resets after
any level change. Levels are clamped to 1..10.

The level is derived from practice/<lang>/*/result.json instead of being
stored in a shared file, so overlapping hourly PRs never conflict.

CLI:
    python tools/curriculum.py java [--root .]
"""

import argparse
import json
import sys
from pathlib import Path

MIN_LEVEL, MAX_LEVEL, STREAK = 1, 10, 3


def compute_level(results: list[dict]) -> int:
    """Replay results in order and return the resulting level."""
    level, streak_passed, streak = MIN_LEVEL, None, 0
    for r in results:
        passed = bool(r["passed"])
        streak = streak + 1 if passed == streak_passed else 1
        streak_passed = passed
        if streak == STREAK:
            level = min(MAX_LEVEL, level + 1) if passed else max(MIN_LEVEL, level - 1)
            streak_passed, streak = None, 0
    return level


def load_results(root: Path, language: str) -> list[dict]:
    """Read practice/<language>/*/result.json, sorted by run_id.

    Unreadable or malformed files are skipped with a warning on stderr.
    """
    results = []
    for path in sorted((Path(root) / "practice" / language).glob("*/result.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(data.get("run_id"), str) or not isinstance(data.get("passed"), bool):
                raise ValueError("missing run_id or passed")
        except (OSError, ValueError, AttributeError) as exc:
            print(f"curriculum: skipping {path}: {exc}", file=sys.stderr)
            continue
        results.append(data)
    return sorted(results, key=lambda r: r["run_id"])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Print the current curriculum level.")
    parser.add_argument("language")
    parser.add_argument("--root", type=Path, default=Path("."))
    args = parser.parse_args(argv)
    print(compute_level(load_results(args.root, args.language)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
