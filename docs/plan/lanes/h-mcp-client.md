# Butler lane H: MCP client

> Exported from the Claude Doc <https://claude.ai/artifact/5sGvdXso1JpHxx4Dn4NLcS> on 2026-10-24. The Claude Doc is the original; if they differ, read [STATUS.md](../STATUS.md) first for what has changed since.


2026-10-21 · Chamantha De silva

## Goal

Lane H lets the model use tools from MCP servers the user configures, on every provider, through the same tool loop and permission step as the built-in tools. It lands at gate M6, which completes v1: J checks a tool call to a local MCP server end to end.

What users get at M6:

- MCP servers declared under `butler.mcp.servers`, started over stdio or reached over HTTP.
- Their tools offered to the model as `mcp__<server>__<tool>`, next to `read_file` and the rest, with a permission tier each.
- `butler mcp` to list servers, their state and their tools, and `/mcp` for the same inside the REPL.
- A server that fails to start reported once, without breaking the turn or the other servers.

Lane H needs no other lane to finish first: it starts from `contracts-v1` and works against M0's `McpGateway` port and its fake.

## Scope

Lane H owns the connection to MCP servers and the translation between MCP and Butler's tool model. Core never sees the MCP SDK.

**In lane H**

- H1: choose and pin the MCP Kotlin SDK; bind `butler.mcp.servers`.
- H2: `McpGatewayImpl` over stdio, replacing M0's `NoMcpGatewayImpl`: start, initialise, list tools, call tools, stop.
- H3: the Streamable HTTP transport for remote servers.
- H4: MCP tools in the catalog as `mcp__<server>__<tool>`, each with a permission tier.
- H5: `butler mcp` and `/mcp`.

**Left to other lanes**

- Offering MCP tools to the model and running them inside the loop: lane A, through `McpGateway.tools()` and `ToolRunner`.
- Asking the user before an MCP call: lane D's policy decides from the tier lane H assigns.
- Sending tool schemas to providers: lane B, unchanged for MCP tools.

**After v1**

- Resources and prompts (`resources/list`, `resources/read`, `prompts/*`). Clawcode exposes resources through two extra tools; Butler adds them later.
- SSE, WebSocket and OAuth-protected servers. Clawcode accepts `sse`, `ws` and `oauth` settings; v1 supports stdio and Streamable HTTP with static headers.
- Sampling, roots and elicitation, where the server calls back into the client.

## Starting point

Butler has the port and nothing behind it; Clawcode has a complete hand-written MCP client to learn from. Clawcode paths are relative to `rust/crates/` at commit `22fe360` (v0.1.7).

**In Butler today (after M0)**

| File | What it gives lane H |
| --- | --- |
| `core/.../gateway/ToolGateways.kt` | `McpGateway { tools(); call(tool, input) }` |
| `core/.../domain/tool/Tools.kt` | `ToolDefinition(name, description, inputSchema, tier)`, `ToolInvocation.External(tool, input)`, `ToolOutcome` |
| `core/.../domain/generic/Identifiers.kt` | `ToolName`: 1 to 128 of letters, digits, `_`, `.`, `:` and `-` |
| `secondary/.../gateway/impl/stub/NoMcpGatewayImpl.kt` | The stand-in H2 replaces |
| `secondary/.../config/McpConfig.kt` | Lane H's own config class |
| `core/src/testFixtures/.../ToolDoubles.kt` | `FakeMcpGateway(tools, outcomes)` for lanes A and I |
| `secondary-adaptor/build.gradle.kts` | kotlinx-serialization and Spring; no Ktor, no coroutines yet |
| `ArchitectureTest` | Only secondary may make HTTP calls; gateway implementations end in `GatewayImpl` |

**Clawcode files to port from**

| Clawcode file | What to take |
| --- | --- |
| `runtime/src/config.rs` | The `mcpServers` format: `type` (inferred as `http` when `url` is set, else `stdio`), `command`, `args`, `env`, `toolCallTimeoutMs`, `url`, `headers`, `required`; `${VAR}` and `~/` expansion in command, args and url |
| `runtime/src/mcp.rs` | `mcp__<server>__<tool>` names; any character outside letters, digits, `_` and `-` becomes `_` |
| `runtime/src/mcp_stdio.rs` | The server manager: initialise (10 s), list tools (30 s), call with a per-server timeout, reset a server after a broken pipe, best-effort discovery that reports failed servers |
| `runtime/src/mcp_client.rs` | The default tool-call timeout: 60,000 ms |
| `runtime/src/mcp_lifecycle_hardened.rs` | Lifecycle phases and error surfaces, for `butler mcp` and doctor output |
| `runtime/src/mcp_tool_bridge.rs` | Tests with scripted servers worth copying as Butler test cases |
| `rusty-claude-cli/src/main.rs` | `RuntimeMcpState`: discovery at start-up; tool definitions with a default schema and description; tier from MCP annotations; the result returned as pretty-printed JSON |

Clawcode writes its own JSON-RPC client and sends protocol version `2025-03-26`. Butler can use the official Kotlin SDK instead (H1).

## Where the code goes

The SDK and every MCP type stay in secondary's approved `mcp/` folder; core gets only naming and tier rules written in plain Kotlin. Paths follow the M0 doc's convention.

| File | Module | New or changed | Task | Owner |
| --- | --- | --- | --- | --- |
| `domain/tool/McpToolNames.kt` | core | New: `mcp__<server>__<tool>`, character clean-up, length cap | H4 | H |
| `domain/tool/McpToolTiers.kt` | core | New: `McpToolHints` and the tier rule | H4 | H |
| `domain/tool/McpServers.kt` | core | New: `McpServerStatus`, `McpServerState` | H5 | H |
| `gateway/ToolGateways.kt` | core | Changed: `McpGateway.servers()` with a default body (contract change) | H5 | Contract |
| `usecase/mcp/ListMcpServersUseCase.kt` | core | New | H5 | H |
| `gradle/libs.versions.toml`, `secondary-adaptor/build.gradle.kts` | build | Changed: the pinned SDK | H1 | Shared, append-only |
| `config/McpProperties.kt` | secondary | New: `butler.mcp.servers` | H1 | H |
| `mcp/support/EnvReferences.kt` | secondary | New: `${VAR}` and `~/` expansion | H1 | H |
| `mcp/McpServerManager.kt` | secondary | New: one connection per server, start, discovery, reset | H2 | H |
| `mcp/McpConnection.kt` | secondary | New: an SDK client over one transport, blocking calls with timeouts | H2 | H |
| `mcp/transport/StdioTransports.kt`, `HttpTransports.kt` | secondary | New | H2, H3 | H |
| `mcp/mapper/McpToolMapper.kt`, `McpResultMapper.kt` | secondary | New: MCP tool to `ToolDefinition`, MCP result to `ToolOutcome` | H2, H4 | H |
| `gateway/impl/McpGatewayImpl.kt` | secondary | New; deletes `stub/NoMcpGatewayImpl.kt` | H2 | H |
| `config/McpConfig.kt` | secondary | Changed: real beans, shutdown hook | H2 | H |
| `cli/command/McpCommand.kt` | api | New: `butler mcp` | H5 | H |
| `cli/repl/SlashCommand.kt`, `ReplRunner.kt` | api | Changed: `/mcp` | H5 | I reviews |
| `cli/render/McpTextRenderer.kt`, `cli/mapper/McpCliMapper.kt`, `cli/dto/McpOutputs.kt` | api | New: text and JSON output | H5 | H |
| `config/UseCaseConfig.kt`, `application.yml` | api | Appended: H's beans and an empty `butler.mcp.servers` | H1, H5 | Shared, append-only |
| `secondary/src/test/.../mcp/TestMcpServer.kt` | secondary test | New: a small MCP server in Kotlin for stdio and HTTP tests | H2, H3 | H |

## Design

Lane H puts the official MCP Kotlin SDK behind M0's `McpGateway` port, keeps Clawcode's names, timeouts and config keys, and differs from Clawcode in three places: servers start on first use, unknown tools default to full access, and results are sent as text rather than raw JSON.

*(Diagram in the Claude Doc: MCP architecture · one core port, one manager, two transports)*

Lane A's tool loop only ever talks to the `McpGateway` port; everything to its right is lane H's, and only `McpServerManager` knows that servers exist.

### SDK choice (H1)

- **Use ****`io.modelcontextprotocol:kotlin-sdk-client`****.** Its latest release is 0.15.0, published 28 July 2026 ([klibs.io](https://klibs.io/package/io.modelcontextprotocol/kotlin-sdk-client), as of 21 Oct 2026). Its client offers stdio, SSE, Streamable HTTP and WebSocket transports and is built on Ktor ([SDK docs](https://kotlin.sdk.modelcontextprotocol.io)). H1 re-checks for a newer release and pins one exact version.
- **Only secondary's ****`mcp/`**** folder imports it.** Core keeps its own types; `verifyCoreIsFrameworkFree` and ArchUnit prove it on every build.
- **Blocking at the edge.** The SDK's client API is coroutine-based; `McpConnection` wraps each call in `runBlocking` with a timeout, on the caller's virtual thread, so the rest of Butler stays blocking as it is today.
- **Fallback.** If the SDK's dependencies clash with Spring Boot 4 or Kotlin 2.3.20, write a small JSON-RPC client like Clawcode's `mcp_stdio.rs` on kotlinx-serialization and Butler's own HTTP client. H1 records the choice on the Contracts tab.

### Server lifecycle (H2)

- **Start on first use.** Clawcode starts every server when the CLI starts. Butler starts one process per command, and `butler status` should not launch servers, so `McpServerManager` connects all servers in parallel the first time `tools()` is called, then keeps them until the JVM exits.
- **Timeouts** (`mcp_stdio.rs`, `mcp_client.rs`): initialise 10 s, list tools 30 s, tool call 60 s by default, overridable per server.
- **Failures stay local.** A server that fails to start or list its tools is marked failed with its error, and the turn continues without its tools. A server marked `required: true` stops the turn instead (confirm Clawcode's exact meaning of `required` in H2).
- **Broken servers.** After a broken pipe or an exited process, the server is reset. Listing is retried once, as in Clawcode; a tool call is never retried automatically, because it may already have had side effects.
- **Shutdown.** `McpConfig` closes every connection when the Spring context closes, so no server process outlives Butler.

### Tool names and tiers (H4)

- **Names** (`mcp.rs`): `mcp__<server>__<tool>`, with every character outside letters, digits, `_` and `-` replaced by `_`. Providers cap tool names (OpenAI at 64 characters), so a longer name keeps its first part and gets a short hash suffix. Two tools that clean up to the same name are an error naming both.
- **Routing back.** The manager keeps a map from the qualified name to the server and the tool's original name, as Clawcode's `tool_index` does.
- **Schema and description.** Taken from the server; a missing schema becomes `{"type":"object","additionalProperties":true}` and a missing description "Invoke MCP tool `<name>`.", as in Clawcode.
- **Tiers.** Clawcode reads the server's annotations: `readOnlyHint` without `destructiveHint` or `openWorldHint` is read-only; destructive or open-world is full access; anything else is workspace-write. Annotations are written by the server and can be wrong, so Butler defaults every MCP tool to `DANGER_FULL_ACCESS`. A server marked `trust-annotations: true` uses Clawcode's rule, and `tools.<tool>.tier` overrides a single tool.

```kotlin
// domain/tool/McpToolNames.kt and McpToolTiers.kt (H4)
object McpToolNames {
    fun qualify(server: String, tool: String): ToolName      // mcp__<server>__<tool>, cleaned and capped
}

data class McpToolHints(val readOnly: Boolean, val destructive: Boolean, val openWorld: Boolean)

object McpToolTiers {
    fun tierFor(hints: McpToolHints?, trustAnnotations: Boolean, override: PermissionTier?): PermissionTier
}
```

### Results (H2)

Clawcode returns the whole MCP result as pretty-printed JSON. Butler sends what the model needs as text: text content joined in order, other content as a one-line note (`[image: image/png, 12 KB]`, `[resource: <uri>]`), and `structuredContent` as JSON only when there is no text. `isError: true`, a JSON-RPC error and a timeout all become `ToolOutcome(isError = true)` with the server's message, never an exception.

### Status for `butler mcp` (H5)

The M0 port has no way to report server state, so H5 adds one method with a default body, which keeps every existing implementation and fake compiling:

```kotlin
// gateway/ToolGateways.kt (H5 contract change, additive)
interface McpGateway {
    fun tools(): List<ToolDefinition>
    fun call(tool: ToolName, input: JsonText): ToolOutcome
    fun servers(): List<McpServerStatus> = emptyList()
}

// domain/tool/McpServers.kt
enum class McpServerState { NOT_STARTED, RUNNING, FAILED }
data class McpServerStatus(val name: String, val transport: String, val state: McpServerState, val tools: List<ToolName>, val error: String?)
```

## Tasks

Five tasks from the Tasks tab, branched as `lane/h/<task>`. One refinement: H5 adds `McpGateway.servers()` through a small contract pull request. H1 comes first; H3 and H4 can run in parallel after H2.

### H1 SDK and configuration (S, needs nothing)

1. Check the SDK's latest release and pin one exact version of `kotlin-sdk-client` in `libs.versions.toml`; add it to secondary only. Add `kotlin-sdk-server` as a test dependency for the test server.
2. Read `./gradlew :secondary-adaptor:dependencies` and confirm the SDK resolves cleanly with Spring Boot 4.0.6 and Kotlin 2.3.20, and which Ktor engine HTTP needs.
3. Add `McpProperties` for `butler.mcp.servers` (see Configuration), with the type inferred as in Clawcode: `http` when `url` is set, else `stdio`.
4. Validate server names (letters, digits, `_`, `-`) and refuse two names that clean up to the same prefix.
5. Record the SDK choice and version on the Contracts tab.
6. Tests: binding for a stdio server, an HTTP server, environment placeholders, per-tool tiers; invalid names refused.

Done when: the build resolves the SDK, nothing in core imports it, and the binding tests pass.

### H2 `McpGatewayImpl` over stdio (M, needs H1 and M0)

1. Write `TestMcpServer` in secondary's tests with the SDK's server module: tools `echo`, `fail` (answers `isError: true`), `slow` (sleeps past a timeout) and `crash` (exits). It runs as a child process from the test classpath, so tests need no Node or Python.
2. Add `McpConnection`: start the process from `command` and `args` in the workspace, with the configured `env` added; drain stderr so the process never blocks, keeping the last lines for error messages; connect the SDK client over stdio; wrap each call in `runBlocking` with its timeout.
3. Add `McpServerManager`: connect every server in parallel on the first `tools()`, with the 10 s and 30 s limits; keep each server's state and error; index qualified names; reset after a broken pipe; never retry a tool call.
4. Add `McpToolMapper` (default schema and description; tier `DANGER_FULL_ACCESS` until H4) and `McpResultMapper` (Results in Design).
5. Add `McpGatewayImpl`: `tools()` from the manager; `call()` routes by qualified name, and an unknown name is an error outcome.
6. Wire it in `McpConfig` with a close-on-shutdown method; delete `stub/NoMcpGatewayImpl.kt`.
7. Tests: tools are listed; `echo` returns its text; `fail` is an error outcome; `slow` times out within a 1 s test timeout; after `crash`, the call fails and the next call restarts the server; a server whose command does not exist is marked failed while the others work; no child process is left after the Spring context closes.

Done when: these tests pass on macOS and Linux.

### H3 HTTP transport (M, needs H2)

1. Add the Streamable HTTP transport with the configured `url` and `headers`.
2. Run `TestMcpServer` over Streamable HTTP in-process on a random port for tests.
3. Never log header values; error messages name the header, not its value.
4. Tests: list and call over HTTP; an HTTP 401 marks the server failed with a clear message; a timeout is an error outcome; ArchUnit still passes.

Done when: the HTTP tests pass.

### H4 Names and tiers in the catalog (S, needs H2)

1. Add `McpToolNames.qualify` with clean-up, the length cap with a hash suffix, and collision detection.
2. Add `McpToolTiers.tierFor` with `trust-annotations` and per-tool overrides; map the SDK's annotations into `McpToolHints` in `McpToolMapper`.
3. Tests: name cases ported from Clawcode's `mcp.rs` tests; every combination of the three hints with trust on and off; overrides; collisions; `tools()` returns qualified names with the expected tiers.

Done when: the core table tests and the catalog test pass.

### H5 `butler mcp` and `/mcp` (S, needs H4)

1. Open the contract pull request for `McpGateway.servers()` with its default body; update `FakeMcpGateway`.
2. Add `ListMcpServersUseCase`.
3. Add `butler mcp` (every server with state, transport, tools or error) and `butler mcp <server>` (one server's tools with tiers). Check Clawcode's `mcp` actions in `main.rs` and keep the same action names where they exist.
4. Add `/mcp` to the REPL; lane I reviews the change in `cli/repl`.
5. Tests: a configured test server shows as running with its tools; a broken one shows as failed with its error; JSON output for both.

Done when: the CLI tests pass and `butler help` lists `mcp`.

## Tests

Lane H tests against a real MCP server written in Kotlin, never against mocks of the SDK, so a protocol change in a new SDK release shows up as a failing test. Every test runs in `./gradlew build`.

| Test class | Module | What it proves | Task |
| --- | --- | --- | --- |
| `McpPropertiesTest` | secondary | Binding, type inference, placeholders, name validation | H1 |
| `McpGatewayImplStdioTest` | secondary | List, call, error result, timeout, crash and restart, failed server, no leftover process | H2 |
| `McpResultMapperTest` | secondary | Text joined, notes for images and resources, structured content, error results | H2 |
| `McpGatewayImplHttpTest` | secondary | List and call over Streamable HTTP; 401; timeout; no header values in errors | H3 |
| `McpToolNamesTest` | core | Clawcode's name clean-up, the length cap, collisions | H4 |
| `McpToolTiersTest` | core | Every hint combination with trust on and off; overrides | H4 |
| `McpCatalogTest` | secondary | `tools()` returns qualified names, schemas and tiers | H4 |
| `McpCommandEndToEndTest` | api | `butler mcp`, `butler mcp <server>` and `/mcp` in text and JSON | H5 |
| ArchUnit and `verifyCoreIsFrameworkFree` | api, core | No SDK or Ktor class outside secondary | Every task |

The test server's `slow` tool and the 1 s test timeout keep the timeout tests under two seconds.

## Configuration

Servers are declared under `butler.mcp.servers`, with Clawcode's keys in Butler's kebab case. Secrets never appear in the file: tokens are environment references, which Spring resolves when Butler starts.

| Key, per server | Default | Clawcode key | Notes |
| --- | --- | --- | --- |
| `type` | `http` if `url` is set, else `stdio` | `type` | v1 accepts `stdio` and `http` |
| `command`, `args` | — | `command`, `args` | `~/` expanded, as in Clawcode |
| `env` | empty | `env` | Added to the inherited environment |
| `url`, `headers` | — | `url`, `headers` | Header values are never logged |
| `tool-call-timeout` | `60s` | `toolCallTimeoutMs` | Initialise (10 s) and list (30 s) are fixed |
| `required` | `false` | `required` | A failed required server stops the turn |
| `enabled` | `true` | — | Butler addition: keep a server declared but off |
| `trust-annotations` | `false` | — | Butler addition: use the server's hints for tiers |
| `tools.<tool>.tier` | — | — | Butler addition: `read-only`, `workspace-write` or `danger-full-access` |

```yaml
butler:
  mcp:
    servers:
      notes:                          # stdio, inferred from command
        command: /opt/mcp/notes-server
        args: ["--root", "~/notes"]
        env:
          NOTES_TOKEN: ${NOTES_TOKEN}   # from the environment, never written here
        tool-call-timeout: 60s
      tracker:                        # http, inferred from url
        url: https://mcp.example.com/mcp
        headers:
          Authorization: "Bearer ${TRACKER_TOKEN}"
        tools:
          search_issues:
            tier: read-only
```

The server names and paths above are placeholders. Spring stops at start-up when a referenced variable is missing; writing `${NOTES_TOKEN:}` makes it empty instead, and the server then fails on its own with a clear message. H1 decides which of the two Butler documents (see Risks and decisions).

## Working with other lanes

Lane H is reached only through `McpGateway`; lanes A, C and D each need one small rule for MCP names. Until lane A's loop exists, H tests through `McpGatewayImpl` directly, and A tests MCP paths with `FakeMcpGateway`.

**What lane A must call.**

```kotlin
// When building the request: offer MCP tools next to the built-ins.
val offeredTools = if (settings.toolsEnabled) BuiltInTools.ALL + mcp.tools() else emptyList()

// In ToolRunner: an External invocation goes to the MCP port, by its qualified name.
is ToolInvocation.External -> mcp.call(invocation.tool, invocation.input)
```

`mcp.tools()` starts the servers on its first call, so the first turn with tools on may take a few seconds longer.

| Lane | What passes between H and that lane |
| --- | --- |
| A Agent loop | The two calls above; MCP tools take the same permission step as built-ins |
| C File tools | `ToolInputGatewayImpl` must decode any `mcp__` name into `ToolInvocation.External(name, input)` without validating it (a C1 rule) |
| D Permissions | Decides from the tier H assigns; `--allowedTools` and rule patterns must accept `mcp__<server>__<tool>` names |
| B Provider tools | Sends MCP schemas as received; if a dialect rejects a schema, B reports it as a tool-definition error and H decides whether to clean it |
| I Terminal | Shows MCP calls like any tool call; reviews `/mcp` in `cli/repl` |
| G Project context | `butler doctor` shows each server's state through `servers()`; H adds that check |
| J Integration | Runs the M6 check below |

**What J checks at M6, which closes v1.** Butler is configured with `TestMcpServer` over stdio. The scripted fake provider asks for `mcp__test__echo`; the call is approved (or `echo` is overridden to `read-only`); the server's answer reaches the model as a tool result; the session saves and resumes. J then tags `gate-m6`.

## Done when

Lane H is done, and v1 with it, when J tags `gate-m6` on a green `main`. Tick these as they land:

- [ ] H1 merged: SDK version pinned and recorded on the Contracts tab; `butler.mcp.servers` binds
- [ ] H2 merged: stdio servers listed and called; failures stay local; no leftover processes; stand-in deleted
- [ ] H3 merged: Streamable HTTP servers with headers from the environment
- [ ] H4 merged: `mcp__<server>__<tool>` names, tiers, collisions caught
- [ ] H5 contract pull request merged: `McpGateway.servers()`
- [ ] H5 merged: `butler mcp`, `butler mcp <server>` and `/mcp`
- [ ] Lanes A, C and D have their MCP rules merged (Working with other lanes)
- [ ] The decisions below answered
- [ ] J's M6 check green and `gate-m6` tagged

## Risks and decisions to confirm

Five decisions shape lane H; the first two are needed before H1. The risks have a fix built into the tasks.

**Decisions**

- [ ] **SDK or own client.** Proposed: the official Kotlin SDK, pinned, confined to `mcp/`. The fallback is a small client like Clawcode's, if H1 finds a dependency clash.
- [ ] **Where users declare servers.** Proposed: besides `application.yml` overrides, import `~/.butler/config.yml` and `<workspace>/.butler/config.yml` with `spring.config.import: optional:file:...`, since maps of servers are awkward as environment variables. Optionally also read the `mcpServers` block of Clawcode's `.claw.json`.
- [ ] **Default tier for MCP tools.** Proposed: `danger-full-access` unless a server is marked `trust-annotations: true` or a tool is overridden. Clawcode trusts every server's annotations.
- [ ] **Result format.** Proposed: text for the model, with notes for other content. Clawcode sends the whole result as pretty-printed JSON.
- [ ] **Missing environment variables.** Proposed: document `${VAR:}`, so a missing token fails only its server. Spring's default is to stop Butler at start-up.

Two Butler choices differ from Clawcode without needing a decision: servers start on first use rather than at start-up, and tool calls are never retried automatically.

**Contract change for review**

| Change | Kind | Reviewed by |
| --- | --- | --- |
| `McpGateway.servers()` with a default body; `McpServerStatus`, `McpServerState` | Additive | A, G, I |

**Risks**

| Risk | Effect | Built-in fix |
| --- | --- | --- |
| The SDK is pre-1.0 and released monthly (0.8.3 in January 2026 to 0.15.0 in July, per klibs.io) | An upgrade breaks Butler | One pinned version; tests run a real MCP server, so breaks show up as failing tests |
| Ktor or coroutines clash with Spring Boot 4 | Build or start-up fails | H1 checks the dependency tree first; the own-client fallback |
| A server process outlives Butler | Leaked processes | Close on context shutdown; a test asserts none are left |
| A careless or hostile server | Misleading annotations, huge outputs, instructions hidden in results | Full access by default, so lane D asks; outputs share lane C's size cap; results are tool results, never system text |
| Tokens in headers or environment leak | Secrets in logs or error messages | Values never logged; error messages name keys only; a test checks it |
| Two tools clean up to the same name, or a name is too long | Provider rejects the request | H4's collision check and length cap |
