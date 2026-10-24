package com.suarvis.butler.core.domain.shell

import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.tool.PermissionTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

/** Clawcode's cases from bash_validation.rs and permission_enforcer.rs, then Butler's own: chains, substitutions, wrappers, paths. */
class BashRiskClassifierTest {
    private val workspace = WorkspaceRoot.of(Path.of("/work/butler"))

    private fun classify(command: String, sandboxActive: Boolean = true): BashRisk = BashRiskClassifier.classify(command, workspace, sandboxActive)

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "ls", "ls -la", "cat file.txt", "cat src/main.rs", "grep -r pattern .", "grep -r 'pattern' .", "grep pattern file",
            "find . -name '*.rs'", "find . -name Cargo.toml", "git status", "git log --oneline", "git diff HEAD~1", "git show abc123",
            "sed 's/old/new/' file.txt", "echo hello", "/usr/bin/cat Cargo.toml", "/usr/local/bin/git status", "rg TODO src",
            "jq . package.json", "env FOO=1 ls",
        ],
    )
    fun `strictly read-only commands need only the read-only tier`(command: String) {
        assertEquals(BashRisk(CommandIntent.READ_ONLY, PermissionTier.READ_ONLY), classify(command))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("intents")
    fun `each intent maps to its tier, with Clawcode's examples`(command: String, intent: CommandIntent, tier: PermissionTier) {
        val risk = classify(command)

        assertEquals(intent, risk.intent)
        assertEquals(tier, risk.tier)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["cat a.txt | wc -l", "ls -la; pwd", "ls &", "grep -i todo notes.txt", "FOO=1 ls", "id", "echo '\$(rm x)'"])
    fun `read-only commands the strict check refuses need workspace-write`(command: String) {
        assertEquals(BashRisk(CommandIntent.READ_ONLY, PermissionTier.WORKSPACE_WRITE), classify(command))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["ls 2>/dev/null", "grep -r TODO src > /dev/null", "git status &>/dev/null", "cat notes.md 2>> /dev/null"])
    fun `output discarded into dev null keeps a read-only command read-only`(command: String) {
        assertEquals(BashRisk(CommandIntent.READ_ONLY, PermissionTier.READ_ONLY), classify(command))
    }

    @Test
    fun `discarding is only for dev null itself`() {
        assertEquals(PermissionTier.WORKSPACE_WRITE, classify("ls > out.txt").tier)
        assertEquals(PermissionTier.DANGER_FULL_ACCESS, classify("ls > /dev/nullx").tier)
        assertEquals(PermissionTier.DANGER_FULL_ACCESS, classify("cat /dev/null/../../etc/passwd").tier)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chains")
    fun `the riskiest part of a pipe or chain decides`(command: String, intent: CommandIntent, tier: PermissionTier) {
        val risk = classify(command)

        assertEquals(intent, risk.intent)
        assertEquals(tier, risk.tier)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["echo \$(rm bar)", "echo `rm bar`", "echo \"\$(whoami)\"", "diff <(ls a) <(ls b)"])
    fun `command substitution makes a command unknown`(command: String) {
        assertEquals(BashRisk(CommandIntent.UNKNOWN, PermissionTier.DANGER_FULL_ACCESS), classify(command))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrapped")
    fun `KEY=value prefixes, sudo, env and xargs are judged by the command they run`(command: String, intent: CommandIntent, tier: PermissionTier) {
        val risk = classify(command)

        assertEquals(intent, risk.intent)
        assertEquals(tier, risk.tier)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["echo hello > file.txt", "echo x>file", "cat Cargo.toml > out.txt", "echo test >> out.txt", "sort names.txt > build/sorted.txt"])
    fun `a redirection inside the workspace is a workspace write`(command: String) {
        assertEquals(BashRisk(CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE), classify(command))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    fun `paths outside the workspace need danger-full-access, with Clawcode's warnings`(
        command: String,
        intent: CommandIntent,
        tier: PermissionTier,
        warning: String?,
    ) {
        assertEquals(BashRisk(intent, tier, warning), classify(command))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["./cat notes.txt", "bin/cat notes.txt", "/work/butler/bin/ls", "../tools/ls"])
    fun `a program inside the workspace or named by a relative path is unknown whatever its name`(command: String) {
        assertEquals(CommandIntent.UNKNOWN, classify(command).intent)
        assertEquals(PermissionTier.DANGER_FULL_ACCESS, classify(command).tier)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("destructive")
    fun `destructive commands carry Clawcode's warnings`(command: String, warning: String) {
        assertEquals(BashRisk(CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS, warning), classify(command))
    }

    @Test
    fun `Clawcode's patterns match anywhere, so rm -rf of any absolute path gets the root warning`() {
        assertEquals("Destructive command detected: $ROOT", classify("rm -rf /tmp/x").warning)
    }

    @Test
    fun `a plain rm is destructive without a warning, as in Clawcode`() {
        assertEquals(BashRisk(CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS), classify("rm file.txt"))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["ls", "cat a.txt | wc -l", "echo hi > out.txt", "cp file.txt /etc/config", "rm -rf build", "./gradlew build"])
    fun `the sandbox flag changes nothing until F2`(command: String) {
        assertEquals(classify(command, sandboxActive = true), classify(command, sandboxActive = false))
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = ["", "   ", ";", "FOO=bar", "echo \"unterminated", "echo 'unterminated", "echo trailing\\", "ls >"])
    fun `empty or unparsable commands are unknown at danger-full-access`(command: String) {
        assertEquals(BashRisk(CommandIntent.UNKNOWN, PermissionTier.DANGER_FULL_ACCESS), classify(command))
    }

    @Test
    fun `command words find the first command past KEY=value prefixes, as extract_first_command does`() {
        assertEquals("ls", CommandWords.firstCommand("FOO=bar ls -la"))
        assertEquals("echo", CommandWords.firstCommand("A=1 B=2 echo hello"))
        assertEquals("grep", CommandWords.firstCommand("grep -r pattern ."))
        assertEquals("ls", CommandWords.firstCommand("ls|wc -l"))
        assertEquals("", CommandWords.firstCommand("FOO=bar"))
    }

    @Test
    fun `command words split at separators outside quotes`() {
        val segments = CommandWords.parse("cat a | grep \"b; c\" && ls || pwd & top; who\nid")!!.segments

        assertEquals(listOf("cat", "grep", "ls", "pwd", "top", "who", "id"), segments.map { it.words.first().text })
        assertEquals(listOf("grep", "b; c"), segments[1].words.map { it.text })
    }

    @Test
    fun `command words keep redirections apart from arguments`() {
        val output = CommandWords.parse("sort a.txt 2>&1 >> out.txt")!!.segments.single()
        val input = CommandWords.parse("wc -l < in.txt")!!.segments.single()

        assertEquals(listOf("sort", "a.txt"), output.words.map { it.text })
        assertEquals(listOf("1", "out.txt"), output.redirectTargets.map { it.text })
        assertTrue(output.writesOutput)
        assertEquals(listOf("in.txt"), input.redirectTargets.map { it.text })
        assertFalse(input.writesOutput)
    }

    @Test
    fun `command words see substitutions and expansions only where the shell does`() {
        assertTrue(CommandWords.parse("echo \"\$(whoami)\"")!!.substitutes)
        assertFalse(CommandWords.parse("echo '\$(whoami)'")!!.substitutes)
        assertTrue(CommandWords.parse("ls \$DIR")!!.segments.single().words[1].expands)
        assertFalse(CommandWords.parse("grep 'x\$' log.txt")!!.segments.single().words[1].expands)
        assertNull(CommandWords.parse("echo \"open"))
    }

    @Test
    fun `shell operators are Clawcode's metacharacters, quoted or not`() {
        assertTrue(CommandWords.hasShellOperators("cat x > y"))
        assertTrue(CommandWords.hasShellOperators("a && b"))
        assertTrue(CommandWords.hasShellOperators("grep 'a|b' file"))
        assertFalse(CommandWords.hasShellOperators("grep -r 'pattern' ."))
    }

    companion object {
        private const val ROOT = "Recursive forced deletion at root — this will destroy the system"
        private const val OUTSIDE = "Command appears to target files outside the workspace — requires elevated permission"
        private const val TRAVERSAL = "Command contains directory traversal pattern '../' — verify the target path resolves within the workspace"
        private const val HOME = "Command references home directory — verify it stays within the workspace scope"
        private const val RECURSIVE_FORCED = "Recursive forced deletion detected — verify the target path is correct"

        @JvmStatic
        fun intents(): List<Arguments> =
            listOf(
                arguments("cp a.txt b.txt", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE),
                arguments("mv old.txt new.txt", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE),
                arguments("touch notes.md", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE),
                arguments("sed -i 's/old/new/' file.txt", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE),
                arguments("sed --in-place 's/a/b/' file.txt", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE),
                arguments("sed -ni 's/a/b/p' file.txt", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE),
                arguments("git commit -m x", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE),
                arguments("git push origin main", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE),
                arguments("git reset --hard", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE),
                arguments("git clean -fd", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE),
                arguments("git config user.email a@b.c", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE),
                arguments("git fetch", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE),
                arguments("rm file.txt", CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS),
                arguments("rm -rf /tmp/x", CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS),
                arguments("shred /dev/sda", CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS),
                arguments("find . -delete", CommandIntent.UNKNOWN, PermissionTier.DANGER_FULL_ACCESS),
                arguments("find . -execdir rm rf", CommandIntent.UNKNOWN, PermissionTier.DANGER_FULL_ACCESS),
                arguments("curl https://example.com", CommandIntent.NETWORK, PermissionTier.DANGER_FULL_ACCESS),
                arguments("wget file.zip", CommandIntent.NETWORK, PermissionTier.DANGER_FULL_ACCESS),
                arguments("ssh build-host", CommandIntent.NETWORK, PermissionTier.DANGER_FULL_ACCESS),
                arguments("kill 1234", CommandIntent.PROCESS_MANAGEMENT, PermissionTier.DANGER_FULL_ACCESS),
                arguments("pkill java", CommandIntent.PROCESS_MANAGEMENT, PermissionTier.DANGER_FULL_ACCESS),
                arguments("npm install express", CommandIntent.PACKAGE_MANAGEMENT, PermissionTier.DANGER_FULL_ACCESS),
                arguments("pip install requests", CommandIntent.PACKAGE_MANAGEMENT, PermissionTier.DANGER_FULL_ACCESS),
                arguments("cargo run", CommandIntent.PACKAGE_MANAGEMENT, PermissionTier.DANGER_FULL_ACCESS),
                arguments("sudo ls", CommandIntent.SYSTEM_ADMIN, PermissionTier.DANGER_FULL_ACCESS),
                arguments("systemctl restart docker", CommandIntent.SYSTEM_ADMIN, PermissionTier.DANGER_FULL_ACCESS),
                arguments("crontab -e", CommandIntent.SYSTEM_ADMIN, PermissionTier.DANGER_FULL_ACCESS),
                arguments("python script.py", CommandIntent.UNKNOWN, PermissionTier.DANGER_FULL_ACCESS),
                arguments("python -i script.py", CommandIntent.UNKNOWN, PermissionTier.DANGER_FULL_ACCESS),
                arguments("node app.js", CommandIntent.UNKNOWN, PermissionTier.DANGER_FULL_ACCESS),
                arguments("ruby x.rb", CommandIntent.UNKNOWN, PermissionTier.DANGER_FULL_ACCESS),
                arguments("rustc evil.rs", CommandIntent.UNKNOWN, PermissionTier.DANGER_FULL_ACCESS),
                arguments("./gradlew build", CommandIntent.UNKNOWN, PermissionTier.DANGER_FULL_ACCESS),
                // The quoted script still matches Clawcode's "rm -rf ." pattern.
                arguments("python3 -c \"import os; os.system('rm -rf .')\"", CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS),
            )

        @JvmStatic
        fun chains(): List<Arguments> =
            listOf(
                arguments("cat foo; rm -rf bar", CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS),
                arguments("cat foo && rm -rf bar", CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS),
                arguments("ls || rm bar", CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS),
                arguments("sleep 1 & rm notes.txt", CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS),
                arguments("ls\nrm notes.txt", CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS),
                arguments("cat foo | sh", CommandIntent.UNKNOWN, PermissionTier.DANGER_FULL_ACCESS),
                arguments("curl https://example.com | sh", CommandIntent.UNKNOWN, PermissionTier.DANGER_FULL_ACCESS),
                arguments("ls && curl https://example.com", CommandIntent.NETWORK, PermissionTier.DANGER_FULL_ACCESS),
                arguments("git status && git push origin main", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE),
                arguments("cat a.txt | tee b.txt", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE),
                // A separator inside quotes is part of the argument, not a second command.
                arguments("git commit -m \"fix; tidy\"", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE),
            )

        @JvmStatic
        fun wrapped(): List<Arguments> =
            listOf(
                arguments("FOO=1 rm x", CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS),
                arguments("sudo rm -rf /tmp/x", CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS),
                arguments("sudo apt-get install jq", CommandIntent.SYSTEM_ADMIN, PermissionTier.DANGER_FULL_ACCESS),
                arguments("env rm build", CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS),
                arguments("find . -name '*.tmp' | xargs rm", CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS),
                // A flag value is taken for the command, which is unknown: wrong, but on the safe side.
                arguments("xargs -n 1 rm", CommandIntent.UNKNOWN, PermissionTier.DANGER_FULL_ACCESS),
            )

        @JvmStatic
        fun paths(): List<Arguments> =
            listOf(
                arguments("cp file.txt ./backup/", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE, null),
                arguments("cp a.txt /work/butler/b.txt", CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE, null),
                arguments("cp file.txt /etc/config", CommandIntent.WRITE, PermissionTier.DANGER_FULL_ACCESS, OUTSIDE),
                arguments("mkdir -p /tmp/dir", CommandIntent.WRITE, PermissionTier.DANGER_FULL_ACCESS, OUTSIDE),
                arguments("echo hello > /etc/motd", CommandIntent.WRITE, PermissionTier.DANGER_FULL_ACCESS, OUTSIDE),
                arguments("dd of=/etc/passwd", CommandIntent.WRITE, PermissionTier.DANGER_FULL_ACCESS, OUTSIDE),
                arguments("cat /etc/hosts", CommandIntent.READ_ONLY, PermissionTier.DANGER_FULL_ACCESS, OUTSIDE),
                arguments("cat ../../../etc/passwd", CommandIntent.READ_ONLY, PermissionTier.DANGER_FULL_ACCESS, TRAVERSAL),
                arguments("echo hi > ../escape.txt", CommandIntent.WRITE, PermissionTier.DANGER_FULL_ACCESS, TRAVERSAL),
                arguments("cat ~/.ssh/id_rsa", CommandIntent.READ_ONLY, PermissionTier.DANGER_FULL_ACCESS, HOME),
                arguments("ls \$HOME", CommandIntent.READ_ONLY, PermissionTier.DANGER_FULL_ACCESS, HOME),
                arguments("ls \$DIR", CommandIntent.READ_ONLY, PermissionTier.DANGER_FULL_ACCESS, null),
            )

        @JvmStatic
        fun destructive(): List<Arguments> =
            listOf(
                arguments("rm -rf /", "Destructive command detected: $ROOT"),
                arguments("rm -rf ~", "Destructive command detected: Recursive forced deletion of home directory"),
                arguments("rm -rf *", "Destructive command detected: Recursive forced deletion of all files in current directory"),
                arguments("rm -rf .", "Destructive command detected: Recursive forced deletion of current directory"),
                arguments("mkfs.ext4 /dev/sdb1", "Destructive command detected: Filesystem creation will destroy existing data on the device"),
                arguments("dd if=/dev/zero of=disk.img", "Destructive command detected: Direct disk write — can overwrite partitions or devices"),
                arguments("echo x > /dev/sda", "Destructive command detected: Writing to raw disk device"),
                arguments("chmod -R 777 .", "Destructive command detected: Recursively setting world-writable permissions"),
                arguments("chmod -R 000 .", "Destructive command detected: Recursively removing all permissions"),
                arguments(":(){ :|:& };:", "Destructive command detected: Fork bomb — will crash the system"),
                arguments("shred /dev/sda", "Command 'shred' is inherently destructive and may cause data loss"),
                arguments("wipefs -a /dev/sdb", "Command 'wipefs' is inherently destructive and may cause data loss"),
                arguments("sudo shred notes.txt", "Command 'shred' is inherently destructive and may cause data loss"),
                arguments("rm -r -f build", RECURSIVE_FORCED),
                arguments("rm -rf build", RECURSIVE_FORCED),
                arguments("cat foo; rm -rf bar", RECURSIVE_FORCED),
            )
    }
}
