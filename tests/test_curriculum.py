import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from tools.curriculum import compute_level, load_results


def rounds(pattern: str) -> list[dict]:
    """'PPF' -> three results in run order: pass, pass, fail."""
    return [{"run_id": f"20261113T{i:04d}Z", "passed": c == "P"} for i, c in enumerate(pattern)]


class ComputeLevelTest(unittest.TestCase):
    def test_empty_is_level_1(self):
        self.assertEqual(compute_level([]), 1)

    def test_three_passes_up(self):
        self.assertEqual(compute_level(rounds("PPP")), 2)

    def test_streak_resets_after_change(self):
        self.assertEqual(compute_level(rounds("PPPPP")), 2)

    def test_three_fails_down_clamped(self):
        self.assertEqual(compute_level(rounds("FFF")), 1)

    def test_down_after_up(self):
        self.assertEqual(compute_level(rounds("PPPFFF")), 1)

    def test_mixed_breaks_streak(self):
        self.assertEqual(compute_level(rounds("PPFPP")), 1)

    def test_cap_at_10(self):
        self.assertEqual(compute_level(rounds("P" * 27)), 10)
        self.assertEqual(compute_level(rounds("P" * 30)), 10)


class LoadResultsTest(unittest.TestCase):
    def test_corrupt_file_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            good = root / "practice" / "java" / "20261113T0100Z"
            bad = root / "practice" / "java" / "20261113T0000Z"
            good.mkdir(parents=True)
            bad.mkdir(parents=True)
            (good / "result.json").write_text(json.dumps(
                {"run_id": "20261113T0100Z", "language": "java", "level": 1, "topic": "t",
                 "task": "x", "passed": True, "attempts": 1}), encoding="utf-8")
            (bad / "result.json").write_text("{not json", encoding="utf-8")

            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                results = load_results(root, "java")

            self.assertEqual([r["run_id"] for r in results], ["20261113T0100Z"])
            self.assertIn("skipping", err.getvalue())

    def test_sorted_by_run_id(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for run_id in ["20261113T0300Z", "20261113T0100Z"]:
                folder = root / "practice" / "java" / f"dir-{run_id}"
                folder.mkdir(parents=True)
                (folder / "result.json").write_text(
                    json.dumps({"run_id": run_id, "passed": True}), encoding="utf-8")
            self.assertEqual([r["run_id"] for r in load_results(root, "java")],
                             ["20261113T0100Z", "20261113T0300Z"])


if __name__ == "__main__":
    unittest.main()
