import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from eval.compare import should_merge
from eval.history import append_rows

ROOT = Path(__file__).resolve().parent.parent

BASE = [{"java": 0.5}, {"java": 0.5}, {"java": 0.42}]  # median 0.5


class ShouldMergeTest(unittest.TestCase):
    def test_two_runs_required(self):
        self.assertFalse(should_merge([{"java": 0.9}], BASE))

    def test_improve_no_regress_merges(self):
        self.assertTrue(should_merge([{"java": 0.58}, {"java": 0.67}], BASE))

    def test_one_lucky_run_holds(self):
        self.assertFalse(should_merge([{"java": 0.75}, {"java": 0.5}], BASE))

    def test_equal_is_not_better(self):
        self.assertFalse(should_merge([{"java": 0.5}, {"java": 0.5}], BASE))

    def test_regression_blocks(self):
        base = [{"java": 0.5, "python": 0.5}]
        self.assertFalse(should_merge([{"java": 0.75, "python": 0.42}] * 2, base))

    def test_missing_language_counts_as_zero(self):
        base = [{"java": 0.5, "python": 0.5}]
        self.assertFalse(should_merge([{"java": 0.75}] * 2, base))

    def test_zero_baselines_holds(self):
        self.assertFalse(should_merge([{"java": 1.0}, {"java": 1.0}], []))

    def test_fewer_than_3_baselines_uses_available(self):
        self.assertTrue(should_merge([{"java": 0.6}, {"java": 0.6}], [{"java": 0.5}]))
        self.assertFalse(should_merge([{"java": 0.6}, {"java": 0.6}], [{"java": 0.6}, {"java": 0.7}]))

    def test_uses_last_two_runs(self):
        runs = [{"java": 0.1}, {"java": 0.6}, {"java": 0.7}]
        self.assertTrue(should_merge(runs, BASE))


class CliTest(unittest.TestCase):
    def test_cli_merge_and_hold(self):
        with tempfile.TemporaryDirectory() as d:
            csv = Path(d) / "history.csv"
            append_rows(csv, "2026-11-13T02:00:00Z", "main", "b1", {"java": 0.5})
            append_rows(csv, "2026-11-13T05:00:00Z", "p-x", "r1", {"java": 0.6})
            append_rows(csv, "2026-11-13T06:00:00Z", "p-x", "r2", {"java": 0.7})

            def run(config):
                return subprocess.run([sys.executable, str(ROOT / "eval" / "compare.py"),
                                       "--csv", str(csv), "--config", config],
                                      capture_output=True, text=True)

            merge = run("p-x")
            self.assertEqual((merge.returncode, merge.stdout.strip()), (0, "merge"), merge.stderr)
            hold = run("p-none")
            self.assertEqual((hold.returncode, hold.stdout.strip()), (1, "hold"), hold.stderr)


if __name__ == "__main__":
    unittest.main()
