package com.suarvis.butler.secondary.workspace.support

import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.tool.ToolInvocation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.time.Duration
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * `grep_search`, ported from Clawcode's `grep_search_impl`: every file under `path` (or `path`
 * itself when it is a file) that passes the `glob` and `type` filters and is valid UTF-8 is
 * searched. `content` and `files_with_matches` (the default) match line by line; `count` counts
 * matches over the whole file. Context lines repeat when windows overlap and use the same
 * `path:line:` prefix as matches, as in Clawcode. `offset` then `head_limit` apply to the file
 * list and, in `content` mode, to the lines. The result is Clawcode's `GrepSearchOutput`.
 *
 * The pattern runs on `java.util.regex`, so it can backtrack where Rust's `regex` cannot; each
 * file's matching stops at [fileDeadline], and a file that reaches it is skipped.
 */
internal class GrepSearchTool(private val guard: PathGuard, private val fileDeadline: Duration) {
    fun run(invocation: ToolInvocation.GrepSearch): String {
        val base = guard.existing(invocation.under ?: WorkspacePath.ROOT_PATH)
        val regex = compile(invocation)
        val globFilter = invocation.glob?.let(GlobPattern::compile)
        val mode = invocation.outputMode ?: FILES_WITH_MATCHES
        val context = invocation.context ?: 0
        val before = invocation.before ?: context
        val after = invocation.after ?: context
        val lineNumbers = invocation.lineNumbers ?: true

        val filenames = ArrayList<String>()
        val contentLines = ArrayList<String>()
        var totalMatches = 0L
        for (file in SearchWalker(guard).files(base)) {
            if (!passesFilters(file, globFilter, invocation.fileType)) continue
            val text = TextFiles.readUtf8OrNull(file) ?: continue
            val spelled = file.toString()
            try {
                val deadline = Deadline(fileDeadline)
                if (mode == COUNT) {
                    val count = countMatches(regex, text, deadline)
                    if (count > 0) {
                        filenames += spelled
                        totalMatches += count
                    }
                    continue
                }
                val lines = TextFiles.lines(text)
                val matched = lines.indices.filter { regex.matcher(TimedText(lines[it], deadline)).find() }
                if (matched.isEmpty()) continue
                filenames += spelled
                if (mode == CONTENT) {
                    for (index in matched) {
                        val start = maxOf(0, index - before)
                        val end = minOf(index.toLong() + after + 1, lines.size.toLong()).toInt()
                        for (current in start until end) {
                            val prefix = if (lineNumbers) "$spelled:${current + 1}:" else "$spelled:"
                            contentLines += prefix + lines[current]
                        }
                    }
                }
            } catch (expired: DeadlineExceeded) {
                continue
            } catch (tooDeep: StackOverflowError) {
                // java.util.regex recurses on long inputs where Rust's regex does not; skip the file, as for the deadline.
                continue
            }
        }

        val files = limited(filenames, invocation.headLimit, invocation.offset)
        if (mode == CONTENT) {
            val lines = limited(contentLines, invocation.headLimit, invocation.offset)
            return envelope(mode, files.items, lines.items.joinToString("\n"), lines.items.size, null, lines.appliedLimit, lines.appliedOffset)
        }
        val numMatches = if (mode == COUNT) totalMatches else null
        return envelope(mode, files.items, null, null, numMatches, files.appliedLimit, files.appliedOffset)
    }

    // Rust's regex: `-i` is Unicode-aware, `multiline` makes `.` match `\n`, and only `\n` ends a line.
    private fun compile(invocation: ToolInvocation.GrepSearch): Pattern {
        var flags = Pattern.UNIX_LINES
        if (invocation.ignoreCase == true) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        if (invocation.multiline == true) flags = flags or Pattern.DOTALL
        return try {
            Pattern.compile(invocation.pattern, flags)
        } catch (invalid: PatternSyntaxException) {
            throw WorkspaceToolException(invalid.message ?: invalid.description)
        }
    }

    // Clawcode's `matches_optional_filters`: the glob is matched against the whole path, and `type`
    // must equal the file's extension.
    private fun passesFilters(file: Path, glob: GlobPattern?, fileType: String?): Boolean {
        if (glob != null && !glob.matches(file.toString())) return false
        return fileType == null || extension(file) == fileType
    }

    // Rust's `Path::extension`: none for `..`, for names without a dot, or for a dot only in front.
    private fun extension(file: Path): String? {
        val name = file.fileName?.toString() ?: return null
        if (name == "..") return null
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) null else name.substring(dot + 1)
    }

    private fun countMatches(regex: Pattern, text: String, deadline: Deadline): Long {
        val matcher = regex.matcher(TimedText(text, deadline))
        var count = 0L
        while (matcher.find()) count++
        return count
    }

    // Clawcode's `apply_limit`: skip `offset`, then keep `head_limit` (default 250, 0 for no limit).
    // A limit is reported only when it cut something; an offset only when it is above zero.
    private fun <T> limited(items: List<T>, limit: Int?, offset: Int?): Limited<T> {
        val skip = (offset ?: 0).coerceAtLeast(0)
        val rest = items.drop(skip)
        val appliedOffset = skip.takeIf { it > 0 }
        val explicitLimit = (limit ?: DEFAULT_HEAD_LIMIT).coerceAtLeast(0)
        if (explicitLimit == 0) return Limited(rest, null, appliedOffset)
        return Limited(rest.take(explicitLimit), explicitLimit.takeIf { rest.size > it }, appliedOffset)
    }

    private fun envelope(
        mode: String,
        filenames: List<String>,
        content: String?,
        numLines: Int?,
        numMatches: Long?,
        appliedLimit: Int?,
        appliedOffset: Int?,
    ): String =
        ToolJson.pretty(
            buildJsonObject {
                put("mode", mode)
                put("numFiles", filenames.size)
                put("filenames", ToolJson.strings(filenames))
                put("content", content)
                put("numLines", numLines)
                put("numMatches", numMatches)
                put("appliedLimit", appliedLimit)
                put("appliedOffset", appliedOffset)
            },
        )

    private class Limited<T>(val items: List<T>, val appliedLimit: Int?, val appliedOffset: Int?)

    /** Checks the clock every [CHECK_EVERY] character reads, so matching stays cheap until a pattern runs away. */
    private class Deadline(timeout: Duration) {
        private val end = System.nanoTime() + timeout.toNanos()
        private var reads = 0

        fun check() {
            reads++
            if (reads % CHECK_EVERY == 0 && System.nanoTime() - end > 0) throw DeadlineExceeded()
        }
    }

    /** The text a pattern runs on, reading [Deadline] on every character, since a backtracking match cannot be interrupted otherwise. */
    private class TimedText(private val text: CharSequence, private val deadline: Deadline) : CharSequence {
        override val length: Int
            get() = text.length

        override fun get(index: Int): Char {
            deadline.check()
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = TimedText(text.subSequence(startIndex, endIndex), deadline)

        override fun toString(): String = text.toString()
    }

    private class DeadlineExceeded : RuntimeException(null, null, false, false)

    companion object {
        /** Clawcode's `apply_limit`: results kept when the model sends no `head_limit`. */
        const val DEFAULT_HEAD_LIMIT = 250

        /**
         * Longest a pattern may run on one file before the file is skipped. Butler's own limit,
         * from lane C's design: Clawcode needs none because Rust's `regex` runs in linear time.
         */
        val FILE_DEADLINE: Duration = Duration.ofSeconds(2)

        private const val FILES_WITH_MATCHES = "files_with_matches"
        private const val CONTENT = "content"
        private const val COUNT = "count"
        private const val CHECK_EVERY = 1024
    }
}
