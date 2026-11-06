package com.suarvis.butler.api.cli

import com.suarvis.butler.api.cli.support.ButlerCli
import com.suarvis.butler.api.cli.support.CliRun
import com.suarvis.butler.api.cli.support.NativeToolResult
import com.suarvis.butler.api.cli.support.ProviderDialect
import com.suarvis.butler.api.cli.support.ScriptedReply
import com.suarvis.butler.api.cli.support.ScriptedToolCall
import com.suarvis.butler.api.cli.support.ScriptedUsage
import com.suarvis.butler.core.domain.tool.BuiltInTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Gate M1 (J1): Butler reads and searches the workspace through tool calls on all three provider
 * dialects. Each story runs the real CLI against the shared fakes, which answer first with scripted
 * tool calls and then with text, and follows one of Clawcode's mock parity scenarios
 * (`rust/mock_parity_scenarios.json` and `mock_parity_harness.rs`, commit 22fe360) where one exists,
 * with the same fixture, call ids and argument pieces.
 *
 * The checks are on the wire (what each request offered and carried back), the JSON result and the
 * saved session, never on screen wording, which belongs to lane I. Tools are deliberately not
 * switched on here: the stories prove the shipped default.
 */
class ToolsEndToEndTest {
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

    @ParameterizedTest
    @EnumSource(ProviderDialect::class)
    fun `read_file_roundtrip - the file goes back as a native tool result, the answer comes out and both are saved`(dialect: ProviderDialect) {
        cli.writeFile("fixture.txt", "alpha parity line\n")
        val read = ScriptedToolCall("toolu_read_fixture", "read_file", """{"path":"fixture.txt"}""")
        cli.fake(dialect).callTools(read).answer("read_file roundtrip complete: ", "alpha parity line")

        val run = prompt(dialect, "Read fixture.txt")

        val (first, second) = requests(dialect, count = 2)
        // Request 1 offers Clawcode's six tools in Clawcode's order, with their schemas unchanged.
        assertEquals(BuiltInTools.ALL.map { it.name.value }, dialect.offeredToolNames(first))
        assertEquals(BuiltInTools.ALL.associate { it.name.value to Json.parseToJsonElement(it.inputSchema.value) }, offeredSchemas(dialect, first))
        assertTrue(dialect.toolResults(first).isEmpty())
        // Request 2 offers the same tools, replays the call and carries the file natively.
        assertEquals(dialect.offeredToolNames(first), dialect.offeredToolNames(second))
        assertEquals(listOf(read.id), dialect.replayedToolCallIds(second))
        val result = dialect.toolResults(second).single()
        assertEquals(read.id, result.callId)
        assertEquals(successFlag(dialect), result.isError)
        val file = Json.parseToJsonElement(result.output).jsonObject.getValue("file").jsonObject
        assertEquals("alpha parity line", file.text("content"))
        assertEquals(cli.workspace.resolve("fixture.txt").toRealPath().toString(), file.text("filePath"))

        val json = run.json()
        assertEquals("read_file roundtrip complete: alpha parity line", json.text("message"))
        assertEquals(2, json.getValue("iterations").jsonPrimitive.int)
        assertCallAndResult(json, read, result.output, isError = false)
        assertEquals(listOf("user", "assistant", "tool", "assistant"), savedMessages().map { it.text("role") })
    }

    @ParameterizedTest
    @EnumSource(ProviderDialect::class)
    fun `multi_tool_turn_roundtrip - read_file and grep_search in one reply both go back, in order`(dialect: ProviderDialect) {
        cli.writeFile("fixture.txt", FIXTURE)
        val read = ScriptedToolCall("toolu_multi_read", "read_file", """{"path":"fixture.txt"}""")
        val grep = grepParity("toolu_multi_grep")
        cli.fake(dialect).callTools(read, grep).answer("multi-tool roundtrip complete: alpha parity line / 2 occurrences")

        val run = prompt(dialect, "Read fixture.txt and count parity")

        val (_, second) = requests(dialect, count = 2)
        assertEquals(listOf(read.id, grep.id), dialect.replayedToolCallIds(second))
        val results = dialect.toolResults(second)
        assertEquals(listOf(read.id, grep.id), results.map { it.callId })
        assertEquals(listOf(successFlag(dialect), successFlag(dialect)), results.map { it.isError })
        assertEquals("alpha parity line\nbeta line\ngamma parity line", Json.parseToJsonElement(results[0].output).jsonObject.getValue("file").jsonObject.text("content"))
        assertEquals(2, Json.parseToJsonElement(results[1].output).jsonObject.getValue("numMatches").jsonPrimitive.int)

        val json = run.json()
        assertEquals("multi-tool roundtrip complete: alpha parity line / 2 occurrences", json.text("message"))
        assertEquals(2, json.getValue("iterations").jsonPrimitive.int)
        assertEquals(listOf(read.id, grep.id), json.objects("tool_uses").map { it.text("id") })
        assertEquals(listOf(read.id, grep.id), json.objects("tool_results").map { it.text("tool_use_id") })
        assertCallAndResult(json, read, results[0].output, isError = false)
        assertCallAndResult(json, grep, results[1].output, isError = false)
        assertEquals(listOf(read.id, grep.id), savedBlocks("tool_result").map { it.text("tool_use_id") })
    }

    @ParameterizedTest
    @EnumSource(ProviderDialect::class)
    fun `grep_chunk_assembly - grep_search arguments streamed in pieces still decode and run`(dialect: ProviderDialect) {
        cli.writeFile("fixture.txt", FIXTURE)
        val grep = grepParity("toolu_grep_fixture")
        cli.fake(dialect).callTools(grep).answer("grep_search matched 2 occurrences")

        val run = prompt(dialect, "Count parity in fixture.txt")

        val (_, second) = requests(dialect, count = 2)
        val result = dialect.toolResults(second).single()
        assertEquals(grep.id, result.callId)
        assertEquals(successFlag(dialect), result.isError)
        val output = Json.parseToJsonElement(result.output).jsonObject
        assertEquals("count", output.text("mode"))
        assertEquals(listOf(cli.workspace.resolve("fixture.txt").toRealPath().toString()), output.getValue("filenames").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, output.getValue("numMatches").jsonPrimitive.int)

        val json = run.json()
        assertEquals("grep_search matched 2 occurrences", json.text("message"))
        assertEquals(2, json.getValue("iterations").jsonPrimitive.int)
        // The pieces join back to exactly what Clawcode's harness expects as the call's input.
        assertEquals("""{"pattern":"parity","path":"fixture.txt","output_mode":"count"}""", grep.argumentsJson)
        assertCallAndResult(json, grep, result.output, isError = false)
    }

    @ParameterizedTest
    @EnumSource(ProviderDialect::class)
    fun `an unknown tool reaches the model as an error result`(dialect: ProviderDialect) {
        val teleport = ScriptedToolCall("toolu_teleport", "teleport", """{"destination":"mars"}""")
        cli.fake(dialect).callTools(teleport).answer("There is no teleport tool.")

        val run = prompt(dialect, "Teleport me")

        // RunToolCallUseCase answers with Clawcode's wording (tools/src/lib.rs).
        val error = "unsupported tool: teleport"
        val (_, second) = requests(dialect, count = 2)
        // Providers reject a call to a tool the request does not define, so TranscriptNormalizer sends
        // the call and its error result as labelled text rather than natively.
        assertTrue(dialect.replayedToolCallIds(second).isEmpty())
        assertTrue(dialect.toolResults(second).isEmpty())
        val body = rawBodies(dialect).last()
        assertTrue(body.contains("[tool error teleport (toolu_teleport)] $error"), body)

        val json = run.json()
        assertEquals("There is no teleport tool.", json.text("message"))
        assertCallAndResult(json, teleport, error, isError = true)
    }

    @ParameterizedTest
    @EnumSource(ProviderDialect::class)
    fun `a path that climbs out of the workspace reaches the model as an error result and nothing outside is read`(dialect: ProviderDialect) {
        Files.writeString(root.resolve("secret.txt"), SECRET)
        val escape = ScriptedToolCall("toolu_escape", "read_file", """{"path":"../secret.txt"}""")
        cli.fake(dialect).callTools(escape).answer("That file is outside the workspace.")

        val run = prompt(dialect, "Read the secret next door")

        // Refused while decoding (ToolInputGatewayImpl), before anything touches the disk.
        val error = "Invalid input for read_file: path '../secret.txt' is outside the workspace"
        val (_, second) = requests(dialect, count = 2)
        assertEquals(listOf(nativeError(dialect, escape.id, error)), dialect.toolResults(second))
        assertCallAndResult(run.json(), escape, error, isError = true)
        assertSecretNeverSent(dialect, run)
    }

    @ParameterizedTest
    @EnumSource(ProviderDialect::class)
    fun `a symbolic link out of the workspace is refused with Clawcode's boundary error and nothing outside is read`(dialect: ProviderDialect) {
        val secret = root.resolve("secret.txt")
        Files.writeString(secret, SECRET)
        link(cli.workspace.resolve("notes.txt"), secret)
        val read = ScriptedToolCall("toolu_link", "read_file", """{"path":"notes.txt"}""")
        cli.fake(dialect).callTools(read).answer("That file leads outside the workspace.")

        val run = prompt(dialect, "Read notes.txt")

        // The path is inside the workspace as spelled, so LocalWorkspaceGatewayImpl refuses it from its
        // real path, in Clawcode's words (file_ops.rs, validate_workspace_boundary).
        val error = "path ${secret.toRealPath()} escapes workspace boundary ${cli.workspace.toRealPath()}"
        val (_, second) = requests(dialect, count = 2)
        assertEquals(listOf(nativeError(dialect, read.id, error)), dialect.toolResults(second))
        assertCallAndResult(run.json(), read, error, isError = true)
        assertSecretNeverSent(dialect, run)
    }

    @ParameterizedTest
    @EnumSource(ProviderDialect::class)
    fun `token_cost_reporting - a two-call turn writes one usage record per call and reports the summed cost`(dialect: ProviderDialect) {
        cli.writeFile("fixture.txt", "alpha parity line\n")
        val read = ScriptedToolCall("toolu_read_fixture", "read_file", """{"path":"fixture.txt"}""")
        cli.fake(dialect).reply(
            ScriptedReply.ToolCalls(listOf(read), usage = ScriptedUsage(inputTokens = 1_000, outputTokens = 200)),
            ScriptedReply.Text(listOf("token cost reporting parity complete."), ScriptedUsage(inputTokens = 1_500, outputTokens = 300)),
        )

        val json = prompt(dialect, "Read fixture.txt").json()

        assertEquals("token cost reporting parity complete.", json.text("message"))
        assertEquals(2, json.getValue("iterations").jsonPrimitive.int)
        val usage = json.getValue("usage").jsonObject
        assertEquals(listOf(2_500L, 500L, 3_000L), listOf("input_tokens", "output_tokens", "total_tokens").map { usage.getValue(it).jsonPrimitive.long })
        assertEquals(summedCost(dialect), json.getValue("estimated_cost").jsonPrimitive.contentOrNull)

        val report = cli.run("--output-format", "json", "usage")
        assertEquals(ExitCodes.OK, report.exitCode, report.stderr)
        val ledger = report.json()
        val model = ledger.objects("models").single()
        assertEquals(ButlerCli.modelFor(dialect), model.text("model"))
        // One record per model call, not one per turn.
        assertEquals(2L, model.getValue("calls").jsonPrimitive.long)
        assertEquals(3_000L, model.getValue("tokens").jsonPrimitive.long)
        // A model without a price (the local one) is recorded at no cost.
        val ledgerCost = summedCost(dialect) ?: "$0.0000"
        assertEquals(ledgerCost, model.text("cost_usd"))
        assertEquals(ledgerCost, ledger.text("total_cost_usd"))
    }

    /** `butler --model <dialect's model> --output-format json prompt <text>`, which must succeed. */
    private fun prompt(dialect: ProviderDialect, text: String): CliRun =
        cli.run(dialect, "--output-format", "json", "prompt", text).also { assertEquals(ExitCodes.OK, it.exitCode, it.stderr) }

    /** The request bodies [dialect]'s fake received, after checking there were [count]. */
    private fun requests(dialect: ProviderDialect, count: Int): List<JsonObject> =
        cli.fake(dialect).requestBodies(dialect.path).also { assertEquals(count, it.size, "requests to ${dialect.path}") }

    private fun rawBodies(dialect: ProviderDialect): List<String> = cli.fake(dialect).recorded.filter { it.path == dialect.path }.map { it.body }

    /** Each offered tool's name and input schema, from where [dialect] puts them. */
    private fun offeredSchemas(dialect: ProviderDialect, body: JsonObject): Map<String, JsonElement> =
        body.objects("tools").associate { tool ->
            when (dialect) {
                ProviderDialect.ANTHROPIC_MESSAGES -> tool.text("name") to tool.getValue("input_schema")
                ProviderDialect.RESPONSES -> tool.text("name") to tool.getValue("parameters")
                ProviderDialect.CHAT_COMPLETIONS -> tool.getValue("function").jsonObject.let { it.text("name") to it.getValue("parameters") }
            }
        }

    /** A successful result's flag: Anthropic reads a missing `is_error` as false; the OpenAI dialects have no flag. */
    private fun successFlag(dialect: ProviderDialect): Boolean? = if (dialect == ProviderDialect.ANTHROPIC_MESSAGES) false else null

    /** An error result as [dialect] carries it: Anthropic sets `is_error`; the OpenAI dialects have no flag, so the text says so. */
    private fun nativeError(dialect: ProviderDialect, callId: String, message: String): NativeToolResult =
        if (dialect == ProviderDialect.ANTHROPIC_MESSAGES) NativeToolResult(callId, message, isError = true) else NativeToolResult(callId, "[error] $message", isError = null)

    /**
     * Two calls at the catalog's prices: (2,500 input × $3 + 500 output × $15) per million for
     * claude-sonnet-4-6, (2,500 × $5 + 500 × $30) per million for gpt-5.5; the local Ollama model has
     * no price, so no estimate.
     */
    private fun summedCost(dialect: ProviderDialect): String? =
        when (dialect) {
            ProviderDialect.ANTHROPIC_MESSAGES -> "$0.0150"
            ProviderDialect.RESPONSES -> "$0.0275"
            ProviderDialect.CHAT_COMPLETIONS -> null
        }

    /** The JSON result and the saved session both hold [call] as the model made it and the result it got back. */
    private fun assertCallAndResult(json: JsonObject, call: ScriptedToolCall, output: String, isError: Boolean) {
        val reportedUse = json.objects("tool_uses").single { it.text("id") == call.id }
        assertEquals(listOf(call.name, call.argumentsJson), listOf(reportedUse.text("name"), reportedUse.text("input")))
        val reportedResult = json.objects("tool_results").single { it.text("tool_use_id") == call.id }
        assertEquals(listOf(call.name, output), listOf(reportedResult.text("tool_name"), reportedResult.text("output")))
        assertEquals(isError, reportedResult.getValue("is_error").jsonPrimitive.boolean)

        val savedUse = savedBlocks("tool_use").single { it.text("id") == call.id }
        assertEquals(listOf(call.name, call.argumentsJson), listOf(savedUse.text("name"), savedUse.text("input")))
        val savedResult = savedBlocks("tool_result").single { it.text("tool_use_id") == call.id }
        assertEquals(listOf(call.name, output), listOf(savedResult.text("tool_name"), savedResult.text("output")))
        assertEquals(isError, savedResult.getValue("is_error").jsonPrimitive.boolean)
    }

    /** The file outside the workspace reached neither the provider, the output nor the saved session. */
    private fun assertSecretNeverSent(dialect: ProviderDialect, run: CliRun) {
        (rawBodies(dialect) + run.stdout + run.stderr + cli.savedSessionText()).forEach { assertFalse(it.contains(SECRET), it) }
    }

    /** The saved session's messages (Clawcode's JSONL `message` lines), oldest first. */
    private fun savedMessages(): List<JsonObject> =
        cli.savedSessionText().lines().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.text("type") == "message" }
            .map { it.getValue("message").jsonObject }

    private fun savedBlocks(type: String): List<JsonObject> = savedMessages().flatMap { it.objects("blocks") }.filter { it.text("type") == type }

    private fun link(link: Path, target: Path) {
        val created =
            try {
                Files.createSymbolicLink(link, target)
                true
            } catch (unsupported: UnsupportedOperationException) {
                false
            } catch (refused: IOException) {
                false
            }
        assumeTrue(created, "this file system cannot create symbolic links")
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.objects(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    private companion object {
        /** Clawcode's grep and multi-tool fixture (`mock_parity_harness.rs`). */
        const val FIXTURE = "alpha parity line\nbeta line\ngamma parity line\n"

        /** Text that must never leave the file outside the workspace. */
        const val SECRET = "launch code 31337"

        /** Clawcode's grep_search call, in the pieces its mock service streams: they cut through a value and stop before a separator. */
        fun grepParity(id: String): ScriptedToolCall =
            ScriptedToolCall.chunked(id, "grep_search", "{\"pattern\":\"par", "ity\",\"path\":\"fixture.txt\"", ",\"output_mode\":\"count\"}")
    }
}
