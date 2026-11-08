package com.suarvis.butler.api.cli.console

import com.suarvis.butler.api.cli.repl.InterruptSignals
import org.jline.utils.Signals

/**
 * SIGINT for the plain reader, where no JLine terminal exists. JLine's [Signals] wraps the JDK's
 * `sun.misc.Signal` by reflection, so Butler never touches `sun.misc` itself; where the JDK lacks it,
 * registering does nothing and Ctrl-C keeps the JVM's default.
 *
 * Without a terminal there are no keys to catch: at an approval prompt Ctrl-C is a signal too, so it
 * stops the turn, but the blocked read still waits for Enter.
 */
object JvmInterruptSignals : InterruptSignals {
    private const val SIGINT = "INT"

    override fun onInterrupt(handler: () -> Unit): AutoCloseable {
        val previous = Signals.register(SIGINT, Runnable { handler() })
        return AutoCloseable { Signals.unregister(SIGINT, previous) }
    }
}
