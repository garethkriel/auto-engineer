"""Validate the practice rounds and file-change rules of one PR.

Checks (spec 8.1, 8.2, 8.4):
- each added practice round has the required files and a valid result.json;
- append-only paths (practice/, lessons/entries/, logs/runs/) only gain files;
- skills/ and lessons/RULES.md change only in maintenance or proposal PRs;
- self-modification paths change only in proposal PRs (claude/p-*).
It also writes maven-dirs.txt: the Maven projects CI must test. These are
added rounds that claim "passed": true, plus every changed skill. Failed
rounds are not run, so their lessons can still merge.

CLI (run from the repo root, with the PR head checked out):
    python eval/validate_round.py --base <base-sha> --head-ref <branch>
Exit 0 = valid, 1 = errors printed.
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

APPEND_ONLY = ("practice/", "lessons/entries/", "logs/runs/")
MAINT_EDITABLE = ("skills/", "lessons/RULES.md")
SELF_MOD = ("CLAUDE.md", "curriculum/", "languages/", "prompts/", "tools/", "templates/", "tests/")

RESULT_SCHEMA = {"run_id": str, "language": str, "level": int, "topic": str,
                 "task": str, "passed": bool, "attempts": int}


def _under(path: str, prefixes: tuple[str, ...]) -> bool:
    """True if path is inside a folder prefix (ending in "/") or equals a file entry."""
    return any(path.startswith(p) if p.endswith("/") else path == p for p in prefixes)


def validate_round(repo: Path, round_dir: Path) -> list[str]:
    """Return a list of problems with one practice round folder (empty = valid)."""
    repo, round_dir = Path(repo), Path(round_dir)
    rel = round_dir.relative_to(repo).as_posix()
    errors = []

    try:
        result = json.loads((round_dir / "result.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return [f"{rel}/result.json: unreadable ({exc})"]

    for key, typ in RESULT_SCHEMA.items():
        # bool is a subclass of int; don't accept true/false as a number.
        value = result.get(key)
        if not isinstance(value, typ) or (typ is int and isinstance(value, bool)):
            errors.append(f"{rel}/result.json: '{key}' missing or not {typ.__name__}")
    if not errors:
        if not 1 <= result["attempts"] <= 3:
            errors.append(f"{rel}/result.json: attempts must be 1-3, got {result['attempts']}")
        if not 1 <= result["level"] <= 10:
            errors.append(f"{rel}/result.json: level must be 1-10, got {result['level']}")
        if result["run_id"] != round_dir.name:
            errors.append(f"{rel}/result.json: run_id '{result['run_id']}' does not match folder")
        if result["language"] != round_dir.parent.name:
            errors.append(f"{rel}/result.json: language '{result['language']}' does not match folder")

    if not (round_dir / "pom.xml").is_file():
        errors.append(f"{rel}: missing pom.xml")
    if not any((round_dir / "src" / "test" / "java").rglob("*Test.java")):
        errors.append(f"{rel}: no src/test/java/**/*Test.java")
    try:
        json.loads((round_dir / "export.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        errors.append(f"{rel}: missing or unreadable export.json")

    run_id = round_dir.name
    for required in (f"lessons/entries/{run_id}.md", f"logs/runs/{run_id}.md"):
        if not (repo / required).is_file():
            errors.append(f"{rel}: missing {required}")
    return errors


def append_only_violations(changes: list[tuple[str, str]], head_ref: str) -> list[str]:
    """Non-add changes to append-only paths; skills/RULES.md edits outside maint/proposal PRs."""
    may_edit_library = head_ref.startswith(("claude/maint-", "claude/p-"))
    violations = []
    for status, path in changes:
        if status == "A":
            continue
        if _under(path, APPEND_ONLY):
            violations.append(f"{status} {path}: append-only path, only new files allowed")
        elif _under(path, MAINT_EDITABLE) and not may_edit_library:
            violations.append(f"{status} {path}: only maintenance or proposal PRs may change this")
    return violations


def self_mod_violations(changes: list[tuple[str, str]], head_ref: str) -> list[str]:
    """Any change to a self-modification path outside a proposal PR."""
    if head_ref.startswith("claude/p-"):
        return []
    return [f"{status} {path}: workflow change, only allowed in a claude/p-* proposal PR"
            for status, path in changes if _under(path, SELF_MOD)]


def added_round_dirs(repo: Path, changes: list[tuple[str, str]]) -> list[Path]:
    """Folders practice/<lang>/<run-id>/ that this PR adds files to."""
    dirs = set()
    for status, path in changes:
        parts = path.split("/")
        if status == "A" and parts[0] == "practice" and len(parts) >= 4:
            dirs.add(Path(repo, *parts[:3]))
    return sorted(dirs)


def maven_dirs_to_test(repo: Path, changes: list[tuple[str, str]]) -> list[Path]:
    """Added rounds that claim to pass, plus every changed skill that still exists."""
    dirs = set()
    for rd in added_round_dirs(repo, changes):
        try:
            if json.loads((rd / "result.json").read_text(encoding="utf-8")).get("passed") is True:
                dirs.add(rd)
        except (OSError, ValueError):
            pass  # reported by validate_round
    for _, path in changes:
        parts = path.split("/")
        if parts[0] == "skills" and len(parts) >= 4:
            skill = Path(repo, *parts[:3])
            if (skill / "pom.xml").is_file():
                dirs.add(skill)
    return sorted(dirs)


def git_changes(base: str) -> list[tuple[str, str]]:
    out = subprocess.run(["git", "diff", "--name-status", "--no-renames", f"{base}...HEAD"],
                         check=True, capture_output=True, text=True).stdout
    changes = []
    for line in out.splitlines():
        status, _, path = line.partition("\t")
        if path:
            changes.append((status[0], path))
    return changes


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate a PR's practice rounds.")
    parser.add_argument("--base", required=True)
    parser.add_argument("--head-ref", required=True)
    parser.add_argument("--out", type=Path, default=Path("maven-dirs.txt"))
    args = parser.parse_args(argv)

    repo = Path(".").resolve()
    changes = git_changes(args.base)
    errors = append_only_violations(changes, args.head_ref) + self_mod_violations(changes, args.head_ref)
    for rd in added_round_dirs(repo, changes):
        errors += validate_round(repo, rd)

    dirs = maven_dirs_to_test(repo, changes)
    args.out.write_text("".join(d.relative_to(repo).as_posix() + "\n" for d in dirs), encoding="utf-8")

    if errors:
        print("validate_round: problems found:")
        for e in errors:
            print(f"  {e}")
        return 1
    print(f"validate_round: {len(changes)} changes OK; {len(dirs)} Maven projects to test")
    return 0


if __name__ == "__main__":
    sys.exit(main())
