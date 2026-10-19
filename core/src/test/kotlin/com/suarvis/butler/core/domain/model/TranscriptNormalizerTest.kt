package com.suarvis.butler.core.domain.model

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.MessageRole
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.ConversationMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptNormalizerTest {
    private val readFile = ToolName("read_file")
    private val offered = setOf(readFile)

    private fun call(id: String, name: ToolName = readFile) = ContentBlock.ToolUse(ToolCallId(id), name, JsonText("""{"path":"a.txt"}"""))

    private fun result(call: ContentBlock.ToolUse, output: String = "contents", isError: Boolean = false) =
        ConversationMessage.toolResult(ContentBlock.ToolResult(call.id, call.name, output, isError))

    private fun user(text: String) = ConversationMessage.userText(text)

    private fun assistant(vararg blocks: ContentBlock) = ConversationMessage.assistant(blocks.toList())

    @Test
    fun `thinking and empty text are dropped, and an answer left empty disappears`() {
        val transcript =
            listOf(
                user("one"),
                assistant(ContentBlock.Thinking("hmm", "sig"), ContentBlock.Text("")),
                user("two"),
                assistant(ContentBlock.Thinking("again", "sig"), ContentBlock.Text("answer")),
            )

        val normalized = TranscriptNormalizer.normalize(transcript, offered)

        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), normalized.map { it.role })
        assertEquals(listOf(ContentBlock.Text("one"), ContentBlock.Text("two")), normalized[0].blocks)
        assertEquals(listOf(ContentBlock.Text("answer")), normalized[1].blocks)
    }

    @Test
    fun `an offered tool with its result stays a native call with its original id`() {
        val call = call("toolu_01AbC")
        val transcript = listOf(user("read"), assistant(ContentBlock.Text("Reading"), call), result(call), user("thanks"))

        val normalized = TranscriptNormalizer.normalize(transcript, offered)

        assertEquals(transcript, normalized)
    }

    @Test
    fun `a tool that is not offered becomes text, so no provider sees an undefined tool`() {
        val bash = call("toolu_9", ToolName("bash"))
        val transcript = listOf(user("list"), assistant(bash), result(bash, "boom", isError = true), user("and?"))

        val normalized = TranscriptNormalizer.normalize(transcript, offered)

        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), normalized.map { it.role })
        assertEquals("""[tool call bash (toolu_9)] {"path":"a.txt"}""", normalized[1].text)
        assertEquals(listOf("[tool error bash (toolu_9)] boom", "and?"), normalized[2].blocks.map { (it as ContentBlock.Text).text })
    }

    @Test
    fun `ids another provider would reject are replaced consistently in the call and its result`() {
        val dotted = call("functions.read_file:0")
        val long = call("x".repeat(64))
        val transcript = listOf(user("read"), assistant(dotted, long), result(dotted), result(long))

        val normalized = TranscriptNormalizer.normalize(transcript, offered)

        val calls = normalized[1].toolUses
        val results = normalized[2].toolResults
        assertEquals(calls.map { it.id }, results.map { it.toolUseId })
        assertTrue(calls.all { Regex("call_[0-9a-f]{24}").matches(it.id.value) }, calls.toString())
        assertNotEquals(calls[0].id, calls[1].id)
    }

    @Test
    fun `repeated ids get unique replacements and keep their pairing`() {
        val first = call("call_0")
        val second = call("call_0")
        val transcript =
            listOf(
                user("a"),
                assistant(first),
                result(first, "first"),
                assistant(second),
                result(second, "second"),
                user("b"),
            )

        val normalized = TranscriptNormalizer.normalize(transcript, offered)

        val exchanges = normalized.filter { it.role == MessageRole.TOOL }.map { it.toolResults.single() }
        val calls = normalized.flatMap { it.toolUses }
        assertEquals("call_0", calls[0].id.value)
        assertNotEquals(calls[0].id, calls[1].id)
        assertEquals(calls.map { it.id }, exchanges.map { it.toolUseId })
        assertEquals(listOf("first", "second"), exchanges.map { it.output })
    }

    @Test
    fun `a call without a result becomes text even when its tool is offered`() {
        val dangling = call("toolu_5")
        val transcript = listOf(user("read"), assistant(ContentBlock.Text("Let me look"), dangling), user("never mind"))

        val normalized = TranscriptNormalizer.normalize(transcript, offered)

        assertTrue(normalized.none { it.toolUses.isNotEmpty() || it.toolResults.isNotEmpty() })
        assertEquals("Let me look\n[tool call read_file (toolu_5)] {\"path\":\"a.txt\"}", normalized[1].text)
    }

    @Test
    fun `results that answer no call become user text merged with the next prompt`() {
        val orphan = ContentBlock.ToolResult(ToolCallId("toolu_gone"), readFile, "stale", isError = false)
        val transcript = listOf(user("start"), ConversationMessage.toolResult(orphan), user("go on"))

        val normalized = TranscriptNormalizer.normalize(transcript, offered)

        assertEquals(1, normalized.size)
        assertEquals("start\n[tool result read_file (toolu_gone)] stale\ngo on", normalized.single().text)
    }

    @Test
    fun `stored system messages are not replayed`() {
        val transcript = listOf(ConversationMessage(MessageRole.SYSTEM, listOf(ContentBlock.Text("old system prompt"))), user("hi"))

        assertEquals(listOf(user("hi")), TranscriptNormalizer.normalize(transcript, offered))
    }
}
