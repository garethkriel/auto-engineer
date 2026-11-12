import tempfile
import unittest
from pathlib import Path

from eval.history import append_rows, baselines, proposal_runs


class HistoryTest(unittest.TestCase):
    def test_history_roundtrip(self):
        with tempfile.TemporaryDirectory() as d:
            csv = Path(d) / "scores" / "history.csv"
            for i, rate in enumerate([0.25, 0.5, 0.5, 0.75]):
                append_rows(csv, f"2026-11-1{i}T02:00:00Z", "main", f"r{i}", {"java": rate})
            append_rows(csv, "2026-11-14T05:00:00Z", "p-x", "p1", {"java": 0.8, "python": 0.1})

            self.assertEqual(baselines(csv, n=3), [{"java": 0.5}, {"java": 0.5}, {"java": 0.75}])
            self.assertEqual(proposal_runs(csv, "p-x"), [{"java": 0.8, "python": 0.1}])
            self.assertEqual(csv.read_text(encoding="utf-8").splitlines()[0],
                             "timestamp,config,run_id,language,pass_rate")

    def test_missing_file_is_empty(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(baselines(Path(d) / "none.csv"), [])


if __name__ == "__main__":
    unittest.main()
