# Butler lane C: File tools

> Exported from the Claude Doc <https://claude.ai/artifact/WkhMqk3vYMaUc6L9no9FZX> on 2026-10-24. The Claude Doc is the original; if they differ, read [STATUS.md](../STATUS.md) first for what has changed since.


2026-10-21 · Chamantha De silva

## Goal

Lane C gives the model Clawcode's five file tools: `read_file`, `glob_search` and `grep_search` for gate M1, `write_file` and `edit_file` for gate M2. Each tool takes the same input and returns the same JSON envelope as in Clawcode `v0.1.7`. Each refuses any path that leaves the workspace and reports a failure as an error outcome the model can react to.

Lane C also owns turning the model's raw JSON input into a typed `ToolInvocation`. That covers all six built-in tools, so lane F's `bash` input is decoded here too.

At M1, a user can ask "what does this repo do?" and Butler reads and searches files on every provider. At M2, Butler can change files, once lane D's approval prompt has asked first.

## Scope

Lane C owns everything between "the model sent this JSON" and "this file changed" for the five file tools, and nothing about whether a tool may run.

**In lane C**

- C1: `ToolInputGatewayImpl`, which decodes and validates the input of all six built-in tools, including `bash`.
- C2: `read_file`, with Clawcode's line window, size cap and binary detection, plus symlink containment.
- C3: `glob_search` and `grep_search`, with all 14 `grep_search` fields, ignore rules, result caps and Clawcode's output envelopes.
- C4: `write_file`, written atomically, keeping the file's permissions.
- C5: `edit_file`, with exact matching, `replace_all`, and a unified diff as the outcome's preview.
- `LocalWorkspaceGatewayImpl`, which routes the five file invocations, and the `butler.workspace.*` settings.

**Left to other lanes**

- Whether a call may run, the approval prompt and the diff shown in it (D). Lane C only produces the diff.
- The loop that calls these tools and turns `InvalidToolInputException` into an error result (A).
- Running `bash` (F). Lane C decodes its input only.
- Sending tool schemas to providers (B) and showing tool calls on screen (I).
- `.gitignore` support, `read_file` selectors and notebook editing, all after v1.

## Starting point

Butler has no tool code today; after M0 it has the contracts, two stand-ins and fakes that lane C replaces. Clawcode keeps all five tools in one Rust file, `runtime/src/file_ops.rs`, which is the reference for every behaviour below.

**In Butler after ****`contracts-v1`**

| File | What lane C uses it for |
| --- | --- |
| core `domain/generic/WorkspacePath.kt` | `within(root, raw)`: the lexical containment check every path goes through |
| core `domain/tool/Tools.kt` | The invocations lane C produces and the `ToolOutcome` it returns |
| core `domain/tool/BuiltInTools.kt` | The six schemas; decoding must accept exactly these fields |
| core `gateway/ToolGateways.kt` | `ToolInputGateway` and `WorkspaceGateway`, the two ports lane C implements |
| secondary `gateway/impl/stub/UnavailableToolInputGatewayImpl.kt`, `UnavailableWorkspaceGatewayImpl.kt` | Stand-ins lane C deletes in C1 and C2 |
| secondary `config/WorkspaceConfig.kt` | Lane C's own config class: the only place its beans are wired |
| secondary `persistence/file/support/AtomicFiles.kt` | The temp-file-then-move pattern C4 copies into its own helper |
| core test fixtures `ToolDoubles.kt` | `RecordingWorkspaceGateway`, which lane A uses until lane C merges |

**In Clawcode ****`v0.1.7`**** (commit ****`22fe360`****)**

| Clawcode file and item | What it does | Lane C task |
| --- | --- | --- |
| `tools/src/lib.rs`: `ReadFileInput`, `WriteFileInput`, `EditFileInput`, `GlobSearchInputValue` (lines 2889 to 2915) and `from_value` (line 2302) | Decodes input with serde; unknown fields are ignored; errors are serde's own text, for example a missing field | C1 |
| `runtime/src/file_ops.rs`: `GrepSearchInput` | The 14 grep fields and their JSON names (`-B`, `-A`, `-C`, `-n`, `-i`, `type`) | C1, C3 |
| `file_ops.rs`: `read_file_with_selector`, `is_binary_file`, `MAX_READ_SIZE` | 10 MB cap; binary means a NUL byte in the first 8 KB; `offset` is a 0-based line index, `limit` a line count | C2 |
| `file_ops.rs`: `glob_search_impl`, `expand_braces`, `GLOB_SEARCH_IGNORED_DIRS` | Skips `.git`, `node_modules`, `.build`, `target`, `dist`, `coverage`; newest first; 100 results | C3 |
| `file_ops.rs`: `grep_search_impl`, `apply_limit` | Default mode `files_with_matches`; `head_limit` defaults to 250, and 0 means no limit; skips files that are not UTF-8 | C3 |
| `file_ops.rs`: `write_file`, `MAX_WRITE_SIZE` | 10 MB cap; creates parent folders; plain write, not atomic | C4 |
| `file_ops.rs`: `edit_file`, `make_patch` | Refuses identical strings and a missing match; without `replace_all` it replaces the first match; the patch lists all old lines then all new lines | C5 |
| `file_ops.rs`: `validate_workspace_boundary`, `is_symlink_escape` | Compares canonical paths with the canonical workspace root | C2 to C5 |
| `tools/src/lib.rs`: `run_read_file` to `run_grep_search` (lines 2569 to 2618) | Every result is the output struct as pretty-printed JSON | C2 to C5 |

The Tasks tab's 256 KB and 200-match numbers were proposals. Clawcode's are 10 MB and 100 or 250; Design says which lane C keeps.

## Where the code goes

All of lane C's code sits in secondary, in a new `workspace/` package plus two gateways; core gains nothing. Paths are under `secondary-adaptor/src/main/kotlin/com/suarvis/butler/secondary/` unless marked.

| File | New or changed | What it holds | Task | Owner |
| --- | --- | --- | --- | --- |
| `gateway/impl/ToolInputGatewayImpl.kt` | New | Decodes the six tools; replaces the input stand-in | C1 | C |
| `workspace/input/ToolInputs.kt` | New | `@Serializable` input DTOs with Clawcode's field names | C1 | C |
| `workspace/mapper/ToolInputWorkspaceMapper.kt` | New | DTO → `ToolInvocation`, including `WorkspacePath.within` | C1 | C |
| `gateway/impl/LocalWorkspaceGatewayImpl.kt` | New | Routes the five file invocations to their tool class; replaces the workspace stand-in | C2, grows in C3 to C5 | C |
| `workspace/output/ToolOutputs.kt` | New | `@Serializable` envelopes with Clawcode's JSON keys | C2 to C5 | C |
| `workspace/support/PathGuard.kt` | New | Real-path containment for existing files and for files about to be created | C2 | C |
| `workspace/support/TextFiles.kt` | New | Size cap, binary check, strict UTF-8 reading | C2 | C |
| `workspace/tool/ReadFileTool.kt` | New | `read_file` | C2 | C |
| `workspace/support/SearchWalker.kt` | New | Folder walk: ignored folders, no link following, per-file containment | C3 | C |
| `workspace/support/GlobPatterns.kt` | New | Brace expansion and glob matching | C3 | C |
| `workspace/tool/GlobSearchTool.kt`, `GrepSearchTool.kt` | New | The two search tools | C3 | C |
| `workspace/support/AtomicWrite.kt` | New | Temp file, then atomic move, keeping POSIX permissions | C4 | C |
| `workspace/tool/WriteFileTool.kt` | New | `write_file` | C4 | C |
| `workspace/support/UnifiedDiff.kt` | New | Unified diff for previews and patches | C5 | C |
| `workspace/tool/EditFileTool.kt` | New | `edit_file` | C5 | C |
| `config/WorkspaceConfig.kt` | Changed | Wires the two gateways and `WorkspaceProperties` | C1, C2 | C |
| `config/WorkspaceProperties.kt` | New | `butler.workspace.*` | C2 | C |
| `gateway/impl/stub/UnavailableToolInputGatewayImpl.kt`, `UnavailableWorkspaceGatewayImpl.kt` | Deleted | The M0 stand-ins | C1, C2 | C |
| api test `architecture/ArchitectureTest.kt` | Changed, append-only | Two rules: workspace mappers end in `WorkspaceMapper`; `workspace..input` and `workspace..output` never touch core | C1 | Shared |

Tests mirror the main tree under `secondary-adaptor/src/test/kotlin/.../secondary/`, one test class per tool plus `ToolInputGatewayImplTest` and `PathGuardTest`, all on JUnit's `@TempDir`.

## Design

Lane C copies Clawcode's inputs, outputs and limits, and departs from it in five places, each for safety or correctness: real-path checks during searches, atomic writes, refusing ambiguous edits, a real diff, and one ignore list for both searches. Each departure is listed under Risks and decisions to confirm.

### Decoding (C1)

- **Same field names and leniency as Clawcode.** The DTOs use Clawcode's JSON names (`old_string`, `-B`, `type`) through `@SerialName`. The `Json` instance sets `ignoreUnknownKeys = true`, because Clawcode's serde structs ignore unknown fields too.
- **Every error names the field.** A missing field, a wrong type or a value under the schema's minimum (`offset` ≥ 0, `limit` ≥ 1, `timeout` ≥ 1) becomes `InvalidToolInputException(tool, "<field>: <problem>")`. Lane A turns it into an error result.
- **Paths are checked while decoding.** `WorkspacePath.within(root, raw)` turns relative and absolute paths into a workspace path; null becomes `"path: <raw> is outside the workspace"`. Clawcode refuses such paths too, in `validate_workspace_boundary`.
- **Names that are not built-in pass through** as `ToolInvocation.External(name, input)`, so lane A routes them to MCP without lane C knowing MCP exists.

Decoding needs the workspace root to resolve absolute paths, which the M0 port does not pass. Lane C proposes one additive contract change before C1:

```kotlin
interface ToolInputGateway {
    /** @throws InvalidToolInputException naming the field that is wrong, or a path outside [workspace] */
    fun decode(workspace: WorkspaceRoot, name: ToolName, input: JsonText): ToolInvocation
}
```

### Path safety

Every path passes two checks before any file is touched. The lexical check runs once, while decoding. The real-path check runs in the adaptor, because only the adaptor touches the disk and can see symlinks.

*(Diagram in the Claude Doc: path safety · two checks, then the tool's own rules)*

A path that fails any check never reaches the disk operation; the model gets an error result naming the reason.

```kotlin
// workspace/support/PathGuard.kt
internal class PathGuard(root: WorkspaceRoot) {
    /** Real path of an existing file or folder, or null when a symlink leads outside the workspace. */
    fun existing(path: WorkspacePath): Path?

    /** Where a write lands: the real path of the nearest existing ancestor plus the rest, or null when outside. */
    fun forWrite(path: WorkspacePath): Path?
}
```

Searches walk folders without following links, as Clawcode's `walkdir` does by default, and check each candidate's real path. Clawcode fails the whole search when one file escapes; lane C skips that file instead, so one stray link cannot hide every other result.

### Outputs

Each tool returns Clawcode's envelope as pretty-printed JSON, with the same keys, so a model used to Clawcode reads familiar results. Paths in outputs are absolute, as in Clawcode; the model may send them back, and `within` accepts them.

| Tool | Envelope keys (Clawcode `file_ops.rs`) |
| --- | --- |
| `read_file` | `type` ("text"), `file` { `filePath`, `content`, `numLines`, `startLine`, `totalLines` }, `truncationFooter` |
| `glob_search` | `durationMs`, `numFiles`, `filenames`, `truncated` |
| `grep_search` | `mode`, `numFiles`, `filenames`, `content`, `numLines`, `numMatches`, `appliedLimit`, `appliedOffset` |
| `write_file` | `type` ("create" or "update"), `filePath`, `content`, `structuredPatch`, `originalFile`, `gitDiff` |
| `edit_file` | `filePath`, `oldString`, `newString`, `originalFile`, `structuredPatch`, `userModified`, `replaceAll`, `gitDiff` |

### read_file (C2)

- `offset` is a 0-based line index and `limit` a line count; `startLine` is `offset + 1`. Content is the selected lines joined with `\n`, without line numbers, as in Clawcode.
- Files over `butler.workspace.read.max-bytes` (10 MB, Clawcode's `MAX_READ_SIZE`) are refused: "file is too large (N bytes, max M bytes)".
- A NUL byte in the first 8 KB means binary: "file appears to be binary". Text that is not valid UTF-8 is refused too, as Clawcode's `read_to_string` does.
- Proposed addition: returned text over `butler.workspace.read.max-output-bytes` (256 KB, the Tasks tab's number) stops at the last whole line and fills Clawcode's `truncationFooter` with the next `offset` to ask for. Without it, one large file can overflow the model's context.

### glob_search and grep_search (C3)

- **Pure JVM, not ripgrep.** Butler stays one self-contained JVM with no binary to install or find, and Clawcode also searches in-process (`regex` plus `walkdir`). Java's regex can backtrack where Rust's cannot, so each file's match runs under a deadline (`butler.workspace.grep.file-timeout`); a file that hits it is skipped and counted.
- **One ignore list for both tools.** Clawcode skips `.git`, `node_modules`, `.build`, `target`, `dist` and `coverage` in `glob_search` only. Lane C applies that list to both tools and adds `build`, `.gradle` and `.butler`, so searches skip Gradle output and saved sessions.
- **glob_search:** the pattern is relative to `path` or to the workspace. Java's `glob:` syntax handles one level of braces, as Clawcode's `expand_braces` does. Results are sorted newest-modified first and capped at 100, with `truncated` set, as in Clawcode.
- **grep_search:** matching is line by line, as in Clawcode. Modes are `files_with_matches` (default), `content` (`path:line:text`, line numbers on unless `-n` is false) and `count`. `context` or `-C` sets both sides; `-B` and `-A` override one side. `type` matches the file extension exactly. `head_limit` defaults to 250, and 0 means no limit; `offset` skips results first. `multiline` sets dot-matches-newline, which, as in Clawcode, matters little with line-by-line matching. Files that are not UTF-8 are skipped.

### write_file (C4)

- Content over 10 MB (Clawcode's `MAX_WRITE_SIZE`) is refused. Missing parent folders are created, only inside the workspace.
- The write is atomic: a temp file in the same folder, then an atomic move, falling back to a plain replace where the file system has no atomic move, as `AtomicFiles` does. Clawcode writes in place, so an interrupted write can leave half a file.
- The new file keeps the old file's POSIX permissions, so an executable script stays executable. A symlink inside the workspace is written through to its target, never replaced by a regular file.

### edit_file (C5)

- Clawcode's two refusals keep their wording: "old_string and new_string must differ" and "old_string not found in file".
- Proposed change: without `replace_all`, more than one match is refused ("old_string matches N places; include more context or set replace_all"). Clawcode silently edits the first match, which can change the wrong place.
- The replacement works on the raw text, so line endings and the final newline stay as they were. The file is then written with `AtomicWrite`.
- `structuredPatch` holds real diff hunks with Clawcode's keys. Clawcode's `make_patch` lists every old line, then every new line.

### Preview for the approval prompt

Lane D's prompt shows the change before it happens, but `ToolOutcome.preview` only exists after the run. Lane C proposes a second additive contract change, so lane A can ask for the diff before asking the user:

```kotlin
interface WorkspaceGateway {
    fun run(workspace: WorkspaceRoot, invocation: ToolInvocation): ToolOutcome

    /** A unified diff of what [invocation] would change, or null for tools that change nothing. Never writes. */
    fun preview(workspace: WorkspaceRoot, invocation: ToolInvocation): String? = null
}
```

## Tasks

Six pull requests, branched as `lane/c/<task>`: the five tasks from the Tasks tab, plus a small contract change C0 that this doc adds. C2 and C3 can run side by side after C1; C4 and C5 can too.

### C0 Contract change (S, needs `contracts-v1`)

1. Add the `workspace` parameter to `ToolInputGateway.decode` and the default `preview` method to `WorkspaceGateway`, as in Design.
2. Update the stand-ins and the fakes in `ToolDoubles.kt` to match; `RecordingWorkspaceGateway` gets a scripted preview.
3. Ask lane A (the only caller) and lane D (the preview's reader) to review; update the Contracts tab in the same pull request.

Done when: merged, with the build green and no behaviour change.

### C1 Input decoding (M, needs C0)

1. Write `workspace/input/ToolInputs.kt`: one `@Serializable` class per tool with Clawcode's names. `GrepSearchInput` maps `-B`, `-A`, `-C`, `-n`, `-i` and `type` with `@SerialName`; every field the schema marks optional is nullable.
2. Write `ToolInputWorkspaceMapper`: DTO to `ToolInvocation`, checking the schema minimums and calling `WorkspacePath.within` for `path` fields. `bash` maps `timeout` milliseconds to a `Duration`.
3. Write `ToolInputGatewayImpl.decode`: built-in names are decoded; other names become `External`. Catch `SerializationException` and rethrow as `InvalidToolInputException` with the field name in front.
4. Wire it in `WorkspaceConfig`, delete `UnavailableToolInputGatewayImpl`, and add the two ArchUnit rules.
5. Tests in `ToolInputGatewayImplTest`, one or more per tool: a valid input decodes to the expected invocation; a missing `path`, a string `limit`, `limit: 0` and `../x` each give an error naming the field; an unknown extra field is ignored; `mcp__fs__read` passes through.

Done when: bad input names the wrong field, for every one of the six tools.

### C2 read_file and the gateway (M, needs C1)

1. Write `PathGuard` and `PathGuardTest`: a file, a folder, a link inside pointing inside (allowed), a link pointing outside (refused), and a new file under a linked folder that leads outside (refused).
2. Write `TextFiles`: size check from file attributes before reading, the NUL check on the first 8 KB, strict UTF-8 decoding.
3. Write `ReadFileTool` and `ReadFileOutput` with Clawcode's line window, plus the proposed output cap with `truncationFooter`.
4. Write `LocalWorkspaceGatewayImpl.run`, routing `ReadFile` and answering the not-yet-built tools with an error outcome. Add `WorkspaceProperties`, wire both in `WorkspaceConfig`, and delete `UnavailableWorkspaceGatewayImpl`.
5. Tests in `ReadFileToolTest`, one per rule: line window at the start, middle and past the end; empty file; 10 MB plus one byte; a NUL byte; invalid UTF-8; CRLF lines; the output cap and its footer; a link escape.

Done when: every rule has its own temp-folder test, and the envelope's keys match Clawcode's.

### C3 glob_search and grep_search (M, needs C1)

1. Write `SearchWalker`: walks from a start folder with `Files.walkFileTree`, never follows links, prunes ignored folder names, and checks each file with `PathGuard`.
2. Write `GlobPatterns` and `GlobSearchTool`: relative and absolute patterns, braces, newest first, cap 100 with `truncated`.
3. Write `GrepSearchTool`: compile the pattern with `CASE_INSENSITIVE` for `-i` and `DOTALL` for `multiline`; apply `glob` and `type` filters; run the three modes; apply `offset` then `head_limit`; enforce the per-file deadline with a `CharSequence` wrapper that checks the clock.
4. Route both invocations in `LocalWorkspaceGatewayImpl`.
5. Tests: each ignored folder is skipped; a link out of the workspace is skipped, not followed; braces match; sorting is by modified time; 101 files give 100 and `truncated: true`; each grep mode; context with `-C`, `-B` and `-A`; `-n` false; `type` and `glob` filters; `head_limit` 0 and 1; `offset`; a catastrophic pattern hits the deadline and the search still returns.

Done when: the temp-folder tests pass and a search over Butler's own repository finishes in a few seconds.

### C4 write_file (S, needs C2)

1. Write `AtomicWrite`: temp file in the target's folder, copy the old file's POSIX permissions when the file system supports them, atomic move with a plain-replace fallback, temp file removed on every path.
2. Write `WriteFileTool`: size cap, `PathGuard.forWrite`, parents created, `create` or `update` in the envelope, diff hunks in `structuredPatch`.
3. Add the `preview` branch: the diff between the current file (or nothing) and the new content, without writing.
4. Tests: create, update, new parent folders, a write through an outside link refused, an executable file stays executable, a link inside the workspace is written through. For the interrupted write, make the move fail with a test seam and check that the old content is intact.

Done when: an interrupted write leaves the old file intact.

### C5 edit_file (M, needs C2)

1. Write `UnifiedDiff`: hunks with 3 lines of context, rendered as text for previews and as Clawcode-shaped hunks for `structuredPatch`.
2. Write `EditFileTool`: identical strings, no match and several matches without `replace_all` are refused before anything is written; otherwise replace, then write with `AtomicWrite`.
3. Add the `preview` branch: the same checks and the same diff, without writing.
4. Tests: single match; `replace_all` over three matches; no match and two matches leave the file byte-identical; CRLF and a missing final newline are kept; the preview equals the diff of the real edit.

Done when: no match or two matches give an error and leave the file untouched.

## Tests

Every lane C test runs against real files in a JUnit `@TempDir`, with no Spring context and no network, so the whole lane's suite stays fast. Symlink tests are skipped where the file system cannot create links.

| Test class (secondary) | What it proves | Task |
| --- | --- | --- |
| `ToolInputGatewayImplTest` | Each tool's input decodes; each bad field is named; unknown names pass through | C1 |
| `ArchitectureTest` (api, two new rules) | Workspace mappers are named alike; input and output DTOs stay free of core types | C1 |
| `PathGuardTest` | Links inside are followed, links outside are refused, for reads and for new files | C2 |
| `ReadFileToolTest` | Line window, size cap, binary and UTF-8 checks, output cap, envelope keys | C2 |
| `GlobSearchToolTest` | Ignore list, link skipping, braces, sort order, 100-result cap | C3 |
| `GrepSearchToolTest` | Three modes, context flags, filters, limits, offset, the regex deadline | C3 |
| `AtomicWriteTest` | Permissions kept; an interrupted move leaves the old file | C4 |
| `WriteFileToolTest` | Create and update, parent folders, preview without writing | C4 |
| `UnifiedDiffTest` | Hunks and context lines for insertions, deletions and changes at the file's edges | C5 |
| `EditFileToolTest` | Single and `replace_all` edits, the three refusals, line endings kept, preview equals the real diff | C5 |
| `LocalWorkspaceGatewayImplTest` | Each invocation reaches its tool; an unexpected invocation (`Bash`, `External`) gives an error outcome | C2 to C5 |

Lane C writes no end-to-end test of its own; J1 and J2 drive these tools through the real CLI.

## Configuration

Lane C adds one block, `butler.workspace`, bound to `WorkspaceProperties` and registered in `WorkspaceConfig`, so `SecondaryAdaptorConfig` stays untouched. It holds limits only, never secrets. Each value can be overridden by an environment variable through Spring's relaxed binding.

| Property | Default | Source of the default | Environment variable |
| --- | --- | --- | --- |
| `butler.workspace.read.max-bytes` | `10MB` | Clawcode `MAX_READ_SIZE` | `BUTLER_WORKSPACE_READ_MAXBYTES` |
| `butler.workspace.read.max-output-bytes` | `256KB` | Proposal (Tasks tab) | `BUTLER_WORKSPACE_READ_MAXOUTPUTBYTES` |
| `butler.workspace.write.max-bytes` | `10MB` | Clawcode `MAX_WRITE_SIZE` | `BUTLER_WORKSPACE_WRITE_MAXBYTES` |
| `butler.workspace.glob.max-results` | `100` | Clawcode `glob_search_impl` | `BUTLER_WORKSPACE_GLOB_MAXRESULTS` |
| `butler.workspace.grep.default-head-limit` | `250` | Clawcode `apply_limit` | `BUTLER_WORKSPACE_GREP_DEFAULTHEADLIMIT` |
| `butler.workspace.grep.file-timeout` | `2s` | Proposal | `BUTLER_WORKSPACE_GREP_FILETIMEOUT` |
| `butler.workspace.ignored-directories` | `.git, node_modules, .build, target, dist, coverage, build, .gradle, .butler` | Clawcode's six, plus three proposed | `BUTLER_WORKSPACE_IGNOREDDIRECTORIES` (comma-separated) |

The byte sizes bind to Spring's `DataSize` and the timeout to `Duration`, so `10MB` and `2s` work as written.

## Working with other lanes

Lane C depends on no other lane's code: it implements two ports and is tested on real files. Other lanes depend on it only through those ports, and use the M0 fakes until lane C merges.

| Lane | What passes between them | When it matters |
| --- | --- | --- |
| A Agent loop | A calls `decode`, then `run`, and turns `InvalidToolInputException` into an error result. Until C1 and C2 merge, A uses `FakeToolInputGateway` and `RecordingWorkspaceGateway`. C0 changes A's call to `decode`, so A reviews C0. | C0, M1 |
| D Permissions | D reads each tool's tier from `BuiltInTools` and asks `preview` for the diff it shows. Path patterns such as `write_file(src/**)` match `WorkspacePath.value`: relative, `/`-separated, never starting with `./`. | C0, M2 |
| F Sandboxed bash | Lane C decodes `bash` input into `ToolInvocation.Bash`. A new `bash` field needs one contract pull request that changes the schema in `BuiltInTools` and lane C's `BashInput` together. | Any time F changes the input |
| I Terminal | I shows each `ToolFinished` result. Outcomes are Clawcode's JSON envelopes, so a short summary line would need a new optional field, agreed through a contract change. | I1 |
| B Provider tools | B sends the `BuiltInTools` schemas; lane C reads no field the schema does not list. | M1 |
| J Integration | J1 runs a scripted `read_file` turn on all three provider fakes; J2 runs an approved edit and a denied edit. | M1 needs C1, C2; M2 needs C4, C5 |

## Done when

Lane C is done for M1 when the read tools work through the real gateways, and for M2 when the write tools do. Tick these as they merge:

**For M1**

- [ ] C0 merged: `decode` takes the workspace; `WorkspaceGateway.preview` exists
- [ ] C1 merged: all six tools decode; bad fields are named; input stand-in deleted
- [ ] C2 merged: `read_file` with every rule tested; workspace stand-in deleted
- [ ] C3 merged: both searches, ignore list, caps and the regex deadline
- [ ] J1's scripted `read_file` turn passes on the three provider fakes

**For M2**

- [ ] C4 merged: atomic `write_file`; permissions kept; preview without writing
- [ ] C5 merged: `edit_file` refuses ambiguous edits; preview equals the real diff
- [ ] J2's approved and denied edits pass
- [ ] The decisions below are answered, and the Contracts tab reflects C0

## Risks and decisions to confirm

Six decisions change behaviour compared with Clawcode and need a yes before C2; the risks have a fix built into the tasks.

**Decisions**

- [ ] **Refuse ambiguous edits.** Proposed: without `replace_all`, two or more matches are refused. Clawcode edits the first match.
- [ ] **Cap returned text at 256 KB** with Clawcode's `truncationFooter`, on top of Clawcode's 10 MB refusal. The alternative is Clawcode's behaviour: the whole file, up to 10 MB.
- [ ] **One ignore list for both searches**, with `build`, `.gradle` and `.butler` added. Clawcode ignores folders in `glob_search` only.
- [ ] **Glob semantics.** Java's `glob:` matches `*` within one folder and `**` across folders. Clawcode's `glob` crate, with its default options, lets `*` cross folders too (confirm against Clawcode's `glob_search_impl` tests). Proposed: Java's rules, which match what models usually expect.
- [ ] **Drop the echoes in write results.** Clawcode's `write_file` and `edit_file` envelopes repeat the full new and old file in `content` and `originalFile`. Proposed: leave both out, since the model already has them and a large file would fill the context twice.
- [ ] **Diff code.** Proposed: a small Myers diff in `UnifiedDiff`, about 150 lines, so no new dependency. The alternative is `java-diff-utils`; its current version could not be checked from here and would be confirmed at C5.

**Risks**

| Risk | Effect | Built-in fix |
| --- | --- | --- |
| Java regex backtracks on a hostile pattern | A search hangs the turn | Per-file deadline in C3, tested with a catastrophic pattern |
| A file changes between the check and the write | A symlink swapped in at the last moment could redirect a write | Accepted for a local, single-user tool; `forWrite` runs right before the move |
| Atomic move across file systems | `ATOMIC_MOVE` fails | The temp file sits in the target's own folder; fallback to a plain replace, as `AtomicFiles` does |
| C0 changes a port after `contracts-v1` | Lane A rebases | Additive change, merged first, reviewed by A and D |
| Clawcode's tools change in a later release | Inputs or envelopes drift | J re-checks `file_ops.rs` at each gate; this doc names commit `22fe360` |
