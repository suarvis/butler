package com.suarvis.butler.secondary.workspace.support

import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.tool.ToolInvocation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * `glob_search`, ported from Clawcode's `glob_search_impl`: a relative pattern is joined to `path`
 * (or the workspace) as text, braces are expanded, and each expanded pattern is walked from its
 * longest folder prefix without glob characters and matched against whole path strings with
 * [GlobPattern]. Matches are sorted newest-modified first and capped at [MAX_RESULTS]. The result
 * is Clawcode's `GlobSearchOutput`.
 */
internal class GlobSearchTool(private val guard: PathGuard) {
    fun run(invocation: ToolInvocation.GlobSearch): String {
        val started = System.nanoTime()
        val base = guard.existing(invocation.under ?: WorkspacePath.ROOT_PATH)
        val pattern = invocation.pattern
        val searchPattern = if (pattern.startsWith(SEPARATOR)) pattern else joined(base, pattern)
        val walker = SearchWalker(guard)
        val seen = HashSet<Path>()
        val matches = ArrayList<Path>()
        for (expanded in GlobPattern.expandBraces(searchPattern)) {
            val compiled = GlobPattern.compile(expanded)
            for (candidate in walker.files(walkRoot(expanded, base))) {
                if (compiled.matches(candidate.toString()) && seen.add(candidate)) matches.add(candidate)
            }
        }
        // Rust sorts `Option<Reverse<SystemTime>>`: newest first, files without a time before all others.
        val sorted = matches.sortedWith(compareBy<Path, FileTime?>(nullsFirst(reverseOrder())) { modified(it) })
        val filenames = sorted.take(MAX_RESULTS).map(Path::toString)
        return ToolJson.pretty(
            buildJsonObject {
                put("durationMs", (System.nanoTime() - started) / NANOS_PER_MILLI)
                put("numFiles", filenames.size)
                put("filenames", ToolJson.strings(filenames))
                put("truncated", sorted.size > MAX_RESULTS)
            },
        )
    }

    // Rust's `PathBuf::join` on text: no normalisation, so the pattern is matched as the model spelled it.
    private fun joined(base: Path, pattern: String): String {
        val prefix = base.toString()
        return if (prefix.endsWith(SEPARATOR)) prefix + pattern else "$prefix$SEPARATOR$pattern"
    }

    // Clawcode's `derive_glob_walk_root`: the path components before the first one holding `*`, `?`
    // or `[`. Like Rust's `Path::components`, empty and `.` components are dropped.
    private fun walkRoot(pattern: String, fallback: Path): Path {
        var root: Path? = if (pattern.startsWith(SEPARATOR)) Path.of(SEPARATOR) else null
        for (component in pattern.split(SEPARATOR)) {
            if (component.isEmpty() || component == ".") continue
            if (component.any { it == '*' || it == '?' || it == '[' }) break
            root = root?.resolve(component) ?: Path.of(component)
        }
        return root ?: fallback
    }

    private fun modified(file: Path): FileTime? =
        try {
            Files.getLastModifiedTime(file)
        } catch (unreadable: IOException) {
            null
        }

    companion object {
        /** Clawcode's cap in `glob_search_impl`: the 100 newest matches, with `truncated` set when there were more. */
        const val MAX_RESULTS = 100

        private const val SEPARATOR = "/"
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
