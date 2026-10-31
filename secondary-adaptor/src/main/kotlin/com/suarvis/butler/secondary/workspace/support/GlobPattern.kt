package com.suarvis.butler.secondary.workspace.support

/**
 * A port of the `glob` crate's `Pattern` (0.3.3, the version Clawcode locks), matched with the
 * crate's default `MatchOptions` as Clawcode's searches match it: case-sensitive, `*`, `?` and
 * `[...]` also match `/`, and a leading dot needs no literal dot. `**` must be a whole path
 * component and stands for any number of folders. Clawcode matches absolute path strings, so `*.rs`
 * finds Rust files in every folder below the start, not only in the start itself.
 *
 * The crate has no brace syntax; [expandBraces] is Clawcode's own expansion, which only
 * `glob_search` applies.
 */
internal class GlobPattern private constructor(private val tokens: List<Token>) {
    fun matches(text: String): Boolean = matchesFrom(text.codePoints().toArray(), 0, 0) == Match.MATCH

    // The crate's `matches_from`. Its `follows_separator` flag only matters for the leading-dot and
    // literal-separator options, both off by default, so it is left out here.
    private fun matchesFrom(file: IntArray, fileStart: Int, tokenStart: Int): Match {
        var position = fileStart
        for (index in tokenStart until tokens.size) {
            when (val token = tokens[index]) {
                Token.AnySequence, Token.AnyRecursiveSequence -> {
                    val empty = matchesFrom(file, position, index + 1)
                    if (empty != Match.SUB_PATTERN_DOESNT_MATCH) return empty
                    while (position < file.size) {
                        val c = file[position++]
                        // `**/` swallows whole folders only, so the rest is tried after a separator.
                        if (token == Token.AnyRecursiveSequence && c != SEPARATOR) continue
                        val rest = matchesFrom(file, position, index + 1)
                        if (rest != Match.SUB_PATTERN_DOESNT_MATCH) return rest
                    }
                }
                else -> {
                    if (position >= file.size) return Match.ENTIRE_PATTERN_DOESNT_MATCH
                    if (!token.matchesOne(file[position++])) return Match.SUB_PATTERN_DOESNT_MATCH
                }
            }
        }
        return if (position >= file.size) Match.MATCH else Match.SUB_PATTERN_DOESNT_MATCH
    }

    private enum class Match { MATCH, SUB_PATTERN_DOESNT_MATCH, ENTIRE_PATTERN_DOESNT_MATCH }

    private sealed interface Token {
        fun matchesOne(c: Int): Boolean = false

        data object AnySequence : Token

        data object AnyRecursiveSequence : Token

        data object AnyChar : Token {
            override fun matchesOne(c: Int): Boolean = true
        }

        data class Literal(val char: Int) : Token {
            override fun matchesOne(c: Int): Boolean = c == char
        }

        data class AnyWithin(val specifiers: List<CharSpecifier>) : Token {
            override fun matchesOne(c: Int): Boolean = specifiers.any { it.contains(c) }
        }

        data class AnyExcept(val specifiers: List<CharSpecifier>) : Token {
            override fun matchesOne(c: Int): Boolean = specifiers.none { it.contains(c) }
        }
    }

    private data class CharSpecifier(val first: Int, val last: Int) {
        fun contains(c: Int): Boolean = c in first..last
    }

    companion object {
        private const val SEPARATOR = '/'.code
        private const val ERROR_WILDCARDS = "wildcards are either regular `*` or recursive `**`"
        private const val ERROR_RECURSIVE_WILDCARDS = "recursive wildcards must form a single path component"
        private const val ERROR_INVALID_RANGE = "invalid range pattern"

        /**
         * The crate's `Pattern::new`.
         *
         * @throws WorkspaceToolException with the crate's `PatternError` text when [pattern] is not a valid glob
         */
        fun compile(pattern: String): GlobPattern {
            val chars = pattern.codePoints().toArray()
            val tokens = ArrayList<Token>()
            var i = 0
            while (i < chars.size) {
                when (chars[i]) {
                    '?'.code -> {
                        tokens += Token.AnyChar
                        i++
                    }
                    '*'.code -> {
                        val old = i
                        while (i < chars.size && chars[i] == '*'.code) i++
                        val count = i - old
                        when {
                            count > 2 -> throw syntaxError(old + 2, ERROR_WILDCARDS)
                            count == 2 -> {
                                if (i != 2 && chars[old - 1] != SEPARATOR) throw syntaxError(old - 1, ERROR_RECURSIVE_WILDCARDS)
                                if (i < chars.size && chars[i] == SEPARATOR) {
                                    i++
                                } else if (i != chars.size) {
                                    throw syntaxError(i, ERROR_RECURSIVE_WILDCARDS)
                                }
                                // The crate collapses `**/**/` into one, except as the very first token.
                                if (!(tokens.size > 1 && tokens.last() == Token.AnyRecursiveSequence)) tokens += Token.AnyRecursiveSequence
                            }
                            else -> tokens += Token.AnySequence
                        }
                    }
                    '['.code -> i = charClass(chars, i, tokens)
                    else -> {
                        tokens += Token.Literal(chars[i])
                        i++
                    }
                }
            }
            return GlobPattern(tokens)
        }

        /**
         * Clawcode's `expand_braces`: one `{a,b}` group at a time, expanded recursively, so
         * `src/{a,b}.{rs,toml}` gives four patterns. An unmatched `{` stays literal.
         */
        fun expandBraces(pattern: String): List<String> {
            val open = pattern.indexOf('{')
            if (open < 0) return listOf(pattern)
            val close = pattern.indexOf('}', open)
            if (close < 0) return listOf(pattern)
            val prefix = pattern.substring(0, open)
            val suffix = pattern.substring(close + 1)
            return pattern.substring(open + 1, close).split(',').flatMap { expandBraces(prefix + it + suffix) }
        }

        // `[...]` or `[!...]` starting at [open]; returns the index after the closing `]`. The first
        // character inside is taken literally even when it is `]`, as in the crate.
        private fun charClass(chars: IntArray, open: Int, tokens: MutableList<Token>): Int {
            if (open + 4 <= chars.size && chars[open + 1] == '!'.code) {
                val close = indexOf(chars, ']'.code, open + 3)
                if (close >= 0) {
                    tokens += Token.AnyExcept(specifiers(chars, open + 2, close))
                    return close + 1
                }
            } else if (open + 3 <= chars.size && chars[open + 1] != '!'.code) {
                val close = indexOf(chars, ']'.code, open + 2)
                if (close >= 0) {
                    tokens += Token.AnyWithin(specifiers(chars, open + 1, close))
                    return close + 1
                }
            }
            throw syntaxError(open, ERROR_INVALID_RANGE)
        }

        // The crate's `parse_char_specifiers`: `a-z` is a range, anything else a single character.
        private fun specifiers(chars: IntArray, from: Int, until: Int): List<CharSpecifier> {
            val specifiers = ArrayList<CharSpecifier>()
            var k = from
            while (k < until) {
                if (k + 3 <= until && chars[k + 1] == '-'.code) {
                    specifiers += CharSpecifier(chars[k], chars[k + 2])
                    k += 3
                } else {
                    specifiers += CharSpecifier(chars[k], chars[k])
                    k++
                }
            }
            return specifiers
        }

        private fun indexOf(chars: IntArray, target: Int, from: Int): Int = (from until chars.size).firstOrNull { chars[it] == target } ?: -1

        private fun syntaxError(position: Int, message: String) = WorkspaceToolException("Pattern syntax error near position $position: $message")
    }
}
