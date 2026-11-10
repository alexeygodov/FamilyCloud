package app.familycloud.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteNamesTest {
    @Test
    fun uploadsWhenMissingOrChanged() {
        assertTrue(needsUpload(null, 10, 20))
        assertFalse(needsUpload(UploadedSnapshot(10, 20), 10, 20))
        assertTrue(needsUpload(UploadedSnapshot(10, 20), 11, 20))
        assertTrue(needsUpload(UploadedSnapshot(10, 20), 10, 21))
    }

    @Test
    fun sameSizeSkipsAndDifferentSizeGetsSuffix() {
        val existing = mapOf("clip.mp4" to 100L, "clip (1).mp4" to 50L)
        assertEquals(NameDecision.AlreadyPresent, decideRemoteName("clip.mp4", existing, 100))
        assertEquals(NameDecision.Upload("clip (2).mp4"), decideRemoteName("clip.mp4", existing, 80))
        assertEquals(NameDecision.Upload("new.bin"), decideRemoteName("new.bin", existing, 1))
    }

    @Test
    fun partNameKeepsExtension() {
        assertEquals("video.part.mp4", partName("video.mp4"))
        assertEquals("readme.part", partName("readme"))
    }

    @Test
    fun interruptedUploadIsNotTreatedAsFinished() {
        assertTrue(needsUpload(UploadedSnapshot(10, 20, incomplete = true), 10, 20))
        assertTrue(needsUpload(null, 10, 20))
    }

    @Test
    fun sameSizeMatchesNameIgnoringCase() {
        assertEquals(NameDecision.AlreadyPresent, decideRemoteName("Clip.mp4", mapOf("clip.mp4" to 100L), 100))
    }
}
