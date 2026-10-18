package com.suarvis.butler.api.cli

import com.suarvis.butler.api.ButlerApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Runs the real application (Spring context, picocli, file store) the way a user would, against
 * a temporary workspace seeded with sessions in Clawcode's JSONL format.
 */
class CliEndToEndTest {
    @TempDir
    lateinit var root: Path

    private lateinit var workspace: Path

    data class CliRun(val exitCode: Int, val stdout: String, val stderr: String) {
        fun json(): JsonObject = Json.parseToJsonElement(stdout.trim()).jsonObject
    }

    @BeforeEach
    fun seedWorkspace() {
        workspace = root.resolve("project").also { Files.createDirectories(it) }
        val sessions = workspace.resolve(".butler/sessions").also { Files.createDirectories(it) }
        sessions.resolve("$OLDER.jsonl").writeText(session(OLDER, updatedAtMs = 1_760_600_000_000))
        sessions.resolve("$NEWER.jsonl").writeText(session(NEWER, updatedAtMs = 1_760_690_004_000))
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
    fun `version reports itself as JSON`() {
        val run = butler("--output-format", "json", "version")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertEquals("version", run.json()["kind"]?.jsonPrimitive?.content)
        assertEquals("ok", run.json()["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `help prints usage`() {
        val run = butler("help")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertTrue(run.stdout.contains("Usage: butler"), run.stdout)
    }

    @Test
    fun `session list returns newest first in Clawcode's envelope`() {
        val run = butler("session", "list", "--output-format", "json")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        val json = run.json()
        assertEquals("sessions", json["kind"]?.jsonPrimitive?.content)
        assertEquals(listOf(NEWER, OLDER), json["sessions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(4, json["session_details"]!!.jsonArray.first().jsonObject["message_count"]!!.jsonPrimitive.int)
        assertEquals(2, json["page"]!!.jsonObject["total_items"]!!.jsonPrimitive.long)
    }

    @Test
    fun `status shows the latest session with usage totals`() {
        val run = butler("status", "--output-format", "json")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        val session = run.json()["session"]!!.jsonObject
        assertEquals(NEWER, session["id"]!!.jsonPrimitive.content)
        assertEquals(4, session["messages"]!!.jsonPrimitive.int)
        assertEquals(370, session["cumulative_usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.long)
        assertEquals("file", run.json()["workspace"]!!.jsonObject["persistence_store"]!!.jsonPrimitive.content)
    }

    @Test
    fun `status renders human-readable text by default`() {
        val run = butler("status")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertTrue(run.stdout.startsWith("Status"), run.stdout)
        assertTrue(run.stdout.contains(NEWER), run.stdout)
    }

    @Test
    fun `resume runs slash commands in order against a saved session`() {
        val run = butler("--resume", "latest", "/status", "/export", "notes.md")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        val notes = workspace.resolve("notes.md")
        assertTrue(notes.exists())
        assertTrue(notes.readText().contains("## 3. Tool result"), notes.readText())
        assertTrue(run.stdout.contains("Exported session $NEWER"), run.stdout)
    }

    @Test
    fun `resume fork creates a new Clawcode-format session file`() {
        val run = butler("--output-format", "json", "--resume", OLDER, "/session", "fork", "experiment")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        val fork = run.json()
        assertEquals("fork", fork["action"]?.jsonPrimitive?.content)
        assertEquals(OLDER, fork["parent_session_id"]?.jsonPrimitive?.content)
        val files = workspace.resolve(".butler/sessions").listDirectoryEntries("*.jsonl")
        assertEquals(3, files.size)
        val forkFile = files.single { it.fileName.toString().startsWith(fork["session_id"]!!.jsonPrimitive.content) }
        assertTrue(forkFile.readText().startsWith("""{"type":"session_meta","version":3"""))
    }

    @Test
    fun `export without a path writes Markdown to stdout`() {
        val run = butler("export", "--session", OLDER)

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertTrue(run.stdout.startsWith("# Conversation export"), run.stdout)
        assertTrue(run.stdout.contains("Session: `$OLDER`"), run.stdout)
    }

    @Test
    fun `delete refuses without --force and succeeds with it`() {
        val refused = butler("--output-format", "json", "session", "delete", OLDER)
        assertEquals(ExitCodes.USAGE, refused.exitCode)
        assertEquals("confirmation_required", refused.json()["error_kind"]?.jsonPrimitive?.content)

        val deleted = butler("session", "delete", OLDER, "--force")
        assertEquals(ExitCodes.OK, deleted.exitCode, deleted.stderr)
        assertFalse(workspace.resolve(".butler/sessions/$OLDER.jsonl").exists())
    }

    @Test
    fun `missing sessions produce a JSON error on stdout and exit code 1`() {
        val run = butler("--output-format", "json", "status", "--session", "session-nope")

        assertEquals(ExitCodes.RUNTIME, run.exitCode)
        val error = run.json()
        assertEquals("error", error["type"]?.jsonPrimitive?.content)
        assertEquals("session_not_found", error["kind"]?.jsonPrimitive?.content)
        assertEquals(1, error["exit_code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `text errors go to stderr with a machine-readable kind`() {
        val run = butler("export", "--session", "session-nope")

        assertEquals(ExitCodes.RUNTIME, run.exitCode)
        assertTrue(run.stdout.isBlank(), run.stdout)
        assertTrue(run.stderr.startsWith("[error-kind: session_not_found]"), run.stderr)
    }

    @Test
    fun `unknown flags are usage errors with exit code 2`() {
        val run = butler("--output-format", "json", "--bogus")

        assertEquals(ExitCodes.USAGE, run.exitCode)
        assertEquals("unknown_argument", run.json()["kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an invalid output format is reported as such`() {
        val run = butler("--output-format", "xml", "status")

        assertEquals(ExitCodes.USAGE, run.exitCode)
        assertTrue(run.stderr.startsWith("[error-kind: invalid_output_format]"), run.stderr)
    }

    @Test
    fun `the filesystem root is refused as a workspace`() {
        val run = butler("--output-format", "json", "status", "--cwd", "/")

        assertEquals(ExitCodes.RUNTIME, run.exitCode)
        assertEquals("broad_cwd", run.json()["kind"]?.jsonPrimitive?.content)
    }

    private companion object {
        const val OLDER = "session-1760600000000-0"
        const val NEWER = "session-1760690000000-0"

        fun session(id: String, updatedAtMs: Long): String =
            """
            {"type":"session_meta","version":3,"session_id":"$id","updated_at_ms":$updatedAtMs,"model":"sonnet"}
            {"type":"message","message":{"role":"user","blocks":[{"type":"text","text":"Explain the README"}]}}
            {"type":"message","message":{"role":"assistant","blocks":[{"type":"tool_use","id":"toolu_1","name":"read_file","input":"{\"path\":\"README.md\"}"}],"usage":{"input_tokens":100,"output_tokens":20,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}
            {"type":"message","message":{"role":"tool","blocks":[{"type":"tool_result","tool_use_id":"toolu_1","tool_name":"read_file","output":"# Demo","is_error":false}]}}
            {"type":"message","message":{"role":"assistant","blocks":[{"type":"text","text":"The README is a heading."}],"usage":{"input_tokens":150,"output_tokens":10,"cache_creation_input_tokens":0,"cache_read_input_tokens":90}}}
            {"type":"prompt_history","timestamp_ms":1760690001000,"text":"Explain the README"}
            """.trimIndent() + "\n"
    }
}
