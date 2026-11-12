"""Protected-path guard.

Fails a PR that adds, modifies, deletes or renames any path matching
.github/protected-paths.txt. CI runs main's copy of this file
(pull_request_target), so a PR cannot weaken it.

CLI:
    python eval/guard.py --patterns .github/protected-paths.txt --changed changed.txt
`changed.txt` lists one path per line. For renames, list both the old and
the new name. Exit 0 = clean, 1 = protected path touched (hits printed).
"""

import argparse
import sys
from fnmatch import fnmatchcase
from pathlib import Path


def load_patterns(path: Path) -> list[str]:
    """Read fnmatch patterns, one per line; blank lines and # comments skipped."""
    patterns = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            patterns.append(line)
    return patterns


def protected_hits(changed: list[str], patterns: list[str]) -> list[str]:
    """Return the changed paths that match any protected pattern, in input order."""
    return [p for p in changed if any(fnmatchcase(p, pat) for pat in patterns)]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--patterns", required=True, type=Path)
    parser.add_argument("--changed", required=True, type=Path)
    args = parser.parse_args(argv)

    changed = [ln.strip() for ln in args.changed.read_text(encoding="utf-8").splitlines() if ln.strip()]
    hits = protected_hits(changed, load_patterns(args.patterns))
    if hits:
        print("Protected paths touched (this PR cannot merge):")
        for h in hits:
            print(f"  {h}")
        return 1
    print(f"guard: {len(changed)} changed paths, none protected")
    return 0


if __name__ == "__main__":
    sys.exit(main())
