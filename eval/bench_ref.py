"""Parse benchmark branch names strictly, so CI only records well-formed runs.

    claude/bench-main-<run-id>              -> ("main", "<run-id>")
    claude/bench-p-<proposal-id>-<run-id>   -> ("p-<proposal-id>", "<run-id>")
Run ids are UTC YYYYMMDDTHHMMZ. Anything else raises ValueError.

CLI: python eval/bench_ref.py <branch>   prints "config=<c>" and "run_id=<r>"
lines (for $GITHUB_OUTPUT), or exits 1.
"""

import re
import sys

RUN_ID = r"\d{8}T\d{4}Z"
_BENCH = re.compile(rf"claude/bench-(main|p-{RUN_ID})-({RUN_ID})")
_CONFIG = re.compile(rf"p-{RUN_ID}")


def parse_bench_branch(ref: str) -> tuple[str, str]:
    m = _BENCH.fullmatch(ref)
    if not m:
        raise ValueError(f"not a benchmark branch: {ref!r}")
    return m.group(1), m.group(2)


def proposal_branch(config: str) -> str:
    """The proposal PR's head branch for a proposal config."""
    if not _CONFIG.fullmatch(config):
        raise ValueError(f"not a proposal config: {config!r}")
    return f"claude/{config}"


def main(argv: list[str]) -> int:
    try:
        config, run_id = parse_bench_branch(argv[1])
    except (IndexError, ValueError) as exc:
        print(exc, file=sys.stderr)
        return 1
    print(f"config={config}")
    print(f"run_id={run_id}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
