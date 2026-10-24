package com.suarvis.butler.core.domain.permission

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.tool.ToolInvocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PermissionSubjectTest {
    private val src = WorkspacePath("src")


    @Test
    fun `built-in calls offer the field Clawcode's lookup would find`() {
        val notes = WorkspacePath("docs/notes.md")

        assertEquals("git status", PermissionSubject.of(ToolInvocation.Bash("git status")))
        assertEquals("docs/notes.md", PermissionSubject.of(ToolInvocation.ReadFile(notes, offset = 10)))
        assertEquals("docs/notes.md", PermissionSubject.of(ToolInvocation.WriteFile(notes, "# Notes")))
        assertEquals("docs/notes.md", PermissionSubject.of(ToolInvocation.EditFile(notes, "a", "b")))
    }

    @Test
    fun `searches offer the path when given, otherwise the pattern`() {
        assertEquals("src", PermissionSubject.of(ToolInvocation.GlobSearch("**/*.kt", under = src)))
        assertEquals("**/*.kt", PermissionSubject.of(ToolInvocation.GlobSearch("**/*.kt")))
        assertEquals("src", PermissionSubject.of(ToolInvocation.GrepSearch("TODO", under = src)))
        assertEquals("TODO", PermissionSubject.of(ToolInvocation.GrepSearch("TODO", glob = "*.kt")))
    }

    @Test
    fun `external calls offer the subject the decoding adaptor picked`() {
        val input = JsonText("""{"url":"https://example.com","message":"m"}""")

        assertEquals("https://example.com", PermissionSubject.of(ToolInvocation.External(ToolName("mcp__demo__run"), input, subject = "https://example.com")))
    }

    @Test
    fun `external calls without a subject are matched whole, Clawcode's fallback`() {
        val input = JsonText("""{"query":"x"}""")

        assertEquals(input.value, PermissionSubject.of(ToolInvocation.External(ToolName("mcp__demo__run"), input)))
    }
}
