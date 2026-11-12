# AUTO-ENGINEER Practice Gym Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a private GitHub repo in which Claude Code cloud scheduled tasks practise Java hourly, get reviewed by an AI reviewer task, merge only through objective CI gates, and self-improve against a frozen, hidden-test benchmark.

**Architecture:** This folder becomes the `auto-engineer` repo. It holds the agent's instructions (`CLAUDE.md`), its accumulated files (skills, lessons, practice rounds), and protected gate code (`eval/`, `.github/`). Three cloud scheduled tasks run on Gareth's plan: practice (hourly), reviewer (GitHub PR trigger) and benchmark (daily plus API trigger). GitHub Actions enforces the merge gate. A second private repo, `auto-engineer-bench`, holds the hidden benchmark tests, the reference solutions and the score history.

**Tech Stack:** Java 21 (OpenJDK, preinstalled in the cloud VM), Maven, JUnit Jupiter 5.11.4, Python 3.12 standard library only (tests use `unittest`, so no package downloads), GitHub Actions, `gh` CLI, Claude Code cloud scheduled tasks.

**Spec:** `docs/superpowers/specs/2026-11-12-auto-engineer-design.md`

## Global Constraints

- **Outward-facing steps need Gareth's yes at the time they run.** These are: creating repos, installing the GitHub App, changing repo settings or rulesets, creating scheduled tasks, adding secrets, and pulling Docker images. Gareth creates tokens and pastes secrets himself. The executor never handles token values.
- **Repos:** `auto-engineer` and `auto-engineer-bench`, both private, on Gareth's GitHub account.
- **Run id format:** UTC `YYYYMMDDTHHMMZ`, for example `20261113T0900Z`.
- **Branches:**
  - practice: `claude/practice-<run-id>`
  - maintenance: `claude/maint-<run-id>`
  - proposal: `claude/p-<run-id>`. A proposal is always its own PR, never part of a practice PR.
  - benchmark solutions: `claude/bench-<config>-<run-id>`, where `<config>` is `main` or `p-<proposal-run-id>`
- **Benchmark solution layout** on a bench branch: `bench-solutions/java/<task-id>/`, with `pom.xml` copied from the template and the sources under `src/main/java/bench/<pkg>/`.
- **Self-modification paths:** `CLAUDE.md`, `curriculum/**`, `languages/**`, `prompts/**`, `tools/**`, `templates/**` and `tests/**`. These may change only in `claude/p-*` PRs, which need the `benchmark` check. A practice or maintenance PR touching them fails validation.
- **Changing protected paths after Task 5:** every change to a protected path, including the executor's own build steps, merges only during Gareth's ruleset-off window. He switches the ruleset off, merges, and switches it back on. No other route exists.
- **Debug limit:** 3 attempts per practice task.
- **Size of a run:** exactly one practice task per practice run.
- **Maintenance hours:** 00, 06, 12 and 18 UTC. Those runs do upkeep instead of a new task.
- **Curriculum:** levels 1–10, starting at 1. 3 consecutive passes moves up one level, 3 consecutive failures moves down one, and the streak resets after any level change.
- **Cron:**
  - practice: `0 * * * *`. Fallback `0 */2 * * *`, only if Task 12's projection exceeds the Actions quota.
  - benchmark baseline: `0 2 * * *`.
- **Benchmark:** 12 Java tasks. Task pass = every test in that task passes. Pass rate = passed tasks / 12.
- **Proposal merge rule:**
  - The proposal branch has exactly 2 benchmark runs, and both must qualify.
  - A run qualifies when at least one language's pass rate is strictly above the median of `main`'s last 3 baselines (fewer if fewer exist), and no language is below that median.
  - With 0 baselines, the proposal can't merge.
- **Protected paths:**
  - `rules/**`, `review/**`, `.claude/settings.json`, `.claude/settings.local.json`, `.github/**`, `benchmark/**`, `eval/**`, `PAUSE`
  - Add, modify, delete and rename all count.
- **Append-only paths in practice PRs:** `practice/**`, `lessons/entries/**` and `logs/runs/**` may only gain new files.
- **No shared mutable files** in practice PRs. `lessons/RULES.md` and skill merges change only in `claude/maint-*` PRs.
- **Exports:** one `practice/<lang>/<run-id>/export.json` per round. `tools/export.py` builds `exports/solutions.jsonl` on demand, and `exports/` is gitignored.
- **Ignored locally:** `.claude-flow/` (plugin state) is gitignored.

## Review Focus

1. **A PR that renames a protected file away, or deletes `PAUSE`, must fail the guard.** The guard must see both old and new paths (`git diff --no-renames`). Pinned in Task 2.
2. **Overlapping hourly runs must not conflict or rewrite history.** A practice PR that modifies an existing file under the append-only paths fails validation. Pinned in Task 4.
3. **A round that failed after 3 attempts must still merge**, because its lesson is the value. CI runs Maven tests only for rounds whose `result.json` has `"passed": true`, and for every skill touched. Pinned in Task 4.
4. **A corrupt or missing `result.json` must not crash the curriculum script.** The bad file is skipped with a warning on stderr, and with no results the level is 1. Pinned in Task 3.
5. **A benchmark solution that is missing or doesn't compile counts as a failed task, not a scoring crash.** Fewer than 3 baselines uses the median of what exists, and 0 baselines blocks the merge. Pinned in Task 10.

---

## Phase 1: Core practice loop (Tasks 1–8)

Phase 1 ends with hourly practice PRs being reviewed and merged on objective checks.

### Task 1: Repo skeleton, agent instructions and locks

**Files:**
- Create: `.gitignore`, `CLAUDE.md`, `rules/safety.md`, `review/REVIEWER.md`, `.claude/settings.json`, `.github/protected-paths.txt`, `languages/java.md`, `curriculum/java.md`, `lessons/RULES.md`, `skills/java/.gitkeep`, `practice/java/.gitkeep`, `lessons/entries/.gitkeep`, `logs/runs/.gitkeep`, `proposals/.gitkeep`, `templates/java/pom.xml`
- Test: `tests/test_locks.py`

**Interfaces:**
- Produces: `.github/protected-paths.txt`, with one `fnmatch` pattern per line. `#` starts a comment, and `*` matches across `/`. Tasks 2 and 4 read it.
- Produces: `templates/java/pom.xml`. Every practice round, skill and benchmark solution copies it. It pins groupId `ae`, Java release 21, `org.junit.jupiter:junit-jupiter:5.11.4` (test scope) and `maven-surefire-plugin:3.5.2`.

- [ ] **Step 1: Write the failing test** `tests/test_locks.py`
  - `test_protected_list_has_required_patterns`: the parsed patterns ⊇ `{"rules/*", "review/*", ".claude/settings.json", ".claude/settings.local.json", ".github/*", "benchmark/*", "eval/*", "PAUSE"}`.
  - `test_settings_deny_covers_protected`: for each pattern, `.claude/settings.json` `permissions.deny` contains `Edit(<path>)` and `Write(<path>)`. Map `x/*` to `x/**`.
  - `test_claude_md_imports_safety`: `CLAUDE.md` contains the line `@rules/safety.md`.
- [ ] **Step 2: Run it and see it fail.** Run `python -m unittest tests.test_locks -v`. Expected: FAIL (files missing).
- [ ] **Step 3: Write the files.**
  - **`CLAUDE.md`** sections, in order:
    - Mission (from the original prompt).
    - Run modes. Check `PAUSE` first and exit if it exists. Then: practice / maintenance by UTC hour / benchmark when the prompt says so.
    - The core operating loop (9 steps from the original prompt, with N = 3).
    - Practice round: the step list from spec 8.2 with the exact paths above. Get the level from `python tools/curriculum.py java`. Never copy or paraphrase `benchmark/` tasks.
    - Maintenance round.
    - Benchmark mode: check out the branch named in the prompt. For each `benchmark/java/<id>/TASK.md`, write a solution under `bench-solutions/java/<id>/`. Never search for or guess hidden tests. Push to `claude/bench-<config>-<run-id>`, and open no PR.
    - Proposal format: change, reason, expected benefit, and benchmark evidence filled in by CI. A proposal goes in its own PR from `claude/p-<run-id>`.
    - Output format (from the original prompt).
    - Branch and PR rules: push your branch, open a PR to `main`, never push to `main`.
  - **`rules/safety.md`:** the original prompt's SAFETY & CONDUCT and the "NEVER modify" rule, verbatim. Add: never fake results, and never touch protected paths.
  - **`review/REVIEWER.md`:** the checklist from spec 8.3, plus the verdict procedure (written in Task 7).
  - **`languages/java.md`:** build, test and format commands (`mvn -q -B test`), idioms and pitfalls.
  - **`curriculum/java.md`:** a topic list for levels 1–10, from fundamentals through the standard library, tooling, idiomatic patterns and concurrency.
  - **`.gitignore`:** `.claude-flow/`, `exports/`, `target/`, `__pycache__/`.
- [ ] **Step 4: Run the test and see it pass.** Same command. Expected: `OK`.
- [ ] **Step 5: Initialise git and commit.** `git init -b main`, then `git add -A`, then `git commit -m "chore: scaffold auto-engineer repo with locks and agent instructions"`. This commit also includes the spec and this plan.

### Task 2: Guard (protected-path check)

**Files:**
- Create: `eval/guard.py`, `.github/workflows/guard.yml`
- Test: `tests/test_guard.py`

**Interfaces:**
- Consumes: `.github/protected-paths.txt` (Task 1).
- Produces: `protected_hits(changed: list[str], patterns: list[str]) -> list[str]` and `load_patterns(path: Path) -> list[str]`. CLI: `python eval/guard.py --patterns <file> --changed <file>` exits 0 when clean. Otherwise it prints each hit and exits 1.

- [ ] **Step 1: Write the failing tests**
  - `test_clean_change`: `protected_hits(["skills/java/x/README.md"], P) == []`.
  - `test_nested_protected`: `"benchmark/java/j01/TASK.md"` is a hit.
  - `test_pause_delete_is_hit`: `["PAUSE"]` gives `["PAUSE"]`.
  - `test_rename_both_sides`: the changed list from `--no-renames` for `rules/safety.md` → `notes/safety.md` hits `rules/safety.md`.
  - `test_comments_and_blanks_ignored`: in `load_patterns`.
  - `test_cli_exit_codes`.
- [ ] **Step 2: Run and see them fail.** Run `python -m unittest tests.test_guard -v`. Expected: FAIL (ImportError).
- [ ] **Step 3: Implement** with `fnmatch.fnmatchcase`.
- [ ] **Step 4: Run and see them pass.** Expected: `OK`.
- [ ] **Step 5: Write `guard.yml`.**
  - Trigger: `pull_request_target` on `[opened, synchronize, reopened]`, base `main`. Permissions: `contents: read`.
  - Check out the **base** ref only, never PR code.
  - Get the changed files with `gh api repos/${{ github.repository }}/pulls/${{ github.event.number }}/files --paginate --jq '.[] | .filename, (.previous_filename // empty)'`, which covers renames.
  - Run `python eval/guard.py`. Job name: `guard`.
- [ ] **Step 6: Commit.** `git commit -m "feat: guard protected paths in PRs"`

### Task 3: Curriculum level and export builder

**Files:**
- Create: `tools/curriculum.py`, `tools/export.py`
- Test: `tests/test_curriculum.py`, `tests/test_export.py`

**Interfaces:**
- Produces: `result.json` schema:
  ```json
  {"run_id": "20261113T0900Z", "language": "java", "level": 1, "topic": "...", "task": "...", "passed": true, "attempts": 2}
  ```
  `attempts` is an integer from 1 to 3.
- Produces: `compute_level(results: list[dict]) -> int` and `load_results(root: Path, language: str) -> list[dict]`. `load_results` reads `practice/<language>/*/result.json`, sorted by `run_id`, and skips unreadable files with a warning on stderr. CLI: `python tools/curriculum.py java` prints the level.
- Produces: `build_jsonl(root: Path, out: Path) -> int`, which returns the number of lines written. CLI: `python tools/export.py`.

- [ ] **Step 1: Write the failing tests**
  - `test_empty_is_level_1`.
  - `test_three_passes_up`: P,P,P → 2.
  - `test_streak_resets_after_change`: P,P,P,P,P → 2.
  - `test_three_fails_down_clamped`: F,F,F at level 1 → 1.
  - `test_mixed_breaks_streak`: P,P,F,P,P → 1.
  - `test_cap_at_10`.
  - `test_corrupt_file_skipped`: a malformed JSON file plus one valid one → 1 result, and stderr contains `skipping`.
  - Export: `test_build_jsonl_lines` (2 rounds → 2 lines, each valid JSON).
- [ ] **Step 2: Run and see them fail.** Run `python -m unittest tests.test_curriculum tests.test_export -v`.
- [ ] **Step 3: Implement both modules.**
- [ ] **Step 4: Run and see them pass.** Expected: `OK`.
- [ ] **Step 5: Commit.** `git commit -m "feat: curriculum level from round results; export builder"`

### Task 4: Round validator and tests workflow

**Files:**
- Create: `eval/validate_round.py`, `.github/workflows/tests.yml`
- Test: `tests/test_validate_round.py`

**Interfaces:**
- Consumes: the `result.json` schema (Task 3) and the template pom (Task 1).
- Produces:
  - `validate_round(repo: Path, round_dir: Path) -> list[str]`. Errors cover:
    - `result.json` matches the schema;
    - `pom.xml` exists;
    - there is at least 1 `src/test/java/**/*Test.java`;
    - `lessons/entries/<run_id>.md` and `logs/runs/<run_id>.md` exist.
  - `append_only_violations(changes: list[tuple[str, str]], head_ref: str) -> list[str]`. `changes` holds `(status, path)` from `git diff --name-status --no-renames`. A non-`A` status under the append-only paths is a violation, except on `claude/maint-*` heads, where `lessons/RULES.md` and `skills/**` may change. Append-only paths stay add-only even there.
  - `self_mod_violations(changes: list[tuple[str, str]], head_ref: str) -> list[str]`. Any change under the self-modification paths is a violation unless `head_ref` starts with `claude/p-`.
  - `maven_dirs_to_test(repo: Path, changes) -> list[Path]`. Returns the dirs of added rounds with `passed: true`, plus every skill dir with any change.
  - CLI `python eval/validate_round.py --base <sha> --head-ref <name>` prints errors, writes `maven-dirs.txt` and exits 1 on any error.

- [ ] **Step 1: Write the failing tests.**
  - `test_valid_round` (fixture in a temp dir).
  - `test_missing_lesson_entry`.
  - `test_attempts_out_of_range` (4 is an error).
  - `test_modify_existing_practice_file_rejected` (`M practice/java/X/Foo.java`).
  - `test_maint_may_edit_rules`.
  - `test_failed_round_not_tested_but_valid`: `passed: false` → no errors and not in `maven_dirs_to_test`.
  - `test_changed_skill_is_tested`.
  - `test_practice_pr_editing_claude_md_rejected`: `M CLAUDE.md` on `claude/practice-X` → 1 violation; the same change on `claude/p-X` → none.
- [ ] **Step 2: Run and see them fail.** Run `python -m unittest tests.test_validate_round -v`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and see them pass.**
- [ ] **Step 5: Write `tests.yml`.**
  - Trigger: `pull_request` to `main`. One job named `tests`, with `actions/setup-java` temurin 21 and Maven cache.
  - Steps:
    1. `python -m unittest discover -s tests`
    2. `python eval/validate_round.py ...`
    3. `mvn -q -B test` in each dir from `maven-dirs.txt`
  - Using one job keeps the Actions minutes low (spec 8.8).
- [ ] **Step 6: Commit.** `git commit -m "feat: validate practice rounds and run their tests in CI"`

### Task 5: Publish repos and lock `main` (needs Gareth)

**Files:**
- Modify: none. These are GitHub-side actions.

- [ ] **Step 1: Create the main repo.** Ask Gareth, then run `gh repo create auto-engineer --private --source . --push`.
- [ ] **Step 2: Create the bench repo.** Ask Gareth, then run `gh repo create auto-engineer-bench --private`. Push an initial `README.md` and `scores/history.csv` with header `timestamp,config,run_id,language,pass_rate`.
- [ ] **Step 3: Gareth installs the Claude GitHub App on `auto-engineer` only, not the bench repo.** Verify with `gh api /user/installations --jq '.installations[] | {app_slug, permissions}'`. **Record whether `administration` appears in the permissions.** This is spec 5, unverified item 4. If it shows `write`, stop and tell Gareth, because the locks would not hold.
- [ ] **Step 4: Apply repo settings.** Ask Gareth, then run `gh api -X PATCH repos/{owner}/auto-engineer -f allow_auto_merge=true -f delete_branch_on_merge=true`.
- [ ] **Step 5: Lock `main` with a ruleset.** Ask Gareth, then create a ruleset on `main` via `gh api -X POST repos/{owner}/auto-engineer/rulesets` with:
  - require a PR;
  - required status checks `guard` and `tests` (`ai-review` is added in Task 7);
  - block force pushes and deletion;
  - **empty bypass list**.
- [ ] **Step 6: Verify the lock.** A direct `git push origin main` of a dummy commit is rejected. Revert the commit locally.

### Task 6: Probe the unverified constraints

**Files:**
- Create: `logs/probe-<run-id>.md`
- Modify: `docs/superpowers/specs/2026-11-12-auto-engineer-design.md` (section 5, moving items to "Verified").

- [ ] **Step 1: Run a one-off probe.** Ask Gareth, then create a cloud task on `auto-engineer` with "Run now" and no schedule. Its prompt tries the following and reports each result verbatim:
  - **(a)** `gh api repos/{owner}/auto-engineer` and `curl -s -o /dev/null -w '%{http_code}' https://api.github.com/repos/{owner}/auto-engineer`. Is GitHub API access available, and with what auth?
  - **(b)** Can it set a commit status on a throwaway `claude/probe` branch?
  - **(c)** `git clone https://github.com/{owner}/auto-engineer-bench`. It must fail for the hidden-tests design.
  - **(d)** `mvn -v` and `java -version`.
- [ ] **Step 2: Gareth checks the Actions minutes quota** at github.com/settings/billing and reports the monthly included minutes.
- [ ] **Step 3: Record everything** in `logs/probe-<run-id>.md` and update spec section 5. Commit via a `claude/`-style branch PR. The spec is not protected, so this goes through the gate.
- [ ] **Step 4: Decide.**
  - If (c) succeeded, use the fallback in spec 8.6: tests in `benchmark/` and scores as Actions artifacts. Note it in the probe log.
  - If (a) or (b) failed, use the file-based verdict channel in Task 7.

### Task 7: Reviewer task and `ai-review` check

**Files:**
- Modify: `review/REVIEWER.md` (the verdict procedure)
- Create: `.github/workflows/ai-review.yml`, `prompts/reviewer.txt` (the task prompt, kept for reference)

**Interfaces:**
- Consumes: the probe results (Task 6).
- Produces: a required status named `ai-review` on the PR head.

- [ ] **Step 1: Pick the variant from the probe.**
  - **API variant:** the reviewer runs `gh api -X POST repos/{owner}/auto-engineer/statuses/<head_sha> -f state=success|failure -f context=ai-review -f description=<one line>`, posts its reasons with `gh pr comment`, and on reject runs `gh pr close`. In this variant `ai-review.yml` is not needed.
  - **File variant:** the reviewer pushes `reviews/<pr-number>.json` to branch `claude/reviews`. The file holds `{"pr": n, "head_sha": "...", "verdict": "approve"|"reject", "reasons": "..."}`. `ai-review.yml` is triggered on push to `claude/reviews`. It reads the file and sets the `ai-review` status with `GITHUB_TOKEN`, then comments or closes the PR.
- [ ] **Step 2: Write the feedback channel into `CLAUDE.md` practice step 2.** API variant: `gh pr list --state closed --search "is:unmerged" -L 5` plus their comments. File variant: read the last 5 `reviews/*.json` with verdict `reject` from `claude/reviews`.
- [ ] **Step 3: Create the reviewer task.** Ask Gareth, then create the cloud task with a GitHub trigger: PR opened and synchronize, base `main`, head prefix `claude/practice-`, `claude/maint-` and `claude/p-`. Prompt: `Review PR #{number} following review/REVIEWER.md exactly. Wait for the guard and tests checks to finish first.`
- [ ] **Step 4: Gate merges on the review.** Ask Gareth, then add `ai-review` to the ruleset's required checks.
- [ ] **Step 5: Test both verdicts.** Open a hand-made PR from a `claude/practice-test` branch containing one valid round. Expected: guard, tests and ai-review are green, and it auto-merges after `gh pr merge --auto --squash`. Then open a second PR with a trivial test (`assertTrue(true)`). Expected: ai-review fails with a reason, and the PR is closed.
- [ ] **Step 6: Commit** the workflow and doc changes through a PR, merged in Gareth's ruleset-off window (they touch `review/` and `.github/`).

### Task 8: Practice task live

**Files:**
- Create: `env/setup.sh`, `prompts/practice.txt`

- [ ] **Step 1: Write `env/setup.sh`.** It runs `mvn -q -B -f templates/java/pom.xml dependency:go-offline || true` to warm the Maven cache, and must finish within 5 minutes. Gareth pastes it into the Default environment's Setup script.
- [ ] **Step 2: Create the practice task.** Ask Gareth, then create the cloud task on cron `0 * * * *`. Prompt (`prompts/practice.txt`): `Run one AUTO-ENGINEER round as defined in CLAUDE.md. Use run id = current UTC time as YYYYMMDDTHHMMZ. Push your branch, open a PR to main, and enable auto-merge with gh pr merge --auto --squash.`
- [ ] **Step 3: Check one round end to end.** Use "Run now" once. Expected:
  - PR `claude/practice-<run-id>` exists;
  - it contains `practice/java/<run-id>/{result.json, export.json, pom.xml, src/...}`, a lesson entry and a run log;
  - guard, tests and ai-review are green, and it merged;
  - `python tools/curriculum.py java` on `main` prints `1`.
- [ ] **Step 4: Check the kill switch.** Gareth commits `PAUSE` to `main` (ruleset off, merge, ruleset on). The next "Run now" exits at step 1 with no PR. Then Gareth removes `PAUSE` the same way.
- [ ] **Step 5: Tell Gareth** to check that extra usage credits are off (spec 8.7). Leave the schedule enabled.

## Phase 2: Benchmark and self-modification (Tasks 9–12)

### Task 9: Java benchmark (needs Gareth's one-time review)

**Files:**
- Create in `auto-engineer`: `benchmark/java/<id>/TASK.md` × 12
- Create in `auto-engineer-bench`: `java/<id>/src/test/java/bench/<pkg>/*Test.java` × 12, `java/<id>/reference/src/main/java/bench/<pkg>/*.java` × 12, `.github/workflows/validate.yml`

Each `TASK.md` fixes the package `bench.<pkg>`, the class and the exact public signatures. The table lists each task's key hidden-test assertions.

| id | Signature | Must be tested |
|---|---|---|
| j01-roman | `RomanNumerals.toRoman(int)`, `fromRoman(String)` | 1994↔"MCMXCIV"; 3999 max; 0 and 4000 throw `IllegalArgumentException` |
| j02-wordfreq | `WordFrequency.top(String text, int k) -> List<Map.Entry<String,Integer>>` | case-insensitive; punctuation stripped; ties alphabetical; k > distinct returns all |
| j03-lru | `LruCache<K,V>(int capacity)`: `get(K) -> Optional<V>`, `put(K,V)`, `size()` | evicts least-recently used; `get` refreshes recency; capacity 0 throws |
| j04-csv | `CsvParser.parseLine(String) -> List<String>` | quoted commas; `""` escape; empty fields; unterminated quote throws |
| j05-intervals | `Intervals.merge(List<int[]>) -> List<int[]>` | unsorted input; touching intervals merge; empty in → empty out |
| j06-lcs | `Lcs.length(String,String)`, `Lcs.sequence(String,String)` | "ABCBDAB"/"BDCABA" → 4; empty strings → 0 / "" |
| j07-expr | `ExprEvaluator.eval(String) -> double` | precedence; parentheses; unary minus; malformed throws `IllegalArgumentException` |
| j08-graph | `Graph.shortestPath(Map<String,Map<String,Integer>>, String, String) -> List<String>` | Dijkstra path; unreachable → empty list; src == dst → `[src]` |
| j09-bank | `Bank`: `open(String id, BigDecimal initial)`, `transfer(String from, String to, BigDecimal amt) throws InsufficientFundsException`, `balance(String)` | checked exception; no partial transfer; unknown id throws `IllegalArgumentException` |
| j10-counter | `ConcurrentWordCounter.count(List<String> lines, int threads) -> Map<String,Long>` | equals the sequential count for 10,000 lines × 8 threads; threads < 1 throws |
| j11-streams | `record Order(String customer, BigDecimal amount, LocalDate date)`; `OrderStats.totalByCustomer`, `topCustomers(int n)`, `monthlyTotals() -> SortedMap<YearMonth,BigDecimal>` | totals exact (BigDecimal); top-n order; months sorted |
| j12-fileindex | `FileIndexer.index(Path dir) -> Map<String, Set<Path>>` | recursive; only `.txt`; words lowercased; uses `@TempDir` |

- [ ] **Step 1: Write the 12 `TASK.md` files and the hidden tests.**
- [ ] **Step 2: Write the reference solutions.**
- [ ] **Step 3: Write `validate.yml` in the bench repo.** For each task it does two things:
  - (a) Copy the reference solution and the tests into a template-pom project. `mvn test` must pass.
  - (b) Use a stub where every method throws `UnsupportedOperationException`. `mvn test` must fail.
  - Push and verify the workflow is green.
- [ ] **Step 4: Gareth reviews** `benchmark/java/` and the bench repo tests once, and his edits are applied. Commit `benchmark/` through a PR with the ruleset temporarily off. That is Gareth's action, since the path is protected.

### Task 10: Scoring, history and the merge rule

**Files:**
- Create: `eval/score.py`, `eval/history.py`, `eval/compare.py`
- Test: `tests/test_score.py`, `tests/test_history.py`, `tests/test_compare.py`

**Interfaces:**
- Produces:
  - `task_passed(report_dir: Path) -> bool`. True when at least 1 surefire `TEST-*.xml` exists, and every file has `tests > 0` and `failures == errors == skipped == 0`. Missing dir → False.
  - `pass_rates(results: dict[str, dict[str, bool]]) -> dict[str, float]`, mapping language → task id → passed.
  - `append_rows(csv: Path, timestamp: str, config: str, run_id: str, rates: dict[str, float]) -> None`.
  - `baselines(csv: Path, n: int = 3) -> list[dict[str, float]]`, the last n `config == "main"` runs.
  - `proposal_runs(csv: Path, config: str) -> list[dict[str, float]]`.
  - `should_merge(runs: list[dict[str, float]], base: list[dict[str, float]]) -> bool`, the rule from Global Constraints.
  - CLI: `python eval/compare.py --csv <file> --config p-<id>`, which prints `merge` or `hold` and exits 0 or 1.

- [ ] **Step 1: Write the failing tests.**
  - `test_missing_report_dir_fails_task`.
  - `test_compile_error_no_reports_fails_task`.
  - `test_skipped_counts_as_fail`.
  - `test_pass_rate_twelfths`: 9/12 → 0.75.
  - `test_two_runs_required`: 1 run → False.
  - `test_improve_no_regress_merges`: base medians {java: 0.5}, runs [{java: 0.58}, {java: 0.67}] → True.
  - `test_one_lucky_run_holds`: [{java: 0.75}, {java: 0.5}] → False.
  - `test_regression_blocks`: with two languages, one up and one down → False.
  - `test_zero_baselines_holds`.
  - `test_fewer_than_3_baselines_uses_available`.
  - `test_history_roundtrip`.
- [ ] **Step 2: Run and see them fail.** Run `python -m unittest tests.test_score tests.test_history tests.test_compare -v`.
- [ ] **Step 3: Implement.** Use `xml.etree.ElementTree` and `statistics.median`.
- [ ] **Step 4: Run and see them pass.**
- [ ] **Step 5: Commit through a PR.** `eval/` is protected, so this is Gareth's ruleset-off merge, which is the same process as Task 9 Step 4.

### Task 11: Benchmark runs and proposal gate

**Files:**
- Create: `.github/workflows/benchmark-score.yml`, `.github/workflows/proposal-bench.yml`, `prompts/benchmark.txt`

- [ ] **Step 1: Gareth adds two secrets.**
  - `BENCH_TOKEN`: a fine-grained PAT with contents read+write on `auto-engineer-bench` only.
  - `FIRE_TOKEN`: the benchmark task's `/fire` bearer token, from step 2.
- [ ] **Step 2: Create the benchmark task.** Ask Gareth, then create the cloud task with:
  - schedule `0 2 * * *`, with prompt `Run AUTO-ENGINEER benchmark mode on branch main, config main.`;
  - an API trigger, whose `text` carries the branch and config for proposal runs.
- [ ] **Step 3: Write `benchmark-score.yml`.**
  - Trigger: push to `claude/bench-*`.
  - Check out the solutions and the bench repo with `BENCH_TOKEN`.
  - For each task: copy the hidden tests into the solution project, run `mvn -B test`, and keep going on failure.
  - Then `score.py` → `history.py append_rows` → commit `scores/history.csv` to the bench repo.
  - If the config is `p-<id>` and it now has 2 runs, run `compare.py`. Set the commit status `benchmark` on the proposal PR head to success on `merge` and failure on `hold`, then post the rates as a PR comment.
- [ ] **Step 4: Write `proposal-bench.yml`.** Trigger: `pull_request_target` on opened, synchronize and reopened, base `main`. It never checks out PR code.
  - Head matches `claude/p-*`: fire the benchmark task twice through `/fire` with `FIRE_TOKEN`, with text `Run benchmark mode on branch <head>, config p-<id>.`, and set the `benchmark` status to `pending`.
  - Any other head: set `benchmark` = success, with description `not a proposal`.
- [ ] **Step 5: Require `benchmark` on `main`.** Ask Gareth, then add `benchmark` to the `main` ruleset's required checks. Rulesets match base branches, not head branches, so the check is required on every PR, and step 4 passes it at once for non-proposals.
- [ ] **Step 6: Test the whole path.**
  - Run the baseline once with "Run now". Expected: a row in `scores/history.csv` with `config=main`.
  - Then a hand-made proposal PR whose `CLAUDE.md` change is a no-op. Expected: 2 bench runs, and `benchmark` = failure (`hold`), because equal is not better.
- [ ] **Step 7: Commit** through a PR, merged in Gareth's ruleset-off window (it touches `.github/`).

### Task 12: Actions minutes check after week 1

- [ ] **Step 1: Measure.** Seven days after Task 8 goes live, add up the minutes with `gh api repos/{owner}/auto-engineer/actions/runs --paginate --jq '.workflow_runs[] | select(.created_at > "<start>") | .id'`. For each run id, use `gh api .../runs/<id>/timing`. Project the monthly total.
- [ ] **Step 2: Decide the cadence.** If the projection is above the quota recorded in Task 6, change the practice task's cron to `0 */2 * * *`, after asking Gareth.
- [ ] **Step 3: Record it.** Write `logs/ci-minutes-<date>.md` with the numbers and the decision, and commit through a PR.
