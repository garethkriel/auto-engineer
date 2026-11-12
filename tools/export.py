"""Build exports/solutions.jsonl from the per-round export.json files.

Each practice round writes practice/<lang>/<run-id>/export.json. This script
concatenates them, one JSON object per line, ordered by path. The output is
gitignored and built on demand (spec 8.9).

CLI:
    python tools/export.py [--root .] [--out exports/solutions.jsonl]
"""

import argparse
import json
import sys
from pathlib import Path


def build_jsonl(root: Path, out: Path) -> int:
    """Write every readable export.json as one line of `out`; return the line count."""
    lines = []
    for path in sorted((Path(root) / "practice").glob("*/*/export.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            print(f"export: skipping {path}: {exc}", file=sys.stderr)
            continue
        lines.append(json.dumps(data, ensure_ascii=False))
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(ln + "\n" for ln in lines), encoding="utf-8")
    return len(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build exports/solutions.jsonl.")
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--out", type=Path, default=Path("exports/solutions.jsonl"))
    args = parser.parse_args(argv)
    print(f"export: wrote {build_jsonl(args.root, args.out)} lines to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
