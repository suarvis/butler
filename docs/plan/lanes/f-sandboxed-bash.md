# Butler lane F: Sandboxed bash

> Exported from the Claude Doc <https://claude.ai/artifact/DdZtFkkKjcmiNE8rMaQhVw> on 2026-10-24. The Claude Doc is the original; if they differ, read [STATUS.md](../STATUS.md) first for what has changed since.


2026-10-21 · Chamantha De silva

## Goal

Lane F lets the model run shell commands safely: each command is classified by risk, run inside an OS sandbox that keeps writes in the workspace, stopped on time, and cleaned up completely. It lands at gate M4, where J checks that a write outside the workspace is blocked on macOS and that a timed-out command leaves no processes behind.

What lane F delivers:

- `BashRiskClassifier`: one classifier in core, replacing the three overlapping ones in Clawcode.
- `SandboxPolicy` and a macOS `sandbox-exec` sandbox. Clawcode has no enforcement on macOS at all; its ROADMAP says so.
- A Linux bubblewrap sandbox. Where no sandbox works, only strictly read-only commands run without approval.
- `CommandGatewayImpl`: timeout, a head-and-tail output cap, the whole process tree killed, and provider API keys removed from the child's environment.
- `butler sandbox`: a status report modelled on `claw sandbox`.

Size: four M tasks and one S, about two weeks for one person. F1, F2 and F4 can start in parallel once `contracts-v1` is tagged.

## Scope

Lane F owns judging a command's risk and running it contained; other lanes own the decision, the loop and the display.

**In lane F**

- F1: `BashRiskClassifier` and the `BashRisk` contract it returns.
- F2: `SandboxPolicy`, `SandboxStatus` and the macOS `sandbox-exec` launcher.
- F3: the Linux bubblewrap launcher, the no-sandbox launcher and the detector that picks one.
- F4: `CommandGatewayImpl`: environment, launch, output cap, timeout, cancellation, process-tree kill, outcome.
- F5: `butler sandbox`, and the command-and-risk preview the approval prompt shows.

**Left to other lanes**

- Turning a risk into allow, ask or deny, and the prompt itself (D).
- Calling the classifier and the gateway inside the tool loop, and passing the turn's cancellation (A).
- Showing a bash call while it runs, and Ctrl-C (I).
- Background commands and sandbox overrides chosen by the model. M0 left both out of the `bash` schema; v1 does not add them.
- Windows. It is not a v1 target; on any system without a working sandbox the approval fallback applies.

## Starting point

Butler runs no processes today; after M0 it has the port, the input type and a stand-in. Clawcode gives good command lists and process code to port, but its sandbox does not stop writes outside the workspace on either platform.

**In Butler (after ****`contracts-v1`****)**

| File | What it holds today | What lane F does |
| --- | --- | --- |
| core `gateway/ToolGateways.kt` | `CommandGateway.run(workspace, command, sandbox): ToolOutcome` | F4 implements it |
| core `domain/tool/Tools.kt`, `BuiltInTools.kt` | `ToolInvocation.Bash(command, timeout, description)`; `bash` at danger-full-access | F1 supplies a per-command tier instead |
| core `domain/shell/SandboxPolicy.kt` | `SandboxPolicy(writableRoots, network)` | F2 adds a factory and `SandboxStatus` |
| secondary `gateway/impl/stub/UnavailableCommandGatewayImpl.kt`, `config/CommandConfig.kt` | "not available yet" | F4 replaces both |
| core test fixtures `RecordingCommandGateway` | Records invocations, returns scripted outcomes | Lanes A and I test against it, not against F |
| secondary `config/ModelProperties.kt` | `butler.providers.*.api-key` values | F4 reads them to strip keys from the child environment |

**In Clawcode (commit ****`22fe360`****, v0.1.7)**

| File | What to take from it | Task |
| --- | --- | --- |
| `tools/src/lib.rs`, `classify_bash_permission` (around line 2309) | The tier Clawcode actually uses at run time: a short read-only list gets workspace-write, everything else danger-full-access | Replaced by F1 |
| `runtime/src/permission_enforcer.rs`, `is_read_only_command` | The strict read-only check: rejects shell operators, interpreters, mutating git subcommands and `find` actions | F1 |
| `runtime/src/bash_validation.rs` | Eight command intents and their lists, destructive patterns, path warnings, `extract_first_command`. Public, but nothing outside the file calls it in this commit | F1 |
| `runtime/src/bash.rs` | `sh -lc`, stdin closed, timeout in milliseconds with no default, 16,384-byte head-only cap, exit codes reported as data | F4 |
| `runtime/src/sandbox.rs` | The Linux `unshare` launcher and its start-up probe; container detection | F3, F5 |
| `rusty-claude-cli/src/main.rs`, `format_sandbox_report` (around line 10407) | The fields of `claw sandbox` | F5 |
| `ROADMAP.md` (around lines 1488 to 1510) | Notes there is no enforcement outside Linux and proposes Seatbelt (`sandbox-exec`) for macOS | F2 |

The key finding: Clawcode's `unshare` launcher creates namespaces but binds nothing read-only, and passes its filesystem mode to the child only as an environment variable (`CLAWD_SANDBOX_FILESYSTEM_MODE`). A write outside the workspace therefore still succeeds on Linux too. M4's check needs real enforcement, hence bubblewrap and `sandbox-exec`.

## Where the code goes

The judgement lives in core as pure code; everything that touches processes lives in secondary's approved `sandbox/` folder and the gateway. Paths follow the M0 doc's abbreviations.

| File | Module | New or changed | Task | Owner |
| --- | --- | --- | --- | --- |
| `domain/shell/BashRisk.kt` | core | New: `CommandIntent`, `BashRisk`, `describe()` for the prompt preview (contract shared with D) | F1, F5 | F |
| `domain/shell/BashRiskClassifier.kt` | core | New | F1 | F |
| `domain/shell/CommandWords.kt` | core | New: first command, shell operators, segments | F1 | F |
| `domain/shell/SandboxPolicy.kt` | core | Changed: `forWorkspace` factory | F2 | F |
| `domain/shell/SandboxStatus.kt` | core | New: `SandboxMechanism`, `SandboxStatus` | F2 | F |
| `usecase/shell/GetSandboxStatusUseCase.kt` | core | New | F5 | F |
| `sandbox/SandboxLauncher.kt` | secondary | New: the launcher interface | F2 | F |
| `sandbox/MacSandboxLauncher.kt` and `resources/sandbox/butler.sb` | secondary | New: `sandbox-exec` and its profile | F2 | F |
| `sandbox/BubblewrapLauncher.kt`, `sandbox/NoSandboxLauncher.kt`, `sandbox/SandboxDetector.kt` | secondary | New | F3 | F |
| `sandbox/support/ChildEnvironment.kt`, `BoundedOutput.kt`, `ProcessTree.kt` | secondary | New | F4 | F |
| `gateway/impl/CommandGatewayImpl.kt` | secondary | New; the stand-in under `stub/` is deleted | F4 | F |
| `config/CommandConfig.kt`, `config/BashProperties.kt` | secondary | Changed and new | F4 | F |
| `cli/command/SandboxCommand.kt`, `cli/mapper/SandboxCliMapper.kt` | api | New: `butler sandbox` | F5 | F |
| `cli/command/ButlerCommand.kt`, `cli/dto/CliOutputs.kt` | api | Changed: register the command, `SandboxOutput` | F5 | Shared, append-only |
| `config/UseCaseConfig.kt`, `src/main/resources/application.yml` | api | Changed: the use case bean, the `butler.bash` block | F4, F5 | Shared, append-only |

The launcher interface is internal to secondary: core never sees how a sandbox is built, only the policy it asked for and the status it got.

## Design

Every bash call is judged in core and run in secondary. Core decides how risky the command is; secondary decides how to contain it, and always cleans up after it.

*(Diagram in the Claude Doc: one bash call · judged in core, run in secondary)*

Read it left to right, then down: nothing reaches the operating system before the classifier and lane D's policy have agreed, and every path out of the process ends in an outcome the model can read.

### One risk classifier instead of three

Clawcode has three bash classifiers that disagree, and only the loosest one runs. F1 merges them into one pure core object and keeps their command lists.

```kotlin
// domain/shell/BashRisk.kt (contract shared with lane D)
enum class CommandIntent { READ_ONLY, WRITE, DESTRUCTIVE, NETWORK, PROCESS_MANAGEMENT, PACKAGE_MANAGEMENT, SYSTEM_ADMIN, UNKNOWN }
data class BashRisk(val intent: CommandIntent, val tier: PermissionTier, val warning: String? = null)

// domain/shell/BashRiskClassifier.kt
object BashRiskClassifier {
    fun classify(command: String, workspace: WorkspaceRoot, sandboxActive: Boolean): BashRisk
}
```

1. **Strict read-only check**, ported from `is_read_only_command`: no shell operators (`; | & $` backticks, redirects, brackets, newlines), a first word on its read-only list, only non-mutating git subcommands, `find` without `-exec` or `-delete`, no `-i` or `--in-place`. A pass means `READ_ONLY` at the read-only tier.
2. **Intent**, ported from `classify_command`: the first command after any `KEY=value` prefixes and `sudo`. Butler adds one rule: a command with operators is split at `|`, `;`, `&&` and `||`, and the riskiest segment decides; `$(` or a backtick makes it `UNKNOWN`.
3. **Tier from intent and sandbox**, as below.
4. **Warnings**, ported from `check_destructive` and `validate_paths`, with Clawcode's message texts, for example `Destructive command detected: Recursive forced deletion at root — this will destroy the system`. A write command naming a system folder (`/etc/`, `/usr/` and the others in `command_targets_outside_workspace`) moves to danger-full-access.

| Intent | Examples from Clawcode's lists | Tier with a sandbox | Tier without |
| --- | --- | --- | --- |
| Read-only, strict check passed | `ls`, `cat`, `grep`, `git status` | read-only | read-only |
| Read-only, with operators | `cat a.txt \| wc -l` | workspace-write | danger-full-access |
| Write | `cp`, `mv`, `mkdir`, `tee`, `sed -i`, `git commit` | workspace-write | danger-full-access |
| Destructive | `rm`, `shred`, `wipefs`, `mkfs`, `dd if=` | danger-full-access, with warning | danger-full-access, with warning |
| Network, process, package, system admin | `curl`, `kill`, `npm`, `sudo` | danger-full-access | danger-full-access |
| Unknown | `./gradlew build`, `python x.py` | danger-full-access | danger-full-access |

Without a sandbox, only the strict read-only commands run without approval in `workspace-write` mode. That is the Tasks tab's "approval for every command" with one exception, listed under decisions.

### Sandbox policy and status

The policy says what a command may touch; a launcher enforces it with the operating system and reports what it managed.

```kotlin
// domain/shell/SandboxPolicy.kt (M0 type; F2 adds the factory)
fun SandboxPolicy.Companion.forWorkspace(workspace: WorkspaceRoot, extraWritable: List<Path> = emptyList(), network: Boolean = true): SandboxPolicy

// domain/shell/SandboxStatus.kt (new)
enum class SandboxMechanism { SANDBOX_EXEC, BUBBLEWRAP, NONE }
data class SandboxStatus(
    val mechanism: SandboxMechanism,
    val active: Boolean,
    val network: Boolean,
    val writableRoots: List<Path>,
    val inContainer: Boolean,
    val fallbackReason: String?,
)
```

By default the writable roots are the workspace and a temp folder created for each run, and the network stays on, as Clawcode's `network_isolation` defaults to off. Both are configurable.

### macOS: sandbox-exec

Clawcode's ROADMAP records that macOS has no enforcement and proposes Seatbelt, noting it is deprecated but still working. Butler wraps the shell in `sandbox-exec -p <profile> -D WORKSPACE=<path> -D TMP=<path> /bin/sh -lc <command>` with this proposed profile:

```text
(version 1)
(allow default)
(deny file-write*)
(allow file-write*
    (subpath (param "WORKSPACE"))
    (subpath (param "TMP"))
    (literal "/dev/null")
    (literal "/dev/tty"))
; added when the policy turns the network off:
(deny network*)
```

- Paths go in as real paths: macOS shows `/tmp` and `/var` under `/private`, and a rule on the short form silently matches nothing.
- Confirm the profile and the deprecation note on your Mac (`man sandbox-exec`); F2's macOS-only test is the proof.

### Linux: bubblewrap, then approval

Bubblewrap gives what Clawcode's `unshare` launcher lacks, a read-only view of everything except the writable roots:

```text
bwrap --ro-bind / / --bind <workspace> <workspace> --bind <tmp> <tmp> --dev /dev --proc /proc \
      --unshare-pid --die-with-parent [--unshare-net] --chdir <workspace> /bin/sh -lc <command>
```

- Probe once at start-up with a trivial command, as Clawcode probes `unshare`, and cache the result.
- No `bwrap`, a failed probe, or a container that forbids user namespaces gives mechanism `NONE` with the reason, and the classifier's no-sandbox column applies.
- Container detection ports Clawcode's markers (`/.dockerenv`, `/run/.containerenv`, container variables, `/proc/1/cgroup` names) for the report.

### Running the command

- **Launch:** `/bin/sh -lc <command>` in the workspace, wrapped by the launcher, stdin from `/dev/null`, as Clawcode does.
- **Environment:** start from Butler's own, then remove every variable whose value equals a configured provider key (`butler.providers.*.api-key`), so a renamed variable is caught too, and every name matching `butler.bash.environment.remove` (default `BUTLER_*` and `*_API_KEY`). `TMPDIR` points at the run's temp folder.
- **Output:** both streams are read on virtual threads into buffers that keep the first and last 8 KiB each, so 16 KiB per stream as in Clawcode, but head and tail instead of head only. A marker says how many bytes were left out.
- **Timeout:** the model's value in milliseconds, otherwise a default, never above a maximum (proposed 120,000 ms and 600,000 ms; Clawcode has no default and waits indefinitely). The message is Clawcode's: `Command exceeded timeout of {ms} ms`.
- **Cancellation:** while waiting, the gateway checks the turn's `TurnCancellation` every 100 ms, so Ctrl-C stops a long command (contract change, below).
- **Kill:** collect `ProcessHandle.descendants()` first, destroy them and the shell, wait two seconds, then force-kill whatever is left. Descendants come first because they are re-parented once the shell dies and can no longer be found from it. On Linux, `--die-with-parent` and the PID namespace end any stragglers.
- **Outcome:** JSON with Clawcode's field names (`stdout`, `stderr`, `interrupted`, `returnCodeInterpretation` as `exit_code:N` or `timeout`, `noOutputExpected`) plus the sandbox mechanism. A non-zero exit is data, not an error result, as in Clawcode; `isError` is set only when the command could not start.

## Tasks

Five tasks, one pull request each, branched as `lane/f/<task>`. F1, F2 and F4 start together; F3 follows F2; F5 follows F1 and F4. Compared with the Tasks tab, F2 also adds `SandboxStatus`, F4 takes cancellation, and F5 builds the prompt preview.

### F1 Risk classifier (M, needs M0)

1. If lane D has not merged it yet, open the `BashRisk` contract pull request: `CommandIntent`, `BashRisk` and a placeholder `classify(command, workspace, sandboxActive)` that returns `UNKNOWN` at danger-full-access. Types only; D reviews.
2. Add `CommandWords.kt`: `firstCommand` ported from `extract_first_command` (skips `KEY=value` prefixes, looks inside `sudo`), `hasShellOperators` with Clawcode's character set, and `segments`, which splits at `|`, `;`, `&&`, `||` and newlines.
3. Port the lists as private constants: write, state-modifying, semantic read-only, network, process, package and system-admin commands, destructive patterns and git subcommands. Use the strict git list from `permission_enforcer.rs` for the read-only tier; `bash_validation.rs` also counts `fetch`, `stash` and `config` as read-only.
4. Write `classify`: the strict check, then intent by riskiest segment, then the tier table, then warnings with Clawcode's texts.
5. Tests: port every case from the test modules of `bash_validation.rs` and `permission_enforcer.rs`, then add Butler's: pipes and chains, `$(…)` and backticks, `sudo rm`, `FOO=1 rm`, `./gradlew build` as unknown, and both sandbox columns.

Done when: the table-driven tests pass and each applicable Clawcode case has a Kotlin twin.

### F2 Sandbox policy and macOS (M, needs M0)

1. Add `SandboxPolicy.forWorkspace`, `SandboxMechanism` and `SandboxStatus` in core.
2. Add `sandbox/SandboxLauncher` in secondary: `wrap(command, policy, workspace): List<String>` (the full argument list to start) and `probe(): SandboxStatus`.
3. Add `MacSandboxLauncher` with the profile as a resource, paths passed through `toRealPath()`, and the network rule only when the policy turns the network off. Its probe runs a trivial command under a minimal profile.
4. Tests: a profile-text test on every system; macOS-only tests (`@EnabledOnOs(MAC)`) that a write inside the workspace succeeds, a write to a folder outside the policy fails, and `> /dev/null` still works.

Done when: the macOS-only tests pass on your Mac. CI on Linux skips them, so record the run in the pull request.

### F3 Linux and the fallback (M, needs F2)

1. Add `BubblewrapLauncher` with the arguments in Design and a cached probe.
2. Add `NoSandboxLauncher`: mechanism `NONE`, `active = false`, and the reason.
3. Add `SandboxDetector`: picks by operating system and probe result, honours `butler.bash.sandbox.mode` (`auto` or `off`), and ports Clawcode's container markers for the status.
4. Tests: Linux-only and skipped when `bwrap` is missing (`assumeTrue`): a write outside the workspace is blocked; the detector picks `NONE` with a reason when the probe fails or the mode is `off`.

Done when: the Linux test passes where bubblewrap is installed, and the fallback test passes everywhere.

### F4 Command gateway (M, needs M0; uses F2 and F3 when merged)

1. Add `BashProperties` (see Configuration).
2. Add `ChildEnvironment`: copy Butler's environment, drop variables whose value equals a configured provider key, drop names matching the remove patterns, set `TMPDIR`.
3. Add `BoundedOutput`, which keeps the head and the tail and counts what it dropped, and `ProcessTree`, which collects descendants, destroys, waits and force-kills.
4. Add `CommandGatewayImpl.run`: merge the core policy with configuration (configuration can only add writable roots or turn the network off), wrap through the detector's launcher, start, read both streams on virtual threads, wait with timeout while polling cancellation, build the JSON outcome, delete the temp folder. Until F3 merges, use `NoSandboxLauncher`.
5. Replace the stand-in in `CommandConfig` and delete it.
6. Tests with real `sh` in temp folders: output and exit code; stderr; a non-zero exit is not an error result; a timeout on `sleep 30 & sleep 30; wait` leaves no descendant alive (check each PID with `ProcessHandle.of`); large output keeps head and tail with the marker; a fake key and a `BUTLER_` variable are absent from `env`; cancellation stops within a second.

Done when: no test leaves an orphan process and no provider key reaches the child.

### F5 butler sandbox and the preview (S, needs F1 and F4)

1. Add `GetSandboxStatusUseCase`, backed by the proposed `CommandGateway.status(workspace)`.
2. Add `butler sandbox` with `--output-format json`. The text report follows `claw sandbox`'s layout, adapted to Butler's fields: Enabled, Active, Mechanism, Network, Writable roots, In container, Fallback reason.
3. Add `BashRisk.describe(command, description)`, the preview lane A puts on the approval request: the description, the command after `$ `, and the intent with any warning.
4. Tests: text and JSON output with a fake status; the preview format.

Done when: `butler sandbox` reports the real mechanism on your Mac and the preview reaches D's prompt.

## Tests

The classifier is tested exhaustively as pure code; the sandboxes and the process code are tested against the real operating system, with platform-specific tests skipped where they cannot run.

| Test | Module | Runs on | What it proves | Task |
| --- | --- | --- | --- | --- |
| `BashRiskClassifierTest` | core | All | Every intent, both sandbox columns, Clawcode's cases and warnings, chains and substitutions | F1 |
| `CommandWordsTest` | core | All | First command past prefixes and `sudo`, operators, segments | F1 |
| `MacSandboxProfileTest` | secondary | All | The profile text, real paths, the network rule | F2 |
| `MacSandboxLauncherTest` | secondary | macOS | Inside writes succeed, outside writes fail, `/dev/null` works | F2 |
| `BubblewrapLauncherTest` | secondary | Linux with `bwrap` | Outside writes fail | F3 |
| `SandboxDetectorTest` | secondary | All | `NONE` with a reason when the probe fails or the mode is `off` | F3 |
| `CommandGatewayImplTest` | secondary | macOS and Linux | Output, exit codes, timeout, no orphans, head-and-tail cap, stripped environment, cancellation | F4 |
| `SandboxCommandTest` | api | All | Text and JSON report from a fake status | F5 |
| `BashRiskDescribeTest` | core | All | The approval preview format | F5 |

Process tests must never leave work behind: each one records the PIDs it started and fails if any is still alive at the end. The full build also runs ArchUnit and the core classpath guard; nothing in `domain/shell` may touch `java.lang.Process`.

## Configuration

Lane F adds one block, `butler.bash`, with the usual environment overrides. It holds no secrets: provider keys are only read, to remove them from the child's environment.

```yaml
butler:
  bash:
    default-timeout: 120s        # proposed; used when the model sends no timeout
    max-timeout: 10m             # proposed; longer requests are cut to this
    output-limit-bytes: 16384    # per stream, half kept from the start and half from the end
    sandbox:
      mode: auto                 # auto or off; off means no sandbox, so the approval fallback applies
      network: true              # false adds the network rule (macOS) or --unshare-net (Linux)
      writable-paths: []         # extra writable folders; see decisions for build caches
    environment:
      remove: ["BUTLER_*", "*_API_KEY"]   # plus any variable whose value is a configured provider key
```

| Property | Environment variable | Default |
| --- | --- | --- |
| `butler.bash.default-timeout` | `BUTLER_BASH_DEFAULT_TIMEOUT` | `120s` (proposed) |
| `butler.bash.max-timeout` | `BUTLER_BASH_MAX_TIMEOUT` | `10m` (proposed) |
| `butler.bash.output-limit-bytes` | `BUTLER_BASH_OUTPUT_LIMIT_BYTES` | `16384` |
| `butler.bash.sandbox.mode` | `BUTLER_BASH_SANDBOX_MODE` | `auto` |
| `butler.bash.sandbox.network` | `BUTLER_BASH_SANDBOX_NETWORK` | `true` |
| `butler.bash.sandbox.writable-paths[n]` | `BUTLER_BASH_SANDBOX_WRITABLEPATHS_<n>` | none |
| `butler.bash.environment.remove[n]` | `BUTLER_BASH_ENVIRONMENT_REMOVE_<n>` | `BUTLER_*`, `*_API_KEY` |

`off` never means "run everything freely": with no sandbox, the classifier's no-sandbox column sends every non-read-only command to the approval prompt in `workspace-write` and `prompt` modes.

## Working with other lanes

Lane F builds and tests on its own; other lanes reach it only through `BashRisk`, `CommandGateway` and the sandbox status, and test against `RecordingCommandGateway` rather than real processes.

| Lane | What F needs from it | What F gives it |
| --- | --- | --- |
| A Agent loop | Calls `BashRiskClassifier.classify` before the permission step, using the cached sandbox status; calls `CommandGateway.run` with the policy and the turn's cancellation; puts `BashRisk.describe` on the approval request | The classifier, the gateway, the preview |
| D Permissions | Turns the risk's tier and intent into allow, ask or deny; never offers "allow for this session" for destructive commands | `BashRisk` and its warnings |
| I Terminal | Shows the description and command while a bash call runs; Ctrl-C sets `TurnCancellation` | Mid-command cancellation that kills the whole tree |
| J Integration | J3 runs the M4 check | The macOS sandbox test, the no-orphans test, the environment test |

**Contract changes lane F proposes** (each a small, additive contract pull request):

1. `domain/shell/BashRisk.kt` with `CommandIntent`, `BashRisk` and `BashRiskClassifier.classify(command, workspace, sandboxActive)`. Shared with lane D's list; reviewed by A and D.
2. `CommandGateway.run(…, cancellation: TurnCancellation = TurnCancellation.NEVER)`, so a long command stops on Ctrl-C. Reviewed by A and I.
3. `CommandGateway.status(workspace): SandboxStatus`, with a default that reports `NONE`, plus the new `SandboxStatus` and `SandboxMechanism` types. Reviewed by A.

**What J checks at M4:** on macOS, `echo x > <a folder outside the workspace>/probe` fails inside the sandbox while the same write inside the workspace succeeds; a command that starts background children and times out leaves none of them alive; `env` run through the tool shows no provider key; `butler sandbox` reports the mechanism in use.

## Done when

Lane F is done when gate M4's checks pass on `main`. Tick these as they land:

- [ ] `BashRisk` contract merged (by F or D)
- [ ] F1 merged: classifier, command words; Clawcode's cases ported
- [ ] F2 merged: policy, status, macOS launcher; macOS test run recorded
- [ ] F3 merged: bubblewrap, no-sandbox launcher, detector
- [ ] F4 merged: gateway; stand-in deleted; no orphans, no keys in the child
- [ ] F5 merged: `butler sandbox`, approval preview
- [ ] Cancellation and status contract changes merged with lane A
- [ ] J3 green for M4

## Risks and decisions to confirm

Six choices need your yes before F4 starts; each has a recommendation. The risks below are the ones a sandbox cannot fully close.

**Decisions**

- [ ] **Timeouts.** Recommended: 120 seconds by default, 10 minutes at most. Clawcode has no default and waits indefinitely.
- [ ] **Network inside the sandbox.** Recommended: on by default, as in Clawcode, because builds download dependencies. Network commands still ask, since their intent is danger-full-access.
- [ ] **Build caches.** Recommended: add `~/.gradle`, `~/.m2`, `~/.npm` and `~/.cache` to the writable paths by default, or `./gradlew build` fails inside the sandbox. The alternative is none by default, documented.
- [ ] **Login shell.** Recommended: `sh -lc`, as Clawcode, so tools on the profile's `PATH` (Homebrew, SDKMAN) are found. `sh -c` is faster but finds less.
- [ ] **No-sandbox fallback.** Recommended: strictly read-only commands still run without asking. The Tasks tab says every command asks, which would prompt for each `ls`.
- [ ] **Output shape.** Recommended: JSON with Clawcode's field names, so models see what they would see in Clawcode. The alternative is plain text with an exit-code line.

**Risks**

| Risk | Effect | Fix |
| --- | --- | --- |
| `sandbox-exec` is deprecated and could disappear in a future macOS | The macOS sandbox stops working | The probe fails, the status says why, and the approval fallback takes over automatically |
| A program detaches with a double fork | It escapes `descendants()` and survives a timeout on macOS | On Linux the PID namespace and `--die-with-parent` end it; on macOS it is documented, and J's test covers the common background case |
| The classifier is a heuristic | An alias, `eval` or a script file hides what a command does | Unknown commands default to danger-full-access, and the sandbox, not the classifier, is the boundary |
| Some Linux distributions restrict unprivileged user namespaces | Bubblewrap's probe fails | The status names the reason; approval fallback applies; `butler sandbox` tells the user |
