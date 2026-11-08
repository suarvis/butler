package com.suarvis.butler.api.cli.repl

/**
 * Stands in for SIGINT and for a Ctrl-C key: [press] calls the handler the running turn registered,
 * from whichever thread presses it, as the JVM's signal thread does.
 */
class ManualInterruptSignals : InterruptSignals {
    @Volatile
    var handler: () -> Unit = {}

    override fun onInterrupt(handler: () -> Unit): AutoCloseable {
        val previous = this.handler
        this.handler = handler
        return AutoCloseable { this.handler = previous }
    }

    fun press() = handler()
}
