package com.suarvis.butler.core.domain.permission

import com.suarvis.butler.core.domain.exception.DomainValidationException
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.ensure

/**
 * Clawcode's permission rule syntax, ported from `PermissionRule::parse` and `parse_rule_matcher`
 * in `runtime/src/permissions.rs`:
 *
 * - `read_file`: every call of the tool.
 * - `bash(*)` or `bash()`: also every call.
 * - `bash(git status)`: calls whose [PermissionSubject] is exactly `git status`.
 * - `bash(git:*)`: calls whose subject starts with `git`. It is a plain prefix, as in Clawcode, so
 *   it also matches `gitk`; `bash(git :*)`, with the space, does not.
 *
 * Inside the parentheses `\(`, `\)` and `\\` stand for `(`, `)` and `\`. A [ToolPattern]'s
 * argument holds the text between the parentheses, trimmed, with those escapes resolved and the
 * `:*` suffix kept, so a pattern converts back to its rule with [rule].
 */
object ToolPatterns {
    /**
     * Reads one rule as the user wrote it. Clawcode turns text it cannot read into a rule for a
     * tool that never exists, so a mistyped deny rule silently denies nothing; Butler refuses it
     * with a message naming the rule, so configuration can stop at start-up instead.
     */
    fun parse(raw: String): ToolPattern {
        val trimmed = raw.trim()
        ensure(trimmed.isNotEmpty()) { "Permission rule must not be blank" }
        val open = firstUnescaped(trimmed, '(')
        val close = lastUnescaped(trimmed, ')')
        if (open != null && close != null && close == trimmed.length - 1 && open < close) {
            val name = trimmed.substring(0, open).trim()
            if (name.isNotEmpty()) {
                return ToolPattern(toolName(name, trimmed), unescape(trimmed.substring(open + 1, close).trim()))
            }
        }
        return ToolPattern(toolName(trimmed, trimmed))
    }

    private fun toolName(name: String, rule: String): ToolName =
        try {
            ToolName(name)
        } catch (invalid: DomainValidationException) {
            throw DomainValidationException("Invalid permission rule '$rule': '$name' is not a tool name")
        }

    private fun firstUnescaped(value: String, needle: Char): Int? {
        var escaped = false
        for ((index, char) in value.withIndex()) {
            if (char == '\\') {
                escaped = !escaped
            } else {
                if (char == needle && !escaped) return index
                escaped = false
            }
        }
        return null
    }

    private fun lastUnescaped(value: String, needle: Char): Int? {
        for (index in value.indices.reversed()) {
            if (value[index] != needle) continue
            var backslashes = 0
            while (index - backslashes > 0 && value[index - backslashes - 1] == '\\') backslashes++
            if (backslashes % 2 == 0) return index
        }
        return null
    }

    /** Clawcode's `unescape_rule_content`, replacement for replacement. */
    private fun unescape(content: String): String = content.replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\")
}

/**
 * Whether a call of [tool] whose subject is [subject] falls under this pattern.
 *
 * Tool names compare without case. Clawcode lower-cases the rule's name and compares it with the
 * call's name as given, so a rule never matched an MCP tool whose name has capitals; ignoring case
 * on both sides keeps a deny rule from silently missing such a tool.
 */
fun ToolPattern.matches(tool: ToolName, subject: String): Boolean {
    if (!this.tool.value.equals(tool.value, ignoreCase = true)) return false
    val expected = argument ?: return true
    return when {
        expected.isEmpty() || expected == ANY -> true
        expected.endsWith(PREFIX) -> subject.startsWith(expected.removeSuffix(PREFIX))
        else -> subject == expected
    }
}

/** The pattern written as a rule, e.g. `bash(git:*)`, so a reason can name the rule that decided, as Clawcode's do. */
val ToolPattern.rule: String
    get() = argument?.let { "${tool.value}(${escape(it)})" } ?: tool.value

private const val ANY = "*"

private const val PREFIX = ":*"

private fun escape(argument: String): String = argument.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
