package com.suarvis.butler.api.cli.dto

import kotlinx.serialization.Serializable

/**
 * One tool in `butler tools`. [tier] uses Clawcode's permission mode names (`read-only`,
 * `workspace-write`, `danger-full-access`); [source] is `built-in` or `mcp`.
 */
@Serializable
data class ToolEntryOutput(
    val name: String,
    val tier: String,
    val description: String,
    val source: String,
)

/**
 * `butler tools`: Clawcode's `claw tools` envelope (`kind`, `action`, `status`, `tools`, `count`),
 * plus each tool's tier and source and whether turns offer tools at all (`butler.tools.enabled`).
 */
@Serializable
data class ToolListOutput(
    val kind: String = "tools",
    val action: String = "list",
    val status: String = "ok",
    val enabled: Boolean,
    val tools: List<ToolEntryOutput>,
    val count: Int,
)
