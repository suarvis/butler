# Butler v1 tasks

> Mirror of the Tasks tab in the v1 plan doc, with the lane review's changes applied (see [STATUS.md](STATUS.md)). Update the Status column in the same commit as the task. Statuses: Merged (commit, or the wave it landed in), Ready (every Need merged), Waiting, In progress (add your name).

| ID | Task | Lane | Needs | Size | Status | Done when |
| --- | --- | --- | --- | --- | --- | --- |
| M0.1 | Tool model: `ToolDefinition`, `PermissionTier`, `ToolInvocation`, `ToolOutcome`, `WorkspacePath`, `BuiltInTools` with JSON schemas | M0 | — | M | Merged (1cf82dc) | Types compile; `WorkspacePath` rejects escapes; every schema is valid JSON |
| M0.2 | The six ports, `InvalidToolInputException`, secondary stand-ins, in-memory fakes in core test fixtures | M0 | M0.1 | M | Merged (646fef8) | App starts; each port has a fake |
| M0.3 | Turn contract: `TurnCallbacks`, `ToolApprover`, `TurnCancellation`, `ToolStarted` and `ToolFinished`, `ModelRequest.tools`, permission types, `execute(command, callbacks)`; CLI updated | M0 | M0.1 | M | Merged (57398c4) | All 217 existing tests still pass |
| M0.4 | `ReasoningReplay` on thinking blocks, stored as optional fields in files and PostgreSQL | M0 | — | S | Merged (bfe3e81) | Old session files load; new fields round-trip in both stores |
| M0.5 | `butler.tools.enabled` (off); contracts reviewed by every lane owner; tag `contracts-v1` | M0 | M0.1–M0.4 | S | Merged (bb72e54) | Tag on `main` |
| A1 | RunToolCallUseCase (was ToolRunner): decode each call, route it to its port; an unknown tool returns an error result | A | M0 | S | Merged (6893340) | Unit test per invocation type |
| A2 | Tool loop in `RunTurnUseCase`: offer tools, run calls one by one, append results, call again until there are no calls | A | A1 | M | Merged (wave 2) | A scripted two-call turn ends with an answer |
| A3 | Iteration limit (default 32, `butler.tools.max-iterations`; Clawcode's CLI has none, its sub-agents stop at 32); invalid input and failures become error results | A | A2 | S | Ready | A looping model stops at the limit with a valid transcript |
| A4 | Budget and rate-limit check before every model call in the loop; usage recorded per call | A | A2 | S | Ready | A budget breach mid-loop reaches the approver |
| A5 | Permission step: `PermissionPolicy`, then `ToolApprover` on Ask; a denial becomes an error result | A | A2 | S | Merged (wave 3) | A denied call reaches the model as an error result |
| A6 | Cancellation between steps; unfinished calls get "interrupted" results | A | A2 | S | Merged (wave 3) | A cancelled turn saves a valid transcript |
| B1 | Anthropic: send `tools` | B | M0 | S | Merged (01741bc) | The fake server receives the schemas |
| B2 | Chat Completions: send `tools[].function` | B | M0 | S | Merged (01741bc) | Same, on the Chat Completions fake |
| B3 | Responses: send flat `tools[]` entries | B | M0 | S | Merged (01741bc) | Same, on the Responses fake |
| B4 | Offered tool history sent natively on all three dialects | B | B1–B3 | S | Merged (wave 2) | A conversation with tool history is accepted by all three fakes |
| C1 | `ToolInputGatewayImpl`: decode and validate the six built-in tools | C | M0 | M | Merged (a6f25dc) | Bad input names the wrong field |
| C2 | `read_file`: line ranges, Clawcode's 10 MB read cap (256 KB proposed only as a cap on returned text), binary detection, `..` and symlink escapes refused | C | C1 | M | Merged (wave 3) | A temp-folder test per rule |
| C3 | `glob_search` and `grep_search`: Clawcode's limits: 100 glob results, default 250 grep results, skip `.git` and build output | C | C1 | M | Merged (wave 3) | Temp-folder tests |
| C4 | `write_file`: atomic write, parent folders only inside the workspace | C | C1 | S | Ready | An interrupted write leaves the old file intact |
| C5 | `edit_file`: exact single match or `replaceAll`, diff in the outcome's preview | C | C1 | M | Ready | No match or two matches give an error and leave the file untouched |
| I1 | Show tool calls while streaming: name and target, then a one-line result | I | M0.3 | S | Merged (wave 2) | Renderer tests |
| I2 | `butler tools`: name, tier, description | I | M0.1, M0.2 | S | Merged (wave 2) | CLI test |
| I3 | JLine 4.4.x line reader behind `CliConsole`: history and line editing | I | — | M | Ready | Existing piped REPL tests still pass |
| I4 | Ctrl-C cancels the running turn through `TurnCancellation`; a second Ctrl-C exits | I | M0.3, I3 | M | Waiting | Interrupt test against a slow fake |
| D1 | `PermissionPolicy`: modes × tiers × allow, deny and ask patterns | D | M0 | M | Merged (82ef839) | Table-driven tests for every mode and tier |
| D2 | Rules from `butler.permissions.*` through `PermissionRulesGatewayImpl` | D | D1 | S | Merged (wave 2) | Configuration binding test |
| D3 | `CliToolApprover`: Clawcode's prompt wording, diff preview, "allow for this session" | D | M0.3 | M | Merged (wave 2) | Fake-console tests |
| D4 | Flags `--permission-mode`, `--dangerously-skip-permissions`, `--allowedTools`; `/permissions` | D | D1 | S | Merged (wave 3) | CLI parsing tests |
| D5 | Scripts: every Ask becomes a denial, listed in the JSON result | D | D3 | S | Ready | End-to-end test in JSON mode |
| E1 | Check the current reasoning request and response fields for Anthropic, OpenAI and xAI | E | — | S | Ready | Findings already in lane E's doc; copy them to docs/plan/CONTRACTS.md |
| E2 | Normalizer keeps same-provider thinking with its replay data, drops it otherwise | E | M0.4 | S | Ready | Core tests |
| E3 | Responses: request encrypted reasoning, replay it before the function calls it led to | E | E1, E2 | M | Waiting | The fake server receives the replayed item |
| E4 | Anthropic: request thinking, replay signed blocks | E | E1, E2 | M | Waiting | The fake server receives the signature intact |
| E5 | `--reasoning-effort` and per-model support in the catalog | E | E1 | M | Waiting | Mapped per dialect; omitted for models without support |
| F1 | `BashRiskClassifier`, ported from Clawcode's command validation | F | M0 | M | Merged (bbc8467) | Table-driven tests: read-only, write, destructive |
| F2 | `SandboxPolicy` and the macOS `sandbox-exec` profile, checked on your macOS | F | M0 | M | Ready | A write outside the workspace is blocked (macOS-only test) |
| F3 | Linux bubblewrap or `unshare`; approval for every command as the fallback | F | F2 | M | Waiting | Linux test, skipped elsewhere |
| F4 | `CommandGatewayImpl`: timeout, head-and-tail output cap, whole process tree killed, provider keys removed from the environment | F | M0 | M | Ready | No orphan processes; environment test |
| F5 | `butler sandbox`; the approval shows the command and its risk | F | F1, F4 | S | Waiting | CLI test |
| G1 | `ProjectContextGatewayImpl`: `CLAUDE.md`, `CLAW.md`, `AGENTS.md` up to the repository root; git branch and status | G | M0 | M | Ready | Temp-repository tests |
| G2 | `SystemPromptComposer` adds the instructions and the tool list | G | G1 | S | Waiting | Prompt snapshot test |
| G3 | `CompactionPolicy` (summary plus the last 4 messages, above about 100k input tokens) and `CompactSessionUseCase`; the summary is built locally, without a model call, as in Clawcode | G | M0 | M | Ready | A compacted session stays valid and resumes |
| G4 | `/compact`, `butler init`, `doctor`, `system-prompt` | G | G2, G3 | M | Waiting | CLI tests |
| H1 | Choose the MCP Kotlin SDK version; `butler.mcp.servers` configuration | H | — | S | Ready | Choice noted on the Contracts tab; configuration binds |
| H2 | `McpGatewayImpl` over stdio | H | H1, M0 | M | Waiting | Test against a small local MCP server |
| H3 | HTTP transport | H | H2 | M | Waiting | Test against an HTTP fake |
| H4 | MCP tools in the catalog as `mcp__<server>__<tool>`; tier configurable, full access by default | H | H2 | S | Waiting | Catalog test |
| H5 | `butler mcp` and `/mcp` | H | H4 | S | Waiting | CLI test |
| J0 | Shared fake providers that can script tool calls on all three dialects, for gate checks | J | M0 | M | Merged (wave 3) | A scripted two-call turn runs on each fake |
| J1 | M1 check: a scripted `read_file` turn on all three provider fakes; switch `butler.tools.enabled` on | J | A2, B1–B4, C2, C3, I1, J0 | M | Ready | Green on `main`; tag `gate-m1` |
| J2 | M2 check: an approved edit, a denied edit, an interrupted turn | J | A5, A6, C4, C5, D3, D5, I4 | M | Waiting | Tag `gate-m2` |
| J3a | Gate M3 end-to-end check | J | Lane E tasks for M3 | M | Waiting | Tag gate-m3 |
| J3b | Gate M4 end-to-end check | J | Lane F tasks for M4 | M | Waiting | Tag gate-m4 |
| J3c | Gate M5 end-to-end check | J | Lane G tasks for M5 | M | Waiting | Tag gate-m5 |
| J3d | Gate M6 end-to-end check | J | Lane H tasks for M6 | M | Waiting | Tag gate-m6 |
| J4 | README, project doc and release notes at each gate | J | Each gate | S | Waiting | Docs match what ships |

Size: S is under a day, M one to three days of focused work.
