# AUTO-ENGINEER Practice Gym — Design

- Date: 2026-11-12
- Status: approved by Gareth, 2026-11-12
- Source: the AUTO-ENGINEER prompt Gareth pasted on 2026-11-12, plus decisions made in conversation that day.
- Marked **[Claude's call]**: choices Claude made that Gareth has not explicitly confirmed.

## 1. Intent

Gareth wants an autonomous coding-practice agent built from the AUTO-ENGINEER prompt. It practises on its own, in the background, in a repo dedicated to it, and gets measurably better at programming over time, with no human in the loop.

"Better" here means: a growing library of verified skills, lessons learned, per-language profiles, and refinements to its own workflow, all stored as files in git. The underlying Claude model does not change; each run starts from the files the previous runs left behind. Progress is measured by a frozen benchmark the agent cannot edit.

## 2. Goals

1. Runs unattended as Claude Code **cloud scheduled tasks** on Gareth's Claude plan. Gareth's PC can be off.
2. Each run executes one practice round of the AUTO-ENGINEER loop: understand, recall, plan, test first, implement, execute and verify, debug (max 3 attempts), reflect, store.
3. **Java first.** Python, JavaScript/TypeScript (Node) and C# (.NET) come later, each once its benchmark exists.
4. A **frozen benchmark** per language, scored as pass rate. The agent can run it, never edit it.
5. An **AI reviewer** (a separate scheduled task with its own instructions) reviews every change instead of Gareth.
6. **Self-changes approve themselves.** A change to the agent's own workflow is merged when the AI reviewer approves it and the benchmark shows improvement with no regression. No human approval step.
7. Full history in git, so any change can be rolled back.
8. A readable log of every run.

## 3. Non-goals

- Not used on Gareth's other projects. Skills and lessons stay in this repo.
- No local Docker or Windows-scheduler setup.
- No fine-tuning. Verified solutions are exported as data files only (section 8.9).
- No physical or hardware engineering simulation in v1.
- Python, JS/TS and C# benchmarks are not part of the first build. They are added later as separate pieces of work.

## 4. Anti-goals (failure even if it technically works)

- The score rises because the agent edited tests, benchmark tasks, scoring code, CI, or its own safety and permission rules.
- The score rises because benchmark answers leak into the skill library (memorisation), not because skill improved.
- Fake results: claiming tests passed without real execution.
- The library fills with duplicate, unverified, or low-value entries.
- Benchmark noise is mistaken for improvement, so random changes get merged as "improvements".

## 5. Constraints

### Verified on 2026-11-12 from code.claude.com docs

- Scheduled tasks need a Pro, Max, Ultra, Team or Enterprise plan.
- Schedules can't be more frequent than hourly: cron expressions that fire more than once per hour are rejected. Account cap: 100 scheduled runs per hour.
- Runs draw down subscription usage the same way interactive sessions do. When the usage window is exhausted, further runs are rejected until it resets. **If usage credits (extra usage) are enabled on the account, runs continue on metered overage.**
- Every run clones the repo fresh from the default branch (unless the prompt says otherwise). Nothing persists between runs except what is pushed to GitHub.
- By default, Claude pushes to branches prefixed `claude/`; the prompt can direct pushes to other branches. GitHub branch protection or rulesets are the way to restrict which branches can be updated. The proxy rejects branch deletion and tag pushes.
- A run can be triggered by schedule, API (`/fire` endpoint), or GitHub events (pull request, release, issue), with filters on author, title, base/head branch, labels, draft and merged. GitHub event triggers need the Claude GitHub App installed on the repo. Creating them from the CLI needs Claude Code v2.1.225+.
- Cloud VM: Ubuntu 24.04, 4 vCPU, 16 GB RAM, 30 GB disk. **OpenJDK 21 with Maven and Gradle is preinstalled.** The Default environment has Limited network, with package-manager hosts (including Maven Central) allowed.
- An optional setup script per environment (Bash, runs as root, up to 64 KB, cached if it finishes in about 5 minutes).
- Foreground commands time out after 2 minutes by default, extendable to 10 minutes.

### Verified on Gareth's PC, 2026-11-12

- Claude Code 2.1.317 is installed, with `claude setup-token` available.
- `gh` 2.102.0 is logged in to Gareth's GitHub account with `repo` and `workflow` scopes.
- No GitHub repo exists for this project yet. This folder is not a git repo.

### Not verified (the builder must check before relying on them)

- Whether a cloud run can call the GitHub REST API (set commit statuses, add labels, merge PRs), or only push via git. This decides how the reviewer's verdict reaches GitHub (section 8.5).
- Whether a cloud run can clone a GitHub repo that was **not** added to its scheduled task. This decides whether hidden benchmark tests can be kept from the agent (section 8.6).
- GitHub Actions minute quota for private repos on Gareth's GitHub plan. Hourly PRs mean roughly 720 CI runs a month (section 8.8).
- Whether the Claude GitHub App's access lets a cloud run change repo settings or rulesets. If it does, the agent could switch off its own locks (section 8.4).

## 6. Approaches considered

| | Approach | Trade-off | Outcome |
|---|---|---|---|
| A | Docker container on Gareth's PC running a `claude -p` loop | Truly continuous, with read-only mounts for hard locks. The PC must stay on and awake. | Claude recommended it. Gareth chose B. |
| B | Claude Code cloud scheduled tasks | The PC can be off, and the VM is sandboxed. Hourly at most, and state persists only via git. | **Chosen by Gareth.** |
| C | Windows Task Scheduler running sessions on the host | No sandbox: an unsupervised agent with every permission on Gareth's real machine. | Dropped. |

## 7. Decided by Gareth

- Practise in this folder's own repo, not on other projects.
- Run "24/7 automatically in the background". On approach B this becomes hourly runs, the most frequent the platform allows.
- An AI agent reviews instead of Gareth, so practice continues without waiting.
- A frozen benchmark scored as pass rate per language, with changes kept only on no regression. Java first.
- Self-changes don't need Gareth's approval. The AI reviewer approves them if they improve the agent.
- Debug limit of 3 attempts.
- Run on Gareth's Claude plan, not an API key.
- Use a cloud routine (approach B).
- Gareth reviews the benchmark once, when it is first written. After that it is locked.
- The repo is private, accepting that the GitHub Actions minute quota may force fewer practice runs.

## 8. Design

### 8.1 Repo and layout

A new **private** GitHub repo on Gareth's account (private: Gareth's decision), named `auto-engineer` **[Claude's call: name]**, created from this folder.

```
CLAUDE.md                  operating loop and workflow (agent may change via proposals)
rules/safety.md            safety and conduct rules, imported by CLAUDE.md      [protected]
review/REVIEWER.md         reviewer agent instructions and checklist             [protected]
.claude/settings.json      permission deny rules                                [protected]
.github/                   CI workflows, CODEOWNERS                              [protected]
benchmark/<lang>/tasks/    benchmark task statements                             [protected]
eval/                      scoring scripts                                      [protected]
PAUSE                      kill switch: present = every run exits at once        [protected]
languages/<lang>.md        Language Profile: build/run/test/lint/format commands, idioms, pitfalls
curriculum/<lang>.md       topic list per level (changed only by proposals or maintenance rounds)
skills/<lang>/<name>/      reusable verified solution: README (description, tags), source, tests
lessons/entries/<run-id>.md  one lesson per run: what worked, what failed, root cause, rule learned
lessons/RULES.md           distilled general rules from recurring lessons (maintenance rounds only)
practice/<lang>/<run-id>/  one round's task, tests, code, results
proposals/<id>.md          self-modification proposals with evidence
logs/runs/<run-id>.md      human-readable log of each run
exports/solutions.jsonl    verified solutions and reasoning summaries for possible future fine-tuning
```

"Protected" means a PR touching that path can't merge (section 8.4). The agent may read protected files.

**No shared mutable files in a practice round [Claude's call]:** hourly runs can overlap with an unmerged or rejected earlier PR. So a practice round only adds new files (its `practice/` folder, its lesson entry, its run log, new or changed skills). The current curriculum level is computed by a script from merged `practice/<lang>/*/result.json` files instead of stored in one file every run would edit. `RULES.md` and skill merges change only in maintenance rounds. This keeps hourly PRs from conflicting with each other.

### 8.2 The practice task (hourly)

A scheduled task on cron `0 * * * *` **[Claude's call: top of every hour]**, on the Default cloud environment. Its prompt tells it to follow `CLAUDE.md`. Each run:

1. Exits at once if `PAUSE` exists.
2. Reads the curriculum, Language Profile, relevant skills, `lessons/RULES.md`, and feedback from recently rejected PRs (section 8.5).
3. Picks or generates **one** practice task, slightly above the current level for the active language. Tasks must not copy or paraphrase benchmark tasks.
4. Writes tests first, then the implementation. Runs build, tests and lint for real, and debugs up to 3 attempts.
5. Writes `lessons/entries/<run-id>.md`, whether it passed or failed.
6. If it passed and the solution is reusable, adds a skill, including its tests.
7. Writes `practice/<lang>/<run-id>/result.json` (task, level, topic, pass/fail, attempts used), which feeds the curriculum level.
8. Optionally writes a self-modification proposal (section 8.6).
9. Writes `logs/runs/<run-id>.md`: task, plan, commands run, results, what was stored.
10. Pushes branch `claude/practice-<run-id>` and opens a PR to `main` with a summary.

**Maintenance rounds [Claude's call]:** runs at 00:00, 06:00, 12:00 and 18:00 UTC do the self-improvement upkeep instead of a new task. They merge duplicate skills, strengthen tests and docs, review lessons for recurring mistakes, and update `RULES.md`.

**Curriculum levels [Claude's call]:** each language has levels 1–10 with topics. Three consecutive passes at a level move it up one. Three consecutive failures move it down one and record a lesson. Java topic order starts with fundamentals, then standard library, tooling (Maven, JUnit 5), and idiomatic patterns, as the original prompt describes. The builder writes the full topic list.

One round per run **[Claude's call]** keeps each run short and plan usage predictable.

### 8.3 The reviewer task (on each PR)

A second scheduled task with a **GitHub trigger**: pull request opened or updated, base `main`, head branch prefix `claude/practice-`. It follows `review/REVIEWER.md`, which the practice agent cannot change. Its checklist:

- CI results are real and green. The reviewer reads them rather than trusting claims in the PR.
- No protected path is touched (CI also enforces this).
- Tests actually exercise the code: not trivial, not asserting on mocks, not skipped.
- New skills are reusable, documented, not duplicates, and contain no benchmark solutions.
- Lessons are accurate, and the root cause is stated.
- Proposals carry valid benchmark evidence (section 8.6).

Verdict: **approve** (section 8.5 says how that reaches GitHub) or **reject**, with written reasons.

### 8.4 Merge gate on `main`

Branch protection or a ruleset on `main`, with no bypass for anyone, including admin.

**Same-identity problem:** the cloud tasks act on GitHub through Gareth's account (via the Claude GitHub App), so GitHub can't tell an agent PR from a Gareth PR. Any exemption by author or branch name could be used by the agent too. So no PR can change protected paths. When Gareth wants to change one (for example to add harder benchmark tasks), he turns the ruleset off in GitHub settings, merges his change, and turns it back on. This only holds if the cloud tasks can't change repo settings. The builder verifies that the Claude GitHub App's installation has no administration permission on the repo (section 5).

Required checks **[Claude's call: exact mechanics left to builder, principle fixed]**:

1. **guard**: runs from `main`'s copy of the workflow (for example `pull_request_target`), so a PR can't alter it. It never executes PR code. It fails if the PR touches any protected path.
2. **tests**: builds and runs every changed skill's tests and the round's tests (Java: Maven or Gradle with JUnit 5).
3. **ai-review**: reflects the reviewer task's verdict.
4. **benchmark**: required only for PRs that contain a proposal (section 8.6).

Auto-merge merges once all required checks pass. Merged branches are deleted by GitHub settings, since the cloud proxy can't delete branches.

### 8.5 Reviewer verdict and feedback channel

GitHub does not let an account approve its own PR, and both tasks act as Gareth's account. So the verdict must be a status check, not a GitHub approval.

- **Preferred:** the reviewer sets a commit status `ai-review` (success or failure) on the PR head via the GitHub API, and posts its reasons as a PR comment.
- **If cloud runs can't call the GitHub API:** the builder designs another channel the practice task can't forge in normal operation. For example, the reviewer pushes a verdict file to a branch only it writes, and a CI job reads it to set the check.

Rejected PRs are closed. The next practice run must see the rejection reasons, by reading recent closed-PR comments or a feedback file the reviewer maintains, and turn them into lessons. **The builder chooses the mechanism once API access is known.**

### 8.6 Self-modification and the benchmark

**Proposals** change `CLAUDE.md`, curriculum strategy, Language Profiles, or the skill library's structure. Never protected paths. Each proposal file states the change, the reason, the expected benefit, and benchmark results.

**The benchmark (Java first):** 12 tasks across difficulty **[Claude's call: count]**, covering for example strings and parsing, collections, generics, streams, recursion and dynamic programming, interface design, exceptions, concurrency, and file I/O. Each has a task statement and JUnit 5 tests. Claude writes it during the build, and Gareth reviews it once before it is locked.

**Hidden tests [Claude's call, depends on an unverified constraint]:** the tests live in a second private repo (`auto-engineer-bench`), read only by GitHub Actions via a scoped token. The agent sees only task statements, so it can't tailor solutions to the tests. If section 5 shows cloud runs can clone any of Gareth's repos, fall back to tests kept in `benchmark/` (protected but readable), and record that the score is more exposed to overfitting.

**How a score is produced:** a benchmark run is an agent session that, using a given branch's `CLAUDE.md` and skills, attempts every benchmark task and pushes its solutions. CI then scores the solutions with the hidden tests and records pass rate per language. The agent never writes scores itself. CI writes them where the agent can't (for example a `scores` branch only CI pushes to) **[Claude's call: location left to builder]**.

**Baseline:** a daily benchmark run on `main` **[Claude's call: 02:00 UTC]**, as a third scheduled task.

**Merge rule for proposals:** the proposal branch is benchmarked **twice**. It merges only if, in both runs, at least one language's pass rate beats `main`'s median of its last 3 baselines, and no language falls below that median. Single LLM runs are noisy, so one lucky run must not count as improvement **[Claude's call: 2 runs, median of 3, refine if noise is larger]**. When every task passes, nothing can "improve". Then the benchmark needs harder tasks, which only Gareth can add (it is protected).

### 8.7 Usage, pausing and stopping

- Hourly practice + per-PR review + daily baseline + proposal benchmarks all draw from Gareth's plan. When the plan's usage window runs out, runs are rejected until reset. That is the natural throttle, and Gareth can't use Claude himself during that time.
- **If extra usage credits are on, runs keep going and bill.** Gareth should keep them off for this, or accept the cost.
- **Stop command:** disable the scheduled tasks at claude.ai/code (immediate). Or commit a `PAUSE` file to `main`, after which every run exits at its first step.

### 8.8 CI minutes

About 720 practice PRs a month, each running guard, tests and the review status, plus benchmark scoring. The repo is private (Gareth's decision), so this uses the GitHub Actions minute quota. Claude's rough estimate is about 3,000 minutes a month at hourly cadence, above the 2,000 minutes GitHub Free included when Claude last knew. The quota is unverified for Gareth's account.

**[Claude's call]:**
- Keep each PR to as few CI jobs as possible and cache Maven dependencies.
- In the first week, measure the real minutes used per PR.
- If the monthly projection exceeds the quota, change the practice cron to every 2 hours (`0 */2 * * *`). Don't buy extra minutes.
- Record the measurement and the decision in `logs/`.

### 8.9 Exports

Every practice round writes `practice/<lang>/<run-id>/export.json`: language, task, final code, tests, pass status, and a short reasoning summary. `tools/export.py` concatenates the merged ones into `exports/solutions.jsonl` on demand. It writes one file per round, not one shared file, to keep the no-shared-mutable-files rule in section 8.1. Nothing consumes it yet.

### 8.10 Logging

- Per run: `logs/runs/<run-id>.md` (section 8.2).
- Per PR: the description summarises the round, and the reviewer's comment records the verdict.
- Each cloud session's full transcript stays viewable at claude.ai/code.

## 9. Left to the builder

- Exact cron expressions, prompts and wording of `CLAUDE.md`, `rules/safety.md` and `review/REVIEWER.md`, faithful to the original AUTO-ENGINEER prompt and this design.
- Java build tool (Maven or Gradle) and project layout per skill.
- CI workflow implementation for guard, tests, ai-review and benchmark scoring, within the principles in section 8.4.
- The reviewer verdict channel and the rejection feedback channel, after checking GitHub API access from a cloud run (section 8.5).
- Where scores are stored, as long as only CI can write them.
- Full Java curriculum topic list.
- Verifying the four unverified constraints in section 5 before relying on them.

## 10. Setup steps that need Gareth

These happen during the build. Each is shown to Gareth before it runs.

1. Create the private GitHub repo(s) on his account.
2. Install the Claude GitHub App on the repo(s) (a GitHub page Gareth approves).
3. Create the scheduled tasks under his claude.ai account.
4. Check whether extra usage credits are on.
5. Review the Java benchmark once before it is locked.
