import tempfile
import unittest
from pathlib import Path

from eval.score import pass_rates, task_passed


def report(d: Path, name: str, tests=3, failures=0, errors=0, skipped=0) -> None:
    d.mkdir(parents=True, exist_ok=True)
    (d / f"TEST-{name}.xml").write_text(
        f'<?xml version="1.0"?><testsuite name="{name}" tests="{tests}" failures="{failures}" '
        f'errors="{errors}" skipped="{skipped}"></testsuite>', encoding="utf-8")


class TaskPassedTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name) / "surefire-reports"

    def tearDown(self):
        self._tmp.cleanup()

    def test_all_green_passes(self):
        report(self.dir, "a.ATest")
        report(self.dir, "a.BTest")
        self.assertTrue(task_passed(self.dir))

    def test_missing_report_dir_fails_task(self):
        self.assertFalse(task_passed(self.dir))

    def test_compile_error_no_reports_fails_task(self):
        self.dir.mkdir(parents=True)  # Maven ran but wrote no TEST-*.xml
        self.assertFalse(task_passed(self.dir))

    def test_one_failure_fails_task(self):
        report(self.dir, "a.ATest")
        report(self.dir, "a.BTest", failures=1)
        self.assertFalse(task_passed(self.dir))

    def test_skipped_counts_as_fail(self):
        report(self.dir, "a.ATest", skipped=1)
        self.assertFalse(task_passed(self.dir))

    def test_zero_tests_fails_task(self):
        report(self.dir, "a.ATest", tests=0)
        self.assertFalse(task_passed(self.dir))

    def test_malformed_xml_fails_task(self):
        self.dir.mkdir(parents=True)
        (self.dir / "TEST-x.xml").write_text("<testsuite", encoding="utf-8")
        self.assertFalse(task_passed(self.dir))


class PassRatesTest(unittest.TestCase):
    def test_pass_rate_twelfths(self):
        results = {"java": {f"j{i:02d}": i <= 9 for i in range(1, 13)}}
        self.assertEqual(pass_rates(results), {"java": 0.75})


class CliTest(unittest.TestCase):
    def test_cli_missing_solution_scores_zero(self):
        import json
        import subprocess
        import sys
        root_dir = Path(__file__).resolve().parent.parent
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            for task in ("j01", "j02"):
                (d / "benchmark" / "java" / task).mkdir(parents=True)
            report(d / "bench-solutions" / "java" / "j01" / "target" / "surefire-reports", "a.ATest")
            out = d / "rates.json"
            proc = subprocess.run([sys.executable, str(root_dir / "eval" / "score.py"),
                                   "--benchmark", str(d / "benchmark"),
                                   "--solutions", str(d / "bench-solutions"), "--out", str(out)],
                                  capture_output=True, text=True)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            self.assertEqual(json.loads(out.read_text(encoding="utf-8")), {"java": 0.5})


if __name__ == "__main__":
    unittest.main()
