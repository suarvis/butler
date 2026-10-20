package com.suarvis.butler.api.cli.prompt

import com.suarvis.butler.api.cli.CliConsole
import com.suarvis.butler.api.cli.OutputFormat
import com.suarvis.butler.core.domain.budget.Budget
import com.suarvis.butler.core.domain.budget.BudgetBreach
import com.suarvis.butler.core.domain.budget.BudgetScope
import com.suarvis.butler.core.domain.budget.Spend
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.routing.RoutingObstacle
import com.suarvis.butler.core.domain.turn.ApprovalDecision
import com.suarvis.butler.core.domain.turn.ApprovalRequest
import com.suarvis.butler.core.fixtures.FixedModelCatalog
import com.suarvis.butler.core.fixtures.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.time.Instant

class CliTurnApproverTest {
    private class FakeConsole(typed: String, override val interactive: Boolean) : CliConsole {
        private val lines = typed.byteInputStream().bufferedReader()
        private val outBuffer = ByteArrayOutputStream()
        private val errBuffer = ByteArrayOutputStream()

        override val input: InputStream = ByteArrayInputStream(ByteArray(0))
        override val out = PrintStream(outBuffer, true, Charsets.UTF_8)
        override val err = PrintStream(errBuffer, true, Charsets.UTF_8)

        override fun readLine(): String? = lines.readLine()

        val printed: String
            get() = outBuffer.toString(Charsets.UTF_8)

        val warnings: String
            get() = errBuffer.toString(Charsets.UTF_8)
    }

    private val now = Instant.parse("2026-10-17T09:00:00Z")
    private val catalog = FixedModelCatalog()
    private val sonnet = catalog.resolve(ModelId("sonnet"))
    private val gpt = catalog.resolve(ModelId("gpt-5.5"))
    private val local = catalog.resolve(ModelId("ollama/llama3.2"))

    private val overBudget =
        ApprovalRequest(
            sonnet,
            listOf(
                RoutingObstacle.OverBudget(
                    listOf(
                        BudgetBreach(
                            Budget(BudgetScope.Provider(ProviderId("anthropic")), BigDecimal("50"), null),
                            Spend(BigDecimal("49.90"), 1_000),
                            Spend(BigDecimal("50.20"), 2_000),
                        ),
                    ),
                ),
            ),
            listOf(gpt, local),
        )

    private val rateLimited = ApprovalRequest(sonnet, listOf(RoutingObstacle.RateLimited(ProviderId("anthropic"), now.plusSeconds(40))), listOf(gpt))

    private fun approver(console: CliConsole, allowOverBudget: Boolean = false, format: OutputFormat = OutputFormat.TEXT) =
        CliTurnApprover(console, format, allowOverBudget, MutableClock(now))

    @Test
    fun `a person can go over budget once and is not asked again in the same session`() {
        val console = FakeConsole("c\n", interactive = true)
        val approver = approver(console)

        assertEquals(ApprovalDecision.Continue, approver.decide(overBudget))
        assertEquals(ApprovalDecision.Continue, approver.decide(overBudget))
        assertTrue(console.printed.contains("anthropic monthly budget: $49.90 of $50.00 spent"), console.printed)
        assertTrue(console.printed.contains("[2] switch to ollama/llama3.2 (ollama)"), console.printed)
        assertEquals(1, Regex("Approval needed").findAll(console.printed).count())
    }

    @Test
    fun `a person can switch to one of the offered models by number`() {
        val console = FakeConsole("2\n", interactive = true)

        assertEquals(ApprovalDecision.SwitchTo(local), approver(console).decide(overBudget))
    }

    @Test
    fun `an unclear answer is asked again, and stop is the default`() {
        val console = FakeConsole("maybe\n\n", interactive = true)

        assertEquals(ApprovalDecision.Stop, approver(console).decide(rateLimited))
        assertTrue(console.printed.contains("anthropic is rate limited for another 40s"), console.printed)
        assertTrue(console.printed.contains("[c] try anthropic anyway"), console.printed)
        assertTrue(console.printed.contains("Type c, s or the number of a model."), console.printed)
    }

    @Test
    fun `scripts are never asked and stop`() {
        assertEquals(ApprovalDecision.Stop, approver(FakeConsole("c\n", interactive = false)).decide(overBudget))
        assertEquals(ApprovalDecision.Stop, approver(FakeConsole("c\n", interactive = true), format = OutputFormat.JSON).decide(overBudget))
    }

    @Test
    fun `--allow-over-budget covers budgets only, with a warning`() {
        val console = FakeConsole("", interactive = false)
        val approver = approver(console, allowOverBudget = true)

        assertEquals(ApprovalDecision.Continue, approver.decide(overBudget))
        assertEquals(ApprovalDecision.Stop, approver.decide(rateLimited))
        assertTrue(console.warnings.contains("warning: anthropic monthly budget"), console.warnings)
    }
}
