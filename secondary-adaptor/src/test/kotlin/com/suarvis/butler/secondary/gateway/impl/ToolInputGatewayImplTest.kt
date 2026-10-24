package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.InvalidToolInputException
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.fixtures.SessionFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration

/** Decoding is lexical, so the workspace (`/work/demo`) never needs to exist on disk. */
class ToolInputGatewayImplTest {
    private val gateway = ToolInputGatewayImpl()

    private fun decode(tool: String, input: String): ToolInvocation = gateway.decode(SessionFixtures.workspace, ToolName(tool), JsonText(input))

    @Test
    fun `read_file decodes every field, and only the path is required`() {
        assertEquals(
            ToolInvocation.ReadFile(WorkspacePath("src/Main.kt"), offset = 10, limit = 20),
            decode("read_file", """{"path":"src/Main.kt","offset":10,"limit":20}"""),
        )
        assertEquals(ToolInvocation.ReadFile(WorkspacePath("README.md")), decode("read_file", """{"path":"README.md"}"""))
    }

    @Test
    fun `glob_search decodes every field, and only the pattern is required`() {
        assertEquals(ToolInvocation.GlobSearch("**/*.kt", WorkspacePath("src")), decode("glob_search", """{"pattern":"**/*.kt","path":"src"}"""))
        assertEquals(ToolInvocation.GlobSearch("*.md"), decode("glob_search", """{"pattern":"*.md"}"""))
    }

    @Test
    fun `grep_search decodes every field, and only the pattern is required`() {
        val full =
            """
            {"pattern":"TODO","path":"src","glob":"*.kt","output_mode":"content","-B":1,"-A":2,"-C":3,"context":4,
             "-n":true,"-i":false,"type":"kotlin","head_limit":50,"offset":5,"multiline":true}
            """

        assertEquals(
            ToolInvocation.GrepSearch(
                pattern = "TODO",
                under = WorkspacePath("src"),
                glob = "*.kt",
                outputMode = "content",
                before = 1,
                after = 2,
                context = 4,
                lineNumbers = true,
                ignoreCase = false,
                fileType = "kotlin",
                headLimit = 50,
                offset = 5,
                multiline = true,
            ),
            decode("grep_search", full),
        )
        assertEquals(ToolInvocation.GrepSearch("TODO"), decode("grep_search", """{"pattern":"TODO"}"""))
    }

    @Test
    fun `grep_search takes -C as the context when context is left out, as Clawcode does`() {
        assertEquals(ToolInvocation.GrepSearch("TODO", context = 3), decode("grep_search", """{"pattern":"TODO","-C":3}"""))
    }

    @Test
    fun `write_file decodes its two required fields, and the content may be empty`() {
        assertEquals(ToolInvocation.WriteFile(WorkspacePath("notes/a.txt"), "hello\n"), decode("write_file", """{"path":"notes/a.txt","content":"hello\n"}"""))
        assertEquals(ToolInvocation.WriteFile(WorkspacePath("empty.txt"), ""), decode("write_file", """{"path":"empty.txt","content":""}"""))
    }

    @Test
    fun `edit_file decodes every field, and replace_all defaults to false`() {
        assertEquals(
            ToolInvocation.EditFile(WorkspacePath("a.kt"), "val x = 1", "val x = 2", replaceAll = true),
            decode("edit_file", """{"path":"a.kt","old_string":"val x = 1","new_string":"val x = 2","replace_all":true}"""),
        )
        assertEquals(
            ToolInvocation.EditFile(WorkspacePath("a.kt"), "old", "new", replaceAll = false),
            decode("edit_file", """{"path":"a.kt","old_string":"old","new_string":"new"}"""),
        )
    }

    @Test
    fun `bash decodes every field, and only the command is required`() {
        assertEquals(
            ToolInvocation.Bash("ls -la", Duration.ofMillis(1500), "List files"),
            decode("bash", """{"command":"ls -la","timeout":1500,"description":"List files"}"""),
        )
        assertEquals(ToolInvocation.Bash("ls"), decode("bash", """{"command":"ls"}"""))
    }

    @Test
    fun `the bash timeout is milliseconds and may be longer than an Int holds`() {
        assertEquals(Duration.ofMinutes(2), (decode("bash", """{"command":"make","timeout":120000}""") as ToolInvocation.Bash).timeout)
        assertEquals(Duration.ofMillis(3_000_000_000), (decode("bash", """{"command":"make","timeout":3000000000}""") as ToolInvocation.Bash).timeout)
    }

    @Test
    fun `absolute paths inside the workspace become relative`() {
        assertEquals(ToolInvocation.ReadFile(WorkspacePath("src/Main.kt")), decode("read_file", """{"path":"/work/demo/src/Main.kt"}"""))
        assertEquals(ToolInvocation.WriteFile(WorkspacePath("a.txt"), "a"), decode("write_file", """{"path":"/work/demo/a.txt","content":"a"}"""))
        assertEquals(
            ToolInvocation.EditFile(WorkspacePath("docs/b.md"), "x", "y"),
            decode("edit_file", """{"path":"/work/demo/docs/./b.md","old_string":"x","new_string":"y"}"""),
        )
        assertEquals(ToolInvocation.GlobSearch("*.kt", WorkspacePath("src")), decode("glob_search", """{"pattern":"*.kt","path":"/work/demo/src"}"""))
        assertEquals(ToolInvocation.GrepSearch("x", under = WorkspacePath.ROOT_PATH), decode("grep_search", """{"pattern":"x","path":"/work/demo"}"""))
    }

    @Test
    fun `an explicit null counts as left out`() {
        assertEquals(ToolInvocation.ReadFile(WorkspacePath("a.txt")), decode("read_file", """{"path":"a.txt","offset":null,"limit":null}"""))
    }

    @ParameterizedTest
    @MethodSource("badInputs")
    fun `bad input is refused, naming the field`(tool: String, input: String, reason: String) {
        val error = assertThrows<InvalidToolInputException> { decode(tool, input) }

        assertEquals(ToolName(tool), error.tool)
        assertEquals("Invalid input for $tool: $reason", error.message)
    }

    @Test
    fun `MCP tools pass through untouched, even input this adaptor could not parse`() {
        assertEquals(
            ToolInvocation.External(ToolName("mcp__x__y"), JsonText("""{"anything":[1,2]}""")),
            decode("mcp__x__y", """{"anything":[1,2]}"""),
        )
        assertEquals(ToolInvocation.External(ToolName("mcp__x__y"), JsonText("not json")), decode("mcp__x__y", "not json"))
    }

    @Test
    fun `MCP calls carry the permission subject Clawcode would pick`() {
        val input = """{"message":"m","url":"https://example.com","nested":{"command":"no"}}"""

        assertEquals(ToolInvocation.External(ToolName("mcp__x__y"), JsonText(input), subject = "https://example.com"), decode("mcp__x__y", input))
    }

    @Test
    fun `fields the schema does not list are ignored, as Clawcode's serde structs ignore them`() {
        assertEquals(ToolInvocation.ReadFile(WorkspacePath("a.txt")), decode("read_file", """{"path":"a.txt","file_path":"b.txt"}"""))
        assertEquals(ToolInvocation.Bash("ls"), decode("bash", """{"command":"ls","dangerouslyDisableSandbox":true}"""))
    }

    @Test
    fun `any other unknown tool is refused`() {
        val error = assertThrows<InvalidToolInputException> { decode("delete_everything", """{"path":"."}""") }

        assertEquals(ToolName("delete_everything"), error.tool)
        assertEquals("Invalid input for delete_everything: unknown tool", error.message)
    }

    companion object {
        @JvmStatic
        fun badInputs(): List<Arguments> =
            listOf(
                // Not a JSON object
                Arguments.of("read_file", """["README.md"]""", "input must be a JSON object"),
                Arguments.of("bash", "\"ls\"", "input must be a JSON object"),
                Arguments.of("write_file", """{"path":""", "input is not valid JSON"),
                // Missing required fields
                Arguments.of("read_file", "{}", "path is required"),
                Arguments.of("read_file", """{"path":null}""", "path is required"),
                Arguments.of("glob_search", """{"path":"src"}""", "pattern is required"),
                Arguments.of("grep_search", "{}", "pattern is required"),
                Arguments.of("write_file", """{"path":"a.txt"}""", "content is required"),
                Arguments.of("edit_file", """{"path":"a.kt","old_string":"x"}""", "new_string is required"),
                Arguments.of("bash", """{"description":"nothing"}""", "command is required"),
                // Wrong types
                Arguments.of("read_file", """{"path":42}""", "path must be a string, got 42"),
                Arguments.of("read_file", """{"path":"a.txt","limit":"5"}""", "limit must be an integer, got a string"),
                Arguments.of("read_file", """{"path":"a.txt","offset":1.5}""", "offset must be an integer, got 1.5"),
                Arguments.of("glob_search", """{"pattern":["*.kt"]}""", "pattern must be a string, got an array"),
                Arguments.of("grep_search", """{"pattern":"x","-n":1}""", "-n must be a boolean, got 1"),
                Arguments.of("grep_search", """{"pattern":"x","-i":"true"}""", "-i must be a boolean, got a string"),
                Arguments.of("grep_search", """{"pattern":"x","type":{"name":"kotlin"}}""", "type must be a string, got an object"),
                Arguments.of("write_file", """{"path":"a.txt","content":true}""", "content must be a string, got true"),
                Arguments.of("edit_file", """{"path":"a.kt","old_string":"x","new_string":"y","replace_all":"yes"}""", "replace_all must be a boolean, got a string"),
                Arguments.of("bash", """{"command":"ls","timeout":"30s"}""", "timeout must be an integer, got a string"),
                // Values under the schema's minimums, or past what the field can hold
                Arguments.of("read_file", """{"path":"a.txt","offset":-1}""", "offset must be at least 0, got -1"),
                Arguments.of("read_file", """{"path":"a.txt","limit":0}""", "limit must be at least 1, got 0"),
                Arguments.of("read_file", """{"path":"a.txt","limit":3000000000}""", "limit must be at most 2147483647, got 3000000000"),
                Arguments.of("grep_search", """{"pattern":"x","-B":-1}""", "-B must be at least 0, got -1"),
                Arguments.of("grep_search", """{"pattern":"x","-A":-2}""", "-A must be at least 0, got -2"),
                Arguments.of("grep_search", """{"pattern":"x","-C":-1}""", "-C must be at least 0, got -1"),
                Arguments.of("grep_search", """{"pattern":"x","context":-1}""", "context must be at least 0, got -1"),
                Arguments.of("grep_search", """{"pattern":"x","head_limit":0}""", "head_limit must be at least 1, got 0"),
                Arguments.of("grep_search", """{"pattern":"x","offset":-1}""", "offset must be at least 0, got -1"),
                Arguments.of("bash", """{"command":"ls","timeout":0}""", "timeout must be at least 1, got 0"),
                // Paths outside the workspace, or no path at all
                Arguments.of("read_file", """{"path":"../x"}""", "path '../x' is outside the workspace"),
                Arguments.of("read_file", """{"path":"/etc/passwd"}""", "path '/etc/passwd' is outside the workspace"),
                Arguments.of("write_file", """{"path":"src/../../x","content":"a"}""", "path 'src/../../x' is outside the workspace"),
                Arguments.of("edit_file", """{"path":"/work/demo-other/a.kt","old_string":"x","new_string":"y"}""", "path '/work/demo-other/a.kt' is outside the workspace"),
                Arguments.of("glob_search", """{"pattern":"*","path":".."}""", "path '..' is outside the workspace"),
                Arguments.of("grep_search", """{"pattern":"x","path":"/tmp"}""", "path '/tmp' is outside the workspace"),
                Arguments.of("read_file", """{"path":"  "}""", "path must not be blank"),
            )
    }
}
