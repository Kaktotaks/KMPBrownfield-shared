package com.example.tasks.shared.data

import com.example.tasks.shared.domain.VoiceNoteFormat
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VoiceNoteFilesTest {
    private val directory = newTempVoiceDirectory()
    private val files = VoiceNoteFiles(directory)

    @AfterTest
    fun tearDown() = deleteDirectory(directory)

    @Test
    fun draftNamesAreUniqueAndTyped() {
        val a = files.newDraftName()
        assertTrue(a.startsWith("draft-") && a.endsWith(".m4a"))
        assertNotEquals(a, files.newDraftName())
    }

    @Test
    fun promoteRenamesDraftToNote() {
        val draft = files.newDraftName()
        writeFile(files.pathOf(draft))
        val note = files.promote(draft)
        assertTrue(note.startsWith("note-") && note.endsWith(".m4a"))
        assertFalse(files.exists(draft))
        assertTrue(files.exists(note))
        assertEquals(listOf(note), files.listAll())
    }

    @Test
    fun deleteIsIdempotentAndPathOfCreatesTheDirectory() {
        val nested = VoiceNoteFiles("$directory/nested/dir")
        val path = nested.pathOf("x.m4a")
        writeFile(path)
        assertTrue(nested.exists("x.m4a"))
        nested.delete("x.m4a")
        nested.delete("x.m4a")
        assertFalse(nested.exists("x.m4a"))
        deleteDirectory("$directory/nested/dir")
        deleteDirectory("$directory/nested")
    }

    @Test
    fun listAllOnMissingDirectoryIsEmpty() {
        assertEquals(emptyList(), VoiceNoteFiles("$directory/missing").listAll())
    }

    @Test
    fun durationFormat() {
        assertEquals("0:00", VoiceNoteFormat.duration(0))
        assertEquals("0:07", VoiceNoteFormat.duration(7_000))
        assertEquals("0:42", VoiceNoteFormat.duration(42_900))
        assertEquals("1:05", VoiceNoteFormat.duration(65_000))
        assertEquals("2:00", VoiceNoteFormat.duration(120_000))
        assertEquals("0:00", VoiceNoteFormat.duration(-5))
    }

    @Test
    fun remainingFormatRoundsUp() {
        assertEquals("0:09", VoiceNoteFormat.remaining(9_000))
        assertEquals("0:09", VoiceNoteFormat.remaining(8_001))
        assertEquals("0:08", VoiceNoteFormat.remaining(8_000))
        assertEquals("0:01", VoiceNoteFormat.remaining(1))
        assertEquals("0:00", VoiceNoteFormat.remaining(0))
        assertEquals("0:00", VoiceNoteFormat.remaining(-5))
        assertEquals("2:00", VoiceNoteFormat.remaining(120_000))
    }
}
