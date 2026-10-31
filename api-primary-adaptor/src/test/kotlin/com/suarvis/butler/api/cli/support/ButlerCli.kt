package com.suarvis.butler.api.cli.support

import com.suarvis.butler.api.ButlerApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** What one CLI run returned and printed. */
data class CliRun(val exitCode: Int, val stdout: String, val stderr: String) {
    /** The last line of stdout as JSON: with `--output-format json`, the result envelope. */
    fun json(): JsonObject = Json.parseToJsonElement(stdout.trim().lines().last()).jsonObject
}

/**
 * Runs `butler` in-process the way a user would, from a temp [workspace], with every provider
 * pointed at the fakes through system properties and `~/.butler` kept under `root`, so the real
 * one is never touched. Anthropic is served by [anthropic]; OpenAI (Responses) and Ollama (Chat
 * Completions) by [openai]. Close it after each test: it clears the properties and stops the fakes.
 */
class ButlerCli(
    root: Path,
    val anthropic: FakeAnthropic = FakeAnthropic(),
    val openai: FakeOpenAi = FakeOpenAi(),
) : AutoCloseable {
    val workspace: Path = root.resolve("project").also { Files.createDirectories(it) }

    private val properties =
        mapOf(
            "butler.providers.anthropic.base-url" to anthropic.baseUrl,
            "butler.providers.anthropic.api-key" to "test-key",
            "butler.providers.openai.base-url" to openai.baseUrl,
            "butler.providers.openai.api-key" to "sk-test",
            "butler.providers.ollama.base-url" to openai.baseUrl,
            "butler.persistence.file.user-directory" to root.resolve("home/.butler").toString(),
        )
    private val extraProperties = mutableSetOf<String>()

    init {
        properties.forEach { (name, value) -> System.setProperty(name, value) }
    }

    /** Sets a system property, such as a budget or permission mode, until [close]. */
    fun property(name: String, value: String): ButlerCli =
        apply {
            extraProperties += name
            System.setProperty(name, value)
        }

    /** Writes a workspace file, creating its folders. */
    fun writeFile(relativePath: String, text: String): Path =
        workspace.resolve(relativePath).also {
            Files.createDirectories(it.parent)
            it.writeText(text)
        }

    /** Runs `butler --cwd <workspace> <args>` with [stdin] as standard input. */
    fun run(vararg args: String, stdin: String = ""): CliRun {
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

    /** Runs with the model [dialect] is reached through ([modelFor]). */
    fun run(dialect: ProviderDialect, vararg args: String, stdin: String = ""): CliRun = run("--model", modelFor(dialect), *args, stdin = stdin)

    /** The fake that serves [dialect]. */
    fun fake(dialect: ProviderDialect): ScriptedProviderServer =
        when (dialect) {
            ProviderDialect.ANTHROPIC_MESSAGES -> anthropic
            ProviderDialect.CHAT_COMPLETIONS, ProviderDialect.RESPONSES -> openai
        }

    /** The text of the only session saved in the workspace. */
    fun savedSessionText(): String = workspace.resolve(".butler/sessions").listDirectoryEntries("*.jsonl").single().readText()

    override fun close() {
        (properties.keys + extraProperties).forEach(System::clearProperty)
        anthropic.close()
        openai.close()
    }

    companion object {
        /** Anthropic's default model, OpenAI's on the Responses API, and Ollama's on Chat Completions. */
        fun modelFor(dialect: ProviderDialect): String =
            when (dialect) {
                ProviderDialect.ANTHROPIC_MESSAGES -> "claude-sonnet-4-6"
                ProviderDialect.RESPONSES -> "gpt-5.5"
                ProviderDialect.CHAT_COMPLETIONS -> "ollama/llama3.2"
            }
    }
}
