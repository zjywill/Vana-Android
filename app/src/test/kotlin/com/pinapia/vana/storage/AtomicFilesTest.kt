package com.pinapia.vana.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun createsMissingParentDirectories() {
        val file = File(folder.root, "a/b/c.json")
        AtomicFiles.writeText(file, "内容")
        assertEquals("内容", file.readText())
    }

    @Test
    fun replacesAnExistingFileWholesale() {
        val file = File(folder.root, "x.json")
        file.writeText("旧的一大段内容".repeat(100))
        AtomicFiles.writeText(file, "新的")
        assertEquals("新的", file.readText())
    }

    @Test
    fun leavesNoTemporaryFileBehind() {
        val file = File(folder.root, "x.json")
        AtomicFiles.writeText(file, "1")
        AtomicFiles.writeText(file, "2")
        assertFalse(File(folder.root, "x.json.tmp").exists())
    }
}
