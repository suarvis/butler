package com.suarvis.butler.api.cli.dto

import kotlinx.serialization.Serializable

/*
 * JSON contract of the CLI (`--output-format json`). Field names are serialised in snake_case and
 * follow Clawcode's envelopes: every object carries `kind` and `status`. These classes never
 * reference domain types; *CliMapper classes translate.
 */

@Serializable
data class ErrorOutput(
    val type: String = "error",
    val kind: String,
    val status: String = "error",
    val errorKind: String,
    val error: String,
    val message: String,
    val action: String = "abort",
    val hint: String?,
    val exitCode: Int,
)

@Serializable
data class HelpOutput(
    val kind: String = "help",
    val status: String = "ok",
    val message: String,
)

@Serializable
data class VersionOutput(
    val kind: String = "version",
    val status: String = "ok",
    val name: String = "butler",
    val version: String,
    val javaVersion: String,
    val message: String,
)

@Serializable
data class UsageOutput(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationInputTokens: Long,
    val cacheReadInputTokens: Long,
    val totalTokens: Long,
)

@Serializable
data class WorkspaceOutput(
    val path: String,
    val persistenceStore: String,
    val sessionCount: Long,
)

@Serializable
data class SessionStatusOutput(
    val id: String,
    val model: String?,
    val messages: Int,
    val turns: Int,
    val pendingToolCalls: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val parentSessionId: String?,
    val branchName: String?,
    val latestUsage: UsageOutput?,
    val cumulativeUsage: UsageOutput,
)

@Serializable
data class StatusOutput(
    val kind: String = "status",
    val status: String = "ok",
    val workspace: WorkspaceOutput,
    val session: SessionStatusOutput?,
)

@Serializable
data class SessionDetailOutput(
    val id: String,
    val model: String?,
    val messageCount: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val parentSessionId: String?,
    val branchName: String?,
)

@Serializable
data class PageOutput(
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val hasNext: Boolean,
)

@Serializable
data class SessionListOutput(
    val kind: String = "sessions",
    val status: String = "ok",
    val action: String = "list",
    val workspace: String,
    val sessions: List<String>,
    val sessionDetails: List<SessionDetailOutput>,
    val page: PageOutput,
)

@Serializable
data class SessionExistsOutput(
    val kind: String = "sessions",
    val status: String = "ok",
    val action: String = "exists",
    val target: String,
    val exists: Boolean,
    val sessionId: String?,
)

@Serializable
data class SessionForkOutput(
    val kind: String = "sessions",
    val status: String = "ok",
    val action: String = "fork",
    val sessionId: String,
    val parentSessionId: String,
    val branchName: String?,
    val messageCount: Int,
)

@Serializable
data class SessionSwitchOutput(
    val kind: String = "sessions",
    val status: String = "ok",
    val action: String = "switch",
    val sessionId: String,
    val messageCount: Int,
)

@Serializable
data class SessionDeleteOutput(
    val kind: String = "sessions",
    val status: String = "ok",
    val action: String = "delete",
    val sessionId: String,
    val deleted: Boolean = true,
)

@Serializable
data class RestoredOutput(
    val kind: String = "restored",
    val status: String = "ok",
    val sessionId: String,
    val messageCount: Int,
    val message: String,
)

@Serializable
data class ExportOutput(
    val kind: String = "export",
    val status: String = "ok",
    val sessionId: String,
    val messageCount: Int,
    val outputPath: String?,
    val markdown: String?,
)

@Serializable
data class ToolUseOutput(
    val id: String,
    val name: String,
    val input: String,
)

@Serializable
data class ToolResultOutput(
    val toolUseId: String,
    val toolName: String,
    val output: String,
    val isError: Boolean,
)

/** Clawcode's one-shot prompt result (`claw --output-format json prompt ...`) plus kind/status/session/provider. */
@Serializable
data class PromptResultOutput(
    val kind: String = "prompt",
    val status: String = "ok",
    val message: String,
    val model: String,
    val provider: String,
    val sessionId: String,
    val iterations: Int,
    val autoCompaction: String? = null,
    val toolUses: List<ToolUseOutput>,
    val toolResults: List<ToolResultOutput>,
    val promptCacheEvents: List<String> = emptyList(),
    val usage: UsageOutput,
    val estimatedCost: String?,
    val stopReason: String,
)

@Serializable
data class UsageModelOutput(
    val provider: String,
    val model: String,
    val calls: Long,
    val costUsd: String,
    val tokens: Long,
)

@Serializable
data class UsageProviderOutput(
    val provider: String,
    val costUsd: String,
    val tokens: Long,
)

@Serializable
data class BudgetOutput(
    val scope: String,
    val name: String,
    val monthlyUsd: String?,
    val monthlyTokens: Long?,
    val spentUsd: String,
    val spentTokens: Long,
    val exceeded: Boolean,
)

/** `butler usage`: this month's estimated spend and where each budget stands. */
@Serializable
data class UsageReportOutput(
    val kind: String = "usage",
    val status: String = "ok",
    val period: String,
    val periodLabel: String,
    val timeZone: String,
    val totalCostUsd: String,
    val totalTokens: Long,
    val providers: List<UsageProviderOutput>,
    val models: List<UsageModelOutput>,
    val budgets: List<BudgetOutput>,
)
