package com.suarvis.butler.api.cli.repl

import com.suarvis.butler.api.cli.CliConsole
import com.suarvis.butler.api.cli.ExitCodes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream

/** The Ctrl-C state machine, with a manual signal source and an exit that only records the code. */
class TurnInterruptsTest {
    private val signals = ManualInterruptSignals()
    private val console = RecordingConsole()
    private val exits = mutableListOf<Int>()
    private val interrupts = TurnInterrupts(console, signals) { code -> exits += code }

    @Test
    fun `a turn nobody interrupts is never cancelled`() {
        assertFalse(interrupts.during { cancellation -> cancellation.isCancelled() })
        assertEquals("", console.errors)
    }

    @Test
    fun `one Ctrl-C cancels the turn and says how to quit, without quitting`() {
        val cancelled =
            interrupts.during { cancellation ->
                assertFalse(cancellation.isCancelled())
                signals.press()
                cancellation.isCancelled()
            }

        assertTrue(cancelled)
        assertEquals(emptyList<Int>(), exits)
        assertEquals("\nInterrupting… press Ctrl-C again to quit\n", console.errors)
    }

    @Test
    fun `a second Ctrl-C before the turn returns exits with 130`() {
        interrupts.during {
            signals.press()
            signals.press()
        }

        assertEquals(listOf(ExitCodes.INTERRUPTED), exits)
        assertEquals(1, console.errors.lines().count { it == TurnInterrupts.NOTICE })
    }

    @Test
    fun `the previous handler is back after a normal turn and after one that throws`() {
        val outside: () -> Unit = {}
        signals.handler = outside

        interrupts.during { assertNotSame(outside, signals.handler) }
        assertSame(outside, signals.handler)

        assertThrows<IllegalStateException> { interrupts.during<Unit> { error("the model call failed") } }
        assertSame(outside, signals.handler)
    }

    @Test
    fun `each turn starts uncancelled, and a signal racing the end of a turn changes nothing`() {
        var late: () -> Unit = {}
        interrupts.during {
            signals.press()
            late = signals.handler
        }

        late()
        late()

        assertEquals(emptyList<Int>(), exits)
        assertFalse(interrupts.during { cancellation -> cancellation.isCancelled() })
    }

    private class RecordingConsole : CliConsole {
        private val errBuffer = ByteArrayOutputStream()

        override val input: InputStream = ByteArrayInputStream(ByteArray(0))
        override val out = PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8)
        override val err = PrintStream(errBuffer, true, Charsets.UTF_8)
        override val interactive = true

        override fun readLine(prompt: String): String? = null

        val errors: String
            get() = errBuffer.toString(Charsets.UTF_8).replace(System.lineSeparator(), "\n")
    }
}
