package com.suarvis.butler.api.cli

import com.suarvis.butler.api.cli.support.ButlerCli
import com.suarvis.butler.api.cli.support.ProviderDialect
import com.suarvis.butler.api.cli.support.ScriptedToolCall
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The iteration limit (A3) through the real CLI: a turn whose model still calls tools after
 * `butler.tools.max-iterations` model calls fails with kind `iteration_limit`, and the session it
 * saved is continued by the next prompt.
 */
class IterationLimitTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `a turn over the limit fails with iteration_limit and the next prompt continues its session`() {
        ButlerCli(root).use { cli ->
            cli.property("butler.tools.enabled", "true").property("butler.tools.max-iterations", "1")
            cli.writeFile("README.md", "# Demo\n")
            cli.anthropic
                .callTools(ScriptedToolCall("toolu_1", "read_file", """{"path":"README.md"}"""))
                .answer("The readme is a heading.")

            val stopped = cli.run("--output-format", "json", "prompt", "Read the readme")

            assertEquals(ExitCodes.RUNTIME, stopped.exitCode, stopped.stderr)
            assertEquals("iteration_limit", stopped.json()["kind"]!!.jsonPrimitive.content)
            assertEquals("conversation loop exceeded the maximum number of iterations (1)", stopped.json()["error"]!!.jsonPrimitive.content)
            assertEquals(1, cli.anthropic.recorded.size)

            val resumed = cli.run("--output-format", "json", "prompt", "--session", "latest", "Go on")

            assertEquals(ExitCodes.OK, resumed.exitCode, resumed.stderr)
            assertEquals("The readme is a heading.", resumed.json()["message"]!!.jsonPrimitive.content)
            // The call the stopped turn ran goes back with its result, so the transcript it saved was valid.
            val carried = ProviderDialect.ANTHROPIC_MESSAGES.toolResults(cli.anthropic.requestBodies().last()).single()
            assertEquals("toolu_1", carried.callId)
            assertEquals(false, carried.isError)
        }
    }
}
