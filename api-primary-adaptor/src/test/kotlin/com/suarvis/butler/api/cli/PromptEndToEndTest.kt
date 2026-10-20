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
import kotlin.io.path.readText

/**
 * Runs `butler` end to end against local fakes of the Anthropic Messages API and an
 * OpenAI-compatible Chat Completions API, so prompts, streaming, the REPL, switching provider and
 * error reporting are exercised without network access or API cost.
 */
class PromptEndToEndTest {
    @TempDir
    lateinit var root: Path

    private lateinit var workspace: Path
    private val anthropic = FakeAnthropic()
    private val openai = FakeOpenAi()

    data class CliRun(val exitCode: Int, val stdout: String, val stderr: String) {
        fun json(): JsonObject = Json.parseToJsonElement(stdout.trim().lines().last()).jsonObject
    }

    @BeforeEach
    fun setUp() {
        workspace = root.resolve("project").also { Files.createDirectories(it) }
        System.setProperty(BASE_URL_PROPERTY, anthropic.baseUrl)
        System.setProperty(API_KEY_PROPERTY, "test-key")
        System.setProperty(OPENAI_BASE_URL_PROPERTY, openai.baseUrl)
        System.setProperty(OPENAI_API_KEY_PROPERTY, "sk-test")
        System.setProperty(OLLAMA_BASE_URL_PROPERTY, openai.baseUrl)
        // Never the real ~/.butler: the usage ledger and cooldowns of a test run stay in the temp folder.
        System.setProperty(USER_DIRECTORY_PROPERTY, root.resolve("home/.butler").toString())
    }

    @AfterEach
    fun tearDown() {
        anthropic.close()
        openai.close()
        listOf(
            BASE_URL_PROPERTY,
            API_KEY_PROPERTY,
            OPENAI_BASE_URL_PROPERTY,
            OPENAI_API_KEY_PROPERTY,
            OLLAMA_BASE_URL_PROPERTY,
            USER_DIRECTORY_PROPERTY,
            ANTHROPIC_BUDGET_PROPERTY,
        ).forEach(System::clearProperty)
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

    private fun lastOpenAiRequest(): JsonObject = Json.parseToJsonElement(openai.requests.last().body).jsonObject

    private fun savedSessionText(): String = workspace.resolve(".butler/sessions").listDirectoryEntries("*.jsonl").single().readText()

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

    @Test
    fun `slash model switches provider mid-session and the new provider gets the whole conversation`() {
        anthropic.answer("Hello from Claude")
        openai.answer("Hello from GPT")

        val run = butler(stdin = "Say hello\n/model gpt\nAnd again\n")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertTrue(run.stdout.contains("Switching provider anthropic → openai"), run.stdout)
        assertTrue(run.stdout.contains("Hello from GPT"), run.stdout)
        assertEquals(1, anthropic.requests.size)
        val request = lastOpenAiRequest()
        assertEquals("gpt-5.5", request["model"]!!.jsonPrimitive.content)
        assertEquals(128_000, request["max_completion_tokens"]!!.jsonPrimitive.int)
        val messages = request["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(listOf("Say hello", "Hello from Claude", "And again"), messages.drop(1).map { it["content"]!!.jsonPrimitive.content })
        assertTrue(savedSessionText().contains("\"model\":\"gpt-5.5\""))
    }

    @Test
    fun `a provider-slash-model reference reaches a local server without a key`() {
        openai.answer("local answer")

        val run = butler("--output-format", "json", "--model", "ollama/llama3.2", "prompt", "hi")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertEquals("ollama", run.json()["provider"]!!.jsonPrimitive.content)
        assertEquals("ollama/llama3.2", run.json()["model"]!!.jsonPrimitive.content)
        assertEquals("llama3.2", lastOpenAiRequest()["model"]!!.jsonPrimitive.content)
        assertEquals(null, openai.requests.last().authorization)
    }

    @Test
    fun `a missing OpenAI key names that provider's variable`() {
        System.setProperty(OPENAI_API_KEY_PROPERTY, "")

        val run = butler("--output-format", "json", "--model", "gpt-5.5", "prompt", "hi")

        assertEquals(ExitCodes.RUNTIME, run.exitCode)
        assertEquals("missing_credentials", run.json()["kind"]!!.jsonPrimitive.content)
        assertTrue(run.json()["hint"]!!.jsonPrimitive.content.contains("OPENAI_API_KEY"), run.stdout)
    }

    @Test
    fun `an unknown model is a usage error and nothing is saved`() {
        val run = butler("--output-format", "json", "--model", "mystery-model", "prompt", "hi")

        assertEquals(ExitCodes.USAGE, run.exitCode)
        assertEquals("unknown_model", run.json()["kind"]!!.jsonPrimitive.content)
        assertTrue(Files.notExists(workspace.resolve(".butler/sessions")) || workspace.resolve(".butler/sessions").listDirectoryEntries().isEmpty())
    }

    @Test
    fun `slash model rejects an unknown model and keeps the current one`() {
        anthropic.answer("still Claude")

        val run = butler(stdin = "/model mystery-model\nhi\n")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertTrue(run.stderr.contains("[error-kind: unknown_model]"), run.stderr)
        assertTrue(run.stdout.contains("still Claude"), run.stdout)
        assertTrue(openai.requests.isEmpty())
    }

    @Test
    fun `every call is recorded in the usage ledger and butler usage reports the month`() {
        anthropic.answer("Hello")
        butler("prompt", "hi")

        val run = butler("--output-format", "json", "usage")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        val report = run.json()
        assertEquals("usage", report["kind"]!!.jsonPrimitive.content)
        assertEquals(17, report["total_tokens"]!!.jsonPrimitive.int)
        assertEquals("$0.0001", report["total_cost_usd"]!!.jsonPrimitive.content)
        val model = report["models"]!!.jsonArray.single().jsonObject
        assertEquals("claude-sonnet-4-6", model["model"]!!.jsonPrimitive.content)
        assertEquals("anthropic", model["provider"]!!.jsonPrimitive.content)
        assertTrue(Files.exists(root.resolve("home/.butler/usage")))
    }

    @Test
    fun `over budget, a script stops with budget_exceeded before calling the provider`() {
        System.setProperty(ANTHROPIC_BUDGET_PROPERTY, "0.0001")

        val run = butler("--output-format", "json", "prompt", "hi")

        assertEquals(ExitCodes.RUNTIME, run.exitCode)
        assertEquals("budget_exceeded", run.json()["kind"]!!.jsonPrimitive.content)
        assertTrue(run.json()["hint"]!!.jsonPrimitive.content.contains("--allow-over-budget"))
        assertTrue(anthropic.requests.isEmpty())
    }

    @Test
    fun `--allow-over-budget lets a script go over with a warning`() {
        System.setProperty(ANTHROPIC_BUDGET_PROPERTY, "0.0001")
        anthropic.answer("over budget, as allowed")

        val run = butler("--allow-over-budget", "prompt", "hi")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertTrue(run.stdout.contains("over budget, as allowed"), run.stdout)
        assertTrue(run.stderr.contains("warning: anthropic monthly budget"), run.stderr)
    }

    @Test
    fun `butler usage shows budgets and flags the exceeded ones`() {
        System.setProperty(ANTHROPIC_BUDGET_PROPERTY, "0.00001")
        anthropic.answer("costly")
        butler("--allow-over-budget", "prompt", "hi")

        val run = butler("usage")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        assertTrue(run.stdout.contains("Budgets"), run.stdout)
        assertTrue(run.stdout.contains("EXCEEDED"), run.stdout)
    }

    @Test
    fun `a 429 is remembered, so the next run does not call the provider until the cooldown ends`() {
        anthropic.fail(429, """{"type":"error","error":{"type":"rate_limit_error","message":"Number of requests has exceeded your rate limit"}}""")

        val first = butler("--output-format", "json", "prompt", "one")
        val second = butler("--output-format", "json", "prompt", "two")

        assertEquals("rate_limited", first.json()["kind"]!!.jsonPrimitive.content)
        assertEquals("rate_limited", second.json()["kind"]!!.jsonPrimitive.content)
        assertTrue(second.json()["message"]!!.jsonPrimitive.content.contains("earlier 429"), second.stdout)
        assertEquals(1, anthropic.requests.size)
        assertTrue(Files.exists(root.resolve("home/.butler/rate-limits.json")))
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

    /** Minimal fake of POST /chat/completions (OpenAI-compatible) that streams scripted answers. */
    private class FakeOpenAi : AutoCloseable {
        data class Recorded(val body: String, val authorization: String?)

        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val replies = ConcurrentLinkedQueue<String>()
        val requests = ConcurrentLinkedQueue<Recorded>()

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}/v1"

        init {
            server.createContext("/v1/chat/completions") { exchange ->
                requests += Recorded(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8), exchange.requestHeaders.getFirst("Authorization"))
                val reply = replies.poll()
                val (status, body) = if (reply == null) 500 to """{"error":{"message":"no scripted reply"}}""" else 200 to reply
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun answer(text: String): FakeOpenAi =
            apply {
                val chunks =
                    listOf(
                        """{"choices":[{"index":0,"delta":{"role":"assistant","content":"$text"},"finish_reason":null}]}""",
                        """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                        """{"choices":[],"usage":{"prompt_tokens":20,"completion_tokens":4}}""",
                        "[DONE]",
                    )
                replies += chunks.joinToString(separator = "") { "data: $it\n\n" }
            }

        override fun close() = server.stop(0)
    }

    private companion object {
        const val BASE_URL_PROPERTY = "butler.providers.anthropic.base-url"
        const val API_KEY_PROPERTY = "butler.providers.anthropic.api-key"
        const val OPENAI_BASE_URL_PROPERTY = "butler.providers.openai.base-url"
        const val OPENAI_API_KEY_PROPERTY = "butler.providers.openai.api-key"
        const val OLLAMA_BASE_URL_PROPERTY = "butler.providers.ollama.base-url"
        const val USER_DIRECTORY_PROPERTY = "butler.persistence.file.user-directory"
        const val ANTHROPIC_BUDGET_PROPERTY = "butler.budgets.providers.anthropic.monthly-usd"
    }
}
