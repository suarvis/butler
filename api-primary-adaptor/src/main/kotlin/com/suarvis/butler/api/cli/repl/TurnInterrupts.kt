package com.suarvis.butler.api.cli.repl

import com.suarvis.butler.api.cli.CliConsole
import com.suarvis.butler.api.cli.ExitCodes
import com.suarvis.butler.core.domain.turn.TurnCancellation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Where Ctrl-C comes from while a turn runs: SIGINT, or a Ctrl-C key at a prompt the turn asks.
 * JLine-backed in production ([com.suarvis.butler.api.cli.console.JLineCliConsole], or
 * [com.suarvis.butler.api.cli.console.JvmInterruptSignals] for the plain reader), a manual trigger in tests.
 */
fun interface InterruptSignals {
    /** Calls [handler] on every Ctrl-C until the returned handle is closed, which puts the previous handler back. */
    fun onInterrupt(handler: () -> Unit): AutoCloseable
}

/**
 * Ctrl-C during a turn, as Clawcode's `HookAbortMonitor` handles it: the first one sets the turn's
 * [TurnCancellation], and the turn stops at its next step with the session saved. The handler lives
 * only while the turn runs, so outside a turn Ctrl-C keeps its usual meaning.
 *
 * Butler adds two things Clawcode does not have: a notice on stderr after the first Ctrl-C, and a
 * second Ctrl-C before the turn returns, which quits with exit code 130, so a turn waiting on a slow
 * response can always be left. Quitting then is safe: the prompt and every finished step are
 * already saved, and the next turn closes any tool call left without a result.
 *
 * A Ctrl-C answer at an approval prompt arrives here too, so it also stops the turn.
 *
 * @param exit ends the process; tests pass a recorder.
 */
class TurnInterrupts(
    private val console: CliConsole,
    private val signals: InterruptSignals,
    private val exit: (Int) -> Unit = { code -> exitProcess(code) },
) {
    /** Runs [turn] with Ctrl-C wired to its cancellation; exits with 130 on a second Ctrl-C. */
    fun <T> during(turn: (TurnCancellation) -> T): T {
        val presses = AtomicInteger()
        // A signal racing the end of the turn must neither cancel the next turn nor quit after this one returned.
        val running = AtomicBoolean(true)
        val registration =
            signals.onInterrupt {
                if (running.get()) interrupted(presses.incrementAndGet())
            }
        try {
            return turn(TurnCancellation { presses.get() > 0 })
        } finally {
            running.set(false)
            registration.close()
        }
    }

    private fun interrupted(press: Int) {
        if (press > 1) {
            exit(ExitCodes.INTERRUPTED)
            return
        }
        // Butler's wording; Clawcode prints nothing until its spinner shows "Interrupted". The line
        // break first, since Ctrl-C usually lands in the middle of streamed text.
        console.err.println()
        console.err.println(NOTICE)
        console.err.flush()
    }

    companion object {
        const val NOTICE = "Interrupting… press Ctrl-C again to quit"
    }
}
