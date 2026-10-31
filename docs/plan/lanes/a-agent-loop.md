# Butler lane A: Agent loop

> Exported from the Claude Doc <https://claude.ai/artifact/CGVb9FuPTSEqgm7SHjRWDh> on 2026-10-24. The Claude Doc is the original; if they differ, read [STATUS.md](../STATUS.md) first for what has changed since.


2026-10-21 · Chamantha De silva

## Goal

Lane A turns one model call per turn into Clawcode's loop: ask the model, run the tools it calls, send back the results, and ask again until it answers without tools. It lands at gate M1, where Butler first reads and searches your files on every provider.

Why one lane owns it: `RunTurnUseCase` is the only place that sees the session, the model, budgets, permissions and tools at once. Every other lane plugs into it through a port, so the loop must have exactly one owner.

What users get when A and the other M1 lanes are merged:

- A question like "what does `RunTurnUseCase` do?" makes the model read the file and answer from it.
- Several tool calls in one reply run one after another, in order, as in Clawcode.
- A tool that fails, a call the user denies, or Ctrl-C never breaks the session: every tool call gets a result, so the next turn and `--resume` still work.
- Budgets and rate limits are checked before every model call in the loop, not only the first.

## Scope

Lane A owns the loop and the order of its steps; what each step does belongs to the lane behind the port.

**In lane A**

- `ToolRunner`: decode a tool call through `ToolInputGateway`, then route it to `WorkspaceGateway`, `CommandGateway` or `McpGateway` (A1).
- The loop in `RunTurnUseCase`: offer tools, run the calls of one reply in order, record each result, call the model again, stop when a reply has no calls (A2).
- The iteration limit and turning every failure inside a step into an error result (A3).
- Budget and rate-limit checks before every model call, and usage recorded per call (A4).
- The permission step: `PermissionPolicy.decide`, then `ToolApprover` on `Ask`; a denial becomes an error result (A5).
- Cancellation between steps, with an "interrupted" result for every unfinished call (A6).
- `ToolStarted` and `ToolFinished` events, so lane I can show calls.

**Not in lane A**

- What the tools do (C, F, H) and how their input is decoded (C).
- The permission rules themselves and the approval prompt (D). Lane A only calls them.
- Sending tools in provider requests (B). Lane A only fills `ModelRequest.tools`.
- Showing calls and catching Ctrl-C (I). Lane A only emits events and polls `TurnCancellation`.
- Reasoning replay (E) and auto-compaction (G). Lane G will add its compaction check to the loop through a hook point lane A leaves for it.
- Clawcode's hooks and steering queue: after v1.

## Starting point

Butler already has most of what a loop needs: a session that tracks pending tool calls, budget-aware model choice with retries, and a transcript normalizer. What it lacks is the loop itself; today a turn makes exactly one model call and offers no tools.

**In Butler today (after M0)**

| File | What it does now | What lane A changes |
| --- | --- | --- |
| `core/usecase/turn/RunTurnUseCase.kt` | Saves the prompt, picks a model through `approvedModel`, makes one call through `answer` (up to 5 attempts with approvals), saves the reply, records usage once. `OFFERED_TOOLS` is empty; any stray call is closed with "Tools are not available in this version of Butler" | Wraps the call in the loop; offers tools when `settings.toolsEnabled` |
| `core/domain/session/Session.kt` | `pendingToolCalls()`, `recordToolResult()` (refuses a result with no pending call), `closeDanglingToolCalls(reason)`; `appendAssistantMessage` refuses while calls are pending | Nothing: the loop uses these as they are |
| `core/domain/model/TranscriptNormalizer.kt` | Keeps a tool call native only if its tool is offered and answered | Nothing: the loop passes the offered tool names instead of an empty set |
| `core/domain/turn/Turn.kt` | `TurnEvent` with `ToolStarted` and `ToolFinished` (M0); `TurnSummary.iterations` is always 1 | Emits the tool events; counts real iterations |
| `core/domain/turn/TurnCallbacks.kt` | `TurnCallbacks`, `ToolApprover`, `TurnCancellation`, `TurnSettings(toolsEnabled)` (M0) | Adds `maxIterations` to `TurnSettings` |
| `core/testFixtures/.../ModelDoubles.kt` | `ScriptedModelGateway` replies with text or a failure | Adds a reply that contains tool calls |
| `core/test/.../RunTurnUseCaseTest.kt` | Single-call turns, budgets, fallbacks, cooldowns | New loop tests beside the existing ones |

**In Clawcode (****`22fe360`****, v0.1.7) to port from**

| File | What to take |
| --- | --- |
| `rust/crates/runtime/src/conversation.rs`, `run_turn` | The loop's order: check interrupt, count the iteration, call the model, save the reply, stop if it has no tool calls, otherwise run each call in order and save its result |
| Same file, interrupt handling | Every pending call still gets a result, worded "Interrupted by user before this tool could run.", so the session stays valid |
| Same file, iteration guard | Exceeding the limit fails with "conversation loop exceeded the maximum number of iterations". The CLI runtime sets no limit (`usize::MAX`); only sub-agents use one, `DEFAULT_AGENT_MAX_ITERATIONS = 32` in `tools/src/lib.rs` |
| Same file, tool errors | A tool error becomes the result text with `is_error` set; a permission denial's reason becomes an error result |
| `rust/crates/runtime/src/permissions.rs` | `PermissionPrompter.decide` answers only Allow or Deny; Butler's "allow for this session" is an addition |

## Where the code goes

Lane A works almost entirely in core's `usecase/turn` package. One refinement to the plan: the tool router is `RunToolCallUseCase` in `usecase/turn/`, not `ToolRunner` in `domain/tool/`. It calls ports, which is use-case work, and ArchUnit requires every top-level class in `core.usecase` to end in `UseCase`.

| File | Module | New or changed | Task | Owner |
| --- | --- | --- | --- | --- |
| `usecase/turn/RunToolCallUseCase.kt` | core | New: decode, permission step, route to a port, build the result | A1, A5 | A |
| `usecase/turn/RunTurnUseCase.kt` | core | Changed: the loop, per-call budget checks, cancellation | A2, A3, A4, A6 | A |
| `domain/turn/TurnCallbacks.kt` | core | Changed: `maxIterations` on `TurnSettings` | A3 | A |
| `domain/exception/ButlerException.kt` | core | Changed: `IterationLimitException` | A3 | Shared |
| `testFixtures/.../fixtures/ModelDoubles.kt` | core | Changed: `ScriptedModelGateway` can reply with tool calls | A2 | Shared, append-only |
| `test/.../usecase/turn/RunToolCallUseCaseTest.kt` | core | New | A1, A5 | A |
| `test/.../usecase/turn/ToolLoopTest.kt` | core | New: loop, limit, budgets, cancellation | A2 to A6 | A |
| `cli/exception/CliExceptionHandler.kt` | api | Changed: one branch for `IterationLimitException` | A3 | Shared |
| `config/UseCaseConfig.kt` | api | Changed: builds `RunToolCallUseCase`; passes it and `McpGateway` to `RunTurnUseCase` | A1, A2 | Shared, append-only |
| `config/TurnProperties.kt` | api | Changed: `max-iterations` | A3 | Shared, append-only |
| `src/main/resources/application.yml` | api | Changed: `butler.tools.max-iterations` | A3 | Shared, append-only |

Lane A never edits secondary: every port it calls already has a stand-in from M0.

## Design

The loop keeps Clawcode's order of steps exactly, and reuses Butler's existing model choice for every call in it. The transcript is valid after every step, so a crash, a denial or Ctrl-C at any point leaves a session the next turn can continue.

*(Diagram in the Claude Doc: one turn's loop · 3 checks, 2 stops, 1 answer)*

Read it top down: each pass checks cancellation and the limit, picks a model, calls it, and either returns the answer or runs the calls and goes round again.

### Decisions

| Decision | Reason |
| --- | --- |
| Calls in one reply run one after another, never in parallel | Clawcode does the same; approvals must come in order, and results land in the order the model asked |
| Every model call goes through today's `approvedModel` and `afterFailure` | Budgets, rate limits and fallbacks already work; the loop only re-estimates the transcript before each call, now that tool results grow it |
| Usage is recorded after each model call, not once per turn | A ten-call turn spends ten times; the ledger and the next budget check must see each call |
| The session is saved after each assistant reply and each tool result | Same as the prompt today: nothing the model or a tool did is lost if the process dies |
| Tools are offered only when `settings.toolsEnabled`: `BuiltInTools.ALL` plus `mcp.tools()`, read once per turn | Off until J1 flips it at M1, so half-built lanes never reach users; one read keeps the list stable within a turn |
| The offered names go to `TranscriptNormalizer` instead of today's empty set | Earlier calls of offered tools then stay native for the provider, as lane B needs |
| A failure inside a tool step becomes an error result, never an exception | Clawcode does the same: the model can read the error and try something else. Model failures still end the turn, as today |
| `ToolStarted` is emitted before the permission step, `ToolFinished` after the result | The user sees which call is asking before the approval prompt appears |
| Cancellation is polled before each model call, before each tool call and after each approval; a stream is stopped by throwing from its event callback | Polling keeps core free of threads. Lane B must confirm the clients close the HTTP stream when the callback throws |
| "Allow for this session" is remembered by the approver, not by core | Use cases are stateless singletons; the CLI's approver (lane D) lives as long as the REPL. Core treats both allow answers the same |

### Wording, from Clawcode where it has one

| Case | Result text | Source |
| --- | --- | --- |
| Call skipped because the turn was cancelled | Interrupted by user before this tool could run. | Clawcode `conversation.rs` |
| User denied the call | tool '<name>' denied by user approval prompt | Clawcode `rusty-claude-cli/src/main.rs` |
| Model called a tool that was not offered | unsupported tool: <name> | Clawcode `tools/src/lib.rs` |
| Turn hit the limit | conversation loop exceeded the maximum number of iterations | Clawcode `conversation.rs` |
| A port threw unexpectedly | Tool <name> failed: <message> | Butler's own; Clawcode returns the bare error text |

### New and changed types

```kotlin
// usecase/turn/RunToolCallUseCase.kt: one tool call, from raw input to a result
class RunToolCallUseCase(
    private val inputs: ToolInputGateway,
    private val files: WorkspaceGateway,
    private val commands: CommandGateway,
    private val mcp: McpGateway,
) {
    data class Command(
        val workspace: WorkspaceRoot,
        val call: ContentBlock.ToolUse,
        val tool: ToolDefinition?,       // null: the model called a tool this turn did not offer
        val mode: PermissionMode,
        val rules: PermissionRules,
    )

    fun execute(command: Command, approver: ToolApprover): ContentBlock.ToolResult
}

// domain/turn/TurnCallbacks.kt
data class TurnSettings(val toolsEnabled: Boolean = false, val maxIterations: Int = 32)

// domain/turn/Turn.kt: additive, so lane I can show "interrupted"
data class TurnSummary(/* existing fields, */ val interrupted: Boolean = false)

// domain/exception/ButlerException.kt
class IterationLimitException(val limit: Int) :
    ButlerException("conversation loop exceeded the maximum number of iterations ($limit)")

// usecase/turn/RunTurnUseCase.kt: three more dependencies
class RunTurnUseCase(/* existing nine, */ private val runToolCall: RunToolCallUseCase,
    private val mcp: McpGateway, private val permissionRules: PermissionRulesGateway,
    private val settings: TurnSettings = TurnSettings())
```

`TurnSummary.interrupted` and the `SandboxPolicy` that `CommandGateway` needs are small contract additions; see Risks.

## Tasks

Six tasks, one pull request each, branched as `lane/a/<task>`. A1 then A2 come first, because M1 needs them. A3 to A6 all edit `RunTurnUseCase`, so one person should take them in this order: A5, A6, A4, A3. M2 needs A5 and A6.

### A1 Run one tool call (S, needs M0)

1. Add `RunToolCallUseCase` with the four ports, as in Design, and build it in `UseCaseConfig`.
2. If `tool` is null, return the error result "unsupported tool: <name>".
3. Decode with `inputs.decode(name, input)`. Catch `InvalidToolInputException` and return its message as an error result.
4. Route by type: the five file invocations go to `files.run`, `Bash` goes to `commands.run`, `External` goes to `mcp.call`. Bash also needs a sandbox policy: pass a workspace-only default until lane F provides one (see Risks). A5 adds the permission step before this.
5. Catch any other `RuntimeException` from a port and return "Tool <name> failed: <message>".
6. Map the `ToolOutcome` to a `ToolResult` with the call's id and name.
7. Tests in `RunToolCallUseCaseTest`, with the M0 fakes: one per invocation type reaches the right port; an unknown tool, bad input and a throwing port each give an error result.

Done when: each invocation type has a passing test and nothing calls the new class yet.

### A2 The loop (M, needs A1)

1. Give `RunTurnUseCase` the `runToolCall` and `mcp` dependencies.
2. At the start of a turn, build the offered list: `BuiltInTools.ALL + mcp.tools()` when `settings.toolsEnabled`, otherwise empty.
3. Split today's `answer` so each pass normalises the current transcript with the offered names and sends `ModelRequest(model, systemPrompt, transcript, tools = offered)`.
4. After each reply: save it. With tools off, keep today's behaviour and close stray calls with "Tools are not available in this version of Butler". With tools on, run each pending call in order: emit `ToolStarted`, call `runToolCall`, record the result with `session.recordToolResult`, save, emit `ToolFinished`.
5. Leave one marked call site after each saved reply, where lane G will add its compaction check.
6. Stop when a reply has no tool calls. The summary lists every reply, counts model calls as `iterations` and sums their usage.
7. Add a tool-call reply to `ScriptedModelGateway`, for example `toolCall(name, input)`.
8. Tests in `ToolLoopTest`: a `read_file` call then an answer ends with the answer; the second request carries the result; two calls in one reply run in order; events arrive as Started, ToolStarted, ToolFinished, Completed; with tools off, requests carry no tools and every existing test still passes.

Done when: a scripted two-call turn ends with an answer, and the 3c behaviour is unchanged with tools off.

### A3 Iteration limit (S, needs A2)

1. Add `maxIterations` to `TurnSettings` (proposed default 32, see Risks), refuse values below 1, and bind `butler.tools.max-iterations`.
2. Before each model call, if the turn has already made `maxIterations` calls, save the session and throw `IterationLimitException`. No calls are pending at that point, so the transcript is valid.
3. Map the exception in `CliExceptionHandler`: kind `iteration_limit`, runtime exit code.
4. Tests: a model that always calls a tool stops at the limit; the saved session has no pending calls; a following prompt continues it; the CLI prints `iteration_limit`.

Done when: a looping model stops at the limit with a valid transcript.

### A4 Budgets and usage per call (S, needs A2)

1. Before every model call, re-estimate input tokens from the current transcript, which now includes tool results.
2. Run `approvedModel` before every call. A `SwitchTo` answer changes the model for the rest of the turn and is saved with `switchModel`, as today.
3. Move `usage.record` into the loop: one record per model call.
4. Apply `MAX_ATTEMPTS` per model call, not per turn.
5. Tests: a budget breach before the second call reaches the `TurnApprover`; `Stop` throws `BudgetExceededException` after the tool results are saved; the ledger holds one record per call; a 429 on the second call sets a cooldown and offers alternatives.

Done when: a budget breach mid-loop reaches the approver, and usage is recorded per call.

### A5 Permission step (S, needs A2)

1. In `RunTurnUseCase`, read `permissionRules.rules()` once per turn and pass `command.permissionMode` and the rules to each call.
2. In `RunToolCallUseCase`, after decoding, call `PermissionPolicy.decide`.
3. `Allow` runs the tool. `Deny` returns its reason as an error result. `Ask` builds a `ToolApprovalRequest` and calls `callbacks.toolApprover`: either allow answer runs the tool; `DENY` returns "tool '<name>' denied by user approval prompt".
4. Preview: the command for `Bash`, the raw input for `External`, null for file tools until the contract can produce a diff (see Risks).
5. Tests: a denied call reaches the model as an error result; a policy `Deny` never calls the approver; `Ask` calls it once with the right tier, mode and reason; `ALLOW_FOR_SESSION` runs the tool.

Done when: a denied call reaches the model as an error result.

### A6 Cancellation (S, needs A2)

1. Poll `callbacks.cancellation` before each model call, before each tool call and after each approval.
2. While streaming, throw a private `TurnInterrupted` from the event callback when cancelled; catch it around the model call and discard the partial reply. A provider failure while cancelled also counts as an interruption, as in Clawcode.
3. Give every remaining pending call the result "Interrupted by user before this tool could run.". Today's crash-recovery text at the start of a turn stays as it is.
4. Save the session and return a summary with `interrupted = true`; `Completed` is still emitted.
5. Tests with `CancelAfter(n)` at each poll point: the saved session is valid, the summary says interrupted, and the next prompt continues it; a cancel during a stream keeps no partial text.

Done when: a cancelled turn saves a valid transcript at every poll point.

## Tests

All of lane A's tests are core tests with in-memory fakes; they need no network, disk or Spring. The rule for every test: after the turn, the saved session has no pending tool calls.

| Test class | Covers | Fakes used | Task |
| --- | --- | --- | --- |
| `RunToolCallUseCaseTest` | Routing per invocation type, unknown tool, bad input, throwing port, permission outcomes | `FakeToolInputGateway`, `RecordingWorkspaceGateway`, `RecordingCommandGateway`, `FakeMcpGateway`, `RecordingToolApprover` | A1, A5 |
| `ToolLoopTest`, loop | Two-call turn, two calls in one reply, event order, tools off unchanged | `ScriptedModelGateway` with tool-call replies, `InMemorySessionGateway` | A2 |
| `ToolLoopTest`, limit | A model that never stops calling tools | Same | A3 |
| `ToolLoopTest`, budgets | Breach before call two, 429 on call two, one usage record per call | Budget doubles in `BudgetDoubles.kt`, `MutableClock` | A4 |
| `ToolLoopTest`, permissions | Denied call reaches the model, policy deny skips the approver | `FixedPermissionRulesGateway`, `RecordingToolApprover` | A5 |
| `ToolLoopTest`, cancellation | Cancel at each poll point and mid-stream | `CancelAfter` | A6 |
| `RunTurnUseCaseTest` (existing) | Single-call turns, budgets, fallbacks, cooldowns | Existing | Every task: must stay green |

Lane A writes no end-to-end tests. Lane J's M1 and M2 checks run the loop through the real CLI against fake providers.

## Configuration

Lane A adds one property to M0's `butler.tools` block; neither holds a secret. Spring's relaxed binding drops the dash in the environment name. Both bind through `TurnProperties` in api and reach core as `TurnSettings`.

| Property | Environment override | Default | Owner | Meaning |
| --- | --- | --- | --- | --- |
| `butler.tools.enabled` | `BUTLER_TOOLS_ENABLED` | `false` until J1 sets it to `true` at M1 | M0, then J | Offer tools to the model at all |
| `butler.tools.max-iterations` | `BUTLER_TOOLS_MAX``ITERATIONS` | `32` (proposed) | A | Most model calls in one turn before it stops with `iteration_limit` |

```yaml
butler:
  tools:
    enabled: false          # J1 turns this on at gate M1
    max-iterations: 32      # one turn's model calls; Clawcode's sub-agents use the same limit
```

## Working with other lanes

Lane A can finish every task against M0's fakes, without waiting for any other lane. Its outputs are what lanes D, I and J build on, so A1 and A2 should merge first.

| Lane | What A uses from it | What it uses from A | Hand-off |
| --- | --- | --- | --- |
| B Provider tools | Nothing directly | `ModelRequest.tools` filled with the offered definitions | B confirms its clients close the HTTP stream when the event callback throws (A6) |
| C File tools | `ToolInputGateway`, `WorkspaceGateway` behind `FakeToolInputGateway` and `RecordingWorkspaceGateway` | Calls routed in order, one at a time | C's real gateways replace the stand-ins; no change in A |
| D Permissions | `PermissionPolicy.decide` (placeholder until D1), `PermissionRulesGateway` | The `ToolApprovalRequest` A builds, and the mode from `Command.permissionMode` | D's `CliToolApprover` remembers "allow for this session" |
| E Reasoning replay | Nothing | The normalizer call A makes each pass | E changes the normalizer, not the loop |
| F Sandboxed bash | `CommandGateway` behind `RecordingCommandGateway` | `Bash` invocations with a sandbox policy | F replaces the workspace-only default policy |
| G Project context | Nothing in A's tasks | The marked call site after each saved reply | G adds its compaction call there, reviewed by A |
| H MCP client | `McpGateway.tools()` and `call` behind `FakeMcpGateway` | `External` invocations | H's tools appear in the offered list with no change in A |
| I Terminal | Nothing | `ToolStarted`, `ToolFinished`, `TurnSummary.interrupted`, polling of `TurnCancellation` | I renders the events and triggers cancellation on Ctrl-C |
| J Integration | Nothing | The whole loop | J1 needs A2; J2 needs A5 and A6 |

What J checks for lane A: at M1, a two-call turn on all three provider fakes; at M2, a denied call that reaches the model as an error and an interrupted turn that resumes cleanly.

## Done when

Lane A is done when all six tasks are merged and J has tagged `gate-m2`. Tick these as they land:

- [ ] A1 merged: `RunToolCallUseCase` routes every invocation type
- [ ] A2 merged: the loop runs; with tools off, 3c behaviour is unchanged
- [ ] A5 merged: policy, then approver; denials reach the model
- [ ] A6 merged: cancellation at every poll point leaves a valid session
- [ ] A4 merged: budgets checked and usage recorded per model call
- [ ] A3 merged: the iteration limit stops a looping model
- [ ] Contract additions merged: `TurnSummary.interrupted`, the default sandbox policy
- [ ] `gate-m1` tagged by J with A2 in it
- [ ] `gate-m2` tagged by J with A5 and A6 in it

## Risks and decisions to confirm

Three decisions need your yes before A2, and three small contract additions need a contract pull request before the tasks that use them.

**Decisions**

- [ ] **Iteration limit of 32.** Clawcode's CLI sets no limit at all (`usize::MAX`); its sub-agents stop at 32. The Tasks tab said 25 with no source. Proposed: 32, configurable, so a runaway model cannot spend a whole budget in one turn.
- [ ] **"Allow for this session" lives in the approver.** Proposed: lane D's `CliToolApprover` remembers grants for as long as the REPL runs; core keeps no state between turns. The alternative stores grants in the session file, so they survive `--resume`.
- [ ] **`RunToolCallUseCase`**** instead of ****`ToolRunner`****.** Same job, placed in `usecase/turn` to satisfy ArchUnit; the Workstreams table on the plan should be updated to match.

**Contract additions (additive, through a contract pull request)**

| Addition | Needed by | Why |
| --- | --- | --- |
| `TurnSummary.interrupted: Boolean = false` | A6, then lane I | The CLI must tell an interrupted turn from a finished one, as Clawcode's summary does |
| `SandboxPolicy.forWorkspace(root)`: writable workspace, no network | A1, then lane F refines it | `CommandGateway.run` needs a policy, and M0 gives no way to build one |
| `WorkspaceGateway.preview(workspace, invocation): String? = null` | A5, then lanes C and D | An approval prompt for an edit should show the diff before the edit runs; only the adaptor can read the file to build it |

**Risks**

| Risk | Effect | Built-in fix |
| --- | --- | --- |
| A client does not close its HTTP stream when the event callback throws | Ctrl-C waits for the stream to end, or leaks a connection | Lane B adds a test for it; until then, cancellation still works between steps |
| The file store rewrites the whole session file on every save | Long, tool-heavy sessions get slower to save | J1 records the timing; an append-only save is a later persistence change, not a loop change |
| A3 to A6 all edit `RunTurnUseCase` | Merge conflicts inside lane A | One person, one task at a time, in the order A5, A6, A4, A3 |
| Normalizing the whole transcript on every pass costs time | Slower turns with many calls | The normalizer is linear and pure; measure in J1 before optimising |
