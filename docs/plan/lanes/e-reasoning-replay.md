# Butler lane E: Reasoning replay

> Exported from the Claude Doc <https://claude.ai/artifact/FxsMB7TLAJTuwjTS4rgL2Q> on 2026-10-24. The Claude Doc is the original; if they differ, read [STATUS.md](../STATUS.md) first for what has changed since.


2026-10-21 · Chamantha De silva

## Goal

Lane E lets a reasoning model keep its own reasoning between the tool calls of one turn, on the provider that produced it, and drops that reasoning cleanly when the session moves to another provider. It is about five to seven days for one person, and it lands at gate M3.

Why it matters: reasoning models plan a multi-step task in their reasoning, then call a tool. If that reasoning is not sent back with the tool result, the model starts each step without its plan. OpenAI's function-calling guide says reasoning items returned with tool calls must be passed back with the tool outputs, and Anthropic has a matching rule for thinking blocks (E1 confirms both). Today Butler drops all thinking from every request, which is safe but forgets the plan.

What users get at M3: GPT models on the Responses API and Claude models with thinking keep their reasoning across tool calls, `--reasoning-effort` sets how hard they think, and switching provider mid-session still works.

## Scope

Lane E owns everything about requesting, storing and replaying reasoning; it changes no tool behaviour and no routing.

**In lane E**

- E1: the provider facts, checked against official docs on 2026-10-21 and summarised under Design, plus whatever is still marked "confirm" there.
- E2: the core rule for which stored reasoning goes back to the model, inside `TranscriptNormalizer`.
- E3: Responses (OpenAI, xAI): ask for encrypted reasoning, store each reasoning item whole, replay it before the calls it led to.
- E4: Anthropic: ask for thinking per model, store thinking and redacted thinking whole, replay them unchanged next to their tool calls.
- E5: `--reasoning-effort` and per-model reasoning settings in `butler.models.profiles`, mapped per dialect.

**Left to other lanes or to later**

- The tool loop that sends replayed reasoning back between calls: lane A, which passes E's settings into every request of a turn.
- The `tools` array that sits next to reasoning in each request: lane B.
- Keeping the system prompt and tool list fixed for the length of a turn: lanes A and G (see Working with other lanes).
- Showing reasoning in the terminal: lane I, from the existing `ThinkingDelta` events.
- Chat Completions reasoning (DeepSeek, OpenRouter, Ollama): parsed for display as today, never replayed. DeepSeek V4's echo rule is listed under decisions.
- Prompt caching of replayed reasoning: after v1.

## Starting point

Butler already parses reasoning from all three dialects and shows it live, but it never asks for reasoning and never sends any back. M0.4 adds the storage slot lane E fills. Secondary paths are under `secondary-adaptor/.../secondary/`.

**In Butler today (after 3c and M0)**

| File | What it does with reasoning now | Gap for lane E |
| --- | --- | --- |
| core `domain/session/ContentBlock.kt` | `Thinking(thinking, signature, replay)`; `ReasoningReplay(provider, itemId, payload)` from M0.4 | Nothing fills `replay` yet |
| core `domain/model/TranscriptNormalizer.kt` | Drops every thinking block from every request | E2: keep same-provider reasoning from the current turn |
| `client/anthropic/mapper/AnthropicClientMapper.kt` | Never sends `thinking`; drops thinking blocks from history | E4: request thinking per model; replay blocks unchanged |
| `client/anthropic/support/AnthropicStreamAccumulator.kt` | Builds thinking text and `signature_delta`; skips `redacted_thinking` | E4: keep redacted blocks; they must go back too |
| `client/openai/mapper/OpenAiResponsesClientMapper.kt` | No `reasoning`, no `include`; reasoning becomes a summary-only `Thinking` | E3: request encrypted reasoning; replay items |
| `client/openai/support/OpenAiResponsesStreamAccumulator.kt` | Keeps summary and content text of `reasoning` items; drops `id` and `encrypted_content` | E3: keep the whole item |
| `client/openai/mapper/OpenAiClientMapper.kt` (Chat Completions) | `reasoning_content` or `reasoning` deltas become `Thinking` with no signature | E5 only: `reasoning_effort` |
| `config/ModelProperties.kt`, `gateway/impl/ModelCatalogGatewayImpl.kt` | No reasoning facts per model | E5: reasoning settings per profile |
| api `cli/command/GlobalOptions.kt` | No reasoning flag | E5: `--reasoning-effort` |

**In Clawcode (v0.1.7, commit 22fe360)**

| File | What it does |
| --- | --- |
| `rust/crates/rusty-claude-cli/src/main.rs` | Parses `--reasoning-effort low\|medium\|high`; `convert_message` passes thinking blocks back with their signature |
| `rust/crates/api/src/providers/openai_compat.rs` | Sends `reasoning_effort` on Chat Completions only; `is_reasoning_model` strips tuning parameters for o1, o3, o4 and some others; DeepSeek V4 gets `thinking: enabled` and its reasoning echoed as `reasoning_content` |
| `rust/crates/api/src/providers/anthropic.rs` | Never requests thinking: there is no `thinking` field in its requests |
| `rust/crates/api/src/types.rs` | `Thinking` and `RedactedThinking` content blocks |

Clawcode has no Responses client, so E3 follows OpenAI's and xAI's documentation, not Clawcode.

## Where the code goes

Lane E owns the core thinking rule and its own reasoning helpers in secondary; it touches lane B's mappers through one call each and lane A's use case only through a contract change.

| File | Module | New or changed | Task | Owner |
| --- | --- | --- | --- | --- |
| `domain/model/TranscriptNormalizer.kt` | core | Changed: `replayFor` parameter and the keep rule | E2 | E (thinking rules) |
| `domain/model/Reasoning.kt` | core | New: `ReasoningSupport`, `ReasoningEffort`, `ReasoningRequest` | E5 | E |
| `domain/model/ModelProfile.kt`, `ModelExchange.kt` | core | Changed: `ModelProfile.reasoning`, `ModelRequest.reasoning` | E5 | Contract change |
| `usecase/turn/RunTurnUseCase.kt` | core | Changed: `Command.reasoningEffort`, passed into each request | E5 | A (E's contract pull request) |
| `client/openai/support/ResponsesReasoning.kt` | secondary | New: `reasoning` and `include` fields; item capture and replay | E3 | E |
| `client/anthropic/support/AnthropicReasoning.kt` | secondary | New: `thinking` and `output_config` fields, beta header, block capture and replay | E4 | E |
| `client/openai/support/ChatReasoning.kt` | secondary | New: `reasoning_effort` field | E5 | E |
| `client/openai/support/OpenAiResponsesStreamAccumulator.kt`, `client/openai/dto/OpenAiStream.kt` | secondary | Changed: keep reasoning item JSON | E3 | E |
| `client/anthropic/support/AnthropicStreamAccumulator.kt`, `client/anthropic/dto/AnthropicStream.kt` | secondary | Changed: keep block JSON, `redacted_thinking` | E4 | E |
| The three `client/*/mapper/*ClientMapper.kt` | secondary | Changed: one call each into E's helpers | E3 to E5 | B (reviewed by E) |
| `config/ModelProperties.kt`, `gateway/impl/ModelCatalogGatewayImpl.kt` | secondary | Changed: `reasoning` block per profile | E5 | E |
| `cli/command/GlobalOptions.kt` and the prompt and REPL paths | api | Changed: `--reasoning-effort` | E5 | I (reviewed by E) |
| `src/main/resources/application.yml` | api | Changed: reasoning settings for the shipped profiles | E5 | Shared, append-only |
| test `client/FakeProviderServer.kt` | secondary | Changed: scripted reasoning streams | E3, E4 | Shared, append-only |

Accumulators hold provider parsing, so E edits them directly; the plan's Workstreams table already gives lane E the `client/*/support/*Reasoning*` files.

## Design

Reasoning is stored as the provider sent it and goes back only to that provider, within the turn that produced it. Everything else is left out, exactly as today.

### What the providers require (E1)

Checked against official documentation on 21 October 2026. The rules differ from model to model, so lane E re-checks them before `gate-m3`.

| Topic | Anthropic Messages | OpenAI Responses | xAI Responses |
| --- | --- | --- | --- |
| Asking for reasoning | `thinking: {type: "adaptive"}` plus `output_config.effort` on Opus 4.6 and later, Sonnet 4.6 and later, Haiku 5.5. Opus 4.5, Sonnet 4.5 and Haiku 4.5 reject adaptive and need `{type: "enabled", budget_tokens}`, at least 1,024. Claude 4.7 and later reject `budget_tokens`. | `reasoning: {effort}`. Values: `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`; support varies by model. | `reasoning.effort`: `low`, `medium`, `high`, `xhigh` (no `xhigh` on grok-4.5). Default `high`; reasoning cannot be turned off. |
| What comes back to replay | `thinking` blocks with a `signature`; the text may be empty when display is omitted. `redacted_thinking` blocks with encrypted `data`. | `reasoning` items. With `store: false`, they carry `encrypted_content` by default; the `include` value is still accepted. | `reasoning` items with `encrypted_content` when `include: ["reasoning.encrypted_content"]` is sent; grok-4.7 returns it always. |
| Replay rule | Every thinking block goes back complete and unmodified with its `tool_use`, redacted ones too, in the original order. | Pass back the reasoning items, function calls and outputs since the last user message, untouched. | Send `encrypted_content` back unchanged in the next `input`. |
| Changes within a turn | Thinking cannot be toggled mid-turn: the API silently turns it off. A block stays valid only while the system prompt, tools and earlier messages are unchanged. | Reasoning is reused only within the same model family; other reasoning is omitted. | Not stated. |
| Other limits | Manual mode allows `tool_choice` `auto` or `none` only. Interleaved thinking needs the beta header `interleaved-thinking-2025-05-14` on Opus 4.5, Sonnet 4.5 and earlier Claude 4 models; Haiku 4.5 does not interleave. | Reasoning text itself is never returned; summaries may be. | Usage reports `reasoning_tokens`. |

Sources: Anthropic [Thinking](https://platform.claude.com/docs/en/build-with-claude/thinking) and [Extended thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking); OpenAI [Reasoning models](https://developers.openai.com/api/docs/guides/reasoning), [Migrate to the Responses API](https://developers.openai.com/api/docs/guides/migrate-to-responses) and [Create a model response](https://developers.openai.com/api/reference/resources/responses/methods/create); xAI [Reasoning](https://docs.x.ai/developers/model-capabilities/text/reasoning).

### The keep rule (E2)

*(Diagram in the Claude Doc: the keep rule for stored reasoning · four checks, one way to be sent)*

Read it top to bottom: a block survives only if all four answers are yes. The third check is the proposed default (decision 1); answering "all turns" there would drop it.

- **Why reasoning must carry its provider.** Messages do not record which provider wrote them, so the provider tag on `ReasoningReplay` is the only way to tell. Lane E therefore fills `replay` for Anthropic thinking too, refining M0's "usually null".
- **Why the payload is the provider's own JSON.** Both providers demand the item back unmodified. Storing the raw item (Responses) or block (Anthropic) and writing it back byte for byte removes any chance of a mapping mistake, and covers `redacted_thinking` and fields Butler does not know yet.
- **Why only the current turn.** Within a turn, the system prompt and tools stay fixed, so the blocks stay valid. Between turns, Butler's system prompt changes (the date today; git status after lane G), which can invalidate older Anthropic blocks. The current turn is also what the tool loop needs.
- **Why drop thinking whose call became text.** The normaliser rewrites a call to a tool no longer offered as text; the message then differs from what the model produced, and its thinking is no longer valid.

```kotlin
// domain/model/TranscriptNormalizer.kt: replayFor = the provider of this request, null to drop all (today)
fun normalize(messages: List<ConversationMessage>, offeredTools: Set<ToolName>, replayFor: ProviderId? = null): List<ConversationMessage>
```

### Storing and replaying (E3, E4)

| Provider sends | Stored as | Replayed as |
| --- | --- | --- |
| Responses `reasoning` item | `Thinking(summary text, null, ReasoningReplay(provider, item id, item JSON))` | The item JSON, before the `function_call` items of the same message |
| Anthropic `thinking` block | `Thinking(text, signature, ReasoningReplay(provider, null, block JSON))` | The block JSON, first in the assistant content, order unchanged |
| Anthropic `redacted_thinking` block | `Thinking("", null, ReasoningReplay(provider, null, block JSON))` | The block JSON, in place |
| Chat Completions `reasoning_content` | `Thinking(text, null, null)`, as today | Never |

### Asking for reasoning (E5)

Each model's catalog profile says what it accepts; the flag picks a value within that; each dialect writes its own field. The settings are fixed for a whole turn, because Anthropic silently turns thinking off if they change mid-turn.

```kotlin
// domain/model/Reasoning.kt
enum class ReasoningEffort { NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX }

/** From butler.models.profiles.<id>.reasoning. */
sealed interface ReasoningSupport {
    data object None : ReasoningSupport
    /** Responses, Chat Completions and Anthropic adaptive thinking. */
    data class Effort(val efforts: Set<ReasoningEffort>, val default: ReasoningEffort? = null) : ReasoningSupport
    /** Anthropic manual thinking, for models that reject adaptive. */
    data class Budget(val budgetTokens: Int, val interleaved: Boolean) : ReasoningSupport
}

/** Built once per turn by lane A, sent on every request of that turn. */
data class ReasoningRequest(val effort: ReasoningEffort?, val replayFor: ProviderId?)

// Additive contract changes: ModelProfile.reasoning = ReasoningSupport.None,
// ModelRequest.reasoning: ReasoningRequest? = null, RunTurnUseCase.Command.reasoningEffort: ReasoningEffort? = null
```

| Dialect | `Effort` support | `Budget` support |
| --- | --- | --- |
| Anthropic | `thinking: {type: "adaptive"}`, `output_config: {effort}` | `thinking: {type: "enabled", budget_tokens}`, plus the interleaved beta header when set |
| Responses | `reasoning: {effort}`, `include: ["reasoning.encrypted_content"]` | Not used |
| Chat Completions | `reasoning_effort`, as Clawcode sends it | Not used |

## Tasks

Five tasks, one pull request each, branched as `lane/e/<task>`. E1 and E2 can start at once; E3 and E4 run in parallel after them; E5's contract pull request should merge early because E3 and E4 read its types. This doc resizes E5 from S to M: it now carries a contract change, catalog settings and a CLI flag.

### E1 Provider facts (S, needs nothing)

1. Start from the table under Design, checked on 21 October 2026.
2. Settle what it leaves open: the Anthropic option that drops invalid thinking blocks instead of rejecting the request, and its name; Anthropic's `output_config.effort` values; the efforts each shipped OpenAI and xAI model accepts; whether OpenAI returns reasoning summaries without asking for them.
3. Write each shipped profile's reasoning settings into the E5 proposal for `application.yml`, quoting the page each value came from.
4. Add a short findings note to the Contracts tab with a link to this doc, as the Tasks tab asks.

Done when: nothing in the Design table is still open, and the findings are on the Contracts tab.

### E2 The keep rule in the normaliser (S, needs M0.4)

1. Add `replayFor: ProviderId? = null` to `TranscriptNormalizer.normalize`. Null keeps today's behaviour: every thinking block is dropped.
2. Find the current turn: the messages after the last `USER` message in the stored transcript (tool results are `TOOL` messages, so they do not end it).
3. Apply the four checks from the diagram. Keep surviving blocks in their original position within the message.
4. Never merge an assistant message that keeps thinking with its neighbour: merging would change the message the reasoning belongs to.
5. Core tests, one per branch: old-style thinking dropped; another provider's dropped; an earlier turn's dropped; a block whose call became text dropped; a current-turn block kept in place; `replayFor = null` drops everything, so existing tests pass unchanged.

Done when: the rule holds in core tests and lane A can pass `model.provider.id`.

### E3 Responses: encrypted reasoning (M, needs E1, E2, and B3 merged)

1. `OpenAiResponsesStreamAccumulator`: keep each `reasoning` item's id and raw JSON, from `response.completed` or `response.output_item.done`, next to the summary text.
2. Mapper `toResponse`: build `Thinking(summary, null, ReasoningReplay(provider, id, raw JSON))`. The client already knows its `ProviderId`; pass it to the mapper.
3. Request: when the model reasons, add `include: ["reasoning.encrypted_content"]`, and `reasoning: {effort}` when an effort is set (`ResponsesReasoning`).
4. Replay: write assistant blocks in their stored order, so a reasoning item goes out before the `function_call` items it led to. A kept `Thinking` becomes its raw JSON, unchanged. This is one call from lane B's `toItems` into `ResponsesReasoning`.
5. Tests on the Responses fake: the request carries `include` and `effort`; a streamed reasoning item is stored with its `encrypted_content`; the next request replays it byte for byte, before its call; after a switch to Anthropic, nothing is replayed.

Done when: the Responses fake receives the replayed item, unchanged, in the right place.

### E4 Anthropic: signed thinking (M, needs E1 and E2)

1. `AnthropicStreamAccumulator`: keep `redacted_thinking` blocks (their `data`) and assemble each thinking block's final JSON from its text and signature. Add `AnthropicBlock.RedactedThinking`.
2. Mapper `toResponse`: `Thinking(text, signature, ReasoningReplay(provider, null, block JSON))`; redacted blocks become `Thinking("", null, replay)`.
3. Request (`AnthropicReasoning`): `Effort` support writes `thinking: {type: "adaptive"}` and `output_config: {effort}`; `Budget` support writes `thinking: {type: "enabled", budget_tokens}` and, when `interleaved` is set, the `anthropic-beta: interleaved-thinking-2025-05-14` header. The client gains one optional header for this.
4. Replay: a kept `Thinking` becomes its stored block JSON, first in the assistant content, in the original order.
5. Tests on the Anthropic fake: the right `thinking` field per mode; the beta header only when interleaved; a signature replayed intact; a redacted block replayed; no `tool_choice` sent, which manual mode requires.

Done when: the Anthropic fake receives every signature intact.

### E5 Reasoning effort and per-model settings (M, needs E1)

1. Contract pull request, additive, reviewed by lanes A and B: `Reasoning.kt`, `ModelProfile.reasoning`, `ModelRequest.reasoning`, `Command.reasoningEffort`.
2. Catalog: bind `butler.models.profiles.<id>.reasoning` (see Configuration) and map it to `ReasoningSupport`. Refuse to start on a budget that is not below the profile's `max-output-tokens`, unless `interleaved` is set.
3. Chat Completions: write `reasoning_effort` when an effort is set, as Clawcode does (`ChatReasoning`).
4. CLI: add `--reasoning-effort <value>` as a global option for prompts and the REPL. Accept Clawcode's `low`, `medium` and `high` and Butler's other names; refuse a value the model does not list, with the model's own list in the message.
5. Tests: binding and validation; flag parsing; the field each dialect writes; no field at all for a model with no reasoning settings.

Done when: each dialect gets its own field, and models without reasoning get none.

## Tests

The keep rule is proved in core with plain messages; the wire shapes are proved on `FakeProviderServer`, which records each request. No test needs a key, and the fakes never check signatures, so the assertions compare bytes.

| Test | Where | What it proves | Task |
| --- | --- | --- | --- |
| One case per branch of the keep rule | core `TranscriptNormalizerTest` | Only same-provider, current-turn blocks with native calls survive | E2 |
| `replayFor = null` | core `TranscriptNormalizerTest` | Today's behaviour, so every earlier test still passes | E2 |
| No merge across kept thinking | core `TranscriptNormalizerTest` | The message reasoning belongs to is never changed | E2 |
| `include` and `reasoning.effort` in the request | `OpenAiResponsesModelGatewayImplTest` | Responses asks for encrypted reasoning | E3 |
| Reasoning item stored whole, then replayed before its call | `OpenAiResponsesModelGatewayImplTest` | Byte-for-byte replay in the right order | E3 |
| `thinking` field per mode; beta header only when interleaved | `AnthropicModelGatewayImplTest` | Adaptive and manual requests match each model's rule | E4 |
| Signature and redacted block replayed intact | `AnthropicModelGatewayImplTest` | Anthropic's unmodified-block rule | E4 |
| `reasoning_effort` on Chat Completions | `OpenAiCompatibleModelGatewayImplTest` | Clawcode parity | E5 |
| No reasoning field for a model without settings | All three gateway tests | Models that do not reason get today's request | E5 |
| Profile binding and budget validation | `ModelCatalogGatewayImplTest` | Bad settings stop at start-up, not at the first call | E5 |
| `--reasoning-effort` parsing and refusal | api `CliUnitTest` | Unknown values fail with the model's own list | E5 |
| Old session files with thinking load and are never replayed | secondary session store tests | Nothing changes for existing sessions | E2, E3 |

Lane J's M3 check runs a scripted two-call tool turn through the CLI on the Responses and Anthropic fakes, then switches provider and checks that nothing is replayed.

## Configuration

Each catalog profile gets an optional `reasoning` block; a profile without one reasons only if the provider does so on its own, and Butler sends no reasoning field. Nothing here is a secret.

| Property under `butler.models.profiles.<id>.reasoning` | Default | Meaning |
| --- | --- | --- |
| `mode` | `none` | `effort` (Responses, Chat Completions, Anthropic adaptive), `budget` (Anthropic manual) or `none` |
| `efforts` | empty | Effort mode: the values the model accepts, from E1 |
| `default-effort` | none | Effort mode: sent when `--reasoning-effort` is not given; empty leaves it to the provider |
| `budget-tokens` | none | Budget mode: the thinking budget, at least 1,024 and below `max-output-tokens` unless `interleaved` |
| `interleaved` | `false` | Budget mode: send the `interleaved-thinking-2025-05-14` beta header |

The shipped profiles, as far as E1's sources go so far. Values marked "confirm" are E1's to settle, and the budget figure is a proposal, not a provider recommendation:

```yaml
butler:
  models:
    profiles:
      claude-opus-4-7:
        reasoning: { mode: effort, efforts: [low, medium, high] }    # adaptive; effort values: confirm
      claude-sonnet-4-6:
        reasoning: { mode: effort, efforts: [low, medium, high] }    # adaptive; effort values: confirm
      claude-haiku-4-5:
        reasoning: { mode: budget, budget-tokens: 8000 }             # rejects adaptive; does not interleave
      "[gpt-5.5]":
        reasoning: { mode: effort, efforts: [low, medium, high] }    # confirm the full list in E1
      "[grok-4.7]":
        reasoning: { mode: effort, efforts: [low, medium, high, xhigh], default-effort: high }
```

The flag is per run: `butler --reasoning-effort high prompt "..."`, or as a global option in the REPL. Clawcode accepts `low`, `medium` and `high`; Butler accepts whatever the model's profile lists, so those three work wherever a profile includes them.

## Working with other lanes

Lane E builds on M0.4's `ReasoningReplay` and proposes one additive contract change (E5). It tests against plain `ConversationMessage` values in core and `FakeProviderServer` in secondary, so it needs no other lane's code to start.

| Lane | What passes between it and lane E |
| --- | --- |
| A Agent loop | A builds one `ReasoningRequest` per turn and sends it on every request of that turn, and calls the normaliser with `replayFor = model.provider.id` when the model reasons. A also composes the system prompt and tool list once per turn: Anthropic blocks stay valid only while both are unchanged. |
| B Provider tools | B owns the mappers; E adds one call into each, reviewed by B. On Responses, E needs assistant blocks written in stored order, so reasoning precedes the calls it led to. B's "no `tool_choice`" decision suits Anthropic's manual thinking mode, which allows only `auto` or `none`. |
| G Project context | G puts git status and instructions into the system prompt. It must not refresh them inside a turn, for the same validity reason. |
| I Terminal | I shows `ThinkingDelta` text as today. Newer Claude models omit thinking text by default, so there may be nothing to show; that is expected. I adds `--reasoning-effort` to the CLI with E. |
| J Integration | J3's M3 check: a scripted two-call tool turn on the Responses and Anthropic fakes with reasoning replayed, then a provider switch with nothing replayed. |
| C, D, F, H | Nothing direct. |

## Done when

Lane E is done when reasoning survives the tool calls of a turn on Responses and Anthropic, disappears after a provider switch, and J's M3 check is green on `main`.

- [ ] E1 merged: no open items in the provider table; findings on the Contracts tab
- [ ] E2 merged: the keep rule in `TranscriptNormalizer`; `replayFor = null` keeps today's behaviour
- [ ] E5 contract pull request merged: `ReasoningSupport`, `ModelRequest.reasoning`, `Command.reasoningEffort`
- [ ] E3 merged: Responses asks for, stores and replays encrypted reasoning
- [ ] E4 merged: Anthropic thinking per model; signatures and redacted blocks replayed intact
- [ ] E5 merged: `--reasoning-effort` and catalog settings, mapped per dialect
- [ ] Lanes A and G confirm the system prompt and tool list are fixed within a turn
- [ ] J's M3 check green and `gate-m3` tagged

## Risks and decisions to confirm

Decisions 1 and 2 shape E2 to E4 and are worth settling before E2 starts; the others can wait until E3 and E4.

**Decisions**

- [ ] **Decision 1: replay only the current turn.** Proposed: it is what the tool loop needs, it stays valid while Butler's system prompt changes between turns, and it costs fewer input tokens. The alternative replays every same-provider turn, which newer OpenAI and Claude models can use, but older Anthropic blocks may be rejected or dropped once the prompt has changed.
- [ ] **Decision 2: store the provider's raw JSON as the replay payload, for Anthropic thinking too.** Proposed, for byte-for-byte replay and because messages do not record their provider. It refines M0's "usually null" and makes session files larger for reasoning models.
- [ ] **Decision 3: no DeepSeek V4 reasoning echo in v1.** Clawcode echoes DeepSeek V4 reasoning as `reasoning_content` because, per its code comment, the API returns 400 otherwise. Proposed: leave it until someone uses DeepSeek through Butler.
- [ ] **Decision 4: ask Responses for reasoning summaries.** Sending `reasoning.summary: "auto"` would give the terminal something to show. Proposed: yes where E1 confirms the model returns them; otherwise leave it out.
- [ ] **Decision 5: keep Anthropic's default thinking display.** Newer models omit thinking text by default, which gives a faster first token. The alternative, `display: "summarized"`, shows thinking at no saving in cost.

**Risks**

| Risk | Effect | What lane E does |
| --- | --- | --- |
| A provider changes its reasoning rules for a new model | Requests fail or reasoning is ignored | Settings live in the catalog, not code; E1's table is re-checked before `gate-m3` |
| The system prompt or tool list changes inside a turn | Anthropic turns thinking off silently, or rejects the blocks | A and G fix both per turn; listed in Done when |
| An encrypted block is tied to the account that produced it | Replay fails after the API key under a provider id changes | Replay is current-turn only, so a new key affects one turn at most; J checks the error is clear |
| Encrypted payloads make session files grow | Larger files and slower loading for long reasoning sessions | Stored only for reasoning models; compaction (lane G) removes them with the old messages |
| Thinking tokens count toward `max_tokens` in budget mode | Answers cut short on small limits | Start-up validation keeps the budget below `max-output-tokens` |
