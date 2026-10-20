package com.suarvis.butler.api.cli

import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** Exit codes shared with Clawcode: 0 success, 1 runtime failure, 2 usage error. */
object ExitCodes {
    const val OK = 0
    const val RUNTIME = 1
    const val USAGE = 2
}

enum class OutputFormat {
    TEXT,
    JSON,
    ;

    companion object {
        const val FLAG = "--output-format"
        const val ENV_VARIABLE = "BUTLER_OUTPUT_FORMAT"

        fun parseOrNull(raw: String?): OutputFormat? {
            val normalized = raw?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.name == normalized }
        }

        /**
         * Best-effort detection from raw arguments. Used when argument parsing itself failed, so
         * that even a parse error is reported as JSON to a script that asked for JSON.
         */
        fun detect(args: List<String>, environmentValue: String?): OutputFormat {
            for ((index, arg) in args.withIndex()) {
                if (arg == "--") break
                val value =
                    when {
                        arg == FLAG -> args.getOrNull(index + 1)
                        arg.startsWith("$FLAG=") -> arg.substringAfter('=')
                        else -> null
                    }
                parseOrNull(value)?.let { return it }
            }
            return parseOrNull(environmentValue) ?: TEXT
        }
    }
}

/** Where command input comes from and output goes. Resolved on every call so tests can redirect System.in/out/err. */
interface CliConsole {
    val input: InputStream
    val out: PrintStream
    val err: PrintStream

    /** True when a person is typing at a terminal, false when input is piped or redirected. */
    val interactive: Boolean

    /**
     * The next line of input, or null at the end. The REPL and approval prompts both read through
     * this, so neither swallows input buffered for the other.
     */
    fun readLine(): String?
}

@Component
class SystemCliConsole : CliConsole {
    override val input: InputStream
        get() = System.`in`

    override val out: PrintStream
        get() = System.out

    override val err: PrintStream
        get() = System.err

    private var reader: Pair<InputStream, BufferedReader>? = null

    @Synchronized
    override fun readLine(): String? {
        // Keyed by the stream so a redirected System.in (tests run several CLIs per JVM) gets a fresh reader.
        val current = System.`in`
        val cached = reader?.takeIf { it.first === current } ?: (current to current.bufferedReader()).also { reader = it }
        return cached.second.readLine()
    }

    // JDK 22+ returns a Console even when redirected, so ask it whether it is a terminal when possible.
    override val interactive: Boolean
        get() {
            val console = System.console() ?: return false
            return runCatching { console.javaClass.getMethod("isTerminal").invoke(console) as Boolean }.getOrDefault(true)
        }
}

/** Process environment, isolated so rules and resolvers stay unit-testable. */
@Component
class CliEnvironment {
    fun variable(name: String): String? = System.getenv(name)

    val currentDirectory: Path
        get() = Path.of("").toAbsolutePath().normalize()

    val homeDirectory: Path
        get() {
            val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
            return if (Files.exists(home)) home.toRealPath() else home
        }
}

object ButlerVersion {
    val current: String
        get() = ButlerVersion::class.java.`package`?.implementationVersion ?: "dev"
}
