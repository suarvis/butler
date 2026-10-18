package com.suarvis.butler.api.cli

import com.suarvis.butler.api.cli.exception.CliUsageException
import com.suarvis.butler.api.cli.mapper.SessionCliMapper
import com.suarvis.butler.api.cli.repl.SlashCommand
import com.suarvis.butler.api.cli.repl.SlashCommandParser
import com.suarvis.butler.api.security.authorization.AuthorizationDecision
import com.suarvis.butler.api.security.authorization.rules.BroadWorkingDirectoryRule
import com.suarvis.butler.api.security.authorization.rules.WorkingDirectoryRequest
import com.suarvis.butler.core.domain.generic.Page
import com.suarvis.butler.core.domain.generic.PageRequest
import com.suarvis.butler.core.fixtures.SessionFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

/** Primary-adaptor logic that needs no Spring context. */
class CliUnitTest {
    @Nested
    inner class SlashCommands {
        @Test
        fun `groups tokens into commands with arguments`() {
            val commands = SlashCommandParser.parse(listOf("/status", "/export", "notes.md", "/session", "fork", "try-b", "/session"))

            assertEquals(
                listOf(SlashCommand.Status, SlashCommand.Export("notes.md"), SlashCommand.SessionFork("try-b"), SlashCommand.SessionList),
                commands,
            )
        }

        @Test
        fun `delete accepts Clawcode's optional --force`() {
            assertEquals(listOf(SlashCommand.SessionDelete("s-1")), SlashCommandParser.parse(listOf("/session", "delete", "s-1", "--force")))
        }

        @Test
        fun `rejects unknown commands and stray arguments`() {
            assertEquals("unsupported_resumed_command", assertThrows<CliUsageException> { SlashCommandParser.parse(listOf("/teleport")) }.kind)
            assertThrows<CliUsageException> { SlashCommandParser.parse(listOf("status")) }
            assertThrows<CliUsageException> { SlashCommandParser.parse(listOf("/status", "extra")) }
            assertThrows<CliUsageException> { SlashCommandParser.parse(listOf("/session", "switch")) }
        }
    }

    @Nested
    inner class OutputFormats {
        @Test
        fun `detects the flag in either spelling and falls back to the environment`() {
            assertEquals(OutputFormat.JSON, OutputFormat.detect(listOf("status", "--output-format", "json"), null))
            assertEquals(OutputFormat.JSON, OutputFormat.detect(listOf("--output-format=JSON", "status"), null))
            assertEquals(OutputFormat.JSON, OutputFormat.detect(listOf("status"), "json"))
            assertEquals(OutputFormat.TEXT, OutputFormat.detect(listOf("--output-format", "xml"), null))
            assertEquals(OutputFormat.TEXT, OutputFormat.detect(listOf("--", "--output-format", "json"), null))
        }
    }

    @Nested
    inner class BroadWorkingDirectory {
        private val rule = BroadWorkingDirectoryRule()
        private val home = Path.of("/Users/someone")

        @Test
        fun `refuses home and root unless allowed`() {
            assertInstanceOf(AuthorizationDecision.Denied::class.java, rule.evaluate(WorkingDirectoryRequest(home, home, false)))
            assertInstanceOf(AuthorizationDecision.Denied::class.java, rule.evaluate(WorkingDirectoryRequest(Path.of("/"), home, false)))
            assertEquals(AuthorizationDecision.Granted, rule.evaluate(WorkingDirectoryRequest(home, home, true)))
        }

        @Test
        fun `accepts project folders`() {
            assertEquals(AuthorizationDecision.Granted, rule.evaluate(WorkingDirectoryRequest(home.resolve("dev/app"), home, false)))
        }
    }

    @Nested
    inner class Paging {
        @Test
        fun `translates 1-based pages into offsets and back`() {
            val request = SessionCliMapper.toPageRequest(page = 3, size = 20)
            assertEquals(PageRequest(offset = 40, limit = 20), request)

            val summaries = listOf(SessionFixtures.emptySession().summary())
            val output = SessionCliMapper.toListOutput(SessionFixtures.workspace, Page(summaries, totalItems = 41, request = request))
            assertEquals(3, output.page.page)
            assertEquals(false, output.page.hasNext)
        }
    }
}
