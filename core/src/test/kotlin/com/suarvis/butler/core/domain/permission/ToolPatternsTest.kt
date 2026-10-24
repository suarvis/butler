package com.suarvis.butler.core.domain.permission

import com.suarvis.butler.core.domain.exception.DomainValidationException
import com.suarvis.butler.core.domain.generic.ToolName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolPatternsTest {
    private val bash = ToolName("bash")

    @Test
    fun `a bare tool name matches every call of that tool`() {
        val pattern = ToolPatterns.parse("read_file")

        assertEquals(ToolPattern(ToolName("read_file")), pattern)
        assertTrue(pattern.matches(ToolName("read_file"), "notes.md"))
        assertFalse(pattern.matches(ToolName("write_file"), "notes.md"))
    }

    @Test
    fun `a star or empty parentheses match every call`() {
        listOf("bash(*)", "bash()", "bash( * )").forEach { rule ->
            val pattern = ToolPatterns.parse(rule)

            assertTrue(pattern.matches(bash, "rm -rf /"), rule)
            assertTrue(pattern.matches(bash, ""), rule)
        }
    }

    @Test
    fun `an argument without a suffix must match the subject exactly`() {
        val pattern = ToolPatterns.parse("bash(git status)")

        assertEquals(ToolPattern(bash, "git status"), pattern)
        assertTrue(pattern.matches(bash, "git status"))
        assertFalse(pattern.matches(bash, "git status --short"))
        assertFalse(pattern.matches(bash, "git"))
    }

    @Test
    fun `a colon-star suffix matches subjects that start with the rest, as a plain prefix`() {
        val pattern = ToolPatterns.parse("bash(git:*)")

        assertEquals(ToolPattern(bash, "git:*"), pattern)
        assertTrue(pattern.matches(bash, "git status"))
        assertTrue(pattern.matches(bash, "git"))
        assertTrue(pattern.matches(bash, "gitk"), "a plain prefix, as in Clawcode")
        assertFalse(pattern.matches(bash, "ls"))
        assertFalse(ToolPatterns.parse("bash(git :*)").matches(bash, "gitk"))
    }

    @Test
    fun `path prefixes work for file tools`() {
        val pattern = ToolPatterns.parse("write_file(src/:*)")

        assertTrue(pattern.matches(ToolName("write_file"), "src/main/App.kt"))
        assertFalse(pattern.matches(ToolName("write_file"), "test/AppTest.kt"))
    }

    @Test
    fun `escaped parentheses and backslashes stand for themselves`() {
        val parentheses = ToolPatterns.parse("""bash(echo \(hi\))""")
        val backslash = ToolPatterns.parse("""bash(printf a\\b)""")

        assertEquals("echo (hi)", parentheses.argument)
        assertTrue(parentheses.matches(bash, "echo (hi)"))
        assertEquals("""printf a\b""", backslash.argument)
        assertTrue(backslash.matches(bash, """printf a\b"""))
    }

    @Test
    fun `an unescaped parenthesis inside the argument belongs to it`() {
        val pattern = ToolPatterns.parse("bash(echo (hi))")

        assertEquals("echo (hi)", pattern.argument)
    }

    @Test
    fun `surrounding spaces are trimmed from the rule, the tool name and the argument`() {
        assertEquals(ToolPattern(bash, "git status"), ToolPatterns.parse("  bash ( git status )  "))
    }

    @Test
    fun `tool names keep their case but match without it`() {
        val pattern = ToolPatterns.parse("Bash(git:*)")

        assertEquals(ToolName("Bash"), pattern.tool)
        assertTrue(pattern.matches(bash, "git status"))
        assertTrue(ToolPatterns.parse("mcp__github__create_issue").matches(ToolName("mcp__GitHub__create_issue"), "{}"))
    }

    @Test
    fun `a pattern writes back to the rule it was read from`() {
        listOf("read_file", "bash(git:*)", "bash(*)", "bash()", """bash(echo \(hi\))""", """bash(printf a\\b)""").forEach { rule ->
            assertEquals(rule, ToolPatterns.parse(rule).rule)
        }
        assertEquals("""bash(echo \(hi\))""", ToolPattern(bash, "echo (hi)").rule)
    }

    @Test
    fun `text that is not a rule is refused with the rule in the message`() {
        listOf("bash(", "bash(git) status", "(git status)", "two words", "bash)(").forEach { rule ->
            val error = assertThrows<DomainValidationException>(rule) { ToolPatterns.parse(rule) }

            assertTrue(error.message!!.contains("'$rule'"), error.message)
        }
        assertThrows<DomainValidationException> { ToolPatterns.parse("   ") }
    }
}
