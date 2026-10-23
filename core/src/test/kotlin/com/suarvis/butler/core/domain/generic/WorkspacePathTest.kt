package com.suarvis.butler.core.domain.generic

import com.suarvis.butler.core.domain.exception.DomainValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class WorkspacePathTest {
    private val root = WorkspaceRoot.of(Path.of("/work/butler"))

    @Test
    fun `relative paths stay relative and normalised`() {
        assertEquals(WorkspacePath("src/Main.kt"), WorkspacePath.within(root, "src/Main.kt"))
        assertEquals(WorkspacePath("src/Main.kt"), WorkspacePath.within(root, "./src/../src/Main.kt"))
    }

    @Test
    fun `absolute paths inside the workspace become relative`() {
        assertEquals(WorkspacePath("core/build.gradle.kts"), WorkspacePath.within(root, "/work/butler/core/build.gradle.kts"))
    }

    @Test
    fun `the workspace itself is dot`() {
        assertEquals(WorkspacePath.ROOT_PATH, WorkspacePath.within(root, "."))
        assertEquals(WorkspacePath.ROOT_PATH, WorkspacePath.within(root, "/work/butler"))
        assertEquals(WorkspacePath.ROOT_PATH, WorkspacePath.within(root, "src/.."))
    }

    @ParameterizedTest
    @ValueSource(strings = ["../x", "/etc/passwd", "a/../../x", "/work/butler-other/x", "/work", "", "   "])
    fun `paths that leave the workspace or are unusable are refused`(raw: String) {
        assertNull(WorkspacePath.within(root, raw))
    }

    @Test
    fun `a NUL character is refused rather than passed to the file system`() {
        assertNull(WorkspacePath.within(root, "src/a\u0000b"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["a//b", "./a", "/abs", "a/../b", "a/.", "", "a/"])
    fun `the constructor only takes the normalised relative form`(raw: String) {
        assertThrows<DomainValidationException> { WorkspacePath(raw) }
    }

    @ParameterizedTest
    @ValueSource(strings = [".", "a", "src/main/kotlin/A.kt", ".gitignore", "dir/.hidden"])
    fun `the constructor accepts normalised relative paths`(raw: String) {
        assertDoesNotThrow { WorkspacePath(raw) }
    }
}
