# Butler lane D: Permissions

> Exported from the Claude Doc <https://claude.ai/artifact/MZFjMxCXPap2xZ8A4YCpbZ> on 2026-10-24. The Claude Doc is the original; if they differ, read [STATUS.md](../STATUS.md) first for what has changed since.


2026-10-21 · Chamantha De silva

## Goal

Lane D decides, for every tool call, whether it runs, asks first or is refused, and asks the person in Clawcode's words. It lands at gate M2: from then on Butler edits files only with permission, and a script never hangs on a prompt.

What lane D delivers:

- `PermissionPolicy` with Clawcode's five modes, its per-tool tiers and its allow, deny and ask rules, in the same order of precedence.
- Rules from `butler.permissions.*`, with environment overrides.
- `CliToolApprover`: Clawcode's approval prompt, a diff or command preview, and "allow for this session".
- The flags `--permission-mode`, `--dangerously-skip-permissions` and `--allowedTools`, and the `/permissions` command.
- In scripts and JSON output, every Ask becomes a denial that is listed in the result.

Size: five tasks (two M, three S), about one and a half weeks for one person. D1 and D3 can run in parallel once `contracts-v1` is tagged.

## Scope

Lane D owns the decision and the question; other lanes own calling it, running tools and showing them.

**In lane D**

- D1: the body of `PermissionPolicy.decide`, `PermissionMode.covers`, rule parsing and matching, and the permission subject of each invocation.
- D2: `PermissionRulesGatewayImpl`, which reads `butler.permissions.*` and replaces the M0 stand-in.
- D3: `CliToolApprover`: the prompt, the preview, "allow for this session" and the non-interactive answer.
- D4: the three flags, Clawcode's mode aliases, and `/permissions` in the REPL.
- D5: denials listed in the JSON result of `butler prompt`.

**Left to other lanes**

- Calling the policy and the approver inside the tool loop, and turning a denial into an error result (A5).
- Classifying a bash command's risk and tier (F1). D consumes the result through a small contract, never F's code.
- Computing an edit's diff before it runs (C5), through the preview contract proposed under Working with other lanes.
- Keeping file tools inside the workspace (C, through `WorkspacePath.within`). Clawcode's `check_file_write` is therefore not needed in D.
- Showing tool calls as they run (I1), and the line reader the prompt uses (I3).
- Hooks that override a decision. Clawcode has them; Butler adds them after v1.

## Starting point

After M0, Butler has the permission types and a placeholder rule, but nothing ever asks: tools are off and the loop does not exist yet. Clawcode's permission logic is small and self-contained, so D1 is mostly a careful port.

**In Butler (after ****`contracts-v1`****)**

| File | What it holds today | What lane D does |
| --- | --- | --- |
| core `domain/permission/Permissions.kt` | `PermissionMode`, `PermissionDecision`, `ToolPattern`, `PermissionRules`, placeholder `PermissionPolicy.decide` | D1 fills the body; signatures stay |
| core `domain/turn/TurnCallbacks.kt` | `ToolApprover`, `ToolApprovalRequest`, `ToolApprovalDecision` | D3 implements `ToolApprover` in the CLI |
| secondary `gateway/impl/stub/EmptyPermissionRulesGatewayImpl.kt`, `config/PermissionConfig.kt` | Empty rules | D2 replaces both |
| api `cli/prompt/CliTurnApprover.kt` | The budget prompt from 3b: checks for a terminal, reads with `CliConsole.readLine()`, never asks a script | D3 follows the same pattern |
| api `cli/command/GlobalOptions.kt`, `CliInvocationResolver.kt` | `--model`, `--allow-over-budget`; `CliInvocation(format, workspace, model, allowOverBudget)` | D4 adds the permission flags and fields |
| api `cli/repl/SlashCommand.kt`, `ReplRunner.kt` | Slash commands; `/model` shows or switches for later turns | D4 adds `/permissions` the same way |
| api `cli/dto/CliOutputs.kt` | `PromptResultOutput` with tool uses and results, no denials | D5 adds `permissionDenials` |

**In Clawcode (commit ****`22fe360`****, v0.1.7)**

| File | What to take from it | Task |
| --- | --- | --- |
| `runtime/src/permissions.rs` | `PermissionMode.covers`, the precedence in `authorize_with_context`, rule parsing, `extract_permission_subject`, the denial wording | D1 |
| `runtime/src/config.rs` (around line 2012) | The `permissions` block: `allow`, `deny`, `ask`, `deniedTools` | D2 |
| `rusty-claude-cli/src/main.rs`, `CliPermissionPrompter` (around line 12993) | The prompt wording | D3 |
| `rusty-claude-cli/src/main.rs`, flag parsing (around lines 1585, 1606, 1730), `normalize_permission_mode` (around 11564), `set_permissions` (around 8776) | Flags, mode aliases, `/permissions` | D4 |
| `tools/src/lib.rs`, `allowed_tool_aliases` (around line 354) | `read`, `write`, `edit`, `glob`, `grep` as `--allowedTools` names | D4 |
| `runtime/src/permission_enforcer.rs` | A second, stricter read-only bash check | Input to F1, not ported by D |
| `runtime/src/policy_engine.rs` | Lane and branch orchestration, not tool permissions | Not ported |

Two Clawcode facts shape the decisions below: its default mode is `workspace-write` (`PermissionModeProvenance::default_fallback`), and its prompt offers only yes or no.

## Where the code goes

The rules live in core, the configuration in secondary, and the prompt and flags in api. Paths follow the M0 doc's abbreviations. Files lane D does not own need that lane's review, as the plan's rules say.

| File | Module | New or changed | Task | Owner |
| --- | --- | --- | --- | --- |
| `domain/permission/Permissions.kt` | core | Changed: `decide` body, `PermissionMode.covers`, optional `bashRisk` parameter | D1 | D |
| `domain/permission/ToolPatterns.kt` | core | New: rule parsing, matching, `PermissionSubject.of(invocation)` | D1 | D |
| `config/PermissionProperties.kt` | secondary | New: binds `butler.permissions.*` | D2 | D |
| `gateway/impl/PermissionRulesGatewayImpl.kt` | secondary | New; the stand-in under `stub/` is deleted | D2 | D |
| `config/PermissionConfig.kt` | secondary | Changed: wires the real gateway | D2 | D |
| `cli/prompt/CliToolApprover.kt` | api | New: prompt, preview, session memory, script answer; `CliToolApprovers` factory | D3, D5 | D |
| `cli/mapper/PermissionCliMapper.kt` | api | New: mode labels and Clawcode aliases, `--allowedTools` names | D4 | D |
| `cli/command/GlobalOptions.kt` | api | Changed: three flags | D4 | Shared |
| `cli/command/CliInvocationResolver.kt` | api | Changed: `permissionMode`, `allowedTools` on `CliInvocation` | D4 | Shared |
| `cli/action/PromptActions.kt` | api | Changed: passes the tool approver and the mode | D3, D4 | I reviews |
| `cli/repl/SlashCommand.kt`, `cli/repl/ReplRunner.kt` | api | Changed: `/permissions` | D4 | I reviews |
| `cli/dto/CliOutputs.kt`, `cli/mapper/TurnCliMapper.kt` | api | Changed: `PermissionDenialOutput`, `permissionDenials` | D5 | Shared, append-only |
| `src/main/resources/application.yml` | api | Changed: `butler.permissions` block | D2 | Shared, append-only |

Tests sit beside them: `PermissionPolicyTest` and `ToolPatternsTest` in core, `PermissionRulesGatewayImplTest` in secondary, `CliToolApproverTest` and `PermissionFlagsTest` in api, plus a case in `PromptEndToEndTest`.

## Design

Butler keeps Clawcode's order of questions exactly and drops hooks until after v1. It adds three things Clawcode lacks: bash risk as an input, approvals remembered for the session, and a script answer that lists what was refused.

*(Diagram in the Claude Doc: permission decision · 4 questions, 3 outcomes)*

Read it top down: the first question answered yes decides. Every Deny, and every No from the approver, reaches the model as an error result with its reason, so the model can try something else.

### The decision, step by step

1. A deny rule matches: Deny, with Clawcode's reason `Permission to use {tool} has been denied by rule '{rule}'`.
2. An ask rule matches: Ask, `tool '{tool}' requires approval due to ask rule '{rule}'`.
3. An allow rule matches, or the mode covers the tier: Allow. An allow rule outranks the mode, as in Clawcode.
4. The mode is `prompt`, or `workspace-write` meets a danger-full-access tool: Ask, `tool '{tool}' requires approval to escalate from {mode} to {tier}`.
5. Otherwise: Deny, `tool '{tool}' requires {tier} permission; current mode is {mode}`.

Clawcode's separate `deniedTools` list becomes a deny rule without an argument: `bash` in `deny` refuses every bash call. One list fewer, same effect.

### Modes and tiers

| Mode | Read-only tools | Workspace-write tools | Danger-full-access tools |
| --- | --- | --- | --- |
| `read-only` | Allow | Deny | Deny |
| `workspace-write` | Allow | Allow | Ask |
| `danger-full-access` | Allow | Allow | Allow |
| `prompt` | Allow (Clawcode: Ask) | Ask | Ask |
| `allow` | Allow | Allow | Allow |

`covers` ranks the three tiers; `allow` covers everything and `prompt` covers nothing, as in Clawcode, with one proposed difference. In Clawcode's prompt mode even `read_file` asks; Butler's lets read-only tools through, which is the plan's recommended default (see decisions).

### Bash: the tier comes from the command

A bash call's tier depends on its command, so lane F's classifier supplies it. D never imports F's code: they meet at one small core type that F owns.

```kotlin
// domain/shell/BashRisk.kt (owned by F; F1 fills the classifier)
enum class CommandIntent { READ_ONLY, WRITE, DESTRUCTIVE, NETWORK, PROCESS_MANAGEMENT, PACKAGE_MANAGEMENT, SYSTEM_ADMIN, UNKNOWN }
data class BashRisk(val intent: CommandIntent, val tier: PermissionTier, val warning: String? = null)

// domain/permission/Permissions.kt (D1, additive parameter)
fun decide(invocation: ToolInvocation, tool: ToolDefinition, mode: PermissionMode, rules: PermissionRules, bashRisk: BashRisk? = null): PermissionDecision
```

- The tier used is `bashRisk?.tier ?: tool.tier`. Before F1 lands, bash keeps its M0 tier, danger-full-access.
- `CommandIntent` copies the eight intents of Clawcode's `bash_validation.rs`.
- Proposed: a destructive command asks even when an allow rule or the mode would allow it, except in `danger-full-access` and `allow`, where the person chose to skip prompts.

### Rules: Clawcode's syntax

| Rule | Matches |
| --- | --- |
| `read_file` | Every `read_file` call |
| `bash(*)` or `bash()` | Every bash call |
| `bash(git status)` | Exactly that command |
| `bash(git:*)` | Commands that start with `git` |
| `write_file(src/:*)` | Writes to paths that start with `src/` |

Parentheses and backslashes inside the argument are escaped with a backslash. Tool names compare without case, as in Clawcode. The argument is matched against one subject per call:

| Invocation | Subject |
| --- | --- |
| `Bash` | The command |
| `ReadFile`, `WriteFile`, `EditFile` | The path |
| `GlobSearch`, `GrepSearch` | The path when given, otherwise the pattern (Clawcode checks `path` first) |
| `External` (MCP) | The raw JSON input, Clawcode's fallback |

One improvement over Clawcode: paths are matched in their workspace-relative form, so `write_file(src/:*)` also covers a model that sends an absolute path.

```kotlin
// domain/permission/ToolPatterns.kt (D1)
object PermissionSubject { fun of(invocation: ToolInvocation): String? }

// ToolPattern keeps its M0 fields and gains parsing and matching; toString() gives the raw rule for messages
fun ToolPattern.Companion.parse(raw: String): ToolPattern
fun ToolPattern.matches(tool: ToolName, subject: String?): Boolean

// PermissionMode keeps its M0 values
fun PermissionMode.covers(tier: PermissionTier): Boolean
```

### The prompt

`CliToolApprover` prints Clawcode's block unchanged, then adds the preview and a third answer.

```text
Permission approval required
  Tool             edit_file
  Current mode     prompt
  Required mode    workspace-write
  Reason           tool 'edit_file' requires approval to escalate from prompt to workspace-write
  Input            {"path":"src/App.kt","old_string":"val x = 1","new_string":"val x = 2"}
--- src/App.kt
+++ src/App.kt
-val x = 1
+val x = 2
Approve this tool call? [y/a/N]: 
```

- The first six lines are Clawcode's; its question reads `Approve this tool call? [y/N]: `. The diff and `a` are Butler's.
- `y` or `yes` allows once. `a` or `always` allows for the session. Anything else, or end of input, denies with Clawcode's reason `tool '{tool}' denied by user approval prompt`.
- Proposed: the Input line is cut at 500 characters, because `write_file` content can be long. Clawcode prints all of it.
- "Allow for this session" lives in the approver for the life of the process. Proposed keys: per tool for `write_file` and `edit_file`, per exact command for bash, and never for destructive bash, where `a` is not offered.
- With no terminal, or with `--output-format json`, nothing is asked: the answer is deny, and the approver keeps the request for D5. In text mode it also prints one line per denial to stderr.

### From flag to turn

The mode is resolved once per run: a flag wins, then `butler.permissions.default-mode`, then the built-in default.

- `--permission-mode` accepts Clawcode's labels and aliases: `default` and `plan` mean read-only; `acceptEdits` and `auto` mean workspace-write; `dontAsk`, `bypassPermissions` and `dangerFullAccess` mean danger-full-access. Butler also accepts `prompt` and `allow`, which Clawcode's flag rejects.
- `--dangerously-skip-permissions`, and Clawcode's alias `--skip-permissions`, mean danger-full-access. Proposed: using it together with `--permission-mode` is a usage error; Clawcode lets the later flag win.
- `--allowedTools` and `--allowed-tools` take a comma-separated list, may repeat, and accept Clawcode's aliases `read`, `write`, `edit`, `glob` and `grep`. Other tools are hidden from the model and refused with Clawcode's message: ``tool `{tool}` is not enabled by the current --allowedTools setting``.
- `/permissions` alone prints the mode; with a mode it switches for later turns, accepting the flag's labels, as Clawcode does.

## Tasks

Five tasks, one pull request each, branched as `lane/d/<task>`. D1 and D3 start together; D2 and D4 follow D1; D5 follows D3. Compared with the Tasks tab, D2 gains a small use case and D4 depends on one contract change; both are marked below.

### D1 Permission policy (M, needs M0)

1. If lane F has not merged it yet, open the `BashRisk` contract pull request: `domain/shell/BashRisk.kt` with `CommandIntent`, `BashRisk` and a placeholder `BashRiskClassifier.classify(command, workspace``, sandboxActive``)` that returns `UNKNOWN` at danger-full-access. Types only; F reviews and owns it afterwards.
2. In `Permissions.kt`, add `PermissionMode.covers(tier)` as an explicit `when`, never by enum order (Clawcode's `privilege_rank` comment explains why). `allow` covers all; `prompt` covers only read-only (proposed); the three tier modes rank read-only < workspace-write < danger-full-access.
3. Add the canonical labels (`read-only`, `workspace-write`, `danger-full-access`, `prompt`, `allow`) and `PermissionMode.fromLabel(label)` for exactly those five. Aliases belong to the CLI (D4).
4. Add `ToolPatterns.kt`: `ToolPattern.parse(raw)` ported from `PermissionRule::parse` (first unescaped `(`, last unescaped `)` at the end, `*` or empty means any, a `:*` suffix means prefix, otherwise exact, then unescape), `matches(tool, subject)`, and `PermissionSubject.of(invocation)` per the Design table.
5. Replace the placeholder body of `decide` with the five steps, the `bashRisk` tier and the destructive rule, using Clawcode's reason texts word for word.
6. Tests: a table of 15 cases (five modes × three tiers); precedence (deny beats allow, ask beats allow, an allow rule beats `read-only`, an ask rule still asks in `allow` mode); bash risk tiers and the destructive rule; parsing cases including escapes. Port every applicable case from the test module of `permissions.rs`.

Done when: the table-driven tests pass and each applicable Clawcode case has a Kotlin twin.

### D2 Rules from configuration (S, needs D1)

1. Add `PermissionProperties` (`butler.permissions`: `default-mode`, `allow`, `deny`, `ask`), registered on `PermissionConfig`.
2. Add `PermissionRulesGatewayImpl`: parse every rule once at start-up. A rule that does not parse stops start-up with a message naming it, for example `Invalid permission rule 'bash(' in butler.permissions.allow[2]`.
3. Add `defaultMode: PermissionMode? = null` to `PermissionRules` (additive, in D's own file) and fill it from `default-mode`.
4. New: `usecase/permission/GetPermissionSettingsUseCase`, which returns the rules, so the CLI can resolve the mode without reaching a gateway. Wire it in `UseCaseConfig`.
5. Delete the stand-in, switch `PermissionConfig` to the real gateway, and append the `butler.permissions` block to `application.yml`.
6. Tests: YAML and environment binding (`BUTLER_PERMISSIONS_DEFAULT_MODE`, `BUTLER_PERMISSIONS_DENY_0`), the start-up failure message, and the use case.

Done when: rules and the default mode bind from YAML and from the environment.

### D3 The approval prompt (M, needs M0.3)

1. Add `CliToolApprover(console, format)` implementing `ToolApprover`, created per run by a `CliToolApprovers` component, as `CliTurnApprovers` does. It asks only when the console is interactive and the format is text.
2. Print Clawcode's six lines from the request (tool name, mode and tier labels, reason, input cut at 500 characters), then `request.preview` when present, then the question.
3. Read with `console.readLine()`: `y` or `yes` gives `ALLOW_ONCE`; `a` or `always` gives `ALLOW_FOR_SESSION` and remembers the key; anything else or end of input gives `DENY`.
4. Before asking, answer `ALLOW_ONCE` silently when the key is remembered. Offer `a` for bash only when the request says the command is not destructive; until that contract lands, never offer it for bash.
5. Without a terminal, or in JSON, answer `DENY` without printing the block and record the request for D5. In text mode, print `denied <tool>: <reason>` to stderr.
6. `PromptActions` passes the approver in `TurnCallbacks`; the REPL keeps one approver for its whole session so memory spans turns (lane I reviews both files).
7. Tests with the fake console used by `CliTurnApproverTest`: each answer, a remembered approval that is not asked again, end of input, the non-interactive and JSON paths, truncation, the preview, and a golden test of the six Clawcode lines.

Done when: the fake-console tests pass and the golden test matches Clawcode's wording.

### D4 Flags and /permissions (S, needs D1 and D2; `--allowedTools` also needs `Command.allowedTools`)

1. Add `PermissionCliMapper`: `parseMode(label)` with the canonical labels and Clawcode's aliases; an unknown label fails as a usage error of kind `invalid_permission_mode`, exit 2. `parseAllowedTools(values)` splits on commas, maps Clawcode's aliases and accepts built-in names and `mcp__` names.
2. Add the three flags, with Clawcode's alternative spellings, to `GlobalOptions`.
3. Add `permissionMode` and `allowedTools` to `CliInvocation`; `CliInvocationResolver` merges them and rejects `--dangerously-skip-permissions` together with `--permission-mode`.
4. `PromptActions` resolves the mode (flag, then `GetPermissionSettingsUseCase`, then `prompt`) and puts it on `Command.permissionMode`. It passes `allowedTools` once lane A's contract change has merged.
5. Add `SlashCommand.Permissions(mode)` to the interactive-only commands; `ReplRunner` prints the mode or switches it for later turns. Model the report on Clawcode's `format_permissions_report` (confirm its wording in `main.rs`).
6. Tests: every label and alias, the conflict error, unknown tool names, and `/permissions` show and switch through a piped REPL.

Done when: the parsing tests and the REPL test pass.

### D5 Denials in scripts (S, needs D3)

1. After the turn, `PromptActions` reads the approver's recorded requests.
2. Add `PermissionDenialOutput(tool, toolUseId, reason)` and `permissionDenials` (always present, possibly empty) to `PromptResultOutput`; map them in `TurnCliMapper`.
3. Keep the exit code at 0: the turn finished, and the denials are data for the script (proposed).
4. Tests: a mapper test now; the end-to-end case in `PromptEndToEndTest` (a scripted `write_file` call in JSON mode is denied and listed, and the session holds an error result) once A5 has merged.

Done when: the JSON result lists every Ask that became a denial.

## Tests

Most of lane D is pure core logic, so most of its tests are fast table-driven unit tests; the CLI parts run against the fake console and the fake provider servers.

| Test | Module | What it proves | Task |
| --- | --- | --- | --- |
| `PermissionPolicyTest` | core | Every mode × tier cell, the precedence order, bash risk tiers, the destructive rule, Clawcode's reason texts | D1 |
| `ToolPatternsTest` | core | Rule parsing (any, exact, prefix, escapes), case-insensitive tool names, the subject per invocation | D1 |
| `PermissionRulesGatewayImplTest` | secondary | YAML and environment binding; a bad rule stops start-up with its name | D2 |
| `GetPermissionSettingsUseCaseTest` | core | The use case returns the configured rules and default mode, using `FixedPermissionRulesGateway` | D2 |
| `CliToolApproverTest` | api | Each answer, session memory, end of input, scripts and JSON never asked, golden prompt text | D3 |
| `PermissionFlagsTest` | api | Labels and Clawcode aliases, the flag conflict, `--allowedTools` names | D4 |
| REPL test for `/permissions` | api | Show and switch through piped input | D4 |
| `PromptEndToEndTest` case | api | JSON result lists a denied `write_file` call; the session holds an error result | D5, after A5 |

The full build also runs ArchUnit and the core classpath guard: `PermissionCliMapper` must end in `CliMapper`, the gateway in `GatewayImpl`, and core must still import nothing outside core and the JDK.

## Configuration

Lane D adds one block, `butler.permissions`, with the same environment overrides as every other Butler property. Rules hold no secrets, so they may live in `application.yml` or in the environment.

```yaml
butler:
  permissions:
    # read-only, workspace-write, danger-full-access, prompt or allow. --permission-mode wins over it.
    default-mode: prompt          # proposed; see decisions
    # Clawcode's rule syntax: tool, tool(exact), tool(prefix:*). Deny wins, then ask, then allow.
    allow: []                     # for example "bash(git status)", "read_file"
    deny: []                      # for example "bash(rm:*)"
    ask: []                       # for example "write_file(.github/:*)"
```

| Property | Environment variable | Default |
| --- | --- | --- |
| `butler.permissions.default-mode` | `BUTLER_PERMISSIONS_DEFAULT_MODE` | `prompt` (proposed) |
| `butler.permissions.allow[n]` | `BUTLER_PERMISSIONS_ALLOW_<n>` | none |
| `butler.permissions.deny[n]` | `BUTLER_PERMISSIONS_DENY_<n>` | none |
| `butler.permissions.ask[n]` | `BUTLER_PERMISSIONS_ASK_<n>` | none |

Use the indexed form for lists in the environment: Spring also splits a single value on commas, but some rules contain commas themselves. Clawcode also reads rules from a project's `.claw/settings.json`; a project-level file for Butler is listed under decisions.

## Working with other lanes

Lane D can build and test everything on its own against M0's fakes; it meets the other lanes only through the contracts below.

| Lane | What D needs from it | What D gives it |
| --- | --- | --- |
| A Agent loop | A5 calls `decide` (with the bash risk) and, on Ask, the `ToolApprover`; turns Deny and `DENY` into error results; applies `Command.allowedTools` | The policy body and the CLI approver; `RecordingToolApprover` stands in for D in A's tests |
| C File tools | An edit or write preview (a diff) before the tool runs | Nothing beyond the rules; path containment stays in C |
| F Sandboxed bash | `BashRisk` from `BashRiskClassifier` (F1) | How a risk becomes a decision, and the destructive rule |
| I Terminal | Review of `PromptActions`, `ReplRunner`, `SlashCommand`; the line reader (I3); Ctrl-C at the prompt (I4) denies the call and cancels the turn | The approver to wire into the REPL |
| J Integration | J2 runs the M2 check | An approved edit, a denied edit, and the JSON denial list to check |

**Contract changes lane D proposes** (each a small contract pull request, additive, reviewed by the lanes named):

1. `domain/shell/BashRisk.kt` with `CommandIntent`, `BashRisk` and a placeholder classifier. Owned by F; reviewed by A and D.
2. `PermissionPolicy.decide(…, bashRisk: BashRisk? = null)` and `PermissionRules.defaultMode`. D's own file; reviewed by A.
3. `ToolApprovalRequest.bashRisk: BashRisk? = null`, so the prompt knows when not to offer "allow for this session". Reviewed by A.
4. `WorkspaceGateway.preview(workspace, invocation): String?` with a default of null. C implements it for edits and writes; A calls it before asking. Reviewed by A and C.
5. `RunTurnUseCase.Command.allowedTools: Set<ToolName>? = null`. A hides other tools and refuses them with Clawcode's message. Reviewed by A.

**What J checks at M2:** with `prompt` mode, an `edit_file` call is shown with its diff and runs after `y`; the same call refused with `N` reaches the model as an error result and the turn ends cleanly; in JSON mode the call is denied without a prompt and listed under `permissionDenials`.

## Done when

Lane D is done when gate M2's permission checks pass on `main`. Tick these as they land:

- [ ] `BashRisk` contract merged (by D or F)
- [ ] D1 merged: policy, rule parsing, subjects; Clawcode's cases ported
- [ ] D2 merged: `butler.permissions.*` bound; stand-in deleted; settings use case wired
- [ ] D3 merged: Clawcode's prompt, preview, session memory, script answer
- [ ] D4 merged: three flags, aliases, `/permissions`
- [ ] D5 merged: `permissionDenials` in the JSON result
- [ ] Contract changes 3 to 5 merged with lanes A and C
- [ ] J2 green: approved edit, denied edit, JSON denial

## Risks and decisions to confirm

The first two decisions are the plan's open questions 4b; the rest are smaller choices where Butler departs from Clawcode. Each has a recommendation.

**Decisions**

- [ ] **Default mode.** Recommended: `prompt`, where read-only tools never ask and every write, edit or risky command asks, with "allow for this session". Clawcode's default is `workspace-write`, which never asks for changes inside the project.
- [ ] **Remembered approvals.** Recommended: "allow for this session" per tool for `write_file` and `edit_file`, per exact command for bash, and never offered for destructive bash.
- [ ] **Read-only tools in ****`prompt`**** mode.** Recommended: allowed without asking. Clawcode's prompt mode asks even for `read_file`.
- [ ] **Destructive bash under an allow rule.** Recommended: still asks, except in `danger-full-access` and `allow`, which the person chose explicitly.
- [ ] **Allow rules and chained commands.** Recommended: a bash allow rule applies only to a command without shell operators (`;`, `|`, `&`, `$`, backticks, redirects), the same characters Clawcode's `is_read_only_command` rejects. Otherwise `bash(git:*)` also allows `git status; rm -rf src`.
- [ ] **Both permission flags at once.** Recommended: a usage error. Clawcode lets the later flag win.
- [ ] **Project-level rules.** Recommended: after v1, a `.butler/` settings file per project. Until then rules come from `application.yml` and the environment.

**Risks**

| Risk | Effect | Fix |
| --- | --- | --- |
| Prefix rules are plain prefixes, as in Clawcode | `bash(git:*)` also matches `gitk` | Document it beside the syntax; rules can use `bash(git :*)` with the space |
| The model retries a denied call again and again | The turn loops on denials | A3's iteration limit ends it; the denial reason tells the model why |
| The prompt and JLine both read the terminal | Garbled input or a hung prompt | D reads only through `CliConsole.readLine()`; lane I keeps that working after I3 |
| A preview is missing (C not merged, or a new tool) | The person approves without seeing the change | The block still shows the full input; previews are additive |
