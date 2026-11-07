package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.secondary.workspace.support.AtomicWrite
import com.suarvis.butler.secondary.workspace.support.GrepSearchTool
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
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

/**
 * Every rule of the five file tools against real files, each in its own test. The workspace and a
 * folder outside it sit side by side in a temp folder, so symlinks can lead out of the workspace.
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

    // write_file

    @Test
    fun `write_file creates a file and answers with Clawcode's envelope as pretty JSON`() {
        val outcome = run(ToolInvocation.WriteFile(WorkspacePath("notes.txt"), "one\ntwo\n"))

        val expected =
            """
            {
              "type": "create",
              "filePath": "${root.resolve("notes.txt")}",
              "content": "one\ntwo\n",
              "structuredPatch": [
                {
                  "oldStart": 0,
                  "oldLines": 0,
                  "newStart": 1,
                  "newLines": 2,
                  "lines": [
                    "+one",
                    "+two"
                  ]
                }
              ],
              "originalFile": null,
              "gitDiff": null
            }
            """.trimIndent()
        assertEquals(ToolOutcome(expected, preview = "--- /dev/null\n+++ notes.txt\n@@ -0,0 +1,2 @@\n+one\n+two\n"), outcome)
        assertEquals("one\ntwo\n", Files.readString(root.resolve("notes.txt")))
    }

    @Test
    fun `write_file over a file is an update with the old text and hunks of changed lines in context`() {
        val before = (1..10).joinToString("") { "$it\n" }
        file("count.txt", before)

        val outcome = run(ToolInvocation.WriteFile(WorkspacePath("count.txt"), before.replace("5\n", "five\n")))

        val result = success(outcome)
        assertEquals(listOf("type", "filePath", "content", "structuredPatch", "originalFile", "gitDiff"), result.keys.toList())
        assertEquals("update", result.getValue("type").jsonPrimitive.content)
        assertEquals(before, result.getValue("originalFile").jsonPrimitive.content)
        assertEquals(listOf(Hunk(2, 7, 2, 7, listOf(" 2", " 3", " 4", "-5", "+five", " 6", " 7", " 8"))), hunks(result))
        assertEquals("--- count.txt\n+++ count.txt\n@@ -2,7 +2,7 @@\n 2\n 3\n 4\n-5\n+five\n 6\n 7\n 8\n", outcome.preview)
        assertEquals(before.replace("5\n", "five\n"), Files.readString(root.resolve("count.txt")))
    }

    @Test
    fun `write_file creates missing parent folders`() {
        val result = success(run(ToolInvocation.WriteFile(WorkspacePath("src/main/App.kt"), "fun main() {}\n")))

        assertEquals(path("src/main/App.kt"), result.getValue("filePath").jsonPrimitive.content)
        assertEquals("fun main() {}\n", Files.readString(root.resolve("src/main/App.kt")))
    }

    @Test
    fun `write_file refuses a path under a linked folder that leads outside and creates nothing there`() {
        link(root.resolve("shared"), outside)

        val outcome = run(ToolInvocation.WriteFile(WorkspacePath("shared/deep/new.txt"), "x"))

        assertEquals(ToolOutcome("path ${outside.resolve("deep/new.txt")} escapes workspace boundary $root", isError = true), outcome)
        assertFalse(Files.exists(outside.resolve("deep")))
    }

    @Test
    fun `write_file refuses a link to a file outside and leaves that file alone`() {
        Files.writeString(outside.resolve("secret.txt"), "secret")
        link(root.resolve("secret.txt"), outside.resolve("secret.txt"))

        val outcome = run(ToolInvocation.WriteFile(WorkspacePath("secret.txt"), "overwritten"))

        assertEquals(ToolOutcome("path ${outside.resolve("secret.txt")} escapes workspace boundary $root", isError = true), outcome)
        assertEquals("secret", Files.readString(outside.resolve("secret.txt")))
    }

    @Test
    fun `write_file refuses a dangling link whose target would be created outside`() {
        link(root.resolve("planted.txt"), outside.resolve("created.txt"))

        val outcome = run(ToolInvocation.WriteFile(WorkspacePath("planted.txt"), "x"))

        assertEquals(ToolOutcome("path ${outside.resolve("created.txt")} escapes workspace boundary $root", isError = true), outcome)
        assertFalse(Files.exists(outside.resolve("created.txt")))
    }

    @Test
    fun `write_file through a link inside the workspace writes its target and keeps the link`() {
        file("real.txt", "old\n")
        link(root.resolve("alias.txt"), root.resolve("real.txt"))

        val result = success(run(ToolInvocation.WriteFile(WorkspacePath("alias.txt"), "new\n")))

        assertEquals(path("real.txt"), result.getValue("filePath").jsonPrimitive.content)
        assertEquals("update", result.getValue("type").jsonPrimitive.content)
        assertEquals("new\n", Files.readString(root.resolve("real.txt")))
        assertTrue(Files.isSymbolicLink(root.resolve("alias.txt")))
    }

    @Test
    fun `write_file through a dangling link inside the workspace creates its target`() {
        link(root.resolve("alias.txt"), root.resolve("docs/real.txt"))

        val result = success(run(ToolInvocation.WriteFile(WorkspacePath("alias.txt"), "new\n")))

        assertEquals(path("docs/real.txt"), result.getValue("filePath").jsonPrimitive.content)
        assertEquals("new\n", Files.readString(root.resolve("docs/real.txt")))
        assertTrue(Files.isSymbolicLink(root.resolve("alias.txt")))
    }

    @Test
    fun `write_file keeps an executable file executable`() {
        assumePosix()
        file("run.sh", "echo old\n")
        val executable = PosixFilePermissions.fromString("rwxr-x---")
        Files.setPosixFilePermissions(root.resolve("run.sh"), executable)

        success(run(ToolInvocation.WriteFile(WorkspacePath("run.sh"), "echo new\n")))

        assertEquals(executable, Files.getPosixFilePermissions(root.resolve("run.sh")))
        assertEquals("echo new\n", Files.readString(root.resolve("run.sh")))
    }

    @Test
    fun `write_file gives a new file the permissions of any new file, not the owner-only ones of a temp file`() {
        assumePosix()
        val usual = Files.getPosixFilePermissions(Files.createFile(temp.resolve("usual.txt")))

        success(run(ToolInvocation.WriteFile(WorkspacePath("new.txt"), "x")))

        assertEquals(usual, Files.getPosixFilePermissions(root.resolve("new.txt")))
    }

    @Test
    fun `a write interrupted while moving into place leaves the old file whole and no temp file behind`() {
        file("notes.txt", "old\n")

        val outcome = interrupted().run(WorkspaceRoot.of(root), ToolInvocation.WriteFile(WorkspacePath("notes.txt"), "new\n"))

        assertEquals(ToolOutcome("interrupted", isError = true), outcome)
        assertEquals("old\n", Files.readString(root.resolve("notes.txt")))
        assertEquals(listOf("notes.txt"), entries(root))
    }

    @Test
    fun `write_file refuses content over 10 MB, creates nothing and has nothing to preview`() {
        val write = ToolInvocation.WriteFile(WorkspacePath("big.txt"), "x".repeat(10 * 1024 * 1024 + 1))

        assertNull(gateway.preview(WorkspaceRoot.of(root), write))
        assertEquals(ToolOutcome("content is too large (10485761 bytes, max 10485760 bytes)", isError = true), run(write))
        assertFalse(Files.exists(root.resolve("big.txt")))
    }

    @Test
    fun `write_file refuses a file that may not be written, as writing it in place would fail`() {
        assumePosix()
        file("locked.txt", "old\n")
        Files.setPosixFilePermissions(root.resolve("locked.txt"), PosixFilePermissions.fromString("r--r--r--"))
        assumeTrue(!Files.isWritable(root.resolve("locked.txt")), "these tests run as a user who may write any file")

        val outcome = run(ToolInvocation.WriteFile(WorkspacePath("locked.txt"), "new\n"))

        assertEquals(ToolOutcome("Permission denied (os error 13)", isError = true), outcome)
        assertEquals("old\n", Files.readString(root.resolve("locked.txt")))
    }

    @Test
    fun `write_file onto a folder is an error`() {
        Files.createDirectories(root.resolve("src"))

        assertEquals(ToolOutcome("Is a directory (os error 21)", isError = true), run(ToolInvocation.WriteFile(WorkspacePath("src"), "x")))
    }

    @Test
    fun `previewing a write shows the diff the write then reports, and creates nothing`() {
        val write = ToolInvocation.WriteFile(WorkspacePath("docs/new.md"), "# Title\n")

        val preview = gateway.preview(WorkspaceRoot.of(root), write)

        assertEquals("--- /dev/null\n+++ docs/new.md\n@@ -0,0 +1 @@\n+# Title\n", preview)
        assertFalse(Files.exists(root.resolve("docs")))
        assertEquals(preview, run(write).preview)
    }

    @Test
    fun `writing the same content again changes nothing and has nothing to preview`() {
        file("same.txt", "a\n")
        val write = ToolInvocation.WriteFile(WorkspacePath("same.txt"), "a\n")

        assertNull(gateway.preview(WorkspaceRoot.of(root), write))
        val outcome = run(write)
        assertEquals(emptyList<Hunk>(), hunks(success(outcome)))
        assertNull(outcome.preview)
    }

    // edit_file

    @Test
    fun `edit_file replaces the one match and answers with Clawcode's envelope as pretty JSON`() {
        file("greek.txt", "alpha\nbeta\ngamma\n")

        val outcome = run(ToolInvocation.EditFile(WorkspacePath("greek.txt"), "beta", "BETA"))

        val expected =
            """
            {
              "filePath": "${root.resolve("greek.txt")}",
              "oldString": "beta",
              "newString": "BETA",
              "originalFile": "alpha\nbeta\ngamma\n",
              "structuredPatch": [
                {
                  "oldStart": 1,
                  "oldLines": 3,
                  "newStart": 1,
                  "newLines": 3,
                  "lines": [
                    " alpha",
                    "-beta",
                    "+BETA",
                    " gamma"
                  ]
                }
              ],
              "userModified": false,
              "replaceAll": false,
              "gitDiff": null
            }
            """.trimIndent()
        assertEquals(ToolOutcome(expected, preview = "--- greek.txt\n+++ greek.txt\n@@ -1,3 +1,3 @@\n alpha\n-beta\n+BETA\n gamma\n"), outcome)
        assertEquals("alpha\nBETA\ngamma\n", Files.readString(root.resolve("greek.txt")))
    }

    @Test
    fun `replace_all replaces every match`() {
        file("names.txt", "foo\nbar foo\nfoo baz\n")

        val result = success(run(ToolInvocation.EditFile(WorkspacePath("names.txt"), "foo", "qux", replaceAll = true)))

        assertTrue(result.getValue("replaceAll").jsonPrimitive.boolean)
        assertEquals("qux\nbar qux\nqux baz\n", Files.readString(root.resolve("names.txt")))
    }

    @Test
    fun `an old_string that is not in the file is refused and the file is left byte for byte`() {
        val bytes = "alpha\nbeta\n".toByteArray()
        Files.write(root.resolve("greek.txt"), bytes)

        val outcome = run(ToolInvocation.EditFile(WorkspacePath("greek.txt"), "delta", "DELTA"))

        assertEquals(ToolOutcome("old_string not found in file", isError = true), outcome)
        assertEquals(bytes.toList(), Files.readAllBytes(root.resolve("greek.txt")).toList())
    }

    @Test
    fun `an old_string found twice without replace_all is refused and the file is left byte for byte`() {
        val bytes = "beta\nalpha\nbeta\n".toByteArray()
        Files.write(root.resolve("greek.txt"), bytes)

        val outcome = run(ToolInvocation.EditFile(WorkspacePath("greek.txt"), "beta", "BETA"))

        assertEquals(ToolOutcome("old_string matches 2 places; include more context or set replace_all", isError = true), outcome)
        assertEquals(bytes.toList(), Files.readAllBytes(root.resolve("greek.txt")).toList())
    }

    @Test
    fun `identical old_string and new_string are refused`() {
        file("greek.txt", "alpha\n")

        val outcome = run(ToolInvocation.EditFile(WorkspacePath("greek.txt"), "alpha", "alpha"))

        assertEquals(ToolOutcome("old_string and new_string must differ", isError = true), outcome)
    }

    @Test
    fun `CRLF line endings and a missing final newline are kept`() {
        file("windows.txt", "one\r\ntwo\r\nthree")

        val outcome = run(ToolInvocation.EditFile(WorkspacePath("windows.txt"), "two", "2"))

        assertEquals("one\r\n2\r\nthree", Files.readString(root.resolve("windows.txt")))
        assertEquals(listOf(Hunk(1, 3, 1, 3, listOf(" one", "-two", "+2", " three", "\\ No newline at end of file"))), hunks(success(outcome)))
    }

    @Test
    fun `the preview of an edit is the diff the edit then makes, and changes nothing`() {
        file("greek.txt", "alpha\nbeta\ngamma\ndelta\n")
        val edit = ToolInvocation.EditFile(WorkspacePath("greek.txt"), "gamma\n", "")

        val preview = gateway.preview(WorkspaceRoot.of(root), edit)

        assertEquals("--- greek.txt\n+++ greek.txt\n@@ -1,4 +1,3 @@\n alpha\n beta\n-gamma\n delta\n", preview)
        assertEquals("alpha\nbeta\ngamma\ndelta\n", Files.readString(root.resolve("greek.txt")))
        assertEquals(preview, run(edit).preview)
        assertEquals("alpha\nbeta\ndelta\n", Files.readString(root.resolve("greek.txt")))
    }

    @Test
    fun `an edit that would be refused has nothing to preview`() {
        file("greek.txt", "beta\nbeta\n")
        val workspace = WorkspaceRoot.of(root)

        assertNull(gateway.preview(workspace, ToolInvocation.EditFile(WorkspacePath("greek.txt"), "delta", "x")))
        assertNull(gateway.preview(workspace, ToolInvocation.EditFile(WorkspacePath("greek.txt"), "beta", "x")))
        assertNull(gateway.preview(workspace, ToolInvocation.EditFile(WorkspacePath("missing.txt"), "beta", "x")))
    }

    @Test
    fun `edit_file on a missing file is an error`() {
        val outcome = run(ToolInvocation.EditFile(WorkspacePath("missing.txt"), "a", "b"))

        assertEquals(ToolOutcome("No such file or directory (os error 2)", isError = true), outcome)
    }

    @Test
    fun `edit_file refuses text that is not UTF-8`() {
        Files.write(root.resolve("latin1.txt"), byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()))

        val outcome = run(ToolInvocation.EditFile(WorkspacePath("latin1.txt"), "c", "k"))

        assertEquals(ToolOutcome("stream did not contain valid UTF-8", isError = true), outcome)
    }

    @Test
    fun `edit_file refuses a link leading outside and leaves its target alone`() {
        Files.writeString(outside.resolve("secret.txt"), "secret")
        link(root.resolve("secret.txt"), outside.resolve("secret.txt"))

        val outcome = run(ToolInvocation.EditFile(WorkspacePath("secret.txt"), "secret", "leaked"))

        assertEquals(ToolOutcome("path ${outside.resolve("secret.txt")} escapes workspace boundary $root", isError = true), outcome)
        assertEquals("secret", Files.readString(outside.resolve("secret.txt")))
    }

    @Test
    fun `edit_file through a link inside the workspace edits its target and keeps the link`() {
        file("real.txt", "old\n")
        link(root.resolve("alias.txt"), root.resolve("real.txt"))

        val result = success(run(ToolInvocation.EditFile(WorkspacePath("alias.txt"), "old", "new")))

        assertEquals(path("real.txt"), result.getValue("filePath").jsonPrimitive.content)
        assertEquals("new\n", Files.readString(root.resolve("real.txt")))
        assertTrue(Files.isSymbolicLink(root.resolve("alias.txt")))
    }

    @Test
    fun `an edit keeps an executable file executable`() {
        assumePosix()
        file("run.sh", "echo old\n")
        val executable = PosixFilePermissions.fromString("rwxr-xr-x")
        Files.setPosixFilePermissions(root.resolve("run.sh"), executable)

        success(run(ToolInvocation.EditFile(WorkspacePath("run.sh"), "old", "new")))

        assertEquals(executable, Files.getPosixFilePermissions(root.resolve("run.sh")))
    }

    @Test
    fun `an edit interrupted while moving into place leaves the old file whole and no temp file behind`() {
        file("notes.txt", "old\n")

        val outcome = interrupted().run(WorkspaceRoot.of(root), ToolInvocation.EditFile(WorkspacePath("notes.txt"), "old", "new"))

        assertEquals(ToolOutcome("interrupted", isError = true), outcome)
        assertEquals("old\n", Files.readString(root.resolve("notes.txt")))
        assertEquals(listOf("notes.txt"), entries(root))
    }

    // Routing

    @Test
    fun `write_file and edit_file reach their tools`() {
        val path = WorkspacePath("a.txt")

        assertFalse(run(ToolInvocation.WriteFile(path, "a")).isError)
        assertFalse(run(ToolInvocation.EditFile(path, "a", "b")).isError)
        assertEquals("b", Files.readString(root.resolve("a.txt")))
    }

    @Test
    fun `bash and MCP calls are refused, naming the tool`() {
        val bash = run(ToolInvocation.Bash("ls"))
        val mcp = run(ToolInvocation.External(ToolName("mcp__docs__search"), JsonText("{}")))

        assertEquals(ToolOutcome("bash is not a workspace file tool", isError = true), bash)
        assertEquals(ToolOutcome("mcp__docs__search is not a workspace file tool", isError = true), mcp)
    }

    @Test
    fun `reading, searching and other gateways' tools have nothing to preview`() {
        file("a.txt", "a")
        val workspace = WorkspaceRoot.of(root)

        assertNull(gateway.preview(workspace, ToolInvocation.ReadFile(WorkspacePath("a.txt"))))
        assertNull(gateway.preview(workspace, ToolInvocation.GlobSearch("*")))
        assertNull(gateway.preview(workspace, ToolInvocation.GrepSearch("a")))
        assertNull(gateway.preview(workspace, ToolInvocation.Bash("ls")))
    }

    private data class Read(val filePath: String, val content: String, val numLines: Int, val startLine: Int, val totalLines: Int)

    private data class Hunk(val oldStart: Int, val oldLines: Int, val newStart: Int, val newLines: Int, val lines: List<String>)

    private fun hunks(result: JsonObject): List<Hunk> =
        result.getValue("structuredPatch").jsonArray.map { element ->
            val hunk = element.jsonObject
            Hunk(
                oldStart = hunk.getValue("oldStart").jsonPrimitive.int,
                oldLines = hunk.getValue("oldLines").jsonPrimitive.int,
                newStart = hunk.getValue("newStart").jsonPrimitive.int,
                newLines = hunk.getValue("newLines").jsonPrimitive.int,
                lines = hunk.getValue("lines").jsonArray.map { it.jsonPrimitive.content },
            )
        }

    /** A gateway whose writes fail at the final move, as a crash between writing the temp file and moving it would. */
    private fun interrupted(): LocalWorkspaceGatewayImpl =
        LocalWorkspaceGatewayImpl(GrepSearchTool.FILE_DEADLINE, AtomicWrite { _, _ -> throw IOException("interrupted") })

    private fun entries(folder: Path): List<String> =
        Files.newDirectoryStream(folder).use { listing -> listing.map { it.fileName.toString() }.sorted() }

    private fun assumePosix() =
        assumeTrue(root.fileSystem.supportedFileAttributeViews().contains("posix"), "this file system has no POSIX permissions")

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
