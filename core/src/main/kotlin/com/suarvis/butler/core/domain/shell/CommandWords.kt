package com.suarvis.butler.core.domain.shell

/**
 * A word of a shell command after quote removal. [expands] is true when an unquoted or
 * double-quoted `$` lets the shell substitute into it, so its value is only known when it runs;
 * [expandsHome] when that substitution is `$HOME`.
 */
internal data class ShellWord(
    val text: String,
    val expands: Boolean = false,
    val expandsHome: Boolean = false,
)

/**
 * One simple command between `|`, `||`, `|&`, `&&`, `&`, `;` and newlines. [text] is its source as
 * written, for Clawcode's substring checks; [words] leaves redirections out; [redirectTargets] are
 * the words after `<` and `>` operators; [writesOutput] says whether any of those opens a file for
 * writing (`>`, `>>`, `>|`, `&>`, `>&`, `<>`), other than `/dev/null`, which discards it.
 */
/** Output sent here is thrown away, so redirecting to it neither writes a file nor leaves the workspace. */
internal const val DISCARD = "/dev/null"

internal data class ShellSegment(
    val text: String,
    val words: List<ShellWord>,
    val redirectTargets: List<ShellWord> = emptyList(),
    val writesOutput: Boolean = false,
)

/** A command split into [segments]; [substitutes] is true when `$(`, a backtick, `<(` or `>(` runs another command inside it. */
internal data class ShellCommand(
    val segments: List<ShellSegment>,
    val substitutes: Boolean,
)

/**
 * The words of a shell command, as far as judging its risk needs them. Not a shell parser: it
 * knows quotes, escapes, separators, redirections and substitutions and nothing else, so anything
 * it cannot follow (an unclosed quote, a trailing backslash, a redirection with no target) comes
 * back as null and the classifier treats the command as unknown rather than guess.
 */
internal object CommandWords {
    /** Clawcode's `SHELL_METACHARS` from `is_read_only_command`: any of them lets a command chain, substitute, pipe or redirect. */
    private val SHELL_METACHARACTERS = setOf(';', '|', '&', '$', '`', '>', '<', '(', ')', '{', '}', '\n')

    fun hasShellOperators(command: String): Boolean = command.any { it in SHELL_METACHARACTERS }

    /** [command] split into segments and words, or null when it cannot be followed. */
    fun parse(command: String): ShellCommand? = Scanner(command).scan()

    /**
     * Clawcode's `extract_first_command`: the first word after any `KEY=value` prefixes, or `""`
     * when there is none. Unlike Clawcode it stops at the first separator, so `ls|wc` gives `ls`.
     */
    fun firstCommand(command: String): String {
        val words = parse(command)?.segments?.firstOrNull()?.words ?: return ""
        return programIndex(words)?.let { words[it].text } ?: ""
    }

    /** Index of the first word that is not a `KEY=value` prefix, or null when every word is one. */
    fun programIndex(words: List<ShellWord>): Int? = words.indexOfFirst { !isAssignment(it.text) }.takeIf { it >= 0 }

    /** Clawcode's test for an environment prefix: a non-empty name of ASCII letters, digits and `_` before the `=`. */
    private fun isAssignment(word: String): Boolean {
        val equals = word.indexOf('=')
        return equals > 0 && word.substring(0, equals).all { it == '_' || it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    }
}

/**
 * Walks a command once, left to right. Single quotes are literal; double quotes still expand `$`
 * and backticks, as in sh, which is why a substitution inside double quotes still counts.
 */
private class Scanner(private val source: String) {
    private val segments = mutableListOf<ShellSegment>()
    private var substitutes = false
    private var broken = false
    private var position = 0

    private var segmentStart = 0
    private val words = mutableListOf<ShellWord>()
    private val targets = mutableListOf<ShellWord>()
    private var writesOutput = false
    private var awaitingTarget = false

    /** The redirection waiting for its target opens it for writing; whether that counts depends on the target. */
    private var pendingOutput = false

    private val word = StringBuilder()
    private var inWord = false
    private var expands = false
    private var expandsHome = false

    fun scan(): ShellCommand? {
        while (position < source.length && !broken) step()
        if (!broken) endSegment(source.length)
        return if (broken) null else ShellCommand(segments.toList(), substitutes)
    }

    private fun step() {
        when (val char = source[position]) {
            ' ', '\t', '\r' -> {
                endWord()
                position++
            }
            '\\' -> escape()
            '\'' -> singleQuoted()
            '"' -> doubleQuoted()
            '$' -> dollar()
            '`' -> backtick()
            ';', '\n' -> separator(1)
            '|' -> separator(if (peek(1) == '|' || peek(1) == '&') 2 else 1)
            '&' ->
                when (peek(1)) {
                    '&' -> separator(2)
                    '>' -> {
                        // `&>` and `&>>` send both streams to a file.
                        position++
                        redirect()
                    }
                    else -> separator(1)
                }
            '>', '<' -> redirect()
            else -> {
                append(char)
                position++
            }
        }
    }

    private fun peek(offset: Int): Char? = source.getOrNull(position + offset)

    private fun separator(length: Int) {
        endSegment(position)
        position += length
        segmentStart = position
    }

    private fun endSegment(end: Int) {
        endWord()
        if (awaitingTarget) {
            broken = true
            return
        }
        if (words.isNotEmpty() || targets.isNotEmpty()) {
            segments += ShellSegment(source.substring(segmentStart, end), words.toList(), targets.toList(), writesOutput)
        }
        words.clear()
        targets.clear()
        writesOutput = false
    }

    /** Reads one redirection operator; the next word becomes its target. A digit word right before it is a descriptor, not an argument. */
    private fun redirect() {
        if (inWord && word.isNotEmpty() && word.all { it.isDigit() }) resetWord() else endWord()
        if (awaitingTarget) {
            broken = true
            return
        }
        val operator = source[position++]
        var output = operator == '>'
        when (peek(0)) {
            '>' -> {
                // `>>` appends; `<>` opens the file for reading and writing.
                output = true
                position++
            }
            '|' -> if (operator == '>') position++
            '<' ->
                if (operator == '<') {
                    // Here-documents (`<<`, `<<-`) and here-strings (`<<<`).
                    position++
                    if (peek(0) == '<' || peek(0) == '-') position++
                }
            '(' -> substitutes = true
        }
        if (peek(0) == '&') position++
        pendingOutput = output
        awaitingTarget = true
    }

    private fun endWord() {
        if (!inWord) return
        val finished = ShellWord(word.toString(), expands, expandsHome)
        if (awaitingTarget) {
            targets += finished
            // Discarding output into /dev/null writes nothing a user could lose (a Butler decision).
            if (pendingOutput && finished.text != DISCARD) writesOutput = true
            pendingOutput = false
            awaitingTarget = false
        } else {
            words += finished
        }
        resetWord()
    }

    private fun resetWord() {
        word.setLength(0)
        inWord = false
        expands = false
        expandsHome = false
    }

    private fun append(char: Char) {
        word.append(char)
        inWord = true
    }

    private fun escape() {
        when (val next = peek(1)) {
            null -> broken = true
            '\n' -> position += 2
            else -> {
                append(next)
                position += 2
            }
        }
    }

    private fun singleQuoted() {
        val close = source.indexOf('\'', position + 1)
        if (close < 0) {
            broken = true
            return
        }
        word.append(source, position + 1, close)
        inWord = true
        position = close + 1
    }

    private fun doubleQuoted() {
        inWord = true
        position++
        while (position < source.length) {
            when (val char = source[position]) {
                '"' -> {
                    position++
                    return
                }
                '\\' -> {
                    val next = peek(1)
                    when {
                        next == null -> break
                        next == '\n' -> position += 2
                        next == '"' || next == '\\' || next == '$' || next == '`' -> {
                            word.append(next)
                            position += 2
                        }
                        else -> {
                            word.append(char)
                            position++
                        }
                    }
                }
                '$' -> dollar()
                '`' -> backtick()
                else -> {
                    word.append(char)
                    position++
                }
            }
        }
        broken = true
    }

    private fun dollar() {
        expands = true
        if (peek(1) == '(') substitutes = true
        if (source.startsWith("HOME", position + 1) || source.startsWith("{HOME", position + 1)) expandsHome = true
        append('$')
        position++
    }

    private fun backtick() {
        substitutes = true
        append('`')
        position++
    }
}
