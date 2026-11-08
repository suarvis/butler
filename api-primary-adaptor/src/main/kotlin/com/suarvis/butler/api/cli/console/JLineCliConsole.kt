package com.suarvis.butler.api.cli.console

import com.suarvis.butler.api.cli.CliConsole
import com.suarvis.butler.api.cli.repl.InterruptSignals
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.WriterOutputStream
import java.io.InputStream
import java.io.PrintStream

/**
 * [CliConsole] for a person at a terminal: JLine's line editor with the keys of Clawcode's
 * rustyline editor (`input.rs`). Up and Down walk the history and Ctrl-R searches it; Ctrl-C clears
 * a typed line, or ends the REPL on an empty one; Ctrl-D on an empty line ends it too. History lives
 * in memory, as in Clawcode.
 *
 * Unlike Clawcode, which adds only REPL prompts to its history, every non-empty line read here goes
 * into it, approval answers included: the REPL and the approval prompts share this one reader. Two
 * of Clawcode's keys are not bound yet: Tab completion, and Shift+Enter or Ctrl-J for a newline
 * (Ctrl-J sends the line, as Enter does).
 *
 * The terminal and the reader are built on first use. Output goes through the terminal too, so
 * Butler's text and the prompt JLine draws reach the screen in order. That includes [err]: with the
 * line editor, error text is shown on the terminal rather than written to the process's stderr.
 *
 * It is also the turn's [InterruptSignals]: SIGINT through the terminal while a turn runs, and a
 * Ctrl-C key pressed at a prompt the turn asks, which JLine reads in raw mode as a key, not a signal.
 *
 * @param openTerminal builds the terminal; tests pass one over in-memory streams.
 */
class JLineCliConsole(
    private val openTerminal: () -> Terminal = { systemTerminal() },
) : CliConsole, InterruptSignals, AutoCloseable {
    private val terminalHolder = lazy(openTerminal)
    private val terminal: Terminal by terminalHolder

    private val reader: LineReader by lazy {
        LineReaderBuilder.builder()
            .terminal(terminal)
            // Prompts are prose and code: `!` and `\` reach the model as typed, never as history expansion or escapes.
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
            .build()
    }

    /** Called for a Ctrl-C key while a turn is running, so an approval answered with Ctrl-C also stops the turn. */
    @Volatile
    private var keyInterrupt: (() -> Unit)? = null

    override val input: InputStream
        get() = System.`in`

    // One stream for both, so they never overtake each other. The UTF-8 round trip into the terminal's
    // writer is lossless; the writer encodes for the terminal.
    override val out: PrintStream by lazy { PrintStream(WriterOutputStream(terminal.writer(), Charsets.UTF_8), true, Charsets.UTF_8) }

    override val err: PrintStream
        get() = out

    /** [com.suarvis.butler.api.config.CliConfig] uses this console only at a terminal. */
    override val interactive: Boolean
        get() = true

    override fun readLine(prompt: String): String? {
        out.flush()
        return try {
            reader.readLine(prompt)
        } catch (interrupted: UserInterruptException) {
            // JLine can leave the thread's interrupt flag set after a Ctrl-C; the next blocking call,
            // such as the model request, would fail on it.
            Thread.interrupted()
            keyInterrupt?.invoke()
            if (interrupted.partialLine.isNullOrEmpty()) null else ""
        } catch (end: EndOfFileException) {
            null
        }
    }

    override fun onInterrupt(handler: () -> Unit): AutoCloseable {
        val previousSignal = terminal.handle(Terminal.Signal.INT, Terminal.SignalHandler { handler() })
        val previousKey = keyInterrupt
        keyInterrupt = handler
        return AutoCloseable {
            keyInterrupt = previousKey
            terminal.handle(Terminal.Signal.INT, previousSignal ?: Terminal.SignalHandler.SIG_DFL)
        }
    }

    /** Restores the terminal's modes; Spring calls it when the application context closes. */
    override fun close() {
        if (terminalHolder.isInitialized()) {
            terminal.flush()
            terminal.close()
        }
    }

    companion object {
        /**
         * The process's terminal. Grapheme-cluster probing is off: it queries the terminal at startup,
         * which costs up to 200 ms where nothing answers and can swallow keys typed ahead, and prompts
         * need no emoji-exact cursor placement.
         */
        fun systemTerminal(): Terminal =
            TerminalBuilder.builder()
                .name("butler")
                .system(true)
                .graphemeCluster(false)
                .build()
    }
}
