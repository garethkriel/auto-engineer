import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from eval.validate_round import (append_only_violations, maven_dirs_to_test,
                                 self_mod_violations, validate_round)

ROOT = Path(__file__).resolve().parent.parent
RUN = "20261113T0900Z"


def make_round(repo: Path, run_id: str = RUN, passed: bool = True, attempts: int = 1,
               lesson: bool = True) -> Path:
    rd = repo / "practice" / "java" / run_id
    (rd / "src" / "test" / "java" / "ae").mkdir(parents=True)
    (rd / "src" / "test" / "java" / "ae" / "FooTest.java").write_text("class FooTest {}", encoding="utf-8")
    (rd / "pom.xml").write_text("<project/>", encoding="utf-8")
    (rd / "result.json").write_text(json.dumps(
        {"run_id": run_id, "language": "java", "level": 1, "topic": "t", "task": "x",
         "passed": passed, "attempts": attempts}), encoding="utf-8")
    (rd / "export.json").write_text(json.dumps(
        {"language": "java", "task": "x", "code": {}, "tests": {}, "passed": passed,
         "reasoning_summary": "s"}), encoding="utf-8")
    if lesson:
        (repo / "lessons" / "entries").mkdir(parents=True, exist_ok=True)
        (repo / "lessons" / "entries" / f"{run_id}.md").write_text("lesson", encoding="utf-8")
    (repo / "logs" / "runs").mkdir(parents=True, exist_ok=True)
    (repo / "logs" / "runs" / f"{run_id}.md").write_text("log", encoding="utf-8")
    return rd


def added(repo: Path) -> list[tuple[str, str]]:
    return [("A", p.relative_to(repo).as_posix()) for p in sorted(repo.rglob("*")) if p.is_file()]


class ValidateRoundTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_valid_round(self):
        self.assertEqual(validate_round(self.repo, make_round(self.repo)), [])

    def test_missing_lesson_entry(self):
        errors = validate_round(self.repo, make_round(self.repo, lesson=False))
        self.assertEqual(len(errors), 1)
        self.assertIn("lessons/entries", errors[0])

    def test_attempts_out_of_range(self):
        errors = validate_round(self.repo, make_round(self.repo, attempts=4))
        self.assertTrue(any("attempts" in e for e in errors), errors)

    def test_failed_round_not_tested_but_valid(self):
        rd = make_round(self.repo, passed=False)
        self.assertEqual(validate_round(self.repo, rd), [])
        self.assertEqual(maven_dirs_to_test(self.repo, added(self.repo)), [])

    def test_passed_round_is_tested(self):
        rd = make_round(self.repo, passed=True)
        self.assertEqual(maven_dirs_to_test(self.repo, added(self.repo)), [rd])

    def test_changed_skill_is_tested(self):
        skill = self.repo / "skills" / "java" / "s"
        (skill / "src" / "main" / "java").mkdir(parents=True)
        (skill / "pom.xml").write_text("<project/>", encoding="utf-8")
        (skill / "src" / "main" / "java" / "S.java").write_text("class S {}", encoding="utf-8")
        changes = [("M", "skills/java/s/src/main/java/S.java")]
        self.assertEqual(maven_dirs_to_test(self.repo, changes), [skill])

    def test_deleted_skill_not_tested(self):
        changes = [("D", "skills/java/gone/pom.xml")]
        self.assertEqual(maven_dirs_to_test(self.repo, changes), [])


class AppendOnlyTest(unittest.TestCase):
    def test_modify_existing_practice_file_rejected(self):
        v = append_only_violations([("M", "practice/java/X/Foo.java")], "claude/practice-X")
        self.assertEqual(len(v), 1)

    def test_delete_lesson_entry_rejected_even_on_maint(self):
        v = append_only_violations([("D", "lessons/entries/X.md")], "claude/maint-X")
        self.assertEqual(len(v), 1)

    def test_maint_may_edit_rules(self):
        changes = [("M", "lessons/RULES.md"), ("M", "skills/java/a/README.md")]
        self.assertEqual(append_only_violations(changes, "claude/maint-X"), [])
        self.assertEqual(len(append_only_violations(changes, "claude/practice-X")), 2)

    def test_practice_may_add_skill(self):
        self.assertEqual(append_only_violations([("A", "skills/java/new/pom.xml")], "claude/practice-X"), [])


class SelfModTest(unittest.TestCase):
    def test_practice_pr_editing_claude_md_rejected(self):
        self.assertEqual(len(self_mod_violations([("M", "CLAUDE.md")], "claude/practice-X")), 1)
        self.assertEqual(self_mod_violations([("M", "CLAUDE.md")], "claude/p-X"), [])

    def test_tools_and_templates_are_self_mod(self):
        changes = [("M", "tools/curriculum.py"), ("A", "templates/java/x.xml")]
        self.assertEqual(len(self_mod_violations(changes, "claude/maint-X")), 2)


class CliTest(unittest.TestCase):
    def test_cli_end_to_end(self):
        with tempfile.TemporaryDirectory() as d:
            repo = Path(d)

            def git(*args):
                subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *args],
                               cwd=repo, check=True, capture_output=True)

            git("init", "-q", "-b", "main")
            (repo / "README.md").write_text("x", encoding="utf-8")
            git("add", "-A")
            git("commit", "-q", "-m", "base")
            base = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, check=True,
                                  capture_output=True, text=True).stdout.strip()
            rd = make_round(repo)
            git("add", "-A")
            git("commit", "-q", "-m", "round")

            cmd = [sys.executable, str(ROOT / "eval" / "validate_round.py"),
                   "--base", base, "--head-ref", f"claude/practice-{RUN}"]
            ok = subprocess.run(cmd, cwd=repo, capture_output=True, text=True)
            self.assertEqual(ok.returncode, 0, ok.stdout + ok.stderr)
            dirs = (repo / "maven-dirs.txt").read_text(encoding="utf-8").split()
            self.assertEqual(dirs, [rd.relative_to(repo).as_posix()])

            (repo / "CLAUDE.md").write_text("changed", encoding="utf-8")
            git("add", "-A")
            git("commit", "-q", "-m", "sneaky")
            bad = subprocess.run(cmd, cwd=repo, capture_output=True, text=True)
            self.assertEqual(bad.returncode, 1)
            self.assertIn("CLAUDE.md", bad.stdout)


if __name__ == "__main__":
    unittest.main()
