package com.suarvis.butler.api.cli

import org.springframework.stereotype.Component
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

/** Where command output goes. Resolved on every call so tests can redirect System.out/err. */
interface CliConsole {
    val out: PrintStream
    val err: PrintStream
}

@Component
class SystemCliConsole : CliConsole {
    override val out: PrintStream
        get() = System.out

    override val err: PrintStream
        get() = System.err
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
