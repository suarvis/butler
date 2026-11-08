package com.suarvis.butler.api.cli.console

import org.jline.terminal.Terminal
import org.jline.terminal.impl.ExternalTerminal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Drives the JLine console over a terminal on in-memory streams, typing the bytes a terminal sends
 * for each key, so nothing depends on the machine's TTY. The input ends after the typed keys.
 */
@Timeout(10)
class JLineCliConsoleTest {
    private val screen = ByteArrayOutputStream()

    private val shown: String
        get() = screen.toString(Charsets.UTF_8)

    private fun console(keys: String): JLineCliConsole {
        val terminal = terminal(keys)
        return JLineCliConsole { terminal }
    }

    /**
     * Paused until raw mode is on, so every key reaches JLine as it does at a real prompt: Ctrl-C as
     * a key rather than a signal, Enter as a carriage return.
     */
    private fun terminal(keys: String): Terminal {
        val input = ByteArrayInputStream(keys.toByteArray(Charsets.UTF_8))
        val terminal = ExternalTerminal(null, "butler-test", "ansi", input, screen, Charsets.UTF_8, Terminal.SignalHandler.SIG_DFL, true)
        terminal.enterRawMode()
        terminal.resume()
        return terminal
    }

    @Test
    fun `Enter returns the typed line, and the reader draws the prompt`() {
        console("explain the build\r").use { console ->
            assertEquals("explain the build", console.readLine("> "))
        }
        assertTrue(shown.contains("> "), shown)
    }

    @Test
    fun `Ctrl-C with typed text clears the line, and the next line reads normally`() {
        console("half a thou${CTRL_C}next\r").use { console ->
            assertEquals("", console.readLine("> "))
            assertFalse(Thread.currentThread().isInterrupted, "a cleared line must not leave the thread interrupted")
            assertEquals("next", console.readLine("> "))
        }
    }

    @Test
    fun `Ctrl-C or Ctrl-D on an empty line ends the input, as does its real end`() {
        console(CTRL_C).use { assertNull(it.readLine("> ")) }
        console(CTRL_D).use { assertNull(it.readLine("> ")) }
        console("").use { assertNull(it.readLine("> ")) }
    }

    @Test
    fun `Up recalls the previous line from history`() {
        console("first prompt\r$UP\r").use { console ->
            assertEquals("first prompt", console.readLine("> "))
            assertEquals("first prompt", console.readLine("> "))
        }
    }

    @Test
    fun `exclamation marks, quotes and backslashes arrive as typed`() {
        val prompt = """fix it! then grep "a\nb" in it's file, and !!"""
        console("$prompt\r").use { assertEquals(prompt, it.readLine("> ")) }
    }

    @Test
    fun `while a turn runs, Ctrl-C typed at a prompt it asks also reaches the turn`() {
        val presses = mutableListOf<String>()
        console("${CTRL_C}y$CTRL_C").use { console ->
            val registration = console.onInterrupt { presses += "turn" }
            assertNull(console.readLine("Approve this tool call? [y/N]: "))
            registration.close()
            // After the turn, Ctrl-C only clears the line again.
            assertEquals("", console.readLine("> "))
        }
        assertEquals(listOf("turn"), presses)
    }

    @Test
    fun `SIGINT reaches the handler only while it is registered`() {
        val terminal = terminal("")
        var presses = 0
        JLineCliConsole { terminal }.use { console ->
            val registration = console.onInterrupt { presses++ }
            terminal.raise(Terminal.Signal.INT)
            registration.close()
            terminal.raise(Terminal.Signal.INT)
        }
        assertEquals(1, presses)
    }

    @Test
    fun `out and err are written through the terminal, in order`() {
        console("").use { console ->
            console.out.print("streamed ")
            console.err.println("warning")
            console.out.println("text")
        }
        assertTrue(shown.contains("streamed warning"), shown)
        assertTrue(shown.indexOf("warning") < shown.indexOf("text"), shown)
    }

    private companion object {
        const val CTRL_C = "\u0003"
        const val CTRL_D = "\u0004"
        const val UP = "\u001b[A"
    }
}
