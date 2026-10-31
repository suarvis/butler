package com.suarvis.butler.api.cli

import com.suarvis.butler.api.cli.command.PermissionFlags
import com.suarvis.butler.api.cli.exception.CliUsageException
import com.suarvis.butler.api.cli.repl.SlashCommand
import com.suarvis.butler.api.cli.repl.SlashCommandParser
import com.suarvis.butler.api.cli.support.ButlerCli
import com.suarvis.butler.api.cli.support.ProviderDialect
import com.suarvis.butler.api.cli.support.ScriptedToolCall
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.permission.PermissionMode
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.PermissionTier
import com.suarvis.butler.core.domain.tool.ToolDefinition
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists

/** `--permission-mode`, `--dangerously-skip-permissions`, `--allowedTools` and `/permissions`, with Clawcode's values and wording. */
class PermissionFlagsTest {
    @Nested
    inner class Modes {
        @Test
        fun `accepts Butler's five labels`() {
            PermissionMode.entries.forEach { assertEquals(it, PermissionFlags.parseMode(it.cliName)) }
        }

        @Test
        fun `accepts Clawcode's aliases`() {
            val aliases =
                mapOf(
                    "default" to PermissionMode.READ_ONLY,
                    "plan" to PermissionMode.READ_ONLY,
                    "acceptEdits" to PermissionMode.WORKSPACE_WRITE,
                    "auto" to PermissionMode.WORKSPACE_WRITE,
                    "dontAsk" to PermissionMode.DANGER_FULL_ACCESS,
                    "bypassPermissions" to PermissionMode.DANGER_FULL_ACCESS,
                    "dangerFullAccess" to PermissionMode.DANGER_FULL_ACCESS,
                )
            aliases.forEach { (alias, mode) -> assertEquals(mode, PermissionFlags.parseMode(alias), alias) }
        }

        @Test
        fun `trims like Clawcode but keeps case`() {
            assertEquals(PermissionMode.READ_ONLY, PermissionFlags.parseMode(" plan "))
            assertNull(PermissionFlags.parseMode("Read-Only"))
            assertNull(PermissionFlags.parseMode("acceptedits"))
            assertNull(PermissionFlags.parseMode("bogus"))
            assertNull(PermissionFlags.parseMode(""))
        }
    }

    @Nested
    inner class AllowedTools {
        private val mcpTool = ToolDefinition(ToolName("mcp__docs__SearchPages"), "Search the docs.", JsonText("{}"), PermissionTier.READ_ONLY)
        private val tools = BuiltInTools.ALL + mcpTool

        private fun parse(vararg values: String): Set<String> = PermissionFlags.parseAllowedTools(values.toList(), tools).map { it.value }.toSet()

        @Test
        fun `splits on commas and spaces and maps Clawcode's aliases`() {
            assertEquals(setOf("read_file", "glob_search"), parse("read,glob"))
            assertEquals(setOf("read_file", "glob_search", "write_file"), parse("read glob", "write_file"))
            assertEquals(setOf("edit_file", "grep_search"), parse(" Edit ,, Grep "))
        }

        @Test
        fun `compares names as Clawcode does, ignoring case, dashes and camelCase humps`() {
            assertEquals(setOf("read_file", "edit_file", "bash"), parse("ReadFile,edit-file,BASH"))
        }

        @Test
        fun `hands core the MCP tool's own name`() {
            assertEquals(setOf("mcp__docs__SearchPages"), parse("mcp__docs__search_pages"))
            assertEquals(setOf("mcp__docs__SearchPages"), parse("mcp__docs__SearchPages"))
        }

        @Test
        fun `an unknown name is a usage error naming every unknown one`() {
            val error = assertThrows<CliUsageException> { parse("read,teleport", "warp") }

            assertEquals("invalid_tool_name", error.kind)
            assertEquals("unsupported tool in --allowedTools: teleport, warp", error.message)
            assertEquals(
                "Available: bash, edit_file, glob_search, grep_search, mcp__docs__search_pages, read_file, write_file\n" +
                    "Aliases: Edit=edit_file, Glob=glob_search, Grep=grep_search, Read=read_file, Write=write_file, " +
                    "edit=edit_file, glob=glob_search, grep=grep_search, mcp__docs__SearchPages=mcp__docs__search_pages, " +
                    "read=read_file, write=write_file\n" +
                    "Hint: Use canonical snake_case tool names from Available or aliases from Aliases.",
                error.hint,
            )
        }

        @Test
        fun `a list without names is refused with Clawcode's message`() {
            listOf("", ",,").forEach { raw ->
                val error = assertThrows<CliUsageException> { parse(raw) }
                assertEquals("missing_argument", error.kind)
                assertEquals("--allowedTools was provided with no usable tool names (got `$raw`). Omit the flag to allow all tools.", error.message)
            }
        }
    }

    @Nested
    inner class SlashPermissions {
        @Test
        fun `shows without an argument and switches with one`() {
            assertEquals(listOf(SlashCommand.Permissions(null)), SlashCommandParser.parse(listOf("/permissions")))
            assertEquals(listOf(SlashCommand.Permissions(PermissionMode.READ_ONLY)), SlashCommandParser.parse(listOf("/permissions", "read-only")))
            assertEquals(listOf(SlashCommand.Permissions(PermissionMode.WORKSPACE_WRITE)), SlashCommandParser.parse(listOf("/Permissions", "acceptEdits")))
            assertEquals(listOf(SlashCommand.Permissions(PermissionMode.ALLOW)), SlashCommandParser.parse(listOf("/permissions", "allow")))
        }

        @Test
        fun `rejects an unknown mode with Clawcode's message and extra arguments`() {
            val error = assertThrows<CliUsageException> { SlashCommandParser.parse(listOf("/permissions", "admin")) }
            assertEquals("invalid_permission_mode", error.kind)
            assertEquals("Unsupported /permissions mode 'admin'. Use read-only, workspace-write, danger-full-access, prompt, or allow.", error.message)
            assertEquals("Usage: /permissions [read-only|workspace-write|danger-full-access|prompt|allow]", error.hint)

            assertEquals("cli_parse", assertThrows<CliUsageException> { SlashCommandParser.parse(listOf("/permissions", "allow", "now")) }.kind)
        }
    }

    @Nested
    inner class EndToEnd {
        @TempDir
        lateinit var root: Path

        private lateinit var cli: ButlerCli

        @BeforeEach
        fun start() {
            cli = ButlerCli(root)
        }

        @AfterEach
        fun stop() {
            cli.close()
        }

        private fun lastToolResult() = ProviderDialect.ANTHROPIC_MESSAGES.toolResults(cli.anthropic.requestBodies().last()).single()

        @Test
        fun `an unknown mode is a usage error with Clawcode's kind and message`() {
            val run = cli.run("--output-format", "json", "--permission-mode", "bogus", "status")

            assertEquals(ExitCodes.USAGE, run.exitCode)
            assertEquals("invalid_permission_mode", run.json()["kind"]!!.jsonPrimitive.content)
            assertEquals("unsupported permission mode 'bogus'.", run.json()["error"]!!.jsonPrimitive.content)
            assertEquals(PermissionFlags.MODE_USAGE, run.json()["hint"]!!.jsonPrimitive.content)
        }

        @Test
        fun `in text mode the error goes to stderr`() {
            val run = cli.run("status", "--permission-mode=admin")

            assertEquals(ExitCodes.USAGE, run.exitCode)
            assertTrue(run.stderr.startsWith("[error-kind: invalid_permission_mode]\nerror: unsupported permission mode 'admin'."), run.stderr)
        }

        @Test
        fun `skipping permissions and naming a mode at once is refused`() {
            val run = cli.run("--output-format", "json", "--dangerously-skip-permissions", "status", "--permission-mode", "read-only")

            assertEquals(ExitCodes.USAGE, run.exitCode)
            assertEquals("cli_parse", run.json()["kind"]!!.jsonPrimitive.content)
        }

        @Test
        fun `an unknown tool is a usage error naming it`() {
            val run = cli.run("--output-format", "json", "--allowedTools", "read,teleport", "status")

            assertEquals(ExitCodes.USAGE, run.exitCode)
            assertEquals("invalid_tool_name", run.json()["kind"]!!.jsonPrimitive.content)
            assertEquals("unsupported tool in --allowedTools: teleport", run.json()["error"]!!.jsonPrimitive.content)
        }

        @Test
        fun `--allowedTools narrows the tools a turn offers, adding up across flags and levels`() {
            cli.property("butler.tools.enabled", "true")
            cli.anthropic.answer("ok")

            val run = cli.run("--allowedTools", "read", "prompt", "--allowed-tools=glob", "--output-format", "json", "list the sources")

            assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
            assertEquals(listOf("read_file", "glob_search"), ProviderDialect.ANTHROPIC_MESSAGES.offeredToolNames(cli.anthropic.requestBodies().last()))
        }

        @Test
        fun `--permission-mode reaches the turn, so read-only refuses a write`() {
            cli.property("butler.tools.enabled", "true")
            cli.anthropic.callTools(ScriptedToolCall("toolu_1", "write_file", """{"path":"notes.txt","content":"hi"}""")).answer("I could not write it.")

            val run = cli.run("--output-format", "json", "--permission-mode", "read-only", "prompt", "write a note")

            assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
            val result = lastToolResult()
            assertEquals("tool 'write_file' requires workspace-write permission; current mode is read-only", result.output)
            assertEquals(true, result.isError)
            assertFalse(cli.workspace.resolve("notes.txt").exists())
        }

        @Test
        fun `slash permissions shows the mode in Clawcode's layout`() {
            val run = cli.run(stdin = "/permissions\n")

            assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
            val expected =
                listOf(
                    "Permissions",
                    "  Active mode      prompt",
                    "  Mode status      live session default",
                    "",
                    "Modes",
                    "  read-only          ○ available Read/search tools only",
                    "  workspace-write    ○ available Edit files inside the workspace",
                    "  danger-full-access ○ available Unrestricted tool access",
                    "  prompt             ● current   Read freely; ask before anything else",
                    "  allow              ○ available Every tool call runs without asking",
                    "",
                    "Usage",
                    "  Inspect current mode with /permissions",
                    "  Switch modes with /permissions <mode>",
                ).joinToString("\n")
            assertEquals(expected, run.stdout.trim().replace(System.lineSeparator(), "\n"))
        }

        @Test
        fun `slash permissions switches for the rest of the session`() {
            val run = cli.run(stdin = "/permissions read-only\n/permissions\n/permissions read-only\n")

            assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
            val stdout = run.stdout.replace(System.lineSeparator(), "\n")
            val switched =
                listOf(
                    "Permissions updated",
                    "  Result           mode switched",
                    "  Previous mode    prompt",
                    "  Active mode      read-only",
                    "  Applies to       subsequent tool calls",
                    "  Usage            /permissions to inspect current mode",
                ).joinToString("\n")
            assertTrue(stdout.startsWith(switched), stdout)
            assertTrue(stdout.contains("  read-only          ● current   Read/search tools only"), stdout)
            // Asking for the mode already active shows the report again rather than a second switch.
            assertEquals(1, Regex("Permissions updated").findAll(stdout).count(), stdout)
        }

        @Test
        fun `a switched mode governs the next turn's tool calls`() {
            cli.property("butler.tools.enabled", "true")
            cli.anthropic.callTools(ScriptedToolCall("toolu_1", "write_file", """{"path":"notes.txt","content":"hi"}""")).answer("Not allowed.")

            val run = cli.run(stdin = "/permissions read-only\nwrite a note\n")

            assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
            assertEquals("tool 'write_file' requires workspace-write permission; current mode is read-only", lastToolResult().output)
        }

        @Test
        fun `the flags set the mode the REPL starts in`() {
            assertTrue(cli.run("--dangerously-skip-permissions", stdin = "/permissions\n").stdout.contains("Active mode      danger-full-access"))
            assertTrue(cli.run("--skip-permissions", stdin = "/permissions\n").stdout.contains("Active mode      danger-full-access"))
            assertTrue(cli.run("--permission-mode=acceptEdits", stdin = "/permissions\n").stdout.contains("Active mode      workspace-write"))
            assertTrue(cli.run("--permission-mode", "allow", stdin = "/permissions\n").stdout.contains("Active mode      allow"))
        }

        @Test
        fun `an unknown mode in the REPL is reported and the session goes on`() {
            val run = cli.run(stdin = "/permissions admin\n/permissions\n")

            assertEquals(ExitCodes.OK, run.exitCode)
            assertTrue(run.stderr.startsWith("[error-kind: invalid_permission_mode]"), run.stderr)
            assertTrue(run.stdout.contains("Active mode      prompt"), run.stdout)
        }
    }
}
