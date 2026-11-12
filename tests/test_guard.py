import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from eval.guard import load_patterns, protected_hits

ROOT = Path(__file__).resolve().parent.parent
P = load_patterns(ROOT / ".github" / "protected-paths.txt")


class GuardTest(unittest.TestCase):
    def test_clean_change(self):
        self.assertEqual(protected_hits(["skills/java/x/README.md"], P), [])

    def test_nested_protected(self):
        self.assertEqual(protected_hits(["benchmark/java/j01/TASK.md"], P),
                         ["benchmark/java/j01/TASK.md"])

    def test_pause_delete_is_hit(self):
        # A deleted file still appears in the changed list by its old name.
        self.assertEqual(protected_hits(["PAUSE"], P), ["PAUSE"])

    def test_rename_both_sides(self):
        # Renaming rules/safety.md -> notes/safety.md lists both names
        # (git diff --no-renames / the API's previous_filename).
        self.assertEqual(protected_hits(["notes/safety.md", "rules/safety.md"], P),
                         ["rules/safety.md"])

    def test_comments_and_blanks_ignored(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "p.txt"
            f.write_text("# comment\n\n  rules/*  \n", encoding="utf-8")
            self.assertEqual(load_patterns(f), ["rules/*"])

    def test_cli_exit_codes(self):
        with tempfile.TemporaryDirectory() as d:
            changed = Path(d) / "changed.txt"
            patterns = ROOT / ".github" / "protected-paths.txt"
            cmd = [sys.executable, str(ROOT / "eval" / "guard.py"),
                   "--patterns", str(patterns), "--changed", str(changed)]

            changed.write_text("skills/java/a/README.md\n", encoding="utf-8")
            clean = subprocess.run(cmd, capture_output=True, text=True)
            self.assertEqual(clean.returncode, 0, clean.stdout + clean.stderr)

            changed.write_text("skills/java/a/README.md\neval/guard.py\n", encoding="utf-8")
            hit = subprocess.run(cmd, capture_output=True, text=True)
            self.assertEqual(hit.returncode, 1)
            self.assertIn("eval/guard.py", hit.stdout)


if __name__ == "__main__":
    unittest.main()
