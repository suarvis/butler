package com.suarvis.butler.secondary.persistence.file.repository

import com.suarvis.butler.secondary.persistence.file.entity.UsageLineRecord
import com.suarvis.butler.secondary.persistence.file.support.UserFileJson
import kotlinx.serialization.SerializationException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

/**
 * The usage ledger as append-only JSONL, one file per UTC month under `<user directory>/usage`,
 * so a month's budget check reads at most two small files. Knows nothing about the domain.
 */
class UsageFileRepository(private val userDirectory: Path) {
    private val usageDirectory: Path
        get() = userDirectory.resolve("usage")

    fun append(record: UsageLineRecord) {
        val file = fileFor(YearMonth.from(Instant.ofEpochMilli(record.atMs).atOffset(ZoneOffset.UTC)))
        Files.createDirectories(file.parent)
        // One short line per append; concurrent Butler processes do not interleave within a line.
        Files.writeString(
            file,
            UserFileJson.json.encodeToString(UsageLineRecord.serializer(), record) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    /** Records with `from <= at < to`. */
    fun read(from: Instant, to: Instant): List<UsageLineRecord> {
        if (!from.isBefore(to)) return emptyList()
        val first = YearMonth.from(from.atOffset(ZoneOffset.UTC))
        val last = YearMonth.from(to.minusMillis(1).atOffset(ZoneOffset.UTC))
        return generateSequence(first) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(last) }
            .map(::fileFor)
            .filter { it.isRegularFile() }
            .flatMap { file -> file.readLines().asSequence().mapNotNull(::decode) }
            .filter { it.atMs >= from.toEpochMilli() && it.atMs < to.toEpochMilli() }
            .toList()
    }

    /** A line cut short by a crash is skipped rather than making every later budget check fail. */
    private fun decode(line: String): UsageLineRecord? {
        if (line.isBlank()) return null
        return try {
            UserFileJson.json.decodeFromString(UsageLineRecord.serializer(), line)
        } catch (malformed: SerializationException) {
            null
        } catch (malformed: IllegalArgumentException) {
            null
        }
    }

    private fun fileFor(month: YearMonth): Path = usageDirectory.resolve("${month.format(MONTH)}.jsonl")

    private companion object {
        val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    }
}
