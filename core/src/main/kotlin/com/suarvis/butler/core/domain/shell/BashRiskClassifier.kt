package com.suarvis.butler.core.domain.shell

import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.tool.PermissionTier

/**
 * Judges a shell command before it runs: what it sets out to do, the permission tier it needs, and
 * a warning for the approval prompt.
 *
 * Clawcode has three bash classifiers that disagree: `is_read_only_command` (permission_enforcer.rs),
 * the `bash_validation.rs` pipeline (`classify_command`, `validate_read_only`, `check_destructive`,
 * `validate_mode`, `validate_paths`), and the runtime's `classify_bash_permission` (tools/src/lib.rs).
 * This object merges the first two, keeping their command lists and warning texts, and replaces the
 * third. Where they disagree the riskier answer wins; the one exception is UNKNOWN, which only means
 * "not on my list" and so yields to a list that does name the command (`rg` is on the strict list
 * only, and stays read-only).
 *
 * 1. Intent, per segment (`classify_command`): the first word after any `KEY=value` prefixes, judged
 *    against Clawcode's lists in Clawcode's order. Clawcode looks only at the command's first word,
 *    so `ls && rm -rf x` passes as read-only; Butler splits at `|`, `||`, `&&`, `&`, `;` and newlines
 *    outside quotes, and the riskiest segment decides. `$(`, backticks, `<(` and `>(` run commands
 *    nobody has judged, so they make the whole command UNKNOWN. An output redirection makes its
 *    segment at least WRITE, because `validate_read_only` treats `>`, `>>` and `>&` as writes.
 *    `sudo` (`extract_sudo_inner`), `env` and `xargs` also count the command they run: the last two
 *    are on Clawcode's read-only list, yet `env rm x` deletes, and `classify_bash_permission` already
 *    sends both to danger-full-access. `find` with an action that runs or deletes is UNKNOWN, since
 *    `is_read_only_command` refuses to reason about it. Git uses the strict list from
 *    permission_enforcer.rs; bash_validation.rs also counts `fetch`, `stash` and `config` as
 *    read-only, and those are WRITE here.
 * 2. Destructive patterns (`check_destructive`) are matched against the whole command, as in
 *    Clawcode, so a match inside quotes counts too. Any match makes the command DESTRUCTIVE and
 *    gives the warning. The patterns are substrings, so `rm -rf /tmp/x` gets the "at root" warning;
 *    the tier is the same either way. Clawcode's last check looks for the literal `-r` and `-f`,
 *    which `rm -rf` never contains, so it also accepts the two flags combined.
 * 3. Paths: a word naming something outside the workspace needs danger-full-access whatever the
 *    intent. This joins `validate_mode` (writes outside the workspace), `validate_paths` (`../`, `~/`
 *    and `$HOME`) and the path rule of `classify_bash_permission` (absolute paths outside the
 *    workspace, and any `$` substitution, whose value is unknown). Butler's file tools refuse such
 *    paths outright, and bash must not be a side door around them. Containment is checked lexically,
 *    as [WorkspacePath] does, which covers the system folders `command_targets_outside_workspace`
 *    lists without misreading a relative `src/lib/` as `/lib/`.
 * 4. Tier: READ_ONLY only when the intent is read-only and the strict check passes (no shell
 *    operators, as `is_read_only_command`); any other read-only command, and any write that stays in
 *    the workspace, needs WORKSPACE_WRITE; everything else needs DANGER_FULL_ACCESS.
 * 5. Butler's one departure: output redirected to `/dev/null` is discarded, so it is neither a write,
 *    nor a path outside the workspace, nor a shell operator for the strict check. `ls 2>/dev/null`
 *    stays read-only.
 *
 * Unused on purpose: `STATE_MODIFYING_COMMANDS`, whose entries are each on a danger-tier list or
 * unknown, which is danger-tier too. The classifier is a heuristic: an alias, `eval` or a script file
 * hides what runs. Whatever it cannot name is UNKNOWN at danger-full-access, and the sandbox, not this
 * object, is the boundary.
 */
object BashRiskClassifier {
    /**
     * @param sandboxActive reserved for F2. None of Clawcode's classifiers consult the sandbox, so it
     *   changes nothing yet; a workspace write needs workspace-write with or without one.
     */
    @Suppress("UNUSED_PARAMETER")
    fun classify(command: String, workspace: WorkspaceRoot, sandboxActive: Boolean): BashRisk {
        val parsed = CommandWords.parse(command)
        val segments = parsed?.segments.orEmpty()
        val structural =
            if (parsed == null || segments.isEmpty()) {
                CommandIntent.UNKNOWN
            } else {
                riskiest(segments.map { segmentIntent(it, workspace) } + listOfNotNull(CommandIntent.UNKNOWN.takeIf { parsed.substitutes }))
            }
        val destructiveWarning = destructiveWarning(command, programsIn(parsed, command, workspace))
        // DESTRUCTIVE is the riskiest intent, so a destructive pattern always decides.
        val intent = if (destructiveWarning != null) CommandIntent.DESTRUCTIVE else structural
        val scope = segments.flatMap { pathWords(it) }.mapNotNull { scopeOf(it, workspace) }.minByOrNull { it.ordinal }
        return BashRisk(intent, tierOf(intent, scope != null, command), destructiveWarning ?: scope?.warning)
    }

    /** A program and its arguments; a wrapper such as `sudo` yields itself and the command it runs. */
    private data class SimpleCommand(val program: String, val arguments: List<ShellWord>)

    /**
     * Where a path leads, most serious first so the first warning wins, in the order Clawcode's
     * `validate_command` reports them: scope, then traversal, then the home directory.
     */
    private enum class Scope(val warning: String?) {
        OUTSIDE("Command appears to target files outside the workspace — requires elevated permission"),
        TRAVERSAL("Command contains directory traversal pattern '../' — verify the target path resolves within the workspace"),
        HOME("Command references home directory — verify it stays within the workspace scope"),

        /** A `$` substitution: its value is unknown until it runs, and Clawcode gives no warning text for it. */
        VARIABLE(null),
    }

    private fun segmentIntent(segment: ShellSegment, workspace: WorkspaceRoot): CommandIntent {
        val commands = commandsIn(segment.words, workspace)
        if (commands.isEmpty()) return CommandIntent.UNKNOWN
        val run = riskiest(commands.map { commandIntent(it, segment.text) })
        return if (segment.writesOutput) riskiest(listOf(run, CommandIntent.WRITE)) else run
    }

    private fun commandsIn(words: List<ShellWord>, workspace: WorkspaceRoot): List<SimpleCommand> {
        val start = CommandWords.programIndex(words) ?: return emptyList()
        val program = programName(words[start].text, workspace)
        val arguments = words.subList(start + 1, words.size)
        // Like extract_sudo_inner: skip the wrapper's flags, then judge the rest as a command.
        val wrapped = if (program in WRAPPERS) commandsIn(arguments.dropWhile { it.text.startsWith("-") }, workspace) else emptyList()
        return listOf(SimpleCommand(program, arguments)) + wrapped
    }

    /**
     * The name to look up. `/usr/bin/cat` is `cat`, as `is_read_only_command` takes the last path
     * part; a program inside the workspace (`./gradlew`, `bin/cat`) keeps its path and so is unknown,
     * because the workspace's own files can be named anything.
     */
    private fun programName(word: String, workspace: WorkspaceRoot): String =
        if (word.startsWith("/") && WorkspacePath.within(workspace, word) == null) word.substringAfterLast('/') else word

    /** Clawcode's `classify_by_first_command`, in its order, with the read-only lists of both classifiers. */
    private fun commandIntent(command: SimpleCommand, segmentText: String): CommandIntent =
        when (command.program) {
            "git" -> gitIntent(command.arguments)
            "find" -> if (FIND_ACTIONS.any { it in segmentText }) CommandIntent.UNKNOWN else CommandIntent.READ_ONLY
            "sed" -> if (editsInPlace(command.arguments, segmentText)) CommandIntent.WRITE else CommandIntent.READ_ONLY
            in READ_ONLY_COMMANDS -> CommandIntent.READ_ONLY
            in ALWAYS_DESTRUCTIVE_COMMANDS, "rm" -> CommandIntent.DESTRUCTIVE
            in WRITE_COMMANDS -> CommandIntent.WRITE
            in NETWORK_COMMANDS -> CommandIntent.NETWORK
            in PROCESS_COMMANDS -> CommandIntent.PROCESS_MANAGEMENT
            in PACKAGE_COMMANDS -> CommandIntent.PACKAGE_MANAGEMENT
            in SYSTEM_ADMIN_COMMANDS -> CommandIntent.SYSTEM_ADMIN
            else -> CommandIntent.UNKNOWN
        }

    /** Clawcode's `classify_git_command`: the first word after `git` that is not a flag. */
    private fun gitIntent(arguments: List<ShellWord>): CommandIntent {
        val subcommand = arguments.firstOrNull { !it.text.startsWith("-") }?.text.orEmpty()
        return if (subcommand in GIT_READ_ONLY_SUBCOMMANDS) CommandIntent.READ_ONLY else CommandIntent.WRITE
    }

    /** `classify_command` looks for ` -i`, `is_read_only_command` for `--in-place`; a clustered flag such as `-ni` edits in place too. */
    private fun editsInPlace(arguments: List<ShellWord>, segmentText: String): Boolean =
        " -i" in segmentText ||
            "--in-place" in segmentText ||
            arguments.any { it.text.startsWith("-") && !it.text.startsWith("--") && 'i' in it.text }

    /** Every program the command runs, wrappers included; the first word when the command cannot be parsed. */
    private fun programsIn(parsed: ShellCommand?, command: String, workspace: WorkspaceRoot): List<String> =
        parsed?.segments?.flatMap { segment -> commandsIn(segment.words, workspace).map { it.program } }
            ?: listOfNotNull(command.trim().split(WHITESPACE).firstOrNull())

    /** Clawcode's `check_destructive`, in its order: patterns, then always-destructive programs, then recursive forced deletion. */
    private fun destructiveWarning(command: String, programs: List<String>): String? {
        DESTRUCTIVE_PATTERNS.firstOrNull { (pattern, _) -> pattern in command }?.let { (_, warning) ->
            return "Destructive command detected: $warning"
        }
        programs.firstOrNull { it in ALWAYS_DESTRUCTIVE_COMMANDS }?.let {
            return "Command '$it' is inherently destructive and may cause data loss"
        }
        if ("rm " in command && (("-r" in command && "-f" in command) || COMBINED_RECURSIVE_FORCE.containsMatchIn(command))) {
            return "Recursive forced deletion detected — verify the target path is correct"
        }
        return null
    }

    /** The words that may name a path: every argument and redirection target, but not the program itself. */
    private fun pathWords(segment: ShellSegment): List<ShellWord> {
        val program = CommandWords.programIndex(segment.words)
        return segment.words.filterIndexed { index, _ -> index != program } + segment.redirectTargets
    }

    private fun scopeOf(word: ShellWord, workspace: WorkspaceRoot): Scope? =
        when {
            word.expandsHome -> Scope.HOME
            word.expands -> Scope.VARIABLE
            else -> pathCandidates(word.text).mapNotNull { scopeOfPath(it, workspace) }.minByOrNull { it.ordinal }
        }

    /**
     * What a word may name: the word itself, the value glued to a short flag (`-o/etc/x`,
     * `-I../include`), and the value after `name=` (`--prefix=/usr`, `of=/dev/sda`). Clawcode skips
     * flags entirely; looking inside them is the conservative choice. Brackets and commas are trimmed,
     * as `has_dangerous_paths` does.
     */
    private fun pathCandidates(text: String): List<String> =
        buildList<String> {
            when {
                !text.startsWith("-") -> add(text)
                !text.startsWith("--") && text.length > 2 -> add(text.substring(2))
            }
            ASSIGNED_VALUE.find(text)?.let { add(text.substring(it.range.last + 1)) }
        }.map { it.trim(*PATH_PUNCTUATION) }.filter { it.isNotEmpty() }

    /** Relative paths resolve against the workspace, where `bash` runs; a `cd` first makes the command unknown anyway. */
    private fun scopeOfPath(path: String, workspace: WorkspaceRoot): Scope? =
        when {
            path == DISCARD -> null
            path.startsWith("~") -> Scope.HOME
            looksLikeWindowsAbsolutePath(path) -> Scope.OUTSIDE
            // has_dangerous_paths flags these even when they would resolve back inside.
            path.startsWith("../") || path == ".." || "../.." in path -> Scope.TRAVERSAL
            '/' !in path && !path.startsWith(".") -> null
            WorkspacePath.within(workspace, path) != null -> null
            ".." in path.split('/') -> Scope.TRAVERSAL
            else -> Scope.OUTSIDE
        }

    /** Clawcode's `looks_like_windows_absolute_path`: `C:/`, `C:\` or a `\\server` share. */
    private fun looksLikeWindowsAbsolutePath(path: String): Boolean =
        (path.length >= 3 && (path[0] in 'a'..'z' || path[0] in 'A'..'Z') && path[1] == ':' && (path[2] == '/' || path[2] == '\\')) ||
            path.startsWith("\\\\")

    private fun tierOf(intent: CommandIntent, leavesWorkspace: Boolean, command: String): PermissionTier =
        when {
            intent != CommandIntent.READ_ONLY && intent != CommandIntent.WRITE -> PermissionTier.DANGER_FULL_ACCESS
            leavesWorkspace -> PermissionTier.DANGER_FULL_ACCESS
            intent == CommandIntent.READ_ONLY && isStrictlyReadOnly(command) -> PermissionTier.READ_ONLY
            else -> PermissionTier.WORKSPACE_WRITE
        }

    /**
     * Clawcode's `is_read_only_command`, character for character: no shell metacharacter anywhere,
     * not even inside quotes, a first word (by its last path part) on the strict list, only the strict
     * git subcommands, `find` without actions, and no `-i ` or `--in-place`.
     */
    private fun isStrictlyReadOnly(command: String): Boolean {
        // Butler's one addition: discarding output (`2>/dev/null`) keeps a read-only command read-only.
        if (CommandWords.hasShellOperators(DISCARD_REDIRECT.replace(command, " "))) return false
        val tokens = command.split(WHITESPACE).filter { it.isNotEmpty() }
        val first = tokens.firstOrNull().orEmpty().substringAfterLast('/')
        if (first == "git") return tokens.getOrNull(1).orEmpty() in GIT_READ_ONLY_SUBCOMMANDS
        if (first == "find" && FIND_ACTIONS.any { it in command }) return false
        return first in STRICT_READ_ONLY_COMMANDS && "-i " !in command && "--in-place" !in command
    }

    private fun riskiest(intents: List<CommandIntent>): CommandIntent = intents.minByOrNull { RISK_ORDER.indexOf(it) } ?: CommandIntent.UNKNOWN

    /**
     * Riskiest first. Clawcode never compares two commands, so the order is Butler's: data loss,
     * then the whole machine, then anything at all, then the specific danger-tier intents, then
     * workspace writes and reads.
     */
    /** `>`, `>>`, `2>`, `&>` and `&>>` into /dev/null, which [isStrictlyReadOnly] may ignore. */
    private val DISCARD_REDIRECT = Regex("(?:[0-9]|&)?>>?\\s*/dev/null(?=\\s|$)")

    private val RISK_ORDER =
        listOf(
            CommandIntent.DESTRUCTIVE,
            CommandIntent.SYSTEM_ADMIN,
            CommandIntent.UNKNOWN,
            CommandIntent.NETWORK,
            CommandIntent.PROCESS_MANAGEMENT,
            CommandIntent.PACKAGE_MANAGEMENT,
            CommandIntent.WRITE,
            CommandIntent.READ_ONLY,
        )

    private val WHITESPACE = Regex("\\s+")

    /** A `name=` prefix, with up to two leading dashes: `FOO=`, `--prefix=`, `of=`. */
    private val ASSIGNED_VALUE = Regex("^-{0,2}[A-Za-z0-9_.-]+=")

    private val PATH_PUNCTUATION = charArrayOf('(', ')', '[', ']', '{', '}', ',')

    /** `-rf`, `-fr`, `-Rf`, `-vrf`: the recursive and force flags in one cluster. */
    private val COMBINED_RECURSIVE_FORCE = Regex("""(^|\s)-[A-Za-z]*([rR][A-Za-z]*f|f[A-Za-z]*[rR])""")

    /** Commands that run the command named in their arguments. Only `sudo` is unwrapped by Clawcode; see the class comment. */
    private val WRAPPERS = setOf("sudo", "env", "xargs")

    /** `find` actions that run or delete, from `is_read_only_command`. */
    private val FIND_ACTIONS = listOf("-exec", "-execdir", "-delete", "-ok", "-fprintf")

    // Clawcode's lists, copied from bash_validation.rs unless noted.

    /** `WRITE_COMMANDS`. */
    private val WRITE_COMMANDS =
        setOf(
            "cp", "mv", "rm", "mkdir", "rmdir", "touch", "chmod", "chown", "chgrp", "ln", "install", "tee",
            "truncate", "shred", "mkfifo", "mknod", "dd",
        )

    /** The git subcommands `is_read_only_command` accepts (permission_enforcer.rs). */
    private val GIT_READ_ONLY_SUBCOMMANDS =
        setOf("status", "log", "diff", "show", "branch", "rev-parse", "ls-files", "blame", "describe", "tag", "remote")

    /** `DESTRUCTIVE_PATTERNS`, in Clawcode's order: the first match gives the warning. */
    private val DESTRUCTIVE_PATTERNS =
        listOf(
            "rm -rf /" to "Recursive forced deletion at root — this will destroy the system",
            "rm -rf ~" to "Recursive forced deletion of home directory",
            "rm -rf *" to "Recursive forced deletion of all files in current directory",
            "rm -rf ." to "Recursive forced deletion of current directory",
            "mkfs" to "Filesystem creation will destroy existing data on the device",
            "dd if=" to "Direct disk write — can overwrite partitions or devices",
            "> /dev/sd" to "Writing to raw disk device",
            "chmod -R 777" to "Recursively setting world-writable permissions",
            "chmod -R 000" to "Recursively removing all permissions",
            ":(){ :|:& };:" to "Fork bomb — will crash the system",
        )

    /** `ALWAYS_DESTRUCTIVE_COMMANDS`. */
    private val ALWAYS_DESTRUCTIVE_COMMANDS = setOf("shred", "wipefs")

    /** `SEMANTIC_READ_ONLY_COMMANDS`. */
    private val SEMANTIC_READ_ONLY_COMMANDS =
        setOf(
            "ls", "cat", "head", "tail", "less", "more", "wc", "sort", "uniq", "grep", "egrep", "fgrep", "find",
            "which", "whereis", "whatis", "man", "info", "file", "stat", "du", "df", "free", "uptime", "uname",
            "hostname", "whoami", "id", "groups", "env", "printenv", "echo", "printf", "date", "cal", "bc", "expr",
            "test", "true", "false", "pwd", "tree", "diff", "cmp", "md5sum", "sha256sum", "sha1sum", "xxd", "od",
            "hexdump", "strings", "readlink", "realpath", "basename", "dirname", "seq", "yes", "tput", "column",
            "jq", "yq", "xargs", "tr", "cut", "paste", "awk", "sed",
        )

    /** The read-only list of `is_read_only_command` (permission_enforcer.rs). */
    private val STRICT_READ_ONLY_COMMANDS =
        setOf(
            "cat", "head", "tail", "less", "more", "wc", "ls", "find", "grep", "rg", "awk", "sed", "echo", "printf",
            "which", "where", "whoami", "pwd", "env", "printenv", "date", "cal", "df", "du", "free", "uptime",
            "uname", "file", "stat", "diff", "sort", "uniq", "tr", "cut", "paste", "test", "true", "false", "type",
            "readlink", "realpath", "basename", "dirname", "sha256sum", "md5sum", "b3sum", "xxd", "hexdump", "od",
            "strings", "tree", "jq", "yq",
        )

    /** Read-only by either classifier's list; whether the tier is read-only is the strict check's call. */
    private val READ_ONLY_COMMANDS = SEMANTIC_READ_ONLY_COMMANDS + STRICT_READ_ONLY_COMMANDS

    /** `NETWORK_COMMANDS`. */
    private val NETWORK_COMMANDS =
        setOf(
            "curl", "wget", "ssh", "scp", "rsync", "ftp", "sftp", "nc", "ncat", "telnet", "ping", "traceroute", "dig",
            "nslookup", "host", "whois", "ifconfig", "ip", "netstat", "ss", "nmap",
        )

    /** `PROCESS_COMMANDS`. */
    private val PROCESS_COMMANDS =
        setOf("kill", "pkill", "killall", "ps", "top", "htop", "bg", "fg", "jobs", "nohup", "disown", "wait", "nice", "renice")

    /** `PACKAGE_COMMANDS`. */
    private val PACKAGE_COMMANDS =
        setOf(
            "apt", "apt-get", "yum", "dnf", "pacman", "brew", "pip", "pip3", "npm", "yarn", "pnpm", "bun", "cargo", "gem",
            "go", "rustup", "snap", "flatpak",
        )

    /** `SYSTEM_ADMIN_COMMANDS`. */
    private val SYSTEM_ADMIN_COMMANDS =
        setOf(
            "sudo", "su", "chroot", "mount", "umount", "fdisk", "parted", "lsblk", "blkid", "systemctl", "service",
            "journalctl", "dmesg", "modprobe", "insmod", "rmmod", "iptables", "ufw", "firewall-cmd", "sysctl", "crontab",
            "at", "useradd", "userdel", "usermod", "groupadd", "groupdel", "passwd", "visudo",
        )
}
