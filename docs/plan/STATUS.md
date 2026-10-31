# Butler v1: where things stand

As of 2026-10-31, `main` has waves 2 and 3 on top of `bbc8467`. The shared contracts are tagged `contracts-v1` (`bb72e54`). Tools stay switched off (`butler.tools.enabled: false`) until gate M1.

## Done

| Task | Commit | What it added |
| --- | --- | --- |
| M0.1 to M0.5 | `1cf82dc` to `bb72e54` | Shared contracts: tool model, ports, stand-ins, test fakes, turn callbacks, reasoning replay storage, the tools switch |
| A1 | `6893340` | `RunToolCallUseCase`: one tool call, decoded, routed to its gateway, failures as error results |
| B1 to B3 | `01741bc` | Tool definitions in Anthropic, Chat Completions and Responses requests |
| C1 | `a6f25dc` | `ToolInputGatewayImpl`: decodes the six built-in tools with Clawcode's field names |
| D1 | `82ef839` | `PermissionPolicy` ported from Clawcode's `permissions.rs` |
| F1 | `bbc8467` | `BashRiskClassifier` ported from Clawcode's `bash_validation.rs` |
| B4 | wave 2 | Tool history sent natively on all three dialects (`is_error` only when true) |
| D2, D3 | wave 2 | Rules from `butler.permissions.*`; `CliToolApprover` with Clawcode's wording and "allow for this session" |
| C2, C3 | wave 3 | `LocalWorkspaceGatewayImpl`: `read_file`, `glob_search`, `grep_search` with Clawcode's limits |
| J0 | wave 3 | Scripted tool-call fakes for all three dialects (`ScriptedStreams`, `ButlerCli`) |
| A2, A5, A6 | waves 2 and 3 | The tool loop, its permission step and cancellation |
| I1, I2, D4 | waves 2 and 3 | Tool lines on screen, `butler tools`, `--permission-mode`, `--dangerously-skip-permissions`, `--allowedTools`, `/permissions` |

Last full build (2026-10-31): 705 tests (core 353, secondary 225 with 15 skipped without Docker, api 127), all green, no warnings.

## Next, in priority order

Gate M1 needs only J1 (it also moves PromptEndToEndTest onto the J0 helpers and switches tools on). Then: C4 and C5 (write and edit), D5, I3 and I4 (JLine, Ctrl-C), A3 and A4, then J2 for gate M2. Independent of those: E1 to E5, F2 to F5, G1 to G4, H1 to H5. [TASKS.md](TASKS.md) has the full list with Needs.

## Decisions made (do not reopen)

| Decision | Where it shows |
| --- | --- |
| Unknown models are rejected; sessions store the full model id | `ModelCatalogGatewayImpl` |
| Budgets: USD per provider per calendar month, optional token and per-model limits; going over asks | `BudgetPolicy`, `butler.budgets` |
| OpenAI and xAI use the Responses API | `application.yml` providers |
| `bash` takes only `command`, `timeout` (ms) and `description`; the sandbox is Butler's policy, not the model's | `BuiltInTools` |
| `grep_search` keeps all 14 of Clawcode's fields | `BuiltInTools`, `ToolInvocation.GrepSearch` |
| Default permission mode `prompt`, and `prompt` lets read-only tools through (Clawcode asks even for reads) | `PermissionModes.kt` |
| Output redirected to `/dev/null` is neither a write nor a path outside the workspace | `BashRiskClassifier`, `CommandWords` |
| Tool input ignores fields the schema does not list, as Clawcode's serde structs do | `ToolInputWorkspaceMapper` |
| MCP calls: the decoder picks the permission subject (`ToolInvocation.External.subject`); core never parses JSON | `ToolInputGatewayImpl`, `PermissionSubject` |
| Responses tools are sent with `strict: false`; no `tool_choice` in any dialect | client mappers |
| Lane A's tool runner is `RunToolCallUseCase` in `usecase/turn` (ArchUnit needs the suffix) | core |
| One config class per port in `secondary-adaptor/.../config/` | `WorkspaceConfig`, `CommandConfig`, ... |

## Decisions taken by default (2026-10-24, the owner can overturn any)

The owner asked to continue without stopping, so the lane docs' proposals were adopted:

| Area | Decision |
| --- | --- |
| Lane I | JLine 4.4.x (jdk11 variant) instead of JLine 3; no emoji on tool lines (Clawcode's words, ✓ and ✗ kept); a second Ctrl-C exits with 130; a cancelled one-shot prompt exits 130, JSON kind `interrupted`; Esc and Tab completion wait until after M2 (optional I5) |
| Lane H | Official MCP Kotlin SDK, pinned, used only in secondary `mcp/`; fall back to a small own client only if its dependencies clash with Spring Boot 4 or Kotlin 2.3 |
| Lane A | Iteration limit default 32 (`butler.tools.max-iterations`) |
| Lanes A and D | "Allow for this session" is remembered by the CLI approver for the run or REPL session, never stored in the session file; file writes and exact non-destructive bash commands can be granted, destructive bash asks every time |

## Departures from the plan docs

- Tool-call input that is not a JSON object is sent to OpenAI-style providers as `{}` (Clawcode sends `{"raw": text}`), matching what the Anthropic mapper already did.
- The approval prompt cuts a tool's input line at 500 characters.
- `--dangerously-skip-permissions` means danger-full-access, as Clawcode's source does (not `allow`); giving both mode flags is a usage error.
- File search: grep also skips Clawcode's six ignored folders; the start folder is never skipped; a file whose real path leaves the workspace is skipped rather than failing the search; a 2-second regex limit per file; walks go in name order; no extra output cap beyond Clawcode's result limits.
- Calls skipped by a cancel still emit ToolStarted then ToolFinished, as Clawcode does; a turn cancelled before its first reply reports stop reason OTHER.

The exported lane docs were written before batch 1. Where they differ from the code, the code and this file win:

- `ToolInputGateway.decode` takes the workspace; `WorkspaceGateway.preview` exists; `CommandGateway.run` takes a `TurnCancellation`; `InstructionFile` uses absolute paths; `BashRisk` and `CommandIntent` live in `core/domain/shell/BashRisk.kt`.
- Clawcode's real limits for lane C are a 10 MB read and write cap, 100 glob results and a default of 250 grep results.
- New task J0 (shared scripted fakes); J3 is split per gate (J3a to J3d).
