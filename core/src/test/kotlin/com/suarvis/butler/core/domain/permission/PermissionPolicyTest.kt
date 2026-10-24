package com.suarvis.butler.core.domain.permission

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.shell.BashRisk
import com.suarvis.butler.core.domain.shell.CommandIntent
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.PermissionTier
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.domain.tool.ToolInvocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionPolicyTest {
    private val notes = WorkspacePath("notes.md")
    private val readNotes = ToolInvocation.ReadFile(notes)
    private val writeNotes = ToolInvocation.WriteFile(notes, "# Notes")

    private enum class Verdict { ALLOW, ASK, DENY }

    /** One built-in call per tier, in tier order: read_file, write_file and bash without a classified risk. */
    private val callPerTier: List<Pair<ToolDefinition, ToolInvocation>> =
        listOf(
            BuiltInTools.READ_FILE to readNotes,
            BuiltInTools.WRITE_FILE to writeNotes,
            BuiltInTools.BASH to ToolInvocation.Bash("ls"),
        )

    /**
     * Clawcode's ladder: for each mode, the verdict for a read-only, a workspace-write and a
     * danger-full-access call. One Butler departure: `prompt` lets read-only calls through.
     */
    private val ladder: Map<PermissionMode, List<Verdict>> =
        mapOf(
            PermissionMode.READ_ONLY to listOf(Verdict.ALLOW, Verdict.DENY, Verdict.DENY),
            PermissionMode.WORKSPACE_WRITE to listOf(Verdict.ALLOW, Verdict.ALLOW, Verdict.ASK),
            PermissionMode.DANGER_FULL_ACCESS to listOf(Verdict.ALLOW, Verdict.ALLOW, Verdict.ALLOW),
            PermissionMode.PROMPT to listOf(Verdict.ALLOW, Verdict.ASK, Verdict.ASK),
            PermissionMode.ALLOW to listOf(Verdict.ALLOW, Verdict.ALLOW, Verdict.ALLOW),
        )

    private fun verdictOf(decision: PermissionDecision): Verdict =
        when (decision) {
            PermissionDecision.Allow -> Verdict.ALLOW
            is PermissionDecision.Ask -> Verdict.ASK
            is PermissionDecision.Deny -> Verdict.DENY
        }

    private fun rules(
        allow: List<String> = emptyList(),
        deny: List<String> = emptyList(),
        ask: List<String> = emptyList(),
    ) = PermissionRules(allow.map(ToolPatterns::parse), deny.map(ToolPatterns::parse), ask.map(ToolPatterns::parse))

    private fun bash(
        command: String,
        mode: PermissionMode,
        rules: PermissionRules = PermissionRules.NONE,
        risk: BashRisk? = null,
    ) = PermissionPolicy.decide(ToolInvocation.Bash(command), BuiltInTools.BASH, mode, rules, risk)

    private fun mcpTool(tier: PermissionTier) = ToolDefinition(ToolName("mcp__fetch__get"), "Fetch a URL.", JsonText("{}"), tier)

    /** The decoding adaptor picks the subject from MCP input (Clawcode's key lookup); here it is the url. */
    private fun mcpCall(url: String) = ToolInvocation.External(ToolName("mcp__fetch__get"), JsonText("""{"url":"$url"}"""), subject = url)

    @Test
    fun `every mode against every tier decides as Clawcode's ladder does`() {
        assertEquals(PermissionMode.entries.toSet(), ladder.keys)
        ladder.forEach { (mode, verdicts) ->
            callPerTier.zip(verdicts).forEach { (call, verdict) ->
                val (tool, invocation) = call
                val decision = PermissionPolicy.decide(invocation, tool, mode, PermissionRules.NONE)

                assertEquals(verdict, verdictOf(decision), "${tool.name} (${tool.tier}) in $mode: $decision")
            }
        }
    }

    @Test
    fun `the built-in calls used for the ladder cover each tier once`() {
        assertEquals(PermissionTier.entries, callPerTier.map { it.first.tier })
    }

    @Test
    fun `modes cover tiers by name, never by declaration order`() {
        PermissionTier.entries.forEach { tier ->
            assertTrue(PermissionMode.ALLOW.covers(tier), "allow covers $tier")
            assertTrue(PermissionMode.DANGER_FULL_ACCESS.covers(tier), "danger-full-access covers $tier")
        }
        assertTrue(PermissionMode.PROMPT.covers(PermissionTier.READ_ONLY))
        assertFalse(PermissionMode.PROMPT.covers(PermissionTier.WORKSPACE_WRITE), "prompt must ask before a write")
        assertFalse(PermissionMode.PROMPT.covers(PermissionTier.DANGER_FULL_ACCESS), "prompt must ask before full access")
        assertTrue(PermissionMode.WORKSPACE_WRITE.covers(PermissionTier.WORKSPACE_WRITE))
        assertFalse(PermissionMode.WORKSPACE_WRITE.covers(PermissionTier.DANGER_FULL_ACCESS))
        assertTrue(PermissionMode.READ_ONLY.covers(PermissionTier.READ_ONLY))
        assertFalse(PermissionMode.READ_ONLY.covers(PermissionTier.WORKSPACE_WRITE))
    }

    @Test
    fun `a mode too low to escalate from is denied with Clawcode's reason`() {
        assertEquals(
            PermissionDecision.Deny("tool 'write_file' requires workspace-write permission; current mode is read-only"),
            PermissionPolicy.decide(writeNotes, BuiltInTools.WRITE_FILE, PermissionMode.READ_ONLY, PermissionRules.NONE),
        )
        assertEquals(
            PermissionDecision.Deny("tool 'bash' requires danger-full-access permission; current mode is read-only"),
            bash("ls", PermissionMode.READ_ONLY),
        )
    }

    @Test
    fun `workspace-write meeting danger-full-access asks with Clawcode's reason`() {
        assertEquals(
            PermissionDecision.Ask("tool 'bash' requires approval to escalate from workspace-write to danger-full-access"),
            bash("echo hi", PermissionMode.WORKSPACE_WRITE),
        )
    }

    @Test
    fun `prompt mode lets read-only tools through and asks for anything more`() {
        assertEquals(PermissionDecision.Allow, PermissionPolicy.decide(readNotes, BuiltInTools.READ_FILE, PermissionMode.PROMPT, PermissionRules.NONE))
        assertEquals(
            PermissionDecision.Ask("tool 'write_file' requires approval to escalate from prompt to workspace-write"),
            PermissionPolicy.decide(writeNotes, BuiltInTools.WRITE_FILE, PermissionMode.PROMPT, PermissionRules.NONE),
        )
    }

    @Test
    fun `allow and deny rules decide bash commands by prefix`() {
        // Clawcode's applies_rule_based_denials_and_allows.
        val rules = rules(allow = listOf("bash(git:*)"), deny = listOf("bash(rm -rf:*)"))

        assertEquals(PermissionDecision.Allow, bash("git status", PermissionMode.READ_ONLY, rules))
        assertEquals(
            PermissionDecision.Deny("Permission to use bash has been denied by rule 'bash(rm -rf:*)'"),
            bash("rm -rf /tmp/x", PermissionMode.READ_ONLY, rules),
        )
    }

    @Test
    fun `a deny rule beats an ask rule and an allow rule for the same call, in every mode`() {
        val rules = rules(allow = listOf("bash(git:*)"), deny = listOf("bash(git push:*)"), ask = listOf("bash(git:*)"))

        PermissionMode.entries.forEach { mode ->
            assertEquals(
                PermissionDecision.Deny("Permission to use bash has been denied by rule 'bash(git push:*)'"),
                bash("git push origin main", mode, rules),
                "$mode",
            )
        }
    }

    @Test
    fun `an ask rule beats an allow rule`() {
        val rules = rules(allow = listOf("bash(git:*)"), ask = listOf("bash(git:*)"))

        assertEquals(
            PermissionDecision.Ask("tool 'bash' requires approval due to ask rule 'bash(git:*)'"),
            bash("git status", PermissionMode.DANGER_FULL_ACCESS, rules),
        )
    }

    @Test
    fun `an ask rule still asks in modes that would allow the call`() {
        // Clawcode's ask_rules_force_prompt_even_when_mode_allows, plus allow mode.
        val rules = rules(ask = listOf("bash(git:*)"))

        listOf(PermissionMode.DANGER_FULL_ACCESS, PermissionMode.ALLOW).forEach { mode ->
            assertEquals(
                PermissionDecision.Ask("tool 'bash' requires approval due to ask rule 'bash(git:*)'"),
                bash("git status", mode, rules),
                "$mode",
            )
        }
    }

    @Test
    fun `an allow rule outranks read-only and prompt modes, for that call only`() {
        val rules = rules(allow = listOf("bash(git status)", "read_file"))

        assertEquals(PermissionDecision.Allow, bash("git status", PermissionMode.READ_ONLY, rules))
        assertEquals(PermissionDecision.Allow, bash("git status", PermissionMode.PROMPT, rules))
        assertEquals(PermissionDecision.Allow, PermissionPolicy.decide(readNotes, BuiltInTools.READ_FILE, PermissionMode.PROMPT, rules))
        assertEquals(Verdict.DENY, verdictOf(bash("git status --short", PermissionMode.READ_ONLY, rules)))
        assertEquals(Verdict.ASK, verdictOf(bash("git status --short", PermissionMode.PROMPT, rules)))
    }

    @Test
    fun `a deny rule without an argument refuses every call of the tool, even in allow mode`() {
        // Clawcode's denied_tools_denies_listed_tools_unconditionally, written as deny rules.
        val rules = rules(deny = listOf("bash", "write_file"))

        assertEquals(
            PermissionDecision.Deny("Permission to use bash has been denied by rule 'bash'"),
            bash("echo hello", PermissionMode.ALLOW, rules),
        )
        assertEquals(
            PermissionDecision.Deny("Permission to use write_file has been denied by rule 'write_file'"),
            PermissionPolicy.decide(writeNotes, BuiltInTools.WRITE_FILE, PermissionMode.ALLOW, rules),
        )
        assertEquals(PermissionDecision.Allow, PermissionPolicy.decide(readNotes, BuiltInTools.READ_FILE, PermissionMode.ALLOW, rules))
    }

    @Test
    fun `rules for one tool never decide another`() {
        val rules = rules(deny = listOf("write_file"), ask = listOf("edit_file"), allow = listOf("bash(ls)"))

        assertEquals(PermissionDecision.Allow, PermissionPolicy.decide(readNotes, BuiltInTools.READ_FILE, PermissionMode.READ_ONLY, rules))
        assertEquals(Verdict.DENY, verdictOf(bash("rm notes.md", PermissionMode.READ_ONLY, rules)))
    }

    @Test
    fun `file rules match the workspace-relative path`() {
        val rules = rules(allow = listOf("write_file(docs/:*)"), deny = listOf("edit_file(build.gradle.kts)"))
        val guide = ToolInvocation.WriteFile(WorkspacePath("docs/guide.md"), "# Guide")
        val buildScript = ToolInvocation.EditFile(WorkspacePath("build.gradle.kts"), "21", "25")

        assertEquals(PermissionDecision.Allow, PermissionPolicy.decide(guide, BuiltInTools.WRITE_FILE, PermissionMode.READ_ONLY, rules))
        assertEquals(Verdict.DENY, verdictOf(PermissionPolicy.decide(writeNotes, BuiltInTools.WRITE_FILE, PermissionMode.READ_ONLY, rules)))
        assertEquals(
            PermissionDecision.Deny("Permission to use edit_file has been denied by rule 'edit_file(build.gradle.kts)'"),
            PermissionPolicy.decide(buildScript, BuiltInTools.EDIT_FILE, PermissionMode.DANGER_FULL_ACCESS, rules),
        )
    }

    @Test
    fun `search rules match the path when given, otherwise the pattern`() {
        val rules = rules(deny = listOf("grep_search(secrets:*)", "glob_search(**/*.pem)"))
        val underSecrets = ToolInvocation.GrepSearch("password", under = WorkspacePath("secrets/prod"))
        val anywhere = ToolInvocation.GrepSearch("password")
        val pemFiles = ToolInvocation.GlobSearch("**/*.pem")

        assertEquals(Verdict.DENY, verdictOf(PermissionPolicy.decide(underSecrets, BuiltInTools.GREP_SEARCH, PermissionMode.ALLOW, rules)))
        assertEquals(PermissionDecision.Allow, PermissionPolicy.decide(anywhere, BuiltInTools.GREP_SEARCH, PermissionMode.ALLOW, rules))
        assertEquals(Verdict.DENY, verdictOf(PermissionPolicy.decide(pemFiles, BuiltInTools.GLOB_SEARCH, PermissionMode.ALLOW, rules)))
    }

    @Test
    fun `rule tool names compare without case`() {
        assertEquals(Verdict.DENY, verdictOf(bash("ls", PermissionMode.ALLOW, rules(deny = listOf("Bash")))))
    }

    @Test
    fun `a classified bash command needs its own tier instead of the tool's`() {
        val readOnly = BashRisk(CommandIntent.READ_ONLY, PermissionTier.READ_ONLY)
        val write = BashRisk(CommandIntent.WRITE, PermissionTier.WORKSPACE_WRITE)
        val destructive = BashRisk(CommandIntent.DESTRUCTIVE, PermissionTier.DANGER_FULL_ACCESS, warning = "deletes files")

        assertEquals(PermissionDecision.Allow, bash("cat notes.md", PermissionMode.READ_ONLY, risk = readOnly))
        assertEquals(PermissionDecision.Allow, bash("mkdir build", PermissionMode.WORKSPACE_WRITE, risk = write))
        assertEquals(
            PermissionDecision.Deny("tool 'bash' requires workspace-write permission; current mode is read-only"),
            bash("mkdir build", PermissionMode.READ_ONLY, risk = write),
        )
        assertEquals(
            PermissionDecision.Ask("tool 'bash' requires approval to escalate from workspace-write to danger-full-access"),
            bash("rm -rf build", PermissionMode.WORKSPACE_WRITE, risk = destructive),
        )
        assertEquals(PermissionDecision.Allow, bash("cat notes.md", PermissionMode.PROMPT, risk = readOnly))
        assertEquals(
            PermissionDecision.Ask("tool 'bash' requires approval to escalate from prompt to workspace-write"),
            bash("mkdir build", PermissionMode.PROMPT, risk = write),
        )
    }

    @Test
    fun `rules still decide before a bash command's tier`() {
        val readOnly = BashRisk(CommandIntent.READ_ONLY, PermissionTier.READ_ONLY)

        assertEquals(Verdict.DENY, verdictOf(bash("cat .env", PermissionMode.ALLOW, rules(deny = listOf("bash(cat .env)")), readOnly)))
        assertEquals(Verdict.ASK, verdictOf(bash("cat notes.md", PermissionMode.READ_ONLY, rules(ask = listOf("bash(cat:*)")), readOnly)))
    }

    @Test
    fun `a bash risk passed with another tool's call is ignored`() {
        val readOnly = BashRisk(CommandIntent.READ_ONLY, PermissionTier.READ_ONLY)

        assertEquals(
            PermissionDecision.Deny("tool 'write_file' requires workspace-write permission; current mode is read-only"),
            PermissionPolicy.decide(writeNotes, BuiltInTools.WRITE_FILE, PermissionMode.READ_ONLY, PermissionRules.NONE, readOnly),
        )
    }

    @Test
    fun `external tools need the tier they were registered with`() {
        val call = mcpCall("https://example.com/a")

        assertEquals(PermissionDecision.Allow, PermissionPolicy.decide(call, mcpTool(PermissionTier.READ_ONLY), PermissionMode.READ_ONLY, PermissionRules.NONE))
        assertEquals(
            PermissionDecision.Ask("tool 'mcp__fetch__get' requires approval to escalate from workspace-write to danger-full-access"),
            PermissionPolicy.decide(call, mcpTool(PermissionTier.DANGER_FULL_ACCESS), PermissionMode.WORKSPACE_WRITE, PermissionRules.NONE),
        )
    }

    @Test
    fun `external tool rules match the subject picked from the input`() {
        val tool = mcpTool(PermissionTier.DANGER_FULL_ACCESS)
        val rules = rules(allow = listOf("mcp__fetch__get(https://example.com/:*)"), deny = listOf("mcp__fetch__get(https://evil.test/:*)"))

        assertEquals(
            PermissionDecision.Allow,
            PermissionPolicy.decide(mcpCall("https://example.com/a"), tool, PermissionMode.READ_ONLY, rules),
        )
        assertEquals(
            PermissionDecision.Deny("Permission to use mcp__fetch__get has been denied by rule 'mcp__fetch__get(https://evil.test/:*)'"),
            PermissionPolicy.decide(mcpCall("https://evil.test/x"), tool, PermissionMode.ALLOW, rules),
        )
        assertEquals(
            Verdict.DENY,
            verdictOf(PermissionPolicy.decide(mcpCall("https://other.test"), tool, PermissionMode.READ_ONLY, rules)),
        )
    }

    @Test
    fun `an external tool rule without an argument matches every call of it`() {
        val decision =
            PermissionPolicy.decide(ToolInvocation.External(ToolName("mcp__fetch__get"), JsonText("[1, 2]")), mcpTool(PermissionTier.READ_ONLY), PermissionMode.ALLOW, rules(ask = listOf("mcp__fetch__get")))

        assertEquals(PermissionDecision.Ask("tool 'mcp__fetch__get' requires approval due to ask rule 'mcp__fetch__get'"), decision)
    }

    @Test
    fun `permission modes carry Clawcode's CLI names`() {
        assertEquals(
            listOf("read-only", "workspace-write", "danger-full-access", "prompt", "allow"),
            PermissionMode.entries.map { it.cliName },
        )
        assertEquals(listOf("read-only", "workspace-write", "danger-full-access"), PermissionTier.entries.map { it.cliName })
    }
}
