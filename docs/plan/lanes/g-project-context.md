# Butler lane G: Project context

> Exported from the Claude Doc <https://claude.ai/artifact/CgXJXnQqMEtXcdQBva23cS> on 2026-10-24. The Claude Doc is the original; if they differ, read [STATUS.md](../STATUS.md) first for what has changed since.


2026-10-21 · Chamantha De silva

## Goal

Lane G makes Butler know the project it works in. Instruction files and git context go into every system prompt, and long sessions compact themselves before they outgrow the model's context. It lands at gate M5, "Knows your project": J checks that `CLAUDE.md` reaches the system prompt and that a long session compacts and resumes.

What users get at M5:

- The instruction files Clawcode reads (`CLAUDE.md`, `CLAW.md`, `AGENTS.md` and the rest listed in Design), from the folder they run Butler in up to the repository root, on every provider.
- The branch, `git status`, the last five commits and the staged files in the prompt, as Clawcode sends them.
- Sessions that compact automatically above the threshold, and `/compact` to do it on demand.
- `butler init` to set a project up, `butler doctor` to check it, and `butler system-prompt` to print the exact prompt the model will get.

Lane G needs no other lane to finish first: it starts from `contracts-v1` and works against the M0 port and fakes.

## Scope

Lane G owns everything about what the model knows of the project before it calls a tool. It does not run tools, and it does not own the turn.

**In lane G**

- G1: `ProjectContextGatewayImpl`, which finds instruction files and reads git context, replacing M0's `NoProjectContextGatewayImpl`.
- G2: `SystemPromptComposer` gains the project context, Clawcode's prompt sections and a tools-aware opening.
- G3: `CompactionPolicy` (pure core: when to compact, where to cut, the summary) and `CompactSessionUseCase`.
- G4: `/compact`, `butler init`, `butler doctor` and `butler system-prompt`.

**Left to other lanes**

- Calling the gateway and the policy from inside the turn: lane A owns `RunTurnUseCase`. Lane G hands A two exact calls (see Working with other lanes).
- Thinking blocks in `TranscriptNormalizer`: lane E. Lane G changes one rule in the same file, so the two coordinate.
- `.claw/settings.json` and `.claw.json` configuration: Butler keeps its own `butler.*` properties; `butler init` writes Butler's files, not Clawcode's settings.
- Clawcode's auto memory, output styles and its runtime-config prompt section: after v1.
- Model-written summaries: Clawcode writes its summary without a model call, and so does v1 (see Design).

## Starting point

Storage for compaction already exists; the prompt, the discovery and the commands do not. Butler paths are relative to each module's Kotlin root; Clawcode paths are relative to `rust/crates/` at commit `22fe360` (v0.1.7).

**In Butler today**

| File | What it does now | What lane G changes |
| --- | --- | --- |
| `core/.../domain/turn/SystemPromptComposer.kt` | A fixed prompt: role, "you cannot read files or run commands", working directory, date, model | G2 rewrites it around the project context and the offered tools |
| `core/.../domain/session/SessionParts.kt` | `SessionCompaction(count, removedMessageCount, summary)` | Nothing: G3 fills it |
| `core/.../domain/session/Session.kt` | Holds `compaction`; no way to replace messages | G3 adds `compact(...)` |
| `secondary/.../persistence/file/...` | Reads and writes Clawcode's `compaction` line (format v3) | Nothing |
| `secondary/.../persistence/postgresql/...` | `compaction_count`, `compaction_removed_message_count`, `compaction_summary` columns (Flyway V1) | Nothing: no migration |
| `core/.../domain/model/TranscriptNormalizer.kt` | Drops `SYSTEM` messages | G3 sends them as user text, as Clawcode does, so the summary reaches the model |
| `core/.../domain/routing/Routing.kt` | `TokenEstimator`: characters / 4 | Not reused: G3 ports Clawcode's per-block estimate (characters / 4 + 1) so thresholds match |
| `api/.../cli/command/InfoCommands.kt`, `WorkspaceCommands.kt`, `repl/SlashCommand.kt` | `version`, `help`, `status`, `export`; no `init`, `doctor`, `system-prompt` or `/compact` | G4 adds them |
| From M0 | `ProjectContextGateway`, `ProjectContext`, `NoProjectContextGatewayImpl`, `ProjectContextConfig`, `FixedProjectContextGateway` | G1 replaces the stand-in |

**Clawcode files to port from**

| Clawcode file | What to take |
| --- | --- |
| `runtime/src/prompt.rs` | Instruction-file discovery and order, the 4,000-character per-file and 12,000-character total limits, de-duplication, `git status`/`git diff` reads, the prompt sections and their order |
| `runtime/src/git_context.rs` | Branch, last five commits, staged files, and their rendering |
| `runtime/src/compact.rs` | When to compact (keep 4 messages, 10,000 estimated tokens), the tool-pair-safe cut, the summary, the continuation message |
| `runtime/src/conversation.rs` | Automatic compaction at 100,000 cumulative input tokens, checked before the next model call |
| `runtime/src/session.rs` | `record_compaction`: the count increments, the summary replaces the old one |
| `rusty-claude-cli/src/init.rs` | What `init` creates and how it reports each file |
| `rusty-claude-cli/src/main.rs` | `render_doctor_report`, `format_compact_report`, `system-prompt` and its `--cwd` and `--date` flags |

Not ported in v1: `runtime/src/trident.rs`, a second compaction pipeline Clawcode uses for `/compact` on resumed sessions, and `summary_compression.rs`, which compresses tool summaries, not sessions.

## Where the code goes

The rules (which files, in which order, how much, when to compact, where to cut) are pure core code; secondary only reads files and runs `git`. Paths follow the M0 doc's convention.

| File | Module | New or changed | Task | Owner |
| --- | --- | --- | --- | --- |
| `domain/context/ProjectContext.kt` | core | Changed: absolute instruction paths, richer `GitContext` (contract change, see Design) | G1 | G |
| `domain/context/InstructionFiles.kt` | core | New: candidate names and order, limits, de-duplication | G1 | G |
| `gateway/ToolGateways.kt` | core | Changed: new `ProjectSetupGateway` port for `init` (contract change) | G4 | Contract |
| `domain/turn/SystemPromptComposer.kt` | core | Changed: sections, project context, tools-aware opening | G2 | G |
| `domain/context/CompactionPolicy.kt` | core | New: when to compact, the cut, the summary, the continuation message | G3 | G |
| `domain/session/Session.kt` | core | Changed: `compact(...)` | G3 | Shared; A reviews |
| `domain/model/TranscriptNormalizer.kt` | core | Changed: `SYSTEM` messages sent as user text | G3 | Shared with E |
| `usecase/session/CompactSessionUseCase.kt` | core | New | G3 | G |
| `usecase/context/ShowSystemPromptUseCase.kt` | core | New: the prompt a turn would send | G4 | G |
| `usecase/context/InitProjectUseCase.kt`, `RunDoctorUseCase.kt` | core | New | G4 | G |
| `domain/context/Doctor.kt` | core | New: `DoctorCheck`, `DoctorReport` | G4 | G |
| `gateway/impl/ProjectContextGatewayImpl.kt` | secondary | New; deletes `stub/NoProjectContextGatewayImpl.kt` | G1 | G |
| `client/git/GitCliClient.kt` | secondary | New: runs `git` with a timeout, never fails the turn | G1 | G |
| `gateway/impl/ProjectSetupGatewayImpl.kt` | secondary | New: writes the `init` files | G4 | G |
| `config/ProjectContextConfig.kt` | secondary | Changed: real beans | G1, G4 | G |
| `config/ContextProperties.kt` | secondary | New: `butler.context.*`, `butler.compaction.*` | G1, G3 | G |
| `cli/command/ContextCommands.kt` | api | New: `init`, `doctor`, `system-prompt` | G4 | G |
| `cli/repl/SlashCommand.kt`, `ReplRunner.kt` | api | Changed: `/compact` | G4 | I reviews |
| `cli/render/ContextTextRenderer.kt`, `cli/mapper/ContextCliMapper.kt`, `cli/dto/ContextOutputs.kt` | api | New: text and JSON output | G4 | G |
| `config/UseCaseConfig.kt`, `application.yml` | api | Appended: G's beans and `butler.context`, `butler.compaction` blocks | G1, G3, G4 | Shared, append-only |

`client/git/` follows the approved `client/<system>/` pattern: `git` is an external system Butler calls.

## Design

Lane G ports Clawcode's behaviour as it is, with three Butler changes: a richer `ProjectContext` contract, a `git` timeout, and settings bound from `butler.*` instead of Clawcode's environment variables. Every number below comes from the Clawcode file named beside it.

### Instruction files (G1)

- **Where Butler looks** (`prompt.rs`, `discover_instruction_files`): every folder from the repository root down to the workspace, root first. The root is the nearest folder above the workspace with a `.git` entry; without one, only the workspace is searched.
- **What it reads in each folder, in order**: `CLAUDE.md`, `CLAW.md`, `AGENTS.md`, `CLAUDE.local.md`, `.claw/CLAUDE.md`, `.claude/CLAUDE.md`, `.claw/instructions.md`, then the `.md`, `.txt` and `.mdc` files in `.claw/rules/` and `.claw/rules.local/`, sorted by name.
- **Other tools' rules**: Clawcode also imports `.cursorrules`, `.cursor/rules/`, `.github/copilot-instructions.md`, `.windsurfrules`, `.plandex/instructions.md` and `.crush/` by default (`rulesImport: auto`). Butler mirrors this with `butler.context.rules-import`.
- **Limits**: empty files are skipped; files with the same content (after collapsing blank lines) are kept once; each file is cut at 4,000 characters and the whole set at 12,000, with `[truncated]` and an "omitted" line where text was dropped.
- **Trust**: instruction files shape the model's behaviour but never change permissions. Lane D's policy does not read them.

The M0 contract cannot hold files above the workspace, because `InstructionFile.path` is a `WorkspacePath`. G1 opens a small contract pull request:

```kotlin
// domain/context/ProjectContext.kt (G1 contract change)
data class ProjectContext(val instructions: List<InstructionFile>, val git: GitContext?)

/** [scope] is the folder the file applies to, shown to the model as Clawcode does. */
data class InstructionFile(val path: Path, val scope: Path, val content: String)

data class GitContext(
    val branch: String?,                       // null when HEAD is detached
    val status: String,                        // git status --short --branch
    val recentCommits: List<GitCommit> = emptyList(),
    val stagedFiles: List<String> = emptyList(),
    val diff: String? = null,                  // only when butler.context.git-diff is on
)

data class GitCommit(val hash: String, val subject: String)
```

### Git context (G1)

`GitCliClient` runs the same commands as Clawcode's `prompt.rs` and `git_context.rs`, in the workspace:

| Command | Gives | Clawcode limit |
| --- | --- | --- |
| `git rev-parse --is-inside-work-tree` | Whether to read git at all | — |
| `git rev-parse --abbrev-ref HEAD` | Branch; `HEAD` means detached | — |
| `git --no-optional-locks status --short --branch` | Status snapshot | — |
| `git --no-optional-locks log --oneline -n 5 --no-decorate` | Recent commits | 5 commits |
| `git --no-optional-locks diff --cached --name-only` | Staged files | — |
| `git diff --cached`, then `git diff` | Staged and unstaged diff | 50,000 characters |

Any failure (no `git`, not a repository, a non-zero exit, the timeout) gives `git = null`, never an error. Clawcode sets no timeout; Butler adds one (`butler.context.git-timeout`, default 5 s) so a hung `git` cannot stall a turn.

The gateway is called once per turn, not once per model call, so the snapshot is fresh each turn but `git` runs only six times per turn.

### System prompt (G2)

The prompt keeps Clawcode's section order from `SystemPromptBuilder.build`:

1. Fixed sections: who Butler is, how it works, doing tasks, care with actions. Clawcode's wording, with Butler's name.
2. A boundary: everything above it stays the same between turns, so providers can cache it.
3. Environment: model, working directory, date, platform.
4. Project context: date, working directory, the number of instruction files, git status, recent commits, the diff if enabled, the branch and staged files.
5. Project instructions: each file under `## <name> (scope: <folder>)`.

The opening depends on the tools offered. With none, it keeps today's "you cannot read files or run commands" line; with tools, that line goes. The prompt never lists tool schemas: providers already receive them, and Clawcode does not repeat them either.

```kotlin
// domain/turn/SystemPromptComposer.kt (G2)
data class PromptEnvironment(val workspace: WorkspaceRoot, val today: LocalDate, val model: ModelId, val platform: String)

object SystemPromptComposer {
    fun compose(environment: PromptEnvironment, context: ProjectContext, tools: List<ToolDefinition>): String
}
```

### Compaction (G3)

Clawcode compacts without calling a model. `compact.rs` builds the summary from the transcript itself, so compaction is free, works offline and is testable as pure core code. v1 does the same.

*(Diagram in the Claude Doc: compaction · 10 messages before, summary plus 5 after)*

Keeping four messages would start the tail at a tool result, so the cut moves back one and the call stays with its result; messages 1 to 5 become one summary message.

| Rule | Clawcode source | Value |
| --- | --- | --- |
| Automatic trigger | `conversation.rs`, `maybe_auto_compact` | Session's cumulative input tokens reach 100,000, checked before the next model call |
| Messages kept | `compact.rs`, `CompactionConfig` | Last 4 |
| `/compact` threshold | `CompactionConfig::default()` | At least 10,000 estimated tokens (characters / 4) beyond the kept messages, else skipped |
| Tool pairs | `compact_session` | If the first kept message is a tool result, the cut moves back to keep its call |
| Summary | `summarize_messages` | Counts per role, tools used, last 3 user requests, up to 3 pending items, up to 8 key files, current work, a timeline cut to 160 characters per block |
| Repeat compaction | `merge_compact_summaries` | The earlier summary's highlights are carried into the new one |
| Result | `compact_session`, `record_compaction` | One system message (continuation, summary, "resume directly" instruction), then the kept messages; `compaction.count` + 1 |

Two Butler details follow from this. `TranscriptNormalizer` must send `SYSTEM` messages as user text, as Clawcode's `convert_messages` does, or the summary never reaches the model. And compaction runs only when no tool call is pending, so the transcript stays valid.

```kotlin
// domain/context/CompactionPolicy.kt (G3)
data class CompactionSettings(
    val preserveRecentMessages: Int = 4,
    val autoThresholdInputTokens: Long = 100_000,
    val manualMinimumEstimatedTokens: Long = 10_000,
)

data class CompactionResult(val session: Session, val removedMessageCount: Int, val keptMessageCount: Int) {
    val skipped: Boolean get() = removedMessageCount == 0
}

object CompactionPolicy {
    fun shouldAutoCompact(session: Session, settings: CompactionSettings): Boolean
    fun compact(session: Session, settings: CompactionSettings, minimumEstimatedTokens: Long, at: Instant): CompactionResult
}

// usecase/session/CompactSessionUseCase.kt (G3): load, compact with the /compact threshold, save
class CompactSessionUseCase(getSession: GetSessionUseCase, sessions: SessionGateway, settings: CompactionSettings, clock: Clock)
```

## Tasks

Four tasks from the Tasks tab, branched as `lane/g/<task>`. Two refinements: G1 starts with a small contract pull request, and G4 adds one port for `init`. G1 and G3 can run in parallel; G2 needs G1's contract change only; G4 needs G2 and G3.

### G1 Instruction files and git context (M, needs M0)

1. Open the contract pull request from Design: `InstructionFile(path, scope, content)`, the richer `GitContext`, `GitCommit`. Update `FixedProjectContextGateway` and the stand-in in the same pull request; lane A reviews.
2. Add `domain/context/InstructionFiles.kt`, pure core: the candidate names per folder in Clawcode's order, the rules folders and extensions, the other tools' files per `rules-import` value, the folder walk from repository root to workspace, and de-duplication by content.
3. Add `client/git/GitCliClient.kt`: `ProcessBuilder("git", ...)` in the workspace, stdin closed, stderr discarded, `GIT_TERMINAL_PROMPT=0` so it never waits for a password, the configured timeout, the whole process killed on timeout, and null for any failure.
4. Add `ProjectContextGatewayImpl.load(workspace)`: find the repository root, read the candidates (skipping folders, empty files and unreadable files), apply `rules-import`, de-duplicate, then read git context when `butler.context.git` is on and the diff only when `git-diff` is on.
5. Wire it in `ProjectContextConfig`, delete `stub/NoProjectContextGatewayImpl.kt`, and add `ContextProperties`.
6. Tests in temporary folders: folder order and in-folder order; `.claw/rules/` sorted by name; `rules-import` as `auto`, `none` and a list; duplicates and empty files dropped; no repository means only the workspace is read. Git tests create a repository with `git init` and are skipped when `git` is missing: branch, detached `HEAD`, five commits, staged files, not a repository, and a timeout against a configurable executable that sleeps.

Done when: the temporary-repository tests pass and the app uses the real gateway.

### G2 System prompt (S, needs G1's contract change)

1. Add `PromptEnvironment` and change `SystemPromptComposer.compose` to the Design signature.
2. Port Clawcode's fixed sections from `prompt.rs` with Butler's name, then the environment, project context and instruction sections in `SystemPromptBuilder.build` order, using Clawcode's rendering and its 4,000 and 12,000 character budgets.
3. Make the opening depend on the offered tools, as in Design.
4. Add `platform: String = "unknown"` to `TurnSettings` (an additive contract change); api fills it from `os.name` and `os.version`.
5. Open the change in `RunTurnUseCase` described in Working with other lanes; lane A reviews it.
6. Tests: golden-file prompts under `core/src/test/resources/prompts/` for no context, instruction files with scopes, the 4,000 and 12,000 character cuts, git sections, and tools on and off. An end-to-end test checks that a `CLAUDE.md` in a temporary workspace reaches the fake provider's system prompt.

Done when: the golden files and the end-to-end test pass.

### G3 Compaction (M, needs M0)

1. Add `CompactionSettings`, `CompactionResult` and `CompactionPolicy`, porting from `compact.rs`: the per-block estimate, `should_compact`, the cut and its walk-back, detecting an earlier summary, `summarize_messages`, `merge_compact_summaries`, `format_compact_summary` and the continuation message text.
2. Add `Session.compact(...)`: the continuation message, then the kept messages; prompt history untouched; `compaction` with the count incremented. Refuse while a tool call is pending, as `fork` does.
3. Change `TranscriptNormalizer` so `SYSTEM` messages go out as user text. Agree the order with lane E, which edits the same file in E2.
4. Add `CompactSessionUseCase`: load, compact with the `/compact` threshold, save, return the result.
5. Tests ported from Clawcode's `compact.rs` tests: below threshold is skipped; four messages kept; the walk-back when the first kept message is a tool result; a second compaction merges summaries and sets the count to 2; keeping zero messages. Plus Butler's: the normalizer sends the summary as user text; a compacted session round-trips through both stores; a turn on a compacted session sends the summary and the tail.

Done when: a compacted session stays valid, saves and resumes.

### G4 Commands (M, needs G2 and G3)

1. `/compact` in the REPL and in `--resume` mode: calls `CompactSessionUseCase` and prints Clawcode's report (`Result compacted`, `Messages removed`, `Messages kept`, or `skipped` with "session below compaction threshold"); JSON `{"kind":"compact","skipped","removed_messages","kept_messages"}` as in Clawcode.
2. `butler system-prompt [--cwd <dir>] [--date <yyyy-mm-dd>]`: `ShowSystemPromptUseCase` composes what the next turn would send; text, or JSON with the same content.
3. `butler init`: add the `ProjectSetupGateway` port (a contract pull request) and `InitProjectUseCase`. It creates `.butler/`, adds `.butler/sessions/` to `.gitignore`, and writes a starter `CLAUDE.md` from Clawcode's `render_init_claude_md`, skipping files that exist. It reports each file as created, updated or skipped, as Clawcode's `init.rs` does.
4. `butler doctor`: `RunDoctorUseCase` checks the default model resolves and its provider is configured, sessions can be listed, the workspace and git are readable, and which instruction files were found or cut. It exits non-zero only when a check fails. Lanes F and H add their checks later.
5. Tests: CLI end-to-end tests in temporary workspaces: `init` twice changes nothing the second time; `doctor` in text and JSON; `system-prompt` contains the `CLAUDE.md` text; `/compact` through the piped REPL.

Done when: the CLI tests pass and `butler help` lists the three commands.

## Tests

Most of lane G is pure core code, so most of its tests are fast unit tests; only discovery, `git` and the commands touch the disk. Every test runs in `./gradlew build`.

| Test class | Module | What it proves | Task |
| --- | --- | --- | --- |
| `InstructionFilesTest` | core | Candidate order, rules folders, `rules-import`, de-duplication | G1 |
| `ProjectContextGatewayImplTest` | secondary | Discovery from repository root to workspace in temporary folders | G1 |
| `GitCliClientTest` | secondary | Branch, commits, staged files, detached `HEAD`, no repository, timeout (skipped without `git`) | G1 |
| `SystemPromptComposerTest` | core | Golden-file prompts: sections, budgets, git, tools on and off | G2 |
| `CompactionPolicyTest` | core | Clawcode's compaction cases plus the walk-back and repeat compaction | G3 |
| `TranscriptNormalizerTest` (extended) | core | `SYSTEM` messages go out as user text | G3 |
| `CompactSessionUseCaseTest` | core | Load, compact, save; skipped below the threshold | G3 |
| Session store contract tests (extended) | secondary | A compacted session round-trips in files and PostgreSQL | G3 |
| `ContextCommandsEndToEndTest` | api | `init`, `doctor`, `system-prompt` and `/compact` against temporary workspaces | G4 |
| `ProjectContextEndToEndTest` | api | `CLAUDE.md` reaches the fake provider; a long session compacts and resumes | G2, G3 |
| ArchUnit and `verifyCoreIsFrameworkFree` | api, core | Rules stay in core; `ProcessBuilder` and file reads stay in secondary | Every task |

The end-to-end compaction test lowers `butler.compaction.auto-threshold-tokens` to a small number, so it needs no large transcript.

## Configuration

Lane G adds two blocks to `application.yml`, `butler.context` and `butler.compaction`; neither holds a secret. Each key has the usual environment override, for example `BUTLER_COMPACTION_AUTO_THRESHOLD_TOKENS`. The file limits (4,000 and 12,000 characters, 50,000 for the diff, five commits) stay constants in core, as in Clawcode.

| Property | Default | Clawcode equivalent |
| --- | --- | --- |
| `butler.context.rules-import` | `auto` | `rulesImport` in settings: `auto`, `none` or a list |
| `butler.context.git` | `true` | Always on |
| `butler.context.git-diff` | `false` (proposed) | Always on |
| `butler.context.git-timeout` | `5s` | None |
| `butler.compaction.auto-enabled` | `true` | Always on |
| `butler.compaction.auto-threshold-tokens` | `100000` | `CLAUDE_CODE_AUTO_COMPACT_INPUT_TOKENS` |
| `butler.compaction.preserve-recent-messages` | `4` | `CompactionConfig.preserve_recent_messages` |
| `butler.compaction.manual-minimum-tokens` | `10000` | `CompactionConfig.max_estimated_tokens` |

```yaml
butler:
  context:
    # auto, none, or a list of: cursor, copilot, windsurf, plandex, crush
    rules-import: auto
    git: true
    # Clawcode sends staged and unstaged diffs (up to 50,000 characters); off until decided.
    git-diff: false
    git-timeout: 5s
  compaction:
    auto-enabled: true
    auto-threshold-tokens: 100000
    preserve-recent-messages: 4
    manual-minimum-tokens: 10000
```

## Working with other lanes

Lane G reaches the turn only through its port, `SystemPromptComposer` and `CompactionPolicy`; lane A owns `RunTurnUseCase` and makes three calls. Until those land, G tests everything in core with `FixedProjectContextGateway`.

**What lane A must call.** G opens this change after A2 (the tool loop) is merged, so the two never edit the loop at the same time; A reviews it.

```kotlin
// RunTurnUseCase: new constructor dependency projectContext: ProjectContextGateway;
// TurnSettings gains platform and compaction (additive).

// 1. Once per turn, before the first model call:
val context = projectContext.load(command.workspace)

// 2. For every model call (the model can change mid-turn, so compose each time; it is cheap):
val prompt = SystemPromptComposer.compose(
    PromptEnvironment(command.workspace, LocalDate.now(clock), model.id, settings.platform), context, offeredTools,
)

// 3. Before every model call, when no tool call is pending:
if (settings.compaction.autoEnabled && CompactionPolicy.shouldAutoCompact(session, settings.compaction)) {
    val result = CompactionPolicy.compact(session, settings.compaction, minimumEstimatedTokens = 0, at = clock.instant())
    session = result.session
    sessions.save(session)
    callbacks.observer.onEvent(TurnEvent.Compacted(result.removedMessageCount))
}
```

`TurnEvent.Compacted(removedMessageCount: Int)` is a new event and an additive contract change, matching the automatic-compaction event Clawcode adds to its turn summary.

| Lane | What passes between G and that lane |
| --- | --- |
| A Agent loop | The three calls above; `FixedProjectContextGateway` in A's tests |
| B Provider tools | The prompt stays one string; fixed sections come first, so B can add a cache marker later without G |
| E Reasoning replay | Both edit `TranscriptNormalizer`: G's rule for `SYSTEM` messages, E2's rule for thinking. Whoever merges second rebases and keeps both tests |
| I Terminal | Renders `TurnEvent.Compacted` as one line; reviews `/compact` in `cli/repl` |
| D Permissions | Nothing: instruction files never change permissions |
| F Sandboxed bash, H MCP client | Each adds one check to `RunDoctorUseCase` (sandbox available; MCP servers) in its own task |
| J Integration | Runs the M5 check below |

**What J checks at M5.** In a temporary workspace with a `CLAUDE.md`, the fake provider receives a system prompt that contains it. With `butler.compaction.auto-threshold-tokens` lowered, a long scripted session compacts by itself, is saved, and resumes with the summary sent as user text and the tail intact.

## Done when

Lane G is done when J tags `gate-m5` on a green `main`. Tick these as they land:

- [ ] G1 contract pull request merged: `InstructionFile` with absolute path and scope, richer `GitContext`
- [ ] G1 merged: real gateway, `GitCliClient`, stand-in deleted, temporary-repository tests green
- [ ] G2 merged: Clawcode's prompt sections, golden files green, tools-aware opening
- [ ] G3 merged: `CompactionPolicy`, `Session.compact`, `CompactSessionUseCase`, normalizer rule agreed with lane E
- [ ] Lane A has merged the three calls and `TurnEvent.Compacted`
- [ ] G4 merged: `/compact`, `init`, `doctor`, `system-prompt`, CLI tests green
- [ ] The three decisions below answered
- [ ] J's M5 check green and `gate-m5` tagged

## Risks and decisions to confirm

Three decisions are worth your yes before G1 starts; four contract changes need their lanes' review; the risks have a fix built into the tasks.

**Decisions**

- [ ] **Git diff in the prompt.** Proposed: off by default (`git-diff: false`). Clawcode always sends staged and unstaged diffs, up to 50,000 characters, which is roughly 12,500 tokens on every call.
- [ ] **What triggers automatic compaction.** Proposed: Clawcode's rule, the session's cumulative input tokens reaching 100,000. The alternative is the latest call's input including cached tokens, which tracks the real context size more closely when prompt caching is on.
- [ ] **Other tools' rule files.** Proposed: `auto`, as in Clawcode, so `.cursorrules` and Copilot instructions in a repository are read. The alternative is `none`, reading only Claude and Claw files.

**Contract changes for review**

| Change | Kind | Reviewed by |
| --- | --- | --- |
| `InstructionFile(path: Path, scope, content)`, richer `GitContext`, `GitCommit` | Changes an M0 type nobody uses yet | A |
| `ProjectSetupGateway` port for `butler init` | New port | All lanes, briefly |
| `TurnSettings.platform`, `TurnSettings.compaction` | Additive fields with defaults | A |
| `TurnEvent.Compacted(removedMessageCount)` | Additive event | A, I |

**Risks**

| Risk | Effect | Built-in fix |
| --- | --- | --- |
| A cloned repository's instruction files try to steer the model | Unwanted edits or commands | Instruction files never change permissions; lane D still asks; `doctor` lists the files loaded |
| Git status changes every turn | The provider's prompt cache misses more often | Fixed sections come first, so a later cache marker keeps them cached |
| The local summary drops detail | The model forgets earlier specifics | The last four messages stay verbatim; Clawcode's summary keeps requests, files and pending work; model-written summaries can follow after v1 |
| Lane E and lane G both edit `TranscriptNormalizer` | Merge conflict | Separate rules and tests; the second to merge rebases |
| `git` hangs on a network drive or lock | The turn stalls | `git-timeout`, the process killed, `git = null` |
| Lane A's loop lands late | M5 waits | Everything except the three calls merges independently; J waits only for that one change |
