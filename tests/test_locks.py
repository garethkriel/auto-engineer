"""Consistency checks between the protected-path list, the permission deny
rules and CLAUDE.md. If any of these drift apart, a lock silently stops
holding, so these tests must stay green."""

import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

REQUIRED_PATTERNS = {
    "rules/*",
    "review/*",
    ".claude/settings.json",
    ".claude/settings.local.json",
    ".github/*",
    "benchmark/*",
    "eval/*",
    "PAUSE",
}


def read_patterns():
    lines = (ROOT / ".github" / "protected-paths.txt").read_text(encoding="utf-8").splitlines()
    return {ln.strip() for ln in lines if ln.strip() and not ln.strip().startswith("#")}


def deny_path(pattern):
    """Protected pattern `x/*` is written as `x/**` in permission rules."""
    return pattern + "*" if pattern.endswith("/*") else pattern


class LocksTest(unittest.TestCase):
    def test_protected_list_has_required_patterns(self):
        self.assertTrue(REQUIRED_PATTERNS.issubset(read_patterns()),
                        REQUIRED_PATTERNS - read_patterns())

    def test_settings_deny_covers_protected(self):
        settings = json.loads((ROOT / "templates" / "claude-settings.json").read_text(encoding="utf-8"))
        deny = set(settings["permissions"]["deny"])
        for pattern in read_patterns():
            path = deny_path(pattern)
            self.assertIn(f"Edit({path})", deny)
            self.assertIn(f"Write({path})", deny)

    def test_claude_md_imports_safety(self):
        lines = (ROOT / "CLAUDE.md").read_text(encoding="utf-8").splitlines()
        self.assertIn("@rules/safety.md", [ln.strip() for ln in lines])


if __name__ == "__main__":
    unittest.main()
