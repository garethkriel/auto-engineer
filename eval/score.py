"""Score a benchmark run from Maven surefire reports.

A task passes only if Maven wrote at least one TEST-*.xml and every suite
has tests > 0 and no failures, errors or skipped tests. A missing or
uncompilable solution writes no reports, so it counts as a failed task,
not a crash (plan Review Focus 5).

CLI (after CI ran the hidden tests in each solution folder):
    python eval/score.py --benchmark benchmark --solutions bench-solutions --out rates.json
Prints and writes {"<language>": <pass rate>}.
"""

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def task_passed(report_dir: Path) -> bool:
    reports = sorted(Path(report_dir).glob("TEST-*.xml"))
    if not reports:
        return False
    for path in reports:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            return False
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        if not suites:
            return False
        for s in suites:
            counts = {k: int(s.get(k, "0")) for k in ("tests", "failures", "errors", "skipped")}
            if counts["tests"] <= 0 or counts["failures"] or counts["errors"] or counts["skipped"]:
                return False
    return True


def pass_rates(results: dict[str, dict[str, bool]]) -> dict[str, float]:
    """language -> task id -> passed  =>  language -> share of tasks passed."""
    return {lang: (sum(tasks.values()) / len(tasks) if tasks else 0.0)
            for lang, tasks in results.items()}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Score benchmark solutions.")
    parser.add_argument("--benchmark", type=Path, default=Path("benchmark"))
    parser.add_argument("--solutions", type=Path, default=Path("bench-solutions"))
    parser.add_argument("--out", type=Path, default=Path("rates.json"))
    args = parser.parse_args(argv)

    results = {}
    for lang_dir in sorted(p for p in args.benchmark.iterdir() if p.is_dir()):
        results[lang_dir.name] = {
            task.name: task_passed(args.solutions / lang_dir.name / task.name / "target" / "surefire-reports")
            for task in sorted(p for p in lang_dir.iterdir() if p.is_dir())
        }
    rates = pass_rates(results)
    args.out.write_text(json.dumps(rates), encoding="utf-8")
    print(json.dumps({"rates": rates, "tasks": results}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
