package com.suarvis.butler.secondary.workspace.support

import com.suarvis.butler.secondary.workspace.support.UnifiedDiff.Hunk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Hunks and context lines for insertions, deletions and changes, at the edges of a file and inside it. */
class UnifiedDiffTest {
    private val ten = (1..10).joinToString("") { "$it\n" }

    @Test
    fun `the same text has no hunks and no text`() {
        val diff = UnifiedDiff.between(ten, ten)

        assertEquals(emptyList<Hunk>(), diff.hunks)
        assertNull(diff.text("a", "a"))
        assertEquals("[]", diff.structuredPatch().toString())
    }

    @Test
    fun `a change inside the file shows three lines of context on each side`() {
        val diff = UnifiedDiff.between(ten, ten.replace("5\n", "five\n"))

        assertEquals(listOf(Hunk(2, 7, 2, 7, listOf(" 2", " 3", " 4", "-5", "+five", " 6", " 7", " 8"))), diff.hunks)
    }

    @Test
    fun `changes at the first and last lines get only the context there is`() {
        val diff = UnifiedDiff.between(ten, ten.replaceFirst("1\n", "one\n").replace("10\n", "ten\n"))

        assertEquals(
            listOf(
                Hunk(1, 4, 1, 4, listOf("-1", "+one", " 2", " 3", " 4")),
                Hunk(7, 4, 7, 4, listOf(" 7", " 8", " 9", "-10", "+ten")),
            ),
            diff.hunks,
        )
    }

    @Test
    fun `lines inserted at the start and appended at the end`() {
        assertEquals(listOf(Hunk(1, 3, 1, 4, listOf("+0", " 1", " 2", " 3"))), UnifiedDiff.between(ten, "0\n$ten").hunks)
        assertEquals(listOf(Hunk(8, 3, 8, 4, listOf(" 8", " 9", " 10", "+11"))), UnifiedDiff.between(ten, "${ten}11\n").hunks)
    }

    @Test
    fun `lines deleted at the start and at the end`() {
        assertEquals(listOf(Hunk(1, 4, 1, 3, listOf("-1", " 2", " 3", " 4"))), UnifiedDiff.between(ten, ten.removePrefix("1\n")).hunks)
        assertEquals(listOf(Hunk(7, 4, 7, 3, listOf(" 7", " 8", " 9", "-10"))), UnifiedDiff.between(ten, ten.removeSuffix("10\n")).hunks)
    }

    @Test
    fun `changes six unchanged lines apart share a hunk and seven apart do not`() {
        val lines = (1..20).map { "$it\n" }
        fun changed(vararg numbers: Int) = lines.mapIndexed { index, line -> if (index + 1 in numbers) "x$line" else line }.joinToString("")

        val close = UnifiedDiff.between(lines.joinToString(""), changed(4, 11))
        val apart = UnifiedDiff.between(lines.joinToString(""), changed(4, 12))

        assertEquals(listOf(Triple(1, 14, 14)), close.hunks.map { Triple(it.oldStart, it.oldLines, it.newLines) })
        assertEquals(listOf(Triple(1, 7, 7), Triple(9, 7, 7)), apart.hunks.map { Triple(it.oldStart, it.oldLines, it.newLines) })
    }

    @Test
    fun `a new file starts the old side at line 0, and an emptied file the new side`() {
        assertEquals(listOf(Hunk(0, 0, 1, 2, listOf("+a", "+b"))), UnifiedDiff.between("", "a\nb\n").hunks)
        assertEquals(listOf(Hunk(1, 2, 0, 0, listOf("-a", "-b"))), UnifiedDiff.between("a\nb\n", "").hunks)
    }

    @Test
    fun `a last line without a newline is marked on the side that lacks it`() {
        val added = UnifiedDiff.between("a\nb", "a\nb\n")
        val unchanged = UnifiedDiff.between("a\nb", "A\nb")

        assertEquals(listOf(Hunk(1, 2, 1, 2, listOf(" a", "-b", UnifiedDiff.NO_NEWLINE_MARKER, "+b"))), added.hunks)
        assertEquals(listOf(Hunk(1, 2, 1, 2, listOf("-a", "+A", " b", UnifiedDiff.NO_NEWLINE_MARKER))), unchanged.hunks)
    }

    @Test
    fun `CRLF endings are compared but not shown`() {
        val diff = UnifiedDiff.between("a\r\nb\r\n", "a\r\nc\r\n")

        assertEquals(listOf(Hunk(1, 2, 1, 2, listOf(" a", "-b", "+c"))), diff.hunks)
        assertEquals(listOf(Hunk(1, 1, 1, 1, listOf("-a", "+a"))), UnifiedDiff.between("a\r\n", "a\n").hunks)
    }

    @Test
    fun `text has header lines, leaves out a count of one, and ends with a newline`() {
        val text = UnifiedDiff.between("a\nb\n", "a\nc\n").text("/dev/null", "notes.txt")

        assertEquals("--- /dev/null\n+++ notes.txt\n@@ -1,2 +1,2 @@\n a\n-b\n+c\n", text)
        assertEquals("--- x\n+++ x\n@@ -1 +1 @@\n-a\n+b\n", UnifiedDiff.between("a\n", "b\n").text("x", "x"))
    }

    @Test
    fun `structuredPatch uses Clawcode's hunk keys in its order`() {
        val patch = UnifiedDiff.between("a\n", "b\n").structuredPatch()

        assertEquals(Json.parseToJsonElement("""[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,"lines":["-a","+b"]}]"""), patch)
        assertEquals(listOf("oldStart", "oldLines", "newStart", "newLines", "lines"), patch[0].jsonObject.keys.toList())
    }

    @Test
    fun `past the edit limit the lines between the common start and end are replaced whole`() {
        val diff = UnifiedDiff.between("a\nb\nc\nd\ne\n", "a\nB\nc\nD\ne\n", maxEditDistance = 3)

        assertEquals(listOf(Hunk(1, 5, 1, 5, listOf(" a", "-b", "-c", "-d", "+B", "+c", "+D", " e"))), diff.hunks)
    }

    @Test
    fun `applying the hunks to the old lines gives the new lines, with the fewest changes`() {
        val random = Random(7)
        val pool = listOf("a\n", "b\n", "c\n", "a\r\n", "")
        repeat(2000) {
            val old = text(random, pool)
            val new = text(random, pool)
            val diff = UnifiedDiff.between(old, new)

            assertEquals(shownLines(new), applied(shownLines(old), diff.hunks), "$old -> $new")
            val changes = diff.hunks.sumOf { hunk -> hunk.lines.count { it.startsWith("-") || it.startsWith("+") } }
            assertEquals(fewestChanges(rawLines(old), rawLines(new)), changes, "$old -> $new")
        }
    }

    // Up to eight lines from [pool]; the empty entry lets the last line end without a newline.
    private fun text(random: Random, pool: List<String>): String {
        val count = random.nextInt(9)
        return (0 until count).joinToString("") { index ->
            val line = pool[random.nextInt(pool.size)]
            if (line.isEmpty()) (if (index == count - 1) "z" else "z\n") else line
        }
    }

    private fun applied(old: List<String>, hunks: List<Hunk>): List<String> {
        val result = ArrayList<String>()
        var at = 0
        for (hunk in hunks) {
            val from = if (hunk.oldLines == 0) hunk.oldStart else hunk.oldStart - 1
            while (at < from) result += old[at++]
            for (line in hunk.lines) {
                when {
                    line == UnifiedDiff.NO_NEWLINE_MARKER -> continue
                    line.startsWith(" ") -> {
                        assertEquals(old[at++], line.substring(1))
                        result += line.substring(1)
                    }
                    line.startsWith("-") -> assertEquals(old[at++], line.substring(1))
                    else -> result += line.substring(1)
                }
            }
        }
        while (at < old.size) result += old[at++]
        return result
    }

    // Lines added plus lines removed, from the longest common subsequence of whole lines.
    private fun fewestChanges(old: List<String>, new: List<String>): Int {
        val common = Array(old.size + 1) { IntArray(new.size + 1) }
        for (i in old.indices.reversed()) {
            for (j in new.indices.reversed()) {
                common[i][j] = if (old[i] == new[j]) common[i + 1][j + 1] + 1 else maxOf(common[i + 1][j], common[i][j + 1])
            }
        }
        return old.size + new.size - 2 * common[0][0]
    }

    private fun rawLines(text: String): List<String> = Regex("(?<=\n)").split(text).filter { it.isNotEmpty() }

    private fun shownLines(text: String): List<String> = rawLines(text).map { it.removeSuffix("\n").removeSuffix("\r") }
}
