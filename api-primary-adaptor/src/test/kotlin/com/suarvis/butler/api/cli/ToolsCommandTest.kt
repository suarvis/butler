package com.suarvis.butler.api.cli

import com.suarvis.butler.api.ButlerApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/** `butler tools` end to end: the real application lists the built-in tools (no MCP servers are configured). */
class ToolsCommandTest {
    @TempDir
    lateinit var workspace: Path

    data class CliRun(val exitCode: Int, val stdout: String, val stderr: String) {
        fun json(): JsonObject = Json.parseToJsonElement(stdout.trim()).jsonObject
    }

    @BeforeEach
    fun switchToolsOff() {
        // Set explicitly, so a BUTLER_TOOLS_ENABLED in the developer's shell cannot change the result.
        System.setProperty(TOOLS_ENABLED_PROPERTY, "false")
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty(TOOLS_ENABLED_PROPERTY)
    }

    private fun butler(vararg args: String): CliRun {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(PrintStream(stdout, true, Charsets.UTF_8))
        System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
        val exitCode =
            try {
                ButlerApplication.run("--cwd", workspace.toString(), *args)
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
            }
        return CliRun(exitCode, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
    }

    @Test
    fun `text lists each built-in tool with its tier and the switched-off note`() {
        val run = butler("tools")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        val lines = run.stdout.lines()
        assertEquals("Available tools (6):", lines.first())
        listOf(
            Regex("  bash +danger-full-access +Execute a shell command in the current workspace\\."),
            Regex("  read_file +read-only +Read a text file from the workspace\\."),
            Regex("  write_file +workspace-write +Write a text file in the workspace\\."),
            Regex("  edit_file +workspace-write +Replace text in a workspace file\\."),
            Regex("  glob_search +read-only +Find files by glob pattern\\."),
            Regex("  grep_search +read-only +Search file contents with a regex pattern\\."),
        ).forEachIndexed { index, row -> assertTrue(row.matches(lines[index + 1]), lines[index + 1]) }
        assertTrue(run.stdout.contains("Tools are switched off"), run.stdout)
    }

    @Test
    fun `JSON follows Clawcode's tools envelope with tier, source and the switch`() {
        val run = butler("tools", "--output-format", "json")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        val json = run.json()
        assertEquals("tools", json["kind"]!!.jsonPrimitive.content)
        assertEquals("list", json["action"]!!.jsonPrimitive.content)
        assertEquals("ok", json["status"]!!.jsonPrimitive.content)
        assertFalse(json["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(6, json["count"]!!.jsonPrimitive.int)
        val tools = json["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("bash", "read_file", "write_file", "edit_file", "glob_search", "grep_search"),
            tools.map { it["name"]!!.jsonPrimitive.content },
        )
        assertEquals(
            listOf("danger-full-access", "read-only", "workspace-write", "workspace-write", "read-only", "read-only"),
            tools.map { it["tier"]!!.jsonPrimitive.content },
        )
        assertTrue(tools.all { it["source"]!!.jsonPrimitive.content == "built-in" })
        assertEquals("Read a text file from the workspace.", tools[1]["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `with tools switched on there is no note and JSON says so`() {
        System.setProperty(TOOLS_ENABLED_PROPERTY, "true")

        val text = butler("tools")
        val json = butler("--output-format", "json", "tools")

        assertEquals(ExitCodes.OK, text.exitCode, text.stderr)
        assertFalse(text.stdout.contains("switched off"), text.stdout)
        assertTrue(json.json()["enabled"]!!.jsonPrimitive.boolean)
    }

    private companion object {
        const val TOOLS_ENABLED_PROPERTY = "butler.tools.enabled"
    }
}
