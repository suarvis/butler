# Butler v1 plan

The rest of v1 is split into ten lanes that run at the same time. One short step first fixed the shared contracts (gate M0, done). Every lane now codes against those contracts and the test fakes, never against another lane's code, and the work comes together at six gates (M1 to M6). v1 is done at M6.

> Exported from the Claude Doc <https://claude.ai/artifact/JzWtyT5m6Xp945LNA8Y44C> on 2026-10-24. Start with [STATUS.md](STATUS.md) for what is done and what is next.

## Files here

| File | Read it for |
| --- | --- |
| [STATUS.md](STATUS.md) | What is merged, what is next, decisions made and still open |
| [TASKS.md](TASKS.md) | Every task with its lane, Needs, size, status and done-when |
| [CONTRACTS.md](CONTRACTS.md) | The shared types and ports every lane codes against |
| [m0-shared-contracts.md](m0-shared-contracts.md) | How the contracts were designed and why |
| [lanes/](lanes/) | One detailed plan per lane: goal, design, step-by-step tasks, tests, hand-offs |
| [HANDOFF.md](HANDOFF.md) | How to hand a task to another agent or engineer |

## Lanes

| Lane | Builds | Owns | Lands at |
| --- | --- | --- | --- |
| [A Agent loop](lanes/a-agent-loop.md) | The model → tool → model loop, iteration limit, permission step, cancellation | core `usecase/turn` (`RunTurnUseCase`, `RunToolCallUseCase`) | M1 |
| [B Provider tools](lanes/b-provider-tools.md) | Tool definitions and native tool history in Anthropic, Responses and Chat Completions requests | secondary `client/*/mapper` | M1 |
| [C File tools](lanes/c-file-tools.md) | Input decoding; `read_file`, `glob_search`, `grep_search` (M1); `write_file`, `edit_file` (M2) | secondary `workspace/`, `LocalWorkspaceGatewayImpl`, `ToolInputGatewayImpl` | M1, M2 |
| [I Terminal](lanes/i-terminal.md) | Tool calls on screen, `butler tools` (M1); JLine REPL and Ctrl-C (M2) | api `cli/render`, `cli/repl`, `CliSupport` | M1, M2 |
| [D Permissions](lanes/d-permissions.md) | Permission modes, allow/deny/ask rules, the approval prompt, Clawcode's flags | core `domain/permission`; api `cli/prompt/CliToolApprover` | M2 |
| [E Reasoning replay](lanes/e-reasoning-replay.md) | Reasoning kept between tool calls; `--reasoning-effort` | core thinking rules; secondary reasoning helpers | M3 |
| [F Sandboxed bash](lanes/f-sandboxed-bash.md) | `bash` tool, risk classifier, macOS and Linux sandboxes, `butler sandbox` | core `domain/shell`; secondary `sandbox/`, `CommandGatewayImpl` | M4 |
| [G Project context](lanes/g-project-context.md) | Instruction files, git context, compaction, `/compact`, `init`, `doctor` | core `domain/context`; secondary `ProjectContextGatewayImpl` | M5 |
| [H MCP client](lanes/h-mcp-client.md) | MCP servers' tools, `butler mcp`, `/mcp` | secondary `mcp/`, `McpGatewayImpl` | M6 |
| [J Integration](lanes/j-integration.md) | End-to-end checks per gate, tags, README | `*EndToEndTest`, `README.md` | Every gate |

## Gates

A gate is passed when its lanes are merged, its end-to-end check is green on `main`, and lane J has tagged the commit (`gate-m1` ...). Lanes keep merging between gates; a gate is a checkpoint, not a start signal. M1 and M2 come first; M3 to M6 can land in any order.

| Gate | What works afterwards | Lanes | End-to-end check |
| --- | --- | --- | --- |
| M0 Contracts (done) | Nothing new for users; every lane can start | M0.1 to M0.5 | Existing tests still pass |
| M1 Reads your code | Butler reads and searches files on every provider | A, B, C (read tools), I (display) | A scripted `read_file` turn on the Anthropic, Responses and Chat Completions fakes |
| M2 Edits safely | Butler edits files after asking; Ctrl-C stops a turn | C (write tools), D, I (JLine, Ctrl-C) | An approved edit, a denied edit, an interrupted turn that resumes cleanly |
| M3 Thinks across calls | Models keep their reasoning between tool calls | E | Reasoning replayed on the fakes and dropped after a provider switch |
| M4 Runs commands | Shell commands in a sandbox, or with approval | F | A write outside the workspace is blocked on macOS; a timed-out command leaves no processes |
| M5 Knows your project | Instruction files in the prompt; long sessions compact themselves | G | `CLAUDE.md` reaches the system prompt; a long session compacts and resumes |
| M6 MCP, v1 done | Tools from MCP servers | H | A tool call to a local MCP server end to end |

When every task for a gate shows Merged in TASKS.md, lane J runs that gate's check. A failure goes back to the owning lane as a new task; J does not patch other lanes' code.

## Rules for working in parallel

- **Code against contracts only.** Use the ports and the fakes in core's test fixtures, never another lane's implementation.
- **Ownership.** Each lane owns the packages in the table above. Changing another lane's files needs that lane's review.
- **Changing a contract.** Additive where possible, in its own small commit, merged before the code that needs it.
- **Shared files.** `UseCaseConfig.kt` and `application.yml` are append-only. `RunTurnUseCase` belongs to lane A; other lanes reach the turn through ports.
- **Always green.** Every commit passes the full build. Unfinished features stay behind `butler.tools.enabled` or their stand-in.
- **Done means merged,** with tests, and its row in TASKS.md says Merged.

## What the plan builds on

| Area | Decision |
| --- | --- |
| Architecture | `core` has no framework dependencies; adaptors depend inward; three-layer mapping; ArchUnit and the core classpath guard run in every build |
| Process | One local process: CLI, agent loop and tools in one JVM, acting on the folder it runs in |
| Tools | Run on the user's machine through ports, so a daemon or web mode can swap the implementation later |
| Policy | Permission rules, path containment and bash risk are pure `core` code; adaptors only execute |
| Approvals | One mechanism for budgets and tool permissions: the turn asks, the CLI answers (terminal prompt, or flags for scripts) |
| Providers | Bring your own key; Anthropic Messages, OpenAI Responses (OpenAI, xAI) and Chat Completions (OpenRouter, Ollama); tools work on all three |
| Clawcode parity | Its tool names, permission modes and approval wording, so its users feel at home |
| Language | Kotlin only; no Python in v1 |
