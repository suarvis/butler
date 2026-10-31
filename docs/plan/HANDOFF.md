# Handing Butler work to another agent

Everything an agent needs is in this repository: [AGENTS.md](../../AGENTS.md) (rules), [STATUS.md](STATUS.md) (where things stand), [TASKS.md](TASKS.md) (what is ready) and one plan per lane in [lanes/](lanes/). Any coding agent that can read files and run `./gradlew build` can take over a task.

## 1. Before you switch

1. Commit everything that is green; make sure `git status` is clean.
2. Check that TASKS.md and STATUS.md match what is merged (each finished task should say `Merged` with its commit).
3. If the agent works from GitHub rather than your folder, push `main` (and tags) first.

## 2. Choose who takes over

| Option | Reads the rules by itself | Can run the build itself | Notes |
| --- | --- | --- | --- |
| Claude Code in your terminal, in `~/work/butler` | Yes: `CLAUDE.md` imports `AGENTS.md` | Yes | Counts against the same Claude plan limits as this app; with an Anthropic API key it is billed per use instead |
| OpenAI Codex CLI | Yes: `AGENTS.md` | Yes | Start it in the repository folder |
| Cursor or another IDE agent | Usually reads `AGENTS.md`; otherwise paste the prompt below | Yes, through its terminal | Open the repository folder as the workspace |
| Any other agent or chat assistant | Paste the prompt below | Only if it has a terminal | Without a terminal, you run the build and paste back the result |
| An engineer on your team | AGENTS.md and the lane doc | Yes | Same steps as an agent |

## 3. The prompt to give it

Replace the task ids. One task per prompt works best; a whole lane is fine for an agent with a long context.

```text
You are taking over work on Butler, a Kotlin + Spring Boot rewrite of the Clawcode coding agent.
Repository: the current folder (main is green).

Read, in this order: AGENTS.md, docs/plan/STATUS.md, docs/plan/TASKS.md, docs/plan/CONTRACTS.md,
then the lane doc for your task in docs/plan/lanes/.

Your task: <TASK ID, e.g. C2> as described in its lane doc's Tasks section.

Rules:
- Change only the files your lane owns; contract changes must be additive and in their own commit.
- Follow Clawcode's behaviour and wording where it has them. Its source is at
  https://github.com/ultraworkers/claw-code (commit 22fe360); clone it outside this repository.
- Run ./gradlew build until it passes with no warnings. Do not delete or skip existing tests.
- Commit as "feat: <what it does> (<TASK ID>)", and in the same commit set the task to Merged
  in docs/plan/TASKS.md and note any decision or departure in docs/plan/STATUS.md.
- If the task needs a decision listed as open in STATUS.md, or the lane doc and the code disagree,
  stop and ask me instead of guessing.

When you finish, reply with: the files you changed, the test count from the build, and anything
you were unsure about.
```

For several tasks at once, give each agent its own lane and its own working copy, so two agents never edit the same tree:

```bash
git worktree add ../butler-lane-c -b lane/c/C2 main   # then start the agent in ../butler-lane-c
```

## 4. Bringing the work back

For each finished task:

1. `./gradlew build` on its branch: green, same or more tests, no warnings.
2. `git diff --stat main...<branch>`: only files the lane owns, plus TASKS.md and STATUS.md.
3. No secrets, no `butler.tools.enabled: true` (only gate M1's task J1 flips it), no deleted tests.
4. Merge into `main` in Needs order (TASKS.md), run the build again, then remove the worktree.
5. When every task for a gate says Merged, run that gate's task (J1, J2, ...) next.

## 5. Coming back to Claude

Open a new session in the same Claude project and say: "Continue Butler from docs/plan/STATUS.md and TASKS.md." The plan docs, this folder and the project's notes carry the context; nothing depends on the old conversation.
