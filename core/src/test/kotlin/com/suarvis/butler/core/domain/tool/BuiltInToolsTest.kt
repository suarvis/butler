package com.suarvis.butler.core.domain.tool

import com.suarvis.butler.core.domain.generic.ToolName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class BuiltInToolsTest {
    @Test
    fun `ships the six Clawcode tools in Clawcode order`() {
        assertEquals(
            listOf("bash", "read_file", "write_file", "edit_file", "glob_search", "grep_search"),
            BuiltInTools.ALL.map { it.name.value },
        )
    }

    @Test
    fun `each tool needs the same permission as in Clawcode`() {
        val tiers = BuiltInTools.ALL.associate { it.name.value to it.tier }

        assertEquals(PermissionTier.READ_ONLY, tiers["read_file"])
        assertEquals(PermissionTier.READ_ONLY, tiers["glob_search"])
        assertEquals(PermissionTier.READ_ONLY, tiers["grep_search"])
        assertEquals(PermissionTier.WORKSPACE_WRITE, tiers["write_file"])
        assertEquals(PermissionTier.WORKSPACE_WRITE, tiers["edit_file"])
        assertEquals(PermissionTier.DANGER_FULL_ACCESS, tiers["bash"])
    }

    @Test
    fun `tools are found by name`() {
        assertSame(BuiltInTools.EDIT_FILE, BuiltInTools.byName(ToolName("edit_file")))
        assertNull(BuiltInTools.byName(ToolName("WebFetch")))
    }

    @Test
    fun `bash offers no sandbox overrides to the model`() {
        val schema = BuiltInTools.BASH.inputSchema.value

        listOf("dangerouslyDisableSandbox", "run_in_background", "filesystemMode", "allowedMounts").forEach { field ->
            assertFalse(schema.contains("\"$field\""), "bash schema must not offer $field")
        }
    }
}
