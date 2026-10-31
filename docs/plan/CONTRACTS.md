# Shared contracts (contracts-v1, plus batch 1)

These are the types and ports every lane codes against. The source files are the truth; this page tells you where to look. Paths are under `core/src/main/kotlin/com/suarvis/butler/core/`.

| Area | File | What is in it |
| --- | --- | --- |
| Workspace paths | `domain/generic/WorkspacePath.kt` | Relative, normalised paths; `WorkspacePath.within(root, raw)` returns null for anything outside the workspace (lexical; adaptors check symlinks) |
| Tool model | `domain/tool/Tools.kt` | `PermissionTier`, `ToolDefinition`, sealed `ToolInvocation` (ReadFile, GlobSearch, GrepSearch with all 14 Clawcode fields, WriteFile, EditFile, Bash, External with an optional permission `subject`), `ToolOutcome` |
| Built-in tools | `domain/tool/BuiltInTools.kt` | Clawcode's six tools and schemas (commit `22fe360`), in Clawcode's order |
| Ports | `gateway/ToolGateways.kt` | `ToolInputGateway.decode(workspace, name, input)`, `WorkspaceGateway.run` and `preview`, `CommandGateway.run(workspace, command, sandbox, cancellation)`, `McpGateway`, `ProjectContextGateway` |
| Permission port | `gateway/PermissionGateways.kt` | `PermissionRulesGateway.rules()` |
| Permissions | `domain/permission/Permissions.kt` and its package | `PermissionMode` (Clawcode's five, with CLI names), `PermissionDecision`, `ToolPattern`, `PermissionRules`, `PermissionPolicy.decide(invocation, tool, mode, rules, bashRisk)` |
| Shell | `domain/shell/` | `SandboxPolicy` (`forWorkspace(root)`), `CommandIntent`, `BashRisk`, `BashRiskClassifier.classify(command, workspace, sandboxActive)` |
| Project context | `domain/context/ProjectContext.kt` | `ProjectContext`, `InstructionFile(path, scope, content)` with absolute paths, `GitContext`, `GitCommit` |
| Turn | `domain/turn/TurnCallbacks.kt`, `TurnCancellation.kt`, `Turn.kt` | `TurnCallbacks` (observer, budget approver, tool approver, cancellation), `ToolApprover`, `ToolApprovalRequest`, `ToolApprovalDecision`, `TurnSettings(toolsEnabled)`, events `ToolStarted` and `ToolFinished`, `TurnSummary.interrupted` |
| Model request | `domain/model/ModelExchange.kt` | `ModelRequest.tools` |
| Use cases | `usecase/turn/RunTurnUseCase.kt`, `RunToolCallUseCase.kt` | `execute(command, callbacks)`; `Command.permissionMode` (default `PROMPT`) and `allowedTools` |
| Reasoning | `domain/session/ContentBlock.kt` | `Thinking.replay: ReasoningReplay?`, stored by both session stores only when present |
| Errors | `domain/exception/ButlerException.kt` | `InvalidToolInputException(tool, reason)` |

## Stand-ins and fakes

Stand-ins live in `secondary-adaptor/.../gateway/impl/stub/`, each wired by its lane's own config class (`WorkspaceConfig`, `CommandConfig`, `McpConfig`, `PermissionConfig`, `ProjectContextConfig`). A lane replaces its stand-in in that class and deletes it. Lane C has already replaced the input decoder.

Core test fixtures for every port: `core/src/testFixtures/.../fixtures/ToolDoubles.kt` (`FakeToolInputGateway`, `RecordingWorkspaceGateway`, `RecordingCommandGateway`, `FakeMcpGateway`, `FixedProjectContextGateway`, `CancelAfter`) and `TurnDoubles.kt` (`RecordingToolApprover`, `FixedPermissionRulesGateway`).

## Changing a contract

1. Keep it additive: a new optional parameter with a default, or a new type. Removing or renaming needs every affected lane's agreement.
2. Put it in its own commit, before the code that needs it, with stand-ins and fakes updated so the build stays green.
3. Update this page and STATUS.md in the same commit.

Additions already planned by single lanes, to make when that lane needs them: `ModelProfile.supportsTools` (B); reasoning support, `ModelRequest.reasoning` and `Command.reasoningEffort` (E); `PermissionRules.defaultMode` (D); `CommandGateway.status` and `SandboxStatus` (F); `ProjectSetupGateway`, compaction settings and `TurnEvent.Compacted` (G); `McpGateway.servers()` (H); cancellation inside `ModelGateway.stream` (A and B, after M2).

## Reasoning fields (task E1)

Lane E's research on the reasoning request and response fields for Anthropic, OpenAI and xAI is in [lanes/e-reasoning-replay.md](lanes/e-reasoning-replay.md); copying its findings here is task E1.
