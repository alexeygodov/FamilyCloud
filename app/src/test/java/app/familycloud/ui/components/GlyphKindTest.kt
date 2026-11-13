package app.familycloud.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class GlyphKindTest {
    @Test
    fun classifiesFoldersPhotosDocumentsArchivesAndAudio() {
        assertEquals(GlyphKind.Folder, glyphKind("Camera", folder = true))
        assertEquals(GlyphKind.Image, glyphKind("IMG_2041.jpg", folder = false))
        assertEquals(GlyphKind.Video, glyphKind("clip.mp4", folder = false))
        assertEquals(GlyphKind.Document, glyphKind("notes.pdf", folder = false))
        assertEquals(GlyphKind.Archive, glyphKind("backup.zip", folder = false))
        assertEquals(GlyphKind.Audio, glyphKind("song.mp3", folder = false))
        assertEquals(GlyphKind.File, glyphKind("readme", folder = false))
    }
}
