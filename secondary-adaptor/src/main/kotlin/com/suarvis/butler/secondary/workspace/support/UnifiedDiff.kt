package com.suarvis.butler.secondary.workspace.support

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The line diff between two texts as unified diff hunks with [CONTEXT_LINES] lines of context: as
 * text for the approval prompt, and as Clawcode's `StructuredPatchHunk` list (`oldStart`,
 * `oldLines`, `newStart`, `newLines`, `lines`) for the `structuredPatch` of `write_file` and
 * `edit_file`.
 *
 * Departure from Clawcode, whose `make_patch` returns one hunk that lists every old line, then
 * every new line: these are real hunks, so context lines (prefixed with a space) and several hunks
 * appear, and an empty side starts at line 0, as in unified diffs, where Clawcode always says 1.
 *
 * Lines are compared with their line endings, so a change from CRLF to LF or a final newline added
 * or removed is a change; they are shown without them, as Rust's `str::lines` splits them, and a
 * last line without a newline is followed by `\ No newline at end of file`.
 */
internal class UnifiedDiff private constructor(
    val hunks: List<Hunk>,
) {
    /** One hunk; [lines] start with ` `, `-` or `+`, or are the no-newline marker. Starts are 1-based. */
    data class Hunk(val oldStart: Int, val oldLines: Int, val newStart: Int, val newLines: Int, val lines: List<String>)

    /**
     * The diff as unified diff text, from `---` and `+++` header lines with the two labels to the
     * last hunk's last line, each line ending in `\n`; null when the texts are the same.
     */
    fun text(oldLabel: String, newLabel: String): String? {
        if (hunks.isEmpty()) return null
        return buildString {
            append("--- ").append(oldLabel).append('\n')
            append("+++ ").append(newLabel).append('\n')
            for (hunk in hunks) {
                append("@@ -").append(range(hunk.oldStart, hunk.oldLines))
                append(" +").append(range(hunk.newStart, hunk.newLines)).append(" @@\n")
                hunk.lines.forEach { append(it).append('\n') }
            }
        }
    }

    /** The hunks with Clawcode's `StructuredPatchHunk` keys, in its order. */
    fun structuredPatch(): JsonArray =
        JsonArray(
            hunks.map { hunk ->
                buildJsonObject {
                    put("oldStart", hunk.oldStart)
                    put("oldLines", hunk.oldLines)
                    put("newStart", hunk.newStart)
                    put("newLines", hunk.newLines)
                    put("lines", ToolJson.strings(hunk.lines))
                }
            },
        )

    // GNU diff and git leave out a count of 1.
    private fun range(start: Int, count: Int): String = if (count == 1) "$start" else "$start,$count"

    companion object {
        /** Unchanged lines shown around each change, as `diff -u` and git show them. */
        const val CONTEXT_LINES = 3

        /**
         * Most inserted plus deleted lines the diff searches for, after the lines both texts start
         * and end with are set aside. Beyond it the lines in between are shown as removed and added
         * whole, as Clawcode shows every change, so a rewrite of a large file costs bounded time
         * and memory (about 4 MB for the search's trace).
         */
        const val MAX_EDIT_DISTANCE = 1000

        const val NO_NEWLINE_MARKER = "\\ No newline at end of file"

        fun between(original: String, updated: String, maxEditDistance: Int = MAX_EDIT_DISTANCE): UnifiedDiff {
            val old = split(original)
            val new = split(updated)
            return UnifiedDiff(hunks(old, new, changes(old, new, maxEditDistance)))
        }

        /** A run of changed lines: old `[oldFrom, oldTo)` is replaced by new `[newFrom, newTo)`, 0-based. */
        private class Change(val oldFrom: Int, val oldTo: Int, val newFrom: Int, val newTo: Int)

        /** Lines with their `\n` kept; the last one may lack it. An empty text has no lines. */
        private fun split(text: String): List<String> {
            val lines = ArrayList<String>()
            var start = 0
            while (start < text.length) {
                val newline = text.indexOf('\n', start)
                val end = if (newline < 0) text.length else newline + 1
                lines += text.substring(start, end)
                start = end
            }
            return lines
        }

        private fun changes(old: List<String>, new: List<String>, maxEditDistance: Int): List<Change> {
            var prefix = 0
            while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
            var suffix = 0
            while (suffix < old.size - prefix && suffix < new.size - prefix && old[old.size - 1 - suffix] == new[new.size - 1 - suffix]) suffix++
            val n = old.size - prefix - suffix
            val m = new.size - prefix - suffix
            if (n == 0 && m == 0) return emptyList()

            // Lines become numbers, so the search compares ints instead of strings.
            val ids = HashMap<String, Int>()
            val a = IntArray(n) { ids.getOrPut(old[prefix + it]) { ids.size } }
            val b = IntArray(m) { ids.getOrPut(new[prefix + it]) { ids.size } }
            val deleted = BooleanArray(n)
            val inserted = BooleanArray(m)
            if (!shortestEdit(a, b, deleted, inserted, maxEditDistance)) {
                deleted.fill(true)
                inserted.fill(true)
            }

            // Lines flagged on neither side pair up in order; each stretch of flagged lines is one change.
            val changes = ArrayList<Change>()
            var i = 0
            var j = 0
            while (i < n || j < m) {
                if (i < n && j < m && !deleted[i] && !inserted[j]) {
                    i++
                    j++
                    continue
                }
                val fromI = i
                val fromJ = j
                while (i < n && deleted[i]) i++
                while (j < m && inserted[j]) j++
                check(i > fromI || j > fromJ) { "unbalanced edit script" }
                changes += Change(prefix + fromI, prefix + i, prefix + fromJ, prefix + j)
            }
            return changes
        }

        /**
         * Myers' O(ND) search for a shortest edit script from [a] to [b], kept inside the edit
         * graph, so no path runs past either text's end. Flags the lines it deletes and inserts and
         * returns true, or returns false when the script needs more than [maxEditDistance] edits.
         */
        private fun shortestEdit(a: IntArray, b: IntArray, deleted: BooleanArray, inserted: BooleanArray, maxEditDistance: Int): Boolean {
            val n = a.size
            val m = b.size
            val limit = minOf(n + m, maxEditDistance)
            val offset = limit + 1
            // Furthest x reached on each diagonal k = x - y; -1 where no path has reached it.
            val reach = IntArray(2 * limit + 3) { -1 }
            val trace = ArrayList<IntArray>()
            var found = -1
            search@ for (d in 0..limit) {
                for (k in -d..d step 2) {
                    if (k < -m || k > n) continue
                    var x =
                        if (d == 0) {
                            0
                        } else {
                            when (step(reach[offset + k + 1], reach[offset + k - 1], k, n, m)) {
                                DOWN -> reach[offset + k + 1]
                                RIGHT -> reach[offset + k - 1] + 1
                                else -> {
                                    // Not a value from two rounds ago: the trace must say no d-path ends here.
                                    reach[offset + k] = -1
                                    continue
                                }
                            }
                        }
                    var y = x - k
                    while (x < n && y < m && a[x] == b[y]) {
                        x++
                        y++
                    }
                    reach[offset + k] = x
                    if (x == n && y == m) {
                        found = d
                        break@search
                    }
                }
                trace += reach.copyOfRange(offset - d, offset + d + 1)
            }
            if (found < 0) return false

            var x = n
            var y = m
            for (d in found downTo 1) {
                val previous = trace[d - 1]
                val k = x - y
                val down = previous.at(k + 1, d - 1)
                val right = previous.at(k - 1, d - 1)
                if (step(down, right, k, n, m) == DOWN) {
                    inserted[down - (k + 1)] = true
                    x = down
                    y = down - (k + 1)
                } else {
                    deleted[right] = true
                    x = right
                    y = right - (k - 1)
                }
            }
            return true
        }

        /**
         * Which move reaches diagonal [k] furthest: down from k + 1 (insert the next line of b)
         * or right from k - 1 (delete the next line of a), counting only moves that stay inside
         * the graph. When both reach as far, down, as in Myers' paper.
         */
        private fun step(fromAbove: Int, fromLeft: Int, k: Int, n: Int, m: Int): Int {
            val canGoDown = fromAbove >= 0 && fromAbove - (k + 1) < m
            val canGoRight = fromLeft in 0 until n
            return when {
                canGoDown && (!canGoRight || fromAbove >= fromLeft + 1) -> DOWN
                canGoRight -> RIGHT
                else -> NONE
            }
        }

        // One round's trace holds diagonals -d..d.
        private fun IntArray.at(k: Int, d: Int): Int = if (k < -d || k > d) -1 else this[k + d]

        private fun hunks(old: List<String>, new: List<String>, changes: List<Change>): List<Hunk> {
            val hunks = ArrayList<Hunk>()
            var first = 0
            while (first < changes.size) {
                // Changes whose context would touch or overlap share a hunk, as in `diff -u`.
                var last = first
                while (last + 1 < changes.size && changes[last + 1].oldFrom - changes[last].oldTo <= 2 * CONTEXT_LINES) last++
                val opening = changes[first]
                val closing = changes[last]
                val oldFrom = maxOf(0, opening.oldFrom - CONTEXT_LINES)
                val newFrom = opening.newFrom - (opening.oldFrom - oldFrom)
                val oldTo = minOf(old.size, closing.oldTo + CONTEXT_LINES)
                val newTo = closing.newTo + (oldTo - closing.oldTo)

                val lines = ArrayList<String>()
                var at = oldFrom
                for (change in changes.subList(first, last + 1)) {
                    while (at < change.oldFrom) lines.addLine(' ', old, at++)
                    for (index in change.oldFrom until change.oldTo) lines.addLine('-', old, index)
                    for (index in change.newFrom until change.newTo) lines.addLine('+', new, index)
                    at = change.oldTo
                }
                while (at < oldTo) lines.addLine(' ', old, at++)

                hunks += Hunk(start(oldFrom, oldTo - oldFrom), oldTo - oldFrom, start(newFrom, newTo - newFrom), newTo - newFrom, lines)
                first = last + 1
            }
            return hunks
        }

        // 1-based; an empty range names the line before it, as in unified diffs.
        private fun start(from: Int, count: Int): Int = if (count == 0) from else from + 1

        // Only a file's last line can lack its `\n`; an unchanged one lacks it on both sides.
        private fun MutableList<String>.addLine(prefix: Char, lines: List<String>, index: Int) {
            val line = lines[index]
            add(prefix + shown(line))
            if (!line.endsWith('\n')) add(NO_NEWLINE_MARKER)
        }

        // Rust's `str::lines`: the `\n` goes, and a `\r` right before it.
        private fun shown(line: String): String =
            when {
                line.endsWith("\r\n") -> line.substring(0, line.length - 2)
                line.endsWith('\n') -> line.substring(0, line.length - 1)
                else -> line
            }

        private const val DOWN = 1
        private const val RIGHT = 2
        private const val NONE = 0
    }
}
