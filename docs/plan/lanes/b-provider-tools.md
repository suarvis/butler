# Butler lane B: Provider tools

> Exported from the Claude Doc <https://claude.ai/artifact/JGPt4887x2R1AKNPeKcH1H> on 2026-10-24. The Claude Doc is the original; if they differ, read [STATUS.md](../STATUS.md) first for what has changed since.


2026-10-21 · Chamantha De silva

## Goal

Lane B makes every provider dialect offer Butler's tools to the model, so the agent loop works the same on Anthropic, OpenAI Responses and Chat Completions. It is about three to four days for one person, and it lands at gate M1.

Most of the wire work already exists. Since slice 3a, all three mappers send tool calls and tool results in the transcript, and all three stream accumulators parse tool calls back out. What is missing is the other half: no request lists the tools the model may call. Lane B adds that list, decides how much control Butler keeps over tool choice, and proves on the fake servers that a request with tools and tool history is accepted.

What users get at M1, together with lanes A, C and I: they can ask Butler about their project on any configured provider, and the model reads and searches files instead of guessing.

## Scope

Lane B changes only how requests are written for each dialect; it runs no tools and decides nothing about which tools to offer.

**In lane B**

- B1 to B3: turn `ModelRequest.tools` into each dialect's `tools` array, and leave the field out when the list is empty.
- One shared schema clean-up for the two OpenAI dialects, ported from Clawcode, so MCP schemas with bare objects are accepted.
- `strict: false` on Responses tools, and no `tool_choice`, as proposed under Design.
- B4: prove that a request carrying both tools and native tool history is accepted on all three fakes, with ids that survive a provider switch.
- Gaps found on the way in the existing mappers and accumulators, with a test each.
- A per-model switch for models that cannot take tools, if the decision at the end is yes.

**Left to other lanes**

- Which tools are offered, and when: lane A builds `ModelRequest.tools` from `BuiltInTools`, MCP tools and `TurnSettings.toolsEnabled`.
- Which tool calls become native history: core's `TranscriptNormalizer`, already in place since 3a.
- MCP tool schemas: lane H lists them; lane B only makes them acceptable on the wire.
- Reasoning items next to tool calls: lane E, which adds its replay helpers to the same mappers.
- Prompt caching of the tool list: after v1.

## Starting point

Butler already speaks tool history and parses tool calls on all three dialects; the only missing piece on the wire is the list of tools. Paths are under `secondary-adaptor/.../secondary/client/` unless noted.

**In Butler today (after 3c, commit 8ec007b)**

| File | What it does with tools now | Gap for lane B |
| --- | --- | --- |
| `anthropic/mapper/AnthropicClientMapper.kt` | Writes `tool_use` blocks (input parsed to an object, `{}` if unparsable) and `tool_result` blocks with `is_error`; merges tool results into the user turn | No `tools`, no `tool_choice` |
| `anthropic/support/AnthropicStreamAccumulator.kt` | Builds tool calls from `content_block_start` plus `input_json_delta` fragments | None found |
| `openai/mapper/OpenAiClientMapper.kt` (Chat Completions) | Puts `tool_calls` on the assistant message with `content: null`; one `tool` message per result; errors prefixed `[error] ` | No `tools`, no schema clean-up |
| `openai/support/OpenAiStreamAccumulator.kt` | Joins `tool_calls` fragments by `index`; servers without `index` send whole calls; missing ids become `call_<index>` | None found; B4 adds a test for whole calls without `index` |
| `openai/mapper/OpenAiResponsesClientMapper.kt` | Writes `function_call` and `function_call_output` items; errors prefixed `[error] ` | No `tools`, no `strict` |
| `openai/support/OpenAiResponsesStreamAccumulator.kt` | Takes `function_call` items from `response.completed` or `response.output_item.done`, using `call_id` | None found |
| core `domain/model/TranscriptNormalizer.kt` | Keeps a call native only if its tool is offered and answered; rewrites ids to `[A-Za-z0-9_-]{1,40}` | None: B relies on it |
| test `client/FakeProviderServer.kt` | Records every request's path, headers and body | Tests parse the body to check `tools` |

The gateway tests already cover tool history on each dialect, for example `tool calls ride on the assistant message and each result is its own tool message`. They send no tools, which is exactly what lane B changes.

**In Clawcode (v0.1.7, commit 22fe360), read before starting**

| File | What to take from it |
| --- | --- |
| `rust/crates/api/src/types.rs` | `ToolDefinition` (name, optional description, `input_schema`) and `ToolChoice` (`auto`, `any`, `tool`) |
| `rust/crates/api/src/providers/openai_compat.rs` | `openai_tool_definition` (Chat Completions shape), `normalize_object_schema` (adds `properties` and `additionalProperties: false` to every object node), `openai_tool_choice`, `sanitize_tool_message_pairing`, `model_rejects_is_error_field` (Kimi) |
| `rust/crates/api/src/providers/anthropic.rs` | Serialises `MessageRequest` directly, so `tools` and `tool_choice` keep the Messages API names |
| `rust/crates/rusty-claude-cli/src/main.rs` | Sends `tool_choice: auto` whenever tools are enabled; `model_supports_local_tools` turns tools off for two models (`compound`, `compound-mini`) |

Clawcode has no Responses client: it calls OpenAI through Chat Completions. The Responses shapes below come from OpenAI's documentation, not from Clawcode.

## Where the code goes

Almost all of lane B is in secondary's three client mappers, which the plan gives to lane B. Lane E later adds its reasoning helpers to the same mappers through one call each, so B keeps each mapper's request building in one function that E can extend.

| File | Module | New or changed | Task | Owner |
| --- | --- | --- | --- | --- |
| `client/anthropic/mapper/AnthropicClientMapper.kt` | secondary | Changed: `tools` | B1 | B |
| `client/openai/mapper/OpenAiClientMapper.kt` | secondary | Changed: `tools[].function`, cleaned schemas | B2 | B |
| `client/openai/mapper/OpenAiResponsesClientMapper.kt` | secondary | Changed: flat `tools[]` with `strict: false` | B3 | B |
| `client/support/ToolSchemas.kt` | secondary | New: parse a `JsonText` schema; the object clean-up for OpenAI dialects | B2 | B |
| test `client/FakeProviderServer.kt` | secondary | Changed: `lastJson()` helper and scripted tool-call streams per dialect | B1 | B, append-only |
| test `gateway/impl/*ModelGatewayImplTest.kt` (three files) | secondary | Changed: tool definition and history tests | B1 to B4 | B |
| test `client/support/ToolSchemasTest.kt` | secondary | New | B2 | B |
| core `domain/model/ModelProfile.kt` | core | Changed: `supportsTools` (only if decision 3 is yes) | B5 | Contract change |
| `config/ModelProperties.kt`, `gateway/impl/ModelCatalogGatewayImpl.kt` | secondary | Changed: `tools` flag per profile (only if decision 3 is yes) | B5 | B |

No new port and no core use case: the request already carries `tools` since M0.3.

## Design

Each mapper turns every `ToolDefinition` into one entry of its dialect's `tools` array. Everything else on the wire stays as it is today.

*(Diagram in the Claude Doc: one Butler tool on three wire formats · definition, call and result per dialect)*

The top row is lane B's work; the two lower rows were built in slices 2 to 3c and are only re-tested in B4.

### Design choices

1. **No ****`tools`**** field when the list is empty.** While `butler.tools.enabled` is off, requests stay byte-identical to today's, so existing tests and providers see no change.
2. **Schemas go to Anthropic unchanged.** Both OpenAI dialects get them through `ToolSchemas.forOpenAi`, a port of Clawcode's `normalize_object_schema`: every object node gets `properties` (empty if missing) and `additionalProperties: false`, unless already set. Clawcode's comment says OpenAI's `/responses` validator rejects object schemas without them. Butler's six schemas already comply; MCP schemas from lane H may not.
3. **Responses tools say ****`strict: false`****.** OpenAI's [migration guide](https://developers.openai.com/api/docs/guides/migrate-to-responses) says omitting `strict` attempts strict mode, falls back to best effort when the schema is incompatible, and that `strict: false` keeps non-strict behaviour explicitly. Butler's schemas have optional fields such as `offset`, which strict mode cannot express unchanged. Explicit false behaves like the other two dialects.
4. **No ****`tool_choice`****.** Clawcode sends `auto` whenever tools are on. Lane B proposes leaving the field out instead, relying on each API's default when tools are present; confirm that default against each provider's API reference in B1 to B3. Fewer fields means fewer rejections from lax OpenAI-compatible servers.
5. **Several calls per answer stay allowed.** No `parallel_tool_calls` or `disable_parallel_tool_use`: lane A runs the calls one after another, as Clawcode does.
6. **Ids are written as the normaliser leaves them.** `TranscriptNormalizer` already makes every native id match `[A-Za-z0-9_-]{1,40}` and keeps each call paired with its result, so mappers never repair pairing the way Clawcode's `sanitize_tool_message_pairing` does.

```kotlin
// client/support/ToolSchemas.kt
object ToolSchemas {
    /** The schema as an object. A schema that is not one is a bug where it was made (BuiltInTools, lane H's listing). */
    fun parse(schema: JsonText): JsonObject

    /** Clawcode's normalize_object_schema: object nodes get `properties` and `additionalProperties: false` unless set. */
    fun forOpenAi(schema: JsonObject): JsonObject
}

// In each mapper's toRequestBody, after the messages:
if (request.tools.isNotEmpty()) put("tools", JsonArray(request.tools.map(::toWireTool)))
```

### The details that differ by dialect

| Concern | Anthropic Messages | OpenAI Responses | Chat Completions |
| --- | --- | --- | --- |
| Where the schema goes | `input_schema`, unchanged | `parameters`, cleaned | `function.parameters`, cleaned |
| `strict` | Not sent | `false` | Not sent |
| Tool input in history | JSON object (parsed; `{}` if unparsable) | JSON text | JSON text |
| Error results | `is_error: true` | `[error] ` prefix | `[error] ` prefix |
| Call id field | `id` and `tool_use_id` | `call_id` | `id` and `tool_call_id` |
| Missing call id in a stream | Not seen | Falls back to the item `id`, then `call_<n>` | `call_<index>` |

## Tasks

Four tasks from the plan plus one optional task, one pull request each, branched as `lane/b/<task>`. B1 to B3 can run in parallel once M0 is merged; B4 needs all three. This doc adds B5, which happens only if decision 3 is yes.

### B1 Anthropic: send tools (S, needs M0)

1. Add `client/support/ToolSchemas.kt` with `parse`. B1 lands it; B2 and B3 add to it.
2. In `AnthropicClientMapper.toRequestBody`, when `request.tools` is not empty, add `tools`: one object per definition with `name`, `description` and `input_schema` (the parsed schema, unchanged).
3. Add `lastJson()` to `FakeProviderServer`, returning the last recorded body as a `JsonObject`, so the three test classes stop parsing bodies by hand.
4. Tests in `AnthropicModelGatewayImplTest`: a request with `BuiltInTools.ALL` sends six tools in order with their schemas intact; a request without tools has no `tools` key; every existing test still passes.

Done when: the Anthropic fake receives the six schemas, and nothing else in the body changes.

### B2 Chat Completions: send `tools[].function` (S, needs M0)

1. Add `ToolSchemas.forOpenAi`, porting Clawcode's `normalize_object_schema`: it walks `properties` values and `items`, and fills only what is missing.
2. In `OpenAiClientMapper.toRequestBody`, when tools are offered, add `tools`: `{"type": "function", "function": {name, description, parameters}}`, with `parameters` cleaned by `forOpenAi`.
3. `ToolSchemasTest`: port Clawcode's three cases (bare object, nested object, existing `additionalProperties: true` left alone) and add one for an array of objects.
4. Gateway test: the six tools arrive in the Chat Completions shape; no `tools` key without tools; the `max_tokens` and `stream_options` settings still apply.

Done when: the Chat Completions fake receives the same six tools in its shape.

### B3 Responses: send flat `tools[]` entries (S, needs M0)

1. In `OpenAiResponsesClientMapper.toRequestBody`, when tools are offered, add `tools`: `{"type": "function", name, description, parameters, "strict": false}`, with `parameters` cleaned by `forOpenAi`.
2. Check `tool_choice` and its default against OpenAI's Responses API reference, and record the answer in decision 2.
3. Gateway test: six flat entries, each with `strict: false`; no `tools` key without tools; `store: false` is still sent.

Done when: the Responses fake receives the same six tools in its shape.

### B4 Native tool history on all three dialects (S, needs B1 to B3)

1. Write one scenario and run it on each fake: `read_file` offered; the transcript holds a user prompt, an assistant `read_file` call with an Anthropic-style id longer than 40 characters, its result, one error result and a follow-up prompt. Pass it through `TranscriptNormalizer`, then the gateway, as lane A will.
2. Assert on each body: the tool list is present; each call and its result carry the same portable id; the Chat Completions assistant message has `content: null`; error results carry `is_error` or the `[error] ` prefix.
3. Add one streamed tool call per dialect if not already covered: Anthropic `input_json_delta` fragments (covered), a Responses `function_call` in `response.completed` (covered), and a Chat Completions call sent whole in one chunk without `index`, as some local servers do (new).
4. Add one check that a call to a tool that is no longer offered reaches every dialect as text, not as a native call.

Done when: a conversation with tool history and tools passes on all three fakes, with ids unchanged from normaliser to wire.

### B5 Models without tools (S, optional, needs decision 3)

1. Contract pull request: add `supportsTools: Boolean = true` to `ModelProfile`; additive, reviewed by lane A.
2. Add `tools: Boolean = true` to `ModelCatalogProperties.Profile` and map it in `ModelCatalogGatewayImpl`.
3. Tell lane A: when `supportsTools` is false, the turn offers no tools, so the normaliser turns old calls into text.
4. Tests: the property binds; a profile with `tools: false` resolves with `supportsTools = false`.

Done when: a model marked `tools: false` gets requests with no tool list.

## Tests

Every lane B test runs against `FakeProviderServer`, which records each request body; no test needs a key or the network. The fakes do not validate requests the way real providers do, so the assertions spell out each provider's documented shape.

| Test | Class | What it proves | Task |
| --- | --- | --- | --- |
| Six tools in Anthropic's shape, schemas intact | `AnthropicModelGatewayImplTest` | B1 sends exactly what `BuiltInTools` defines | B1 |
| No `tools` key without tools, on each dialect | All three gateway tests | Requests stay byte-identical while tools are off | B1 to B3 |
| Object clean-up cases from Clawcode | `ToolSchemasTest` | MCP schemas with bare objects become acceptable | B2 |
| Six tools in the Chat Completions shape | `OpenAiCompatibleModelGatewayImplTest` | `function` nesting and cleaned `parameters` | B2 |
| Six flat entries with `strict: false` | `OpenAiResponsesModelGatewayImplTest` | Responses shape and the strict choice | B3 |
| Tools plus history, one scenario per dialect | All three gateway tests | Calls and results stay paired under portable ids | B4 |
| A whole Chat Completions call without `index` | `OpenAiCompatibleModelGatewayImplTest` | Local servers that send calls in one chunk still work | B4 |
| A call to a tool no longer offered | All three gateway tests | It reaches the model as text, never as a native call | B4 |
| `tools: false` on a profile | `ModelCatalogGatewayImplTest` | The per-model switch binds and resolves | B5 |

The end-to-end proof belongs to lane J: J1 runs a scripted `read_file` turn through the CLI on all three fakes. Before tagging `gate-m1`, one manual turn per real provider with your own keys catches anything the fakes cannot, such as a provider rejecting a schema.

## Configuration

Lane B adds one property, and only if B5 goes ahead; B1 to B4 need no configuration. No secrets are involved: provider keys stay where 3a put them, read from the environment.

| Property | Default | Meaning |
| --- | --- | --- |
| `butler.models.profiles.<id>.tools` | `true` | `false` for a model that rejects tool definitions; the turn then offers it no tools |

```yaml
butler:
  models:
    profiles:
      "[ollama/gemma3]":   # brackets keep the slash in the key, as for "[gpt-5.5]"
        tools: false   # this local model answers in text only
```

The model name in the example is illustrative; which local models lack tool support should be checked against the provider before adding entries to the shipped `application.yml`.

## Working with other lanes

Lane B depends only on M0's `ModelRequest.tools` and `ToolDefinition`; it can finish before any tool runs. It needs no core fakes: its tests build `ModelRequest` values directly and read what `FakeProviderServer` received.

| Lane | What passes between it and lane B |
| --- | --- |
| A Agent loop | A fills `ModelRequest.tools` and feeds `TranscriptNormalizer` the same set; B writes whatever it is given. If B5 lands, A offers no tools to a model with `supportsTools = false`. |
| E Reasoning replay | E adds its replay helpers to the three mappers B owns, wired in with one call each. On Responses, reasoning items must sit before the `function_call` items they led to, so B keeps input-item order in one function E can extend. |
| H MCP client | MCP schemas reach the OpenAI dialects through `ToolSchemas.forOpenAi`. Tool names are H's to shape: Butler's `ToolName` allows `.` and `:` and up to 128 characters, which providers may reject. Confirm each provider's name rule and have H map names that break it. |
| J Integration | J1 runs a scripted `read_file` turn on the three fakes after B1 to B3 merge, then turns `butler.tools.enabled` on. A manual turn per real provider before `gate-m1` is recommended. |
| C, D, F, G, I | Nothing direct. |

Contract changes lane B may propose: `ModelProfile.supportsTools` (B5, additive). Anything else stays inside secondary.

## Done when

Lane B is done when all three dialects send the tool list and accept tool history, and J1 is green on `main`.

- [ ] B1 merged: Anthropic sends `tools`; `ToolSchemas.parse` in place
- [ ] B2 merged: Chat Completions sends `tools[].function` with cleaned schemas
- [ ] B3 merged: Responses sends flat entries with `strict: false`; `tool_choice` default confirmed
- [ ] B4 merged: tools plus history pass on all three fakes, ids intact
- [ ] B5 merged, or dropped by decision 3
- [ ] No request changes while `butler.tools.enabled` is off
- [ ] J1 green and `gate-m1` tagged

## Risks and decisions to confirm

Three decisions shape lane B; the first two can be settled during B3, the third before B5 starts.

**Decisions**

- [ ] **Decision 1: ****`strict: false`**** on Responses tools.** Proposed, so Butler's schemas with optional fields behave the same on every dialect. The alternative rewrites each schema for strict mode: every field required, optional ones made nullable.
- [ ] **Decision 2: no ****`tool_choice`****.** Proposed, relying on each API's default when tools are present; B3 confirms that default in each provider's reference. The alternative copies Clawcode and always sends `auto`.
- [ ] **Decision 3: a per-model ****`tools: false`**** switch (B5).** Proposed for local models that reject tool definitions. Clawcode hard-codes two such models; Butler would keep the list in configuration, like its other model facts.

**Risks**

| Risk | Effect | What lane B does |
| --- | --- | --- |
| A real provider rejects a schema the fakes accept | The first real tool turn fails with a 400 | Manual turn per provider before `gate-m1`; the error message names the field |
| An OpenAI-compatible server rejects `tools` or answers tool calls in its own format | Tools fail on that server only | B4's whole-call stream test; B5's switch turns tools off for that model |
| MCP tool names break a provider's name rule | Requests with MCP tools are rejected | Flagged to lane H under Working with other lanes |
| Lane E needs a different item order on Responses | E reworks B's mapper | B keeps input-item building in one function, and E reviews B3 |
