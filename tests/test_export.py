import json
import tempfile
import unittest
from pathlib import Path

from tools.export import build_jsonl


class ExportTest(unittest.TestCase):
    def test_build_jsonl_lines(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for run_id in ["20261113T0100Z", "20261113T0200Z"]:
                folder = root / "practice" / "java" / run_id
                folder.mkdir(parents=True)
                (folder / "export.json").write_text(json.dumps(
                    {"language": "java", "task": run_id, "code": {}, "tests": {},
                     "passed": True, "reasoning_summary": "s"}), encoding="utf-8")
            out = root / "exports" / "solutions.jsonl"

            count = build_jsonl(root, out)

            self.assertEqual(count, 2)
            lines = out.read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(lines), 2)
            self.assertEqual([json.loads(ln)["task"] for ln in lines],
                             ["20261113T0100Z", "20261113T0200Z"])


if __name__ == "__main__":
    unittest.main()
