package com.suarvis.butler.api.cli

import com.suarvis.butler.api.cli.support.ButlerCli
import com.suarvis.butler.api.cli.support.ProviderDialect
import com.suarvis.butler.api.cli.support.ScriptedToolCall
import kotlinx.serialization.json.JsonObject
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
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * D5: a script is never asked. In JSON output every call the turn would have asked about is
 * denied, reaches the model as an error result, and is listed under `permission_denials`; the
 * turn still finishes with exit code 0.
 */
class PermissionDenialsTest {
    @TempDir
    lateinit var root: Path

    private lateinit var cli: ButlerCli

    @BeforeEach
    fun start() {
        cli = ButlerCli(root)
        cli.property("butler.tools.enabled", "true")
    }

    @AfterEach
    fun stop() {
        cli.close()
    }

    @Test
    fun `a write in prompt mode is denied without asking and listed in the JSON result`() {
        cli.anthropic
            .callTools(ScriptedToolCall("toolu_1", "write_file", """{"path":"notes.txt","content":"hi"}"""))
            .answer("I could not write it.")

        val run = cli.run("--output-format", "json", "prompt", "write a note")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        val denial = run.json().objects("permission_denials").single()
        assertEquals("write_file", denial.text("tool"))
        assertEquals("toolu_1", denial.text("tool_use_id"))
        assertEquals("tool 'write_file' requires approval to escalate from prompt to workspace-write", denial.text("reason"))
        // The model got Clawcode's denial as an error result, and nothing was written.
        val result = ProviderDialect.ANTHROPIC_MESSAGES.toolResults(cli.anthropic.requestBodies().last()).single()
        assertEquals("tool 'write_file' denied by user approval prompt", result.output)
        assertEquals(true, result.isError)
        assertFalse(cli.workspace.resolve("notes.txt").exists())
        // With JSON output the denial is in the result only; the text-mode notice on stderr is not printed.
        assertFalse(run.stderr.contains("denied write_file"), run.stderr)
    }

    @Test
    fun `the saved session holds the denial as an error result`() {
        cli.anthropic
            .callTools(ScriptedToolCall("toolu_1", "write_file", """{"path":"notes.txt","content":"hi"}"""))
            .answer("I could not write it.")
        cli.run("--output-format", "json", "prompt", "write a note")
        cli.anthropic.answer("Still no.")

        val next = cli.run("--output-format", "json", "prompt", "--session", "latest", "and now?")

        assertEquals(ExitCodes.OK, next.exitCode, next.stderr)
        val carried = ProviderDialect.ANTHROPIC_MESSAGES.toolResults(cli.anthropic.requestBodies().last()).single()
        assertEquals("toolu_1", carried.callId)
        assertEquals(true, carried.isError)
        // A later turn without denials lists none of the earlier ones.
        assertTrue(next.json().objects("permission_denials").isEmpty())
    }

    @Test
    fun `reads never ask, so a turn that only reads lists no denials`() {
        cli.writeFile("README.md", "# Demo\n")
        cli.anthropic
            .callTools(ScriptedToolCall("toolu_1", "read_file", """{"path":"README.md"}"""))
            .answer("A heading.")

        val run = cli.run("--output-format", "json", "prompt", "read the readme")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertTrue(run.json().containsKey("permission_denials"), run.stdout)
        assertTrue(run.json().objects("permission_denials").isEmpty())
    }

    @Test
    fun `a call the mode refuses outright is not a denial of an ask`() {
        cli.anthropic
            .callTools(ScriptedToolCall("toolu_1", "write_file", """{"path":"notes.txt","content":"hi"}"""))
            .answer("Not allowed.")

        val run = cli.run("--output-format", "json", "--permission-mode", "read-only", "prompt", "write a note")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertTrue(run.json().objects("permission_denials").isEmpty())
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.objects(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }
}
