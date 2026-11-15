package com.suarvis.butler.api.cli

import com.suarvis.butler.api.cli.support.ButlerCli
import com.suarvis.butler.api.cli.support.ProviderDialect
import com.suarvis.butler.api.cli.support.ScriptedToolCall
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * `butler.permissions.default-mode` (D2b): the mode a run starts in when no flag picks one, so a
 * user who prefers `workspace-write` is never asked about changes inside the project.
 */
class DefaultPermissionModeTest {
    @TempDir
    lateinit var root: Path

    private lateinit var cli: ButlerCli

    @BeforeEach
    fun start() {
        cli = ButlerCli(root)
    }

    @AfterEach
    fun stop() {
        cli.close()
    }

    private fun activeMode(vararg args: String): String {
        val run = cli.run(*args, stdin = "/permissions\n")
        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        val active = Regex("Active mode +(\\S+)").find(run.stdout)
        assertTrue(active != null, run.stdout)
        return active!!.groupValues[1]
    }

    @Test
    fun `without the setting a run starts in prompt`() {
        assertEquals("prompt", activeMode())
    }

    @Test
    fun `the setting picks the mode a run starts in, Clawcode's aliases included`() {
        cli.property("butler.permissions.default-mode", "workspace-write")
        assertEquals("workspace-write", activeMode())

        cli.property("butler.permissions.default-mode", "acceptEdits")
        assertEquals("workspace-write", activeMode())
    }

    @Test
    fun `a flag still wins over the setting`() {
        cli.property("butler.permissions.default-mode", "workspace-write")

        assertEquals("read-only", activeMode("--permission-mode", "read-only"))
        assertEquals("danger-full-access", activeMode("--dangerously-skip-permissions"))
    }

    @Test
    fun `workspace-write from the setting writes inside the project without asking`() {
        cli.property("butler.tools.enabled", "true").property("butler.permissions.default-mode", "workspace-write")
        cli.anthropic.callTools(ScriptedToolCall("toolu_1", "write_file", """{"path":"notes.txt","content":"hi"}""")).answer("Written.")

        val run = cli.run("--output-format", "json", "prompt", "write a note")

        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        val result = ProviderDialect.ANTHROPIC_MESSAGES.toolResults(cli.anthropic.requestBodies().last()).single()
        assertEquals(false, result.isError, result.output)
        assertEquals("hi", cli.workspace.resolve("notes.txt").readText())
    }

    @Test
    fun `an unknown value is a usage error naming the setting`() {
        cli.property("butler.permissions.default-mode", "admin")

        val run = cli.run("--output-format", "json", "status")

        assertEquals(ExitCodes.USAGE, run.exitCode)
        assertEquals("invalid_permission_mode", run.json()["kind"]!!.jsonPrimitive.content)
        assertEquals(
            "unsupported permission mode 'admin' in butler.permissions.default-mode.",
            run.json()["error"]!!.jsonPrimitive.content,
        )
        assertTrue(run.json()["hint"]!!.jsonPrimitive.content.contains("BUTLER_PERMISSIONS_DEFAULTMODE"))
    }
}
