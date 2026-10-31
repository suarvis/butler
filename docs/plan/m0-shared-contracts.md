# Butler M0: Shared contracts

> Exported from the Claude Doc <https://claude.ai/artifact/H7Dp3aQEpmLQVzx2gBXeSY> on 2026-10-24. The Claude Doc is the original; if they differ, read [STATUS.md](../STATUS.md) first for what has changed since.


2026-10-21 · Chamantha De silva

## Goal

M0 adds the shared types and ports for tools, permissions and the tool loop, puts a do-nothing stand-in behind every port, and changes no behaviour. It is about a week of work for one person, less if someone else takes M0.4. When the tag `contracts-v1` is on `main`, all nine build lanes (A to I) can start on the same day.

Why it comes first: every lane meets the others at the same few seams, such as the tool model, the turn's callbacks and the tools a request offers. If each lane defined these as it went, every merge would conflict. Fixing them once lets each lane code against an interface and a fake, never against another lane's unfinished code.

What each lane takes from M0:

| Lane | Uses from M0 |
| --- | --- |
| A Agent loop | `ToolInvocation`, `ToolOutcome`, the six ports, `TurnCallbacks`, `PermissionPolicy.decide`, `TurnSettings` |
| B Provider tools | `ModelRequest.tools`, `ToolDefinition` |
| C File tools | `ToolInputGateway`, `WorkspaceGateway`, `WorkspacePath`, `InvalidToolInputException` |
| I Terminal | `ToolStarted` and `ToolFinished` events, `TurnCancellation`, `BuiltInTools` |
| D Permissions | `PermissionMode`, `PermissionRules`, `PermissionPolicy`, `ToolApprover`, `PermissionRulesGateway`, `Command.permissionMode` |
| E Reasoning replay | `ReasoningReplay` on thinking blocks, already stored by both session stores |
| F Sandboxed bash | `CommandGateway`, `SandboxPolicy`, `ToolInvocation.Bash` |
| G Project context | `ProjectContextGateway`, `ProjectContext` |
| H MCP client | `McpGateway`, `ToolInvocation.External` |
| J Integration | `butler.tools.enabled`, the `contracts-v1` tag |

## Scope

The test for anything in M0: if a user could notice it, it belongs to a lane, not to M0.

**In M0**

- M0.1: the tool model (`WorkspacePath`, `PermissionTier`, `ToolDefinition`, `ToolInvocation`, `ToolOutcome`) and `BuiltInTools` with Clawcode's six schemas.
- M0.2: the six ports, `SandboxPolicy`, `ProjectContext`, `InvalidToolInputException`, a stand-in for each port in secondary, and an in-memory fake for each port in core's test fixtures.
- M0.3: the turn contract (`TurnCallbacks`, `ToolApprover`, `TurnCancellation`, the two tool events, `ModelRequest.tools`, the permission types, `Command.permissionMode`, `TurnSettings`), with the CLI and existing tests moved to the new `execute` signature.
- M0.4: `ReasoningReplay` on thinking blocks, stored as optional fields in session files and in PostgreSQL.
- M0.5: `butler.tools.enabled` (off), every lane owner's review, the Contracts tab brought up to date, the `contracts-v1` tag.

**Left to the lanes**

- Running any tool (C, F, H), and the model → tool → model loop (A).
- Sending tools to providers. The mappers ignore `ModelRequest.tools` until lane B.
- Real permission decisions beyond the placeholder rule, the approval prompt and the permission flags (D).
- Showing tool calls, JLine and Ctrl-C (I).
- Requesting or replaying reasoning (E).
- Instruction files, git context and compaction (G).
- MCP servers (H).

## Where the code goes

New contracts live in core; secondary only gets stand-ins, and api only gets the new call shape. Core paths are under `core/src/main/kotlin/com/suarvis/butler/core/`, secondary under `secondary-adaptor/.../secondary/`, api under `api-primary-adaptor/.../api/`. After `contracts-v1`, each file passes to the lane named in the last column.

| File | Module | New or changed | Task | Owner after M0 |
| --- | --- | --- | --- | --- |
| `domain/generic/WorkspacePath.kt` | core | New | M0.1 | C |
| `domain/tool/Tools.kt` | core | New: `PermissionTier`, `ToolDefinition`, `ToolInvocation`, `ToolOutcome` | M0.1 | Contract |
| `domain/tool/BuiltInTools.kt` | core | New: the six definitions and schemas | M0.1 | C (F for `bash`) |
| `gateway/ToolGateways.kt` | core | New: the six ports | M0.2 | Contract |
| `domain/shell/SandboxPolicy.kt` | core | New | M0.2 | F |
| `domain/context/ProjectContext.kt` | core | New | M0.2 | G |
| `domain/exception/ButlerException.kt` | core | Changed: `InvalidToolInputException` | M0.2 | Shared |
| `domain/permission/Permissions.kt` | core | New: modes, rules, decision, placeholder policy | M0.3 | D |
| `domain/turn/TurnCallbacks.kt` | core | New: callbacks, `ToolApprover`, `TurnCancellation`, `TurnSettings` | M0.3 | A |
| `domain/turn/Turn.kt` | core | Changed: `ToolStarted`, `ToolFinished` | M0.3 | A |
| `domain/model/ModelExchange.kt` | core | Changed: `ModelRequest.tools` | M0.3 | Contract |
| `usecase/turn/RunTurnUseCase.kt` | core | Changed: `Command.permissionMode`, `execute(command, callbacks)`, settings | M0.3 | A |
| `domain/session/ContentBlock.kt` | core | Changed: `Thinking.replay`, `ReasoningReplay` | M0.4 | E |
| `testFixtures/.../fixtures/ToolDoubles.kt` | core | New: a fake per port, a recording approver | M0.2 | Shared, append-only |
| `gateway/impl/stub/*GatewayImpl.kt` (six files) | secondary | New stand-ins | M0.2 | The lane that replaces each |
| `config/``*Config.kt (one per port)` | secondary | New: five config classes that wire the stand-ins (see Stand-ins) | M0.2 | The lane that replaces each |
| `persistence/file/entity/SessionFileRecords.kt` and its mapper | secondary | Changed: optional replay fields | M0.4 | E |
| `persistence/postgresql/support/ContentJsonCodec.kt` and its mapper | secondary | Changed: optional replay fields | M0.4 | E |
| `cli/action/PromptActions.kt` | api | Changed: passes `TurnCallbacks` | M0.3 | I |
| `cli/render/StreamingTurnObserver.kt` | api | Changed: ignores the two tool events for now | M0.3 | I |
| `config/UseCaseConfig.kt` and `config/TurnProperties.kt` | api | Changed and new: binds `butler.tools.*` into `TurnSettings` | M0.5 | Shared, append-only |
| `src/main/resources/application.yml` | api | Changed: `butler.tools.enabled: false` | M0.5 | Shared, append-only |

No Flyway migration is needed: PostgreSQL keeps message blocks in a `jsonb` column, so the replay fields are new JSON keys, not new columns.

## Design

The [Contracts tab](https://claude.ai/artifact/JzWtyT5m6Xp945LNA8Y44C) of the v1 plan holds the full signatures. This section gives the reason behind each type and four refinements found while checking the plan against the code and Clawcode `v0.1.7`: `WorkspacePath.within`, the full `grep_search` fields, a smaller `bash` input and `TurnSettings`. M0.5 copies the refinements into the Contracts tab.

### Tool model

- **`ToolDefinition`**** mirrors Clawcode's ****`ToolSpec`** (name, description, JSON schema, required permission), so the model sees the same tools it would in Clawcode. The schema is `JsonText` because core has no JSON library; providers send it unchanged.
- **The six schemas are copied from Clawcode's ****`mvp_tool_specs()`** in `rust/crates/tools/src/lib.rs`, word for word, with one cut: `bash` offers only `command`, `timeout` (milliseconds, as in Clawcode) and `description`. Clawcode also lets the model ask for background runs and sandbox overrides. In Butler the sandbox is decided by policy, not by the model; lane F can add an override later through a contract change.
- **`PermissionTier`**** is Clawcode's per-tool required mode**: `read_file`, `glob_search` and `grep_search` are read-only; `write_file` and `edit_file` are workspace-write; `bash` is danger-full-access.
- **`ToolInvocation`**** is typed**, so permission rules, path checks and bash risk can stay pure core code that reads fields instead of parsing JSON. `External` passes MCP input through untouched.
- **`ToolOutcome`**** carries failures as data.** A missing file or a bad pattern goes back to the model as an error result it can react to, never as an exception that ends the turn.

```kotlin
// domain/tool/Tools.kt: the two invocations that differ from the Contracts tab
data class GrepSearch(
    val pattern: String,
    val under: WorkspacePath? = null,
    val glob: String? = null,
    val outputMode: String? = null,     // Clawcode: output_mode
    val before: Int? = null,            // -B
    val after: Int? = null,             // -A
    val context: Int? = null,           // -C or context
    val lineNumbers: Boolean? = null,   // -n
    val ignoreCase: Boolean? = null,    // -i
    val fileType: String? = null,       // type
    val headLimit: Int? = null,         // head_limit
    val offset: Int? = null,
    val multiline: Boolean? = null,
) : ToolInvocation

data class Bash(val command: String, val timeout: Duration? = null, val description: String? = null) : ToolInvocation
```

Null means the model left the field out; lane C owns the defaults, taken from Clawcode's implementation.

### Workspace paths

Models send both relative and absolute paths. `WorkspacePath` always holds the relative, normalised form, and a single factory does the lexical containment check, so every file tool refuses `../` escapes the same way. Symlink escapes need the disk, so lane C checks them in the adaptor.

```kotlin
// domain/generic/WorkspacePath.kt
/** A path inside the workspace: relative, normalised, `/`-separated; "." is the workspace itself. */
@JvmInline
value class WorkspacePath(val value: String) {
    init {
        ensure(value == "." || value.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Not a normalised relative workspace path: $value"
        }
    }

    companion object {
        /** [raw] relative to [root], or null when it leads outside. Lexical only: no disk access. */
        fun within(root: WorkspaceRoot, raw: String): WorkspacePath?
    }
}
```

### Ports

One port per lane that executes something, so a lane can be replaced or faked without touching the others. `WorkspaceGateway` takes the whole `ToolInvocation` rather than one method per tool: lane C can then add file tools without a contract change. Lane A's `ToolRunner` routes `Bash` to `CommandGateway`, `External` to `McpGateway` and the rest to `WorkspaceGateway`.

### Permissions

`PermissionMode` copies Clawcode's five modes and their CLI names: `read-only`, `workspace-write`, `danger-full-access`, `prompt`, `allow`. M0's `PermissionPolicy.decide` holds only a placeholder rule (read-only tools are allowed, anything else asks), so lane A can wire the permission step before lane D finishes. Lane D replaces the body, never the signature.

### The turn

- **`TurnCallbacks`**** bundles everything the caller supplies**, so later additions such as a steering queue extend one data class instead of changing `execute` again.
- **`ToolApprover`**** is separate from ****`TurnApprover`** because the answers differ: a budget question is continue, switch or stop; a tool question is allow once, allow for the session, or deny. Both default to the safe answer (`STOP`, `DENY`) for runs nobody can be asked about.
- **`TurnCancellation`**** is polled between steps**, not a thread interrupt, so core stays free of threading and the transcript is always left valid.
- **`TurnSettings`**** is new.** Core cannot read configuration, so the flag reaches the use case as a constructor value bound from `butler.tools.*`. Lane A adds `maxIterations` to it later.

```kotlin
// domain/turn/TurnCallbacks.kt (added to the Contracts tab's types)
data class TurnSettings(val toolsEnabled: Boolean = false)

// usecase/turn/RunTurnUseCase.kt
class RunTurnUseCase(/* existing nine dependencies, */ private val settings: TurnSettings = TurnSettings())
```

### Reasoning replay

`ReasoningReplay` is tagged with the provider that produced it, because only that provider accepts it back. Its payload is an opaque string, for example OpenAI's encrypted reasoning content. Anthropic's signature already lives in `Thinking.signature`, and lane E may also keep Anthropic's raw thinking block in the payload; E4 decides. Stores write the fields only when present, so files without replay stay byte-identical to today's.

## Stand-ins

Every port gets two doubles: a stand-in in secondary, so the app starts and behaves exactly as today, and a fake in core's test fixtures, so lanes test against the contract alone. A lane replaces its stand-in with the real implementation in the same pull request that adds it, and deletes the stand-in.

| Port | Stand-in in secondary (`gateway/impl/stub/`) | What it does | Fake in core test fixtures | Replaced by |
| --- | --- | --- | --- | --- |
| `ToolInputGateway` | `UnavailableToolInputGatewayImpl` | Throws `InvalidToolInputException`: "tools are not available in this build yet" | `FakeToolInputGateway(decode)`: a lambda from name and input to an invocation | C1 |
| `WorkspaceGateway` | `UnavailableWorkspaceGatewayImpl` | Error outcome: "`<tool>` is not available in this build yet" | `RecordingWorkspaceGateway(outcomes)`: records each invocation, returns scripted outcomes | C2 |
| `CommandGateway` | `UnavailableCommandGatewayImpl` | Error outcome, same wording | `RecordingCommandGateway(outcomes)` | F4 |
| `McpGateway` | `NoMcpGatewayImpl` | No tools; any call is an error outcome | `FakeMcpGateway(tools, outcomes)` | H2 |
| `PermissionRulesGateway` | `EmptyPermissionRulesGatewayImpl` | Empty rules | `FixedPermissionRulesGateway(rules)` | D2 |
| `ProjectContextGateway` | `NoProjectContextGatewayImpl` | No instruction files, no git context | `FixedProjectContextGateway(context)` | G1 |

The Contracts tab calls the rules stand-in `ConfiguredPermissionRulesGatewayImpl`; `Empty…` says what it does, and M0.5 renames it on the tab.

Two more fakes go in `ToolDoubles.kt` because lanes A, D and I all need them: `RecordingToolApprover(vararg decisions)`, which records each request and answers in order, and `CancelAfter(checks)`, a `TurnCancellation` that turns true after a set number of checks.

**One config class per port.** Instead of one shared `ToolConfig`, M0 adds `WorkspaceConfig` (both C ports), `CommandConfig` (F), `McpConfig` (H), `PermissionConfig` (D) and `ProjectContextConfig` (G) in secondary's `config/`. Each lane then edits only its own class, so swapping a stand-in never conflicts with another lane. The stand-ins need no configuration properties.

## Tasks

Five tasks, one pull request each, branched as `lane/m0/<task>`. M0.1 and M0.4 can start at once; M0.2 and M0.3 need M0.1; M0.5 closes M0. With two people, one takes M0.1 then M0.2, the other M0.4 then M0.3.

### M0.1 Tool model (M, needs nothing)

1. Add `domain/generic/WorkspacePath.kt` as in Design. `within(root, raw)` resolves `raw` against `root.path`, normalises it, returns null unless the result starts with `root.path`, then relativises and joins the parts with `/`. The root itself becomes `.`.
2. Add `domain/tool/Tools.kt`: `PermissionTier`, `ToolDefinition` (blank descriptions refused), the seven `ToolInvocation` variants with the Design refinements, and `ToolOutcome`.
3. Add `domain/tool/BuiltInTools.kt`: one constant per tool (`READ_FILE`, `GLOB_SEARCH`, `GREP_SEARCH`, `WRITE_FILE`, `EDIT_FILE`, `BASH`), `ALL` in Clawcode's order (bash, read_file, write_file, edit_file, glob_search, grep_search), and `byName(name): ToolDefinition?`. Paste each schema as a raw string from Clawcode's `mvp_tool_specs()`, and trim `bash` to `command`, `timeout` and `description`.
4. Core tests: `WorkspacePath` accepts `src/Main.kt`, an absolute path inside the root and the root itself; `within` returns null for `../x`, `/etc/passwd` and `a/../../x`; the constructor refuses `a//b`, `./a` and `/abs`. `BuiltInTools` has six unique names with the tiers listed in Design.
5. Secondary test `BuiltInToolSchemasTest` (secondary already has kotlinx-serialization): each schema parses, has `"type": "object"`, lists only known properties under `required`, and sets `additionalProperties` to false.

Done when: the types compile, both test classes pass and nothing else changes.

### M0.2 Ports, stand-ins and fakes (M, needs M0.1)

1. Add `gateway/ToolGateways.kt` with the six interfaces exactly as on the Contracts tab.
2. Add `domain/shell/SandboxPolicy.kt` and `domain/context/ProjectContext.kt` (with `InstructionFile` and `GitContext`).
3. Add `InvalidToolInputException(tool, reason)` to the sealed `ButlerException`. The CLI's `classifyDomain` is exhaustive, so give it a branch: kind `invalid_tool_input`, runtime exit code. Lane A turns the exception into an error result, so the branch should never fire.
4. Add the six stand-ins under `secondary/gateway/impl/stub/` and the five config classes, as in Stand-ins.
5. Add `core/src/testFixtures/.../fixtures/ToolDoubles.kt` with the six fakes, `RecordingToolApprover` and `CancelAfter`.
6. Tests: `StandInGatewaysTest` in secondary checks each stand-in's answer; a fixtures test checks that the recording fakes record and answer in order; the existing Spring end-to-end tests prove the app still starts.

Done when: the app starts, every port has one bean and one fake, and ArchUnit passes (stand-in names end in `GatewayImpl`; core gateways are interfaces).

### M0.3 Turn contract (M, needs M0.1)

1. Add `domain/permission/Permissions.kt`. The placeholder `PermissionPolicy.decide` allows the `READ_ONLY` tier and returns `Ask("<tool> needs approval")` for the rest, whatever the mode.
2. Add `domain/turn/TurnCallbacks.kt`: `TurnCallbacks`, `ToolApprover`, `ToolApprovalRequest`, `ToolApprovalDecision`, `TurnCancellation`, `TurnSettings`.
3. In `Turn.kt`, add `ToolStarted` and `ToolFinished` inside `TurnEvent`, next to the existing events.
4. In `ModelExchange.kt`, add `tools: List<ToolDefinition> = emptyList()` to `ModelRequest`.
5. In `RunTurnUseCase`: add `permissionMode = PermissionMode.PROMPT` to `Command`, replace `execute(command, observer, approver)` with `execute(command, callbacks = TurnCallbacks())`, and add the `settings` constructor parameter. Inside, read `callbacks.observer` and `callbacks.approver`; requests still carry no tools. Keep no old overload: one signature, callers moved in the same pull request.
6. Move the callers: `PromptActions` passes `TurnCallbacks(observer = observer, approver = approver)`; `StreamingTurnObserver` ignores the two tool events; the 23 `execute` calls in `RunTurnUseCaseTest` move to the callbacks form.
7. Tests: the placeholder policy allows each read-only tool and asks for the rest; every existing test passes unchanged in what it asserts.

Done when: all 217 existing tests pass and the new ones are green.

### M0.4 Reasoning replay (S, needs nothing)

1. In `ContentBlock.kt`, add `replay: ReasoningReplay? = null` to `Thinking` and add `ReasoningReplay(provider, itemId, payload)`.
2. File store: add `replay: ReasoningReplayRecord? = null` to `ThinkingBlockRecord`, where `ReasoningReplayRecord(provider: String, itemId: String? = null, payload: String)` is serialisable, and map it both ways in `SessionFilePersistenceMapper`. `SessionJsonl` already sets `explicitNulls = false`, so a missing replay writes nothing.
3. PostgreSQL: the same optional field on `ContentJsonCodec.Thinking` and its mapper. No migration: blocks are `jsonb`.
4. Leave `TranscriptNormalizer` alone; it still drops thinking until lane E's E2.
5. Tests: a session line written by 3c loads unchanged; a thinking block without replay writes the same bytes as before; a block with replay round-trips through the file store and through the PostgreSQL contract test (skipped without Docker, like the rest).

Done when: old files load and the new field round-trips in both stores.

### M0.5 Switch, review and tag (S, needs M0.1 to M0.4)

1. Add `api/config/TurnProperties.kt`: `@ConfigurationProperties("butler.tools") data class TurnProperties(val enabled: Boolean = false)`, registered with `@EnableConfigurationProperties` on `UseCaseConfig`, which builds `TurnSettings` from it and passes it to `RunTurnUseCase`. `BUTLER_TOOLS_ENABLED` overrides it, as for every Butler property.
2. Add `butler.tools.enabled: false` to `application.yml`, with a one-line comment that J1 turns it on at M1.
3. Test: the property defaults to false and binds true when set.
4. Copy the four refinements and the stand-in names into the Contracts tab.
5. Review: each lane's owner reads the Contracts tab and their lane doc, and approves the pull request or asks for an additive change.
6. Merge, tag `contracts-v1` on `main`, set M0.1 to M0.5 to Merged on the Tasks tab, and tell the lanes to branch.

## Tests and architecture checks

M0 proves two things: the new types hold their rules, and nothing a user can see has changed. Run `./gradlew build --continue` after each task; every check below runs in it.

| Check | Where | What it proves | Task |
| --- | --- | --- | --- |
| `WorkspacePathTest` | core | Escapes refused, absolute paths inside the root accepted | M0.1 |
| `BuiltInToolsTest` | core | Six tools, unique names, Clawcode's tiers | M0.1 |
| `BuiltInToolSchemasTest` | secondary | Every schema is valid JSON Schema of the shape providers expect | M0.1 |
| `StandInGatewaysTest` | secondary | Each stand-in answers "not available yet" or empty | M0.2 |
| `ToolDoublesTest` | core | The fakes record and answer in order, so lanes can trust them | M0.2 |
| `PermissionPolicyTest` | core | The placeholder rule, until D1 replaces it | M0.3 |
| Existing 217 tests | all | No behaviour change: same prompts, sessions, budgets, output | M0.3 |
| Session store tests, both stores | secondary | Old files load; replay round-trips; nothing extra is written | M0.4 |
| `TurnPropertiesTest` | api | `butler.tools.enabled` is off unless set | M0.5 |
| ArchUnit (`ArchitectureTest`) | api | core depends on nothing outside core and the JDK; gateways are interfaces; stand-ins end in `GatewayImpl` | Every task |
| `verifyCoreIsFrameworkFree` | core | core's runtime classpath is still only Kotlin | Every task |

No end-to-end test changes in M0: with tools off and stand-ins behind every port, the CLI's output must be byte-identical to 3c.

## Done when, and how lanes pick up

M0 is done when the tag `contracts-v1` is on a green `main`. Tick these as they land:

- [ ] M0.1 merged: tool model, `WorkspacePath`, six schemas
- [ ] M0.2 merged: six ports, stand-ins, fakes, five config classes
- [ ] M0.3 merged: turn contract; all 217 earlier tests green
- [ ] M0.4 merged: replay fields in both stores; old files load
- [ ] M0.5 merged: `butler.tools.enabled` off; Contracts tab updated
- [ ] Every lane owner has approved the contracts
- [ ] `contracts-v1` tagged on `main`

Then each lane:

1. Branches from the tag as `lane/<letter>-<name>`, for example `lane/c-file-tools`, and works task by task from its own lane doc.
2. Starts by replacing its stand-in, if it has one, in its own config class.
3. Tests against the fakes in `ToolDoubles.kt`, never against another lane's implementation.
4. Needs a contract change? Opens a small contract pull request first, as the plan's rules say, and merges it before the code that uses it.

## Additions from the lane review

The ten lane docs asked for contract changes. Nine are folded into M0, because more than one lane needs them; the rest stay with their lane as small additive contract pull requests. M0.5 copies all of this into the Contracts tab.

| Addition | Why | Needed by | Task |
| --- | --- | --- | --- |
| `ToolInputGateway.decode(workspace, name, input)` | Decoding turns absolute paths into `WorkspacePath`, which needs the root | C, A | M0.2 |
| `WorkspaceGateway.preview(workspace, invocation): String? = null` | The approval prompt shows an edit's diff before it runs; `ToolOutcome.preview` only exists after | C, D, A | M0.2 |
| `CommandGateway.run(workspace, command, sandbox, cancellation)` | Ctrl-C must stop a running command, not just the next step | F, A | M0.2 |
| `SandboxPolicy.forWorkspace(root)` | One default policy for the turn until lane F tunes it | A, F | M0.2 |
| `domain/shell/BashRisk.kt`: `CommandIntent`, `BashRisk` | Permissions read bash risk without depending on F's classifier, which stays F1 | F, D | M0.2 |
| `InstructionFile(path: Path, scope, content)`; `GitContext` gains `recentCommits`, `stagedFiles`, `diff` and a `GitCommit` type | Instruction files can sit above the workspace, up to the repository root | G, A | M0.2 |
| `ToolApprovalRequest.bashRisk` and `PermissionPolicy.decide(…, bashRisk: BashRisk? = null)` | The prompt and the policy both need the risk | D, F, A | M0.3 |
| `Command.allowedTools: Set<ToolName>? = null` | `--allowedTools` filters what the turn offers | D, A, I | M0.3 |
| `TurnSummary.interrupted: Boolean = false` | The CLI reports a cancelled turn differently | A, I | M0.3 |

Lane A's `ToolRunner` becomes `RunToolCallUseCase` in `usecase/turn`, because ArchUnit requires the `UseCase` suffix there.

**Left to the owning lane**

| Lane | Addition, as its own contract pull request |
| --- | --- |
| B | `ModelProfile.supportsTools` |
| E | Reasoning support on `ModelProfile`, `ModelRequest.reasoning`, `Command.reasoningEffort`, a `replayFor` parameter on `TranscriptNormalizer` |
| D | `PermissionRules.defaultMode` |
| F | `CommandGateway.status(workspace)` and `SandboxStatus` |
| G | `ProjectSetupGateway` for `butler init`; compaction settings; `TurnEvent.Compacted` |
| H | `McpGateway.servers()` with a default body |
| A and B, after M2 | Cancellation inside `ModelGateway.stream`, so Ctrl-C stops a response mid-stream |

**Task changes the lanes found**, applied to the Tasks tab in M0.5:

- C: Clawcode's real limits are a 10 MB read and write cap, 100 glob results and a default of 250 grep results, not the tab's 256 KB and 200.
- J: a new J0 for shared fakes that can script tool calls; J3 split into one task per gate; J1 also needs B4 and C3; J2 also needs A5, A6 and D5.
- E: E5 grows from S to M.
- G: G3 builds the compaction summary locally, without a model call, as Clawcode does.
- I: I2 also needs M0.2.

## Risks and decisions to confirm

Three decisions are worth your yes before M0.1 starts; the risks below have a fix built into the tasks.

**Decisions**

- [ ] **`bash`**** input without sandbox overrides.** Proposed: the model can send only `command`, `timeout` and `description`; Butler's policy decides the sandbox. The alternative keeps Clawcode's `dangerouslyDisableSandbox`, which would turn into an approval prompt.
- [ ] **All of Clawcode's ****`grep_search`**** fields.** Proposed: keep all 14, so models trained on Clawcode-style tools behave the same; this adds about a day to lane C's C3. The alternative offers `pattern`, `path`, `glob`, `output_mode` and `-i` only.
- [ ] **`PROMPT`**** as the default mode until question 4b is answered.** It changes nothing in M0, because no tool runs before M1.

**Risks**

| Risk | Effect | Built-in fix |
| --- | --- | --- |
| A contract needs to change after `contracts-v1` | Lanes rebase onto a moved type | Additive changes only, through a small contract pull request reviewed by the lanes that use it |
| Clawcode changes its tool schemas | Butler's tools drift from Clawcode's | `BuiltInTools` records the Clawcode commit it was copied from (`22fe360`, v0.1.7); J re-checks at each gate |
| The new `execute` signature breaks a caller | Build fails | Only `PromptActions` and `RunTurnUseCaseTest` call it; M0.3 moves both in the same pull request |
| Replay fields change session files | Clawcode or older Butler builds cannot read new files | Fields are written only when present, and nothing writes them until lane E |
