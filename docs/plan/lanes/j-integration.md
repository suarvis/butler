# Butler lane J: Integration and gates

> Exported from the Claude Doc <https://claude.ai/artifact/UNqSpFQzMYKabgEqoEe2ru> on 2026-10-24. The Claude Doc is the original; if they differ, read [STATUS.md](../STATUS.md) first for what has changed since.


2026-10-21 · Chamantha De silva

## Goal

Lane J proves that the lanes work together. At each gate (M1 to M6) it runs one end-to-end check through the real CLI against fake provider servers, tags the green commit `gate-mN`, and updates the README and project doc to say what now works.

Why one owner: every other lane tests against fakes of its neighbours, so no lane on its own can see that the real pieces fit. J is the only lane that wires them all together. It owns no feature code, so it can judge a gate without defending its own work.

What J delivers:

- An end-to-end test per gate, modelled on Clawcode's mock parity scenarios (`rust/mock_parity_scenarios.json`), run on the Anthropic, Responses and Chat Completions fakes.
- The switch from `butler.tools.enabled: false` to `true` at M1, the moment users first get tools.
- Six tags, `gate-m1` to `gate-m6`; `gate-m6` marks v1 done.
- Docs that match what ships at every gate: README status, the project doc's progress line, short release notes.

## Scope

J writes tests, fakes, configuration defaults and docs, never feature code. When a gate check fails, the fix goes back to the lane that owns the code.

**In lane J**

- Shared fake providers for the CLI tests that can script tool calls on all three dialects (J0, new).
- One end-to-end test class per gate, run through `ButlerApplication.run` exactly as the existing CLI tests do (J1 to J3).
- Turning `butler.tools.enabled` on at M1 (J1).
- Tagging `gate-m1` to `gate-m6` on green `main` commits.
- README, project doc and release notes at each gate (J4).
- Re-checking Butler's six tool schemas against Clawcode's at each gate, so the two do not drift.

**Never in lane J**

- Patching another lane's code to make a gate pass. A failure becomes a new task for the owning lane.
- Changing a contract. J asks for it through the contract pull request like any lane.
- Starting lanes or blocking merges. Lanes merge whenever they are green; a gate is a checkpoint, not a start signal.

## Starting point

Butler already runs the real CLI end to end against local fake servers; what is missing is a fake that can answer with tool calls. Clawcode has a ready list of scenarios to copy.

**In Butler today**

| File | What it does now | What J does with it |
| --- | --- | --- |
| `api-primary-adaptor/src/test/.../cli/PromptEndToEndTest.kt` | 20 tests that run `ButlerApplication.run("--cwd", workspace, ...)` with captured stdin, stdout and stderr; point providers at fakes through system properties (`butler.providers.<id>.base-url`, `api-key`); keep `~/.butler` in a temp folder | The pattern every gate test follows |
| Same file, `FakeAnthropic` and `FakeOpenAi` (private) | Answer `/v1/messages`, `/v1/responses` and `/v1/chat/completions` with text only, and record request bodies | Moved to a shared file and taught to answer with tool calls (J0) |
| `api-primary-adaptor/src/test/.../cli/CliEndToEndTest.kt` | CLI contract: help, version, sessions, error envelopes | Unchanged; must stay green at every gate |
| `secondary-adaptor/src/test/.../client/FakeProviderServer.kt` | Adaptor-level fake with `AnthropicStreams`, `OpenAiStreams`, `OpenAiResponsesStreams` text helpers | Lane B adds tool-call streams here for its own tests; J reuses the same JSON shapes so both fakes agree |
| `README.md` | Status line says slices 0–3 and "Tools, permission approvals and MCP come in the next slices" | Rewritten at each gate (J4) |
| Project doc `claude/butler-rewrite-proposal.md`, "Progress" and "Next" | Records 3c as the latest slice | One line per gate (J4) |

**In Clawcode (****`22fe360`****, v0.1.7)**

| File | What to take |
| --- | --- |
| `rust/mock_parity_scenarios.json` | 12 named scenarios, among them `read_file_roundtrip`, `multi_tool_turn_roundtrip`, `write_file_allowed`, `write_file_denied`, `bash_permission_prompt_approved`, `bash_permission_prompt_denied`, `auto_compact_triggered`, `token_cost_reporting` |
| `rust/MOCK_PARITY_HARNESS.md`, `rust/scripts/run_mock_parity_harness.sh` | How Clawcode runs the real binary against `mock-anthropic-service`; J's gate tests do the same with Butler's JVM entry point |
| `rust/crates/mock-anthropic-service/` | The JSON shape of scripted tool-use streams, as a cross-check for J0 |

## Where the code goes

J's code is test code in api-primary-adaptor plus the docs; the only production change is one line of `application.yml`. Test paths are under `api-primary-adaptor/src/test/kotlin/com/suarvis/butler/api/cli/`.

| File | Module | New or changed | Task | Owner |
| --- | --- | --- | --- | --- |
| `support/FakeProviders.kt` | api test | New: `FakeAnthropic` and `FakeOpenAi` moved out of `PromptEndToEndTest`, able to script tool calls | J0 | J; lanes add helpers, append-only |
| `support/ButlerCli.kt` | api test | New: the shared `butler(...)` runner, temp workspace and provider properties | J0 | J |
| `PromptEndToEndTest.kt` | api test | Changed: uses the shared fakes and runner; same 20 tests | J0 | J |
| `ToolsEndToEndTest.kt` | api test | New: the M1 check | J1 | J |
| `EditsEndToEndTest.kt` | api test | New: the M2 check | J2 | J |
| `ReasoningEndToEndTest.kt` | api test | New: the M3 check | J3 | J |
| `CommandsEndToEndTest.kt` | api test | New: the M4 check (macOS part skipped elsewhere) | J3 | J |
| `ProjectContextEndToEndTest.kt` | api test | New: the M5 check | J3 | J |
| `McpEndToEndTest.kt` | api test | New: the M6 check, using lane H's test MCP server | J3 | J |
| `api-primary-adaptor/src/main/resources/application.yml` | api | Changed: `butler.tools.enabled: true` | J1 | Shared, append-only |
| `README.md` | root | Changed at every gate | J4 | J |
| `CHANGELOG.md` | root | New: one entry per gate | J4 | J |
| Project doc `claude/butler-rewrite-proposal.md` | Project | Changed at every gate | J4 | J |

One file per gate keeps each gate's test easy to find, and lets M3 to M6 merge in any order without touching each other's file.

## Design

A gate check is a short story told through the real CLI: a temp workspace with a few files, a fake provider that answers with scripted tool calls, and assertions on what the provider received, what the CLI printed and what the session saved. Each story runs on all three provider dialects.

*(Diagram in the Claude Doc: gates M0 to M6 · lanes merged and the check for each)*

M1 and M2 come in order because M2 builds on the loop and read tools; M3 to M6 each depend only on their own lane, so they can be tagged whenever that lane is merged.

### How one gate check runs

1. Create a temp workspace with the files the story needs, and a temp `~/.butler`, exactly as `PromptEndToEndTest` does today.
2. Point each provider at the fakes through system properties: `butler.providers.anthropic.base-url`, `openai` (Responses) and `ollama` (Chat Completions, through an `ollama/<model>` reference).
3. Queue the replies: first a reply with one or more tool calls, then a reply with the final text.
4. Run the CLI through `ButlerApplication.run("--cwd", workspace, ...)`, usually as `prompt` with `--output-format json`.
5. Assert on four things: the first request offered the tool schemas; the second request carried the tool result in the dialect's native form; the JSON result holds the answer; the saved session holds the call and its result.
6. Repeat steps 2 to 5 for each dialect, using a parameterised test so one story covers all three.

### Scripted tool calls in the fakes (J0)

The fakes gain one helper per dialect. Each must match the shapes lane B's clients parse, so J copies them from lane B's adaptor tests in `FakeProviderServer.kt` once B1 to B3 land.

| Dialect | Fake reply for a tool call | Native result J asserts in the next request |
| --- | --- | --- |
| Anthropic Messages | `content_block_start` with a `tool_use` block, `input_json_delta` chunks, `stop_reason` `tool_use` | A `tool_result` block with the same `tool_use_id` |
| OpenAI Responses | `response.output_item.done` with a `function_call` item (`call_id`, `name`, `arguments`), then `response.completed` | A `function_call_output` input item with the same `call_id` |
| Chat Completions | `delta.tool_calls` with `id`, `function.name` and `arguments` in pieces, `finish_reason` `tool_calls` | A `role: tool` message with the same `tool_call_id` |

Arguments arrive split across chunks in every helper, as in Clawcode's `grep_chunk_assembly` scenario, because that is where clients break.

### Decisions

| Decision | Reason |
| --- | --- |
| One test class per gate | M3 to M6 can merge in any order without touching each other's file |
| Every story on all three dialects | Tools must work on Anthropic, Responses and Chat Completions; a bug in one mapper must fail a gate |
| Clawcode's scenario names in the test names | Shows parity at a glance and makes Clawcode's harness a ready checklist |
| J asserts on the wire and on the saved session, not on log text | Output wording belongs to lane I and may change; the protocol and the transcript may not |
| `butler.tools.enabled` flips only in J1 | Users get tools exactly when the M1 story passes on all three dialects |

## Tasks

The Tasks tab lists J1 to J4. This doc adds J0, the shared fakes, and splits J3 into one task per gate so each can be tagged on its own. Branch as `lane/j/<task>`. Two task needs are refined: J1 also needs B4 and C3, and J2 also needs A5, A6 and D5 (see Working with other lanes).

### J0 Shared fakes and runner (S, needs M0)

1. Move `FakeAnthropic`, `FakeOpenAi` and the `butler(...)` runner out of `PromptEndToEndTest` into `support/FakeProviders.kt` and `support/ButlerCli.kt`. The 20 existing tests must pass unchanged.
2. Add one tool-call helper per dialect, as in Design, with arguments split across at least two chunks.
3. Add request accessors: requests by path, parsed as JSON.
4. Once B1 to B3 land, compare each helper's JSON with lane B's adaptor fakes and align any difference.

Done when: `PromptEndToEndTest` is green on the shared support files, and each helper's stream is parsed by the real client in a smoke test.

### J1 Gate M1: reads your code (M, needs J0, A2, B1 to B4, C2, C3, I1)

1. Add `ToolsEndToEndTest`, parameterised over Anthropic, Responses and Chat Completions.
2. `read_file_roundtrip`: the workspace holds `notes.txt`; reply 1 calls `read_file` on it, reply 2 answers. Assert the six schemas in request 1, the file text as a native result in request 2, the answer in the JSON result, and the call and result in the saved session.
3. `multi_tool_turn_roundtrip`: `read_file` and `grep_search` in one reply, both results in order.
4. `grep_chunk_assembly`: the `grep_search` arguments arrive in pieces and still decode.
5. An unknown tool and a `../` path each reach the model as an error result; nothing outside the workspace is read.
6. `token_cost_reporting`: a two-call turn writes two usage records and reports the summed cost.
7. In the same pull request, set `butler.tools.enabled: true` and fix any existing assertion that expected requests without tools.
8. When green on `main`, tag `gate-m1` and run J4.

Done when: green on all three dialects, tools on by default, `gate-m1` tagged.

### J2 Gate M2: edits safely (M, needs J1, A5, A6, C4, C5, D3, D5, I4)

1. Add `EditsEndToEndTest`.
2. `write_file_allowed`: with `--permission-mode workspace-write`, the write succeeds and the file holds the new text.
3. An approved edit: in the REPL, piped input answers lane D's prompt with yes; the file changes and the diff preview was printed.
4. `write_file_denied`: in read-only mode the model gets an error result and the file is untouched.
5. In JSON mode, an `Ask` becomes a denial listed in the JSON result (D5).
6. An interrupted turn: a slow fake reply, a cancel through lane I's test hook, then `--resume latest`; the next request carries a result for every call.
7. Tag `gate-m2` and run J4.

Done when: all five stories pass and `gate-m2` is tagged.

### J3a Gate M3: thinks across calls (M, needs E2, E3, E4)

1. Add `ReasoningEndToEndTest`.
2. Responses: reply 1 holds an encrypted reasoning item and a `function_call`; request 2 replays the reasoning item before the call's output.
3. Anthropic: a signed thinking block comes back intact in request 2.
4. After `/model` switches provider, no reasoning from the old provider is sent.
5. Tag `gate-m3` and run J4.

### J3b Gate M4: runs commands (M, needs F1 to F5)

1. Add `CommandsEndToEndTest`.
2. `bash_stdout_roundtrip` in danger-full-access mode; `bash_permission_prompt_approved` and `bash_permission_prompt_denied` from workspace-write.
3. On macOS only (`@EnabledOnOs(MAC)`), a write outside the workspace is blocked by the sandbox.
4. A command past its timeout leaves no child processes (checked with `ProcessHandle` descendants).
5. Tag `gate-m4` and run J4.

### J3c Gate M5: knows your project (M, needs G1 to G4)

1. Add `ProjectContextEndToEndTest`.
2. A `CLAUDE.md` in the workspace appears in the system prompt of request 1.
3. `auto_compact_triggered`: a session past the threshold compacts, and the next turn resumes from the summary.
4. `/compact` in the REPL does the same on demand.
5. Tag `gate-m5` and run J4.

### J3d Gate M6: MCP, v1 done (M, needs H2, H4, H5)

1. Add `McpEndToEndTest` with lane H's local test MCP server configured under `butler.mcp.servers`.
2. The model calls `mcp__<server>__<tool>`; the result reaches the model; `butler mcp` lists the server and its tools.
3. Tag `gate-m6`: v1 is done. Run J4.

### J4 Docs at every gate (S, each gate)

1. README: status line and the list of what works, plus any new property or flag.
2. `CHANGELOG.md`: one entry per gate with its date, what works and new settings.
3. Project doc: the Progress and Next lines.
4. Compare `BuiltInTools` schemas with Clawcode's at the recorded commit; any drift becomes a task for lane C or F.
5. Set the gate's rows to Merged on the Tasks tab and tell everyone what now works.

Done when: the docs match what the tag ships.

## Tests

Each gate has one test class; together they become Butler's parity suite. All run in the normal `./gradlew build`, need no network or API key, and finish in seconds because the fakes answer at once.

| Gate | Test class | Stories (Clawcode scenario where one exists) | Dialects | Runs on |
| --- | --- | --- | --- | --- |
| M1 | `ToolsEndToEndTest` | `read_file_roundtrip`, `multi_tool_turn_roundtrip`, `grep_chunk_assembly`, `token_cost_reporting`, unknown tool, path escape | All three | Every OS |
| M2 | `EditsEndToEndTest` | `write_file_allowed`, `write_file_denied`, approved edit through the prompt, JSON-mode denial, interrupted turn resumed | All three for the first two; Anthropic for the REPL stories | Every OS |
| M3 | `ReasoningEndToEndTest` | Replay on Responses, replay on Anthropic, dropped after a provider switch | Responses, Anthropic | Every OS |
| M4 | `CommandsEndToEndTest` | `bash_stdout_roundtrip`, `bash_permission_prompt_approved`, `bash_permission_prompt_denied`, timeout leaves no processes, sandbox blocks an outside write | Anthropic | Sandbox story on macOS only; the rest everywhere |
| M5 | `ProjectContextEndToEndTest` | Instruction file in the system prompt, `auto_compact_triggered`, `/compact` | Anthropic | Every OS |
| M6 | `McpEndToEndTest` | Tool call to a local MCP server, `butler mcp` listing | Anthropic | Every OS |

Once a gate is tagged, its test class stays in the build for good, so a later lane cannot quietly break an earlier gate. The REPL, sandbox, compaction and MCP stories run on one dialect because their logic sits above the provider; the M1 stories already prove each dialect's tool wiring.

## Configuration

J changes one production default and sets test-only values through system properties, never through files in the repository. No real key is ever used: tests pass placeholder keys such as `test-key` to the fakes, as today.

| Setting | Where | Value | When |
| --- | --- | --- | --- |
| `butler.tools.enabled` | `application.yml` | `true` | J1, at gate M1 |
| `butler.providers.<id>.base-url` | System property in the test | The fake server's local URL | Every gate test |
| `butler.providers.<id>.api-key` | System property in the test | A placeholder, never a real key | Every gate test |
| `butler.persistence.file.user-directory` | System property in the test | A temp folder, so the real `~/.butler` is never touched | Every gate test |
| `butler.permissions.*`, `butler.mcp.servers.*` | System properties in the M2 and M6 tests | As lanes D and H define them | J2, J3d |

Users can still turn tools off after M1 with `BUTLER_TOOLS_ENABLED=false`, which J4 documents in the README.

## Working with other lanes

J starts a gate's test as soon as the gate's first lane task merges, writes it against what is on `main`, and keeps it disabled until every needed task is Merged. That way the check is ready the day the last lane lands.

**What each gate needs, and from whom**

| Gate | Needs merged | Added to the plan's list, and why |
| --- | --- | --- |
| M1 | A2, B1 to B4, C2, C3, I1 | B4: the second request must carry the tool result natively. C3: the M1 promise includes searching files |
| M2 | A5, A6, C4, C5, D3, D5, I4 | A5 and A6: the permission step and cancellation live in the loop. D5: the JSON-mode story |
| M3 | E2, E3, E4 | None |
| M4 | F1 to F5 | None |
| M5 | G1 to G4 | None |
| M6 | H2, H4, H5 | H2 also provides the local test MCP server J reuses |

**Hand-offs J asks for**

- Lane B: tool-call streams in `FakeProviderServer.kt`, so J0's helpers can copy their JSON.
- Lane I: a test hook that triggers the CLI's cancellation, so J2 can interrupt a turn in-process.
- Lane H: the local MCP server from H2, runnable from the api tests.

**When a gate check fails**

1. J finds the lane whose code breaks the story, from the failing assertion.
2. J adds a task to the Tasks tab for that lane, with the failing test's name and the assertion that failed, and leaves the gate untagged.
3. The owning lane fixes it in its own pull request; J never patches lane code.
4. J reruns the check on `main` and tags the gate once it is green.

## Done when

Lane J is done when `gate-m6` is tagged and the README says v1. Each gate has the same four ticks: needs merged, check green, tag, docs.

**Before M1**

- [ ] J0 merged: shared fakes and runner; `PromptEndToEndTest` unchanged and green

**M1 Reads your code**

- [ ] A2, B1 to B4, C2, C3, I1 merged
- [ ] `ToolsEndToEndTest` green on all three dialects; `butler.tools.enabled: true`
- [ ] `gate-m1` tagged
- [ ] README, `CHANGELOG.md`, project doc updated

**M2 Edits safely**

- [ ] A5, A6, C4, C5, D3, D5, I4 merged
- [ ] `EditsEndToEndTest` green
- [ ] `gate-m2` tagged
- [ ] Docs updated

**M3 Thinks across calls**

- [ ] E2, E3, E4 merged
- [ ] `ReasoningEndToEndTest` green
- [ ] `gate-m3` tagged
- [ ] Docs updated

**M4 Runs commands**

- [ ] F1 to F5 merged
- [ ] `CommandsEndToEndTest` green, including the macOS sandbox story on a Mac
- [ ] `gate-m4` tagged
- [ ] Docs updated

**M5 Knows your project**

- [ ] G1 to G4 merged
- [ ] `ProjectContextEndToEndTest` green
- [ ] `gate-m5` tagged
- [ ] Docs updated

**M6 MCP, v1 done**

- [ ] H2, H4, H5 merged
- [ ] `McpEndToEndTest` green
- [ ] `gate-m6` tagged
- [ ] README status says v1; project doc records v1 done

## Risks and decisions to confirm

Three decisions are worth settling before J0; the risks have fixes built into the tasks.

**Decisions**

- [ ] **How J2 interrupts a turn.** Proposed: lane I exposes a test hook that triggers the CLI's `TurnCancellation`, so the test stays in-process like the others. The alternative runs the packaged jar as a child process and sends it SIGINT, which is closer to real use but slower and harder on Windows.
- [ ] **`CHANGELOG.md`**** for release notes.** Proposed: a new file at the repository root, one entry per gate. The alternative keeps notes only in the README and the project doc.
- [ ] **Who runs lane J.** It suits the person who reviews most pull requests, because a gate needs someone who can judge every lane's work. Ties to the plan's open question on who runs the lanes.

**Risks**

| Risk | Effect | Built-in fix |
| --- | --- | --- |
| J's fakes and lane B's fakes disagree on a stream's JSON | A gate passes on a fake no real provider would send | J0 step 4 aligns them with lane B's adaptor fakes; both copy the provider's documented shapes |
| Turning tools on at M1 changes request bodies | Existing tests that compare whole requests fail | J1 fixes those assertions in the same pull request that flips the switch |
| The macOS sandbox story cannot run on Linux CI | M4 could be tagged without that story running | J3b requires one green run on a Mac before tagging `gate-m4` |
| Gate tests make the build slower | Every pull request waits longer | Fakes answer at once; if the suite passes 30 seconds, J moves gate tests to a tagged Gradle task |
| A lane finishes its tasks but its gate test reveals a gap | The gate slips | J writes each gate test early, disabled, so gaps show before the last task lands |
