import unittest

from eval.bench_ref import parse_bench_branch, proposal_branch


class ParseBenchBranchTest(unittest.TestCase):
    def test_main_baseline(self):
        self.assertEqual(parse_bench_branch("claude/bench-main-20261113T0200Z"),
                         ("main", "20261113T0200Z"))

    def test_proposal_run(self):
        self.assertEqual(parse_bench_branch("claude/bench-p-20261113T0900Z-20261113T1105Z"),
                         ("p-20261113T0900Z", "20261113T1105Z"))

    def test_rejects_other_branches(self):
        for ref in ["main", "claude/practice-20261113T0900Z", "claude/bench-main",
                    "claude/bench-evil-20261113T0200Z", "claude/bench-main-2026",
                    "claude/bench-p-x-20261113T0200Z", "claude/bench-main-20261113T0200Z;rm"]:
            with self.subTest(ref=ref):
                with self.assertRaises(ValueError):
                    parse_bench_branch(ref)

    def test_proposal_branch_for_config(self):
        self.assertEqual(proposal_branch("p-20261113T0900Z"), "claude/p-20261113T0900Z")
        with self.assertRaises(ValueError):
            proposal_branch("main")


if __name__ == "__main__":
    unittest.main()
