package com.suarvis.butler.secondary.persistence.file.repository

import com.suarvis.butler.secondary.persistence.file.entity.RateLimitFileRecord
import com.suarvis.butler.secondary.persistence.file.support.AtomicFiles
import com.suarvis.butler.secondary.persistence.file.support.UserFileJson
import kotlinx.serialization.SerializationException
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** `<user directory>/rate-limits.json`, rewritten atomically. A damaged file reads as "no cooldowns": they are only hints. */
class RateLimitFileRepository(userDirectory: Path) {
    private val file: Path = userDirectory.resolve("rate-limits.json")

    fun read(): RateLimitFileRecord {
        if (!file.isRegularFile()) return RateLimitFileRecord()
        return try {
            UserFileJson.json.decodeFromString(RateLimitFileRecord.serializer(), file.readText())
        } catch (damaged: SerializationException) {
            RateLimitFileRecord()
        } catch (damaged: IllegalArgumentException) {
            RateLimitFileRecord()
        }
    }

    fun write(record: RateLimitFileRecord) {
        AtomicFiles.write(file, UserFileJson.json.encodeToString(RateLimitFileRecord.serializer(), record))
    }
}
