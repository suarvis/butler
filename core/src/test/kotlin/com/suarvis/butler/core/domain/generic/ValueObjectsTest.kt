package com.suarvis.butler.core.domain.generic

import com.suarvis.butler.core.domain.exception.DomainValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class ValueObjectsTest {
    @ParameterizedTest
    @ValueSource(strings = ["session-1743724800123-0", "a", "abc.DEF_123-x"])
    fun `accepts safe session ids`(raw: String) {
        assertDoesNotThrow { SessionId(raw) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "-leading-dash", "../escape", "has/slash", "has space", ".hidden"])
    fun `rejects session ids that are unsafe as file names`(raw: String) {
        assertThrows<DomainValidationException> { SessionId(raw) }
    }

    @Test
    fun `rejects session ids longer than 128 characters`() {
        assertThrows<DomainValidationException> { SessionId("a".repeat(129)) }
    }

    @Test
    fun `tool names allow MCP style names`() {
        assertEquals("mcp__github__create_issue", ToolName("mcp__github__create_issue").value)
        assertThrows<DomainValidationException> { ToolName("bad name") }
    }

    @Test
    fun `model ids reject whitespace`() {
        assertThrows<DomainValidationException> { ModelId("claude sonnet") }
        assertEquals("claude-sonnet-4-6", ModelId("claude-sonnet-4-6").value)
    }

    @Test
    fun `workspace root must be absolute and is normalised by the factory`() {
        assertEquals(Path.of("/work/demo"), WorkspaceRoot.of(Path.of("/work/./other/../demo")).path)
        assertThrows<DomainValidationException> { WorkspaceRoot.of(Path.of("relative/dir")) }
        assertThrows<DomainValidationException> { WorkspaceRoot(Path.of("/work/../demo")) }
    }

    @Test
    fun `token usage adds field by field and totals all counters`() {
        val sum = TokenUsage(1, 2, 3, 4) + TokenUsage(10, 20, 30, 40)

        assertEquals(TokenUsage(11, 22, 33, 44), sum)
        assertEquals(110, sum.totalTokens)
        assertThrows<DomainValidationException> { TokenUsage(inputTokens = -1) }
    }

    @Test
    fun `page knows whether more items follow`() {
        val request = PageRequest(offset = 10, limit = 5)

        assertTrue(Page(listOf(1, 2, 3, 4, 5), totalItems = 16, request = request).hasNext)
        assertFalse(Page(listOf(1, 2, 3, 4, 5), totalItems = 15, request = request).hasNext)
        assertEquals(listOf("1"), Page(listOf(1), 1, PageRequest.firstPage()).map(Int::toString).items)
    }

    @Test
    fun `page request validates its window`() {
        assertThrows<DomainValidationException> { PageRequest(offset = -1, limit = 10) }
        assertThrows<DomainValidationException> { PageRequest(offset = 0, limit = 0) }
        assertThrows<DomainValidationException> { PageRequest(offset = 0, limit = PageRequest.MAX_LIMIT + 1) }
    }
}
