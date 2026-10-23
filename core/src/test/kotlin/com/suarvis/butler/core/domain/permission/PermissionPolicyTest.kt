package com.suarvis.butler.core.domain.permission

import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.domain.tool.ToolInvocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionPolicyTest {
    private val notes = WorkspacePath("notes.md")

    private val readOnly: Map<ToolDefinition, ToolInvocation> =
        mapOf(
            BuiltInTools.READ_FILE to ToolInvocation.ReadFile(notes),
            BuiltInTools.GLOB_SEARCH to ToolInvocation.GlobSearch("**/*.kt"),
            BuiltInTools.GREP_SEARCH to ToolInvocation.GrepSearch("TODO"),
        )

    private val needsApproval: Map<ToolDefinition, ToolInvocation> =
        mapOf(
            BuiltInTools.WRITE_FILE to ToolInvocation.WriteFile(notes, "# Notes"),
            BuiltInTools.EDIT_FILE to ToolInvocation.EditFile(notes, "Notes", "Plans"),
            BuiltInTools.BASH to ToolInvocation.Bash("ls"),
        )

    @Test
    fun `read-only tools are allowed in every mode`() {
        PermissionMode.entries.forEach { mode ->
            readOnly.forEach { (tool, invocation) ->
                assertEquals(PermissionDecision.Allow, PermissionPolicy.decide(invocation, tool, mode, PermissionRules.NONE), "${tool.name} in $mode")
            }
        }
    }

    @Test
    fun `writing files and running commands ask in every mode until the real policy arrives`() {
        PermissionMode.entries.forEach { mode ->
            needsApproval.forEach { (tool, invocation) ->
                val decision = PermissionPolicy.decide(invocation, tool, mode, PermissionRules.NONE)

                val ask = assertInstanceOf(PermissionDecision.Ask::class.java, decision, "${tool.name} in $mode")
                assertTrue(ask.reason.contains(tool.name.value), ask.reason)
            }
        }
    }

    @Test
    fun `permission modes carry Clawcode's CLI names`() {
        assertEquals(
            listOf("read-only", "workspace-write", "danger-full-access", "prompt", "allow"),
            PermissionMode.entries.map { it.cliName },
        )
    }
}
