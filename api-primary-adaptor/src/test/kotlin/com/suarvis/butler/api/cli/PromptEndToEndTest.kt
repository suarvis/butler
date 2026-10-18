package com.suarvis.butler.api.cli

import com.suarvis.butler.api.ButlerApplication
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.listDirectoryEntries

/**
 * Runs `butler` end to end against a local fake of the Anthropic Messages API, so prompts,
 * streaming, the REPL and error reporting are exercised without network access or API cost.
 */
class PromptEndToEndTest {
    @TempDir
    lateinit var root: Path

    private lateinit var workspace: Path
    private val anthropic = FakeAnthropic()

    data class CliRun(val exitCode: Int, val stdout: String, val stderr: String) {
        fun json(): JsonObject = Json.parseToJsonElement(stdout.trim().lines().last()).jsonObject
    }

    @BeforeEach
    fun setUp() {
        workspace = root.resolve("project").also { Files.createDirectories(it) }
        System.setProperty(BASE_URL_PROPERTY, anthropic.baseUrl)
        System.setProperty(API_KEY_PROPERTY, "test-key")
    }

    @AfterEach
    fun tearDown() {
        anthropic.close()
        System.clearProperty(BASE_URL_PROPERTY)
        System.clearProperty(API_KEY_PROPERTY)
    }

    private fun butler(vararg args: String, stdin: String = ""): CliRun {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val original = Triple(System.`in`, System.out, System.err)
        System.setIn(ByteArrayInputStream(stdin.toByteArray()))
        System.setOut(PrintStream(stdout, true, Charsets.UTF_8))
        System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
        val exitCode =
            try {
                ButlerApplication.run("--cwd", workspace.toString(), *args)
            } finally {
                System.setIn(original.first)
                System.setOut(original.second)
                System.setErr(original.third)
            }
        return CliRun(exitCode, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
    }

    private fun lastRequest(): JsonObject = Json.parseToJsonElement(anthropic.requests.last()).jsonObject

    @Test
    fun `a one-shot prompt returns Clawcode's JSON result and saves the session`() {
        anthropic.answer("Hello", " there!")

        val run = butler("--output-format", "json", "prompt", "Say hello")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        val result = run.json()
        assertEquals("Hello there!", result["message"]!!.jsonPrimitive.content)
        assertEquals("claude-sonnet-4-6", result["model"]!!.jsonPrimitive.content)
        assertEquals("anthropic", result["provider"]!!.jsonPrimitive.content)
        assertEquals(12, result["usage"]!!.jsonObject["input_tokens"]!!.jsonPrimitive.int)
        assertEquals("$0.0001", result["estimated_cost"]!!.jsonPrimitive.content)
        assertEquals(1, workspace.resolve(".butler/sessions").listDirectoryEntries("*.jsonl").size)
        val message = lastRequest()["messages"]!!.jsonArray.single().jsonObject
        assertEquals("Say hello", message["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the shorthand form streams the answer as text`() {
        anthropic.answer("Hello", " there!")

        val run = butler("Say hello")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertEquals("Hello there!", run.stdout.trim())
    }

    @Test
    fun `piped stdin becomes the prompt`() {
        anthropic.answer("ok")

        val run = butler("prompt", stdin = "text from a pipe")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertTrue(anthropic.requests.last().contains("text from a pipe"))
    }

    @Test
    fun `a prompt can continue the latest session`() {
        anthropic.answer("first").answer("second")
        butler("prompt", "one")

        val run = butler("prompt", "--session", "latest", "two")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertEquals(3, lastRequest()["messages"]!!.jsonArray.size)
        assertEquals(1, workspace.resolve(".butler/sessions").listDirectoryEntries("*.jsonl").size)
    }

    @Test
    fun `--model picks the model through its alias`() {
        anthropic.answer("ok")

        butler("--model", "opus", "prompt", "hi")

        assertEquals("claude-opus-4-7", lastRequest()["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a missing API key is reported with a hint`() {
        System.setProperty(API_KEY_PROPERTY, "")

        val run = butler("--output-format", "json", "prompt", "hi")

        assertEquals(ExitCodes.RUNTIME, run.exitCode)
        assertEquals("missing_credentials", run.json()["kind"]!!.jsonPrimitive.content)
        assertTrue(run.json()["hint"]!!.jsonPrimitive.content.contains("ANTHROPIC_API_KEY"))
    }

    @Test
    fun `provider errors map to error kinds`() {
        anthropic.fail(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")

        val run = butler("prompt", "hi")

        assertEquals(ExitCodes.RUNTIME, run.exitCode)
        assertTrue(run.stderr.startsWith("[error-kind: provider_authentication]"), run.stderr)
    }

    @Test
    fun `the REPL runs prompts and slash commands until exit`() {
        anthropic.answer("Hello", " there!")

        val run = butler(stdin = "Say hello\n/status\n/exit\nnever sent\n")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertTrue(run.stdout.contains("Hello there!"), run.stdout)
        assertTrue(run.stdout.contains("Status"), run.stdout)
        assertEquals(1, anthropic.requests.size)
    }

    @Test
    fun `the REPL reports errors and keeps going`() {
        anthropic.answer("still here")

        val run = butler(stdin = "/teleport\nAre you there?\n")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertTrue(run.stderr.contains("[error-kind: unsupported_resumed_command]"), run.stderr)
        assertTrue(run.stdout.contains("still here"), run.stdout)
    }

    @Test
    fun `--resume without slash commands continues the session in the REPL`() {
        anthropic.answer("first").answer("second")
        butler("prompt", "one")

        val run = butler("--resume", "latest", stdin = "two\n")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertTrue(run.stdout.contains("Restored session"), run.stdout)
        assertEquals(3, lastRequest()["messages"]!!.jsonArray.size)
    }

    /** Minimal fake of POST /v1/messages that streams scripted answers. */
    private class FakeAnthropic : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val replies = ConcurrentLinkedQueue<Pair<Int, String>>()
        val requests = ConcurrentLinkedQueue<String>()

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/v1/messages") { exchange ->
                requests += exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                val (status, body) = replies.poll() ?: (500 to """{"type":"error","error":{"type":"api_error","message":"no scripted reply"}}""")
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun answer(vararg chunks: String): FakeAnthropic =
            apply {
                val events =
                    buildList {
                        add("message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":12,"output_tokens":1}}}""")
                        add("content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
                        chunks.forEach { add("content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$it"}}""") }
                        add("content_block_stop" to """{"type":"content_block_stop","index":0}""")
                        add("message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}""")
                        add("message_stop" to """{"type":"message_stop"}""")
                    }
                replies += 200 to events.joinToString(separator = "") { (name, data) -> "event: $name\ndata: $data\n\n" }
            }

        fun fail(status: Int, body: String): FakeAnthropic = apply { replies += status to body }

        override fun close() = server.stop(0)
    }

    private companion object {
        const val BASE_URL_PROPERTY = "butler.providers.anthropic.base-url"
        const val API_KEY_PROPERTY = "butler.providers.anthropic.api-key"
    }
}
