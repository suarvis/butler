package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration

/**
 * Every rule of `read_file`, `glob_search` and `grep_search` against real files, each in its own
 * test. The workspace and a folder outside it sit side by side in a temp folder, so symlinks can
 * lead out of the workspace.
 */
class LocalWorkspaceGatewayImplTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var root: Path
    private lateinit var outside: Path
    private val gateway = LocalWorkspaceGatewayImpl()

    @BeforeEach
    fun createFolders() {
        root = Files.createDirectories(temp.resolve("workspace")).toRealPath()
        outside = Files.createDirectories(temp.resolve("outside")).toRealPath()
    }

    // read_file

    @Test
    fun `read_file answers with Clawcode's envelope as pretty JSON`() {
        file("notes.txt", "one\ntwo\n")

        val outcome = run(ToolInvocation.ReadFile(WorkspacePath("notes.txt")))

        val expected =
            """
            {
              "type": "text",
              "file": {
                "filePath": "${root.resolve("notes.txt")}",
                "content": "one\ntwo",
                "numLines": 2,
                "startLine": 1,
                "totalLines": 2
              }
            }
            """.trimIndent()
        assertEquals(ToolOutcome(expected), outcome)
    }

    @Test
    fun `limit alone reads that many lines from the start`() {
        file("lines.txt", "a\nb\nc\nd\n")

        val read = readFile("lines.txt", limit = 2)

        assertEquals("a\nb", read.content)
        assertEquals(listOf(2, 1, 4), listOf(read.numLines, read.startLine, read.totalLines))
    }

    @Test
    fun `offset is a 0-based line index and startLine is offset plus one`() {
        file("lines.txt", "a\nb\nc\nd\n")

        val read = readFile("lines.txt", offset = 1, limit = 2)

        assertEquals("b\nc", read.content)
        assertEquals(listOf(2, 2, 4), listOf(read.numLines, read.startLine, read.totalLines))
    }

    @Test
    fun `an offset past the end reads no lines`() {
        file("lines.txt", "a\nb\nc\n")

        val read = readFile("lines.txt", offset = 10)

        assertEquals("", read.content)
        assertEquals(listOf(0, 4, 3), listOf(read.numLines, read.startLine, read.totalLines))
    }

    @Test
    fun `an empty file has no lines`() {
        file("empty.txt", "")

        val read = readFile("empty.txt")

        assertEquals("", read.content)
        assertEquals(listOf(0, 1, 0), listOf(read.numLines, read.startLine, read.totalLines))
    }

    @Test
    fun `CRLF endings are dropped and a final line ending adds no line`() {
        file("windows.txt", "a\r\nb\r\n")

        val read = readFile("windows.txt")

        assertEquals("a\nb", read.content)
        assertEquals(2, read.totalLines)
    }

    @Test
    fun `a file over 10 MB is refused before it is read`() {
        RandomAccessFile(root.resolve("big.txt").toFile(), "rw").use { it.setLength(10L * 1024 * 1024 + 1) }

        val outcome = run(ToolInvocation.ReadFile(WorkspacePath("big.txt")))

        assertEquals(ToolOutcome("file is too large (10485761 bytes, max 10485760 bytes)", isError = true), outcome)
    }

    @Test
    fun `a NUL byte in the first 8 KB means binary`() {
        Files.write(root.resolve("image.png"), byteArrayOf(0x89.toByte(), 0x50, 0x00, 0x47))

        val outcome = run(ToolInvocation.ReadFile(WorkspacePath("image.png")))

        assertEquals(ToolOutcome("file appears to be binary", isError = true), outcome)
    }

    @Test
    fun `a NUL byte after the first 8 KB is not looked for`() {
        file("late.txt", "a".repeat(8192) + "\u0000")

        assertEquals(8193, readFile("late.txt").content.length)
    }

    @Test
    fun `text that is not UTF-8 is refused`() {
        Files.write(root.resolve("latin1.txt"), byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()))

        val outcome = run(ToolInvocation.ReadFile(WorkspacePath("latin1.txt")))

        assertEquals(ToolOutcome("stream did not contain valid UTF-8", isError = true), outcome)
    }

    @Test
    fun `a missing file is an error outcome`() {
        val outcome = run(ToolInvocation.ReadFile(WorkspacePath("missing.txt")))

        assertEquals(ToolOutcome("No such file or directory (os error 2)", isError = true), outcome)
    }

    @Test
    fun `a folder is an error outcome`() {
        Files.createDirectories(root.resolve("src"))

        val outcome = run(ToolInvocation.ReadFile(WorkspacePath("src")))

        assertEquals(ToolOutcome("Is a directory (os error 21)", isError = true), outcome)
    }

    @Test
    fun `a link inside the workspace is read through to its real path`() {
        file("real.txt", "hello")
        link(root.resolve("alias.txt"), root.resolve("real.txt"))

        val read = readFile("alias.txt")

        assertEquals("hello", read.content)
        assertEquals(root.resolve("real.txt").toString(), read.filePath)
    }

    @Test
    fun `a link leading outside the workspace is refused`() {
        Files.writeString(outside.resolve("secret.txt"), "secret")
        link(root.resolve("secret.txt"), outside.resolve("secret.txt"))

        val outcome = run(ToolInvocation.ReadFile(WorkspacePath("secret.txt")))

        assertEquals(ToolOutcome("path ${outside.resolve("secret.txt")} escapes workspace boundary $root", isError = true), outcome)
    }

    @Test
    fun `a file under a linked folder that leads outside is refused`() {
        Files.writeString(outside.resolve("secret.txt"), "secret")
        link(root.resolve("shared"), outside)

        val outcome = run(ToolInvocation.ReadFile(WorkspacePath("shared/secret.txt")))

        assertEquals(ToolOutcome("path ${outside.resolve("secret.txt")} escapes workspace boundary $root", isError = true), outcome)
    }

    // glob_search

    @Test
    fun `glob_search answers with Clawcode's envelope keys`() {
        file("a.kt", "")

        val result = success(run(ToolInvocation.GlobSearch("*.kt")))

        assertEquals(listOf("durationMs", "numFiles", "filenames", "truncated"), result.keys.toList())
        assertEquals(1, result.getValue("numFiles").jsonPrimitive.int)
        assertEquals(listOf(path("a.kt")), filenames(result))
        assertFalse(result.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test
    fun `a relative pattern starts at the workspace and double star spans any number of folders`() {
        file("a.kt", "")
        file("src/b.kt", "")
        file("src/deep/c.kt", "")
        file("d.md", "")

        val found = globFiles("**/*.kt")

        assertEquals(setOf(path("a.kt"), path("src/b.kt"), path("src/deep/c.kt")), found.toSet())
    }

    @Test
    fun `a single star also crosses folders, as in Clawcode's glob crate`() {
        file("a.kt", "")
        file("src/b.kt", "")

        assertEquals(setOf(path("a.kt"), path("src/b.kt")), globFiles("*.kt").toSet())
    }

    @Test
    fun `path narrows the search and the pattern is relative to it`() {
        file("a.kt", "")
        file("src/b.kt", "")
        file("src/deep/c.kt", "")

        val found = globFiles("*.kt", under = "src")

        assertEquals(setOf(path("src/b.kt"), path("src/deep/c.kt")), found.toSet())
    }

    @Test
    fun `braces expand into alternatives`() {
        file("a.rs", "")
        file("b.toml", "")
        file("c.txt", "")

        assertEquals(setOf(path("a.rs"), path("b.toml")), globFiles("*.{rs,toml}").toSet())
    }

    @Test
    fun `a pattern without wildcards finds that one file`() {
        file("src/b.kt", "")
        file("src/c.kt", "")

        assertEquals(listOf(path("src/b.kt")), globFiles("src/b.kt"))
    }

    @Test
    fun `Clawcode's ignored folders are not searched`() {
        val ignored = listOf(".git", "node_modules", ".build", "target", "dist", "coverage")
        ignored.forEach { file("$it/pkg/AGENTS.md", "") }
        file("src/AGENTS.md", "")

        assertEquals(listOf(path("src/AGENTS.md")), globFiles("**/AGENTS.md"))
    }

    @Test
    fun `matches are sorted newest first`() {
        listOf("old.txt" to 1_000L, "new.txt" to 3_000L, "mid.txt" to 2_000L).forEach { (name, seconds) ->
            file(name, "")
            Files.setLastModifiedTime(root.resolve(name), FileTime.fromMillis(seconds * 1000))
        }

        assertEquals(listOf(path("new.txt"), path("mid.txt"), path("old.txt")), globFiles("*.txt"))
    }

    @Test
    fun `more than 100 matches give the newest 100 and truncated`() {
        (1..101).forEach { file("f$it.txt", "") }

        val result = success(run(ToolInvocation.GlobSearch("*.txt")))

        assertEquals(100, result.getValue("numFiles").jsonPrimitive.int)
        assertEquals(100, filenames(result).size)
        assertTrue(result.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test
    fun `links are not followed, so nothing outside is found`() {
        Files.writeString(outside.resolve("secret.txt"), "secret")
        file("real.txt", "")
        link(root.resolve("shared"), outside)
        link(root.resolve("alias.txt"), root.resolve("real.txt"))

        assertEquals(listOf(path("real.txt")), globFiles("**/*.txt"))
    }

    @Test
    fun `an absolute pattern outside the workspace is refused`() {
        Files.writeString(outside.resolve("secret.txt"), "secret")

        val outcome = run(ToolInvocation.GlobSearch("$outside/*.txt"))

        assertEquals(ToolOutcome("path $outside escapes workspace boundary $root", isError = true), outcome)
    }

    @Test
    fun `an invalid pattern is an error outcome with the glob crate's message`() {
        val outcome = run(ToolInvocation.GlobSearch("a**"))

        // The pattern is matched as "<root>/a**"; the crate reports the character before the stars.
        val position = root.toString().length + 1
        assertEquals(ToolOutcome("Pattern syntax error near position $position: recursive wildcards must form a single path component", isError = true), outcome)
    }

    @Test
    fun `a missing search path is an error outcome`() {
        val outcome = run(ToolInvocation.GlobSearch("*.kt", WorkspacePath("missing")))

        assertEquals(ToolOutcome("No such file or directory (os error 2)", isError = true), outcome)
    }

    // grep_search

    @Test
    fun `grep_search lists matching files by default, in Clawcode's envelope`() {
        file("a.txt", "a TODO here\n")
        file("b.txt", "nothing\n")

        val outcome = run(ToolInvocation.GrepSearch("TODO"))

        val expected =
            """
            {
              "mode": "files_with_matches",
              "numFiles": 1,
              "filenames": [
                "${path("a.txt")}"
              ],
              "content": null,
              "numLines": null,
              "numMatches": null,
              "appliedLimit": null,
              "appliedOffset": null
            }
            """.trimIndent()
        assertEquals(ToolOutcome(expected), outcome)
    }

    @Test
    fun `no match gives an empty list on one line, as serde_json writes it`() {
        file("a.txt", "nothing\n")

        val outcome = run(ToolInvocation.GrepSearch("TODO", outputMode = "count"))

        val expected =
            """
            {
              "mode": "count",
              "numFiles": 0,
              "filenames": [],
              "content": null,
              "numLines": null,
              "numMatches": 0,
              "appliedLimit": null,
              "appliedOffset": null
            }
            """.trimIndent()
        assertEquals(ToolOutcome(expected), outcome)
    }

    @Test
    fun `content mode gives path, line number and line`() {
        file("a.txt", "x\nTODO one\ny\n")

        val result = grep(ToolInvocation.GrepSearch("TODO", outputMode = "content"))

        assertEquals("${path("a.txt")}:2:TODO one", result.getValue("content").jsonPrimitive.content)
        assertEquals(1, result.getValue("numLines").jsonPrimitive.int)
        assertEquals(1, result.getValue("numFiles").jsonPrimitive.int)
    }

    @Test
    fun `line numbers false drops them from content`() {
        file("a.txt", "x\nTODO one\n")

        val result = grep(ToolInvocation.GrepSearch("TODO", outputMode = "content", lineNumbers = false))

        assertEquals("${path("a.txt")}:TODO one", result.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `count mode counts every match over the whole file`() {
        file("a.txt", "aa a\na\n")
        file("b.txt", "none\n")

        val result = grep(ToolInvocation.GrepSearch("a", outputMode = "count"))

        assertEquals("count", result.getValue("mode").jsonPrimitive.content)
        assertEquals(4, result.getValue("numMatches").jsonPrimitive.int)
        assertEquals(listOf(path("a.txt")), filenames(result))
    }

    @Test
    fun `context sets both sides, and before and after each override one side`() {
        file("a.txt", "1\n2\nhit\n4\n5\n")
        val p = path("a.txt")

        val both = grep(ToolInvocation.GrepSearch("hit", outputMode = "content", context = 1))
        val overridden = grep(ToolInvocation.GrepSearch("hit", outputMode = "content", context = 1, before = 0, after = 2))

        assertEquals("$p:2:2\n$p:3:hit\n$p:4:4", both.getValue("content").jsonPrimitive.content)
        assertEquals("$p:3:hit\n$p:4:4\n$p:5:5", overridden.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `overlapping context windows repeat lines, as in Clawcode`() {
        file("a.txt", "hit\nhit\n")
        val p = path("a.txt")

        val result = grep(ToolInvocation.GrepSearch("hit", outputMode = "content", after = 1))

        assertEquals("$p:1:hit\n$p:2:hit\n$p:2:hit", result.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `ignore case matches either case`() {
        file("a.txt", "Todo\n")

        assertEquals(emptyList<String>(), filenames(grep(ToolInvocation.GrepSearch("TODO"))))
        assertEquals(listOf(path("a.txt")), filenames(grep(ToolInvocation.GrepSearch("TODO", ignoreCase = true))))
    }

    @Test
    fun `the glob filter is matched against the whole path`() {
        file("a.kt", "TODO\n")
        file("src/b.kt", "TODO\n")
        file("c.md", "TODO\n")

        val found = filenames(grep(ToolInvocation.GrepSearch("TODO", glob = "*.kt")))

        assertEquals(setOf(path("a.kt"), path("src/b.kt")), found.toSet())
    }

    @Test
    fun `type must equal the file extension`() {
        file("a.kt", "TODO\n")
        file("build.gradle.kts", "TODO\n")

        assertEquals(listOf(path("a.kt")), filenames(grep(ToolInvocation.GrepSearch("TODO", fileType = "kt"))))
    }

    @Test
    fun `without head_limit 250 results are kept and the limit is reported`() {
        (1..251).forEach { file("f%03d.txt".format(it), "hit\n") }

        val result = grep(ToolInvocation.GrepSearch("hit"))

        assertEquals(250, result.getValue("numFiles").jsonPrimitive.int)
        assertEquals(250, result.getValue("appliedLimit").jsonPrimitive.int)
    }

    @Test
    fun `head_limit keeps that many results`() {
        file("a.txt", "hit\n")
        file("b.txt", "hit\n")

        val result = grep(ToolInvocation.GrepSearch("hit", headLimit = 1))

        assertEquals(listOf(path("a.txt")), filenames(result))
        assertEquals(1, result.getValue("appliedLimit").jsonPrimitive.int)
    }

    @Test
    fun `head_limit 0 means no limit`() {
        (1..251).forEach { file("f%03d.txt".format(it), "hit\n") }

        val result = grep(ToolInvocation.GrepSearch("hit", headLimit = 0))

        assertEquals(251, result.getValue("numFiles").jsonPrimitive.int)
        assertEquals(JsonNull, result.getValue("appliedLimit"))
    }

    @Test
    fun `offset skips results before the limit and is reported`() {
        listOf("a.txt", "b.txt", "c.txt").forEach { file(it, "hit\n") }

        val result = grep(ToolInvocation.GrepSearch("hit", offset = 1))

        assertEquals(listOf(path("b.txt"), path("c.txt")), filenames(result))
        assertEquals(1, result.getValue("appliedOffset").jsonPrimitive.int)
        assertEquals(JsonNull, result.getValue("appliedLimit"))
    }

    @Test
    fun `in content mode the limit counts lines`() {
        file("a.txt", "hit 1\nhit 2\nhit 3\n")

        val result = grep(ToolInvocation.GrepSearch("hit", outputMode = "content", headLimit = 2))

        assertEquals(2, result.getValue("numLines").jsonPrimitive.int)
        assertEquals(2, result.getValue("appliedLimit").jsonPrimitive.int)
        assertEquals(1, result.getValue("numFiles").jsonPrimitive.int)
    }

    @Test
    fun `files that are not UTF-8 are skipped`() {
        Files.write(root.resolve("latin1.txt"), byteArrayOf(0x68, 0x69, 0x74, 0xE9.toByte()))
        file("utf8.txt", "hit\n")

        assertEquals(listOf(path("utf8.txt")), filenames(grep(ToolInvocation.GrepSearch("hit"))))
    }

    @Test
    fun `ignored folders are skipped by grep too`() {
        file(".git/config", "hit\n")
        file("node_modules/pkg/index.js", "hit\n")
        file("src/main.kt", "hit\n")

        assertEquals(listOf(path("src/main.kt")), filenames(grep(ToolInvocation.GrepSearch("hit"))))
    }

    @Test
    fun `linked files and folders are not searched`() {
        Files.writeString(outside.resolve("secret.txt"), "hit\n")
        file("real.txt", "hit\n")
        link(root.resolve("shared"), outside)
        link(root.resolve("alias.txt"), root.resolve("real.txt"))

        assertEquals(listOf(path("real.txt")), filenames(grep(ToolInvocation.GrepSearch("hit"))))
    }

    @Test
    fun `path may name a single file`() {
        file("a.txt", "hit\n")
        file("b.txt", "hit\n")

        assertEquals(listOf(path("b.txt")), filenames(grep(ToolInvocation.GrepSearch("hit", under = WorkspacePath("b.txt")))))
    }

    @Test
    fun `multiline lets a dot match a newline`() {
        file("a.txt", "a\nb\n")

        val single = grep(ToolInvocation.GrepSearch("a.b", outputMode = "count"))
        val multiline = grep(ToolInvocation.GrepSearch("a.b", outputMode = "count", multiline = true))

        assertEquals(0, single.getValue("numMatches").jsonPrimitive.int)
        assertEquals(1, multiline.getValue("numMatches").jsonPrimitive.int)
    }

    @Test
    fun `an invalid regex is an error outcome`() {
        val outcome = run(ToolInvocation.GrepSearch("(unclosed"))

        assertTrue(outcome.isError)
        assertTrue(outcome.output.startsWith("Unclosed group"), outcome.output)
    }

    @Test
    fun `a runaway pattern stops at the file deadline and the search still returns`() {
        file("fast.txt", "xxy\n")
        file("slow.txt", "x".repeat(40) + "\n")
        val impatient = LocalWorkspaceGatewayImpl(grepFileDeadline = Duration.ofMillis(100))

        // The backreference keeps the JDK from memoising, so this backtracks for minutes on slow.txt.
        val outcome = impatient.run(WorkspaceRoot.of(root), ToolInvocation.GrepSearch("""(x+)+\1y"""))

        assertEquals(listOf(path("fast.txt")), filenames(success(outcome)))
    }

    // Routing

    @Test
    fun `write_file and edit_file are not available yet`() {
        val path = WorkspacePath("a.txt")

        assertEquals(ToolOutcome("write_file is not available in this build yet", isError = true), run(ToolInvocation.WriteFile(path, "a")))
        assertEquals(ToolOutcome("edit_file is not available in this build yet", isError = true), run(ToolInvocation.EditFile(path, "a", "b")))
    }

    @Test
    fun `bash and MCP calls are refused, naming the tool`() {
        val bash = run(ToolInvocation.Bash("ls"))
        val mcp = run(ToolInvocation.External(ToolName("mcp__docs__search"), JsonText("{}")))

        assertEquals(ToolOutcome("bash is not a workspace file tool", isError = true), bash)
        assertEquals(ToolOutcome("mcp__docs__search is not a workspace file tool", isError = true), mcp)
    }

    @Test
    fun `no tool offers a preview yet`() {
        assertNull(gateway.preview(WorkspaceRoot.of(root), ToolInvocation.WriteFile(WorkspacePath("a.txt"), "a")))
    }

    private data class Read(val filePath: String, val content: String, val numLines: Int, val startLine: Int, val totalLines: Int)

    private fun run(invocation: ToolInvocation): ToolOutcome = gateway.run(WorkspaceRoot.of(root), invocation)

    private fun success(outcome: ToolOutcome): JsonObject {
        assertFalse(outcome.isError, outcome.output)
        return Json.parseToJsonElement(outcome.output).jsonObject
    }

    private fun readFile(relative: String, offset: Int? = null, limit: Int? = null): Read {
        val file = success(run(ToolInvocation.ReadFile(WorkspacePath(relative), offset, limit))).getValue("file").jsonObject
        return Read(
            filePath = file.getValue("filePath").jsonPrimitive.content,
            content = file.getValue("content").jsonPrimitive.content,
            numLines = file.getValue("numLines").jsonPrimitive.int,
            startLine = file.getValue("startLine").jsonPrimitive.int,
            totalLines = file.getValue("totalLines").jsonPrimitive.int,
        )
    }

    private fun globFiles(pattern: String, under: String? = null): List<String> =
        filenames(success(run(ToolInvocation.GlobSearch(pattern, under?.let(::WorkspacePath)))))

    private fun grep(invocation: ToolInvocation.GrepSearch): JsonObject = success(run(invocation))

    private fun filenames(result: JsonObject): List<String> = result.getValue("filenames").jsonArray.map { it.jsonPrimitive.content }

    private fun path(relative: String): String = root.resolve(relative).toString()

    private fun file(relative: String, content: String) {
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    private fun link(link: Path, target: Path) {
        val created =
            try {
                Files.createSymbolicLink(link, target)
                true
            } catch (unsupported: UnsupportedOperationException) {
                false
            } catch (refused: IOException) {
                false
            }
        assumeTrue(created, "this file system cannot create symbolic links")
    }
}
