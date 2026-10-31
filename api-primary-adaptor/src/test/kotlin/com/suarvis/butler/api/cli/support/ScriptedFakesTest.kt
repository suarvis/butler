package com.suarvis.butler.api.cli.support

import com.suarvis.butler.api.cli.ExitCodes
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * The shared CLI fakes (J0) answer the real CLI on all three dialects, and the api copy of
 * [ScriptedStreams] stays identical to secondary-adaptor's, whose ScriptedProviderStreamsTest parses
 * every scripted stream, tool calls included, through the real gateways.
 */
class ScriptedFakesTest {
    @TempDir
    lateinit var root: Path

    @ParameterizedTest
    @EnumSource(ProviderDialect::class)
    fun `a scripted answer reaches the real CLI on every dialect and the request is recorded at the dialect's path`(dialect: ProviderDialect) {
        ButlerCli(root).use { cli ->
            cli.fake(dialect).answer("Hello", " there!")

            val run = cli.run(dialect, "--output-format", "json", "prompt", "Say hello")

            assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
            assertEquals("Hello there!", run.json()["message"]!!.jsonPrimitive.content)
            val fake = cli.fake(dialect)
            assertEquals(listOf(dialect.path), fake.recorded.map { it.path })
            // The provider prefix of a provider/model reference never reaches the server.
            assertEquals(ButlerCli.modelFor(dialect).substringAfter('/'), fake.requestBodies(dialect.path).single()["model"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `the api copy of ScriptedStreams matches secondary-adaptor's apart from the package line`() {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
        val secondary = repository.resolve("secondary-adaptor/src/test/kotlin/com/suarvis/butler/secondary/client/ScriptedStreams.kt").readLines()
        val api = repository.resolve("api-primary-adaptor/src/test/kotlin/com/suarvis/butler/api/cli/support/ScriptedStreams.kt").readLines()

        assertEquals("package com.suarvis.butler.secondary.client", secondary.first())
        assertEquals("package com.suarvis.butler.api.cli.support", api.first())
        assertEquals(secondary.drop(1), api.drop(1), "Change both copies of ScriptedStreams.kt together")
    }
}
