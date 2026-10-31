# Butler lane I: Terminal

> Exported from the Claude Doc <https://claude.ai/artifact/CvUCKuFovMQHNmiMgGHunD> on 2026-10-24. The Claude Doc is the original; if they differ, read [STATUS.md](../STATUS.md) first for what has changed since.


2026-10-21 · Chamantha De silva

## Goal

Lane I makes tool use visible and controllable in the terminal: tool calls on screen and `butler tools` for gate M1, a real line editor and Ctrl-C that stops a turn for gate M2. It follows Clawcode `v0.1.7`'s layout, wording and keys wherever Butler's CLI can.

At M1, a user watching `butler` sees each file the model reads or searches, as it happens, and can list the tools the model is offered. At M2, the REPL has history and line editing, and Ctrl-C stops a runaway turn without losing the session.

## Scope

Lane I owns how the terminal shows and controls a turn, never what the turn does. It works in the api module, plus one small read-only use case in core.

**In lane I**

- I1: tool calls and results on screen while a turn streams, in Clawcode's layout, with display caps.
- I2: `butler tools`, listing each tool's name, tier and description, in text and JSON.
- I3: a JLine line editor behind `CliConsole` for interactive use, with history and line editing; piped input keeps today's plain reader.
- I4: Ctrl-C at the prompt and during a turn, wired to `TurnCancellation`.
- `butler.cli.*` settings for the line editor and colour.

**Left to other lanes**

- Stopping the turn safely and writing the "interrupted" results (A). Lane I only raises the flag.
- The tool approval prompt and its wording (D). Lane I gives it a line reader that works with JLine.
- What each tool returns (C, F, H). Lane I only formats it.
- Markdown rendering with syntax highlighting, Tab completion and the Esc key, after M2 (see decisions).

## Starting point

Butler's REPL already reads lines, runs slash commands and streams text; it lacks tool display, line editing and interruption. Clawcode spreads the matching behaviour over its CLI crate, `rusty-claude-cli`.

**In Butler after ****`contracts-v1`** (api paths under `api-primary-adaptor/.../api/cli/`)

| File | What it does today | Lane I change |
| --- | --- | --- |
| `CliSupport.kt`: `CliConsole`, `SystemCliConsole` | One shared `BufferedReader` over stdin; `interactive` asks `System.console()` whether it is a terminal | I3: JLine console for terminals; the plain reader stays for pipes |
| `render/StreamingTurnObserver.kt` | Prints text deltas raw; ignores `Started`, `ThinkingDelta` and, after M0.3, the two tool events | I1: renders `ToolStarted` and `ToolFinished` |
| `repl/ReplRunner.kt` | Prints `> `, reads a line, skips empty lines, exits on end of input | I3: passes the prompt to the reader; I4: wraps each turn in the interrupt handler |
| `action/PromptActions.kt` | Builds the observer and calls `execute(command, callbacks)` | I1: richer observer; I4: adds the `TurnCancellation` |
| `prompt/CliTurnApprover.kt` | Prints `Choice [s]: ` and reads through `CliConsole.readLine()` | I3: reads through the prompt-aware `readLine`, unchanged in behaviour |
| `command/InfoCommands.kt`, `command/ButlerCommand.kt` | The `version` and `help` pattern; the subcommand list | I2: a `ToolsCommand` in the same pattern |
| `render/CliOutputWriter.kt`, `dto/CliOutputs.kt` | Text or JSON for every command; `PromptResultOutput` already lists `toolUses` and `toolResults` | I2: `ToolListOutput` |
| core test fixtures `ToolDoubles.kt` | `CancelAfter` and `RecordingToolApprover` from M0 | I4 tests use `CancelAfter` |

**In Clawcode ****`v0.1.7`**** (commit ****`22fe360`****)**

| Clawcode file and item | What it does | Lane I task |
| --- | --- | --- |
| `main.rs`: `format_tool_call_start` (line 14018) | A small box with the tool name, then one detail line: "Reading <path>…", "Writing <path> (N lines)", "Editing <path>" with a first-line `-`/`+` preview, the glob or grep pattern with its folder, `$ <command>` for bash | I1 |
| `main.rs`: `format_tool_result` (line 14071) and its helpers | `✓` or `✗` plus the tool name; errors show 160 characters; "Read <path> (lines a-b of n)", "Wrote" or "Updated <path> (N lines)", "Edited <path>" with up to 2 hunks of 6 lines, "glob_search matched N files" with the first 8 | I1 |
| `main.rs`: `READ_DISPLAY_MAX_LINES`, `TOOL_OUTPUT_DISPLAY_MAX_LINES`, `truncate_output_for_display` (line 14407) | Shows at most 80 lines or 6,000 characters of a read, 60 lines or 4,000 characters of other output, then "… output truncated for display; full result preserved in session." | I1 |
| `input.rs`: `read_line` | rustyline on a terminal, a plain line read otherwise; Ctrl-C clears a typed line or exits on an empty one; Ctrl-D exits; history is kept in memory | I3, I4 |
| `main.rs`: `HookAbortMonitor` (around line 7834) | During a turn, Ctrl-C trips the turn interrupt signal, which the loop and the streaming client poll | I4 |
| `interrupt.rs` | Esc also interrupts a turn, on Unix terminals only; a gate hands stdin to permission prompts while the listener pauses | Decision: after M2 |
| `main.rs`: `render_repl_help` (line 9910) | Lists the keys: Up/Down, Ctrl-R, Tab, Ctrl-C, Esc, Shift+Enter or Ctrl+J | I3, I4 help text |

## Where the code goes

Almost all of lane I sits in the api module; core gains one read-only use case for `butler tools`. Api paths are under `api-primary-adaptor/src/main/kotlin/com/suarvis/butler/api/`.

| File | Module | New or changed | What it holds | Task | Owner |
| --- | --- | --- | --- | --- | --- |
| `cli/render/ToolCallRenderer.kt` | api | New | Pure functions: the start block and the result lines for each tool, with display caps | I1 | I |
| `cli/render/TerminalStyle.kt` | api | New | Colour on or off: a terminal, `NO_COLOR` unset, `butler.cli.color` | I1 | I |
| `cli/render/StreamingTurnObserver.kt` | api | Changed | Prints the two tool events through `ToolCallRenderer` | I1 | I |
| `cli/action/PromptActions.kt` | api | Changed | Passes the style and, in I4, the turn's cancellation | I1, I4 | I |
| `core/.../usecase/tool/ListToolsUseCase.kt` | core | New | Built-in tools plus `McpGateway.tools()`, and whether tools are switched on | I2 | I |
| `config/UseCaseConfig.kt` | api | Changed, append-only | The `ListToolsUseCase` bean | I2 | Shared |
| `cli/command/ToolsCommand.kt` | api | New | `butler tools` | I2 | I |
| `cli/command/ButlerCommand.kt` | api | Changed, append-only | One more subcommand | I2 | Shared |
| `cli/dto/CliOutputs.kt` | api | Changed, append-only | `ToolListOutput`, `ToolEntryOutput` | I2 | Shared |
| `cli/mapper/ToolCliMapper.kt`, `cli/render/ToolTextRenderer.kt` | api | New | Domain to DTO; the text table | I2 | I |
| `gradle/libs.versions.toml`, `api-primary-adaptor/build.gradle.kts` | build | Changed, append-only | The JLine dependency | I3 | Shared |
| `cli/console/JLineCliConsole.kt` | api | New | `CliConsole` over a JLine `Terminal` and `LineReader` | I3 | I |
| `cli/CliSupport.kt` | api | Changed | `readLine(prompt)`; `SystemCliConsole` stops being a `@Component` | I3 | I |
| `config/CliConfig.kt`, `config/CliProperties.kt` | api | New | Chooses the console; binds `butler.cli.*` | I3 | I |
| `cli/repl/ReplRunner.kt` | api | Changed | Prompt passed to the reader; each turn wrapped in `TurnInterrupts` | I3, I4 | I |
| `cli/prompt/CliTurnApprover.kt` | api | Changed | `readLine("Choice [s]: ")` instead of print then read | I3 | Shared with D |
| `cli/repl/TurnInterrupts.kt` | api | New | The Ctrl-C state machine and the `TurnCancellation` for the running turn | I4 | I |

Tests sit next to the existing CLI tests in `api-primary-adaptor/src/test/kotlin/.../api/cli/`, plus `ListToolsUseCaseTest` in core.

## Design

Lane I copies Clawcode's screen layout, wording and keys, and adds two things Clawcode does not document: a second Ctrl-C that quits a turn that will not stop, and one shared line reader for the REPL and every approval prompt.

### Tool calls on screen (I1)

- **`ToolStarted`**** prints a start block, ****`ToolFinished`**** a result.** The block is a small frame with the tool name and one detail line, worded as Clawcode's `format_tool_call_start`: "Reading <path>…", "Writing <path> (N lines)", "Editing <path>" with a first-line `-`/`+` preview, the search pattern with its folder, or `$ <command>`.
- **Results follow Clawcode's ****`format_tool_result`****.** `✓` or `✗` and the tool name, then: "Read <path> (lines a-b of n)" with the content, "Wrote" or "Updated <path> (N lines)", "Edited <path>" with up to 2 hunks of 6 lines, or "glob_search matched N files" with the first 8. Errors show their first 160 characters.
- **Same display caps as Clawcode:** 80 lines or 6,000 characters of a read, 60 lines or 4,000 characters of other output, then "… output truncated for display; full result preserved in session."
- **Tools lane I does not know** (MCP, later tools) get the generic form: name plus a compact one-line summary of the payload.
- **Formatting is pure.** `ToolCallRenderer` takes the call's input and the outcome text and returns lines, so every format is unit-tested without a terminal. It reads lane C's JSON envelopes with kotlinx-serialization, already an api dependency, and falls back to raw text when the output is not JSON.
- **Text mode only.** `--compact` and JSON print nothing extra; JSON already lists `toolUses` and `toolResults`. Before a block, the observer ends any half-printed line of model text.
- **Colour only on a terminal**, when `NO_COLOR` is unset and `butler.cli.color` allows it, so piped output and tests stay plain.

### butler tools (I2)

The api module may only reach gateways through use cases, so a small core use case lists the tools. It picks up MCP tools by itself once lane H lands.

```kotlin
// core usecase/tool/ListToolsUseCase.kt
class ListToolsUseCase(private val settings: TurnSettings, private val mcp: McpGateway) {
    data class ToolList(val enabled: Boolean, val builtIn: List<ToolDefinition>, val external: List<ToolDefinition>)

    fun execute(): ToolList = ToolList(settings.toolsEnabled, BuiltInTools.ALL, mcp.tools())
}
```

Text output is a three-column table (name, tier, description), with a note when `butler.tools.enabled` is off. JSON is `{ "kind": "tools", "enabled": …, "tools": [{ "name", "tier", "description", "source" }] }`. Tiers use Clawcode's mode names: `read-only`, `workspace-write`, `danger-full-access`.

### Line editor (I3)

- **JLine, on terminals only.** `CliConfig` picks `JLineCliConsole` when the session is interactive and `butler.cli.line-editor` is `auto` or `jline`; otherwise `SystemCliConsole`, exactly as today. Piped input and every existing test keep the plain reader.
- **Version.** JLine 4 is the active line and needs Java 11 or later; on Java 11 to 21 its `jdk11` variant leaves out the FFM terminal and uses JNI or exec. JLine 3 still gets maintenance releases (3.30.17 on 8 September). Proposed: the newest JLine 4.4.x when I3 starts, checked on the [releases page](https://github.com/jline/jline3/releases) and the [README](https://github.com/jline/jline3).
- **The reader draws the prompt.** JLine must own the prompt to redraw a line correctly, so `CliConsole.readLine` takes it.
- **Clawcode's keys map onto ****`readLine`****'s return**, so the REPL loop keeps its shape: Ctrl-C with typed text returns `""` (the line is cleared and the REPL skips empty lines); Ctrl-C on an empty line, or Ctrl-D, returns null (the REPL exits). History lives in memory, as in Clawcode; Up, Down and Ctrl-R come from JLine.
- **One reader for every question.** The REPL, the budget approver and lane D's tool approver all read through `console.readLine(prompt)`, so JLine alone owns stdin. Clawcode needs its `StdinPromptGate` because two readers share it.

```kotlin
interface CliConsole {
    val input: InputStream
    val out: PrintStream
    val err: PrintStream
    val interactive: Boolean

    /** The next line; "" when Ctrl-C cleared it; null at end of input or on Ctrl-C at an empty line. */
    fun readLine(prompt: String = ""): String?
}
```

### Ctrl-C (I4)

*(Diagram in the Claude Doc: Ctrl-C state machine · 5 states)*

Only a second Ctrl-C during a turn quits mid-turn; every other path returns to the prompt with the session saved.

- **Two kinds of Ctrl-C.** While JLine reads a line, Ctrl-C is a key press that JLine reports. During a turn, the terminal is in normal mode, so Ctrl-C reaches the JVM as SIGINT.
- **Signal handling through JLine.** `TurnInterrupts` registers for SIGINT with JLine's `Terminal.handle(Terminal.Signal.INT, …)` when a JLine terminal exists, or JLine's `org.jline.utils.Signals` otherwise (confirm at I4 that it wraps `sun.misc.Signal`). Butler code never touches `sun.misc` itself. The handler lives only for one turn, and the previous one is restored, so outside a turn SIGINT keeps the JVM's default.
- **First Ctrl-C: stop the turn.** The flag behind the turn's `TurnCancellation` is set, and "Interrupting… press Ctrl-C again to quit" goes to stderr. Lane A stops at its next step, writes "interrupted" results and saves. This matches Clawcode's `HookAbortMonitor`.
- **Second Ctrl-C before the turn returns: exit with code 130.** It is safe: the prompt was saved before the model call, and `RunTurnUseCase` already closes dangling tool calls on the next run.
- **Ctrl-C at an approval prompt** returns `""`, which both approvers treat as their default (stop, or deny), and also cancels the turn.
- **A one-shot ****`butler prompt`** uses the same handler. Proposed: a cancelled turn exits with 130; in JSON mode it prints the error envelope with kind `interrupted`.
- **Mid-stream limit.** Lane A polls cancellation between steps, so a long model response streams to its end first. Clawcode's streaming client polls its signal too; lane I proposes the contract change that would allow this (see Working with other lanes).

```kotlin
// cli/repl/TurnInterrupts.kt
class TurnInterrupts(private val console: CliConsole, private val signals: InterruptSignals) {
    /** Runs [turn] with Ctrl-C wired to its cancellation; exits with 130 on a second Ctrl-C. */
    fun <T> during(turn: (TurnCancellation) -> T): T
}

/** JLine-backed in production, a manual trigger in tests. */
fun interface InterruptSignals {
    fun onInterrupt(handler: () -> Unit): AutoCloseable
}
```

## Tasks

Four pull requests, branched as `lane/i/<task>`, matching the Tasks tab. I1, I2 and I3 can start together after `contracts-v1`; I4 needs I3. I2 now also needs M0.2, for `McpGateway`.

### I1 Tool calls on screen (S, needs M0.3)

1. Write `TerminalStyle`: colour when the console is interactive, `NO_COLOR` is unset and `butler.cli.color` is not `never`. Until I3 adds `CliProperties`, read only the first two.
2. Write `ToolCallRenderer` with `started(call): List<String>` and `finished(call, result): List<String>`, one branch per built-in tool plus the generic form, and Clawcode's caps and truncation notice.
3. In `StreamingTurnObserver`, end any half-printed text line, then print the renderer's lines for `ToolStarted` and `ToolFinished`; flush after each event.
4. Pass the style from `PromptActions`.
5. Tests in `ToolCallRendererTest`: one per tool for the start block and for success; an error over 160 characters; a 100-line read cut at 80 lines with the notice; an edit with three hunks showing two; output that is not JSON; colour off gives no escape codes. `StreamingTurnObserverTest`: text, tool block, text again, with the line breaks in the right places.

Done when: the renderer tests pass and a scripted turn against a fake shows each call and result once.

### I2 butler tools (S, needs M0.1 and M0.2)

1. Write `ListToolsUseCase` in core and its bean in `UseCaseConfig`.
2. Write `ToolEntryOutput` and `ToolListOutput` in `CliOutputs.kt`, `ToolCliMapper` and `ToolTextRenderer`.
3. Write `ToolsCommand` in the `version` command's pattern, with the global options mixin, and add it to `ButlerCommand`'s subcommands.
4. Tests: `ListToolsUseCaseTest` with `FakeMcpGateway` (built-ins first, then MCP tools, the flag passed through); a CLI test for text and JSON output, including the switched-off note.

Done when: `butler tools` and `butler tools --output-format json` print the six built-in tools with their tiers.

### I3 JLine line editor (M, needs nothing)

1. Add JLine to the version catalogue and the api module, as decided under Risks (proposed: the newest 4.4.x, `jdk11` variant).
2. Change `CliConsole.readLine` to take the prompt, with an empty default. `SystemCliConsole` prints the prompt and reads as before; drop its `@Component`.
3. Write `JLineCliConsole`: one system `Terminal` and one `LineReader` with in-memory history, built lazily on first use. Map JLine's `UserInterruptException` to `""` when the line had text and to null when it was empty; map `EndOfFileException` to null. `out` and `err` write through the terminal so JLine can redraw around them.
4. Write `CliProperties` (`butler.cli.line-editor`, `butler.cli.color`) and `CliConfig`, which builds the console bean.
5. Move the callers: `ReplRunner` passes `"> "` instead of printing it; `CliTurnApprover` passes `"Choice [s]: "`. Tell lane D to read the same way in `CliToolApprover`.
6. Tests: `JLineCliConsoleTest` drives a JLine terminal built over in-memory streams: typing and Enter, Ctrl-C with text, Ctrl-C on an empty line, Ctrl-D, Up recalls history. `CliConfigTest`: a non-interactive console always gets the plain reader.

Done when: every existing piped REPL and approval test passes unchanged, and the JLine tests pass.

### I4 Ctrl-C stops a turn (M, needs M0.3 and I3)

1. Write `InterruptSignals` over JLine (terminal signal handler, or `org.jline.utils.Signals` without a terminal), returning a handle that restores the previous handler.
2. Write `TurnInterrupts.during`: builds the turn's `TurnCancellation` from a flag; first interrupt sets it and prints the notice; a second one, before the turn returns, exits with 130; the handler is removed when the turn returns or throws.
3. Wrap each turn: `ReplRunner` and the one-shot prompt path both call `PromptActions.run` inside `during`, passing the cancellation into `TurnCallbacks`.
4. Make a Ctrl-C answer at an approval prompt also set the flag, through the same `TurnInterrupts`.
5. Add the keys to the REPL's `/help` text, in Clawcode's words where they apply.
6. Tests: `TurnInterruptsTest` with a manual `InterruptSignals`: one interrupt cancels, two exit with 130 (through an injectable exit function), the handler is restored after a normal turn and after an exception. A REPL test against a slow fake model: the interrupt arrives during a tool call, the turn ends with an "interrupted" result, and the next prompt in the same REPL runs on a valid transcript.

Done when: the interrupt test against a slow fake passes and the session resumes cleanly.

## Tests

Lane I tests run without a real terminal: formatting is pure, JLine runs over in-memory streams, and signals come from a manual trigger. Nothing depends on the machine's TTY, so the suite behaves the same in CI.

| Test class | Module | What it proves | Task |
| --- | --- | --- | --- |
| `ToolCallRendererTest` | api | Each tool's start block and result, the display caps, the truncation notice, plain output without colour | I1 |
| `StreamingTurnObserverTest` | api | Text and tool blocks interleave with correct line breaks | I1 |
| `ListToolsUseCaseTest` | core | Built-in tools, then MCP tools, and the switch passed through | I2 |
| `ToolsCommandTest` (in the CLI test style) | api | Text table, JSON shape, the switched-off note, exit code 0 | I2 |
| `JLineCliConsoleTest` | api | Enter, Ctrl-C with and without text, Ctrl-D and history, over a JLine terminal on in-memory streams | I3 |
| `CliConfigTest` | api | Piped input always gets the plain reader | I3 |
| Existing `CliEndToEndTest`, `PromptEndToEndTest`, `CliTurnApproverTest` | api | Nothing changes for piped use and approval prompts | I3 |
| `TurnInterruptsTest` | api | One interrupt cancels; two exit with 130; the handler is always restored | I4 |
| REPL interrupt test against a slow fake | api | The turn stops with an "interrupted" result and the next prompt works | I4, needs lane A's A2 and A6 |

Until lane A's loop merges, the I4 REPL test checks only that the cancellation reaches `RunTurnUseCase`; the full version joins J2's M2 check.

## Configuration

Lane I adds one small block, `butler.cli`, bound to `CliProperties` and registered in its own `CliConfig`. It holds display choices only, never secrets, and each value has an environment override through Spring's relaxed binding.

| Property | Values | Default | Environment variable |
| --- | --- | --- | --- |
| `butler.cli.line-editor` | `auto` (JLine on a terminal, plain otherwise), `jline`, `plain` | `auto` | `BUTLER_CLI_LINEEDITOR` |
| `butler.cli.color` | `auto` (colour on a terminal unless `NO_COLOR` is set), `always`, `never` | `auto` | `BUTLER_CLI_COLOR` |

`plain` is the escape hatch if JLine misbehaves in some terminal. `NO_COLOR` follows the common convention: any non-empty value turns colour off in `auto` mode.

## Working with other lanes

Lane I reads the turn's events and supplies its cancellation; it never calls a tool or decides a permission. Until other lanes merge, it tests with M0's events and fakes.

| Lane | What passes between them | When it matters |
| --- | --- | --- |
| A Agent loop | A emits `ToolStarted` and `ToolFinished` and polls the `TurnCancellation` lane I supplies. I4's full REPL test needs A2 and A6. | I1, I4 |
| C File tools | I1 reads lane C's JSON envelopes (Clawcode's keys). If lane C changes a key, `ToolCallRendererTest` breaks first. | I1 |
| D Permissions | D's `CliToolApprover` reads with `console.readLine(prompt)` from I3, and treats `""` from a Ctrl-C as deny. `CliTurnApprover` is touched by both lanes; I3 changes only its read call. | I3, D3 |
| F Sandboxed bash | I1 shows `$ <command>` and bash results in Clawcode's form once F's outcome format is settled; until then, the generic form. | I1, M4 |
| H MCP client | MCP tools appear in `butler tools` through `McpGateway.tools()` without lane I changing; the renderer shows them in the generic form. | I2, M6 |
| A and B: proposed contract change | To stop a response mid-stream, as Clawcode's streaming client does, `ModelGateway.stream` would take the `TurnCancellation` and the clients would close the stream when it is set. A small contract pull request, owned by A, implemented by B. | After M2 is fine |
| J Integration | J1 checks a `read_file` turn shows its call and result; J2 checks an interrupted turn resumes cleanly. | M1, M2 |

## Done when

Lane I is done for M1 when tool use is visible and listable, and for M2 when the line editor and Ctrl-C work. Tick these as they merge:

**For M1**

- [ ] I1 merged: tool calls and results on screen, with Clawcode's caps; plain output when piped
- [ ] I2 merged: `butler tools` in text and JSON, with tiers and the switched-off note
- [ ] J1's scripted `read_file` turn shows its call and its result

**For M2**

- [ ] I3 merged: JLine on terminals; every piped REPL and approval test unchanged
- [ ] Lane D's `CliToolApprover` reads through `readLine(prompt)`
- [ ] I4 merged: first Ctrl-C stops the turn, second exits with 130, handler always restored
- [ ] J2's interrupted turn resumes cleanly in the same REPL
- [ ] The decisions below are answered

## Risks and decisions to confirm

Five decisions shape what users see and need a yes before I1 and I3; the risks have a fix built into the tasks.

**Decisions**

- [ ] **JLine 4 rather than 3.** Proposed: the newest JLine 4.4.x, `jdk11` variant, since 4 is the active line and Butler runs on Java 21. The Tasks tab said JLine 3, which still gets maintenance releases (3.30.17 on 8 September).
- [ ] **No emoji in tool lines.** Clawcode prefixes lines with emoji (a page, a pencil, a magnifier). Proposed: keep its words, `✓`, `✗` and the frame, but drop the emoji, whose width differs between terminals and breaks alignment.
- [ ] **A second Ctrl-C exits with 130.** Clawcode documents only the first Ctrl-C during a turn. Proposed: the second one quits, so a turn stuck in a slow response can always be left.
- [ ] **Exit code and JSON for a cancelled one-shot prompt.** Proposed: exit 130, and in JSON mode the error envelope with kind `interrupted`.
- [ ] **Esc and Tab completion after M2.** Clawcode's Esc key also stops a turn, and Tab completes commands. Proposed: both wait until after M2, as an optional I5, so M2 is not held up.

**Risks**

| Risk | Effect | Built-in fix |
| --- | --- | --- |
| JLine misdetects a terminal (IDE consoles, Windows terminals) | Garbled prompt or no line editing | `butler.cli.line-editor=plain` restores today's reader |
| JNI terminal access on newer JDKs | Native-access warnings from Java 24 on | Butler targets Java 21; a later launcher can add `--enable-native-access` |
| SIGINT arrives while an approval prompt reads | Two handlers race for one key press | During a prompt JLine reads in raw mode, so Ctrl-C arrives as a key, not a signal; one `TurnInterrupts` owns the flag either way |
| Ctrl-C cannot stop a response mid-stream | The first Ctrl-C waits for the response to finish | The second Ctrl-C quits; the contract change in Working with other lanes removes the wait |
| Lane C changes an envelope key | Tool lines show less | `ToolCallRendererTest` fails first; the renderer falls back to the generic form |
